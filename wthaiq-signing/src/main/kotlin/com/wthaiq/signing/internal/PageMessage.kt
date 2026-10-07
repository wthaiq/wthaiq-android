package com.wthaiq.signing.internal

import com.wthaiq.signing.ReturnUrlMatch
import com.wthaiq.signing.SigningEvent
import com.wthaiq.signing.SigningOutcome
import com.wthaiq.signing.SigningPageState
import com.wthaiq.signing.SigningReference
import com.wthaiq.signing.SigningResult
import com.wthaiq.signing.WthaiqSigningError
import kotlin.math.abs
import kotlin.math.floor

/** A signal decoded from the page bridge. */
internal sealed class PageSignal {
    data class Viewed(val reference: SigningReference) : PageSignal()
    data class Signed(val reference: SigningReference, val detection: SigningOutcome.Detection) : PageSignal()
    data class Declined(
        val reference: SigningReference,
        val reason: String?,
        val detection: SigningOutcome.Detection,
    ) : PageSignal()
    data class Failed(val reason: String) : PageSignal()
    data class State(
        val state: SigningPageState,
        val reference: SigningReference,
        val detection: SigningOutcome.Detection,
    ) : PageSignal()
    data class Other(val name: String, val fields: Map<String, String>) : PageSignal()
}

/**
 * Decodes the JSON strings posted by [BridgeScript] to the `wthaiqSigning` web message listener.
 *
 * Two kinds are posted:
 * * `{"kind":"message","origin":…,"data":{…}}` — a `window` message from the signing page (`data.source` is
 *   `wthaiq` or `wthaiq-inner`; see API-SPEC §8.13);
 * * `{"kind":"dom","event":…,"state":…}` — an outcome read from the signing page content.
 */
internal object PageMessageDecoder {
    const val MAX_FIELD_LENGTH: Int = 200

    fun decode(json: String, isTrustedOrigin: (String) -> Boolean): PageSignal? {
        val body = MiniJson.parse(json) as? Map<*, *> ?: return null
        return when (body["kind"] as? String) {
            "message" -> {
                val origin = body["origin"] as? String ?: return null
                if (!isTrustedOrigin(origin)) return null
                val data = body["data"] as? Map<*, *> ?: return null
                decodePageMessage(stringFields(data))
            }
            "dom" -> {
                val event = body["event"] as? String ?: return null
                decodeContent(event, body["state"] as? String)
            }
            else -> null
        }
    }

    fun stringFields(data: Map<*, *>): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        for ((key, value) in data) {
            if (key !is String) continue
            when (value) {
                is String -> fields[key] = value
                is Boolean -> fields[key] = value.toString()
                is Double -> fields[key] = formatNumber(value)
            }
        }
        return fields
    }

    private fun formatNumber(value: Double): String =
        if (value == floor(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()

    fun decodePageMessage(fields: Map<String, String>): PageSignal? {
        val source = fields["source"]
        if (source != "wthaiq" && source != "wthaiq-inner") return null
        var name = clean(fields["event"]).orEmpty()
        if (name.isEmpty()) {
            val type = clean(fields["type"])
            if (type != null && type.startsWith("wthaiq:")) name = type.removePrefix("wthaiq:")
        }
        name = name.lowercase()
        if (name.isEmpty() || name.length > 64) return null

        val reference = SigningReference(clean(fields["signature_request"]), clean(fields["signer"]))
        val reason = clean(fields["reason"])

        return when (name) {
            "viewed" -> PageSignal.Viewed(reference)
            "signed", "completed" -> PageSignal.Signed(reference, SigningOutcome.Detection.PAGE_EVENT)
            "declined" -> PageSignal.Declined(reference, reason, SigningOutcome.Detection.PAGE_EVENT)
            "error" -> PageSignal.Failed(reason ?: "unknown")
            "state" -> {
                val raw = clean(fields["state"]) ?: return null
                val state = SigningPageState.fromValue(raw.lowercase()) ?: return null
                PageSignal.State(state, reference, SigningOutcome.Detection.PAGE_EVENT)
            }
            else -> PageSignal.Other(name, fields - "source")
        }
    }

    fun decodeContent(event: String, state: String?): PageSignal? = when (event) {
        "signed" -> PageSignal.Signed(SigningReference.UNKNOWN, SigningOutcome.Detection.PAGE_CONTENT)
        "declined" -> PageSignal.Declined(SigningReference.UNKNOWN, null, SigningOutcome.Detection.PAGE_CONTENT)
        "state" -> {
            val raw = clean(state)
            val value = raw?.let(SigningPageState::fromValue)
            value?.let { PageSignal.State(it, SigningReference.UNKNOWN, SigningOutcome.Detection.PAGE_CONTENT) }
        }
        else -> null
    }

    fun clean(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        return if (trimmed.isEmpty() || trimmed.length > MAX_FIELD_LENGTH) null else trimmed
    }
}

/** The session state machine: turns signals into events and the single final result. */
internal class SigningFlow(
    result: SigningResult? = null,
    pageState: SigningPageState? = null,
    knownReference: SigningReference = SigningReference.UNKNOWN,
) {
    sealed class Effect {
        data object Ignored : Effect()
        data class Event(val event: SigningEvent) : Effect()
        data class Finish(val result: SigningResult) : Effect()
    }

    var result: SigningResult? = result
        private set

    var pageState: SigningPageState? = pageState
        private set

    /**
     * Identifiers learned from earlier page events (the embedded page sends them with `viewed`), used to complete
     * outcomes detected from the page content, which carries none.
     */
    var knownReference: SigningReference = knownReference
        private set

    val isFinished: Boolean get() = result != null

    fun apply(signal: PageSignal): Effect {
        if (result != null) return Effect.Ignored
        return when (signal) {
            is PageSignal.Viewed -> {
                remember(signal.reference)
                Effect.Event(SigningEvent.Viewed(signal.reference))
            }
            is PageSignal.Signed -> finish(SigningResult.Signed(SigningOutcome(resolve(signal.reference), signal.detection)))
            is PageSignal.Declined -> finish(
                SigningResult.Declined(SigningOutcome(resolve(signal.reference), signal.detection, reason = signal.reason)),
            )
            is PageSignal.Failed -> finish(
                if (signal.reason == "session_expired") {
                    SigningResult.Expired
                } else {
                    SigningResult.Failed(WthaiqSigningError.PageError(signal.reason))
                },
            )
            is PageSignal.State -> applyState(signal.state, resolve(signal.reference), signal.detection)
            is PageSignal.Other -> Effect.Event(SigningEvent.PageMessage(signal.name, signal.fields))
        }
    }

    fun applyReturn(match: ReturnUrlMatch): Effect {
        if (result != null) return Effect.Ignored
        val reference = SigningReference(match.signatureRequestId, match.signerId)
        val outcome = SigningOutcome(reference, SigningOutcome.Detection.RETURN_URL, returnUrl = match.url)
        return when (val status = match.status) {
            ReturnUrlMatch.Status.Completed, ReturnUrlMatch.Status.Unspecified -> finish(SigningResult.Signed(outcome))
            ReturnUrlMatch.Status.Declined -> finish(SigningResult.Declined(outcome))
            is ReturnUrlMatch.Status.Other ->
                finish(SigningResult.Failed(WthaiqSigningError.UnexpectedReturnStatus(status.value)))
        }
    }

    /** The signer or the app ended the session. A visible load error is reported instead of `Canceled`. */
    fun cancel(showingError: WthaiqSigningError?): Effect {
        if (result != null) return Effect.Ignored
        return finish(if (showingError != null) SigningResult.Failed(showingError) else SigningResult.Canceled)
    }

    fun fail(error: WthaiqSigningError): Effect {
        if (result != null) return Effect.Ignored
        return finish(SigningResult.Failed(error))
    }

    private fun remember(reference: SigningReference) {
        knownReference = SigningReference(
            reference.signatureRequestId ?: knownReference.signatureRequestId,
            reference.signerId ?: knownReference.signerId,
        )
    }

    private fun resolve(reference: SigningReference): SigningReference {
        remember(reference)
        return knownReference
    }

    private fun applyState(
        state: SigningPageState,
        reference: SigningReference,
        detection: SigningOutcome.Detection,
    ): Effect = when (state) {
        SigningPageState.ALREADY_SIGNED ->
            finish(SigningResult.Signed(SigningOutcome(reference, detection, wasAlreadyFinal = true)))
        SigningPageState.ALREADY_DECLINED ->
            finish(SigningResult.Declined(SigningOutcome(reference, detection, wasAlreadyFinal = true)))
        SigningPageState.EXPIRED -> finish(SigningResult.Expired)
        SigningPageState.CANCELED, SigningPageState.NOT_FOUND, SigningPageState.MISSING_TOKEN ->
            finish(SigningResult.Failed(WthaiqSigningError.LinkUnavailable(state)))
        SigningPageState.NOT_YOUR_TURN, SigningPageState.ACCESS_CODE_REQUIRED, SigningPageState.IDENTITY_UNAVAILABLE -> {
            if (pageState == state) {
                Effect.Ignored
            } else {
                pageState = state
                Effect.Event(SigningEvent.PageStateChanged(state))
            }
        }
    }

    private fun finish(value: SigningResult): Effect {
        result = value
        return Effect.Finish(value)
    }
}
