package com.wthaiq.signing.internal

import com.wthaiq.signing.WthaiqSigningConfiguration
import com.wthaiq.signing.WthaiqSigningError

/** The language actually used once `AUTOMATIC` has been resolved. */
internal enum class ResolvedLanguage(val code: String) {
    ARABIC("ar"),
    ENGLISH("en"),
    ;

    val isRightToLeft: Boolean get() = this == ARABIC

    companion object {
        /** [preferredLanguages] are BCP 47 tags in the app's order (its resolved locales). */
        fun resolve(language: WthaiqSigningConfiguration.Language, preferredLanguages: List<String>): ResolvedLanguage =
            when (language) {
                WthaiqSigningConfiguration.Language.ARABIC -> ARABIC
                WthaiqSigningConfiguration.Language.ENGLISH -> ENGLISH
                WthaiqSigningConfiguration.Language.AUTOMATIC ->
                    if (preferredLanguages.firstOrNull()?.lowercase()?.startsWith("ar") == true) ARABIC else ENGLISH
            }
    }
}

/** The icon shown on the error screen. */
internal enum class ErrorIcon {
    OFFLINE,
    CLOCK,
    LINK,
    WARNING,
}

/**
 * Text shown by the SDK itself, in formal Arabic and English. Kept in code (not in resources) so every string is
 * covered by unit tests and identical to the iOS SDK.
 */
internal data class SigningStrings(
    val language: ResolvedLanguage,
    val title: String,
    val close: String,
    val loading: String,
    val retry: String,
    val confirmTitle: String,
    val confirmMessage: String,
    val confirmLeave: String,
    val confirmStay: String,
    val announceSigned: String,
    val announceDeclined: String,
    val okay: String,
    val cancel: String,
    val offlineTitle: String,
    val offlineMessage: String,
    val timeoutTitle: String,
    val timeoutMessage: String,
    val serverTitle: String,
    val serverMessage: String,
    val genericTitle: String,
    val genericMessage: String,
    val invalidLinkTitle: String,
    val invalidLinkMessage: String,
    val crashTitle: String,
    val crashMessage: String,
    val chooseFile: String,
) {
    val isRightToLeft: Boolean get() = language.isRightToLeft

    /** Title and message for the error screen. */
    fun errorText(error: WthaiqSigningError): Pair<String, String> {
        if (error.isOffline) return offlineTitle to offlineMessage
        return when (error) {
            WthaiqSigningError.TimedOut -> timeoutTitle to timeoutMessage
            is WthaiqSigningError.HttpStatus -> serverTitle to serverMessage
            is WthaiqSigningError.InvalidUrl,
            is WthaiqSigningError.LinkUnavailable,
            is WthaiqSigningError.UnexpectedReturnStatus,
            -> invalidLinkTitle to invalidLinkMessage
            WthaiqSigningError.WebContentTerminated -> crashTitle to crashMessage
            is WthaiqSigningError.Network, is WthaiqSigningError.PageError -> genericTitle to genericMessage
        }
    }

    companion object {
        fun icon(error: WthaiqSigningError): ErrorIcon {
            if (error.isOffline) return ErrorIcon.OFFLINE
            return when (error) {
                WthaiqSigningError.TimedOut -> ErrorIcon.CLOCK
                is WthaiqSigningError.InvalidUrl,
                is WthaiqSigningError.LinkUnavailable,
                is WthaiqSigningError.UnexpectedReturnStatus,
                -> ErrorIcon.LINK
                else -> ErrorIcon.WARNING
            }
        }

        fun make(language: ResolvedLanguage): SigningStrings = when (language) {
            ResolvedLanguage.ARABIC -> SigningStrings(
                language = ResolvedLanguage.ARABIC,
                title = "توقيع المستند",
                close = "إغلاق",
                loading = "جارٍ تحميل المستند…",
                retry = "إعادة المحاولة",
                confirmTitle = "الخروج من التوقيع؟",
                confirmMessage = "لم يكتمل التوقيع بعد، ويمكنك فتح الرابط مرّة أخرى لاحقًا لإتمامه.",
                confirmLeave = "خروج",
                confirmStay = "متابعة التوقيع",
                announceSigned = "تمّ توقيع المستند.",
                announceDeclined = "سُجِّل اعتذارك عن التوقيع.",
                okay = "حسنًا",
                cancel = "إلغاء",
                offlineTitle = "لا يوجد اتصال بالإنترنت",
                offlineMessage = "تحقّق من اتصالك بالإنترنت، ثم أعد المحاولة.",
                timeoutTitle = "استغرق التحميل وقتًا أطول من المعتاد",
                timeoutMessage = "الاتصال بطيء أو متقطّع. أعد المحاولة بعد لحظات.",
                serverTitle = "الخدمة غير متاحة مؤقّتًا",
                serverMessage = "تعذّر الوصول إلى صفحة التوقيع الآن. أعد المحاولة بعد لحظات.",
                genericTitle = "تعذّر تحميل المستند",
                genericMessage = "حدث خطأ أثناء تحميل صفحة التوقيع. أعد المحاولة.",
                invalidLinkTitle = "لا يمكن فتح رابط التوقيع",
                invalidLinkMessage = "هذا الرابط غير صالح للتوقيع داخل التطبيق. اطلب من مُرسِل المستند رابطًا جديدًا.",
                crashTitle = "توقّفت الصفحة على نحو غير متوقّع",
                crashMessage = "أعد تحميل الصفحة لمتابعة التوقيع.",
                chooseFile = "اختر ملفًّا",
            )
            ResolvedLanguage.ENGLISH -> SigningStrings(
                language = ResolvedLanguage.ENGLISH,
                title = "Sign document",
                close = "Close",
                loading = "Loading the document…",
                retry = "Try again",
                confirmTitle = "Leave signing?",
                confirmMessage = "The document has not been signed yet. You can open the link again later to finish.",
                confirmLeave = "Leave",
                confirmStay = "Continue signing",
                announceSigned = "The document has been signed.",
                announceDeclined = "You declined to sign.",
                okay = "OK",
                cancel = "Cancel",
                offlineTitle = "No internet connection",
                offlineMessage = "Check your connection, then try again.",
                timeoutTitle = "This is taking longer than usual",
                timeoutMessage = "The connection is slow or interrupted. Try again in a moment.",
                serverTitle = "Service temporarily unavailable",
                serverMessage = "The signing page cannot be reached right now. Try again in a moment.",
                genericTitle = "The document could not be loaded",
                genericMessage = "Something went wrong while loading the signing page. Try again.",
                invalidLinkTitle = "This signing link cannot be opened",
                invalidLinkMessage = "This link cannot be used for signing in the app. Ask the sender for a new link.",
                crashTitle = "The page stopped unexpectedly",
                crashMessage = "Reload the page to continue signing.",
                chooseFile = "Choose a file",
            )
        }
    }
}

/** Maps WebView loading errors to [WthaiqSigningError]. */
internal object LoadFailure {
    /** `WebViewClient.ERROR_TIMEOUT`. */
    const val ERROR_TIMEOUT: Int = -8

    /** `WebViewClient.ERROR_FAILED_SSL_HANDSHAKE`. */
    const val ERROR_FAILED_SSL_HANDSHAKE: Int = -11

    /** Returns `null` for errors that are not failures (a navigation that was canceled or replaced). */
    fun classify(code: Int, description: String?): WthaiqSigningError? {
        val text = description.orEmpty()
        if (text.contains("ERR_ABORTED", ignoreCase = true)) return null
        if (code == ERROR_TIMEOUT || text.contains("ERR_TIMED_OUT", ignoreCase = true)) return WthaiqSigningError.TimedOut
        return WthaiqSigningError.Network(code, text.ifEmpty { "Error $code" })
    }

    /** HTTP statuses that replace the page with the retry screen. Other 4xx pages are shown as served. */
    fun isBlockingStatus(status: Int): Boolean = status == 429 || status >= 500
}
