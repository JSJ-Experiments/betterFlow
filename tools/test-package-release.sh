#!/usr/bin/env bash
set -euo pipefail
SOURCE=$(cd "$(dirname "$0")/.." && pwd)
FIXTURE=$(mktemp -d)
trap 'rm -rf "$FIXTURE"' EXIT
cp -a "$SOURCE/module" "$FIXTURE/module"
mkdir -p "$FIXTURE/webui/dist"
printf 'packaging test fixture\n' > "$FIXTURE/webui/dist/index.html"
printf 'dummy APK; no Android compilation\n' > "$FIXTURE/fixture.apk"
cd "$FIXTURE"
BETTERFLOW_VERSION_NAME=0.1.88 BETTERFLOW_VERSION_CODE=88 GITHUB_RUN_NUMBER=88 \
  GITHUB_REPOSITORY=JSJ-Experiments/betterFlow APK_PATH=fixture.apk \
  bash "$SOURCE/tools/package-release.sh"
python3 - <<'PY'
import hashlib, json
from pathlib import Path
root = Path('release/generated')
manifest = dict(line.split('=', 1) for line in (root / 'runtime.env').read_text().splitlines())
base = 'https://github.com/JSJ-Experiments/betterFlow/releases/download/build-88/'
assert manifest['zipUrl'] == base + 'betterflow-runtime.zip'
assert manifest['sha256'] == hashlib.sha256((root / 'betterflow-runtime.zip').read_bytes()).hexdigest()
update = json.loads((root / 'update.json').read_text())
assert update['zipUrl'] == base + 'betterflow-module.zip'
assert update['changelog'].endswith('/releases/tag/build-88')
assert update['versionCode'] == 88
print('Release packaging checks passed')
PY
