#!/bin/zsh
# Builds the Android app and publishes it as the latest GitHub release, which the
# README links to. Pass --install to also install it on a phone connected over ADB.
set -euo pipefail
cd "$(dirname "$0")/.."

count=$(git rev-list --count HEAD)
version="1.$count"
[[ -d /opt/homebrew/opt/openjdk@17 ]] && export JAVA_HOME=/opt/homebrew/opt/openjdk@17

echo "Building Signal $version…"
(cd android && gradle assembleDebug -q -PsignalVersionCode="$count" -PsignalVersionName="$version")
mkdir -p dist
cp android/app/build/outputs/apk/debug/app-debug.apk dist/Signal.apk

echo "Publishing release android-v$version…"
gh release create "android-v$version" dist/Signal.apk \
  --title "Signal for Android $version" \
  --notes "Built from $(git rev-parse --short HEAD): $(git log -1 --pretty=%s)" \
  --latest

if [[ "${1:-}" == "--install" ]]; then
  adb install -r dist/Signal.apk
fi
echo "Done: https://github.com/ikppramesh/signal/releases/latest/download/Signal.apk"
