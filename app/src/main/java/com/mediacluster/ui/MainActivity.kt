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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mediacluster.Cluster
import com.mediacluster.ClusterService
import com.mediacluster.library.DriveConfig
import com.mediacluster.library.MediaFolder
import com.mediacluster.library.MediaScanner
import com.mediacluster.library.NetworkDrive
import com.mediacluster.library.explainDriveError
import com.mediacluster.library.SharedFolders
import com.mediacluster.library.covers
import com.mediacluster.library.mediaUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
            ) else if (Build.VERSION.SDK_INT >= 29) arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        )
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { LibraryScreen() } } }
        if (savedInstanceState == null) addShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        addShared(intent)
    }

    /** Media shared to the app from a gallery or file manager is added to the cluster. */
    private fun addShared(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> return
        }
        Cluster.uploader(this).add(uris)
    }

    @Composable
    private fun LibraryScreen() {
        val snap by Cluster.index.snapshot.collectAsStateWithLifecycle()
        val shared by Cluster.folders(this).shared.collectAsStateWithLifecycle()
        val webPage by Cluster.settings(this).webPage.collectAsStateWithLifecycle()
        val uploadStatus by Cluster.uploader(this).status.collectAsStateWithLifecycle()
        val drive by Cluster.settings(this).drive.collectAsStateWithLifecycle()
        val driveStatus by Cluster.driveStatus.collectAsStateWithLifecycle()
        var pickingFolders by remember { mutableStateOf(false) }
        var editingDrive by remember { mutableStateOf(false) }
        var device by rememberSaveable { mutableStateOf<String?>(null) }
        val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            Cluster.uploader(this@MainActivity).add(uris)
        }
        Column(Modifier.padding(16.dp)) {
            val drives = snap.drives.size
            Text(
                "${snap.peers.size} phones" + (if (drives > 0) " + $drives drive${if (drives == 1) "" else "s"}" else "") + " in cluster",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Pooled storage: ${Formatter.formatFileSize(this@MainActivity, snap.freeBytes)} free of " +
                    Formatter.formatFileSize(this@MainActivity, snap.totalBytes),
            )
            Text("On your TV, open the media/DLNA source \"MediaCluster\".", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { pickingFolders = true }) { Text(sharingLabel(shared)) }
                Button(onClick = { pickMedia.launch(arrayOf("video/*", "audio/*", "image/*")) }) { Text("Add media") }
            }
            uploadStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Web page for browsers")
                    val host = snap.peers[Cluster.peerId(this@MainActivity)]?.host
                    Text(
                        when {
                            !webPage -> "Lets phones and laptops without the app watch in a browser"
                            host == null -> "Starting…"
                            else -> "Open http://$host:${ClusterService.PORT}/ on the same Wi-Fi"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = webPage, onCheckedChange = { Cluster.settings(this@MainActivity).setWebPage(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(drive?.name ?: "Router drive")
                    Text(
                        if (drive == null) "Add a USB disk plugged into your router to the cluster"
                        else "${drive?.address}\n${driveStatus ?: "Starting…"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { editingDrive = true }) { Text(if (drive == null) "Set up" else "Change") }
            }
            val devices = snap.devices
            // A device that left the cluster can't stay selected.
            val shownDevice = device?.takeIf { id -> devices.any { it.id == id } }
            if (devices.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    item {
                        FilterChip(selected = shownDevice == null, onClick = { device = null }, label = { Text("All devices") })
                    }
                    items(devices, key = { it.id }) { d ->
                        FilterChip(
                            selected = shownDevice == d.id,
                            onClick = { device = if (shownDevice == d.id) null else d.id },
                            label = { Text("${d.name} (${d.itemCount})") },
                        )
                    }
                }
            }
            val shownItems = if (shownDevice == null) snap.allItems else snap.allItems.filter { snap.deviceOf(it) == shownDevice }
            if (shownDevice != null && shownItems.isEmpty()) {
                Text("Nothing shared from this device.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }
            LazyColumn {
                items(shownItems, key = { it.id }) { item ->
                    val peer = snap.peers[item.peerId]
                    ListItem(
                        headlineContent = { Text(item.title) },
                        supportingContent = { Text("${snap.locationOf(item) ?: "?"} · ${Formatter.formatFileSize(this@MainActivity, item.sizeBytes)}") },
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
        if (editingDrive) DriveDialog(drive, onDismiss = { editingDrive = false }) {
            Cluster.settings(this).setDrive(it)
            editingDrive = false
        }
    }

    /**
     * Sets up the network drive this phone adds to the cluster: a disk shared over SMB,
     * typically a USB drive on the router. Saving null removes it.
     */
    @Composable
    private fun DriveDialog(current: DriveConfig?, onDismiss: () -> Unit, onSave: (DriveConfig?) -> Unit) {
        var address by remember { mutableStateOf(current?.address ?: "") }
        var user by remember { mutableStateOf(current?.user ?: "") }
        var password by remember { mutableStateOf(current?.password ?: "") }
        var name by remember { mutableStateOf(current?.name ?: "Router drive") }
        var shares by remember { mutableStateOf<List<String>?>(null) }
        var finding by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val parsed = DriveConfig.parse(address, user, password, name)
        val hostOnly = DriveConfig.split(address)
        fun findShares() {
            val (host, port, _) = hostOnly ?: return
            finding = "Looking for shared folders on $host…"
            shares = null
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { NetworkDrive.listShares(host, port, user.trim(), password) } }
                result.onSuccess { shares = it; finding = if (it.isEmpty()) "No shared folders found on $host" else null }
                    .onFailure { finding = "Can't list folders: " + explainDriveError(it, user.isBlank(), host) }
            }
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Router drive") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Turn on file sharing (SMB) for the USB drive in your router's settings, then enter its address. " +
                            "This phone indexes the drive and streams it to the cluster, so pick a phone that stays at home.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = address, onValueChange = { address = it }, singleLine = true,
                        label = { Text("Address") }, placeholder = { Text("\\\\192.168.1.1\\usb\\Media") },
                        isError = address.isNotBlank() && parsed == null,
                        supportingText = { Text("\\\\router\\share or \\\\router\\share\\folder. Only know the router's address? Enter it, add the login below and tap Find shared folders.") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    OutlinedTextField(
                        value = user, onValueChange = { user = it }, singleLine = true,
                        label = { Text("Username") }, supportingText = { Text("Leave empty for guest access") },
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true,
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name in the cluster") })
                    TextButton(enabled = hostOnly != null, onClick = ::findShares) { Text("Find shared folders") }
                    finding?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    shares?.forEach { share ->
                        val (host, port, _) = hostOnly ?: return@forEach
                        TextButton(onClick = { address = "\\\\" + host + (if (port != 445) ":$port" else "") + "\\" + share }) {
                            Text("\\\\$host\\$share")
                        }
                    }
                }
            },
            confirmButton = { TextButton(enabled = parsed != null, onClick = { onSave(parsed) }) { Text("Save") } },
            dismissButton = {
                Row {
                    if (current != null) TextButton(onClick = { onSave(null) }) { Text("Remove") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            },
        )
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
