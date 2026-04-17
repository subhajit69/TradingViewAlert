package com.trading.alert

import android.content.*
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class MainActivity : AppCompatActivity() {

    private lateinit var etSheetId: EditText
    private lateinit var etApiKey: EditText
    private lateinit var etSheetName: EditText
    private lateinit var etPollInterval: EditText
    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private var isRunning = false

    // Receives alert notifications from SheetPollingService
    private val alertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra("message") ?: "New alert!"
            val timestamp = java.text.SimpleDateFormat(
                "HH:mm:ss", java.util.Locale.getDefault()
            ).format(java.util.Date())
            runOnUiThread {
                tvLog.text = "[$timestamp] $message\n" + tvLog.text
                tvStatus.text = "🔔 ALERT: $message"
            }
        }
    }

    // Receives status updates from SheetPollingService
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("status") ?: ""
            runOnUiThread {
                tvStatus.text = status
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etSheetId      = findViewById(R.id.etSheetId)
        etApiKey       = findViewById(R.id.etApiKey)
        etSheetName    = findViewById(R.id.etSheetName)
        etPollInterval = findViewById(R.id.etPollInterval)
        btnToggle      = findViewById(R.id.btnToggle)
        tvStatus       = findViewById(R.id.tvStatus)
        tvLog          = findViewById(R.id.tvLog)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }

        // Load saved settings from encrypted storage
        // Falls back to BuildConfig values (from local.properties / GitHub Secrets)
        val prefs = getEncryptedPrefs()
        etSheetId.setText(prefs.getString("sheet_id", BuildConfig.SHEET_ID))
        etApiKey.setText(prefs.getString("api_key", BuildConfig.SHEETS_API_KEY))
        etSheetName.setText(prefs.getString("sheet_name", "Sheet1"))
        etPollInterval.setText(prefs.getString("poll_interval", "5"))

        btnToggle.setOnClickListener {
            if (!isRunning) startPolling() else stopPolling()
        }

        // Register broadcast receivers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                alertReceiver,
                IntentFilter("com.trading.alert.ALERT_RECEIVED"),
                RECEIVER_NOT_EXPORTED
            )
            registerReceiver(
                statusReceiver,
                IntentFilter("com.trading.alert.STATUS_UPDATE"),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(alertReceiver, IntentFilter("com.trading.alert.ALERT_RECEIVED"))
            registerReceiver(statusReceiver, IntentFilter("com.trading.alert.STATUS_UPDATE"))
        }
    }

    private fun startPolling() {
        val sheetId   = etSheetId.text.toString().trim()
        val apiKey    = etApiKey.text.toString().trim()
        val sheetName = etSheetName.text.toString().trim().ifEmpty { "Sheet1" }
        val interval  = etPollInterval.text.toString().trim().toLongOrNull() ?: 5L

        if (sheetId.isEmpty() || apiKey.isEmpty()) {
            Toast.makeText(this, "Sheet ID and API Key are required!", Toast.LENGTH_SHORT).show()
            return
        }

        // Save securely to encrypted SharedPreferences
        getEncryptedPrefs().edit()
            .putString("sheet_id",      sheetId)
            .putString("api_key",       apiKey)
            .putString("sheet_name",    sheetName)
            .putString("poll_interval", interval.toString())
            .apply()

        // Start the polling service
        val intent = Intent(this, SheetPollingService::class.java).apply {
            putExtra("sheet_id",      sheetId)
            putExtra("api_key",       apiKey)
            putExtra("sheet_name",    sheetName)
            putExtra("poll_interval", interval)
        }
        startForegroundService(intent)

        isRunning      = true
        btnToggle.text = "Stop Monitoring"
        tvStatus.text  = "✅ Starting monitor..."
    }

    private fun stopPolling() {
        stopService(Intent(this, SheetPollingService::class.java))
        isRunning      = false
        btnToggle.text = "Start Monitoring"
        tvStatus.text  = "⛔ Monitoring Stopped"
    }

    // Encrypted SharedPreferences using Android Keystore (AES256)
    private fun getEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            this,
            "secure_tv_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(alertReceiver)
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
