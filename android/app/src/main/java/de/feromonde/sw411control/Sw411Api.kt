package de.feromonde.sw411control

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class Sw411State(
    val connected: Boolean = false,
    val firmware: String? = null,
    val input: Int? = null,
    val inputName: String? = null,
    val inputNames: List<String> = listOf("HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4"),
    val power: Boolean? = null,
    val autoSwitch: Boolean? = null,
    val earc: Boolean? = null,
    val temperature: Double? = null,
    val port: String? = null,
    val error: String? = null
)

data class Sw411Log(
    val time: String,
    val message: String,
    val level: String
)

data class UpdateStatus(
    val state: String,
    val currentVersion: String? = null,
    val targetVersion: String? = null,
    val error: String? = null
)

class Sw411Api(baseUrl: String) {
    private val baseUrl = normalizeBaseUrl(baseUrl)
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .build()

    private val streamClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    suspend fun status(): Sw411State = getJson("/api/status?force=1").toState()

    suspend fun logs(): List<Sw411Log> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url("/api/logs")).get().build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val arr = JSONArray(body)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Sw411Log(
                            time = o.optString("time"),
                            message = o.optString("message"),
                            level = o.optString("level", "info")
                        )
                    )
                }
            }
        }
    }

    suspend fun selectInput(input: Int) {
        post("/api/input/$input", null)
    }

    suspend fun setAuto(enabled: Boolean) {
        post("/api/auto", JSONObject().put("enabled", enabled))
    }

    suspend fun setEarc(enabled: Boolean) {
        post("/api/earc", JSONObject().put("enabled", enabled))
    }

    suspend fun togglePower(): Boolean? {
        val result = post("/api/power/toggle", null)
        return if (result.has("power") && !result.isNull("power")) result.optBoolean("power") else null
    }

    suspend fun reboot() {
        post("/api/reboot", null)
    }

    suspend fun updateController() {
        post("/api/update-control", null)
    }

    suspend fun updateStatus(): UpdateStatus {
        val o = getJson("/api/update-status")
        return UpdateStatus(
            state = o.optString("state", "unknown"),
            currentVersion = o.optNullableString("current_version"),
            targetVersion = o.optNullableString("target_version"),
            error = o.optNullableString("error")
        )
    }

    suspend fun streamEvents(
        onConnected: (Boolean) -> Unit,
        onState: (Sw411State) -> Unit
    ) = withContext(Dispatchers.IO) {
        while (currentCoroutineContext().isActive) {
            try {
                val request = Request.Builder()
                    .url(url("/api/events"))
                    .header("Accept", "text/event-stream")
                    .get()
                    .build()

                streamClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("SSE HTTP ${response.code}")
                    onConnected(true)

                    val source = response.body?.source() ?: error("Empty SSE body")
                    while (currentCoroutineContext().isActive) {
                        val line = source.readUtf8Line() ?: break
                        if (line.startsWith("data:")) {
                            val payload = line.removePrefix("data:").trim()
                            if (payload.isNotEmpty()) {
                                onState(JSONObject(payload).toState())
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                onConnected(false)
            }

            if (currentCoroutineContext().isActive) {
                delay(1500)
            }
        }
    }

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url(path)).get().build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            JSONObject(response.body?.string().orEmpty())
        }
    }

    private suspend fun post(path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val payload = (body ?: JSONObject()).toString().toRequestBody(jsonType)
        val req = Request.Builder().url(url(path)).post(payload).build()
        client.newCall(req).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(raw).optString("error") }.getOrNull()
                error(message?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}")
            }
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }

    private fun url(path: String): String = baseUrl + path

    companion object {
        fun normalizeBaseUrl(value: String): String {
            var v = value.trim()
            if (!v.startsWith("http://") && !v.startsWith("https://")) {
                v = "http://$v"
            }
            return v.trimEnd('/')
        }
    }
}

private fun JSONObject.toState(): Sw411State {
    val names = optJSONArray("input_names")?.let { arr ->
        buildList {
            for (i in 0 until arr.length()) add(arr.optString(i, "HDMI ${i + 1}"))
        }
    }?.takeIf { it.size == 4 } ?: listOf("HDMI 1", "HDMI 2", "HDMI 3", "HDMI 4")

    val input = if (has("input") && !isNull("input")) optInt("input") else null

    return Sw411State(
        connected = optBoolean("connected", false),
        firmware = optNullableString("firmware"),
        input = input,
        inputName = optNullableString("input_name") ?: input?.let { names.getOrNull(it - 1) },
        inputNames = names,
        power = optNullableBoolean("power"),
        autoSwitch = optNullableBoolean("auto_switch"),
        earc = optNullableBoolean("earc"),
        temperature = if (has("temperature") && !isNull("temperature")) optDouble("temperature") else null,
        port = optNullableString("port"),
        error = optNullableString("error")
    )
}

private fun JSONObject.optNullableBoolean(name: String): Boolean? =
    if (has(name) && !isNull(name)) optBoolean(name) else null

private fun JSONObject.optNullableString(name: String): String? =
    if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotBlank() } else null
