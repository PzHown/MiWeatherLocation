#!/system/bin/sh

MODDIR="${0%/*}"
STATUS="$MODDIR/status.log"
MODULE_PROP="$MODDIR/module.prop"
PKG="com.miui.weather2"

echo "=== MiWeatherLocation HYOS Diagnostic ==="
if [ -f "$MODULE_PROP" ]; then
  grep -E '^(name|version|versionCode)=' "$MODULE_PROP" 2>/dev/null
fi

echo
echo "=== HYOS lifecycle ==="
if [ -s "$STATUS" ]; then
  cat "$STATUS"
else
  echo "status.log missing or empty"
fi

echo
echo "=== Weather process ==="
PID="$(pidof "$PKG" 2>/dev/null | awk '{print $1}')"
if [ -n "$PID" ]; then
  echo "PID=$PID"
  if [ -r "/proc/$PID/exe" ]; then
    EXE="$(readlink "/proc/$PID/exe" 2>/dev/null)"
    echo "EXE=$EXE"
  fi
else
  echo "Weather process not running"
fi

echo
echo "=== Module maps ==="
if [ -n "$PID" ] && [ -r "/proc/$PID/maps" ]; then
  grep -F 'libmiweatherlocation_hyos.so' "/proc/$PID/maps" 2>/dev/null || echo "module .so not found in Weather maps"
else
  echo "maps unavailable"
fi

echo
echo "=== Result ==="
if grep -q 'S3 WEATHER_MATCH' "$STATUS" 2>/dev/null; then
  echo "PASS: Zygisk Next HYOS callback reached Xiaomi Weather."
elif grep -q 'S3 registerModule rc=0' "$STATUS" 2>/dev/null; then
  echo "PARTIAL: HYOS module registered, but Xiaomi Weather callback not observed."
elif grep -q 'S3 onModuleLoaded' "$STATUS" 2>/dev/null; then
  echo "PARTIAL: module loaded into hyos_spawner, but HYOS registration did not complete."
else
  echo "FAIL: no HYOS lifecycle markers found."
fi

echo
echo "Copy all output above and send it back for analysis."
