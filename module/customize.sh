#!/system/bin/sh
SKIPUNZIP=0
PKG=com.jadenjsj.betterflow
DATA_DIR=/data/adb/betterflow-data
ui_print "- betterFlow: installing hot-reloadable runtime"
mkdir -p "$DATA_DIR" "$DATA_DIR/backups" "$DATA_DIR/tmp"
VERSION=$(grep '^version=' "$MODPATH/module.prop" | cut -d= -f2-)
VERSION_CODE=$(grep '^versionCode=' "$MODPATH/module.prop" | cut -d= -f2-)
[ -n "$VERSION" ] && echo "$VERSION" > "$DATA_DIR/current_version_name"
[ -n "$VERSION_CODE" ] && echo "$VERSION_CODE" > "$DATA_DIR/current_version"
if [ -s "$MODPATH/payload/betterflow.apk" ]; then
  cp -f "$MODPATH/payload/betterflow.apk" "$DATA_DIR/current.apk"
  chmod 600 "$DATA_DIR/current.apk"
  pm install -r "$DATA_DIR/current.apk" >/dev/null 2>&1 && ui_print "- APK installed without reboot" || ui_print "! APK install deferred to boot service"
fi
appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || appops set "$PKG" android:system_alert_window allow >/dev/null 2>&1 || true
pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
chmod 0755 "$MODPATH"/*.sh "$MODPATH"/scripts/*.sh 2>/dev/null || true
MODDIR="$MODPATH" nohup sh "$MODPATH/scripts/watchdog.sh" >"$DATA_DIR/watchdog.log" 2>&1 &
ui_print "- Action button = check/download/apply latest release now"
ui_print "- WebUI can do the same; routine updates do not need reboot"
