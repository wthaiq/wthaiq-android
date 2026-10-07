package com.wthaiq.signing.internal

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.browser.customtabs.CustomTabsIntent
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/**
 * Keeps the signing session's cookies, storage and cache away from the app's other WebViews and deletes them when
 * the session ends (`usesEphemeralWebsiteData`).
 *
 * Where the installed WebView supports profiles (`WebViewFeature.MULTI_PROFILE`), the session runs in its own
 * profile, which is wiped when the session ends and deleted before the next session starts. Otherwise the default
 * profile is used with the HTTP cache bypassed, and the cookies and web storage of the hosts the session visited are
 * removed at the end (best effort: the app's own WebView data for other hosts is never touched).
 */
internal class WebsiteDataSession(private val ephemeral: Boolean, private val freshStart: Boolean) {
    private val usesProfile: Boolean =
        ephemeral && runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)

    private var prepared = false

    /** Call on the main thread before a WebView is created; only the first call of a session does anything. */
    fun prepare() {
        if (prepared) return
        prepared = true
        if (!usesProfile || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        runCatching {
            if (freshStart) {
                ProfileStore.getInstance().getProfile(PROFILE_NAME)?.let { wipe(it) }
                runCatching { ProfileStore.getInstance().deleteProfile(PROFILE_NAME) }
            }
            ProfileStore.getInstance().getOrCreateProfile(PROFILE_NAME)
        }
    }

    /** Must be the first call on a new WebView. */
    fun attach(webView: WebView) {
        if (!usesProfile || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        runCatching { WebViewCompat.setProfile(webView, PROFILE_NAME) }
    }

    fun configure(settings: WebSettings) {
        settings.cacheMode = if (ephemeral && !usesProfile) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
    }

    /** Clears the session data. [hosts] are the hosts the session may have stored data for. */
    fun clear(webView: WebView?, hosts: Collection<String>) {
        if (!ephemeral) return
        if (usesProfile && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching { webView?.clearCache(true) }
            runCatching { ProfileStore.getInstance().getProfile(PROFILE_NAME)?.let { wipe(it) } }
            Handler(Looper.getMainLooper()).postDelayed(::deleteProfileLater, PROFILE_DELETE_DELAY_MILLIS)
            return
        }
        val cookies = runCatching { CookieManager.getInstance() }.getOrNull()
        for (host in hosts.map(HostMatcher::normalize).filter { it.isNotEmpty() && !it.startsWith("*") }.toSet()) {
            for (scheme in listOf("https", "http")) {
                val origin = "$scheme://$host"
                runCatching { WebStorage.getInstance().deleteOrigin(origin) }
                if (cookies != null) expireCookies(cookies, origin, host)
            }
        }
        runCatching { cookies?.flush() }
    }

    private fun deleteProfileLater() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        runCatching { ProfileStore.getInstance().deleteProfile(PROFILE_NAME) }
    }

    private fun wipe(profile: androidx.webkit.Profile) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        runCatching { profile.cookieManager.removeAllCookies(null) }
        runCatching { profile.webStorage.deleteAllData() }
        runCatching { profile.cookieManager.flush() }
    }

    private fun expireCookies(cookies: CookieManager, origin: String, host: String) {
        val header = runCatching { cookies.getCookie("$origin/") }.getOrNull() ?: return
        val names = header.split(';').mapNotNull { it.substringBefore('=').trim().ifEmpty { null } }.toSet()
        val labels = host.split('.')
        val parent = if (labels.size > 2) labels.takeLast(2).joinToString(".") else host
        for (name in names) {
            for (domain in setOf("", "; Domain=$host", "; Domain=$parent")) {
                runCatching { cookies.setCookie("$origin/", "$name=; Max-Age=0; Path=/$domain") }
            }
        }
    }

    companion object {
        const val PROFILE_NAME: String = "wthaiq-signing"
        private const val PROFILE_DELETE_DELAY_MILLIS: Long = 1_000
    }
}

/** Hands links to the system: Custom Tabs (or the browser) for web pages, the mail, messaging and phone apps. */
internal object ExternalOpener {
    fun open(context: Context, url: String, toolbarColor: Int?): Boolean {
        val uri = Uri.parse(url)
        return when (uri.scheme?.lowercase()) {
            "https", "http" -> openWeb(context, uri, toolbarColor)
            "mailto", "sms", "smsto" -> start(context, Intent(Intent.ACTION_SENDTO, uri))
            "tel" -> start(context, Intent(Intent.ACTION_DIAL, uri))
            else -> false
        }
    }

    private fun openWeb(context: Context, uri: Uri, toolbarColor: Int?): Boolean {
        try {
            val builder = CustomTabsIntent.Builder().setShowTitle(true)
            if (toolbarColor != null) {
                builder.setDefaultColorSchemeParams(
                    androidx.browser.customtabs.CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build(),
                )
            }
            builder.build().launchUrl(context, uri)
            return true
        } catch (_: ActivityNotFoundException) {
            return start(context, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: RuntimeException) {
            return false
        }
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** An empty `204` answer used for frames and requests the navigation policy does not let load. */
internal fun blockedResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** Instrumented tests serve fixture pages for platform URLs through this hook. Never set in production. */
internal object TestHooks {
    @Volatile
    var responder: ((WebResourceRequest) -> WebResourceResponse?)? = null
}
