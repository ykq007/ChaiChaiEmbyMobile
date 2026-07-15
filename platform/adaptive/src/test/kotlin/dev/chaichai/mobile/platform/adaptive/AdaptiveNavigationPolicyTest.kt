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
    fun `separating vertical hinge keeps navigation and content together`() {
        assertEquals(
            NavigationPlacement.Bottom,
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
    }
}
