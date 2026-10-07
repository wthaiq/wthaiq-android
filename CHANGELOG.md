# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org).

## [1.0.0] - 2026-10-07

### Added

- `com.wthaiq:signing`: `WthaiqSigning.Contract` (Activity Result API), `WthaiqSigningActivity`,
  `WthaiqSigningFragment` with a listener and the Fragment Result API, `SigningRequest`.
- `com.wthaiq:signing-compose`: `rememberWthaiqSigningLauncher` and `WthaiqSigningSheet`.
- Typed `SigningResult` (signed, declined, canceled, expired, failed) and `SigningEvent`, with `SigningOutcome`
  (reference, detection, return URL, already-final flag, reason) and `WthaiqSigningError`.
- `WthaiqSigningConfiguration`: language (automatic, Arabic, English), allowed hosts, return URLs, close button,
  appearance (brand tint `#121AB0` / `#8B91F7`, light/dark, title, header, progress bar, close confirmation),
  timeout, dismissal, identity verification policy and hosts, platform return detection, ephemeral website data,
  development switches.
- Completion detection from return URLs, the embedded page's `postMessage` events (WebMessageListener restricted to
  the platform origins) and the signing page content.
- Navigation policy limited to the platform hosts, return URLs and the identity verification step; other links open in
  Custom Tabs or the system.
- File chooser and camera for ID attachments, runtime camera/microphone permission handling for identity
  verification, Safe Browsing, isolated website data, retry screen, renderer crash recovery, edge-to-edge insets,
  RTL/LTR, dark mode, TalkBack labels and announcements, state restoration.
- `SigningLink` validation helpers and `SigningResult.fromReturnUrl`.
- Sample app with an offline fixture mode.
