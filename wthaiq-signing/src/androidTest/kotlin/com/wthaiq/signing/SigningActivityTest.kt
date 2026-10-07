package com.wthaiq.signing

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.view.View
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SigningActivityTest {

    private val embed = "https://wthaiq.com/embed/sign/fixture1"
    private val signPage = "https://wthaiq.com/sign.php?token=t1"

    @Before
    fun setUp() {
        Fixtures.install()
    }

    @After
    fun tearDown() {
        Fixtures.uninstall()
    }

    @Test
    fun embeddedSignedEventFinishesWithSigned() {
        Fixtures.page(
            "wthaiq.com/embed/sign/fixture1",
            Fixtures.document("<h1>Embedded</h1>", Fixtures.post("viewed") + "setTimeout(function(){${Fixtures.post("signed")}},400);"),
        )
        val session = launchSigning(embed, fast())
        val result = session.awaitResult() as SigningResult.Signed
        assertEquals(SigningReference("sr_test", "sgr_test"), result.outcome.reference)
        assertEquals(SigningOutcome.Detection.PAGE_EVENT, result.outcome.detection)
        assertTrue(session.recorder.has<SigningEvent.Viewed>())
        assertTrue(session.recorder.has<SigningEvent.PageLoaded>())
        session.close()
    }

    @Test
    fun embeddedDeclinedEventKeepsReason() {
        Fixtures.page("wthaiq.com/embed/sign/fixture1", Fixtures.document("", Fixtures.post("declined", ",reason:'signer_declined'")))
        val session = launchSigning(embed, fast())
        val result = session.awaitResult() as SigningResult.Declined
        assertEquals("signer_declined", result.outcome.reason)
        session.close()
    }

    @Test
    fun expiredSessionStaysOpenUntilClosed() {
        Fixtures.page(
            "wthaiq.com/embed/sign/fixture1",
            Fixtures.document("<p>Expired</p>", "parent.postMessage({source:'wthaiq',event:'error',reason:'session_expired'},'*');"),
        )
        val session = launchSigning(embed, fast())
        waitUntil(message = "expired result") { session.currentResult() == SigningResult.Expired }
        Thread.sleep(400)
        assertEquals(Lifecycle.RESUMED_STATE, session.scenario.state.name)
        onView(withId(R.id.wthaiq_signing_close)).perform(click())
        assertEquals(SigningResult.Expired, session.awaitResult())
        session.close()
    }

    @Test
    fun signingPageCompletionPanelIsDetected() {
        Fixtures.page(
            "wthaiq.com/sign.php",
            Fixtures.document(
                "<div id='sign-ok' style='display:none'>Signed</div>",
                "setTimeout(function(){document.getElementById('sign-ok').style.display='block';},300);",
            ),
        )
        val session = launchSigning(signPage, fast())
        val result = session.awaitResult() as SigningResult.Signed
        assertEquals(SigningOutcome.Detection.PAGE_CONTENT, result.outcome.detection)
        session.close()
    }

    @Test
    fun statePagesMapToResults() {
        Fixtures.page(
            "wthaiq.com/sign.php",
            Fixtures.document("<div class='state-page'><div class='state-icon'><i class='fa-solid fa-file-circle-xmark'></i></div></div>"),
        )
        val session = launchSigning(signPage, fast())
        waitUntil(message = "link unavailable") {
            session.currentResult() == SigningResult.Failed(WthaiqSigningError.LinkUnavailable(SigningPageState.CANCELED))
        }
        session.close()
    }

    @Test
    fun nonFinalStatePageIsAnEvent() {
        Fixtures.page(
            "wthaiq.com/sign.php",
            Fixtures.document("<div class='state-page'><div class='state-icon'><i class='fa-solid fa-key'></i></div></div>"),
        )
        val session = launchSigning(signPage, fast())
        waitUntil(message = "page state event") {
            session.recorder.events.contains(SigningEvent.PageStateChanged(SigningPageState.ACCESS_CODE_REQUIRED))
        }
        assertNull(session.currentResult())
        session.close()
    }

    @Test
    fun configuredReturnUrlFinishesWithoutLoadingIt() {
        Fixtures.page(
            "wthaiq.com/sign.php",
            Fixtures.document(
                "",
                "setTimeout(function(){location.href='https://customer.example/done?session_id=sr_1&signer_id=sgr_1&status=declined';},200);",
            ),
        )
        val configuration = fast { addReturnUrl("https://customer.example/done") }
        val session = launchSigning(signPage, configuration)
        val result = session.awaitResult() as SigningResult.Declined
        assertEquals(SigningOutcome.Detection.RETURN_URL, result.outcome.detection)
        assertEquals(SigningReference("sr_1", "sgr_1"), result.outcome.reference)
        assertFalse(Fixtures.requested.any { it.startsWith("https://customer.example") })
        session.close()
    }

    @Test
    fun platformReturnNavigationIsDetectedWithoutConfiguration() {
        Fixtures.page(
            "wthaiq.com/sign.php",
            Fixtures.document("", "location.href='https://other.example/back?session_id=sr_2&signer_id=sgr_2&status=completed';"),
        )
        val session = launchSigning(signPage, fast())
        val result = session.awaitResult() as SigningResult.Signed
        assertEquals("sr_2", result.outcome.reference.signatureRequestId)
        assertFalse(Fixtures.requested.any { it.startsWith("https://other.example") })
        session.close()
    }

    @Test
    fun plainReturnUrlFromTheEmbedWrapperMeansSigned() {
        Fixtures.page(
            "wthaiq.com/embed/sign/fixture1",
            Fixtures.document("", "setTimeout(function(){window.top.location.href='https://customer.example/return';},200);"),
        )
        val session = launchSigning(embed, fast { addReturnUrl("https://customer.example/return") })
        val result = session.awaitResult() as SigningResult.Signed
        assertEquals("https://customer.example/return", result.outcome.returnUrl)
        session.close()
    }

    @Test
    fun innerFramePanelUsesIdentifiersFromTheWrapper() {
        Fixtures.page(
            "wthaiq.com/embed/sign/frame1",
            Fixtures.document("<iframe id='signFrame' src='/sign.php?token=inner&embed=1' style='width:100%;height:300px'></iframe>", Fixtures.post("viewed")),
        )
        Fixtures.page(
            "wthaiq.com/sign.php",
            Fixtures.document(
                "<div id='sign-ok' style='display:none'>Signed</div>",
                "setTimeout(function(){document.getElementById('sign-ok').style.display='block';},700);",
            ),
        )
        val session = launchSigning("https://wthaiq.com/embed/sign/frame1", fast())
        val result = session.awaitResult() as SigningResult.Signed
        assertEquals(SigningOutcome.Detection.PAGE_CONTENT, result.outcome.detection)
        assertEquals(SigningReference("sr_test", "sgr_test"), result.outcome.reference)
        session.close()
    }

    @Test
    fun messagesFromOtherOriginsAreIgnored() {
        Fixtures.page(
            "wthaiq.com/embed/sign/fixture1",
            Fixtures.document(
                "<iframe src='https://evil.example/frame'></iframe>",
                "setTimeout(function(){${Fixtures.post("declined")}},1500);",
            ),
        )
        Fixtures.page(
            "evil.example/frame",
            Fixtures.document("", "parent.postMessage({source:'wthaiq',event:'signed'},'*');"),
        )
        val session = launchSigning(embed, fast())
        val result = session.awaitResult()
        assertTrue("got $result", result is SigningResult.Declined)
        session.close()
    }

    @Test
    fun tappedExternalLinkOpensOutsideAndIsNotLoaded() {
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            Fixtures.page(
                "wthaiq.com/sign.php",
                Fixtures.document("<a id='terms' href='https://example.org/terms' style='display:block;padding:40px;font-size:24px'>Terms</a>"),
            )
            val session = launchSigning(signPage, fast())
            waitUntil(message = "page loaded") { session.recorder.has<SigningEvent.PageLoaded>() }
            session.tapElement("terms")
            waitUntil(message = "external open") { session.recorder.has<SigningEvent.ExternalUrlOpened>() }
            intended(allOf(hasAction(Intent.ACTION_VIEW), hasData("https://example.org/terms")))
            assertFalse(Fixtures.requested.any { it.startsWith("https://example.org") })
            assertTrue(session.currentUrl().startsWith("https://wthaiq.com/sign.php"))
            session.close()
        } finally {
            Intents.release()
        }
    }

    @Test
    fun newWindowOpensOutside() {
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_VIEW)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
            Fixtures.page(
                "wthaiq.com/sign.php",
                Fixtures.document(
                    "<button id='open' style='display:block;width:100%;height:120px'>Open</button>",
                    "document.getElementById('open').onclick=function(){window.open('https://example.org/privacy','_blank');};",
                ),
            )
            val session = launchSigning(signPage, fast())
            waitUntil(message = "page loaded") { session.recorder.has<SigningEvent.PageLoaded>() }
            session.tapElement("open")
            waitUntil(message = "external open") {
                session.recorder.events.contains(SigningEvent.ExternalUrlOpened("https://example.org/privacy"))
            }
            assertFalse(Fixtures.requested.any { it.startsWith("https://example.org") })
            session.close()
        } finally {
            Intents.release()
        }
    }

    @Test
    fun scriptedNavigationToAnUnlistedHostIsBlocked() {
        Fixtures.page(
            "wthaiq.com/sign.php",
            Fixtures.document("<p>Page</p>", "setTimeout(function(){location.href='https://unlisted.example/x';},300);"),
        )
        val configuration = fast {
            identityVerification = WthaiqSigningConfiguration.IdentityVerificationPolicy.LISTED_HOSTS_ONLY
        }
        val session = launchSigning(signPage, configuration)
        waitUntil(message = "blocked event") {
            session.recorder.events.contains(SigningEvent.NavigationBlocked("https://unlisted.example/x"))
        }
        assertFalse(Fixtures.requested.any { it.startsWith("https://unlisted.example") })
        assertTrue(session.currentUrl().startsWith("https://wthaiq.com/"))
        session.close()
    }

    @Test
    fun scriptedNavigationFromPlatformIsAdmittedAsIdentityVerification() {
        Fixtures.page("wthaiq.com/sign.php", Fixtures.document("<p>Page</p>", "location.href='https://verify.provider.example/s';"))
        Fixtures.page("verify.provider.example/s", Fixtures.document("<p>Identity verification</p>"))
        val session = launchSigning(signPage, fast())
        waitUntil(message = "identity event") {
            session.recorder.events.contains(SigningEvent.IdentityVerificationStarted("verify.provider.example"))
        }
        waitUntil(message = "provider page loaded") { Fixtures.requested.any { it.startsWith("https://verify.provider.example/s") } }
        session.close()
    }

    @Test
    fun backPressAsksBeforeCancelling() {
        Fixtures.page("wthaiq.com/embed/sign/fixture1", Fixtures.document("<h1>Document</h1>"))
        val session = launchSigning(embed, fast { language = WthaiqSigningConfiguration.Language.ENGLISH })
        waitUntil(message = "page loaded") { session.recorder.has<SigningEvent.PageLoaded>() }
        pressBack()
        onView(withText("Leave signing?")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText("Continue signing")).inRoot(isDialog()).perform(click())
        assertNull(session.currentResult())
        pressBack()
        onView(withText("Leave")).inRoot(isDialog()).perform(click())
        assertEquals(SigningResult.Canceled, session.awaitResult())
        session.close()
    }

    @Test
    fun arabicInterfaceIsRightToLeft() {
        Fixtures.page("wthaiq.com/embed/sign/fixture1", Fixtures.document("<h1>مستند</h1>"))
        val session = launchSigning(embed, fast { language = WthaiqSigningConfiguration.Language.ARABIC })
        waitUntil(message = "page loaded") { session.recorder.has<SigningEvent.PageLoaded>() }
        session.scenario.onActivity { activity ->
            assertEquals(View.LAYOUT_DIRECTION_RTL, activity.findViewById<View>(R.id.wthaiq_signing_root).layoutDirection)
            assertEquals("إغلاق", activity.findViewById<View>(R.id.wthaiq_signing_close).contentDescription)
        }
        assertTrue(Fixtures.requested.first().endsWith("lang=ar"))
        onView(withId(R.id.wthaiq_signing_close)).perform(click())
        onView(withText("خروج")).inRoot(isDialog()).perform(click())
        assertEquals(SigningResult.Canceled, session.awaitResult())
        session.close()
    }

    @Test
    fun serverErrorShowsRetryAndRecovers() {
        Fixtures.page("wthaiq.com/embed/sign/fixture1", Fixtures.document("<p>busy</p>"), status = 503)
        val session = launchSigning(embed, fast())
        waitUntil(message = "load failed event") {
            session.recorder.events.contains(SigningEvent.LoadFailed(WthaiqSigningError.HttpStatus(503)))
        }
        onView(withId(R.id.wthaiq_signing_retry)).check(matches(isDisplayed()))
        Fixtures.page("wthaiq.com/embed/sign/fixture1", Fixtures.document("", Fixtures.post("signed")))
        onView(withId(R.id.wthaiq_signing_retry)).perform(click())
        assertTrue(session.awaitResult() is SigningResult.Signed)
        session.close()
    }

    @Test
    fun closingTheErrorScreenReportsTheError() {
        Fixtures.page("wthaiq.com/embed/sign/fixture1", Fixtures.document("<p>busy</p>"), status = 500)
        val session = launchSigning(embed, fast())
        waitUntil(message = "error screen") { session.recorder.has<SigningEvent.LoadFailed>() }
        onView(withId(R.id.wthaiq_signing_error_close)).perform(click())
        assertEquals(SigningResult.Failed(WthaiqSigningError.HttpStatus(500)), session.awaitResult())
        session.close()
    }

    @Test
    fun invalidUrlFailsImmediately() {
        val session = launchSigning("https://evil.example/sign.php?token=x", fast())
        waitUntil(message = "invalid url result") { session.currentResult() != null }
        assertEquals(
            SigningResult.Failed(WthaiqSigningError.InvalidUrl(UrlValidationError.HostNotAllowed("evil.example"))),
            session.currentResult(),
        )
        assertTrue(Fixtures.requested.isEmpty())
        onView(withId(R.id.wthaiq_signing_close)).perform(click())
        assertTrue(session.awaitResult() is SigningResult.Failed)
        session.close()
    }

    @Test
    fun sessionSurvivesActivityRecreation() {
        Fixtures.page("wthaiq.com/embed/sign/fixture1", Fixtures.document("<h1>Document</h1>", Fixtures.post("viewed")))
        val session = launchSigning(embed, fast())
        waitUntil(message = "viewed") { session.recorder.has<SigningEvent.Viewed>() }
        session.scenario.recreate()
        waitUntil(message = "reloaded") { session.recorder.events.count { it is SigningEvent.PageLoaded } >= 2 }
        session.evaluate("window.postMessage({source:'wthaiq',event:'signed'},'*');")
        val result = session.awaitResult() as SigningResult.Signed
        assertEquals("sr_test", result.outcome.reference.signatureRequestId)
        session.close()
    }

    @Test
    fun resultIsKeptAcrossRecreationAfterFinishing() {
        Fixtures.page(
            "wthaiq.com/embed/sign/fixture1",
            Fixtures.document("", "parent.postMessage({source:'wthaiq',event:'error',reason:'session_expired'},'*');"),
        )
        val session = launchSigning(embed, fast())
        waitUntil(message = "expired") { session.currentResult() == SigningResult.Expired }
        session.scenario.recreate()
        assertEquals(SigningResult.Expired, session.currentResult())
        onView(withId(R.id.wthaiq_signing_close)).perform(click())
        assertEquals(SigningResult.Expired, session.awaitResult())
        session.close()
    }

    private fun fast(block: WthaiqSigningConfiguration.Builder.() -> Unit = {}): WthaiqSigningConfiguration =
        WthaiqSigningConfiguration {
            dismissal = WthaiqSigningConfiguration.Dismissal.Automatic(100)
            block()
        }

    private object Lifecycle {
        const val RESUMED_STATE = "RESUMED"
    }
}
