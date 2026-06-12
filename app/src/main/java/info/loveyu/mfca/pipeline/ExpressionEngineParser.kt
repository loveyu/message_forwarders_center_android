package info.loveyu.mfca.pipeline

internal fun parseExpression(tokens: List<FilterToken>): ParsedFilter {
    if (tokens.isEmpty()) return ParsedFilter(ParsedNodeType.CONSTANT, true)

    val result = parseOr(tokens, mutableListOf())
    return result.first
}

internal fun parseOr(tokens: List<FilterToken>, accumulated: MutableList<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
    var (left, remaining) = parseAnd(tokens, accumulated)

    while (remaining.isNotEmpty() && remaining.first().type == FilterTokenType.LOGICAL && remaining.first().value == "||") {
        remaining = remaining.drop(1)
        val (right, newRemaining) = parseAnd(remaining, mutableListOf())
        left = ParsedFilter(type = ParsedNodeType.OR, left = left, right = right)
        remaining = newRemaining
    }

    return Pair(left, remaining)
}

internal fun parseAnd(tokens: List<FilterToken>, accumulated: MutableList<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
    var (left, remaining) = parsePrimary(tokens, accumulated)

    while (remaining.isNotEmpty() && remaining.first().type == FilterTokenType.LOGICAL && remaining.first().value == "&&") {
        remaining = remaining.drop(1)
        val (right, newRemaining) = parsePrimary(remaining, mutableListOf())
        left = ParsedFilter(type = ParsedNodeType.AND, left = left, right = right)
        remaining = newRemaining
    }

    return Pair(left, remaining)
}

internal fun parsePrimary(tokens: List<FilterToken>, accumulated: MutableList<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
    if (tokens.isEmpty()) return Pair(ParsedFilter(ParsedNodeType.CONSTANT, true), emptyList())

    val token = tokens.first()
    val remaining = tokens.drop(1)

    return when {
        token.type == FilterTokenType.PAREN && token.value == "(" -> {
            val (expr, rest) = parseOr(remaining, mutableListOf())
            if (rest.isNotEmpty() && rest.first().type == FilterTokenType.PAREN && rest.first().value == ")") {
                Pair(expr, rest.drop(1))
            } else {
                Pair(expr, remaining)
            }
        }
        token.type == FilterTokenType.NOT -> {
            val (operand, rest) = parsePrimary(remaining, mutableListOf())
            Pair(ParsedFilter(ParsedNodeType.NOT, left = operand), rest)
        }
        token.type == FilterTokenType.IDENT -> {
            parseComparison(token.value, remaining)
        }
        token.type == FilterTokenType.NUMBER -> {
            parseLiteralComparison(token.value, remaining)
        }
        token.type == FilterTokenType.STRING -> {
            parseLiteralComparison(token.value, remaining)
        }
        token.type == FilterTokenType.BOOLEAN -> {
            Pair(ParsedFilter(ParsedNodeType.CONSTANT, token.value.toBoolean()), remaining)
        }
        else -> Pair(ParsedFilter(ParsedNodeType.CONSTANT, true), remaining)
    }
}

internal fun parseComparison(path: String, tokens: List<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
    if (tokens.isEmpty()) {
        return Pair(ParsedFilter(ParsedNodeType.PATH, path = path), tokens)
    }

    val op = tokens.first()
    if (op.type == FilterTokenType.OPERATOR) {
        val remaining = tokens.drop(1)
        if (remaining.isNotEmpty()) {
            val value = remaining.first()
            val valueStr = when (value.type) {
                FilterTokenType.STRING -> value.value
                FilterTokenType.NUMBER -> value.value
                FilterTokenType.BOOLEAN -> value.value
                FilterTokenType.IDENT -> value.value
                else -> value.value
            }
            return Pair(
                ParsedFilter(
                    ParsedNodeType.COMPARISON,
                    path = path,
                    operator = op.value,
                    value = valueStr
                ),
                remaining.drop(1)
            )
        }
    }

    if (tokens.isNotEmpty() && tokens.first().type == FilterTokenType.PAREN && tokens.first().value == "(") {
        return parseFunctionCall(path, tokens)
    }

    return Pair(ParsedFilter(ParsedNodeType.PATH, path = path), tokens)
}

internal fun parseLiteralComparison(literalValue: String, tokens: List<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
    if (tokens.isEmpty()) {
        val numVal = literalValue.toDoubleOrNull()
        return if (numVal != null) {
            Pair(ParsedFilter(ParsedNodeType.CONSTANT, numVal != 0.0), tokens)
        } else {
            Pair(ParsedFilter(ParsedNodeType.CONSTANT, literalValue.isNotEmpty()), tokens)
        }
    }

    val op = tokens.first()
    if (op.type == FilterTokenType.OPERATOR) {
        val remaining = tokens.drop(1)
        if (remaining.isNotEmpty()) {
            val value = remaining.first()
            val valueStr = when (value.type) {
                FilterTokenType.STRING -> value.value
                FilterTokenType.NUMBER -> value.value
                FilterTokenType.BOOLEAN -> value.value
                FilterTokenType.IDENT -> value.value
                else -> value.value
            }
            return Pair(
                ParsedFilter(
                    ParsedNodeType.LITERAL_COMPARISON,
                    path = literalValue,
                    operator = op.value,
                    value = valueStr
                ),
                remaining.drop(1)
            )
        }
    }

    val numVal = literalValue.toDoubleOrNull()
    return if (numVal != null) {
        Pair(ParsedFilter(ParsedNodeType.CONSTANT, numVal != 0.0), tokens)
    } else {
        Pair(ParsedFilter(ParsedNodeType.CONSTANT, literalValue.isNotEmpty()), tokens)
    }
}

internal fun parseFunctionCall(name: String, tokens: List<FilterToken>): Pair<ParsedFilter, List<FilterToken>> {
    var remaining = tokens.drop(1)
    val args = mutableListOf<String>()

    while (remaining.isNotEmpty() && !(remaining.first().type == FilterTokenType.PAREN && remaining.first().value == ")")) {
        if (remaining.first().type == FilterTokenType.IDENT) {
            args.add(remaining.first().value)
        } else if (remaining.first().type == FilterTokenType.STRING || remaining.first().type == FilterTokenType.NUMBER) {
            args.add(remaining.first().value)
        }
        remaining = remaining.drop(1)
        if (remaining.isNotEmpty() && remaining.first().type == FilterTokenType.COMMA) {
            remaining = remaining.drop(1)
        }
    }

    if (remaining.isNotEmpty() && remaining.first().type == FilterTokenType.PAREN && remaining.first().value == ")") {
        remaining = remaining.drop(1)
    }

    return Pair(
        ParsedFilter(
            ParsedNodeType.FUNCTION,
            path = name,
            args = args
        ),
        remaining
    )
}
