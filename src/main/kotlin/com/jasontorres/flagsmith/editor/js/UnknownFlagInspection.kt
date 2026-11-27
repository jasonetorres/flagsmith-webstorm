package com.jasontorres.flagsmith.editor.js

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.javascript.psi.JSElementVisitor
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.PsiElementVisitor
import com.jasontorres.flagsmith.FlagCache
import com.jasontorres.flagsmith.editor.CreateUnknownFlagFix

class UnknownFlagInspection : LocalInspectionTool() {
    override fun getDisplayName(): String = "Unknown Flagsmith flag"
    override fun getGroupDisplayName(): String = "Flagsmith"
    override fun getStaticDescription(): String =
        "Reports string literals in Flagsmith APIs (useFlags, hasFeature, getValue, flags['…']) that do not match a known flag in the configured environment."

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : JSElementVisitor() {
            override fun visitJSLiteralExpression(node: JSLiteralExpression) {
                if (!JsFlagUsages.isFlagApiLiteral(node)) return
                val name = JsFlagUsages.stringLiteralName(node) ?: return
                val cache = FlagCache.getInstance(node.project)
                if (cache.isKnown(name)) return
                holder.registerProblem(
                    node,
                    "Unknown Flagsmith flag \"$name\"",
                    CreateUnknownFlagFix(name)
                )
            }
        }
    }
}
