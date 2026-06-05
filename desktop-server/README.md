# desktop-server

Independent desktop backend module for the local JQViewer HTTP API.

Package layout:

- `config/`: startup arguments, bind address, token, and data directory.
- `http/`: local HTTP server, routing, CORS, and token checks.
- `service/`: JMComic online API adapter and DTO mapping.
- `store/`: desktop-local settings and history persistence.

Rules:

- Desktop backend code lives in this module or another top-level desktop module, never under `android/app`.
- This module stays independent from Android App, Capacitor Plugin, Android notification, SAF, Keystore, ForegroundService, and Android Context APIs.
- This module depends on the published `io.github.jukomu:jmcomic-core` artifact, not the local `JMComic-Api-Java/` source directory.
- Shared frontend code reaches platform behavior through service/client/port types.
- Stage 3 implements the online core API only: init, auth state, search/category, album/photo, comments, browse history, and basic settings.
- Download, PDF, tray, packaging, and desktop notification UI remain out of this module stage.

Stage 3 temporary image note:

- Stage 3 returns photo image metadata and the frontend may temporarily read original CDN image URLs remembered from `getPhoto` / `preloadImages`.
- This is not the final desktop image-resource design.
- Stage 4 must replace that temporary path with token-protected desktop `/image/{photoId}/{sortOrder}` and `/thumb/{photoId}/{sortOrder}` endpoints, plus cache/preload semantics.
