# Correção de abertura v4.1

O APK v4 original fechou na reprodução em Android 14 e 15. No Android 14,
o log mostrou NullPointerException em PhoneWindow.getInsetsController, chamado
por MainActivity.hideSystemUi antes de setContentView. A correção cria a tela
primeiro e obtém o controlador através da DecorView já inicializada.

O menu inicial usa controles Android e abre sem iniciar sensores, serviços ou busca
automática dos aplicativos. A importação fica acessível diretamente. A busca só
inicia quando solicitada ou após a instalação de um jogo importado.

A resolução de atividades trata resultados vazios/nulos, copia a lista antes de
ordenar e filtra registros incompletos. Um pacote inacessível é ignorado pela
busca; erros globais aparecem na tela em vez de encerrar uma thread sem tratamento.
O menu VR permanece disponível como opção. Aplicativos que fecharem por outra
exceção gravam um relatório local; COPIAR DIAGNÓSTICO permite fornecer o erro exato.
O relatório não é enviado automaticamente.

Os testes instrumentados abrem o menu inicial, o menu VR e a tela de importação,
e verificam assinatura/adaptação, em Android API 29, 34 e 35. Esses testes não
executam jogos Quest nem reproduzem necessariamente particularidades de Motorola.
A correção é baseada no erro reproduzido; particularidades do Moto G85 ainda
podem ser confirmadas pelo relatório do aparelho, se necessário.

Instale o APK v4.1. Se a assinatura de desenvolvimento impedir atualizar,
desinstale somente o NEXA antigo. Mantenha PhoneXR, driver e jogos instalados.
Abra NEXA, use IMPORTAR APK ou BUSCAR JOGOS INSTALADOS e EXECUTAR SELECIONADO.
Se houver novo fechamento, reabra NEXA e toque COPIAR DIAGNÓSTICO.
