package info.loveyu.mfca.output

import info.loveyu.mfca.config.HttpOutputConfig
import info.loveyu.mfca.queue.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpOutputTest {

    @Test
    fun `prepareRequest filters out non-configured headers`() {
        val output =
            HttpOutput(
                name = "gps_logger_output",
                config = HttpOutputConfig(name = "gps_logger_output", url = "https://example.com")
            )

        val prepared =
            output.prepareRequest(
                QueueItem(
                    data = "payload".toByteArray(),
                    headers =
                        linkedMapOf(
                            "content-type" to "application/json",
                            "Content-Length" to "999",
                            "remote-addr" to "127.0.0.1",
                            "X-Matched-Path" to "/api",
                            "X-Trace-Id" to "trace-1"
                        )
                )
            )

        assertEquals("POST", prepared.method)
        assertEquals("payload", String(prepared.body))
        // content-type extracted separately
        assertEquals("application/json", prepared.contentType)
        assertNull(prepared.headers["content-type"])
        // content-length stripped
        assertFalse(prepared.headers.containsKey("Content-Length"))
        // unconfigured input headers must be filtered out
        assertFalse(prepared.headers.containsKey("remote-addr"))
        assertFalse(prepared.headers.containsKey("X-Matched-Path"))
        assertFalse(prepared.headers.containsKey("X-Trace-Id"))
    }

    @Test
    fun `prepareRequest forwards headers declared in output config`() {
        val output =
            HttpOutput(
                name = "out",
                config =
                    HttpOutputConfig(
                        name = "out",
                        url = "https://example.com",
                        headers = mapOf("X-Token" to "secret", "X-Source" to "{source}")
                    )
            )

        // item.headers would contain formatted values after applyOutputFormat
        val prepared =
            output.prepareRequest(
                QueueItem(
                    data = "body".toByteArray(),
                    headers =
                        linkedMapOf(
                            "content-type" to "text/plain",
                            "remote-addr" to "10.0.0.1",
                            "X-Token" to "secret",         // set by format step
                            "X-Source" to "mqtt_in"        // set by format step
                        )
                )
            )

        assertEquals("text/plain", prepared.contentType)
        assertEquals("secret", prepared.headers["X-Token"])
        assertEquals("mqtt_in", prepared.headers["X-Source"])
        // unconfigured headers still filtered
        assertFalse(prepared.headers.containsKey("remote-addr"))
    }

    @Test
    fun `prepareRequest defaults content-type when absent`() {
        val output =
            HttpOutput(
                name = "out",
                config = HttpOutputConfig(name = "out", url = "https://example.com")
            )
        val prepared =
            output.prepareRequest(QueueItem(data = byteArrayOf(), headers = emptyMap()))

        assertEquals("application/octet-stream", prepared.contentType)
        assertTrue(prepared.headers.isEmpty())
    }
}
