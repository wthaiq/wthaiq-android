package com.wthaiq.signing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SigningLinkTest {

    private fun reason(url: String?, configuration: WthaiqSigningConfiguration = WthaiqSigningConfiguration.DEFAULT) =
        (SigningLink.check(url, configuration) as SigningLink.Check.Invalid).reason

    @Test
    fun signingPageUrlIsValid() {
        val link = SigningLink.validate("https://wthaiq.com/sign.php?token=abc123")
        assertEquals(SigningLink.Kind.SIGNING_PAGE, link.kind)
        assertEquals("wthaiq.com", link.host)
        assertEquals("https://wthaiq.com/sign.php?token=abc123", link.url)
    }

    @Test
    fun embeddedSessionUrlIsValid() {
        val link = SigningLink.validate("https://www.wthaiq.com/embed/sign/0a1B2c3D/")
        assertEquals(SigningLink.Kind.EMBEDDED_SESSION, link.kind)
    }

    @Test
    fun englishPrefixIsAccepted() {
        assertEquals(SigningLink.Kind.SIGNING_PAGE, SigningLink.validate("https://wthaiq.com/en/sign.php?token=x").kind)
        assertEquals(SigningLink.Kind.EMBEDDED_SESSION, SigningLink.validate("https://wthaiq.com/EN/embed/sign/abc").kind)
    }

    @Test
    fun otherPathOnAllowedHostIsAccepted() {
        assertEquals(SigningLink.Kind.OTHER, SigningLink.validate("https://wthaiq.com/verify").kind)
        assertEquals(SigningLink.Kind.OTHER.value, "other")
    }

    @Test
    fun plainHttpIsRefused() {
        assertEquals(UrlValidationError.UnsupportedScheme("http"), reason("http://wthaiq.com/sign.php?token=x"))
    }

    @Test
    fun otherSchemesAreRefused() {
        assertEquals(UrlValidationError.UnsupportedScheme("ftp"), reason("ftp://wthaiq.com/sign.php?token=x"))
        assertEquals(UrlValidationError.UnsupportedScheme("javascript"), reason("javascript:alert(1)"))
        assertEquals(UrlValidationError.UnsupportedScheme(""), reason("/sign.php?token=x"))
    }

    @Test
    fun missingAndMalformedUrls() {
        assertEquals(UrlValidationError.MissingUrl, reason(null))
        assertEquals(UrlValidationError.MissingUrl, reason("  "))
        assertEquals(UrlValidationError.Malformed, reason("https://wthaiq.com/sign.php?token=a b"))
        assertEquals(UrlValidationError.MissingHost, reason("https:///sign.php?token=x"))
    }

    @Test
    fun unknownHostIsRefused() {
        assertEquals(UrlValidationError.HostNotAllowed("evil.com"), reason("https://evil.com/sign.php?token=x"))
        assertEquals(UrlValidationError.HostNotAllowed("wthaiq.com.evil.com"), reason("https://wthaiq.com.evil.com/embed/sign/a"))
    }

    @Test
    fun customDomainNeedsToBeAllowed() {
        val url = "https://sign.example.com/sign.php?token=x"
        assertFalse(SigningLink.isValid(url))
        val configuration = WthaiqSigningConfiguration { addAllowedHost("Sign.Example.com") }
        assertTrue(SigningLink.isValid(url, configuration))
        val wildcard = WthaiqSigningConfiguration { addAllowedHost("*.example.com") }
        assertTrue(SigningLink.isValid(url, wildcard))
        assertFalse(SigningLink.isValid("https://example.com/sign.php?token=x", wildcard))
    }

    @Test
    fun credentialsAreRefused() {
        assertEquals(UrlValidationError.ContainsCredentials, reason("https://user@wthaiq.com/sign.php?token=x"))
        assertEquals(UrlValidationError.ContainsCredentials, reason("https://evil.com@wthaiq.com/sign.php?token=x"))
    }

    @Test
    fun missingTokenIsRefused() {
        assertEquals(UrlValidationError.MissingToken, reason("https://wthaiq.com/sign.php"))
        assertEquals(UrlValidationError.MissingToken, reason("https://wthaiq.com/sign.php?token=%20"))
        assertEquals(UrlValidationError.MissingToken, reason("https://wthaiq.com/embed/sign/"))
        assertEquals(UrlValidationError.MissingToken, reason("https://wthaiq.com/embed/sign/abc-def"))
    }

    @Test
    fun localDevelopmentNeedsTheFlag() {
        val url = "http://10.0.2.2:8782/sign.php?token=x"
        assertEquals(UrlValidationError.UnsupportedScheme("http"), reason(url))
        val dev = WthaiqSigningConfiguration { allowsInsecureLocalHosts = true }
        assertTrue(SigningLink.isValid(url, dev))
        assertTrue(SigningLink.isValid("http://localhost/embed/sign/abc", dev))
        assertEquals(UrlValidationError.UnsupportedScheme("http"), reason("http://wthaiq.com/sign.php?token=x", dev))
    }

    @Test
    fun languageParameterIsAdded() {
        val link = SigningLink.validate("https://wthaiq.com/sign.php?token=x#top")
        assertEquals("https://wthaiq.com/sign.php?token=x&lang=ar#top", link.urlFor(WthaiqSigningConfiguration.Language.ARABIC))
        assertEquals("https://wthaiq.com/sign.php?token=x&lang=en#top", link.urlFor(WthaiqSigningConfiguration.Language.ENGLISH))
        assertEquals(link.url, link.urlFor(WthaiqSigningConfiguration.Language.AUTOMATIC))
    }

    @Test
    fun languageParameterIsAddedToPathOnlyUrl() {
        val link = SigningLink.validate("https://wthaiq.com/embed/sign/abc")
        assertEquals("https://wthaiq.com/embed/sign/abc?lang=en", link.urlFor(WthaiqSigningConfiguration.Language.ENGLISH))
    }

    @Test
    fun existingLanguageParameterIsKept() {
        val link = SigningLink.validate("https://wthaiq.com/embed/sign/abc?lang=ar")
        assertEquals(link.url, link.urlFor(WthaiqSigningConfiguration.Language.ENGLISH))
    }

    @Test
    fun validateThrowsWithTheReason() {
        try {
            SigningLink.validate("https://evil.com/sign.php?token=x")
            fail("expected an exception")
        } catch (error: InvalidSigningUrlException) {
            assertEquals(UrlValidationError.HostNotAllowed("evil.com"), error.reason)
            assertTrue(error.message!!.contains("allowedHosts"))
        }
    }

    @Test
    fun errorsHaveDescriptions() {
        listOf(
            UrlValidationError.MissingUrl,
            UrlValidationError.Malformed,
            UrlValidationError.UnsupportedScheme("http"),
            UrlValidationError.MissingHost,
            UrlValidationError.HostNotAllowed("x"),
            UrlValidationError.ContainsCredentials,
            UrlValidationError.MissingToken,
        ).forEach { assertTrue(it.description.isNotBlank()) }
    }
}
