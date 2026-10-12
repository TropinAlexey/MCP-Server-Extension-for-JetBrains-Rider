package com.github.tropin.ridermcp.ide

/** Masks secrets in query results for rider_execute_console (maskSecrets) — no IDE dependencies, unit-tested. */
internal object SecretMasker {

    const val MASK = "***"

    /** Column names whose whole value is a secret (matched on the name without '_', '-', ' '). */
    private val secretColumn = Regex("password|passwd|pwd|secret|token|apikey|privatekey|credential")

    /** Column names holding connection strings: only the secret parts of the value are masked. */
    private val connectionColumn = Regex("connectionstring|connstring|connstr|dsn|jdbcurl")

    /** `key=value` secrets in connection strings / URLs; value may be quoted or {braced}. */
    private val keyValue = Regex(
        """(?i)\b((?:password|pwd|passwd|secret|client[_-]?secret|api[_-]?key|access[_-]?key|access[_-]?token|token|sharedaccesskey|accountkey)\s*=\s*)("[^"]*"|'[^']*'|\{[^}]*}|[^;&\s,]*)"""
    )

    /** JSON `"password": "value"`. */
    private val jsonValue = Regex(
        """(?i)("(?:password|pwd|passwd|secret|client_?secret|api_?key|access_?token|token)"\s*:\s*)"(?:[^"\\]|\\.)*""""
    )

    /** URL userinfo `scheme://user:secret@host`. */
    private val userInfo = Regex("""(://[^/\s:@]+:)[^@\s/]+@""")

    enum class ColumnKind { PLAIN, SECRET, CONNECTION }

    fun classifyColumn(label: String): ColumnKind {
        val name = label.lowercase().replace(Regex("[_\\-\\s]"), "")
        return when {
            connectionColumn.containsMatchIn(name) -> ColumnKind.CONNECTION
            secretColumn.containsMatchIn(name) -> ColumnKind.SECRET
            else -> ColumnKind.PLAIN
        }
    }

    /** Returns the masked cell value and whether anything was masked. */
    fun maskCell(value: String, kind: ColumnKind): Pair<String, Boolean> {
        if (kind == ColumnKind.SECRET) return if (value.isEmpty()) value to false else MASK to true
        val masked = maskText(value)
        return masked to (masked != value)
    }

    /** Masks `password=…`-style secrets inside free text (cells, server messages). */
    fun maskText(text: String): String {
        if (text.isEmpty()) return text
        return text.replace(keyValue) { m ->
            val v = m.groupValues[2]
            if (v.isEmpty()) m.value else m.groupValues[1] + MASK
        }
            .replace(jsonValue) { m -> m.groupValues[1] + "\"" + MASK + "\"" }
            .replace(userInfo) { m -> m.groupValues[1] + MASK + "@" }
    }
}
