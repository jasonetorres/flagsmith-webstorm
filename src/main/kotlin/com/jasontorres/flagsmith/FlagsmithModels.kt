package com.jasontorres.flagsmith

import com.google.gson.annotations.SerializedName

/**
 * A feature flag state from the Flagsmith API.
 * Represents one flag in an environment with its enabled state and value.
 */
data class FeatureState(
    val id: Int,
    val enabled: Boolean,
    @SerializedName("feature_state_value") val value: Any?,
    val feature: FeatureInfo
)

/**
 * Minimal feature metadata returned inside a feature state.
 */
data class FeatureInfo(
    val id: Int,
    val name: String,
    val description: String? = null,
    val type: String? = null
)

/**
 * Paginated list of feature states from GET /api/v1/environments/{key}/features/
 */
data class FeatureStatesList(
    val results: List<FeatureState>
)

/**
 * Request body for toggling a feature state via the experimental API.
 * PATCH /api/v1/environments/{key}/features/{id}/
 */
data class ToggleFeatureStateRequest(
    val environmentDefault: EnvironmentDefault? = null
)

data class EnvironmentDefault(
    val enabled: Boolean,
    val value: ValueObject? = null,
    val variants: List<VariantWeight>? = null
)

data class ValueObject(
    val type: String,
    val value: String
)

data class VariantWeight(
    val id: Int,
    val weight: Double
)

/**
 * Experiment from GET /api/v1/environments/{key}/experiments/
 * and GET /api/v1/environments/{key}/experiments/{id}/.
 *
 * Field names follow the Flagsmith API (snake_case in JSON).
 */
data class Experiment(
    val id: Int,
    val name: String,
    val hypothesis: String? = null,
    val status: String,
    val feature: ExperimentFeature? = null,
    val metrics: List<ExperimentMetric>? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    @SerializedName("started_at") val startedAt: String? = null,
    @SerializedName("ended_at") val endedAt: String? = null,
    @SerializedName("rollout_decision") val rolloutDecision: String? = null,
    @SerializedName("rollout_variant") val rolloutVariant: Any? = null,
    @SerializedName("experiment_rollout") val experimentRollout: ExperimentRollout? = null
)

/**
 * Feature info inside a list/detail experiment response, including multivariate options.
 */
data class ExperimentFeature(
    val id: Int,
    val name: String,
    val type: String? = null,
    @SerializedName("initial_value") val initialValue: Any? = null,
    @SerializedName("multivariate_options") val multivariateOptions: List<MultivariateOption>? = null
)

/**
 * A multivariate option on a feature. There is no `label` field; the readable
 * value comes from key / string_value / integer_value / boolean_value.
 */
data class MultivariateOption(
    val id: Int,
    val uuid: String? = null,
    val type: String? = null,
    @SerializedName("integer_value") val integerValue: Int? = null,
    @SerializedName("string_value") val stringValue: String? = null,
    @SerializedName("boolean_value") val booleanValue: Boolean? = null,
    @SerializedName("default_percentage_allocation") val defaultPercentageAllocation: Double? = null,
    val key: String? = null
) {
    fun displayValue(): String {
        return when {
            !key.isNullOrBlank() -> key
            !stringValue.isNullOrBlank() -> stringValue
            integerValue != null -> integerValue.toString()
            booleanValue != null -> booleanValue.toString()
            else -> "#$id"
        }
    }
}

data class ExperimentMetric(
    val id: Int,
    val metric: Int? = null,
    @SerializedName("metric_name") val metricName: String? = null,
    val aggregation: String? = null,
    val direction: String? = null,
    @SerializedName("expected_direction") val expectedDirection: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
)

data class ExperimentRollout(
    val enabled: Boolean? = null,
    @SerializedName("rollout_percentage") val rolloutPercentage: Double? = null,
    @SerializedName("feature_state_value") val featureStateValue: TypedValue? = null,
    @SerializedName("multivariate_feature_state_values") val multivariateFeatureStateValues: List<RolloutVariantAllocation>? = null
)

data class TypedValue(
    val type: String? = null,
    val value: String? = null
)

data class RolloutVariantAllocation(
    @SerializedName("multivariate_feature_option") val multivariateFeatureOption: Int? = null,
    @SerializedName("percentage_allocation") val percentageAllocation: Double? = null
)

/**
 * Paginated list from GET /api/v1/environments/{key}/experiments/.
 * CustomPagination wraps results and the view adds status_counts.
 */
data class ExperimentsList(
    val count: Int? = null,
    val next: String? = null,
    val previous: String? = null,
    val results: List<Experiment> = emptyList(),
    @SerializedName("status_counts") val statusCounts: Map<String, Int>? = null
)

/**
 * Cached warehouse computation (results or exposures).
 * GET .../experiments/{id}/results/ returns `{ "results": ExperimentComputation | null }`.
 * GET .../experiments/{id}/exposures/ returns `{ "exposures": ExperimentComputation | null }`.
 */
data class ExperimentComputation(
    @SerializedName("as_of") val asOf: String? = null,
    @SerializedName("last_error_at") val lastErrorAt: String? = null,
    @SerializedName("refresh_requested_at") val refreshRequestedAt: String? = null,
    val payload: Map<String, Any>? = null,
    @SerializedName("is_final") val isFinal: Boolean? = null
)

data class ExperimentResultsResponse(
    val results: ExperimentComputation? = null
)

data class ExperimentExposuresResponse(
    val exposures: ExperimentComputation? = null
)

/**
 * Trait-based A/B results from
 * GET /api/v1/environments/{key}/experiments/results/?feature={name}.
 * Identities set `exp_{feature}_variant` and `exp_{feature}_converted`.
 */
data class TraitExperimentResults(
    val feature: String,
    val variants: List<TraitVariantResult> = emptyList(),
    val statistics: TraitExperimentStatistics? = null
)

data class TraitVariantResult(
    val variant: String,
    val evaluations: Int,
    val conversions: Int,
    @SerializedName("conversion_rate") val conversionRate: Double
)

data class TraitExperimentStatistics(
    @SerializedName("p_value") val pValue: Double? = null,
    val significant: Boolean? = null,
    @SerializedName("chance_to_win") val chanceToWin: Map<String, Double>? = null,
    val lift: String? = null,
    val winner: String? = null,
    val recommendation: String? = null,
    @SerializedName("sample_size_warning") val sampleSizeWarning: String? = null
)

/** Result wrapper so the Experiments tab can show API errors instead of a blank list. */
sealed class FlagsmithApiResult<out T> {
    data class Ok<T>(val value: T) : FlagsmithApiResult<T>()
    data class Err(val message: String) : FlagsmithApiResult<Nothing>()
}


/**
 * Environment from GET /api/v1/environments/{key}/.
 * `project` is the numeric project id.
 */
data class EnvironmentInfo(
    val id: Int,
    val name: String? = null,
    val project: Int,
    @SerializedName("api_key") val apiKey: String? = null
)

/**
 * Feature created via POST /api/v1/projects/{project_id}/features/.
 */
data class CreatedFeature(
    val id: Int,
    val name: String,
    val description: String? = null,
    val type: String? = null,
    @SerializedName("default_enabled") val defaultEnabled: Boolean? = null,
    @SerializedName("initial_value") val initialValue: Any? = null
)

