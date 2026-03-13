package com.ntfy.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.ntfy.wear.MainActivity
import com.ntfy.wear.R
import com.ntfy.wear.api.ApiService
import com.ntfy.wear.db.NtfyMessage
import com.ntfy.wear.db.StorageManager
import com.ntfy.wear.db.Subscription
import kotlinx.coroutines.*
import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap

class SubscriberService : Service() {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var storageManager: StorageManager
    private val apiService = ApiService()
    private val activeConnections = ConcurrentHashMap<String, Call>()
    private val notificationHelper by lazy { NotificationHelper(this) }
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false
    
    override fun onCreate() {
        super.onCreate()
        storageManager = StorageManager(this)
        notificationHelper.createNotificationChannel()
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NtfyWear:SubscriberWakeLock"
        )
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startService()
            ACTION_STOP -> stopService()
            ACTION_REFRESH -> refreshConnections()
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        stopService()
        serviceScope.cancel()
        
        // Schedule restart
        sendBroadcast(Intent(this, ServiceRestartReceiver::class.java))
    }
    
    private fun startService() {
        if (isRunning) return
        
        isRunning = true
        
        // Start as foreground service
        val notification = createServiceNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        // Acquire wake lock
        wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes
        
        // Start connections
        refreshConnections()
    }
    
    private fun stopService() {
        isRunning = false
        
        // Cancel all connections
        activeConnections.values.forEach { it.cancel() }
        activeConnections.clear()
        
        // Release wake lock
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun refreshConnections() {
        serviceScope.launch {
            storageManager.subscriptions.collect { subscriptions ->
                // Close connections for removed subscriptions
                val currentIds = subscriptions.map { it.id }.toSet()
                activeConnections.keys.filter { it !in currentIds }.forEach { id ->
                    activeConnections.remove(id)?.cancel()
                }
                
                // Open connections for new subscriptions
                subscriptions.filter { it.instant }.forEach { subscription ->
                    if (!activeConnections.containsKey(subscription.id)) {
                        connectToSubscription(subscription)
                    }
                }
                
                // Update notification
                updateServiceNotification(subscriptions.size)
            }
        }
    }
    
    private fun connectToSubscription(subscription: Subscription) {
        val call = apiService.subscribe(
            subscription = subscription,
            since = "all",
            onMessage = { message ->
                showNotification(subscription, message)
            },
            onError = { error ->
                // Log error and retry after delay
                serviceScope.launch {
                    delay(5000)
                    if (isRunning && !activeConnections.containsKey(subscription.id)) {
                        connectToSubscription(subscription)
                    }
                }
            },
            onClose = {
                // Reconnect if service is still running
                serviceScope.launch {
                    delay(1000)
                    if (isRunning) {
                        val subs = storageManager.subscriptions.collect { it }
                        val sub = subs.find { it.id == subscription.id && it.instant }
                        if (sub != null) {
                            connectToSubscription(sub)
                        } else {
                            activeConnections.remove(subscription.id)
                        }
                    }
                }
            }
        )
        
        activeConnections[subscription.id] = call
    }
    
    private fun showNotification(subscription: Subscription, message: NtfyMessage) {
        notificationHelper.showNotification(subscription, message)
    }
    
    private fun createServiceNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
    
    private fun updateServiceNotification(subscriptionCount: Int) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val text = if (subscriptionCount == 0) {
            getString(R.string.service_running_no_subscriptions)
        } else {
            getString(R.string.service_running_with_subscriptions, subscriptionCount)
        }
        
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    companion object {
        const val ACTION_START = "com.ntfy.wear.ACTION_START"
        const val ACTION_STOP = "com.ntfy.wear.ACTION_STOP"
        const val ACTION_REFRESH = "com.ntfy.wear.ACTION_REFRESH"
        
        private const val CHANNEL_ID = "ntfy_service_channel"
        private const val NOTIFICATION_ID = 1
        
        fun start(context: Context) {
            val intent = Intent(context, SubscriberService::class.java).apply {
                action = ACTION_START
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, SubscriberService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        fun refresh(context: Context) {
            val intent = Intent(context, SubscriberService::class.java).apply {
                action = ACTION_REFRESH
            }
            context.startService(intent)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            SubscriberService.start(context)
        }
    }
}

class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SubscriberService.start(context)
    }
}
