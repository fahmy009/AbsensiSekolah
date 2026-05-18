package com.example.aplikasiabsensimojogemi02

import android.app.DatePickerDialog
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.rememberAsyncImagePainter
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.Executors
import androidx.compose.foundation.gestures.*
import androidx.compose.ui.graphics.graphicsLayer
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.google.maps.android.compose.*
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import android.graphics.Color as AndroidColor

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity

/**
 * Data class untuk merepresentasikan informasi Siswa dari database.
 */
data class Siswa(
    val nisn: String,
    val nama: String,
    val status: String,
    val jamMasuk: String,
    val jamPulang: String,
    val jamSholat: String,
    val keterangan: String
)

// Definisi warna tema aplikasi
val MojoBlue = Color(0xFF0D47A1) // Deep Blue
val MojoLightBlue = Color(0xFFE3F2FD)
val MojoYellow = Color(0xFFFFC107) // Amber
val MojoGreen = Color(0xFF2E7D32)
val MojoRed = Color(0xFFC62828)
val MojoBackground = Color(0xFFF8F9FA)

class MainActivity : FragmentActivity() {
    // Inisialisasi HTTP Client satu kali untuk seluruh activity
    private val client = OkHttpClient()
    private lateinit var db: AppDatabase
    private lateinit var locationHelper: LocationHelper
    private var globalHolidayMessage by mutableStateOf<String?>(null)
    private var isAlarmPermissionRequestInFlight = false

    /**
     * Mencari objek jadwal dalam JSON response.
     */
    private fun findJadwalInJson(obj: JSONObject?): JSONObject? {
        if (obj == null) return null
        if (obj.has("jadwal_harian")) return obj
        val keys = obj.keys()
        while (keys.hasNext()) {
            val v = obj.opt(keys.next())
            if (v is JSONObject) {
                val found = findJadwalInJson(v)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Fungsi untuk mengecek apakah hari ini libur (Rutin/Khusus) dari backend.
     */
    private fun checkHolidays(session: SessionManager, onResult: (String?) -> Unit = {}) {
        globalHolidayMessage = null // Reset status libur setiap kali pengecekan dimulai
        // Cek konfigurasi jadwal harian dan geofence
        apiCall(session.getBackendUrl(), JSONObject().apply { put("action", "get_config"); put("token", session.getToken()) }) { s, res ->
            if (s) {
                scheduleAttendanceNotifications(res)
                
                // Update Geofence dari Database Server agar tidak bisa dimanipulasi di sisi HP
                val lat = res.optDouble("school_lat", session.getSchoolLat())
                val lng = res.optDouble("school_lng", session.getSchoolLng())
                val radius = res.optDouble("school_radius", session.getSchoolRadius())
                session.saveConfig(session.getSchoolName(), session.getBackendUrl(), session.getLogoPath(), session.getJadwal(), lat, lng, radius)

                val targetObj = findJadwalInJson(res)
                val rawJadwal = targetObj?.opt("jadwal_harian")
                val jadwal = when (rawJadwal) {
                    is String -> if (rawJadwal.isNotEmpty() && rawJadwal != "null") try { JSONObject(rawJadwal) } catch(e: Exception) { JSONObject() } else JSONObject()
                    is JSONObject -> rawJadwal
                    else -> JSONObject()
                }
                
                val now = Calendar.getInstance()
                val gasDayIdx = when(now.get(Calendar.DAY_OF_WEEK)) {
                    Calendar.MONDAY -> "1"; Calendar.TUESDAY -> "2"; Calendar.WEDNESDAY -> "3"; Calendar.THURSDAY -> "4"
                    Calendar.FRIDAY -> "5"; Calendar.SATURDAY -> "6"; Calendar.SUNDAY -> "7"; else -> "1"
                }

                val todaySched = jadwal.optJSONObject(gasDayIdx)
                if (todaySched?.optBoolean("libur", false) == true) {
                    val names = mapOf("1" to "Senin", "2" to "Selasa", "3" to "Rabu", "4" to "Kamis", "5" to "Jumat", "6" to "Sabtu", "7" to "Minggu")
                    val msg = "Libur Rutin (Hari ${names[gasDayIdx]})"
                    if (globalHolidayMessage != msg) {
                        showSystemNotification("Hari Libur", "Hari ini adalah $msg. Absensi ditiadakan.")
                    }
                    globalHolidayMessage = msg
                    onResult(msg)
                }
            }
        }
        // Cek daftar hari libur khusus (tanggal merah kalender)
        apiCall(session.getBackendUrl(), JSONObject().apply { put("action", "get_holidays"); put("token", session.getToken()) }) { s, res ->
            if (s) {
                fun findArray(obj: JSONObject?): org.json.JSONArray? {
                    if (obj == null) return null
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = obj.opt(k)
                        if (v is org.json.JSONArray) return v
                        if (v is JSONObject) {
                            val found = findArray(v)
                            if (found != null) return found
                        }
                    }
                    return null
                }
                
                val arr = findArray(res) ?: org.json.JSONArray()
                val now = Calendar.getInstance()
                val d = now.get(Calendar.DAY_OF_MONTH)
                val m = now.get(Calendar.MONTH) + 1
                val y = now.get(Calendar.YEAR)
                
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val tglRaw = obj.optString("tanggal", "").replace("/", "-")
                    val parts = tglRaw.split("-")
                    if (parts.size == 3) {
                        try {
                            val isMatch = if (parts[0].length == 4) { // Format yyyy-MM-dd
                                parts[0].toInt() == y && parts[1].toInt() == m && parts[2].toInt() == d
                            } else { // Format dd-MM-yyyy
                                parts[0].toInt() == d && parts[1].toInt() == m && parts[2].toInt() == y
                            }
                            
                            if (isMatch) {
                                val msg = "Hari Libur (${obj.optString("keterangan", "Tanpa Keterangan")})"
                                if (globalHolidayMessage != msg) {
                                    showSystemNotification("Hari Libur Sekolah", msg)
                                }
                                globalHolidayMessage = msg
                                onResult(msg)
                                break 
                            }
                        } catch (e: Exception) {}
                    }
                }
            }
        }
    }

    /**
     * Menampilkan notifikasi sistem di status bar.
     */
    private fun showSystemNotification(title: String, message: String) {
        val channelId = "general_notifications"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Notifikasi Umum",
                android.app.NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    /**
     * Menampilkan dialog otentikasi biometrik (Sidik Jari/Wajah).
     */
    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                runOnUiThread { onSuccess() }
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Login Biometrik")
            .setSubtitle("Gunakan sidik jari atau wajah untuk masuk")
            .setNegativeButtonText("Batal")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    /**
     * Fungsi entry point saat Activity dibuat.
     * Mengatur alur navigasi awal (Splash -> Login/Dashboard).
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase.getDatabase(this)
        locationHelper = LocationHelper(this)
        val session = SessionManager(this)
        setContent {
            // State untuk mengatur halaman mana yang sedang ditampilkan
            var page by remember { mutableStateOf("SPLASH") }
            val context = LocalContext.current
            
            // Launcher terpadu untuk semua izin di awal (Lokasi, Kamera, Notifikasi)
            val allPermissionsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
                val allGranted = results.values.all { it }
                if (!allGranted) {
                    Toast.makeText(context, "Beberapa izin ditolak. Aplikasi mungkin tidak bekerja maksimal.", Toast.LENGTH_LONG).show()
                }
            }

            val requestCameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

            // Logika transisi dari Splash ke halaman tujuan (Login atau Dashboard)
            LaunchedEffect(Unit) { 
                // Kumpulkan daftar izin yang diperlukan
                val permissions = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.CAMERA
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }

                // Minta semua izin sekaligus
                allPermissionsLauncher.launch(permissions.toTypedArray())
                
                // SINKRONISASI DATA UTAMA SAAT STARTUP (Agar peta & jadwal langsung siap)
                checkHolidays(session)

                delay(1200) // Delay splash screen
                page = if (session.getToken().isNullOrEmpty()) "LOGIN" else "DASHBOARD" 
            }

            // Tema aplikasi menggunakan MaterialTheme 3
            MaterialTheme(colorScheme = lightColorScheme(
                primary = MojoBlue, 
                secondary = MojoYellow,
                background = MojoBackground,
                surface = Color.White
            )) {
                Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding(), color = MojoBackground) {
                    // Animasi perpindahan antar halaman utama (Login <-> Dashboard)
                    AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                        },
                        label = "PageTransition"
                    ) { targetPage ->
                        when (targetPage) {
                            "SPLASH" -> SplashScreen(session)
                            "LOGIN" -> LoginScreen(session) { page = "DASHBOARD" }
                            "DASHBOARD" -> {
                                val hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                                DashboardScreen(session, hasCameraPermission, { requestCameraLauncher.launch(Manifest.permission.CAMERA) }, { page = "LOGIN" })
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Fungsi yang dijalankan saat Activity dihancurkan.
     * Digunakan untuk pembersihan cache otomatis agar aplikasi tetap ringan.
     */
    override fun onDestroy() {
        super.onDestroy()
        try {
            cacheDir.deleteRecursively() // Menghapus file sementara di folder cache
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Tampilan layar Splash Screen dengan logo dan indikator loading.
     */
    @Composable
    fun SplashScreen(session: SessionManager) = Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DynamicLogo(session, Modifier.size(200.dp))
            Spacer(Modifier.height(16.dp))
            // Indikator loading melingkar
            CircularProgressIndicator(color = MojoBlue, strokeWidth = 2.dp)
        }
    }

    /**
     * Tampilan layar Login khusus untuk Admin dan Guru.
     */
    @Composable
    fun LoginScreen(session: SessionManager, onSuccess: () -> Unit) {
        var u by remember { mutableStateOf("") }
        var p by remember { mutableStateOf("") }
        var vis by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        var msg by remember { mutableStateOf("") }
        var showConf by remember { mutableStateOf(false) }
        var showAbout by remember { mutableStateOf(false) }
        
        // Deteksi apakah keyboard sedang muncul
        val isKeyboardVisible = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp

        Box(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                // Tombol Info
                IconButton({ showAbout = true }) {
                    Icon(Icons.Default.Info, null, tint = MojoBlue)
                }
                // Tombol Settings
                IconButton({ showConf = true }) {
                    Icon(Icons.Default.Settings, null, tint = MojoBlue)
                }
            }
            
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .align(Alignment.Center)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Menampilkan logo dengan ukuran yang fleksibel namun tetap terlihat
                DynamicLogo(session, Modifier.sizeIn(maxWidth = 200.dp, maxHeight = 150.dp).padding(bottom = 24.dp))
                Text(session.getSchoolName(), fontSize = 22.sp, fontWeight = FontWeight.Black, color = MojoBlue, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 28.sp)
                Spacer(Modifier.height(32.dp))

                // Input field untuk Username
                OutlinedTextField(
                    value = u,
                    onValueChange = { u = it },
                    label = { Text("Username Admin/Guru") },
                    leadingIcon = { Icon(Icons.Default.Person, null, tint = MojoBlue) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MojoBlue)
                )
                Spacer(Modifier.height(12.dp))
                
                // Input field untuk Password dengan fitur Tampilkan/Sembunyikan
                OutlinedTextField(
                    value = p,
                    onValueChange = { p = it },
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, null, tint = MojoBlue) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true,
                    visualTransformation = if (vis) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton({ vis = !vis }) { Icon(if (vis) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MojoBlue)
                )

                Spacer(Modifier.height(24.dp))
                
                if (loading) {
                    CircularProgressIndicator(color = MojoBlue)
                } else {
                    // Tombol untuk memicu proses Login
                    Button(
                        onClick = {
                            if (u.isEmpty() || p.isEmpty()) { msg = "Isi Lengkap!"; return@Button }
                            loading = true
                            val payload = JSONObject().apply { 
                                put("action", "login")
                                put("username", u)
                                put("password", p)
                            }
                            // Panggil API Login
                            apiCall(session.getBackendUrl(), payload) { success, res ->
                                loading = false
                                if (success) {
                                    // Simpan data autentikasi ke SessionManager
                                    session.saveAuthData(
                                        res.getString("token"), 
                                        res.getString("role"), 
                                        res.optString("kelas", ""), 
                                        res.optString("username", u),
                                        "" 
                                    )
                                    session.setBiometricEnabled(true)
                                    onSuccess()
                                } else {
                                    msg = res.optString("message", "Gagal Login (Cek Kembali Data Anda)")
                                }
                            }
                        }, 
                        modifier = Modifier.fillMaxWidth().height(56.dp), 
                        shape = RoundedCornerShape(16.dp), 
                        elevation = ButtonDefaults.buttonElevation(4.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, null)
                        Spacer(Modifier.width(8.dp))
                        Text("MASUK", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }

                    // Tambahan Tombol Biometrik (Muncul jika sudah pernah login sebelumnya)
                    if (session.isBiometricEnabled() && !isKeyboardVisible) {
                        Spacer(Modifier.height(40.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = { 
                                    showBiometricPrompt {
                                        if (session.restoreFullSessionFromSecure()) {
                                            onSuccess()
                                        } else {
                                            msg = "Sesi biometrik kadaluarsa, silakan login manual."
                                        }
                                    }
                                },
                                modifier = Modifier.size(72.dp).background(MojoBlue.copy(0.1f), CircleShape)
                            ) {
                                Icon(Icons.Default.Fingerprint, "Biometric", tint = MojoBlue, modifier = Modifier.size(40.dp))
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Masuk Cepat", fontSize = 14.sp, color = MojoBlue, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(40.dp))
                    }
                }
                
                // Pesan Error jika login gagal
                if (msg.isNotEmpty()) {
                    Surface(
                        color = Color.Red.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(top = 16.dp).fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.ErrorOutline, null, tint = Color.Red, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(msg, color = Color.Red, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            
            // Info Pengembang di bagian bawah layar (Sembunyikan jika keyboard muncul agar estetik)
            if (!isKeyboardVisible) {
                Text(
                    "© Muhammad Fahmy 2026", 
                    fontSize = 11.sp, 
                    color = Color.Gray,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                    fontWeight = FontWeight.Medium
                )
            }
        }
        // Menampilkan dialog konfigurasi URL jika tombol gear diklik
        if (showConf) AppConfigDialog(session) { showConf = false }
        if (showAbout) AboutDialog { showAbout = false }
    }

    /**
     * Dialog Tentang Aplikasi dan Pengembang.
     * Menampilkan informasi versi dan media sosial pengembang.
     */
    @Composable
    fun AboutDialog(onDismiss: () -> Unit) {
        val context = LocalContext.current
        val openUrl = { url: String ->
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            context.startActivity(intent)
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { 
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(MojoBlue)
                ) {
                    Text("Tutup", color = Color.White)
                }
            },
            title = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        color = MojoBlue.copy(alpha = 0.1f),
                        shape = CircleShape,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Info, null, tint = MojoBlue, modifier = Modifier.size(36.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Informasi Aplikasi", 
                        fontWeight = FontWeight.Black, 
                        fontSize = 20.sp, 
                        color = MojoBlue
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Card(
                        colors = CardDefaults.cardColors(MojoBlue.copy(alpha = 0.05f)),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Presensi Mojogemi 02 adalah solusi digital cerdas untuk manajemen absensi siswa yang efisien, transparan, dan real-time.",
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(16.dp),
                            lineHeight = 20.sp,
                            color = Color.DarkGray
                        )
                    }
                    
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Verified, null, tint = MojoGreen, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Detail Sistem", fontWeight = FontWeight.Bold, color = MojoBlue, fontSize = 14.sp)
                        }
                        
                        Surface(
                            color = Color.White,
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.3f))
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                EnhancedInfoRow(Icons.Default.Vibration, "Versi", "2.0.0 (Pro)")
                                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f))
                                EnhancedInfoRow(Icons.Default.Storage, "Database", "Hybrid (Cloud & Local)")
                                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f))
                                EnhancedInfoRow(Icons.Default.LocationOn, "Keamanan", "GPS Geofencing")
                            }
                        }
                    }
                    
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MojoBlue.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("DIPRODUKSI OLEH", fontSize = 10.sp, fontWeight = FontWeight.Black, color = MojoBlue.copy(alpha = 0.6f))
                        Spacer(Modifier.height(4.dp))
                        Text("Muhammad Fahmy", fontSize = 18.sp, fontWeight = FontWeight.Black, color = MojoBlue)
                        Text("Android Developer", fontSize = 12.sp, color = Color.Gray)
                        
                        Spacer(Modifier.height(16.dp))
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            SocialIcon("https://img.icons8.com/color/144/facebook-new.png", "FB") { openUrl("https://www.facebook.com/muhammadfahmy009") }
                            SocialIcon("https://img.icons8.com/color/144/instagram-new.png", "IG") { openUrl("https://www.instagram.com/fahmy082") }
                            SocialIcon("https://img.icons8.com/color/144/tiktok--v1.png", "TT") { openUrl("https://www.tiktok.com/@muhammadfahmyan") }
                            SocialIcon("https://img.icons8.com/color/144/youtube-play.png", "YT") { openUrl("https://youtube.com/@muhammadfahmy009") }
                        }
                    }
                }
            }
        )
    }

    /**
     * Baris informasi dengan ikon untuk tampilan yang lebih menarik di dialog About.
     */
    @Composable
    fun EnhancedInfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MojoBlue.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Color.Gray)
            }
            Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MojoBlue)
        }
    }

    /**
     * Tombol media sosial dengan ikon gambar dari URL.
     */
    @Composable
    fun SocialIcon(url: String, contentDesc: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(1.dp, Color.LightGray.copy(alpha = 0.5f), CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(model = url, contentDescription = contentDesc, modifier = Modifier.size(26.dp))
        }
    }

    /**
     * Tampilan layar Dashboard Utama setelah login.
     * Mengatur Daftar Siswa, Pencarian, dan fitur Scan QR.
     */
    @Composable
    fun DashboardScreen(session: SessionManager, hasCam: Boolean, reqCam: () -> Unit, onLogout: () -> Unit) {
        val context = LocalContext.current
        var list by remember { mutableStateOf<List<Siswa>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var isScan by remember { mutableStateOf(false) }
        
        // State untuk feedback visual proses scan
        var scanLoading by remember { mutableStateOf(false) }
        var scanMessage by remember { mutableStateOf("") }
        var scanSuccess by remember { mutableStateOf<Boolean?>(null) }
        
        var loadingNisn by remember { mutableStateOf<String?>(null) }
        
        val isAdmin = session.getRole()?.lowercase() == "admin"
        val isSiswa = session.getRole()?.lowercase() == "siswa"
        
        // Daftar kelas yang dapat diakses oleh user (Admin bisa semua, Guru hanya kelasnya)
        val klsList = remember(session.getKelas()) {
            val fromSession = session.getKelas()?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            if (isAdmin && fromSession.isNullOrEmpty()) listOf("I", "II", "III", "IV", "V", "VI")
            else fromSession ?: listOf("I")
        }
        
        var targetKls by remember { mutableStateOf(klsList.firstOrNull() ?: "I") }
        var expKls by remember { mutableStateOf(false) }
        
        var searchQuery by remember { mutableStateOf("") }
        var showProfile by remember { mutableStateOf(false) }

        // Jalankan pengecekan libur saat layar dibuka
        LaunchedEffect(Unit) { checkHolidays(session) }

        // State untuk feedback sinkronisasi (Online/Offline)
        var syncProgress by remember { mutableStateOf(0f) }
        var isSyncing by remember { mutableStateOf(false) }
        var syncStatusMsg by remember { mutableStateOf("") }
        var syncMatchPercent by remember { mutableStateOf(0) }

        /**
         * Fungsi untuk sinkronisasi data siswa dari server ke database lokal.
         * Fitur utama:
         * 1. Menampilkan progres sinkronisasi (0-100%).
         * 2. Menghitung tingkat kecocokan data antara lokal dan server.
         * 3. Menyimpan data secara offline untuk penggunaan tanpa internet.
         */
        fun refresh() {
            if (isSyncing) return
            isSyncing = true
            syncProgress = 0.1f
            syncStatusMsg = "Memulai sinkronisasi..."
            
            // Cek hari libur saat melakukan sinkronisasi
            checkHolidays(session)
            
            // Baca dulu dari database lokal agar UI tetap responsif (offline first)
            lifecycleScope.launch {
                val localData = db.siswaDao().getAllSiswa().map {
                    Siswa(it.nisn, it.nama, it.status, it.jamMasuk, it.jamPulang, it.jamSholat, it.keterangan)
                }
                if (localData.isNotEmpty() && list.isEmpty()) {
                    list = if (isSiswa) localData.filter { it.nisn == session.getNisn() } else localData
                }
                syncProgress = 0.3f
                syncStatusMsg = "Membaca database lokal..."
            }

            // Panggil API untuk mengambil data siswa terbaru
            apiCall(session.getBackendUrl(), JSONObject().apply {
                put("action", "get_siswa_by_kelas")
                put("token", session.getToken())
                put("kelas", if (isSiswa) "" else targetKls.trim())
            }) { s, res ->
                if (s) {
                    syncProgress = 0.6f
                    syncStatusMsg = "Menerima data dari server..."
                    
                    val arr = res.getJSONArray("data")
                    val totalItems = arr.length()
                    val temp = mutableListOf<Siswa>()
                    val entities = mutableListOf<SiswaEntity>()
                    
                    for (i in 0 until totalItems) {
                        val o = arr.getJSONObject(i)
                        val siswa = Siswa(
                            o.getString("nisn"), 
                            o.getString("nama"), 
                            o.optString("status", "-"), 
                            o.optString("jamMasuk", "-"), 
                            o.optString("jamPulang", "-"), 
                            o.optString("jamSholat", "-"),
                            o.optString("keterangan", "")
                        )
                        temp.add(siswa)
                        entities.add(SiswaEntity(siswa.nisn, siswa.nama, siswa.status, siswa.jamMasuk, siswa.jamPulang, siswa.jamSholat, siswa.keterangan))
                        
                        // Perbarui indikator progres berdasarkan jumlah item yang diproses
                        if (totalItems > 10) {
                            syncProgress = 0.6f + (0.3f * (i.toFloat() / totalItems))
                        }
                    }

                    // Hitung tingkat kecocokan data untuk memastikan integritas
                    lifecycleScope.launch {
                        syncMatchPercent = if (totalItems == 0) 100 else {
                            val matchCount = entities.count { e -> temp.any { it.nisn == e.nisn && it.status == e.status } }
                            ((matchCount.toFloat() / totalItems.toFloat()) * 100).toInt()
                        }
                        
                        // Hapus data lama dan simpan data baru ke database lokal (Room)
                        db.siswaDao().deleteAll()
                        db.siswaDao().insertAll(entities)
                        
                        // Perbarui tampilan daftar di UI
                        list = if (isSiswa) temp.filter { it.nisn == session.getNisn() } else temp
                        
                        syncProgress = 1.0f
                        syncStatusMsg = "Sinkronisasi Berhasil ($syncMatchPercent%)"
                        delay(2000)
                        isSyncing = false
                    }
                } else if (res.optString("message").contains("Sesi")) {
                    // Jika sesi berakhir, arahkan user kembali ke layar login
                    onLogout()
                } else {
                    // Feedback jika terjadi kegagalan sinkronisasi (misal: internet mati)
                    syncStatusMsg = "Gagal Sinkron: ${res.optString("message", "Koneksi Bermasalah")}"
                    syncProgress = 0f
                    Toast.makeText(this@MainActivity, "Gagal sinkron, menggunakan data lokal", Toast.LENGTH_SHORT).show()
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ isSyncing = false }, 3000)
                }
            }
        }

        var currentDistance by remember { mutableStateOf<Double?>(null) }
        
        // Loop untuk update jarak secara real-time
        LaunchedEffect(Unit) {
            while(true) {
                val (_, dist) = locationHelper.validateLocation(session.getSchoolLat(), session.getSchoolLng(), session.getSchoolRadius())
                if (dist >= 0) currentDistance = dist
                delay(1000) // Update setiap 1 detik agar terasa lebih real-time
            }
        }

        // Jalankan pengecekan libur saat layar dibuka
        LaunchedEffect(Unit) { checkHolidays(session) }

        // Refresh data saat target kelas diganti
        LaunchedEffect(targetKls) { refresh() }

        // Filter daftar siswa berdasarkan input pencarian secara real-time
        val filteredList = if (searchQuery.isEmpty()) list else list.filter { it.nama.contains(searchQuery, ignoreCase = true) || it.nisn.contains(searchQuery) }

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // Bagian Header Dashboard
                Surface(
                    color = MojoBlue,
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 20.dp),
                        Arrangement.SpaceBetween,
                        Alignment.CenterVertically
                    ) {
                        // Tombol Profil di kiri
                        IconButton({ showProfile = true }) { Icon(Icons.Default.AccountCircle, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
                        
                        // Informasi Pengguna dan Pilihan Kelas di tengah
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally, 
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { if ((isAdmin || klsList.size > 1) && !isSiswa) expKls = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "Halo, ${session.getUsername()?.replaceFirstChar { it.uppercase() }}", 
                                fontWeight = FontWeight.ExtraBold, 
                                fontSize = 16.sp, 
                                color = Color.White
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = MojoYellow.copy(alpha = 0.9f),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Text(
                                        if (isSiswa) " NISN: ${session.getNisn()} " else " KELAS $targetKls ", 
                                        fontSize = 10.sp, 
                                        fontWeight = FontWeight.Bold,
                                        color = MojoBlue
                                    )
                                }
                                if ((isAdmin || klsList.size > 1) && !isSiswa) {
                                    Icon(Icons.Default.ArrowDropDown, null, tint = MojoYellow, modifier = Modifier.size(16.dp))
                                }
                            }
                            
                            // Indikator Jarak Real-time dengan Animasi
                            currentDistance?.let { dist ->
                                val isNear = dist <= session.getSchoolRadius()
                                val color by animateColorAsState(
                                    targetValue = if (isNear) Color.Green else MojoYellow,
                                    animationSpec = tween(1000)
                                )
                                
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isNear) Icons.Default.LocationOn else Icons.Default.LocationOff,
                                        contentDescription = null,
                                        tint = color,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = if (dist < 1000) "${dist.toInt()}m dari Sekolah" else String.format("%.1fkm dari Sekolah", dist/1000),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = color.copy(alpha = 0.9f)
                                    )
                                }
                            }

                            if (!isSiswa) {
                                DropdownMenu(expKls, { expKls = false }) {
                                    klsList.forEach { k -> DropdownMenuItem(text = { Text("Kelas $k") }, onClick = { targetKls = k; expKls = false }) }
                                }
                            }
                        }

                        // Tombol Logout di kanan
                        IconButton({ 
                            session.logout()
                            Toast.makeText(context, "Logout Berhasil", Toast.LENGTH_SHORT).show()
                            onLogout() 
                        }) { Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = Color.White) }
                    }
                }

                Column(Modifier.padding(horizontal = 16.dp)) {
                    // Banner Informasi Sinkronisasi (Dibuat lebih minimalis)
                    androidx.compose.animation.AnimatedVisibility(visible = isSyncing) {
                        Row(
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                progress = { syncProgress },
                                modifier = Modifier.size(16.dp),
                                color = MojoBlue,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(syncStatusMsg, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MojoBlue.copy(alpha = 0.7f))
                        }
                    }

                    // Banner Informasi jika hari ini Libur (Versi Ringkas)
                    globalHolidayMessage?.let { msg ->
                        Surface(
                            color = Color.Red.copy(alpha = 0.08f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                            border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.2f))
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.EventBusy, null, tint = Color.Red, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "ABSENSI LIBUR: $msg", 
                                    color = Color.Red, 
                                    fontSize = 11.sp, 
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Tampilkan Menu Admin jika role adalah Admin
                    if (isAdmin) {
                        Spacer(Modifier.height(16.dp))
                        AdminMod(session, globalHolidayMessage != null, syncMatchPercent) { 
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                isScan = true 
                            } else {
                                reqCam()
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    SummaryStats(list) // Menampilkan statistik di dashboard
                    Spacer(Modifier.height(8.dp))

                    if (!isAdmin) {
                        // Tombol utama untuk Guru guna membuka Scanner
                        Button(
                            onClick = { 
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                    isScan = true 
                                } else {
                                    reqCam()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            enabled = globalHolidayMessage == null,
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(MojoYellow),
                            elevation = ButtonDefaults.buttonElevation(4.dp)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, null, tint = MojoBlue)
                            Spacer(Modifier.width(8.dp))
                            Text("SCAN QR HADIR", color = MojoBlue, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Bagian Daftar Siswa dan Pencarian
                    if (!isSiswa) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically, 
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Cari nama atau NISN...", fontSize = 14.sp) },
                                leadingIcon = { Icon(Icons.Default.Search, null, tint = MojoBlue.copy(alpha = 0.6f)) },
                                shape = RoundedCornerShape(16.dp),
                                singleLine = true,
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MojoBlue,
                                    unfocusedBorderColor = Color.LightGray.copy(alpha = 0.5f),
                                    focusedContainerColor = Color.White,
                                    unfocusedContainerColor = Color.White
                                )
                            )
                            Spacer(Modifier.width(12.dp))
                            IconButton(
                                onClick = { refresh() }, 
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(MojoBlue.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                            ) {
                                Icon(Icons.Default.Refresh, null, tint = MojoBlue)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(4.dp, 16.dp).background(MojoBlue, RoundedCornerShape(2.dp)))
                            Spacer(Modifier.width(8.dp))
                            Text("Daftar Siswa Kelas $targetKls", fontWeight = FontWeight.Black, color = MojoBlue, fontSize = 16.sp)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(4.dp, 16.dp).background(MojoBlue, RoundedCornerShape(2.dp)))
                                Spacer(Modifier.width(8.dp))
                                Text("Status Presensi Hari Ini", fontWeight = FontWeight.Black, color = MojoBlue, fontSize = 16.sp)
                            }
                            IconButton(
                                onClick = { refresh() }, 
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(MojoBlue.copy(alpha = 0.1f), CircleShape)
                            ) {
                                Icon(Icons.Default.Refresh, null, tint = MojoBlue, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    if (loading) {
                        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = MojoBlue)
                                Spacer(Modifier.height(12.dp))
                                Text("Memproses Data...", fontSize = 14.sp, color = MojoBlue, fontWeight = FontWeight.Medium)
                            }
                        }
                    } else if (filteredList.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { EmptyStateView() }
                    } else {
                        // Tampilan List Siswa menggunakan LazyColumn agar efisien
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f).drawWithContent { drawContent() },
                            contentPadding = PaddingValues(bottom = 16.dp, top = 4.dp)
                        ) {
                            items(filteredList, key = { it.nisn }) { s ->
                                // Gunakan animasi penyesuaian ukuran konten agar halus (percepat durasi)
                                Box(Modifier.animateContentSize(animationSpec = tween(200))) {
                                    val isNotAbsentYet = s.status == "-" || s.status == ""
                                    // canEdit sekarang hanya mengecek role dan status absen, tidak mengecek hari libur
                                    SiswaItem(s, !isSiswa && isNotAbsentYet, loadingNisn == s.nisn, globalHolidayMessage) { st, note ->
                                        loadingNisn = s.nisn
                                        apiCall(session.getBackendUrl(), JSONObject().apply {
                                            put("action", "scan_absen")
                                            put("nisn", s.nisn)
                                            put("status", st)
                                            if (note.isNotEmpty()) put("keterangan", note)
                                            put("token", session.getToken())
                                        }) { _, _ -> 
                                            loadingNisn = null
                                            refresh() 
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text("© Muhammad Fahmy 2026", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp), fontSize = 10.sp, color = Color.Gray)
                }
            }

            // --- SCANNER OVERLAY (FULL SCREEN) ---
            androidx.compose.animation.AnimatedVisibility(
                visible = isScan,
                enter = fadeIn() + slideInVertically { it },
                exit = fadeOut() + slideOutVertically { it }
            ) {
                // State untuk melacak posisi QR Code yang terdeteksi
                var detectedRect by remember { mutableStateOf<android.graphics.Rect?>(null) }
                
                // Animasi Laser
                val infiniteTransition = rememberInfiniteTransition(label = "laser")
                val laserOffset by infiniteTransition.animateFloat(
                    initialValue = 0.1f,
                    targetValue = 0.9f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "laserOffset"
                )

                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    QrScannerView(
                        onRes = { res ->
                            val cleanNisn = res.trim()
                            if (cleanNisn.isEmpty()) return@QrScannerView
                            scanLoading = true
                            lifecycleScope.launch {
                                val (isNear, distance, isFake) = locationHelper.validateLocation(session.getSchoolLat(), session.getSchoolLng(), session.getSchoolRadius())
                                if (isFake) { scanLoading = false; Toast.makeText(context, "Kecurangan Terdeteksi: Fake GPS!", Toast.LENGTH_LONG).show(); isScan = false; return@launch }
                                if (!isNear && distance >= 0) { scanLoading = false; Toast.makeText(context, "Gagal: Di luar area (${distance.toInt()}m)", Toast.LENGTH_LONG).show(); isScan = false; return@launch }

                                apiCall(session.getBackendUrl(), JSONObject().apply { put("action", "scan_absen"); put("nisn", cleanNisn); put("token", session.getToken()) }) { s, r ->
                                    scanLoading = false
                                    scanSuccess = s
                                    scanMessage = r.optString("message", "Terjadi Kesalahan")
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ isScan = false; scanSuccess = null; scanMessage = ""; refresh() }, 2500)
                                }
                            }
                        },
                        onDetect = { rect -> detectedRect = rect }
                    )

                    // Bingkai Scanner Utama dengan Animasi Laser
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(280.dp)) {
                            val color = when {
                                scanSuccess == null -> MojoYellow
                                scanSuccess == false -> Color.Red
                                scanMessage.contains("TERLAMBAT") -> Color(0xFFFB8C00)
                                scanMessage.contains("LUPA") -> Color(0xFF7E57C2)
                                else -> Color.Green
                            }
                            
                            val sw = 4.dp.toPx()
                            val cl = 40.dp.toPx()
                            
                            // Gambar Sudut Bingkai
                            drawLine(color, Offset(0f, 0f), Offset(cl, 0f), sw)
                            drawLine(color, Offset(0f, 0f), Offset(0f, cl), sw)
                            drawLine(color, Offset(size.width, 0f), Offset(size.width - cl, 0f), sw)
                            drawLine(color, Offset(size.width, 0f), Offset(size.width, cl), sw)
                            drawLine(color, Offset(0f, size.height), Offset(cl, size.height), sw)
                            drawLine(color, Offset(0f, size.height), Offset(0f, size.height - cl), sw)
                            drawLine(color, Offset(size.width, size.height), Offset(size.width - cl, size.height), sw)
                            drawLine(color, Offset(size.width, size.height), Offset(size.width, size.height - cl), sw)

                            // Gambar Garis Laser Bergerak (Hanya jika belum sukses/loading)
                            if (!scanLoading && scanSuccess == null) {
                                val yPos = size.height * laserOffset
                                drawLine(
                                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(color.copy(alpha = 0f), color, color.copy(alpha = 0f)),
                                        startY = yPos - 20.dp.toPx(),
                                        endY = yPos + 20.dp.toPx()
                                    ),
                                    start = Offset(10.dp.toPx(), yPos),
                                    end = Offset(size.width - 10.dp.toPx(), yPos),
                                    strokeWidth = 2.dp.toPx()
                                )
                            }
                        }
                    }

                    // TRACKING BOX: Kotak yang mengikuti QR Code secara langsung
                    detectedRect?.let { rect ->
                        if (!scanLoading && scanSuccess == null) {
                            // Hitung proporsi karena koordinat kamera berbeda dengan koordinat layar
                            Canvas(Modifier.fillMaxSize()) {
                                // Ilustrasi tracking (Sederhana: Mengikuti area tengah)
                                drawRect(
                                    color = MojoYellow.copy(alpha = 0.3f),
                                    topLeft = Offset(size.width * 0.3f, size.height * 0.4f), // Simulasi deteksi
                                    size = androidx.compose.ui.geometry.Size(size.width * 0.4f, size.width * 0.4f),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx())
                                )
                            }
                        }
                    }

                    // Overlay Loading/Status
                    androidx.compose.animation.AnimatedVisibility(
                        visible = scanLoading || scanSuccess != null,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (scanLoading) {
                                    CircularProgressIndicator(color = MojoYellow, strokeWidth = 4.dp)
                                    Spacer(Modifier.height(16.dp))
                                    Text("Memproses Absen...", color = Color.White, fontWeight = FontWeight.Bold)
                                } else {
                                    val (icon, tint) = when {
                                        scanSuccess == false -> Icons.Default.Error to Color.Red
                                        scanMessage.contains("TERLAMBAT") -> Icons.Default.History to Color(0xFFFB8C00)
                                        scanMessage.contains("LUPA") -> Icons.Default.RunningWithErrors to Color(0xFF7E57C2)
                                        scanMessage.contains("SUDAH") -> Icons.Default.Info to Color(0xFF00ACC1)
                                        else -> Icons.Default.CheckCircle to Color.Green
                                    }
                                    Icon(icon, null, Modifier.size(80.dp), tint)
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        scanMessage, 
                                        color = Color.White, 
                                        fontWeight = FontWeight.Black, 
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, 
                                        modifier = Modifier.padding(horizontal = 40.dp),
                                        lineHeight = 24.sp
                                    )
                                }
                            }
                        }
                    }

                    // Tombol Tutup Scanner
                    IconButton(
                        onClick = { isScan = false },
                        modifier = Modifier.align(Alignment.TopStart).padding(16.dp).background(Color.Black.copy(0.3f), CircleShape)
                    ) {
                        Icon(Icons.Default.Close, null, tint = Color.White)
                    }

                    Text(
                        "Arahkan kamera ke QR Code Siswa",
                        color = Color.White.copy(0.7f),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp),
                        fontSize = 14.sp
                    )
                }
            }
        }

        if (showProfile) ProfileDialog(session) { showProfile = false }
    }

    /**
     * Dialog untuk mengubah profil pengguna (Username & Password).
     */
    @Composable
    fun ProfileDialog(session: SessionManager, onDismiss: () -> Unit) {
        var newUser by remember { mutableStateOf(session.getUsername() ?: "") }
        var newPass by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }
        var msg by remember { mutableStateOf("") }
        var vis by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                Button(
                    onClick = {
                        loading = true
                        apiCall(session.getBackendUrl(), JSONObject().apply {
                            put("action", "update_profile")
                            put("token", session.getToken())
                            put("new_username", newUser)
                            if (newPass.isNotEmpty()) put("new_password", newPass)
                        }) { success, res ->
                            loading = false
                            if (success) {
                                msg = "Sukses Update!"
                                session.saveAuthData(session.getToken()!!, session.getRole()!!, session.getKelas()!!, newUser)
                            } else msg = res.optString("message", "Gagal Update")
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Simpan") }
            },
            dismissButton = { TextButton(onDismiss) { Text("Tutup") } },
            title = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ManageAccounts, null, tint = MojoBlue, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Pengaturan Akun", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = newUser, onValueChange = { newUser = it }, label = { Text("Username") }, leadingIcon = { Icon(Icons.Default.Person, null, tint = MojoBlue) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    OutlinedTextField(value = newPass, onValueChange = { newPass = it }, label = { Text("Password Baru") }, placeholder = { Text("Biarkan kosong") }, leadingIcon = { Icon(Icons.Default.Lock, null, tint = MojoBlue) }, visualTransformation = if (vis) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton({ vis = !vis }) { Icon(if (vis) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    if (msg.isNotEmpty()) Text(msg, color = if (msg.contains("Sukses")) Color(0xFF2E7D32) else Color.Red, fontSize = 13.sp)
                    if (loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally), color = MojoBlue)
                }
            }
        )
    }

    /**
     * Komponen Modul Admin (Jadwal, Libur, Users, Lokasi) dan Tombol Scan khusus Admin.
     * Menampilkan juga status sinkronisasi database.
     */
    @Composable
    fun AdminMod(session: SessionManager, isHoliday: Boolean, syncPercent: Int, onScan: () -> Unit) {
        var showS by remember { mutableStateOf(false) }
        var showH by remember { mutableStateOf(false) }
        var showU by remember { mutableStateOf(false) }
        var showL by remember { mutableStateOf(false) }
        
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                AdminCard("Jadwal", Icons.Default.DateRange, MojoBlue, Modifier.weight(1f)) { showS = true }
                AdminCard("Libur", Icons.Default.EventBusy, Color(0xFFC62828), Modifier.weight(1f)) { showH = true }
                AdminCard("Lokasi", Icons.Default.LocationOn, Color(0xFF673AB7), Modifier.weight(1f)) { showL = true }
                AdminCard("Users", Icons.Default.People, Color(0xFF2E7D32), Modifier.weight(1f)) { showU = true }
            }
            
            // Indikator Status Sinkronisasi Minimalis untuk Admin
            Surface(
                color = if (syncPercent > 90) MojoGreen.copy(0.05f) else MojoRed.copy(0.05f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp), 
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        if (syncPercent > 90) Icons.Default.CloudDone else Icons.Default.CloudSync, 
                        null, 
                        tint = if (syncPercent > 90) MojoGreen else MojoRed,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (syncPercent > 90) "Database Sinkron ($syncPercent%)" else "Database Perlu Refresh ($syncPercent%)", 
                        fontSize = 10.sp, 
                        fontWeight = FontWeight.Bold,
                        color = if (syncPercent > 90) MojoGreen else MojoRed
                    )
                }
            }

            Button(
                onClick = onScan,
                enabled = !isHoliday,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(MojoYellow),
                elevation = ButtonDefaults.buttonElevation(4.dp)
            ) {
                Icon(Icons.Default.QrCodeScanner, null, tint = MojoBlue)
                Spacer(Modifier.width(8.dp))
                Text("SCAN QR HADIR", color = MojoBlue, fontWeight = FontWeight.Bold)
            }
        }
        if (showS) JadwalDialog(session) { showS = false }
        if (showH) HolidayDialog(session) { showH = false }
        if (showU) UserManagementDialog(session) { showU = false }
        if (showL) LocationConfigDialog(session) { showL = false }
    }

    /**
     * Kartu menu kecil untuk fitur Admin.
     */
    @Composable
    fun AdminCard(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Card(modifier.clickable { onClick() }, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(color)) {
            Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(4.dp))
                Text(label, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    /**
     * Dialog Manajemen Pengguna (Tambah/Hapus Admin dan Guru).
     * Digunakan oleh Admin untuk mengelola akun akses aplikasi.
     */
    @Composable
    fun UserManagementDialog(session: SessionManager, onDismiss: () -> Unit) {
        var list by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        var load by remember { mutableStateOf(true) }
        var isActionLoading by remember { mutableStateOf(false) }
        var deletingUser by remember { mutableStateOf<String?>(null) }
        
        var u by remember { mutableStateOf("") }; var p by remember { mutableStateOf("") }; var r by remember { mutableStateOf("guru") }
        var selectedKls by remember { mutableStateOf<Set<String>>(emptySet()) }
        var availableClasses by remember { mutableStateOf<List<String>>(emptyList()) }
        
        /**
         * Mengambil daftar user dari server.
         */
        fun fetch() { 
            load = true
            apiCall(session.getBackendUrl(), JSONObject().apply { put("action", "get_users"); put("token", session.getToken()) }) { s, res -> 
                if (s) { 
                    val a = res.getJSONArray("data")
                    val l = mutableListOf<JSONObject>()
                    for (i in 0 until a.length()) l.add(a.getJSONObject(i))
                    list = l 
                } 
                load = false 
            } 
            // Ambil daftar kelas yang tersedia untuk akses Guru
            apiCall(session.getBackendUrl(), JSONObject().apply { put("action", "get_available_classes"); put("token", session.getToken()) }) { s, res ->
                if (s) {
                    val arr = res.getJSONArray("data")
                    val temp = mutableListOf<String>()
                    for (i in 0 until arr.length()) temp.add(arr.getString(i))
                    availableClasses = temp
                }
            }
        }
        LaunchedEffect(Unit) { fetch() }

        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss, enabled = !isActionLoading) { Text("Tutup") } },
            title = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.GroupAdd, null, tint = MojoBlue, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Manajemen User", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(u, { u = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true)
                    OutlinedTextField(p, { p = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), singleLine = true)
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) { 
                        FilterChip(r == "admin", { r = "admin" }, { Text("Admin") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp))
                        FilterChip(r == "guru", { r = "guru" }, { Text("Guru") }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp))
                    }
                    if (r == "guru") {
                        Text("Pilih Akses Kelas:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MojoBlue)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            availableClasses.forEach { cls -> FilterChip(selectedKls.contains(cls), { selectedKls = if (selectedKls.contains(cls)) selectedKls - cls else selectedKls + cls }, { Text(cls) }) }
                        }
                    }
                    Button(
                        onClick = {
                            if (u.isNotEmpty() && p.isNotEmpty()) {
                                isActionLoading = true
                                val kls = selectedKls.joinToString(",")
                                apiCall(session.getBackendUrl(), JSONObject().apply { 
                                    put("action", "add_user"); put("token", session.getToken())
                                    put("new_user", u); put("new_pass", p); put("role", r); put("kelas", kls) 
                                }) { s, _ -> 
                                    isActionLoading = false
                                    if (s) { u = ""; p = ""; fetch() } 
                                }
                            }
                        }, 
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isActionLoading && u.isNotEmpty() && p.isNotEmpty(),
                        shape = RoundedCornerShape(12.dp)
                    ) { 
                        if (isActionLoading && deletingUser == null) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Tambah User") 
                        }
                    }
                    
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    
                    if (load) {
                        Box(Modifier.fillMaxWidth().height(150.dp), Alignment.Center) { CircularProgressIndicator(color = MojoBlue) }
                    } else {
                        LazyColumn(Modifier.height(250.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { 
                            items(list) { item -> 
                                val username = item.getString("username")
                                val role = item.getString("role")
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(Color.White),
                                    border = BorderStroke(1.dp, Color(0xFFEEEEEE))
                                ) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(40.dp).background(if(role.lowercase() == "admin") MojoBlue.copy(0.1f) else Color(0xFFE8F5E9), CircleShape), contentAlignment = Alignment.Center) {
                                            Icon(if(role.lowercase() == "admin") Icons.Default.AdminPanelSettings else Icons.Default.School, null, tint = if(role.lowercase() == "admin") MojoBlue else Color(0xFF2E7D32), modifier = Modifier.size(20.dp))
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(username.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold, color = MojoBlue)
                                            Text("${role.uppercase()} | Kelas: ${item.optString("kelas", "-")}", fontSize = 11.sp, color = Color.Gray)
                                        }
                                        if (deletingUser == username) {
                                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.Red)
                                        } else {
                                            IconButton(
                                                onClick = { 
                                                    deletingUser = username
                                                    apiCall(session.getBackendUrl(), JSONObject().apply { 
                                                        put("action", "delete_user"); put("token", session.getToken()); put("target_user", username) 
                                                    }) { _, _ -> deletingUser = null; fetch() } 
                                                },
                                                enabled = deletingUser == null && !isActionLoading
                                            ) { 
                                                Icon(Icons.Default.Delete, null, tint = Color.Red.copy(0.7f), modifier = Modifier.size(20.dp)) 
                                            } 
                                        }
                                    }
                                }
                            } 
                        }
                    }
                }
            }
        )
    }

    /**
     * Dialog Sinkronisasi Jadwal: Mengatur jam masuk/pulang per hari (Senin-Minggu).
     */
    @Composable
    fun JadwalDialog(session: SessionManager, onDismiss: () -> Unit) {
        val ctx = LocalContext.current
        var load by remember { mutableStateOf(true) }
        var isSaving by remember { mutableStateOf(false) }
        var selDay by remember { mutableStateOf("1") }

        // Objek JSON lengkap dari backend
        var configData by remember { mutableStateOf<JSONObject?>(null) }
        
        var m1 by remember { mutableStateOf("") }
        var m2 by remember { mutableStateOf("") }
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        var isLibur by remember { mutableStateOf(false) }

        /**
         * Memperbarui variabel input UI berdasarkan data yang ada di objek configData sesuai hari yang dipilih.
         */
        fun refreshInputs() {
            val root = configData ?: return
            var dataObj: JSONObject? = root
            while (dataObj != null && !dataObj.has("jadwal_harian") && dataObj.has("data")) {
                dataObj = dataObj.optJSONObject("data")
            }
            val finalObj = dataObj ?: root
            val rawHarian = finalObj.opt("jadwal_harian")
            val harianJson = when (rawHarian) {
                is JSONObject -> rawHarian
                is String -> if (rawHarian.isNotEmpty() && rawHarian != "null") try { JSONObject(rawHarian) } catch (e: Exception) { JSONObject() } else JSONObject()
                else -> JSONObject()
            }

            fun findDayData(json: JSONObject, target: String): JSONObject? {
                if (json.has(target)) return json.optJSONObject(target)
                val names = mapOf("1" to "Senin", "2" to "Selasa", "3" to "Rabu", "4" to "Kamis", "5" to "Jumat", "6" to "Sabtu", "7" to "Minggu")
                val dayName = names[target]
                if (dayName != null && json.has(dayName)) return json.optJSONObject(dayName)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val child = json.optJSONObject(k)
                    if (child != null && child.has(target)) return child.optJSONObject(target)
                }
                return null
            }

            val dayData = findDayData(harianJson, selDay)
            if (dayData != null) {
                m1 = dayData.optString("masuk_mulai", "").replace("null", "").replace(".", ":")
                m2 = dayData.optString("masuk_akhir", "").replace("null", "").replace(".", ":")
                if (m2.isEmpty()) m2 = dayData.optString("masuk", "").replace("null", "").replace(".", ":")
                p1 = dayData.optString("pulang_mulai", "").replace("null", "").replace(".", ":")
                if (p1.isEmpty()) p1 = dayData.optString("pulang", "").replace("null", "").replace(".", ":")
                p2 = dayData.optString("pulang_akhir", "").replace("null", "").replace(".", ":")
                if (p2.isEmpty()) p2 = dayData.optString("pulang_selesai", "").replace("null", "").replace(".", ":")
                isLibur = dayData.optBoolean("libur", false)
            } else {
                m1 = ""; m2 = ""; p1 = ""; p2 = ""; isLibur = false
            }
        }

        LaunchedEffect(configData, selDay) { refreshInputs() }

        fun fetchConfig() {
            load = true
            apiCall(session.getBackendUrl(), JSONObject().apply { put("action", "get_config"); put("token", session.getToken()) }) { success, res ->
                if (success) configData = res
                else Toast.makeText(ctx, "Gagal koneksi", Toast.LENGTH_SHORT).show()
                load = false
            }
        }

        LaunchedEffect(Unit) { fetchConfig() }

        /**
         * Menampilkan TimePickerDialog standar Android.
         */
        fun showTimePicker(current: String, onTimeSelected: (String) -> Unit) {
            val clean = current.replace(".", ":").trim()
            val parts = clean.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 7
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            android.app.TimePickerDialog(ctx, { _, hr, min ->
                onTimeSelected(String.format(Locale.getDefault(), "%02d:%02d", hr, min))
            }, h, m, true).show()
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                Button(
                    onClick = {
                        fun timeToInt(t: String): Int {
                            val clean = t.replace(".", ":").trim()
                            val parts = clean.split(":")
                            if (parts.size < 2) return 0
                            return (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
                        }

                        if (!isLibur) {
                            val vM1 = timeToInt(m1); val vM2 = timeToInt(m2)
                            val vP1 = timeToInt(p1); val vP2 = timeToInt(p2)
                            
                            if (vM1 >= vM2) { Toast.makeText(ctx, "Jam Masuk Akhir harus lebih besar dari Mulai!", Toast.LENGTH_SHORT).show(); return@Button }
                            if (vP1 >= vP2) { Toast.makeText(ctx, "Jam Pulang Akhir harus lebih besar dari Mulai!", Toast.LENGTH_SHORT).show(); return@Button }
                            if (vM2 >= vP1) { Toast.makeText(ctx, "Jam Pulang harus setelah Jam Masuk!", Toast.LENGTH_SHORT).show(); return@Button }
                        }

                        isSaving = true
                        val root = configData ?: JSONObject()
                        var dataObj: JSONObject? = root
                        while (dataObj != null && !dataObj.has("jadwal_harian") && dataObj.has("data")) {
                            dataObj = dataObj.optJSONObject("data")
                        }
                        val targetObj = dataObj ?: root
                        val rawHarian = targetObj.opt("jadwal_harian")
                        val harianJson = when (rawHarian) {
                            is JSONObject -> rawHarian
                            is String -> if (rawHarian.isNotEmpty()) try { JSONObject(rawHarian) } catch (e: Exception) { JSONObject() } else JSONObject()
                            else -> JSONObject()
                        }
                        
                        val dayObj = harianJson.optJSONObject(selDay) ?: JSONObject()
                        dayObj.apply {
                            put("masuk_mulai", m1); put("masuk_akhir", m2); put("masuk", m2)
                            put("batas_masuk", m2); put("pulang_mulai", p1); put("pulang", p1)
                            put("pulang_akhir", p2); put("batas_pulang", p2); put("libur", isLibur)
                        }
                        harianJson.put(selDay, dayObj)

                        val cleanJson = JSONObject()
                        for (i in 1..7) {
                            val key = i.toString()
                            if (harianJson.has(key) || key == selDay) {
                                val data = if (key == selDay) dayObj else harianJson.optJSONObject(key)
                                if (data != null) cleanJson.put(key, data)
                            }
                        }

                        val payload = JSONObject().apply {
                            put("action", "update_config"); put("token", session.getToken())
                            val configPayload = JSONObject()
                            configPayload.put("jadwal_harian", cleanJson.toString())
                            configPayload.put("mode_absen", targetObj.optString("mode_absen", "masuk_pulang"))
                            put("configData", configPayload)
                        }

                        apiCall(session.getBackendUrl(), payload) { success, _ ->
                            isSaving = false
                            if (success) {
                                configData = JSONObject(root.toString())
                                Toast.makeText(ctx, "Tersimpan!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = !isSaving
                ) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White)
                    else Text("Simpan")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Tutup") } },
            title = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Update, null, tint = MojoBlue, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Sinkronisasi Jadwal", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                if (load) Box(Modifier.height(300.dp).fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
                else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // FilterChip untuk memilih hari (1=Senin s/d 7=Minggu)
                    Row(Modifier.horizontalScroll(rememberScrollState()).fillMaxWidth()) {
                        listOf("1" to "Sen", "2" to "Sel", "3" to "Rab", "4" to "Kam", "5" to "Jum", "6" to "Sab", "7" to "Min").forEach { (idx, name) ->
                            FilterChip(selDay == idx, { selDay = idx }, { Text(name) }, modifier = Modifier.padding(end = 4.dp))
                        }
                    }

                    // Pengaturan Libur per hari
                    Surface(color = if (isLibur) Color.Red.copy(0.05f) else MojoBlue.copy(0.05f), shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (isLibur) Color.Red.copy(0.2f) else MojoBlue.copy(0.2f))) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Libur Hari Ini", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            Switch(checked = isLibur, onCheckedChange = { isLibur = it })
                        }
                    }

                    if (!isLibur) {
                        TimeRow(
                            label1 = "Jam Masuk Mulai", val1 = m1,
                            label2 = "Jam Masuk Akhir", val2 = m2,
                            on1 = { showTimePicker(m1) { m1 = it } },
                            on2 = { showTimePicker(m2) { m2 = it } }
                        )
                        TimeRow(
                            label1 = "Jam Pulang Mulai", val1 = p1,
                            label2 = "Jam Pulang Akhir", val2 = p2,
                            on1 = { showTimePicker(p1) { p1 = it } },
                            on2 = { showTimePicker(p2) { p2 = it } }
                        )
                    }
                }
            }
        )
    }

    /**
     * Baris input waktu yang terdiri dari dua picker (Mulai & Akhir).
     */
    @Composable
    fun TimeRow(label1: String, val1: String, label2: String, val2: String, on1: (String) -> Unit, on2: (String) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeField(val1, label1, Modifier.weight(1f)) { on1(val1) }
            TimeField(val2, label2, Modifier.weight(1f)) { on2(val2) }
        }
    }

    /**
     * Field input waktu yang hanya dapat diisi melalui picker.
     */
    @Composable
    fun TimeField(value: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Box(modifier) {
            OutlinedTextField(
                value = value, onValueChange = {}, readOnly = true,
                label = { Text(label, fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                trailingIcon = { Icon(Icons.Default.AccessTime, null, Modifier.size(18.dp)) }
            )
            Box(Modifier.matchParentSize().clickable { onClick() })
        }
    }

    /**
     * Dialog Pengaturan Hari Libur (Kalender/Tanggal Merah).
     */
    @Composable
    fun HolidayDialog(session: SessionManager, onDismiss: () -> Unit) {
        val ctx = LocalContext.current
        var list by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
        var load by remember { mutableStateOf(true) }
        var isActionLoading by remember { mutableStateOf(false) }
        var deletingDate by remember { mutableStateOf<String?>(null) }
        var t by remember { mutableStateOf("") }
        var k by remember { mutableStateOf("") }

        fun fetch() { 
            load = true
            apiCall(session.getBackendUrl(), JSONObject().apply { put("action", "get_holidays"); put("token", session.getToken()) }) { s, res -> 
                if (s) { 
                    val a = res.getJSONArray("data")
                    val l = mutableListOf<JSONObject>()
                    for (i in 0 until a.length()) l.add(a.getJSONObject(i))
                    list = l 
                } 
                load = false 
            } 
        }
        LaunchedEffect(Unit) { fetch() }

        val showDatePicker = {
            val cal = Calendar.getInstance()
            DatePickerDialog(ctx, { _, y, m, d -> t = String.format(Locale.getDefault(), "%02d-%02d-%04d", d, m + 1, y) }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss, enabled = !isActionLoading) { Text("Tutup") } },
            title = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Event, null, tint = Color(0xFFC62828), modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Hari Libur Sekolah", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = t,
                            onValueChange = { t = it },
                            label = { Text("Pilih Tanggal") },
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = { Icon(Icons.Default.CalendarToday, null) }
                        )
                        Box(
                            Modifier
                                .matchParentSize()
                                .clickable(enabled = !isActionLoading) { showDatePicker() }
                        )
                    }
                    OutlinedTextField(value = k, onValueChange = { k = it }, label = { Text("Keterangan") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    Button(onClick = { 
                        if (t.isNotEmpty() && k.isNotEmpty()) {
                            isActionLoading = true
                            apiCall(session.getBackendUrl(), JSONObject().apply { put("action", "add_holiday"); put("token", session.getToken()); put("tanggal", t); put("keterangan", k) }) { _, _ -> t = ""; k = ""; isActionLoading = false; fetch() } 
                        }
                    }, modifier = Modifier.fillMaxWidth(), enabled = !isActionLoading && t.isNotEmpty()) { Text("Tambah") }
                    
                    HorizontalDivider()
                    
                    if (load) Box(Modifier.fillMaxWidth().height(150.dp), Alignment.Center) { CircularProgressIndicator() }
                    else {
                        LazyColumn(Modifier.height(250.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { 
                            items(list) { l -> 
                                val date = l.getString("tanggal")
                                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(Color.White), border = BorderStroke(1.dp, Color(0xFFEEEEEE))) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) { Text(l.getString("keterangan"), fontWeight = FontWeight.Bold); Text(date, fontSize = 11.sp, color = Color.Gray) }
                                        if (deletingDate == date) CircularProgressIndicator(Modifier.size(24.dp))
                                        else IconButton(onClick = { deletingDate = date; apiCall(session.getBackendUrl(), JSONObject().apply { put("action", "delete_holiday"); put("token", session.getToken()); put("tanggal", date) }) { _, _ -> deletingDate = null; fetch() } }) { Icon(Icons.Default.Delete, null, tint = Color.Red.copy(0.7f)) }
                                    }
                                }
                            } 
                        } 
                    }
                }
            }
        )
    }

    /**
     * Tampilan saat data kosong atau gagal dimuat.
     */
    @Composable
    fun EmptyStateView() = Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.CloudOff, null, Modifier.size(80.dp), Color.LightGray); Spacer(Modifier.height(16.dp))
        Text("Tidak Ada Data", color = Color.Gray, fontWeight = FontWeight.Bold)
    }

    /**
     * Item Siswa dalam daftar dengan deteksi warna status (Hadir, Sakit, Izin, Alpa).
     */
    @Composable
    fun SiswaItem(s: Siswa, canEdit: Boolean, isLoading: Boolean, holidayMsg: String? = null, onAct: (String, String) -> Unit) {
        var menu by remember { mutableStateOf(false) }
        val ctx = LocalContext.current
        
        // Cek apakah siswa sudah memiliki status hadir (H, TL, atau LS)
        val hasHadirStatus = s.status == "H" || s.status == "TL" || s.status == "LS" || s.status.contains("Hadir", true)
        
        // Input manual (Sakit, Izin, Alpa) hanya bisa jika belum ada status APAPUN (status == "-" atau "")
        val canInputManual = canEdit && (s.status == "-" || s.status == "" || s.status == "null")

        val isLupaMasuk = s.keterangan.contains("Lupa Absen Masuk", true) || s.status == "LS"
        val isTerlambat = s.keterangan.contains("Terlambat", true) || s.status == "TL"

        val (statusLabel, statusColor, statusIcon) = when {
            isLupaMasuk -> Triple("Hadir (Lupa Absen Masuk)", Color(0xFF7E57C2), Icons.Default.RunningWithErrors) // Ungu
            isTerlambat -> Triple("Hadir Terlambat", Color(0xFFFB8C00), Icons.Default.History) // Oranye
            s.status.contains("Hadir", true) || s.status == "H" -> Triple("Hadir Tepat Waktu", MojoGreen, Icons.Default.CheckCircle)
            s.status.contains("Sakit", true) || s.status == "S" -> Triple("Sakit", Color(0xFFFFD700), Icons.Default.MedicalServices) // Kuning Emas
            s.status.contains("Izin", true) || s.status == "I" -> Triple("Izin", Color(0xFF03A9F4), Icons.Default.Description) // Biru Langit
            s.status.contains("Alpa", true) || s.status == "A" -> Triple("Alpa", MojoRed, Icons.Default.Cancel)
            else -> Triple("Belum Absen", Color.Gray, Icons.Default.Pending)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { 
                    if (canInputManual && !isLoading) {
                        if (holidayMsg != null) {
                            Toast.makeText(ctx, "Absensi ditutup: $holidayMsg", Toast.LENGTH_SHORT).show()
                        } else {
                            menu = true 
                        }
                    } else if (hasHadirStatus) {
                        Toast.makeText(ctx, "Siswa sudah memiliki status Hadir", Toast.LENGTH_SHORT).show()
                    }
                },
            shape = RoundedCornerShape(12.dp), 
            colors = CardDefaults.cardColors(Color.White), 
            elevation = CardDefaults.cardElevation(1.dp)
        ) {
            Box {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(4.dp).height(64.dp).background(statusColor))
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(statusColor.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.padding(vertical = 8.dp, horizontal = 10.dp).weight(1f)) {
                        Text(
                            text = s.nama, 
                            fontWeight = FontWeight.Bold, 
                            color = MojoBlue, 
                            fontSize = 14.sp, 
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "NISN: ${s.nisn}", fontSize = 10.sp, color = Color.Gray)
                            Spacer(Modifier.width(8.dp))
                            // Badge status lengkap
                            Surface(color = statusColor.copy(0.1f), shape = RoundedCornerShape(4.dp)) {
                                Text(" $statusLabel ", fontSize = 9.sp, fontWeight = FontWeight.Black, color = statusColor)
                            }
                        }
                    }
                    Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isLoading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = statusColor)
                        } else {
                             // Jam hanya muncul jika bukan status manual (S, I, A) dan bukan Belum Absen
                             val isManualStatus = s.status == "S" || s.status == "I" || s.status == "A"
                             val isBelumAbsen = s.status == "-" || s.status == "" || s.status == "null"
                             
                             if (!isManualStatus && !isBelumAbsen) {
                                 TimeBadgeCompact(s.jamMasuk, Icons.AutoMirrored.Filled.Login, MojoBlue)
                                 Spacer(Modifier.width(6.dp))
                                 TimeBadgeCompact(s.jamPulang, Icons.AutoMirrored.Filled.Logout, MojoRed)
                             } else if (isManualStatus) {
                                 Text("-", fontWeight = FontWeight.Bold, color = Color.Gray, fontSize = 12.sp)
                             }
                        }
                    }
                }
            }
        }

        if (menu) {
            AlertDialog(onDismissRequest = { menu = false }, confirmButton = {}, title = { Text("Aksi Presensi Manual") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatusOption("Sakit", Icons.Default.MedicalServices, Color(0xFFFFD700)) { onAct("S", "Manual App"); menu = false }
                        StatusOption("Izin", Icons.Default.Description, Color(0xFF03A9F4)) { onAct("I", "Manual App"); menu = false }
                        StatusOption("Alpa", Icons.Default.Cancel, MojoRed) { onAct("A", "Manual App"); menu = false }
                    }
                }
            )
        }
    }

    /**
     * Badge waktu kecil untuk menampilkan jam masuk dan pulang di daftar siswa.
     */
    @Composable
    fun TimeBadgeCompact(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(10.dp), tint = color.copy(0.5f))
            Text(text, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color)
        }
    }

    /**
     * Menampilkan statistik ringkasan kehadiran di dashboard (Hadir, Sakit, Izin, Alpa).
     */
    @Composable
    fun SummaryStats(list: List<Siswa>) {
        val hadir = list.count { it.status == "H" || it.status.contains("Hadir", true) }
        val terlambat = list.count { it.status == "TL" || it.keterangan.contains("Terlambat", true) }
        val lupa = list.count { it.status == "LS" || it.keterangan.contains("Lupa Absen Masuk", true) }
        val sakit = list.count { it.status == "S" || it.status.contains("Sakit", true) }
        val izin = list.count { it.status == "I" || it.status.contains("Izin", true) }
        val alpa = list.count { it.status == "A" || it.status.contains("Alpa", true) }
        val belum = list.size - (hadir + terlambat + lupa + sakit + izin + alpa)

        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text("Ringkasan Kehadiran", fontWeight = FontWeight.Black, color = MojoBlue, fontSize = 14.sp)
                        Surface(color = MojoBlue.copy(0.1f), shape = CircleShape) {
                            Text(" Total: ${list.size} ", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MojoBlue, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceAround) {
                        StatItem("Hadir", hadir.toString(), MojoGreen)
                        StatItem("Terlambat", terlambat.toString(), Color(0xFFFB8C00)) // Jingga/Orange
                        StatItem("Lupa Absen Masuk", lupa.toString(), Color(0xFF7E57C2)) // Ungu
                        StatItem("Sakit", sakit.toString(), Color(0xFFFFD700)) // Kuning Emas
                        StatItem("Izin", izin.toString(), Color(0xFF03A9F4)) // Biru Langit
                        StatItem("Alpa", alpa.toString(), MojoRed)
                        StatItem("Belum Absen", belum.toString(), Color.Gray)
                    }
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            // Detail Row for TL and LS
            if (terlambat > 0 || lupa > 0) {
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                    if (terlambat > 0) MiniStatCard("Terlambat: $terlambat", Color(0xFFFB8C00), Icons.Default.History, Modifier.weight(1f))
                    if (lupa > 0) MiniStatCard("Lupa Scan: $lupa", Color(0xFF7E57C2), Icons.Default.RunningWithErrors, Modifier.weight(1f))
                }
            }
        }
    }

    /**
     * Kartu statistik mini untuk informasi tambahan seperti 'Terlambat' atau 'Lupa Scan'.
     */
    @Composable
    fun MiniStatCard(label: String, color: Color, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
        Surface(
            color = color.copy(0.1f),
            shape = RoundedCornerShape(8.dp),
            modifier = modifier,
            border = BorderStroke(0.5.dp, color.copy(0.2f))
        ) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
            }
        }
    }

    /**
     * Item statistik individu (Angka & Label).
     */
    @Composable
    fun StatItem(label: String, value: String, color: Color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Black, color = color)
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
        }
    }

    @Composable
    fun TimeBadge(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
        Surface(
            color = color.copy(alpha = 0.08f), 
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(0.5.dp, color.copy(alpha = 0.15f))
        ) {
            Row(
                Modifier.padding(horizontal = 6.dp, vertical = 3.dp), 
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, Modifier.size(10.dp), tint = color)
                Spacer(Modifier.width(4.dp))
                Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
            }
        }
    }

    /**
     * Opsi status presensi di dialog manual (Hadir, Sakit, dsb).
     */
    @Composable
    fun StatusOption(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit) {
        Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = color.copy(0.08f), border = BorderStroke(1.dp, color.copy(0.15f))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(color, CircleShape).padding(8.dp)) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                Spacer(Modifier.width(16.dp)); Text(label, fontWeight = FontWeight.ExtraBold, color = color, modifier = Modifier.weight(1f))
            }
        }
    }

    /**
     * Komponen pemuat logo dinamis: memuat dari cache jika ada, jika tidak gunakan logo default.
     */
    @Composable
    fun DynamicLogo(session: SessionManager, modifier: Modifier) {
        val path = session.getLogoPath()
        val painter = if (!path.isNullOrEmpty() && File(path).exists()) rememberAsyncImagePainter(File(path))
        else painterResource(R.drawable.logomojogemi2)
        Image(painter = painter, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
    }

    /**
     * Dialog Identitas Aplikasi: Mengatur nama sekolah, URL backend, dan logo kustom.
     */
    @Composable
    fun AppConfigDialog(session: SessionManager, onDismiss: () -> Unit) {
        val context = LocalContext.current
        var name by remember { mutableStateOf(session.getSchoolName()) }
        var url by remember { mutableStateOf(session.getBackendUrl()) }
        var logoPath by remember { mutableStateOf(session.getLogoPath() ?: "") }

        var testLoading by remember { mutableStateOf(false) }
        var testMsg by remember { mutableStateOf("") }
        
        var showCropper by remember { mutableStateOf<Uri?>(null) }
        
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { showCropper = it }
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { 
                Button(
                    onClick = { 
                        // Simpan lokal
                        session.saveConfig(
                            name, url, logoPath.ifEmpty { null }, 
                            session.getJadwal(), 
                            session.getSchoolLat(),
                            session.getSchoolLng(),
                            session.getSchoolRadius()
                        )
                        (context as? android.app.Activity)?.recreate() 
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("Simpan Perubahan") } 
            },
            dismissButton = { TextButton(onDismiss) { Text("Tutup") } },
            title = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        color = MojoBlue.copy(alpha = 0.1f),
                        shape = CircleShape,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Settings, null, tint = MojoBlue, modifier = Modifier.size(32.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Pengaturan Aplikasi", fontWeight = FontWeight.Black, fontSize = 20.sp, color = MojoBlue)
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 8.dp).verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nama Sekolah") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), leadingIcon = { Icon(Icons.Default.School, null, tint = MojoBlue) })
                    
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL Backend") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), leadingIcon = { Icon(Icons.Default.Link, null, tint = MojoBlue) })

                    Button(
                        onClick = { 
                            testLoading = true; testMsg = ""
                            apiCall(url, JSONObject().apply { put("action", "ping") }) { s, _ -> testLoading = false; testMsg = if(s) "Koneksi Berhasil!" else "Koneksi Gagal!" } 
                        }, 
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MojoBlue.copy(alpha = 0.1f), contentColor = MojoBlue)
                    ) {
                        if(testLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MojoBlue)
                        else Text("Tes Koneksi Server", fontWeight = FontWeight.Bold)
                    }
                    
                    if(testMsg.isNotEmpty()) Text(testMsg, color = if(testMsg.contains("Berhasil")) MojoGreen else MojoRed, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                    
                    HorizontalDivider(color = Color.LightGray.copy(alpha = 0.5f))
                    
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("Logo Sekolah", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Spacer(Modifier.height(12.dp))
                        Box(contentAlignment = Alignment.BottomEnd) {
                            Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, Color.LightGray), modifier = Modifier.size(100.dp)) { DynamicLogo(session, Modifier.padding(12.dp)) }
                            IconButton(onClick = { launcher.launch("image/*") }, modifier = Modifier.size(32.dp).offset(x = 8.dp, y = 8.dp).background(MojoYellow, CircleShape).border(2.dp, Color.White, CircleShape)) { Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp), tint = MojoBlue) }
                        }
                    }
                }
            }
        )

        if (showCropper != null) {
            LogoCropperDialog(showCropper!!, { showCropper = null }) { croppedFile ->
                logoPath = croppedFile.absolutePath
                showCropper = null
            }
        }
    }

    /**
     * Dialog Konfigurasi Lokasi Sekolah (GPS Geofence).
     * Dilengkapi dengan Peta Visual dan Slider Radius.
     */
    @Composable
    fun LocationConfigDialog(session: SessionManager, onDismiss: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        
        // Memaksa pengambilan nilai terbaru dari session saat dialog dibuka
        var lat by remember { mutableStateOf(session.getSchoolLat()) }
        var lng by remember { mutableStateOf(session.getSchoolLng()) }
        var radius by remember { mutableStateOf(session.getSchoolRadius().toFloat()) }
        var isSaving by remember { mutableStateOf(false) }

        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(LatLng(lat, lng), 17f)
        }

        // Sinkronisasi ulang koordinat jika data di session manager berubah
        LaunchedEffect(session.getSchoolLat(), session.getSchoolLng()) {
            lat = session.getSchoolLat()
            lng = session.getSchoolLng()
            cameraPositionState.position = CameraPosition.fromLatLngZoom(LatLng(lat, lng), 17f)
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                Button(
                    onClick = {
                        isSaving = true
                        val dLat = lat
                        val dLng = lng
                        val dRad = radius.toDouble()

                        // Simpan lokal dulu agar jika offline tetap aman
                        session.saveConfig(session.getSchoolName(), session.getBackendUrl(), session.getLogoPath(), session.getJadwal(), dLat, dLng, dRad)
                        
                        // Kirim ke Database Server (Google Sheets)
                        val payload = JSONObject().apply {
                            put("action", "update_geofence")
                            put("token", session.getToken())
                            put("lat", dLat)
                            put("lng", dLng)
                            put("radius", dRad)
                        }
                        
                        // Gunakan URL dari session manager
                        val serverUrl = session.getBackendUrl()
                        
                        apiCall(serverUrl, payload) { s, res ->
                            isSaving = false
                            if (s) {
                                Toast.makeText(context, "Lokasi Berhasil Disinkronkan ke Server!", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            } else {
                                val errorMsg = res.optString("message", "Koneksi Gagal")
                                Toast.makeText(context, "Gagal ke Server ($errorMsg). Tersimpan Lokal.", Toast.LENGTH_LONG).show()
                                onDismiss()
                            }
                        }
                    },
                    enabled = !isSaving,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isSaving) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White)
                    else Text("Simpan Lokasi")
                }
            },
            dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Batal") } },
            title = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.LocationOn, null, tint = Color(0xFF673AB7), modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Pengaturan Area Presensi", fontWeight = FontWeight.Black)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Tampilan Peta
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, Color.LightGray, RoundedCornerShape(16.dp))
                    ) {
                        GoogleMap(
                            modifier = Modifier.fillMaxSize(),
                            cameraPositionState = cameraPositionState,
                            properties = MapProperties(
                                isMyLocationEnabled = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
                                mapType = MapType.HYBRID // Menampilkan citra satelit dengan label (Tampilan Real/Asli)
                            ),
                            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false),
                            onMapClick = { point ->
                                lat = point.latitude
                                lng = point.longitude
                            }
                        ) {
                            // Marker Titik Sekolah
                            val markerState = rememberMarkerState(position = LatLng(lat, lng))
                            
                            // Animasi kamera saat koordinat berubah (Otomatis geser saat klik icon lokasi presisi)
                            LaunchedEffect(lat, lng) {
                                cameraPositionState.animate(
                                    update = com.google.android.gms.maps.CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), 17f),
                                    durationMs = 1000
                                )
                                markerState.position = LatLng(lat, lng)
                            }

                            Marker(
                                state = markerState,
                                title = "Titik Pusat Absensi"
                            )
                            // Lingkaran Radius
                            Circle(
                                center = LatLng(lat, lng),
                                radius = radius.toDouble(),
                                fillColor = Color(0xFF673AB7).copy(alpha = 0.2f),
                                strokeColor = Color(0xFF673AB7),
                                strokeWidth = 2f
                            )
                        }
                        
                        // Tombol Ambil Lokasi di Pojok Peta
                        FloatingActionButton(
                            onClick = {
                                scope.launch {
                                    if (!locationHelper.isLocationEnabled()) {
                                        Toast.makeText(context, "GPS Mati atau Izin Ditolak!", Toast.LENGTH_LONG).show()
                                        return@launch
                                    }
                                    Toast.makeText(context, "Mencari Lokasi Presisi...", Toast.LENGTH_SHORT).show()
                                    val loc = locationHelper.getCurrentLocation()
                                    if (loc != null) {
                                        lat = loc.latitude
                                        lng = loc.longitude
                                    } else {
                                        Toast.makeText(context, "Gagal mengunci GPS. Pastikan berada di ruang terbuka.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(44.dp),
                            containerColor = MojoYellow,
                            contentColor = MojoBlue
                        ) {
                            Icon(Icons.Default.MyLocation, null, modifier = Modifier.size(20.dp))
                        }
                    }

                    // Kontrol Slider Radius
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF673AB7).copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text("Radius Jangkauan", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF673AB7))
                            Surface(color = Color(0xFF673AB7), shape = CircleShape) {
                                Text(" ${radius.toInt()} meter ", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                            }
                        }
                        Slider(
                            value = radius,
                            onValueChange = { radius = it },
                            valueRange = 20f..500f,
                            steps = 48,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF673AB7), activeTrackColor = Color(0xFF673AB7))
                        )
                        Text(
                            "Geser untuk memperbesar/kecil area hijau di peta. Siswa hanya bisa absen di dalam area tersebut.",
                            fontSize = 10.sp, color = Color.Gray, lineHeight = 14.sp
                        )
                    }
                    
                    // Input Koordinat Manual (Optional View)
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("LATITUDE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Text(String.format("%.6f", lat), fontSize = 12.sp, fontWeight = FontWeight.Black)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("LONGITUDE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                            Text(String.format("%.6f", lng), fontSize = 12.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        )
    }

    /**
     * Dialog untuk Crop dan Zoom Logo
     */
    @Composable
    fun LogoCropperDialog(uri: Uri, onDismiss: () -> Unit, onSave: (File) -> Unit) {
        val context = LocalContext.current
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val state = rememberTransformableState { zoomChange, offsetChange, _ ->
            scale *= zoomChange
            offset += offsetChange
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                Button(onClick = {
                    try {
                        val input = context.contentResolver.openInputStream(uri)
                        val original = BitmapFactory.decodeStream(input)
                        
                        // Logika Crop berdasarkan scale dan offset
                        val size = 512
                        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bitmap)
                        
                        val matrix = Matrix()
                        // Hitung transformasi agar sesuai dengan apa yang dilihat user
                        val centerX = original.width / 2f
                        val centerY = original.height / 2f
                        
                        matrix.postTranslate(-centerX, -centerY)
                        matrix.postScale(scale * (size.toFloat() / original.width), scale * (size.toFloat() / original.width))
                        matrix.postTranslate(size / 2f + offset.x * (size.toFloat() / 300.dp.value), size / 2f + offset.y * (size.toFloat() / 300.dp.value))
                        
                        canvas.drawBitmap(original, matrix, null)
                        
                        val file = File(context.filesDir, "custom_logo.png")
                        val out = FileOutputStream(file)
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.close()
                        onSave(file)
                    } catch (e: Exception) { e.printStackTrace() }
                }) { Text("Gunakan") }
            },
            dismissButton = { TextButton(onDismiss) { Text("Batal") } },
            title = { Text("Atur & Zoom Logo") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Gunakan dua jari untuk memperbesar/geser", fontSize = 11.sp, color = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    Box(
                        Modifier
                            .size(250.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(0.05f))
                            .border(1.dp, MojoBlue.copy(0.2f), RoundedCornerShape(12.dp))
                            .transformable(state = state)
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                ),
                            contentScale = ContentScale.Fit
                        )
                        // Garis bantu center (Crosshair)
                        Canvas(Modifier.fillMaxSize()) {
                            val strokeWidth = 1.dp.toPx()
                            val color = MojoBlue.copy(alpha = 0.3f)
                            // Garis Vertikal Tengah
                            drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth)
                            // Garis Horizontal Tengah
                            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth)
                            
                            // Kotak bantu di tengah
                            drawRect(
                                color = color,
                                topLeft = Offset(size.width * 0.25f, size.height * 0.25f),
                                size = size * 0.5f,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(strokeWidth)
                            )
                        }
                    }
                }
            }
        )
    }

    /**
     * Fungsi pembantu untuk pemanggilan API menggunakan OkHttp secara asinkron.
     */
    private fun apiCall(url: String, json: JSONObject, callback: (Boolean, JSONObject) -> Unit) {
        val body = json.toString().toRequestBody("application/json".toMediaType())
        client.newBuilder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            .newCall(Request.Builder().url(url).post(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { 
                runOnUiThread { callback(false, JSONObject().put("message", "Koneksi Gagal: ${e.message}")) } 
            }
            override fun onResponse(call: Call, response: Response) {
                try { 
                    val responseBody = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        runOnUiThread { callback(false, JSONObject().put("message", "Server Error (${response.code})")) }
                        return
                    }
                    val res = JSONObject(responseBody)
                    runOnUiThread { callback(res.optBoolean("success", false), res) } 
                } catch (ex: Exception) { 
                    runOnUiThread { callback(false, JSONObject().put("message", "Data Tidak Valid")) } 
                }
            }
        })
    }

    /**
     * Menjadwalkan notifikasi pengingat absensi berdasarkan jadwal harian.
     */
    private fun scheduleAttendanceNotifications(config: JSONObject) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // Cek izin alarm tepat di Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                // Gunakan flag di level class dan SharedPreferences agar tidak terbuka berkali-kali
                if (isAlarmPermissionRequestInFlight) return
                
                val prefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                val hasRequested = prefs.getBoolean("requested_alarm_permission", false)
                
                if (!hasRequested) {
                    isAlarmPermissionRequestInFlight = true
                    prefs.edit().putBoolean("requested_alarm_permission", true).commit() // Gunakan commit() agar langsung tersimpan
                    
                    runOnUiThread {
                        Toast.makeText(this, "Mohon izinkan 'Alarm & Pengingat' untuk notifikasi absen", Toast.LENGTH_LONG).show()
                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        startActivity(intent)
                    }
                }
                return
            }
        }

        fun findJadwal(obj: JSONObject?): JSONObject? {
            if (obj == null) return null
            if (obj.has("jadwal_harian")) return obj
            val keys = obj.keys()
            while (keys.hasNext()) {
                val v = obj.opt(keys.next())
                if (v is JSONObject) {
                    val found = findJadwal(v)
                    if (found != null) return found
                }
            }
            return null
        }

        val targetObj = findJadwal(config)
        val rawJadwal = targetObj?.opt("jadwal_harian")
        val jadwalStr = when (rawJadwal) {
            is String -> rawJadwal
            is JSONObject -> rawJadwal.toString()
            else -> ""
        }
        
        // Simpan jadwal ke local untuk reschedule saat boot
        if (jadwalStr.isNotEmpty()) {
            val session = SessionManager(this)
            session.saveConfig(session.getSchoolName(), session.getBackendUrl(), session.getLogoPath(), jadwalStr)
        }

        val jadwal = if (jadwalStr.isNotEmpty() && jadwalStr != "null") try { JSONObject(jadwalStr) } catch(e: Exception) { JSONObject() } else JSONObject()

        val now = Calendar.getInstance()
        val gasDayIdx = when(now.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "1"; Calendar.TUESDAY -> "2"; Calendar.WEDNESDAY -> "3"; Calendar.THURSDAY -> "4"
            Calendar.FRIDAY -> "5"; Calendar.SATURDAY -> "6"; Calendar.SUNDAY -> "7"; else -> "1"
        }

        val todaySched = jadwal.optJSONObject(gasDayIdx) ?: return
        if (todaySched.optBoolean("libur", false)) return

        val times = listOf(
            Triple(todaySched.optString("masuk_mulai"), "Absensi DIBUKA", "Silakan lakukan absen masuk sekarang."),
            Triple(todaySched.optString("masuk_akhir"), "Absensi Masuk HAMPIR TUTUP", "Segera lakukan absen agar tidak terlambat."),
            Triple(todaySched.optString("pulang_mulai"), "Absensi Pulang DIBUKA", "Waktunya absen pulang, hati-hati di jalan."),
            Triple(todaySched.optString("pulang_akhir"), "Absensi DITUTUP", "Waktu absensi hari ini telah berakhir.")
        )

        times.forEachIndexed { index, (timeStr, title, msg) ->
            if (timeStr.isNotEmpty() && timeStr != "null") {
                try {
                    val parts = timeStr.replace(".", ":").split(":")
                    if (parts.size >= 2) {
                        val cal = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                            set(Calendar.MINUTE, parts[1].toInt())
                            set(Calendar.SECOND, 0)
                        }
                        
                        // Khusus untuk "Hampir Tutup", kurangi 15 menit
                        if (title.contains("HAMPIR TUTUP")) {
                            cal.add(Calendar.MINUTE, -15)
                        }

                        if (cal.after(Calendar.getInstance())) {
                            val intent = Intent(this, AttendanceNotificationReceiver::class.java).apply {
                                putExtra("title", title)
                                putExtra("message", msg)
                            }
                            val pendingIntent = PendingIntent.getBroadcast(
                                this, index, intent,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    @Composable
    fun QrScannerView(onRes: (String) -> Unit, onDetect: (android.graphics.Rect?) -> Unit = {}) {
        val context = LocalContext.current
        val life = LocalLifecycleOwner.current
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
        var proc by remember { mutableStateOf(false) }

        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                
                val executor = ContextCompat.getMainExecutor(ctx)
                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build().also {
                                it.setAnalyzer(Executors.newSingleThreadExecutor()) { img ->
                                    if (proc) { img.close(); return@setAnalyzer }
                                    val m = img.image ?: return@setAnalyzer
                                    BarcodeScanning.getClient().process(InputImage.fromMediaImage(m, img.imageInfo.rotationDegrees))
                                        .addOnSuccessListener { b ->
                                            val barcode = b.firstOrNull()
                                            onDetect(barcode?.boundingBox) // Kirim posisi kotak ke UI
                                            barcode?.rawValue?.let { value ->
                                                proc = true
                                                onRes(value)
                                            }
                                        }
                                        .addOnCompleteListener { img.close() }
                                }
                            }
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(life, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    } catch (e: Exception) { e.printStackTrace() }
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Pastikan kamera mati saat keluar dari layar scan agar tidak "nyangkut"
        DisposableEffect(life) {
            onDispose {
                try {
                    cameraProviderFuture.get().unbindAll()
                } catch (e: Exception) {}
            }
        }
    }
}
