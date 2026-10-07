package com.wthaiq.signing

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.ConfigurationCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wthaiq.signing.internal.BridgeScript
import com.wthaiq.signing.internal.BundleCodec
import com.wthaiq.signing.internal.ExternalOpener
import com.wthaiq.signing.internal.HostMatcher
import com.wthaiq.signing.internal.LoadFailure
import com.wthaiq.signing.internal.MediaCaptureKind
import com.wthaiq.signing.internal.MediaPermissions
import com.wthaiq.signing.internal.NavigationBlockReason
import com.wthaiq.signing.internal.NavigationDecision
import com.wthaiq.signing.internal.NavigationPolicy
import com.wthaiq.signing.internal.NavigationRequest
import com.wthaiq.signing.internal.NavigationTrigger
import com.wthaiq.signing.internal.PageMessageDecoder
import com.wthaiq.signing.internal.ResolvedLanguage
import com.wthaiq.signing.internal.SigningFlow
import com.wthaiq.signing.internal.SigningHeaderView
import com.wthaiq.signing.internal.SigningPalette
import com.wthaiq.signing.internal.SigningStatusView
import com.wthaiq.signing.internal.SigningStrings
import com.wthaiq.signing.internal.TestHooks
import com.wthaiq.signing.internal.ThinProgressView
import com.wthaiq.signing.internal.UrlParts
import com.wthaiq.signing.internal.WebsiteDataSession
import com.wthaiq.signing.internal.blockedResponse
import com.wthaiq.signing.internal.dividerView
import com.wthaiq.signing.internal.dp
import java.io.File

/**
 * Shows a Wthaiq signing page natively and reports how the session ended.
 *
 * Give it a URL your backend obtained from the Wthaiq API — a signer's `signing_url`, a session's `sign_url`, or the
 * `signing_url` of `POST /signers/{id}/signing_session`. Never put an API key in the app.
 *
 * The result is delivered once to the [Listener] (an explicit [listener], else the parent fragment or the activity
 * when it implements [Listener]) and once through the Fragment Result API under the request key given to
 * [newInstance]. Read it with [resultFrom].
 *
 * ```kotlin
 * supportFragmentManager.beginTransaction()
 *     .replace(R.id.container, WthaiqSigningFragment.newInstance(signingUrl))
 *     .addToBackStack(WthaiqSigningFragment.BACK_STACK_NAME)
 *     .commit()
 * ```
 *
 * When you host the fragment yourself, declare `android:configChanges="orientation|screenSize|screenLayout|
 * smallestScreenSize|keyboardHidden"` on the activity so a rotation keeps the page (and anything the signer typed);
 * otherwise the page is reloaded after the activity is recreated. [WthaiqSigningActivity] already does this.
 */
@Suppress("TooManyFunctions")
public class WthaiqSigningFragment : Fragment() {

    /** Receives the result, events and dismissal requests. Every method except [onSigningResult] has a default. */
    public interface Listener {
        /** The session ended. Called exactly once. */
        public fun onSigningResult(fragment: WthaiqSigningFragment, result: SigningResult)

        /** An informational event (page loaded, identity verification started, link opened in the browser…). */
        public fun onSigningEvent(fragment: WthaiqSigningFragment, event: SigningEvent) {}

        /**
         * The signer tapped Close, or the automatic dismissal delay elapsed after a result. The default calls
         * [WthaiqSigningFragment.dismissFromContainer].
         */
        public fun onSigningDismissRequested(fragment: WthaiqSigningFragment) {
            fragment.dismissFromContainer()
        }

        /** Return `false` to handle an external link yourself. The default opens it in Custom Tabs or the system. */
        public fun shouldOpenExternalUrl(fragment: WthaiqSigningFragment, url: String): Boolean = true
    }

    /**
     * An explicit listener. It takes precedence over a parent fragment or activity that implements [Listener], but
     * it is not restored after the fragment is recreated; prefer implementing [Listener] in the host.
     */
    public var listener: Listener? = null

    /** The URL given at creation. */
    public val url: String? get() = arguments?.getString(ARG_URL)

    /** The configuration given at creation. */
    public val configuration: WthaiqSigningConfiguration by lazy {
        BundleCodec.readConfiguration(arguments?.getBundle(ARG_CONFIGURATION))
    }

    /** The result, once the session has ended. */
    public val result: SigningResult? get() = if (this::flow.isInitialized) flow.result else null

    private lateinit var flow: SigningFlow
    private lateinit var policy: NavigationPolicy
    private lateinit var strings: SigningStrings
    private lateinit var language: ResolvedLanguage
    private lateinit var websiteData: WebsiteDataSession
    private var link: SigningLink? = null
    private var palette: SigningPalette? = null

    private var webView: WebView? = null
    private var content: FrameLayout? = null
    private var progressView: ThinProgressView? = null
    private var statusView: SigningStatusView? = null
    private var confirmDialog: AlertDialog? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { handleTimeout() }
    private val dismissRunnable = Runnable { requestDismissal() }

    private var hasFinishedFirstLoad = false
    private var isMainFrameLoading = false
    private var mainFrameFailed = false
    private var visibleError: WthaiqSigningError? = null
    private var retryUrl: String? = null
    private var terminationCount = 0
    private var resultDelivered = false
    private var usesDocumentStartScript = false

    @Volatile
    private var currentPageHost: String? = null

    @Volatile
    private var lastRequestedHost: String? = null

    @Volatile
    private var lastRequestedUrl: String? = null

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCaptureUri: Uri? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingFileParams: WebChromeClient.FileChooserParams? = null

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { output ->
        deliverChosenFiles(output.resultCode, output.data)
    }

    private val mediaPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onMediaPermissionsResult()
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val params = pendingFileParams
        pendingFileParams = null
        if (params != null) launchFileChooser(params, cameraAllowed = granted)
    }

    // region Public API

    /** Ends the session with [SigningResult.Canceled] (if it has not ended yet) and stops loading. Does not dismiss. */
    public fun cancel() {
        cancelTimeout()
        webView?.stopLoading()
        apply(flow.cancel(null))
    }

    /** Reloads the signing page (or retries the page that failed). */
    public fun reload() {
        retry()
    }

    /**
     * Removes the signing screen: finishes [WthaiqSigningActivity], pops the back stack entry named
     * [BACK_STACK_NAME], or removes the fragment.
     */
    public fun dismissFromContainer() {
        val host = activity ?: return
        if (host is WthaiqSigningActivity) {
            host.finish()
            return
        }
        if (!isAdded) return
        val manager = parentFragmentManager
        if (manager.isStateSaved) return
        val inBackStack = (0 until manager.backStackEntryCount).any { manager.getBackStackEntryAt(it).name == BACK_STACK_NAME }
        if (inBackStack) {
            manager.popBackStack(BACK_STACK_NAME, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        } else {
            manager.beginTransaction().remove(this).commit()
        }
    }

    // endregion

    // region Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val context = requireContext()
        language = ResolvedLanguage.resolve(configuration.language, appLanguages(context))
        strings = SigningStrings.make(language)
        val state = savedInstanceState?.getBundle(STATE_KEY)
        flow = SigningFlow(
            result = BundleCodec.readResult(state?.getBundle("result")),
            pageState = state?.getString("pageState")?.let(SigningPageState::fromValue),
            knownReference = SigningReference(state?.getString("signatureRequestId"), state?.getString("signerId")),
        )
        policy = NavigationPolicy(configuration, state?.getStringArrayList("admitted").orEmpty())
        if (flow.isFinished) policy.markFinished()
        hasFinishedFirstLoad = state?.getBoolean("firstLoad") ?: false
        retryUrl = state?.getString("retryUrl")
        resultDelivered = state?.getBoolean("resultDelivered") ?: false
        websiteData = WebsiteDataSession(configuration.usesEphemeralWebsiteData, freshStart = savedInstanceState == null)
        when (val check = SigningLink.check(url, configuration)) {
            is SigningLink.Check.Valid -> link = check.link
            is SigningLink.Check.Invalid -> apply(flow.fail(WthaiqSigningError.InvalidUrl(check.reason)))
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = inflater.context
        val isDark = when (configuration.appearance.interfaceStyle) {
            WthaiqSigningConfiguration.InterfaceStyle.LIGHT -> false
            WthaiqSigningConfiguration.InterfaceStyle.DARK -> true
            WthaiqSigningConfiguration.InterfaceStyle.SYSTEM ->
                (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        val palette = SigningPalette(isDark, configuration.appearance.tintColor)
        this.palette = palette

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            layoutDirection = if (language.isRightToLeft) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            id = R.id.wthaiq_signing_root
        }
        if (configuration.appearance.showsHeader) {
            val header = SigningHeaderView(
                context,
                palette,
                configuration.appearance.title ?: strings.title,
                strings.close,
                configuration.showsCloseButton,
            )
            header.id = R.id.wthaiq_signing_header
            header.closeButton.id = R.id.wthaiq_signing_close
            header.closeButton.setOnClickListener { closeTapped() }
            root.addView(header, LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT))
            root.addView(dividerView(context, palette.divider), LinearLayout.LayoutParams(MATCH, 1))
        }
        val content = FrameLayout(context)
        this.content = content
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val status = SigningStatusView(context, palette)
        status.id = R.id.wthaiq_signing_status
        status.retryButton.id = R.id.wthaiq_signing_retry
        status.closeButton.id = R.id.wthaiq_signing_error_close
        status.onRetry = { retry() }
        status.onClose = { closeTapped() }
        content.addView(status, FrameLayout.LayoutParams(MATCH, MATCH))
        statusView = status

        val progress = ThinProgressView(context, palette.tint)
        val progressParams = FrameLayout.LayoutParams(MATCH, context.dp(2f))
        content.addView(progress, progressParams)
        progressView = progress

        if (requireArguments().getBoolean(ARG_HANDLES_INSETS)) applyWindowInsets(root)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    closeTapped()
                }
            },
        )
        val link = link
        val failure = (flow.result as? SigningResult.Failed)?.error
        when {
            link != null -> {
                websiteData.prepare()
                installWebView()
                load(retryUrl ?: link.urlFor(configuration.language))
                flow.result?.let { scheduleAutomaticDismissal(it) }
            }
            failure != null -> showError(failure)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!this::flow.isInitialized) return
        outState.putBundle(
            STATE_KEY,
            Bundle().apply {
                flow.result?.let { putBundle("result", BundleCodec.writeResult(it)) }
                putString("pageState", flow.pageState?.value)
                putString("signatureRequestId", flow.knownReference.signatureRequestId)
                putString("signerId", flow.knownReference.signerId)
                putStringArrayList("admitted", ArrayList(policy.admittedHosts))
                putBoolean("firstLoad", hasFinishedFirstLoad)
                putString("retryUrl", currentReloadUrl())
                putBoolean("resultDelivered", resultDelivered)
            },
        )
    }

    override fun onDestroyView() {
        confirmDialog?.dismiss()
        confirmDialog = null
        cancelTimeout()
        mainHandler.removeCallbacks(dismissRunnable)
        cancelPendingFileChooser()
        pendingPermissionRequest?.deny()
        pendingPermissionRequest = null
        val view = webView
        val ending = isSessionEnding()
        if (ending) websiteData.clear(view, sessionHosts())
        destroyWebView(view)
        webView = null
        content = null
        statusView = null
        progressView = null
        if (ending) deleteCaptures()
        super.onDestroyView()
    }

    override fun onDestroy() {
        if (isSessionEnding() && this::flow.isInitialized && flow.result == null) {
            apply(flow.cancel(visibleError))
        }
        super.onDestroy()
    }

    // endregion

    // region Layout

    private fun applyWindowInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            target.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
    }

    // endregion

    // region Web view

    @SuppressLint("SetJavaScriptEnabled")
    private fun installWebView() {
        val content = content ?: return
        val context = requireContext()
        val webView = WebView(context)
        websiteData.attach(webView)
        webView.id = R.id.wthaiq_signing_web_view

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(true)
        settings.setGeolocationEnabled(false)
        settings.mediaPlaybackRequiresUserGesture = true
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.userAgentString = "${settings.userAgentString} WthaiqSigning-Android/${WthaiqSigning.VERSION}"
        websiteData.configure(settings)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
        }
        if (configuration.isWebContentsDebuggingEnabled) WebView.setWebContentsDebuggingEnabled(true)

        installBridge(webView)

        webView.webViewClient = SigningWebViewClient()
        webView.webChromeClient = SigningChromeClient()
        webView.setBackgroundColor(0xFFFFFFFF.toInt())
        webView.isHorizontalScrollBarEnabled = false
        webView.alpha = if (hasFinishedFirstLoad && visibleError == null) 1f else 0f
        content.addView(
            webView,
            0,
            FrameLayout.LayoutParams(MATCH, MATCH),
        )
        this.webView = webView
    }

    private fun installBridge(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val rules = originRules()
        if (rules.isEmpty()) return
        try {
            WebViewCompat.addWebMessageListener(webView, BridgeScript.OBJECT_NAME, rules) { _, message, sourceOrigin, _, _ ->
                receive(message.data, sourceOrigin.toString())
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, BridgeScript.source, rules)
                usesDocumentStartScript = true
            }
        } catch (_: IllegalArgumentException) {
            usesDocumentStartScript = false
        }
    }

    /** Origin rules for the platform hosts (and the local development server when enabled). */
    private fun originRules(): Set<String> {
        val rules = LinkedHashSet<String>()
        val hostPattern = Regex("^(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$")
        for (raw in configuration.allowedHosts) {
            val host = HostMatcher.normalize(raw)
            if (hostPattern.matches(host)) rules.add("https://$host")
        }
        val parts = link?.let { UrlParts.parse(it.url) }
        if (parts != null && configuration.allowsInsecureLocalHosts && HostMatcher.isLocalDevelopmentHost(parts.host)) {
            rules.add(parts.origin)
        }
        return rules
    }

    private fun destroyWebView(view: WebView?) {
        view ?: return
        view.stopLoading()
        view.webChromeClient = null
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
    }

    private fun load(target: String) {
        hideError()
        retryUrl = target
        val status = statusView
        if (status != null && (!hasFinishedFirstLoad || status.visibility == View.VISIBLE)) {
            status.showLoading(strings.loading)
            status.visibility = View.VISIBLE
        }
        lastRequestedUrl = target
        lastRequestedHost = UrlParts.parse(target)?.host
        webView?.loadUrl(target)
    }

    private fun retry() {
        val link = link ?: return
        terminationCount = 0
        if (webView == null) installWebView()
        load(retryUrl ?: link.urlFor(configuration.language))
    }

    private fun currentReloadUrl(): String? {
        val current = webView?.url
        val parts = UrlParts.parse(current)
        if (parts != null && (parts.scheme == "https" || parts.scheme == "http") && policy.isTrustedContentHost(parts.host)) {
            return current
        }
        return retryUrl
    }

    private fun updateProgress(value: Float) {
        if (!configuration.appearance.showsProgressBar) return
        progressView?.setProgress(value)
    }

    private fun startTimeout() {
        cancelTimeout()
        mainHandler.postDelayed(timeoutRunnable, configuration.effectiveTimeoutMillis)
    }

    private fun cancelTimeout() {
        mainHandler.removeCallbacks(timeoutRunnable)
    }

    private fun handleTimeout() {
        if (!isMainFrameLoading || flow.result != null) return
        mainFrameFailed = true
        webView?.stopLoading()
        showLoadError(WthaiqSigningError.TimedOut, null)
    }

    private fun revealContent() {
        if (visibleError != null) return
        statusView?.visibility = View.GONE
        val view = webView ?: return
        if (view.alpha < 1f) {
            if (hasFinishedFirstLoad) view.alpha = 1f else view.animate().alpha(1f).setDuration(200).start()
        }
    }

    // endregion

    // region Errors

    private fun showLoadError(error: WthaiqSigningError, failingUrl: String?) {
        cancelTimeout()
        isMainFrameLoading = false
        val parts = UrlParts.parse(failingUrl)
        if (parts != null && (parts.scheme == "https" || parts.scheme == "http")) retryUrl = failingUrl
        showError(error)
        emit(SigningEvent.LoadFailed(error))
    }

    private fun showError(error: WthaiqSigningError) {
        visibleError = error
        val (title, message) = strings.errorText(error)
        val status = statusView ?: return
        status.showError(
            title = title,
            message = message,
            icon = SigningStrings.icon(error),
            retryTitle = if (error.isRetryable && link != null) strings.retry else null,
            closeTitle = if (configuration.showsCloseButton) strings.close else null,
        )
        status.visibility = View.VISIBLE
        webView?.alpha = 0f
    }

    private fun hideError() {
        visibleError = null
    }

    // endregion

    // region Session

    private fun receive(data: String?, frameOrigin: String) {
        if (data == null || !this::flow.isInitialized) return
        if (!policy.isPlatformOrigin(frameOrigin)) return
        val signal = PageMessageDecoder.decode(data) { policy.isPlatformOrigin(it) } ?: return
        apply(flow.apply(signal))
    }

    private fun apply(effect: SigningFlow.Effect) {
        when (effect) {
            SigningFlow.Effect.Ignored -> Unit
            is SigningFlow.Effect.Event -> emit(effect.event)
            is SigningFlow.Effect.Finish -> finish(effect.result)
        }
    }

    private fun finish(result: SigningResult) {
        policy.markFinished()
        cancelTimeout()
        announce(result)
        deliverResult(result)
        scheduleAutomaticDismissal(result)
    }

    private fun deliverResult(result: SigningResult) {
        if (resultDelivered) return
        resultDelivered = true
        if (isAdded) {
            val requestKey = arguments?.getString(ARG_REQUEST_KEY) ?: REQUEST_KEY
            val bundle = Bundle().apply { putBundle(RESULT_KEY, BundleCodec.writeResult(result)) }
            parentFragmentManager.setFragmentResult(requestKey, bundle)
        }
        resolveListener()?.onSigningResult(this, result)
    }

    private fun scheduleAutomaticDismissal(result: SigningResult) {
        val delay = configuration.dismissalDelayMillis ?: return
        if (result !is SigningResult.Signed && result !is SigningResult.Declined) return
        mainHandler.removeCallbacks(dismissRunnable)
        mainHandler.postDelayed(dismissRunnable, delay)
    }

    private fun requestDismissal() {
        mainHandler.removeCallbacks(dismissRunnable)
        val listener = resolveListener()
        if (listener != null) listener.onSigningDismissRequested(this) else dismissFromContainer()
    }

    private fun closeTapped() {
        if (flow.result != null) {
            requestDismissal()
            return
        }
        val shouldConfirm = configuration.appearance.confirmsBeforeClosing &&
            hasFinishedFirstLoad &&
            visibleError == null &&
            confirmDialog?.isShowing != true
        if (shouldConfirm) presentCloseConfirmation() else closeNow()
    }

    private fun closeNow() {
        cancelTimeout()
        webView?.stopLoading()
        apply(flow.cancel(visibleError))
        requestDismissal()
    }

    private fun presentCloseConfirmation() {
        val context = context ?: return
        val theme = if (palette?.isDark == true) {
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        } else {
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        }
        val dialog = AlertDialog.Builder(context, theme)
            .setTitle(strings.confirmTitle)
            .setMessage(strings.confirmMessage)
            .setNegativeButton(strings.confirmStay, null)
            .setPositiveButton(strings.confirmLeave) { _, _ -> closeNow() }
            .create()
        dialog.setOnShowListener {
            dialog.window?.decorView?.layoutDirection =
                if (language.isRightToLeft) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        }
        dialog.setOnDismissListener { if (confirmDialog === dialog) confirmDialog = null }
        confirmDialog = dialog
        dialog.show()
    }

    private fun announce(result: SigningResult) {
        val text = when (result) {
            is SigningResult.Signed -> strings.announceSigned
            is SigningResult.Declined -> strings.announceDeclined
            else -> return
        }
        view?.announceForAccessibility(text)
    }

    private fun emit(event: SigningEvent) {
        resolveListener()?.onSigningEvent(this, event)
    }

    private fun resolveListener(): Listener? = listener ?: (parentFragment as? Listener) ?: (activity as? Listener)

    private fun isSessionEnding(): Boolean {
        val host = activity ?: return true
        if (host.isChangingConfigurations) return false
        return host.isFinishing || isRemoving
    }

    private fun sessionHosts(): Set<String> = configuration.allowedHosts + policy.admittedHosts +
        listOfNotNull(link?.host)

    // endregion

    // region Navigation

    /** Applies a navigation decision on the main thread. Returns `true` when the navigation may proceed. */
    private fun handle(decision: NavigationDecision, target: String): Boolean = when (decision) {
        NavigationDecision.Allow -> true
        is NavigationDecision.AllowExternalFlow -> {
            emit(SigningEvent.IdentityVerificationStarted(decision.host))
            true
        }
        is NavigationDecision.Cancel -> {
            if (decision.reason != NavigationBlockReason.FINISHED) emit(SigningEvent.NavigationBlocked(target))
            false
        }
        is NavigationDecision.OpenExternally -> {
            openExternally(decision.url)
            false
        }
        is NavigationDecision.Complete -> {
            apply(flow.applyReturn(decision.match))
            false
        }
    }

    private fun openExternally(target: String) {
        val scheme = UrlParts.parse(target)?.scheme ?: return
        if (scheme !in setOf("https", "http", "mailto", "tel", "sms")) return
        val listener = resolveListener()
        if (listener != null && !listener.shouldOpenExternalUrl(this, target)) return
        val context = context ?: return
        if (ExternalOpener.open(context, target, palette?.background)) emit(SigningEvent.ExternalUrlOpened(target))
    }

    private fun handleNewWindow(target: String, userGesture: Boolean) {
        val request = NavigationRequest(
            url = target,
            isMainFrame = false,
            opensNewWindow = true,
            trigger = if (userGesture) NavigationTrigger.LINK_ACTIVATED else NavigationTrigger.OTHER,
            sourceHost = currentPageHost,
        )
        handle(policy.decide(request), target)
    }

    private inner class SigningWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (view !== webView) return true
            val target = request.url.toString()
            val redirect = request.isRedirect
            val trigger = if (request.hasGesture() && !redirect) NavigationTrigger.LINK_ACTIVATED else NavigationTrigger.OTHER
            val source = if (redirect) lastRequestedHost else currentPageHost
            val navigation = NavigationRequest(target, request.isForMainFrame, false, trigger, source)
            val allowed = handle(policy.decide(navigation), target)
            if (allowed && request.isForMainFrame) {
                lastRequestedHost = UrlParts.parse(target)?.host
                lastRequestedUrl = target
            }
            return !allowed
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val target = request.url.toString()
            if (request.isForMainFrame) {
                if (configuration.returnUrls.any { it.matches(target) }) {
                    mainHandler.post { handle(policy.decide(NavigationRequest(target)), target) }
                    return blockedResponse()
                }
                return TestHooks.responder?.invoke(request)
            }
            val accept = request.requestHeaders.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value
            val isDocument = request.method.equals("GET", ignoreCase = true) && accept?.startsWith("text/html") == true
            if (isDocument) {
                val referer = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
                val source = UrlParts.parse(referer)?.host?.ifEmpty { null } ?: currentPageHost
                val decision = policy.decideSubframeDocument(target, source)
                when (decision) {
                    NavigationDecision.Allow -> Unit
                    is NavigationDecision.AllowExternalFlow -> mainHandler.post { handle(decision, target) }
                    else -> {
                        mainHandler.post { handle(decision, target) }
                        return blockedResponse()
                    }
                }
            }
            return TestHooks.responder?.invoke(request)
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (view !== webView) return
            isMainFrameLoading = true
            mainFrameFailed = false
            currentPageHost = UrlParts.parse(url)?.host
            updateProgress(0.1f)
            startTimeout()
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== webView) return
            isMainFrameLoading = false
            cancelTimeout()
            updateProgress(1f)
            if (!usesDocumentStartScript && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                policy.isPlatformOrigin(UrlParts.parse(url)?.origin)
            ) {
                view.evaluateJavascript(BridgeScript.source, null)
            }
            if (mainFrameFailed || visibleError != null) return
            terminationCount = 0
            revealContent()
            hasFinishedFirstLoad = true
            if (url != null) emit(SigningEvent.PageLoaded(url))
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (view !== webView || !request.isForMainFrame) return
            val failure = LoadFailure.classify(error.errorCode, error.description?.toString()) ?: return
            mainFrameFailed = true
            isMainFrameLoading = false
            cancelTimeout()
            if (flow.result != null) return
            showLoadError(failure, request.url.toString())
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (view !== webView || !request.isForMainFrame) return
            if (!LoadFailure.isBlockingStatus(errorResponse.statusCode)) return
            mainFrameFailed = true
            view.stopLoading()
            if (flow.result != null) return
            showLoadError(WthaiqSigningError.HttpStatus(errorResponse.statusCode), request.url.toString())
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            if (view !== webView || error.url != lastRequestedUrl) return
            mainFrameFailed = true
            if (flow.result != null) return
            showLoadError(
                WthaiqSigningError.Network(LoadFailure.ERROR_FAILED_SSL_HANDSHAKE, "SSL error ${error.primaryError}"),
                error.url,
            )
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view !== webView) {
                destroyWebView(view)
                return true
            }
            terminationCount += 1
            val lastUrl = currentReloadUrl()
            destroyWebView(view)
            webView = null
            if (flow.result != null) return true
            val currentLink = link
            if (terminationCount <= 1 && currentLink != null) {
                installWebView()
                load(lastUrl ?: currentLink.urlFor(configuration.language))
            } else {
                showLoadError(WthaiqSigningError.WebContentTerminated, lastUrl)
            }
            return true
        }
    }

    private inner class SigningChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (view === webView && isMainFrameLoading) updateProgress(maxOf(0.1f, newProgress / 100f).coerceAtMost(0.95f))
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val hit = view.hitTestResult
            val anchor = hit.extra
            if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE && !anchor.isNullOrEmpty()) {
                handleNewWindow(anchor, isUserGesture)
                return false
            }
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val popup = WebView(view.context)
            websiteData.attach(popup)
            var captured = false
            fun capture(target: String?) {
                if (captured || target.isNullOrEmpty() || target == "about:blank") return
                captured = true
                mainHandler.post {
                    handleNewWindow(target, isUserGesture)
                    destroyWebView(popup)
                }
            }
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    capture(request.url.toString())
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    view.stopLoading()
                    capture(url)
                }
            }
            mainHandler.postDelayed({ if (!captured) destroyWebView(popup) }, POPUP_TIMEOUT_MILLIS)
            transport.webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            mainHandler.post { handleMediaRequest(request) }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            if (pendingPermissionRequest === request) pendingPermissionRequest = null
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: android.webkit.GeolocationPermissions.Callback?,
        ) {
            callback?.invoke(origin, false, false)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            cancelPendingFileChooser()
            pendingFileCallback = filePathCallback
            val wantsCamera = fileChooserParams.isCaptureEnabled ||
                fileChooserParams.acceptTypes.any { it.isBlank() || it.startsWith("image/") }
            if (wantsCamera && declares(Manifest.permission.CAMERA) && !isGranted(Manifest.permission.CAMERA)) {
                pendingFileParams = fileChooserParams
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                launchFileChooser(fileChooserParams, cameraAllowed = wantsCamera)
            }
            return true
        }
    }

    // endregion

    // region Camera, microphone and files

    private fun handleMediaRequest(request: PermissionRequest) {
        val resources = request.resources.toSet()
        val video = PermissionRequest.RESOURCE_VIDEO_CAPTURE in resources
        val audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE in resources
        val kind = when {
            video && audio -> MediaCaptureKind.CAMERA_AND_MICROPHONE
            video -> MediaCaptureKind.CAMERA
            audio -> MediaCaptureKind.MICROPHONE
            else -> {
                request.deny()
                return
            }
        }
        val declared = MediaPermissions(
            camera = declares(Manifest.permission.CAMERA),
            microphone = declares(Manifest.permission.RECORD_AUDIO),
        )
        if (!policy.allowsMediaCapture(kind, request.origin.toString(), declared)) {
            request.deny()
            return
        }
        val needed = listOfNotNull(
            Manifest.permission.CAMERA.takeIf { video },
            Manifest.permission.RECORD_AUDIO.takeIf { audio },
        ).filterNot(::isGranted)
        if (needed.isEmpty()) {
            grantMedia(request)
        } else {
            pendingPermissionRequest?.deny()
            pendingPermissionRequest = request
            mediaPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun onMediaPermissionsResult() {
        val request = pendingPermissionRequest ?: return
        pendingPermissionRequest = null
        val resources = request.resources.toSet()
        val cameraOk = PermissionRequest.RESOURCE_VIDEO_CAPTURE !in resources || isGranted(Manifest.permission.CAMERA)
        val microphoneOk = PermissionRequest.RESOURCE_AUDIO_CAPTURE !in resources || isGranted(Manifest.permission.RECORD_AUDIO)
        if (cameraOk && microphoneOk) grantMedia(request) else request.deny()
    }

    private fun grantMedia(request: PermissionRequest) {
        val allowed = request.resources.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
        }
        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
    }

    private fun launchFileChooser(params: WebChromeClient.FileChooserParams, cameraAllowed: Boolean) {
        if (pendingFileCallback == null) return
        val context = context ?: return cancelPendingFileChooser()
        val mimeTypes = params.acceptTypes
            .flatMap { it.split(',') }
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .map { if (it.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.drop(1)) ?: "*/*" else it }
            .distinct()
        val pick = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
        pick.type = mimeTypes.singleOrNull() ?: "*/*"
        if (mimeTypes.size > 1) pick.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

        val capture = if (cameraAllowed && (!declares(Manifest.permission.CAMERA) || isGranted(Manifest.permission.CAMERA))) {
            captureIntent(context)
        } else {
            null
        }
        val chooser = Intent.createChooser(pick, strings.chooseFile)
        if (capture != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(capture))
        try {
            fileChooserLauncher.launch(chooser)
        } catch (_: RuntimeException) {
            cancelPendingFileChooser()
        }
    }

    private fun captureIntent(context: Context): Intent? {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(context.packageManager) == null) return null
        return try {
            val directory = File(context.cacheDir, CAPTURE_DIRECTORY).apply { mkdirs() }
            val file = File.createTempFile("capture-", ".jpg", directory)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.wthaiq.signing.files", file)
            pendingCaptureUri = uri
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = android.content.ClipData.newRawUri("", uri)
            intent
        } catch (_: Exception) {
            pendingCaptureUri = null
            null
        }
    }

    private fun deliverChosenFiles(resultCode: Int, data: Intent?) {
        val callback = pendingFileCallback ?: return
        pendingFileCallback = null
        val captureUri = pendingCaptureUri
        pendingCaptureUri = null
        var uris = WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        val ok = resultCode == android.app.Activity.RESULT_OK
        val clip = data?.clipData
        if (uris == null && clip != null && ok) {
            uris = Array(clip.itemCount) { clip.getItemAt(it).uri }
        }
        val captured = captureUri?.takeIf { ok && captureHasContent(it) }
        if (uris.isNullOrEmpty() && captured != null) uris = arrayOf(captured)
        callback.onReceiveValue(uris)
    }

    private fun captureHasContent(uri: Uri): Boolean {
        val name = uri.lastPathSegment ?: return false
        val file = File(File(requireContext().cacheDir, CAPTURE_DIRECTORY), name)
        return file.exists() && file.length() > 0
    }

    private fun cancelPendingFileChooser() {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        pendingFileParams = null
    }

    private fun deleteCaptures() {
        val context = context ?: return
        runCatching { File(context.cacheDir, CAPTURE_DIRECTORY).deleteRecursively() }
    }

    private fun isGranted(permission: String): Boolean {
        val context = context ?: return false
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun declares(permission: String): Boolean {
        val context = context ?: return false
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            info.requestedPermissions?.contains(permission) == true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    // endregion

    public companion object {
        /** The default Fragment Result API request key. */
        public const val REQUEST_KEY: String = "com.wthaiq.signing.REQUEST"

        /** The key of the result inside the Fragment Result bundle; read it with [resultFrom]. */
        public const val RESULT_KEY: String = "com.wthaiq.signing.RESULT"

        /** Back stack name that [dismissFromContainer] pops. */
        public const val BACK_STACK_NAME: String = "com.wthaiq.signing"

        private const val ARG_URL = BundleCodec.EXTRA_URL
        private const val ARG_CONFIGURATION = BundleCodec.EXTRA_CONFIGURATION
        private const val ARG_REQUEST_KEY = "com.wthaiq.signing.arg.REQUEST_KEY"
        internal const val ARG_HANDLES_INSETS: String = "com.wthaiq.signing.arg.HANDLES_INSETS"
        private const val STATE_KEY = "com.wthaiq.signing.state"
        private const val CAPTURE_DIRECTORY = "wthaiq_signing"
        private const val POPUP_TIMEOUT_MILLIS = 10_000L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        /**
         * Creates the fragment. The URL is validated against [configuration] when the fragment is created; an invalid
         * URL shows an error screen and finishes with `Failed(InvalidUrl(…))`.
         */
        @JvmStatic
        @JvmOverloads
        public fun newInstance(
            url: String,
            configuration: WthaiqSigningConfiguration = WthaiqSigningConfiguration.DEFAULT,
            requestKey: String = REQUEST_KEY,
        ): WthaiqSigningFragment = create(url, configuration, requestKey, handlesInsets = false)

        internal fun create(
            url: String,
            configuration: WthaiqSigningConfiguration,
            requestKey: String,
            handlesInsets: Boolean,
        ): WthaiqSigningFragment = WthaiqSigningFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_URL, url)
                putBundle(ARG_CONFIGURATION, BundleCodec.writeConfiguration(configuration))
                putString(ARG_REQUEST_KEY, requestKey)
                putBoolean(ARG_HANDLES_INSETS, handlesInsets)
            }
        }

        /** Reads the result from a Fragment Result bundle delivered under the request key. */
        @JvmStatic
        public fun resultFrom(bundle: Bundle): SigningResult? = BundleCodec.readResult(bundle.getBundle(RESULT_KEY))

        internal fun appLanguages(context: Context): List<String> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val manager = context.getSystemService(android.app.LocaleManager::class.java)
                val locales = manager?.applicationLocales
                if (locales != null && !locales.isEmpty) {
                    return (0 until locales.size()).map { locales[it].toLanguageTag() }
                }
            }
            val locales = ConfigurationCompat.getLocales(context.resources.configuration)
            return (0 until locales.size()).mapNotNull { locales[it]?.toLanguageTag() }
        }
    }
}
