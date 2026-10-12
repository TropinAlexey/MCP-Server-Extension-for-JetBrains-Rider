package com.github.tropin.ridermcp.ide

/**
 * Lexical read-only filter for rider_execute_console — no IDE dependencies, unit-tested.
 *
 * Rejects statements that can change data or schema (DML/DDL, permissions, backup/restore,
 * transaction escapes) before anything is sent to the server. Session-scoped objects stay
 * writable: `#temp` tables and `@table` variables (MSSQL), `CREATE TEMP TABLE` (PostgreSQL).
 * It is a first line of defence; the executor additionally runs the script in a rolled-back
 * transaction (MSSQL) or a read-only session (PostgreSQL).
 */
internal object SqlReadOnlyGuard {

    data class Verdict(val violations: List<String>) {
        val allowed: Boolean get() = violations.isEmpty()
    }

    private enum class Kind { WORD, LITERAL, QUOTED, PUNCT }

    private data class Token(val kind: Kind, val text: String) {
        val upper: String get() = if (kind == Kind.WORD) text.uppercase() else ""
        fun isWord(vararg words: String) = kind == Kind.WORD && words.any { it.equals(text, ignoreCase = true) }
        fun isPunct(c: String) = kind == Kind.PUNCT && text == c
    }

    private const val MAX_DEPTH = 5

    /** Read-only system procedures callable by EXEC (optionally sys./dbo./master.. qualified). */
    private val readOnlyProcs = setOf(
        "sp_who", "sp_who2", "sp_spaceused", "sp_columns", "sp_columns_ex", "sp_tables", "sp_tables_ex",
        "sp_databases", "sp_server_info", "sp_describe_first_result_set", "sp_describe_undeclared_parameters",
        "sp_lock", "sp_stored_procedures", "sp_pkeys", "sp_fkeys", "sp_statistics", "sp_sproc_columns",
        "sp_depends", "sp_datatype_info", "sp_special_columns", "sp_table_privileges", "sp_column_privileges",
        "sp_readerrorlog", "sp_enumerrorlogs", "sp_monitor", "sp_linkedservers", "sp_catalogs",
        "sp_primarykeys", "sp_foreignkeys", "sp_indexes", "xp_msver", "xp_fixeddrives", "xp_readerrorlog"
    )

    /** EXEC targets whose first argument is SQL text: checked when literal, otherwise unchecked dynamic SQL. */
    private val dynamicSqlProcs = setOf("sp_executesql", "sp_msforeachdb", "sp_msforeachtable")

    private val readOnlyDbcc = setOf(
        "INPUTBUFFER", "OPENTRAN", "SHOWCONTIG", "SHOW_STATISTICS", "SQLPERF", "TRACESTATUS",
        "USEROPTIONS", "PROCCACHE", "LOGINFO", "OUTPUTBUFFER"
    )

    /** Statements rejected outright when they look like a statement head (not a column of that name). */
    private val deniedStatements = setOf(
        "GRANT", "REVOKE", "DENY", "KILL", "BACKUP", "RESTORE", "SHUTDOWN", "RECONFIGURE", "CHECKPOINT",
        "SETUSER", "WRITETEXT", "UPDATETEXT", "UPSERT", "RENAME", "LOAD", "IMPORT", "VACUUM", "ANALYZE",
        "REINDEX", "CLUSTER", "COPY", "REFRESH", "CALL", "LOCK", "REASSIGN", "DISCARD"
    )

    /** Words after which a denied keyword is a column/alias rather than a statement head. */
    private val clauseWords = setOf(
        "FROM", "AS", "AND", "OR", "IS", "IN", "DESC", "ASC", "LIKE", "BETWEEN", "NOT", "END", "THEN",
        "ELSE", "WHEN", "ON", "WHERE", "GROUP", "ORDER", "HAVING", "UNION", "JOIN", "INNER", "LEFT",
        "RIGHT", "OUTER", "CROSS", "COLLATE", "NULL"
    )

    fun check(sql: String, tsql: Boolean): Verdict {
        val violations = linkedSetOf<String>()
        checkTokens(tokenize(sql, tsql), tsql, 0, violations)
        return Verdict(violations.toList())
    }

    /** Returns true when unchecked dynamic SQL was found. */
    private fun checkTokens(tokens: List<Token>, tsql: Boolean, depth: Int, out: MutableSet<String>): Boolean {
        if (depth > MAX_DEPTH) {
            out.add("dynamic SQL nested deeper than $MAX_DEPTH levels")
            return false
        }
        var dynamic = false
        var i = 0
        // T-SQL allows a bare procedure call as the first statement of a batch.
        tokens.firstOrNull()?.let { first ->
            if (tsql && first.kind == Kind.WORD && first.text.lowercase().let { it.startsWith("sp_") || it.startsWith("xp_") }) {
                val r = checkExec(tokens, 0, tsql, depth, out)
                dynamic = r.second || dynamic
                i = r.first
            }
        }
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.kind != Kind.WORD || prev(tokens, i)?.isPunct(".") == true) { i++; continue }
            val next = tokens.getOrNull(i + 1)
            val nextIsCall = next?.isPunct("(") == true
            when (t.upper) {
                "INSERT" -> if (!nextIsCall) i = requireTempTarget(tokens, i + 1, "INSERT", out, skip = setOf("INTO"))
                "UPDATE" -> when {
                    nextIsCall -> Unit // UPDATE(column) inside triggers
                    prev(tokens, i)?.isWord("ON", "KEY") == true -> Unit // ON UPDATE CASCADE, ON DUPLICATE KEY UPDATE
                    prev(tokens, i)?.isWord("FOR") == true -> out.add("SELECT … FOR UPDATE (row locks)")
                    next?.isWord("STATISTICS") == true -> out.add("UPDATE STATISTICS")
                    else -> i = requireTempTarget(tokens, i + 1, "UPDATE", out)
                }
                "DELETE" -> if (!nextIsCall && prev(tokens, i)?.isWord("ON") != true) {
                    i = requireTempTarget(tokens, i + 1, "DELETE", out, skip = setOf("FROM"))
                }
                "MERGE" -> when {
                    next?.isWord("JOIN", "UNION") == true -> Unit // join / union hints
                    prev(tokens, i)?.let { it.isPunct("(") || it.isPunct(",") } == true -> Unit
                    else -> i = requireTempTarget(tokens, i + 1, "MERGE", out, skip = setOf("INTO"))
                }
                "REPLACE" -> if (!nextIsCall && statementLike(tokens, i)) out.add("REPLACE")
                "TRUNCATE" -> if (!nextIsCall) {
                    i = if (next?.isWord("TABLE") == true) requireTempTarget(tokens, i + 2, "TRUNCATE TABLE", out)
                    else { out.add("TRUNCATE"); i + 1 }
                }
                "ALTER" -> i = if (next?.isWord("TABLE") == true) requireTempTarget(tokens, i + 2, "ALTER TABLE", out)
                else { out.add("ALTER ${next?.text?.uppercase().orEmpty()}".trim()); i + 1 }
                "CREATE" -> i = checkCreate(tokens, i, out)
                "DROP" -> i = checkDrop(tokens, i, out)
                "INTO" -> i = checkInto(tokens, i, out)
                "EXEC", "EXECUTE" -> {
                    val r = checkExec(tokens, i + 1, tsql, depth, out)
                    dynamic = r.second || dynamic
                    i = r.first
                }
                "COMMIT" -> out.add("COMMIT (would persist changes made in the read-only transaction)")
                "SET" -> checkSet(tokens, i, out)
                "READ" -> if (next?.isWord("WRITE") == true) out.add("READ WRITE transaction mode")
                "DBCC" -> if (next == null || next.upper !in readOnlyDbcc) out.add("DBCC ${next?.text?.uppercase().orEmpty()}".trim())
                "DO" -> if (next?.kind == Kind.LITERAL) out.add("DO block")
                "COMMENT" -> if (next?.isWord("ON") == true) out.add("COMMENT ON")
                else -> if (t.upper in deniedStatements && statementLike(tokens, i)) out.add(t.upper)
            }
            i++
        }
        return dynamic
    }

    private fun prev(tokens: List<Token>, i: Int): Token? = tokens.getOrNull(i - 1)

    /**
     * True when the keyword at [i] heads a statement rather than naming a column:
     * not preceded by a list/compare position and not followed by a clause word or operator.
     */
    private fun statementLike(tokens: List<Token>, i: Int): Boolean {
        val p = prev(tokens, i)
        if (p != null && p.kind == Kind.PUNCT && p.text != ";") return false
        if (p != null && p.isWord("SELECT", "BY", "AS", "WHERE", "AND", "OR", "ON", "DISTINCT", "TOP")) return false
        val n = tokens.getOrNull(i + 1) ?: return true
        return when (n.kind) {
            Kind.PUNCT -> n.text == ";"
            Kind.WORD -> n.upper !in clauseWords
            else -> true
        }
    }

    /** Reads `a.b.c` / `[a]..#t` starting at [start]; returns the name parts and the index after the name. */
    private fun readName(tokens: List<Token>, start: Int): Pair<List<String>, Int>? {
        var i = start
        val parts = mutableListOf<String>()
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.kind == Kind.WORD || t.kind == Kind.QUOTED) {
                parts.add(t.text); i++
                if (i < tokens.size && tokens[i].isPunct(".")) {
                    while (i < tokens.size && tokens[i].isPunct(".")) i++
                    continue
                }
            }
            break
        }
        return if (parts.isEmpty()) null else parts to i
    }

    private fun isSessionObject(parts: List<String>): Boolean =
        parts.last().startsWith("#") || parts.first().startsWith("@")

    /** Skips `TOP (n) [PERCENT]` and words from [skip]. */
    private fun skipModifiers(tokens: List<Token>, start: Int, skip: Set<String>): Int {
        var i = start
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t.isWord("TOP") -> {
                    i++
                    if (i < tokens.size && tokens[i].isPunct("(")) i = skipParens(tokens, i) else i++
                    if (i < tokens.size && tokens[i].isWord("PERCENT")) i++
                }
                t.kind == Kind.WORD && t.upper in skip -> i++
                else -> return i
            }
        }
        return i
    }

    private fun skipParens(tokens: List<Token>, open: Int): Int {
        var depth = 0
        var i = open
        while (i < tokens.size) {
            if (tokens[i].isPunct("(")) depth++
            if (tokens[i].isPunct(")")) { depth--; if (depth == 0) return i + 1 }
            i++
        }
        return i
    }

    private fun requireTempTarget(tokens: List<Token>, start: Int, what: String, out: MutableSet<String>, skip: Set<String> = emptySet()): Int {
        val at = skipModifiers(tokens, start, skip)
        val name = readName(tokens, at)
        if (name == null || !isSessionObject(name.first)) {
            out.add("$what ${name?.first?.joinToString(".").orEmpty()}".trim())
            return at
        }
        return name.second - 1
    }

    private fun checkCreate(tokens: List<Token>, i: Int, out: MutableSet<String>): Int {
        var j = i + 1
        while (j < tokens.size && tokens[j].isWord("LOCAL", "GLOBAL")) j++
        val t = tokens.getOrNull(j)
        return when {
            t == null -> { out.add("CREATE"); j }
            t.isWord("TEMP", "TEMPORARY") -> j // PostgreSQL session-scoped objects
            t.isWord("TABLE") -> requireTempTarget(tokens, j + 1, "CREATE TABLE", out)
            t.isWord("UNIQUE", "CLUSTERED", "NONCLUSTERED", "INDEX") -> {
                var k = j
                while (k < tokens.size && !tokens[k].isWord("ON")) k++
                if (k >= tokens.size) { out.add("CREATE INDEX"); k } else requireTempTarget(tokens, k + 1, "CREATE INDEX ON", out)
            }
            else -> { out.add("CREATE ${t.text.uppercase()}"); j }
        }
    }

    private fun checkDrop(tokens: List<Token>, i: Int, out: MutableSet<String>): Int {
        if (tokens.getOrNull(i + 1)?.isWord("TABLE") != true) {
            out.add("DROP ${tokens.getOrNull(i + 1)?.text?.uppercase().orEmpty()}".trim())
            return i + 1
        }
        var j = i + 2
        if (tokens.getOrNull(j)?.isWord("IF") == true && tokens.getOrNull(j + 1)?.isWord("EXISTS") == true) j += 2
        while (true) {
            val name = readName(tokens, j)
            if (name == null || !isSessionObject(name.first)) {
                out.add("DROP TABLE ${name?.first?.joinToString(".").orEmpty()}".trim())
                return j
            }
            j = name.second
            if (tokens.getOrNull(j)?.isPunct(",") == true) j++ else return j - 1
        }
    }

    /** Standalone INTO: SELECT … INTO, FETCH … INTO, OUTPUT … INTO. INSERT/MERGE consume their own INTO. */
    private fun checkInto(tokens: List<Token>, i: Int, out: MutableSet<String>): Int {
        val next = tokens.getOrNull(i + 1)
        if (next?.isWord("TEMP", "TEMPORARY") == true) return i + 1
        val name = readName(tokens, i + 1)
        if (name == null || !isSessionObject(name.first)) {
            out.add("SELECT … INTO ${name?.first?.joinToString(".").orEmpty()}".trim())
            return i
        }
        return name.second - 1
    }

    private fun checkSet(tokens: List<Token>, i: Int, out: MutableSet<String>) {
        val next = tokens.getOrNull(i + 1) ?: return
        when {
            next.isWord("IMPLICIT_TRANSACTIONS") -> out.add("SET IMPLICIT_TRANSACTIONS")
            next.isWord("TRANSACTION") && tokens.getOrNull(i + 2)?.isWord("ISOLATION") != true -> out.add("SET TRANSACTION")
            next.isWord("SESSION") -> out.add("SET SESSION")
            next.isWord("default_transaction_read_only", "transaction_read_only") -> out.add("SET ${next.text}")
        }
    }

    /**
     * EXEC/EXECUTE at [start] (index after the keyword). Allows read-only system procedures and
     * checks literal dynamic SQL recursively. Returns the next index and whether unchecked dynamic SQL was seen.
     */
    private fun checkExec(tokens: List<Token>, start: Int, tsql: Boolean, depth: Int, out: MutableSet<String>): Pair<Int, Boolean> {
        var j = start
        val first = tokens.getOrNull(j) ?: run { out.add("EXEC"); return j to false }
        if (first.isPunct("(")) {
            val end = skipParens(tokens, j)
            val inner = tokens.subList(j + 1, (end - 1).coerceAtLeast(j + 1))
            return end - 1 to checkDynamicArg(inner, tsql, depth, out)
        }
        if (first.isWord("AS")) { out.add("EXECUTE AS"); return j to false }
        // EXEC @rc = proc …
        if (first.kind == Kind.WORD && first.text.startsWith("@") && tokens.getOrNull(j + 1)?.isPunct("=") == true) j += 2
        val name = readName(tokens, j) ?: run { out.add("EXEC"); return j to false }
        val parts = name.first
        val proc = parts.last().lowercase()
        if (parts.size == 1 && parts[0].startsWith("@")) {
            out.add("EXEC ${parts[0]} (procedure name in a variable)")
            return name.second to false
        }
        val qualifierOk = parts.dropLast(1).all { it.isEmpty() || it.lowercase() in setOf("sys", "dbo", "master") }
        return when {
            !qualifierOk -> { out.add("EXEC ${parts.joinToString(".")}"); name.second to false }
            proc in dynamicSqlProcs -> {
                // First argument up to the next ',' (or end of statement) is the SQL text.
                var k = name.second
                if (tokens.getOrNull(k)?.kind == Kind.WORD && tokens[k].text.startsWith("@") && tokens.getOrNull(k + 1)?.isPunct("=") == true) k += 2
                val arg = mutableListOf<Token>()
                while (k < tokens.size && !tokens[k].isPunct(",") && !tokens[k].isPunct(";")) {
                    if (arg.isNotEmpty() && tokens[k].kind == Kind.WORD && arg.last().kind != Kind.PUNCT) break
                    arg.add(tokens[k]); k++
                }
                k - 1 to checkDynamicArg(arg, tsql, depth, out)
            }
            proc in readOnlyProcs || proc.startsWith("sp_help") -> name.second - 1 to false
            else -> { out.add("EXEC ${parts.joinToString(".")} (not a read-only system procedure)"); name.second to false }
        }
    }

    /**
     * `'lit' + N'lit'` is checked recursively. Anything built from variables is rejected (fail closed):
     * its text is unknown until run time and could assemble a COMMIT that escapes the rollback-only transaction.
     */
    private fun checkDynamicArg(arg: List<Token>, tsql: Boolean, depth: Int, out: MutableSet<String>): Boolean {
        val literals = arg.filter { it.kind == Kind.LITERAL }
        val onlyLiterals = arg.all { it.kind == Kind.LITERAL || it.isPunct("+") }
        if (literals.isNotEmpty() && onlyLiterals) {
            val text = literals.joinToString("") { it.text }.replace("?", "x")
            val inner = linkedSetOf<String>()
            val dynamic = checkTokens(tokenize(text, tsql), tsql, depth + 1, inner)
            inner.forEach { out.add("$it (in dynamic SQL)") }
            return dynamic
        }
        out.add("dynamic SQL from variables (text cannot be checked; use a literal, sp_MSforeachdb '…[?]…' or three-part names)")
        return false
    }

    private fun tokenize(sql: String, tsql: Boolean): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val len = sql.length
        fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '@' || c == '#' || c == '$'
        while (i < len) {
            val c = sql[i]
            when {
                c.isWhitespace() -> i++
                c == '-' && i + 1 < len && sql[i + 1] == '-' -> { while (i < len && sql[i] != '\n') i++ }
                c == '/' && i + 1 < len && sql[i + 1] == '*' -> {
                    i += 2
                    while (i < len && !(sql[i] == '*' && i + 1 < len && sql[i + 1] == '/')) i++
                    i += 2
                }
                c == '\'' || ((c == 'N' || c == 'n' || c == 'E' || c == 'e') && i + 1 < len && sql[i + 1] == '\'') -> {
                    if (c != '\'') i++
                    i++
                    val sb = StringBuilder()
                    while (i < len) {
                        if (sql[i] == '\'') {
                            if (i + 1 < len && sql[i + 1] == '\'') { sb.append('\''); i += 2 } else { i++; break }
                        } else sb.append(sql[i++])
                    }
                    tokens.add(Token(Kind.LITERAL, sb.toString()))
                }
                c == '$' && dollarTag(sql, i) != null -> {
                    val tag = dollarTag(sql, i)!!
                    val bodyStart = i + tag.length
                    val end = sql.indexOf(tag, bodyStart).let { if (it < 0) len else it }
                    tokens.add(Token(Kind.LITERAL, sql.substring(bodyStart, end)))
                    i = (end + tag.length).coerceAtMost(len)
                }
                c == '"' || c == '`' || (c == '[' && tsql) -> {
                    val close = if (c == '[') ']' else c
                    val end = sql.indexOf(close, i + 1).let { if (it < 0) len else it }
                    tokens.add(Token(Kind.QUOTED, sql.substring(i + 1, end)))
                    i = end + 1
                }
                isWordChar(c) -> {
                    val start = i
                    while (i < len && isWordChar(sql[i])) i++
                    tokens.add(Token(Kind.WORD, sql.substring(start, i)))
                }
                else -> { tokens.add(Token(Kind.PUNCT, c.toString())); i++ }
            }
        }
        return tokens
    }

    /** PostgreSQL dollar-quote opener (`$$` or `$tag$`) at [i], or null. */
    private fun dollarTag(sql: String, i: Int): String? {
        if (i > 0 && (sql[i - 1].isLetterOrDigit() || sql[i - 1] == '_')) return null
        var j = i + 1
        while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
        return if (j < sql.length && sql[j] == '$') sql.substring(i, j + 1) else null
    }
}
