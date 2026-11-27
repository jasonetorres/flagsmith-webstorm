package com.jasontorres.flagsmith

import com.google.gson.GsonBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import javax.swing.JButton
import java.awt.FlowLayout
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.MessageDialogBuilder
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.net.URI
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.event.ListSelectionListener
import kotlin.concurrent.thread

/**
 * Experiments list and detail view, wired to the real Flagsmith Experimentation API.
 *
 * Endpoints (all under /api/v1):
 * - GET /environments/{key}/experiments/ — paginated list (`results`, `status_counts`)
 * - GET /environments/{key}/experiments/{id}/ — detail, includes `experiment_rollout`
 * - GET /environments/{key}/experiments/{id}/results/ — warehouse stats (`{ results: ... | null }`)
 * - GET /environments/{key}/experiments/{id}/exposures/ — warehouse exposures
 * - GET /environments/{key}/experiments/results/?feature={name} — trait-based A/B results
 */
class ExperimentsTab(private val project: Project) {
    private var panel: JPanel? = null
    private var mainSplit: JSplitPane? = null
    private var experimentList: JBList<String>? = null
    private var detailName: JBLabel? = null
    private var detailStatus: JBLabel? = null
    private var detailFeature: JBLabel? = null
    private var detailVariants: JBLabel? = null
    private var detailMetrics: JBLabel? = null
    private var detailRollout: JBLabel? = null
    private var detailHypothesis: JBLabel? = null
    private var detailStarted: JBLabel? = null
    private var resultsLabel: JBLabel? = null
    private var detailResults: JBTextArea? = null
    private var spinner: JProgressBar? = null
    private var emptyPanel: JPanel? = null
    private var emptyLabel: JBLabel? = null
    private var settingsLink: ActionLink? = null
    private var startButton: JButton? = null
    private var pauseButton: JButton? = null
    private var completeButton: JButton? = null
    private var traitResultsPanel: TraitResultsPanel? = null
    private var selectedExperiment: Experiment? = null

    private var experiments: List<Experiment> = emptyList()
    private val listModel = DefaultListModel<String>()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()

    fun createPanel(): JComponent {
        panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
        }

        val topPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(4)
        }
        val actionGroup = DefaultActionGroup(
            object : AnAction("Refresh", "Reload experiments", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    loadExperiments()
                }
            },
            Separator(),
            object : AnAction("Open in Dashboard", "Open Flagsmith dashboard", null) {
                override fun actionPerformed(e: AnActionEvent) {
                    val settings = service<FlagsmithSettings>()
                    val url = FlagsmithClient(
                        settings.apiBaseUrl,
                        settings.loadApiKey(),
                        settings.environmentKey
                    ).getDashboardUrl()
                    Desktop.getDesktop().browse(URI(url))
                }
            }
        )
        val toolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionToolbar("FlagsmithExperimentsToolbar", actionGroup, true)
        toolbar.targetComponent = panel
        topPanel.add(toolbar.component, BorderLayout.EAST)
        panel!!.add(topPanel, BorderLayout.NORTH)

        // Stacked: the tool window is a narrow side panel, so a side-by-side
        // split leaves the list a few pixels wide.
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT).apply {
            resizeWeight = 0.2
            isContinuousLayout = true
        }
        mainSplit = splitPane

        experimentList = JBList(listModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
        }
        experimentList!!.addListSelectionListener(ListSelectionListener { e ->
            if (e.valueIsAdjusting) return@ListSelectionListener
            val idx = experimentList?.selectedIndex ?: return@ListSelectionListener
            val exp = experiments.getOrNull(idx) ?: return@ListSelectionListener
            showExperimentDetail(exp)
            loadExperimentExtras(exp)
        })
        splitPane.leftComponent = JBScrollPane(experimentList!!)

        val detailPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
        }

        val formPanel = JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.emptyBottom(8)
            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = JBUI.insets(2, 0)
                weightx = 1.0
            }

            detailName = JBLabel("").apply {
                font = font.deriveFont(Font.BOLD, 14f)
            }
            gbc.gridx = 0
            gbc.gridy = 0
            gbc.gridwidth = 2
            add(detailName!!, gbc)

            fun addRow(row: Int, label: String, value: JBLabel) {
                gbc.gridy = row
                gbc.gridx = 0
                gbc.gridwidth = 1
                gbc.weightx = 0.0
                add(JBLabel(label), gbc)
                gbc.gridx = 1
                gbc.weightx = 1.0
                add(value, gbc)
            }

            detailStatus = JBLabel("")
            addRow(1, "Status:", detailStatus!!)
            detailFeature = JBLabel("")
            addRow(2, "Flag:", detailFeature!!)
            detailVariants = JBLabel("")
            addRow(3, "Variants:", detailVariants!!)
            detailMetrics = JBLabel("")
            addRow(4, "Metrics:", detailMetrics!!)
            detailRollout = JBLabel("")
            addRow(5, "Rollout:", detailRollout!!)
            detailStarted = JBLabel("")
            addRow(6, "Started:", detailStarted!!)

            detailHypothesis = JBLabel("").apply {
                font = font.deriveFont(Font.PLAIN)
            }
            gbc.gridy = 7
            gbc.gridx = 0
            gbc.gridwidth = 2
            gbc.weightx = 1.0
            add(JBLabel("Hypothesis:"), gbc)
            gbc.gridy = 8
            add(detailHypothesis!!, gbc)
        }
        startButton = JButton("Start").apply { isEnabled = false; addActionListener { runLifecycle("start") } }
        pauseButton = JButton("Pause").apply { isEnabled = false; addActionListener { runLifecycle("pause") } }
        completeButton = JButton("Complete").apply { isEnabled = false; addActionListener { runLifecycle("complete") } }
        val lifecyclePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(startButton)
            add(pauseButton)
            add(completeButton)
        }
        val northStack = JPanel(BorderLayout())
        northStack.add(formPanel, BorderLayout.CENTER)
        northStack.add(lifecyclePanel, BorderLayout.SOUTH)
        detailPanel.add(northStack, BorderLayout.NORTH)

        resultsLabel = JBLabel("Results").apply {
            font = font.deriveFont(Font.BOLD)
        }
        detailResults = JBTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = UIManager.getFont("TextArea.font")
            background = UIManager.getColor("TextArea.background")
            text = "Select an experiment to view details."
        }
        traitResultsPanel = TraitResultsPanel()
        val resultsNorth = JPanel(BorderLayout())
        resultsNorth.add(resultsLabel!!, BorderLayout.NORTH)
        resultsNorth.add(traitResultsPanel!!, BorderLayout.CENTER)
        val resultsPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(8)
            add(resultsNorth, BorderLayout.NORTH)
            add(JBScrollPane(detailResults!!), BorderLayout.CENTER)
        }
        detailPanel.add(resultsPanel, BorderLayout.CENTER)
        splitPane.rightComponent = detailPanel
        panel!!.add(splitPane, BorderLayout.CENTER)

        emptyLabel = JBLabel(emptyStateHtml("No experiments found for this environment.")).apply {
            horizontalAlignment = SwingConstants.CENTER
        }
        settingsLink = ActionLink("Configure Flagsmith in Settings") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "Flagsmith")
        }
        emptyPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(24)
            add(emptyLabel!!, BorderLayout.CENTER)
            val linkWrap = JPanel().apply {
                isOpaque = false
                add(settingsLink)
            }
            add(linkWrap, BorderLayout.SOUTH)
            isVisible = false
        }
        // Overlay-style empty state sits in the same center area; toggled with the split pane.
        val center = JPanel(BorderLayout())
        center.add(splitPane, BorderLayout.CENTER)
        center.add(emptyPanel!!, BorderLayout.SOUTH)
        panel!!.remove(splitPane)
        panel!!.add(center, BorderLayout.CENTER)

        spinner = JProgressBar().apply {
            isIndeterminate = true
            isVisible = false
            maximumSize = Dimension(100, 16)
            border = JBUI.Borders.empty(4, 0, 4, 0)
        }
        panel!!.add(spinner!!, BorderLayout.SOUTH)

        return panel!!
    }

    fun loadExperiments() {
        val settings = service<FlagsmithSettings>()
        if (!settings.isConfigured) {
            ApplicationManager.getApplication().invokeLater {
                showEmptyState("Flagsmith is not configured. Add your API URL, Organisation API key, and Environment key to load experiments.")
            }
            return
        }

        ApplicationManager.getApplication().invokeLater {
            spinner?.isVisible = true
            emptyPanel?.isVisible = false
            mainSplit?.isVisible = true
        }
        thread {
            val client = FlagsmithClient(
                settings.apiBaseUrl,
                settings.loadApiKey(),
                settings.environmentKey
            )
            val result = client.getExperiments()
            ApplicationManager.getApplication().invokeLater {
                spinner?.isVisible = false
                when (result) {
                    is FlagsmithApiResult.Ok -> {
                        experiments = result.value.results
                        updateList()
                    }
                    is FlagsmithApiResult.Err -> {
                        experiments = emptyList()
                        listModel.clear()
                        showEmptyState("Couldn't load experiments. ${result.message}")
                    }
                }
            }
        }
    }

    private fun updateList() {
        listModel.clear()
        if (experiments.isEmpty()) {
            showEmptyState(
                "No experiments in this environment yet. " +
                    "Flagsmith experiments are A/B tests attached to a multivariate flag. " +
                    "Create one in the Flagsmith dashboard: pick a multivariate flag, add a hypothesis and a metric, then start the experiment. " +
                    "If you are running the hackathon demo, look for “New checkout v2” after entering your keys in Settings, then hit Refresh."
            )
            return
        }
        emptyPanel?.isVisible = false
        mainSplit?.isVisible = true
        experiments.forEach { exp ->
            listModel.addElement("${exp.name} (${exp.status})")
        }
        // The split may have been laid out while hidden, which collapses the list.
        SwingUtilities.invokeLater {
            val rows = experiments.size.coerceIn(1, 4)
            val listHeight = (experimentList?.fixedCellHeight?.takeIf { it > 0 } ?: 24) * rows + 12
            mainSplit?.dividerLocation = listHeight
            if (experimentList?.selectedIndex == -1 && experiments.isNotEmpty()) {
                experimentList?.selectedIndex = 0
            }
        }
    }

    private fun showExperimentDetail(exp: Experiment) {
        selectedExperiment = exp
        detailName?.text = exp.name
        detailStatus?.text = exp.status.replaceFirstChar { it.uppercase() }
        detailFeature?.text = exp.feature?.name ?: "—"
        detailVariants?.text = formatVariants(exp)
        detailMetrics?.text = formatMetrics(exp)
        detailRollout?.text = formatRollout(exp)
        detailStarted?.text = formatTimestamp(exp.startedAt) ?: "Not started"
        detailHypothesis?.text = "<html><body style='width:280px'>${escape(exp.hypothesis ?: "—")}</body></html>"
        detailResults?.text = "Loading results…"
        resultsLabel?.text = "Results"
        updateLifecycleButtons(exp)
    }

    private fun loadExperimentExtras(exp: Experiment) {
        val settings = service<FlagsmithSettings>()
        if (!settings.isConfigured) return

        thread {
            val client = FlagsmithClient(
                settings.apiBaseUrl,
                settings.loadApiKey(),
                settings.environmentKey
            )
            val detail = client.getExperiment(exp.id)
            val warehouseResults = client.getExperimentResults(exp.id)
            val exposures = client.getExperimentExposures(exp.id)
            val featureName = when (detail) {
                is FlagsmithApiResult.Ok -> detail.value.feature?.name
                else -> exp.feature?.name
            }
            val traitResults = featureName?.let { client.getTraitExperimentResults(it) }

            ApplicationManager.getApplication().invokeLater {
                if (detail is FlagsmithApiResult.Ok) {
                    showExperimentDetail(detail.value)
                }
                when (traitResults) {
                    is FlagsmithApiResult.Ok -> traitResultsPanel?.showResults(traitResults.value)
                    else -> traitResultsPanel?.clear()
                }
                detailResults?.text = buildResultsText(warehouseResults, exposures, traitResults)
                resultsLabel?.text = "Results"
            }
        }
    }

    private fun buildResultsText(
        warehouseResults: FlagsmithApiResult<ExperimentComputation?>,
        exposures: FlagsmithApiResult<ExperimentComputation?>,
        traitResults: FlagsmithApiResult<TraitExperimentResults>?
    ): String {
        val sections = mutableListOf<String>()

        when (traitResults) {
            is FlagsmithApiResult.Ok -> sections.add(formatTraitResults(traitResults.value))
            is FlagsmithApiResult.Err -> sections.add("Trait-based results: ${traitResults.message}")
            null -> {}
        }

        when (warehouseResults) {
            is FlagsmithApiResult.Ok -> {
                val computation = warehouseResults.value
                if (computation?.payload?.isNotEmpty() == true) {
                    val asOf = formatTimestamp(computation.asOf) ?: "unknown"
                    val finalNote = if (computation.isFinal == true) " (final)" else ""
                    sections.add("Warehouse results as of $asOf$finalNote:\n${prettyGson.toJson(computation.payload)}")
                } else if (computation?.lastErrorAt != null) {
                    sections.add("Warehouse results failed to compute at ${formatTimestamp(computation.lastErrorAt)}.")
                } else {
                    sections.add(
                        "No warehouse results yet. Flagsmith computes lift, credible intervals, and win probability " +
                            "here when a data warehouse is connected to the environment " +
                            "(GET /environments/{key}/experiments/{id}/results/). " +
                            "Trait-based results above do not require a warehouse."
                    )
                }
            }
            is FlagsmithApiResult.Err -> sections.add("Warehouse results: ${warehouseResults.message}")
        }

        when (exposures) {
            is FlagsmithApiResult.Ok -> {
                val computation = exposures.value
                if (computation?.payload?.isNotEmpty() == true) {
                    sections.add("Exposures:\n${prettyGson.toJson(computation.payload)}")
                }
            }
            is FlagsmithApiResult.Err -> sections.add("Exposures: ${exposures.message}")
        }

        return sections.joinToString("\n\n")
    }

    private fun formatTraitResults(results: TraitExperimentResults): String {
        val stats = results.statistics
        val lines = mutableListOf<String>()
        lines.add("Trait-based A/B results for ${results.feature}")
        lines.add("Variant        Evaluations  Conversions  Rate")
        results.variants.forEach { variant ->
            lines.add(
                "%-14s %11d  %11d  %s%%".format(
                    variant.variant,
                    variant.evaluations,
                    variant.conversions,
                    variant.conversionRate
                )
            )
        }
        if (stats != null) {
            lines.add("")
            val significant = when (stats.significant) {
                true -> "yes"
                false -> "no"
                null -> "—"
            }
            lines.add("Winner: ${stats.winner ?: "—"}")
            lines.add("Lift: ${stats.lift ?: "—"}")
            lines.add("p-value: ${stats.pValue ?: "—"}  Significant: $significant")
            val chances = stats.chanceToWin?.entries?.joinToString(", ") { (name, chance) ->
                "$name ${"%.1f".format(chance * 100)}%"
            }
            if (!chances.isNullOrBlank()) {
                lines.add("Chance to win: $chances")
            }
            if (!stats.recommendation.isNullOrBlank()) {
                lines.add(stats.recommendation)
            }
            if (!stats.sampleSizeWarning.isNullOrBlank()) {
                lines.add(stats.sampleSizeWarning)
            }
        }
        return lines.joinToString("\n")
    }


    private fun updateLifecycleButtons(exp: Experiment) {
        val status = exp.status.lowercase()
        startButton?.isEnabled = status == "created" || status == "paused"
        pauseButton?.isEnabled = status == "running"
        completeButton?.isEnabled = status == "running" || status == "paused"
    }

    private fun runLifecycle(action: String) {
        val exp = selectedExperiment ?: return
        val settings = service<FlagsmithSettings>()
        if (!settings.isConfigured) return

        when (action) {
            "start" -> {
                if (!MessageDialogBuilder.yesNo("Start experiment", "Start \"${exp.name}\"? Enrolled identities will begin receiving the experiment rollout.")
                        .ask(project)
                ) return
            }
            "pause" -> {
                if (!MessageDialogBuilder.yesNo("Pause experiment", "Pause \"${exp.name}\"? You can start it again later.")
                        .ask(project)
                ) return
            }
            "complete" -> {
                // Completing is irreversible. Ask clearly, and do not auto-complete during tests.
                if (!MessageDialogBuilder.yesNo(
                        "Complete experiment",
                        "Complete \"${exp.name}\"? This cannot be undone. You'll choose whether to keep the control, roll out a variant, or disable the flag."
                    ).ask(project)
                ) return
            }
        }

        var decision: String? = null
        var variantId: Int? = null
        if (action == "complete") {
            val choices = arrayOf("control", "variant", "disable")
            val picked = Messages.showEditableChooseDialog(
                "What should this feature serve after the experiment ends?",
                "Rollout decision",
                null,
                choices,
                "control",
                null
            ) ?: return
            decision = picked
            if (decision == "variant") {
                val options = exp.feature?.multivariateOptions.orEmpty()
                if (options.isEmpty()) {
                    Messages.showErrorDialog(project, "This experiment has no variants to roll out.", "Flagsmith")
                    return
                }
                val labels = options.map { "${it.displayValue()} (id ${it.id})" }.toTypedArray()
                val chosen = Messages.showEditableChooseDialog(
                    "Which variant should be rolled out?",
                    "Rollout variant",
                    null,
                    labels,
                    labels.first(),
                    null
                ) ?: return
                variantId = options[labels.indexOf(chosen)].id
            }
        }

        AppExecutorUtil.getAppExecutorService().execute {
            val client = settings.client()
            val result = when (action) {
                "start" -> client.startExperiment(exp.id)
                "pause" -> client.pauseExperiment(exp.id)
                "complete" -> client.completeExperiment(exp.id, decision ?: "control", variantId)
                else -> FlagsmithApiResult.Err("Unknown action")
            }
            ApplicationManager.getApplication().invokeLater {
                when (result) {
                    is FlagsmithApiResult.Ok -> {
                        val updated = result.value
                        experiments = experiments.map { if (it.id == updated.id) updated else it }
                        updateList()
                        val idx = experiments.indexOfFirst { it.id == updated.id }
                        if (idx >= 0) experimentList?.selectedIndex = idx
                        showExperimentDetail(updated)
                        loadExperimentExtras(updated)
                    }
                    is FlagsmithApiResult.Err -> {
                        Messages.showErrorDialog(project, "Couldn't $action experiment: ${result.message}", "Flagsmith")
                    }
                }
            }
        }
    }

    private fun formatVariants(exp: Experiment): String {
        val feature = exp.feature
        val control = exp.experimentRollout?.featureStateValue?.value
            ?: feature?.initialValue?.toString()
        val options = feature?.multivariateOptions.orEmpty()
        val allocations = exp.experimentRollout?.multivariateFeatureStateValues
            ?.associate { it.multivariateFeatureOption to it.percentageAllocation }
            .orEmpty()
        val parts = mutableListOf<String>()
        if (!control.isNullOrBlank()) {
            parts.add("control $control")
        }
        options.forEach { option ->
            val alloc = allocations[option.id] ?: option.defaultPercentageAllocation
            val allocText = alloc?.let { " ${trimNumber(it)}%" } ?: ""
            parts.add("${option.displayValue()}$allocText")
        }
        return parts.joinToString(", ").ifBlank { "—" }
    }

    private fun formatMetrics(exp: Experiment): String {
        val metrics = exp.metrics.orEmpty()
        if (metrics.isEmpty()) return "—"
        return metrics.joinToString(", ") { metric ->
            val name = metric.metricName ?: "metric ${metric.metric ?: metric.id}"
            val direction = metric.expectedDirection?.let { " ($it)" } ?: ""
            "$name$direction"
        }
    }

    private fun formatRollout(exp: Experiment): String {
        val rollout = exp.experimentRollout ?: return "—"
        val pct = rollout.rolloutPercentage?.let { "${trimNumber(it)}% enrolled" } ?: "enrolment unknown"
        val enabled = when (rollout.enabled) {
            true -> "enabled"
            false -> "disabled"
            null -> ""
        }
        return listOf(pct, enabled).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun trimNumber(value: Double): String {
        return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
    }

    private fun formatTimestamp(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.replace('T', ' ').removeSuffix("Z").take(19) + " UTC"
    }

    private fun showEmptyState(message: String) {
        emptyLabel?.text = emptyStateHtml(message)
        emptyPanel?.isVisible = true
        mainSplit?.isVisible = experiments.isNotEmpty()
    }

    private fun emptyStateHtml(message: String): String {
        return "<html><body style='width:360px;text-align:center'>${escape(message)}</body></html>"
    }

    private fun escape(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
