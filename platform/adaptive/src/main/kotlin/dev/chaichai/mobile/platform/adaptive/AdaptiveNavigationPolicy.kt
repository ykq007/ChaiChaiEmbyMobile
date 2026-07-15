package dev.chaichai.mobile.platform.adaptive

data class WindowCharacteristics(
    val usableWidthDp: Int,
    val usableHeightDp: Int,
    val hasSeparatingVerticalHinge: Boolean = false,
)

enum class NavigationPlacement { Bottom, Rail }
enum class ContentWidthClass { Compact, Medium, Expanded }

data class AdaptiveLayout(
    val navigationPlacement: NavigationPlacement,
    val isHeightConstrained: Boolean,
    val contentWidthClass: ContentWidthClass,
)

object AdaptiveNavigationPolicy {
    private const val RailMinimumWidthDp = 600
    private const val ExpandedMinimumWidthDp = 840
    private const val ConstrainedHeightDp = 480

    fun placement(window: WindowCharacteristics): NavigationPlacement =
        if (window.usableWidthDp >= RailMinimumWidthDp) {
            NavigationPlacement.Rail
        } else {
            NavigationPlacement.Bottom
        }

    fun layout(window: WindowCharacteristics): AdaptiveLayout = AdaptiveLayout(
        navigationPlacement = placement(window),
        isHeightConstrained = window.usableHeightDp < ConstrainedHeightDp,
        contentWidthClass = when {
            window.usableWidthDp >= ExpandedMinimumWidthDp -> ContentWidthClass.Expanded
            window.usableWidthDp >= RailMinimumWidthDp -> ContentWidthClass.Medium
            else -> ContentWidthClass.Compact
        },
    )
}
