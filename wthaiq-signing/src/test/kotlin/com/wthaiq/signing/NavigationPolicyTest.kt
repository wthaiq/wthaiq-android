package com.wthaiq.signing

import com.wthaiq.signing.internal.MediaCaptureKind
import com.wthaiq.signing.internal.MediaPermissions
import com.wthaiq.signing.internal.NavigationBlockReason
import com.wthaiq.signing.internal.NavigationDecision
import com.wthaiq.signing.internal.NavigationPolicy
import com.wthaiq.signing.internal.NavigationRequest
import com.wthaiq.signing.internal.NavigationTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {

    private val tapped = NavigationTrigger.LINK_ACTIVATED
    private val scripted = NavigationTrigger.OTHER

    private fun policy(block: WthaiqSigningConfiguration.Builder.() -> Unit = {}) =
        NavigationPolicy(WthaiqSigningConfiguration(block))

    private fun request(
        url: String,
        trigger: NavigationTrigger = scripted,
        source: String? = "wthaiq.com",
        mainFrame: Boolean = true,
        newWindow: Boolean = false,
    ) = NavigationRequest(url, mainFrame, newWindow, trigger, source)

    private val cancelHost = NavigationDecision.Cancel(NavigationBlockReason.HOST_NOT_ALLOWED)

    @Test
    fun platformPagesAreAllowed() {
        val policy = policy()
        assertEquals(NavigationDecision.Allow, policy.decide(request("https://wthaiq.com/sign.php?token=x")))
        assertEquals(NavigationDecision.Allow, policy.decide(request("https://WWW.wthaiq.com/embed/sign/a", tapped)))
        assertEquals(NavigationDecision.Allow, policy.decide(request("https://wthaiq.com/x", source = null, mainFrame = false)))
    }

    @Test
    fun configuredReturnUrlCompletes() {
        val policy = policy { addReturnUrl("https://example.com/done") }
        val decision = policy.decide(request("https://example.com/done?session_id=sr_1&status=declined"))
        assertTrue(decision is NavigationDecision.Complete)
        assertEquals(ReturnUrlMatch.Status.Declined, (decision as NavigationDecision.Complete).match.status)
    }

    @Test
    fun configuredReturnUrlCompletesFromSubframeAndCustomScheme() {
        val policy = policy {
            addReturnUrl("https://example.com/done")
            addReturnUrl(ReturnUrlPattern("myapp"))
        }
        assertTrue(policy.decide(request("https://example.com/done", mainFrame = false, source = null)) is NavigationDecision.Complete)
        assertTrue(policy.decide(request("myapp://signed", tapped)) is NavigationDecision.Complete)
        assertTrue(policy.decideSubframeDocument("https://example.com/done/x", "evil.com") is NavigationDecision.Complete)
    }

    @Test
    fun platformReturnNavigationIsDetectedWithoutConfiguration() {
        val policy = policy()
        val decision = policy.decide(request("https://customer.example/back?session_id=sr_1&signer_id=sgr_2&status=completed"))
        assertTrue(decision is NavigationDecision.Complete)
        assertEquals("sgr_2", (decision as NavigationDecision.Complete).match.signerId)
        assertTrue(
            policy.decideSubframeDocument("https://customer.example/back?session_id=sr_1&status=declined", "wthaiq.com")
                is NavigationDecision.Complete,
        )
    }

    @Test
    fun platformReturnDetectionNeedsPlatformSourceAndCanBeDisabled() {
        val target = "https://customer.example/back?session_id=sr_1&status=completed"
        assertEquals(cancelHost, policy().decide(request(target, source = "evil.com")))
        val disabled = policy { detectsPlatformReturnNavigation = false }
        assertTrue(disabled.decide(request(target)) is NavigationDecision.AllowExternalFlow)
        assertTrue(policy().decide(request("https://customer.example/back?session_id=sr_1")) is NavigationDecision.AllowExternalFlow)
    }

    @Test
    fun newWindowsOpenExternally() {
        val policy = policy()
        assertEquals(
            NavigationDecision.OpenExternally("https://wthaiq.com/terms"),
            policy.decide(request("https://wthaiq.com/terms", tapped, newWindow = true, mainFrame = false)),
        )
        assertEquals(
            NavigationDecision.OpenExternally("https://other.com/x"),
            policy.decide(request("https://other.com/x", scripted, newWindow = true, mainFrame = false)),
        )
    }

    @Test
    fun tappedExternalLinkOpensInTheBrowser() {
        assertEquals(
            NavigationDecision.OpenExternally("https://other.com/page"),
            policy().decide(request("https://other.com/page", tapped)),
        )
    }

    @Test
    fun scriptedNavigationFromPlatformStartsIdentityVerification() {
        val policy = policy()
        assertEquals(
            NavigationDecision.AllowExternalFlow("verify.provider.example"),
            policy.decide(request("https://verify.provider.example/session/1")),
        )
        assertTrue("verify.provider.example" in policy.admittedHosts)
        assertEquals(NavigationDecision.Allow, policy.decide(request("https://verify.provider.example/step2", tapped)))
        assertTrue(policy.isTrustedContentHost("verify.provider.example"))
    }

    @Test
    fun admittedHostMayRedirectToAnotherProviderHost() {
        val policy = policy()
        policy.decide(request("https://verify.provider.example/"))
        assertEquals(
            NavigationDecision.AllowExternalFlow("cdn.provider.example"),
            policy.decide(request("https://cdn.provider.example/", source = "verify.provider.example")),
        )
    }

    @Test
    fun identityVerificationInsideEmbeddedFrame() {
        val policy = policy()
        assertEquals(
            NavigationDecision.AllowExternalFlow("verify.provider.example"),
            policy.decideSubframeDocument("https://verify.provider.example/s", "wthaiq.com"),
        )
        assertEquals(NavigationDecision.Allow, policy.decideSubframeDocument("https://verify.provider.example/s2", null))
    }

    @Test
    fun scriptedNavigationFromUnknownSourceIsRefused() {
        assertEquals(cancelHost, policy().decide(request("https://evil.com/", source = "evil2.com")))
        assertEquals(cancelHost, policy().decide(request("https://evil.com/", source = null)))
        assertEquals(cancelHost, policy().decideSubframeDocument("https://ads.example/", null))
    }

    @Test
    fun restoredPolicyKeepsAdmittedHosts() {
        val restored = NavigationPolicy(WthaiqSigningConfiguration.DEFAULT, listOf("Verify.Provider.Example"))
        assertEquals(NavigationDecision.Allow, restored.decide(request("https://verify.provider.example/x", source = null)))
    }

    @Test
    fun listedHostsOnlyPolicy() {
        val policy = policy {
            identityVerification = WthaiqSigningConfiguration.IdentityVerificationPolicy.LISTED_HOSTS_ONLY
            addIdentityVerificationHost("*.provider.example")
        }
        assertEquals(cancelHost, policy.decide(request("https://other-provider.example/")))
        assertEquals(cancelHost, policy.decideSubframeDocument("https://other-provider.example/", "wthaiq.com"))
        assertEquals(NavigationDecision.Allow, policy.decide(request("https://verify.provider.example/")))
        assertEquals(cancelHost, policy.decide(request("https://provider.example/")))
    }

    @Test
    fun plainHttp() {
        val policy = policy()
        assertEquals(
            NavigationDecision.Cancel(NavigationBlockReason.INSECURE_CONNECTION),
            policy.decide(request("http://wthaiq.com/sign.php?token=x")),
        )
        assertEquals(
            NavigationDecision.OpenExternally("http://other.com/"),
            policy.decide(request("http://other.com/", tapped)),
        )
        val dev = policy { allowsInsecureLocalHosts = true }
        assertEquals(NavigationDecision.Allow, dev.decide(request("http://10.0.2.2:8782/sign.php?token=x", source = null)))
        assertTrue(dev.isPlatformOrigin("http://10.0.2.2:8782"))
        assertFalse(policy.isPlatformOrigin("http://10.0.2.2:8782"))
    }

    @Test
    fun systemSchemes() {
        val policy = policy()
        assertEquals(NavigationDecision.OpenExternally("mailto:a@b.c"), policy.decide(request("mailto:a@b.c", tapped)))
        assertEquals(NavigationDecision.OpenExternally("tel:+201000000000"), policy.decide(request("tel:+201000000000", tapped)))
        assertEquals(
            NavigationDecision.Cancel(NavigationBlockReason.UNSUPPORTED_SCHEME),
            policy.decide(request("sms:123", scripted)),
        )
    }

    @Test
    fun otherSchemes() {
        val policy = policy()
        val unsupported = NavigationDecision.Cancel(NavigationBlockReason.UNSUPPORTED_SCHEME)
        assertEquals(NavigationDecision.Allow, policy.decide(request("about:blank")))
        assertEquals(unsupported, policy.decide(request("data:text/html,hi")))
        assertEquals(NavigationDecision.Allow, policy.decide(request("data:text/html,hi", mainFrame = false)))
        assertEquals(NavigationDecision.Allow, policy.decide(request("blob:https://wthaiq.com/1", mainFrame = false)))
        assertEquals(unsupported, policy.decide(request("intent://scan/#Intent;scheme=zxing;end", tapped)))
        assertEquals(unsupported, policy.decide(request("javascript:alert(1)", tapped)))
        assertEquals(unsupported, policy.decide(request("file:///sdcard/x.html", tapped)))
        assertEquals(unsupported, policy.decide(request("not a url")))
    }

    @Test
    fun afterFinishingExternalNavigationIsRefused() {
        val policy = policy { addReturnUrl("https://example.com/done") }
        policy.markFinished()
        val finished = NavigationDecision.Cancel(NavigationBlockReason.FINISHED)
        assertEquals(finished, policy.decide(request("https://example.com/done?status=completed")))
        assertEquals(finished, policy.decide(request("https://verify.provider.example/")))
        assertEquals(finished, policy.decide(request("https://customer.example/?session_id=sr_1&status=completed")))
        assertEquals(NavigationDecision.Allow, policy.decide(request("https://wthaiq.com/sign.php?token=x")))
    }

    @Test
    fun mediaCapture() {
        val policy = policy { addIdentityVerificationHost("id.example") }
        val both = MediaPermissions(camera = true, microphone = true)
        val cameraOnly = MediaPermissions(camera = true, microphone = false)
        assertTrue(policy.allowsMediaCapture(MediaCaptureKind.CAMERA, "https://wthaiq.com", both))
        assertTrue(policy.allowsMediaCapture(MediaCaptureKind.CAMERA, "https://id.example", cameraOnly))
        assertFalse(policy.allowsMediaCapture(MediaCaptureKind.CAMERA_AND_MICROPHONE, "https://wthaiq.com", cameraOnly))
        assertFalse(policy.allowsMediaCapture(MediaCaptureKind.MICROPHONE, "https://wthaiq.com", cameraOnly))
        assertFalse(policy.allowsMediaCapture(MediaCaptureKind.CAMERA, "https://evil.com", both))
        assertFalse(policy.allowsMediaCapture(MediaCaptureKind.CAMERA, "http://wthaiq.com", both))
        assertFalse(policy.allowsMediaCapture(MediaCaptureKind.CAMERA, "https://wthaiq.com", MediaPermissions(false, false)))
    }

    @Test
    fun platformOriginChecks() {
        val policy = policy { addAllowedHost("*.example.com") }
        assertTrue(policy.isPlatformOrigin("https://wthaiq.com"))
        assertTrue(policy.isPlatformOrigin("https://sign.example.com"))
        assertFalse(policy.isPlatformOrigin("https://example.com"))
        assertFalse(policy.isPlatformOrigin("http://wthaiq.com"))
        assertFalse(policy.isPlatformOrigin("null"))
        assertFalse(policy.isPlatformOrigin(""))
        assertFalse(policy.isPlatformOrigin(null))
        assertTrue(policy.isPlatformFrame("https", "www.wthaiq.com"))
    }
}
