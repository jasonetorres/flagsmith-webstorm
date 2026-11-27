package com.jasontorres.flagsmith.editor.js

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.jasontorres.flagsmith.FlagCache
import com.jasontorres.flagsmith.editor.FlagActions
import com.jasontorres.flagsmith.editor.FlagIcons
import java.awt.event.MouseEvent

class JsFlagLineMarkerProvider : LineMarkerProvider {
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val file: PsiFile = element.containingFile ?: return null
        val cache = FlagCache.getInstance(file.project)

        val literal = element as? JSLiteralExpression
        if (literal != null && JsFlagUsages.isFlagApiLiteral(literal)) {
            val name = JsFlagUsages.stringLiteralName(literal) ?: return null
            val flag = cache.get(name) ?: return null
            val anchor = literal.leafAnchor() ?: return null
            return marker(anchor, file, flag)
        }

        val ref = element as? JSReferenceExpression
        if (ref != null && JsFlagUsages.isFlagsPropertyAccess(ref)) {
            val name = ref.referenceName ?: return null
            val flag = cache.get(name) ?: return null
            val anchor = ref.referenceNameElement ?: ref
            return marker(anchor, file, flag)
        }
        return null
    }

    private fun marker(anchor: PsiElement, file: PsiFile, flag: com.jasontorres.flagsmith.FeatureState): LineMarkerInfo<PsiElement> {
        val handler = GutterIconNavigationHandler<PsiElement> { event: MouseEvent?, _ ->
            FlagActions.showFlagPopup(file.project, flag, event)
        }
        return LineMarkerInfo(
            anchor,
            anchor.textRange,
            FlagIcons.forFlag(flag.enabled),
            { FlagActions.tooltip(flag) },
            handler,
            GutterIconRenderer.Alignment.LEFT
        ) { "Flagsmith flag ${flag.feature.name}" }
    }

    private fun JSLiteralExpression.leafAnchor(): PsiElement? {
        return firstChild ?: this
    }
}
