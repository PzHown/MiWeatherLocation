#!/system/bin/sh

ui_print "- MiWeatherLocation Native"
ui_print "- Target: Xiaomi Weather com.miui.weather2"
ui_print "- Architecture: $ARCH"

if [ "$ARCH" != "arm64" ]; then
  abort "! This alpha build currently supports arm64 only"
fi

CFG_DIR=/data/adb/miweatherlocation
mkdir -p "$CFG_DIR"
chmod 0700 "$CFG_DIR"

if [ ! -f "$CFG_DIR/config.properties" ]; then
  cp "$MODPATH/config.properties" "$CFG_DIR/config.properties"
  ui_print "- Installed default Guangzhou Tower configuration"
else
  ui_print "- Preserved existing configuration"
fi
chmod 0600 "$CFG_DIR/config.properties" 2>/dev/null || true

ui_print "- Reboot required after installation"
