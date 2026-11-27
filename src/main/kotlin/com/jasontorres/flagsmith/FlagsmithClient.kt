package com.jasontorres.flagsmith

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

private val gson: Gson = GsonBuilder().create()

/**
 * HTTP client for the Flagsmith Management API and environment SDK endpoints.
 * All methods are designed to be called from a background thread.
 */
class FlagsmithClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val environmentKey: String
) {
    /**
     * Test the connection by fetching the projects list.
     * Returns true if the API responds with 200.
     */
    fun testConnection(): Boolean {
        return try {
            val conn = createConnection("/projects/")
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fetch all feature states for the configured environment.
     * Returns the list of flags or an empty list on failure.
     */
    fun getFeatureStates(): List<FeatureState> {
        return try {
            val conn = createConnection("/flags/")
            conn.setRequestProperty("X-Environment-Key", environmentKey)
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                gson.fromJson(response, Array<FeatureState>::class.java).toList()
            } else {
                conn.disconnect()
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * GET /api/v1/environments/{key}/ — includes the numeric project id.
     */
    fun getEnvironment(): FlagsmithApiResult<EnvironmentInfo> {
        return getJson("/environments/$environmentKey/", EnvironmentInfo::class.java)
    }

    /**
     * Toggle a feature flag's enabled state.
     * PATCH /api/v1/environments/{key}/featurestates/{id}/
     */
    fun toggleFeatureState(featureStateId: Int, newEnabled: Boolean, featureId: Int? = null): Boolean {
        val ok = sendJson<Unit>(
            "PATCH",
            "/environments/$environmentKey/featurestates/$featureStateId/",
            mapOf("enabled" to newEnabled),
            null
        ) is FlagsmithApiResult.Ok
        if (ok && featureId != null) syncExperimentRollouts(featureId, newEnabled)
        return ok
    }

    /**
     * Make the IDE toggle a real kill switch. A running experiment owns a
     * system segment override (its percentage rollout) that keeps the flag on
     * for identified users even after the environment default is turned off,
     * and Flagsmith refuses direct edits to that override (409). The supported
     * route is the experiment's rollout endpoint, so the rollout's `enabled`
     * follows the toggle; percentage, value and variant split stay as they are.
     * GET   /api/v1/environments/{key}/experiments/
     * GET   /api/v1/environments/{key}/experiments/{id}/
     * PATCH /api/v1/environments/{key}/experiments/{id}/rollout/
     */
    fun syncExperimentRollouts(featureId: Int, newEnabled: Boolean): Boolean {
        val experiments = (getExperiments() as? FlagsmithApiResult.Ok)?.value?.results ?: return false
        var allOk = true
        experiments
            .filter { it.feature?.id == featureId && it.status != "completed" }
            .forEach { exp ->
                val detail = getJson(
                    "/environments/$environmentKey/experiments/${exp.id}/",
                    Map::class.java
                ) as? FlagsmithApiResult.Ok ?: run { allOk = false; return@forEach }
                @Suppress("UNCHECKED_CAST")
                val rollout = (detail.value["experiment_rollout"] as? Map<String, Any?>)?.toMutableMap()
                    ?: return@forEach
                if (rollout["enabled"] == newEnabled) return@forEach
                rollout["enabled"] = newEnabled
                @Suppress("UNCHECKED_CAST")
                val audience = (rollout["audience"] as? Map<String, Any?>) ?: emptyMap()
                val segmentIds = ((audience["segment_ids"] ?: audience["segments"]) as? List<*>)
                    .orEmpty()
                    .mapNotNull { seg ->
                        when (seg) {
                            is Number -> seg.toInt()
                            is Map<*, *> -> (seg["id"] as? Number)?.toInt()
                            else -> null
                        }
                    }
                rollout["audience"] = mapOf(
                    "match" to (audience["match"] ?: "any"),
                    "segment_ids" to segmentIds
                )
                val r = sendJson<Unit>(
                    "PATCH",
                    "/environments/$environmentKey/experiments/${exp.id}/rollout/",
                    rollout,
                    null
                )
                if (r !is FlagsmithApiResult.Ok) allOk = false
            }
        return allOk
    }

    /**
     * Update the environment default value of a feature state.
     * PATCH /api/v1/environments/{key}/featurestates/{id}/
     * with `{"feature_state_value": <value>}`. The view treats this as a raw
     * value and types it via generate_feature_state_value_data.
     */
    fun updateFeatureStateValue(featureStateId: Int, newValue: String?): Boolean {
        return sendJson<Unit>(
            "PATCH",
            "/environments/$environmentKey/featurestates/$featureStateId/",
            mapOf("feature_state_value" to newValue),
            null
        ) is FlagsmithApiResult.Ok
    }

    /**
     * Create a STANDARD feature in a project.
     * POST /api/v1/projects/{project_id}/features/
     */
    fun createFeature(
        projectId: Int,
        name: String,
        description: String,
        defaultEnabled: Boolean,
        initialValue: String
    ): FlagsmithApiResult<CreatedFeature> {
        val body = mutableMapOf<String, Any>(
            "name" to name,
            "description" to description,
            "default_enabled" to defaultEnabled,
            "type" to "STANDARD"
        )
        if (initialValue.isNotEmpty()) {
            body["initial_value"] = initialValue
        }
        return sendJson("POST", "/projects/$projectId/features/", body, CreatedFeature::class.java)
    }

    /**
     * Fetch all experiments for the configured environment.
     * GET /api/v1/environments/{key}/experiments/
     */
    fun getExperiments(): FlagsmithApiResult<ExperimentsList> {
        return getJson("/environments/$environmentKey/experiments/", ExperimentsList::class.java)
    }

    /**
     * Fetch a single experiment including experiment_rollout.
     * GET /api/v1/environments/{key}/experiments/{id}/
     */
    fun getExperiment(experimentId: Int): FlagsmithApiResult<Experiment> {
        return getJson("/environments/$environmentKey/experiments/$experimentId/", Experiment::class.java)
    }

    /**
     * Warehouse-computed statistical results for one experiment.
     * GET /api/v1/environments/{key}/experiments/{id}/results/
     */
    fun getExperimentResults(experimentId: Int): FlagsmithApiResult<ExperimentComputation?> {
        return when (val result = getJson(
            "/environments/$environmentKey/experiments/$experimentId/results/",
            ExperimentResultsResponse::class.java
        )) {
            is FlagsmithApiResult.Ok -> FlagsmithApiResult.Ok(result.value.results)
            is FlagsmithApiResult.Err -> result
        }
    }

    /**
     * Warehouse-computed variant exposure counts for one experiment.
     * GET /api/v1/environments/{key}/experiments/{id}/exposures/
     */
    fun getExperimentExposures(experimentId: Int): FlagsmithApiResult<ExperimentComputation?> {
        return when (val result = getJson(
            "/environments/$environmentKey/experiments/$experimentId/exposures/",
            ExperimentExposuresResponse::class.java
        )) {
            is FlagsmithApiResult.Ok -> FlagsmithApiResult.Ok(result.value.exposures)
            is FlagsmithApiResult.Err -> result
        }
    }

    /**
     * Trait-based A/B results for a feature.
     * GET /api/v1/environments/{key}/experiments/results/?feature={name}
     */
    fun getTraitExperimentResults(featureName: String): FlagsmithApiResult<TraitExperimentResults> {
        val encoded = URLEncoder.encode(featureName, StandardCharsets.UTF_8)
        return getJson(
            "/environments/$environmentKey/experiments/results/?feature=$encoded",
            TraitExperimentResults::class.java
        )
    }

    /** POST /api/v1/environments/{key}/experiments/{id}/start/ */
    fun startExperiment(experimentId: Int): FlagsmithApiResult<Experiment> {
        return sendJson(
            "POST",
            "/environments/$environmentKey/experiments/$experimentId/start/",
            emptyMap<String, Any>(),
            Experiment::class.java
        )
    }

    /** POST /api/v1/environments/{key}/experiments/{id}/pause/ */
    fun pauseExperiment(experimentId: Int): FlagsmithApiResult<Experiment> {
        return sendJson(
            "POST",
            "/environments/$environmentKey/experiments/$experimentId/pause/",
            emptyMap<String, Any>(),
            Experiment::class.java
        )
    }

    /**
     * POST /api/v1/environments/{key}/experiments/{id}/complete/
     * Body: { "rollout_decision": "control"|"variant"|"disable", "rollout_variant": <id>? }
     */
    fun completeExperiment(
        experimentId: Int,
        rolloutDecision: String,
        rolloutVariantId: Int? = null
    ): FlagsmithApiResult<Experiment> {
        val body = mutableMapOf<String, Any>("rollout_decision" to rolloutDecision)
        if (rolloutVariantId != null) {
            body["rollout_variant"] = rolloutVariantId
        }
        return sendJson(
            "POST",
            "/environments/$environmentKey/experiments/$experimentId/complete/",
            body,
            Experiment::class.java
        )
    }

    /**
     * Get the dashboard URL (removes /api/v1 from the base URL).
     */
    fun getDashboardUrl(): String {
        return baseUrl.replace("/api/v1", "")
    }

    private fun <T> getJson(path: String, type: Class<T>): FlagsmithApiResult<T> {
        return try {
            val conn = createConnection(path)
            conn.requestMethod = "GET"
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code in 200..299) {
                parseBody(body, type, path)
            } else {
                FlagsmithApiResult.Err(httpError(code, body, path))
            }
        } catch (e: Exception) {
            FlagsmithApiResult.Err(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun <T> sendJson(
        method: String,
        path: String,
        body: Any?,
        type: Class<T>?
    ): FlagsmithApiResult<T> {
        return try {
            val publisher = if (body == null) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                HttpRequest.BodyPublishers.ofString(gson.toJson(body))
            }
            val builder = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl$path"))
                .header("Authorization", "Api-Key $apiKey")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .method(method, publisher)
            if (body != null) {
                builder.header("Content-Type", "application/json")
            }
            val response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            val code = response.statusCode()
            val responseBody = response.body() ?: ""
            if (code in 200..299) {
                if (type == null) {
                    @Suppress("UNCHECKED_CAST")
                    FlagsmithApiResult.Ok(Unit as T)
                } else {
                    parseBody(responseBody, type, path)
                }
            } else {
                FlagsmithApiResult.Err(httpError(code, responseBody, path))
            }
        } catch (e: Exception) {
            FlagsmithApiResult.Err(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun <T> parseBody(body: String, type: Class<T>, path: String): FlagsmithApiResult<T> {
        val parsed = gson.fromJson(body, type)
        return if (parsed == null) {
            FlagsmithApiResult.Err("Empty response from $path")
        } else {
            FlagsmithApiResult.Ok(parsed)
        }
    }

    private fun httpError(code: Int, body: String, path: String): String {
        val detail = try {
            val map = gson.fromJson(body, Map::class.java)
            val value = map?.get("detail") ?: map?.get("message")
            value?.toString()
        } catch (_: Exception) {
            null
        }
        val suffix = if (!detail.isNullOrBlank()) ": $detail" else ""
        return "HTTP $code for $path$suffix"
    }

    private fun createConnection(path: String): HttpURLConnection {
        val url = URI.create("$baseUrl$path").toURL()
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Api-Key $apiKey")
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        return conn
    }

    companion object {
        private val HTTP: HttpClient = HttpClient.newHttpClient()
    }
}
