#!/bin/zsh
# Installs Signal as background services that start at login/boot and restart if they stop:
#   • local.signal.server  – the Signal page, Wi-Fi helper and drop recorder (runs as you)
#   • local.signal.dns     – the network-wide website log on port 53 (needs admin rights)
# Usage:  sudo scripts/install-services.sh            install or update
#         sudo scripts/install-services.sh uninstall  remove both
set -euo pipefail

if [[ $EUID -ne 0 || -z "${SUDO_USER:-}" ]]; then
  echo "Run with sudo from your own account: sudo scripts/install-services.sh"
  exit 1
fi

DIR="$(cd "$(dirname "$0")/.." && pwd)"
USER_NAME="$SUDO_USER"
USER_ID=$(id -u "$USER_NAME")
GROUP_ID=$(id -g "$USER_NAME")
USER_HOME=$(dscl . -read "/Users/$USER_NAME" NFSHomeDirectory | awk '{print $2}')
NODE=$(sudo -u "$USER_NAME" zsh -lc 'command -v node')
DAEMON=/Library/LaunchDaemons/local.signal.dns.plist
AGENT="$USER_HOME/Library/LaunchAgents/local.signal.server.plist"

stop_all() {
  launchctl bootout system "$DAEMON" 2>/dev/null || true
  launchctl bootout "gui/$USER_ID" "$AGENT" 2>/dev/null || true
  pkill -f "$DIR/dns-monitor.js" 2>/dev/null || true
  pkill -f "node dns-monitor.js" 2>/dev/null || true
  pkill -f "node server.js" 2>/dev/null || true
  pkill -f "$DIR/server.js" 2>/dev/null || true
}

if [[ "${1:-}" == "uninstall" ]]; then
  stop_all
  rm -f "$DAEMON" "$AGENT"
  echo "Removed. Remember to set your router's DNS back if you pointed it at this Mac."
  exit 0
fi

stop_all
mkdir -p "$DIR/build" "$USER_HOME/Library/LaunchAgents"
chown "$USER_ID:$GROUP_ID" "$DIR/build"

cat > "$DAEMON" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>local.signal.dns</string>
  <key>ProgramArguments</key><array><string>$NODE</string><string>$DIR/dns-monitor.js</string></array>
  <key>WorkingDirectory</key><string>$DIR</string>
  <key>EnvironmentVariables</key><dict>
    <key>SUDO_UID</key><string>$USER_ID</string>
    <key>SUDO_GID</key><string>$GROUP_ID</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>StandardOutPath</key><string>$DIR/build/dns-monitor.log</string>
  <key>StandardErrorPath</key><string>$DIR/build/dns-monitor.log</string>
</dict></plist>
PLIST
chown root:wheel "$DAEMON"; chmod 644 "$DAEMON"

cat > "$AGENT" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>local.signal.server</string>
  <key>ProgramArguments</key><array><string>$NODE</string><string>$DIR/server.js</string></array>
  <key>WorkingDirectory</key><string>$DIR</string>
  <key>EnvironmentVariables</key><dict><key>PATH</key><string>$(dirname "$NODE"):/usr/bin:/bin:/usr/sbin:/sbin</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>StandardOutPath</key><string>$DIR/build/server.log</string>
  <key>StandardErrorPath</key><string>$DIR/build/server.log</string>
</dict></plist>
PLIST
chown "$USER_ID:$GROUP_ID" "$AGENT"

launchctl bootstrap system "$DAEMON"
launchctl bootstrap "gui/$USER_ID" "$AGENT"
sleep 3
echo
launchctl print system/local.signal.dns >/dev/null 2>&1 && echo "✓ Website log is running in the background (port 53)." || echo "✗ Website log did not start. See $DIR/build/dns-monitor.log"
launchctl print "gui/$USER_ID/local.signal.server" >/dev/null 2>&1 && echo "✓ Signal server is running in the background: http://localhost:8787" || echo "✗ Signal server did not start. See $DIR/build/server.log"
echo "Both start automatically after a restart. To remove: sudo scripts/install-services.sh uninstall"
