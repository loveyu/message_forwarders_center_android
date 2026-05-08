package info.loveyu.mfca.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigLoaderCallTest {

    // ── Top-level call section parsing ────────────────────────────────────

    @Test
    fun `call section parses name and type`() {
        val config =
            ConfigLoader.loadConfig(
                """
                call:
                  - name: resource-upload
                    type: http
                    url: http://example.com/upload
                """.trimIndent()
            )

        val call = config.calls.single()
        assertEquals("resource-upload", call.name)
        assertEquals(CallType.http, call.type)
    }

    @Test
    fun `call section parses url method headers body response timeout retry`() {
        val config =
            ConfigLoader.loadConfig(
                """
                call:
                  - name: my-call
                    type: http
                    url: "http://example.com/{args[0]}"
                    method: POST
                    headers:
                      content-type: "{args[1].content-type}"
                      x-source: flowgate
                    body: "{args[0]}"
                    response: "{response}"
                    timeout: 20s
                    retry:
                      maxAttempts: 3
                      interval: 2s
                """.trimIndent()
            )

        val call = config.calls.single()
        assertEquals("http://example.com/{args[0]}", call.url)
        assertEquals("POST", call.method)
        assertEquals("{args[1].content-type}", call.headers["content-type"])
        assertEquals("flowgate", call.headers["x-source"])
        assertEquals("{args[0]}", call.body)
        assertEquals("{response}", call.response)
        assertEquals(20_000L, call.timeout.millis)
        assertNotNull(call.retry)
        assertEquals(3, call.retry!!.maxAttempts)
        assertEquals(2_000L, call.retry!!.interval.millis)
    }

    @Test
    fun `call section defaults method to POST and timeout to 15s`() {
        val config =
            ConfigLoader.loadConfig(
                """
                call:
                  - name: minimal-call
                    url: http://example.com
                """.trimIndent()
            )

        val call = config.calls.single()
        assertEquals("POST", call.method)
        assertEquals(15_000L, call.timeout.millis)
        assertNull(call.retry)
        assertNull(call.body)
        assertNull(call.response)
        assertTrue(call.headers.isEmpty())
    }

    @Test
    fun `call section parses multiple calls`() {
        val config =
            ConfigLoader.loadConfig(
                """
                call:
                  - name: upload
                    url: http://upload.example.com
                  - name: analyze
                    url: http://ai.example.com/analyze
                    method: POST
                """.trimIndent()
            )

        assertEquals(2, config.calls.size)
        assertEquals("upload", config.calls[0].name)
        assertEquals("analyze", config.calls[1].name)
    }

    @Test
    fun `empty call section produces empty list`() {
        val config = ConfigLoader.loadConfig("version: \"1.0\"")
        assertTrue(config.calls.isEmpty())
    }

    // ── transform.call field parsing ──────────────────────────────────────

    @Test
    fun `transform call parses single entry`() {
        val config =
            ConfigLoader.loadConfig(
                """
                call:
                  - name: my-call
                    url: http://example.com
                outputs:
                  internal:
                    - name: out
                      type: clipboard
                rules:
                  - name: test_rule
                    from: some_input
                    pipeline:
                      - transform:
                          call:
                            - uploadResponse: "my-call(data, headers)"
                        to: [out]
                """.trimIndent()
            )

        val step = config.rules.single().pipeline.single()
        val callSteps = step.transform!!.call
        assertNotNull(callSteps)
        assertEquals(1, callSteps!!.size)
        assertEquals("my-call(data, headers)", callSteps[0]["uploadResponse"])
    }

    @Test
    fun `transform call parses chained entries in order`() {
        val config =
            ConfigLoader.loadConfig(
                """
                call:
                  - name: upload
                    url: http://upload.example.com
                  - name: analyze
                    url: http://ai.example.com
                outputs:
                  internal:
                    - name: out
                      type: clipboard
                rules:
                  - name: chained
                    from: input_src
                    pipeline:
                      - transform:
                          call:
                            - uploaded: "upload(data, headers)"
                            - aiResult: "analyze(uploaded, data)"
                        to: [out]
                """.trimIndent()
            )

        val callSteps = config.rules.single().pipeline.single().transform!!.call!!
        assertEquals(2, callSteps.size)
        assertEquals("upload(data, headers)", callSteps[0]["uploaded"])
        assertEquals("analyze(uploaded, data)", callSteps[1]["aiResult"])
    }

    @Test
    fun `transform call can coexist with format and filter`() {
        val config =
            ConfigLoader.loadConfig(
                """
                call:
                  - name: enrich-svc
                    url: http://enrich.example.com
                outputs:
                  internal:
                    - name: out
                      type: clipboard
                rules:
                  - name: combined
                    from: src
                    pipeline:
                      - transform:
                          call:
                            - enriched: "enrich-svc(data)"
                          filter: '"{enriched.ok}" == "true"'
                          format: "{enriched.result}"
                        to: [out]
                """.trimIndent()
            )

        val transform = config.rules.single().pipeline.single().transform!!
        assertNotNull(transform.call)
        assertEquals("enrich-svc(data)", transform.call!![0]["enriched"])
        assertEquals("{enriched.result}", transform.format)
        assertNotNull(transform.filter)
    }

    @Test
    fun `absent transform call field is null`() {
        val config =
            ConfigLoader.loadConfig(
                """
                outputs:
                  internal:
                    - name: out
                      type: clipboard
                rules:
                  - name: no_call
                    from: src
                    pipeline:
                      - transform:
                          format: "{data}"
                        to: [out]
                """.trimIndent()
            )

        assertNull(config.rules.single().pipeline.single().transform!!.call)
    }
}
