package com.jasontorres.flagsmith.editor.js

import com.intellij.lang.javascript.psi.JSArgumentList
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSDefinitionExpression
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

object JsFlagUsages {
    fun stringLiteralName(literal: JSLiteralExpression): String? {
        if (!literal.isStringLiteral) return null
        return literal.stringValue?.takeIf { it.isNotBlank() }
    }

    fun isFlagApiLiteral(literal: JSLiteralExpression): Boolean {
        return classifyLiteral(literal) != null
    }

    fun classifyLiteral(literal: JSLiteralExpression): Kind? {
        if (stringLiteralName(literal) == null) return null
        if (isInUseFlags(literal) || isInNamedCall(literal, "hasFeature", "getValue")) {
            return Kind.CALL_LITERAL
        }
        val index = PsiTreeUtil.getParentOfType(literal, JSIndexedPropertyAccessExpression::class.java, false)
        if (index != null && index.indexExpression == literal && isFlagsQualifier(index.qualifier)) {
            return Kind.INDEX_ACCESS
        }
        return null
    }

    fun isFlagsPropertyAccess(ref: JSReferenceExpression): Boolean {
        val qualifier = ref.qualifier ?: return false
        return isFlagsQualifier(qualifier)
    }

    private fun isInUseFlags(element: PsiElement): Boolean {
        val array = PsiTreeUtil.getParentOfType(element, JSArrayLiteralExpression::class.java, false)
        val call = if (array != null) {
            PsiTreeUtil.getParentOfType(array, JSCallExpression::class.java, false)
        } else {
            PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java, false)
        }
        return isNamedCall(call, "useFlags")
    }

    private fun isInNamedCall(element: PsiElement, vararg names: String): Boolean {
        val call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java, false) ?: return false
        val args = call.argumentList ?: call as? JSArgumentList
        val arguments = call.arguments
        if (element !is JSLiteralExpression || arguments.none { it == element }) {
            // still allow if it's a direct argument
            val list = PsiTreeUtil.getParentOfType(element, JSArgumentList::class.java, false)
            if (list == null || list.parent != call) {
                if (!isNamedCall(call, *names)) return false
            }
        }
        return isNamedCall(call, *names)
    }

    private fun isNamedCall(call: JSCallExpression?, vararg names: String): Boolean {
        if (call == null) return false
        val method = call.methodExpression
        val referenced = when (method) {
            is JSReferenceExpression -> method.referenceName
            else -> method?.text?.substringAfterLast('.')
        }
        return referenced != null && names.any { it == referenced }
    }

    private fun isFlagsQualifier(qualifier: PsiElement?): Boolean {
        val ref = qualifier as? JSReferenceExpression ?: return false
        if (ref.qualifier != null) return false
        if (ref.referenceName == "flags") return true
        val resolved = ref.resolve() ?: return false
        val variable = when (resolved) {
            is JSVariable -> resolved
            is JSDefinitionExpression -> resolved.parent as? JSVariable
            else -> PsiTreeUtil.getParentOfType(resolved, JSVariable::class.java, false)
        } ?: return false
        val initializer = variable.initializer
        val call = initializer as? JSCallExpression
            ?: PsiTreeUtil.getChildOfType(initializer, JSCallExpression::class.java)
        return isNamedCall(call, "useFlags")
    }

    enum class Kind { CALL_LITERAL, INDEX_ACCESS }
}
