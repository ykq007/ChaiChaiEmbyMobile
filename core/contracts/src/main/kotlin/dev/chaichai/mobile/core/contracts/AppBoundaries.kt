package dev.chaichai.mobile.core.contracts

import kotlinx.coroutines.flow.StateFlow
import java.time.Instant

interface EmbyGateway {
    val connectionState: StateFlow<GatewayConnectionState>
    suspend fun verifyAuthentication(requestedDestination: String? = null): GatewayAuthenticationStatus =
        GatewayAuthenticationStatus.Unavailable
}
enum class GatewayConnectionState { Disconnected, Connected }
enum class GatewayAuthenticationStatus { Valid, Expired, Unavailable }
interface PlaybackCoordinator { val isPlaying: StateFlow<Boolean> }
fun interface AppClock { fun now(): Instant }
interface ConnectivityMonitor { val isOnline: StateFlow<Boolean> }

data class AppBoundaries(
    val gateway: EmbyGateway,
    val playback: PlaybackCoordinator,
    val clock: AppClock,
    val connectivity: ConnectivityMonitor,
    val serverSetup: ServerSetupBoundary? = null,
)
