#!/usr/bin/env bash
#
# Auto-fix Android 10 / OxygenOS 10 (API 29) compatibility for the
# AnyClaw APK build. Idempotent — safe to run on every CI build.
#
# Android 10 / OxygenOS 10 on OnePlus 7:
#   - Enables hardware-accelerated GPU rendering for the whole app.
#   - Keeps legacy external storage so Android 10 scoped storage never
#     blocks app files.
#   - Keeps cleartext traffic for the localhost HTTP UI.
#   - Enforces targetSdk = 28: this app executes binaries from its app
#     data directory (Termux approach). Android 10+ apps targeting
#     API 29+ get W^X enforced by SELinux, which blocks that, so 28 is
#     the maximum safe target SDK.
#   - Enables Gradle parallel + cache to speed up CI builds.
#
# Usage:
#   bash android/scripts/android10-compat-fix.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MANIFEST="$ROOT/android/app/src/main/AndroidManifest.xml"
GRADLE_FILE="$ROOT/android/app/build.gradle.kts"
GRADLE_PROPS="$ROOT/android/gradle.properties"

log() { echo "==> $*"; }

# Add an android: attribute to the <application> tag if it is missing.
add_manifest_attr() {
    local attr="$1"
    if grep -q "$attr" "$MANIFEST"; then
        log "OK: $attr already present"
    else
        sed -i "0,/^[[:space:]]*<application/s//&\n        $attr/" "$MANIFEST"
        log "FIXED: added $attr"
    fi
}

# --- 1. Hardware acceleration (GPU rendering on device) ---
add_manifest_attr 'android:hardwareAccelerated="true"'

# --- 2. Android 10 scoped storage compatibility ---
add_manifest_attr 'android:requestLegacyExternalStorage="true"'

# --- 3. Localhost HTTP UI needs cleartext traffic ---
add_manifest_attr 'android:usesCleartextTraffic="true"'

# --- 3b. Larger heap for WebView + server memory headroom ---
add_manifest_attr 'android:largeHeap="true"'

# --- 4. Keep targetSdk = 28 (W^X workaround for Android 10+) ---
if grep -q 'targetSdk = 28' "$GRADLE_FILE"; then
    log "OK: targetSdk = 28 (W^X-safe for Android 10 / OxygenOS 10)"
else
    sed -i -E 's/targetSdk = [0-9]+/targetSdk = 28/' "$GRADLE_FILE"
    log "FIXED: reset targetSdk to 28"
fi

# --- 5. Gradle build acceleration ---
for line in "org.gradle.parallel=true" "org.gradle.caching=true"; do
    if grep -qF "$line" "$GRADLE_PROPS"; then
        log "OK: $line"
    else
        printf '%s\n' "$line" >> "$GRADLE_PROPS"
        log "FIXED: added $line"
    fi
done

log "Android 10 / OxygenOS 10 compatibility checks complete."
