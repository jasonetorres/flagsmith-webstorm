package com.jasontorres.flagsmith.editor

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.psi.PsiFile

/**
 * Text-level heuristic for Flagsmith flag usages in JS/TS.
 *
 * Matches string literals (and `flags.x` property names) inside:
 * - useFlags([...])
 * - flagsmith.hasFeature('x') / flagsmith.getValue('x')
 * - flags['x'] / flags.x
 */
data class FlagUsageSpan(
    val name: String,
    val startOffset: Int,
    val endOffset: Int,
    val isLiteral: Boolean
)

object FlagUsageScanner {
    private val JS_EXTENSIONS = setOf("js", "jsx", "ts", "tsx", "mjs", "cjs")
    private val JS_LANGUAGE_IDS = setOf(
        "JavaScript",
        "TypeScript",
        "TypeScript JSX",
        "JSX Harmony",
        "ECMAScript 6"
    )

    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val QUOTED = Regex("""(['"`])((?:\\.|(?!\1).)*?)\1""")

    private val USE_FLAGS = Regex("""\buseFlags\s*\(\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    private val API_CALL = Regex("""\b(?:flagsmith\s*\.\s*)?(?:hasFeature|getValue)\s*\(\s*(['"`])((?:\\.|(?!\1).)*?)\1""")
    private val FLAGS_INDEX = Regex("""\bflags\s*\[\s*(['"`])((?:\\.|(?!\1).)*?)\1\s*\]""")
    private val FLAGS_PROP = Regex("""\bflags\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)""")

    fun isJsLikeFile(file: PsiFile): Boolean {
        val ext = file.virtualFile?.extension?.lowercase()
        if (ext in JS_EXTENSIONS) return true
        if (file.language.id in JS_LANGUAGE_IDS) return true
        val fileType: FileType = file.fileType
        if (fileType is LanguageFileType && fileType.language.id in JS_LANGUAGE_IDS) return true
        return false
    }

    /**
     * True when the file is handled by the JavaScript PSI plugin, so the
     * text fallback should stand down.
     */
    fun hasJavaScriptPsi(file: PsiFile): Boolean {
        return file.language.id in JS_LANGUAGE_IDS ||
            file.language.baseLanguage?.id in JS_LANGUAGE_IDS
    }

    fun findUsages(text: String): List<FlagUsageSpan> {
        val found = LinkedHashMap<Triple<Int, Int, String>, FlagUsageSpan>()

        fun add(name: String, start: Int, end: Int, literal: Boolean) {
            if (name.isEmpty()) return
            found.putIfAbsent(Triple(start, end, name), FlagUsageSpan(name, start, end, literal))
        }

        USE_FLAGS.findAll(text).forEach { match ->
            val body = match.groups[1] ?: return@forEach
            val bodyStart = body.range.first
            QUOTED.findAll(body.value).forEach { q ->
                val name = unescape(q.groupValues[2])
                add(name, bodyStart + q.range.first + 1, bodyStart + q.range.first + 1 + name.length, true)
            }
        }

        API_CALL.findAll(text).forEach { match ->
            val name = unescape(match.groupValues[2])
            val token = match.groups[2] ?: return@forEach
            add(name, token.range.first, token.range.last + 1, true)
        }

        FLAGS_INDEX.findAll(text).forEach { match ->
            val name = unescape(match.groupValues[2])
            val token = match.groups[2] ?: return@forEach
            add(name, token.range.first, token.range.last + 1, true)
        }

        FLAGS_PROP.findAll(text).forEach { match ->
            val name = match.groupValues[1]
            val token = match.groups[1] ?: return@forEach
            add(name, token.range.first, token.range.last + 1, false)
        }

        return found.values.sortedBy { it.startOffset }
    }

    fun isCompletionContext(textBeforeCaret: String): Boolean {
        val tail = textBeforeCaret.takeLast(200)
        return USE_FLAGS.containsMatchIn(tail + "]") ||
            Regex("""\b(?:flagsmith\s*\.\s*)?(?:hasFeature|getValue)\s*\(\s*['"`][^'"`]*$""").containsMatchIn(tail) ||
            Regex("""\bflags\s*\[\s*['"`][^'"`]*$""").containsMatchIn(tail)
    }

    private fun unescape(raw: String): String {
        return raw.replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\`", "`")
            .replace("\\\\", "\\")
    }
}
