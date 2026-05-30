package info.loveyu.mfca.vpn

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MihomoProcessManagerTest {
    @Test
    fun buildCommand_usesCoreWorkDirAndProfile() {
        val command = MihomoProcessManager.buildCommand(
            coreFile = File("/tmp/mihomo"),
            workDir = File("/tmp/work"),
            profileFile = File("/tmp/profile.yaml"),
        )

        assertEquals(
            listOf("/tmp/mihomo", "-d", "/tmp/work", "-f", "/tmp/profile.yaml"),
            command,
        )
    }
}
