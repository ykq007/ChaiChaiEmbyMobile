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
import dev.chaichai.mobile.platform.server.AuthenticatedEmbyGateway
import dev.chaichai.mobile.platform.server.EmbyAuthenticator
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
import dev.chaichai.mobile.platform.server.ServerPrivateDataCleaner
import dev.chaichai.mobile.platform.playback.Media3ServicePlaybackEngine
import dev.chaichai.mobile.platform.playback.PlaybackCoordinatorImpl
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
        val gateway = AuthenticatedEmbyGateway(
            vault,
            homeCache = homeCache,
            movieCache = movieCache,
            seriesCache = seriesCache,
            searchCache = searchCache,
            deviceId = deviceId,
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
        applicationScope.launch {
            serverSetup.state.collect { gateway.setConnected(it is ServerSetupState.Authenticated) }
        }
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
        return AppBoundaries(
            gateway = gateway,
            playback = PlaybackCoordinatorImpl(
                applicationScope,
                DurableProgressGateway(EmbyPlaybackGateway(vault, deviceId = deviceId), progress, clock),
                Media3ServicePlaybackEngine(context),
                androidPlaybackCapabilities(),
            ),
            clock = clock,
            connectivity = connectivity,
            serverSetup = serverSetup,
            account = AccountManager(
                applicationScope, vault, progress,
                ServerPrivateDataCleaner(homeCache, movieCache, seriesCache, searchCache),
                serverSetup::signedOut,
            ),
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
