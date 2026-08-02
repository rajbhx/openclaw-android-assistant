#!/usr/bin/env bash
#
# Build a fully preinstalled Termux prefix and package it as an APK asset.
#
# "Preinstalled requirements" means every package (Node.js, Python, npm
# packages, the Codex native binary, proot, libraries) is baked into the APK
# itself. On first launch the app extracts the prefix and runs offline — no
# downloads and no installs happen on the device.
#
# Why this works on a plain Linux CI runner (x86_64 or arm64):
#   Termux binaries link against Android's bionic linker
#   (/system/bin/linker64), so they cannot be executed on a Linux host. This
#   script therefore NEVER runs them. Instead it:
#     1. extracts the Termux bootstrap and every needed .deb with dpkg-deb -x
#     2. runs host-node npm installs with --prefix into the staging prefix
#     3. installs the static Codex native binary from its npm tarball
#     4. applies the same patches the Android app applies on-device
#     5. rewrites hardcoded Termux paths to the app's real prefix
#     6. packages everything into app/src/main/assets/preinstalled-prefix.zip
#        (regular files + SYMLINKS.txt, the Termux bootstrap layout)
#
# The Android app (BootstrapInstaller) prefers this asset over the plain
# bootstrap archive, so every "is X installed?" check short-circuits on first
# launch.
#
# Usage:
#   bash android/scripts/preinstall-requirements.sh [--arch aarch64]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ASSETS_DIR="$PROJECT_DIR/app/src/main/assets"

ARCH="aarch64"
if [ "${1:-}" = "--arch" ] && [ -n "${2:-}" ]; then
    ARCH="$2"
fi

TERMUX_PREFIX="/data/data/com.termux/files/usr"
DEVICE_PREFIX="/data/user/0/com.codex.mobile.op7/files/usr"

BOOTSTRAP_VERSION="bootstrap-2026.02.12-r1+apt.android-7"
BOOTSTRAP_URL="https://github.com/termux/termux-packages/releases/download/${BOOTSTRAP_VERSION}/bootstrap-${ARCH}.zip"
MIRROR_URL="https://sourceforge.net/projects/termux-packages.mirror/files/${BOOTSTRAP_VERSION}/bootstrap-${ARCH}.zip/download"

REPO_BASE="https://packages.termux.dev/apt/termux-main"

# Every Termux package the app installs at first run — minus the koffi build
# toolchain (cmake/clang/binutils/lld/libllvm/ndk-*), which is only needed to
# compile native modules at install time and is not used at runtime.
PACKAGES=(
    proot libtalloc termux-exec
    dropbear zlib termux-auth openssl
    c-ares libicu libsqlite nodejs-lts npm
    python python-pip
    git make
    libedit libffi libarchive libxml2 liblzma libcurl libuv
    libnghttp2 libnghttp3 rhash jsoncpp
)

WORK="$PROJECT_DIR/.preinstall"
STAGE_ROOT="$WORK/rootfs"
STAGE_PREFIX="$STAGE_ROOT$TERMUX_PREFIX"
DEBS_DIR="$WORK/debs"

mkdir -p "$ASSETS_DIR" "$WORK" "$DEBS_DIR"

log() { echo "==> $*"; }
die() { echo "ERROR: $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Termux bootstrap archive
# ---------------------------------------------------------------------------
BOOTSTRAP_ZIP="$ASSETS_DIR/bootstrap-${ARCH}.zip"
if [ ! -f "$BOOTSTRAP_ZIP" ]; then
    BOOTSTRAP_ZIP="$WORK/bootstrap-${ARCH}.zip"
    if [ ! -f "$BOOTSTRAP_ZIP" ]; then
        log "Downloading the Termux bootstrap..."
        curl -fSL --retry 3 -o "$BOOTSTRAP_ZIP" "$BOOTSTRAP_URL" \
            || curl -fSL --retry 3 -L -o "$BOOTSTRAP_ZIP" "$MIRROR_URL" \
            || die "Failed to download the Termux bootstrap"
    fi
fi

log "Extracting the bootstrap into the staging root..."
python3 - "$BOOTSTRAP_ZIP" "$STAGE_ROOT/data/data/com.termux/files" <<'PYEOF'
import os, sys, zipfile
src, dst_parent = sys.argv[1], sys.argv[2]
os.makedirs(dst_parent, exist_ok=True)
dst = os.path.join(dst_parent, "usr")
with zipfile.ZipFile(src) as z:
    z.extractall(dst)
print("bootstrap extracted to", dst)
PYEOF

# ---------------------------------------------------------------------------
# 2. Termux package index + .deb downloads
# ---------------------------------------------------------------------------
log "Downloading the Termux package index..."
curl -fsSL --retry 3 -o "$WORK/Packages.gz" \
    "$REPO_BASE/dists/stable/main/binary-$ARCH/Packages.gz"

python3 - "$WORK/Packages.gz" "${PACKAGES[@]}" > "$WORK/deb-urls.txt" <<'PYEOF'
import gzip, sys
index = gzip.open(sys.argv[1], "rt").read()

# Parse the Debian package index into stanzas (Package -> fields).
by_pkg = {}
for stanza in index.split("\n\n"):
    fields = {}
    current = None
    for line in stanza.splitlines():
        if not line.strip():
            continue
        if line[:1] in (" ", "\t"):
            if current:
                fields[current] += "\n" + line.strip()
            continue
        if ":" in line:
            key, value = line.split(":", 1)
            fields[key] = value.strip()
            current = key
        else:
            current = None
    if "Package" in fields:
        by_pkg[fields["Package"]] = fields

missing = []
for pkg in sys.argv[2:]:
    fields = by_pkg.get(pkg)
    if not fields or "Filename" not in fields:
        missing.append(pkg)
        continue
    print(fields["Filename"], fields.get("Size", "0"))
if missing:
    print("ERROR: packages not in the index: " + ", ".join(missing), file=sys.stderr)
    sys.exit(1)
print("resolved %d packages" % (len(sys.argv) - 2), file=sys.stderr)
PYEOF

log "Downloading ${#PACKAGES[@]} .deb packages..."
while read -r deb_url deb_size; do
    [ -n "$deb_url" ] || continue
    out="$DEBS_DIR/$(basename "$deb_url")"
    if [ -f "$out" ] && [ "$(stat -c%s "$out" 2>/dev/null || echo 0)" = "$deb_size" ]; then
        echo "  (cached) $(basename "$deb_url")"
        continue
    fi
    echo "  $deb_url"
    if ! curl -fsSL --retry 5 --retry-all-errors --retry-delay 2 -o "$out" "$REPO_BASE/$deb_url"; then
        echo "  retrying once after a short pause..."
        sleep 3
        curl -fsSL --retry 5 --retry-all-errors --retry-delay 2 -o "$out" "$REPO_BASE/$deb_url" \
            || die "failed to download $(basename "$deb_url")"
    fi
done < "$WORK/deb-urls.txt"
EXPECTED_DEBS=$(wc -l < "$WORK/deb-urls.txt" | tr -d ' ')
ACTUAL_DEBS=$(find "$DEBS_DIR" -maxdepth 1 -name '*.deb' -size +0c | wc -l | tr -d ' ')
[ "$EXPECTED_DEBS" = "$ACTUAL_DEBS" ] || die "expected $EXPECTED_DEBS debs, found $ACTUAL_DEBS"

# ---------------------------------------------------------------------------
# 3. Extract .debs into the staging prefix
# ---------------------------------------------------------------------------
log "Extracting .deb packages with dpkg-deb..."
DEB_STAGE="$WORK/deb-stage"
for deb in "$DEBS_DIR"/*.deb; do
    [ -f "$deb" ] || continue
    echo "  $(basename "$deb")"
    dpkg-deb -x "$deb" "$DEB_STAGE/"
done
if [ -d "$DEB_STAGE$TERMUX_PREFIX" ]; then
    cp -a "$DEB_STAGE$TERMUX_PREFIX/." "$STAGE_PREFIX/"
elif [ -d "$DEB_STAGE/usr" ]; then
    cp -a "$DEB_STAGE/usr/." "$STAGE_PREFIX/"
else
    die "Unexpected .deb layout in $DEB_STAGE"
fi

# ---------------------------------------------------------------------------
# 4. npm installs (host node, --prefix into the staging prefix)
# ---------------------------------------------------------------------------
NPM_BIN="$(command -v npm)" || die "npm not found — Node.js is required on the CI runner"
export npm_config_update_notifier=false npm_config_fund=false npm_config_audit=false
CODEX_VERSION="0.104.0" # keep in sync with CodexServerManager.CODEX_VERSION

log "Installing OpenClaw via npm (--ignore-scripts)..."
"$NPM_BIN" install -g --prefix "$STAGE_PREFIX" --ignore-scripts --no-audit --no-fund openclaw@latest

log "Installing the Codex CLI (JS launcher) via npm..."
"$NPM_BIN" install -g --prefix "$STAGE_PREFIX" --ignore-scripts --no-audit --no-fund \
    --omit=optional "@openai/codex@${CODEX_VERSION}"

log "Installing codex-web-local (WebView UI server) via npm..."
"$NPM_BIN" install -g --prefix "$STAGE_PREFIX" --ignore-scripts --no-audit --no-fund \
    codex-web-local@0.1.0

# ---------------------------------------------------------------------------
# 5. Codex native binary (static musl, from its npm tarball)
# ---------------------------------------------------------------------------
log "Installing the Codex native binary (${CODEX_VERSION}-linux-arm64)..."
curl -fsSL --retry 3 -o "$WORK/codex-native.tgz" \
    "https://registry.npmjs.org/@openai/codex/-/codex-${CODEX_VERSION}-linux-arm64.tgz"
tar xzf "$WORK/codex-native.tgz" -C "$WORK/"
CODEX_PKG_DIR="$STAGE_PREFIX/lib/node_modules/@openai/codex-linux-arm64"
mkdir -p "$CODEX_PKG_DIR"
cp -a "$WORK/package/." "$CODEX_PKG_DIR/"
chmod 700 "$CODEX_PKG_DIR/vendor/aarch64-unknown-linux-musl/codex/codex"
chmod 700 "$CODEX_PKG_DIR/vendor/aarch64-unknown-linux-musl/path/rg" 2>/dev/null || true

# ---------------------------------------------------------------------------
# 6. Wrapper scripts (same as the app creates on-device)
# ---------------------------------------------------------------------------
P="$STAGE_PREFIX"
D="$DEVICE_PREFIX"

log "Creating wrapper scripts..."
cat > "$P/bin/codex" <<WEOF
#!$D/bin/sh
exec $D/bin/node $D/lib/node_modules/@openai/codex/bin/codex.js "\$@"
WEOF
chmod 700 "$P/bin/codex"

cat > "$P/bin/npm" <<WEOF
#!$D/bin/sh
exec $D/bin/node $D/lib/node_modules/npm/bin/npm-cli.js "\$@"
WEOF
chmod 700 "$P/bin/npm"

cat > "$P/bin/systemctl" <<WEOF
#!$D/bin/sh
exit 0
WEOF
chmod 700 "$P/bin/systemctl"

if [ -f "$P/bin/python3" ] && [ ! -e "$P/bin/python" ]; then
    ln -s python3 "$P/bin/python"
fi

# ---------------------------------------------------------------------------
# 7. Patch OpenClaw + remap paths + package the zip
# ---------------------------------------------------------------------------
log "Patching OpenClaw and packaging preinstalled-prefix.zip..."
python3 "$SCRIPT_DIR/package-prefix.py" \
    --prefix "$STAGE_PREFIX" \
    --termux "$TERMUX_PREFIX" \
    --device "$DEVICE_PREFIX" \
    --out "$ASSETS_DIR/preinstalled-prefix.zip"

echo ""
echo "Preinstalled prefix ready: $ASSETS_DIR/preinstalled-prefix.zip"
echo "The APK now contains the full runtime — nothing is downloaded on the device."
