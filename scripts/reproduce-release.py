#!/usr/bin/env python3
"""Build twice from clean, separate source trees and compare the actual released JAR."""
import argparse
import importlib.util
import json
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("evidence", ROOT / "scripts/review-evidence.py")
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("Output directory must be empty; previous evidence is never overwritten")
    lock = json.loads((ROOT / "ci/environment.json").read_text())
    rustc = subprocess.check_output(["rustc", "-V"], text=True).strip()
    cargo = subprocess.check_output(["cargo", "-V"], text=True).strip()
    maven = subprocess.check_output(["mvn", "-v"], text=True).strip()
    if not rustc.startswith("rustc " + lock["rust"] + " "):
        raise ValueError("Rust compiler differs from ci/environment.json")
    if "Apache Maven " + lock["maven"] not in maven:
        raise ValueError("Maven differs from ci/environment.json")
    if os.uname().machine != "x86_64" or os.uname().sysname != "Linux":
        raise ValueError("This release recipe targets Linux x86-64 only")
    subprocess.run(["python3", str(ROOT / "scripts/prepare-game.py")], check=True)
    sources = evidence.source_files(ROOT)
    jars = []
    with tempfile.TemporaryDirectory(prefix="trinium-reproduction-") as scratch:
        for label in ("first", "second-different-path"):
            root = Path(scratch) / label
            for name in sources:
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / name, target)
            (root / ".game").mkdir()
            (root / ".game/minecraft-26.3-client.jar").symlink_to(ROOT / ".game/minecraft-26.3-client.jar")
            env = os.environ.copy()
            for key in ("CARGO_BUILD_TARGET", "CARGO_TARGET_DIR", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER", "RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS"):
                env.pop(key, None)
            env.update(SOURCE_DATE_EPOCH=str(lock["sourceDateEpoch"]), CARGO_INCREMENTAL="0", CARGO_NET_OFFLINE="true",
                       RUSTFLAGS=f"--remap-path-prefix={root}=/trinium -C target-cpu=x86-64 -C link-arg=-Wl,--build-id=none")
            command = ["mvn", "-B", "-Pnative,review", "clean", "verify"]
            print("Building", label, flush=True)
            with (output / (label + ".log")).open("w") as log:
                subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
            built = root / "target/Trinium-0.1.0-mc26.3.jar"
            evidence.verify(ROOT, built)
            # Exact packaged binary, not a synthetic fixture or previous release.
            with (output / (label + "-jni.log")).open("w") as log:
                subprocess.run(["java", "--enable-native-access=ALL-UNNAMED", "-Xcheck:jni", "-jar", str(built),
                                "--require-native", "--selftest"], cwd=root, env=env,
                               stdout=log, stderr=subprocess.STDOUT, check=True)
            destination = output / (label + ".jar")
            shutil.copy2(built, destination)
            jars.append(destination)
            # Keep disk usage bounded: retain artifacts/logs, remove generated test copies.
            shutil.rmtree(root)
    result = evidence.verify(ROOT, jars[0])
    if jars[0].read_bytes() != jars[1].read_bytes():
        raise ValueError("The two independent JARs differ; inspect retained artifacts")
    result.update(independentBuildsIdentical=True, rustc=rustc, cargo=cargo,
                  maven=maven, sourceCommit=os.environ.get("GITHUB_SHA"),
                  ciRun=os.environ.get("GITHUB_RUN_ID"),
                  environment="pinned-ci" if os.environ.get("GITHUB_ACTIONS") == "true" else "local")
    (output / "reproducibility.json").write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    final = output / "Trinium-0.1.0-mc26.3.jar"
    shutil.copy2(jars[0], final)
    (output / "SHA256SUMS.txt").write_text(result["jarSha256"] + "  " + final.name + "\n")
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
