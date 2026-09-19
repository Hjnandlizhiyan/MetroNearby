package com.metronearby.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationCompat
import androidx.core.os.CancellationSignal
import com.metronearby.domain.UserLocation
import com.metronearby.domain.UserLocationPolicy
import kotlin.coroutines.resume
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class Coordinates(val lat: Double, val lng: Double)

/**
 * 基于 Android 原生 LocationManager 的定位，不引入任何地图 SDK。
 *
 * 同时请求可用的 GPS / 网络定位，再与两分钟内的缓存一起按精度择优。
 * 过期缓存和误差大于 5 公里的结果不会参与最近站判断。
 */
class AndroidLocationProvider(private val context: Context) {

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun manager(): LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    @SuppressLint("MissingPermission")
    fun lastKnown(nowMillis: Long = System.currentTimeMillis()): UserLocation? {
        if (!hasPermission()) return null
        val manager = manager() ?: return null
        val candidates = PROVIDERS
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .map { it.toUserLocation() }
        return UserLocationPolicy.best(candidates, nowMillis)
    }

    /**
     * 请求一次当前定位；失败或超时后仅回退到仍在有效期内的缓存。
     */
    @SuppressLint("MissingPermission")
    suspend fun resolveBest(): UserLocation? {
        if (!hasPermission()) return null
        val manager = manager() ?: return null
        val cached = PROVIDERS
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .map { it.toUserLocation() }
        val enabledProviders = CURRENT_PROVIDERS.filter { manager.isProviderEnabled(it) }
        val current = coroutineScope {
            enabledProviders.map { provider ->
                async {
                    withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MILLIS) {
                        requestCurrent(manager, provider)
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return UserLocationPolicy.best(current + cached, System.currentTimeMillis())
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestCurrent(manager: LocationManager, provider: String): UserLocation? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }

            LocationManagerCompat.getCurrentLocation(
                manager,
                provider,
                signal,
                ContextCompat.getMainExecutor(context)
            ) { location ->
                if (continuation.isActive) {
                    continuation.resume(location?.toUserLocation())
                }
            }
        }

    private fun android.location.Location.toUserLocation(): UserLocation = UserLocation(
        lat = latitude,
        lng = longitude,
        accuracyMeters = accuracy.takeIf { hasAccuracy() },
        capturedAtMillis = time,
        provider = provider,
        isMock = LocationCompat.isMock(this)
    )

    private companion object {
        const val CURRENT_LOCATION_TIMEOUT_MILLIS = 5_000L
        val CURRENT_PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
    }
}
