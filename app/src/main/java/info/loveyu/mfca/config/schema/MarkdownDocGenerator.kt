package info.loveyu.mfca.config.schema

object MarkdownDocGenerator {

    fun generate(schema: ObjectNodeDef, title: String = "Config Schema"): String {
        return buildString {
            appendLine("# $title")
            appendLine()
            appendObjectContent(schema, headingLevel = 2)
        }.trimEnd()
    }

    /**
     * Renders [schema]'s children as:
     * 1. A summary table of all direct children
     * 2. Sub-sections (at [headingLevel]) for children that are objects/object-lists/object-maps
     */
    private fun StringBuilder.appendObjectContent(schema: ObjectNodeDef, headingLevel: Int) {
        if (schema.children.isEmpty()) return

        appendChildrenTable(schema.children)

        for (child in schema.children) {
            if (!hasSubsection(child)) continue
            appendLine()
            val h = "#".repeat(headingLevel.coerceAtMost(6))
            appendLine("$h `${child.name}`")
            appendLine()
            child.deprecated?.let { appendDeprecation(it) }
            child.description?.let {
                appendLine(it)
                appendLine()
            }
            appendSectionMeta(child)
            expandChild(child, (headingLevel + 1).coerceAtMost(6))
        }
    }

    private fun StringBuilder.expandChild(node: NodeDef, headingLevel: Int) {
        when (node) {
            is ObjectNodeDef -> {
                if (node.children.isNotEmpty()) {
                    appendLine()
                    appendObjectContent(node, headingLevel)
                }
            }
            is ListNodeDef -> {
                val item = node.itemSchema as? ObjectNodeDef ?: return
                if (item.children.isEmpty()) return
                appendLine()
                appendObjectContent(item, headingLevel)
            }
            is MapNodeDef -> {
                val value = node.valueSchema as? ObjectNodeDef ?: return
                if (value.children.isEmpty()) return
                appendLine()
                appendLine("*每个命名实例的属性如下：*")
                appendLine()
                appendObjectContent(value, headingLevel)
            }
            else -> {}
        }
    }

    private fun hasSubsection(node: NodeDef): Boolean =
        when (node) {
            is ObjectNodeDef -> node.children.isNotEmpty()
            is ListNodeDef -> (node.itemSchema as? ObjectNodeDef)?.children?.isNotEmpty() == true
            is MapNodeDef -> (node.valueSchema as? ObjectNodeDef)?.children?.isNotEmpty() == true
            else -> false
        }

    private fun StringBuilder.appendChildrenTable(children: List<NodeDef>) {
        appendLine("| 字段 | 类型 | 必填 | 默认值 | 说明 |")
        appendLine("|------|------|:----:|--------|------|")
        for (child in children) {
            val required = if (child.isRequired) "✓" else ""
            val default = defaultValue(child)?.let { "`$it`" } ?: ""
            val descParts = mutableListOf<String>()
            child.description?.replace("|", "\\|")?.replace("\n", " ")?.let { descParts += it }
            scalarConstraint(child)?.let { descParts += it }
            child.deprecated?.let { dep ->
                val note = buildString {
                    append("⚠️ *Deprecated")
                    dep.since?.let { append(" since $it") }
                    dep.replacement?.let { append(". Use `${dep.replacement}`") }
                    append("*")
                }
                descParts += note
            }
            val desc = descParts.joinToString(" ")
            appendLine("| `${child.name}` | ${typeLabel(child)} | $required | $default | $desc |")
        }
    }

    /** Inline constraint hint shown in the description column for scalar fields. */
    private fun scalarConstraint(node: NodeDef): String? =
        when (node) {
            is IntNodeDef -> node.min?.let { mn -> node.max?.let { mx -> "_(${mn}–${mx})_" } }
            is LongNodeDef -> node.min?.let { mn -> node.max?.let { mx -> "_(${mn}–${mx})_" } }
            is DoubleNodeDef -> node.min?.let { mn -> node.max?.let { mx -> "_(${mn}–${mx})_" } }
            is EnumNodeDef -> node.values.joinToString(" / ") { "`$it`" }
            is StringNodeDef -> node.allowedValues?.joinToString(" / ") { "`$it`" }
            else -> null
        }

    /** Meta bullet-list shown inside a complex node's own heading section. */
    private fun StringBuilder.appendSectionMeta(node: NodeDef) {
        val lines = mutableListOf<String>()
        lines += "**Type**: ${typeLabel(node)}"
        if (node.isRequired) lines += "**Required**: yes"
        when (node) {
            is ListNodeDef -> {
                node.minSize?.let { lines += "**Min items**: $it" }
                node.maxSize?.let { lines += "**Max items**: $it" }
            }
            else -> {}
        }
        lines.forEach { appendLine("- $it") }
        appendLine()
    }

    private fun StringBuilder.appendDeprecation(dep: DeprecatedInfo) {
        val line = buildString {
            append("> ⚠️ **Deprecated**")
            dep.since?.let { append(" since $it") }
            dep.message?.let { append(" — $it") }
            dep.replacement?.let { append(". Use `${dep.replacement}` instead.") }
        }
        appendLine(line)
        appendLine()
    }

    private fun defaultValue(node: NodeDef): String? =
        when (node) {
            is StringNodeDef -> node.default
            is IntNodeDef -> node.default?.toString()
            is LongNodeDef -> node.default?.toString()
            is DoubleNodeDef -> node.default?.toString()
            is BooleanNodeDef -> node.default?.toString()
            is DurationNodeDef -> node.default
            is EnumNodeDef -> node.default
            else -> null
        }

    private fun typeLabel(node: NodeDef): String =
        when (node) {
            is ObjectNodeDef -> "object"
            is StringNodeDef -> "string"
            is IntNodeDef -> "int"
            is LongNodeDef -> "long"
            is DoubleNodeDef -> "double"
            is BooleanNodeDef -> "boolean"
            is DurationNodeDef -> "duration"
            is EnumNodeDef -> "enum"
            is ListNodeDef -> "list[${typeLabel(node.itemSchema)}]"
            is MapNodeDef -> "map[${typeLabel(node.valueSchema)}]"
            is AnyNodeDef -> "any"
        }
}
