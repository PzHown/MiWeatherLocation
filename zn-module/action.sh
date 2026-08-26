#!/system/bin/sh

MODDIR="${0%/*}"
STATUS="$MODDIR/status.log"
MODULE_PROP="$MODDIR/module.prop"
PKG="com.miui.weather2"
TAG="MiWeatherLocationHYOS"
TMP="/data/local/tmp/miweatherlocation_hyos_action_$$.log"
trap 'rm -f "$TMP"' EXIT

echo "=== MiWeatherLocation HYOS Diagnostic ==="
if [ -f "$MODULE_PROP" ]; then
  grep -E '^(name|version|versionCode)=' "$MODULE_PROP" 2>/dev/null
fi

echo
echo "=== HYOS lifecycle file marker ==="
if [ -s "$STATUS" ]; then
  cat "$STATUS"
else
  echo "status.log missing or empty (may be SELinux; not treated as callback failure)"
fi

echo
echo "=== HYOS lifecycle logcat ==="
logcat -d -v threadtime -s "$TAG:I" '*:S' 2>/dev/null | tail -n 120 > "$TMP"
if [ -s "$TMP" ]; then
  cat "$TMP"
else
  echo "No $TAG records currently present in logcat buffer"
fi

echo
echo "=== Weather process ==="
PID="$(pidof "$PKG" 2>/dev/null | awk '{print $1}')"
if [ -n "$PID" ]; then
  echo "PID=$PID"
  if [ -r "/proc/$PID/exe" ]; then
    echo "EXE=$(readlink "/proc/$PID/exe" 2>/dev/null)"
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
ALL="$TMP"
if [ -s "$STATUS" ]; then
  cat "$STATUS" >> "$ALL"
fi
if grep -q 'S4 WEATHER_MATCH' "$ALL" 2>/dev/null; then
  echo "PASS: HYOS callback reached Xiaomi Weather."
elif grep -q 'S4 registerModule rc=0' "$ALL" 2>/dev/null; then
  echo "PARTIAL: HYOS module registered; Weather callback not observed."
elif grep -q 'S4 onModuleLoaded ENTER' "$ALL" 2>/dev/null; then
  echo "PARTIAL: Zygisk Next called onModuleLoaded; inspect runtime/register lines above."
elif grep -q 'S4 constructor reached' "$ALL" 2>/dev/null; then
  echo "PARTIAL: library constructor ran, but no onModuleLoaded record was observed."
else
  echo "FAIL: no constructor or Zygisk Next lifecycle records found."
fi

echo
echo "Copy all output above and send it back for analysis."
