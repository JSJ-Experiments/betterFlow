#!/system/bin/sh
# Compatibility shim for old runtime bundles. Persistent polling was removed;
# boot restoration is handled once by boot-completed.sh/service.sh.
rm -f /data/adb/betterflow-data/watchdog.pid
# A hot update cannot replace an old init .rc file, so explicitly disable that
# legacy service if it launches this updated shim.
setprop ctl.stop betterflow_watchdog 2>/dev/null || true
exit 0
