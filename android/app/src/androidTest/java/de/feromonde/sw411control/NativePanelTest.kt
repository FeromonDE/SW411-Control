package de.feromonde.sw411control

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class NativePanelTest {
    private lateinit var server: MockWebServer
    private val requests = ConcurrentLinkedQueue<RecordedRequest>()
    @Volatile private var input = 1
    @Volatile private var names = listOf("PS5", "Thomson", "HDMI 3", "HDMI 4")
    private val compose = createAndroidComposeRule<MainActivity>()
    private val backend = object : ExternalResource() {
        override fun before() {
            server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add(request)
                    val path = request.path.orEmpty().substringBefore('?')
                    if (path == "/api/events") {
                        return MockResponse().setHeader("Content-Type", "text/event-stream")
                            .setChunkedBody("data: ${state()}\n\n" + ": keepalive\n\n".repeat(5000), 512)
                            .throttleBody(512, 1, TimeUnit.SECONDS)
                    }
                    val response = when {
                        path == "/api/status" -> state()
                        path == "/api/app" -> "{\"version\":\"0.4.1\"}"
                        path == "/api/logs" -> "[{\"time\":\"17:00:00\",\"message\":\"Устройство подключено\",\"level\":\"info\"}]"
                        path == "/api/settings" -> {
                            if (request.method == "PUT") {
                                val array = JSONObject(request.body.clone().readUtf8()).getJSONArray("input_names")
                                names = List(4) { array.getString(it) }
                            }
                            settings()
                        }
                        path.startsWith("/api/input/") -> { input = path.takeLast(1).toInt(); "{\"ok\":true}" }
                        else -> "{\"ok\":true}"
                    }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(response)
                }
            }
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("sw411", 0)
                .edit().putString("server_url", server.url("/").toString().trimEnd('/')).commit()
        }
        override fun after() { server.shutdown() }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(backend).around(compose)

    private fun state() = JSONObject().put("connected", true).put("firmware", "v2.10.13")
        .put("port", "/dev/sw411").put("input", input).put("input_names", JSONArray(names))
        .put("power", true).put("auto_switch", false).put("earc", true).put("temperature", 42).toString()
    private fun settings() = JSONObject().put("input_names", JSONArray(names)).put("api_token", "TEST-TOKEN-NOT-A-REAL-SECRET")
        .put("mqtt", JSONObject().put("enabled", false).put("host", "").put("port", 1883)
            .put("username", "").put("base_topic", "sw411").put("discovery_prefix", "homeassistant").put("connected", false)).toString()
    private fun ready() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("Подключено").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun screenIsNativeAndMatchesWebSections() {
        ready()
        compose.onNodeWithText("Быстрые действия").assertIsDisplayed()
        compose.onNodeWithText("Входы").assertIsDisplayed()
        compose.onNodeWithText("42 °C").assertIsDisplayed()
        fun containsWebView(view: View): Boolean = view is WebView ||
            (view is ViewGroup && (0 until view.childCount).any { containsWebView(view.getChildAt(it)) })
        compose.activityRule.scenario.onActivity { assertFalse(containsWebView(it.window.decorView)) }
        screenshot("native-home")
        compose.onNodeWithText("Ручной режим").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Audio EDID").assertIsDisplayed()
        screenshot("native-modes")
    }

    @Test fun switchingSourceSendsNativeRestCommand() {
        ready()
        compose.onNodeWithText("Thomson").performClick()
        compose.waitUntil(5000) { requests.any { it.method == "POST" && it.path == "/api/input/2" } }
        assertEquals(2, input)
    }

    @Test fun settingsAreEditedAndSavedInsideApp() {
        ready()
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithText("Настройки").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("HDMI 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("HDMI 1").performTextReplacement("PlayStation")
        compose.onNodeWithContentDescription("MQTT broker").performScrollTo().performTextReplacement("mqtt.local")
        screenshot("native-settings")
        compose.onNodeWithText("Сохранить", substring = false).performClick()
        compose.waitUntil(10000) { requests.any { it.method == "PUT" && it.path == "/api/settings" } }
        val payload = JSONObject(requests.first { it.method == "PUT" && it.path == "/api/settings" }.body.clone().readUtf8())
        assertEquals("PlayStation", payload.getJSONArray("input_names").getString(0))
        assertEquals("mqtt.local", payload.getJSONObject("mqtt").getString("host"))
        assertEquals("", payload.getJSONObject("mqtt").getString("password"))
        assertFalse(payload.has("api_token"))
        compose.waitUntil(10000) { compose.onAllNodesWithText("PlayStation").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun shortPressDoesNotReboot() {
        ready()
        compose.onNodeWithContentDescription("Перезагрузить").performTouchInput { click() }
        compose.waitForIdle()
        assertFalse(requests.any { it.path == "/api/reboot" })
        compose.onNodeWithText("Для перезагрузки удерживай 1,5 сек.").assertIsDisplayed()
    }

    @Test fun holdingRebootSendsCommandOnce() {
        ready()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Перезагрузить").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1700)
        compose.onNodeWithContentDescription("Перезагрузить").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitUntil(5000) { requests.any { it.path == "/api/reboot" } }
        assertEquals(1, requests.count { it.path == "/api/reboot" })
    }
}
