package com.flipperhome

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Quiet connected-device notification lets Android retain the BLE process in the background. */
class FlipperConnectionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connection get() = (application as FlipperHomeApplication).connection
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,"Flipper connection",NotificationManager.IMPORTANCE_LOW).apply {
            description = "Keeps your Flipper connected while using other apps"
            setSound(null,null); enableVibration(false); setShowBadge(false)
        })
        try { startForeground(ID,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
        catch(e: Exception) { connection.link.status.value = "Couldn't keep Flipper connected: ${e.message}"; stopSelf(); return }
        scope.launch { connection.link.status.collectLatest {
            if(Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) notifications.notify(ID,notification())
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.action == DISCONNECT) { connection.disconnect(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(this,1,Intent(this,FlipperConnectionService::class.java).setAction(DISCONNECT),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle("Flipper Home")
            .setContentText(connection.link.status.value).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null,"Disconnect",disconnect).build()).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { connection.voice.stop(); scope.cancel(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "flipper_connection"
        private const val ID = 1
        private const val DISCONNECT = "com.flipperhome.DISCONNECT"
    }
}
