package com.wthaiq.signing

import com.wthaiq.signing.internal.MiniJson
import com.wthaiq.signing.internal.PageMessageDecoder
import com.wthaiq.signing.internal.PageSignal
import com.wthaiq.signing.internal.SigningFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageMessageTest {

    private val trusted: (String) -> Boolean = { it == "https://wthaiq.com" }

    private fun message(data: String, origin: String = "https://wthaiq.com") =
        PageMessageDecoder.decode("""{"kind":"message","origin":"$origin","data":$data}""", trusted)

    private val ids = """"signature_request":"sr_1","signer":"sgr_2""""

    @Test
    fun embeddedSignedEvent() {
        assertEquals(
            PageSignal.Signed(SigningReference("sr_1", "sgr_2"), SigningOutcome.Detection.PAGE_EVENT),
            message("""{"source":"wthaiq","event":"signed","type":"wthaiq:signed",$ids}"""),
        )
        assertTrue(message("""{"source":"wthaiq","event":"completed"}""") is PageSignal.Signed)
    }

    @Test
    fun embeddedDeclinedEventKeepsReason() {
        assertEquals(
            PageSignal.Declined(SigningReference("sr_1", "sgr_2"), "signer_declined", SigningOutcome.Detection.PAGE_EVENT),
            message("""{"source":"wthaiq","event":"declined",$ids,"reason":"signer_declined"}"""),
        )
    }

    @Test
    fun embeddedErrorAndViewed() {
        assertEquals(PageSignal.Failed("session_expired"), message("""{"source":"wthaiq","event":"error","reason":"session_expired"}"""))
        assertEquals(PageSignal.Failed("unknown"), message("""{"source":"wthaiq","event":"error"}"""))
        assertEquals(PageSignal.Viewed(SigningReference("sr_1", "sgr_2")), message("""{"source":"wthaiq","event":"viewed",$ids}"""))
    }

    @Test
    fun eventNameFallsBackToType() {
        assertTrue(message("""{"source":"wthaiq","type":"wthaiq:SIGNED"}""") is PageSignal.Signed)
        assertNull(message("""{"source":"wthaiq","type":"other:signed"}"""))
    }

    @Test
    fun innerStateMessage() {
        assertEquals(
            PageSignal.State(SigningPageState.NOT_YOUR_TURN, SigningReference("sr_1", null), SigningOutcome.Detection.PAGE_EVENT),
            message("""{"source":"wthaiq-inner","event":"state","state":"NOT_YOUR_TURN","signature_request":"sr_1","signer":""}"""),
        )
        assertNull(message("""{"source":"wthaiq-inner","event":"state","state":"bogus"}"""))
    }

    @Test
    fun unknownEventIsForwarded() {
        assertEquals(
            PageSignal.Other("progress", mapOf("event" to "progress", "step" to "2", "done" to "true")),
            message("""{"source":"wthaiq","event":"progress","step":2,"done":true,"nested":{"a":1},"list":[1]}"""),
        )
    }

    @Test
    fun untrustedOriginOrSourceIsIgnored() {
        assertNull(message("""{"source":"wthaiq","event":"signed"}""", origin = "https://evil.com"))
        assertNull(message("""{"source":"other","event":"signed"}"""))
        assertNull(message("""{"event":"signed"}"""))
    }

    @Test
    fun malformedBodiesAreIgnored() {
        listOf(
            "",
            "null",
            "[]",
            "{",
            """{"kind":"message"}""",
            """{"kind":"message","origin":"https://wthaiq.com","data":"x"}""",
            """{"kind":"other","event":"signed"}""",
            """{"kind":"dom"}""",
            """{"kind":"dom","event":"weird"}""",
            """{"kind":"message","origin":"https://wthaiq.com","data":{"source":"wthaiq","event":""}}""",
        ).forEach { assertNull(it, PageMessageDecoder.decode(it, trusted)) }
        val longName = "x".repeat(65)
        assertNull(message("""{"source":"wthaiq","event":"$longName"}"""))
    }

    @Test
    fun overlongIdentifiersAreDropped() {
        val long = "sr_" + "a".repeat(250)
        val signal = message("""{"source":"wthaiq","event":"signed","signature_request":"$long","signer":"  sgr_2  "}""")
        assertEquals(PageSignal.Signed(SigningReference(null, "sgr_2"), SigningOutcome.Detection.PAGE_EVENT), signal)
    }

    @Test
    fun pageContentSignals() {
        val dom = { body: String -> PageMessageDecoder.decode(body, trusted) }
        assertEquals(PageSignal.Signed(SigningReference.UNKNOWN, SigningOutcome.Detection.PAGE_CONTENT), dom("""{"kind":"dom","event":"signed"}"""))
        assertEquals(
            PageSignal.Declined(SigningReference.UNKNOWN, null, SigningOutcome.Detection.PAGE_CONTENT),
            dom("""{"kind":"dom","event":"declined"}"""),
        )
        assertEquals(
            PageSignal.State(SigningPageState.EXPIRED, SigningReference.UNKNOWN, SigningOutcome.Detection.PAGE_CONTENT),
            dom("""{"kind":"dom","event":"state","state":"expired"}"""),
        )
        assertNull(dom("""{"kind":"dom","event":"state"}"""))
    }

    @Test
    fun everyPageStateNameDecodes() {
        SigningPageState.entries.forEach { state ->
            val signal = PageMessageDecoder.decodeContent("state", state.value)
            assertEquals(state, (signal as PageSignal.State).state)
        }
    }

    @Test
    fun jsonReader() {
        assertEquals(mapOf("a" to "x\"y\nA", "b" to listOf(1.0, true, null)), MiniJson.parse("""{"a":"x\"y\nA","b":[1,true,null]}"""))
        assertEquals(-12.5e2, MiniJson.parse("-12.5e2"))
        assertNull(MiniJson.parse("""{"a":01}"""))
        assertNull(MiniJson.parse("""{"a":1,}"""))
        assertNull(MiniJson.parse("""{"a":"unterminated}"""))
        assertNull(MiniJson.parse("""{"a":1} trailing"""))
        assertNull(MiniJson.parse("[".repeat(20) + "]".repeat(20)))
        assertNull(MiniJson.parse("\"" + "a".repeat(MiniJson.MAX_LENGTH) + "\""))
        assertNull(MiniJson.parse("tru"))
    }

    @Test
    fun signedFinishesOnceAndIgnoresLaterSignals() {
        val flow = SigningFlow()
        val first = flow.apply(PageSignal.Signed(SigningReference("sr_1"), SigningOutcome.Detection.PAGE_EVENT))
        assertTrue(first is SigningFlow.Effect.Finish)
        assertEquals(SigningFlow.Effect.Ignored, flow.apply(PageSignal.Declined(SigningReference.UNKNOWN, null, SigningOutcome.Detection.PAGE_EVENT)))
        assertEquals(SigningFlow.Effect.Ignored, flow.cancel(null))
        assertEquals(SigningFlow.Effect.Ignored, flow.applyReturn(ReturnUrlMatch("https://e.com/?status=declined")))
        assertTrue(flow.isFinished)
        assertTrue(flow.result is SigningResult.Signed)
    }

    @Test
    fun contentOutcomeUsesIdentifiersFromEarlierEvents() {
        val flow = SigningFlow()
        assertEquals(
            SigningFlow.Effect.Event(SigningEvent.Viewed(SigningReference("sr_1", "sgr_2"))),
            flow.apply(PageSignal.Viewed(SigningReference("sr_1", "sgr_2"))),
        )
        val effect = flow.apply(PageSignal.Signed(SigningReference.UNKNOWN, SigningOutcome.Detection.PAGE_CONTENT))
        val result = (effect as SigningFlow.Effect.Finish).result as SigningResult.Signed
        assertEquals(SigningReference("sr_1", "sgr_2"), result.outcome.reference)
        assertEquals(SigningOutcome.Detection.PAGE_CONTENT, result.outcome.detection)
    }

    @Test
    fun declinedKeepsReason() {
        val effect = SigningFlow().apply(PageSignal.Declined(SigningReference.UNKNOWN, "signer_declined", SigningOutcome.Detection.PAGE_EVENT))
        val result = (effect as SigningFlow.Effect.Finish).result as SigningResult.Declined
        assertEquals("signer_declined", result.outcome.reason)
    }

    @Test
    fun viewedAndUnknownMessagesAreEvents() {
        val flow = SigningFlow()
        assertEquals(
            SigningFlow.Effect.Event(SigningEvent.PageMessage("x", mapOf("a" to "b"))),
            flow.apply(PageSignal.Other("x", mapOf("a" to "b"))),
        )
        assertTrue(!flow.isFinished)
    }

    @Test
    fun errorReasons() {
        assertEquals(SigningResult.Expired, (SigningFlow().apply(PageSignal.Failed("session_expired")) as SigningFlow.Effect.Finish).result)
        assertEquals(
            SigningResult.Failed(WthaiqSigningError.PageError("boom")),
            (SigningFlow().apply(PageSignal.Failed("boom")) as SigningFlow.Effect.Finish).result,
        )
    }

    @Test
    fun finalPageStates() {
        fun finalFor(state: SigningPageState): SigningResult =
            (SigningFlow().apply(PageSignal.State(state, SigningReference.UNKNOWN, SigningOutcome.Detection.PAGE_CONTENT)) as SigningFlow.Effect.Finish).result
        assertTrue((finalFor(SigningPageState.ALREADY_SIGNED) as SigningResult.Signed).outcome.wasAlreadyFinal)
        assertTrue((finalFor(SigningPageState.ALREADY_DECLINED) as SigningResult.Declined).outcome.wasAlreadyFinal)
        assertEquals(SigningResult.Expired, finalFor(SigningPageState.EXPIRED))
        listOf(SigningPageState.CANCELED, SigningPageState.NOT_FOUND, SigningPageState.MISSING_TOKEN).forEach {
            assertEquals(SigningResult.Failed(WthaiqSigningError.LinkUnavailable(it)), finalFor(it))
        }
    }

    @Test
    fun nonFinalPageStatesAreReportedOncePerChange() {
        val flow = SigningFlow()
        fun state(value: SigningPageState) = flow.apply(PageSignal.State(value, SigningReference.UNKNOWN, SigningOutcome.Detection.PAGE_CONTENT))
        assertEquals(SigningFlow.Effect.Event(SigningEvent.PageStateChanged(SigningPageState.ACCESS_CODE_REQUIRED)), state(SigningPageState.ACCESS_CODE_REQUIRED))
        assertEquals(SigningFlow.Effect.Ignored, state(SigningPageState.ACCESS_CODE_REQUIRED))
        assertEquals(SigningFlow.Effect.Event(SigningEvent.PageStateChanged(SigningPageState.NOT_YOUR_TURN)), state(SigningPageState.NOT_YOUR_TURN))
        assertEquals(SigningPageState.NOT_YOUR_TURN, flow.pageState)
        assertTrue(!flow.isFinished)
    }

    @Test
    fun returnMatches() {
        fun result(url: String) = (SigningFlow().applyReturn(ReturnUrlMatch(url)) as SigningFlow.Effect.Finish).result
        val signed = result("https://e.com/done?session_id=sr_1&signer_id=sgr_2&status=completed") as SigningResult.Signed
        assertEquals(SigningOutcome.Detection.RETURN_URL, signed.outcome.detection)
        assertEquals("https://e.com/done?session_id=sr_1&signer_id=sgr_2&status=completed", signed.outcome.returnUrl)
        assertTrue(result("https://e.com/done") is SigningResult.Signed)
        assertTrue(result("https://e.com/done?status=declined") is SigningResult.Declined)
        assertEquals(SigningResult.Failed(WthaiqSigningError.UnexpectedReturnStatus("odd")), result("https://e.com/done?status=odd"))
    }

    @Test
    fun cancel() {
        assertEquals(SigningResult.Canceled, (SigningFlow().cancel(null) as SigningFlow.Effect.Finish).result)
        assertEquals(
            SigningResult.Failed(WthaiqSigningError.TimedOut),
            (SigningFlow().cancel(WthaiqSigningError.TimedOut) as SigningFlow.Effect.Finish).result,
        )
        val restored = SigningFlow(result = SigningResult.Expired)
        assertEquals(SigningFlow.Effect.Ignored, restored.cancel(null))
    }
}
