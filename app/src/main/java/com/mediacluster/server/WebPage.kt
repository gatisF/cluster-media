package com.mediacluster.server

import com.mediacluster.dlna.ContentDirectory.Companion.esc
import com.mediacluster.library.ClusterIndex
import com.mediacluster.library.MediaItem
import com.mediacluster.library.MediaKind
import com.mediacluster.library.Uploader
import com.mediacluster.library.mediaUrl

/**
 * The optional browser page at "/": the merged cluster library, playable in any browser,
 * so phones and laptops without the app can watch too. Files stream straight from the phone that holds them.
 */
object WebPage {

    fun render(snap: ClusterIndex.Snapshot): String {
        val count = snap.allItems.size
        return """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>MediaCluster</title>
<style>
:root { color-scheme: light dark; --bg:#fff; --fg:#1b1b1f; --muted:#666; --line:#e3e3e8; --accent:#3b5bdb; }
@media (prefers-color-scheme: dark) { :root { --bg:#121216; --fg:#e8e8ee; --muted:#9a9aa6; --line:#2a2a33; --accent:#8ea2ff; } }
* { box-sizing: border-box; }
body { margin:0; font:16px/1.4 system-ui, sans-serif; background:var(--bg); color:var(--fg); }
header { position:sticky; top:0; background:var(--bg); border-bottom:1px solid var(--line); padding:12px 16px; z-index:1; }
h1 { font-size:20px; margin:0; } .sub { color:var(--muted); font-size:14px; }
#player { display:none; margin-top:10px; } #player video, #player audio { width:100%; max-height:60vh; background:#000; }
#now { font-size:14px; margin-top:4px; }
nav { display:flex; gap:8px; margin-top:10px; }
nav button { border:1px solid var(--line); background:none; color:var(--fg); padding:6px 12px; border-radius:16px; font:inherit; font-size:14px; }
nav button.on { background:var(--accent); border-color:var(--accent); color:#fff; }
.top { display:flex; justify-content:space-between; align-items:center; gap:12px; }
.add { background:var(--accent); color:#fff; border-radius:16px; padding:6px 14px; font-size:14px; cursor:pointer; white-space:nowrap; }
.add input { display:none; }
#upload { font-size:14px; margin-top:6px; overflow-wrap:anywhere; }
main { padding:8px 16px 32px; }
.row { display:flex; justify-content:space-between; gap:12px; padding:10px 0; border-bottom:1px solid var(--line); cursor:pointer; }
.row:hover .t { color:var(--accent); } .t { overflow-wrap:anywhere; } .m { color:var(--muted); font-size:13px; white-space:nowrap; }
.grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(110px, 1fr)); gap:6px; padding-top:8px; }
.grid a { display:block; aspect-ratio:1; background:var(--line); }
.grid img { width:100%; height:100%; object-fit:cover; display:block; }
.empty { color:var(--muted); padding:24px 0; }
</style></head><body>
<header>
<div class="top"><div>
<h1>MediaCluster</h1>
<div class="sub">${snap.peers.size} phone${plural(snap.peers.size)}${drivesLabel(snap)} · $count file${plural(count)}</div>
</div><label class="add">Add media<input id="files" type="file" multiple accept="video/*,audio/*,image/*"></label></div>
<div id="upload" class="sub"></div>
<div id="player"></div><div id="now" class="sub"></div>
<nav>
<button data-tab="VIDEO" class="on">Videos</button><button data-tab="AUDIO">Music</button><button data-tab="IMAGE">Photos</button>
</nav>
</header>
<main>
${section(snap, MediaKind.VIDEO, "No videos shared yet.")}
${section(snap, MediaKind.AUDIO, "No music shared yet.")}
${section(snap, MediaKind.IMAGE, "No photos shared yet.")}
</main>
<script>
const targets = ${targetsJson(snap)};
const RESERVE = ${Uploader.RESERVE_BYTES};
const say = t => document.getElementById('upload').textContent = t;
// Each file goes to the phone or network drive with the most free space, like "Add media" in the app.
function send(file, label) {
  const target = targets.filter(p => p.free - RESERVE > file.size).sort((a, b) => b.free - a.free)[0];
  if (!target) return Promise.reject(new Error(file.name + ': no phone has room'));
  return new Promise((resolve, reject) => {
    const x = new XMLHttpRequest();
    x.open('POST', target.url + 'name=' + encodeURIComponent(file.name) + '&mime=' + encodeURIComponent(file.type));
    x.upload.onprogress = e => e.lengthComputable && say(label + file.name + ' to ' + target.name + '… ' + Math.floor(e.loaded * 100 / e.total) + '%');
    x.onload = () => x.status < 300 ? (target.free -= file.size, resolve()) : reject(new Error(file.name + ': ' + (x.responseText || x.status)));
    x.onerror = () => reject(new Error(file.name + ': connection failed'));
    x.send(file);
  });
}
document.getElementById('files').onchange = async e => {
  const files = [...e.target.files]; let added = 0; const failed = [];
  for (const [i, f] of files.entries()) {
    try { await send(f, 'Adding ' + (files.length > 1 ? (i + 1) + ' of ' + files.length + ': ' : '')); added++; }
    catch (err) { failed.push(err.message); }
  }
  e.target.value = '';
  say('Added ' + added + ' file' + (added === 1 ? '' : 's') + (failed.length ? '. Not added: ' + failed.join('; ') : '. Refreshing…'));
  if (added) { await fetch('/api/refresh', { method: 'POST' }).catch(() => {}); setTimeout(() => location.reload(), failed.length ? 8000 : 2500); }
};
document.querySelectorAll('nav button').forEach(b => b.onclick = () => {
  document.querySelectorAll('nav button').forEach(x => x.classList.toggle('on', x === b));
  document.querySelectorAll('main > section').forEach(s => s.hidden = s.id !== b.dataset.tab);
});
document.querySelectorAll('.row').forEach(r => r.onclick = () => {
  const p = document.getElementById('player');
  const el = document.createElement(r.dataset.kind === 'AUDIO' ? 'audio' : 'video');
  el.controls = true; el.autoplay = true; el.src = r.dataset.src;
  p.replaceChildren(el); p.style.display = 'block';
  document.getElementById('now').textContent = r.querySelector('.t').textContent;
  window.scrollTo(0, 0);
});
</script>
</body></html>"""
    }

    private fun section(snap: ClusterIndex.Snapshot, kind: MediaKind, emptyText: String): String {
        val items = snap.itemsOfKind(kind)
        val hidden = if (kind == MediaKind.VIDEO) "" else " hidden"
        val body = when {
            items.isEmpty() -> """<p class="empty">$emptyText</p>"""
            kind == MediaKind.IMAGE -> items.mapNotNull { item ->
                val url = url(snap, item) ?: return@mapNotNull null
                """<a href="$url" target="_blank"><img loading="lazy" src="$url" alt="${esc(item.title)}"></a>"""
            }.joinToString("", """<div class="grid">""", "</div>")
            else -> items.mapNotNull { item ->
                val url = url(snap, item) ?: return@mapNotNull null
                val meta = listOfNotNull(
                    item.durationMs.takeIf { it > 0 }?.let(::duration),
                    snap.locationOf(item)?.let(::esc),
                ).joinToString(" · ")
                """<div class="row" data-kind="$kind" data-src="$url"><span class="t">${esc(item.title)}</span><span class="m">$meta</span></div>"""
            }.joinToString("")
        }
        return """<section id="$kind"$hidden>$body</section>"""
    }

    private fun targetsJson(snap: ClusterIndex.Snapshot) = snap.storageTargets.joinToString(",", "[", "]") { t ->
        // esc() makes the name safe inside the inline <script> too: it removes < and quotes.
        """{"name":"${esc(t.name).replace("\\", "")}","url":"${t.uploadUrl}","free":${t.freeBytes}}"""
    }

    private fun drivesLabel(snap: ClusterIndex.Snapshot) =
        snap.drives.size.let { if (it == 0) "" else " + $it drive${plural(it)}" }

    private fun url(snap: ClusterIndex.Snapshot, item: MediaItem) = snap.peers[item.peerId]?.mediaUrl(item)?.let(::esc)

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
