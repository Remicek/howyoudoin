#!/usr/bin/env python3
"""Lists the Go modules and Rust crates compiled into hydbridge, with their licenses, for the app's
Settings -> Open-source licenses screen:

    bridge-mobile/scripts/generate-licenses.py

Writes feature/settings/presentation/src/commonMain/composeResources/files/native-licenses.json.
Go: the packages actually linked for Android (go list -deps), each attributed to the nearest LICENSE file
above it (so a subfolder with its own license, like mautrix-telegram's MIT gotd fork, is reported as such).
Rust: libsignal-ffi's normal (non-build, non-dev) dependency tree from cargo metadata.
"""
import json
import os
import subprocess
import sys

MODULE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(MODULE_DIR)
OUT = os.path.join(ROOT, "feature/settings/presentation/src/commonMain/composeResources/files/native-licenses.json")
LIBSIGNAL = os.path.join(ROOT, "..", "howyoudoin-bridges/mautrix-signal/pkg/libsignalgo/libsignal")

USED_FOR = {
    "go.mau.fi/mautrix-meta": "Messenger and Instagram",
    "go.mau.fi/whatsmeow": "WhatsApp",
    "go.mau.fi/mautrix-telegram": "Telegram",
    "go.mau.fi/mautrix-signal": "Signal",
    "com.devvalor/howyoudoin/bridgemobile": "HowYouDoin's network clients",
}

# Modules replaced (go.mod) by a different project that takes on their module path
REPLACED_BY = {
    "github.com/mattn/go-sqlite3": "github.com/thinkgos/go-sqlcipher",
}

# C libraries a Go module bundles in its own sources, listed next to it
BUNDLED = {
    "github.com/mattn/go-sqlite3": [
        {"name": "SQLCipher", "version": "4.6.1", "license": "BSD-3-Clause", "url": "https://www.zetetic.net/sqlcipher/"},
        {"name": "LibTomCrypt", "version": "1.18.2", "license": "Unlicense", "url": "https://github.com/libtom/libtomcrypt"},
    ],
}

# Modules without a license file whose sources state the license in their headers
KNOWN_LICENSES = {
    "github.com/beeper/poly1305": "BSD-3-Clause",  # fork of golang.org/x/crypto ("The Go Authors", BSD-style)
}

LICENSE_NAMES = ("LICENSE", "LICENSE.md", "LICENSE.txt", "LICENCE", "COPYING", "LICENSE-MIT", "LICENSE-APACHE")


def classify(text):
    t = " ".join(text.split()).lower()
    # The MPL names the GPL family as "secondary licenses", so it's checked first
    if "mozilla public license" in t:
        return "MPL-2.0"
    if "gnu affero general public license" in t:
        return "AGPL-3.0"
    if "gnu lesser general public license" in t:
        return "LGPL"
    if "gnu general public license" in t:
        return "GPL"
    if "apache license" in t and "version 2.0" in t:
        return "Apache-2.0"
    if "permission is hereby granted, free of charge" in t:
        return "MIT"
    if "permission to use, copy, modify, and" in t and "distribute this software for any purpose" in t:
        return "ISC"
    if "redistribution and use in source and binary forms" in t:
        return "BSD-3-Clause" if "neither the name" in t or "may not be used to endorse" in t else "BSD-2-Clause"
    if "unlicense" in t or "public domain" in t:
        return "Public domain"
    return "See license file"


def nearest_license(pkg_dir, module_dir):
    d = pkg_dir
    while True:
        for name in LICENSE_NAMES:
            path = os.path.join(d, name)
            if os.path.isfile(path):
                return path
        if os.path.normpath(d) == os.path.normpath(module_dir) or os.path.dirname(d) == d:
            return None
        d = os.path.dirname(d)


def go_libraries():
    env = dict(os.environ, GOOS="android", GOARCH="arm64", CGO_ENABLED="1", GOFLAGS="-mod=mod")
    out = subprocess.run(["go", "list", "-deps", "-json", "./hydbridge"], cwd=MODULE_DIR, env=env,
                         capture_output=True, text=True, check=True).stdout
    decoder, pos, packages = json.JSONDecoder(), 0, []
    while pos < len(out):
        while pos < len(out) and out[pos].isspace():
            pos += 1
        if pos >= len(out):
            break
        obj, pos = decoder.raw_decode(out, pos)
        packages.append(obj)
    found = {}
    for pkg in packages:
        module = pkg.get("Module")
        if not module or pkg.get("Standard"):
            continue
        actual = module.get("Replace") or module
        module_dir = actual.get("Dir")
        if not module_dir:
            continue
        license_path = nearest_license(pkg["Dir"], module_dir)
        key = (module["Path"], license_path)
        if key in found:
            continue
        rel = os.path.relpath(os.path.dirname(license_path), module_dir) if license_path else "."
        path = REPLACED_BY.get(module["Path"], module["Path"])
        name = path if rel == "." else path + "/" + rel
        version = module.get("Version") or ""
        if module.get("Replace") and module["Replace"].get("Path", "").startswith((".", "/")):
            # A local checkout (replace directive): its commit identifies the code
            commit = subprocess.run(["git", "-C", module_dir, "rev-parse", "--short", "HEAD"], capture_output=True, text=True)
            version = "commit " + commit.stdout.strip() if commit.returncode == 0 else "local checkout"
        license_name = KNOWN_LICENSES.get(module["Path"], "Unknown")
        if license_path:
            with open(license_path, encoding="utf-8", errors="replace") as f:
                license_name = classify(f.read())
        url = "https://" + path if "." in path.split("/")[0] else None
        found[key] = {"name": name, "version": version, "license": license_name, "url": url,
                      "usedFor": next((v for k, v in USED_FOR.items() if module["Path"].startswith(k)), None)}
        for lib in BUNDLED.get(module["Path"], []):
            found[(lib["name"], None)] = dict(lib, usedFor=None)
    # Go's own runtime and standard library are linked in too
    go_version = subprocess.run(["go", "env", "GOVERSION"], capture_output=True, text=True).stdout.strip()
    found[("go", None)] = {"name": "Go standard library", "version": go_version, "license": "BSD-3-Clause",
                           "url": "https://go.dev", "usedFor": None}
    return [v for k, v in found.items() if v["usedFor"] != "HowYouDoin's network clients"]


def rust_libraries():
    manifest = os.path.join(LIBSIGNAL, "Cargo.toml")
    if not os.path.isfile(manifest):
        print("libsignal checkout not found; skipping Rust crates", file=sys.stderr)
        return []
    meta = json.loads(subprocess.run(
        ["cargo", "metadata", "--format-version", "1", "--manifest-path", manifest, "--filter-platform", "aarch64-linux-android"],
        capture_output=True, text=True, check=True).stdout)
    packages = {p["id"]: p for p in meta["packages"]}
    nodes = {n["id"]: n for n in meta["resolve"]["nodes"]}
    root = next(p["id"] for p in meta["packages"] if p["name"] == "libsignal-ffi")
    seen, stack = set(), [root]
    while stack:
        pid = stack.pop()
        if pid in seen:
            continue
        seen.add(pid)
        for dep in nodes[pid]["deps"]:
            if any(k["kind"] is None for k in dep["dep_kinds"]):
                stack.append(dep["pkg"])
    result = []
    # libsignal's own workspace crates are one project: listed once, with libsignal-ffi's version
    result.append({
        "name": "libsignal", "version": packages[root]["version"], "license": packages[root].get("license") or "AGPL-3.0-only",
        "url": "https://github.com/signalapp/libsignal", "usedFor": "Signal",
    })
    for pid in seen:
        p = packages[pid]
        if p.get("source") is None:
            continue
        result.append({
            "name": p["name"],
            "version": p["version"],
            "license": p.get("license") or "See license file",
            "url": p.get("repository") or p.get("homepage"),
            "usedFor": "Signal (Rust)",
        })
    return result


def main():
    libraries = go_libraries() + rust_libraries()
    # The network clients first, then everything else by name
    libraries.sort(key=lambda l: (l["usedFor"] is None or l["usedFor"].endswith("(Rust)"), l["name"].lower(), l.get("version") or ""))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump({"libraries": libraries}, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"Wrote {len(libraries)} entries to {os.path.relpath(OUT, ROOT)}")


if __name__ == "__main__":
    main()
