package com.wthaiq.signing

/** Identifiers of the signature request and signer, when the page or the return URL provides them. */
public data class SigningReference @JvmOverloads constructor(
    /** The signature request (or session) id, `sr_…`. */
    public val signatureRequestId: String? = null,
    /** The signer id, `sgr_…`. */
    public val signerId: String? = null,
) {
    /** `true` when neither identifier is known. */
    public val isEmpty: Boolean get() = signatureRequestId == null && signerId == null

    public companion object {
        /** No identifiers available. */
        @JvmField
        public val UNKNOWN: SigningReference = SigningReference()
    }
}

/** Details of a signed or declined result. */
public data class SigningOutcome @JvmOverloads constructor(
    /** Request and signer identifiers, when known. */
    public val reference: SigningReference = SigningReference.UNKNOWN,
    /** How the outcome was detected. */
    public val detection: Detection,
    /** The return URL the page navigated to, for [Detection.RETURN_URL]. */
    public val returnUrl: String? = null,
    /** `true` when the link had already been signed or declined before it was opened. */
    public val wasAlreadyFinal: Boolean = false,
    /** A reason code sent by the page, for example `signer_declined`. */
    public val reason: String? = null,
) {
    /** How the SDK learned the outcome. */
    public enum class Detection(
        /** The wire name. */
        public val value: String,
    ) {
        /** The page navigated to one of your return URLs. */
        RETURN_URL("return_url"),

        /** The page sent a signing event. */
        PAGE_EVENT("page_event"),

        /** The SDK read the outcome from the page content (best effort). */
        PAGE_CONTENT("page_content"),
    }
}

/**
 * How a signing session ended. Delivered exactly once.
 *
 * This is a signal for your interface, not proof of signature: confirm the final state on your backend with the
 * `signature_request.completed` / `signer.signed` webhooks or `GET /signature_requests/{id}`.
 */
public sealed class SigningResult {
    /** The signer signed (or had already signed this link). */
    public data class Signed(override val outcome: SigningOutcome) : SigningResult()

    /** The signer declined (or had already declined this link). */
    public data class Declined(override val outcome: SigningOutcome) : SigningResult()

    /** The signer closed the screen before finishing, or the app canceled the session. */
    public data object Canceled : SigningResult()

    /** The signing link or embedded session has expired. Ask your backend for a new one. */
    public data object Expired : SigningResult()

    /** The session could not continue. */
    public data class Failed(public val error: WthaiqSigningError) : SigningResult()

    /** `true` for [Signed]. */
    public val isSigned: Boolean get() = this is Signed

    /** The outcome of [Signed] and [Declined]; `null` for the other results. */
    public open val outcome: SigningOutcome? get() = null

    public companion object {
        /**
         * Parses a return URL your app received some other way (for example through an App Link after signing in
         * the browser). Returns `null` unless the URL carries `status=completed` or `status=declined`.
         */
        @JvmStatic
        public fun fromReturnUrl(url: String): SigningResult? {
            val match = ReturnUrlMatch(url)
            val reference = SigningReference(match.signatureRequestId, match.signerId)
            val outcome = SigningOutcome(reference, SigningOutcome.Detection.RETURN_URL, returnUrl = url)
            return when (match.status) {
                ReturnUrlMatch.Status.Completed -> Signed(outcome)
                ReturnUrlMatch.Status.Declined -> Declined(outcome)
                else -> null
            }
        }
    }
}

/** States the signing page can show instead of the signing form. */
public enum class SigningPageState(
    /** The wire name used by the page events. */
    public val value: String,
) {
    /** This signer already signed. Ends the session with [SigningResult.Signed] (`wasAlreadyFinal`). */
    ALREADY_SIGNED("already_signed"),

    /** This signer already declined. Ends the session with [SigningResult.Declined] (`wasAlreadyFinal`). */
    ALREADY_DECLINED("already_declined"),

    /** The link has expired. Ends the session with [SigningResult.Expired]. */
    EXPIRED("expired"),

    /** The sender canceled the request. Ends the session with `Failed(LinkUnavailable(CANCELED))`. */
    CANCELED("canceled"),

    /** No request matches the link. Ends the session with `Failed(LinkUnavailable(NOT_FOUND))`. */
    NOT_FOUND("not_found"),

    /** The link has no signing token. Ends the session with `Failed(LinkUnavailable(MISSING_TOKEN))`. */
    MISSING_TOKEN("missing_token"),

    /** Ordered signing: an earlier signer has not signed yet. The screen stays open. */
    NOT_YOUR_TURN("not_your_turn"),

    /** The page asks for the access code sent separately by the sender. The screen stays open. */
    ACCESS_CODE_REQUIRED("access_code"),

    /** In-person signing is paused because identity verification is unavailable. The screen stays open. */
    IDENTITY_UNAVAILABLE("identity_unavailable"),
    ;

    /** `true` when this state ends the session. */
    public val isFinal: Boolean
        get() = when (this) {
            NOT_YOUR_TURN, ACCESS_CODE_REQUIRED, IDENTITY_UNAVAILABLE -> false
            else -> true
        }

    public companion object {
        /** The state with the wire name [value], or `null`. */
        @JvmStatic
        public fun fromValue(value: String): SigningPageState? = entries.firstOrNull { it.value == value }
    }
}

/** Errors reported in [SigningResult.Failed] and [SigningEvent.LoadFailed]. */
public sealed class WthaiqSigningError {
    /** A short English description for logs. */
    public abstract val description: String

    /** The URL was refused before loading. */
    public data class InvalidUrl(public val reason: UrlValidationError) : WthaiqSigningError() {
        override val description: String get() = reason.description
    }

    /** A network error: the WebView error code (`WebViewClient.ERROR_*`) and its description. */
    public data class Network(public val code: Int, public val message: String) : WthaiqSigningError() {
        override val description: String get() = "Network error $code: $message"
    }

    /** The page did not finish loading within [WthaiqSigningConfiguration.timeoutMillis]. */
    public data object TimedOut : WthaiqSigningError() {
        override val description: String get() = "The signing page did not finish loading in time."
    }

    /** The signing page answered with an HTTP error (429 or 5xx). */
    public data class HttpStatus(public val status: Int) : WthaiqSigningError() {
        override val description: String get() = "The signing page answered with HTTP $status."
    }

    /**
     * The link cannot be signed ([SigningPageState.CANCELED], [SigningPageState.NOT_FOUND] or
     * [SigningPageState.MISSING_TOKEN]).
     */
    public data class LinkUnavailable(public val state: SigningPageState) : WthaiqSigningError() {
        override val description: String get() = "The signing link cannot be used (${state.value})."
    }

    /** The page reported an error with this reason code. */
    public data class PageError(public val reason: String) : WthaiqSigningError() {
        override val description: String get() = "The signing page reported an error ($reason)."
    }

    /** The WebView renderer process stopped repeatedly. */
    public data object WebContentTerminated : WthaiqSigningError() {
        override val description: String get() = "The web content process stopped unexpectedly."
    }

    /** A return URL carried an unknown `status` value. */
    public data class UnexpectedReturnStatus(public val status: String) : WthaiqSigningError() {
        override val description: String get() = "The return URL carried an unknown status ($status)."
    }

    /** `true` when trying again may help. */
    public val isRetryable: Boolean
        get() = when (this) {
            is Network, TimedOut, is HttpStatus, WebContentTerminated -> true
            is InvalidUrl, is LinkUnavailable, is PageError, is UnexpectedReturnStatus -> false
        }

    /** `true` for network errors caused by a missing or lost connection. */
    public val isOffline: Boolean
        get() {
            if (this !is Network) return false
            if (code == ERROR_HOST_LOOKUP) return true
            val text = message.uppercase()
            return OFFLINE_MARKERS.any { text.contains(it) }
        }

    internal companion object {
        /** `WebViewClient.ERROR_HOST_LOOKUP`. */
        const val ERROR_HOST_LOOKUP: Int = -2
        private val OFFLINE_MARKERS = listOf(
            "ERR_INTERNET_DISCONNECTED",
            "ERR_NAME_NOT_RESOLVED",
            "ERR_NETWORK_CHANGED",
            "ERR_ADDRESS_UNREACHABLE",
            "ERR_NAME_RESOLUTION_FAILED",
        )
    }
}

/** Informational events reported while the session runs. */
public sealed class SigningEvent {
    /** A page finished loading in the main frame. */
    public data class PageLoaded(public val url: String) : SigningEvent()

    /** The embedded signing page reported that the signer opened the document. */
    public data class Viewed(public val reference: SigningReference) : SigningEvent()

    /** The page shows a state that does not end the session (see [SigningPageState.isFinal]). */
    public data class PageStateChanged(public val state: SigningPageState) : SigningEvent()

    /** The identity verification step opened on a third-party host. */
    public data class IdentityVerificationStarted(public val host: String) : SigningEvent()

    /** A link was handed to the system (browser, mail, phone). */
    public data class ExternalUrlOpened(public val url: String) : SigningEvent()

    /** A navigation was refused by the navigation policy. */
    public data class NavigationBlocked(public val url: String) : SigningEvent()

    /** A page failed to load; the retry screen is shown. */
    public data class LoadFailed(public val error: WthaiqSigningError) : SigningEvent()

    /** A page message this version does not know, kept for forward compatibility. */
    public data class PageMessage(public val name: String, public val fields: Map<String, String>) : SigningEvent()
}

/** Receives [SigningEvent]s while a signing session runs. */
public fun interface SigningEventListener {
    public fun onSigningEvent(event: SigningEvent)
}
