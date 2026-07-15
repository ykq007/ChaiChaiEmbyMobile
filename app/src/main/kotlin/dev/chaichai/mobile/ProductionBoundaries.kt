package dev.chaichai.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.chaichai.mobile.core.contracts.AppBoundaries
import dev.chaichai.mobile.core.contracts.AppClock
import dev.chaichai.mobile.core.contracts.ConnectivityMonitor
import dev.chaichai.mobile.core.contracts.PlaybackCoordinator
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
import dev.chaichai.mobile.platform.server.createRoomHomeCache
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
        val gateway = AuthenticatedEmbyGateway(vault, homeCache = createRoomHomeCache(context))
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
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return AppBoundaries(
            gateway = gateway,
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
