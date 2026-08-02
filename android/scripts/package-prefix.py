#!/usr/bin/env python3
"""Patch + package a staged Termux prefix into preinstalled-prefix.zip.

The zip layout mirrors the official Termux bootstrap archives so the Android
app (BootstrapInstaller) extracts it with the same logic:
  - regular files are stored as-is
  - symlinks are NOT stored; every symlink is recorded in SYMLINKS.txt as
    "target<LEFT ARROW>linkpath" (U+2190, exactly the Termux format)

Before packaging this script:
  1. applies the same OpenClaw patches the Android app applies on-device
     (openclaw.mjs shebang, /tmp & /bin/sh & /usr/bin/env rewrites,
     gateway runner + device-auth patches)
  2. rewrites every hardcoded /data/data/com.termux/files/usr path to the
     app's real prefix (/data/user/0/com.codex.mobile.op7/files/usr) in all
     text files (bin scripts, node_modules, dpkg metadata, apt config)
  3. resolves symlink chains so SYMLINKS.txt entries are order-independent
"""

import argparse
import os
import shutil
import stat
import sys
import zipfile


def walk_files(root):
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in filenames:
            yield os.path.join(dirpath, name)


def is_binary(path):
    try:
        with open(path, "rb") as f:
            return b"\x00" in f.read(8192)
    except OSError:
        return True


def rewrite_text_files(prefix, old, new):
    """Replace `old` with `new` in every text file under prefix (lossless)."""
    n = 0
    old_bytes = old.encode("latin-1")
    for p in walk_files(prefix):
        if os.path.islink(p) or is_binary(p):
            continue
        try:
            with open(p, "rb") as f:
                data = f.read()
        except OSError:
            continue
        if old_bytes not in data:
            continue
        # latin-1 round-trips every byte, so nothing is corrupted even for
        # files with non-UTF-8 content; the path strings are pure ASCII.
        text = data.decode("latin-1")
        text = text.replace(old, new)
        try:
            with open(p, "wb") as f:
                f.write(text.encode("latin-1"))
        except OSError:
            continue
        n += 1
    return n


def patch_openclaw(prefix, termux):
    """Replicate the app's patchOpenClawPaths + patchGatewayForAndroid."""
    oc = os.path.join(prefix, "lib/node_modules/openclaw")
    if not os.path.isdir(oc):
        return

    # 1. openclaw.mjs shebang
    mjs = os.path.join(oc, "openclaw.mjs")
    if os.path.isfile(mjs) and not os.path.islink(mjs):
        try:
            with open(mjs, "r", encoding="utf-8", errors="ignore") as f:
                s = f.read()
            if s.startswith("#!/usr/bin/env node"):
                with open(mjs, "w", encoding="utf-8") as f:
                    f.write(s.replace("#!/usr/bin/env node", "#!" + termux + "/bin/node", 1))
        except OSError:
            pass

    # 2. path rewrites inside JS files (same patterns as the app)
    replacements = [
        (b'"/tmp/"', ('"' + termux + '/tmp/"').encode()),
        (b"'/tmp/'", ("'" + termux + "/tmp/'").encode()),
        (b'"/tmp"', ('"' + termux + '/tmp"').encode()),
        (b"'/tmp'", ("'" + termux + "/tmp'").encode()),
        (b'"/bin/sh"', ('"' + termux + '/bin/sh"').encode()),
        (b"'/bin/sh'", ("'" + termux + "/bin/sh'").encode()),
        (b'"/bin/bash"', ('"' + termux + '/bin/bash"').encode()),
        (b"'/bin/bash'", ("'" + termux + "/bin/bash'").encode()),
        (b'"/usr/bin/env"', ('"' + termux + '/bin/env"').encode()),
        (b"'/usr/bin/env'", ("'" + termux + "/bin/env'").encode()),
    ]
    for p in walk_files(oc):
        if not p.endswith((".js", ".mjs", ".cjs")) or os.path.islink(p) or is_binary(p):
            continue
        try:
            with open(p, "rb") as f:
                data = f.read()
        except OSError:
            continue
        orig = data
        for old, new in replacements:
            data = data.replace(old, new)
        if data != orig:
            try:
                with open(p, "wb") as f:
                    f.write(data)
            except OSError:
                pass

    # 3. gateway patches (best effort — only when the exact strings exist)
    runner_pat = b'console.error("[openclaw] Unhandled promise rejection:", formatUncaughtError(reason));'
    runner_new = (
        b'if (reason && reason.message && reason.message.includes("interface")) { '
        b'console.warn("[openclaw] Non-fatal network interface error (continuing):", '
        b'formatUncaughtError(reason)); return; } '
        b'console.error("[openclaw] Unhandled promise rejection:", formatUncaughtError(reason));'
    )
    gw_pat = b"function evaluateMissingDeviceIdentity(params) {"
    gw_new = (
        b'function evaluateMissingDeviceIdentity(params) { if (params.controlUiAuthPolicy.allowBypass) '
        b'return { kind: "allow" };'
    )
    dist = os.path.join(oc, "dist")
    if os.path.isdir(dist):
        for p in walk_files(dist):
            if os.path.islink(p) or is_binary(p):
                continue
            try:
                with open(p, "rb") as f:
                    data = f.read()
            except OSError:
                continue
            orig = data
            base = os.path.basename(p)
            if base.startswith("runner-") and runner_pat in data:
                data = data.replace(runner_pat, runner_new, 1)
            if base.startswith("gateway-cli-") and gw_pat in data:
                data = data.replace(gw_pat, gw_new, 1)
            if data != orig:
                try:
                    with open(p, "wb") as f:
                        f.write(data)
                except OSError:
                    pass


def final_target(prefix, link_path, termux, device, hops=24):
    """Return an order-independent symlink target for SYMLINKS.txt."""
    t = os.readlink(link_path)
    cur = link_path
    for _ in range(hops):
        if t.startswith("/"):
            if t.startswith(termux):
                return device + t[len(termux):]
            return t
        nxt = os.path.normpath(os.path.join(os.path.dirname(cur), t))
        if os.path.islink(nxt):
            t = os.readlink(nxt)
            cur = nxt
            continue
        # Terminal is a real file/dir: emit a target relative to the
        # original link's parent directory so creation order never matters.
        return os.path.relpath(nxt, os.path.dirname(link_path))
    return t


def collect_symlinks(prefix, termux, device):
    lines = []
    for dirpath, _dirnames, filenames in os.walk(prefix, followlinks=False):
        for name in filenames:
            p = os.path.join(dirpath, name)
            if not os.path.islink(p):
                continue
            target = final_target(prefix, p, termux, device)
            rel = os.path.relpath(p, prefix)
            lines.append(target + "\u2190./" + rel)
    return lines


def package(prefix, out_zip, symlink_lines):
    n_files = 0
    n_symlinks = 0
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        info = zipfile.ZipInfo("SYMLINKS.txt")
        info.external_attr = 0o644 << 16
        z.writestr(info, "\n".join(symlink_lines) + "\n")

        for dirpath, _dirnames, filenames in os.walk(prefix, followlinks=False):
            for name in filenames:
                p = os.path.join(dirpath, name)
                if os.path.islink(p):
                    n_symlinks += 1
                    continue
                try:
                    st = os.stat(p)
                except OSError:
                    continue
                rel = os.path.relpath(p, prefix)
                info = zipfile.ZipInfo(rel)
                info.date_time = (1980, 1, 1, 0, 0, 0)
                info.external_attr = (stat.S_IMODE(st.st_mode)) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                with z.open(info, "w") as dst, open(p, "rb") as src:
                    shutil.copyfileobj(src, dst, 1024 * 1024)
                n_files += 1
    return n_files, n_symlinks


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--prefix", required=True, help="staged Termux prefix directory")
    ap.add_argument("--termux", required=True, help="Termux prefix path baked into files")
    ap.add_argument("--device", required=True, help="final device prefix path")
    ap.add_argument("--out", required=True, help="output zip path")
    args = ap.parse_args()

    prefix = os.path.abspath(args.prefix)
    if not os.path.isdir(prefix):
        sys.exit("ERROR: staged prefix not found: " + prefix)

    print("==> Patching OpenClaw paths/gateway...")
    patch_openclaw(prefix, args.termux)

    print("==> Rewriting Termux paths to the device prefix...")
    n = rewrite_text_files(prefix, args.termux, args.device)
    print(f"    rewritten {n} files")

    print("==> Collecting symlinks...")
    symlinks = collect_symlinks(prefix, args.termux, args.device)
    print(f"    {len(symlinks)} symlinks")

    print("==> Writing " + args.out + " ...")
    n_files, n_symlinks = package(prefix, args.out, symlinks)
    size = os.path.getsize(args.out)
    print(f"    {n_files} files, {n_symlinks} symlinks (via SYMLINKS.txt)")
    print(f"    zip size: {size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
