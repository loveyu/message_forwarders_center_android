package info.loveyu.mfca.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnProfileManagerTest {
    @Test
    fun buildRuntimeProfileContent_injectsLocalMixedPortAndDisablesTun() {
        val source =
            """
            port: 7890
            tun:
              enable: true
              stack: mixed
            """.trimIndent()

        val content = VpnProfileManager.buildRuntimeProfileContent(source, 17890)

        assertTrue(content.contains("mixed-port: 17890"))
        assertTrue(content.contains("allow-lan: false"))
        assertTrue(content.contains("bind-address: 127.0.0.1"))
        assertTrue(content.contains("enable: false"))
    }

    @Test
    fun buildRuntimeProfileContent_injectsDnsConfigWhenMissing() {
        val source =
            """
            port: 7890
            tun:
              enable: true
            """.trimIndent()

        val content = VpnProfileManager.buildRuntimeProfileContent(source, 17890)

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

        val content = VpnProfileManager.buildRuntimeProfileContent(source, 17890)

        assertTrue("dns.listen should be overridden", content.contains("127.0.0.1:1053"))
        assertTrue("dns.enhanced-mode should be fake-ip", content.contains("enhanced-mode: fake-ip"))
        assertTrue("nameserver should be preserved", content.contains("8.8.8.8"))
        assertEquals("old listen should not appear", -1, content.indexOf("0.0.0.0:53"))
    }
}
