#!/usr/bin/env python3
"""Stage the patched Windows x64 language server and its provenance for buildPlugin.

Requires a built Slang checkout at the pinned commit with patches/slang/0001..0008
applied, CMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded, and the slang-glsl-module target.
No server download or compiler invocation is performed by this script.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
BASE = "5f9227cf6e5055b6a9ee742fdd729aab9162cf25"
BINARIES = ("slangd.exe", "slang-compiler.dll", "slang-glsl-module.dll")
SMOKES = ("preprocessor-trace", "preprocessor-context", "shader-variants", "branch-preview",
          "structured-buffer", "type-hover", "struct-hover", "field-hover")


def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def pe_imports(path):
    """Check PE machine and direct imports without depending on a developer's dumpbin."""
    data = path.read_bytes()
    if data[:2] != b"MZ":
        raise ValueError(f"Not a PE executable: {path}")
    pe = struct.unpack_from("<I", data, 0x3c)[0]
    machine, count = struct.unpack_from("<HH", data, pe + 4)
    optional_size = struct.unpack_from("<H", data, pe + 20)[0]
    optional = pe + 24
    if data[pe:pe + 4] != b"PE\0\0" or machine != 0x8664 or struct.unpack_from("<H", data, optional)[0] != 0x20b:
        raise ValueError(f"Windows AMD64 PE32+ required: {path}")
    sections = [struct.unpack_from("<IIII", data, optional + optional_size + 40 * i + 8) for i in range(count)]

    def offset(rva):
        for virtual_size, address, raw_size, raw in sections:
            if address <= rva < address + min(virtual_size, raw_size):
                return raw + rva - address
        raise ValueError(f"Invalid PE RVA in {path}")

    rva, size = struct.unpack_from("<II", data, optional + 120)
    imports = []
    if rva:
        start = offset(rva)
        for pos in range(start, start + size, 20):
            descriptor = struct.unpack_from("<IIIII", data, pos)
            if not any(descriptor):
                break
            name = offset(descriptor[3])
            imports.append(data[name:data.index(b"\0", name)].decode("ascii"))
    if any(re.match(r"(?i)(msvcp|msvcr|vcruntime|concrt|ucrtbase|api-ms-win-crt-)", name) for name in imports):
        raise ValueError(f"{path.name} uses a dynamic CRT; rebuild with -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded: {imports}")
    return imports


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ROOT / ".slang-m4a-source")
    parser.add_argument("--build-dir", type=Path, default=ROOT / ".slang-m4a-build")
    parser.add_argument("--configuration", default="RelWithDebInfo")
    parser.add_argument("--output", type=Path, default=ROOT / ".bundled-runtime/windows-x86_64")
    args = parser.parse_args()
    source, build, output = args.source.resolve(), args.build_dir.resolve(), args.output.resolve()
    patches = sorted((ROOT / "patches/slang").glob("*.patch"))
    revision = run("git", "-C", source, "rev-parse", "HEAD", capture_output=True, text=True).stdout.strip()
    if revision != BASE:
        raise ValueError(f"Expected source base {BASE}, got {revision}")
    # Compare the complete tracked source with the patch series, using a private index.
    with tempfile.TemporaryDirectory(prefix="slang-bundle-index-") as temp:
        env = dict(os.environ, GIT_INDEX_FILE=str(Path(temp) / "index"))
        run("git", "-C", source, "read-tree", "HEAD", env=env)
        for patch in patches:
            run("git", "-C", source, "apply", "--cached", patch, env=env)
        run("git", "-C", source, "diff", "--exit-code", env=env)

    cache = dict(re.findall(r"^([^#/:]+):[^=]+=(.*)$", (build / "CMakeCache.txt").read_text(), re.MULTILINE))
    if cache.get("CMAKE_MSVC_RUNTIME_LIBRARY") != "MultiThreaded":
        raise ValueError("The staged runtime must use CMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded")
    binary_dir = build / args.configuration / "bin"
    if not binary_dir.is_dir():
        binary_dir = build / "bin"  # single-configuration generators
    imports = {name: pe_imports(binary_dir / name) for name in BINARIES}
    output.mkdir(parents=True, exist_ok=True)
    for name in BINARIES:
        shutil.copy2(binary_dir / name, output / name)

    def copy(relative, destination):
        target = output / destination
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(relative, target)

    def external(option, directory):
        override = cache.get("SLANG_OVERRIDE_" + option + "_PATH", "OFF")
        return (source / "external" if override in ("", "OFF", "FALSE", "0") else Path(override)) / directory

    copy(source / "LICENSE", "licenses/Slang.txt")
    copy(external("UNORDERED_DENSE", "unordered_dense") / "LICENSE", "licenses/unordered_dense.txt")
    copy(external("MINIZ", "miniz") / "LICENSE", "licenses/miniz.txt")
    copy(external("LZ4", "lz4") / "lib/LICENSE", "licenses/lz4.txt")
    copy(external("SPIRV_HEADERS", "spirv-headers") / "LICENSE", "licenses/SPIRV-Headers.txt")
    vulkan = external("VULKAN_HEADERS", "vulkan")
    copy(vulkan / "LICENSE.md", "licenses/Vulkan-Headers.txt")
    for license_file in (vulkan / "LICENSES").iterdir():
        if license_file.is_file():
            copy(license_file, "licenses/" + license_file.name)
    # lua.h carries Lua's full copyright and MIT permission notice.
    copy(external("LUA", "lua") / "lua.h", "licenses/lua.h")
    for patch in patches:
        copy(patch, "patches/" + patch.name)
    (output / "NOTICE.txt").write_text(
        "Enhanced Slang language server for the CLion Slang plugin.\n"
        "Upstream: https://github.com/shader-slang/slang\n"
        f"Base commit: {BASE}\n"
        "Modified by the CLion Slang plugin contributors; all changes are in patches/.\n"
        "Changes: preprocessor trace/contexts/variants/preview, structured-buffer tokens,\n"
        "type alias hover, struct natural layout and field size/alignment/offset hover.\n"
        "Slang is Apache-2.0 WITH LLVM-exception. See licenses/ for license texts\n"
        "and third-party copyright notices. Built with static MSVC runtime.\n",
        encoding="utf-8")
    # Execute the copies, so mixed DLLs or missing runtime dependencies fail before packaging.
    for smoke in SMOKES:
        print(f"Validating bundled {smoke}...", flush=True)
        run(sys.executable, "-B", ROOT / f"scripts/slangd-{smoke}-smoke.py", "--slangd", output / "slangd.exe")
    if list(output.glob("*.bin")):
        raise ValueError("Unexpected module cache: verify that the matching slang-glsl-module.dll loads correctly")
    manifest = {
        "schemaVersion": 1, "profile": "clion-slang-enhanced", "platform": "windows-x86_64",
        "source": {"repository": "https://github.com/shader-slang/slang", "commit": revision,
                   "patches": {patch.name: sha256(patch) for patch in patches}},
        "build": {"configuration": args.configuration, "generator": cache.get("CMAKE_GENERATOR"),
                  "crt": "static", "imports": imports},
        "files": {path.relative_to(output).as_posix(): sha256(path) for path in sorted(output.rglob("*"))
                  if path.is_file() and path.name != "manifest.json"},
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Verified runtime staged at {output}")


if __name__ == "__main__":
    main()
