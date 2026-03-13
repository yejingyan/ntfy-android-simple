package com.ntfy.wear.db

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ntfy_preferences")

class StorageManager(private val context: Context) {
    
    private val gson = Gson()
    private val subscriptionsKey = stringPreferencesKey("subscriptions")
    private val defaultBaseUrlKey = stringPreferencesKey("default_base_url")
    
    val subscriptions: Flow<List<Subscription>> = context.dataStore.data.map { preferences ->
        val json = preferences[subscriptionsKey] ?: "[]"
        val type = object : TypeToken<List<Subscription>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    }
    
    val defaultBaseUrl: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[defaultBaseUrlKey] ?: "https://ntfy.sh"
    }
    
    suspend fun addSubscription(subscription: Subscription) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[subscriptionsKey] ?: "[]"
            val type = object : TypeToken<List<Subscription>>() {}.type
            val currentList = (gson.fromJson(currentJson, type) as? List<Subscription>) ?: emptyList()
            
            // Check if subscription already exists
            if (currentList.none { it.baseUrl == subscription.baseUrl && it.topic == subscription.topic }) {
                val newList = currentList + subscription
                preferences[subscriptionsKey] = gson.toJson(newList)
            }
        }
    }
    
    suspend fun removeSubscription(subscriptionId: String) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[subscriptionsKey] ?: "[]"
            val type = object : TypeToken<List<Subscription>>() {}.type
            val currentList = (gson.fromJson(currentJson, type) as? List<Subscription>) ?: emptyList()
            
            val newList = currentList.filter { it.id != subscriptionId }
            preferences[subscriptionsKey] = gson.toJson(newList)
        }
    }
    
    suspend fun updateSubscription(subscription: Subscription) {
        context.dataStore.edit { preferences ->
            val currentJson = preferences[subscriptionsKey] ?: "[]"
            val type = object : TypeToken<List<Subscription>>() {}.type
            val currentList = (gson.fromJson(currentJson, type) as? List<Subscription>) ?: emptyList()
            
            val newList = currentList.map { 
                if (it.id == subscription.id) subscription else it 
            }
            preferences[subscriptionsKey] = gson.toJson(newList)
        }
    }
    
    suspend fun setDefaultBaseUrl(url: String) {
        context.dataStore.edit { preferences ->
            preferences[defaultBaseUrlKey] = url
        }
    }
    
    suspend fun getSubscriptionsSnapshot(): List<Subscription> {
        return subscriptions.map { it }.collect { return@collect it }
    }
}
