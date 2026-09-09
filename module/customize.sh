#!/system/bin/sh
SKIPUNZIP=0
PKG=com.jadenjsj.betterflow
DATA_DIR=/data/adb/betterflow-data
ui_print "- betterFlow: installing hot-reloadable runtime"
mkdir -p "$DATA_DIR" "$DATA_DIR/backups" "$DATA_DIR/tmp"
setprop ctl.stop betterflow_watchdog 2>/dev/null || true
if [ -f "$DATA_DIR/watchdog.pid" ]; then
  old_watchdog=$(cat "$DATA_DIR/watchdog.pid" 2>/dev/null || true)
  case "$old_watchdog" in ''|*[!0-9]*) ;; *) kill "$old_watchdog" 2>/dev/null || true;; esac
  rm -f "$DATA_DIR/watchdog.pid"
fi
VERSION=$(grep '^version=' "$MODPATH/module.prop" | cut -d= -f2-)
VERSION_CODE=$(grep '^versionCode=' "$MODPATH/module.prop" | cut -d= -f2-)
[ -n "$VERSION" ] && echo "$VERSION" > "$DATA_DIR/current_version_name"
[ -n "$VERSION_CODE" ] && echo "$VERSION_CODE" > "$DATA_DIR/current_version"
if [ -s "$MODPATH/payload/betterflow.apk" ]; then
  cp -f "$MODPATH/payload/betterflow.apk" "$DATA_DIR/current.apk"
  chmod 600 "$DATA_DIR/current.apk"
  . "$MODPATH/scripts/common.sh"
  install_apk "$DATA_DIR/current.apk" && ui_print "- APK installed without reboot" || ui_print "! APK install deferred to boot service"
fi
appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || appops set "$PKG" android:system_alert_window allow >/dev/null 2>&1 || true
pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
chmod 0755 "$MODPATH"/*.sh "$MODPATH"/scripts/*.sh 2>/dev/null || true
. "$MODPATH/scripts/common.sh"
sync_bubble_marker_from_prefs
start_bubble_if_enabled
ui_print "- Action button = check/download/apply latest release now"
ui_print "- Gboard mode is event-driven; no persistent watchdog is installed"
