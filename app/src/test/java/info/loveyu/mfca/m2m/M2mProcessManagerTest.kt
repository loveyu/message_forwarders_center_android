package info.loveyu.mfca.m2m

import info.loveyu.mfca.m2m.process.M2mProcessManager
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class M2mProcessManagerTest {
    @Test
    fun buildArgs_usesWorkDirAndProfile() {
        val args = M2mProcessManager.buildArgs(
            workDir = File("/tmp/work"),
            profileFile = File("/tmp/profile.yaml"),
        )

        assertEquals(
            listOf("-d", "/tmp/work", "-f", "/tmp/profile.yaml"),
            args,
        )
    }
}
