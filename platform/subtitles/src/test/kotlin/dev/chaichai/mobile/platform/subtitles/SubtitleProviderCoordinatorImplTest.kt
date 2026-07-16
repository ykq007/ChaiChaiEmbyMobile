package dev.chaichai.mobile.platform.subtitles

import dev.chaichai.mobile.core.contracts.ExternalSubtitleActivation
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.PlaybackState
import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.SubtitleCandidate
import dev.chaichai.mobile.core.contracts.SubtitleProviderOutcome
import dev.chaichai.mobile.core.contracts.SubtitleSearchHints
import dev.chaichai.mobile.core.contracts.SubtitleSearchState
import dev.chaichai.mobile.core.contracts.TrackDelivery
import dev.chaichai.mobile.platform.proxy.InMemoryProxyCredentialVault
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class SubtitleProviderCoordinatorImplTest {

    private val identity = MediaIdentity("server", "movie")
    private val scope = HomeScope("server", "user")

    private fun candidate(providerId: String, id: String, format: String = "srt") = SubtitleCandidate(
        id = "$providerId:$id", providerId = providerId, providerName = providerId.uppercase(),
        language = "en", languageLabel = "English", releaseName = "Rel-$id", format = format,
        downloadHint = "/download/$id",
    )

    private fun provider(id: String) = SubtitleProvider(id = id, name = id.uppercase(), baseUrl = "https://$id.example")

    @Test
    fun mixed_providers_yield_partial_results_with_per_provider_status() = runTest {
        val client = FakeClient().apply {
            searchResults["a"] = { listOf(candidate("a", "1")) }
            searchResults["b"] = { throw SocketTimeoutException("slow") }
            searchResults["c"] = { emptyList() }
        }
        val store = InMemorySubtitleProviderConfigStore(
            SubtitleProviderStoreState(listOf(provider("a"), provider("b"), provider("c"))),
        )
        val playback = FakePlayback()
        val coordinator = SubtitleProviderCoordinatorImpl(
            this, client, store, InMemoryDownloadStore(), playback, InMemoryProxyCredentialVault(),
        )

        coordinator.searchForCurrentMedia(identity, scope, SubtitleSearchHints("Arrival"))
        advanceUntilIdle()

        val results = coordinator.searchState.value as SubtitleSearchState.Results
        assertEquals(1, results.candidates.size) // only A returned; B failed, C empty — partial results survive
        val statuses = results.providerStatuses.associate { it.providerId to it.outcome }
        assertEquals(SubtitleProviderOutcome.Ok, statuses["a"])
        assertEquals(SubtitleProviderOutcome.TimedOut, statuses["b"])
        assertEquals(SubtitleProviderOutcome.Empty, statuses["c"])
    }

    @Test
    fun malformed_provider_results_are_contained_never_thrown() = runTest {
        val client = FakeClient().apply { searchResults["a"] = { throw IllegalStateException("bad json") } }
        val store = InMemorySubtitleProviderConfigStore(SubtitleProviderStoreState(listOf(provider("a"))))
        val coordinator = SubtitleProviderCoordinatorImpl(
            this, client, store, InMemoryDownloadStore(), FakePlayback(), InMemoryProxyCredentialVault(),
        )
        coordinator.searchForCurrentMedia(identity, scope, SubtitleSearchHints("x"))
        advanceUntilIdle()
        val results = coordinator.searchState.value as SubtitleSearchState.Results
        assertEquals(SubtitleProviderOutcome.Failed, results.providerStatuses.single().outcome)
        assertTrue(results.candidates.isEmpty())
    }

    @Test
    fun no_providers_configured_reports_actionable_failure() = runTest {
        val coordinator = SubtitleProviderCoordinatorImpl(
            this, FakeClient(), InMemorySubtitleProviderConfigStore(), InMemoryDownloadStore(),
            FakePlayback(), InMemoryProxyCredentialVault(),
        )
        coordinator.searchForCurrentMedia(identity, scope, SubtitleSearchHints("x"))
        advanceUntilIdle()
        assertTrue(coordinator.searchState.value is SubtitleSearchState.Failure)
    }

    @Test
    fun selecting_a_candidate_downloads_and_activates_it_as_the_current_external_subtitle() = runTest {
        val client = FakeClient().apply {
            searchResults["a"] = { listOf(candidate("a", "1")) }
            downloads["a:1"] = "WEBVTT\n".toByteArray()
        }
        val store = InMemorySubtitleProviderConfigStore(SubtitleProviderStoreState(listOf(provider("a"))))
        val playback = FakePlayback()
        val coordinator = SubtitleProviderCoordinatorImpl(
            this, client, store, InMemoryDownloadStore(), playback, InMemoryProxyCredentialVault(),
        )
        coordinator.searchForCurrentMedia(identity, scope, SubtitleSearchHints("x"))
        advanceUntilIdle()

        coordinator.selectCandidate("a:1")
        advanceUntilIdle()

        assertEquals(1, playback.activations.size)
        val activation = playback.activations.single()
        assertEquals(TrackDelivery.External, activation.track.delivery)
        assertEquals(ExternalSubtitleActivation.SubtitleStreamIndex, activation.track.index)
        assertEquals("application/x-subrip", activation.mimeType) // srt candidate
        val results = coordinator.searchState.value as SubtitleSearchState.Results
        assertEquals("a:1", results.activatedCandidateId)
        assertNull(results.activationError)
    }

    @Test
    fun incompatible_subtitle_leaves_the_prior_track_and_playback_intact() = runTest {
        val client = FakeClient().apply { searchResults["a"] = { listOf(candidate("a", "1", format = "pgs")) } }
        val store = InMemorySubtitleProviderConfigStore(SubtitleProviderStoreState(listOf(provider("a"))))
        val playback = FakePlayback()
        val coordinator = SubtitleProviderCoordinatorImpl(
            this, client, store, InMemoryDownloadStore(), playback, InMemoryProxyCredentialVault(),
        )
        coordinator.searchForCurrentMedia(identity, scope, SubtitleSearchHints("x"))
        advanceUntilIdle()

        coordinator.selectCandidate("a:1")
        advanceUntilIdle()

        // Never handed to the player; the prior subtitle stays current.
        assertTrue(playback.activations.isEmpty())
        val results = coordinator.searchState.value as SubtitleSearchState.Results
        assertTrue(results.activationError!!.contains("isn't supported"))
    }

    @Test
    fun download_failure_leaves_the_prior_track_and_playback_intact() = runTest {
        val client = FakeClient().apply {
            searchResults["a"] = { listOf(candidate("a", "1")) }
            downloadError["a:1"] = SubtitleProviderAuthException("nope")
        }
        val store = InMemorySubtitleProviderConfigStore(SubtitleProviderStoreState(listOf(provider("a"))))
        val playback = FakePlayback()
        val coordinator = SubtitleProviderCoordinatorImpl(
            this, client, store, InMemoryDownloadStore(), playback, InMemoryProxyCredentialVault(),
        )
        coordinator.searchForCurrentMedia(identity, scope, SubtitleSearchHints("x"))
        advanceUntilIdle()
        coordinator.selectCandidate("a:1")
        advanceUntilIdle()

        assertTrue(playback.activations.isEmpty())
        val results = coordinator.searchState.value as SubtitleSearchState.Results
        assertTrue(results.activationError!!.contains("current subtitle is still active"))
    }

    @Test
    fun replacement_selecting_a_then_b_activates_both_in_order() = runTest {
        val client = FakeClient().apply {
            searchResults["a"] = { listOf(candidate("a", "1"), candidate("a", "2")) }
            downloads["a:1"] = "one".toByteArray()
            downloads["a:2"] = "two".toByteArray()
        }
        val store = InMemorySubtitleProviderConfigStore(SubtitleProviderStoreState(listOf(provider("a"))))
        val playback = FakePlayback()
        val coordinator = SubtitleProviderCoordinatorImpl(
            this, client, store, InMemoryDownloadStore(), playback, InMemoryProxyCredentialVault(),
        )
        coordinator.searchForCurrentMedia(identity, scope, SubtitleSearchHints("x"))
        advanceUntilIdle()
        coordinator.selectCandidate("a:1")
        advanceUntilIdle()
        coordinator.selectCandidate("a:2")
        advanceUntilIdle()
        assertEquals(2, playback.activations.size)
    }

    @Test
    fun cancellation_prevents_superseded_results_from_landing() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = FakeClient().apply {
            searchResults["a"] = { gate.await(); listOf(candidate("a", "1")) }
        }
        val store = InMemorySubtitleProviderConfigStore(SubtitleProviderStoreState(listOf(provider("a"))))
        val coordinator = SubtitleProviderCoordinatorImpl(
            this, client, store, InMemoryDownloadStore(), FakePlayback(), InMemoryProxyCredentialVault(),
        )
        coordinator.searchForCurrentMedia(identity, scope, SubtitleSearchHints("x"))
        runCurrent()
        coordinator.cancelSearch()
        gate.complete(Unit)
        advanceUntilIdle()
        // The superseded search must not overwrite the cancelled (Idle) state.
        assertEquals(SubtitleSearchState.Idle, coordinator.searchState.value)
    }

    @Test
    fun account_credentials_are_read_from_the_vault_and_passed_to_the_client() = runTest {
        val vault = InMemoryProxyCredentialVault().apply {
            save(SubtitleProviderHttpClients.accountCredentialKey("a"), ProxyCredentials("user", "key"))
        }
        val client = FakeClient().apply { searchResults["a"] = { emptyList() } }
        val store = InMemorySubtitleProviderConfigStore(SubtitleProviderStoreState(listOf(provider("a"))))
        val coordinator = SubtitleProviderCoordinatorImpl(
            this, client, store, InMemoryDownloadStore(), FakePlayback(), vault,
        )
        coordinator.searchForCurrentMedia(identity, scope, SubtitleSearchHints("x"))
        advanceUntilIdle()
        assertEquals("user", client.lastAccount?.username)
    }

    private class FakeClient : SubtitleProviderClient {
        val searchResults = mutableMapOf<String, suspend () -> List<SubtitleCandidate>>()
        val downloads = mutableMapOf<String, ByteArray>()
        val downloadError = mutableMapOf<String, Throwable>()
        var lastAccount: ProxyCredentials? = null

        override suspend fun search(
            provider: SubtitleProvider,
            query: SubtitleProviderQuery,
            account: ProxyCredentials?,
        ): List<SubtitleCandidate> {
            lastAccount = account
            return (searchResults[provider.id] ?: { emptyList() }).invoke()
        }

        override suspend fun download(
            provider: SubtitleProvider,
            candidate: SubtitleCandidate,
            account: ProxyCredentials?,
        ): ByteArray {
            downloadError[candidate.id]?.let { throw it }
            return downloads[candidate.id] ?: error("no bytes")
        }
    }

    private class InMemoryDownloadStore : SubtitleDownloadStore {
        override fun store(candidateId: String, extension: String, bytes: ByteArray): String =
            "memory://$candidateId.$extension"
    }

    private class FakePlayback : PlaybackCoordinator {
        val activations = mutableListOf<ExternalSubtitleActivation>()
        override val isPlaying: StateFlow<Boolean> = MutableStateFlow(true)
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState.Idle)
        override fun submit(request: dev.chaichai.mobile.core.contracts.MediaPlaybackRequest) = Unit
        override fun toggleControls() = Unit
        override fun playPause() = Unit
        override fun seekBy(deltaTicks: Long) = Unit
        override fun seekTo(positionTicks: Long) = Unit
        override fun addExternalSubtitle(activation: ExternalSubtitleActivation) { activations += activation }
        override fun retry() = Unit
        override fun exit() = Unit
    }
}
