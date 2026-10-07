package com.wthaiq.signing

import android.os.Bundle
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import java.util.concurrent.CopyOnWriteArrayList

/** Hosts [WthaiqSigningFragment] the way an app would, recording what the fragment reports. */
class HostTestActivity : FragmentActivity(), WthaiqSigningFragment.Listener {
    val results: MutableList<SigningResult> = CopyOnWriteArrayList()
    val fragmentResults: MutableList<SigningResult> = CopyOnWriteArrayList()
    val events: MutableList<SigningEvent> = CopyOnWriteArrayList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { id = CONTAINER })
        supportFragmentManager.setFragmentResultListener(WthaiqSigningFragment.REQUEST_KEY, this) { _, bundle ->
            WthaiqSigningFragment.resultFrom(bundle)?.let(fragmentResults::add)
        }
    }

    fun show(url: String, configuration: WthaiqSigningConfiguration) {
        supportFragmentManager.beginTransaction()
            .replace(CONTAINER, WthaiqSigningFragment.newInstance(url, configuration))
            .addToBackStack(WthaiqSigningFragment.BACK_STACK_NAME)
            .commit()
    }

    override fun onSigningResult(fragment: WthaiqSigningFragment, result: SigningResult) {
        results.add(result)
    }

    override fun onSigningEvent(fragment: WthaiqSigningFragment, event: SigningEvent) {
        events.add(event)
    }

    companion object {
        const val CONTAINER: Int = 0x7f0c0101
    }
}
