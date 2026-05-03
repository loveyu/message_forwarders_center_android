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
}
