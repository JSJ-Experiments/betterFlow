#!/system/bin/sh
MODDIR=${MODDIR:-$(cd "${0%/*}/.." 2>/dev/null && pwd)}
. "$MODDIR/scripts/common.sh"
CONFIG="$DATA_DIR/module.conf"
ensure_config() {
  [ -f "$CONFIG" ] || cat > "$CONFIG" <<'CFG'
auto_update=1
update_interval_seconds=21600
watchdog_interval_seconds=12
CFG
}
set_auto() {
  ensure_config
  value="$1"
  if grep -q '^auto_update=' "$CONFIG" 2>/dev/null; then
    sed -i "s/^auto_update=.*/auto_update=$value/" "$CONFIG"
  else
    echo "auto_update=$value" >> "$CONFIG"
  fi
}
case "${1:-status}" in
  status) MODDIR="$MODDIR" sh "$MODDIR/scripts/status.sh" ;;
  update) MODDIR="$MODDIR" sh "$MODDIR/scripts/hot-update.sh" ;;
  start) ensure_permissions; start_app ;;
  stop) am force-stop "$PKG" >/dev/null 2>&1 || true ;;
  settings) am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true ;;
  auto-on) set_auto 1 ;;
  auto-off) set_auto 0 ;;
  *) echo "usage: $0 {status|update|start|stop|settings|auto-on|auto-off}" >&2; exit 2 ;;
esac
