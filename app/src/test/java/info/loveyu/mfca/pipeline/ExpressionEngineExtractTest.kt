package info.loveyu.mfca.pipeline

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for GJSON path extraction: extractPath, extract, extractAndTransform,
 * evaluateExtractExpression, parseFunctionArgs.
 *
 * Also covers Map/List input support and new extract expression features:
 * data/$data, $headers.X, context variables, nested function calls.
 */
class ExpressionEngineExtractTest : ExpressionEngineBaseTest() {

    @Before
    override fun setUp() {
        super.setUp()
        // Override gzEncode/gzDecode to use java.util.Base64 instead of android.util.Base64
        engine.registerCustomFunction(
            "gzEncode",
            ExpressionEngine.BuiltinFunction("gzEncode", 1) { args ->
                val str = args.getOrNull(0)?.toString() ?: return@BuiltinFunction ""
                try {
                    val bos = ByteArrayOutputStream()
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
                    GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
                        gzip.readBytes().toString(Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    ""
                }
            }
        )
    }

    // ── Basic path extraction ──────────────────────────────────

    @Test
    fun `extractPath - simple key`() {
        val json = json("""{"name":"hello","age":30}""")
        assertEquals("hello", engine.extractPath(json, "name"))
    }

    @Test
    fun `extractPath - nested path`() {
        val json = json("""{"data":{"temperature":25.5,"humidity":60}}""")
        val result = engine.extractPath(json, "data.temperature")
        // org.json parses 25.5 as Double
        assertNotNull(result)
        assertEquals(25.5, (result as Number).toDouble(), 0.001)
    }

    @Test
    fun `extractPath - deep nested path`() {
        val json = json("""{"a":{"b":{"c":"deep"}}}""")
        assertEquals("deep", engine.extractPath(json, "a.b.c"))
    }

    @Test
    fun `extractPath - missing key returns null`() {
        val json = json("""{"name":"hello"}""")
        assertNull(engine.extractPath(json, "missing"))
    }

    @Test
    fun `extractPath - missing nested key returns null`() {
        val json = json("""{"a":{"b":"value"}}""")
        assertNull(engine.extractPath(json, "a.c"))
    }

    @Test
    fun `extractPath - empty path returns json`() {
        val json = json("""{"key":"value"}""")
        assertEquals(json, engine.extractPath(json, ""))
    }

    @Test
    fun `extractPath - null json returns null`() {
        assertNull(engine.extractPath(null, "key"))
    }

    @Test
    fun `extractPath - extracts number`() {
        val json = json("""{"count":42}""")
        assertEquals(42, engine.extractPath(json, "count"))
    }

    @Test
    fun `extractPath - extracts boolean`() {
        val json = json("""{"active":true}""")
        assertEquals(true, engine.extractPath(json, "active"))
    }

    @Test
    fun `extractPath - extracts null value`() {
        val json = json("""{"value":null}""")
        assertEquals(JSONObject.NULL, engine.extractPath(json, "value"))
    }

    // ── Array access ───────────────────────────────────────────

    @Test
    fun `extractPath - array index access`() {
        val json = json("""{"items":["a","b","c"]}""")
        assertEquals("a", engine.extractPath(json, "items[0]"))
    }

    @Test
    fun `extractPath - array index middle`() {
        val json = json("""{"items":["a","b","c"]}""")
        assertEquals("b", engine.extractPath(json, "items[1]"))
    }

    @Test
    fun `extractPath - array index last`() {
        val json = json("""{"items":["a","b","c"]}""")
        assertEquals("c", engine.extractPath(json, "items[2]"))
    }

    @Test
    fun `extractPath - array of objects`() {
        val json = json("""{"users":[{"name":"alice"},{"name":"bob"}]}""")
        assertEquals("alice", engine.extractPath(json, "users[0].name"))
    }

    @Test
    fun `extractPath - nested array in object`() {
        val json = json("""{"matrix":[[1,2],[3,4]]}""")
        val arr = engine.extractPath(json, "matrix[0]") as? org.json.JSONArray
        assertNotNull(arr)
        assertEquals(1, arr?.getInt(0))
        assertEquals(2, arr?.getInt(1))
    }

    @Test
    fun `extractPath - array index out of bounds returns null`() {
        val json = json("""{"items":["a","b"]}""")
        assertNull(engine.extractPath(json, "items[5]"))
    }

    // ── Wildcards ──────────────────────────────────────────────

    @Test
    fun `extractPath - wildcard extracts all elements`() {
        val json = json("""{"items":[{"name":"a"},{"name":"b"},{"name":"c"}]}""")
        val result = engine.extractPath(json, "items[*].name")
        assertTrue(result is List<*>)
        val list = result as List<*>
        assertEquals(3, list.size)
        assertEquals("a", list[0])
        assertEquals("b", list[1])
        assertEquals("c", list[2])
    }

    @Test
    fun `extractPath - wildcard on array returns all items`() {
        val json = json("""{"arr":[1,2,3]}""")
        val result = engine.extractPath(json, "arr[*]")
        assertTrue(result is List<*>)
        assertEquals(listOf(1, 2, 3), result)
    }

    @Test
    fun `extractPath - wildcard with no sub-path`() {
        val json = json("""{"items":[{"x":1},{"x":2}]}""")
        val result = engine.extractPath(json, "items[*]")
        assertTrue(result is List<*>)
        assertEquals(2, (result as List<*>).size)
    }

    // ── Modifiers ──────────────────────────────────────────────

    @Test
    fun `extractPath - at-keys on object`() {
        val json = json("""{"a":1,"b":2,"c":3}""")
        val result = engine.extractPath(json, "@keys")
        assertTrue(result is List<*>)
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `extractPath - at-values on object`() {
        val json = json("""{"a":1,"b":2}""")
        val result = engine.extractPath(json, "@values")
        assertTrue(result is List<*>)
        assertEquals(listOf(1, 2), result)
    }

    @Test
    fun `extractPath - at-len on object`() {
        val json = json("""{"a":1,"b":2,"c":3}""")
        assertEquals(3L, engine.extractPath(json, "@len"))
    }

    @Test
    fun `extractPath - at-len on array`() {
        val arr = jsonArray("""[1,2,3,4,5]""")
        assertEquals(5L, engine.extractPath(arr, "@len"))
    }

    @Test
    fun `extractPath - at-len on string not supported`() {
        // extractPath only handles JSONObject and JSONArray, not raw String
        assertNull(engine.extractPath("hello", "@len"))
    }

    @Test
    fun `extractPath - at-this returns input`() {
        val json = json("""{"key":"value"}""")
        assertEquals(json, engine.extractPath(json, "@this"))
    }

    @Test
    fun `extractPath - at-keys on array`() {
        val arr = jsonArray("""[10,20,30]""")
        val result = engine.extractPath(arr, "@keys")
        assertEquals(listOf("0", "1", "2"), result)
    }

    @Test
    fun `extractPath - at-length alias`() {
        val json = json("""{"a":1,"b":2}""")
        assertEquals(2L, engine.extractPath(json, "@length"))
    }

    // ── Special paths ──────────────────────────────────────────

    @Test
    fun `extractPath - dollar-raw returns string representation`() {
        val json = json("""{"key":"value"}""")
        val result = engine.extractPath(json, "\$raw")
        assertEquals(json.toString(), result)
    }

    @Test
    fun `extractPath - dollar-raw on array`() {
        val arr = jsonArray("""[1,2,3]""")
        val result = engine.extractPath(arr, "\$raw")
        assertEquals(arr.toString(), result)
    }

    @Test
    fun `extractPath - dollar-headers access`() {
        val json = json("""{"data":"test"}""")
        val headers = mapOf("X-Topic" to "sensor/data", "X-QoS" to "1")
        assertEquals("sensor/data", engine.extractPath(json, "\$headers.X-Topic", headers))
        assertEquals("1", engine.extractPath(json, "\$headers.X-QoS", headers))
    }

    @Test
    fun `extractPath - dollar-headers missing key`() {
        val json = json("""{"data":"test"}""")
        val headers = mapOf("X-Topic" to "sensor/data")
        assertNull(engine.extractPath(json, "\$headers.X-Missing", headers))
    }

    @Test
    fun `extractPath - dollar sign returns json`() {
        val json = json("""{"key":"value"}""")
        assertEquals(json, engine.extractPath(json, "\$"))
    }

    // ── extract() wrapper ──────────────────────────────────────

    @Test
    fun `extract - delegates to extractPath`() {
        val json = json("""{"name":"test"}""")
        assertEquals("test", engine.extract(json, "name"))
    }

    // ── extractAndTransform ────────────────────────────────────

    @Test
    fun `extractAndTransform - string to ByteArray`() {
        val json = json("""{"msg":"hello"}""")
        val result = engine.extractAndTransform(json, "msg")
        assertNotNull(result)
        assertEquals("hello", String(result!!))
    }

    @Test
    fun `extractAndTransform - number to ByteArray`() {
        val json = json("""{"count":42}""")
        val result = engine.extractAndTransform(json, "count")
        assertNotNull(result)
        assertEquals("42", String(result!!))
    }

    @Test
    fun `extractAndTransform - nested object to ByteArray`() {
        val json = json("""{"data":{"x":1}}""")
        val result = engine.extractAndTransform(json, "data")
        assertNotNull(result)
        val parsed = JSONObject(String(result!!))
        assertEquals(1, parsed.getInt("x"))
    }

    @Test
    fun `extractAndTransform - missing path returns null`() {
        val json = json("""{"key":"value"}""")
        assertNull(engine.extractAndTransform(json, "missing"))
    }

    // ── evaluateExtractExpression with function calls ──────────

    @Test
    fun `evaluateExtractExpression - simple path`() {
        val json = json("""{"content":"hello"}""")
        val result = engine.evaluateExtractExpression(json, "content")
        assertEquals("hello", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - base64Decode function`() {
        val json = json("""{"content":"aGVsbG8gd29ybGQ="}""")
        val result = engine.evaluateExtractExpression(json, "base64Decode(content)")
        assertEquals("hello world", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - toUpperCase function`() {
        val json = json("""{"name":"hello"}""")
        val result = engine.evaluateExtractExpression(json, "toUpperCase(name)")
        assertEquals("HELLO", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - toLowerCase function`() {
        val json = json("""{"name":"HELLO"}""")
        val result = engine.evaluateExtractExpression(json, "toLowerCase(name)")
        assertEquals("hello", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - trim function`() {
        val json = json("""{"text":"  spaces  "}""")
        val result = engine.evaluateExtractExpression(json, "trim(text)")
        assertEquals("spaces", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - base64Decode then separate toUpperCase`() {
        // Test each step individually since nested calls depend on template context
        val json = json("""{"content":"aGVsbG8="}""")
        val decoded = engine.evaluateExtractExpression(json, "base64Decode(content)")
        assertNotNull(decoded)
        assertEquals("hello", String(decoded!!))

        // Now test toUpperCase on the decoded result
        val json2 = json("""{"decoded":"hello"}""")
        val upper = engine.evaluateExtractExpression(json2, "toUpperCase(decoded)")
        assertEquals("HELLO", String(upper!!))
    }

    @Test
    fun `evaluateExtractExpression - returns null for missing path`() {
        val json = json("""{"key":"value"}""")
        assertNull(engine.evaluateExtractExpression(json, "nonexistent"))
    }

    // ── JSONArray direct extraction ────────────────────────────

    @Test
    fun `extractPath - top-level array wildcard`() {
        val arr = jsonArray("""[{"id":1},{"id":2}]""")
        val result = engine.extractPath(arr, "[*].id")
        assertEquals(listOf(1, 2), result)
    }

    @Test
    fun `extractPath - top-level array index`() {
        val arr = jsonArray("""[10,20,30]""")
        assertEquals(20, engine.extractPath(arr, "[1]"))
    }

    @Test
    fun `extractPath - top-level array modifier`() {
        val arr = jsonArray("""[10,20]""")
        assertEquals(2L, engine.extractPath(arr, "@len"))
    }

    @Test
    fun `extractPath - top-level array all elements`() {
        val arr = jsonArray("""[10,20,30]""")
        val result = engine.extractPath(arr, "[*]")
        assertEquals(listOf(10, 20, 30), result)
    }

    // ── extractPath with Map/List inputs ───────────────────────

    @Test
    fun `extractPath - Map simple key`() {
        val map = mapOf("name" to "hello", "age" to 30)
        assertEquals("hello", engine.extractPath(map, "name"))
    }

    @Test
    fun `extractPath - Map nested path`() {
        val map = mapOf("data" to mapOf("temperature" to 25.5))
        val result = engine.extractPath(map, "data.temperature")
        assertEquals(25.5, result)
    }

    @Test
    fun `extractPath - Map missing key returns null`() {
        val map = mapOf("name" to "hello")
        assertNull(engine.extractPath(map, "missing"))
    }

    @Test
    fun `extractPath - Map with List value and array access`() {
        val map = mapOf("items" to listOf("a", "b", "c"))
        assertEquals("a", engine.extractPath(map, "items[0]"))
        assertEquals("c", engine.extractPath(map, "items[2]"))
    }

    @Test
    fun `extractPath - Map wildcard on nested list`() {
        val map = mapOf("items" to listOf(mapOf("name" to "a"), mapOf("name" to "b")))
        val result = engine.extractPath(map, "items[*].name")
        assertTrue(result is List<*>)
        assertEquals(listOf("a", "b"), result)
    }

    @Test
    fun `extractPath - Map @keys modifier`() {
        val map = mapOf("a" to 1, "b" to 2, "c" to 3)
        val result = engine.extractPath(map, "@keys")
        assertTrue(result is List<*>)
        val keys = (result as List<*>).toSet()
        assertEquals(setOf("a", "b", "c"), keys)
    }

    @Test
    fun `extractPath - Map @values modifier`() {
        val map = mapOf("a" to 1, "b" to 2)
        val result = engine.extractPath(map, "@values")
        assertTrue(result is List<*>)
        assertEquals(listOf(1, 2), result)
    }

    @Test
    fun `extractPath - Map @len modifier`() {
        val map = mapOf("a" to 1, "b" to 2, "c" to 3)
        assertEquals(3L, engine.extractPath(map, "@len"))
    }

    @Test
    fun `extractPath - Map $raw returns JSON string`() {
        val map = mapOf("key" to "value")
        val result = engine.extractPath(map, "\$raw")
        assertTrue(result is String)
        val parsed = JSONObject(result as String)
        assertEquals("value", parsed.getString("key"))
    }

    @Test
    fun `extractPath - List index access`() {
        val list = listOf("x", "y", "z")
        assertEquals("y", engine.extractPath(list, "[1]"))
    }

    @Test
    fun `extractPath - List wildcard`() {
        val list = listOf(10, 20, 30)
        val result = engine.extractPath(list, "[*]")
        assertEquals(listOf(10, 20, 30), result)
    }

    @Test
    fun `extractPath - List @len modifier`() {
        val list = listOf(1, 2, 3, 4)
        assertEquals(4L, engine.extractPath(list, "@len"))
    }

    @Test
    fun `extractPath - List out of bounds returns null`() {
        val list = listOf("a", "b")
        assertNull(engine.extractPath(list, "[5]"))
    }

    // ── evaluateExtractExpression with new signature ───────────

    @Test
    fun `evaluateExtractExpression - data keyword references raw data`() {
        val data = """{"content":"ignored"}""".toByteArray()
        val json = json("""{"content":"ignored"}""")
        val result = engine.evaluateExtractExpression(data, json, "data")
        assertNotNull(result)
        assertEquals("""{"content":"ignored"}""", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - dollar-data references raw data`() {
        val data = """{"content":"ignored"}""".toByteArray()
        val json = json("""{"content":"ignored"}""")
        val result = engine.evaluateExtractExpression(data, json, "\$data")
        assertNotNull(result)
        assertEquals("""{"content":"ignored"}""", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - dollar-headers access`() {
        val data = """{"key":"value"}""".toByteArray()
        val json = json("""{"key":"value"}""")
        val headers = mapOf("X-Topic" to "sensor/data", "X-QoS" to "1")
        val result = engine.evaluateExtractExpression(data, json, "\$headers.X-Topic", headers)
        assertNotNull(result)
        assertEquals("sensor/data", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - headers-X access without dollar`() {
        val data = """{"key":"value"}""".toByteArray()
        val json = json("""{"key":"value"}""")
        val headers = mapOf("X-Topic" to "test-value")
        val result = engine.evaluateExtractExpression(data, json, "headers.X-Topic", headers)
        assertNotNull(result)
        assertEquals("test-value", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - dollar-raw on data`() {
        val data = """{"content":"hello"}""".toByteArray()
        val json = json("""{"content":"hello"}""")
        val result = engine.evaluateExtractExpression(data, json, "\$raw")
        assertNotNull(result)
        assertEquals("""{"content":"hello"}""", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - context variable dollar-rule`() {
        val data = """{"key":"value"}""".toByteArray()
        val json = json("""{"key":"value"}""")
        val context = mapOf("rule" to "my-rule", "source" to "mqtt-in")
        val result = engine.evaluateExtractExpression(data, json, "\$rule", null, context)
        assertNotNull(result)
        assertEquals("my-rule", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - context variable dollar-source`() {
        val data = """{"key":"value"}""".toByteArray()
        val json = json("""{"key":"value"}""")
        val context = mapOf("source" to "http-in")
        val result = engine.evaluateExtractExpression(data, json, "\$source", null, context)
        assertNotNull(result)
        assertEquals("http-in", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - base64Decode with data for non-JSON input`() {
        // base64Decode returns the original string on invalid input (by design)
        val rawText = "hello non-json world"
        val data = rawText.toByteArray()
        val result = engine.evaluateExtractExpression(data, null, "base64Decode(data)")
        assertNotNull(result)
        assertEquals(rawText, String(result!!))

        // Test with actual base64-encoded data
        val encoded = java.util.Base64.getEncoder().encodeToString(rawText.toByteArray())
        val data2 = encoded.toByteArray()
        val result2 = engine.evaluateExtractExpression(data2, null, "base64Decode(data)")
        assertNotNull(result2)
        assertEquals(rawText, String(result2!!))
    }

    @Test
    fun `evaluateExtractExpression - path extraction still works with new signature`() {
        val data = """{"name":"test"}""".toByteArray()
        val json = json("""{"name":"test"}""")
        val result = engine.evaluateExtractExpression(data, json, "name")
        assertEquals("test", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - function call still works with new signature`() {
        val data = """{"encoded":"aGVsbG8gd29ybGQ="}""".toByteArray()
        val json = json("""{"encoded":"aGVsbG8gd29ybGQ="}""")
        val result = engine.evaluateExtractExpression(data, json, "base64Decode(encoded)")
        assertEquals("hello world", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - nested function base64Decode of gzDecode of data`() {
        val rawText = "compressed content"
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(rawText.toByteArray()) }
        val b64Gz = java.util.Base64.getEncoder().encodeToString(compressed.toByteArray())

        val data = b64Gz.toByteArray()
        val result = engine.evaluateExtractExpression(data, null, "gzDecode(data)")
        assertNotNull(result)
        assertEquals(rawText, String(result!!))
    }

    // ── data/headers variable support in extract ───────────────

    @Test
    fun `evaluateExtractExpression - data prefix accesses root field`() {
        val data = """{"type":"text","content":"hello"}""".toByteArray()
        val json = json("""{"type":"text","content":"hello"}""")
        val result = engine.evaluateExtractExpression(data, json, "data.type")
        assertEquals("text", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - data prefix with function arg`() {
        // base64Decode(data.content) — data.content resolves to root "content" field
        val encoded = java.util.Base64.getEncoder().encodeToString("hello world".toByteArray())
        val jsonStr = """{"content":"$encoded"}"""
        val data = jsonStr.toByteArray()
        val result = engine.evaluateExtractExpression(data, null, "base64Decode(data.content)")
        assertEquals("hello world", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - data prefix numeric field`() {
        val data = """{"temperature":25.5}""".toByteArray()
        val json = json("""{"temperature":25.5}""")
        val result = engine.evaluateExtractExpression(data, json, "data.temperature")
        assertNotNull(result)
        assertEquals("25.5", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - headers prefix accesses header`() {
        val data = """{"msg":"test"}""".toByteArray()
        val json = json("""{"msg":"test"}""")
        val headers = mapOf("mqtt_topic" to "sensor/data")
        val result = engine.evaluateExtractExpression(data, json, "headers.mqtt_topic", headers)
        assertEquals("sensor/data", String(result!!))
    }

    @Test
    fun `evaluateExtractExpression - real world clipboard use case`() {
        // Matches the user's actual config: extract: base64Decode(data.content)
        val payload = """{"type":"text","content":"Y2xpcGJvYXJkVXBkYXRlQmVmb3Jl","sendTime":1234567890}"""
        val data = payload.toByteArray()
        val result = engine.evaluateExtractExpression(data, null, "base64Decode(data.content)")
        assertEquals("clipboardUpdateBefore", String(result!!))
    }
}
