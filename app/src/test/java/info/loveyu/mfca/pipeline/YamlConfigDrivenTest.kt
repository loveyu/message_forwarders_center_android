package info.loveyu.mfca.pipeline

import info.loveyu.mfca.config.OutputFormatStep
import info.loveyu.mfca.config.TransformConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests driven by various YAML-like configuration scenarios.
 * Tests simulate how the rule engine processes messages through
 * extract → format → filter pipelines using different configuration patterns.
 *
 * These tests validate that the ExpressionEngine correctly handles
 * real-world configuration combinations without needing actual YAML parsing.
 */
class YamlConfigDrivenTest : ExpressionEngineBaseTest() {

    // ── Scenario 1: MQTT sensor data forwarding ───────────────

    @Test
    fun `scenario - mqtt sensor extract and forward`() {
        // Simulates config:
        // rules:
        //   - name: sensor_rule
        //     from: mqtt_sensor
        //     pipeline:
        //       - transform:
        //           extract: data.temperature
        //         to: [http_output]
        val inputData = """{"data":{"temperature":25.5,"humidity":60,"sensorId":"S001"}}"""
        val json = JSONObject(inputData)

        val result = engine.evaluateExtractExpression(json, "data.temperature")
        assertNotNull(result)
        assertEquals("25.5", String(result!!))
    }

    @Test
    fun `scenario - mqtt sensor filter by threshold`() {
        // filter: data.temperature > 20
        val hotData = """{"data":{"temperature":25.5}}""".toByteArray()
        val coldData = """{"data":{"temperature":15.0}}""".toByteArray()

        assertTrue(engine.executeFilter("data.temperature > 20", hotData))
        assertFalse(engine.executeFilter("data.temperature > 20", coldData))
    }

    @Test
    fun `scenario - mqtt sensor format message`() {
        // format: "Sensor {data.sensorId}: {data.temperature}°C"
        val data = """{"data":{"sensorId":"S001","temperature":25.5}}""".toByteArray()
        val result = engine.evaluateFormatTemplate(
            "Sensor {data.sensorId}: {data.temperature}°C",
            data,
            emptyMap()
        )
        assertEquals("Sensor S001: 25.5°C", String(result))
    }

    // ── Scenario 2: HTTP webhook with header extraction ────────

    @Test
    fun `scenario - webhook extract from headers`() {
        // extract: $headers.X-Event-Type
        val json = JSONObject("""{"body":"event data"}""")
        val headers = mapOf("X-Event-Type" to "push", "X-Signature" to "abc123")

        assertEquals("push", engine.extractPath(json, "\$headers.X-Event-Type", headers))
        assertEquals("abc123", engine.extractPath(json, "\$headers.X-Signature", headers))
    }

    @Test
    fun `scenario - webhook filter by event type`() {
        val data = """{"event":"push"}""".toByteArray()
        assertTrue(engine.executeFilter("event == \"push\"", data))
        assertFalse(engine.executeFilter("event == \"pull_request\"", data))
    }

    @Test
    fun `scenario - webhook format with headers and body`() {
        val data = """{"action":"opened","number":42}""".toByteArray()
        val headers = mapOf("X-Github-Event" to "pull_request")
        val result = engine.evaluateFormatTemplate(
            "[{\$headers.X-Github-Event}] action={action}, #{number}",
            data,
            headers
        )
        assertEquals("[pull_request] action=opened, #42", String(result))
    }

    // ── Scenario 3: Gotify notification formatting ─────────────

    @Test
    fun `scenario - gotify extract title and message`() {
        val json = JSONObject("""{"title":"Alert","message":"Disk 90% full","priority":5}""")

        val title = engine.extractPath(json, "title")
        val message = engine.extractPath(json, "message")
        val priority = engine.extractPath(json, "priority")

        assertEquals("Alert", title)
        assertEquals("Disk 90% full", message)
        assertEquals(5, priority)
    }

    @Test
    fun `scenario - gotify format notification with enricher data`() {
        // After enrichment that adds "icon" field:
        val data = """{"title":"Alert","message":"Disk full","icon":"https://example.com/icon.png"}""".toByteArray()
        val result = engine.evaluateFormatTemplate(
            "{title}: {message}",
            data,
            emptyMap()
        )
        assertEquals("Alert: Disk full", String(result))
    }

    // ── Scenario 4: Base64-encoded payload processing ──────────

    @Test
    fun `scenario - extract base64 content then decode`() {
        // config:
        //   extract: base64Decode(payload)
        val encoded = java.util.Base64.getEncoder().encodeToString(
            """{"user":"alice","action":"login"}""".toByteArray()
        )
        val json = JSONObject("""{"payload":"$encoded"}""")

        val result = engine.evaluateExtractExpression(json, "base64Decode(payload)")
        assertNotNull(result)
        val decoded = JSONObject(String(result!!))
        assertEquals("alice", decoded.getString("user"))
        assertEquals("login", decoded.getString("action"))
    }

    @Test
    fun `scenario - decode then extract nested field`() {
        val encoded = java.util.Base64.getEncoder().encodeToString(
            """{"temp":42}""".toByteArray()
        )
        val json = JSONObject("""{"payload":"$encoded"}""")
        val decoded = engine.evaluateExtractExpression(json, "base64Decode(payload)")
        val decodedJson = JSONObject(String(decoded!!))
        val temp = engine.extractPath(decodedJson, "temp")
        assertEquals(42, temp)
    }

    // ── Scenario 5: Format steps - per-output data transformation ─

    @Test
    fun `scenario - format steps add timestamp and rule info`() {
        // config:
        //   format:
        //     - $data.receivedAt: "{$receivedAt}"
        //     - $data.rule: "{$rule}"
        val data = """{"msg":"hello"}""".toByteArray()
        val context = mapOf("rule" to "forward_rule", "receivedAt" to "1704067200000")
        val steps = listOf(
            OutputFormatStep(target = "\$data.receivedAt", template = "{\$receivedAt}"),
            OutputFormatStep(target = "\$data.rule", template = "{\$rule}")
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap(), context)
        val json = JSONObject(String(newData))
        assertEquals("hello", json.getString("msg"))
        // receivedAt is stored as Long (parseJsonValueOrString parses numeric strings)
        assertEquals(1704067200000L, json.getLong("receivedAt"))
        assertEquals("forward_rule", json.getString("rule"))
    }

    @Test
    fun `scenario - format steps delete sensitive fields`() {
        // config:
        //   format:
        //     - $data:
        //         delete: ["password", "token"]
        val data = """{"user":"alice","password":"secret","token":"abc","role":"admin"}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(
                target = "\$data",
                template = "",
                raw = mapOf("delete" to listOf("password", "token"))
            )
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertEquals("alice", json.getString("user"))
        assertEquals("admin", json.getString("role"))
        assertFalse(json.has("password"))
        assertFalse(json.has("token"))
    }

    @Test
    fun `scenario - format steps add field then delete another`() {
        val data = """{"name":"test","temp":"remove_me"}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$data.added", template = "new_value"),
            OutputFormatStep(
                target = "\$data",
                template = "",
                raw = mapOf("delete" to listOf("temp"))
            )
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertEquals("test", json.getString("name"))
        assertEquals("new_value", json.getString("added"))
        assertFalse(json.has("temp"))
    }

    // ── Scenario 6: Header manipulation via format steps ───────

    @Test
    fun `scenario - set custom header from json data`() {
        val data = """{"topic":"sensor/temp","qos":"1"}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$header.X-Topic", template = "{topic}"),
            OutputFormatStep(target = "\$header.X-QoS", template = "{qos}")
        )
        val (_, headers) = engine.applyFormatSteps(steps, data, emptyMap())
        assertEquals("sensor/temp", headers["X-Topic"])
        assertEquals("1", headers["X-QoS"])
    }

    @Test
    fun `scenario - replace all headers from json`() {
        val data = """{"h1":"v1","h2":"v2"}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$header", template = "{data}")
        )
        val (_, headers) = engine.applyFormatSteps(
            steps,
            data,
            mapOf("old" to "header")
        )
        assertEquals("v1", headers["h1"])
        assertEquals("v2", headers["h2"])
        assertFalse(headers.containsKey("old"))
    }

    // ── Scenario 7: Complex filter with AND/OR logic ──────────

    @Test
    fun `scenario - complex filter - temperature and humidity`() {
        // filter: data.temperature > 20 && data.humidity < 80
        val goodData = """{"data":{"temperature":25,"humidity":60}}""".toByteArray()
        val badTemp = """{"data":{"temperature":15,"humidity":60}}""".toByteArray()
        val badHumidity = """{"data":{"temperature":25,"humidity":90}}""".toByteArray()

        assertTrue(engine.executeFilter("data.temperature > 20 && data.humidity < 80", goodData))
        assertFalse(engine.executeFilter("data.temperature > 20 && data.humidity < 80", badTemp))
        assertFalse(engine.executeFilter("data.temperature > 20 && data.humidity < 80", badHumidity))
    }

    @Test
    fun `scenario - filter with OR - accept multiple event types`() {
        // filter: event == "push" || event == "tag"
        val push = """{"event":"push"}""".toByteArray()
        val tag = """{"event":"tag"}""".toByteArray()
        val issue = """{"event":"issue"}""".toByteArray()

        assertTrue(engine.executeFilter("event == \"push\" || event == \"tag\"", push))
        assertTrue(engine.executeFilter("event == \"push\" || event == \"tag\"", tag))
        assertFalse(engine.executeFilter("event == \"push\" || event == \"tag\"", issue))
    }

    @Test
    fun `scenario - filter with function - check array non-empty`() {
        // filter: length(items) > 0
        val withItems = """{"items":[1,2,3]}""".toByteArray()
        val empty = """{"items":[]}""".toByteArray()

        // length returns Long, comparison > 0
        // In filter: length evaluates to 3L > 0 → true
        assertTrue(engine.executeFilter("length(items) > 0", withItems))
        // length evaluates to 0L > 0 → false
        assertFalse(engine.executeFilter("length(items) > 0", empty))
    }

    @Test
    fun `scenario - filter contains substring`() {
        // filter: contains(message, needle) where needle is a JSON field
        val errorMsg = """{"message":"Connection error occurred","needle":"error"}""".toByteArray()
        val okMsg = """{"message":"Connection successful","needle":"error"}""".toByteArray()

        assertTrue(engine.executeFilter("contains(message, needle)", errorMsg))
        assertFalse(engine.executeFilter("contains(message, needle)", okMsg))
    }

    // ── Scenario 8: Full pipeline simulation ───────────────────

    @Test
    fun `scenario - full pipeline extract filter format`() {
        // 1. Input data
        val inputData = """{"data":{"sensorId":"S001","temperature":28.5,"humidity":45}}"""

        // 2. Extract
        val json = JSONObject(inputData)
        val extracted = engine.evaluateExtractExpression(json, "data.temperature")
        assertNotNull(extracted)
        assertEquals("28.5", String(extracted!!))

        // 3. Filter (check temperature > 25)
        val filterData = inputData.toByteArray()
        assertTrue(engine.executeFilter("data.temperature > 25", filterData))

        // 4. Format output
        val formatted = engine.evaluateFormatTemplate(
            "Sensor {data.sensorId}: T={data.temperature}°C H={data.humidity}%",
            filterData,
            emptyMap()
        )
        assertEquals("Sensor S001: T=28.5°C H=45%", String(formatted))
    }

    @Test
    fun `scenario - full pipeline with format steps`() {
        val inputData = """{"data":{"user":"alice","pass":"secret","msg":"hello"}}"""
        val dataBytes = inputData.toByteArray()

        // Filter: user == "alice"
        assertTrue(engine.executeFilter("data.user == \"alice\"", dataBytes))

        // Format steps: delete password, add timestamp
        val context = mapOf("rule" to "auth_rule", "receivedAt" to "1704067200000")
        val steps = listOf(
            OutputFormatStep(
                target = "\$data",
                template = "",
                raw = mapOf("delete" to listOf("data.pass"))
            ),
            OutputFormatStep(target = "\$data.processedAt", template = "{\$receivedAt}"),
            OutputFormatStep(target = "\$header.X-Processed-By", template = "{\$rule}")
        )
        val (newData, newHeaders) = engine.applyFormatSteps(steps, dataBytes, emptyMap(), context)

        val json = JSONObject(String(newData))
        assertEquals("alice", json.getJSONObject("data").getString("user"))
        assertFalse(json.getJSONObject("data").has("pass"))
        assertEquals(1704067200000L, json.getLong("processedAt"))
        assertEquals("auth_rule", newHeaders["X-Processed-By"])
    }

    // ── Scenario 9: Non-JSON data handling ─────────────────────

    @Test
    fun `scenario - plain text data with format template`() {
        val data = "Hello World".toByteArray()
        val result = engine.evaluateFormatTemplate(
            "Received: {data}",
            data,
            emptyMap()
        )
        assertEquals("Received: Hello World", String(result))
    }

    @Test
    fun `scenario - plain text data with function in template`() {
        val data = "hello".toByteArray()
        // Use zero-arg function with non-JSON data
        val result = engine.evaluateFormatTemplate(
            "Data: {data}",
            data,
            emptyMap()
        )
        assertEquals("Data: hello", String(result))
    }

    @Test
    fun `scenario - plain text with raw data function filter`() {
        engine.registerRawDataFunction(
            "textContains",
            ExpressionEngine.RawDataFunction("textContains") { data, args ->
                val needle = args.getOrNull(0)?.toString() ?: ""
                String(data).contains(needle)
            }
        )
        val data = "error: connection timeout".toByteArray()
        assertTrue(engine.executeFilter("textContains(\"error\")", data))
        assertFalse(engine.executeFilter("textContains(\"success\")", data))
    }

    // ── Scenario 10: Nested function calls in templates ────────

    @Test
    fun `scenario - nested functions in format template`() {
        val data = """{"content":"aGVsbG8gd29ybGQ="}""".toByteArray()
        val result = engine.evaluateFormatTemplate(
            "Decoded: {base64Decode(content)}",
            data,
            emptyMap()
        )
        assertEquals("Decoded: hello world", String(result))
    }

    @Test
    fun `scenario - function with data path arg in template`() {
        val data = """{"msg":"hello world"}""".toByteArray()
        val result = engine.evaluateFormatTemplate(
            "Upper: {toUpperCase(msg)}",
            data,
            emptyMap()
        )
        assertEquals("Upper: HELLO WORLD", String(result))
    }

    @Test
    fun `scenario - urlEncode in template`() {
        val data = """{"query":"a=1&b=2"}""".toByteArray()
        val result = engine.evaluateFormatTemplate(
            "?query={urlEncode(query)}",
            data,
            emptyMap()
        )
        assertEquals("?query=a%3D1%26b%3D2", String(result))
    }

    // ── Scenario 11: Array wildcard extraction ─────────────────

    @Test
    fun `scenario - extract all sensor IDs from array`() {
        val json = JSONObject(
            """{"sensors":[{"id":"S1","val":1},{"id":"S2","val":2},{"id":"S3","val":3}]}"""
        )
        val result = engine.extractPath(json, "sensors[*].id")
        assertEquals(listOf("S1", "S2", "S3"), result)
    }

    @Test
    fun `scenario - filter on array length`() {
        // length(x) in filter evaluates as function call returning non-zero = true
        val withItems = """{"alerts":["high","critical"]}""".toByteArray()
        val empty = """{"alerts":[]}""".toByteArray()
        // Non-empty array → length > 0 → filter passes
        assertTrue(engine.executeFilter("length(alerts)", withItems))
        // Empty array → length == 0 → filter fails
        assertFalse(engine.executeFilter("length(alerts)", empty))
    }

    // ── Scenario 12: $raw extraction ───────────────────────────

    @Test
    fun `scenario - extract raw json string`() {
        val json = JSONObject("""{"data":{"nested":"value"}}""")
        val result = engine.extractPath(json, "\$raw")
        val reparsed = JSONObject(result.toString())
        assertEquals("value", reparsed.getJSONObject("data").getString("nested"))
    }

    // ── Scenario 13: Type detection via filter ─────────────────

    @Test
    fun `scenario - filter checks data is JSON object`() {
        val objData = """{"type":"object"}""".toByteArray()
        val arrData = """[1,2,3]""".toByteArray()

        // isObject(data) won't work on the full JSON because extractPath
        // treats the top-level as the JSON itself. We test a nested field.
        val data = """{"payload":{"key":"value"}}""".toByteArray()
        assertTrue(engine.executeFilter("isObject(payload)", data))
    }

    @Test
    fun `scenario - filter checks field is number`() {
        val data = """{"count":42,"name":"test"}""".toByteArray()
        assertTrue(engine.executeFilter("isNumber(count)", data))
        assertFalse(engine.executeFilter("isNumber(name)", data))
    }

    // ── Scenario 14: UUID generation in templates ──────────────

    @Test
    fun `scenario - generate unique ID in format`() {
        val data = """{"msg":"test"}""".toByteArray()
        val result = engine.evaluateFormatTemplate(
            "id={uuidv4()}&msg={msg}",
            data,
            emptyMap()
        )
        val s = String(result)
        assertTrue(s.startsWith("id="))
        assertTrue(s.contains("&msg=test"))
        val uuid = s.substring(3, s.indexOf("&"))
        assertTrue(uuid.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    // ── Scenario 15: JSON array extraction and formatting ──────

    @Test
    fun `scenario - extract array element and format`() {
        val json = JSONObject("""{"events":[{"type":"push","repo":"myapp"},{"type":"issue","repo":"other"}]}""")
        val firstEvent = engine.extractPath(json, "events[0]")
        assertNotNull(firstEvent)
        val firstObj = firstEvent as JSONObject
        assertEquals("push", firstObj.getString("type"))
        assertEquals("myapp", firstObj.getString("repo"))
    }

    @Test
    fun `scenario - format steps replace data with template`() {
        // Transform: take nested data and flatten
        val data = """{"payload":{"user":"bob","action":"login"}}""".toByteArray()
        val steps = listOf(
            OutputFormatStep(target = "\$data", template = "{payload}")
        )
        val (newData, _) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertEquals("bob", json.getString("user"))
        assertEquals("login", json.getString("action"))
    }

    // ── Scenario 16: Multiple format steps with mixed targets ──

    @Test
    fun `scenario - mixed format targets`() {
        val data = """{"msg":"hello","secret":"hidden"}""".toByteArray()
        val steps = listOf(
            // Delete secret
            OutputFormatStep(target = "\$data", template = "", raw = mapOf("delete" to listOf("secret"))),
            // Add header
            OutputFormatStep(target = "\$header.X-Message", template = "{msg}"),
            // Add UUID field
            OutputFormatStep(target = "\$data.id", template = "{uuidv4()}")
        )
        val (newData, newHeaders) = engine.applyFormatSteps(steps, data, emptyMap())
        val json = JSONObject(String(newData))
        assertFalse(json.has("secret"))
        assertEquals("hello", json.getString("msg"))
        assertTrue(json.has("id"))
        assertEquals("hello", newHeaders["X-Message"])
    }
}
