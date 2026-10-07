package com.wthaiq.signing.compose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.wthaiq.signing.SigningEventListener
import com.wthaiq.signing.SigningRequest
import com.wthaiq.signing.SigningResult
import com.wthaiq.signing.UrlValidationError
import com.wthaiq.signing.WthaiqSigning
import com.wthaiq.signing.WthaiqSigningConfiguration
import com.wthaiq.signing.WthaiqSigningError

/**
 * Starts the full-screen signing activity from Compose. Create it with [rememberWthaiqSigningLauncher].
 */
@Stable
public class WthaiqSigningLauncher internal constructor(
    private val launcher: ActivityResultLauncher<SigningRequest>,
) {
    /** Presents the signing page for [url]. The result arrives in the `onResult` callback given at creation. */
    public fun launch(
        url: String,
        configuration: WthaiqSigningConfiguration = WthaiqSigningConfiguration.DEFAULT,
    ) {
        launcher.launch(SigningRequest(url, configuration))
    }

    /** Presents the signing page for [request]. */
    public fun launch(request: SigningRequest) {
        launcher.launch(request)
    }
}

/**
 * Remembers a [WthaiqSigningLauncher]. [onResult] is called once per launch, after the signing screen has closed,
 * and is redelivered after the app's process is recreated (Activity Result API).
 *
 * ```kotlin
 * val signing = rememberWthaiqSigningLauncher { result ->
 *     if (result.isSigned) viewModel.refreshOrder()
 * }
 * Button(onClick = { signing.launch(signingUrl) }) { Text("Sign") }
 * ```
 *
 * @param onEvent informational events while the signing screen is open.
 */
@Composable
public fun rememberWthaiqSigningLauncher(
    onEvent: SigningEventListener? = null,
    onResult: (SigningResult) -> Unit,
): WthaiqSigningLauncher {
    val currentOnEvent by rememberUpdatedState(onEvent)
    val contract = remember {
        WthaiqSigning.Contract { event -> currentOnEvent?.onSigningEvent(event) }
    }
    val launcher = rememberLauncherForActivityResult(contract, onResult)
    return remember(launcher) { WthaiqSigningLauncher(launcher) }
}

/**
 * Presents the signing page full screen while [isPresented] is `true`, the declarative twin of the iOS
 * `.wthaiqSigning(isPresented:url:)` modifier.
 *
 * [onResult] is called once per presentation, after the signing screen has closed; set [isPresented] back to `false`
 * there. A `null` [url] reports `Failed(InvalidUrl(MissingUrl))` without opening anything. The presentation survives
 * configuration changes and process recreation.
 *
 * ```kotlin
 * var signing by rememberSaveable { mutableStateOf(false) }
 * WthaiqSigningSheet(isPresented = signing, url = signingUrl) { result ->
 *     signing = false
 *     if (result.isSigned) viewModel.refreshOrder()
 * }
 * ```
 */
@Composable
public fun WthaiqSigningSheet(
    isPresented: Boolean,
    url: String?,
    configuration: WthaiqSigningConfiguration = WthaiqSigningConfiguration.DEFAULT,
    onEvent: SigningEventListener? = null,
    onResult: (SigningResult) -> Unit,
) {
    val currentOnResult by rememberUpdatedState(onResult)
    var launched by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberWthaiqSigningLauncher(onEvent) { result ->
        launched = false
        currentOnResult(result)
    }
    LaunchedEffect(isPresented, url) {
        if (!isPresented) {
            launched = false
            return@LaunchedEffect
        }
        if (launched) return@LaunchedEffect
        if (url == null) {
            currentOnResult(SigningResult.Failed(WthaiqSigningError.InvalidUrl(UrlValidationError.MissingUrl)))
            return@LaunchedEffect
        }
        launched = true
        launcher.launch(url, configuration)
    }
}
