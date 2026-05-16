package com.example.aplikasiabsensimojogemi02

data class LoginResponse(
    val success: Boolean,
    val message: String?,
    val token: String?,
    val role: String?,
    val nama: String?,
    val kelas: String?
)

data class LoginRequest(
    val action: String = "login",
    val username: String,
    val password: String,
    val nisn: String? = null
)