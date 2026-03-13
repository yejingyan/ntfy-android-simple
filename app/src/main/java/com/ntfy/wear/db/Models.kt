package com.ntfy.wear.db

import java.util.UUID

data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val baseUrl: String,
    val topic: String,
    val instant: Boolean = true,
    val displayName: String? = null
) {
    val fullUrl: String
        get() = "$baseUrl/$topic"
}

data class NtfyMessage(
    val id: String,
    val title: String,
    val message: String,
    val priority: Int = 3,
    val timestamp: Long = System.currentTimeMillis() / 1000,
    val tags: List<String> = emptyList()
) {
    companion object {
        const val PRIORITY_MIN = 1
        const val PRIORITY_LOW = 2
        const val PRIORITY_DEFAULT = 3
        const val PRIORITY_HIGH = 4
        const val PRIORITY_MAX = 5
    }
}
