package com.wthaiq.signing

import com.wthaiq.signing.internal.LoadFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnUrlAndResultTest {

    @Test
    fun patternFromUrl() {
        val pattern = ReturnUrlPattern.parse("HTTPS://Example.com/Signing/Done/?x=1#y")!!
        assertEquals("https", pattern.scheme)
        assertEquals("example.com", pattern.host)
        assertEquals("/Signing/Done", pattern.pathPrefix)
        assertEquals("https://example.com/Signing/Done", pattern.toString())
    }

    @Test
    fun patternMatchesPathPrefixOnSegmentBoundary() {
        val pattern = ReturnUrlPattern.parse("https://example.com/signing/done")!!
        assertTrue(pattern.matches("https://example.com/signing/done"))
        assertTrue(pattern.matches("https://EXAMPLE.com:443/signing/done/?status=completed"))
        assertTrue(pattern.matches("https://example.com/signing/done/step"))
        assertFalse(pattern.matches("https://example.com/signing/donex"))
        assertFalse(pattern.matches("https://example.com/signing"))
        assertFalse(pattern.matches("http://example.com/signing/done"))
        assertFalse(pattern.matches("https://other.com/signing/done"))
    }

    @Test
    fun rootPatternMatchesAnyPathOnHost() {
        val pattern = ReturnUrlPattern.parse("https://example.com")!!
        assertEquals("/", pattern.pathPrefix)
        assertTrue(pattern.matches("https://example.com/anything?x"))
        assertFalse(pattern.matches("https://sub.example.com/"))
    }

    @Test
    fun customSchemeWithoutHostMatchesAnyHost() {
        val pattern = ReturnUrlPattern("MyApp")
        assertTrue(pattern.matches("myapp://signing/done"))
        assertTrue(pattern.matches("myapp:done"))
        assertFalse(pattern.matches("otherapp://signing/done"))
        val withHost = ReturnUrlPattern.parse("myapp://signing/done")!!
        assertTrue(withHost.matches("myapp://signing/done?status=declined"))
        assertFalse(withHost.matches("myapp://other/done"))
    }

    @Test
    fun webPatternWithoutHostMatchesNothing() {
        assertNull(ReturnUrlPattern.parse("https:///done"))
        assertNull(ReturnUrlPattern.parse("/done"))
        assertNull(ReturnUrlPattern.parse("not a url"))
        assertFalse(ReturnUrlPattern("https").matches("https://example.com/"))
    }

    @Test
    fun configurationAddReturnUrl() {
        val builder = WthaiqSigningConfiguration.Builder()
        assertTrue(builder.addReturnUrl("https://example.com/done"))
        assertTrue(builder.addReturnUrl("https://example.com/done"))
        assertFalse(builder.addReturnUrl("https:///done"))
        assertEquals(1, builder.build().returnUrls.size)
    }

    @Test
    fun matchParsesPlatformQuery() {
        val match = ReturnUrlMatch("https://example.com/done?session_id=sr_123&signer_id=sgr_9&status=completed")
        assertEquals(ReturnUrlMatch.Status.Completed, match.status)
        assertEquals("sr_123", match.signatureRequestId)
        assertEquals("sgr_9", match.signerId)
        assertTrue(match.isPlatformReturnTarget)
    }

    @Test
    fun matchStatuses() {
        assertEquals(ReturnUrlMatch.Status.Declined, ReturnUrlMatch("https://e.com/?status=DECLINED").status)
        assertEquals(ReturnUrlMatch.Status.Unspecified, ReturnUrlMatch("https://e.com/").status)
        assertEquals(ReturnUrlMatch.Status.Unspecified, ReturnUrlMatch("https://e.com/?status=").status)
        assertEquals(ReturnUrlMatch.Status.Other("weird"), ReturnUrlMatch("https://e.com/?status=weird").status)
    }

    @Test
    fun platformReturnTargetNeedsRequestIdAndKnownStatus() {
        assertFalse(ReturnUrlMatch("https://e.com/?session_id=sr_1").isPlatformReturnTarget)
        assertFalse(ReturnUrlMatch("https://e.com/?session_id=x_1&status=completed").isPlatformReturnTarget)
        assertFalse(ReturnUrlMatch("https://e.com/?session_id=sr_1&status=other").isPlatformReturnTarget)
        assertTrue(ReturnUrlMatch("https://e.com/?session_id=sr_1&status=declined").isPlatformReturnTarget)
    }

    @Test
    fun overlongValuesAreIgnored() {
        val long = "sr_" + "a".repeat(250)
        assertNull(ReturnUrlMatch("https://e.com/?session_id=$long&status=completed").signatureRequestId)
    }

    @Test
    fun resultFromReturnUrl() {
        val signed = SigningResult.fromReturnUrl("https://e.com/?session_id=sr_1&signer_id=sgr_2&status=completed")
        assertTrue(signed is SigningResult.Signed)
        val outcome = signed!!.outcome!!
        assertEquals(SigningReference("sr_1", "sgr_2"), outcome.reference)
        assertEquals(SigningOutcome.Detection.RETURN_URL, outcome.detection)
        assertTrue(SigningResult.fromReturnUrl("https://e.com/?status=declined") is SigningResult.Declined)
        assertNull(SigningResult.fromReturnUrl("https://e.com/"))
        assertNull(SigningResult.fromReturnUrl("https://e.com/?status=x"))
    }

    @Test
    fun accessors() {
        val outcome = SigningOutcome(detection = SigningOutcome.Detection.PAGE_EVENT)
        assertTrue(SigningResult.Signed(outcome).isSigned)
        assertFalse(SigningResult.Declined(outcome).isSigned)
        assertEquals(outcome, SigningResult.Declined(outcome).outcome)
        assertNull(SigningResult.Canceled.outcome)
        assertNull(SigningResult.Expired.outcome)
        assertTrue(SigningReference.UNKNOWN.isEmpty)
        assertFalse(SigningReference("sr_1").isEmpty)
        assertEquals("page_content", SigningOutcome.Detection.PAGE_CONTENT.value)
    }

    @Test
    fun errorProperties() {
        assertTrue(WthaiqSigningError.Network(-6, "net::ERR_CONNECTION_REFUSED").isRetryable)
        assertTrue(WthaiqSigningError.TimedOut.isRetryable)
        assertTrue(WthaiqSigningError.HttpStatus(503).isRetryable)
        assertTrue(WthaiqSigningError.WebContentTerminated.isRetryable)
        assertFalse(WthaiqSigningError.InvalidUrl(UrlValidationError.MissingUrl).isRetryable)
        assertFalse(WthaiqSigningError.LinkUnavailable(SigningPageState.CANCELED).isRetryable)
        assertFalse(WthaiqSigningError.PageError("x").isRetryable)
        assertFalse(WthaiqSigningError.UnexpectedReturnStatus("x").isRetryable)

        assertTrue(WthaiqSigningError.Network(-2, "net::ERR_NAME_NOT_RESOLVED").isOffline)
        assertTrue(WthaiqSigningError.Network(-1, "net::ERR_INTERNET_DISCONNECTED").isOffline)
        assertFalse(WthaiqSigningError.Network(-6, "net::ERR_CONNECTION_REFUSED").isOffline)
        assertFalse(WthaiqSigningError.TimedOut.isOffline)
        assertTrue(WthaiqSigningError.HttpStatus(500).description.contains("500"))
    }

    @Test
    fun pageStateFinality() {
        val nonFinal = setOf(
            SigningPageState.NOT_YOUR_TURN,
            SigningPageState.ACCESS_CODE_REQUIRED,
            SigningPageState.IDENTITY_UNAVAILABLE,
        )
        SigningPageState.entries.forEach { assertEquals(it.name, it !in nonFinal, it.isFinal) }
        assertEquals(SigningPageState.ACCESS_CODE_REQUIRED, SigningPageState.fromValue("access_code"))
        assertNull(SigningPageState.fromValue("unknown"))
    }

    @Test
    fun canceledAndInterruptedLoadsAreNotFailures() {
        assertNull(LoadFailure.classify(-1, "net::ERR_ABORTED"))
    }

    @Test
    fun timeoutsAndNetworkErrors() {
        assertEquals(WthaiqSigningError.TimedOut, LoadFailure.classify(-8, "net::ERR_TIMED_OUT"))
        assertEquals(WthaiqSigningError.TimedOut, LoadFailure.classify(-1, "net::ERR_TIMED_OUT"))
        assertEquals(WthaiqSigningError.Network(-2, "net::ERR_NAME_NOT_RESOLVED"), LoadFailure.classify(-2, "net::ERR_NAME_NOT_RESOLVED"))
        assertEquals(WthaiqSigningError.Network(-6, "Error -6"), LoadFailure.classify(-6, null))
    }

    @Test
    fun blockingStatuses() {
        assertTrue(LoadFailure.isBlockingStatus(429))
        assertTrue(LoadFailure.isBlockingStatus(500))
        assertTrue(LoadFailure.isBlockingStatus(503))
        assertFalse(LoadFailure.isBlockingStatus(404))
        assertFalse(LoadFailure.isBlockingStatus(403))
        assertFalse(LoadFailure.isBlockingStatus(200))
    }
}
