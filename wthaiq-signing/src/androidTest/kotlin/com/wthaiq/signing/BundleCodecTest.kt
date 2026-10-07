package com.wthaiq.signing

import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wthaiq.signing.internal.BundleCodec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundleCodecTest {

    /** Writes the bundle into a Parcel and back, as saved state and intents do. */
    private fun roundTrip(bundle: Bundle): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            return parcel.readBundle(javaClass.classLoader)!!
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun configurationRoundTrips() {
        val configuration = WthaiqSigningConfiguration {
            language = WthaiqSigningConfiguration.Language.ARABIC
            addAllowedHost("sign.example.com")
            addReturnUrl("https://example.com/done")
            addReturnUrl(ReturnUrlPattern("myapp"))
            showsCloseButton = false
            appearance {
                tintColor = SigningColor(0x112233, 0x445566)
                interfaceStyle = WthaiqSigningConfiguration.InterfaceStyle.DARK
                title = "توقيع العقد"
                showsHeader = false
                showsProgressBar = false
                confirmsBeforeClosing = false
            }
            timeoutMillis = 45_000
            dismissal = WthaiqSigningConfiguration.Dismissal.Manual
            identityVerification = WthaiqSigningConfiguration.IdentityVerificationPolicy.LISTED_HOSTS_ONLY
            addIdentityVerificationHost("*.provider.example")
            detectsPlatformReturnNavigation = false
            usesEphemeralWebsiteData = false
            allowsInsecureLocalHosts = true
            isWebContentsDebuggingEnabled = true
        }
        assertEquals(configuration, BundleCodec.readConfiguration(roundTrip(BundleCodec.writeConfiguration(configuration))))
        assertEquals(
            WthaiqSigningConfiguration.DEFAULT,
            BundleCodec.readConfiguration(roundTrip(BundleCodec.writeConfiguration(WthaiqSigningConfiguration.DEFAULT))),
        )
        assertEquals(WthaiqSigningConfiguration.DEFAULT, BundleCodec.readConfiguration(null))
    }

    @Test
    fun everyResultRoundTrips() {
        val outcome = SigningOutcome(SigningReference("sr_1", "sgr_1"), SigningOutcome.Detection.RETURN_URL, "https://e.com/?x", true, "r")
        val results = listOf(
            SigningResult.Signed(outcome),
            SigningResult.Declined(SigningOutcome(detection = SigningOutcome.Detection.PAGE_CONTENT)),
            SigningResult.Canceled,
            SigningResult.Expired,
            SigningResult.Failed(WthaiqSigningError.InvalidUrl(UrlValidationError.MissingUrl)),
            SigningResult.Failed(WthaiqSigningError.InvalidUrl(UrlValidationError.Malformed)),
            SigningResult.Failed(WthaiqSigningError.InvalidUrl(UrlValidationError.UnsupportedScheme("http"))),
            SigningResult.Failed(WthaiqSigningError.InvalidUrl(UrlValidationError.MissingHost)),
            SigningResult.Failed(WthaiqSigningError.InvalidUrl(UrlValidationError.HostNotAllowed("evil.com"))),
            SigningResult.Failed(WthaiqSigningError.InvalidUrl(UrlValidationError.ContainsCredentials)),
            SigningResult.Failed(WthaiqSigningError.InvalidUrl(UrlValidationError.MissingToken)),
            SigningResult.Failed(WthaiqSigningError.Network(-2, "net::ERR_NAME_NOT_RESOLVED")),
            SigningResult.Failed(WthaiqSigningError.TimedOut),
            SigningResult.Failed(WthaiqSigningError.HttpStatus(503)),
            SigningResult.Failed(WthaiqSigningError.LinkUnavailable(SigningPageState.NOT_FOUND)),
            SigningResult.Failed(WthaiqSigningError.PageError("boom")),
            SigningResult.Failed(WthaiqSigningError.WebContentTerminated),
            SigningResult.Failed(WthaiqSigningError.UnexpectedReturnStatus("odd")),
        )
        for (result in results) {
            assertEquals(result, BundleCodec.readResult(roundTrip(BundleCodec.writeResult(result))))
        }
    }

    @Test
    fun contractTreatsMissingResultAsCanceled() {
        val contract = WthaiqSigning.Contract()
        assertEquals(SigningResult.Canceled, contract.parseResult(android.app.Activity.RESULT_CANCELED, null))
        assertEquals(SigningResult.Canceled, contract.parseResult(android.app.Activity.RESULT_OK, null))
    }
}
