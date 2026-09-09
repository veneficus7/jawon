#!/usr/bin/env bash
#
# Stand up a music server Jawon can play from, on a fresh Ubuntu/Debian box.
#
#   sudo bash server_setup.sh --domain music.example.net
#   sudo bash server_setup.sh --no-tls            # LAN only, plain http
#
# Installs Navidrome and album_drop as systemd services, puts Caddy in front for
# automatic HTTPS, and opens the host firewall. Re-running it is safe: everything here
# is idempotent, so it doubles as the upgrade path.
#
# What it deliberately does NOT do:
#
#   * Create the machine. That is your cloud console, and it needs your account.
#   * Open the cloud provider's own firewall. On Oracle, AWS and GCP there are TWO
#     firewalls — the one on the host, which this handles, and a Security List / Security
#     Group in the web console, which nothing on the machine can reach. Forgetting the
#     second is the single most common reason a correctly-installed server appears dead,
#     so the summary at the end says so again.
#   * Create the Navidrome admin user. Navidrome makes the first account from the web UI
#     on first visit, and scripting around that would mean writing its database by hand.
#
set -euo pipefail

DOMAIN=""
USE_TLS=1
MUSIC_DIR="/music"
NAVIDROME_PORT=4533
DROP_PORT=880
DROP_SUBDOMAIN="drop"

die() { echo "error: $*" >&2; exit 1; }
say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --domain)     DOMAIN="${2:-}"; shift 2 ;;
    --no-tls)     USE_TLS=0; shift ;;
    --music-dir)  MUSIC_DIR="${2:-}"; shift 2 ;;
    -h|--help)    sed -n '2,30p' "$0"; exit 0 ;;
    *)            die "unknown option: $1" ;;
  esac
done

[[ $EUID -eq 0 ]] || die "run this with sudo"
[[ $USE_TLS -eq 0 || -n $DOMAIN ]] || die "give --domain <name>, or --no-tls for a LAN-only server"

command -v apt-get >/dev/null || die "this script is for Ubuntu/Debian; adapt it for other distros"

# The Oracle free tier is ARM, and most guides assume x86. Getting this wrong downloads a
# binary that will not run and fails with a message that does not mention architecture.
case "$(uname -m)" in
  x86_64|amd64)  ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  *)             die "unsupported architecture: $(uname -m)" ;;
esac
say "Architecture: $ARCH"

say "Installing prerequisites"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates ffmpeg python3 jq >/dev/null

# ---------------------------------------------------------------------------- Navidrome

say "Installing Navidrome"
id -u navidrome >/dev/null 2>&1 || useradd --system --home /var/lib/navidrome --shell /usr/sbin/nologin navidrome
install -d -o navidrome -g navidrome /var/lib/navidrome /etc/navidrome
install -d -o navidrome -g navidrome "$MUSIC_DIR"

LATEST="$(curl -fsSL https://api.github.com/repos/navidrome/navidrome/releases/latest | jq -r .tag_name)"
[[ -n $LATEST && $LATEST != null ]] || die "could not find the latest Navidrome release"
VERSION="${LATEST#v}"
CURRENT="$(/opt/navidrome/navidrome --version 2>/dev/null | head -1 || true)"

if [[ $CURRENT == *"$VERSION"* ]]; then
  echo "Navidrome $VERSION already installed"
else
  TARBALL="navidrome_${VERSION}_linux_${ARCH}.tar.gz"
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  curl -fsSL -o "$TMP/$TARBALL" \
    "https://github.com/navidrome/navidrome/releases/download/${LATEST}/${TARBALL}"
  # Verified against the checksums published with the same release. This catches a
  # truncated or corrupted download; it is not a defence against a compromised release,
  # and pretending otherwise would be worse than saying so.
  curl -fsSL -o "$TMP/sums" \
    "https://github.com/navidrome/navidrome/releases/download/${LATEST}/checksums.txt"
  ( cd "$TMP" && grep " ${TARBALL}\$" sums | sha256sum -c - ) \
    || die "checksum mismatch on the Navidrome download — refusing to install it"
  install -d /opt/navidrome
  tar -xzf "$TMP/$TARBALL" -C /opt/navidrome navidrome
  chmod +x /opt/navidrome/navidrome
  echo "Installed Navidrome $VERSION"
fi

cat > /etc/navidrome/navidrome.toml <<EOF
MusicFolder = "$MUSIC_DIR"
DataFolder = "/var/lib/navidrome"
Address = "127.0.0.1"
Port = $NAVIDROME_PORT
# Rescans on a timer as a backstop; album_drop also asks for a scan the moment an upload
# finishes, so this only has to catch files that arrived some other way.
ScanSchedule = "@every 1h"
# Jawon asks for format=raw and reads ReplayGain out of the file's own tags, so leave the
# audio alone by default. A client that wants transcoding can still ask per-player.
EOF
chown navidrome:navidrome /etc/navidrome/navidrome.toml
chmod 640 /etc/navidrome/navidrome.toml

cat > /etc/systemd/system/navidrome.service <<'EOF'
[Unit]
Description=Navidrome Music Server
After=network-online.target
Wants=network-online.target

[Service]
User=navidrome
Group=navidrome
ExecStart=/opt/navidrome/navidrome --configfile /etc/navidrome/navidrome.toml
Restart=on-failure
RestartSec=5
# Nothing here needs to write outside its own directories or gain privileges.
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/navidrome MUSIC_DIR_PLACEHOLDER

[Install]
WantedBy=multi-user.target
EOF
sed -i "s|MUSIC_DIR_PLACEHOLDER|$MUSIC_DIR|" /etc/systemd/system/navidrome.service

# ---------------------------------------------------------------------------- album_drop

say "Installing album_drop"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ -f "$SCRIPT_DIR/album_drop.py" ]] || die "album_drop.py must sit next to this script"

install -d /opt/album_drop
install -m 755 "$SCRIPT_DIR/album_drop.py" /opt/album_drop/album_drop.py

# The token is generated once and then left alone, so re-running this script does not
# silently invalidate a link somebody is already using.
TOKEN_FILE=/etc/navidrome/album_drop.token
if [[ -s $TOKEN_FILE ]]; then
  DROP_TOKEN="$(cat "$TOKEN_FILE")"
  echo "Keeping the existing upload token"
else
  DROP_TOKEN="$(head -c 24 /dev/urandom | base64 | tr -d '=+/' | cut -c1-32)"
  printf '%s' "$DROP_TOKEN" > "$TOKEN_FILE"
  chmod 600 "$TOKEN_FILE"
fi

cat > /etc/systemd/system/album-drop.service <<EOF
[Unit]
Description=album_drop uploader for Navidrome
After=network-online.target

[Service]
User=navidrome
Group=navidrome
Environment=ALBUM_DROP_TOKEN=$DROP_TOKEN
# Bound to localhost on purpose: Caddy is what faces the internet, so this is reachable
# only over TLS and never directly on its own port.
ExecStart=/usr/bin/python3 /opt/album_drop/album_drop.py \\
  --music-root $MUSIC_DIR --bind 127.0.0.1 --port $DROP_PORT \\
  --navidrome-url http://127.0.0.1:$NAVIDROME_PORT
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$MUSIC_DIR

[Install]
WantedBy=multi-user.target
EOF

# ---------------------------------------------------------------------------- Caddy / TLS

if [[ $USE_TLS -eq 1 ]]; then
  say "Installing Caddy for automatic HTTPS"
  if ! command -v caddy >/dev/null; then
    apt-get install -y -qq debian-keyring debian-archive-keyring apt-transport-https >/dev/null
    curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/gpg.key \
      | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -fsSL https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt \
      | tee /etc/apt/sources.list.d/caddy-stable.list >/dev/null
    apt-get update -qq
    apt-get install -y -qq caddy >/dev/null
  fi

  # Two host blocks rather than one with a sub-path: album_drop's page asks for /upload
  # and /rescan at the root, so serving it under a prefix would break every request it
  # makes. A subdomain costs one DNS record and nothing else.
  cat > /etc/caddy/Caddyfile <<EOF
$DOMAIN {
	reverse_proxy 127.0.0.1:$NAVIDROME_PORT
}

$DROP_SUBDOMAIN.$DOMAIN {
	reverse_proxy 127.0.0.1:$DROP_PORT
}
EOF
  systemctl enable --now caddy >/dev/null 2>&1 || true
  systemctl reload caddy 2>/dev/null || systemctl restart caddy
else
  say "Skipping TLS (--no-tls)"
  # Without Caddy nothing would be reachable, since both services bind to localhost.
  sed -i "s|Address = \"127.0.0.1\"|Address = \"0.0.0.0\"|" /etc/navidrome/navidrome.toml
  sed -i "s|--bind 127.0.0.1|--bind 0.0.0.0|" /etc/systemd/system/album-drop.service
fi

# ---------------------------------------------------------------------------- firewall

say "Opening the host firewall"
if command -v ufw >/dev/null && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow 22/tcp >/dev/null
  if [[ $USE_TLS -eq 1 ]]; then ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null
  else ufw allow $NAVIDROME_PORT/tcp >/dev/null; ufw allow $DROP_PORT/tcp >/dev/null; fi
  echo "ufw rules added"
else
  # Ubuntu images on Oracle ship an iptables INPUT chain that REJECTs everything past
  # port 22, and the rule sits *above* anything appended — so -A silently does nothing and
  # the port stays shut. Inserting at the top is the fix, and the rules have to be
  # persisted or they vanish on the next reboot.
  add_rule() {
    iptables -C INPUT -p tcp --dport "$1" -j ACCEPT 2>/dev/null \
      || iptables -I INPUT 1 -p tcp --dport "$1" -j ACCEPT
  }
  if [[ $USE_TLS -eq 1 ]]; then add_rule 80; add_rule 443
  else add_rule $NAVIDROME_PORT; add_rule $DROP_PORT; fi
  apt-get install -y -qq iptables-persistent netfilter-persistent >/dev/null 2>&1 || true
  netfilter-persistent save >/dev/null 2>&1 || true
  echo "iptables rules inserted and saved"
fi

# ---------------------------------------------------------------------------- start

say "Starting services"
systemctl daemon-reload
systemctl enable --now navidrome >/dev/null
systemctl enable --now album-drop >/dev/null
sleep 3
systemctl is-active --quiet navidrome  || die "navidrome failed to start: journalctl -u navidrome"
systemctl is-active --quiet album-drop || die "album-drop failed to start: journalctl -u album-drop"

if [[ $USE_TLS -eq 1 ]]; then
  BASE="https://$DOMAIN"
  DROP="https://$DROP_SUBDOMAIN.$DOMAIN/?t=$DROP_TOKEN"
else
  IP="$(curl -fsSL --max-time 5 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')"
  BASE="http://$IP:$NAVIDROME_PORT"
  DROP="http://$IP:$DROP_PORT/?t=$DROP_TOKEN"
fi

cat <<EOF

============================================================
  Done. Three things left, and only you can do them.
============================================================

1. OPEN THE CLOUD FIREWALL TOO.
   The host firewall is set. Your provider has a second one the machine
   cannot touch:
     Oracle : Networking > VCN > Subnet > Security List > Add Ingress Rule
     AWS    : the instance's Security Group
     GCP    : VPC network > Firewall
$(if [[ $USE_TLS -eq 1 ]]; then echo "   Open TCP 80 and 443 from 0.0.0.0/0."; \
   else echo "   Open TCP $NAVIDROME_PORT and $DROP_PORT from 0.0.0.0/0."; fi)
   If the addresses below time out, this is why.
$(if [[ $USE_TLS -eq 1 ]]; then cat <<DNS

   DNS: point $DOMAIN and $DROP_SUBDOMAIN.$DOMAIN at this machine.
   Caddy gets certificates automatically once they resolve.
DNS
fi)

2. MAKE YOUR ACCOUNTS.
   Open  $BASE
   The first visit creates the admin account. Then, in Settings > Users,
   add a SECOND, non-admin user — call it 'guest' — with a long random
   password. That is the account you hand out. Never the admin.

3. MAKE THE INVITE LINK.
   python3 tools/make_invite.py $BASE guest '<the guest password>'

   Paste the result into Jawon, or bake it into a build:
     ./gradlew assembleRelease -Pjawon.serverInvite=<link>

------------------------------------------------------------
Uploading albums:   $DROP
  (bookmark it — the token is the password, and it is also in
   $TOKEN_FILE)

Music folder:       $MUSIC_DIR
Logs:               journalctl -u navidrome -f
                    journalctl -u album-drop -f
Re-run this script any time; it upgrades in place and keeps your token.
============================================================
EOF
