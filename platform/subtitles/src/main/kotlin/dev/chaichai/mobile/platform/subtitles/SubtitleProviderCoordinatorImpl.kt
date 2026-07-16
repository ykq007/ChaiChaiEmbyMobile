package dev.chaichai.mobile.platform.subtitles

import dev.chaichai.mobile.core.contracts.ExternalSubtitleActivation
import dev.chaichai.mobile.core.contracts.HomeScope
import dev.chaichai.mobile.core.contracts.MediaIdentity
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import dev.chaichai.mobile.core.contracts.PlaybackTrack
import dev.chaichai.mobile.core.contracts.PlaybackTrackType
import dev.chaichai.mobile.core.contracts.ProxyCredentials
import dev.chaichai.mobile.core.contracts.SubtitleCandidate
import dev.chaichai.mobile.core.contracts.SubtitleProviderController
import dev.chaichai.mobile.core.contracts.SubtitleProviderOutcome
import dev.chaichai.mobile.core.contracts.SubtitleProviderStatus
import dev.chaichai.mobile.core.contracts.SubtitleSearchHints
import dev.chaichai.mobile.core.contracts.SubtitleSearchState
import dev.chaichai.mobile.core.contracts.TrackDelivery
import dev.chaichai.mobile.platform.proxy.ProxyCredentialVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException

/**
 * The real [SubtitleProviderController]. Fans out a search across every enabled provider (per-provider
 * status, partial results survive one provider failing), and is the single place that CONTAINS every
 * provider and download failure: nothing thrown by a provider client ever reaches playback. On select
 * it downloads the subtitle, validates it is a supported format, stores it locally, and activates it as
 * the current External subtitle via [PlaybackCoordinator.addExternalSubtitle] WITHOUT restarting
 * playback — and if anything fails the prior subtitle stays current (AC3/AC4).
 *
 * Font-file upload and cross-device sync are OUT of scope and intentionally not implemented.
 */
class SubtitleProviderCoordinatorImpl(
    private val scope: CoroutineScope,
    private val client: SubtitleProviderClient,
    private val configStore: SubtitleProviderConfigStore,
    private val downloadStore: SubtitleDownloadStore,
    private val playback: PlaybackCoordinator,
    private val accountVault: ProxyCredentialVault,
) : SubtitleProviderController {

    private val mutableState = MutableStateFlow<SubtitleSearchState>(SubtitleSearchState.Idle)
    override val searchState: StateFlow<SubtitleSearchState> = mutableState

    private var searchJob: Job? = null
    private var activateJob: Job? = null
    /** candidateId -> (provider, candidate) from the most recent search, for download. */
    private var candidateIndex: Map<String, Pair<SubtitleProvider, SubtitleCandidate>> = emptyMap()

    override fun searchForCurrentMedia(identity: MediaIdentity, scope: HomeScope, hints: SubtitleSearchHints) {
        val providers = configStore.load().providers.filter { it.enabled }
        searchJob?.cancel()
        activateJob?.cancel()
        candidateIndex = emptyMap()
        if (providers.isEmpty()) {
            mutableState.value = SubtitleSearchState.Failure(
                "Add a subtitle provider in settings to search online.",
                identity,
            )
            return
        }
        mutableState.value = SubtitleSearchState.Searching(identity)
        val query = SubtitleProviderQuery(
            title = hints.title,
            language = hints.language,
            season = hints.season,
            episode = hints.episode,
            runtimeTicks = hints.runtimeTicks,
        )
        val job = this.scope.launch {
            val candidates = mutableListOf<SubtitleCandidate>()
            val index = mutableMapOf<String, Pair<SubtitleProvider, SubtitleCandidate>>()
            val statuses = mutableListOf<SubtitleProviderStatus>()
            for (provider in providers) {
                val account = accountVault.load(SubtitleProviderHttpClients.accountCredentialKey(provider.id))
                val result = runCatching { client.search(provider, query, account) }
                val outcome = result.fold(
                    onSuccess = { found ->
                        for (candidate in found) {
                            index[candidate.id] = provider to candidate
                            candidates += candidate
                        }
                        if (found.isEmpty()) SubtitleProviderOutcome.Empty else SubtitleProviderOutcome.Ok
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        classify(error)
                    },
                )
                statuses += SubtitleProviderStatus(provider.id, provider.name, outcome)
            }
            candidateIndex = index
            mutableState.value = SubtitleSearchState.Results(
                identity = identity,
                candidates = candidates,
                providerStatuses = statuses,
            )
        }
        searchJob = job
    }

    override fun selectCandidate(candidateId: String) {
        val results = mutableState.value as? SubtitleSearchState.Results ?: return
        val (provider, candidate) = candidateIndex[candidateId] ?: return
        val mime = SubtitleFormats.mimeTypeOf(candidate.format)
        if (mime == null) {
            // Incompatible subtitle: never handed to the player; the prior track stays current.
            mutableState.value = results.copy(
                activatingCandidateId = null,
                activationError = "That subtitle format (${candidate.format}) isn't supported. " +
                    "Your current subtitle is still active.",
            )
            return
        }
        activateJob?.cancel()
        mutableState.value = results.copy(activatingCandidateId = candidateId, activationError = null)
        val job = scope.launch {
            val account = accountVault.load(SubtitleProviderHttpClients.accountCredentialKey(provider.id))
            val bytes = runCatching { client.download(provider, candidate, account) }.getOrElse { error ->
                if (error is CancellationException) throw error
                failActivation(candidateId, downloadFailureMessage(error))
                return@launch
            }
            val localRef = runCatching {
                downloadStore.store(candidate.id, SubtitleFormats.fileExtensionOf(candidate.format), bytes)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                failActivation(candidateId, "The downloaded subtitle couldn't be saved. Your current subtitle is still active.")
                return@launch
            }
            playback.addExternalSubtitle(
                ExternalSubtitleActivation(
                    track = PlaybackTrack(
                        index = ExternalSubtitleActivation.SubtitleStreamIndex,
                        type = PlaybackTrackType.Subtitle,
                        language = candidate.language,
                        title = subtitleTitle(candidate),
                        delivery = TrackDelivery.External,
                        isCurrent = true,
                    ),
                    localRef = localRef,
                    mimeType = mime,
                ),
            )
            val current = mutableState.value as? SubtitleSearchState.Results ?: return@launch
            mutableState.value = current.copy(
                activatingCandidateId = null,
                activatedCandidateId = candidateId,
                activationError = null,
            )
        }
        activateJob = job
    }

    override fun cancelSearch() {
        searchJob?.cancel()
        activateJob?.cancel()
        searchJob = null
        activateJob = null
        candidateIndex = emptyMap()
        mutableState.value = SubtitleSearchState.Idle
    }

    private fun failActivation(candidateId: String, message: String) {
        val current = mutableState.value as? SubtitleSearchState.Results ?: return
        mutableState.value = current.copy(
            activatingCandidateId = null,
            activationError = message,
        )
    }

    private fun subtitleTitle(candidate: SubtitleCandidate): String = buildList {
        candidate.languageLabel?.let(::add)
        candidate.releaseName?.let(::add)
        add(candidate.providerName)
    }.joinToString(" · ")

    private fun downloadFailureMessage(error: Throwable): String = when {
        error is SubtitleProviderAuthException ->
            "That provider rejected the sign-in. Your current subtitle is still active."
        error.hasCause<SocketTimeoutException>() ->
            "That subtitle download timed out. Your current subtitle is still active."
        else -> "That subtitle couldn't be downloaded. Your current subtitle is still active."
    }

    private fun classify(error: Throwable): SubtitleProviderOutcome = when {
        error is SubtitleProviderAuthException -> SubtitleProviderOutcome.AuthFailed
        error.hasCause<SocketTimeoutException>() -> SubtitleProviderOutcome.TimedOut
        else -> SubtitleProviderOutcome.Failed
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
        generateSequence(this) { it.cause }.any { it is T }
}
