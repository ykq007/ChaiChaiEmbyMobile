package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class TopLevelNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun user_can_reach_every_top_level_destination_in_order() {
        composeRule.onNode(hasText("Home") and isHeading()).assertIsDisplayed()

        listOf("Libraries", "Search", "Settings").forEach { destination ->
            composeRule.onNodeWithText(destination).performClick()
            composeRule.onNode(hasText(destination) and isHeading()).assertIsDisplayed()
        }
    }
}
