# Xeno PS4 Browser

Homebrew para PS4 desbloqueado/GoldHEN que baixa arquivos por HTTP/HTTPS diretamente para `/data/pkg`.

## Controles
- **X**: abrir o teclado nativo do PS4, inserir uma URL e iniciar o download.
- **Quadrado**: editar a URL sem iniciar.
- **Círculo**: sair (quando nenhum download estiver ativo).

O download é feito em uma thread separada, mostra progresso e usa um arquivo `.part` temporário para evitar deixar arquivo incompleto com o nome final.

## Compilação automática
A workflow `Build PS4 PKG` usa a imagem oficial do OpenOrbis e publica o PKG em **Actions > Artifacts**.

## Observação
O app é destinado a homebrew e arquivos que você tem permissão para baixar/usar. Ele não contorna licenças de conteúdo comercial.
