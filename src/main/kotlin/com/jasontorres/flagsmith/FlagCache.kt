package com.jasontorres.flagsmith

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Project-level cache of the current environment's feature flags.
 * Refreshed when the tool window loads, after a toggle or edit, and on a timer.
 */
@Service(Service.Level.PROJECT)
class FlagCache(private val project: Project) : Disposable {
    @Volatile
    private var flagsByName: Map<String, FeatureState> = emptyMap()

    @Volatile
    private var snapshot: List<FeatureState> = emptyList()

    private val started = AtomicBoolean(false)
    private val refreshInFlight = AtomicBoolean(false)
    private var periodic: ScheduledFuture<*>? = null

    fun start() {
        if (!started.compareAndSet(false, true)) {
            refresh()
            return
        }
        refresh()
        periodic = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            {
                if (!project.isDisposed) {
                    refresh()
                }
            },
            REFRESH_INTERVAL_SECONDS,
            REFRESH_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    fun flags(): List<FeatureState> = snapshot

    fun get(name: String): FeatureState? = flagsByName[name]

    fun knownNames(): Set<String> = flagsByName.keys

    fun isKnown(name: String): Boolean = flagsByName.containsKey(name)

    /**
     * Optimistic in-memory update (toggle / value edit). Publishes and
     * restarts the daemon so gutter icons update immediately.
     */
    fun replace(updated: FeatureState) {
        val next = snapshot.toMutableList()
        val idx = next.indexOfFirst { it.id == updated.id || it.feature.name == updated.feature.name }
        if (idx >= 0) {
            next[idx] = updated
        } else {
            next.add(updated)
        }
        apply(next, restartDaemon = true)
    }

    fun refresh() {
        val settings = service<FlagsmithSettings>()
        if (!settings.isConfigured) {
            apply(emptyList(), restartDaemon = snapshot.isNotEmpty())
            return
        }
        if (!refreshInFlight.compareAndSet(false, true)) return
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val client = settings.client()
                val flags = client.getFeatureStates()
                resolveProjectId(client, settings)
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        apply(flags, restartDaemon = true)
                    }
                }
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    private fun resolveProjectId(client: FlagsmithClient, settings: FlagsmithSettings) {
        if (settings.projectId > 0) return
        when (val result = client.getEnvironment()) {
            is FlagsmithApiResult.Ok -> {
                if (result.value.project > 0) {
                    settings.setProjectId(result.value.project)
                }
            }
            is FlagsmithApiResult.Err -> {}
        }
    }

    private fun apply(flags: List<FeatureState>, restartDaemon: Boolean) {
        val sorted = flags.sortedBy { it.feature.name }
        val changed = !sameSnapshot(snapshot, sorted)
        flagsByName = sorted.associateBy { it.feature.name }
        snapshot = sorted
        if (changed) {
            project.publishFlagsChanged(sorted)
            if (restartDaemon && !project.isDisposed) {
                DaemonCodeAnalyzer.getInstance(project).restart("Flagsmith flag cache updated")
            }
        }
    }

    private fun sameSnapshot(a: List<FeatureState>, b: List<FeatureState>): Boolean {
        if (a.size != b.size) return false
        return a.zip(b).all { (left, right) ->
            left.id == right.id &&
                left.enabled == right.enabled &&
                left.value == right.value &&
                left.feature.name == right.feature.name &&
                left.feature.description == right.feature.description
        }
    }

    override fun dispose() {
        periodic?.cancel(false)
        periodic = null
    }

    companion object {
        const val REFRESH_INTERVAL_SECONDS = 15L

        fun getInstance(project: Project): FlagCache = project.service()
    }
}

class FlagCacheStartup : com.intellij.openapi.startup.ProjectActivity {
    override suspend fun execute(project: Project) {
        FlagCache.getInstance(project).start()
    }
}
