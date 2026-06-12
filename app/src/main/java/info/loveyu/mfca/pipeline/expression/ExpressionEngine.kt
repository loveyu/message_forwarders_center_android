package info.loveyu.mfca.pipeline.expression

import java.util.concurrent.ConcurrentHashMap

class ExpressionEngine {

    internal val builtinFunctions = ConcurrentHashMap<String, BuiltinFunction>()
    internal val rawDataFunctions = ConcurrentHashMap<String, RawDataFunction>()
    internal val compiledFilters = ConcurrentHashMap<String, CompiledFilter>()
    var deviceIdValue: String = ""
    internal var clipboardUpdateBeforeFn: ((String) -> Long)? = null

    init {
        registerBuiltinFunctions()
    }

    fun getBuiltinFunctions(): Map<String, BuiltinFunction> = builtinFunctions.toMap()

    fun registerCustomFunction(name: String, fn: BuiltinFunction) {
        builtinFunctions[name] = fn
    }

    fun registerRawDataFunction(name: String, fn: RawDataFunction) {
        rawDataFunctions[name] = fn
    }

    fun shutdown() {
        compiledFilters.clear()
    }
}
