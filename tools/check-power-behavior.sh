#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "power-behavior check failed: $*" >&2
  exit 1
}

for script in module/*.sh module/scripts/*.sh; do
  sh -n "$script"
done

if grep -q 'android.permission.WAKE_LOCK' app/src/main/AndroidManifest.xml; then
  fail "the APK must not request WAKE_LOCK"
fi
if grep -Eq '^[[:space:]]*service[[:space:]]+betterflow_watchdog' module/initrc/betterflow.rc; then
  fail "the module must not install a persistent watchdog service"
fi
if grep -Eq 'watchdog\.sh|hot-update\.sh|oom_score_adj' module/service.sh module/boot-completed.sh; then
  fail "boot scripts must not start polling, updates, or OOM tuning"
fi
if grep -Eq 'auto-on|auto-off' module/scripts/control.sh webui/src/main.js webui/src/index.html; then
  fail "periodic update controls must stay removed"
fi

echo "Power behavior checks passed"
