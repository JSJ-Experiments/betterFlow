#!/system/bin/sh
MODDIR=${MODDIR:-$(cd "${0%/*}/.." 2>/dev/null && pwd)}
. "$MODDIR/scripts/common.sh"
VERSION=$(cat "$DATA_DIR/current_version_name" 2>/dev/null || echo unknown)
CODE=$(cat "$DATA_DIR/current_version" 2>/dev/null || echo 0)
WPID=$(cat "$DATA_DIR/watchdog.pid" 2>/dev/null || true)
APID=$(pidof "$PKG" 2>/dev/null || true)
AUTO=$(manifest_value auto_update "$DATA_DIR/module.conf" 2>/dev/null); [ -n "$AUTO" ] || AUTO=1
LAST=$(cat "$DATA_DIR/last_update_epoch" 2>/dev/null || echo never)
echo "version=$VERSION"
echo "versionCode=$CODE"
echo "watchdogPid=${WPID:-stopped}"
echo "appPid=${APID:-stopped}"
echo "autoUpdate=$AUTO"
echo "lastUpdate=$LAST"
