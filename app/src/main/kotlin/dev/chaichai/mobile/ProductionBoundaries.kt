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

object ProductionBoundaries {
    fun create(context: Context): AppBoundaries {
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
