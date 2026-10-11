package de.feromonde.sw411control

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ControllerApiTest {
    @Test fun unknownDeviceValuesRemainUnknown() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"connected":false,"input":null,"power":null,"auto_switch":null,"earc":null,"temperature":null}"""))
            val state = Sw411Api(server.url("/").toString()).status()
            assertNull(state.input)
            assertNull(state.power)
            assertNull(state.autoSwitch)
            assertNull(state.earc)
            assertNull(state.temperature)
            assertEquals("/api/status?force=1", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test fun savingSettingsKeepsEmptyPasswordAndDoesNotOverwriteApiToken() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("""{"input_names":["PlayStation","Thomson","HDMI 3","HDMI 4"],"api_token":"server-token","mqtt":{"enabled":true,"host":"mqtt.local","port":1883,"connected":true}}"""))
            val saved = Sw411Api(server.url("/").toString()).saveSettings(ControllerSettings(
                inputNames = listOf(" PlayStation ", "Thomson", "", "HDMI 4"), apiToken = "do-not-send",
                mqtt = MqttSettings(enabled = true, host = " mqtt.local ", password = "")))
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/settings", request.path)
            val payload = JSONObject(request.body.readUtf8())
            assertFalse(payload.has("api_token"))
            assertEquals("", payload.getJSONObject("mqtt").getString("password"))
            assertEquals("mqtt.local", payload.getJSONObject("mqtt").getString("host"))
            assertEquals("HDMI 3", payload.getJSONArray("input_names").getString(2))
            assertEquals("server-token", saved.apiToken)
            assertTrue(saved.mqtt.connected)
        } finally { server.shutdown() }
    }

    @Test fun switchingInputUsesControllerRestCommand() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            Sw411Api(server.url("/").toString()).selectInput(2)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/input/2", request.path)
        } finally { server.shutdown() }
    }

    @Test fun stoppingLiveStatusCancelsAnIdleSocket() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val api = Sw411Api(server.url("/").toString())
            val job = launch { api.streamEvents({}, {}) }
            val request = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) }
            assertNotNull(request)
            withTimeout(1000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally { server.shutdown() }
    }

    @Test fun serverAddressRejectsCredentialsAndNonHttpSchemes() {
        assertEquals("http://192.168.2.159", Sw411Api.normalizeBaseUrl(" 192.168.2.159/ "))
        assertEquals("https://controller.local:8443/control", Sw411Api.normalizeBaseUrl("https://controller.local:8443/control/"))
        for (value in listOf("javascript:alert(1)", "file:///etc/passwd", "http://user:pass@controller.local", "http://controller.local:70000", "http://")) {
            assertTrue(value, runCatching { Sw411Api.normalizeBaseUrl(value) }.isFailure)
        }
    }
}
