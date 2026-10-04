#!/usr/bin/env python3
"""Transplant ONLY the tested Client classes into the user's pinned PhoneXR APK.
Output is unsigned. Native libraries and all other DEX archives stay byte-identical.
Requires apktool 2.12.1. Never publishes the original runtime binary.
"""
import argparse
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

SOURCE_SHA = '56dd0f53317e1e70d240d4de39217f7553011a04b1d4cf22a637de6d1ebf7fa8'
PREFIX = 'org/freedesktop/monado/ipc/'

def run(*command):
    subprocess.run(command, check=True)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('original',type=Path)
    parser.add_argument('client_dex',type=Path)
    parser.add_argument('output',type=Path)
    parser.add_argument('--apktool',type=Path,required=True)
    args=parser.parse_args()
    if hashlib.sha256(args.original.read_bytes()).hexdigest()!=SOURCE_SHA:
        raise SystemExit('Unexpected source APK; repair is pinned to the supplied PhoneXR 1.1.0/code 7')
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='phonexr-ipc-') as temporary:
        root=Path(temporary)
        source=root/'source'
        fixed=root/'fixed'
        bootstrap=root/'client.apk'
        with zipfile.ZipFile(args.original) as original, zipfile.ZipFile(bootstrap,'w') as apk:
            for name in ['AndroidManifest.xml','resources.arsc']:
                apk.writestr(name,original.read(name))
            apk.writestr('classes.dex',args.client_dex.read_bytes())
        run('java','-jar',str(args.apktool),'d','-f',str(args.original),'-o',str(source))
        run('java','-jar',str(args.apktool),'d','-f','-r',str(bootstrap),'-o',str(fixed))
        new_classes=list((fixed/'smali').rglob('*.smali'))
        if not new_classes or any(not str(p.relative_to(fixed/'smali')).startswith(PREFIX+'Client') for p in new_classes):
            raise SystemExit('Repair DEX contains unexpected classes; ABI stubs must not ship')
        destination=source/'smali_classes4'/PREFIX
        if not (destination/'Client.smali').is_file():raise SystemExit('Original Client DEX layout changed')
        for old in destination.glob('Client*.smali'):old.unlink()
        for new in new_classes:shutil.copy2(new,destination/new.name)
        config=source/'apktool.yml'
        text=config.read_text()
        text,count=re.subn(r"(?m)^(\s+versionCode:)\s+.*$",r"\1 8",text)
        if count!=1:raise SystemExit('versionCode metadata not found')
        text,count=re.subn(r"(?m)^(\s+versionName:)\s+.*$",r"\1 1.1.1-nexa-ipc",text)
        if count!=1:raise SystemExit('versionName metadata not found')
        config.write_text(text)
        rebuilt=root/'rebuilt.apk'
        run('java','-jar',str(args.apktool),'b',str(source),'-o',str(rebuilt))
        changed={'classes4.dex','AndroidManifest.xml'}
        with zipfile.ZipFile(args.original) as original,zipfile.ZipFile(rebuilt) as build,zipfile.ZipFile(args.output,'w') as output:
            for entry in original.infolist():
                if entry.filename.upper().startswith('META-INF/'):continue
                data=build.read(entry.filename) if entry.filename in changed else original.read(entry.filename)
                output.writestr(entry,data)
        with zipfile.ZipFile(args.original) as original,zipfile.ZipFile(args.output) as output:
            differences=[name for name in output.namelist() if original.read(name)!=output.read(name)]
            if set(differences)!=changed:raise SystemExit('Unexpected archive modifications: '+repr(differences))
            for name in original.namelist():
                if name.endswith('.so'):
                    assert hashlib.sha256(original.read(name)).digest()==hashlib.sha256(output.read(name)).digest()
        print('Verified: only AndroidManifest.xml and classes4.dex changed; all native libraries preserved.')

if __name__=='__main__':main()
