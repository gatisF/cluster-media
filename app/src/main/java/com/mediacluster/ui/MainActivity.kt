package com.mediacluster.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import com.mediacluster.library.SharedFolders
import com.mediacluster.library.covers
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
        val shared by Cluster.folders(this).shared.collectAsStateWithLifecycle()
        var pickingFolders by remember { mutableStateOf(false) }
        Column(Modifier.padding(16.dp)) {
            Text("${snap.peers.size} phones in cluster", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Pooled storage: ${Formatter.formatFileSize(this@MainActivity, snap.freeBytes)} free of " +
                    Formatter.formatFileSize(this@MainActivity, snap.totalBytes),
            )
            Text("On your TV, open the media/DLNA source \"MediaCluster\".", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { pickingFolders = true }, modifier = Modifier.padding(vertical = 8.dp)) {
                Text(sharingLabel(shared))
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

    private fun sharingLabel(shared: SharedFolders): String {
        val picked = shared.trees.size
        val media = shared.mediaFolders ?: return "Sharing all folders" + if (picked > 0) " + $picked picked" else ""
        val n = media.size + picked
        return "Sharing $n folder${if (n == 1) "" else "s"}"
    }

    /**
     * Lets the user choose what the cluster can see: media folders (each with its subfolders),
     * plus any other folder picked with the system folder picker.
     */
    @Composable
    private fun FolderDialog(current: SharedFolders, onDismiss: () -> Unit, onSave: (SharedFolders) -> Unit) {
        var folders by remember { mutableStateOf<List<MediaFolder>?>(null) }
        var shareAll by remember { mutableStateOf(current.mediaFolders == null) }
        var checked by remember { mutableStateOf(current.mediaFolders.orEmpty()) }
        var trees by remember { mutableStateOf(current.trees) }
        val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                // Keep read access after a reboot so the service can go on serving this folder.
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                trees = trees + uri.toString()
            }
        }
        LaunchedEffect(Unit) {
            folders = withContext(Dispatchers.IO) {
                runCatching { MediaScanner(this@MainActivity, Cluster.peerId(this@MainActivity)).folders() }.getOrDefault(emptyList())
            }
            // Turning "all" off starts from everything ticked, which the top-level folders cover.
            if (current.mediaFolders == null) checked = folders.orEmpty().filter { it.depth == 0 }.map { it.path }.toSet()
        }

        fun finish(saved: SharedFolders?) {
            // Give back folder access nobody uses any more: removed folders on save, newly picked ones on cancel.
            val keep = saved?.trees ?: current.trees
            (current.trees + trees - keep).forEach {
                runCatching { contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            if (saved != null) onSave(saved) else onDismiss()
        }

        AlertDialog(
            onDismissRequest = { finish(null) },
            title = { Text("Shared folders") },
            text = {
                LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("All media folders, including new ones", Modifier.weight(1f))
                            Switch(checked = shareAll, onCheckedChange = { shareAll = it })
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }
                    val list = folders
                    when {
                        list == null -> item { Text("Looking for media folders…") }
                        list.isEmpty() -> item { Text("No media found. Check that the app may read photos, videos and music.") }
                        else -> items(list, key = { it.path }) { folder ->
                            // A ticked folder brings its subfolders along; those show as ticked and locked.
                            val inherited = checked.any { it != folder.path && it.covers(folder.path) }
                            val on = shareAll || inherited || folder.path in checked
                            val enabled = !shareAll && !inherited
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = (folder.depth * 16).dp)
                                    .clickable(enabled = enabled) {
                                        checked = if (on) checked - folder.path else checked + folder.path
                                    },
                            ) {
                                Checkbox(checked = on, onCheckedChange = null, enabled = enabled)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(folder.path.trimEnd('/').substringAfterLast('/').ifEmpty { "(storage root)" })
                                    Text(
                                        "${folder.itemCount} file${if (folder.itemCount == 1) "" else "s"}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                    item {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("Other folders", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Any folder, including hidden ones the gallery skips. Everything inside is shared.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    items(trees.toList(), key = { it }) { tree ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(MediaScanner.treeLabel(tree), Modifier.weight(1f))
                            TextButton(onClick = { trees = trees - tree }) { Text("Remove") }
                        }
                    }
                    item {
                        TextButton(onClick = { pickTree.launch(null) }) { Text("Add folder…") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    // Keep only the outermost ticked folders; their subfolders are included anyway.
                    val outer = checked.filter { f -> checked.none { it != f && it.covers(f) } }.toSet()
                    finish(SharedFolders(if (shareAll) null else outer, trees))
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { finish(null) }) { Text("Cancel") } },
        )
    }
}
