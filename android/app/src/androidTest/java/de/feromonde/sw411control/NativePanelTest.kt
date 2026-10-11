package de.feromonde.sw411control

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        val resolver = instrumentation.targetContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SW411-native-ui")
        }) ?: error("Cannot save native UI screenshot")
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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

    private fun assertSheetButtonsFit() {
        val decor = compose.activity.window.decorView
        val insets = ViewCompat.getRootWindowInsets(decor)!!
            .getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime())
        val bottom = decor.height - insets.bottom
        for (label in listOf("Отмена", "Сохранить")) {
            val button = compose.onNodeWithText(label, substring = false).assertIsDisplayed()
                .fetchSemanticsNode().boundsInWindow
            assertTrue("$label overlaps Android navigation or keyboard: $button, bottom=$bottom",
                button.bottom <= bottom && button.top >= 0)
            assertTrue("$label has been clipped", button.height >= 48 * decor.resources.displayMetrics.density)
        }
    }

    @Test fun menuRowsAreSeparateAndCompact() {
        ready()
        compose.onNodeWithContentDescription("Меню").performClick()
        val settings = compose.onNodeWithText("Настройки").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val server = compose.onNodeWithText("Адрес сервера").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val update = compose.onNodeWithContentDescription("Обновить из GitHub").assertIsDisplayed()
            .fetchSemanticsNode().boundsInWindow
        assertTrue("Menu rows overlap", settings.bottom < server.top && server.bottom < update.top)
        assertTrue("Menu is too tall for a phone", update.bottom - settings.top <
            250 * compose.activity.resources.displayMetrics.density)
        screenshot("native-menu")
    }

    @Test fun serverButtonsStayAboveNavigationAndKeyboard() {
        ready()
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithText("Адрес сервера").performClick()
        compose.waitForIdle()
        assertSheetButtonsFit()
        screenshot("native-server")
        compose.onNodeWithContentDescription("SW411 Control на Nexbox").performClick()
        compose.waitUntil(10000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitForIdle()
        assertSheetButtonsFit()
        screenshot("native-server-keyboard")
        compose.onNodeWithText("Отмена", substring = false).performClick()
        compose.onNodeWithContentDescription("Меню").assertIsDisplayed()
    }

    @Test fun settingsAreEditedAndSavedInsideApp() {
        ready()
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.onNodeWithText("Настройки").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("HDMI 1").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("HDMI 1").performTextReplacement("PlayStation")
        compose.onNodeWithContentDescription("MQTT broker").performScrollTo().performTextReplacement("mqtt.local")
        screenshot("native-settings")
        assertSheetButtonsFit()
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
