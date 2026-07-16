package dev.chaichai.mobile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.chaichai.mobile.core.contracts.ExternalSubtitleActivation
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.core.contracts.SubtitleCandidate
import dev.chaichai.mobile.core.contracts.SubtitleProviderController
import dev.chaichai.mobile.core.contracts.SubtitleProviderOutcome
import dev.chaichai.mobile.core.contracts.SubtitleProviderStatus
import dev.chaichai.mobile.core.contracts.SubtitleSearchHints
import dev.chaichai.mobile.core.contracts.SubtitleSearchState
import dev.chaichai.mobile.design.system.ChaiChaiTheme
import dev.chaichai.mobile.feature.playback.PlaybackHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * App-level acceptance for the in-player "Find subtitles online" surface (Subtitle Expansion, #32),
 * using INDEPENDENTLY AUTHORED fakes (no platform code). Verifies searching by the current media shows
 * candidates with provenance and per-provider status, selecting activates a subtitle without leaving
 * playback, and a provider/download failure shows an actionable message while playback stays intact and
 * the prior subtitle survives.
 */
class SubtitleProviderPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun activePlayback() = object : NoOpPlaybackCoordinator() {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(
            PlaybackState.Active(
                identity = MediaIdentity("server", "movie"),
                title = "Arrival",
                positionTicks = 20_000_000,
                runtimeTicks = 7_200_000_000,
                isPaused = false,
                scope = HomeScope("server", "user"),
            ),
        )
    }

    private fun openSearchPanel() {
        compose.onNodeWithContentDescription("Tracks").performClick()
        compose.onNodeWithTag("find-subtitles-online").performClick()
    }

    @Test
    fun searching_shows_candidates_with_provenance_and_per_provider_status() {
        val controller = FakeSubtitleProvider().apply {
            resultsOnSearch = SubtitleSearchState.Results(
                identity = MediaIdentity("server", "movie"),
                candidates = listOf(
                    candidate("open", "1", "English", "Arrival.2016.1080p"),
                    candidate("subdb", "2", "Spanish", "Arrival.2016.720p"),
                ),
                providerStatuses = listOf(
                    SubtitleProviderStatus("open", "OpenSubs", SubtitleProviderOutcome.Ok),
                    SubtitleProviderStatus("subdb", "SubDB", SubtitleProviderOutcome.Failed),
                ),
            )
        }
        compose.setContent {
            ChaiChaiTheme(reducedMotion = true) { PlaybackHost(activePlayback(), subtitleProvider = controller) }
        }
        openSearchPanel()

        compose.onNodeWithTag("subtitle-search-panel").assertExists()
        assertEquals(MediaIdentity("server", "movie"), controller.searchedIdentity)
        // Provenance + per-provider status are surfaced.
        compose.onNodeWithTag("subtitle-search-panel")
            .performScrollToNode(hasTestTag("subtitle-candidate-open:1"))
        compose.onNodeWithText("via OpenSubs", substring = true).assertExists()
        compose.onNodeWithTag("subtitle-provider-statuses").assertExists()
    }

    @Test
    fun selecting_a_candidate_activates_it_without_leaving_playback() {
        val controller = FakeSubtitleProvider().apply {
            resultsOnSearch = SubtitleSearchState.Results(
                identity = MediaIdentity("server", "movie"),
                candidates = listOf(candidate("open", "1", "English", "Arrival.2016.1080p")),
                providerStatuses = listOf(SubtitleProviderStatus("open", "OpenSubs", SubtitleProviderOutcome.Ok)),
            )
            activateOutcome = ActivateOutcome.Success
        }
        compose.setContent {
            ChaiChaiTheme(reducedMotion = true) { PlaybackHost(activePlayback(), subtitleProvider = controller) }
        }
        openSearchPanel()
        compose.onNodeWithTag("subtitle-search-panel")
            .performScrollToNode(hasTestTag("subtitle-candidate-open:1"))
        compose.onNodeWithTag("subtitle-candidate-open:1").performClick()

        assertEquals("open:1", controller.selectedCandidateId)
        // Still in playback: the transport control remains.
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithText("Current", substring = true).assertExists()
    }

    @Test
    fun a_provider_failure_shows_an_actionable_message_with_playback_intact() {
        val controller = FakeSubtitleProvider().apply {
            resultsOnSearch = SubtitleSearchState.Results(
                identity = MediaIdentity("server", "movie"),
                candidates = listOf(candidate("open", "1", "English", "Arrival.2016.1080p")),
                providerStatuses = listOf(SubtitleProviderStatus("open", "OpenSubs", SubtitleProviderOutcome.Ok)),
            )
            activateOutcome = ActivateOutcome.Failure("That subtitle couldn't be downloaded. Your current subtitle is still active.")
        }
        compose.setContent {
            ChaiChaiTheme(reducedMotion = true) { PlaybackHost(activePlayback(), subtitleProvider = controller) }
        }
        openSearchPanel()
        compose.onNodeWithTag("subtitle-search-panel")
            .performScrollToNode(hasTestTag("subtitle-candidate-open:1"))
        compose.onNodeWithTag("subtitle-candidate-open:1").performClick()

        compose.onNodeWithTag("subtitle-activation-error").assertIsDisplayed()
        // Contained: playback is untouched.
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithContentDescription("Forward 30 seconds").assertExists()
    }

    @Test
    fun the_search_panel_survives_an_activity_restart() {
        val controller = FakeSubtitleProvider().apply {
            resultsOnSearch = SubtitleSearchState.Results(
                identity = MediaIdentity("server", "movie"),
                candidates = listOf(candidate("open", "1", "English", "Arrival.2016.1080p")),
                providerStatuses = listOf(SubtitleProviderStatus("open", "OpenSubs", SubtitleProviderOutcome.Ok)),
            )
        }
        compose.setContent {
            ChaiChaiTheme(reducedMotion = true) { PlaybackHost(activePlayback(), subtitleProvider = controller) }
        }
        openSearchPanel()
        compose.onNodeWithTag("subtitle-search-panel").assertExists()

        compose.activityRule.scenario.recreate()

        // The panel is restored (rememberSaveable) and re-populates with candidates.
        compose.onNodeWithTag("subtitle-search-panel").assertExists()
        compose.onNodeWithTag("subtitle-search-panel")
            .performScrollToNode(hasTestTag("subtitle-candidate-open:1"))
        compose.onNodeWithText("via OpenSubs", substring = true).assertExists()
    }

    private fun candidate(providerId: String, id: String, language: String, release: String) = SubtitleCandidate(
        id = "$providerId:$id",
        providerId = providerId,
        providerName = if (providerId == "open") "OpenSubs" else "SubDB",
        language = language.take(2).lowercase(),
        languageLabel = language,
        releaseName = release,
        matchInfo = "hash match",
        format = "srt",
        downloadHint = "/download/$id",
    )

    private sealed interface ActivateOutcome {
        data object Success : ActivateOutcome
        data class Failure(val message: String) : ActivateOutcome
    }

    private class FakeSubtitleProvider : SubtitleProviderController {
        private val mutable = MutableStateFlow<SubtitleSearchState>(SubtitleSearchState.Idle)
        override val searchState: StateFlow<SubtitleSearchState> = mutable

        var resultsOnSearch: SubtitleSearchState.Results? = null
        var activateOutcome: ActivateOutcome? = null
        var searchedIdentity: MediaIdentity? = null
        var selectedCandidateId: String? = null

        override fun searchForCurrentMedia(identity: MediaIdentity, scope: HomeScope, hints: SubtitleSearchHints) {
            searchedIdentity = identity
            mutable.value = resultsOnSearch ?: SubtitleSearchState.Searching(identity)
        }

        override fun selectCandidate(candidateId: String) {
            selectedCandidateId = candidateId
            val results = mutable.value as? SubtitleSearchState.Results ?: return
            mutable.value = when (val outcome = activateOutcome) {
                is ActivateOutcome.Success -> results.copy(activatingCandidateId = null, activatedCandidateId = candidateId, activationError = null)
                is ActivateOutcome.Failure -> results.copy(activatingCandidateId = null, activationError = outcome.message)
                null -> results.copy(activatingCandidateId = candidateId)
            }
        }

        override fun cancelSearch() {
            mutable.value = SubtitleSearchState.Idle
        }
    }
}
