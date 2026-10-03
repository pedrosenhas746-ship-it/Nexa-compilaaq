#!/usr/bin/env python3
"""Adapt a standalone VrApi APK to the experimental NEXA/OpenXR library.

Requires apktool, readelf, zipalign, apksigner and an existing signing keystore.
Does not modify entitlement, platform services, dex or game assets.
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
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ANDROID = 'http://schemas.android.com/apk/res/android'
A = '{' + ANDROID + '}'
RUNTIME = 'org.freedesktop.monado.openxr_runtime.out_of_process'
MACHINES = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40)}
ET.register_namespace('android', ANDROID)


def validate_elf(data, abi):
    if abi not in MACHINES or len(data) < 20 or data[:4] != b'\x7fELF' or data[5:7] != b'\x01\x01':
        raise ValueError('Unsupported ELF ABI/header: ' + abi)
    elf_class, machine = MACHINES[abi]
    if data[4] != elf_class or struct.unpack_from('<H', data, 18)[0] != machine:
        raise ValueError('ELF machine does not match ' + abi)
    if struct.unpack_from('<H', data, 16)[0] != 3:
        raise ValueError('Expected a shared library (ET_DYN)')


def unique_entries(archive):
    names = archive.namelist()
    if len(names) != len(set(names)):
        raise ValueError('Duplicate ZIP entries')
    return names


def plan(apk_path, kit_path):
    """Verify every replaced library and its architecture before any mutation."""
    with zipfile.ZipFile(apk_path) as apk, zipfile.ZipFile(kit_path) as kit:
        names = unique_entries(apk)
        unique_entries(kit)
        if 'AndroidManifest.xml' not in names:
            raise ValueError('No Android manifest in APK')
        paths = sorted(n for n in names if re.fullmatch(r'lib/[^/]+/libvrapi\.so', n))
        if not paths:
            raise ValueError('No standalone libvrapi.so found; OpenXR or embedded VrApi needs another route')
        abis = []
        replacements = {}
        for path in paths:
            abi = path.split('/')[1]
            validate_elf(apk.read(path), abi)
            abis.append(abi)
            for name in ['libvrapi.so', 'libopenxr_loader.so']:
                entry = f'lib/{abi}/{name}'
                try:
                    data = kit.read(entry)
                except KeyError as e:
                    raise ValueError('Kit lacks ' + entry) from e
                validate_elf(data, abi)
                replacements[entry] = data
        return abis, replacements


def update_manifest(root):
    if root.get('split') or root.find('uses-split') is not None:
        raise ValueError('Standalone APKs only; split packages must be adapted and signed as a complete set')
    app = root.find('application')
    if app is None or app.get(A + 'isSplitRequired') == 'true':
        raise ValueError('Missing application or required splits')
    app.set(A + 'extractNativeLibs', 'true')
    for permission in ['org.khronos.openxr.permission.OPENXR', 'org.khronos.openxr.permission.OPENXR_SYSTEM']:
        if not any(n.get(A + 'name') == permission for n in root.findall('uses-permission')):
            ET.SubElement(root, 'uses-permission', {A + 'name': permission})
    queries = root.find('queries')
    if queries is None:
        queries = ET.SubElement(root, 'queries')
    for package in [RUNTIME, 'com.oculus.systemdriver', 'org.khronos.openxr.runtime_broker']:
        if not any(n.get(A + 'name') == package for n in queries.findall('package')):
            ET.SubElement(queries, 'package', {A + 'name': package})
    authorities = 'org.khronos.openxr.runtime_broker;org.khronos.openxr.system_runtime_broker'
    if not any(n.get(A + 'authorities') == authorities for n in queries.findall('provider')):
        ET.SubElement(queries, 'provider', {A + 'authorities': authorities})
    # Broker discovery is used by the Khronos Android loader.
    if not any(i.find("action[@" + A + "name='org.khronos.openxr.OpenXRRuntimeService']") is not None
               for i in queries.findall('intent')):
        intent = ET.SubElement(queries, 'intent')
        ET.SubElement(intent, 'action', {A + 'name': 'org.khronos.openxr.OpenXRRuntimeService'})
    for feature in root.findall('uses-feature'):
        if feature.get(A + 'name', '').startswith('android.hardware.vr.'):
            feature.set(A + 'required', 'false')
    for activity in list(app.findall('activity')) + list(app.findall('activity-alias')):
        for intent in activity.findall('intent-filter'):
            main = any(a.get(A + 'name') == 'android.intent.action.MAIN' for a in intent.findall('action'))
            vr = any(c.get(A + 'name') == 'com.oculus.intent.category.VR' for c in intent.findall('category'))
            launcher = any(c.get(A + 'name') == 'android.intent.category.LAUNCHER' for c in intent.findall('category'))
            if main and vr and not launcher:
                ET.SubElement(intent, 'category', {A + 'name': 'android.intent.category.LAUNCHER'})


def elf_symbols(readelf, path, undefined):
    output = subprocess.check_output([readelf, '--dyn-syms', '--wide', str(path)], text=True)
    result = set()
    for line in output.splitlines():
        fields = line.split()
        if len(fields) < 8 or not fields[0].rstrip(':').isdigit():
            continue
        if (fields[6] == 'UND') != undefined or fields[4] not in ['GLOBAL', 'WEAK']:
            continue
        symbol = fields[7].split('@')[0]
        if symbol.startswith('vrapi_'):
            result.add(symbol)
    return result


def verify_imports(apk_path, replacements, readelf, directory):
    report = {}
    with zipfile.ZipFile(apk_path) as apk:
        for abi in sorted({n.split('/')[1] for n in replacements}):
            candidate = directory / 'candidate.so'
            candidate.write_bytes(replacements[f'lib/{abi}/libvrapi.so'])
            exports = elf_symbols(readelf, candidate, False)
            imported = set()
            for info in apk.infolist():
                if not re.fullmatch(r'lib/' + re.escape(abi) + r'/[^/]+\.so', info.filename):
                    continue
                if info.filename.endswith('/libvrapi.so'):
                    continue
                if info.file_size > 512 * 1024 * 1024:
                    raise ValueError('Library exceeds import audit limit: ' + info.filename)
                with apk.open(info) as source, candidate.open('wb') as target:
                    shutil.copyfileobj(source, target, 1024 * 1024)
                imported |= elf_symbols(readelf, candidate, True)
            missing = sorted(imported - exports)
            if missing:
                raise ValueError(f'{abi}: unimplemented imports: ' + ', '.join(missing))
            report[abi] = {'requiredStaticImports': sorted(imported), 'exportCount': len(exports),
                           'dynamicLookupAudited': False}
    return report


def adapt(args):
    if args.output.exists() or args.output.resolve() == args.apk.resolve():
        raise ValueError('Choose a new output path')
    if not args.keystore.is_file():
        raise ValueError('Signing keystore not found')
    # Signing passwords stay in the environment, never command arguments or logs.
    if 'NEXA_KEYSTORE_PASSWORD' not in os.environ:
        raise ValueError('Set NEXA_KEYSTORE_PASSWORD before signing')
    commands = {}
    for name in ['apktool', 'readelf', 'zipalign', 'apksigner']:
        commands[name] = shutil.which(name)
        if commands[name] is None:
            raise ValueError('Required tool missing: ' + name)
    abis, replacements = plan(args.apk, args.kit)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='nexa-adapt-') as temp:
        work = Path(temp)
        imports = verify_imports(args.apk, replacements, commands['readelf'], work)
        decoded = work / 'decoded'
        subprocess.run([commands['apktool'], 'd', '-s', '-f', '-o', str(decoded), str(args.apk)], check=True)
        manifest = decoded / 'AndroidManifest.xml'
        tree = ET.parse(manifest)
        update_manifest(tree.getroot())
        tree.write(manifest, encoding='utf-8', xml_declaration=True)
        for entry, data in replacements.items():
            target = decoded / entry
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        unsigned, aligned, signed = [work / n for n in ['unsigned.apk', 'aligned.apk', 'signed.apk']]
        subprocess.run([commands['apktool'], 'b', str(decoded), '-o', str(unsigned)], check=True)
        subprocess.run([commands['zipalign'], '-P', '16', '-f', '4', str(unsigned), str(aligned)], check=True)
        subprocess.run([commands['apksigner'], 'sign', '--ks', str(args.keystore), '--ks-key-alias', args.alias,
                        '--ks-pass', 'env:NEXA_KEYSTORE_PASSWORD', '--out', str(signed), str(aligned)], check=True)
        subprocess.run([commands['apksigner'], 'verify', '--verbose', str(signed)], check=True)
        # Verify the actual signed artifact still includes exactly our libraries.
        with zipfile.ZipFile(signed) as output:
            for entry, data in replacements.items():
                if output.read(entry) != data:
                    raise ValueError('Final APK library differs from kit: ' + entry)
        shutil.copy2(signed, args.output)
    report = {'input': args.apk.name, 'output': args.output.name, 'abis': abis,
              'runtimeTested': False, 'canRun': None, 'imports': imports,
              'sha256': hashlib.sha256(args.output.read_bytes()).hexdigest(),
              'limitations': ['OpenGL ES only', 'Requires PhoneXR runtime and URI launch route',
                              'Dynamic dlsym calls and ABI structure versions need game testing',
                              'Meta services and hand skeleton API are not implemented',
                              'Re-signing changes app identity; original saved data may not be transferable']}
    Path(str(args.output) + '.report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--kit', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--alias', default='androiddebugkey')
    try:
        adapt(parser.parse_args())
    except (ValueError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, str(error) + '\n')
