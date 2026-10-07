# Security Policy

## Reporting a vulnerability

Please report security issues privately by email to **support@wthaiq.com** with the subject
"Security: wthaiq-android". Do not open a public GitHub issue, pull request or discussion for a vulnerability.

Include a description of the issue, the affected version, the Android version, WebView version and device, steps to
reproduce and, if possible, a proof of concept. We acknowledge reports within three business days and keep you
informed until the issue is fixed.

## Supported versions

| Version | Supported |
| ------- | --------- |
| 1.x     | Yes       |

## Using the SDK safely

- **Never ship a Wthaiq API key in an app.** Create signing links on your server with a secret key (`sk_…`) and send
  only the signing URL to the app. Anything inside an APK or app bundle can be extracted.
- A signing URL lets its holder sign as that signer. Send it only to the signer it belongs to, over TLS, just before
  presenting it, and never log it. `SigningRequest.toString()` deliberately omits the URL.
- Treat the result the SDK reports as a signal for your interface. Confirm signatures on your server with verified
  webhooks (`signer.signed`, `signature_request.completed`) or `GET /signature_requests/{id}` before acting on them.
- Keep `allowedHosts` limited to `wthaiq.com`, `www.wthaiq.com` and your own custom signing domain.
- Leave `allowsInsecureLocalHosts` and `isWebContentsDebuggingEnabled` off in release builds, and never allow
  cleartext traffic in a release network security configuration.
- Keep `usesEphemeralWebsiteData` on (the default) on shared or managed devices.

## What the SDK enforces

- Signing URLs must be `https`, without credentials, on an allowed host.
- Only Wthaiq pages, your return URLs (which end the session) and the identity verification step load in the signing
  view; subframe documents go through the same policy; other links open in Custom Tabs or the system browser, and
  unknown schemes (including `intent:`, `file:` and `javascript:`) are refused.
- Page events are accepted only from Wthaiq frames and origins, through a WebMessageListener restricted to those
  origins. No `addJavascriptInterface` object is exposed to any page.
- File and content access are disabled, mixed content is refused, Safe Browsing is enabled, and TLS errors cancel the
  load.
- Camera and microphone access is granted only to trusted pages, and only when the app declares the permission.
- Session cookies, storage and cache are isolated in a separate WebView profile where supported and deleted when the
  session ends.
