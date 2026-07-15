package dev.chaichai.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class TopLevelNavigationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun first_launch_requires_server_setup_before_top_level_navigation() {
        composeRule.onNodeWithText("Connect to Emby").assertIsDisplayed()
        composeRule.onNodeWithText("Server Address").assertIsDisplayed()
    }
}
