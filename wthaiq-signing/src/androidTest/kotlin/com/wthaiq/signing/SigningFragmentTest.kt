package com.wthaiq.signing

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SigningFragmentTest {

    @Before
    fun setUp() {
        Fixtures.install()
    }

    @After
    fun tearDown() {
        Fixtures.uninstall()
    }

    @Test
    fun hostedFragmentDeliversResultOnceThroughBothChannelsAndPopsItself() {
        Fixtures.page(
            "wthaiq.com/embed/sign/host1",
            Fixtures.document("", Fixtures.post("viewed") + "setTimeout(function(){${Fixtures.post("signed")}},300);"),
        )
        ActivityScenario.launch(HostTestActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.show(
                    "https://wthaiq.com/embed/sign/host1",
                    WthaiqSigningConfiguration { dismissal = WthaiqSigningConfiguration.Dismissal.Automatic(100) },
                )
            }
            waitUntil(message = "fragment removed") {
                var done = false
                scenario.onActivity { activity ->
                    done = activity.results.isNotEmpty() && activity.supportFragmentManager.backStackEntryCount == 0
                }
                done
            }
            scenario.onActivity { activity ->
                assertEquals(1, activity.results.size)
                assertTrue(activity.results.single() is SigningResult.Signed)
                assertEquals(activity.results, activity.fragmentResults)
                assertTrue(activity.events.any { it is SigningEvent.Viewed })
                assertTrue(activity.supportFragmentManager.fragments.none { it is WthaiqSigningFragment })
            }
        }
    }

    @Test
    fun fileInputOpensTheSystemChooser() {
        Intents.init()
        try {
            intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            Fixtures.page(
                "wthaiq.com/sign.php",
                Fixtures.document(
                    "<input id='file' type='file' accept='image/jpeg,image/png,image/webp,application/pdf' " +
                        "capture='environment' style='display:block;width:100%;height:150px'>",
                ),
            )
            val session = launchSigning("https://wthaiq.com/sign.php?token=f1")
            waitUntil(message = "page loaded") { session.recorder.has<SigningEvent.PageLoaded>() }
            session.tapElement("file")
            waitUntil(message = "chooser") { Intents.getIntents().any { it.action == Intent.ACTION_CHOOSER } }
            intended(hasAction(Intent.ACTION_CHOOSER))
            val chooser = Intents.getIntents().first { it.action == Intent.ACTION_CHOOSER }
            @Suppress("DEPRECATION")
            val pick = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_GET_CONTENT, pick.action)
            assertEquals(
                listOf("image/jpeg", "image/png", "image/webp", "application/pdf"),
                pick.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.toList(),
            )
            session.close()
        } finally {
            Intents.release()
        }
    }
}
