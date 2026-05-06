package info.loveyu.mfca.config.schema

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Generates config_schema.md into the assets directory.
 *
 * Not run during regular test passes. Triggered explicitly by:
 *   ./gradlew generateConfigDoc
 */
class GenerateConfigDocTest {

    @Test
    fun `generate config schema markdown to assets`() {
        assumeTrue(
            "Skipped in regular test runs. Use ./gradlew generateConfigDoc to regenerate.",
            System.getProperty("generateConfigDoc") == "true",
        )

        val markdown =
            MarkdownDocGenerator.generate(
                AppConfigSchema.schema,
                "Config Schema Reference",
            )

        val outputFile = File(System.getProperty("user.dir"), "src/main/assets/config_schema.md")
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(markdown + "\n")
    }
}
