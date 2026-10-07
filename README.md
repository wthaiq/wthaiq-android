# Wthaiq Signing for Android

> مكتبة أندرويد الرسمية لمنصة وثائق للتوقيع الإلكتروني. تعرض صفحة التوقيع داخل تطبيقك بواجهة أصيلة، وتُبلغ التطبيق بنتيجة
> الجلسة: وُقِّع المستند، أو اعتذر الموقِّع، أو أُغلقت الصفحة، أو انتهت صلاحية الرابط. ولا يحمل التطبيق أيّ مفتاح سرّي
> لواجهة الربط: يُنشئ خادمك رابط التوقيع بمكتبة الخادم، ثم يسلّمه إلى التطبيق. وتدعم المكتبة العربية والإنجليزية،
> والاتجاهين من اليمين إلى اليسار ومن اليسار إلى اليمين، والوضع الداكن، وأدوات الوصول.

The official Android library for showing [Wthaiq](https://developers.wthaiq.com) e-signature pages inside your app.
Your backend creates the signing link; the SDK presents it natively and tells your app how the session ended.

- Activity Result API, Fragment and Jetpack Compose entry points; minSdk 24, Kotlin and Java friendly.
- Signed / declined / canceled / expired / failed results, detected without polling and without an API key.
- Loading, error and retry screens, timeouts, edge-to-edge insets, dark mode, Arabic (RTL) and English (LTR),
  TalkBack and font scaling.
- Navigation locked to the Wthaiq hosts, your return URL and the identity verification step; every other link opens
  in Custom Tabs or the system browser.
- File chooser and camera for ID attachments and identity verification, guarded by the permissions your app declares.
- Survives rotation without reloading the page, and restores the session after the process is recreated.

## Contents

- [Installation](#installation)
- [Security model](#security-model)
- [Quick start: Activity Result API](#quick-start-activity-result-api)
- [Quick start: Jetpack Compose](#quick-start-jetpack-compose)
- [Quick start: Fragment](#quick-start-fragment)
- [Handling the result](#handling-the-result)
- [Getting a signing URL on your backend](#getting-a-signing-url-on-your-backend)
- [Configuration](#configuration)
- [Return URLs](#return-urls)
- [Language, RTL and dark mode](#language-rtl-and-dark-mode)
- [Camera, ID attachments and identity verification](#camera-id-attachments-and-identity-verification)
- [Events](#events)
- [How completion is detected](#how-completion-is-detected)
- [ProGuard and R8](#proguard-and-r8)
- [Troubleshooting](#troubleshooting)
- [Versioning](#versioning)
- [Support and security](#support-and-security)

## Installation

```kotlin
// build.gradle.kts of your app module
dependencies {
    implementation("com.wthaiq:signing:1.0.0")
    // Optional: Jetpack Compose entry points (includes com.wthaiq:signing)
    implementation("com.wthaiq:signing-compose:1.0.0")
}
```

Both artifacts are served from Maven Central (`mavenCentral()` in your repositories).

> **Publishing is pending.** Until 1.0.0 appears on Maven Central, build the library from this repository with
> `./gradlew publishToMavenLocal` and add `mavenLocal()` to your repositories.

Requirements:

| | |
|---|---|
| Android | minSdk 24 (Android 7.0), compileSdk 35 |
| Android System WebView | 83 or later for page events (`WebViewFeature.WEB_MESSAGE_LISTENER`); older WebViews still detect return URLs |
| Dependencies | AndroidX only: `activity`, `fragment`, `core`, `webkit`, `browser` (Custom Tabs); the Compose artifact adds `compose-runtime` and `activity-compose` |

## Security model

- **No API key of any kind in the app.** Your backend calls the Wthaiq API with its secret key and hands only the
  signing URL to the app. Anything inside an app package can be extracted.
- The result the SDK reports drives your interface. Confirm the final state on your backend with verified webhooks
  (`signer.signed`, `signature_request.completed`) or `GET /signature_requests/{id}` before acting on it.
- The SDK never calls the Wthaiq API itself; it has no key to call it with.
- Signing URLs must be `https`, without user info, on an allowed host (`wthaiq.com`, `www.wthaiq.com`, plus your
  custom signing domain). Anything else is refused before it loads.
- Page events are accepted only from frames **and** message origins on the allowed hosts. They reach native code
  through `WebViewCompat.addWebMessageListener`, restricted to those origins; the SDK never uses
  `addJavascriptInterface`.
- JavaScript is enabled because the signing page needs it; file and content access are off, mixed content is refused,
  Safe Browsing is on, and TLS errors are never bypassed.
- By default the session's cookies, storage and cache are isolated and deleted when the session ends
  (`usesEphemeralWebsiteData`).

## Quick start: Activity Result API

```kotlin
class OrderActivity : ComponentActivity() {

    private val signing = registerForActivityResult(WthaiqSigning.Contract()) { result ->
        when (result) {
            is SigningResult.Signed -> showConfirming(result.outcome.reference.signatureRequestId)
            is SigningResult.Declined -> showDeclined(result.outcome.reason)
            SigningResult.Canceled -> Unit // nothing signed; the link still works until it expires
            SigningResult.Expired -> requestNewLink()
            is SigningResult.Failed -> showError(result.error)
        }
    }

    private fun sign(signingUrl: String) {
        signing.launch(signingUrl) // or signing.launch(SigningRequest(signingUrl, configuration))
    }
}
```

`WthaiqSigning.Contract(onEvent = { event -> … })` also delivers [events](#events) while the screen is open. The
contract starts `WthaiqSigningActivity`, a full-screen, edge-to-edge activity that handles rotation itself.

Java:

```java
ActivityResultLauncher<SigningRequest> signing =
        registerForActivityResult(new WthaiqSigning.Contract(), result -> {
            if (result instanceof SigningResult.Signed) { /* … */ }
        });
signing.launch(new SigningRequest(signingUrl));
```

## Quick start: Jetpack Compose

```kotlin
@Composable
fun OrderScreen(viewModel: OrderViewModel) {
    val signing = rememberWthaiqSigningLauncher { result ->
        if (result.isSigned) viewModel.refreshOrder()
    }
    Button(onClick = { signing.launch(viewModel.signingUrl) }) { Text("Sign") }
}
```

Declarative presentation, the twin of the iOS `.wthaiqSigning(isPresented:url:)` modifier:

```kotlin
var signing by rememberSaveable { mutableStateOf(false) }

WthaiqSigningSheet(isPresented = signing, url = signingUrl) { result ->
    signing = false
    if (result.isSigned) viewModel.refreshOrder()
}
```

Both present the same full-screen signing activity, so they survive configuration changes and process recreation
(the Activity Result API redelivers the result).

## Quick start: Fragment

Host `WthaiqSigningFragment` in your own layout when the signing page should live inside your screen:

```kotlin
class SigningHostActivity : FragmentActivity(), WthaiqSigningFragment.Listener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signing_host)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, WthaiqSigningFragment.newInstance(signingUrl))
                .addToBackStack(WthaiqSigningFragment.BACK_STACK_NAME)
                .commit()
        }
    }

    override fun onSigningResult(fragment: WthaiqSigningFragment, result: SigningResult) {
        // called exactly once
    }

    override fun onSigningEvent(fragment: WthaiqSigningFragment, event: SigningEvent) { /* optional */ }

    override fun onSigningDismissRequested(fragment: WthaiqSigningFragment) {
        fragment.dismissFromContainer() // the default: pops BACK_STACK_NAME or removes the fragment
    }
}
```

- The listener is the parent fragment or the activity when it implements `WthaiqSigningFragment.Listener`, or an
  explicit `fragment.listener` (not restored after recreation).
- The result is also published through the Fragment Result API under `WthaiqSigningFragment.REQUEST_KEY` (or the key
  given to `newInstance`); read it with `WthaiqSigningFragment.resultFrom(bundle)`.
- Declare `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"` on your
  activity so a rotation keeps the page (and anything the signer typed or drew). Otherwise the page is reloaded after
  the activity is recreated.
- Your activity handles window insets and `android:windowSoftInputMode="adjustResize"`; `WthaiqSigningActivity`
  does this for you.

## Handling the result

| Result | Meaning | What to do |
|---|---|---|
| `Signed(outcome)` | The signer signed (or, with `outcome.wasAlreadyFinal`, had already signed this link). | Show a "confirming" state; wait for `signer.signed` / `signature_request.completed` on your backend. |
| `Declined(outcome)` | The signer declined (or had already declined). `outcome.reason` may carry a code such as `signer_declined`. | Tell the user; the sender is notified by Wthaiq. |
| `Canceled` | The signer closed the screen (Close or Back), or your code called `cancel()`. | Nothing was signed. Offer to resume later; the same link still works until it expires. |
| `Expired` | The link or embedded session has expired. | Ask your backend for a new link (`POST /signers/{id}/signing_session` links last 30 minutes). |
| `Failed(error)` | The session could not continue. | See `WthaiqSigningError` below. |

`outcome.reference` carries `signatureRequestId` (`sr_…`) and `signerId` (`sgr_…`) when the page or the return URL
provides them; `outcome.detection` says how the outcome was detected (`RETURN_URL`, `PAGE_EVENT`, `PAGE_CONTENT`).

`WthaiqSigningError`:

| Case | When |
|---|---|
| `InvalidUrl(UrlValidationError)` | The URL was refused before loading (`MissingUrl`, `Malformed`, `UnsupportedScheme`, `MissingHost`, `HostNotAllowed`, `ContainsCredentials`, `MissingToken`). |
| `Network(code, message)` | A WebView loading error (`WebViewClient.ERROR_*`, offline, DNS, TLS…); `isOffline` tells connectivity problems apart. |
| `TimedOut` | A page did not finish within `timeoutMillis`. |
| `HttpStatus(status)` | The signing page answered 429 or 5xx. |
| `LinkUnavailable(SigningPageState)` | The sender canceled the request (`CANCELED`), no request matches the link (`NOT_FOUND`), or the link has no token (`MISSING_TOKEN`). |
| `PageError(reason)` | The embedded page reported another error code. |
| `WebContentTerminated` | The WebView renderer stopped more than once. |
| `UnexpectedReturnStatus(status)` | A return URL carried an unknown `status`. |

Network, timeout, HTTP and renderer errors show a retry screen first; `Failed` is reported only if the signer then
closes the screen. `isRetryable` tells you whether trying again may help.

Validate a URL before showing any interface with `SigningLink.validate(url, configuration)` (throws
`InvalidSigningUrlException`), `SigningLink.check(…)` or `SigningLink.isValid(…)`.

## Getting a signing URL on your backend

Any of these URLs works (see the [API reference](https://developers.wthaiq.com/docs)):

| Server call | URL to send to the app | Notes |
|---|---|---|
| `POST /signers/{id}/signing_session` with `mode: "iframe"` | `signing_url` (`https://wthaiq.com/embed/sign/…`) | Best for apps: valid 30 minutes, reports signed / declined / expired events. Needs the `embed` plan feature. |
| `POST /signers/{id}/signing_session` with `mode: "redirect"` and `success_url` / `cancel_url` | `signing_url` | The page returns to your URL with `?session_id=…&signer_id=…&status=completed\|declined`. Needs `return_url`. |
| `POST /sessions` (`channel: "link"`, optional `return_url`, `language`) | `sign_url` | Workflow-based requests. |
| `POST /signature_requests` | `signers[].signing_url` | Plain links; the SDK reads the outcome from the page. |

Node.js example (the PHP and Python libraries use the same names):

```js
import Wthaiq from 'wthaiq';
const wt = new Wthaiq(process.env.WTHAIQ_API_KEY);

app.post('/wthaiq/signing-url', requireUser, async (req, res) => {
  const signerId = await signerIdFor(req.user, req.body.order_id); // your lookup
  const session = await wt.signers.createSigningSession(signerId, { mode: 'iframe' });
  res.json({ url: session.signing_url });
});
```

## Configuration

```kotlin
val configuration = WthaiqSigningConfiguration {
    language = WthaiqSigningConfiguration.Language.ARABIC // AUTOMATIC (default), ARABIC, ENGLISH
    showsCloseButton = true
    appearance {
        tintColor = SigningColor(0x121AB0, 0x8B91F7) // default: Wthaiq brand blue (light, dark)
        interfaceStyle = WthaiqSigningConfiguration.InterfaceStyle.SYSTEM // LIGHT, DARK
        title = null // default "Sign document" / «توقيع المستند»
        confirmsBeforeClosing = true
    }
    timeoutMillis = 30_000 // per page load, clamped to 5 000–120 000
    dismissal = WthaiqSigningConfiguration.Dismissal.Automatic(1_500) // or Dismissal.Manual
    addReturnUrl("https://example.com/signing/done")
    addAllowedHost("sign.example.com") // your custom signing domain, if any
}
signing.launch(SigningRequest(signingUrl, configuration))
```

Java: `new WthaiqSigningConfiguration.Builder()`, set the properties, then `build()`.

| Option | Default | Description |
|---|---|---|
| `language` | `AUTOMATIC` | SDK text language. Explicit values also add `lang=ar`/`lang=en` to the URL. `AUTOMATIC` follows the app's locale (the per-app language on Android 13+, otherwise the device language) and leaves the page in the request's language. |
| `allowedHosts` | `wthaiq.com`, `www.wthaiq.com` | Hosts allowed to serve the signing page (`*.example.com` wildcards accepted, never matching the apex). Add your custom signing domain. |
| `returnUrls` | none | Your `return_url` / `success_url` / `cancel_url` patterns. |
| `showsCloseButton` | `true` | Show Close in the title bar and on error screens. |
| `appearance` | brand blue, system style | Tint, light/dark, title, header, progress bar, close confirmation. |
| `timeoutMillis` | `30000` | Milliseconds before the retry screen replaces a page that does not load. |
| `dismissal` | `Automatic(1500)` | After signed/declined, dismiss after the delay (so the signer sees the confirmation), or `Manual`. Other results never dismiss automatically. |
| `identityVerification` | `FOLLOW_PLATFORM` | `LISTED_HOSTS_ONLY` allows only `identityVerificationHosts` for the identity verification step. |
| `identityVerificationHosts` | none | Hosts always allowed for identity verification. |
| `detectsPlatformReturnNavigation` | `true` | Treat the page's own `?session_id=sr_…&status=…` navigation as the end of the session even if the URL is not in `returnUrls`. |
| `usesEphemeralWebsiteData` | `true` | Isolate and delete cookies, storage and cache when the session ends. |
| `allowsInsecureLocalHosts` | `false` | Development only: allow `http` and local hosts (`localhost`, `127.0.0.1`, `10.0.2.2`, `*.localhost`, `*.test`). |
| `isWebContentsDebuggingEnabled` | `false` | Development only: enable `chrome://inspect` (process-wide; the SDK only ever turns it on). |

## Return URLs

Set `return_url` (sessions, iframe signing sessions) or `success_url` / `cancel_url` (redirect signing sessions) on
your backend, and add the same URLs with `addReturnUrl`. When the page navigates to a matching URL, in any frame, the
session ends with the parsed result and your page is never loaded inside the signing view. Patterns match scheme,
host and path prefix; the query is ignored. Custom schemes (`ReturnUrlPattern("myapp")`) are matched too, although
the API only accepts `https` return URLs on an accepted domain.

The signing page appends `session_id`, `signer_id` and `status=completed|declined` to return URLs, except the
embedded page in iframe mode, which navigates to the plain `return_url` after signing (the SDK learns the outcome from
the page's events before that).

If your app receives a return URL some other way (for example an App Link after signing in the browser), parse it
with `SigningResult.fromReturnUrl(url)`.

## Language, RTL and dark mode

- `ARABIC` lays out the SDK's own interface right to left with formal Arabic text; `ENGLISH` uses left to right.
  Right-to-left layout needs `android:supportsRtl="true"` on your `<application>` (the default in new projects).
- The SDK's interface follows the system light/dark setting (or `appearance.interfaceStyle`). The brand blue switches
  to a lighter shade in dark mode for contrast. The signing page itself is designed on a light background.
- Text uses `sp` sizes, so it follows the system font scale. The close button is labeled, titles are accessibility
  headings, the error screen is a live region, and signed/declined results are announced for TalkBack.
- Back shows the same "Leave signing?" confirmation as Close once the page has loaded.

## Camera, ID attachments and identity verification

- **ID attachment** (`<input type="file" accept="image/*,application/pdf" capture>`): the SDK opens the system
  chooser (files and gallery) with the camera as an extra option. Photos are written to the app's cache through the
  SDK's own `FileProvider` and deleted when the session ends. No permission is needed for this unless your app
  declares `CAMERA`, in which case Android requires it to be granted: the SDK asks for it, and without it offers files
  only.
- **Identity verification** opens a page of an external identity verification provider (a third-party host) inside
  the signing view and needs the live camera. Declare the permission in your app and the SDK requests it at runtime
  when the page asks:

  ```xml
  <uses-permission android:name="android.permission.CAMERA" />
  <uses-feature android:name="android.hardware.camera" android:required="false" />
  <!-- only if the step also needs audio -->
  <uses-permission android:name="android.permission.RECORD_AUDIO" />
  ```

  Camera and microphone are granted only to Wthaiq pages and to the identity verification host the platform page
  opened, and only when your app declares the permission. Without the declaration the request is denied.
- **Token signing** (`method: token`) needs the desktop signing agent on the same computer, so it cannot be completed
  on a phone.

## Events

`SigningEvent` is informational: `PageLoaded`, `Viewed(reference)`, `PageStateChanged(state)` (`NOT_YOUR_TURN`,
`ACCESS_CODE_REQUIRED`, `IDENTITY_UNAVAILABLE`), `IdentityVerificationStarted(host)`, `ExternalUrlOpened`,
`NavigationBlocked`, `LoadFailed(error)` and `PageMessage(name, fields)` for messages a newer page sends that this
version does not know. Receive them with `WthaiqSigning.Contract(onEvent)`, `rememberWthaiqSigningLauncher(onEvent)`
or `WthaiqSigningFragment.Listener.onSigningEvent`.

## How completion is detected

The first final signal wins and the result is delivered exactly once:

1. **Return URL** — navigation (any frame) to one of your `returnUrls`, or the page's own
   `?session_id=sr_…&status=completed|declined` navigation.
2. **Page events** — the embedded signing page posts `viewed`, `signed`, `declined` and `error` messages; a bridge
   script injected at document start into platform frames forwards them through a WebMessageListener restricted to
   the platform origins.
3. **Page content** (best effort, `sign.php`) — the completion panels and the "cannot sign" state pages. A signed
   result waits while a token signature is still being merged into the PDF.
4. **Closing** — Close or Back after confirmation → `Canceled` (or `Failed` with the visible load error).

## ProGuard and R8

No rules are needed in your app. The library ships `consumer-rules.pro` (applied automatically) and uses no
reflection, no `@JavascriptInterface` and no class-name serialization, so full R8 mode is safe.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `Failed(InvalidUrl(HostNotAllowed))` | Add your custom signing domain with `addAllowedHost`. |
| Results arrive only through the return URL or page content, never as page events | The device's Android System WebView is older than 83; ask the user to update it from Google Play. |
| The page reloads after rotation in your own activity | Declare the `configChanges` listed in [Quick start: Fragment](#quick-start-fragment), or use the contract. |
| The SDK interface stays left to right in Arabic | Set `android:supportsRtl="true"` on your `<application>`. |
| `net::ERR_CLEARTEXT_NOT_PERMITTED` against a local server | Development only: enable `allowsInsecureLocalHosts` and allow cleartext for that host in a debug `network_security_config.xml`. The emulator reaches your computer at `10.0.2.2`. |
| Identity verification shows a camera error | Declare `android.permission.CAMERA` (see above). |
| An external link does nothing | No browser is installed; `ExternalUrlOpened` is not emitted in that case. |

## Versioning

The SDK follows [Semantic Versioning](https://semver.org). The public API is checked on every build against the
`api/*.api` dumps (binary-compatibility-validator). `WthaiqSigning.VERSION` is the SDK version and
`WthaiqSigning.API_VERSION` the Wthaiq API version it was built against. See [CHANGELOG.md](CHANGELOG.md).

## Support and security

- Documentation: <https://developers.wthaiq.com/docs>
- Support: <support@wthaiq.com>
- Report security issues privately as described in [SECURITY.md](SECURITY.md).

Licensed under the [MIT License](LICENSE).
