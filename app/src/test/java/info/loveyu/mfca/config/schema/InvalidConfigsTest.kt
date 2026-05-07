package info.loveyu.mfca.config.schema

import info.loveyu.mfca.config.ConfigLoadException
import info.loveyu.mfca.config.ConfigLoader
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests that invalid YAML configs produce appropriate errors when loaded.
 *
 * Invalid config files are stored in src/test/resources/invalid_configs/ and are intentionally
 * broken to exercise schema validation and parsing error paths.
 */
class InvalidConfigsTest {

    private val invalidConfigsDir =
        File(System.getProperty("user.dir"), "src/test/resources/invalid_configs")

    private fun loadInvalid(name: String): String =
        File(invalidConfigsDir, name).readText()

    private fun assertThrowsConfigLoadException(yaml: String) {
        try {
            ConfigLoader.loadConfig(yaml)
            fail("Expected ConfigLoadException but config loaded successfully")
        } catch (e: ConfigLoadException) {
            // expected
        }
    }

    @Test
    fun `invalid duration format throws ConfigLoadException`() {
        assertThrowsConfigLoadException(loadInvalid("invalid_duration.yaml"))
    }

    @Test
    fun `missing required link output name throws ConfigLoadException`() {
        assertThrowsConfigLoadException(loadInvalid("missing_required_field.yaml"))
    }

    @Test
    fun `missing required link id throws ConfigLoadException`() {
        assertThrowsConfigLoadException(loadInvalid("missing_link_id.yaml"))
    }

    @Test
    fun `inline invalid duration format throws ConfigLoadException`() {
        val yaml =
            """
            links:
              - id: test_link
                dsn: mqtt://localhost:1883
            outputs:
              link:
                - name: test_out
                  linkId: test_link
                  topic: test/out
                  queue:
                    name: q
                    delay: "99xyz"
            queues:
              memory:
                q:
                  capacity: 100
            """
                .trimIndent()
        assertThrowsConfigLoadException(yaml)
    }

    @Test
    fun `decimal duration strings are parsed correctly`() {
        val yaml =
            """
            links:
              - id: test_link
                dsn: mqtt://localhost:1883
            outputs:
              link:
                - name: test_out
                  linkId: test_link
                  topic: test/out
                  queue:
                    name: q
                    delay: "1.5s"
            queues:
              memory:
                q:
                  capacity: 100
            """
                .trimIndent()
        val config = ConfigLoader.loadConfig(yaml)
        val queueRef = config.outputs.link.first().queue
        assertTrue("delay should be 1500ms", queueRef!!.delay.millis == 1500L)
    }

    @Test
    fun `invalid configs directory exists and contains yaml files`() {
        assertTrue(
            "invalid_configs dir should exist: ${invalidConfigsDir.absolutePath}",
            invalidConfigsDir.isDirectory,
        )
        val yamlFiles = invalidConfigsDir.listFiles { f -> f.extension == "yaml" }
        assertTrue("invalid_configs dir should contain yaml files", yamlFiles!!.isNotEmpty())
    }
}
