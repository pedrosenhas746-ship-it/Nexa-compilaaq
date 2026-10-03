#!/usr/bin/env python3
"""Exercise rebuild/signing/import validation using a synthetic Android APK.

This fixture is not a Quest game and is never executed.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parents[2]
kit_path = root / 'out/NEXA-VrApi-Native-Kit-v3.zip'
launcher = root / 'native/app/build/outputs/apk/debug/app-debug.apk'
ndk = Path(os.environ['ANDROID_HOME']) / 'ndk/26.3.11579264/toolchains/llvm/prebuilt/linux-x86_64'
with tempfile.TemporaryDirectory(prefix='nexa-fixture-') as temp:
    work = Path(temp)
    native = work / 'lib'
    native.mkdir()
    with zipfile.ZipFile(kit_path) as kit:
        for name in ['libvrapi.so', 'libopenxr_loader.so']:
            (native / name).write_bytes(kit.read('lib/arm64-v8a/' + name))
    source = work / 'client.c'
    source.write_text('extern int vrapi_Initialize(const void *);\nint probe(const void *p) { return vrapi_Initialize(p); }\n')
    compiler = ndk / 'bin/aarch64-linux-android26-clang'
    client = native / 'libnexa_fixture.so'
    subprocess.run([str(compiler), '-shared', '-fPIC', str(source), '-L' + str(native), '-lvrapi',
                    '-Wl,--no-undefined', '-o', str(client)], check=True)
    # Use the original SDK binary only as the fixture's input, never as our implementation.
    with zipfile.ZipFile('/tmp/vrapi-sdk.zip') as sdk:
        paths = [n for n in sdk.namelist() if n.endswith('/libvrapi.so') and '/arm64-v8a/' in n]
        if not paths:
            raise SystemExit('No original VrApi binary for smoke fixture')
        original = sdk.read(sorted(paths)[0])
    fixture = work / 'fixture.apk'
    with zipfile.ZipFile(launcher) as apk, zipfile.ZipFile(fixture, 'w') as target:
        for info in apk.infolist():
            target.writestr(info, apk.read(info))
        target.writestr('lib/arm64-v8a/libvrapi.so', original)
        target.writestr('lib/arm64-v8a/libnexa_fixture.so', client.read_bytes())
    keystore = work / 'test.jks'
    subprocess.run(['keytool', '-genkeypair', '-keystore', str(keystore), '-storepass', 'android',
                    '-keypass', 'android', '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048',
                    '-validity', '30', '-dname', 'CN=NEXA Fixture'], check=True)
    env = dict(os.environ, NEXA_KEYSTORE_PASSWORD='android')
    subprocess.run(['python3', str(root / 'quest-compat/tools/adapt-quest-apk.py'), str(fixture),
                    '--kit', str(kit_path), '--output', str(work / 'adapted.apk'),
                    '--keystore', str(keystore)], check=True, env=env)
print('Synthetic APK rebuilt, signed and verified. No game or device execution.')
