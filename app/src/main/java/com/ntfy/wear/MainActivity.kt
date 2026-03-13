package com.ntfy.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.ntfy.wear.databinding.ActivityMainBinding
import com.ntfy.wear.db.Subscription
import com.ntfy.wear.service.SubscriberService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SubscriptionAdapter
    private lateinit var storageManager: com.ntfy.wear.db.StorageManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setSupportActionBar(binding.toolbar)
        
        storageManager = (application as NtfyApplication).storageManager
        
        setupRecyclerView()
        setupFab()
        observeSubscriptions()
        
        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission()
        }
        
        // Start the subscriber service
        SubscriberService.start(this)
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                showSettingsDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun setupRecyclerView() {
        adapter = SubscriptionAdapter(
            onDelete = { subscription ->
                deleteSubscription(subscription)
            },
            onToggleInstant = { subscription, enabled ->
                toggleInstant(subscription, enabled)
            }
        )
        
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }
    
    private fun setupFab() {
        binding.fab.setOnClickListener {
            showAddSubscriptionDialog()
        }
    }
    
    private fun observeSubscriptions() {
        lifecycleScope.launch {
            storageManager.subscriptions.collect { subscriptions ->
                adapter.submitList(subscriptions)
                binding.emptyView.visibility = if (subscriptions.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
    
    private fun showAddSubscriptionDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_subscription, null)
        val topicInput = dialogView.findViewById<EditText>(R.id.topicInput)
        val serverInput = dialogView.findViewById<EditText>(R.id.serverInput)
        
        // Set default server
        lifecycleScope.launch {
            val defaultUrl = storageManager.defaultBaseUrl.first()
            serverInput.setText(defaultUrl)
        }
        
        AlertDialog.Builder(this)
            .setTitle(R.string.add_subscription)
            .setView(dialogView)
            .setPositiveButton(R.string.add) { _, _ ->
                val topic = topicInput.text.toString().trim()
                val server = serverInput.text.toString().trim()
                
                if (topic.isNotEmpty() && server.isNotEmpty()) {
                    addSubscription(server, topic)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val serverInput = dialogView.findViewById<EditText>(R.id.defaultServerInput)
        
        lifecycleScope.launch {
            val defaultUrl = storageManager.defaultBaseUrl.first()
            serverInput.setText(defaultUrl)
        }
        
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val server = serverInput.text.toString().trim()
                if (server.isNotEmpty()) {
                    lifecycleScope.launch {
                        storageManager.setDefaultBaseUrl(server)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
    
    private fun addSubscription(baseUrl: String, topic: String) {
        lifecycleScope.launch {
            val subscription = Subscription(
                baseUrl = baseUrl.removeSuffix("/"),
                topic = topic,
                instant = true,
                displayName = topic
            )
            storageManager.addSubscription(subscription)
            
            // Refresh service connections
            SubscriberService.refresh(this@MainActivity)
        }
    }
    
    private fun deleteSubscription(subscription: Subscription) {
        lifecycleScope.launch {
            storageManager.removeSubscription(subscription.id)
            SubscriberService.refresh(this@MainActivity)
        }
    }
    
    private fun toggleInstant(subscription: Subscription, enabled: Boolean) {
        lifecycleScope.launch {
            val updated = subscription.copy(instant = enabled)
            storageManager.updateSubscription(updated)
            SubscriberService.refresh(this@MainActivity)
        }
    }
    
    private fun requestNotificationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_CODE
            )
        }
    }
    
    companion object {
        private const val NOTIFICATION_PERMISSION_CODE = 100
    }
}

class SubscriptionAdapter(
    private val onDelete: (Subscription) -> Unit,
    private val onToggleInstant: (Subscription, Boolean) -> Unit
) : ListAdapter<Subscription, SubscriptionAdapter.ViewHolder>(DiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subscription, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.nameText)
        private val urlText: TextView = itemView.findViewById(R.id.urlText)
        private val instantIndicator: View = itemView.findViewById(R.id.instantIndicator)
        private val deleteButton: View = itemView.findViewById(R.id.deleteButton)
        private val toggleInstantButton: View = itemView.findViewById(R.id.toggleInstantButton)
        
        fun bind(subscription: Subscription) {
            nameText.text = subscription.displayName ?: subscription.topic
            urlText.text = subscription.fullUrl
            instantIndicator.visibility = if (subscription.instant) View.VISIBLE else View.GONE
            
            deleteButton.setOnClickListener {
                onDelete(subscription)
            }
            
            toggleInstantButton.setOnClickListener {
                onToggleInstant(subscription, !subscription.instant)
            }
        }
    }
    
    class DiffCallback : DiffUtil.ItemCallback<Subscription>() {
        override fun areItemsTheSame(oldItem: Subscription, newItem: Subscription): Boolean {
            return oldItem.id == newItem.id
        }
        
        override fun areContentsTheSame(oldItem: Subscription, newItem: Subscription): Boolean {
            return oldItem == newItem
        }
    }
}
