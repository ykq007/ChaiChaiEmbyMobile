package dev.chaichai.mobile.platform.adaptive

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveNavigationPolicyTest {
    @Test
    fun `compact usable width uses bottom navigation`() {
        assertEquals(
            NavigationPlacement.Bottom,
            AdaptiveNavigationPolicy.placement(
                WindowCharacteristics(usableWidthDp = 599, usableHeightDp = 700),
            ),
        )
    }

    @Test
    fun `eligible width uses navigation rail`() {
        assertEquals(
            NavigationPlacement.Rail,
            AdaptiveNavigationPolicy.placement(
                WindowCharacteristics(usableWidthDp = 600, usableHeightDp = 700),
            ),
        )
    }

    @Test
    fun `wide unobstructed pane uses rail when a separating hinge is present`() {
        assertEquals(
            NavigationPlacement.Rail,
            AdaptiveNavigationPolicy.placement(
                WindowCharacteristics(
                    usableWidthDp = 840,
                    usableHeightDp = 700,
                    hasSeparatingVerticalHinge = true,
                ),
            ),
        )
    }

    @Test
    fun `constrained height is reported independently of width`() {
        val layout = AdaptiveNavigationPolicy.layout(
            WindowCharacteristics(usableWidthDp = 840, usableHeightDp = 479),
        )

        assertEquals(NavigationPlacement.Rail, layout.navigationPlacement)
        assertEquals(true, layout.isHeightConstrained)
        assertEquals(ContentWidthClass.Expanded, layout.contentWidthClass)
    }

    @Test
    fun `content density breakpoints are owned by adaptive policy`() {
        assertEquals(ContentWidthClass.Compact, AdaptiveNavigationPolicy.layout(WindowCharacteristics(599, 700)).contentWidthClass)
        assertEquals(ContentWidthClass.Medium, AdaptiveNavigationPolicy.layout(WindowCharacteristics(600, 700)).contentWidthClass)
        assertEquals(ContentWidthClass.Expanded, AdaptiveNavigationPolicy.layout(WindowCharacteristics(840, 700)).contentWidthClass)
    }

    @Test
    fun `list detail waits for 840dp after rail space`() {
        assertEquals(false, AdaptiveNavigationPolicy.layout(WindowCharacteristics(919, 700)).supportsListDetail)
        assertEquals(true, AdaptiveNavigationPolicy.layout(WindowCharacteristics(920, 700)).supportsListDetail)
    }

    @Test
    fun `two hinge separated panes independently enable list detail`() {
        val eligible = WindowCharacteristics(
            usableWidthDp = 420,
            usableHeightDp = 700,
            hasSeparatingVerticalHinge = true,
            verticalPaneWidthsDp = listOf(400, 420),
        )
        val narrowDetail = eligible.copy(verticalPaneWidthsDp = listOf(400, 359))

        assertEquals(true, AdaptiveNavigationPolicy.layout(eligible).supportsListDetail)
        assertEquals(false, AdaptiveNavigationPolicy.layout(narrowDetail).supportsListDetail)
        assertEquals(NavigationPlacement.Bottom, AdaptiveNavigationPolicy.layout(eligible).navigationPlacement)
    }
}
