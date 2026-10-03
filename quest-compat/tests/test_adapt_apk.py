import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

script = Path(__file__).resolve().parents[1] / 'tools/adapt-quest-apk.py'
spec = importlib.util.spec_from_file_location('adapt', script)
adapt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(adapt)


def elf(abi):
    data = bytearray(64)
    data[:7] = b'\x7fELF' + bytes([adapt.MACHINES[abi][0], 1, 1])
    struct.pack_into('<HH', data, 16, 3, adapt.MACHINES[abi][1])
    return bytes(data)


class AdaptTests(unittest.TestCase):
    def test_supported_abis(self):
        for abi in adapt.MACHINES:
            adapt.validate_elf(elf(abi), abi)

    def test_wrong_abi_is_rejected(self):
        with self.assertRaises(ValueError):
            adapt.validate_elf(elf('arm64-v8a'), 'armeabi-v7a')

    def test_executable_is_rejected(self):
        data = bytearray(elf('arm64-v8a'))
        struct.pack_into('<H', data, 16, 2)
        with self.assertRaises(ValueError):
            adapt.validate_elf(data, 'arm64-v8a')

    def test_both_architectures_require_a_complete_kit(self):
        with tempfile.TemporaryDirectory() as temp:
            apk, kit = Path(temp) / 'game.apk', Path(temp) / 'kit.zip'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('AndroidManifest.xml', '<manifest/>')
                for abi in adapt.MACHINES:
                    archive.writestr(f'lib/{abi}/libvrapi.so', elf(abi))
            with zipfile.ZipFile(kit, 'w') as archive:
                for name in ['libvrapi.so', 'libopenxr_loader.so']:
                    archive.writestr('lib/arm64-v8a/' + name, elf('arm64-v8a'))
            with self.assertRaises(ValueError):
                adapt.plan(apk, kit)

    def test_openxr_only_does_not_receive_vrapi(self):
        with tempfile.TemporaryDirectory() as temp:
            apk, kit = Path(temp) / 'game.apk', Path(temp) / 'kit.zip'
            with zipfile.ZipFile(apk, 'w') as archive:
                archive.writestr('AndroidManifest.xml', '<manifest/>')
                archive.writestr('lib/arm64-v8a/libopenxr_loader.so', elf('arm64-v8a'))
            with zipfile.ZipFile(kit, 'w'):
                pass
            with self.assertRaises(ValueError):
                adapt.plan(apk, kit)

    def test_manifest_is_idempotent_and_keeps_permissions(self):
        root = ET.fromstring(f'''<manifest xmlns:android="{adapt.ANDROID}" package="game.test">
        <uses-permission android:name="android.permission.INTERNET"/>
        <uses-feature android:name="android.hardware.vr.headtracking" android:required="true"/>
        <application><activity android:name="GameActivity"><intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="com.oculus.intent.category.VR"/>
        </intent-filter></activity></application></manifest>''')
        adapt.update_manifest(root)
        once = ET.tostring(root)
        adapt.update_manifest(root)
        self.assertEqual(once, ET.tostring(root))
        self.assertEqual(root.find('uses-permission').get(adapt.A + 'name'), 'android.permission.INTERNET')
        self.assertEqual(root.find('uses-feature').get(adapt.A + 'required'), 'false')
        self.assertEqual(root.find('application').get(adapt.A + 'extractNativeLibs'), 'true')
        categories = root.findall('./application/activity/intent-filter/category')
        self.assertTrue(any(c.get(adapt.A + 'name') == 'android.intent.category.LAUNCHER' for c in categories))

    def test_split_package_is_rejected(self):
        for manifest in ['<manifest split="config.arm64_v8a"><application/></manifest>',
                         '<manifest><uses-split name="base"/><application/></manifest>']:
            with self.assertRaises(ValueError):
                adapt.update_manifest(ET.fromstring(manifest))


if __name__ == '__main__':
    unittest.main()
