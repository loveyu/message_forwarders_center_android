package info.loveyu.mfca.config.schema

data class DeprecatedInfo(
    val since: String? = null,
    val message: String? = null,
    val replacement: String? = null,
)

sealed class NodeDef {
    abstract val name: String
    abstract val description: String?
    abstract val isRequired: Boolean
    abstract val deprecated: DeprecatedInfo?
}

data class ObjectNodeDef(
    override val name: String,
    override val description: String? = null,
    override val isRequired: Boolean = false,
    override val deprecated: DeprecatedInfo? = null,
    val children: List<NodeDef> = emptyList(),
    val allowExtra: Boolean = true,
    /** If the YAML value is a scalar, wrap it into a map with this key. */
    val scalarKey: String? = null,
) : NodeDef()

data class StringNodeDef(
    override val name: String,
    override val description: String? = null,
    override val isRequired: Boolean = false,
    override val deprecated: DeprecatedInfo? = null,
    val default: String? = null,
    val pattern: Regex? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val allowedValues: List<String>? = null,
) : NodeDef()

data class IntNodeDef(
    override val name: String,
    override val description: String? = null,
    override val isRequired: Boolean = false,
    override val deprecated: DeprecatedInfo? = null,
    val default: Int? = null,
    val min: Int? = null,
    val max: Int? = null,
) : NodeDef()

data class LongNodeDef(
    override val name: String,
    override val description: String? = null,
    override val isRequired: Boolean = false,
    override val deprecated: DeprecatedInfo? = null,
    val default: Long? = null,
    val min: Long? = null,
    val max: Long? = null,
) : NodeDef()

data class DoubleNodeDef(
    override val name: String,
    override val description: String? = null,
    override val isRequired: Boolean = false,
    override val deprecated: DeprecatedInfo? = null,
    val default: Double? = null,
    val min: Double? = null,
    val max: Double? = null,
) : NodeDef()

data class BooleanNodeDef(
    override val name: String,
    override val description: String? = null,
    override val isRequired: Boolean = false,
    override val deprecated: DeprecatedInfo? = null,
    val default: Boolean? = null,
) : NodeDef()

data class DurationNodeDef(
    override val name: String,
    override val description: String? = null,
    override val isRequired: Boolean = false,
    override val deprecated: DeprecatedInfo? = null,
    val default: String? = null,
    val minMillis: Long? = null,
    val maxMillis: Long? = null,
) : NodeDef()

data class EnumNodeDef(
    override val name: String,
    override val description: String? = null,
    override val isRequired: Boolean = false,
    override val deprecated: DeprecatedInfo? = null,
    val default: String? = null,
    val values: List<String>,
) : NodeDef()

data class ListNodeDef(
    override val name: String,
    override val description: String? = null,
    override val isRequired: Boolean = false,
    override val deprecated: DeprecatedInfo? = null,
    val itemSchema: NodeDef,
    val minSize: Int? = null,
    val maxSize: Int? = null,
) : NodeDef()

data class MapNodeDef(
    override val name: String,
    override val description: String? = null,
    override val isRequired: Boolean = false,
    override val deprecated: DeprecatedInfo? = null,
    val valueSchema: NodeDef,
) : NodeDef()

/** Accepts any YAML value (string, number, boolean, list, map) without type enforcement. */
data class AnyNodeDef(
    override val name: String,
    override val description: String? = null,
    override val isRequired: Boolean = false,
    override val deprecated: DeprecatedInfo? = null,
) : NodeDef()

// ==================== DSL Builders ====================

class ObjectNodeBuilder(val name: String) {
    var description: String? = null
    var isRequired: Boolean = false
    var deprecated: DeprecatedInfo? = null
    var allowExtra: Boolean = true
    var scalarKey: String? = null
    val children = mutableListOf<NodeDef>()

    fun required() {
        isRequired = true
    }

    fun deprecated(
        since: String? = null,
        message: String? = null,
        replacement: String? = null,
    ) {
        deprecated = DeprecatedInfo(since, message, replacement)
    }

    fun string(name: String, block: StringNodeBuilder.() -> Unit = {}): StringNodeDef =
        StringNodeBuilder(name).apply(block).build().also { children += it }

    fun int(name: String, block: IntNodeBuilder.() -> Unit = {}): IntNodeDef =
        IntNodeBuilder(name).apply(block).build().also { children += it }

    fun long(name: String, block: LongNodeBuilder.() -> Unit = {}): LongNodeDef =
        LongNodeBuilder(name).apply(block).build().also { children += it }

    fun double(name: String, block: DoubleNodeBuilder.() -> Unit = {}): DoubleNodeDef =
        DoubleNodeBuilder(name).apply(block).build().also { children += it }

    fun boolean(name: String, block: BooleanNodeBuilder.() -> Unit = {}): BooleanNodeDef =
        BooleanNodeBuilder(name).apply(block).build().also { children += it }

    fun duration(name: String, block: DurationNodeBuilder.() -> Unit = {}): DurationNodeDef =
        DurationNodeBuilder(name).apply(block).build().also { children += it }

    fun enum(
        name: String,
        values: List<String>,
        block: EnumNodeBuilder.() -> Unit = {},
    ): EnumNodeDef = EnumNodeBuilder(name, values).apply(block).build().also { children += it }

    fun objectNode(name: String, block: ObjectNodeBuilder.() -> Unit = {}): ObjectNodeDef =
        ObjectNodeBuilder(name).apply(block).build().also { children += it }

    fun objectList(
        name: String,
        block: ListNodeBuilder.() -> Unit = {},
        itemBlock: ObjectNodeBuilder.() -> Unit,
    ): ListNodeDef {
        val itemSchema = ObjectNodeBuilder("<item>").apply(itemBlock).build()
        return ListNodeBuilder(name).apply(block).build(itemSchema).also { children += it }
    }

    fun stringList(name: String, block: ListNodeBuilder.() -> Unit = {}): ListNodeDef =
        ListNodeBuilder(name).apply(block).build(StringNodeDef("<item>")).also { children += it }

    fun objectMap(name: String, block: ObjectNodeBuilder.() -> Unit): MapNodeDef {
        val valueSchema = ObjectNodeBuilder("<value>").apply(block).build()
        return MapNodeDef(name, valueSchema = valueSchema).also { children += it }
    }

    fun any(name: String, block: AnyNodeBuilder.() -> Unit = {}): AnyNodeDef =
        AnyNodeBuilder(name).apply(block).build().also { children += it }

    fun build(): ObjectNodeDef =
        ObjectNodeDef(name, description, isRequired, deprecated, children.toList(), allowExtra, scalarKey)
}

class StringNodeBuilder(val name: String) {
    var description: String? = null
    var isRequired: Boolean = false
    var deprecated: DeprecatedInfo? = null
    var default: String? = null
    var pattern: Regex? = null
    var minLength: Int? = null
    var maxLength: Int? = null
    var allowedValues: List<String>? = null

    fun required() {
        isRequired = true
    }

    fun deprecated(
        since: String? = null,
        message: String? = null,
        replacement: String? = null,
    ) {
        deprecated = DeprecatedInfo(since, message, replacement)
    }

    fun build(): StringNodeDef =
        StringNodeDef(
            name,
            description,
            isRequired,
            deprecated,
            default,
            pattern,
            minLength,
            maxLength,
            allowedValues,
        )
}

class IntNodeBuilder(val name: String) {
    var description: String? = null
    var isRequired: Boolean = false
    var deprecated: DeprecatedInfo? = null
    var default: Int? = null
    var min: Int? = null
    var max: Int? = null

    fun required() {
        isRequired = true
    }

    fun deprecated(
        since: String? = null,
        message: String? = null,
        replacement: String? = null,
    ) {
        deprecated = DeprecatedInfo(since, message, replacement)
    }

    fun range(min: Int, max: Int) {
        this.min = min
        this.max = max
    }

    fun build(): IntNodeDef = IntNodeDef(name, description, isRequired, deprecated, default, min, max)
}

class LongNodeBuilder(val name: String) {
    var description: String? = null
    var isRequired: Boolean = false
    var deprecated: DeprecatedInfo? = null
    var default: Long? = null
    var min: Long? = null
    var max: Long? = null

    fun required() {
        isRequired = true
    }

    fun deprecated(
        since: String? = null,
        message: String? = null,
        replacement: String? = null,
    ) {
        deprecated = DeprecatedInfo(since, message, replacement)
    }

    fun range(min: Long, max: Long) {
        this.min = min
        this.max = max
    }

    fun build(): LongNodeDef =
        LongNodeDef(name, description, isRequired, deprecated, default, min, max)
}

class DoubleNodeBuilder(val name: String) {
    var description: String? = null
    var isRequired: Boolean = false
    var deprecated: DeprecatedInfo? = null
    var default: Double? = null
    var min: Double? = null
    var max: Double? = null

    fun required() {
        isRequired = true
    }

    fun deprecated(
        since: String? = null,
        message: String? = null,
        replacement: String? = null,
    ) {
        deprecated = DeprecatedInfo(since, message, replacement)
    }

    fun range(min: Double, max: Double) {
        this.min = min
        this.max = max
    }

    fun build(): DoubleNodeDef =
        DoubleNodeDef(name, description, isRequired, deprecated, default, min, max)
}

class BooleanNodeBuilder(val name: String) {
    var description: String? = null
    var isRequired: Boolean = false
    var deprecated: DeprecatedInfo? = null
    var default: Boolean? = null

    fun required() {
        isRequired = true
    }

    fun deprecated(
        since: String? = null,
        message: String? = null,
        replacement: String? = null,
    ) {
        deprecated = DeprecatedInfo(since, message, replacement)
    }

    fun build(): BooleanNodeDef =
        BooleanNodeDef(name, description, isRequired, deprecated, default)
}

class DurationNodeBuilder(val name: String) {
    var description: String? = null
    var isRequired: Boolean = false
    var deprecated: DeprecatedInfo? = null
    var default: String? = null
    var minMillis: Long? = null
    var maxMillis: Long? = null

    fun required() {
        isRequired = true
    }

    fun deprecated(
        since: String? = null,
        message: String? = null,
        replacement: String? = null,
    ) {
        deprecated = DeprecatedInfo(since, message, replacement)
    }

    fun build(): DurationNodeDef =
        DurationNodeDef(name, description, isRequired, deprecated, default, minMillis, maxMillis)
}

class EnumNodeBuilder(val name: String, val values: List<String>) {
    var description: String? = null
    var isRequired: Boolean = false
    var deprecated: DeprecatedInfo? = null
    var default: String? = null

    fun required() {
        isRequired = true
    }

    fun deprecated(
        since: String? = null,
        message: String? = null,
        replacement: String? = null,
    ) {
        deprecated = DeprecatedInfo(since, message, replacement)
    }

    fun build(): EnumNodeDef = EnumNodeDef(name, description, isRequired, deprecated, default, values)
}

class ListNodeBuilder(val name: String) {
    var description: String? = null
    var isRequired: Boolean = false
    var deprecated: DeprecatedInfo? = null
    var minSize: Int? = null
    var maxSize: Int? = null

    fun required() {
        isRequired = true
    }

    fun deprecated(
        since: String? = null,
        message: String? = null,
        replacement: String? = null,
    ) {
        deprecated = DeprecatedInfo(since, message, replacement)
    }

    fun build(itemSchema: NodeDef): ListNodeDef =
        ListNodeDef(name, description, isRequired, deprecated, itemSchema, minSize, maxSize)
}

class AnyNodeBuilder(val name: String) {
    var description: String? = null
    var isRequired: Boolean = false
    var deprecated: DeprecatedInfo? = null

    fun required() {
        isRequired = true
    }

    fun deprecated(
        since: String? = null,
        message: String? = null,
        replacement: String? = null,
    ) {
        deprecated = DeprecatedInfo(since, message, replacement)
    }

    fun build(): AnyNodeDef = AnyNodeDef(name, description, isRequired, deprecated)
}

/** Top-level DSL factory for creating a root schema. */
fun configSchema(name: String = "<root>", block: ObjectNodeBuilder.() -> Unit): ObjectNodeDef =
    ObjectNodeBuilder(name).apply(block).build()
