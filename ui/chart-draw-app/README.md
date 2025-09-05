# Chart Draw App (Template Copy)

A lightweight React template for rendering OHLC charts and allowing users to draw:
- Lines
- Channels (parallel)
- Triangles
- Fibonacci Retracement
- Fibonacci Extension

The app talks to backend REST endpoints:
- GET /api/ohlc?symbol=SYMBOL&interval=1m|5m|15m|1h|1d
- GET /api/drawings?symbol=SYMBOL&timeframe=TF
- POST /api/drawings
- PUT /api/drawings/{id}
- DELETE /api/drawings/{id}

## Dev

- Node 18+ recommended
- Install: `npm i`
- Run: `npm run dev`
- The dev server proxies `/api` requests to `http://localhost:8080`.

## Build

- `npm run build` produces static assets under `dist/`.
- You can serve `dist/` behind your Spring app or any static file server.

## Notes

- This template is intentionally minimal and does not include real-time updates.
- Drawings are stored in the backend DB and reloaded as overlays based on symbol and timeframe.
- The drawing overlay is an SVG layer; payloads are stored as JSON and can be reused later for rendering or exporting images to send to your AI workflows.
