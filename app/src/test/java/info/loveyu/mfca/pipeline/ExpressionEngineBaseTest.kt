package info.loveyu.mfca.pipeline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before

/**
 * Base test class providing a shared ExpressionEngine instance
 * with android.util.Base64 replaced by java.util.Base64 for JUnit compatibility.
 */
abstract class ExpressionEngineBaseTest {

    protected lateinit var engine: ExpressionEngine

    @Before
    open fun setUp() {
        engine = ExpressionEngine()
        // Override base64 functions with java.util.Base64 for non-Android JUnit
        engine.registerCustomFunction(
            "base64Decode",
            ExpressionEngine.BuiltinFunction("base64Decode", 1) { args ->
                val str = args.getOrNull(0)?.toString() ?: ""
                try {
                    java.util.Base64.getDecoder().decode(str).toString(Charsets.UTF_8)
                } catch (e: Exception) {
                    str
                }
            }
        )
        engine.registerCustomFunction(
            "base64Encode",
            ExpressionEngine.BuiltinFunction("base64Encode", 1) { args ->
                val str = args.getOrNull(0)?.toString() ?: ""
                java.util.Base64.getEncoder()
                    .encodeToString(str.toByteArray(Charsets.UTF_8))
            }
        )
    }

    /** Helper: build a JSONObject from JSON string */
    protected fun json(s: String): JSONObject = JSONObject(s)

    /** Helper: build a JSONArray from JSON string */
    protected fun jsonArray(s: String): JSONArray = JSONArray(s)
}
