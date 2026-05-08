package info.loveyu.mfca.config.schema

import info.loveyu.mfca.config.ConfigLoader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates all sample YAML configs in app/src/main/assets/samples/ pass schema validation and
 * parse without error.
 */
class SampleConfigsTest {

    private val samplesDir =
        File(System.getProperty("user.dir"), "src/main/assets/samples")

    @Test
    fun `samples directory exists and contains yaml files`() {
        assertTrue("samples dir should exist: ${samplesDir.absolutePath}", samplesDir.isDirectory)
        val yamlFiles = samplesDir.listFiles { f -> f.extension == "yaml" }
        assertNotNull(yamlFiles)
        assertTrue("samples dir should contain yaml files", yamlFiles!!.isNotEmpty())
    }

    @Test
    fun `all sample configs pass schema validation and parse successfully`() {
        val yamlFiles =
            samplesDir
                .listFiles { f -> f.extension == "yaml" }
                ?.sortedBy { it.name }
                ?: emptyList()

        assertTrue("No sample yaml files found in ${samplesDir.absolutePath}", yamlFiles.isNotEmpty())

        val failures = mutableListOf<String>()
        for (file in yamlFiles) {
            try {
                ConfigLoader.loadConfig(file.readText())
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message}"
            }
        }

        assertTrue(
            "Sample config validation failures:\n${failures.joinToString("\n")}",
            failures.isEmpty(),
        )
    }

    @Test
    fun `01 basic mqtt parses correctly`() {
        val config = loadSample("01_basic_mqtt.yaml")
        assertTrue("links should be non-empty", config.links.isNotEmpty())
        assertTrue("rules should be non-empty", config.rules.isNotEmpty())
    }

    @Test
    fun `11 clipboard forward with list linkId parses correctly`() {
        val config = loadSample("11_clipboard_forward.yaml")
        val linkInput = config.inputs.link.firstOrNull { it.name == "mqtt_clipboard" }
        assertNotNull("mqtt_clipboard input should exist", linkInput)
        assertTrue(
            "linkId list should have 2 entries",
            linkInput!!.linkIds.size == 2,
        )
    }

    @Test
    fun `17 fail queue parses correctly`() {
        val config = loadSample("17_fail_queue.yaml")
        val mqttOut = config.outputs.link.firstOrNull { it.name == "mqtt_out" }
        assertNotNull("mqtt_out output should exist", mqttOut)
        assertNotNull("mqtt_out should have onFailureQueue", mqttOut!!.onFailureQueue)
        assertTrue("sqlite queues should be non-empty", config.queues.sqlite.isNotEmpty())
    }

    @Test
    fun `18 output format parses correctly`() {
        val config = loadSample("18_output_format.yaml")
        val linkOutputs = config.outputs.link
        assertTrue("link outputs should be non-empty", linkOutputs.isNotEmpty())
        val enriched = linkOutputs.firstOrNull { it.name == "mqtt_enriched" }
        assertNotNull("mqtt_enriched output should exist", enriched)
        assertNotNull("mqtt_enriched should have format steps", enriched!!.format)
    }

    @Test
    fun `19 call resource parses correctly`() {
        val config = loadSample("19_call_resource.yaml")
        assertTrue("calls should be non-empty", config.calls.isNotEmpty())
        val uploadCall = config.calls.firstOrNull { it.name == "resource-upload" }
        assertNotNull("resource-upload call should exist", uploadCall)
        assertEquals("http://files.example.com/upload", uploadCall!!.url)
        assertNotNull("resource-upload should have retry", uploadCall.retry)
        val ruleWithCall = config.rules.firstOrNull { it.name == "gotify_notify" }
        assertNotNull("gotify_notify rule should exist", ruleWithCall)
        val callSteps = ruleWithCall!!.pipeline.first().transform?.call
        assertNotNull("gotify_notify pipeline should have call steps", callSteps)
        assertTrue("call steps should have 2 entries", callSteps!!.size == 2)
    }

    @Test
    fun `99 full demo parses all sections`() {
        val config = loadSample("99_full_demo.yaml")
        assertTrue("links should be non-empty", config.links.isNotEmpty())
        assertTrue("rules should be non-empty", config.rules.isNotEmpty())
        assertTrue("http outputs should be non-empty", config.outputs.http.isNotEmpty())
        assertTrue("internal outputs should be non-empty", config.outputs.internal.isNotEmpty())
        assertTrue("memory queues should be non-empty", config.queues.memory.isNotEmpty())
        assertTrue("sqlite queues should be non-empty", config.queues.sqlite.isNotEmpty())
        assertTrue("deadLetter enabled", config.deadLetter.enabled)
    }

    private fun loadSample(name: String) =
        ConfigLoader.loadConfig(File(samplesDir, name).readText())
}
