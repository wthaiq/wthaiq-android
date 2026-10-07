# Contributing

Thank you for helping improve Wthaiq Signing for Android.

## Development setup

- JDK 17, Android SDK with platform 35 and build-tools 35.0.0.
- Write `sdk.dir=/path/to/Android/Sdk` to `local.properties` (git-ignored) or set `ANDROID_HOME`.
- Use the Gradle wrapper: `./gradlew` (`gradlew.bat` on Windows).

## Project layout

| Module | Artifact | Contents |
|---|---|---|
| `wthaiq-signing` | `com.wthaiq:signing` | Activity, fragment, contract, configuration, results. Pure logic (URL parsing, navigation policy, message decoding, state machine) lives in `com.wthaiq.signing.internal` without Android types, so it is unit tested on the JVM. |
| `wthaiq-signing-compose` | `com.wthaiq:signing-compose` | Jetpack Compose entry points. |
| `sample` | none | Sample app with an offline fixture mode. |

## Checks

Run before opening a pull request:

```sh
./gradlew testDebugUnitTest lint detekt apiCheck assembleRelease
./gradlew :wthaiq-signing:connectedDebugAndroidTest   # with an emulator or device attached (API 24+)
```

- Instrumented tests serve fixture pages for `https://wthaiq.com/…` through an internal request hook, so they never
  touch the network. `LocalServerEndToEndTest` runs only when its instrumentation arguments point at a local
  development server (see the class documentation).
- If you change the public API on purpose, run `./gradlew apiDump` and commit the updated `api/*.api` files.
- Keep the behaviour aligned with the iOS SDK and with `SDK-DESIGN.md` §10; record any platform difference there.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org).

## Releasing (maintainers)

1. Update `VERSION_NAME` in `gradle.properties`, `WthaiqSigning.VERSION`, and `CHANGELOG.md`.
2. Provide the signing key as Gradle properties (for example in `~/.gradle/gradle.properties`, never in the
   repository): `signingInMemoryKey`, `signingInMemoryKeyId`, `signingInMemoryKeyPassword`.
3. `./gradlew publishAllPublicationsToStagingRepository` writes signed artifacts to `build/staging-deploy`.
4. Zip the contents of `build/staging-deploy` (keeping the `com/wthaiq/…` layout) and upload the bundle in the
   [Central Portal](https://central.sonatype.com) under the verified `com.wthaiq` namespace, then publish it there.
5. Tag the release `vX.Y.Z`.
