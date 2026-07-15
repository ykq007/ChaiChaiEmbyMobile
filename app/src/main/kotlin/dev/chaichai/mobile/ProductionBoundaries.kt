package dev.chaichai.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.EmbyGateway
import dev.chaichai.mobile.core.contracts.GatewayConnectionState
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dev.chaichai.mobile.core.contracts.ServerSetupState
import dev.chaichai.mobile.platform.server.EmbyAuthenticator
import dev.chaichai.mobile.platform.server.EmbyProbe
import dev.chaichai.mobile.platform.server.KeystoreSessionVault
import dev.chaichai.mobile.platform.server.ServerSetupCoordinator
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
        val serverSetup = ServerSetupCoordinator(
            applicationScope,
            EmbyProbe(),
            EmbyAuthenticator(),
            KeystoreSessionVault(context),
            deviceId,
        )
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return AppBoundaries(
            gateway = object : EmbyGateway {
                override val connectionState = serverSetup.state
                    .map { if (it is ServerSetupState.Authenticated) GatewayConnectionState.Connected else GatewayConnectionState.Disconnected }
                    .stateIn(applicationScope, SharingStarted.Eagerly, GatewayConnectionState.Disconnected)
            },
            playback = object : PlaybackCoordinator {
                override val isPlaying = MutableStateFlow(false)
            },
            clock = AppClock { Instant.now() },
            connectivity = object : ConnectivityMonitor {
                override val isOnline = MutableStateFlow(online)
            },
            serverSetup = serverSetup,
        )
    }
}
