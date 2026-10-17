#!/bin/sh
# Baut die Web-Version: engine.js (AudioWorklet), app.js (Oberfläche),
# index.html (vollständige Seite, z. B. für GitHub Pages) und artifact.html (Inhalt für die veröffentlichte Seite)
cd "$(dirname "$0")"
cat src/fx.js src/inst.js src/engine-core.js > engine.js
cat src/app-core.js src/app-ui.js src/app-overlays.js > app.js
cp src/index.html artifact.html
{ printf '<!doctype html>\n<html lang="de">\n<head>\n<meta charset="utf-8">\n<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">\n</head>\n<body>\n'; cat src/index.html; printf '\n</body>\n</html>\n'; } > index.html
