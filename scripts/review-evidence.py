#!/usr/bin/env python3
"""Source inventory, packaged-native verification, and exact artifact comparison."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from zipfile import ZipFile

ROOT_FILES = ("pom.xml", "LICENSE", "README.md", "SHADERS.md", "REVIEW.md",
              "THIRD_PARTY_NOTICES.md", ".gitignore", ".gitattributes",
              "native/Cargo.toml", "native/Cargo.lock")
SOURCE_DIRS = ("src", "native/src", "scripts", "build-tools", "tools", "ci", ".github", "examples")
NATIVE_PATH = "natives/linux-x86_64/libtrinium_native.so"
OWN_BRIDGE = "com/mojang/renderpearl/backend/opengl/TriniumGlDrawBridge.class"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def source_files(root):
    paths = {root / name for name in ROOT_FILES}
    for directory in SOURCE_DIRS:
        paths.update(p for p in (root / directory).rglob("*")
                     if p.is_file() and "__pycache__" not in p.parts)
    result = {}
    for path in sorted(paths):
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"Missing or linked source input: {path.relative_to(root)}")
        name = path.relative_to(root).as_posix()
        if path.suffix.lower() in {".class", ".so", ".dll", ".exe", ".jar", ".dylib", ".pyc"}:
            raise ValueError(f"Compiled input is not allowed: {name}")
        result[name] = digest(path.read_bytes())
    return result


def inventory(root):
    files = source_files(root)
    encoded = json.dumps(files, sort_keys=True, separators=(",", ":")).encode()
    return {"schema": 1, "sourceSha256": digest(encoded), "files": files}


def stage(root, classes):
    info = inventory(root)
    info["native"] = {"path": NATIVE_PATH, "sha256": digest((classes / NATIVE_PATH).read_bytes()),
                      "rustc": subprocess.check_output(["rustc", "-Vv"], text=True).strip(),
                      "cc": subprocess.check_output(["cc", "--version"], text=True).splitlines()[0]}
    destination = classes / "META-INF/trinium-build.json"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(info, indent=2, sort_keys=True) + "\n")
    print("Source inventory:", info["sourceSha256"])


def verify(root, jar):
    expected = inventory(root)
    with ZipFile(jar) as z:
        names = z.namelist()
        if len(names) != len(set(names)) or z.testzip():
            raise ValueError("Duplicate or corrupt JAR entries")
        native = [n for n in names if n.endswith((".so", ".dll", ".dylib", ".exe", ".jar"))]
        if native != [NATIVE_PATH]:
            raise ValueError(f"Unexpected binary inventory: {native}")
        for name in names:
            if name.endswith(".class") and not (name.startswith("fr/pierrecreeper/trinium/") or name == OWN_BRIDGE):
                raise ValueError(f"Unexpected class: {name}")
        built = json.loads(z.read("META-INF/trinium-build.json"))
        if built["files"] != expected["files"] or built["sourceSha256"] != expected["sourceSha256"]:
            raise ValueError("Source inventory mismatch")
        so = z.read(NATIVE_PATH)
        if built["native"]["sha256"] != digest(so):
            raise ValueError("Native build inventory mismatch")
        if z.read(NATIVE_PATH + ".sha256").decode().strip() != digest(so):
            raise ValueError("Native loader checksum mismatch")
        for name in ("Cargo.toml", "Cargo.lock", *["src/" + p.name for p in sorted((root / "native/src").glob("*.rs"))]):
            if z.read("trinium-build/native/" + name) != (root / "native" / name).read_bytes():
                raise ValueError(f"Bundled Rust source mismatch: {name}")
        if z.read("META-INF/LICENSE") != (root / "LICENSE").read_bytes():
            raise ValueError("Main license mismatch")
        if json.loads(z.read("fabric.mod.json"))["license"] != "All Rights Reserved":
            raise ValueError("Fabric license mismatch")
        if not any(n.startswith("META-INF/third-party/") for n in names):
            raise ValueError("Third-party runtime notices missing")
    return {"jarSha256": digest(jar.read_bytes()), "nativeSha256": digest(so),
            "sourceSha256": expected["sourceSha256"], "verified": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["inventory", "stage", "verify", "compare"])
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--classes", type=Path)
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--other", type=Path)
    args = parser.parse_args()
    if args.mode == "stage":
        if args.classes is None:
            parser.error("--classes is required")
        stage(args.root.resolve(), args.classes.resolve())
        return
    if args.mode == "inventory":
        result = inventory(args.root.resolve())
    else:
        if args.jar is None:
            parser.error("--jar is required")
        result = verify(args.root.resolve(), args.jar)
        if args.mode == "compare":
            if args.other is None:
                parser.error("--other is required")
            other = verify(args.root.resolve(), args.other)
            if result != other or args.jar.read_bytes() != args.other.read_bytes():
                raise ValueError("Independent JAR builds differ")
            result["independentBuildsIdentical"] = True
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
