# NexaQuest 1.4.5 — Original QuestCraft Lodge

This build keeps the Android Studio/native runtime and converts the original QuestCraft lodge from the pinned QCXR source instead of recreating it by eye.

Source anchors used by the APK workflow:
- `Assets/Scenes/Main.unity`
- `Assets/WinterLodge/**`
- `Assets/QCWorld/QCWorld.obj`
- original MainMenu/CRT UI assets and TextMesh Pro SDF data

The build generates `nexa-room.json`, eight GLB runtime models, rendered CRT menu textures and hitboxes, then packages them into the Android APK. No Unity runtime is required on-device.

This marker intentionally triggers the Android Studio workflow for a fresh end-to-end 1.4.5 validation.
