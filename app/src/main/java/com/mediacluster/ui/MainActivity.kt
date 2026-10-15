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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediacluster.Cluster
import com.mediacluster.ClusterService
import com.mediacluster.library.MediaFolder
import com.mediacluster.library.MediaScanner
import com.mediacluster.library.mediaUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val shared by Cluster.folders(this).selected.collectAsStateWithLifecycle()
        var pickingFolders by remember { mutableStateOf(false) }
        Column(Modifier.padding(16.dp)) {
            Text("${snap.peers.size} phones in cluster", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Pooled storage: ${Formatter.formatFileSize(this@MainActivity, snap.freeBytes)} free of " +
                    Formatter.formatFileSize(this@MainActivity, snap.totalBytes),
            )
            Text("On your TV, open the media/DLNA source \"MediaCluster\".", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { pickingFolders = true }, modifier = Modifier.padding(vertical = 8.dp)) {
                Text(shared?.let { "Sharing ${it.size} folder${if (it.size == 1) "" else "s"}" } ?: "Sharing all folders")
            }
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
        if (pickingFolders) FolderDialog(shared, onDismiss = { pickingFolders = false }) {
            Cluster.folders(this).set(it)
            pickingFolders = false
        }
    }

    /** Lets the user choose which of this phone's media folders the cluster can see. */
    @Composable
    private fun FolderDialog(current: Set<String>?, onDismiss: () -> Unit, onSave: (Set<String>?) -> Unit) {
        var folders by remember { mutableStateOf<List<MediaFolder>?>(null) }
        var shareAll by remember { mutableStateOf(current == null) }
        var checked by remember { mutableStateOf(current.orEmpty()) }
        LaunchedEffect(Unit) {
            folders = withContext(Dispatchers.IO) {
                runCatching { MediaScanner(this@MainActivity, Cluster.peerId(this@MainActivity)).folders() }.getOrDefault(emptyList())
            }
            if (current == null) checked = folders.orEmpty().map { it.path }.toSet()
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Shared folders") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("All folders, including new ones", Modifier.weight(1f))
                        Switch(checked = shareAll, onCheckedChange = { shareAll = it })
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    val list = folders
                    when {
                        list == null -> Text("Looking for media folders…")
                        list.isEmpty() -> Text("No media found. Check that the app may read photos, videos and music.")
                        else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(list, key = { it.path }) { folder ->
                                val on = shareAll || folder.path in checked
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = !shareAll) {
                                        checked = if (on) checked - folder.path else checked + folder.path
                                    },
                                ) {
                                    Checkbox(checked = on, onCheckedChange = null, enabled = !shareAll)
                                    Column(Modifier.padding(start = 8.dp)) {
                                        Text(folder.path.trimEnd('/').ifEmpty { "(storage root)" })
                                        Text("${folder.itemCount} file${if (folder.itemCount == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onSave(if (shareAll) null else checked) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}
