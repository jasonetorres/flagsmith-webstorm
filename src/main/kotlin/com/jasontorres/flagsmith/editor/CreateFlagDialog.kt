package com.jasontorres.flagsmith.editor

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

class CreateFlagDialog(
    project: Project,
    prefillName: String = ""
) : DialogWrapper(project) {
    private val nameField = JBTextField(prefillName).apply { columns = 32 }
    private val descriptionField = JBTextField().apply { columns = 32 }
    private val enabledBox = JBCheckBox("Enabled in new environments", false)
    private val valueField = JBTextField().apply { columns = 32 }

    init {
        title = "Create Flagsmith Flag"
        init()
    }

    val flagName: String get() = nameField.text.trim()
    val flagDescription: String get() = descriptionField.text.trim()
    val defaultEnabled: Boolean get() = enabledBox.isSelected
    val initialValue: String get() = valueField.text

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4, 0)
        }

        fun row(row: Int, label: String, field: JComponent, weight: Double = 1.0) {
            gbc.gridy = row
            gbc.gridx = 0
            gbc.weightx = 0.0
            panel.add(JBLabel(label), gbc)
            gbc.gridx = 1
            gbc.weightx = weight
            panel.add(field, gbc)
        }

        row(0, "Name:", nameField)
        row(1, "Description:", descriptionField)
        gbc.gridy = 2
        gbc.gridx = 0
        gbc.gridwidth = 2
        panel.add(enabledBox, gbc)
        gbc.gridwidth = 1
        row(3, "Initial value:", valueField)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField
}
