package com.jasontorres.flagsmith.editor

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

object FlagIcons {
    val enabled: Icon = StatusDot(JBColor(0x2E7D32, 0x66BB6A))
    val disabled: Icon = StatusDot(JBColor(0x9E9E9E, 0x757575))

    fun forFlag(enabled: Boolean): Icon = if (enabled) this.enabled else disabled
}

private class StatusDot(private val color: JBColor) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(10)
    override fun getIconHeight(): Int = JBUI.scale(10)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        val size = iconWidth - JBUI.scale(1)
        g2.fillOval(x, y, size, size)
        g2.dispose()
    }
}
