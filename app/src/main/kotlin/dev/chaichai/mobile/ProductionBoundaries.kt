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
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return AppBoundaries(
            gateway = object : EmbyGateway {
                override val connectionState = MutableStateFlow(GatewayConnectionState.Disconnected)
            },
            playback = object : PlaybackCoordinator {
                override val isPlaying = MutableStateFlow(false)
            },
            clock = AppClock { Instant.now() },
            connectivity = object : ConnectivityMonitor {
                override val isOnline = MutableStateFlow(online)
            },
        )
    }
}
