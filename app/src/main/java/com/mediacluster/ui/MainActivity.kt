package com.mediacluster.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediacluster.Cluster
import com.mediacluster.ClusterService
import com.mediacluster.library.mediaUrl

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        startForegroundService(Intent(this, ClusterService::class.java))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.launch(
            if (Build.VERSION.SDK_INT >= 33) arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS,
            ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        )
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { LibraryScreen() } } }
    }

    @Composable
    private fun LibraryScreen() {
        val snap by Cluster.index.snapshot.collectAsStateWithLifecycle()
        Column(Modifier.padding(16.dp)) {
            Text("${snap.peers.size} phones in cluster", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Pooled storage: ${Formatter.formatFileSize(this@MainActivity, snap.freeBytes)} free of " +
                    Formatter.formatFileSize(this@MainActivity, snap.totalBytes),
            )
            Text("On your TV, open the media/DLNA source \"MediaCluster\".", style = MaterialTheme.typography.bodySmall)
            LazyColumn {
                items(snap.allItems, key = { it.id }) { item ->
                    val peer = snap.peers[item.peerId]
                    ListItem(
                        headlineContent = { Text(item.title) },
                        supportingContent = { Text("${peer?.name ?: "?"} · ${Formatter.formatFileSize(this@MainActivity, item.sizeBytes)}") },
                        modifier = Modifier.clickable {
                            if (peer != null) startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(peer.mediaUrl(item)), item.mimeType)
                            )
                        },
                    )
                }
            }
        }
    }
}
