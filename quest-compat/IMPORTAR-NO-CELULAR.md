# NEXA Quest Bridge v4 — importação no celular

Instale PhoneXR, o driver VrApi v3 e o NEXA v4 em um celular Android 8 ou mais recente.
Não instale o driver no próprio Quest. Os dois primeiros componentes continuam os mesmos da versão v3.

Abra NEXA, toque **IMPORTAR APK**, depois **Selecionar APK**. Selecione o APK completo do
seu jogo. A análise identifica as ABIs, escolhe a arquitetura que o Android suporta,
recusa splits incompletos e compara imports VrApi estáticos com exports reais do adaptador.
Quando a análise permitir, a cópia é adaptada e assinada automaticamente.
Depois toque **Instalar cópia**.
O Android pode pedir para permitir instalações desta fonte; confirme a instalação.
Toque **Abrir no NEXA**, depois **EXECUTAR**. O jogo segue pelo PhoneXR.

A preparação é local e não requer PC, upload do jogo ou chave da Meta. Inclui bibliotecas
ARM32/ARM64 produzidas no build, altera o manifest binário preservando recursos,
comprime as bibliotecas para extração em dispositivos com páginas de 4/16 KB e assina
com uma chave RSA gerada pelo AndroidKeyStore. A verificação da assinatura e dos
arquivos originais precede a instalação. A chave local persiste enquanto os dados do
NEXA são preservados; limpar dados/desinstalar o NEXA pode impedir atualizar cópias anteriores.

Não há garantia universal. Um APK preparado/instalado não significa jogo funcionando.
O caminho VrApi exige OpenGL ES; Vulkan, skeleton/mesh de mãos e serviços Meta
continuam incompletos. Chamadas dinâmicas dlsym, extensões OpenXR, diferenças de SDK,
tracking, controles, imagem e desempenho dependem de validação real.
Esta versão não importa OBB, splits, APKs com sharedUserId nem runtimes embutidos sem
loader separado. APK ARM32 exige Android com suporte real a apps ARM32.
OpenXR recebe o loader Khronos e descoberta do PhoneXR, sem injetar VrApi.
A importação preserva assets, DEX e a lógica/licenças do jogo; não implementa bypass.

A cópia tem nova assinatura e preserva o packageName: ela não pode substituir uma
instalação com outra chave. O NEXA nunca desinstala o original nem apaga saves;
use um perfil ou aparelho de teste. O arquivo escolhido fica no cache privado durante a importação, sem alterar a origem.

## Validação automatizada

O workflow compila APK/driver, verifica símbolos e executa o mesmo código Java de
manifest/ELF/ZIP usado no Android sobre uma fixture nativa. Confere edição idempotente,
rejeição de XML/ABI inválidos, assinatura v2, alinhamento ZIP e preservação das entradas.
Uma verificação Android em emulador testa assinatura AndroidKeyStore e leitura do APK
preparado. Isso não testa execução de jogos Quest, renderização ou controles.

Se você já instalou o NEXA v3 e o Android recusar atualizar por assinatura,
desinstale somente o NEXA v3 antes de instalar v4. PhoneXR e o driver já instalados
podem permanecer. Não desinstale o jogo original para resolver esse aviso.
