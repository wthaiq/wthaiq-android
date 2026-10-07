package com.wthaiq.signing.sample

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.wthaiq.signing.SigningEvent
import com.wthaiq.signing.SigningResult
import com.wthaiq.signing.WthaiqSigningConfiguration
import com.wthaiq.signing.compose.WthaiqSigningSheet
import com.wthaiq.signing.compose.rememberWthaiqSigningLauncher
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF121AB0))) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SampleScreen(
                        fixtureUrl = { path -> fixtures(applicationContext).baseUrl + path },
                        openFragmentHost = { url, local -> startActivity(FragmentHostActivity.intent(this, url, local)) },
                    )
                }
            }
        }
    }

    companion object {
        private var server: FixtureServer? = null

        @Synchronized
        fun fixtures(context: Context): FixtureServer =
            server ?: FixtureServer(context.applicationContext.assets).also { server = it }
    }
}

/** The configuration the sample uses. Local fixture mode enables the development switch for 127.0.0.1. */
fun sampleConfiguration(local: Boolean): WthaiqSigningConfiguration = WthaiqSigningConfiguration {
    addReturnUrl("https://example.com/signing/done")
    allowsInsecureLocalHosts = local
}

@Composable
private fun SampleScreen(fixtureUrl: (String) -> String, openFragmentHost: (String, Boolean) -> Unit) {
    var localMode by rememberSaveable { mutableStateOf(true) }
    var backendUrl by rememberSaveable { mutableStateOf(BuildConfig.BACKEND_URL) }
    var status by rememberSaveable { mutableStateOf("No session yet.") }
    var sheetUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetVisible by rememberSaveable { mutableStateOf(false) }
    val events = remember { mutableStateListOf<String>() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val launcher = rememberWthaiqSigningLauncher(onEvent = { event -> events.add(0, describe(event)) }) { result ->
        status = describe(result)
    }

    WthaiqSigningSheet(
        isPresented = sheetVisible,
        url = sheetUrl,
        configuration = sampleConfiguration(localMode),
        onEvent = { event -> events.add(0, describe(event)) },
    ) { result ->
        sheetVisible = false
        status = describe(result)
    }

    fun withSigningUrl(block: (String) -> Unit) {
        val local = localMode
        val endpoint = backendUrl
        if (!local) status = "Asking your backend for a signing link…"
        thread {
            val outcome = runCatching {
                if (local) fixtureUrl("/embed/sign/demo123") else SigningBackend.fetchSigningUrl(endpoint)
            }
            mainHandler.post {
                outcome.onSuccess(block).onFailure { status = "Backend error: ${it.message}" }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Wthaiq Signing", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Local fixture (offline)", modifier = Modifier.weight(1f))
            Switch(checked = localMode, onCheckedChange = { localMode = it })
        }
        if (!localMode) {
            OutlinedTextField(
                value = backendUrl,
                onValueChange = { backendUrl = it },
                label = { Text("Backend endpoint") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        Button(
            onClick = { withSigningUrl { url -> launcher.launch(url, sampleConfiguration(localMode)) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign (Activity Result launcher)") }
        OutlinedButton(
            onClick = { withSigningUrl { url -> sheetUrl = url; sheetVisible = true } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign (WthaiqSigningSheet)") }
        OutlinedButton(
            onClick = { withSigningUrl { url -> openFragmentHost(url, localMode) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign (Fragment in your activity)") }
        if (localMode) {
            OutlinedButton(
                onClick = {
                    thread {
                        val url = fixtureUrl("/sign.php?token=demo")
                        mainHandler.post { launcher.launch(url, sampleConfiguration(true)) }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Direct signing page with return URL") }
        }
        Text("Result: $status", style = MaterialTheme.typography.bodyLarge)
        Text("Events", style = MaterialTheme.typography.titleMedium)
        events.take(30).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

internal fun describe(result: SigningResult): String = when (result) {
    is SigningResult.Signed ->
        "Signed (${result.outcome.detection.value}) request=${result.outcome.reference.signatureRequestId}"
    is SigningResult.Declined -> "Declined (${result.outcome.detection.value}) reason=${result.outcome.reason}"
    SigningResult.Canceled -> "Canceled"
    SigningResult.Expired -> "Expired — ask the backend for a new link"
    is SigningResult.Failed -> "Failed: ${result.error.description}"
}

internal fun describe(event: SigningEvent): String = when (event) {
    is SigningEvent.PageLoaded -> "Page loaded"
    is SigningEvent.Viewed -> "Viewed ${event.reference.signatureRequestId}"
    is SigningEvent.PageStateChanged -> "Page state ${event.state.value}"
    is SigningEvent.IdentityVerificationStarted -> "Identity verification on ${event.host}"
    is SigningEvent.ExternalUrlOpened -> "Opened externally"
    is SigningEvent.NavigationBlocked -> "Navigation blocked"
    is SigningEvent.LoadFailed -> "Load failed: ${event.error.description}"
    is SigningEvent.PageMessage -> "Page message ${event.name}"
}
