package com.wthaiq.signing

import com.wthaiq.signing.internal.HostMatcher

/**
 * Options for one signing session. Every option has a safe default, so [WthaiqSigningConfiguration.DEFAULT]
 * works for links on `wthaiq.com`.
 *
 * ```kotlin
 * val configuration = WthaiqSigningConfiguration {
 *     language = WthaiqSigningConfiguration.Language.ARABIC
 *     addReturnUrl("https://example.com/signing/done")
 *     addAllowedHost("sign.example.com")
 * }
 * ```
 */
public class WthaiqSigningConfiguration private constructor(builder: Builder) {

    /** The language of the SDK's own interface and, when set explicitly, of the signing page. */
    public enum class Language(
        /** The value added as `lang=` to the signing URL, or `null` for [AUTOMATIC]. */
        public val code: String?,
    ) {
        /**
         * Follow the app's current locale for the SDK's interface. The signing page keeps the language chosen when
         * the request was created (`language` on `POST /sessions`).
         */
        AUTOMATIC(null),

        /** Arabic, right to left. Adds `lang=ar` to the signing URL. */
        ARABIC("ar"),

        /** English, left to right. Adds `lang=en` to the signing URL. */
        ENGLISH("en"),
    }

    /** Which third-party hosts may load inside the signing view during identity verification. */
    public enum class IdentityVerificationPolicy {
        /**
         * Allow a host that a platform page navigates to by script or redirect (this is how the signing page opens
         * the identity verification step), plus [identityVerificationHosts]. Links the signer taps to other hosts
         * still open in the browser.
         */
        FOLLOW_PLATFORM,

        /** Allow only [identityVerificationHosts]. Identity verification fails if its host is not listed. */
        LISTED_HOSTS_ONLY,
    }

    /** Light or dark appearance for the SDK's interface. */
    public enum class InterfaceStyle {
        SYSTEM,
        LIGHT,
        DARK,
    }

    /** What happens after the signer signs or declines. */
    public sealed class Dismissal {
        /**
         * Ask the container to dismiss the signing screen after [delayMillis], so the signer sees the page's
         * confirmation first. Other results (expired, failed) never dismiss automatically.
         */
        public class Automatic(public val delayMillis: Long = DEFAULT_DISMISSAL_DELAY_MILLIS) : Dismissal() {
            override fun equals(other: Any?): Boolean = other is Automatic && other.delayMillis == delayMillis
            override fun hashCode(): Int = delayMillis.hashCode()
            override fun toString(): String = "Automatic(delayMillis=$delayMillis)"
        }

        /** Keep the signing screen open until the signer taps Close. */
        public data object Manual : Dismissal()
    }

    /** Visual options. */
    public class Appearance private constructor(builder: Builder) {
        /** Tint for buttons and the progress bar. Defaults to the Wthaiq brand blue. */
        public val tintColor: SigningColor = builder.tintColor

        /** Light, dark or the system setting. The signing page itself is always light. */
        public val interfaceStyle: InterfaceStyle = builder.interfaceStyle

        /** Title shown above the page. `null` uses "Sign document" in the session language. */
        public val title: String? = builder.title

        /** Show the SDK's title bar. */
        public val showsHeader: Boolean = builder.showsHeader

        /** Show a thin progress bar while pages load. */
        public val showsProgressBar: Boolean = builder.showsProgressBar

        /** Ask for confirmation when the signer closes the screen (Close or Back) before finishing. */
        public val confirmsBeforeClosing: Boolean = builder.confirmsBeforeClosing

        /** A builder initialized with these values. */
        public fun newBuilder(): Builder = Builder(this)

        override fun equals(other: Any?): Boolean =
            other is Appearance &&
                other.tintColor == tintColor &&
                other.interfaceStyle == interfaceStyle &&
                other.title == title &&
                other.showsHeader == showsHeader &&
                other.showsProgressBar == showsProgressBar &&
                other.confirmsBeforeClosing == confirmsBeforeClosing

        override fun hashCode(): Int =
            listOf(tintColor, interfaceStyle, title, showsHeader, showsProgressBar, confirmsBeforeClosing).hashCode()

        override fun toString(): String =
            "Appearance(tintColor=$tintColor, interfaceStyle=$interfaceStyle, title=$title, showsHeader=$showsHeader, " +
                "showsProgressBar=$showsProgressBar, confirmsBeforeClosing=$confirmsBeforeClosing)"

        /** Builds an [Appearance]. */
        public class Builder() {
            public var tintColor: SigningColor = SigningColor.BRAND
            public var interfaceStyle: InterfaceStyle = InterfaceStyle.SYSTEM
            public var title: String? = null
            public var showsHeader: Boolean = true
            public var showsProgressBar: Boolean = true
            public var confirmsBeforeClosing: Boolean = true

            internal constructor(source: Appearance) : this() {
                tintColor = source.tintColor
                interfaceStyle = source.interfaceStyle
                title = source.title
                showsHeader = source.showsHeader
                showsProgressBar = source.showsProgressBar
                confirmsBeforeClosing = source.confirmsBeforeClosing
            }

            public fun build(): Appearance = Appearance(this)
        }

        public companion object {
            /** The default appearance. */
            @JvmField
            public val DEFAULT: Appearance = Builder().build()
        }
    }

    /** Language of the SDK's interface and, when explicit, of the signing page. */
    public val language: Language = builder.language

    /**
     * Hosts allowed to serve the signing page (exact hosts or `*.example.com`). Add your custom signing domain
     * here. Page events are trusted only from these hosts.
     */
    public val allowedHosts: Set<String> = builder.allowedHosts.toSet()

    /**
     * Your return URLs (`return_url`, `success_url`, `cancel_url`). Navigation to a matching URL ends the session;
     * it is never loaded inside the signing view.
     */
    public val returnUrls: List<ReturnUrlPattern> = builder.returnUrls.toList()

    /** Show a Close button. */
    public val showsCloseButton: Boolean = builder.showsCloseButton

    /** Visual options. */
    public val appearance: Appearance = builder.appearance

    /** Milliseconds to wait for a page to load before showing the retry screen (clamped to 5 000–120 000). */
    public val timeoutMillis: Long = builder.timeoutMillis

    /** What happens after the signer signs or declines. */
    public val dismissal: Dismissal = builder.dismissal

    /** Which third-party hosts may load during identity verification. */
    public val identityVerification: IdentityVerificationPolicy = builder.identityVerification

    /** Hosts always allowed for identity verification (exact hosts or `*.example.com`). */
    public val identityVerificationHosts: Set<String> = builder.identityVerificationHosts.toSet()

    /**
     * Recognise the platform's own return navigation (`?session_id=sr_…&signer_id=…&status=…`) even when its URL
     * is not listed in [returnUrls].
     */
    public val detectsPlatformReturnNavigation: Boolean = builder.detectsPlatformReturnNavigation

    /**
     * Isolate cookies, storage and cache of the signing session and delete them when the session ends, so nothing
     * from it stays on the device. Uses a separate WebView profile where the installed WebView supports it.
     */
    public val usesEphemeralWebsiteData: Boolean = builder.usesEphemeralWebsiteData

    /**
     * Development only: accept `http` and local hosts (`localhost`, `127.0.0.1`, `10.0.2.2`, `*.localhost`,
     * `*.test`).
     */
    public val allowsInsecureLocalHosts: Boolean = builder.allowsInsecureLocalHosts

    /**
     * Development only: enable WebView remote debugging (`chrome://inspect`). This switch is global to the app's
     * process; the SDK only ever turns it on, never off.
     */
    public val isWebContentsDebuggingEnabled: Boolean = builder.isWebContentsDebuggingEnabled

    /** [timeoutMillis] clamped to the accepted range. */
    internal val effectiveTimeoutMillis: Long
        get() = timeoutMillis.coerceIn(MIN_TIMEOUT_MILLIS, MAX_TIMEOUT_MILLIS)

    /** Milliseconds before an automatic dismissal, or `null` for [Dismissal.Manual]. Clamped to 0–30 000. */
    internal val dismissalDelayMillis: Long?
        get() = when (val value = dismissal) {
            is Dismissal.Manual -> null
            is Dismissal.Automatic -> value.delayMillis.coerceIn(0L, MAX_DISMISSAL_DELAY_MILLIS)
        }

    /** A builder initialized with this configuration. */
    public fun newBuilder(): Builder = Builder(this)

    override fun equals(other: Any?): Boolean =
        other is WthaiqSigningConfiguration &&
            other.language == language &&
            other.allowedHosts == allowedHosts &&
            other.returnUrls == returnUrls &&
            other.showsCloseButton == showsCloseButton &&
            other.appearance == appearance &&
            other.timeoutMillis == timeoutMillis &&
            other.dismissal == dismissal &&
            other.identityVerification == identityVerification &&
            other.identityVerificationHosts == identityVerificationHosts &&
            other.detectsPlatformReturnNavigation == detectsPlatformReturnNavigation &&
            other.usesEphemeralWebsiteData == usesEphemeralWebsiteData &&
            other.allowsInsecureLocalHosts == allowsInsecureLocalHosts &&
            other.isWebContentsDebuggingEnabled == isWebContentsDebuggingEnabled

    override fun hashCode(): Int = listOf(
        language, allowedHosts, returnUrls, showsCloseButton, appearance, timeoutMillis, dismissal,
        identityVerification, identityVerificationHosts, detectsPlatformReturnNavigation, usesEphemeralWebsiteData,
        allowsInsecureLocalHosts, isWebContentsDebuggingEnabled,
    ).hashCode()

    override fun toString(): String =
        "WthaiqSigningConfiguration(language=$language, allowedHosts=$allowedHosts, returnUrls=$returnUrls, " +
            "showsCloseButton=$showsCloseButton, appearance=$appearance, timeoutMillis=$timeoutMillis, " +
            "dismissal=$dismissal, identityVerification=$identityVerification, " +
            "identityVerificationHosts=$identityVerificationHosts, " +
            "detectsPlatformReturnNavigation=$detectsPlatformReturnNavigation, " +
            "usesEphemeralWebsiteData=$usesEphemeralWebsiteData, allowsInsecureLocalHosts=$allowsInsecureLocalHosts, " +
            "isWebContentsDebuggingEnabled=$isWebContentsDebuggingEnabled)"

    /** Builds a [WthaiqSigningConfiguration]. */
    public class Builder() {
        public var language: Language = Language.AUTOMATIC
        public var allowedHosts: MutableSet<String> = WthaiqSigning.PLATFORM_HOSTS.toMutableSet()
        public var returnUrls: MutableList<ReturnUrlPattern> = mutableListOf()
        public var showsCloseButton: Boolean = true
        public var appearance: Appearance = Appearance.DEFAULT
        public var timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS
        public var dismissal: Dismissal = Dismissal.Automatic()
        public var identityVerification: IdentityVerificationPolicy = IdentityVerificationPolicy.FOLLOW_PLATFORM
        public var identityVerificationHosts: MutableSet<String> = mutableSetOf()
        public var detectsPlatformReturnNavigation: Boolean = true
        public var usesEphemeralWebsiteData: Boolean = true
        public var allowsInsecureLocalHosts: Boolean = false
        public var isWebContentsDebuggingEnabled: Boolean = false

        internal constructor(source: WthaiqSigningConfiguration) : this() {
            language = source.language
            allowedHosts = source.allowedHosts.toMutableSet()
            returnUrls = source.returnUrls.toMutableList()
            showsCloseButton = source.showsCloseButton
            appearance = source.appearance
            timeoutMillis = source.timeoutMillis
            dismissal = source.dismissal
            identityVerification = source.identityVerification
            identityVerificationHosts = source.identityVerificationHosts.toMutableSet()
            detectsPlatformReturnNavigation = source.detectsPlatformReturnNavigation
            usesEphemeralWebsiteData = source.usesEphemeralWebsiteData
            allowsInsecureLocalHosts = source.allowsInsecureLocalHosts
            isWebContentsDebuggingEnabled = source.isWebContentsDebuggingEnabled
        }

        /**
         * Adds a return URL (scheme, host and path prefix are matched; the query is ignored). Returns `false` when
         * the URL cannot be used as a pattern (an `http(s)` URL without a host, or text that is not a URL).
         */
        public fun addReturnUrl(url: String): Boolean {
            val pattern = ReturnUrlPattern.parse(url) ?: return false
            return addReturnUrl(pattern)
        }

        /** Adds a return URL pattern. Always returns `true`. */
        public fun addReturnUrl(pattern: ReturnUrlPattern): Boolean {
            if (pattern !in returnUrls) {
                returnUrls.add(pattern)
            }
            return true
        }

        /** Allows another host to serve the signing page, for example your custom signing domain. */
        public fun addAllowedHost(host: String): Builder {
            val normalized = HostMatcher.normalize(host)
            if (normalized.isNotEmpty()) {
                allowedHosts.add(normalized)
            }
            return this
        }

        /** Allows a host for the identity verification step (exact host or `*.example.com`). */
        public fun addIdentityVerificationHost(host: String): Builder {
            val normalized = HostMatcher.normalize(host)
            if (normalized.isNotEmpty()) {
                identityVerificationHosts.add(normalized)
            }
            return this
        }

        /** Changes the appearance with a builder block. */
        public fun appearance(block: Appearance.Builder.() -> Unit): Builder {
            appearance = appearance.newBuilder().apply(block).build()
            return this
        }

        public fun build(): WthaiqSigningConfiguration = WthaiqSigningConfiguration(this)
    }

    public companion object {
        /** Default page-load timeout. */
        public const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000

        /** Shortest accepted timeout; shorter values are raised to it. */
        public const val MIN_TIMEOUT_MILLIS: Long = 5_000

        /** Longest accepted timeout; longer values are lowered to it. */
        public const val MAX_TIMEOUT_MILLIS: Long = 120_000

        /** Default delay before an automatic dismissal. */
        public const val DEFAULT_DISMISSAL_DELAY_MILLIS: Long = 1_500

        internal const val MAX_DISMISSAL_DELAY_MILLIS: Long = 30_000

        /** The default configuration. */
        @JvmField
        public val DEFAULT: WthaiqSigningConfiguration = Builder().build()
    }
}

/** Builds a [WthaiqSigningConfiguration] with a Kotlin builder block. */
public fun WthaiqSigningConfiguration(
    block: WthaiqSigningConfiguration.Builder.() -> Unit,
): WthaiqSigningConfiguration = WthaiqSigningConfiguration.Builder().apply(block).build()
