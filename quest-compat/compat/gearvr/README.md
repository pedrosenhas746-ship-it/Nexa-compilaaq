# Adaptador legado: source corrigido, ainda não compilado

Estes arquivos derivam do código enviado pelo usuário em
questandgearandopenxr(1).zip. A origem e hashes estão em provenance.json.
Não são o núcleo clean-room original do Horizon-Bridge. Comentários herdados
que prometem executar jogos não constituem resultados comprovados.

## Correções efetuadas

- Namespaces e tipos qualificados usam compatibility_layer, identificador C++ válido.
- Headers OpenXR restaurados aos nomes oficiais; incluído GLES antes dos tipos GL.
- Retirado o redirecionamento ARM32 para nomes de pacote/biblioteca inexistentes.
  O loader padrão fica responsável pela descoberta; ARM32 continua não validado.
- CMake usa o loader oficial importado por Prefab e não um .so renomeado do anexo.
- Falha de inicialização limpa recursos antes de nova tentativa.
- Contagens e retornos de extensões, views, formatos, espaços e imagens são verificados.
- Aquisição de imagem inválida não indexa o array. Timeout espera novamente a mesma
  imagem adquirida, sem tratá-lo como sucesso ou liberar uma imagem não disponível.
- Flags de posição válida/rastreada são propagadas do runtime, separadas da orientação.
- A conversão de relógio requerida pelo adaptador passa a ser exigida na inicialização.

source-repairs.patch permite revisar as mudanças em relação ao source recebido.
O patch de entrada UDP do Monado recebido não foi aplicado automaticamente:
ele também exige revisão de validade espacial, sincronização e nomes do upstream.

## Construção

O build Android principal inclui o alvo estático hb_legacy_xr para compilar o
backend contra os headers do mesmo loader. O alvo ainda não foi compilado aqui
por ausência do NDK e dos downloads necessários. O módulo opcional Android `legacy-driver` da versão 0.12 agora o empacota como
driver junto com o frontend; ver docs/LEGACY-0.12.md. A rota via PhoneXR está
ligada à biblioteca local, mas não foi executada e não garante que jogos a aceitem.

O frontend VrApi fica disponível para construção opcional com
HB_BUILD_LEGACY_VRAPI=ON e VRAPI_ROOT apontando para os headers SDK correspondentes.
Sem eles, o frontend fica desligado e o build principal não os exige. Nenhuma
declaração binária VrApi foi inventada para ocultar essa dependência.
Não foram reempacotados os APKs, loaders binários ou chaves do anexo.

## Trabalho restante

Precisam de validação: ABI VrApi, erros do frame loop, estados GL, formatos e
camadas além de projection, perfis de input, relógios, lifecycle Android e
serviços de plataforma. Existem caminhos legados que ainda retornam capacidades
fixas; eles não validam hardware e não devem ser usados como prova de identidade
Meta ou de compatibilidade. Não há tratamento de licença/anti-cheat neste reparo.
Nenhum jogo foi executado. Reduzir uma cópia final não reduz automaticamente
as chamadas gráficas já realizadas pelo jogo.

Na 0.12, o alvo frontend inclui vrapi_driver.cpp (JNI) e libdl. Entradas são
zeradas quando perdem foco/atividade; SubmitFrame2 respeita shouldRender e o
retorno de begin_frame. Essas mudanças nativas ainda precisam de build e teste.
