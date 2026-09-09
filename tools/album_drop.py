#!/usr/bin/env python3
"""
album_drop — drag albums into a browser, they land on the music server.

Navidrome has no upload of its own; it watches a folder. This is the missing half: a
single file, no dependencies, that you run next to it. Open the page on a laptop or a
phone, drop a folder in, and the album is on the server and scanned before you have
closed the tab.

    python3 album_drop.py --music-root /music --token <something-long>

Then open http://<server>:880/?t=<token>

Why this shape:

* **Stdlib only.** It has to run on a small VM that may never get another package
  installed, and a tool that needs a build step to upload a file is not simpler than
  scp.
* **No multipart parsing.** The page sends one file per request with the bytes as the
  raw body and the path in the query string, so the server just reads Content-Length.
  `cgi.FieldStorage` — the obvious way — is deprecated and gone in 3.13, and hand-rolling
  multipart is exactly the kind of code that quietly mishandles a filename with a comma.
* **A token, always.** This writes files to disk from the open internet. There is no
  no-auth mode, and it refuses to start without a token rather than defaulting to one.

It is deliberately not a general file manager: an extension allowlist, a size cap, and
every path component sanitised. It puts audio and cover art into the music folder, and
that is all it can do.
"""
import argparse
import hmac
import json
import os
import re
import sys
import unicodedata
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

AUDIO = {".flac", ".mp3", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".aiff", ".aif", ".wma", ".alac"}
ART = {".jpg", ".jpeg", ".png", ".webp"}
SIDECAR = {".lrc", ".cue"}
ALLOWED = AUDIO | ART | SIDECAR

MAX_BYTES = 512 * 1024 * 1024  # one file; a long lossless track is nowhere near this
MAX_COMPONENT = 120

PAGE = r"""<!doctype html>
<title>Drop albums</title>
<style>
  :root { color-scheme: dark; }
  body { margin:0; background:#0b0b0d; color:#e8e4da;
         font:14px ui-monospace,Menlo,Consolas,monospace; }
  main { max-width:760px; margin:0 auto; padding:32px 20px 80px; }
  h1 { font-size:15px; letter-spacing:2px; font-weight:700; margin:0 0 6px; }
  p.sub { color:#8b8578; margin:0 0 24px; line-height:1.6; }
  #zone { border:1px dashed #3a3a42; padding:56px 20px; text-align:center;
          border-radius:2px; transition:border-color .15s, background .15s; }
  #zone.hot { border-color:#e0a94a; background:#16150f; }
  #zone b { display:block; font-size:14px; letter-spacing:1.5px; margin-bottom:8px; }
  #zone span { color:#8b8578; }
  label.pick { display:inline-block; margin-top:18px; border:1px solid #e0a94a55;
               color:#e0a94a; padding:10px 18px; cursor:pointer; letter-spacing:1.4px;
               font-size:11px; font-weight:700; }
  input[type=file] { display:none; }
  ul { list-style:none; padding:0; margin:24px 0 0; }
  li { display:flex; gap:12px; padding:7px 0; border-bottom:1px solid #1c1c22; }
  li .n { flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  li .s { color:#8b8578; font-size:12px; }
  .ok { color:#7fbf7f; } .bad { color:#d98080; } .go { color:#e0a94a; }
  #done { margin-top:22px; color:#e0a94a; line-height:1.6; }
</style>
<main>
  <h1>DROP ALBUMS HERE</h1>
  <p class="sub">A folder per album, tags already written. Everything lands on the
    server and is scanned straight away — then hit SYNC LIBRARY in Jawon.</p>
  <div id="zone">
    <b>DRAG A FOLDER IN</b>
    <span>or</span>
    <label class="pick">CHOOSE A FOLDER<input type="file" id="pick" webkitdirectory multiple></label>
    <label class="pick">CHOOSE FILES<input type="file" id="pickf" multiple></label>
  </div>
  <ul id="list"></ul>
  <div id="done"></div>
</main>
<script>
const token = new URLSearchParams(location.search).get('t') || '';
const zone = document.getElementById('zone');
const list = document.getElementById('list');
const done = document.getElementById('done');
let queue = [], running = false, okCount = 0, badCount = 0;

function row(path) {
  const li = document.createElement('li');
  li.innerHTML = '<span class="n"></span><span class="s go">waiting</span>';
  li.querySelector('.n').textContent = path;
  list.appendChild(li);
  return li.querySelector('.s');
}

function add(file, path) { queue.push({file, path, cell: row(path)}); }

async function pump() {
  if (running) return;
  running = true;
  while (queue.length) {
    const job = queue.shift();
    job.cell.textContent = 'sending';
    try {
      const res = await fetch('/upload?t=' + encodeURIComponent(token) +
                              '&path=' + encodeURIComponent(job.path), {
        method: 'POST', body: job.file
      });
      const body = await res.json();
      if (res.ok) { job.cell.textContent = 'stored'; job.cell.className = 's ok'; okCount++; }
      else { job.cell.textContent = body.error || ('failed ' + res.status);
             job.cell.className = 's bad'; badCount++; }
    } catch (e) {
      job.cell.textContent = 'failed'; job.cell.className = 's bad'; badCount++;
    }
  }
  running = false;
  done.textContent = okCount + ' stored, ' + badCount + ' skipped. Asking the server to rescan…';
  try {
    const r = await fetch('/rescan?t=' + encodeURIComponent(token), {method: 'POST'});
    const b = await r.json();
    done.textContent = okCount + ' stored, ' + badCount + ' skipped. ' + (b.message || '');
  } catch (e) { done.textContent = okCount + ' stored, ' + badCount + ' skipped.'; }
}

// Directory entries, walked recursively — this is what makes dropping a whole album work.
async function walk(entry, prefix) {
  if (entry.isFile) {
    await new Promise(res => entry.file(f => { add(f, prefix + entry.name); res(); }));
  } else if (entry.isDirectory) {
    const reader = entry.createReader();
    while (true) {
      const batch = await new Promise(res => reader.readEntries(res));
      if (!batch.length) break;
      for (const e of batch) await walk(e, prefix + entry.name + '/');
    }
  }
}

zone.addEventListener('dragover', e => { e.preventDefault(); zone.classList.add('hot'); });
zone.addEventListener('dragleave', () => zone.classList.remove('hot'));
zone.addEventListener('drop', async e => {
  e.preventDefault(); zone.classList.remove('hot');
  const items = [...e.dataTransfer.items].map(i => i.webkitGetAsEntry()).filter(Boolean);
  for (const entry of items) await walk(entry, '');
  pump();
});

function fromInput(input) {
  for (const f of input.files) add(f, f.webkitRelativePath || f.name);
  pump();
}
document.getElementById('pick').addEventListener('change', e => fromInput(e.target));
document.getElementById('pickf').addEventListener('change', e => fromInput(e.target));
</script>
"""


def safe_component(name):
    """One path segment, reduced to something that cannot escape the music root.

    Rejects rather than repairs where repairing would be a guess: '..' and absolute
    paths are refused outright, because a request containing one is not a filename that
    needs tidying, it is a request to write somewhere else.
    """
    name = unicodedata.normalize("NFC", name).strip().strip(".")
    if not name or name in (".", ".."):
        return None
    if "/" in name or "\\" in name or "\x00" in name:
        return None
    # Windows-hostile and shell-hostile characters out; everything else, including
    # accents and CJK, is a perfectly good album name and stays.
    name = re.sub(r'[<>:"|?*\x00-\x1f]', "_", name)
    return name[:MAX_COMPONENT] or None


def safe_relpath(raw):
    """The whole relative path, or None. Keeps at most Artist/Album/Track.ext."""
    parts = [p for p in raw.replace("\\", "/").split("/") if p not in ("", ".")]
    if not parts:
        return None
    cleaned = []
    for p in parts:
        s = safe_component(p)
        if s is None:
            return None
        cleaned.append(s)
    # Deeper than Artist/Album/Disc/Track is almost certainly a mis-drop; flatten the
    # middle rather than refusing, so a stray wrapper folder does not cost the upload.
    if len(cleaned) > 4:
        cleaned = cleaned[:1] + cleaned[-3:]
    if os.path.splitext(cleaned[-1])[1].lower() not in ALLOWED:
        return None
    return os.path.join(*cleaned)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "album_drop"

    # Injected by main()
    music_root = None
    token = None
    scan_url = None

    def log_message(self, fmt, *args):
        sys.stderr.write("  %s\n" % (fmt % args))
        sys.stderr.flush()

    def authorised(self, query):
        supplied = (query.get("t") or [""])[0]
        return bool(self.token) and hmac.compare_digest(supplied, self.token)

    def reply(self, code, payload, content_type="application/json"):
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        if parsed.path not in ("/", "/index.html"):
            return self.reply(404, {"error": "not found"})
        if not self.authorised(query):
            # The page itself is behind the token too, so a stray scan of the port finds
            # nothing that hints at what this is.
            return self.reply(403, {"error": "add ?t=<token> to the address"})
        self.reply(200, PAGE.encode("utf-8"), "text/html; charset=utf-8")

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        if not self.authorised(query):
            return self.reply(403, {"error": "bad token"})
        if parsed.path == "/rescan":
            return self.rescan()
        if parsed.path != "/upload":
            return self.reply(404, {"error": "not found"})

        rel = safe_relpath((query.get("path") or [""])[0])
        if rel is None:
            return self.reply(400, {"error": "not an audio or artwork file"})

        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return self.reply(400, {"error": "empty"})
        if length > MAX_BYTES:
            return self.reply(413, {"error": "too big"})

        dest = os.path.join(self.music_root, rel)
        # Belt and braces over safe_relpath: resolve and confirm the result is still
        # inside the music root before a single byte is written.
        root = os.path.realpath(self.music_root)
        if os.path.commonpath([root, os.path.realpath(os.path.dirname(dest))]) != root:
            return self.reply(400, {"error": "path escapes the music folder"})

        os.makedirs(os.path.dirname(dest), exist_ok=True)
        # Written beside the target and renamed, so a dropped connection leaves a stray
        # .part rather than a half-file the scanner will happily index as a real track.
        temp = dest + ".part"
        remaining = length
        try:
            with open(temp, "wb") as f:
                while remaining > 0:
                    chunk = self.rfile.read(min(1 << 20, remaining))
                    if not chunk:
                        raise IOError("connection closed early")
                    f.write(chunk)
                    remaining -= len(chunk)
            os.replace(temp, dest)
        except Exception as e:
            if os.path.exists(temp):
                os.remove(temp)
            return self.reply(500, {"error": str(e)})

        self.reply(200, {"stored": rel})

    def rescan(self):
        if not self.scan_url:
            return self.reply(200, {"message": "Navidrome will pick it up on its own schedule."})
        try:
            with urllib.request.urlopen(self.scan_url, timeout=15) as r:
                r.read()
            return self.reply(200, {"message": "Server is scanning now."})
        except Exception as e:
            return self.reply(200, {"message": "Uploaded, but the rescan call failed: %s" % e})


def main():
    ap = argparse.ArgumentParser(description="Drag-and-drop uploader for a Navidrome music folder.")
    ap.add_argument("--music-root", required=True, help="the folder Navidrome watches")
    ap.add_argument("--port", type=int, default=880)
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--token", default=os.environ.get("ALBUM_DROP_TOKEN", ""),
                    help="required; or set ALBUM_DROP_TOKEN")
    ap.add_argument("--navidrome-url", default="", help="e.g. http://localhost:4533")
    ap.add_argument("--navidrome-user", default="")
    ap.add_argument("--navidrome-pass", default="")
    args = ap.parse_args()

    if len(args.token) < 16:
        # No default, and no short one. This writes files to disk from the network; a
        # guessable token is the same as no token, and defaulting to one would mean
        # every copy of this script shares it.
        sys.exit("A --token of at least 16 characters is required.\n"
                 "Generate one:  python3 -c \"import secrets;print(secrets.token_urlsafe(24))\"")

    root = os.path.realpath(args.music_root)
    if not os.path.isdir(root):
        sys.exit("No such folder: %s" % root)

    scan_url = ""
    if args.navidrome_url and args.navidrome_user:
        import hashlib
        import secrets
        salt = secrets.token_hex(8)
        token = hashlib.md5((args.navidrome_pass + salt).encode()).hexdigest()
        scan_url = ("%s/rest/startScan.view?u=%s&t=%s&s=%s&v=1.16.1&c=album_drop&f=json"
                    % (args.navidrome_url.rstrip("/"),
                       urllib.parse.quote(args.navidrome_user), token, salt))

    Handler.music_root = root
    Handler.token = args.token
    Handler.scan_url = scan_url

    print("album_drop -> %s" % root)
    print("open  http://<this-machine>:%d/?t=%s" % (args.port, args.token))
    if not scan_url:
        print("(no --navidrome-url given, so uploads wait for Navidrome's own scan)")
    sys.stdout.flush()
    ThreadingHTTPServer((args.bind, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
