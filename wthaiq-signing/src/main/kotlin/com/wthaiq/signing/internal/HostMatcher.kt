package com.wthaiq.signing.internal

/** Host comparison rules shared by URL validation, the navigation policy and the page message gate. */
internal object HostMatcher {
    /** Lowercases, trims whitespace, removes IPv6 brackets and trailing dots. */
    fun normalize(host: String): String {
        var value = host.trim().lowercase()
        if (value.length >= 2 && value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length - 1)
        }
        while (value.endsWith(".")) {
            value = value.dropLast(1)
        }
        return value
    }

    /**
     * Returns `true` when [host] equals one of [patterns], or is a subdomain of a `*.example.com` pattern.
     * A wildcard pattern never matches its apex domain.
     */
    fun matches(host: String, patterns: Collection<String>): Boolean {
        val candidate = normalize(host)
        if (candidate.isEmpty()) return false
        for (raw in patterns) {
            val pattern = normalize(raw)
            if (pattern.startsWith("*.")) {
                val suffix = pattern.substring(1)
                if (suffix.length > 1 && candidate.length > suffix.length && candidate.endsWith(suffix)) {
                    return true
                }
            } else if (pattern.isNotEmpty() && pattern == candidate) {
                return true
            }
        }
        return false
    }

    /**
     * Hosts that only exist on a developer machine. Plain `http` is accepted for them when
     * `allowsInsecureLocalHosts` is enabled. `10.0.2.2` is the Android emulator's address for the computer
     * running it (an Android-only addition to the iOS list).
     */
    fun isLocalDevelopmentHost(host: String): Boolean {
        val value = normalize(host)
        if (value == "localhost" || value == "127.0.0.1" || value == "::1" || value == "10.0.2.2") {
            return true
        }
        return value.endsWith(".localhost") || value.endsWith(".test")
    }
}
