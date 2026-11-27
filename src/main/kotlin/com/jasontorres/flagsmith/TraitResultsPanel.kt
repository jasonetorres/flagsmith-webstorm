package com.jasontorres.flagsmith

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingConstants

/**
 * Visual summary of trait-based A/B results: winner, significance badge,
 * lift, p-value, and per-variant conversion bars.
 */
class TraitResultsPanel : JPanel(BorderLayout()) {
    private val winnerLabel = JBLabel("").apply { font = font.deriveFont(Font.BOLD, 14f) }
    private val badge = JBLabel("", SwingConstants.CENTER).apply {
        isOpaque = true
        border = JBUI.Borders.empty(2, 8)
        font = font.deriveFont(Font.BOLD)
    }
    private val liftLabel = JBLabel("")
    private val pValueLabel = JBLabel("")
    private val recommendationLabel = JBLabel("")
    private val barsPanel = JPanel(GridBagLayout())

    init {
        border = JBUI.Borders.emptyBottom(8)
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        header.add(winnerLabel)
        header.add(badge)
        val meta = JPanel(FlowLayout(FlowLayout.LEFT, 12, 0))
        meta.add(liftLabel)
        meta.add(pValueLabel)
        val north = JPanel(BorderLayout())
        north.add(header, BorderLayout.NORTH)
        north.add(meta, BorderLayout.CENTER)
        north.add(recommendationLabel, BorderLayout.SOUTH)
        add(north, BorderLayout.NORTH)
        add(barsPanel, BorderLayout.CENTER)
        isVisible = false
    }

    fun clear() {
        isVisible = false
        barsPanel.removeAll()
    }

    fun showResults(results: TraitExperimentResults) {
        val stats = results.statistics
        val winner = stats?.winner
        winnerLabel.text = if (winner.isNullOrBlank()) "No winner yet" else "Winner: $winner"
        when (stats?.significant) {
            true -> {
                badge.text = "Significant"
                badge.background = JBColor(Color(0xE8F5E9), Color(0x1B5E20))
                badge.foreground = JBColor(Color(0x1B5E20), Color(0xC8E6C9))
                badge.isVisible = true
            }
            false -> {
                badge.text = "Not significant"
                badge.background = JBColor(Color(0xEEEEEE), Color(0x424242))
                badge.foreground = JBColor(Color(0x424242), Color(0xEEEEEE))
                badge.isVisible = true
            }
            null -> badge.isVisible = false
        }
        liftLabel.text = "Lift: ${stats?.lift ?: "—"}"
        pValueLabel.text = "p-value: ${stats?.pValue ?: "—"}"
        recommendationLabel.text = listOfNotNull(stats?.recommendation, stats?.sampleSizeWarning)
            .joinToString("  ")
        recommendationLabel.isVisible = recommendationLabel.text.isNotBlank()

        barsPanel.removeAll()
        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(2, 0)
            fill = GridBagConstraints.HORIZONTAL
        }
        results.variants.forEachIndexed { index, variant ->
            gbc.gridy = index
            gbc.gridx = 0
            gbc.weightx = 0.0
            barsPanel.add(JBLabel(variant.variant + "  "), gbc)
            gbc.gridx = 1
            gbc.weightx = 1.0
            val bar = JProgressBar(0, 100).apply {
                value = variant.conversionRate.toInt().coerceIn(0, 100)
                isStringPainted = true
                string = "${variant.conversionRate}%  (${variant.conversions}/${variant.evaluations})"
                preferredSize = Dimension(160, 16)
            }
            barsPanel.add(bar, gbc)
        }
        barsPanel.revalidate()
        barsPanel.repaint()
        isVisible = true
    }
}
