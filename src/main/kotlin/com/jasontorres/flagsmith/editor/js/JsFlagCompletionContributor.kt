package com.jasontorres.flagsmith.editor.js

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.jasontorres.flagsmith.FlagCache
import com.jasontorres.flagsmith.editor.FlagActions
import com.jasontorres.flagsmith.editor.FlagIcons

class JsFlagCompletionContributor : CompletionContributor() {
    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    val literal = PsiTreeUtil.getParentOfType(parameters.position, JSLiteralExpression::class.java, false)
                        ?: parameters.position.parent as? JSLiteralExpression
                        ?: return
                    if (!JsFlagUsages.isFlagApiLiteral(literal)) return
                    val cache = FlagCache.getInstance(parameters.position.project)
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
        )
    }
}
