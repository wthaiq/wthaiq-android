package com.wthaiq.signing

import com.wthaiq.signing.internal.HostMatcher
import com.wthaiq.signing.internal.UrlParts

/**
 * Describes one of your return URLs. Navigation to a matching URL ends the signing session instead of loading
 * your page inside the signing view.
 *
 * Scheme and host are compared case-insensitively; the path matches when it equals [pathPrefix] or continues it
 * after a `/`. The query, fragment and port are ignored. An `http(s)` pattern always needs a host.
 */
public class ReturnUrlPattern @JvmOverloads constructor(
    scheme: String,
    host: String? = null,
    pathPrefix: String = "/",
) {
    /** Lowercased scheme, for example `https` or your app's custom scheme. */
    public val scheme: String = scheme.trim().lowercase()

    /** Normalized host, or `null` to accept any host (custom schemes only). */
    public val host: String? = host?.let(HostMatcher::normalize)?.ifEmpty { null }

    /** Normalized path prefix (`/` accepts any path). */
    public val pathPrefix: String = normalizePath(pathPrefix)

    /** Returns `true` when [url] is covered by this pattern. */
    public fun matches(url: String): Boolean {
        val parts = UrlParts.parse(url) ?: return false
        return matches(parts)
    }

    internal fun matches(parts: UrlParts): Boolean {
        if (parts.scheme.isEmpty() || parts.scheme != scheme) return false
        val expectedHost = host
        if (expectedHost != null) {
            if (parts.host != expectedHost) return false
        } else if (isWebScheme(scheme)) {
            return false
        }
        if (pathPrefix == "/") return true
        val path = normalizePath(parts.path)
        return path == pathPrefix || path.startsWith("$pathPrefix/")
    }

    override fun equals(other: Any?): Boolean =
        other is ReturnUrlPattern && other.scheme == scheme && other.host == host && other.pathPrefix == pathPrefix

    override fun hashCode(): Int = listOf(scheme, host, pathPrefix).hashCode()

    override fun toString(): String = "$scheme://${host ?: "*"}$pathPrefix"

    public companion object {
        /**
         * Builds a pattern from a full URL such as `https://example.com/signing/done`. Returns `null` for text that
         * is not a URL, a URL without a scheme, or an `http(s)` URL without a host.
         */
        @JvmStatic
        public fun parse(url: String): ReturnUrlPattern? {
            val parts = UrlParts.parse(url.trim()) ?: return null
            if (parts.scheme.isEmpty()) return null
            if (isWebScheme(parts.scheme) && parts.host.isEmpty()) return null
            return ReturnUrlPattern(parts.scheme, parts.host.ifEmpty { null }, parts.path)
        }

        internal fun isWebScheme(scheme: String): Boolean = scheme == "https" || scheme == "http"

        internal fun normalizePath(path: String): String {
            var value = path.trim()
            if (!value.startsWith("/")) value = "/$value"
            while (value.length > 1 && value.endsWith("/")) value = value.dropLast(1)
            return value
        }
    }
}

/**
 * The parts of a return URL that the signing page adds: `?session_id=sr_…&signer_id=sgr_…&status=completed|declined`.
 */
public class ReturnUrlMatch(
    /** The full URL the page navigated to. */
    public val url: String,
) {
    /** The `status` query parameter. */
    public sealed class Status {
        /** `status=completed`: the signer signed. */
        public data object Completed : Status()

        /** `status=declined`: the signer declined. */
        public data object Declined : Status()

        /**
         * No `status` parameter. The embedded signing page navigates to the plain `return_url` only after the
         * signer has signed.
         */
        public data object Unspecified : Status()

        /** Any other value. */
        public data class Other(public val value: String) : Status()
    }

    private val parts: UrlParts? = UrlParts.parse(url)

    /** The parsed `status`. */
    public val status: Status = when (val value = clean("status")?.lowercase()) {
        null -> Status.Unspecified
        "completed" -> Status.Completed
        "declined" -> Status.Declined
        else -> Status.Other(value)
    }

    /** `session_id` (the signature request id, `sr_…`). */
    public val signatureRequestId: String? = clean("session_id")

    /** `signer_id` (`sgr_…`). */
    public val signerId: String? = clean("signer_id")

    /** `true` when the query has the exact shape the signing page appends to a return URL. */
    internal val isPlatformReturnTarget: Boolean
        get() {
            val identifier = signatureRequestId ?: return false
            if (!identifier.startsWith("sr_")) return false
            return status == Status.Completed || status == Status.Declined
        }

    private fun clean(name: String): String? {
        val raw = parts?.queryValue(name) ?: return null
        val trimmed = raw.trim()
        return if (trimmed.isEmpty() || trimmed.length > 200) null else trimmed
    }

    override fun equals(other: Any?): Boolean = other is ReturnUrlMatch && other.url == url

    override fun hashCode(): Int = url.hashCode()

    override fun toString(): String =
        "ReturnUrlMatch(status=$status, signatureRequestId=$signatureRequestId, signerId=$signerId)"
}
