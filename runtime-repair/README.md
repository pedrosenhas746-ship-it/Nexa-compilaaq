# PhoneXR 1.1.0 IPC repair

This patches the Java Client loaded by libopenxr_monado.so. It keeps the original JNI field/method descriptors and AIDL protocol. Source reviewed against Monado a6d31c6c1ce5ab54c6a897328f963571a757e4e7 and the user's PhoneXR 1.1.0/code 7 APK.

Confirmed corrections: predicate-based five-second binding wait (the subsequent native connect RPC retains its existing server startup timeout); null/dead/disconnected callbacks wake the waiter; one guarded unbind for an attempted bind, including false/SecurityException returns; executor shutdown; stable service references and volatile JNI fields; sender and failure-path socket ownership; Binder RuntimeException handling; UI attachment dispatched to main thread.

The runtime-contract module provides compile/test ABI declarations only. Its MonadoView/SystemUiController stubs must NEVER be packaged into PhoneXR. Only Client, its generated inner/lambda classes, and a Java-only diagnostic activity/log reader are transplanted; PhoneXR's existing helper implementations remain in place. NEXA uses these modules only in instrumentation tests. The original native libraries remain byte-identical.

The custom PhoneXR native fork was not supplied. Its compositor, Vulkan initialization, sensor driver and controller receiver cannot be rebuilt from the upstream snapshot plus the incomplete supplied patches. This Java repair does not prove the cause of XR_ERROR_RUNTIME_UNAVAILABLE (-51), create an XR session or make Quest/Pico games universally compatible.

Android instrumentation tests exercise callbacks, timeouts, interruption, socket bytes, cleanup and JNI signatures on API 29/35/36. They do not execute the ARM64 PhoneXR native compositor or Granny.

Modified PhoneXR must be signed with a new certificate because the original signing key is unavailable. Android therefore requires uninstalling the original PhoneXR before installing the repaired package; that removes PhoneXR settings. NEXA keeps its existing test certificate and updates normally. Do not uninstall applications automatically.

The new PhoneXR diagnostic activity reads bounded logs from its own PID, without READ_LOGS. Open it from NEXA after running the runtime probe to see native server initialization messages that are inaccessible from NEXA's UID. Its access does not imply native initialization succeeded.
