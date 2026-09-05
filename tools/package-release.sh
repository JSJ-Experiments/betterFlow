#!/usr/bin/env bash
set -euo pipefail

VERSION_NAME=${BETTERFLOW_VERSION_NAME:?BETTERFLOW_VERSION_NAME is required}
VERSION_CODE=${BETTERFLOW_VERSION_CODE:?BETTERFLOW_VERSION_CODE is required}
APK=${APK_PATH:-app/build/outputs/apk/release/app-release.apk}
OUT=${OUT_DIR:-release/generated}
REPO=${GITHUB_REPOSITORY:-JSJ-Experiments/betterFlow}

rm -rf "$OUT"
mkdir -p "$OUT/module" "$OUT/runtime/app" "$OUT/runtime/module-runtime/scripts" "$OUT/runtime/module-runtime/webroot"
cp -a module/. "$OUT/module/"
rm -rf "$OUT/module/webroot"
mkdir -p "$OUT/module/webroot"
cp -a webui/dist/. "$OUT/module/webroot/"
cp "$APK" "$OUT/module/payload/betterflow.apk"
cp "$APK" "$OUT/runtime/app/betterflow.apk"

for script in common.sh watchdog.sh status.sh control.sh; do
  cp "module/scripts/$script" "$OUT/runtime/module-runtime/scripts/$script"
done
cp -a webui/dist/. "$OUT/runtime/module-runtime/webroot/"
chmod 0755 "$OUT/module"/*.sh "$OUT/module/scripts"/*.sh "$OUT/runtime/module-runtime/scripts"/*.sh

python3 - "$OUT/module/module.prop" "$VERSION_NAME" "$VERSION_CODE" <<'PY'
from pathlib import Path
import sys
path=Path(sys.argv[1]); version=sys.argv[2]; code=sys.argv[3]
lines=[]
for line in path.read_text().splitlines():
    if line.startswith('version='): line=f'version={version}'
    elif line.startswith('versionCode='): line=f'versionCode={code}'
    lines.append(line)
path.write_text('\n'.join(lines)+'\n')
PY

(
  cd "$OUT/module"
  zip -qr9 ../betterflow-module.zip . -x '*.DS_Store'
)
(
  cd "$OUT/runtime"
  zip -qr9 ../betterflow-runtime.zip . -x '*.DS_Store'
)
cp "$APK" "$OUT/betterflow.apk"

RUNTIME_SHA=$(sha256sum "$OUT/betterflow-runtime.zip" | awk '{print $1}')
MODULE_SHA=$(sha256sum "$OUT/betterflow-module.zip" | awk '{print $1}')
cat > "$OUT/runtime.env" <<ENV
version=${VERSION_NAME}
versionCode=${VERSION_CODE}
zipUrl=https://github.com/${REPO}/releases/latest/download/betterflow-runtime.zip
sha256=${RUNTIME_SHA}
ENV
cat > "$OUT/update.json" <<JSON
{
  "version": "${VERSION_NAME}",
  "versionCode": ${VERSION_CODE},
  "zipUrl": "https://github.com/${REPO}/releases/latest/download/betterflow-module.zip",
  "changelog": "https://github.com/${REPO}/releases/latest"
}
JSON
cat > "$OUT/checksums.txt" <<SUMS
${MODULE_SHA}  betterflow-module.zip
${RUNTIME_SHA}  betterflow-runtime.zip
$(sha256sum "$OUT/betterflow.apk" | awk '{print $1}')  betterflow.apk
SUMS
printf 'Packaged betterFlow %s (%s)\n' "$VERSION_NAME" "$VERSION_CODE"
