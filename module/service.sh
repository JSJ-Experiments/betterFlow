#!/system/bin/sh
MODDIR=${0%/*}
DATA_DIR=/data/adb/betterflow-data
mkdir -p "$DATA_DIR"
# Current KernelSU will supervise betterflow_watchdog through initrc after boot_completed.
# Keep a delayed fallback for late-load/custom-rc-disabled installations.
(
  while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
  sleep 8
  if [ "$(getprop init.svc.betterflow_watchdog)" != "running" ]; then
    MODDIR="$MODDIR" nohup sh "$MODDIR/scripts/watchdog.sh" >"$DATA_DIR/watchdog.log" 2>&1 &
  fi
) >/dev/null 2>&1 &
