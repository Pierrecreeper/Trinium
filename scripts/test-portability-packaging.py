# Native packaging and loader regression tests after mvn clean verify; requires Python 3.
from pathlib import Path
import tempfile, struct, hashlib, subprocess, json, shutil, os
p=Path(__file__).resolve().parent.parent
version=json.loads((p/'src/main/resources/fabric.mod.json').read_text())['version']
cp=os.pathsep.join([str(p/'target/classes'),str(p/'target/build-tool-check')])
platforms=[('linux-x86_64','x86_64-unknown-linux-gnu','libtrinium_native.so'),('linux-aarch64','aarch64-unknown-linux-gnu','libtrinium_native.so'),('windows-x86_64','x86_64-pc-windows-msvc','trinium_native.dll'),('windows-aarch64','aarch64-pc-windows-msvc','trinium_native.dll'),('macos-x86_64','x86_64-apple-darwin','libtrinium_native.dylib'),('macos-aarch64','aarch64-apple-darwin','libtrinium_native.dylib')]
(p/'verification').mkdir(exist_ok=True)
(p/'target/build-tool-check').mkdir(exist_ok=True)
subprocess.run(['javac','-cp',str(p/'target/classes'),'-d',str(p/'target/build-tool-check'),str(p/'build-tools/PackageNatives.java')],check=True)
log=[]
def invoke(args,expect,label):
    r=subprocess.run(['java','-cp',cp,'PackageNatives',*map(str,args)],capture_output=True,text=True)
    assert (r.returncode==0)==expect,(label,r.stdout,r.stderr)
    log.append(f'PASS {label} (exit={r.returncode})\n{r.stdout}{r.stderr}')
with tempfile.TemporaryDirectory(prefix='trinium-packaging-') as tmp:
    root=Path(tmp);out=root/'classes';out.mkdir()
    shutil.copy(p/'src/main/resources/fabric.mod.json',out/'fabric.mod.json')
    invoke(['universal',root,out],False,'missing bundles rejected')
    saved={}
    for name,target,file in platforms:
        # These are artificial binary headers, NOT native libraries. Never loaded or delivered.
        b=bytearray(256);arm=name.endswith('aarch64')
        if name.startswith('linux'):
            struct.pack_into('<I',b,0,0x464c457f);b[4]=2;b[5]=1;struct.pack_into('<HH',b,16,3,183 if arm else 62)
        elif name.startswith('windows'):
            struct.pack_into('<H',b,0,0x5a4d);struct.pack_into('<I',b,0x3c,128);struct.pack_into('<I',b,128,0x4550);struct.pack_into('<H',b,132,0xaa64 if arm else 0x8664);struct.pack_into('<H',b,150,0x2000)
        else:
            struct.pack_into('<II',b,0,0xfeedfacf,0x0100000c if arm else 0x01000007);struct.pack_into('<I',b,12,6)
        d=root/'native-bundles/natives'/name;d.mkdir(parents=True)
        (d/file).write_bytes(b);(d/(file+'.sha256')).write_text(hashlib.sha256(b).hexdigest()+'\n')
        (d/'build.properties').write_text(f'version={version}\nabi=3\ntarget={target}\n')
        saved[name]=(d,file,bytes(b))
    invoke(['universal',root,out],True,'six synthetic HEADER fixtures accepted for packaging only')
    data=json.loads((out/'fabric.mod.json').read_text());assert len(data['custom']['trinium:rust_targets'])==6 and data['custom']['trinium:rust_bundled']
    log.append('PASS six-target manifest metadata\n')
    d,file,b=saved['linux-x86_64'];(d/(file+'.sha256')).write_text('0'*64)
    invoke(['universal',root,out],False,'bad digest rejected before copy')
    (d/(file+'.sha256')).write_text(hashlib.sha256(b).hexdigest())
    (d/'build.properties').write_text('version=old\nabi=3\ntarget=x86_64-unknown-linux-gnu\n')
    invoke(['universal',root,out],False,'mixed versions rejected')
    (d/'build.properties').write_text(f'version={version}\nabi=3\ntarget=x86_64-unknown-linux-gnu\n')
    d2,f2,b2=saved['linux-aarch64'];(d/file).write_bytes(b2);(d/(file+'.sha256')).write_text(hashlib.sha256(b2).hexdigest())
    invoke(['universal',root,out],False,'wrong machine binary rejected even with matching digest')
    invoke(['host',root,out],False,'host build rejects missing Cargo output')
log.append('All synthetic fixtures were confined to an automatically deleted temporary directory. No synthetic native file enters the delivered JAR.\n')
(p/'verification/packaging-tests.txt').write_text('\n'.join(log))
# Exercise the actual loader's negative paths in fresh JVMs.
jar=p/f'target/Trinium-{version}-mc26.3.jar'
results=[]
for args,success,label in [
 (['--require-native','--selftest'],True,'packaged native loads and self-tests'),
 (['--verify-universal'],False,'host-only JAR cannot pass universal-native audit'),
 (['--selftest'],True,'Java fallback selftest'),
]:
 r=subprocess.run(['java','-jar',str(jar),*args],capture_output=True,text=True)
 assert (r.returncode==0)==success,(label,r.stdout,r.stderr)
 results.append(f'PASS {label} (exit={r.returncode})\n{r.stdout}{r.stderr}')
r=subprocess.run(['java','-Dtrinium.disableNative=true','-jar',str(jar),'--selftest'],capture_output=True,text=True)
assert r.returncode==0
results.append(f'PASS explicit Java fallback (exit={r.returncode})\n{r.stdout}{r.stderr}')
with tempfile.TemporaryDirectory(prefix='trinium-loader-') as tmp:
 # Deliberately corrupt resource: digest check rejects it BEFORE any System.load.
 d=Path(tmp)/'natives/linux-x86_64';d.mkdir(parents=True)
 (d/'libtrinium_native.so').write_bytes(b'bad')
 (d/'libtrinium_native.so.sha256').write_text('0'*64)
 r=subprocess.run(['java','-cp',os.pathsep.join([tmp,str(p/'target/classes')]),'fr.pierrecreeper.trinium.TriniumCli','--selftest'],capture_output=True,text=True)
 assert r.returncode==0 and 'Native SHA-256 digest mismatch' in r.stdout,r.stdout+r.stderr
 results.append(f'PASS corrupt native falls back before load (exit={r.returncode})\n{r.stdout}{r.stderr}')
(p/'verification/loader-tests.txt').write_text('\n'.join(results))
print('PASS packaging gates (synthetic headers only) and five loader/CLI scenarios. Logs written.')
