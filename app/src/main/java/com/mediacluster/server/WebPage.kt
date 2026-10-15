package com.mediacluster.server

import com.mediacluster.dlna.ContentDirectory.Companion.esc
import com.mediacluster.library.ClusterIndex
import com.mediacluster.library.MediaItem
import com.mediacluster.library.MediaKind
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
main { padding:8px 16px 32px; }
.row { display:flex; justify-content:space-between; gap:12px; padding:10px 0; border-bottom:1px solid var(--line); cursor:pointer; }
.row:hover .t { color:var(--accent); } .t { overflow-wrap:anywhere; } .m { color:var(--muted); font-size:13px; white-space:nowrap; }
.grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(110px, 1fr)); gap:6px; padding-top:8px; }
.grid a { display:block; aspect-ratio:1; background:var(--line); }
.grid img { width:100%; height:100%; object-fit:cover; display:block; }
.empty { color:var(--muted); padding:24px 0; }
</style></head><body>
<header>
<h1>MediaCluster</h1>
<div class="sub">${snap.peers.size} phone${plural(snap.peers.size)} · $count file${plural(count)}</div>
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
                    snap.peers[item.peerId]?.name?.let(::esc),
                ).joinToString(" · ")
                """<div class="row" data-kind="$kind" data-src="$url"><span class="t">${esc(item.title)}</span><span class="m">$meta</span></div>"""
            }.joinToString("")
        }
        return """<section id="$kind"$hidden>$body</section>"""
    }

    private fun url(snap: ClusterIndex.Snapshot, item: MediaItem) = snap.peers[item.peerId]?.mediaUrl(item)?.let(::esc)

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
