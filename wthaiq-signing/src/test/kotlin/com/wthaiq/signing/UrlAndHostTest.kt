package com.wthaiq.signing

import com.wthaiq.signing.internal.HostMatcher
import com.wthaiq.signing.internal.UrlParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlAndHostTest {

    @Test
    fun splitsAFullUrl() {
        val parts = assertNotNullValue(UrlParts.parse("HTTPS://User:Pw@WWW.Wthaiq.COM.:8443/en/sign.php?token=a%20b&x#frag"))
        assertEquals("https", parts.scheme)
        assertEquals("User:Pw", parts.userInfo)
        assertEquals("www.wthaiq.com", parts.host)
        assertEquals(8443, parts.port)
        assertEquals("/en/sign.php", parts.path)
        assertEquals("a b", parts.queryValue("token"))
        assertTrue(parts.hasQueryItem("x"))
        assertNull(parts.queryValue("x"))
        assertEquals("frag", parts.rawFragment)
        assertEquals("https://www.wthaiq.com:8443", parts.origin)
    }

    @Test
    fun keepsPlusAndDecodesUtf8() {
        val parts = assertNotNullValue(UrlParts.parse("https://a.com/p?q=a+b&ar=%D8%AA%D9%88%D9%82%D9%8A%D8%B9&bad=%ZZ%"))
        assertEquals("a+b", parts.queryValue("q"))
        assertEquals("توقيع", parts.queryValue("ar"))
        assertEquals("%ZZ%", parts.queryValue("bad"))
    }

    @Test
    fun parsesIpv6AndCustomSchemes() {
        val ipv6 = assertNotNullValue(UrlParts.parse("http://[::1]:8080/x"))
        assertEquals("::1", ipv6.host)
        assertEquals("http://[::1]:8080", ipv6.origin)
        val custom = assertNotNullValue(UrlParts.parse("myapp://signing/done?status=completed"))
        assertEquals("myapp", custom.scheme)
        assertEquals("signing", custom.host)
        assertEquals("/done", custom.path)
        val opaque = assertNotNullValue(UrlParts.parse("mailto:support@wthaiq.com"))
        assertEquals("mailto", opaque.scheme)
        assertFalse(opaque.hasAuthority)
    }

    @Test
    fun refusesTextThatIsNotAUrl() {
        assertNull(UrlParts.parse(null))
        assertNull(UrlParts.parse(""))
        assertNull(UrlParts.parse("https://wthaiq.com/a b"))
        assertNull(UrlParts.parse("https://evil.com\\@wthaiq.com/"))
        assertNull(UrlParts.parse("https://wthaiq.com:99999/"))
        assertNull(UrlParts.parse("https://wthaiq.com:12ab/"))
        assertNull(UrlParts.parse("1https://x"))
    }

    @Test
    fun encodesLikeEncodeUriComponent() {
        assertEquals("a%20b%2Bc%26d%3D%D8%AA", UrlParts.percentEncode("a b+c&d=ت"))
        assertEquals("-_.!~*'()", UrlParts.percentEncode("-_.!~*'()"))
    }

    @Test
    fun normalizeLowercasesTrimsAndDropsTrailingDots() {
        assertEquals("wthaiq.com", HostMatcher.normalize("  WTHAIQ.com.. "))
        assertEquals("::1", HostMatcher.normalize("[::1]"))
        assertEquals("", HostMatcher.normalize("   "))
    }

    @Test
    fun exactMatch() {
        assertTrue(HostMatcher.matches("WTHAIQ.COM", setOf("wthaiq.com")))
        assertFalse(HostMatcher.matches("evilwthaiq.com", setOf("wthaiq.com")))
        assertFalse(HostMatcher.matches("wthaiq.com.evil.com", setOf("wthaiq.com")))
        assertFalse(HostMatcher.matches("", setOf("wthaiq.com")))
    }

    @Test
    fun wildcardMatchesSubdomainsButNotApex() {
        val patterns = setOf("*.example.com")
        assertTrue(HostMatcher.matches("sign.example.com", patterns))
        assertTrue(HostMatcher.matches("a.b.example.com", patterns))
        assertFalse(HostMatcher.matches("example.com", patterns))
        assertFalse(HostMatcher.matches("badexample.com", patterns))
    }

    @Test
    fun degenerateWildcardMatchesNothing() {
        assertFalse(HostMatcher.matches("com", setOf("*.")))
        assertFalse(HostMatcher.matches("a.com", setOf("*")))
        assertFalse(HostMatcher.matches("a.com", setOf("")))
    }

    @Test
    fun localDevelopmentHosts() {
        listOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2", "app.localhost", "wthaiq.test").forEach {
            assertTrue(it, HostMatcher.isLocalDevelopmentHost(it))
        }
        listOf("wthaiq.com", "localhost.com", "10.0.2.3", "test").forEach {
            assertFalse(it, HostMatcher.isLocalDevelopmentHost(it))
        }
    }

    private fun <T> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
