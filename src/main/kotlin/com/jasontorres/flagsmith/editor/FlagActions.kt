package com.jasontorres.flagsmith.editor

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.util.concurrency.AppExecutorUtil
import com.jasontorres.flagsmith.FeatureState
import com.jasontorres.flagsmith.FlagCache
import com.jasontorres.flagsmith.FlagsmithApiResult
import com.jasontorres.flagsmith.FlagsmithClient
import com.jasontorres.flagsmith.FlagsmithSettings
import com.jasontorres.flagsmith.publishFlagToggled
import java.awt.Component
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseEvent

object FlagActions {
    fun tooltip(flag: FeatureState): String {
        val state = if (flag.enabled) "on" else "off"
        val value = flag.value?.toString()?.ifBlank { "—" } ?: "—"
        val description = flag.feature.description?.ifBlank { null }
        return buildString {
            append("Flagsmith: ${flag.feature.name}\n")
            append("Enabled: $state\n")
            append("Value: $value")
            if (description != null) {
                append("\n")
                append(description)
            }
        }
    }

    fun tailText(flag: FeatureState): String {
        val state = if (flag.enabled) "on" else "off"
        val value = flag.value?.toString()?.ifBlank { null }
        return if (value == null) "  $state" else "  $state · $value"
    }

    fun showFlagPopup(project: Project, flag: FeatureState, input: Any?) {
        val group = DefaultActionGroup().apply {
            add(object : AnAction(if (flag.enabled) "Toggle off" else "Toggle on") {
                override fun actionPerformed(e: AnActionEvent) {
                    toggleFlag(project, flag, !flag.enabled)
                }
            })
            add(object : AnAction("Copy value") {
                override fun actionPerformed(e: AnActionEvent) {
                    val text = flag.value?.toString() ?: ""
                    CopyPasteManager.getInstance().setContents(StringSelection(text))
                }
            })
            add(object : AnAction("Open in Flagsmith dashboard") {
                override fun actionPerformed(e: AnActionEvent) {
                    openDashboard(project)
                }
            })
        }
        val popup: JBPopup = JBPopupFactory.getInstance()
            .createActionGroupPopup(
                flag.feature.name,
                group,
                dataContextFor(input),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true
            )
        showPopup(popup, input)
    }

    fun toggleFlag(project: Project, flag: FeatureState, enabled: Boolean) {
        val settings = service<FlagsmithSettings>()
        if (!settings.isConfigured) {
            notify(project, "Flagsmith is not configured.", NotificationType.WARNING)
            return
        }
        FlagCache.getInstance(project).replace(flag.copy(enabled = enabled))
        project.publishFlagToggled(flag.feature.name, enabled)
        AppExecutorUtil.getAppExecutorService().execute {
            val ok = settings.client().toggleFeatureState(flag.id, enabled)
            ApplicationManager.getApplication().invokeLater {
                if (!ok) {
                    FlagCache.getInstance(project).replace(flag)
                    project.publishFlagToggled(flag.feature.name, flag.enabled)
                    notify(project, "Failed to toggle flag '${flag.feature.name}'. Change rolled back.", NotificationType.ERROR)
                } else {
                    FlagCache.getInstance(project).refresh()
                }
            }
        }
    }

    fun updateValue(project: Project, flag: FeatureState, newValue: String, onFailure: () -> Unit = {}) {
        val settings = service<FlagsmithSettings>()
        if (!settings.isConfigured) {
            notify(project, "Flagsmith is not configured.", NotificationType.WARNING)
            onFailure()
            return
        }
        val updated = flag.copy(value = newValue.ifBlank { null })
        FlagCache.getInstance(project).replace(updated)
        AppExecutorUtil.getAppExecutorService().execute {
            val ok = settings.client().updateFeatureStateValue(flag.id, newValue)
            ApplicationManager.getApplication().invokeLater {
                if (!ok) {
                    FlagCache.getInstance(project).replace(flag)
                    notify(project, "Failed to update value for '${flag.feature.name}'.", NotificationType.ERROR)
                    onFailure()
                } else {
                    FlagCache.getInstance(project).refresh()
                }
            }
        }
    }

    fun createFlag(
        project: Project,
        prefillName: String = "",
        onCreated: (() -> Unit)? = null
    ) {
        val settings = service<FlagsmithSettings>()
        if (!settings.isConfigured) {
            Messages.showWarningDialog(project, "Configure Flagsmith in Settings | Tools | Flagsmith first.", "Flagsmith")
            return
        }
        val dialog = CreateFlagDialog(project, prefillName)
        if (!dialog.showAndGet()) return
        val name = dialog.flagName
        if (name.isBlank()) {
            Messages.showErrorDialog(project, "A flag name is required.", "Flagsmith")
            return
        }
        AppExecutorUtil.getAppExecutorService().execute {
            val client = settings.client()
            val projectId = resolveProjectId(settings, client)
            if (projectId == null) {
                ApplicationManager.getApplication().invokeLater {
                    notify(project, "Couldn't determine the Flagsmith project id from this environment.", NotificationType.ERROR)
                }
                return@execute
            }
            val result = client.createFeature(
                projectId = projectId,
                name = name,
                description = dialog.flagDescription,
                defaultEnabled = dialog.defaultEnabled,
                initialValue = dialog.initialValue
            )
            ApplicationManager.getApplication().invokeLater {
                when (result) {
                    is FlagsmithApiResult.Ok -> {
                        notify(project, "Created flag '${result.value.name}'.", NotificationType.INFORMATION)
                        FlagCache.getInstance(project).refresh()
                        onCreated?.invoke()
                    }
                    is FlagsmithApiResult.Err -> {
                        notify(project, "Couldn't create flag '$name'. ${result.message}", NotificationType.ERROR)
                    }
                }
            }
        }
    }

    fun resolveProjectId(settings: FlagsmithSettings, client: FlagsmithClient): Int? {
        if (settings.projectId > 0) return settings.projectId
        return when (val result = client.getEnvironment()) {
            is FlagsmithApiResult.Ok -> {
                val id = result.value.project
                if (id > 0) settings.setProjectId(id)
                id.takeIf { it > 0 }
            }
            is FlagsmithApiResult.Err -> null
        }
    }

    fun openDashboard(project: Project) {
        val settings = service<FlagsmithSettings>()
        val url = FlagsmithClient(settings.apiBaseUrl, settings.loadApiKey(), settings.environmentKey).getDashboardUrl()
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        } catch (e: Exception) {
            notify(project, "Couldn't open the Flagsmith dashboard: ${e.message}", NotificationType.ERROR)
        }
    }

    fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Flagsmith")
            .createNotification("Flagsmith", message, type)
            .notify(project)
    }

    private fun showPopup(popup: JBPopup, input: Any?) {
        when (input) {
            is MouseEvent -> popup.show(RelativePointWrap.point(input))
            is Component -> popup.showInCenterOf(input)
            else -> popup.showInFocusCenter()
        }
    }

    private fun dataContextFor(input: Any?): DataContext {
        val component = when (input) {
            is AnActionEvent -> return input.dataContext
            is java.awt.event.MouseEvent -> input.component
            is Component -> input
            else -> java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        }
        return if (component != null) {
            DataManager.getInstance().getDataContext(component)
        } else {
            DataContext.EMPTY_CONTEXT
        }
    }
}

private object RelativePointWrap {
    fun point(event: MouseEvent) = com.intellij.ui.awt.RelativePoint(event)
}
