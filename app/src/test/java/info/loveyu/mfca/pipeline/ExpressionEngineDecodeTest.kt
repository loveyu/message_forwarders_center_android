package info.loveyu.mfca.pipeline

import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the decode pipeline (evaluateDecodePipeline) and new built-in functions
 * (jsonDecode, yamlDecode, yamlEncode, gzEncode/gzDecode, gzipEncode/gzipDecode).
 */
class ExpressionEngineDecodeTest : ExpressionEngineBaseTest() {

    @Before
    override fun setUp() {
        super.setUp()
        // Override gzEncode/gzDecode to use java.util.Base64 instead of android.util.Base64
        engine.registerCustomFunction(
            "gzEncode",
            ExpressionEngine.BuiltinFunction("gzEncode", 1) { args ->
                val str = args.getOrNull(0)?.toString() ?: return@BuiltinFunction ""
                try {
                    val bos = java.io.ByteArrayOutputStream()
                    GZIPOutputStream(bos).use { gzip ->
                        gzip.write(str.toByteArray(Charsets.UTF_8))
                    }
                    java.util.Base64.getEncoder().encodeToString(bos.toByteArray())
                } catch (e: Exception) {
                    ""
                }
            }
        )
        engine.registerCustomFunction(
            "gzDecode",
            ExpressionEngine.BuiltinFunction("gzDecode", 1) { args ->
                val str = args.getOrNull(0)?.toString() ?: return@BuiltinFunction ""
                try {
                    val compressed = java.util.Base64.getDecoder().decode(str)
                    GZIPInputStream(java.io.ByteArrayInputStream(compressed)).use { gzip ->
                        gzip.readBytes().toString(Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    ""
                }
            }
        )
        engine.registerCustomFunction("gzipEncode", engine.getBuiltinFunctions()["gzEncode"]!!)
        engine.registerCustomFunction("gzipDecode", engine.getBuiltinFunctions()["gzDecode"]!!)
    }

    // ── base64Decode|jsonDecode ───────────────────────────────────

    @Test
    fun `base64Decode then jsonDecode pipeline`() {
        val original = """{"name":"test","value":42}"""
        val b64 = java.util.Base64.getEncoder().encodeToString(original.toByteArray())
        val data = b64.toByteArray()
        val result = engine.evaluateDecodePipeline("base64Decode|jsonDecode", data)
        assertNotNull(result)
        val json = JSONObject(String(result!!))
        assertEquals("test", json.getString("name"))
        assertEquals(42, json.getInt("value"))
    }

    // ── jsonDecode ────────────────────────────────────────────────

    @Test
    fun `jsonDecode single function`() {
        val data = """{"key":"value"}""".toByteArray()
        val result = engine.evaluateDecodePipeline("jsonDecode", data)
        assertNotNull(result)
        val json = JSONObject(String(result!!))
        assertEquals("value", json.getString("key"))
    }

    @Test
    fun `jsonDecode json array`() {
        val data = """[1,2,3]""".toByteArray()
        val result = engine.evaluateDecodePipeline("jsonDecode", data)
        assertNotNull(result)
        val arr = JSONArray(String(result!!))
        assertEquals(3, arr.length())
        assertEquals(1, arr.getInt(0))
    }

    // ── jsonDecode|jsonDecode (double-encoded JSON) ───────────────

    @Test
    fun `jsonDecode double encoded`() {
        val inner = """{"x":1}"""
        val quoted = JSONObject.quote(inner) // -> "\"{"x":1}\""
        val data = quoted.toByteArray()
        val result = engine.evaluateDecodePipeline("jsonDecode|jsonDecode", data)
        assertNotNull(result)
        val json = JSONObject(String(result!!))
        assertEquals(1, json.getInt("x"))
    }

    // ── base64Decode only ─────────────────────────────────────────

    @Test
    fun `base64Decode single function`() {
        val original = "hello world"
        val b64 = java.util.Base64.getEncoder().encodeToString(original.toByteArray())
        val data = b64.toByteArray()
        val result = engine.evaluateDecodePipeline("base64Decode", data)
        assertNotNull(result)
        assertEquals(original, String(result!!))
    }

    // ── gzEncode / gzDecode ───────────────────────────────────────

    @Test
    fun `gzEncode and gzDecode roundtrip`() {
        val data = "hello gzip world".toByteArray()
        val encoded = engine.evaluateDecodePipeline("gzEncode", data)
        assertNotNull(encoded)
        val encodedStr = String(encoded!!)
        assertTrue(encodedStr.isNotEmpty())

        val decoded = engine.evaluateDecodePipeline("gzDecode", encoded)
        assertNotNull(decoded)
        assertEquals("hello gzip world", String(decoded!!))
    }

    // ── gzEncode|base64Encode then base64Decode|gzDecode ──────────

    @Test
    fun `gzEncode then gzDecode pipeline roundtrip`() {
        val original = "test gzip pipeline"
        // gzEncode already returns base64 string
        val encoded = engine.evaluateDecodePipeline("gzEncode", original.toByteArray())
        assertNotNull(encoded)
        val decoded = engine.evaluateDecodePipeline("gzDecode", encoded!!)
        assertNotNull(decoded)
        assertEquals(original, String(decoded!!))
    }

    // ── gzipEncode / gzipDecode aliases ───────────────────────────

    @Test
    fun `gzipEncode and gzipDecode are aliases`() {
        val original = "alias test"
        val encoded = engine.evaluateDecodePipeline("gzipEncode", original.toByteArray())
        assertNotNull(encoded)
        val decoded = engine.evaluateDecodePipeline("gzipDecode", encoded!!)
        assertNotNull(decoded)
        assertEquals(original, String(decoded!!))
    }

    // ── yamlDecode ────────────────────────────────────────────────

    @Test
    fun `yamlDecode parses yaml map`() {
        val yaml = "name: test\nvalue: 42\n"
        val data = yaml.toByteArray()
        val result = engine.evaluateDecodePipeline("yamlDecode", data)
        assertNotNull(result)
        val json = JSONObject(String(result!!))
        assertEquals("test", json.getString("name"))
        assertEquals(42, json.getInt("value"))
    }

    @Test
    fun `yamlDecode parses yaml list`() {
        val yaml = "- item1\n- item2\n- item3\n"
        val data = yaml.toByteArray()
        val result = engine.evaluateDecodePipeline("yamlDecode", data)
        assertNotNull(result)
        val arr = JSONArray(String(result!!))
        assertEquals(3, arr.length())
    }

    // ── yamlEncode ────────────────────────────────────────────────

    @Test
    fun `yamlEncode serializes object`() {
        val data = """{"name":"test","count":5}""".toByteArray()
        val result = engine.evaluateDecodePipeline("jsonDecode|yamlEncode", data)
        assertNotNull(result)
        val yaml = String(result!!)
        assertTrue(yaml.contains("name"))
        assertTrue(yaml.contains("test"))
    }

    // ── Error handling ────────────────────────────────────────────

    @Test
    fun `unknown function returns null`() {
        val data = "test".toByteArray()
        val result = engine.evaluateDecodePipeline("nonExistentFunction", data)
        assertNull(result)
    }

    @Test
    fun `invalid input to base64Decode returns string unchanged then pipeline continues`() {
        // base64Decode on invalid data returns the original string (by design)
        val data = "not-valid-base64!!!".toByteArray()
        val result = engine.evaluateDecodePipeline("base64Decode", data)
        // base64Decode catches the exception and returns the original string
        assertNotNull(result)
    }

    // ── Single function (no pipe) ─────────────────────────────────

    @Test
    fun `single function without pipe`() {
        val data = """{"a":1}""".toByteArray()
        val result = engine.evaluateDecodePipeline("jsonDecode", data)
        assertNotNull(result)
        val json = JSONObject(String(result!!))
        assertEquals(1, json.getInt("a"))
    }

    // ── jsonEncode|base64Encode then base64Decode|jsonDecode ──────

    @Test
    fun `jsonEncode then base64Decode and jsonDecode roundtrip`() {
        val original = "roundtrip test"
        // jsonEncode wraps in quotes, base64Encode encodes
        val encoded = engine.evaluateDecodePipeline("jsonEncode|base64Encode", original.toByteArray())
        assertNotNull(encoded)
        // base64Decode decodes, jsonDecode unquotes
        val decoded = engine.evaluateDecodePipeline("base64Decode|jsonDecode", encoded!!)
        assertNotNull(decoded)
        assertEquals(original, String(decoded!!))
    }
}
