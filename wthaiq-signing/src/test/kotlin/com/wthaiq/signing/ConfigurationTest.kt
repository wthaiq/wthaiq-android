package com.wthaiq.signing

import com.wthaiq.signing.internal.BridgeScript
import com.wthaiq.signing.internal.ErrorIcon
import com.wthaiq.signing.internal.ResolvedLanguage
import com.wthaiq.signing.internal.SigningStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationTest {

    @Test
    fun defaults() {
        val configuration = WthaiqSigningConfiguration.DEFAULT
        assertEquals(WthaiqSigningConfiguration.Language.AUTOMATIC, configuration.language)
        assertEquals(setOf("wthaiq.com", "www.wthaiq.com"), configuration.allowedHosts)
        assertTrue(configuration.returnUrls.isEmpty())
        assertTrue(configuration.showsCloseButton)
        assertEquals(SigningColor.BRAND, configuration.appearance.tintColor)
        assertEquals(WthaiqSigningConfiguration.InterfaceStyle.SYSTEM, configuration.appearance.interfaceStyle)
        assertNull(configuration.appearance.title)
        assertTrue(configuration.appearance.showsHeader)
        assertTrue(configuration.appearance.showsProgressBar)
        assertTrue(configuration.appearance.confirmsBeforeClosing)
        assertEquals(30_000L, configuration.timeoutMillis)
        assertEquals(WthaiqSigningConfiguration.Dismissal.Automatic(1_500), configuration.dismissal)
        assertEquals(WthaiqSigningConfiguration.IdentityVerificationPolicy.FOLLOW_PLATFORM, configuration.identityVerification)
        assertTrue(configuration.identityVerificationHosts.isEmpty())
        assertTrue(configuration.detectsPlatformReturnNavigation)
        assertTrue(configuration.usesEphemeralWebsiteData)
        assertFalse(configuration.allowsInsecureLocalHosts)
        assertFalse(configuration.isWebContentsDebuggingEnabled)
    }

    @Test
    fun timeoutIsClamped() {
        assertEquals(5_000L, WthaiqSigningConfiguration { timeoutMillis = 1 }.effectiveTimeoutMillis)
        assertEquals(120_000L, WthaiqSigningConfiguration { timeoutMillis = 999_999 }.effectiveTimeoutMillis)
        assertEquals(45_000L, WthaiqSigningConfiguration { timeoutMillis = 45_000 }.effectiveTimeoutMillis)
        assertEquals(5_000L, WthaiqSigningConfiguration { timeoutMillis = -5 }.effectiveTimeoutMillis)
    }

    @Test
    fun dismissalDelay() {
        assertEquals(1_500L, WthaiqSigningConfiguration.DEFAULT.dismissalDelayMillis)
        assertNull(WthaiqSigningConfiguration { dismissal = WthaiqSigningConfiguration.Dismissal.Manual }.dismissalDelayMillis)
        assertEquals(0L, WthaiqSigningConfiguration { dismissal = WthaiqSigningConfiguration.Dismissal.Automatic(-3) }.dismissalDelayMillis)
        assertEquals(
            30_000L,
            WthaiqSigningConfiguration { dismissal = WthaiqSigningConfiguration.Dismissal.Automatic(90_000) }.dismissalDelayMillis,
        )
    }

    @Test
    fun addAllowedHostNormalizes() {
        val configuration = WthaiqSigningConfiguration {
            addAllowedHost("  Sign.Example.COM. ")
            addAllowedHost("   ")
            addIdentityVerificationHost("ID.Example.com")
        }
        assertTrue("sign.example.com" in configuration.allowedHosts)
        assertEquals(3, configuration.allowedHosts.size)
        assertEquals(setOf("id.example.com"), configuration.identityVerificationHosts)
    }

    @Test
    fun builderRoundTripsAndEquality() {
        val original = WthaiqSigningConfiguration {
            language = WthaiqSigningConfiguration.Language.ENGLISH
            addReturnUrl("https://example.com/done")
            appearance { title = "Sign"; confirmsBeforeClosing = false }
        }
        val copy = original.newBuilder().build()
        assertEquals(original, copy)
        assertEquals(original.hashCode(), copy.hashCode())
        assertNotEquals(original, WthaiqSigningConfiguration.DEFAULT)
        assertEquals("Sign", original.appearance.title)
        assertFalse(original.appearance.confirmsBeforeClosing)
        assertTrue(original.toString().contains("ENGLISH"))
    }

    @Test
    fun languageResolution() {
        val auto = WthaiqSigningConfiguration.Language.AUTOMATIC
        assertEquals(ResolvedLanguage.ARABIC, ResolvedLanguage.resolve(auto, listOf("ar-EG", "en")))
        assertEquals(ResolvedLanguage.ENGLISH, ResolvedLanguage.resolve(auto, listOf("en-US", "ar")))
        assertEquals(ResolvedLanguage.ENGLISH, ResolvedLanguage.resolve(auto, emptyList()))
        assertEquals(ResolvedLanguage.ARABIC, ResolvedLanguage.resolve(WthaiqSigningConfiguration.Language.ARABIC, listOf("en")))
        assertEquals(ResolvedLanguage.ENGLISH, ResolvedLanguage.resolve(WthaiqSigningConfiguration.Language.ENGLISH, listOf("ar")))
        assertTrue(ResolvedLanguage.ARABIC.isRightToLeft)
        assertFalse(ResolvedLanguage.ENGLISH.isRightToLeft)
        assertEquals("ar", WthaiqSigningConfiguration.Language.ARABIC.code)
        assertNull(WthaiqSigningConfiguration.Language.AUTOMATIC.code)
    }

    @Test
    fun versionConstants() {
        assertEquals("1.0.0", WthaiqSigning.VERSION)
        assertEquals("2026-07-01", WthaiqSigning.API_VERSION)
        assertEquals(setOf("wthaiq.com", "www.wthaiq.com"), WthaiqSigning.PLATFORM_HOSTS)
    }

    @Test
    fun brandColor() {
        assertEquals(0x121AB0, SigningColor.BRAND.light)
        assertEquals(0x8B91F7, SigningColor.BRAND.dark)
        assertEquals(0xFF121AB0.toInt(), SigningColor.BRAND.argb(darkMode = false))
        assertEquals(0xFF8B91F7.toInt(), SigningColor.BRAND.argb(darkMode = true))
        assertEquals(SigningColor(0x123456), SigningColor(0xFF123456.toInt(), 0x123456))
    }

    @Test
    fun brandContrast() {
        assertTrue(SigningColor.contrastRatio(0x121AB0, 0xFFFFFF) >= 11.0)
        assertTrue(SigningColor.contrastRatio(0x8B91F7, 0x000000) >= 7.0)
        assertEquals(21.0, SigningColor.contrastRatio(0x000000, 0xFFFFFF), 0.01)
    }

    @Test
    fun foregroundChoice() {
        assertEquals(0xFFFFFF, SigningColor.foreground(0x121AB0))
        assertEquals(0x0B0B0B, SigningColor.foreground(0xFFE066))
        assertEquals(0x0B0B0B, SigningColor.foreground(0x8B91F7))
    }

    @Test
    fun everyStringExistsInBothLanguages() {
        val arabic = SigningStrings.make(ResolvedLanguage.ARABIC)
        val english = SigningStrings.make(ResolvedLanguage.ENGLISH)
        val fields = SigningStrings::class.java.declaredFields.filter { it.type == String::class.java }
        assertTrue(fields.size >= 25)
        for (field in fields) {
            field.isAccessible = true
            val ar = field.get(arabic) as String
            val en = field.get(english) as String
            assertTrue(field.name, ar.isNotBlank())
            assertTrue(field.name, en.isNotBlank())
            assertNotEquals(field.name, ar, en)
        }
        assertTrue(arabic.isRightToLeft)
        assertEquals("توقيع المستند", arabic.title)
        assertEquals("Sign document", english.title)
    }

    @Test
    fun errorTextMapping() {
        val strings = SigningStrings.make(ResolvedLanguage.ENGLISH)
        assertEquals(strings.offlineTitle, strings.errorText(WthaiqSigningError.Network(-2, "x")).first)
        assertEquals(strings.timeoutTitle, strings.errorText(WthaiqSigningError.TimedOut).first)
        assertEquals(strings.serverTitle, strings.errorText(WthaiqSigningError.HttpStatus(503)).first)
        assertEquals(strings.invalidLinkTitle, strings.errorText(WthaiqSigningError.InvalidUrl(UrlValidationError.MissingUrl)).first)
        assertEquals(strings.invalidLinkTitle, strings.errorText(WthaiqSigningError.LinkUnavailable(SigningPageState.NOT_FOUND)).first)
        assertEquals(strings.crashTitle, strings.errorText(WthaiqSigningError.WebContentTerminated).first)
        assertEquals(strings.genericTitle, strings.errorText(WthaiqSigningError.Network(-6, "refused")).first)
        assertEquals(strings.genericTitle, strings.errorText(WthaiqSigningError.PageError("x")).first)
    }

    @Test
    fun errorIcons() {
        assertEquals(ErrorIcon.OFFLINE, SigningStrings.icon(WthaiqSigningError.Network(-2, "x")))
        assertEquals(ErrorIcon.CLOCK, SigningStrings.icon(WthaiqSigningError.TimedOut))
        assertEquals(ErrorIcon.LINK, SigningStrings.icon(WthaiqSigningError.UnexpectedReturnStatus("x")))
        assertEquals(ErrorIcon.WARNING, SigningStrings.icon(WthaiqSigningError.HttpStatus(500)))
    }

    @Test
    fun bridgeScriptUsesTheWebMessageObjectAndNoJavascriptInterface() {
        val source = BridgeScript.source
        assertTrue(source.contains("window.wthaiqSigning"))
        assertTrue(source.contains("JSON.stringify(body)"))
        assertTrue(source.contains("/\\/sign\\.php$/i"))
        assertTrue(source.contains("'fa-id-card', 'identity_unavailable'"))
        assertTrue(source.contains("pades=auto"))
        assertFalse(source.contains("$" + "OBJECT_NAME"))
        assertFalse(source.contains("removeAttribute('capture')"))
    }
}
