#!/system/bin/sh
MODID=betterflow
PKG=com.jadenjsj.betterflow
SERVICE=com.jadenjsj.betterflow/.OverlayService
ACTION_WAKE=com.jadenjsj.betterflow.action.WAKE
DATA_DIR=/data/adb/betterflow-data
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
  rm -f "$stage"
  cp -f "$src" "$stage" || return 1
  chmod 0644 "$stage" 2>/dev/null || true
  if pm install -r "$stage" >/dev/null 2>&1; then
    rm -f "$stage"
    return 0
  fi
  rm -f "$stage"
  return 1
}

service_running() {
  dumpsys activity services "$PKG" 2>/dev/null | grep -q "${PKG}/.OverlayService"
}

ensure_permissions() {
  appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || appops set "$PKG" android:system_alert_window allow >/dev/null 2>&1 || true
  pm grant "$PKG" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
  pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
}

start_app() {
  cmd package set-stopped-state "$PKG" false >/dev/null 2>&1 || true
  am start-foreground-service --user 0 -a "$ACTION_WAKE" -n "$SERVICE" >/dev/null 2>&1 || \
    am startservice --user 0 -a "$ACTION_WAKE" -n "$SERVICE" >/dev/null 2>&1 || true
}

boost_app() {
  for pid in $(pidof "$PKG" 2>/dev/null); do
    echo -900 > "/proc/$pid/oom_score_adj" 2>/dev/null || true
  done
}
