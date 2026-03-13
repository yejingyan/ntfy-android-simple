package com.ntfy.wear.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.ntfy.wear.db.NtfyMessage
import com.ntfy.wear.db.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiService {
    
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES) // Long polling timeout
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val loggingClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY })
        .build()
    
    data class NtfyResponse(
        @SerializedName("id") val id: String,
        @SerializedName("time") val time: Long,
        @SerializedName("event") val event: String,
        @SerializedName("title") val title: String? = null,
        @SerializedName("message") val message: String? = null,
        @SerializedName("priority") val priority: Int? = null,
        @SerializedName("tags") val tags: List<String>? = null
    )
    
    /**
     * Subscribe to a topic using long polling
     */
    fun subscribe(
        subscription: Subscription,
        since: String = "all",
        onMessage: (NtfyMessage) -> Unit,
        onError: (Throwable) -> Unit,
        onClose: () -> Unit = {}
    ): Call {
        val url = "${subscription.baseUrl}/${subscription.topic}/json?since=$since&poll=1"
        
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        
        val call = client.newCall(request)
        
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) {
                    onError(e)
                }
                onClose()
            }
            
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            onError(IOException("Unexpected response ${resp.code}"))
                            return
                        }
                        
                        val source = resp.body?.source()
                        if (source != null) {
                            while (!source.exhausted()) {
                                val line = source.readUtf8Line()
                                if (line != null) {
                                    parseAndEmit(line, onMessage)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!call.isCanceled()) {
                        onError(e)
                    }
                } finally {
                    onClose()
                }
            }
        })
        
        return call
    }
    
    /**
     * Poll for messages once
     */
    suspend fun poll(subscription: Subscription, since: String = "all"): List<NtfyMessage> = withContext(Dispatchers.IO) {
        val url = "${subscription.baseUrl}/${subscription.topic}/json?since=$since&poll=1"
        
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext emptyList()
                }
                
                val messages = mutableListOf<NtfyMessage>()
                val body = response.body?.string() ?: return@withContext emptyList()
                
                body.lines().forEach { line ->
                    if (line.isNotBlank()) {
                        try {
                            val ntfyResponse = gson.fromJson(line, NtfyResponse::class.java)
                            if (ntfyResponse.event == "message" && ntfyResponse.id != null) {
                                messages.add(
                                    NtfyMessage(
                                        id = ntfyResponse.id,
                                        title = ntfyResponse.title ?: "",
                                        message = ntfyResponse.message ?: "",
                                        priority = ntfyResponse.priority ?: 3,
                                        timestamp = ntfyResponse.time,
                                        tags = ntfyResponse.tags ?: emptyList()
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            // Skip invalid lines
                        }
                    }
                }
                
                return@withContext messages
            }
        } catch (e: Exception) {
            return@withContext emptyList()
        }
    }
    
    private fun parseAndEmit(line: String, onMessage: (NtfyMessage) -> Unit) {
        try {
            val ntfyResponse = gson.fromJson(line, NtfyResponse::class.java)
            if (ntfyResponse.event == "message" && ntfyResponse.id != null) {
                onMessage(
                    NtfyMessage(
                        id = ntfyResponse.id,
                        title = ntfyResponse.title ?: "",
                        message = ntfyResponse.message ?: "",
                        priority = ntfyResponse.priority ?: 3,
                        timestamp = ntfyResponse.time,
                        tags = ntfyResponse.tags ?: emptyList()
                    )
                )
            }
        } catch (e: Exception) {
            // Skip invalid lines
        }
    }
    
    /**
     * Publish a message to a topic
     */
    suspend fun publish(
        baseUrl: String,
        topic: String,
        message: String,
        title: String? = null,
        priority: Int = 3,
        tags: List<String>? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val url = buildString {
            append("$baseUrl/$topic")
            append("?message=${java.net.URLEncoder.encode(message, "UTF-8")}")
            if (title != null) {
                append("&title=${java.net.URLEncoder.encode(title, "UTF-8")}")
            }
            append("&priority=$priority")
            if (!tags.isNullOrEmpty()) {
                append("&tags=${java.net.URLEncoder.encode(tags.joinToString(","), "UTF-8")}")
            }
        }
        
        val request = Request.Builder()
            .url(url)
            .post(okhttp3.RequestBody.create(null, byteArrayOf()))
            .build()
        
        try {
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            return@withContext false
        }
    }
}
