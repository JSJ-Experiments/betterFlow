#!/system/bin/sh
MODDIR=${MODDIR:-$(cd "${0%/*}/.." 2>/dev/null && pwd)}
. "$MODDIR/scripts/common.sh"
# Make fallback mode as hard to OOM-kill as Android allows; initrc mode also sets this.
echo -1000 > /proc/$$/oom_score_adj 2>/dev/null || true
PIDFILE="$DATA_DIR/watchdog.pid"
if [ -f "$PIDFILE" ]; then
  old=$(cat "$PIDFILE" 2>/dev/null)
  [ -n "$old" ] && kill -0 "$old" 2>/dev/null && exit 0
fi
echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE"' EXIT INT TERM

CONFIG="$DATA_DIR/module.conf"
[ -f "$CONFIG" ] || cat > "$CONFIG" <<'CFG'
auto_update=1
update_interval_seconds=21600
watchdog_interval_seconds=12
CFG
last_check=0

while true; do
  if pm path "$PKG" >/dev/null 2>&1; then
    ensure_permissions
    if ! service_running; then
      start_app
      sleep 1
    fi
    boost_app
  elif [ -s "$DATA_DIR/current.apk" ]; then
    install_apk "$DATA_DIR/current.apk" || true
  fi

  AUTO=$(manifest_value auto_update "$CONFIG"); [ -n "$AUTO" ] || AUTO=1
  INTERVAL=$(manifest_value update_interval_seconds "$CONFIG"); case "$INTERVAL" in ''|*[!0-9]*) INTERVAL=21600;; esac
  NOW=$(date +%s 2>/dev/null || echo 0)
  if [ "$AUTO" = "1" ] && [ "$NOW" -gt 0 ] && [ $((NOW - last_check)) -ge "$INTERVAL" ]; then
    last_check=$NOW
    MODDIR="$MODDIR" sh "$MODDIR/scripts/hot-update.sh" --quiet --if-newer >/dev/null 2>&1 || true
  fi
  SLEEP=$(manifest_value watchdog_interval_seconds "$CONFIG"); case "$SLEEP" in ''|*[!0-9]*) SLEEP=12;; esac
  sleep "$SLEEP"
done
