package com.jasontorres.flagsmith

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import javax.swing.*
import javax.swing.border.EmptyBorder
import kotlin.concurrent.thread

/**
 * Persisted (non-secret) Flagsmith settings. The API key lives in PasswordSafe, not here.
 */
class FlagsmithState {
    var apiBaseUrl: String = "http://localhost:8000/api/v1"
    var environmentKey: String = ""
    var projectId: Int = 0
}

/**
 * Application-level settings service. Persists to FlagsmithSettings.xml and keeps the
 * Organisation API key in the IDE's PasswordSafe (macOS Keychain).
 */
@Service(Service.Level.APP)
@State(name = "FlagsmithState", storages = [Storage("FlagsmithSettings.xml")])
class FlagsmithSettings : PersistentStateComponent<FlagsmithState> {
    private var myState = FlagsmithState()

    override fun getState(): FlagsmithState = myState
    override fun loadState(state: FlagsmithState) {
        myState = state
    }

    private val credentialAttributes = CredentialAttributes(generateServiceName("Flagsmith", "orgApiKey"))

    val apiBaseUrl: String
        get() = myState.apiBaseUrl

    val environmentKey: String
        get() = myState.environmentKey

    val isConfigured: Boolean
        get() = myState.apiBaseUrl.isNotBlank() &&
                myState.environmentKey.isNotBlank() &&
                loadApiKey().isNotBlank()

    fun setApiBaseUrl(url: String) {
        myState.apiBaseUrl = url.trim().removeSuffix("/")
    }

    fun setEnvironmentKey(key: String) {
        myState.environmentKey = key.trim()
    }

    val projectId: Int
        get() = myState.projectId

    fun setProjectId(id: Int) {
        myState.projectId = id
    }

    fun client(): FlagsmithClient = FlagsmithClient(apiBaseUrl, loadApiKey(), environmentKey)

    @Volatile
    private var cachedApiKey: String? = null

    init {
        // PasswordSafe is a slow (keychain) operation — never hit it on the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            loadApiKeyFromSafe()
        }
    }

    /**
     * Returns the cached Organisation API key. Safe to call on the EDT.
     * If the cache is still cold, returns an empty string and a background load is used instead.
     */
    fun loadApiKey(): String {
        cachedApiKey?.let { return it }
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            return ""
        }
        return loadApiKeyFromSafe()
    }

    /** Blocking PasswordSafe read. Call from a background thread only. */
    fun loadApiKeyFromSafe(): String {
        val value = PasswordSafe.instance.getPassword(credentialAttributes) ?: ""
        cachedApiKey = value
        return value
    }

    fun saveApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        cachedApiKey = trimmed
        persistApiKey(trimmed)
    }

    fun clearApiKey() {
        cachedApiKey = ""
        persistApiKey("")
    }

    private fun persistApiKey(trimmed: String) {
        val write = Runnable {
            PasswordSafe.instance.setPassword(credentialAttributes, trimmed.ifBlank { null })
        }
        val app = ApplicationManager.getApplication()
        if (app.isDispatchThread) {
            app.executeOnPooledThread(write)
        } else {
            write.run()
        }
    }
}

/**
 * Configurable UI shown in Settings | Tools | Flagsmith.
 */
class FlagsmithConfigurable : Configurable, DumbAware {
    private var panel: JPanel? = null
    private var baseUrlField: JBTextField? = null
    private var apiKeyField: JBPasswordField? = null
    private var envKeyField: JBTextField? = null
    private var testStatusLabel: JBLabel? = null

    private val settings: FlagsmithSettings by lazy {
        com.intellij.openapi.components.service<FlagsmithSettings>()
    }

    override fun getDisplayName(): String = "Flagsmith"

    override fun createComponent(): JComponent {
        panel = JPanel(GridBagLayout()).apply {
            border = EmptyBorder(12, 12, 12, 12)
        }
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 0, 4, 0)
        }

        // API Base URL
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0
        panel!!.add(JBLabel("API Base URL:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        baseUrlField = JBTextField().apply {
            columns = 30
            toolTipText = "e.g. http://localhost:8000/api/v1"
        }
        panel!!.add(baseUrlField!!, gbc)

        // Master API Key
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0
        panel!!.add(JBLabel("Master API Key:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        apiKeyField = JBPasswordField().apply {
            columns = 30
            toolTipText = "Organisation Master API Key"
        }
        panel!!.add(apiKeyField!!, gbc)

        // Environment Key
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        panel!!.add(JBLabel("Environment Key:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        envKeyField = JBTextField().apply {
            columns = 30
            toolTipText = "The SDK environment key (public)"
        }
        panel!!.add(envKeyField!!, gbc)

        // Test button + status
        gbc.gridx = 0; gbc.gridy = 3; gbc.gridwidth = 2
        gbc.anchor = GridBagConstraints.EAST
        gbc.weightx = 0.0
        val testPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
        val testButton = JButton("Test Connection")
        testButton.addActionListener(object : ActionListener {
            override fun actionPerformed(e: ActionEvent?) {
                testConnection()
            }
        })
        testStatusLabel = JBLabel("Not tested")
        testPanel.add(testButton)
        testPanel.add(testStatusLabel!!)
        panel!!.add(testPanel, gbc)

        return panel!!
    }

    private fun testConnection() {
        javax.swing.SwingUtilities.invokeLater {
            testStatusLabel?.text = "Testing..."
            testStatusLabel?.foreground = Color.GRAY
        }
        thread {
            val client = FlagsmithClient(
                baseUrl = baseUrlField?.text?.removeSuffix("/") ?: "",
                apiKey = String(apiKeyField?.password ?: charArrayOf()),
                environmentKey = envKeyField?.text ?: ""
            )
            val result = client.testConnection()
            javax.swing.SwingUtilities.invokeLater {
                testStatusLabel?.text = if (result) "Connected" else "Failed"
                testStatusLabel?.foreground = if (result) Color.GREEN else Color.RED
            }
        }
    }

    override fun isModified(): Boolean {
        return baseUrlField?.text != settings.apiBaseUrl ||
                envKeyField?.text != settings.environmentKey
    }

    override fun apply() {
        settings.setApiBaseUrl(baseUrlField?.text ?: "")
        settings.saveApiKey(String(apiKeyField?.password ?: charArrayOf()))
        settings.setEnvironmentKey(envKeyField?.text ?: "")
        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.forEach { project ->
            FlagCache.getInstance(project).refresh()
        }
    }

    override fun reset() {
        baseUrlField?.text = settings.apiBaseUrl
        envKeyField?.text = settings.environmentKey
        testStatusLabel?.text = "Not tested"
        testStatusLabel?.foreground = null
        val cached = settings.loadApiKey()
        if (cached.isNotEmpty()) {
            apiKeyField?.text = cached
        } else {
            // Cache may still be cold on first open; fill the field off the EDT.
            apiKeyField?.text = ""
            thread {
                val key = settings.loadApiKeyFromSafe()
                javax.swing.SwingUtilities.invokeLater {
                    apiKeyField?.text = key
                }
            }
        }
    }

    override fun disposeUIResources() {
        panel = null
    }
}
