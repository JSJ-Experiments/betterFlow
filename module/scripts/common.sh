#!/system/bin/sh
MODID=betterflow
PKG=com.jadenjsj.betterflow
SERVICE=com.jadenjsj.betterflow/.OverlayService
ACTION_WAKE=com.jadenjsj.betterflow.action.WAKE
ACTION_SHOW=com.jadenjsj.betterflow.action.SHOW
ACTION_HIDE=com.jadenjsj.betterflow.action.HIDE
DATA_DIR=/data/adb/betterflow-data
BUBBLE_MARKER=$DATA_DIR/bubble_enabled
REPO=JSJ-Experiments/betterFlow
LATEST_BASE=https://github.com/$REPO/releases/latest/download
mkdir -p "$DATA_DIR" "$DATA_DIR/tmp" "$DATA_DIR/backups" 2>/dev/null

bb() {
  if [ -x /data/adb/ksu/bin/busybox ]; then
    /data/adb/ksu/bin/busybox "$@"
  elif command -v busybox >/dev/null 2>&1; then
    busybox "$@"
  else
    "$@"
  fi
}

download() {
  url="$1"; out="$2"
  rm -f "$out"
  if command -v curl >/dev/null 2>&1; then
    curl -LfsS --connect-timeout 15 --max-time 180 "$url" -o "$out"
  else
    bb wget -q -T 180 -O "$out" "$url"
  fi
}

manifest_value() {
  key="$1"; file="$2"
  bb awk -F= -v k="$key" '$1 == k { sub(/^[^=]*=/, ""); print; exit }' "$file"
}


install_apk() {
  src="$1"
  [ -s "$src" ] || return 1
  stage="/data/local/tmp/betterflow-install-$$.apk"
  error_log="$DATA_DIR/install-error.log"
  rm -f "$stage"
  cp -f "$src" "$stage" || return 1
  chmod 0644 "$stage" 2>/dev/null || true
  restorecon "$stage" >/dev/null 2>&1 || true
  output=$(pm install --user 0 -r "$stage" 2>&1)
  result=$?
  if [ "$result" -eq 0 ]; then
    rm -f "$stage"
    rm -f "$error_log"
    return 0
  fi
  printf '%s\n' "$output" > "$error_log"
  printf '%s\n' "$output" >&2
  rm -f "$stage"
  return 1
}

service_running() {
  dumpsys activity services "$PKG" 2>/dev/null | grep -q "${PKG}/.OverlayService"
}

stop_legacy_watchdog() {
  setprop ctl.stop betterflow_watchdog 2>/dev/null || true
  pid=$(cat "$DATA_DIR/watchdog.pid" 2>/dev/null || true)
  case "$pid" in ''|*[!0-9]*) rm -f "$DATA_DIR/watchdog.pid"; return;; esac
  if [ -r "/proc/$pid/cmdline" ] && tr '\000' ' ' < "/proc/$pid/cmdline" | grep -q '/betterflow/scripts/watchdog.sh'; then
    [ "$pid" = "$$" ] || kill "$pid" 2>/dev/null || true
  fi
  rm -f "$DATA_DIR/watchdog.pid"
}

sync_bubble_marker_from_prefs() {
  prefs=/data/user/0/$PKG/shared_prefs/betterflow.xml
  [ -r "$prefs" ] || prefs=/data/data/$PKG/shared_prefs/betterflow.xml
  [ -r "$prefs" ] || return 0
  if grep -q 'name="bubble_visible" value="true"' "$prefs"; then
    : > "$BUBBLE_MARKER"
  elif grep -q 'name="bubble_visible" value="false"' "$prefs"; then
    rm -f "$BUBBLE_MARKER"
  fi
}

bubble_enabled() {
  [ -f "$BUBBLE_MARKER" ]
}

set_bubble_enabled() {
  if [ "$1" = "1" ]; then
    : > "$BUBBLE_MARKER"
  else
    rm -f "$BUBBLE_MARKER"
  fi
}

ensure_permissions() {
  appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || appops set "$PKG" android:system_alert_window allow >/dev/null 2>&1 || true
  pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
  pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
}

unstop_app() {
  cmd package unstop --user 0 "$PKG" >/dev/null 2>&1 || \
    cmd package set-stopped-state "$PKG" false >/dev/null 2>&1 || true
}

start_app() {
  action=${1:-$ACTION_WAKE}
  unstop_app
  if am start-foreground-service --user 0 -a "$action" -n "$SERVICE" >/dev/null 2>&1; then
    return 0
  fi
  if am startservice --user 0 -a "$action" -n "$SERVICE" >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

start_bubble_if_enabled() {
  sync_bubble_marker_from_prefs
  bubble_enabled || return 0
  start_app "$ACTION_WAKE"
}
