package com.wthaiq.signing.internal

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import com.wthaiq.signing.R
import com.wthaiq.signing.SigningColor

/** Colors of the SDK's own interface. The signing page keeps its own (light) design. */
internal class SigningPalette(val isDark: Boolean, tint: SigningColor) {
    val background: Int = if (isDark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
    val textPrimary: Int = if (isDark) 0xFFFFFFFF.toInt() else 0xFF0B0B0B.toInt()
    val textSecondary: Int = if (isDark) 0xFFB8B8C2.toInt() else 0xFF5B5B66.toInt()
    val divider: Int = if (isDark) 0xFF2C2C31.toInt() else 0xFFE6E6EA.toInt()
    val tint: Int = tint.argb(isDark)
    val onTint: Int = SigningColor.foreground(this.tint and 0xFFFFFF) or 0xFF000000.toInt()
}

internal fun Context.dp(value: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

/** The title bar: close button and title. */
@SuppressLint("ViewConstructor")
internal class SigningHeaderView(
    context: Context,
    palette: SigningPalette,
    title: String,
    closeLabel: String,
    showsCloseButton: Boolean,
) : LinearLayout(context) {
    val closeButton: ImageButton = ImageButton(context)
    val titleView: TextView = TextView(context)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(56f)
        setBackgroundColor(palette.background)
        val startPadding = if (showsCloseButton) context.dp(4f) else context.dp(16f)
        setPaddingRelative(startPadding, 0, context.dp(16f), 0)

        closeButton.setImageResource(R.drawable.wthaiq_ic_close)
        closeButton.imageTintList = ColorStateList.valueOf(palette.textPrimary)
        closeButton.contentDescription = closeLabel
        closeButton.background = RippleDrawable(
            ColorStateList.valueOf(palette.textPrimary and 0x22FFFFFF),
            null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) },
        )
        closeButton.visibility = if (showsCloseButton) VISIBLE else GONE
        addView(closeButton, LayoutParams(context.dp(48f), context.dp(48f)))

        titleView.text = title
        titleView.setTextColor(palette.textPrimary)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        titleView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        titleView.maxLines = 1
        titleView.ellipsize = android.text.TextUtils.TruncateAt.END
        titleView.textAlignment = TEXT_ALIGNMENT_VIEW_START
        ViewCompat.setAccessibilityHeading(titleView, true)
        val titleParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        titleParams.marginStart = if (showsCloseButton) context.dp(8f) else 0
        addView(titleView, titleParams)
    }
}

/** A two-pixel load progress bar that fills from the start edge (right in Arabic). */
@SuppressLint("ViewConstructor")
internal class ThinProgressView(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private var fraction = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
    }

    fun setProgress(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        val growing = clamped >= fraction
        fraction = clamped
        invalidate()
        animate().cancel()
        if (clamped < 1f) {
            alpha = 1f
        } else if (growing) {
            animate().alpha(0f).setStartDelay(200).setDuration(250).start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width * fraction
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            canvas.drawRect(this.width - width, 0f, this.width.toFloat(), height.toFloat(), paint)
        } else {
            canvas.drawRect(0f, 0f, width, height.toFloat(), paint)
        }
    }
}

/** The loading and error screen shown over the page. */
@SuppressLint("ViewConstructor")
internal class SigningStatusView(context: Context, private val palette: SigningPalette) : FrameLayout(context) {
    private val content = LinearLayout(context)
    private val spinner = ProgressBar(context)
    private val iconView = ImageView(context)
    val titleView: TextView = TextView(context)
    private val messageView = TextView(context)
    val retryButton: Button = Button(context)
    val closeButton: Button = Button(context)

    var onRetry: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    init {
        setBackgroundColor(palette.background)
        isClickable = true
        isFocusable = false

        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL
        val padding = context.dp(32f)
        content.setPadding(padding, padding, padding, padding)
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        spinner.isIndeterminate = true
        spinner.indeterminateTintList = ColorStateList.valueOf(palette.tint)
        spinner.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        content.addView(spinner, LinearLayout.LayoutParams(context.dp(40f), context.dp(40f)))

        iconView.imageTintList = ColorStateList.valueOf(palette.textSecondary)
        iconView.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        content.addView(iconView, LinearLayout.LayoutParams(context.dp(48f), context.dp(48f)))

        titleView.setTextColor(palette.textPrimary)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        titleView.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        titleView.gravity = Gravity.CENTER
        ViewCompat.setAccessibilityHeading(titleView, true)
        content.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        content.addView(titleView, wrapWidth(topMargin = 16f))

        messageView.setTextColor(palette.textSecondary)
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        messageView.gravity = Gravity.CENTER
        messageView.setLineSpacing(0f, 1.25f)
        content.addView(messageView, wrapWidth(topMargin = 8f))

        styleButton(retryButton, filled = true)
        retryButton.setOnClickListener { onRetry?.invoke() }
        content.addView(retryButton, wrapWidth(topMargin = 24f, matchWidth = false))

        styleButton(closeButton, filled = false)
        closeButton.setOnClickListener { onClose?.invoke() }
        content.addView(closeButton, wrapWidth(topMargin = 8f, matchWidth = false))
    }

    fun showLoading(text: String) {
        spinner.visibility = VISIBLE
        iconView.visibility = GONE
        titleView.visibility = GONE
        messageView.text = text
        messageView.visibility = VISIBLE
        retryButton.visibility = GONE
        closeButton.visibility = GONE
        contentDescription = text
    }

    fun showError(title: String, message: String, icon: ErrorIcon, retryTitle: String?, closeTitle: String?) {
        spinner.visibility = GONE
        iconView.setImageResource(
            when (icon) {
                ErrorIcon.OFFLINE -> R.drawable.wthaiq_ic_offline
                ErrorIcon.CLOCK -> R.drawable.wthaiq_ic_clock
                ErrorIcon.LINK -> R.drawable.wthaiq_ic_link
                ErrorIcon.WARNING -> R.drawable.wthaiq_ic_warning
            },
        )
        iconView.visibility = VISIBLE
        titleView.text = title
        titleView.visibility = VISIBLE
        messageView.text = message
        messageView.visibility = VISIBLE
        retryButton.text = retryTitle
        retryButton.visibility = if (retryTitle != null) VISIBLE else GONE
        closeButton.text = closeTitle
        closeButton.visibility = if (closeTitle != null) VISIBLE else GONE
        contentDescription = null
    }

    private fun wrapWidth(topMargin: Float, matchWidth: Boolean = true): LinearLayout.LayoutParams {
        val width = if (matchWidth) LinearLayout.LayoutParams.MATCH_PARENT else LinearLayout.LayoutParams.WRAP_CONTENT
        return LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            this.topMargin = context.dp(topMargin)
        }
    }

    private fun styleButton(button: Button, filled: Boolean) {
        button.isAllCaps = false
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        button.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        button.minWidth = context.dp(160f)
        button.minHeight = context.dp(48f)
        button.stateListAnimator = null
        val horizontal = context.dp(24f)
        button.setPadding(horizontal, 0, horizontal, 0)
        val radius = context.dp(7f).toFloat()
        val shape = GradientDrawable().apply {
            cornerRadius = radius
            setColor(if (filled) palette.tint else 0x00000000)
        }
        val mask = GradientDrawable().apply {
            cornerRadius = radius
            setColor(0xFFFFFFFF.toInt())
        }
        val rippleColor = if (filled) palette.onTint and 0x33FFFFFF else palette.tint and 0x22FFFFFF
        button.background = RippleDrawable(ColorStateList.valueOf(rippleColor), shape, mask)
        button.setTextColor(if (filled) palette.onTint else palette.tint)
    }
}

/** A plain divider line. */
internal fun dividerView(context: Context, color: Int): View = View(context).apply {
    background = ColorDrawable(color)
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}
