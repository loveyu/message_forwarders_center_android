package info.loveyu.mfca.pipeline

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for GJSON path extraction: extractPath, extract, extractAndTransform,
 * evaluateExtractExpression, parseFunctionArgs.
 */
class ExpressionEngineExtractTest : ExpressionEngineBaseTest() {

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
}
