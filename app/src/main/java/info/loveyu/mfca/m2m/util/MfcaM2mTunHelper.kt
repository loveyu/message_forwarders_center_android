package info.loveyu.mfca.m2m.util

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.app.PendingIntent
import android.content.Intent
import info.loveyu.mfca.ui.main.MainActivity
import info.loveyu.mfca.R
import info.loveyu.mfca.config.models.M2mAccessControlMode
import info.loveyu.mfca.m2m.models.M2mCandidateState
import info.loveyu.mfca.m2m.models.PreparedM2mArtifacts
import info.loveyu.mfca.m2m.service.MfcaM2mService
import info.loveyu.mfca.util.LogManager
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.ByteOrder

internal object MfcaM2mTunHelper {
    fun establish(
        service: VpnService,
        candidate: M2mCandidateState,
        artifacts: PreparedM2mArtifacts
    ): ParcelFileDescriptor? {
        val accessControl = candidate.effectiveAccessControlMode
        val packageCount = candidate.effectivePackages.size
        LogManager.logInfo("VPN", "Establishing TUN: candidate=${candidate.config.name}, mode=$accessControl, packages=$packageCount, ipv6=${artifacts.ipv6}")
        @Suppress("DEPRECATION")
        val builder = service.Builder()
            .setBlocking(false)
            .setMtu(MfcaM2mService.TUN_MTU)
            .setSession(service.getString(R.string.vpn_notification_title))
            .addAddress(MfcaM2mService.TUN_GATEWAY, MfcaM2mService.TUN_SUBNET_PREFIX)
            .addRoute(MfcaM2mService.NET_ANY, 0)
            .addDnsServer(MfcaM2mService.TUN_DNS_PRIMARY)
            .addDnsServer(MfcaM2mService.TUN_DNS_SECONDARY)

        if (artifacts.ipv6) {
            LogManager.logDebug("VPN", "TUN: adding IPv6 route ::/0")
            builder.addRoute("::", 0)
        }

        when (accessControl) {
            M2mAccessControlMode.acceptAll -> {
                LogManager.logDebug("VPN", "TUN: acceptAll mode, disallowing self (${service.packageName})")
                builder.addDisallowedApplication(service.packageName)
            }
            M2mAccessControlMode.exclude -> {
                val pkgs = (candidate.effectivePackages + service.packageName).distinct()
                LogManager.logDebug("VPN", "TUN: exclude mode, disallowing ${pkgs.size} apps: ${pkgs.take(5)}${if (pkgs.size > 5) "..." else ""}")
                pkgs.forEach { pkg -> builder.addDisallowedApplication(pkg) }
            }
            M2mAccessControlMode.include -> {
                LogManager.logDebug("VPN", "TUN: include mode, allowing ${candidate.effectivePackages.size} apps: ${candidate.effectivePackages.take(5)}${if (candidate.effectivePackages.size > 5) "..." else ""}")
                candidate.effectivePackages.distinct().forEach { pkg ->
                    builder.addAllowedApplication(pkg)
                }
            }
        }

        val configureIntent = PendingIntent.getActivity(
            service,
            1202,
            Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        builder.setConfigureIntent(configureIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val tun = builder.establish()
        if (tun == null) {
            LogManager.logError("VPN", "TUN establishment returned null — VPN may not be permitted or supported")
        } else {
            LogManager.logInfo("VPN", "TUN established")
        }
        return tun
    }

    fun close(tunInterface: ParcelFileDescriptor?) {
        if (tunInterface != null) {
            LogManager.logInfo("VPN", "Closing TUN interface")
        }
        runCatching { tunInterface?.close() }
    }

    fun findInterfaceName(ifacesBefore: Set<String> = emptySet()): String? {
        if (ifacesBefore.isNotEmpty()) {
            try {
                val ifacesAfter = snapshotInterfaceNames()
                val newIfaces = ifacesAfter - ifacesBefore
                if (newIfaces.isNotEmpty()) {
                    val name = newIfaces.first()
                    LogManager.logInfo("VPN", "TUN interface found via /proc/net/dev diff: $name")
                    return name
                }
            } catch (e: Exception) {
                LogManager.logDebug("VPN", "findTunInterfaceName diff failed: ${e.message}")
            }
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && addr.hostAddress == MfcaM2mService.TUN_GATEWAY) {
                        LogManager.logInfo("VPN", "TUN interface found via NetworkInterface API: ${iface.name}")
                        return iface.name
                    }
                }
            }
        } catch (e: Exception) {
            LogManager.logDebug("VPN", "findTunInterfaceName NetworkInterface failed: ${e.message}")
        }

        try {
            val targetNet = "172.19.0.0"
            val targetPrefix = 30
            var found: String? = null
            File("/proc/net/route").useLines { lines ->
                lines.drop(1).forEach { line ->
                    if (found != null) return@forEach
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 8) {
                        val iface = parts[0]
                        val destIp = routeHexToDotted(parts[1])
                        val maskIp = routeHexToDotted(parts[7])
                        val prefix = maskToPrefix(maskIp)
                        if (destIp == targetNet && prefix == targetPrefix) {
                            found = iface
                        }
                    }
                }
            }
            if (found != null) {
                LogManager.logInfo("VPN", "TUN interface found via /proc/net/route: $found")
                return found
            }
        } catch (e: Exception) {
            LogManager.logDebug("VPN", "findTunInterfaceName route failed: ${e.message}")
        }

        LogManager.logError("VPN", "Failed to find TUN interface using all methods")
        return null
    }

    fun snapshotInterfaceNames(): Set<String> {
        return try {
            File("/proc/net/dev").readLines().mapNotNull { line ->
                val trimmed = line.trimStart()
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx > 0) trimmed.substring(0, colonIdx) else null
            }.toSet()
        } catch (e: Exception) {
            LogManager.logDebug("VPN", "snapshotInterfaceNames failed: ${e.message}")
            emptySet()
        }
    }

    fun routeHexToDotted(hex: String): String {
        val padded = hex.padStart(8, '0')
        val bytes = (0..3).map { padded.substring(it * 2, it * 2 + 2).toInt(16) }
        if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            return bytes.reversed().joinToString(".")
        }
        return bytes.joinToString(".")
    }

    fun maskToPrefix(mask: String): Int {
        return mask.split(".").sumOf { Integer.bitCount(it.toInt()) }
    }
}
