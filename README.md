# NEXA XR OS 23 — Public Builder

Builder público do NEXA XR OS 23.

- Base móvel XR do NEXA 23
- MineVR Bridge (`com.minevr.bridge.START`) com fallback QuestCraft/QCXR
- Integração Roblox Android/VR preparada pelo NEXA
- SUPERHOT removido por completo deste source/builder
- Payloads proprietários de jogos não são incluídos
- Box64/PC-runtime não é alegado como embutido neste APK mobile-XR

O workflow reconstrói o source a partir de um pacote base64 verificado por SHA-256, hidrata o modelo MediaPipe de mãos, roda o preflight e então tenta compilar o APK Android via Unity 6000.2.15f1.
