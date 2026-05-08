package info.loveyu.mfca.pipeline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for evaluateFormatTemplateWithExtras: call args ({args[N]}, {args[N].path})
 * and call vars ({varName}, {varName.path}, {response}).
 */
class ExpressionEngineCallTest : ExpressionEngineBaseTest() {

    private val emptyData = "{}".toByteArray()
    private val emptyHeaders = emptyMap<String, String>()

    // ── args[N] ──────────────────────────────────────────────────────────────

    @Test
    fun `args 0 resolves string argument`() {
        val result = engine.evaluateFormatTemplateWithExtras(
            template = "{args[0]}",
            data = emptyData,
            headers = emptyHeaders,
            callArgs = listOf("hello world")
        )
        assertEquals("hello world", result)
    }

    @Test
    fun `args 1 resolves second argument`() {
        val result = engine.evaluateFormatTemplateWithExtras(
            template = "{args[1]}",
            data = emptyData,
            headers = emptyHeaders,
            callArgs = listOf("first", "second")
        )
        assertEquals("second", result)
    }

    @Test
    fun `args N out of bounds resolves to empty string`() {
        val result = engine.evaluateFormatTemplateWithExtras(
            template = "{args[5]}",
            data = emptyData,
            headers = emptyHeaders,
            callArgs = listOf("only one")
        )
        assertEquals("", result)
    }

    @Test
    fun `args N with map argument serializes to JSON`() {
        val headers = mapOf("content-type" to "application/json", "x-id" to "42")
        val result = engine.evaluateFormatTemplateWithExtras(
            template = "{args[0]}",
            data = emptyData,
            headers = emptyHeaders,
            callArgs = listOf(headers)
        )
        val parsed = JSONObject(result)
        assertEquals("application/json", parsed.getString("content-type"))
        assertEquals("42", parsed.getString("x-id"))
    }

    @Test
    fun `args N dot path extracts field from map argument`() {
        val headers = mapOf("content-type" to "application/json", "x-token" to "abc")
        val result = engine.evaluateFormatTemplateWithExtras(
            template = "{args[0].content-type}",
            data = emptyData,
            headers = emptyHeaders,
            callArgs = listOf(headers)
        )
        assertEquals("application/json", result)
    }

    @Test
    fun `args N dot path extracts nested field from JSONObject`() {
        val arg = JSONObject("""{"user":{"name":"Alice","age":30}}""")
        val result = engine.evaluateFormatTemplateWithExtras(
            template = "{args[0].user.name}",
            data = emptyData,
            headers = emptyHeaders,
            callArgs = listOf(arg)
        )
        assertEquals("Alice", result)
    }

    @Test
    fun `args N dot path on string argument tries JSON parse`() {
        val result = engine.evaluateFormatTemplateWithExtras(
            template = "{args[0].key}",
            data = emptyData,
            headers = emptyHeaders,
            callArgs = listOf("""{"key":"value"}""")
        )
        assertEquals("value", result)
    }

    // ── callVars ─────────────────────────────────────────────────────────────

    @Test
    fun `callVar resolves plain string value`() {
        val result = engine.evaluateFormatTemplateWithExtras(
            template = "{myVar}",
            data = emptyData,
            headers = emptyHeaders,
            callVars = mapOf("myVar" to "stored value")
        )
        assertEquals("stored value", result)
    }

    @Test
    fun `callVar resolves JSONObject field via dot path`() {
        val jsonObj = JSONObject("""{"answer":"42","confidence":0.99}""")
        val result = engine.evaluateFormatTemplateWithExtras(
            template = "{aiResult.answer}",
            data = emptyData,
            headers = emptyHeaders,
            callVars = mapOf("aiResult" to jsonObj)
        )
        assertEquals("42", result)
    }

    @Test
    fun `callVar resolves Map field via dot path`() {
        val mapVar = mapOf("status" to "ok", "url" to "http://example.com/file.jpg")
        val result = engine.evaluateFormatTemplateWithExtras(
            template = "Status: {uploaded.status}, URL: {uploaded.url}",
            data = emptyData,
            headers = emptyHeaders,
            callVars = mapOf("uploaded" to mapVar)
        )
        assertEquals("Status: ok, URL: http://example.com/file.jpg", result)
    }

    @Test
    fun `callVar unknown variable falls through to empty string`() {
        val result = engine.evaluateFormatTemplateWithExtras(
            template = "{unknownVar}",
            data = emptyData,
            headers = emptyHeaders,
            callVars = emptyMap()
        )
        assertEquals("", result)
    }

    // ── response alias ───────────────────────────────────────────────────────

    @Test
    fun `response keyword resolves from callVars response key`() {
        val result = engine.evaluateFormatTemplateWithExtras(
            template = "{response}",
            data = emptyData,
            headers = emptyHeaders,
            callVars = mapOf("response" to """{"id":"123"}""")
        )
        assertEquals("""{"id":"123"}""", result)
    }

    // ── mixed templates ──────────────────────────────────────────────────────

    @Test
    fun `mixed template with args callVars and standard data`() {
        val data = """{"topic":"sensors"}""".toByteArray()
        val args = listOf("uploaded-id-42", mapOf("content-type" to "text/plain"))
        val callVars = mapOf("aiResult" to JSONObject("""{"summary":"all good"}"""))

        val result = engine.evaluateFormatTemplateWithExtras(
            template = "ID={args[0]} type={args[1].content-type} topic={data.topic} ai={aiResult.summary}",
            data = data,
            headers = emptyHeaders,
            callArgs = args,
            callVars = callVars
        )
        assertEquals("ID=uploaded-id-42 type=text/plain topic=sensors ai=all good", result)
    }

    @Test
    fun `callVars take priority over standard GJSON when name matches`() {
        // If there is a callVar "data", it should not conflict because "data" is a reserved keyword
        // But a callVar like "result" should shadow any JSON field named "result"
        val data = """{"result":"from-json"}""".toByteArray()
        val callVars = mapOf("result" to "from-call-var")

        val result = engine.evaluateFormatTemplateWithExtras(
            template = "{result}",
            data = data,
            headers = emptyHeaders,
            callVars = callVars
        )
        assertEquals("from-call-var", result)
    }

    @Test
    fun `standard data placeholder still works with extras`() {
        val data = """{"msg":"hello"}""".toByteArray()
        val result = engine.evaluateFormatTemplateWithExtras(
            template = "{data.msg}",
            data = data,
            headers = emptyHeaders,
            callArgs = listOf("arg0"),
            callVars = mapOf("x" to "y")
        )
        assertEquals("hello", result)
    }

    @Test
    fun `double brace escape still works in extras mode`() {
        val result = engine.evaluateFormatTemplateWithExtras(
            template = "{{",
            data = emptyData,
            headers = emptyHeaders
        )
        assertEquals("{", result)
    }

    @Test
    fun `double brace escape with trailing content in extras mode`() {
        val result = engine.evaluateFormatTemplateWithExtras(
            template = "prefix {{ suffix",
            data = emptyData,
            headers = emptyHeaders
        )
        assertEquals("prefix { suffix", result)
    }

    // ── anyValueToString / extractFromAny helpers ─────────────────────────

    @Test
    fun `anyValueToString converts null to empty string`() {
        assertEquals("", engine.anyValueToString(null))
    }

    @Test
    fun `anyValueToString converts string unchanged`() {
        assertEquals("hello", engine.anyValueToString("hello"))
    }

    @Test
    fun `anyValueToString converts Map to JSON string`() {
        val map = mapOf("a" to 1, "b" to "two")
        val result = engine.anyValueToString(map)
        val parsed = JSONObject(result)
        assertEquals(1, parsed.getInt("a"))
        assertEquals("two", parsed.getString("b"))
    }

    @Test
    fun `anyValueToString converts JSONObject to JSON string`() {
        val obj = JSONObject("""{"key":"value"}""")
        assertEquals("""{"key":"value"}""", engine.anyValueToString(obj))
    }

    @Test
    fun `extractFromAny extracts field from JSONObject`() {
        val obj = JSONObject("""{"name":"Alice","score":99}""")
        val result = engine.extractFromAny(obj, "name")
        assertEquals("Alice", result)
    }

    @Test
    fun `extractFromAny extracts field from Map`() {
        val map = mapOf("status" to "ok")
        val result = engine.extractFromAny(map, "status")
        assertEquals("ok", result?.toString())
    }

    @Test
    fun `extractFromAny extracts field from JSON string`() {
        val result = engine.extractFromAny("""{"x":42}""", "x")
        assertEquals(42, result)
    }

    @Test
    fun `extractFromAny returns null for null input`() {
        assertNull(engine.extractFromAny(null, "key"))
    }
}
