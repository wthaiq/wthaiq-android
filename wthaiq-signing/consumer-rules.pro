# Consumer R8/ProGuard rules for com.wthaiq:signing.
#
# The SDK uses no reflection, no @JavascriptInterface and no serialization by class name. Its activity and file
# provider are referenced from the merged manifest, which R8 keeps automatically. Page events reach native code
# through androidx.webkit's WebMessageListener, which needs no keep rules.
#
# Results and configurations travel between the app and the signing activity as Bundles with explicit keys, so
# shrinking, obfuscation and optimization of the app (including full R8 mode) are safe.
