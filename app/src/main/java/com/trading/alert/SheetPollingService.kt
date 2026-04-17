package com.trading.alert

import android.app.*
import android.content.Intent
import android.media.MediaPlayer
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class SheetPollingService : Service() {

    private val CHANNEL_ID      = "SheetPollingChannel"
    private val NOTIFICATION_ID = 1
    private var pollingJob: Job? = null
    private var lastRowCount     = -1  // -1 = not yet initialized

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sheetId      = intent?.getStringExtra("sheet_id")      ?: return START_NOT_STICKY
        val apiKey       = intent.getStringExtra("api_key")         ?: return START_NOT_STICKY
        val sheetName    = intent.getStringExtra("sheet_name")      ?: "Sheet1"
        val pollInterval = intent.getLongExtra("poll_interval", 5L)

        createNotificationChannel()

        // Show persistent foreground notification
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("📈 TV Alert Monitor")
            .setContentText("Watching Google Sheet for new alerts...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        // Start polling coroutine
        startPolling(sheetId, apiKey, sheetName, pollInterval)

        return START_STICKY
    }

    private fun startPolling(
        sheetId: String,
        apiKey: String,
        sheetName: String,
        intervalSec: Long
    ) {
        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    checkSheet(sheetId, apiKey, sheetName)
                } catch (e: Exception) {
                    broadcastStatus("⚠️ Error: ${e.message}")
                }
                delay(intervalSec * 1000)
            }
        }
    }

    private fun checkSheet(sheetId: String, apiKey: String, sheetName: String) {
        val encodedSheet = java.net.URLEncoder.encode("$sheetName!A:Z", "UTF-8")
        val urlStr = "https://sheets.googleapis.com/v4/spreadsheets" +
                "/$sheetId/values/$encodedSheet?key=$apiKey"

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod  = "GET"
            connectTimeout = 10000
            readTimeout    = 10000
        }

        try {
            val responseCode = conn.responseCode

            if (responseCode == 200) {
                val responseText = conn.inputStream.bufferedReader().readText()
                val json         = JSONObject(responseText)
                val values       = json.optJSONArray("values")
                val rowCount     = values?.length() ?: 0

                when {
                    lastRowCount == -1 -> {
                        // First poll — record baseline count, don't trigger alert
                        lastRowCount = rowCount
                        broadcastStatus("✅ Monitoring active. Current rows: $rowCount")
                    }
                    rowCount > lastRowCount -> {
                        // New row(s) detected!
                        val latestRow = values?.getJSONArray(rowCount - 1)
                        val rowData   = buildString {
                            if (latestRow != null) {
                                for (i in 0 until latestRow.length()) {
                                    if (i > 0) append(" | ")
                                    append(latestRow.getString(i))
                                }
                            }
                        }
                        lastRowCount = rowCount
                        playAlertSound()
                        broadcastAlert("🚨 $rowData")
                        updateNotification("New Alert: $rowData")
                    }
                    else -> {
                        // No new rows
                        val time = java.text.SimpleDateFormat(
                            "HH:mm:ss", java.util.Locale.getDefault()
                        ).format(java.util.Date())
                        broadcastStatus("✅ Watching... Rows: $rowCount | Last check: $time")
                    }
                }
            } else if (responseCode == 403) {
                broadcastStatus("⚠️ API Key error (403). Check key & sheet permissions.")
            } else if (responseCode == 404) {
                broadcastStatus("⚠️ Sheet not found (404). Check Sheet ID.")
            } else {
                broadcastStatus("⚠️ API error: HTTP $responseCode")
            }

        } finally {
            conn.disconnect()
        }
    }

    private fun playAlertSound() {
        try {
            val mp = MediaPlayer.create(this, R.raw.alert_sound)
            mp?.setOnCompletionListener { it.release() }
            mp?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun broadcastAlert(message: String) {
        val intent = Intent("com.trading.alert.ALERT_RECEIVED").apply {
            putExtra("message", message)
        }
        sendBroadcast(intent)
    }

    private fun broadcastStatus(status: String) {
        val intent = Intent("com.trading.alert.STATUS_UPDATE").apply {
            putExtra("status", status)
        }
        sendBroadcast(intent)
    }

    private fun updateNotification(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔔 TradingView Alert!")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sheet Polling Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors Google Sheet for TradingView alerts"
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
