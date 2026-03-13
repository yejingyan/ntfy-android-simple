package com.ntfy.wear.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ntfy.wear.MainActivity
import com.ntfy.wear.R
import com.ntfy.wear.db.NtfyMessage
import com.ntfy.wear.db.Subscription

class NotificationHelper(private val context: Context) {
    
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Service channel
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_service_description)
                setShowBadge(false)
            }
            
            // Default notification channel
            val defaultChannel = NotificationChannel(
                CHANNEL_DEFAULT,
                context.getString(R.string.channel_default_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_default_description)
            }
            
            // High priority channel
            val highChannel = NotificationChannel(
                CHANNEL_HIGH,
                context.getString(R.string.channel_high_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_high_description)
            }
            
            notificationManager.createNotificationChannels(listOf(
                serviceChannel,
                defaultChannel,
                highChannel
            ))
        }
    }
    
    fun showNotification(subscription: Subscription, message: NtfyMessage) {
        val notificationId = generateNotificationId(subscription.id, message.id)
        
        val channelId = when (message.priority) {
            NtfyMessage.PRIORITY_MAX, NtfyMessage.PRIORITY_HIGH -> CHANNEL_HIGH
            else -> CHANNEL_DEFAULT
        }
        
        val pendingIntent = createContentIntent(subscription)
        
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(message.title.takeIf { it.isNotBlank() } ?: subscription.displayName ?: subscription.topic)
            .setContentText(message.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(getPriority(message.priority))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        
        // Add actions if tags contain action URLs
        message.tags.forEach { tag ->
            if (tag.startsWith("http://") || tag.startsWith("https://")) {
                val actionIntent = Intent(Intent.ACTION_VIEW, Uri.parse(tag))
                val actionPendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    actionIntent,
                    PendingIntent.FLAG_IMMUTABLE
                )
                builder.addAction(0, context.getString(R.string.action_open), actionPendingIntent)
            }
        }
        
        notificationManager.notify(notificationId, builder.build())
    }
    
    private fun createContentIntent(subscription: Subscription): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("subscription_id", subscription.id)
        }
        
        return PendingIntent.getActivity(
            context,
            subscription.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    
    private fun getPriority(priority: Int): Int {
        return when (priority) {
            NtfyMessage.PRIORITY_MAX -> NotificationCompat.PRIORITY_MAX
            NtfyMessage.PRIORITY_HIGH -> NotificationCompat.PRIORITY_HIGH
            NtfyMessage.PRIORITY_LOW -> NotificationCompat.PRIORITY_LOW
            NtfyMessage.PRIORITY_MIN -> NotificationCompat.PRIORITY_MIN
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
    }
    
    private fun generateNotificationId(subscriptionId: String, messageId: String): Int {
        return (subscriptionId + messageId).hashCode()
    }
    
    companion object {
        const val CHANNEL_SERVICE = "ntfy_service"
        const val CHANNEL_DEFAULT = "ntfy_default"
        const val CHANNEL_HIGH = "ntfy_high"
    }
}
