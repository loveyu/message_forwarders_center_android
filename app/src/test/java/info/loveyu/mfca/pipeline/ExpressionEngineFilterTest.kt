package info.loveyu.mfca.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for filter expression parsing and evaluation:
 * compileFilter, tokenize, parseExpression, executeFilter.
 */
class ExpressionEngineFilterTest : ExpressionEngineBaseTest() {

    // ── Comparison operators ───────────────────────────────────

    @Test
    fun `filter - equals string`() {
        val data = """{"status":"ok"}""".toByteArray()
        assertTrue(engine.executeFilter("status == \"ok\"", data))
    }

    @Test
    fun `filter - equals string mismatch`() {
        val data = """{"status":"error"}""".toByteArray()
        assertFalse(engine.executeFilter("status == \"ok\"", data))
    }

    @Test
    fun `filter - not equals`() {
        val data = """{"status":"error"}""".toByteArray()
        assertTrue(engine.executeFilter("status != \"ok\"", data))
    }

    @Test
    fun `filter - greater than`() {
        val data = """{"count":10}""".toByteArray()
        assertTrue(engine.executeFilter("count > 5", data))
    }

    @Test
    fun `filter - greater than false`() {
        val data = """{"count":3}""".toByteArray()
        assertFalse(engine.executeFilter("count > 5", data))
    }

    @Test
    fun `filter - less than`() {
        val data = """{"count":3}""".toByteArray()
        assertTrue(engine.executeFilter("count < 5", data))
    }

    @Test
    fun `filter - greater than or equal`() {
        val data = """{"count":5}""".toByteArray()
        assertTrue(engine.executeFilter("count >= 5", data))
    }

    @Test
    fun `filter - less than or equal`() {
        val data = """{"count":5}""".toByteArray()
        assertTrue(engine.executeFilter("count <= 5", data))
    }

    @Test
    fun `filter - less than or equal false`() {
        val data = """{"count":6}""".toByteArray()
        assertFalse(engine.executeFilter("count <= 5", data))
    }

    // ── Logical operators ──────────────────────────────────────

    @Test
    fun `filter - AND both true`() {
        val data = """{"x":10,"y":20}""".toByteArray()
        assertTrue(engine.executeFilter("x > 5 && y > 10", data))
    }

    @Test
    fun `filter - AND one false`() {
        val data = """{"x":10,"y":5}""".toByteArray()
        assertFalse(engine.executeFilter("x > 5 && y > 10", data))
    }

    @Test
    fun `filter - OR both true`() {
        val data = """{"x":10,"y":20}""".toByteArray()
        assertTrue(engine.executeFilter("x > 5 || y > 10", data))
    }

    @Test
    fun `filter - OR one true`() {
        val data = """{"x":10,"y":5}""".toByteArray()
        assertTrue(engine.executeFilter("x > 5 || y > 10", data))
    }

    @Test
    fun `filter - OR both false`() {
        val data = """{"x":3,"y":5}""".toByteArray()
        assertFalse(engine.executeFilter("x > 5 || y > 10", data))
    }

    @Test
    fun `filter - complex AND OR combination`() {
        val data = """{"a":1,"b":2,"c":3}""".toByteArray()
        assertTrue(engine.executeFilter("a == 1 && (b == 2 || c == 99)", data))
    }

    @Test
    fun `filter - complex AND OR combination false`() {
        val data = """{"a":9,"b":2,"c":3}""".toByteArray()
        assertFalse(engine.executeFilter("a == 1 && (b == 2 || c == 99)", data))
    }

    // ── Boolean constants ──────────────────────────────────────

    @Test
    fun `filter - true constant`() {
        val data = """{"x":1}""".toByteArray()
        assertTrue(engine.executeFilter("true", data))
    }

    @Test
    fun `filter - false constant`() {
        val data = """{"x":1}""".toByteArray()
        assertFalse(engine.executeFilter("false", data))
    }

    // ── Path existence check ───────────────────────────────────

    @Test
    fun `filter - path exists`() {
        val data = """{"status":"ok"}""".toByteArray()
        assertTrue(engine.executeFilter("status", data))
    }

    @Test
    fun `filter - path not exists`() {
        val data = """{"status":"ok"}""".toByteArray()
        assertFalse(engine.executeFilter("missing_field", data))
    }

    @Test
    fun `filter - nested path exists`() {
        val data = """{"data":{"temperature":25}}""".toByteArray()
        assertTrue(engine.executeFilter("data.temperature", data))
    }

    // ── Function calls in filters ──────────────────────────────

    @Test
    fun `filter - contains function true`() {
        val data = """{"msg":"hello world"}""".toByteArray()
        assertTrue(engine.executeFilter("contains(msg, \"hello\")", data))
    }

    @Test
    fun `filter - contains function false`() {
        // contains in filter: second arg is a JSON path, so put the search text in JSON
        val data = """{"msg":"hello world","needle":"xyz"}""".toByteArray()
        assertFalse(engine.executeFilter("contains(msg, needle)", data))
    }

    @Test
    fun `filter - startsWith function`() {
        val data = """{"path":"/api/v1/data"}""".toByteArray()
        assertTrue(engine.executeFilter("startsWith(path, \"/api\")", data))
    }

    @Test
    fun `filter - endsWith function`() {
        val data = """{"filename":"test.json"}""".toByteArray()
        assertTrue(engine.executeFilter("endsWith(filename, \".json\")", data))
    }

    @Test
    fun `filter - length function greater than`() {
        val data = """{"items":[1,2,3]}""".toByteArray()
        assertTrue(engine.executeFilter("length(items) > 0", data))
    }

    @Test
    fun `filter - length function equals zero`() {
        val data = """{"items":[]}""".toByteArray()
        assertFalse(engine.executeFilter("length(items) > 0", data))
    }

    // ── Header-based filtering ─────────────────────────────────

    @Test
    fun `filter - with headers`() {
        val data = """{"msg":"test"}""".toByteArray()
        // Filter IDENT tokenizer doesn't support hyphens, so use simple header key
        val headers = mapOf("Topic" to "sensor/data")
        assertTrue(
            engine.executeFilter(
                "\$headers.Topic == \"sensor/data\"",
                data,
                headers
            )
        )
    }

    @Test
    fun `filter - header comparison false`() {
        val data = """{"msg":"test"}""".toByteArray()
        val headers = mapOf("Topic" to "other")
        assertFalse(
            engine.executeFilter(
                "\$headers.Topic == \"sensor/data\"",
                data,
                headers
            )
        )
    }

    // ── Pre-parsed JSON overload ───────────────────────────────

    @Test
    fun `filter - with pre-parsed json`() {
        val json = org.json.JSONObject("""{"status":"active","count":5}""")
        val data = """{"status":"active","count":5}""".toByteArray()
        assertTrue(engine.executeFilter("status == \"active\"", json, data))
        assertTrue(engine.executeFilter("count > 3", json, data))
        assertFalse(engine.executeFilter("count > 10", json, data))
    }

    // ── Non-JSON data ──────────────────────────────────────────

    @Test
    fun `filter - non-JSON data defaults to pass`() {
        val data = "plain text data".toByteArray()
        assertTrue(engine.executeFilter("some.path == \"value\"", data))
    }

    @Test
    fun `filter - empty expression compiles`() {
        val data = """{"x":1}""".toByteArray()
        val compiled = engine.compileFilter("")
        // Empty expression should not crash; may return default true
        assertTrue(compiled.parsed?.constantValue ?: true)
    }

    // ── Raw data function ──────────────────────────────────────

    @Test
    fun `filter - raw data function`() {
        engine.registerRawDataFunction(
            "testRaw",
            ExpressionEngine.RawDataFunction("testRaw") { data, _ ->
                String(data).contains("test")
            }
        )
        val data = """this is a test message""".toByteArray()
        assertTrue(engine.executeFilter("testRaw()", data))
    }

    @Test
    fun `filter - raw data function with args`() {
        engine.registerRawDataFunction(
            "dataEquals",
            ExpressionEngine.RawDataFunction("dataEquals") { data, args ->
                val expected = args.getOrNull(0)?.toString() ?: ""
                String(data) == expected
            }
        )
        val data = "hello".toByteArray()
        assertTrue(engine.executeFilter("dataEquals(\"hello\")", data))
        assertFalse(engine.executeFilter("dataEquals(\"world\")", data))
    }

    // ── Precompile ─────────────────────────────────────────────

    @Test
    fun `precompileExpressions - caches filters`() {
        engine.precompileExpressions(listOf("x > 0", "y == \"test\""))
        // Second call should use cache
        val data = """{"x":5,"y":"test"}""".toByteArray()
        assertTrue(engine.executeFilter("x > 0", data))
        assertTrue(engine.executeFilter("y == \"test\"", data))
    }

    // ── clipboardNew deduplication simulation ───────────────────

    @Test
    fun `clipboardNew simulation - first call passes`() {
        val lastPassed = mutableMapOf<String, Long>()
        engine.registerRawDataFunction(
            "simClipboardNew",
            ExpressionEngine.RawDataFunction("simClipboardNew") { data, args ->
                val maxAgeMs = (args.getOrNull(0) as? Number)?.toLong()?.times(1000L) ?: 10_000L
                val text = String(data)
                val now = System.currentTimeMillis()
                val last = lastPassed[text]
                if (last != null && (now - last) <= maxAgeMs) false
                else { lastPassed[text] = now; true }
            }
        )
        val data = "hello clipboard".toByteArray()
        assertTrue(engine.executeFilter("simClipboardNew(30)", data))
    }

    @Test
    fun `clipboardNew simulation - repeated call blocked`() {
        val lastPassed = mutableMapOf<String, Long>()
        engine.registerRawDataFunction(
            "simClipboardNew2",
            ExpressionEngine.RawDataFunction("simClipboardNew2") { data, args ->
                val maxAgeMs = (args.getOrNull(0) as? Number)?.toLong()?.times(1000L) ?: 10_000L
                val text = String(data)
                val now = System.currentTimeMillis()
                val last = lastPassed[text]
                if (last != null && (now - last) <= maxAgeMs) false
                else { lastPassed[text] = now; true }
            }
        )
        val data = "repeated content".toByteArray()
        // First call passes
        assertTrue(engine.executeFilter("simClipboardNew2(30)", data))
        // Immediate second call is blocked (in-memory cache prevents window reset)
        assertFalse(engine.executeFilter("simClipboardNew2(30)", data))
    }

    @Test
    fun `clipboardNew simulation - different content passes independently`() {
        val lastPassed = mutableMapOf<String, Long>()
        engine.registerRawDataFunction(
            "simClipboardNew3",
            ExpressionEngine.RawDataFunction("simClipboardNew3") { data, args ->
                val maxAgeMs = (args.getOrNull(0) as? Number)?.toLong()?.times(1000L) ?: 10_000L
                val text = String(data)
                val now = System.currentTimeMillis()
                val last = lastPassed[text]
                if (last != null && (now - last) <= maxAgeMs) false
                else { lastPassed[text] = now; true }
            }
        )
        // First content passes
        assertTrue(engine.executeFilter("simClipboardNew3(30)", "content A".toByteArray()))
        // Second different content also passes independently
        assertTrue(engine.executeFilter("simClipboardNew3(30)", "content B".toByteArray()))
        // Repeating first content is still blocked
        assertFalse(engine.executeFilter("simClipboardNew3(30)", "content A".toByteArray()))
    }

    // ── and/or/not keyword operators ─────────────────────────────

    @Test
    fun `filter - and keyword both true`() {
        val data = """{"a":1,"b":1}""".toByteArray()
        assertTrue(engine.executeFilter("a > 0 and b > 0", data))
    }

    @Test
    fun `filter - and keyword one false`() {
        val data = """{"a":1,"b":-1}""".toByteArray()
        assertFalse(engine.executeFilter("a > 0 and b > 0", data))
    }

    @Test
    fun `filter - or keyword one true`() {
        val data = """{"a":1,"b":-1}""".toByteArray()
        assertTrue(engine.executeFilter("a > 0 or b > 0", data))
    }

    @Test
    fun `filter - or keyword both false`() {
        val data = """{"a":-1,"b":-1}""".toByteArray()
        assertFalse(engine.executeFilter("a > 0 or b > 0", data))
    }

    @Test
    fun `filter - not keyword negates true`() {
        val data = """{"a":5}""".toByteArray()
        assertFalse(engine.executeFilter("not a > 0", data))
    }

    @Test
    fun `filter - not keyword negates false`() {
        val data = """{"a":-1}""".toByteArray()
        assertTrue(engine.executeFilter("not a > 0", data))
    }

    @Test
    fun `filter - not keyword with parens`() {
        val data = """{"a":1}""".toByteArray()
        assertFalse(engine.executeFilter("not (1 == 1 and 2 == 2)", data))
    }

    @Test
    fun `filter - not keyword with or`() {
        val data = """{"a":1}""".toByteArray()
        assertTrue(engine.executeFilter("not (1 == 2 and 1 == 3)", data))
    }

    @Test
    fun `filter - negative number comparison`() {
        val data = """{"x":-1}""".toByteArray()
        assertTrue(engine.executeFilter("-1 < 0", data))
    }

    // ── Two-phase filter (with {…} template) ────────────────────

    @Test
    fun `two-phase - numeric comparison`() {
        val data = """{"temperature":30}""".toByteArray()
        assertTrue(engine.executeTwoPhaseFilter("{temperature} > 25", data))
    }

    @Test
    fun `two-phase - numeric comparison false`() {
        val data = """{"temperature":20}""".toByteArray()
        assertFalse(engine.executeTwoPhaseFilter("{temperature} > 25", data))
    }

    @Test
    fun `two-phase - string comparison`() {
        val data = """{"type":"text"}""".toByteArray()
        assertTrue(engine.executeTwoPhaseFilter("\"{type}\" == \"text\"", data))
    }

    @Test
    fun `two-phase - string comparison false`() {
        val data = """{"type":"json"}""".toByteArray()
        assertFalse(engine.executeTwoPhaseFilter("\"{type}\" == \"text\"", data))
    }

    @Test
    fun `two-phase - function in template`() {
        val data = """{"items":[1,2,3]}""".toByteArray()
        assertTrue(engine.executeTwoPhaseFilter("{length(items)} > 0", data))
    }

    @Test
    fun `two-phase - function in template empty array`() {
        val data = """{"items":[]}""".toByteArray()
        assertFalse(engine.executeTwoPhaseFilter("{length(items)} > 0", data))
    }

    @Test
    fun `two-phase - clipboardUpdateBefore with or`() {
        var callCount = 0
        engine.clipboardUpdateBeforeFn = { _ ->
            callCount++
            if (callCount <= 1) 60L else -1L
        }
        val data = """{"data":"hello"}""".toByteArray()
        // 60 > 30 => true
        assertTrue(engine.executeTwoPhaseFilter("{clipboardUpdateBefore(data)} > 30 or {clipboardUpdateBefore(data)} < 0", data))
    }

    @Test
    fun `two-phase - clipboardUpdateBefore new content`() {
        engine.clipboardUpdateBeforeFn = { _ -> -1L }
        val data = """{"data":"new"}""".toByteArray()
        // -1 < 0 => true
        assertTrue(engine.executeTwoPhaseFilter("{clipboardUpdateBefore(data)} > 30 or {clipboardUpdateBefore(data)} < 0", data))
    }

    @Test
    fun `two-phase - clipboardUpdateBefore recent content rejected`() {
        engine.clipboardUpdateBeforeFn = { _ -> 5L }
        val data = """{"data":"recent"}""".toByteArray()
        // 5 > 30 = false, 5 < 0 = false => overall false
        assertFalse(engine.executeTwoPhaseFilter("{clipboardUpdateBefore(data)} > 30 or {clipboardUpdateBefore(data)} < 0", data))
    }

    @Test
    fun `two-phase - not with template`() {
        val data = """{"status":"inactive"}""".toByteArray()
        assertTrue(engine.executeTwoPhaseFilter("not \"{status}\" == \"active\"", data))
    }

    @Test
    fun `two-phase - compound and with templates`() {
        val data = """{"data":{"type":"sensor","temperature":25}}""".toByteArray()
        assertTrue(engine.executeTwoPhaseFilter("\"{data.type}\" == \"sensor\" and {data.temperature} > 20", data))
    }

    @Test
    fun `two-phase - compound and false`() {
        val data = """{"data":{"type":"sensor","temperature":15}}""".toByteArray()
        assertFalse(engine.executeTwoPhaseFilter("\"{data.type}\" == \"sensor\" and {data.temperature} > 20", data))
    }

    @Test
    fun `two-phase - pre-parsed json overload`() {
        val json = org.json.JSONObject("""{"temperature":30}""")
        val data = """{"temperature":30}""".toByteArray()
        assertTrue(engine.executeTwoPhaseFilter("{temperature} > 25", json, data))
    }

    // ── Backward compatibility ──────────────────────────────────

    @Test
    fun `backward compat - legacy filter still works`() {
        val data = """{"type":"text"}""".toByteArray()
        assertTrue(engine.executeTwoPhaseFilter("type == \"text\"", data))
    }

    @Test
    fun `backward compat - clipboardNew raw data function still works`() {
        engine.registerRawDataFunction(
            "testClipboardNewCompat",
            ExpressionEngine.RawDataFunction("testClipboardNewCompat") { _, _ -> true }
        )
        val data = "hello".toByteArray()
        assertTrue(engine.executeTwoPhaseFilter("testClipboardNewCompat(10)", data))
    }

    // ── Sample pattern conversions ──────────────────────────────

    @Test
    fun `sample pattern - type check`() {
        val data = """{"type":"text"}""".toByteArray()
        assertTrue(engine.executeTwoPhaseFilter("\"{type}\" == \"text\"", data))
    }

    @Test
    fun `sample pattern - nested temperature`() {
        val data = """{"data":{"temperature":30}}""".toByteArray()
        assertTrue(engine.executeTwoPhaseFilter("{data.temperature} > 25", data))
    }

    @Test
    fun `sample pattern - nested status`() {
        val data = """{"data":{"status":"active"}}""".toByteArray()
        assertTrue(engine.executeTwoPhaseFilter("\"{data.status}\" == \"active\"", data))
    }

    @Test
    fun `sample pattern - length of nested array`() {
        val data = """{"data":{"devices":["a","b"]}}""".toByteArray()
        assertTrue(engine.executeTwoPhaseFilter("{length(data.devices)} > 0", data))
    }

    @Test
    fun `sample pattern - length of array`() {
        val data = """{"data":[1,2,3]}""".toByteArray()
        assertTrue(engine.executeTwoPhaseFilter("{length(data)} > 0", data))
    }

    @Test
    fun `sample pattern - contains in template`() {
        val data = """{"topic":"sensor/temperature"}""".toByteArray()
        val headers = mapOf("mqtt_topic" to "sensor/temperature")
        assertTrue(engine.executeTwoPhaseFilter("\"{\$headers.mqtt_topic}\" == \"sensor/temperature\"", data, headers))
    }

    @Test
    fun `sample pattern - header not equals`() {
        val data = """{"msg":"test"}""".toByteArray()
        val headers = mapOf("mqtt_topic" to "sensor/data")
        assertTrue(engine.executeTwoPhaseFilter("\"{\$headers.mqtt_topic}\" != \"sensor/other\"", data, headers))
    }

    @Test
    fun `sample pattern - header id type check`() {
        val data = """{"msg":"test"}""".toByteArray()
        val headers = mapOf("X-IdType" to "mqtt_failures")
        assertTrue(engine.executeTwoPhaseFilter("\"{\$headers.X-IdType}\" == \"mqtt_failures\"", data, headers))
    }

    @Test
    fun `sample pattern - temperature greater than zero`() {
        val data = """{"temperature":25}""".toByteArray()
        assertTrue(engine.executeTwoPhaseFilter("{temperature} > 0", data))
    }

    @Test
    fun `sample pattern - escape double brace`() {
        val data = """{"x":1}""".toByteArray()
        // {{ should resolve to literal {
        assertTrue(engine.executeTwoPhaseFilter("{{not-a-placeholder}}", data))
    }
}
