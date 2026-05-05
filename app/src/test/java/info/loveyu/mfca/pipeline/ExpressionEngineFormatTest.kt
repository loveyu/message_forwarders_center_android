package info.loveyu.mfca.pipeline

import info.loveyu.mfca.config.OutputFormatStep
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for template formatting: evaluateFormatTemplate, resolveFormatExpression,
 * applyFormatSteps, resolveFormatArg, parseJsonValueOrString, removeJsonPath.
 */
class ExpressionEngineFormatTest : ExpressionEngineBaseTest() {

    // ── evaluateFormatTemplate - basic placeholders ────────────

    @Test
    fun `formatTemplate - literal text without placeholders`() {
        val data = """{"key":"value"}""".toByteArray()
        val result = engine.evaluateFormatTemplate("fixed text", data, emptyMap())
        assertEquals("fixed text", String(result))
    }

    @Test
    fun `formatTemplate - data placeholder`() {
        val data = """{"key":"value"}""".toByteArray()
        val result = engine.evaluateFormatTemplate("{data}", data, emptyMap())
        assertEquals("""{"key":"value"}""", String(result))
    }

    @Test
    fun `formatTemplate - headers placeholder`() {
        val data = "test".toByteArray()
        val headers = mapOf("X-Topic" to "sensor", "X-QoS" to "1")
        val result = engine.evaluateFormatTemplate("{headers}", data, headers)
        val parsed = JSONObject(String(result))
        assertEquals("sensor", parsed.getString("X-Topic"))
        assertEquals("1", parsed.getString("X-QoS"))
    }

    @Test
    fun `formatTemplate - headers dot access`() {
        val data = """{"key":"value"}""".toByteArray()
        val headers = mapOf("X-Topic" to "my/topic")
        val result = engine.evaluateFormatTemplate("{\$headers.X-Topic}", data, headers)
        assertEquals("my/topic", String(result))
    }

    @Test
    fun `formatTemplate - headers dot access without dollar`() {
        val data = """{"key":"value"}""".toByteArray()
        val headers = mapOf("X-Topic" to "my/topic")
        val result = engine.evaluateFormatTemplate("{headers.X-Topic}", data, headers)
        assertEquals("my/topic", String(result))
    }

    @Test
    fun `formatTemplate - mixed text and placeholder`() {
        val data = """{"name":"Alice"}""".toByteArray()
        val result = engine.evaluateFormatTemplate("Hello, {name}!", data, emptyMap())
        assertEquals("Hello, Alice!", String(result))
    }

    @Test
    fun `formatTemplate - multiple placeholders`() {
        val data = """{"first":"John","last":"Doe"}""".toByteArray()
        val result = engine.evaluateFormatTemplate("{first} {last}", data, emptyMap())
        assertEquals("John Doe", String(result))
    }

    @Test
    fun `formatTemplate - nested json path`() {
        val data = """{"temperature":25}""".toByteArray()
        val result = engine.evaluateFormatTemplate("Temp: {data.temperature}", data, emptyMap())
        assertEquals("Temp: 25", String(result))
    }

    // ── Context variables ──────────────────────────────────────

    @Test
    fun `formatTemplate - dollar-rule context`() {
        val data = "test".toByteArray()
        val context = mapOf("rule" to "myRule")
        val result = engine.evaluateFormatTemplate("{\$rule}", data, emptyMap(), context)
        assertEquals("myRule", String(result))
    }

    @Test
    fun `formatTemplate - dollar-source context`() {
        val data = "test".toByteArray()
        val context = mapOf("source" to "mqtt_input")
        val result = engine.evaluateFormatTemplate("{\$source}", data, emptyMap(), context)
        assertEquals("mqtt_input", String(result))
    }

    @Test
    fun `formatTemplate - dollar-receivedAt context`() {
        val data = "test".toByteArray()
        val context = mapOf("receivedAt" to "1704067200000")
        val result = engine.evaluateFormatTemplate("{\$receivedAt}", data, emptyMap(), context)
        assertEquals("1704067200000", String(result))
    }

    // ── Function calls in template ─────────────────────────────

    @Test
    fun `formatTemplate - function call now`() {
        val data = """{"dummy":1}""".toByteArray()
        val result = engine.evaluateFormatTemplate("{now()}", data, emptyMap())
        val ts = String(result).toLongOrNull()
        assertNotNull(ts)
        assertTrue(ts!! > 0)
    }

    @Test
    fun `formatTemplate - function call toUpperCase`() {
        val data = """{"name":"alice"}""".toByteArray()
        val result = engine.evaluateFormatTemplate("{toUpperCase(name)}", data, emptyMap())
        assertEquals("ALICE", String(result))
    }

    @Test
    fun `formatTemplate - nested function in template`() {
        // Test each function separately since nested function resolution
        // depends on the template parser's handling of inner parentheses
        val data = """{"text":"  hello  "}""".toByteArray()
        // Single-level function call works
        val result = engine.evaluateFormatTemplate("{trim(text)}", data, emptyMap())
        assertEquals("hello", String(result))
    }

    @Test
    fun `formatTemplate - single function toUpperCase`() {
        val data = """{"text":"hello"}""".toByteArray()
        val result = engine.evaluateFormatTemplate("{toUpperCase(text)}", data, emptyMap())
        assertEquals("HELLO", String(result))
    }

    @Test
    fun `formatTemplate - function with path args`() {
        val data = """{"text":"hello world","target":"world","replacement":"kotlin"}""".toByteArray()
        val result = engine.evaluateFormatTemplate("{replace(text, target, replacement)}", data, emptyMap())
        assertEquals("hello kotlin", String(result))
    }

    @Test
    fun `formatTemplate - base64Encode in template`() {
        val data = """{"text":"hello"}""".toByteArray()
        val result = engine.evaluateFormatTemplate("{base64Encode(text)}", data, emptyMap())
        assertEquals("aGVsbG8=", String(result))
    }

    // ── Escaped braces ─────────────────────────────────────────

    @Test
    fun `formatTemplate - escaped double brace`() {
        val data = "test".toByteArray()
        // {{ is escaped to {, then "literal}" is treated as a placeholder expression
        // Actually {{literal}} → first {{ → {, then "literal}" → placeholder "literal}"
        // Since there's no closing } for the literal, the behavior depends on the template parser.
        // Let's test a simpler case: {{} -> {
        val result = engine.evaluateFormatTemplate("{{", data, emptyMap())
        assertEquals("{", String(result))
    }

    @Test
    fun `formatTemplate - mixed escaped and placeholder`() {
        val data = """{"name":"world"}""".toByteArray()
        // {{ → literal {, then the template continues
        val result = engine.evaluateFormatTemplate("prefix {{ {name}", data, emptyMap())
        // "prefix " + "{" + " " + resolve("name") → "prefix { world"
        assertEquals("prefix { world", String(result))
    }

    // ── Unclosed brace ─────────────────────────────────────────

    @Test
    fun `formatTemplate - unclosed brace preserved as-is`() {
        val data = "test".toByteArray()
        val result = engine.evaluateFormatTemplate("hello {world", data, emptyMap())
        assertEquals("hello {world", String(result))
    }

    // ── Non-JSON data in template ──────────────────────────────

    @Test
    fun `formatTemplate - non-JSON data returns as-is for data placeholder`() {
        val data = "plain text".toByteArray()
        val result = engine.evaluateFormatTemplate("{data}", data, emptyMap())
        assertEquals("plain text", String(result))
    }

    @Test
    fun `formatTemplate - function works with non-JSON data`() {
        val data = "plain text".toByteArray()
        // toUpperCase() with no args returns "" since args[0] is null
        // Use a zero-arg function like now() or uuidv4() instead
        val result = engine.evaluateFormatTemplate("{now()}", data, emptyMap())
        assertTrue(String(result).toLongOrNull() != null)
    }

    // ── applyFormatSteps ───────────────────────────────────────

    @Test
    fun `formatSteps - dollar-data replaces entire data`() {
        val data = """{"key":"value"}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$data", template = "replaced content")
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        assertEquals("replaced content", String(newData))
    }

    @Test
    fun `formatSteps - dollar-data with template expression`() {
        val data = """{"name":"Alice"}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$data", template = "Hello, {name}!")
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        assertEquals("Hello, Alice!", String(newData))
    }

    @Test
    fun `formatSteps - dollar-data-field adds field`() {
        val data = """{"name":"Alice"}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$data.age", template = "30")
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertEquals("Alice", json.getString("name"))
        assertEquals(30, json.getInt("age"))
    }

    @Test
    fun `formatSteps - dollar-data-field overwrites existing field`() {
        val data = """{"name":"Alice","age":25}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$data.age", template = "30")
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertEquals(30, json.getInt("age"))
    }

    @Test
    fun `formatSteps - dollar-data-field with json value`() {
        val data = """{"name":"test"}""".toByteArray()
        // Template that produces a JSON string from existing data
        val steps = listOf(
            OutputFormatStep(target = "\$data.meta", template = "{data}")
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        val meta = json.getJSONObject("meta")
        assertEquals("test", meta.getString("name"))
    }

    @Test
    fun `formatSteps - dollar-data-field with boolean value`() {
        val data = """{"name":"test"}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$data.active", template = "true")
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertTrue(json.getBoolean("active"))
    }

    @Test
    fun `formatSteps - dollar-data-field with null value`() {
        val data = """{"name":"test"}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$data.removed", template = "null")
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertTrue(json.isNull("removed"))
    }

    @Test
    fun `formatSteps - dollar-header sets single header`() {
        val data = "test".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$header.X-Custom", template = "my-value")
        )
        val (_, newHeaders) = engine.applyFormatSteps(steps, data, emptyMap())
        assertEquals("my-value", newHeaders["X-Custom"])
    }

    @Test
    fun `formatSteps - dollar-header preserves existing headers`() {
        val data = "test".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$header.X-New", template = "new-value")
        )
        val (_, newHeaders) = engine.applyFormatSteps(
            steps,
            data,
            mapOf("X-Existing" to "old")
        )
        assertEquals("old", newHeaders["X-Existing"])
        assertEquals("new-value", newHeaders["X-New"])
    }

    @Test
    fun `formatSteps - dollar-header replaces all headers`() {
        val data = """{"h1":"v1","h2":"v2"}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$header", template = "{data}")
        )
        val (_, newHeaders) = engine.applyFormatSteps(
            steps,
            data,
            mapOf("X-Old" to "old")
        )
        assertEquals("v1", newHeaders["h1"])
        assertEquals("v2", newHeaders["h2"])
        assertFalse(newHeaders.containsKey("X-Old"))
    }

    @Test
    fun `formatSteps - multiple steps in sequence`() {
        val data = """{"name":"Alice"}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$data.greeting", template = "Hello, {name}!"),
            OutputFormatStep(target = "\$header.X-Greeting", template = "Hello, {name}!")
        )
        val (newData, newHeaders) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertEquals("Alice", json.getString("name"))
        assertEquals("Hello, Alice!", json.getString("greeting"))
        assertEquals("Hello, Alice!", newHeaders["X-Greeting"])
    }

    @Test
    fun `formatSteps - dollar-data with template referencing headers`() {
        val data = """{"msg":"test"}""".toByteArray()
        val headers = mapOf("X-Topic" to "sensor/data")
        val steps = listOf(
            OutputFormatStep(
                target = "\$data",
                template = "topic={\$headers.X-Topic}&msg={msg}"
            )
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, headers)
        assertEquals("topic=sensor/data&msg=test", String(newData))
    }

    // ── $delete operations ─────────────────────────────────────

    @Test
    fun `formatSteps - delete single field from data`() {
        val data = """{"name":"Alice","password":"secret","age":30}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(
                target = "\$data",
                template = "",
                raw = mapOf("delete" to listOf("password"))
            )
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertEquals("Alice", json.getString("name"))
        assertEquals(30, json.getInt("age"))
        assertFalse(json.has("password"))
    }

    @Test
    fun `formatSteps - delete multiple fields from data`() {
        val data = """{"a":1,"b":2,"c":3,"d":4}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(
                target = "\$data",
                template = "",
                raw = mapOf("delete" to listOf("b", "d"))
            )
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertEquals(1, json.getInt("a"))
        assertEquals(3, json.getInt("c"))
        assertFalse(json.has("b"))
        assertFalse(json.has("d"))
    }

    @Test
    fun `formatSteps - delete nested field`() {
        val data = """{"user":{"name":"Alice","secret":"pass","details":{"email":"a@b.c","token":"xyz"}}}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(
                target = "\$data",
                template = "",
                raw = mapOf("delete" to listOf("user.secret", "user.details.token"))
            )
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertFalse(json.getJSONObject("user").has("secret"))
        assertFalse(json.getJSONObject("user").getJSONObject("details").has("token"))
        assertEquals("Alice", json.getJSONObject("user").getString("name"))
    }

    @Test
    fun `formatSteps - delete from headers`() {
        val data = """{"key":"value"}""".toByteArray()
        val headers = mapOf("X-Keep" to "yes", "X-Remove" to "no")
        val steps = listOf(
            OutputFormatStep(
                target = "\$header",
                template = "",
                raw = mapOf("delete" to listOf("X-Remove"))
            )
        )
        val (_, newHeaders) = engine.applyFormatSteps(steps, data, headers)
        assertEquals("yes", newHeaders["X-Keep"])
        assertFalse(newHeaders.containsKey("X-Remove"))
    }

    @Test
    fun `formatSteps - delete target alias dollar-delete`() {
        val data = """{"a":1,"b":2,"c":3}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(
                target = "\$delete",
                template = "",
                raw = mapOf("delete" to listOf("b"))
            )
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertFalse(json.has("b"))
        assertEquals(1, json.getInt("a"))
    }

    @Test
    fun `formatSteps - delete with list shorthand`() {
        val data = """{"a":1,"b":2,"c":3}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(
                target = "\$delete",
                template = "",
                raw = listOf("a", "c")
            )
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertFalse(json.has("a"))
        assertEquals(2, json.getInt("b"))
        assertFalse(json.has("c"))
    }

    @Test
    fun `formatSteps - delete with comma separated string`() {
        val data = """{"a":1,"b":2,"c":3}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(
                target = "\$data",
                template = "",
                raw = mapOf("delete" to "a, c")
            )
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertFalse(json.has("a"))
        assertFalse(json.has("c"))
        assertEquals(2, json.getInt("b"))
    }

    // ── formatSteps with context variables ─────────────────────

    @Test
    fun `formatSteps - context variables in template`() {
        val data = """{"msg":"hello"}""".toByteArray()
        val context = mapOf("rule" to "testRule", "source" to "mqtt")
        val steps = listOf(
            OutputFormatStep(
                target = "\$data",
                template = "rule={\$rule}&source={\$source}&msg={msg}"
            )
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap(), context)
        assertEquals("rule=testRule&source=mqtt&msg=hello", String(newData))
    }

    @Test
    fun `formatSteps - dollar-data-field with template expression`() {
        val data = """{"msg":"hello"}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$data.ts", template = "{now()}")
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertTrue(json.has("ts"))
        assertTrue(json.getLong("ts") > 0)
    }

    // ── preParsedJson overload ─────────────────────────────────

    @Test
    fun `formatTemplate - preParsedJson overload`() {
        val json = JSONObject("""{"name":"Bob"}""")
        val data = """{"name":"Bob"}""".toByteArray()
        val result = engine.evaluateFormatTemplate(
            "Hello, {name}",
            data,
            json,
            emptyMap()
        )
        assertEquals("Hello, Bob", String(result))
    }

    @Test
    fun `formatTemplate - non-JSON data with preParsedJson null`() {
        val data = "plain text".toByteArray()
        val result = engine.evaluateFormatTemplate("{data}", data, null, emptyMap())
        assertEquals("plain text", String(result))
    }

    // ── delete non-existing key is safe ────────────────────────

    @Test
    fun `formatSteps - delete non-existing key is no-op`() {
        val data = """{"a":1}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(
                target = "\$data",
                template = "",
                raw = mapOf("delete" to listOf("nonexistent"))
            )
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertEquals(1, json.getInt("a"))
        assertFalse(json.has("nonexistent"))
    }

    // ── dollar-data-field on non-JSON wraps as object ──────────

    @Test
    fun `formatSteps - dollar-data-field on non-JSON wraps as object`() {
        val data = "plain text".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$data.content", template = "plain text")
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertEquals("plain text", json.getString("content"))
    }

    // ── dollar-data-field with number value ────────────────────

    @Test
    fun `formatSteps - dollar-data-field stores number correctly`() {
        val data = """{"name":"test"}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$data.count", template = "42")
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertEquals(42, json.getInt("count"))
    }

    // ── dollar-data-field with array value ─────────────────────

    @Test
    fun `formatSteps - dollar-data-field stores array correctly`() {
        val data = """{"name":"test"}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$data.items", template = "[1,2,3]")
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        val arr = json.getJSONArray("items")
        assertEquals(3, arr.length())
        assertEquals(1, arr.getInt(0))
    }

    // ── Nested / recursive function calls in format templates ───

    @Test
    fun `nested - jsonEncode of base64Encode data`() {
        val data = "hello world".toByteArray()
        val result = engine.evaluateFormatTemplate("{jsonEncode(base64Encode(data))}", data, emptyMap())
        val s = String(result)
        // base64Encode("hello world") = "aGVsbG8gd29ybGQ="
        // jsonEncode returns quoted string: "aGVsbG8gd29ybGQ="
        assertEquals(""""aGVsbG8gd29ybGQ="""", s)
    }

    @Test
    fun `nested - jsonEncode of base64Encode with special chars`() {
        val data = """he said "hello"""".toByteArray()
        val result = engine.evaluateFormatTemplate("{jsonEncode(base64Encode(data))}", data, emptyMap())
        val s = String(result)
        // Should be a quoted string (base64 of the input)
        assertTrue(s.startsWith("\""))
        assertTrue(s.endsWith("\""))
    }

    @Test
    fun `nested - base64Encode of data via format template`() {
        val data = "hello".toByteArray()
        val result = engine.evaluateFormatTemplate("{base64Encode(data)}", data, emptyMap())
        assertEquals("aGVsbG8=", String(result))
    }

    @Test
    fun `nested - urlEncode of base64Encode data`() {
        val data = "hello world".toByteArray()
        val result = engine.evaluateFormatTemplate("{urlEncode(base64Encode(data))}", data, emptyMap())
        val s = String(result)
        // base64Encode produces "aGVsbG8gd29ybGQ=", urlEncode should encode the = sign
        assertTrue(s.contains("aGVsbG8gd29ybGQ"))
    }

    @Test
    fun `nested - toUpperCase of trim via format template`() {
        val data = """{"name":"  hello  "}""".toByteArray()
        val result = engine.evaluateFormatTemplate("{toUpperCase(trim(name))}", data, emptyMap())
        assertEquals("HELLO", String(result))
    }

    @Test
    fun `nested - jsonEncode of toUpperCase data`() {
        val data = """{"msg":"hello"}""".toByteArray()
        val result = engine.evaluateFormatTemplate("{jsonEncode(toUpperCase(msg))}", data, emptyMap())
        val s = String(result)
        assertEquals(""""HELLO"""", s)
    }

    @Test
    fun `nested - base64Encode of jsonEncode data`() {
        val data = """hello""".toByteArray()
        val result = engine.evaluateFormatTemplate("{base64Encode(jsonEncode(data))}", data, emptyMap())
        val s = String(result)
        // jsonEncode("hello") = "\"hello\"" (a quoted string)
        // base64Encode of that quoted string
        val expected = java.util.Base64.getEncoder()
            .encodeToString(""""hello""""
                .toByteArray(Charsets.UTF_8))
        assertEquals(expected, s)
    }

    @Test
    fun `nested - jsonEncode with non-JSON data`() {
        val data = "plain text".toByteArray()
        val result = engine.evaluateFormatTemplate("{jsonEncode(data)}", data, emptyMap())
        val s = String(result)
        assertEquals(""""plain text"""", s)
    }

    @Test
    fun `nested - base64Encode with non-JSON data`() {
        val data = "plain text".toByteArray()
        val result = engine.evaluateFormatTemplate("{base64Encode(data)}", data, emptyMap())
        assertEquals("cGxhaW4gdGV4dA==", String(result))
    }

    @Test
    fun `nested - jsonEncode of base64Encode non-JSON data`() {
        val data = "plain text".toByteArray()
        val result = engine.evaluateFormatTemplate("{jsonEncode(base64Encode(data))}", data, emptyMap())
        val s = String(result)
        // base64Encode("plain text") = "cGxhaW4gdGV4dA=="
        // jsonEncode returns: "cGxhaW4gdGV4dA=="
        assertEquals(""""cGxhaW4gdGV4dA=="""", s)
    }

    @Test
    fun `nested - triple nesting jsonEncode urlEncode base64Encode`() {
        val data = "hello".toByteArray()
        val result = engine.evaluateFormatTemplate("{jsonEncode(urlEncode(base64Encode(data)))}", data, emptyMap())
        val s = String(result)
        // base64Encode("hello") = "aGVsbG8="
        // urlEncode("aGVsbG8=") = "aGVsbG8%3D"
        // jsonEncode("aGVsbG8%3D") = "\"aGVsbG8%3D\""
        assertTrue(s.startsWith("\""))
        assertTrue(s.contains("aGVsbG8"))
        assertTrue(s.endsWith("\""))
    }

    // ── jsonEncode in JSON template context (with {{ escaping) ──

    @Test
    fun `jsonEncode in json template with double brace escape`() {
        val data = "hello world".toByteArray()
        val template = """{{"payload":{jsonEncode(base64Encode(data))}}}"""
        val result = engine.evaluateFormatTemplate(template, data, emptyMap())
        val s = String(result)
        // Should produce valid JSON: {"payload":"aGVsbG8gd29ybGQ="}
        val parsed = JSONObject(s)
        assertEquals("aGVsbG8gd29ybGQ=", parsed.getString("payload"))
    }

    @Test
    fun `jsonEncode special chars in json template`() {
        val data = """he said "hello"""".toByteArray()
        val template = """{{"message":{jsonEncode(data)}}}"""
        val result = engine.evaluateFormatTemplate(template, data, emptyMap())
        val s = String(result)
        // Should produce valid JSON with escaped quotes
        val parsed = JSONObject(s)
        assertEquals("""he said "hello"""", parsed.getString("message"))
    }

    // ── Functions with literal number args in format templates ──

    @Test
    fun `now with literal 3 in format template`() {
        val data = "test".toByteArray()
        val result = engine.evaluateFormatTemplate("{now(3)}", data, emptyMap())
        val s = String(result)
        assertTrue(s.contains("."))
        assertEquals(3, s.split(".")[1].length)
    }

    @Test
    fun `randStr with literal 8 in format template`() {
        val data = "test".toByteArray()
        val result = engine.evaluateFormatTemplate("{randStr(8)}", data, emptyMap())
        val s = String(result)
        assertEquals(8, s.length)
        assertTrue(s.all { it.isLetterOrDigit() })
    }

    @Test
    fun `msToSec with literal number args in format template`() {
        val data = "test".toByteArray()
        val result = engine.evaluateFormatTemplate("{msToSec(12345, 6)}", data, emptyMap())
        val s = String(result)
        assertTrue(s.startsWith("12.345"))
        assertEquals(6, s.split(".")[1].length)
    }
}
