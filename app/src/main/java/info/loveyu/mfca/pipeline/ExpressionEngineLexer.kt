package info.loveyu.mfca.pipeline

internal fun tokenize(expr: String): List<FilterToken> {
    val tokens = mutableListOf<FilterToken>()
    var i = 0

    while (i < expr.length) {
        val c = expr[i]

        when {
            c.isWhitespace() -> i++
            c == '(' || c == ')' -> {
                tokens.add(FilterToken(FilterTokenType.PAREN, c.toString()))
                i++
            }
            c == ',' -> {
                tokens.add(FilterToken(FilterTokenType.COMMA, ","))
                i++
            }
            c == '"' || c == '\'' -> {
                val end = expr.indexOf(c, i + 1)
                if (end > i) {
                    tokens.add(FilterToken(FilterTokenType.STRING, expr.substring(i + 1, end)))
                    i = end + 1
                } else {
                    i++
                }
            }
            expr.substring(i).startsWith(">=") -> {
                tokens.add(FilterToken(FilterTokenType.OPERATOR, ">="))
                i += 2
            }
            expr.substring(i).startsWith("<=") -> {
                tokens.add(FilterToken(FilterTokenType.OPERATOR, "<="))
                i += 2
            }
            expr.substring(i).startsWith("==") -> {
                tokens.add(FilterToken(FilterTokenType.OPERATOR, "=="))
                i += 2
            }
            expr.substring(i).startsWith("!=") -> {
                tokens.add(FilterToken(FilterTokenType.OPERATOR, "!="))
                i += 2
            }
            c == '>' || c == '<' -> {
                tokens.add(FilterToken(FilterTokenType.OPERATOR, c.toString()))
                i++
            }
            expr.substring(i).startsWith("&&") -> {
                tokens.add(FilterToken(FilterTokenType.LOGICAL, "&&"))
                i += 2
            }
            expr.substring(i).startsWith("||") -> {
                tokens.add(FilterToken(FilterTokenType.LOGICAL, "||"))
                i += 2
            }
            c.isLetter() || c == '_' || c == '$' || c == '.' || c == '[' || c == ']' -> {
                val start = i
                while (i < expr.length && (expr[i].isLetterOrDigit() || expr[i] == '_' || expr[i] == '$' || expr[i] == '.' || expr[i] == '[' || expr[i] == ']' || expr[i] == '@')) {
                    i++
                }
                val word = expr.substring(start, i)
                when {
                    word == "and" -> tokens.add(FilterToken(FilterTokenType.LOGICAL, "&&"))
                    word == "or" -> tokens.add(FilterToken(FilterTokenType.LOGICAL, "||"))
                    word == "not" -> tokens.add(FilterToken(FilterTokenType.NOT, "not"))
                    word in listOf("true", "false", "null") -> tokens.add(FilterToken(FilterTokenType.BOOLEAN, word))
                    else -> tokens.add(FilterToken(FilterTokenType.IDENT, word))
                }
            }
            c.isDigit() -> {
                val start = i
                while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                    i++
                }
                tokens.add(FilterToken(FilterTokenType.NUMBER, expr.substring(start, i)))
            }
            c == '-' && i + 1 < expr.length && expr[i + 1].isDigit() -> {
                val prev = tokens.lastOrNull()
                if (prev == null || prev.type != FilterTokenType.IDENT && prev.type != FilterTokenType.NUMBER && prev.type != FilterTokenType.STRING && prev.type != FilterTokenType.BOOLEAN) {
                    val start = i
                    i++
                    while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) {
                        i++
                    }
                    tokens.add(FilterToken(FilterTokenType.NUMBER, expr.substring(start, i)))
                } else {
                    i++
                }
            }
            else -> i++
        }
    }

    return tokens
}
