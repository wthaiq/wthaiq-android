package com.wthaiq.signing

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * An sRGB color with separate values for light and dark mode, written as `0xRRGGBB` (alpha is ignored; the color
 * is always opaque).
 */
public class SigningColor(light: Int, dark: Int) {
    /** One color for both modes. */
    public constructor(rgb: Int) : this(rgb, rgb)

    /** The color used in light mode, `0xRRGGBB`. */
    public val light: Int = light and 0xFFFFFF

    /** The color used in dark mode, `0xRRGGBB`. */
    public val dark: Int = dark and 0xFFFFFF

    /** The opaque ARGB color int for light or dark mode, ready for Android views. */
    public fun argb(darkMode: Boolean): Int = (if (darkMode) dark else light) or OPAQUE

    override fun equals(other: Any?): Boolean = other is SigningColor && other.light == light && other.dark == dark

    override fun hashCode(): Int = 31 * light + dark

    override fun toString(): String = "SigningColor(light=#%06X, dark=#%06X)".format(light, dark)

    public companion object {
        private const val OPAQUE: Int = -0x1000000

        /**
         * The Wthaiq brand blue `#121AB0`, with a lighter `#8B91F7` in dark mode so it stays readable on dark
         * backgrounds (contrast 11.7:1 on white and 7.5:1 on black).
         */
        @JvmField
        public val BRAND: SigningColor = SigningColor(0x121AB0, 0x8B91F7)

        /** WCAG 2 relative luminance of `0xRRGGBB`. */
        internal fun relativeLuminance(rgb: Int): Double {
            fun channel(value: Double): Double = if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            val red = ((rgb shr 16) and 0xFF) / 255.0
            val green = ((rgb shr 8) and 0xFF) / 255.0
            val blue = (rgb and 0xFF) / 255.0
            return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
        }

        /** WCAG 2 contrast ratio between two colors (1 to 21). */
        internal fun contrastRatio(first: Int, second: Int): Double {
            val lumA = relativeLuminance(first)
            val lumB = relativeLuminance(second)
            return (max(lumA, lumB) + 0.05) / (min(lumA, lumB) + 0.05)
        }

        /** The text color drawn on top of `rgb`: white when it reaches 4.5:1, otherwise near-black. */
        internal fun foreground(rgb: Int): Int = if (contrastRatio(rgb, 0xFFFFFF) >= 4.5) 0xFFFFFF else 0x0B0B0B
    }
}
