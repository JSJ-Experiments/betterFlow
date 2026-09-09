#!/system/bin/sh
MODDIR=${0%/*}
DATA_DIR=/data/adb/betterflow-data
mkdir -p "$DATA_DIR"
# A one-shot fallback for KernelSU builds that do not invoke boot-completed.sh.
# It exits after boot and never polls while the device is running.
(
  while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
  sleep 8
  . "$MODDIR/scripts/common.sh"
  stop_legacy_watchdog
  ensure_permissions
  start_bubble_if_enabled
) >/dev/null 2>&1 &
