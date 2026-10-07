package com.wthaiq.signing

import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Optional checks against a local development server with real signing pages (test mode). Skipped unless the
 * instrumentation arguments are given, for example:
 *
 * ```
 * ./gradlew :wthaiq-signing:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.wthaiq.signing.LocalServerEndToEndTest \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eBase=http://10.0.2.2:8782 \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eEmbedToken=<48 hex> \
 *   -Pandroid.testInstrumentationRunnerArguments.e2eSignToken=<signing token>
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LocalServerEndToEndTest {

    private val arguments = InstrumentationRegistry.getArguments()
    private val base: String? = arguments.getString("e2eBase")

    private val configuration = WthaiqSigningConfiguration {
        language = WthaiqSigningConfiguration.Language.ENGLISH
        allowsInsecureLocalHosts = true
    }

    @Test
    fun embeddedSessionReportsViewedWithRealIdentifiers() {
        val token = arguments.getString("e2eEmbedToken")
        assumeTrue("local server arguments not given", base != null && token != null)
        val session = launchSigning("$base/embed/sign/$token", configuration)
        waitUntil(30_000, "viewed event from the embed page", { session.recorder.events.toString() }) {
            session.recorder.has<SigningEvent.Viewed>()
        }
        val viewed = session.recorder.events.filterIsInstance<SigningEvent.Viewed>().first()
        assertTrue(viewed.reference.signatureRequestId.orEmpty().startsWith("sr_"))
        assertTrue(viewed.reference.signerId.orEmpty().startsWith("sgr_"))
        assertFalse(session.recorder.has<SigningEvent.LoadFailed>())
        assertNull(session.currentResult())
        waitUntil(30_000, "embed page loaded", { session.recorder.events.toString() }) {
            session.recorder.has<SigningEvent.PageLoaded>()
        }
        pressBack()
        onView(withText("Leave")).inRoot(isDialog()).perform(click())
        assertEquals(SigningResult.Canceled, session.awaitResult())
        session.close()
    }

    @Test
    fun directSigningPageLoadsWithoutEndingTheSession() {
        val token = arguments.getString("e2eSignToken")
        assumeTrue("local server arguments not given", base != null && token != null)
        val session = launchSigning("$base/sign.php?token=$token", configuration)
        waitUntil(30_000, "signing page loaded", { session.recorder.events.toString() }) {
            session.recorder.has<SigningEvent.PageLoaded>()
        }
        Thread.sleep(3_000)
        assertFalse(session.recorder.has<SigningEvent.LoadFailed>())
        assertNull(session.currentResult())
        assertTrue(session.currentUrl().contains("/sign.php"))
        session.close()
    }
}
