package info.loveyu.mfca.output

import info.loveyu.mfca.config.HttpOutputConfig
import info.loveyu.mfca.queue.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HttpOutputTest {

    @Test
    fun `prepareRequest normalizes headers and extracts content type`() {
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
                            "Content-Type" to "application/json",
                            "content-type" to "text/plain",
                            "Content-Length" to "999",
                            "X-Trace-Id" to "trace-1"
                        )
                )
            )

        assertEquals("POST", prepared.method)
        assertEquals("payload", String(prepared.body))
        assertEquals("text/plain", prepared.contentType)
        assertNull(prepared.headers["Content-Type"])
        assertNull(prepared.headers["content-type"])
        assertFalse(prepared.headers.containsKey("Content-Length"))
        assertEquals("trace-1", prepared.headers["X-Trace-Id"])
    }
}
