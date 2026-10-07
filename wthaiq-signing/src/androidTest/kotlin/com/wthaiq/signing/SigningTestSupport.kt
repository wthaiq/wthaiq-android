package com.wthaiq.signing

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.wthaiq.signing.internal.TestHooks
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Serves fixture pages for every URL the WebView requests, so the tests never touch the network. */
object Fixtures {
    private class Page(val status: Int, val html: String)

    private val pages = ConcurrentHashMap<String, Page>()
    val requested: MutableList<String> = CopyOnWriteArrayList()

    fun install() {
        pages.clear()
        requested.clear()
        TestHooks.responder = { request ->
            val url = request.url.toString()
            requested.add(url)
            val key = request.url.host + request.url.path
            val page = pages[key]
            val status = page?.status ?: 404
            val body = page?.html ?: "<!doctype html><title>Not found</title>"
            WebResourceResponse(
                "text/html",
                "utf-8",
                status,
                if (status == 200) "OK" else "Error",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(body.toByteArray()),
            )
        }
    }

    fun uninstall() {
        TestHooks.responder = null
        pages.clear()
    }

    /** Registers [html] for `host/path` (the query is ignored). */
    fun page(hostAndPath: String, html: String, status: Int = 200) {
        pages[hostAndPath] = Page(status, html)
    }

    fun document(body: String, script: String = ""): String =
        "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width'>" +
            "</head><body style='margin:0;font:16px sans-serif'>$body<script>$script</script></body></html>"

    /** A script that posts an embedded signing event to the page's own window, as the embed wrapper does. */
    fun post(event: String, extra: String = ""): String =
        "parent.postMessage({source:'wthaiq',event:'$event',type:'wthaiq:$event'," +
            "signature_request:'sr_test',signer:'sgr_test'$extra},'*');"
}

/** Collects events delivered through the contract's listener. */
class EventRecorder : SigningEventListener {
    val events: MutableList<SigningEvent> = CopyOnWriteArrayList()

    override fun onSigningEvent(event: SigningEvent) {
        events.add(event)
    }

    inline fun <reified T : SigningEvent> has(): Boolean = events.any { it is T }
}

class SigningSession(
    val scenario: ActivityScenario<WthaiqSigningActivity>,
    val recorder: EventRecorder,
) {
    /** Waits for the activity to finish and parses its result like the contract does. */
    fun awaitResult(): SigningResult {
        // Asking for the result while the activity is still running makes ActivityScenario wait for its own 45 s
        // timeout even though the result arrives in time, so wait for the activity to finish first.
        waitUntil(timeoutMillis = 20_000, message = "activity to finish") { scenario.state == Lifecycle.State.DESTROYED }
        val result = scenario.result
        return WthaiqSigning.Contract().parseResult(result.resultCode, result.resultData)
    }

    /** The result the fragment holds right now. */
    fun currentResult(): SigningResult? {
        var value: SigningResult? = null
        scenario.onActivity { activity ->
            val fragment = activity.supportFragmentManager.fragments.firstOrNull() as? WthaiqSigningFragment
            value = fragment?.result
        }
        return value
    }

    /** The URL the WebView shows, read on the main thread. */
    fun currentUrl(): String {
        var url: String? = null
        scenario.onActivity { url = it.findViewById<WebView>(R.id.wthaiq_signing_web_view)?.url }
        return url.orEmpty()
    }

    fun evaluate(script: String) {
        scenario.onActivity { activity ->
            activity.findViewById<WebView>(R.id.wthaiq_signing_web_view).evaluateJavascript(script, null)
        }
    }

    /** Taps the centre of the element with [id] with real touch events, so the WebView sees a user gesture. */
    fun tapElement(id: String) {
        val latch = CountDownLatch(1)
        var rect: String? = null
        scenario.onActivity { activity ->
            activity.findViewById<WebView>(R.id.wthaiq_signing_web_view).evaluateJavascript(
                "(function(){var r=document.getElementById('$id').getBoundingClientRect();" +
                    "return (r.left+r.width/2)+','+(r.top+r.height/2);})()",
            ) { value ->
                rect = value
                latch.countDown()
            }
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "element $id not measured" }
        val (x, y) = rect!!.trim('"').split(',').map { it.toFloat() }
        scenario.onActivity { activity ->
            val view = activity.findViewById<WebView>(R.id.wthaiq_signing_web_view)
            val density = activity.resources.displayMetrics.density
            val px = x * density
            val py = y * density
            val downTime = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, px, py, 0)
            val up = MotionEvent.obtain(downTime, downTime + 60, MotionEvent.ACTION_UP, px, py, 0)
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }
    }

    /** Closes the scenario unless the activity already finished itself (closing then only waits for a timeout). */
    fun close() {
        if (scenario.state != Lifecycle.State.DESTROYED) scenario.close()
    }
}

fun launchSigning(
    url: String,
    configuration: WthaiqSigningConfiguration = WthaiqSigningConfiguration.DEFAULT,
): SigningSession {
    val recorder = EventRecorder()
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val intent = WthaiqSigning.Contract(recorder).createIntent(context, SigningRequest(url, configuration))
    val scenario = ActivityScenario.launchActivityForResult<WthaiqSigningActivity>(intent)
    return SigningSession(scenario, recorder)
}

/** Polls [condition] on the test thread until it is true or [timeoutMillis] elapse. */
fun waitUntil(timeoutMillis: Long = 10_000, message: String = "condition", condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    while (SystemClock.uptimeMillis() < deadline) {
        if (condition()) return
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(50)
    }
    throw AssertionError("Timed out waiting for $message")
}

val Activity.signingFragment: WthaiqSigningFragment
    get() = (this as WthaiqSigningActivity).supportFragmentManager.fragments.first() as WthaiqSigningFragment
