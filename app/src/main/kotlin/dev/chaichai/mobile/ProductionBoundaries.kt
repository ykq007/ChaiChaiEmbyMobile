package dev.chaichai.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.ServerSetupState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dev.chaichai.mobile.platform.server.AggregatedSearchGateway
import dev.chaichai.mobile.platform.server.AuthenticatedEmbyGateway
import dev.chaichai.mobile.platform.server.AuthorityScopedHttpClients
import dev.chaichai.mobile.platform.server.EmbyAuthenticator
import dev.chaichai.mobile.platform.server.SingleSessionVault
import dev.chaichai.mobile.platform.server.EmbyProbe
import dev.chaichai.mobile.platform.server.KeystoreSessionVault
import dev.chaichai.mobile.platform.server.ServerSetupCoordinator
import dev.chaichai.mobile.platform.server.EmbyPlaybackGateway
import dev.chaichai.mobile.platform.server.DurableProgressGateway
import dev.chaichai.mobile.platform.server.EmbyProgressRemote
import dev.chaichai.mobile.platform.server.ProgressSyncManager
import dev.chaichai.mobile.platform.server.WorkManagerProgressRetryScheduler
import dev.chaichai.mobile.platform.server.createRoomProgressOutbox
import dev.chaichai.mobile.platform.server.AccountManager
import dev.chaichai.mobile.platform.server.ServerDirectoryManager
import dev.chaichai.mobile.platform.server.ServerProxyManager
import dev.chaichai.mobile.platform.server.ServerProxyStore
import dev.chaichai.mobile.platform.server.ProxyConnectionTester
import dev.chaichai.mobile.platform.server.SharedPreferencesProxyPersistence
import dev.chaichai.mobile.platform.server.VaultBackedProxySelector
import dev.chaichai.mobile.platform.server.ServerRegistryStore
import dev.chaichai.mobile.platform.server.SharedPreferencesRegistryPersistence
import dev.chaichai.mobile.platform.server.ServerPrivateDataCleaner
import dev.chaichai.mobile.platform.server.SharedPreferencesPlaybackPreferences
import dev.chaichai.mobile.platform.danmaku.DanmakuControllerImpl
import dev.chaichai.mobile.platform.danmaku.DanmakuEndpointHttpClients
import dev.chaichai.mobile.platform.danmaku.DanmakuEndpointManager
import dev.chaichai.mobile.platform.danmaku.DanmakuEndpointTester
import dev.chaichai.mobile.platform.danmaku.OkHttpDanmakuEndpointClient
import dev.chaichai.mobile.platform.danmaku.SharedPreferencesDanmakuConfigStore
import dev.chaichai.mobile.platform.subtitles.FileSubtitleDownloadStore
import dev.chaichai.mobile.platform.subtitles.OkHttpSubtitleProviderClient
import dev.chaichai.mobile.platform.subtitles.SharedPreferencesSubtitleProviderConfigStore
import dev.chaichai.mobile.platform.subtitles.SubtitleProviderCoordinatorImpl
import dev.chaichai.mobile.platform.subtitles.SubtitleProviderHttpClients
import dev.chaichai.mobile.platform.subtitles.SubtitleProviderManager
import dev.chaichai.mobile.platform.subtitles.SubtitleProviderTester
import dev.chaichai.mobile.platform.proxy.KeystoreProxyCredentialVault
import java.io.File
import dev.chaichai.mobile.platform.playback.Media3ServicePlaybackEngine
import dev.chaichai.mobile.platform.playback.PlaybackCoordinatorImpl
import dev.chaichai.mobile.platform.playback.PlaybackDiagnosticsImpl
import dev.chaichai.mobile.platform.playback.androidPlaybackCapabilities
import dev.chaichai.mobile.platform.server.createRoomHomeCache
import dev.chaichai.mobile.platform.server.createRoomMovieCache
import dev.chaichai.mobile.platform.server.createRoomSeriesCache
import dev.chaichai.mobile.platform.server.createRoomSearchCache
import java.util.UUID
import java.time.Instant
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ProductionBoundariesModule {
    @Provides
    @Singleton
    fun provideAppBoundaries(@ApplicationContext context: Context): AppBoundaries {
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preferences = context.getSharedPreferences("mobile_client_identity", Context.MODE_PRIVATE)
        val deviceId = preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("device_id", it).apply()
        }
        val vault = KeystoreSessionVault(context)
        val homeCache = createRoomHomeCache(context)
        val movieCache = createRoomMovieCache(context)
        val seriesCache = createRoomSeriesCache(context)
        val searchCache = createRoomSearchCache(context)
        // Proxy Routing (#30): non-secret config in a versioned scoped-JSON store, credentials in a
        // Keystore-protected vault. A single VaultBackedProxySelector feeds the one shared
        // AuthorityScopedHttpClients, so API, artwork, playback and subtitle traffic for a server all
        // route identically. Certificate Bypass stays a separate per-authority decision inside the
        // clients and is never touched here.
        val proxyStore = ServerProxyStore(
            SharedPreferencesProxyPersistence(context),
            KeystoreProxyCredentialVault(context),
        )
        val httpClients = AuthorityScopedHttpClients(VaultBackedProxySelector(vault, proxyStore))
        val gateway = AuthenticatedEmbyGateway(
            vault,
            clients = httpClients,
            homeCache = homeCache,
            movieCache = movieCache,
            seriesCache = seriesCache,
            searchCache = searchCache,
            deviceId = deviceId,
        )
        // Aggregated Search (#29): fan out search across every configured server while Home,
        // libraries, playback and details stay bound to whichever server is active. Each
        // per-server fetch reuses the exact single-server search path via a throwaway gateway
        // pinned to that one stored session, so a switched/removed server can never leak into a
        // still-running fan-out.
        val aggregatedGateway = AggregatedSearchGateway(
            delegate = gateway,
            vault = vault,
            gatewayFactory = { session ->
                AuthenticatedEmbyGateway(
                    SingleSessionVault(session),
                    clients = httpClients,
                    homeCache = homeCache,
                    movieCache = movieCache,
                    seriesCache = seriesCache,
                    searchCache = searchCache,
                    deviceId = deviceId,
                )
            },
        )
        val serverSetup = ServerSetupCoordinator(
            applicationScope,
            EmbyProbe(),
            EmbyAuthenticator(),
            vault,
            deviceId,
            gateway,
        )
        gateway.onAuthenticationExpired = serverSetup::authenticationExpired
        val clock = AppClock { Instant.now() }
        val connectivity = AndroidConnectivityMonitor(context)
        val progress = ProgressSyncManager(
            createRoomProgressOutbox(context),
            EmbyProgressRemote(vault, deviceId),
            clock,
            connectivity,
            WorkManagerProgressRetryScheduler(context),
            applicationScope,
        )
        // Danmaku endpoint routing (#31): the endpoint client, controller and management boundary all
        // share ONE config store so a routing change made in Settings is seen by live matching. The
        // per-endpoint proxy credential vault is a SEPARATE Keystore namespace from the Emby server
        // proxy vault, so danmaku secrets and Emby secrets never mix. This danmaku path builds its own
        // clients via DanmakuEndpointHttpClients and NEVER touches the Emby AuthorityScopedHttpClients
        // or its Certificate Bypass — a Server Address's Certificate Bypass cannot reach a danmaku
        // endpoint. Changing an endpoint's route reloads only that endpoint's danmaku; playback is
        // never interrupted.
        val danmakuConfigStore = SharedPreferencesDanmakuConfigStore(context)
        val danmakuCredentialVault = KeystoreProxyCredentialVault(
            context,
            preferencesName = "danmaku_proxy_credentials",
            keyAlias = "chai_chai_danmaku_proxy_key",
            entryPrefix = "danmaku_proxy_",
        )
        val danmakuClients = DanmakuEndpointHttpClients(danmakuCredentialVault)
        val danmakuController = DanmakuControllerImpl(
            applicationScope,
            OkHttpDanmakuEndpointClient(danmakuClients),
            danmakuConfigStore,
        )
        val danmakuEndpointManager = DanmakuEndpointManager(
            configStore = danmakuConfigStore,
            vault = danmakuCredentialVault,
            tester = DanmakuEndpointTester(danmakuClients),
            onConfigChanged = danmakuController::onEndpointsChanged,
        )
        val playbackEngine = Media3ServicePlaybackEngine(context)
        val playbackCapabilities = androidPlaybackCapabilities()
        val playbackPreferences = SharedPreferencesPlaybackPreferences(context)
        val playbackCoordinator = PlaybackCoordinatorImpl(
            applicationScope,
            DurableProgressGateway(
                EmbyPlaybackGateway(vault, clients = httpClients, deviceId = deviceId),
                progress,
                clock,
            ),
            playbackEngine,
            playbackCapabilities,
            preferences = playbackPreferences,
        )
        // Playback Polish diagnostics (#35): reads the SAME capabilities/engine/preferences the
        // coordinator negotiated and applied with — no separate probing path that could drift from
        // what's actually active — and observes the coordinator's own state to remember the most
        // recent failure KIND. See PlaybackDiagnosticsImpl's doc comment for exactly what is/isn't
        // included; assembly here never touches the gateway, so no server token or media URL is ever
        // in scope.
        val playbackDiagnostics = PlaybackDiagnosticsImpl(
            applicationScope,
            playbackCoordinator.state,
            playbackCapabilities,
            playbackEngine,
            playbackPreferences,
        )
        // Subtitle Expansion (#32): a completely separate provider path that reuses platform:proxy for
        // routing and a subtitle-namespaced Keystore vault for BOTH provider account credentials and
        // proxy credentials. Like the danmaku path it never touches the Emby AuthorityScopedHttpClients
        // or its Certificate Bypass (this module does not depend on platform:server). Selecting a
        // candidate downloads it and hands it to the SAME playback coordinator, which side-loads it as
        // the current External subtitle without restarting playback or losing position/paused state.
        val subtitleConfigStore = SharedPreferencesSubtitleProviderConfigStore(context)
        val subtitleVault = KeystoreProxyCredentialVault(
            context,
            preferencesName = "subtitle_provider_credentials",
            keyAlias = "chai_chai_subtitle_provider_key",
            entryPrefix = "subtitle_provider_",
        )
        val subtitleClients = SubtitleProviderHttpClients(subtitleVault)
        val subtitleController = SubtitleProviderCoordinatorImpl(
            scope = applicationScope,
            client = OkHttpSubtitleProviderClient(subtitleClients),
            configStore = subtitleConfigStore,
            downloadStore = FileSubtitleDownloadStore(File(context.cacheDir, "subtitles")),
            playback = playbackCoordinator,
            accountVault = subtitleVault,
        )
        val subtitleManager = SubtitleProviderManager(
            configStore = subtitleConfigStore,
            vault = subtitleVault,
            tester = SubtitleProviderTester(subtitleClients),
        )
        val privateDataCleaner = ServerPrivateDataCleaner(homeCache, movieCache, seriesCache, searchCache)
        val serverDirectory = ServerDirectoryManager(
            scope = applicationScope,
            store = ServerRegistryStore(SharedPreferencesRegistryPersistence(context)),
            vault = vault,
            progress = progress,
            privateData = privateDataCleaner,
            // Switching re-points the vault's active scope, then resets the shared gateway so
            // Home/library/search reload against the newly active server without a process restart.
            onActiveRebind = {
                gateway.setConnected(false)
                gateway.setConnected(vault.restore() != null)
            },
            onBeginAddServer = serverSetup::beginAddServer,
        )
        applicationScope.launch {
            serverSetup.state.collect { setupState ->
                val authenticated = setupState is ServerSetupState.Authenticated
                gateway.setConnected(authenticated)
                // First server, an added server, and re-authentication all converge here: keep the
                // registry's active entry aligned with the freshly authenticated vault session.
                if (authenticated) serverDirectory.registerActiveSession()
            }
        }
        return AppBoundaries(
            gateway = aggregatedGateway,
            playback = playbackCoordinator,
            clock = clock,
            connectivity = connectivity,
            serverSetup = serverSetup,
            account = AccountManager(
                applicationScope, vault, progress,
                privateDataCleaner,
                serverSetup::signedOut,
            ),
            danmaku = danmakuController,
            serverDirectory = serverDirectory,
            serverProxy = ServerProxyManager(
                proxyStore,
                ProxyConnectionTester(vault, proxyStore, httpClients),
            ),
            danmakuEndpoints = danmakuEndpointManager,
            subtitleProvider = subtitleController,
            subtitleProviders = subtitleManager,
            playbackDiagnostics = playbackDiagnostics,
        )
    }
}

private class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val mutableOnline = MutableStateFlow(manager.isOnline())
    override val isOnline = mutableOnline

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { mutableOnline.value = manager.isOnline() }
            override fun onLost(network: Network) { mutableOnline.value = manager.isOnline() }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                mutableOnline.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        })
    }

    private fun ConnectivityManager.isOnline(): Boolean = getNetworkCapabilities(activeNetwork)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}
