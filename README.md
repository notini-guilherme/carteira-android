# Carteira (app Android)

App Android que abre a carteira de ações, FIIs e cripto dentro de um WebView.

- `app/src/main/assets/index.html`: a carteira (gerada por `build_android.py`, versão limpa, sem dados pessoais).
- `MainActivity.kt`: janela do app, anexar notas/arquivos, links externos no navegador.
- `Bridges.kt`: ponte para buscar cotações (brapi, Binance, Mercado Bitcoin, Banco Central) sem as regras de CORS e para salvar o backup em Downloads.
- `.github/workflows/build.yml`: a cada envio para a branch `main`, o GitHub compila e publica o APK em **Releases** (marcador `ultima`).

Instalar no celular: abra a página de Releases do repositório, baixe `Carteira.apk` e instale
(o Android pede para permitir "instalar apps desconhecidos" para o navegador ou para o app Arquivos).

A chave de assinatura `app/debug.keystore` é só de teste (mantém a mesma assinatura entre as compilações).
Para publicar na Google Play será preciso uma chave própria e o pacote `.aab`.
