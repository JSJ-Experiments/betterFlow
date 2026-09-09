#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/scripts/common.sh"
stop_legacy_watchdog
ensure_permissions
start_bubble_if_enabled
