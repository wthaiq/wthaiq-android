package com.wthaiq.signing

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import com.wthaiq.signing.internal.BundleCodec
import com.wthaiq.signing.internal.SigningEventBus

/**
 * Entry point of the Wthaiq signing SDK.
 *
 * Your backend creates the signing link with a server SDK (never put an API key in the app) and hands the URL to
 * the app. Present it with the Activity Result API:
 *
 * ```kotlin
 * private val signing = registerForActivityResult(WthaiqSigning.Contract()) { result ->
 *     when (result) {
 *         is SigningResult.Signed -> showConfirming(result.outcome.reference)
 *         is SigningResult.Declined -> showDeclined()
 *         SigningResult.Canceled -> Unit
 *         SigningResult.Expired -> requestNewLink()
 *         is SigningResult.Failed -> showError(result.error)
 *     }
 * }
 *
 * signing.launch(SigningRequest(signingUrl))
 * ```
 */
public object WthaiqSigning {
    /** The SDK version (Semantic Versioning). */
    public const val VERSION: String = BuildConfig.SDK_VERSION

    /** The Wthaiq API version this release was built against. */
    public const val API_VERSION: String = "2026-07-01"

    /**
     * The hosts that serve Wthaiq signing pages. Add your custom signing domain to
     * [WthaiqSigningConfiguration.allowedHosts] when your account uses one.
     */
    @JvmField
    public val PLATFORM_HOSTS: Set<String> = setOf("wthaiq.com", "www.wthaiq.com")

    /**
     * Starts [WthaiqSigningActivity] for a [SigningRequest] and returns its [SigningResult].
     *
     * A result code other than `RESULT_OK` (for example when the system finishes the activity) is reported as
     * [SigningResult.Canceled].
     *
     * @param onEvent receives informational [SigningEvent]s while the signing screen is open. It is held only for
     *   that time and is not restored after the app's process is recreated.
     */
    public class Contract @JvmOverloads constructor(
        private val onEvent: SigningEventListener? = null,
    ) : ActivityResultContract<SigningRequest, SigningResult>() {

        override fun createIntent(context: Context, input: SigningRequest): Intent {
            val token = onEvent?.let(SigningEventBus::register)
            return WthaiqSigningActivity.createIntent(context, input, token)
        }

        override fun parseResult(resultCode: Int, intent: Intent?): SigningResult {
            intent?.getStringExtra(BundleCodec.EXTRA_EVENT_TOKEN)?.let(SigningEventBus::unregister)
            if (resultCode != Activity.RESULT_OK || intent == null) return SigningResult.Canceled
            return BundleCodec.readResult(intent.getBundleExtra(BundleCodec.EXTRA_RESULT)) ?: SigningResult.Canceled
        }
    }
}

/** What to present: the signing URL from your backend and the session options. */
public class SigningRequest @JvmOverloads constructor(
    /** A signer's `signing_url`, a session's `sign_url`, or the `signing_url` of `POST /signers/{id}/signing_session`. */
    public val url: String,
    /** Session options. */
    public val configuration: WthaiqSigningConfiguration = WthaiqSigningConfiguration.DEFAULT,
) {
    override fun equals(other: Any?): Boolean =
        other is SigningRequest && other.url == url && other.configuration == configuration

    override fun hashCode(): Int = 31 * url.hashCode() + configuration.hashCode()

    /** The URL is not printed: a signing URL lets its holder sign. */
    override fun toString(): String = "SigningRequest(configuration=$configuration)"
}

/** Launches the signing screen for [url]. */
@JvmOverloads
public fun ActivityResultLauncher<SigningRequest>.launch(
    url: String,
    configuration: WthaiqSigningConfiguration = WthaiqSigningConfiguration.DEFAULT,
) {
    launch(SigningRequest(url, configuration))
}
