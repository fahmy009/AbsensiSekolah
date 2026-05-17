package com.example.aplikasiabsensimojogemi02

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.*

/**
 * Helper untuk mengelola pembacaan GPS dan keamanan lokasi (Geofencing & Anti-Fake GPS).
 */
class LocationHelper(private val context: Context) {
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    /**
     * Mengecek apakah izin lokasi diberikan dan layanan GPS aktif.
     */
    fun isLocationEnabled(): Boolean {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        
        return hasPermission && (isGpsEnabled || isNetworkEnabled)
    }

    /**
     * Mendeteksi apakah lokasi berasal dari aplikasi Mock/Fake GPS.
     * Fitur keamanan krusial untuk mencegah kecurangan siswa.
     */
    fun isMockLocation(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
    }

    /**
     * Mengambil lokasi perangkat saat ini dengan mekanisme timeout dan high accuracy.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Location? = withContext(Dispatchers.IO) {
        if (!isLocationEnabled()) return@withContext null

        try {
            // 1. Coba ambil lokasi terakhir (lastLocation)
            val lastLocation = Tasks.await(fusedLocationClient.lastLocation, 5, TimeUnit.SECONDS)
            if (lastLocation != null && (System.currentTimeMillis() - lastLocation.time) < 30000) {
                return@withContext lastLocation
            }

            // 2. Paksa ambil lokasi baru yang akurat
            val cts = CancellationTokenSource()
            val freshLocation = Tasks.await(
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token),
                15, TimeUnit.SECONDS
            )
            
            return@withContext freshLocation ?: lastLocation
        } catch (e: Exception) {
            Log.e("LocationHelper", "Error: ${e.message}")
            null
        }
    }

    /**
     * Mengecek apakah lokasi berada di radius sekolah DAN bukan lokasi palsu.
     * Returns: Triple(isWithinRadius, distance, isFakeLocation)
     */
    @SuppressLint("MissingPermission")
    suspend fun validateLocation(targetLat: Double, targetLng: Double, radiusInMeters: Double): Triple<Boolean, Double, Boolean> {
        return try {
            val location = getCurrentLocation()
            if (location != null) {
                val isFake = isMockLocation(location)
                val distance = calculateDistance(location.latitude, location.longitude, targetLat, targetLng)
                Triple(distance <= radiusInMeters, distance, isFake)
            } else {
                Triple(false, -1.0, false)
            }
        } catch (e: Exception) {
            Triple(false, -2.0, false)
        }
    }

    /**
     * Algoritma Haversine untuk menghitung jarak antara dua titik koordinat.
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3 // Jari-jari bumi dalam meter
        val phi1 = lat1 * PI / 180
        val phi2 = lat2 * PI / 180
        val deltaPhi = (lat2 - lat1) * PI / 180
        val deltaLambda = (lon2 - lon1) * PI / 180

        val a = sin(deltaPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return r * c
    }
}
