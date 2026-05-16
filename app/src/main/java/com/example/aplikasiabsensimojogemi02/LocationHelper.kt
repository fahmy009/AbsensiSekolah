package com.example.aplikasiabsensimojogemi02

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
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
        
        Log.d("LocationHelper", "Permission: $hasPermission, GPS: $isGpsEnabled, Network: $isNetworkEnabled")
        return hasPermission && (isGpsEnabled || isNetworkEnabled)
    }

    /**
     * Mengambil lokasi perangkat saat ini dengan mekanisme timeout dan high accuracy.
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Location? = withContext(Dispatchers.IO) {
        if (!isLocationEnabled()) {
            Log.e("LocationHelper", "Location disabled or permission missing")
            return@withContext null
        }

        try {
            // 1. Coba ambil lokasi terakhir (lastLocation) - sangat cepat
            val lastLocation = Tasks.await(fusedLocationClient.lastLocation, 5, TimeUnit.SECONDS)
            if (lastLocation != null && (System.currentTimeMillis() - lastLocation.time) < 30000) {
                Log.d("LocationHelper", "Using fresh lastLocation")
                return@withContext lastLocation
            }

            // 2. Jika lastLocation null atau usang, paksa ambil lokasi baru
            Log.d("LocationHelper", "Requesting fresh location...")
            val cts = CancellationTokenSource()
            val freshLocation = Tasks.await(
                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token),
                15, TimeUnit.SECONDS
            )
            
            if (freshLocation != null) {
                Log.d("LocationHelper", "Fresh location acquired: ${freshLocation.latitude}, ${freshLocation.longitude}")
                return@withContext freshLocation
            }

            Log.w("LocationHelper", "Failed to get fresh location, falling back to lastLocation")
            return@withContext lastLocation
        } catch (e: Exception) {
            Log.e("LocationHelper", "Error getting location: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Mengecek apakah lokasi perangkat berada di dalam radius sekolah.
     */
    @SuppressLint("MissingPermission")
    suspend fun isWithinRadius(targetLat: Double, targetLng: Double, radiusInMeters: Double): Pair<Boolean, Double> {
        return try {
            val location = getCurrentLocation()
            if (location != null) {
                val distance = calculateDistance(location.latitude, location.longitude, targetLat, targetLng)
                Log.d("LocationHelper", "Distance to school: $distance meters, Radius: $radiusInMeters")
                Pair(distance <= radiusInMeters, distance)
            } else {
                Log.e("LocationHelper", "isWithinRadius: Location is NULL")
                Pair(false, -1.0)
            }
        } catch (e: Exception) {
            Log.e("LocationHelper", "isWithinRadius Error: ${e.message}")
            Pair(false, -2.0)
        }
    }

    /**
     * Algoritma Haversine untuk menghitung jarak antara dua titik koordinat di Bumi.
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
