package com.wthaiq.signing.internal

import com.wthaiq.signing.ReturnUrlMatch
import com.wthaiq.signing.WthaiqSigningConfiguration

/** What started a navigation. */
internal enum class NavigationTrigger {
    /** The signer tapped a link (`WebResourceRequest.hasGesture()`). */
    LINK_ACTIVATED,

    /** A script navigation or a redirect. */
    OTHER,
}

/** A navigation the WebView is about to perform. */
internal data class NavigationRequest(
    val url: String,
    val isMainFrame: Boolean = true,
    val opensNewWindow: Boolean = false,
    val trigger: NavigationTrigger = NavigationTrigger.OTHER,
    /** Host of the frame that started the navigation, as far as the WebView lets us know it. */
    val sourceHost: String? = null,
)

internal enum class NavigationBlockReason {
    UNSUPPORTED_SCHEME,
    INSECURE_CONNECTION,
    HOST_NOT_ALLOWED,
    FINISHED,
}

internal sealed class NavigationDecision {
    /** Load it in the signing view. */
    data object Allow : NavigationDecision()

    /** Load it and remember the host as part of the identity verification step. */
    data class AllowExternalFlow(val host: String) : NavigationDecision()

    /** Do not load it. */
    data class Cancel(val reason: NavigationBlockReason) : NavigationDecision()

    /** Do not load it; hand it to the system (browser, mail, phone). */
    data class OpenExternally(val url: String) : NavigationDecision()

    /** Do not load it; the session ends with this return URL. */
    data class Complete(val match: ReturnUrlMatch) : NavigationDecision()
}

internal enum class MediaCaptureKind {
    CAMERA,
    MICROPHONE,
    CAMERA_AND_MICROPHONE,
}

/** Which runtime permissions the host app declares in its manifest (and the user granted). */
internal data class MediaPermissions(val camera: Boolean, val microphone: Boolean)

/**
 * Decides what the signing view does with every navigation. Pure logic, no Android types, fully unit tested.
 * Thread safe: [decide] runs on the main thread, [decideSubframeDocument] on the WebView's IO thread.
 *
 * Rules, in order (SDK-DESIGN §10.3):
 * 1. A URL matching one of `returnUrls` ends the session (never loaded).
 * 2. `about:` is allowed; `data:`/`blob:` only in subframes; `mailto:`/`tel:`/`sms:` open in the system when the
 *    signer tapped them; every other non-web scheme is refused.
 * 3. New-window requests (`target="_blank"`, `window.open`) open in the browser.
 * 4. Plain `http` is refused (except local development hosts when enabled); a tapped `http` link opens in the browser.
 * 5. A platform page navigating to `…?session_id=sr_…&status=completed|declined` ends the session.
 * 6. Platform hosts are allowed. After the session has ended, any other main-frame navigation is refused.
 * 7. Identity verification hosts (listed, or admitted earlier in this session) are allowed.
 * 8. A tapped link to another host opens in the browser.
 * 9. A scripted navigation or redirect from a platform page (or an admitted host) to another https host is
 *    admitted as the identity verification step under `FOLLOW_PLATFORM`.
 * 10. Everything else is refused.
 */
internal class NavigationPolicy(
    val configuration: WthaiqSigningConfiguration,
    admitted: Collection<String> = emptyList(),
) {
    private val lock = Any()
    private val admittedHostSet = admitted.map(HostMatcher::normalize).filter { it.isNotEmpty() }.toMutableSet()

    @Volatile
    var isFinished: Boolean = false
        private set

    /** Hosts admitted as part of the identity verification step, for state restoration. */
    val admittedHosts: Set<String> get() = synchronized(lock) { admittedHostSet.toSet() }

    fun markFinished() {
        isFinished = true
    }

    fun isPlatformHost(host: String?): Boolean {
        if (host == null) return false
        val normalized = HostMatcher.normalize(host)
        if (normalized.isEmpty()) return false
        if (HostMatcher.matches(normalized, configuration.allowedHosts)) return true
        return configuration.allowsInsecureLocalHosts && HostMatcher.isLocalDevelopmentHost(normalized)
    }

    /** Hosts whose pages may use the camera and microphone: platform hosts and identity verification hosts. */
    fun isTrustedContentHost(host: String?): Boolean {
        if (host == null) return false
        val normalized = HostMatcher.normalize(host)
        return isPlatformHost(normalized) ||
            isAdmitted(normalized) ||
            HostMatcher.matches(normalized, configuration.identityVerificationHosts)
    }

    fun isSecure(scheme: String, host: String): Boolean {
        val lower = scheme.lowercase()
        if (lower == "https") return true
        return lower == "http" && configuration.allowsInsecureLocalHosts && HostMatcher.isLocalDevelopmentHost(host)
    }

    /** `true` for a frame (scheme and host of its origin) on a platform host. */
    fun isPlatformFrame(scheme: String, host: String): Boolean = isSecure(scheme, host) && isPlatformHost(host)

    /** `true` for an origin string such as `https://wthaiq.com` (a `MessageEvent.origin` or a frame origin). */
    fun isPlatformOrigin(origin: String?): Boolean {
        val parts = UrlParts.parse(origin) ?: return false
        if (parts.scheme.isEmpty() || parts.host.isEmpty()) return false
        return isPlatformFrame(parts.scheme, parts.host)
    }

    fun allowsMediaCapture(kind: MediaCaptureKind, origin: String?, permissions: MediaPermissions): Boolean {
        val parts = UrlParts.parse(origin) ?: return false
        if (!isSecure(parts.scheme, parts.host) || !isTrustedContentHost(parts.host)) return false
        return when (kind) {
            MediaCaptureKind.CAMERA -> permissions.camera
            MediaCaptureKind.MICROPHONE -> permissions.microphone
            MediaCaptureKind.CAMERA_AND_MICROPHONE -> permissions.camera && permissions.microphone
        }
    }

    fun decide(request: NavigationRequest): NavigationDecision {
        val parts = UrlParts.parse(request.url) ?: return NavigationDecision.Cancel(NavigationBlockReason.UNSUPPORTED_SCHEME)
        val scheme = parts.scheme

        if (configuration.returnUrls.any { it.matches(parts) }) {
            return complete(ReturnUrlMatch(request.url))
        }

        if (scheme != "http" && scheme != "https") return decideOtherScheme(request, scheme)
        return decideWeb(request, scheme, parts.host)
    }

    /** Rule 2: schemes other than `http` and `https`. */
    private fun decideOtherScheme(request: NavigationRequest, scheme: String): NavigationDecision = when (scheme) {
        "about" -> NavigationDecision.Allow
        "data", "blob" -> if (request.isMainFrame || request.opensNewWindow) {
            NavigationDecision.Cancel(NavigationBlockReason.UNSUPPORTED_SCHEME)
        } else {
            NavigationDecision.Allow
        }
        "mailto", "tel", "sms" -> {
            val tapped = request.trigger == NavigationTrigger.LINK_ACTIVATED || request.opensNewWindow
            if (tapped) {
                NavigationDecision.OpenExternally(request.url)
            } else {
                NavigationDecision.Cancel(NavigationBlockReason.UNSUPPORTED_SCHEME)
            }
        }
        else -> NavigationDecision.Cancel(NavigationBlockReason.UNSUPPORTED_SCHEME)
    }

    /** Rules 3–10 for `http` and `https`. */
    @Suppress("ReturnCount")
    private fun decideWeb(request: NavigationRequest, scheme: String, host: String): NavigationDecision {
        if (host.isEmpty()) return NavigationDecision.Cancel(NavigationBlockReason.HOST_NOT_ALLOWED)

        if (request.opensNewWindow) return NavigationDecision.OpenExternally(request.url)

        if (!isSecure(scheme, host)) {
            return if (request.trigger == NavigationTrigger.LINK_ACTIVATED) {
                NavigationDecision.OpenExternally(request.url)
            } else {
                NavigationDecision.Cancel(NavigationBlockReason.INSECURE_CONNECTION)
            }
        }

        val fromPlatform = isPlatformHost(request.sourceHost)
        if (configuration.detectsPlatformReturnNavigation && fromPlatform && !isPlatformHost(host)) {
            val match = ReturnUrlMatch(request.url)
            if (match.isPlatformReturnTarget) return complete(match)
        }

        if (isPlatformHost(host)) return NavigationDecision.Allow
        if (isFinished && request.isMainFrame) return NavigationDecision.Cancel(NavigationBlockReason.FINISHED)
        if (isAdmitted(host) || HostMatcher.matches(host, configuration.identityVerificationHosts)) {
            return NavigationDecision.Allow
        }
        if (request.trigger == NavigationTrigger.LINK_ACTIVATED) return NavigationDecision.OpenExternally(request.url)
        if (request.trigger == NavigationTrigger.OTHER &&
            configuration.identityVerification == WthaiqSigningConfiguration.IdentityVerificationPolicy.FOLLOW_PLATFORM &&
            !isFinished
        ) {
            val sourceAdmitted = request.sourceHost?.let { isAdmitted(HostMatcher.normalize(it)) } ?: false
            if (fromPlatform || sourceAdmitted) {
                synchronized(lock) { admittedHostSet.add(host) }
                return NavigationDecision.AllowExternalFlow(host)
            }
        }
        return NavigationDecision.Cancel(NavigationBlockReason.HOST_NOT_ALLOWED)
    }

    /**
     * The Android WebView does not ask before a frame inside the page navigates, so document loads in subframes are
     * checked when their request is made (`shouldInterceptRequest`). [sourceHost] is the host of the page that
     * embeds the frame (from the `Referer` header, else the main frame's host). The result is the same decision as
     * [decide] for a scripted subframe navigation; `OpenExternally` cannot happen here.
     */
    fun decideSubframeDocument(url: String, sourceHost: String?): NavigationDecision =
        decide(
            NavigationRequest(
                url = url,
                isMainFrame = false,
                opensNewWindow = false,
                trigger = NavigationTrigger.OTHER,
                sourceHost = sourceHost,
            ),
        )

    private fun complete(match: ReturnUrlMatch): NavigationDecision =
        if (isFinished) NavigationDecision.Cancel(NavigationBlockReason.FINISHED) else NavigationDecision.Complete(match)

    private fun isAdmitted(host: String): Boolean = synchronized(lock) { host in admittedHostSet }
}
