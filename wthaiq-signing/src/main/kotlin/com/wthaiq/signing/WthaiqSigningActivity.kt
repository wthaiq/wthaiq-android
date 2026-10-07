package com.wthaiq.signing

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.wthaiq.signing.internal.BundleCodec
import com.wthaiq.signing.internal.SigningEventBus

/**
 * A full-screen activity that hosts [WthaiqSigningFragment]. Start it with [WthaiqSigning.Contract]; it finishes
 * with `RESULT_OK` and the [SigningResult] in its result intent.
 *
 * It handles rotation and keyboard changes itself, so the page (and anything the signer typed or drew) is kept. Other
 * recreations, including the app's process being stopped in the background, restore the session state and reload the
 * page.
 */
public class WthaiqSigningActivity : FragmentActivity(), WthaiqSigningFragment.Listener {

    private var eventToken: String? = null
    private var finalResult: SigningResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        val configuration = BundleCodec.readConfiguration(intent.getBundleExtra(BundleCodec.EXTRA_CONFIGURATION))
        val dark = isDark(configuration)
        setTheme(if (dark) R.style.Wthaiq_Theme_Signing_Dark else R.style.Wthaiq_Theme_Signing_Light)
        val barStyle = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        super.onCreate(savedInstanceState)
        eventToken = intent.getStringExtra(BundleCodec.EXTRA_EVENT_TOKEN)

        finalResult = BundleCodec.readResult(savedInstanceState?.getBundle(STATE_RESULT))
        finalResult?.let(::publishResult)

        val container = FrameLayout(this).apply { id = R.id.wthaiq_signing_container }
        setContentView(container)
        if (savedInstanceState == null) {
            val fragment = WthaiqSigningFragment.create(
                url = intent.getStringExtra(BundleCodec.EXTRA_URL).orEmpty(),
                configuration = configuration,
                requestKey = WthaiqSigningFragment.REQUEST_KEY,
                handlesInsets = true,
            )
            supportFragmentManager.beginTransaction()
                .replace(R.id.wthaiq_signing_container, fragment, FRAGMENT_TAG)
                .commitNow()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        finalResult?.let { outState.putBundle(STATE_RESULT, BundleCodec.writeResult(it)) }
    }

    override fun onDestroy() {
        if (isFinishing) eventToken?.let(SigningEventBus::unregister)
        super.onDestroy()
    }

    override fun onSigningResult(fragment: WthaiqSigningFragment, result: SigningResult) {
        finalResult = result
        publishResult(result)
    }

    override fun onSigningEvent(fragment: WthaiqSigningFragment, event: SigningEvent) {
        SigningEventBus.dispatch(eventToken, event)
    }

    override fun onSigningDismissRequested(fragment: WthaiqSigningFragment) {
        finish()
    }

    private fun publishResult(result: SigningResult) {
        val data = Intent()
            .putExtra(BundleCodec.EXTRA_RESULT, BundleCodec.writeResult(result))
            .putExtra(BundleCodec.EXTRA_EVENT_TOKEN, eventToken)
        setResult(Activity.RESULT_OK, data)
    }

    private fun isDark(configuration: WthaiqSigningConfiguration): Boolean =
        when (configuration.appearance.interfaceStyle) {
            WthaiqSigningConfiguration.InterfaceStyle.LIGHT -> false
            WthaiqSigningConfiguration.InterfaceStyle.DARK -> true
            WthaiqSigningConfiguration.InterfaceStyle.SYSTEM ->
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }

    public companion object {
        private const val FRAGMENT_TAG = "com.wthaiq.signing.fragment"
        private const val STATE_RESULT = "com.wthaiq.signing.state.RESULT"

        /**
         * The intent that starts the signing activity. Prefer [WthaiqSigning.Contract], which also parses the result.
         */
        @JvmStatic
        public fun createIntent(context: Context, request: SigningRequest): Intent = createIntent(context, request, null)

        internal fun createIntent(context: Context, request: SigningRequest, eventToken: String?): Intent =
            Intent(context, WthaiqSigningActivity::class.java)
                .putExtra(BundleCodec.EXTRA_URL, request.url)
                .putExtra(BundleCodec.EXTRA_CONFIGURATION, BundleCodec.writeConfiguration(request.configuration))
                .apply { if (eventToken != null) putExtra(BundleCodec.EXTRA_EVENT_TOKEN, eventToken) }
    }
}
