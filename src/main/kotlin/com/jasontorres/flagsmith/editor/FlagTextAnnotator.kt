package com.jasontorres.flagsmith.editor

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.jasontorres.flagsmith.FeatureState
import com.jasontorres.flagsmith.FlagCache
import javax.swing.Icon

/**
 * Platform fallback used when the JavaScript PSI plugin is not loaded.
 * Scans JS/TS file text for flag API usages and adds gutters / warnings.
 */
class FlagTextAnnotator : Annotator, DumbAware {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val file = element.containingFile ?: return
        if (element != file) return
        if (!FlagUsageScanner.isJsLikeFile(file)) return
        if (FlagUsageScanner.hasJavaScriptPsi(file)) return
        val project = file.project
        val cache = FlagCache.getInstance(project)
        val text = file.text ?: return

        FlagUsageScanner.findUsages(text).forEach { usage ->
            if (usage.startOffset < 0 || usage.endOffset > text.length || usage.startOffset >= usage.endOffset) {
                return@forEach
            }
            val range = TextRange(usage.startOffset, usage.endOffset)
            val known = cache.get(usage.name)
            if (known != null) {
                holder.newAnnotation(HighlightSeverity.INFORMATION, FlagActions.tooltip(known))
                    .range(range)
                    .gutterIconRenderer(FlagGutterIcon(project, known))
                    .create()
            } else if (usage.isLiteral) {
                holder.newSilentAnnotation(HighlightSeverity.WARNING)
                    .range(range)
                    .tooltip("Unknown Flagsmith flag \"${usage.name}\"")
                    .withFix(CreateUnknownFlagFix(usage.name))
                    .create()
            }
        }
    }
}

class FlagGutterIcon(
    private val project: com.intellij.openapi.project.Project,
    private val flag: FeatureState
) : GutterIconRenderer(), DumbAware {
    override fun getIcon(): Icon = FlagIcons.forFlag(flag.enabled)
    override fun getTooltipText(): String = FlagActions.tooltip(flag)
    override fun isNavigateAction(): Boolean = true
    override fun getClickAction(): AnAction {
        return object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                FlagActions.showFlagPopup(project, flag, e.inputEvent)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        return other is FlagGutterIcon && other.flag.id == flag.id && other.flag.enabled == flag.enabled
    }

    override fun hashCode(): Int = flag.id * 31 + flag.enabled.hashCode()
}
