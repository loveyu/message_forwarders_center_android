package info.loveyu.mfca.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StoragePathResolverTest {

    private val filesDir = File("/tmp/app-files")
    private val cacheDir = File("/tmp/app-cache")
    private val sdcardDir = File("/tmp/sdcard")

    @Test
    fun `resolveFile uses external files dir for data scheme`() {
        val externalFilesDir = File("/tmp/app-external")

        val resolved =
            StoragePathResolver.resolveFile(
                path = "data://logs/app.log",
                externalFilesDir = externalFilesDir,
                filesDir = filesDir,
                cacheDir = cacheDir,
                sdcardDir = sdcardDir
            )

        assertEquals(File(externalFilesDir, "logs/app.log").absolutePath, resolved.absolutePath)
    }

    @Test
    fun `resolveFile falls back to files dir for data scheme`() {
        val resolved =
            StoragePathResolver.resolveFile(
                path = "data://logs/app.log",
                externalFilesDir = null,
                filesDir = filesDir,
                cacheDir = cacheDir,
                sdcardDir = sdcardDir
            )

        assertEquals(File(filesDir, "logs/app.log").absolutePath, resolved.absolutePath)
    }

    @Test
    fun `resolveFile uses cache dir for cache scheme`() {
        val resolved =
            StoragePathResolver.resolveFile(
                path = "cache://images/icon.png",
                externalFilesDir = null,
                filesDir = filesDir,
                cacheDir = cacheDir,
                sdcardDir = sdcardDir
            )

        assertEquals(File(cacheDir, "images/icon.png").absolutePath, resolved.absolutePath)
    }

    @Test
    fun `resolveFile strips file scheme`() {
        val resolved =
            StoragePathResolver.resolveFile(
                path = "file:///tmp/demo.txt",
                externalFilesDir = null,
                filesDir = filesDir,
                cacheDir = cacheDir,
                sdcardDir = sdcardDir
            )

        assertEquals("/tmp/demo.txt", resolved.absolutePath)
    }

    @Test
    fun `resolveFile allows raw paths when requested`() {
        val resolved =
            StoragePathResolver.resolveFile(
                path = "/tmp/raw.txt",
                externalFilesDir = null,
                filesDir = filesDir,
                cacheDir = cacheDir,
                sdcardDir = sdcardDir,
                allowRawPath = true
            )

        assertEquals("/tmp/raw.txt", resolved.absolutePath)
    }

    @Test
    fun `resolveFile rejects unsupported protocol`() {
        val exception =
            runCatching {
                StoragePathResolver.resolveFile(
                    path = "content://demo",
                    externalFilesDir = null,
                    filesDir = filesDir,
                    cacheDir = cacheDir,
                    sdcardDir = sdcardDir
                )
            }.exceptionOrNull()

        assertTrue(exception is IllegalArgumentException)
        assertEquals(
            "Unsupported path protocol: content://demo, must use data://, cache://, sdcard:// or file://",
            exception?.message
        )
    }
}
