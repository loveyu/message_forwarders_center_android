package info.loveyu.mfca.config.schema

object MarkdownDocGenerator {

    fun generate(schema: ObjectNodeDef, title: String = "Config Schema"): String {
        return buildString {
            appendLine("# $title")
            appendLine()
            appendObjectChildren(schema, 2)
        }.trimEnd()
    }

    private fun StringBuilder.appendObjectChildren(schema: ObjectNodeDef, headingLevel: Int) {
        for (child in schema.children) {
            appendNode(child, schema.name, headingLevel)
        }
    }

    private fun StringBuilder.appendNode(node: NodeDef, parentPath: String, headingLevel: Int) {
        val path =
            when {
                parentPath == "<root>" -> node.name
                parentPath == "<item>" || parentPath == "<value>" -> node.name
                else -> "$parentPath.${node.name}"
            }
        val heading = "#".repeat(headingLevel)

        appendLine("$heading `${node.name}`")
        appendLine()

        node.description?.let {
            appendLine(it)
            appendLine()
        }

        node.deprecated?.let { dep ->
            val deprecationLine = buildString {
                append("> ⚠️ **Deprecated**")
                dep.since?.let { append(" since $it") }
                dep.message?.let { append(" — $it") }
                dep.replacement?.let { append(". Use `${dep.replacement}` instead.") }
            }
            appendLine(deprecationLine)
            appendLine()
        }

        appendNodeMeta(node)
        appendLine()

        when (node) {
            is ObjectNodeDef -> {
                if (node.children.isNotEmpty()) {
                    appendObjectChildren(
                        node.copy(name = path),
                        (headingLevel + 1).coerceAtMost(6),
                    )
                }
            }
            is ListNodeDef -> {
                val itemSchema = node.itemSchema
                if (itemSchema is ObjectNodeDef && itemSchema.children.isNotEmpty()) {
                    appendLine("**Item fields:**")
                    appendLine()
                    appendObjectChildren(
                        itemSchema.copy(name = "$path[]"),
                        (headingLevel + 1).coerceAtMost(6),
                    )
                }
            }
            is MapNodeDef -> {
                val valueSchema = node.valueSchema
                if (valueSchema is ObjectNodeDef && valueSchema.children.isNotEmpty()) {
                    appendLine("**Value fields:**")
                    appendLine()
                    appendObjectChildren(
                        valueSchema.copy(name = "$path.<key>"),
                        (headingLevel + 1).coerceAtMost(6),
                    )
                }
            }
            else -> {}
        }
    }

    private fun StringBuilder.appendNodeMeta(node: NodeDef) {
        val lines = mutableListOf<String>()

        lines += "**Type**: ${typeLabel(node)}"

        if (node.isRequired) {
            lines += "**Required**: yes"
        }

        when (node) {
            is StringNodeDef -> {
                node.default?.let { lines += "**Default**: `$it`" }
                node.minLength?.let { lines += "**Min length**: $it" }
                node.maxLength?.let { lines += "**Max length**: $it" }
                node.pattern?.let { lines += "**Pattern**: `${it.pattern}`" }
                node.allowedValues?.let { lines += "**Allowed**: ${it.joinToString(", ") { v -> "`$v`" }}" }
            }
            is IntNodeDef -> {
                node.default?.let { lines += "**Default**: `$it`" }
                node.min?.let { min -> node.max?.let { max -> lines += "**Range**: $min – $max" } }
            }
            is LongNodeDef -> {
                node.default?.let { lines += "**Default**: `$it`" }
                node.min?.let { min -> node.max?.let { max -> lines += "**Range**: $min – $max" } }
            }
            is DoubleNodeDef -> {
                node.default?.let { lines += "**Default**: `$it`" }
                node.min?.let { min -> node.max?.let { max -> lines += "**Range**: $min – $max" } }
            }
            is BooleanNodeDef -> {
                node.default?.let { lines += "**Default**: `$it`" }
            }
            is DurationNodeDef -> {
                node.default?.let { lines += "**Default**: `$it`" }
            }
            is EnumNodeDef -> {
                node.default?.let { lines += "**Default**: `$it`" }
                lines += "**Values**: ${node.values.joinToString(", ") { "`$it`" }}"
            }
            is ListNodeDef -> {
                node.minSize?.let { lines += "**Min items**: $it" }
                node.maxSize?.let { lines += "**Max items**: $it" }
            }
            else -> {}
        }

        lines.forEach { appendLine("- $it") }
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
            is ListNodeDef -> "list of ${typeLabel(node.itemSchema)}"
            is MapNodeDef -> "map of ${typeLabel(node.valueSchema)}"
            is AnyNodeDef -> "any"
        }
}
