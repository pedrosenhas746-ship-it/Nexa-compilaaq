# NEXA ARM32 Lab 0.1

Experimental **CPU/ELF probe**, not an Android virtual machine or a Quest game
launcher. Installing this app does not enable ARM32 packages in the system and
does not change the NEXA 4.1 importer. No Quest gameplay is confirmed.

The separate GPLv2 companion app uses Unicorn 2.1.4, pinned to commit
`8028ec436f2d9376525352dd38ed9ed6b9f6be10`, to execute A32 and Thumb instructions
inside a 64-bit process. NEXA's existing APK does not link the GPL component.

## What the prototype does

* Select a standalone APK through Android's document picker.
* Identify ARM32 libraries and execute ARM, Thumb and NEON CPU self-tests.
* Map selected ELF32 ARM shared objects into **guest memory**, process their
  SysV dynamic symbol tables and REL relocations, and run bounded entrypoints.
* Query `opus_get_version_string` when the APK contains `libopus_egpv.so`.
* Attempt `JNI_OnLoad` from `libmain.so` using a guest JavaVM table. Unimplemented
  calls stop at named traps, rather than returning fabricated success.
* Copy a report containing the host model/ABI and the exact stopping point.

The game APK remains local. The app requests no network, root, installer or
package-enumeration permission. It copies at most 1 GiB per APK and extracts
only selected libraries, each at most 128 MiB. Individual calls stop after
100,000 instructions or one second. Guest syscalls and external imports are
not forwarded to the host. Constructors are not run. This is an emulator
experiment, not a hardened hostile-code sandbox.

## Missing before GTAG can launch

JNI/Java object and pointer marshalling, guest Android linker and bionic/TLS,
dynamic loading, constructors, guest threads/signals, EGL/GLES and native
windows, Unity activity integration, then VrApi/OpenXR and inputs. ELF APS2,
RELA, GNU-hash-only and additional relocation formats are also unsupported.
Replacing library folder names cannot provide any of these.

## Building

Use Java 17, Gradle 8.9, SDK 35, NDK 26.3.11579264, CMake 3.22.1. Check out the
pinned Unicorn revision and run:

```
python3 tools/fixture.py app/src/main/assets
gradle :app:assembleDebug :app:assembleAndroidTest -PunicornRoot=/path/to/unicorn
```

The workflow `.github/workflows/arm32-lab.yml` builds ARM64 and x86_64 host
libraries and executes seven Android API 35 emulator tests. The fixture contains
only independently authored ARM instructions, never a commercial game binary.
Source code of this companion is GPL-2.0-only; see LICENSE and Unicorn's
upstream COPYING/CREDITS. Original NEXA and game files retain their own licences.
