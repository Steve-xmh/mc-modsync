#!/usr/bin/env python3
"""Builds a ModSync manifest.json plus a content-addressed files/ directory from a folder of jars.

Only used by the test suite (and handy for a static/CDN deployment of a manifest).
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import zipfile


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read_mod_id(path: str):
    try:
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            for name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
                if name in names:
                    text = z.read(name).decode("utf-8", "replace")
                    for line in text.splitlines():
                        line = line.split("#", 1)[0].strip()
                        if line.startswith("modId"):
                            m = re.search(r"=\s*['\"]([^'\"]+)['\"]", line)
                            if m:
                                return m.group(1)
            if "fabric.mod.json" in names:
                data = json.loads(z.read("fabric.mod.json").decode("utf-8", "replace"))
                if isinstance(data, dict) and data.get("id"):
                    return data["id"]
    except Exception:
        return None
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mods", required=True, help="directory containing the jars to publish")
    ap.add_argument("--out", required=True, help="output directory (manifest.json + files/)")
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--server-id", default="testserver00000001")
    ap.add_argument("--pack-name", default="ModSync Test Pack")
    ap.add_argument("--exclude", action="append", default=[])
    ap.add_argument("--direct-url-for", default=None,
                    help="file name that should carry a 'url' field pointing at BASE_URL/files/<sha256>")
    ap.add_argument("--minecraft-version", default=None,
                    help="optional 'minecraft' field announced to clients")
    ap.add_argument("--loader-version", default=None,
                    help="optional loader version announced to clients")
    ap.add_argument("--loader-type", default="neoforge")
    ap.add_argument("--generated-at", default="2026-10-08T00:00:00.000Z",
                    help="manifest 'generatedAt' (ISO-8601)；用于测试时间戳顺序护栏")
    args = ap.parse_args()

    files_dir = os.path.join(args.out, "files")
    os.makedirs(files_dir, exist_ok=True)

    entries = []
    for name in sorted(os.listdir(args.mods)):
        if not name.endswith(".jar") or name.startswith("."):
            continue
        if name in args.exclude:
            continue
        src = os.path.join(args.mods, name)
        if not os.path.isfile(src):
            continue
        digest = sha256(src)
        shutil.copyfile(src, os.path.join(files_dir, digest))
        entry = {
            "path": "mods/" + name,
            "sha256": digest,
            "size": os.path.getsize(src),
        }
        mod_id = read_mod_id(src)
        if mod_id:
            entry["modId"] = mod_id
        if args.direct_url_for and name == args.direct_url_for:
            entry["url"] = args.base_url.rstrip("/") + "/files/" + digest
        entries.append(entry)

    manifest = {
        "schema": 1,
        "packName": args.pack_name,
        "serverId": args.server_id,
        "generatedAt": args.generated_at,
        "baseUrl": args.base_url.rstrip("/"),
        "files": entries,
    }
    if args.minecraft_version:
        manifest["minecraft"] = args.minecraft_version
    if args.loader_version:
        manifest["loader"] = {"type": args.loader_type, "version": args.loader_version}
    with open(os.path.join(args.out, "manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")
    print(f"[gen-manifest] {len(entries)} files -> {os.path.join(args.out, 'manifest.json')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
