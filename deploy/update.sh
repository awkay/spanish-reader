#!/bin/sh
# Installs or updates the Spanish Reader server binary and web app from the latest GitHub release.
# Run as root on the server: sh /opt/spanish-reader/update.sh   (or pass a tag: sh update.sh build-7)
set -eu

REPO=awkay/spanish-reader
TAG=${1:-}
case "$(uname -m)" in
    x86_64) ARCH=amd64 ;;
    aarch64 | arm64) ARCH=arm64 ;;
    *) echo "Unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac

if [ -n "$TAG" ]; then
    API="https://api.github.com/repos/$REPO/releases/tags/$TAG"
else
    API="https://api.github.com/repos/$REPO/releases/latest"
fi
JSON=$(curl -fsSL "$API")
TAG=$(printf '%s' "$JSON" | grep -o '"tag_name": *"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
BIN_URL=$(printf '%s' "$JSON" | grep -o "https://[^\"]*spanish-reader-server-linux-$ARCH" | head -1)
WEB_URL=$(printf '%s' "$JSON" | grep -o 'https://[^"]*spanish-reader-web.tar.gz' | head -1)
if [ -z "$BIN_URL" ] || [ -z "$WEB_URL" ]; then
    echo "Release $TAG has no server/web assets" >&2
    exit 1
fi
echo "Installing $TAG"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -fsSL -o "$TMP/server" "$BIN_URL"
curl -fsSL -o "$TMP/web.tar.gz" "$WEB_URL"
mkdir -p "$TMP/web"
tar -xzf "$TMP/web.tar.gz" -C "$TMP/web"
test -f "$TMP/web/index.html"

install -d /opt/spanish-reader/bin
install -m 0755 "$TMP/server" /opt/spanish-reader/bin/spanish-reader-server.new
mv /opt/spanish-reader/bin/spanish-reader-server.new /opt/spanish-reader/bin/spanish-reader-server

rm -rf /opt/spanish-reader/web.new
cp -r "$TMP/web" /opt/spanish-reader/web.new
chmod -R a+rX /opt/spanish-reader/web.new
rm -rf /opt/spanish-reader/web.old
if [ -d /opt/spanish-reader/web ]; then mv /opt/spanish-reader/web /opt/spanish-reader/web.old; fi
mv /opt/spanish-reader/web.new /opt/spanish-reader/web
rm -rf /opt/spanish-reader/web.old
echo "$TAG" > /opt/spanish-reader/VERSION

# YouTube changes often; an old yt-dlp stops working. Only the standalone release binary can update itself.
if [ -x /usr/local/bin/yt-dlp ]; then /usr/local/bin/yt-dlp -U || echo "yt-dlp update failed (continuing)"; fi

systemctl restart spanish-reader
sleep 1
systemctl --no-pager --lines=3 status spanish-reader
echo "Done: $TAG"
