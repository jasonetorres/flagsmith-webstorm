package com.jasontorres.flagsmith.editor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

class CreateUnknownFlagFix(private val flagName: String) : LocalQuickFix, IntentionAction {
    override fun getFamilyName(): String = "Create Flagsmith flag"
    override fun getName(): String = "Create flag \"$flagName\" in Flagsmith"
    override fun getText(): String = name
    override fun startInWriteAction(): Boolean = false
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = flagName.isNotBlank()

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        FlagActions.createFlag(project, flagName)
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        FlagActions.createFlag(project, flagName)
    }
}
