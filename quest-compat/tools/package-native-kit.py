#!/usr/bin/env python3
import hashlib
from pathlib import Path
import shutil
import subprocess
import zipfile
import json

out = Path('out')
out.mkdir(exist_ok=True)
driver = Path('quest-compat/android/legacy-driver/build/outputs/apk/debug/legacy-driver-debug.apk')
launcher = Path('native/app/build/outputs/apk/debug/app-debug.apk')
shutil.copy2(driver, out / 'NEXA-VrApi-Driver-v3-debug.apk')
shutil.copy2(launcher, out / 'NEXA-Quest-Bridge-v3-debug.apk')
kit = out / 'NEXA-VrApi-Native-Kit-v3.zip'
readelf = shutil.which('readelf')
if readelf is None:
    raise SystemExit('readelf is required to audit actual native exports')
with zipfile.ZipFile(driver) as apk, zipfile.ZipFile(kit, 'w', zipfile.ZIP_DEFLATED) as target:
    for abi in ['arm64-v8a', 'armeabi-v7a']:
        for name in ['libvrapi.so', 'libopenxr_loader.so']:
            data = apk.read(f'lib/{abi}/{name}')
            lib = out / f'{abi}-{name}'
            lib.write_bytes(data)
            header = subprocess.check_output([readelf, '-h', str(lib)], text=True)
            expected = 'AArch64' if abi == 'arm64-v8a' else 'ARM'
            if expected not in header:
                raise SystemExit(f'Wrong ELF machine for {abi}')
            if name == 'libvrapi.so':
                symbols = subprocess.check_output([readelf, '--dyn-syms', '--wide', str(lib)], text=True)
                exports = sorted({l.split()[-1] for l in symbols.splitlines()
                                  if len(l.split()) >= 8 and l.split()[6] != 'UND' and 'vrapi_' in l})
                for required in ['vrapi_Initialize', 'vrapi_EnterVrMode', 'vrapi_SubmitFrame2', 'vrapi_GetPredictedTracking2']:
                    if required not in exports:
                        raise SystemExit(f'Missing actual export: {required}')
                if abi == 'armeabi-v7a':
                    profile = json.loads(Path('quest-compat/profiles/gtag-pre-alpha.json').read_text())
                    missing = sorted(set(profile['requiredVrApiSymbols']) - set(exports))
                    if missing:
                        raise SystemExit('GTAG native imports unresolved: ' + ', '.join(missing))
                    print('GTAG ARM32:', len(profile['requiredVrApiSymbols']), 'imports resolved (not game execution)')
                dynamic = subprocess.check_output([readelf, '-d', str(lib)], text=True)
                if '(SONAME)' not in dynamic or '[libvrapi.so]' not in dynamic:
                    raise SystemExit('Wrong VrApi SONAME')
                target.writestr(f'lib/{abi}/exports.txt', '\n'.join(exports) + '\n')
                print(abi, len(exports), 'VrApi exports verified')
            target.writestr(f'lib/{abi}/{name}', data)
            lib.unlink()
    for source, name in [('quest-compat/tools/adapt-quest-apk.py', 'adapt-quest-apk.py'),
                         ('quest-compat/README.md', 'README.md')]:
        target.write(source, name)
for path in sorted(out.iterdir()):
    if path.suffix in ['.apk', '.zip']:
        (out / (path.name + '.sha256')).write_text(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name + '\n')
