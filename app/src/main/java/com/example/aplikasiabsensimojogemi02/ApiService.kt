package com.example.aplikasiabsensimojogemi02

import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("exec") // Akhiran URL script Google
    fun loginUser(@Body request: LoginRequest): Call<LoginResponse>
}