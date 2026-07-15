package dev.chaichai.mobile.platform.adaptive

data class WindowCharacteristics(
    val usableWidthDp: Int,
    val usableHeightDp: Int,
    val hasSeparatingVerticalHinge: Boolean = false,
    val verticalPaneWidthsDp: List<Int> = emptyList(),
    val hasSeparatingHorizontalHinge: Boolean = false,
    val horizontalPaneHeightsDp: List<Int> = emptyList(),
)

enum class NavigationPlacement { Bottom, Rail }
enum class ContentWidthClass { Compact, Medium, Expanded }

data class AdaptiveLayout(
    val navigationPlacement: NavigationPlacement,
    val isHeightConstrained: Boolean,
    val contentWidthClass: ContentWidthClass,
    val supportsListDetail: Boolean,
)

enum class PlaybackTracksPresentation { ModalBottom, AnchoredSide }

sealed interface PlaybackSafePane {
    data object WholeWindow : PlaybackSafePane
    data class Start(val widthDp: Int) : PlaybackSafePane
    data class End(val widthDp: Int) : PlaybackSafePane
    data class Top(val heightDp: Int) : PlaybackSafePane
    data class Bottom(val heightDp: Int) : PlaybackSafePane
}

data class PlaybackTracksLayout(
    val presentation: PlaybackTracksPresentation,
    val safePane: PlaybackSafePane,
)

object AdaptiveNavigationPolicy {
    private const val RailMinimumWidthDp = 600
    private const val ExpandedMinimumWidthDp = 840
    private const val ConstrainedHeightDp = 480
    private const val ListDetailMinimumWindowWidthDp = 920
    private const val HingePaneMinimumWidthDp = 360

    fun placement(window: WindowCharacteristics): NavigationPlacement =
        if (window.hasSeparatingVerticalHinge && window.verticalPaneWidthsDp.size == 2) {
            NavigationPlacement.Bottom
        } else if (window.usableWidthDp >= RailMinimumWidthDp) {
            NavigationPlacement.Rail
        } else {
            NavigationPlacement.Bottom
        }

    fun layout(window: WindowCharacteristics): AdaptiveLayout = AdaptiveLayout(
        navigationPlacement = placement(window),
        isHeightConstrained = window.usableHeightDp < ConstrainedHeightDp,
        supportsListDetail = window.usableWidthDp >= ListDetailMinimumWindowWidthDp ||
            window.verticalPaneWidthsDp.let { panes ->
                panes.size == 2 && panes.all { it >= HingePaneMinimumWidthDp }
            },
        contentWidthClass = when {
            window.usableWidthDp >= ExpandedMinimumWidthDp -> ContentWidthClass.Expanded
            window.usableWidthDp >= RailMinimumWidthDp -> ContentWidthClass.Medium
            else -> ContentWidthClass.Compact
        },
    )

    fun playbackTracks(window: WindowCharacteristics): PlaybackTracksLayout {
        val pane = when {
            window.hasSeparatingVerticalHinge && window.verticalPaneWidthsDp.size == 2 -> {
                val (start, end) = window.verticalPaneWidthsDp
                if (start >= end) PlaybackSafePane.Start(start) else PlaybackSafePane.End(end)
            }
            window.hasSeparatingHorizontalHinge && window.horizontalPaneHeightsDp.size == 2 -> {
                val (top, bottom) = window.horizontalPaneHeightsDp
                if (top >= bottom) PlaybackSafePane.Top(top) else PlaybackSafePane.Bottom(bottom)
            }
            else -> PlaybackSafePane.WholeWindow
        }
        return PlaybackTracksLayout(
            presentation = if (pane == PlaybackSafePane.WholeWindow && window.usableWidthDp >= ExpandedMinimumWidthDp) {
                PlaybackTracksPresentation.AnchoredSide
            } else {
                PlaybackTracksPresentation.ModalBottom
            },
            safePane = pane,
        )
    }
}
