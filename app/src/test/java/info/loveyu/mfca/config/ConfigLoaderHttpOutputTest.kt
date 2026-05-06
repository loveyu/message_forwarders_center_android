package info.loveyu.mfca.config

import info.loveyu.mfca.pipeline.ExpressionEngine
import info.loveyu.mfca.util.LogManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ConfigLoaderHttpOutputTest {

    @Test
    fun `http output parses headers and body templates`() {
        val config =
            ConfigLoader.loadConfig(
                """
                outputs:
                  http:
                    - name: gps_logger_output
                      url: https://example.com
                      method: POST
                      headers:
                        content-type: "{headers.content-type}"
                        x-device-id: "{data.deviceId}"
                      body: "{data}"
                      timeout: 15s
                """.trimIndent()
            )

        val output = config.outputs.http.single()
        assertEquals("gps_logger_output", output.name)
        assertEquals("https://example.com", output.url)
        assertEquals("POST", output.method)
        assertEquals("{headers.content-type}", output.headers["content-type"])
        assertEquals("{data.deviceId}", output.headers["x-device-id"])
        assertEquals("{data}", output.body)
        assertEquals(15_000L, output.timeout.millis)
    }

    @Test
    fun `http output headers and body are converted into effective format steps`() {
        val output =
            HttpOutputConfig(
                name = "gps_logger_output",
                url = "https://example.com",
                format = listOf(OutputFormatStep(target = "\$header.X-Trace-Id", template = "{data.traceId}")),
                headers =
                    linkedMapOf(
                        "content-type" to "{headers.Content-Type}",
                        "x-device-id" to "{data.deviceId}"
                    ),
                body = "{data}"
            )

        val steps = output.effectiveFormatSteps
        assertNotNull(steps)
        assertEquals(listOf("\$header.X-Trace-Id", "\$header.content-type", "\$header.x-device-id", "\$data"), steps!!.map { it.target })
    }

    @Test
    fun `http output templates resolve headers case insensitively`() {
        LogManager.logToStdout = true
        val engine = ExpressionEngine()
        val output =
            HttpOutputConfig(
                name = "gps_logger_output",
                url = "https://example.com",
                headers = linkedMapOf("content-type" to "{headers.content-type}"),
                body = "{data}"
            )

        val (body, headers) =
            engine.applyFormatSteps(
                output.effectiveFormatSteps!!,
                """{"deviceId":"gps-01","speed":12}""".toByteArray(),
                linkedMapOf("Content-Type" to "application/json")
            )

        assertEquals("""{"deviceId":"gps-01","speed":12}""", String(body))
        assertEquals("application/json", headers["content-type"])
    }
}
