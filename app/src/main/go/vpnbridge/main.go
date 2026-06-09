package main

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"info.loveyu.mfca/vpnbridge/internal/nat"
	"golang.org/x/sys/unix"
)

const (
	socksVersion      = 0x05
	socksAuthNone     = 0x00
	socksCommandTCP   = 0x01
	socksCommandUDP   = 0x03
	socksAddressIPv4  = 0x01
	socksAddressIPv6  = 0x04
	socksReplySuccess = 0x00
)

type bridge struct {
	tcp        *nat.TCP
	udp        *nat.UDP
	socksAddr  string
	dnsAddr    string
	udpRelay   bool
	udpLock    sync.Mutex
	udpSession map[string]*udpAssociation
}

type udpAssociation struct {
	localAddr *net.UDPAddr
	control   net.Conn
	udpConn   *net.UDPConn
	bridge    *bridge
	closed    chan struct{}
	closeOnce sync.Once
}

type dnsConnEntry struct {
	conn   *net.UDPConn
	source *net.UDPAddr
	target *net.UDPAddr
}

func main() {
	var controlSocket string
	var socksAddr string
	var gatewayCIDR string
	var portalAddr string
	var dnsAddr string
	var udpRelay bool

	flag.StringVar(&controlSocket, "control-socket", "", "abstract unix domain socket name")
	flag.StringVar(&socksAddr, "socks", "127.0.0.1:17890", "local socks5 proxy address")
	flag.StringVar(&gatewayCIDR, "gateway", "172.19.0.1/30", "tun gateway cidr")
	flag.StringVar(&portalAddr, "portal", "172.19.0.2", "tun portal address")
	flag.StringVar(&dnsAddr, "dns", "", "local DNS server for port 53 hijacking (e.g. 127.0.0.1:1053)")
	flag.BoolVar(&udpRelay, "udp-relay", true, "relay non-DNS UDP through SOCKS5")
	flag.Parse()

	if controlSocket == "" {
		log.Fatal("missing --control-socket")
	}

	fd, err := receiveTunFd(controlSocket)
	if err != nil {
		log.Fatalf("receive tun fd failed: %v", err)
	}

	network, err := netip.ParsePrefix(gatewayCIDR)
	if err != nil {
		log.Fatalf("invalid gateway: %v", err)
	}
	portal, err := netip.ParseAddr(portalAddr)
	if err != nil {
		log.Fatalf("invalid portal: %v", err)
	}

	device := os.NewFile(uintptr(fd), "/dev/tun")
	tcp, udp, err := nat.Start(device, network, portal)
	if err != nil {
		_ = device.Close()
		log.Fatalf("start tun2socket failed: %v", err)
	}
	defer tcp.Close()
	defer udp.Close()
	defer device.Close()

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	b := &bridge{
		tcp:        tcp,
		udp:        udp,
		socksAddr:  socksAddr,
		dnsAddr:    dnsAddr,
		udpRelay:   udpRelay,
		udpSession: make(map[string]*udpAssociation),
	}

	go b.runTCP(ctx)
	go b.runUDP(ctx)

	<-ctx.Done()
}

func (b *bridge) runTCP(ctx context.Context) {
	for {
		conn, err := b.tcp.Accept()
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
				return
			}
			log.Printf("tcp accept failed: %v", err)
			continue
		}

		go b.handleTCP(conn)
	}
}

func (b *bridge) handleTCP(conn net.Conn) {
	defer conn.Close()

	target, ok := conn.RemoteAddr().(*net.TCPAddr)
	if !ok {
		log.Printf("invalid tcp target addr: %T", conn.RemoteAddr())
		return
	}

	upstream, err := dialSocksTCP(b.socksAddr, target)
	if err != nil {
		log.Printf("tcp socks connect failed: %v", err)
		return
	}
	defer upstream.Close()

	copyDone := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(upstream, conn)
		closeWrite(upstream)
		copyDone <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(conn, upstream)
		closeWrite(conn)
		copyDone <- struct{}{}
	}()
	<-copyDone
}

func (b *bridge) runUDP(ctx context.Context) {
	dnsConns := make(map[string]*dnsConnEntry)
	var dnsConnLock sync.Mutex

	buf := make([]byte, 65535)
	for {
		n, source, destination, err := b.udp.ReadFrom(buf)
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
				return
			}
			log.Printf("udp read failed: %v", err)
			continue
		}

		sourceAddr, ok := source.(*net.UDPAddr)
		if !ok {
			log.Printf("invalid udp source addr: %T", source)
			continue
		}
		targetAddr, ok := destination.(*net.UDPAddr)
		if !ok {
			log.Printf("invalid udp destination addr: %T", destination)
			continue
		}

		payload := append([]byte(nil), buf[:n]...)

		// DNS hijacking: intercept port 53 and forward to local DNS server
		if targetAddr.Port == 53 && b.dnsAddr != "" {
			dnsConnLock.Lock()
			entry, exists := dnsConns[sourceAddr.String()]
			if !exists {
				resolvedAddr, resolveErr := net.ResolveUDPAddr("udp", b.dnsAddr)
				if resolveErr != nil {
					dnsConnLock.Unlock()
					log.Printf("dns resolve failed: %v", resolveErr)
					continue
				}
				conn, dialErr := net.DialUDP("udp", nil, resolvedAddr)
				if dialErr != nil {
					dnsConnLock.Unlock()
					log.Printf("dns dial failed: %v", dialErr)
					continue
				}
				entry = &dnsConnEntry{
					conn:   conn,
					source: sourceAddr,
					target: targetAddr,
				}
				dnsConns[sourceAddr.String()] = entry
				go dnsResponseReader(entry, &dnsConns, &dnsConnLock, b.udp)
			}
			conn := entry.conn
			dnsConnLock.Unlock()

			if _, writeErr := conn.Write(payload); writeErr != nil {
				log.Printf("dns write failed: %v", writeErr)
			}
			continue
		}

		// Drop non-DNS UDP when relay is disabled
		if !b.udpRelay {
			continue
		}

		session, err := b.getOrCreateUDPAssociation(sourceAddr)
		if err != nil {
			log.Printf("udp association failed: %v", err)
			continue
		}
		if err := session.writeTo(payload, targetAddr); err != nil {
			log.Printf("udp write failed: %v", err)
			b.removeUDPAssociation(sourceAddr.String())
		}
	}
}

func dnsResponseReader(entry *dnsConnEntry, dnsConns *map[string]*dnsConnEntry, lock *sync.Mutex, udpWriter *nat.UDP) {
	buf := make([]byte, 65535)
	entry.conn.SetReadDeadline(time.Now().Add(30 * time.Second))
	n, err := entry.conn.Read(buf)

	lock.Lock()
	delete(*dnsConns, entry.source.String())
	lock.Unlock()
	entry.conn.Close()

	if err != nil {
		return
	}
	if _, writeErr := udpWriter.WriteTo(buf[:n], entry.target, entry.source); writeErr != nil {
		log.Printf("dns response write failed: %v", writeErr)
	}
}

func (b *bridge) getOrCreateUDPAssociation(localAddr *net.UDPAddr) (*udpAssociation, error) {
	key := localAddr.String()

	b.udpLock.Lock()
	defer b.udpLock.Unlock()

	if existing, ok := b.udpSession[key]; ok {
		return existing, nil
	}

	association, err := newUDPAssociation(b.socksAddr, localAddr, b)
	if err != nil {
		return nil, err
	}
	b.udpSession[key] = association
	go association.readLoop()
	return association, nil
}

func (b *bridge) removeUDPAssociation(key string) {
	b.udpLock.Lock()
	defer b.udpLock.Unlock()

	if association, ok := b.udpSession[key]; ok {
		delete(b.udpSession, key)
		association.close()
	}
}

func newUDPAssociation(socksAddr string, localAddr *net.UDPAddr, bridge *bridge) (*udpAssociation, error) {
	control, udpConn, err := dialSocksUDP(socksAddr)
	if err != nil {
		return nil, err
	}
	return &udpAssociation{
		localAddr: localAddr,
		control:   control,
		udpConn:   udpConn,
		bridge:    bridge,
		closed:    make(chan struct{}),
	}, nil
}

func (u *udpAssociation) writeTo(payload []byte, target *net.UDPAddr) error {
	packet, err := encodeSocksUDPDatagram(target, payload)
	if err != nil {
		return err
	}
	_, err = u.udpConn.Write(packet)
	return err
}

func (u *udpAssociation) readLoop() {
	buf := make([]byte, 65535)
	for {
		n, err := u.udpConn.Read(buf)
		if err != nil {
			u.bridge.removeUDPAssociation(u.localAddr.String())
			return
		}

		payload, remoteAddr, err := decodeSocksUDPDatagram(buf[:n])
		if err != nil {
			log.Printf("udp decode failed: %v", err)
			continue
		}

		if _, err := u.bridge.udp.WriteTo(payload, remoteAddr, u.localAddr); err != nil {
			log.Printf("udp write back failed: %v", err)
			u.bridge.removeUDPAssociation(u.localAddr.String())
			return
		}
	}
}

func (u *udpAssociation) close() {
	u.closeOnce.Do(func() {
		close(u.closed)
		_ = u.udpConn.Close()
		_ = u.control.Close()
	})
}

func dialSocksTCP(socksAddr string, target *net.TCPAddr) (net.Conn, error) {
	conn, err := net.DialTimeout("tcp", socksAddr, 5*time.Second)
	if err != nil {
		return nil, err
	}

	if err := socksHandshake(conn); err != nil {
		_ = conn.Close()
		return nil, err
	}

	if err := sendSocksRequest(conn, socksCommandTCP, target.IP, target.Port); err != nil {
		_ = conn.Close()
		return nil, err
	}

	if _, err := readSocksReply(conn); err != nil {
		_ = conn.Close()
		return nil, err
	}

	return conn, nil
}

func dialSocksUDP(socksAddr string) (net.Conn, *net.UDPConn, error) {
	control, err := net.DialTimeout("tcp", socksAddr, 5*time.Second)
	if err != nil {
		return nil, nil, err
	}

	if err := socksHandshake(control); err != nil {
		_ = control.Close()
		return nil, nil, err
	}

	if err := sendSocksRequest(control, socksCommandUDP, net.IPv4zero, 0); err != nil {
		_ = control.Close()
		return nil, nil, err
	}

	bindAddr, err := readSocksReply(control)
	if err != nil {
		_ = control.Close()
		return nil, nil, err
	}

	if bindAddr.IP == nil || bindAddr.IP.IsUnspecified() {
		if remoteAddr, ok := control.RemoteAddr().(*net.TCPAddr); ok {
			bindAddr.IP = remoteAddr.IP
		}
	}

	udpConn, err := net.DialUDP("udp", nil, bindAddr)
	if err != nil {
		_ = control.Close()
		return nil, nil, err
	}

	return control, udpConn, nil
}

func socksHandshake(conn net.Conn) error {
	if _, err := conn.Write([]byte{socksVersion, 0x01, socksAuthNone}); err != nil {
		return err
	}

	reply := make([]byte, 2)
	if _, err := io.ReadFull(conn, reply); err != nil {
		return err
	}
	if reply[0] != socksVersion || reply[1] != socksAuthNone {
		return fmt.Errorf("unsupported socks auth response: %v", reply)
	}
	return nil
}

func sendSocksRequest(conn net.Conn, command byte, ip net.IP, port int) error {
	addressField, atyp, err := encodeIP(ip)
	if err != nil {
		return err
	}

	request := make([]byte, 0, 6+len(addressField))
	request = append(request, socksVersion, command, 0x00, atyp)
	request = append(request, addressField...)
	request = binary.BigEndian.AppendUint16(request, uint16(port))

	_, err = conn.Write(request)
	return err
}

func readSocksReply(conn net.Conn) (*net.UDPAddr, error) {
	header := make([]byte, 4)
	if _, err := io.ReadFull(conn, header); err != nil {
		return nil, err
	}
	if header[0] != socksVersion {
		return nil, fmt.Errorf("invalid socks version: %d", header[0])
	}
	if header[1] != socksReplySuccess {
		return nil, fmt.Errorf("socks reply error: %d", header[1])
	}

	ip, port, err := readAddress(conn, header[3])
	if err != nil {
		return nil, err
	}

	return &net.UDPAddr{IP: ip, Port: port}, nil
}

func encodeSocksUDPDatagram(target *net.UDPAddr, payload []byte) ([]byte, error) {
	addressField, atyp, err := encodeIP(target.IP)
	if err != nil {
		return nil, err
	}

	packet := make([]byte, 0, 6+len(addressField)+len(payload))
	packet = append(packet, 0x00, 0x00, 0x00, atyp)
	packet = append(packet, addressField...)
	packet = binary.BigEndian.AppendUint16(packet, uint16(target.Port))
	packet = append(packet, payload...)
	return packet, nil
}

func decodeSocksUDPDatagram(packet []byte) ([]byte, *net.UDPAddr, error) {
	if len(packet) < 4 {
		return nil, nil, errors.New("short udp packet")
	}
	if packet[2] != 0x00 {
		return nil, nil, errors.New("fragmented udp packet is unsupported")
	}

	reader := bytes.NewReader(packet[4:])
	ip, port, err := readAddress(reader, packet[3])
	if err != nil {
		return nil, nil, err
	}

	addressLength := 0
	switch packet[3] {
	case socksAddressIPv4:
		addressLength = 4
	case socksAddressIPv6:
		addressLength = 16
	default:
		return nil, nil, fmt.Errorf("unsupported udp atyp: %d", packet[3])
	}

	offset := 4 + addressLength + 2
	if len(packet) < offset {
		return nil, nil, errors.New("invalid udp packet length")
	}
	payload := append([]byte(nil), packet[offset:]...)
	return payload, &net.UDPAddr{IP: ip, Port: port}, nil
}

func readAddress(reader io.Reader, atyp byte) (net.IP, int, error) {
	switch atyp {
	case socksAddressIPv4:
		buf := make([]byte, 6)
		if _, err := io.ReadFull(reader, buf); err != nil {
			return nil, 0, err
		}
		return net.IPv4(buf[0], buf[1], buf[2], buf[3]), int(binary.BigEndian.Uint16(buf[4:])), nil
	case socksAddressIPv6:
		buf := make([]byte, 18)
		if _, err := io.ReadFull(reader, buf); err != nil {
			return nil, 0, err
		}
		return net.IP(buf[:16]), int(binary.BigEndian.Uint16(buf[16:])), nil
	default:
		return nil, 0, fmt.Errorf("unsupported atyp: %d", atyp)
	}
}

func encodeIP(ip net.IP) ([]byte, byte, error) {
	if ip4 := ip.To4(); ip4 != nil {
		return ip4, socksAddressIPv4, nil
	}
	if ip16 := ip.To16(); ip16 != nil {
		return ip16, socksAddressIPv6, nil
	}
	return nil, 0, fmt.Errorf("unsupported ip: %v", ip)
}

func receiveTunFd(controlSocket string) (int, error) {
	fd, err := unix.Socket(unix.AF_UNIX, unix.SOCK_STREAM, 0)
	if err != nil {
		return -1, err
	}
	defer func() {
		if err != nil {
			_ = unix.Close(fd)
		}
	}()

	if err = unix.Connect(fd, &unix.SockaddrUnix{Name: "\x00" + controlSocket}); err != nil {
		return -1, err
	}

	buf := make([]byte, 1)
	oob := make([]byte, unix.CmsgSpace(4))
	_, oobn, _, _, err := unix.Recvmsg(fd, buf, oob, 0)
	if err != nil {
		return -1, err
	}

	messages, err := unix.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		return -1, err
	}
	for _, message := range messages {
		fds, parseErr := unix.ParseUnixRights(&message)
		if parseErr == nil && len(fds) > 0 {
			_ = unix.Close(fd)
			return fds[0], nil
		}
	}

	return -1, errors.New("no fd received")
}

func closeWrite(conn net.Conn) {
	type closeWriter interface {
		CloseWrite() error
	}
	if cw, ok := conn.(closeWriter); ok {
		_ = cw.CloseWrite()
	}
}
