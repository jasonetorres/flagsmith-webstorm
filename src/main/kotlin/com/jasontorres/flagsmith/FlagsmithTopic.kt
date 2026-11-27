package com.jasontorres.flagsmith

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * Message-bus topic for broadcasting flag changes so the tool window, gutter
 * icons, inspections, and completion stay in sync with [FlagCache].
 */
interface FlagsmithTopic {
    fun onFlagToggled(flagName: String, enabled: Boolean) {}
    fun onFlagsChanged(flags: List<FeatureState>) {}
}

val flagsmithTopic: Topic<FlagsmithTopic> = Topic(
    "Flagsmith Flag Changes",
    FlagsmithTopic::class.java
)

fun Project.publishFlagToggled(flagName: String, enabled: Boolean) {
    messageBus.syncPublisher(flagsmithTopic).onFlagToggled(flagName, enabled)
}

fun Project.publishFlagsChanged(flags: List<FeatureState>) {
    messageBus.syncPublisher(flagsmithTopic).onFlagsChanged(flags)
}
