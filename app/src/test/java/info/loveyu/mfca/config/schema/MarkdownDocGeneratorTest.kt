package info.loveyu.mfca.config.schema

import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownDocGeneratorTest {

    @Test
    fun `generates title`() {
        val s = configSchema { string("host") {} }
        val md = MarkdownDocGenerator.generate(s, "My Config")
        assertTrue(md.startsWith("# My Config"))
    }

    @Test
    fun `includes field names`() {
        val s =
            configSchema {
                string("host") { description = "The hostname" }
                int("port") { default = 8080 }
            }
        val md = MarkdownDocGenerator.generate(s)
        assertTrue(md.contains("`host`"))
        assertTrue(md.contains("`port`"))
    }

    @Test
    fun `includes type labels`() {
        val s =
            configSchema {
                string("host") {}
                int("port") {}
                boolean("ssl") {}
                duration("timeout") {}
                enum("strategy", listOf("a", "b")) {}
                // objectNode with a child to get its own section with **Type**: object
                objectNode("nested") { string("x") {} }
                stringList("tags") {}
            }
        val md = MarkdownDocGenerator.generate(s)
        // scalar types appear in table columns
        assertTrue(md.contains("| string |"))
        assertTrue(md.contains("| int |"))
        assertTrue(md.contains("| boolean |"))
        assertTrue(md.contains("| duration |"))
        assertTrue(md.contains("| enum |"))
        // object with children gets a dedicated section
        assertTrue(md.contains("**Type**: object"))
        // list type appears in table
        assertTrue(md.contains("list[string]"))
    }

    @Test
    fun `includes defaults`() {
        val s =
            configSchema {
                int("port") { default = 8080 }
                string("host") { default = "localhost" }
                boolean("ssl") { default = false }
                duration("timeout") { default = "5s" }
                enum("mode", listOf("a", "b")) { default = "a" }
            }
        val md = MarkdownDocGenerator.generate(s)
        // defaults appear in the table's default column wrapped in backticks
        assertTrue(md.contains("`8080`"))
        assertTrue(md.contains("`localhost`"))
        assertTrue(md.contains("`false`"))
        assertTrue(md.contains("`5s`"))
    }

    @Test
    fun `includes required marker`() {
        val s = configSchema { string("host") { required() } }
        val md = MarkdownDocGenerator.generate(s)
        // required fields show ✓ in the table column
        assertTrue(md.contains("✓"))
    }

    @Test
    fun `includes enum values`() {
        val s = configSchema { enum("overflow", listOf("dropOldest", "dropNew", "block")) {} }
        val md = MarkdownDocGenerator.generate(s)
        assertTrue(md.contains("`dropOldest`"))
        assertTrue(md.contains("`dropNew`"))
        assertTrue(md.contains("`block`"))
    }

    @Test
    fun `includes deprecation warning`() {
        val s =
            configSchema {
                string("oldHost") {
                    deprecated(since = "2.0", message = "Use host instead", replacement = "host")
                }
            }
        val md = MarkdownDocGenerator.generate(s)
        assertTrue(md.contains("Deprecated"))
        assertTrue(md.contains("since 2.0"))
        assertTrue(md.contains("`host`"))
    }

    @Test
    fun `includes descriptions`() {
        val s = configSchema { string("host") { description = "The target host" } }
        val md = MarkdownDocGenerator.generate(s)
        assertTrue(md.contains("The target host"))
    }

    @Test
    fun `AppConfigSchema generates valid markdown`() {
        val md = MarkdownDocGenerator.generate(AppConfigSchema.schema, "MFCA Config")
        assertTrue(md.startsWith("# MFCA Config"))
        assertTrue(md.contains("`version`"))
        assertTrue(md.contains("`scheduler`"))
        assertTrue(md.contains("`links`"))
        assertTrue(md.contains("`rules`"))
    }
}
