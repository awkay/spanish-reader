# Web app (iPhone) — server setup on the Linode

Instructions for a Claude Code session with ssh access to the Linode. Work through them in order. Each step says how
to check it. **Never write the access code or the AI key into this repository, a commit, or a chat log you paste
elsewhere.** Ask Tony for them when you reach step 6.

## What gets installed

```
iPhone Safari (PWA, data in IndexedDB)  ──https──▶  nginx (existing, TLS)
                                                    ├─ /        static files  /opt/spanish-reader/web
                                                    └─ /api/    proxy ──▶ 127.0.0.1:8090  spanish-reader-server (Go, systemd)
                                                                          ├─ AI proxy → z.ai (key on server)
                                                                          ├─ Piper TTS + lame → MP3 per page
                                                                          └─ JSON store /var/lib/spanish-reader
```

- `spanish-reader-server`: one static Go binary (~7 MB). It uses ~15 MB RAM idle. Piper runs as a child process
  that peaks at ~200 MB while it renders a page, and only one render runs at a time. The systemd unit caps
  everything at 600 MB.
- The server keeps no per-user state except shared lessons and the shared sentence cache (glosses, translations,
  expressions). Vocabulary and word statuses live only on each phone.
- Releases: every push to `main` publishes GitHub Release `build-<n>` containing
  `spanish-reader-server-linux-amd64`, `spanish-reader-server-linux-arm64` and `spanish-reader-web.tar.gz`.
  `deploy/update.sh` installs the latest one.

Files referenced below live in `deploy/` of https://github.com/awkay/spanish-reader. Fetch them raw, for example:
`curl -fsSLO https://raw.githubusercontent.com/awkay/spanish-reader/main/deploy/update.sh`.

## 1. DNS

Add an **A** record `spanish-reader.fulcrologic.com` → the Linode's IPv4 address. Add an AAAA record too if the other
sites have one. Check: `dig +short spanish-reader.fulcrologic.com` returns the Linode's address.

## 2. Look at the box first

```sh
uname -m                 # x86_64 → amd64 assets, aarch64 → arm64
free -m                  # expect ~2 GB total; need ~300 MB free headroom for Piper
df -h /opt /var/lib      # need ~250 MB (Piper 25 MB + voice 63 MB + binary + audio cache)
nginx -v; ls /etc/nginx/sites-enabled /etc/nginx/conf.d 2>/dev/null
ls /etc/letsencrypt/live 2>/dev/null; which certbot
ss -ltnp | grep 8090     # must be empty; otherwise pick another port and change SR_LISTEN + the nginx proxy_pass
```

Look at how the existing sites get their certificates (certbot `--nginx` plugin, webroot, acme.sh, …) and use the
same method in step 8.

## 3. User and directories

```sh
sudo useradd --system --home /var/lib/spanish-reader --shell /usr/sbin/nologin spanish-reader
sudo install -d -o root -g root -m 0755 /opt/spanish-reader /opt/spanish-reader/bin /opt/piper
sudo install -d -o spanish-reader -g spanish-reader -m 0700 /var/lib/spanish-reader
```

## 4. lame and Piper

```sh
sudo apt-get update && sudo apt-get install -y lame
which lame               # /usr/bin/lame (that path is in the env file)

# Piper 2023.11.14-2. Use piper_linux_aarch64.tar.gz on arm64.
cd /tmp && curl -fsSLO https://github.com/rhasspy/piper/releases/download/2023.11.14-2/piper_linux_x86_64.tar.gz
sudo tar -xzf piper_linux_x86_64.tar.gz -C /opt/piper      # creates /opt/piper/piper/{piper,lib*,espeak-ng-data}
/opt/piper/piper/piper --help | head -3
```

The voice. Tony chooses between `es_MX-claude-high` (default), `es_MX-ald-medium` and `es_MX-ald-x_low` (smallest,
21 MB). Ask Tony which one if he hasn't said. Both the `.onnx` and the `.onnx.json` files are needed:

```sh
sudo install -d -m 0755 /opt/piper/voices
V=es_MX-claude-high; P=es/es_MX/claude/high      # ald-medium: P=es/es_MX/ald/medium   ald-x_low: P=es/es_MX/ald/x_low
for f in $V.onnx $V.onnx.json; do
  sudo curl -fsSL -o /opt/piper/voices/$f https://huggingface.co/rhasspy/piper-voices/resolve/main/$P/$f
done
echo 'Hola, ¿cómo estás?' | /opt/piper/piper/piper --model /opt/piper/voices/$V.onnx --output_file /tmp/t.wav && ls -l /tmp/t.wav
```

You can switch voices later: download another voice, change `SR_PIPER_MODEL` and restart. Audio is cached per voice,
so nothing else needs clearing.

## 5. Binary and web files

```sh
curl -fsSL -o /tmp/update.sh https://raw.githubusercontent.com/awkay/spanish-reader/main/deploy/update.sh
sudo install -m 0755 /tmp/update.sh /opt/spanish-reader/update.sh
```

Don't run it yet: it restarts the service, which doesn't exist until step 7.

## 6. Configuration (secrets)

```sh
curl -fsSL -o /tmp/sr.env https://raw.githubusercontent.com/awkay/spanish-reader/main/deploy/spanish-reader.env.example
sudo install -o root -g spanish-reader -m 0640 /tmp/sr.env /etc/spanish-reader.env
sudo nano /etc/spanish-reader.env     # or sed: fill in the two CHANGE_ME values
```

- `SR_ACCESS_CODE`: the household code. **Ask Tony.** Don't print it back.
- `SR_AI_API_KEY`: Tony's z.ai key. **Ask Tony.** The defaults use z.ai's Responses API with `glm-5.3-flash`, and
  `glm-5.3` for "Improve". Other providers work too:

  | Provider | Settings |
  |---|---|
  | OpenAI-compatible chat (Ollama Cloud, local Ollama, z.ai chat) | `SR_AI_PROTOCOL=chat` with `SR_AI_BASE_URL=https://ollama.com/v1` |
  | Anthropic | `SR_AI_PROTOCOL=anthropic`, `SR_AI_BASE_URL=https://api.anthropic.com`, `SR_AI_MODEL=claude-haiku-4-5` |

- `SR_PIPER_MODEL`: the voice from step 4.
- `SR_SECRET` is optional. If it's unset, the server generates `/var/lib/spanish-reader/secret` on first start. That
  file signs session tokens, and deleting it signs everyone out.

Check: `sudo stat -c '%U:%G %a' /etc/spanish-reader.env` shows `root:spanish-reader 640`.

## 7. systemd service, then install the release

```sh
curl -fsSL -o /tmp/sr.service https://raw.githubusercontent.com/awkay/spanish-reader/main/deploy/spanish-reader.service
sudo install -m 0644 /tmp/sr.service /etc/systemd/system/spanish-reader.service
sudo systemctl daemon-reload
sudo systemctl enable spanish-reader
sudo sh /opt/spanish-reader/update.sh       # downloads the latest build, installs it and (re)starts the service
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8090/api/session    # expect 401
journalctl -u spanish-reader -n 20 --no-pager
```

The log should end with something like `listening on 127.0.0.1:8090 (data in /var/lib/spanish-reader, tts: true, voice: "es_MX-claude-high")`. If it shows `tts: false`, check the
Piper/lame paths in the env file.

## 8. nginx and TLS

```sh
curl -fsSL -o /tmp/sr.nginx https://raw.githubusercontent.com/awkay/spanish-reader/main/deploy/nginx-spanish-reader.conf
sudo install -m 0644 /tmp/sr.nginx /etc/nginx/sites-available/spanish-reader.conf
sudo ln -s /etc/nginx/sites-available/spanish-reader.conf /etc/nginx/sites-enabled/
#  (on a conf.d-style install, copy it to /etc/nginx/conf.d/spanish-reader.conf instead)
#  If the box has no IPv6, delete the `listen [::]:80;` line.
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d spanish-reader.fulcrologic.com     # or the method the other sites use
sudo nginx -t && sudo systemctl reload nginx
```

The site must be HTTPS: the session cookie is `Secure`, and iOS only installs PWAs and service workers over HTTPS.
If certbot's edit adds its own `server` block for port 80, keep the redirect and leave the `location` blocks in the 443
block.

Check (all of these were verified against this exact nginx config in a test container):

```sh
curl -sI https://spanish-reader.fulcrologic.com/ | grep -i -E 'HTTP/|cache-control'            # 200, no-cache
curl -sI https://spanish-reader.fulcrologic.com/manifest.webmanifest | grep -i content-type    # application/manifest+json
curl -s -o /dev/null -w '%{http_code}\n' https://spanish-reader.fulcrologic.com/api/session   # 401
# A wrong code takes ~2 s and is refused (don't loop this: 20 failures/hour lock logins for an hour):
time curl -s -X POST -H 'Content-Type: application/json' -d '{"code":"000000"}' https://spanish-reader.fulcrologic.com/api/login
```

End-to-end with the real code. Read the code from the env file so it never appears in the transcript:

```sh
CODE=$(sudo sed -n 's/^SR_ACCESS_CODE=//p' /etc/spanish-reader.env)
J=$(mktemp); curl -s -c $J -H 'Content-Type: application/json' -d "{\"code\":\"$CODE\"}" https://spanish-reader.fulcrologic.com/api/login >/dev/null
curl -s -b $J https://spanish-reader.fulcrologic.com/api/session; echo                     # {"ok":true,"tts":true,...}
curl -s -b $J -H 'Content-Type: application/json' -d '{"sentences":["Hola, ¿cómo estás?","Muy bien."]}' \
  https://spanish-reader.fulcrologic.com/api/tts; echo                                     # {"audio":"/api/audio/….mp3","timings":[[0,…],[…]]}
curl -s -b $J -H 'Content-Type: application/json' -d '{"system":"Reply with the single word ok.","user":"ping","items":1}' \
  https://spanish-reader.fulcrologic.com/api/ai; echo                                      # {"text":"ok"} (or similar)
rm -f $J
```

`free -m` during the TTS call should show the box still comfortable.

## 9. On the phones (Tony does this)

1. Open https://spanish-reader.fulcrologic.com in **Safari** and enter the access code.
2. Share → **Add to Home Screen**, then open it from the home-screen icon. From then on it runs full screen and works
   offline for lessons that are already loaded.
3. In the app's Settings: set **Your name** (shown on shared lessons) and tap **Keep data on this device**, which
   asks iOS for persistent storage. Use **Export backup** now and then: iOS can evict web data from apps that go
   unused for weeks.
4. Android app → Settings → **Web app (Share to web)**: the URL is prefilled. Enter the access code and your name.
   Then, in the library, a lesson's menu → **Share to web** uploads the lesson with its cached glosses and
   translations (never word statuses). It appears under **Shared lessons** in the web app.

## Updating

Each push to `main` produces a new release. To install it:

```sh
sudo sh /opt/spanish-reader/update.sh            # latest
sudo sh /opt/spanish-reader/update.sh build-12   # a specific build (also how to roll back)
cat /opt/spanish-reader/VERSION
```

Installed phones pick up the new web build the next time the app opens online. The service worker is versioned per
build.

If you want automatic updates, add a nightly cron: `17 4 * * * root sh /opt/spanish-reader/update.sh >/var/log/spanish-reader-update.log 2>&1`
in `/etc/cron.d/spanish-reader`. Ask Tony before enabling it.

## Backups

Everything the server owns is under `/var/lib/spanish-reader`:

| Path | Contents | Back up? |
|---|---|---|
| `store/lessons/*.json` | shared lessons | yes |
| `store/sentences/` | shared gloss/translation cache | yes (AI money went into it) |
| `secret` | token signing key | optional; losing it only signs everyone out |
| `tts/` | regenerable MP3s and Piper WAVs | no |

```sh
sudo tar -czf ~/spanish-reader-store-$(date +%F).tgz -C /var/lib/spanish-reader store
```

## Troubleshooting

- **Logs:** `journalctl -u spanish-reader -f`. AI provider errors are logged (`ai: …`) and reach the app
  as "AI request failed".
- **Memory:** check `systemctl status spanish-reader` (it shows memory and peak). If the box is tight, use the
  `es_MX-ald-x_low` voice (~170 MB peak instead of ~200 MB) or lower `MemoryMax`. A page that runs out of memory
  fails with a TTS error, and the server keeps running.
- **Audio cache growth:** about 6 KB per spoken second (48 kbps MP3) plus WAVs. Clearing it is safe:
  `sudo systemctl stop spanish-reader && sudo rm -rf /var/lib/spanish-reader/tts && sudo systemctl start spanish-reader`.
- **Locked out after typos:** 20 wrong codes in an hour lock logins for the rest of the hour. Restarting the service
  clears the lock.
- **Changing the access code:** edit `/etc/spanish-reader.env` and restart. Existing sessions stop working, and the
  phones and the Android app need the new code.
- **502 from nginx:** the service is down. Check `systemctl status spanish-reader` and the env file permissions.
- **Phone shows an old version:** close the app fully and reopen it while online. As a last resort, Settings → Safari
  → Advanced → Website Data → remove the site. That also deletes local vocabulary, so export a backup first.
