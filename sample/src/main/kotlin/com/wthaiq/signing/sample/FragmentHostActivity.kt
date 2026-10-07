package com.wthaiq.signing.sample

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import com.wthaiq.signing.SigningEvent
import com.wthaiq.signing.SigningResult
import com.wthaiq.signing.WthaiqSigningFragment

/** Shows how to host [WthaiqSigningFragment] in your own activity. */
class FragmentHostActivity : FragmentActivity(), WthaiqSigningFragment.Listener {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this).apply { id = CONTAINER_ID }
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(container)
        if (savedInstanceState == null) {
            val url = intent.getStringExtra(EXTRA_URL).orEmpty()
            val local = intent.getBooleanExtra(EXTRA_LOCAL, false)
            supportFragmentManager.beginTransaction()
                .replace(CONTAINER_ID, WthaiqSigningFragment.newInstance(url, sampleConfiguration(local)))
                .commit()
        }
    }

    override fun onSigningResult(fragment: WthaiqSigningFragment, result: SigningResult) {
        Toast.makeText(this, describe(result), Toast.LENGTH_LONG).show()
    }

    override fun onSigningEvent(fragment: WthaiqSigningFragment, event: SigningEvent) = Unit

    override fun onSigningDismissRequested(fragment: WthaiqSigningFragment) {
        finish()
    }

    companion object {
        private const val CONTAINER_ID = 0x7f0b1001
        private const val EXTRA_URL = "url"
        private const val EXTRA_LOCAL = "local"

        fun intent(context: Context, url: String, local: Boolean): Intent =
            Intent(context, FragmentHostActivity::class.java).putExtra(EXTRA_URL, url).putExtra(EXTRA_LOCAL, local)
    }
}
