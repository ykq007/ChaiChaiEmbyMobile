package dev.chaichai.mobile.core.contracts

/**
 * Present provider subtitles accessibly (issue #33). Android-free value type describing how rendered
 * subtitles look, consumed by [PlaybackCoordinator.setSubtitleAppearance] and applied LIVE by the
 * Media3 engine via `SubtitleView`/`CaptionStyleCompat` + fractional text size — no restart, no
 * renegotiation. [Default] is chosen to meet WCAG AA contrast (>= 4.5:1, see [contrastRatio]) and its
 * [position] choices all stay inside the safe render pane (see [SubtitlePositionBounds]).
 *
 * DOCUMENTED SCOPE (per issue #33 AC4): every field on this type is a single USER PREFERENCE keyed by
 * [HomeScope] (server+user) — see `PlaybackPreferences.subtitleAppearanceFor`/`setSubtitleAppearance`
 * and its `SharedPreferencesPlaybackPreferences` implementation in `platform:server`. This mirrors
 * #25's playback-speed scope: a viewer's chosen text size/position/color/edge style/opacity follows
 * them across every title on that server account. It is deliberately NOT media-scoped, unlike #25's
 * subtitle DELAY (per-[MediaIdentity]) or #32's activated provider subtitle (per-[MediaIdentity] via
 * [ExternalSubtitleActivation]) — appearance is about the viewer's accessibility needs, not the media.
 * No font-file upload, no cross-device sync (out of scope for Subtitle Expansion).
 */
data class SubtitleAppearance(
    val textScale: Float = 1.0f,
    val position: SubtitlePosition = SubtitlePosition.BottomDefault,
    val colorPreset: SubtitleColorPreset = SubtitleColorPreset.WhiteOnBlack,
    val edgeStyle: SubtitleEdgeStyle = SubtitleEdgeStyle.Outline,
    val windowOpacity: Float = 0.7f,
) {
    companion object {
        const val MinTextScale = 0.5f
        const val MaxTextScale = 2.0f
        val Default = SubtitleAppearance()
    }
}

/**
 * Bounded caption vertical placement. Every value is deliberately kept inside the safe render pane —
 * see [SubtitlePositionBounds.bottomPaddingFraction], which clamps the resulting padding fraction so
 * no choice can ever push subtitle text onto an unsafe cutout/fold/hinge inset.
 */
enum class SubtitlePosition { BottomDefault, Lower, Upper }

/**
 * Accessible color presets only (no free-form custom color in this milestone): each foreground/
 * background pairing meets WCAG AA contrast (>= 4.5:1, verified by [contrastRatio] in tests).
 */
enum class SubtitleColorPreset(val foregroundArgb: Long, val backgroundArgb: Long) {
    WhiteOnBlack(0xFFFFFFFFL, 0xE6000000L),
    YellowOnBlack(0xFFFFEB3BL, 0xE6000000L),
    BlackOnWhite(0xFF000000L, 0xE6FFFFFFL),
}

/** Caption edge treatment; mirrors Media3's `CaptionStyleCompat` edge types. */
enum class SubtitleEdgeStyle { None, Outline, DropShadow }

/**
 * Pure function mapping [SubtitlePosition] to a bottom-padding FRACTION of the safe render pane's
 * height. The render pane itself is already padded clear of cutouts/folds/hinges by the adaptive
 * `PlaybackWindowLayout`; this only decides WHERE inside that already-safe pane the caption sits, and
 * the result is always clamped into [MinBottomPaddingFraction]..[MaxBottomPaddingFraction] so no
 * current or future [SubtitlePosition] value can land text outside the safe area (see the
 * accessibility unit test asserting this bound for every enum entry).
 */
object SubtitlePositionBounds {
    const val MinBottomPaddingFraction = 0.04f
    const val MaxBottomPaddingFraction = 0.30f

    fun bottomPaddingFraction(position: SubtitlePosition): Float = when (position) {
        SubtitlePosition.BottomDefault -> 0.08f
        SubtitlePosition.Lower -> MinBottomPaddingFraction
        SubtitlePosition.Upper -> MaxBottomPaddingFraction
    }.coerceIn(MinBottomPaddingFraction, MaxBottomPaddingFraction)
}

/**
 * WCAG 2.x relative-luminance contrast ratio between two `0xAARRGGBB` colors (alpha ignored: captions
 * always render their own background swatch behind the text, so no compositing against arbitrary video
 * frames is modeled). Pure/Android-free so readability can be asserted in a plain JVM unit test without
 * Robolectric or instrumentation.
 */
fun contrastRatio(foregroundArgb: Long, backgroundArgb: Long): Double {
    val l1 = relativeLuminance(foregroundArgb)
    val l2 = relativeLuminance(backgroundArgb)
    val lighter = maxOf(l1, l2)
    val darker = minOf(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(argb: Long): Double {
    val r = linearChannel(argb, 16)
    val g = linearChannel(argb, 8)
    val b = linearChannel(argb, 0)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

private fun linearChannel(argb: Long, shift: Int): Double {
    val value = ((argb shr shift) and 0xFF) / 255.0
    return if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
}
