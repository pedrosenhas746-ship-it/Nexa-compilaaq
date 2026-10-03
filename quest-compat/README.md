# NEXA Quest Bridge v3 — biblioteca VrApi própria

Esta versão usa o backend VrApi/OpenXR do Horizon Bridge 0.12 anexado pelo usuário.
Gera uma `libvrapi.so` própria, com SONAME correto, em ARM64 e ARM32. O código de
renderização e tracking pertence ao adaptador; os headers 1.1.50 servem como contrato
binário, e nenhuma biblioteca VrApi binária da Meta é usada no build.

O runtime continua sendo PhoneXR/Monado, separado da biblioteca. Não é universal:
o frontend admite OpenGL ES, layers de projeção e input de controle. Vulkan,
hand skeleton/mesh e serviços Meta continuam sem implementação funcional.

## Dois caminhos para testes

1. Instale **NEXA-Quest-Bridge-v3-debug.apk**, PhoneXR e
   **NEXA-VrApi-Driver-v3-debug.apk** no celular Android. O NEXA detecta VrApi
   e abre através do launcher PhoneXR, que encaminha ao driver e concede visibilidade
   entre os processos. O loader original do jogo precisa aceitar esse contrato.
   Não instale o driver com package `com.oculus.systemdriver` no próprio Quest.
2. Quando o loader original não aceitar o driver, adapte uma cópia do APK
   substituindo `libvrapi.so` e incluindo o loader Khronos em cada ABI. A ferramenta
   abaixo também ajusta a descoberta do runtime e as categorias de abertura.

O NEXA v3 não importa nem adapta APKs pelo próprio celular. A ferramenta de
adaptação é para PC. Jogos OpenXR usam o launcher PhoneXR sem o driver VrApi.
Jogos que usam OVRPlugin com VrApi embutida não podem receber uma biblioteca
separada automaticamente; precisam de análise específica.

## Adaptar um APK standalone no PC

Necessário: Python 3, apktool 2.12.1 ou mais recente (aapt2 padrão), readelf, Android build-tools 35 (`zipalign` e
`apksigner`) no PATH, e uma keystore de teste. Extraia este ZIP numa pasta.

```sh
keytool -genkeypair -keystore nexa-test.jks -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
  -dname 'CN=NEXA Test'
export NEXA_KEYSTORE_PASSWORD=android
python3 adapt-quest-apk.py jogo.apk \
  --kit NEXA-VrApi-Native-Kit-v3.zip --output jogo-nexa.apk \
  --keystore nexa-test.jks
```

O script verifica ABI e imports VrApi estáticos, reconstrói o manifest, assina,
verifica a assinatura e compara as bibliotecas do APK final com o kit. Recusa
splits incompletos, arquiteturas sem biblioteca e funções estáticas ausentes.
Chamadas por dlsym e diferenças de layout entre versões do SDK ainda precisam
de teste real. A ausência de imports estáticos não significa compatibilidade.
Não altera entitlement, anti-cheat, OVR Platform, lógica ou assets do jogo.
Uma cópia assinada com outra chave não atualiza o original instalado; pode exigir
um aparelho/perfil de teste separado para preservar a instalação e os saves.

## O que foi validado

CI compila o frontend VrApi, backend OpenXR e APK do driver com NDK 26.3;
compila o NEXA com SDK 35; verifica ELF, SONAME e exports das duas ABIs.
Os testes host verificam rejeição de ABIs incorretas/splits e edição do manifest.
Essas verificações não executam jogos. Nenhum GTAG/Quest APK foi testado aqui.

## Origem e reprodução

- Base: Horizon-Bridge-0.12-source(1).zip, fornecida pelo usuário.
- Backend derivado do Compatibility-Layer recebido na base; ver
  `compat/gearvr/provenance.json` e `compat/gearvr/README.md` no source.
- Headers: arquivo `ovr_sdk_mobile_1.50.0.zip` do mirror
  `emawind84/ovr_sdk_mobile_dist`, commit
  `edaf95714e80dcd2645f69ef255d6fbfc883a73e`; blob
  `c5d3fa6ef4f722616cb6339055c9cad9f87c9e43` verificado antes da extração.
- Loader: Khronos `openxr_loader_for_android:1.1.49` via Maven/Prefab.
- Workflow: `.github/workflows/quest-native-bridge.yml` na branch
  `quest-native-bridge-v3` de `pedrosenhas746-ship-it/Nexa-compilaaq`.
