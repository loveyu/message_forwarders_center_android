package info.loveyu.mfca.vpn

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MihomoProcessManagerTest {
    @Test
    fun buildArgs_usesWorkDirAndProfile() {
        val args = MihomoProcessManager.buildArgs(
            workDir = File("/tmp/work"),
            profileFile = File("/tmp/profile.yaml"),
        )

        assertEquals(
            listOf("-d", "/tmp/work", "-f", "/tmp/profile.yaml"),
            args,
        )
    }
}
