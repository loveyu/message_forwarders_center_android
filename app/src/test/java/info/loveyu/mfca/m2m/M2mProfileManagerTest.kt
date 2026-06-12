package info.loveyu.mfca.m2m

import info.loveyu.mfca.m2m.config.M2mProfileManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M2mProfileManagerTest {
    @Test
    fun buildRuntimeProfileContent_injectsLocalMixedPortAndDisablesTun() {
        val source =
            """
            port: 7890
            tun:
              enable: true
              stack: mixed
            """.trimIndent()

        val content = M2mProfileManager.buildRuntimeProfileContent(source, 17890, 9090, "testsecret")

        assertTrue(content.contains("mixed-port: 17890"))
        assertTrue(content.contains("allow-lan: false"))
        assertTrue(content.contains("bind-address: 127.0.0.1"))
        assertTrue(content.contains("enable: false"))
        assertTrue(content.contains("external-controller: 127.0.0.1:9090"))
        assertTrue(content.contains("secret: testsecret"))
    }

    @Test
    fun buildRuntimeProfileContent_injectsDnsConfigWhenMissing() {
        val source =
            """
            port: 7890
            tun:
              enable: true
            """.trimIndent()

        val content = M2mProfileManager.buildRuntimeProfileContent(source, 17890, 9091, "secret1")

        assertTrue("dns.enable should be true", content.contains("enable: true"))
        assertTrue("dns.listen should be injected", content.contains("127.0.0.1:1053"))
        assertTrue("dns.enhanced-mode should be fake-ip", content.contains("enhanced-mode: fake-ip"))
    }

    @Test
    fun buildRuntimeProfileContent_overridesDnsListenAndPreservesNameserver() {
        val source =
            """
            port: 7890
            dns:
              listen: 0.0.0.0:53
              nameserver:
                - 8.8.8.8
            tun:
              enable: true
            """.trimIndent()

        val content = M2mProfileManager.buildRuntimeProfileContent(source, 17890, 9092, "secret2")

        assertTrue("dns.listen should be overridden", content.contains("127.0.0.1:1053"))
        assertTrue("dns.enhanced-mode should be fake-ip", content.contains("enhanced-mode: fake-ip"))
        assertTrue("nameserver should be preserved", content.contains("8.8.8.8"))
        assertEquals("old listen should not appear", -1, content.indexOf("0.0.0.0:53"))
    }

    @Test
    fun buildRuntimeProfileContent_usesConfigSecretWhenPresent() {
        val source =
            """
            port: 7890
            secret: myconfigsecret
            tun:
              enable: true
            """.trimIndent()

        val content = M2mProfileManager.buildRuntimeProfileContent(source, 17890, 9093, "autosecret")

        assertTrue("should keep config secret", content.contains("secret: myconfigsecret"))
        assertEquals("auto secret should not appear", -1, content.indexOf("autosecret"))
    }

    @Test
    fun buildRuntimeProfileContent_removesRedundantPorts() {
        val source =
            """
            port: 7890
            socks-port: 7891
            redir-port: 7892
            tproxy-port: 7893
            tun:
              enable: true
            """.trimIndent()

        val content = M2mProfileManager.buildRuntimeProfileContent(source, 17890, 9094, "s")

        assertEquals("port should be removed", -1, content.indexOf("port: 7890"))
        assertEquals("socks-port should be removed", -1, content.indexOf("socks-port: 7891"))
        assertEquals("redir-port should be removed", -1, content.indexOf("redir-port: 7892"))
        assertEquals("tproxy-port should be removed", -1, content.indexOf("tproxy-port: 7893"))
        assertTrue("mixed-port should be present", content.contains("mixed-port: 17890"))
    }
}
