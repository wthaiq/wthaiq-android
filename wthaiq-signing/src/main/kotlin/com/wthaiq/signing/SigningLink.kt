package com.wthaiq.signing

import com.wthaiq.signing.internal.HostMatcher
import com.wthaiq.signing.internal.UrlParts

/** Why a URL was refused before anything was loaded. */
public sealed class UrlValidationError {
    /** A short English description for logs. */
    public abstract val description: String

    /** No URL was provided. */
    public data object MissingUrl : UrlValidationError() {
        override val description: String get() = "No signing URL was provided."
    }

    /** The text is not a URL (it contains spaces, control characters or `\`, or cannot be split). */
    public data object Malformed : UrlValidationError() {
        override val description: String get() = "The signing URL is not a valid URL."
    }

    /**
     * The scheme is not `https` (plain `http` is accepted only for local development hosts when
     * `allowsInsecureLocalHosts` is enabled).
     */
    public data class UnsupportedScheme(public val scheme: String) : UrlValidationError() {
        override val description: String get() = "Signing URLs must use https (got \"$scheme\")."
    }

    /** The URL has no host. */
    public data object MissingHost : UrlValidationError() {
        override val description: String get() = "The signing URL has no host."
    }

    /** The host is not in [WthaiqSigningConfiguration.allowedHosts]. */
    public data class HostNotAllowed(public val host: String) : UrlValidationError() {
        override val description: String
            get() = "The host \"$host\" is not an allowed signing host. Add your custom signing domain to allowedHosts."
    }

    /** The URL contains a user name or password. */
    public data object ContainsCredentials : UrlValidationError() {
        override val description: String get() = "Signing URLs must not contain a user name or password."
    }

    /** A signing page URL without its signing token. */
    public data object MissingToken : UrlValidationError() {
        override val description: String get() = "The signing URL has no signing token."
    }
}

/** Thrown by [SigningLink.validate] when a URL cannot be used. */
public class InvalidSigningUrlException(
    /** Why the URL was refused. */
    public val reason: UrlValidationError,
) : IllegalArgumentException(reason.description)

/**
 * A signing URL that passed validation.
 *
 * Use [SigningLink.validate] on the URL your backend returns before showing it, or let the signing screen do it.
 */
public class SigningLink private constructor(
    /** The validated URL, unchanged. */
    public val url: String,
    /** The kind of signing URL. */
    public val kind: Kind,
    /** The normalized host. */
    public val host: String,
) {
    /** The kind of signing URL. */
    public enum class Kind(
        /** The wire name. */
        public val value: String,
    ) {
        /** `https://<host>/sign.php?token=…` (`signing_url` / `sign_url` from the API). */
        SIGNING_PAGE("signing_page"),

        /** `https://wthaiq.com/embed/sign/<token>` (`POST /signers/{id}/signing_session`). */
        EMBEDDED_SESSION("embedded_session"),

        /** Another page on an allowed host. */
        OTHER("other"),
    }

    /**
     * The URL to load for [language]: adds `lang=ar` or `lang=en` unless the URL already has a `lang` parameter.
     * [WthaiqSigningConfiguration.Language.AUTOMATIC] leaves the URL unchanged.
     */
    public fun urlFor(language: WthaiqSigningConfiguration.Language): String {
        val code = language.code ?: return url
        val parts = UrlParts.parse(url) ?: return url
        if (parts.hasQueryItem("lang")) return url
        val hashIndex = url.indexOf('#')
        val base = if (hashIndex >= 0) url.substring(0, hashIndex) else url
        val fragment = if (hashIndex >= 0) url.substring(hashIndex) else ""
        val separator = when {
            !base.contains('?') -> "?"
            base.endsWith("?") || base.endsWith("&") -> ""
            else -> "&"
        }
        return "$base${separator}lang=$code$fragment"
    }

    override fun equals(other: Any?): Boolean = other is SigningLink && other.url == url && other.kind == kind

    override fun hashCode(): Int = 31 * url.hashCode() + kind.hashCode()

    override fun toString(): String = "SigningLink(kind=$kind, host=$host)"

    /** The result of [check]: either a [SigningLink] or the reason it was refused. */
    public sealed class Check {
        /** The URL is valid. */
        public data class Valid(public val link: SigningLink) : Check()

        /** The URL was refused. */
        public data class Invalid(public val reason: UrlValidationError) : Check()
    }

    public companion object {
        /** Validates [url] against [configuration] and returns the outcome without throwing. */
        @JvmStatic
        @JvmOverloads
        public fun check(
            url: String?,
            configuration: WthaiqSigningConfiguration = WthaiqSigningConfiguration.DEFAULT,
        ): Check {
            if (url.isNullOrBlank()) return Check.Invalid(UrlValidationError.MissingUrl)
            val parts = UrlParts.parse(url) ?: return Check.Invalid(UrlValidationError.Malformed)
            val host = parts.host
            val isLocal = configuration.allowsInsecureLocalHosts && HostMatcher.isLocalDevelopmentHost(host)
            if (!(parts.scheme == "https" || (parts.scheme == "http" && isLocal))) {
                return Check.Invalid(UrlValidationError.UnsupportedScheme(parts.scheme))
            }
            if (host.isEmpty()) return Check.Invalid(UrlValidationError.MissingHost)
            if (parts.userInfo != null) return Check.Invalid(UrlValidationError.ContainsCredentials)
            if (!HostMatcher.matches(host, configuration.allowedHosts) && !isLocal) {
                return Check.Invalid(UrlValidationError.HostNotAllowed(host))
            }
            return when (val kind = classify(parts)) {
                null -> Check.Invalid(UrlValidationError.MissingToken)
                else -> Check.Valid(SigningLink(url, kind, host))
            }
        }

        /**
         * Validates [url] against [configuration].
         *
         * @throws InvalidSigningUrlException when the URL cannot be used.
         */
        @JvmStatic
        @JvmOverloads
        @Throws(InvalidSigningUrlException::class)
        public fun validate(
            url: String?,
            configuration: WthaiqSigningConfiguration = WthaiqSigningConfiguration.DEFAULT,
        ): SigningLink = when (val result = check(url, configuration)) {
            is Check.Valid -> result.link
            is Check.Invalid -> throw InvalidSigningUrlException(result.reason)
        }

        /** Returns `true` when [url] is a valid signing URL for [configuration]. */
        @JvmStatic
        @JvmOverloads
        public fun isValid(
            url: String?,
            configuration: WthaiqSigningConfiguration = WthaiqSigningConfiguration.DEFAULT,
        ): Boolean = check(url, configuration) is Check.Valid

        /** The kind of a URL on an allowed host, or `null` when a signing token is missing. */
        private fun classify(parts: UrlParts): Kind? {
            var path = parts.path
            if (path.lowercase().startsWith("/en/")) {
                path = path.substring(3)
            }
            val lower = path.lowercase()
            if (lower == "/sign.php") {
                val token = parts.queryValue("token").orEmpty()
                return if (token.isBlank()) null else Kind.SIGNING_PAGE
            }
            val embedPrefix = "/embed/sign"
            if (lower == embedPrefix || lower.startsWith("$embedPrefix/")) {
                val token = path.substring(embedPrefix.length).trim('/')
                val isToken = token.isNotEmpty() && token.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
                return if (isToken) Kind.EMBEDDED_SESSION else null
            }
            return Kind.OTHER
        }
    }
}
