#!/usr/bin/env python3
"""Prepare the official compile-time client; never bundle Minecraft in the mod/source ZIP."""
from pathlib import Path
import hashlib, json, shutil, sys, urllib.request

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root/'scripts/minecraft-client.json').read_text())
target = root/'.game/minecraft-26.3-client.jar'
target.parent.mkdir(exist_ok=True)

def valid(path):
    return path.is_file() and path.stat().st_size == manifest['size'] and hashlib.sha1(path.read_bytes()).hexdigest() == manifest['sha1']

if valid(target):
    print('Minecraft 26.3 client already verified')
else:
    temporary = target.with_suffix('.download')
    try:
        if len(sys.argv) == 2:
            shutil.copyfile(sys.argv[1], temporary)
        else:
            with urllib.request.urlopen(manifest['url'], timeout=60) as source, temporary.open('wb') as output:
                shutil.copyfileobj(source, output)
        if not valid(temporary):
            raise SystemExit('Minecraft client size or SHA-1 does not match official metadata')
        temporary.replace(target)
        print('Minecraft 26.3 client prepared and verified')
    finally:
        temporary.unlink(missing_ok=True)
