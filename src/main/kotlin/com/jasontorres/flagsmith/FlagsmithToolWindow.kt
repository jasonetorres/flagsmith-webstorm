package com.jasontorres.flagsmith

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.jasontorres.flagsmith.editor.FlagActions
import org.jetbrains.annotations.NotNull
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel
import kotlin.concurrent.thread

/**
 * Factory that creates the Flagsmith tool window in the right sidebar.
 */
class FlagsmithToolWindow : ToolWindowFactory {
    override fun createToolWindowContent(@NotNull project: Project, @NotNull toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val flagsTab = FlagsTab(project)
        val experimentsTab = ExperimentsTab(project)

        val flagsContent = contentManager.factory.createContent(
            flagsTab.createPanel(), "Flags", false
        )
        val experimentsContent = contentManager.factory.createContent(
            experimentsTab.createPanel(), "Experiments", false
        )

        contentManager.addContent(flagsContent)
        contentManager.addContent(experimentsContent)

        // Load initial data
        flagsTab.loadFlags()
        experimentsTab.loadExperiments()
    }
}

/**
 * Custom table model for feature flags with editable Enabled column.
 */
class FlagsTableModel(
    private val onToggle: (FeatureState, Boolean) -> Unit,
    private val onValueChange: (FeatureState, String) -> Unit
) : AbstractTableModel() {
    private var flags: List<FeatureState> = emptyList()

    val columnNames = arrayOf("Name", "Enabled", "Value", "Description")
    private val columnClasses = arrayOf<Class<*>>(String::class.java, java.lang.Boolean::class.java, String::class.java, String::class.java)

    fun setFlags(flags: List<FeatureState>) {
        this.flags = flags
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = flags.size
    override fun getColumnCount(): Int = 4
    override fun getColumnName(col: Int): String = columnNames[col]
    override fun getColumnClass(col: Int): Class<*> = columnClasses[col]
    override fun isCellEditable(row: Int, col: Int): Boolean = col == 1 || col == 2

    override fun getValueAt(row: Int, col: Int): Any? {
        val fs = flags[row]
        return when (col) {
            0 -> fs.feature.name
            1 -> fs.enabled
            2 -> fs.value?.toString() ?: ""
            3 -> fs.feature.description ?: ""
            else -> null
        }
    }

    override fun setValueAt(aValue: Any?, row: Int, col: Int) {
        val fs = flags[row]
        if (col == 1 && aValue is Boolean) {
            // Optimistic update so the checkbox flips immediately; rolled back on failure
            flags = flags.toMutableList().also { it[row] = fs.copy(enabled = aValue) }
            fireTableCellUpdated(row, col)
            onToggle(fs, aValue)
        } else if (col == 2) {
            val newValue = aValue?.toString() ?: ""
            flags = flags.toMutableList().also { it[row] = fs.copy(value = newValue.ifBlank { null }) }
            fireTableCellUpdated(row, col)
            onValueChange(fs, newValue)
        }
    }

    fun getFlag(row: Int): FeatureState = flags[row]
}

/**
 * Tab 1: Feature Flags table with toggle, search, and refresh.
 */
class FlagsTab(private val project: Project) {
    private var panel: JPanel? = null
    private var table: JBTable? = null
    private var searchField: JBTextField? = null
    private var spinner: JProgressBar? = null
    private var emptyPanel: JPanel? = null
    private var emptyLabel: JBLabel? = null
    private var settingsLink: ActionLink? = null
    private var scrollPane: JScrollPane? = null

    private var allFlags: List<FeatureState> = emptyList()
    private var filteredFlags: List<FeatureState> = emptyList()

    private var isUpdatingModel = false
    private val tableModel = FlagsTableModel(
        onToggle = { flag, enabled -> toggleFlag(flag, enabled) },
        onValueChange = { flag, value -> updateValue(flag, value) }
    )

    fun createPanel(): JComponent {
        panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
        }

        // ---- Top bar: search + refresh + open dashboard ----
        val topPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(4)
        }
        searchField = JBTextField().apply {
            toolTipText = "Filter flags by name"
        }
        searchField!!.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = applyFilter()
            override fun removeUpdate(e: DocumentEvent?) = applyFilter()
            override fun changedUpdate(e: DocumentEvent?) = applyFilter()
        })
        topPanel.add(searchField!!, BorderLayout.CENTER)

        val buttonGroup = DefaultActionGroup(
            object : AnAction("New Flag", "Create a Flagsmith flag", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    FlagActions.createFlag(project)
                }
            },
            RefreshFlagsAction(project, this@FlagsTab),
            Separator(),
            object : AnAction("Open in Dashboard", "Open Flagsmith dashboard", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    val settings = service<FlagsmithSettings>()
                    val url = FlagsmithClient(
                        settings.apiBaseUrl,
                        settings.loadApiKey(),
                        settings.environmentKey
                    ).getDashboardUrl()
                    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                }
            }
        )
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "FlagsmithFlagsToolbar", buttonGroup, true
        )
        toolbar.targetComponent = panel
        topPanel.add(toolbar.component, BorderLayout.EAST)

        // ---- Table ----
        table = JBTable(tableModel).apply {
            autoCreateRowSorter = true
            columnModel.getColumn(1).maxWidth = 70
            columnModel.getColumn(1).minWidth = 70
            showHorizontalLines = true
        }

        scrollPane = JScrollPane(table!!)

        // ---- Empty state ----
        emptyLabel = JBLabel("").apply {
            horizontalAlignment = SwingConstants.CENTER
        }
        settingsLink = ActionLink("") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "com.jasontorres.flagsmith.settings")
        }

        emptyPanel = JPanel(GridLayout(2, 1)).apply {
            add(emptyLabel!!)
            add(settingsLink!!)
            setVisible(false)
        }

        // ---- Spinner ----
        spinner = JProgressBar().apply {
            isIndeterminate = true
            setVisible(false)
            maximumSize = Dimension(100, 16)
            border = JBUI.Borders.empty(4, 0, 4, 0)
        }

        // ---- Assemble ----
        val centerPanel = JPanel(BorderLayout())
        centerPanel.add(scrollPane!!, BorderLayout.CENTER)
        centerPanel.add(emptyPanel!!, BorderLayout.NORTH)

        panel!!.add(topPanel, BorderLayout.NORTH)
        panel!!.add(centerPanel, BorderLayout.CENTER)
        panel!!.add(spinner!!, BorderLayout.SOUTH)

        project.messageBus.connect().subscribe(flagsmithTopic, object : FlagsmithTopic {
            override fun onFlagsChanged(flags: List<FeatureState>) {
                ApplicationManager.getApplication().invokeLater {
                    showCachedFlags()
                }
            }
        })

        return panel!!
    }

    fun loadFlags() {
        val settings = service<FlagsmithSettings>()
        FlagCache.getInstance(project).start()
        if (!settings.isConfigured) {
            ApplicationManager.getApplication().invokeLater {
                showEmptyState("Flagsmith is not configured.", "Configure Flagsmith in Settings")
            }
            return
        }

        ApplicationManager.getApplication().invokeLater {
            spinner?.setVisible(true)
            emptyPanel?.setVisible(false)
            scrollPane?.setVisible(true)
        }
        FlagCache.getInstance(project).refresh()
        // The cache publishes onFlagsChanged; showCachedFlags applies the result.
        ApplicationManager.getApplication().invokeLater {
            spinner?.setVisible(false)
            showCachedFlags()
        }
    }

    fun showCachedFlags() {
        spinner?.setVisible(false)
        allFlags = FlagCache.getInstance(project).flags()
        applyFilter()
    }

    private fun applyFilter() {
        val query = searchField?.text?.lowercase() ?: ""
        filteredFlags = if (query.isEmpty()) allFlags else allFlags.filter {
            it.feature.name.lowercase().contains(query)
        }
        updateTable()
    }

    private fun updateTable() {
        isUpdatingModel = true
        try {
            if (allFlags.isEmpty()) {
                showEmptyState(
                    "No feature flags found for this environment.",
                    "Configure Flagsmith in Settings"
                )
                tableModel.setFlags(emptyList())
                return
            }

            if (filteredFlags.isEmpty()) {
                showEmptyState("No flags match your search.", "")
                tableModel.setFlags(emptyList())
                return
            }

            // Show table, hide empty state
            emptyPanel?.setVisible(false)
            scrollPane?.setVisible(true)
            tableModel.setFlags(filteredFlags)
        } finally {
            isUpdatingModel = false
        }
    }

    private fun showEmptyState(message: String, linkText: String) {
        emptyLabel?.text = message
        if (linkText.isNotBlank()) {
            settingsLink?.text = linkText
            settingsLink?.setVisible(true)
        } else {
            settingsLink?.setVisible(false)
        }
        emptyPanel?.setVisible(true)
        scrollPane?.setVisible(false)
    }

    private fun toggleFlag(flag: FeatureState, newEnabled: Boolean) {
        FlagActions.toggleFlag(project, flag, newEnabled)
    }

    private fun updateValue(flag: FeatureState, newValue: String) {
        FlagActions.updateValue(project, flag, newValue) {
            showCachedFlags()
        }
    }
}

/**
 * Action to refresh the flags list.
 */
class RefreshFlagsAction(
    private val project: Project,
    private val flagsTab: FlagsTab
) : DumbAwareAction("Refresh", "Reload feature flags from Flagsmith", AllIcons.Actions.Refresh) {
    override fun actionPerformed(@NotNull e: AnActionEvent) {
        flagsTab.loadFlags()
    }
}
