package com.example.aplikasiabsensimojogemi02

import android.content.Context

class SessionManager(context: Context) {
    private val prefs = context.getSharedPreferences("AbsensiPrefs", Context.MODE_PRIVATE)

    fun saveAuthData(token: String, role: String, kelas: String, username: String, nisn: String = "") {
        prefs.edit().apply {
            putString("token", token)
            putString("role", role)
            putString("kelas", kelas)
            putString("username", username)
            putString("nisn", nisn)
            apply()
        }
    }

    fun getToken(): String? = prefs.getString("token", null)
    fun getRole(): String? = prefs.getString("role", "")
    fun getKelas(): String? = prefs.getString("kelas", "")
    fun getUsername(): String? = prefs.getString("username", "")
    fun getNisn(): String? = prefs.getString("nisn", "")

    fun saveConfig(schoolName: String, url: String, logoPath: String?, jadwal: String? = null, lat: Double? = -8.2435, lng: Double? = 113.8447, radius: Double? = 100.0) {
        prefs.edit().apply {
            putString("school_name", schoolName)
            putString("backend_url", url)
            putString("logo_path", logoPath)
            putString("jadwal_harian", jadwal)
            putFloat("school_lat", (lat ?: -8.2435).toFloat())
            putFloat("school_lng", (lng ?: 113.8447).toFloat())
            putFloat("school_radius", (radius ?: 100.0).toFloat())
            apply()
        }
    }

    fun getSchoolLat(): Double = prefs.getFloat("school_lat", -8.2435f).toDouble()
    fun getSchoolLng(): Double = prefs.getFloat("school_lng", 113.8447f).toDouble()
    fun getSchoolRadius(): Double = prefs.getFloat("school_radius", 100.0f).toDouble()
    fun getJadwal(): String? = prefs.getString("jadwal_harian", null)

    fun getSchoolName(): String = prefs.getString("school_name", "PRESENSI SDN MOJOGEMI 02") ?: "PRESENSI SDN MOJOGEMI 02"
    fun getBackendUrl(): String = prefs.getString("backend_url", "https://script.google.com/macros/s/AKfycbz5BwUNBP04EgUD6mRPmEoIuioewQxv1BFHvwMhvwPrG1fPMwj7-iDPAMoGSu8ufalo/exec") ?: "https://script.google.com/macros/s/AKfycbz5BwUNBP04EgUD6mRPmEoIuioewQxv1BFHvwMhvwPrG1fPMwj7-iDPAMoGSu8ufalo/exec"
    fun getLogoPath(): String? = prefs.getString("logo_path", null)

    fun logout() {
        prefs.edit().apply {
            remove("token")
            remove("role")
            remove("kelas")
            remove("username")
            apply()
        }
    }
}