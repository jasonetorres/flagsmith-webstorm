package com.jasontorres.flagsmith.editor

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.jasontorres.flagsmith.FlagCache

/**
 * Platform fallback completion for JS/TS files when JavaScript PSI is unavailable.
 */
class FlagTextCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile
        if (!FlagUsageScanner.isJsLikeFile(file)) return
        if (FlagUsageScanner.hasJavaScriptPsi(file)) return
        val document = parameters.editor.document
        val offset = parameters.offset
        if (offset <= 0 || offset > document.textLength) return
        val prefixText = document.text.substring(0, offset)
        if (!FlagUsageScanner.isCompletionContext(prefixText)) return

        val cache = FlagCache.getInstance(file.project)
        cache.flags().forEach { flag ->
            result.addElement(
                LookupElementBuilder.create(flag.feature.name)
                    .withTailText(FlagActions.tailText(flag), true)
                    .withTypeText("Flagsmith")
                    .withIcon(FlagIcons.forFlag(flag.enabled))
            )
        }
    }
}
