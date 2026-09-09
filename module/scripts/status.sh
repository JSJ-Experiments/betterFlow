#!/system/bin/sh
MODDIR=${MODDIR:-$(cd "${0%/*}/.." 2>/dev/null && pwd)}
. "$MODDIR/scripts/common.sh"
VERSION=$(cat "$DATA_DIR/current_version_name" 2>/dev/null || echo unknown)
CODE=$(cat "$DATA_DIR/current_version" 2>/dev/null || echo 0)
APID=$(pidof "$PKG" 2>/dev/null || true)
LAST=$(cat "$DATA_DIR/last_update_epoch" 2>/dev/null || echo never)
if bubble_enabled; then BUBBLE=enabled; else BUBBLE=disabled; fi
echo "version=$VERSION"
echo "versionCode=$CODE"
echo "backgroundPolling=disabled"
echo "appPid=${APID:-stopped}"
echo "bubble=$BUBBLE"
echo "updateMode=manual"
echo "lastUpdate=$LAST"
