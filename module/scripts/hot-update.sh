#!/system/bin/sh
set -u
MODDIR=${MODDIR:-$(cd "${0%/*}/.." 2>/dev/null && pwd)}
. "$MODDIR/scripts/common.sh"
QUIET=0
IF_NEWER=0
for arg in "$@"; do
  [ "$arg" = "--quiet" ] && QUIET=1
  [ "$arg" = "--if-newer" ] && IF_NEWER=1
done
say() { [ "$QUIET" -eq 1 ] || echo "$*"; }

LOCK="$DATA_DIR/update.lock"
if ! mkdir "$LOCK" 2>/dev/null; then
  say "betterFlow: another update is already running"
  exit 2
fi
trap 'rm -rf "$LOCK" "$DATA_DIR/tmp/update.$$"' EXIT INT TERM
TMP="$DATA_DIR/tmp/update.$$"
mkdir -p "$TMP"
MANIFEST="$TMP/runtime.env"

say "betterFlow: checking latest release…"
if ! download "$LATEST_BASE/runtime.env" "$MANIFEST"; then
  say "betterFlow: failed to download runtime manifest"
  exit 1
fi
VERSION_CODE=$(manifest_value versionCode "$MANIFEST")
VERSION_NAME=$(manifest_value version "$MANIFEST")
ZIP_URL=$(manifest_value zipUrl "$MANIFEST")
ZIP_SHA=$(manifest_value sha256 "$MANIFEST")
case "$VERSION_CODE" in ''|*[!0-9]*) say "betterFlow: invalid release manifest"; exit 1;; esac
[ -n "$ZIP_URL" ] && [ -n "$ZIP_SHA" ] || { say "betterFlow: incomplete release manifest"; exit 1; }
CURRENT=$(cat "$DATA_DIR/current_version" 2>/dev/null || echo 0)
case "$CURRENT" in ''|*[!0-9]*) CURRENT=0;; esac
if [ "$IF_NEWER" -eq 1 ] && [ "$VERSION_CODE" -le "$CURRENT" ]; then
  exit 0
fi
if [ "$VERSION_CODE" -eq "$CURRENT" ]; then
  say "betterFlow: already on $VERSION_NAME ($VERSION_CODE); re-applying latest build"
fi

ZIP="$TMP/runtime.zip"
say "betterFlow: downloading $VERSION_NAME ($VERSION_CODE)…"
download "$ZIP_URL" "$ZIP" || { say "betterFlow: runtime download failed"; exit 1; }
ACTUAL=$(bb sha256sum "$ZIP" | bb awk '{print $1}')
[ "$ACTUAL" = "$ZIP_SHA" ] || { say "betterFlow: SHA-256 mismatch; refusing update"; exit 1; }
mkdir -p "$TMP/unpack"
bb unzip -oq "$ZIP" -d "$TMP/unpack" || { say "betterFlow: unzip failed"; exit 1; }
APK="$TMP/unpack/app/betterflow.apk"
[ -s "$APK" ] || { say "betterFlow: release has no APK"; exit 1; }

OLD_APK="$DATA_DIR/backups/betterflow-prev.apk"
if [ -s "$DATA_DIR/current.apk" ]; then cp -f "$DATA_DIR/current.apk" "$OLD_APK"; fi
cp -f "$APK" "$DATA_DIR/current.apk.new"
chmod 600 "$DATA_DIR/current.apk.new"

say "betterFlow: installing APK in-place…"
if ! pm install -r "$DATA_DIR/current.apk.new" >/dev/null 2>&1; then
  say "betterFlow: APK install failed; leaving current version untouched"
  rm -f "$DATA_DIR/current.apk.new"
  exit 1
fi
mv -f "$DATA_DIR/current.apk.new" "$DATA_DIR/current.apk"

# Script payloads are intentionally replaced only after the APK has installed.
if [ -d "$TMP/unpack/module-runtime/scripts" ]; then
  for script in common.sh watchdog.sh status.sh control.sh; do
    [ -s "$TMP/unpack/module-runtime/scripts/$script" ] || continue
    cp -f "$TMP/unpack/module-runtime/scripts/$script" "$MODDIR/scripts/$script.new" || continue
    chmod 0755 "$MODDIR/scripts/$script.new"
    mv -f "$MODDIR/scripts/$script.new" "$MODDIR/scripts/$script"
  done
fi
if [ -d "$TMP/unpack/module-runtime/webroot" ]; then
  rm -rf "$MODDIR/webroot.new"
  mkdir -p "$MODDIR/webroot.new"
  cp -a "$TMP/unpack/module-runtime/webroot/." "$MODDIR/webroot.new/" || true
  if [ -s "$MODDIR/webroot.new/index.html" ]; then
    rm -rf "$MODDIR/webroot.old"
    [ -d "$MODDIR/webroot" ] && mv "$MODDIR/webroot" "$MODDIR/webroot.old"
    mv "$MODDIR/webroot.new" "$MODDIR/webroot"
    rm -rf "$MODDIR/webroot.old"
  fi
fi

echo "$VERSION_CODE" > "$DATA_DIR/current_version"
echo "$VERSION_NAME" > "$DATA_DIR/current_version_name"
date +%s > "$DATA_DIR/last_update_epoch" 2>/dev/null || true
ensure_permissions
am force-stop "$PKG" >/dev/null 2>&1 || true
start_app
sleep 1
boost_app
say "betterFlow: hot update applied — $VERSION_NAME ($VERSION_CODE), no reboot requested"
