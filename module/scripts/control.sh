#!/system/bin/sh
MODDIR=${MODDIR:-$(cd "${0%/*}/.." 2>/dev/null && pwd)}
. "$MODDIR/scripts/common.sh"
case "${1:-status}" in
  status) MODDIR="$MODDIR" sh "$MODDIR/scripts/status.sh" ;;
  update) MODDIR="$MODDIR" sh "$MODDIR/scripts/hot-update.sh" ;;
  start) set_bubble_enabled 1; ensure_permissions; start_app "$ACTION_SHOW" ;;
  stop)
    set_bubble_enabled 0
    am startservice --user 0 -a "$ACTION_HIDE" -n "$SERVICE" >/dev/null 2>&1 || true
    sleep 1
    service_running && am force-stop "$PKG" >/dev/null 2>&1 || true
    ;;
  settings) am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true ;;
  *) echo "usage: $0 {status|update|start|stop|settings}" >&2; exit 2 ;;
esac
