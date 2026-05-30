package info.loveyu.mfca.vpn

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
}
