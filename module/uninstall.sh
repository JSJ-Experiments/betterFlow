#!/system/bin/sh
PKG=com.jadenjsj.betterflow
if [ -f /data/adb/betterflow-data/watchdog.pid ]; then
  kill "$(cat /data/adb/betterflow-data/watchdog.pid)" 2>/dev/null || true
fi
pm uninstall "$PKG" >/dev/null 2>&1 || true
rm -rf /data/adb/betterflow-data
