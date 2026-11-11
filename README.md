# Signal

A Wi-Fi signal tracker with a bold, animated readout. It shows how strong your connection is, which networks are around you, every device on your network, and how much data goes in and out.

**[Download the Android app (APK)](https://github.com/ikppramesh/signal/releases/latest/download/Signal.apk)** · **[Open the web version](https://ir-wifi-signal.vercel.app)**

The APK link always points to the newest build. Older builds are on the [releases page](https://github.com/ikppramesh/signal/releases).

## What it does

| Tab | Shows |
|---|---|
| **Signal** | Live signal strength as a big percentage, warnings when it gets weak, a star map of nearby networks, network security, router ping and a speed test |
| **Devices** | Every device on the current network: name, type, address and whether it is online |
| **Traffic** | Upload and download for today, the last 24 hours (line chart), the last 14 days and all time, plus which websites each device or app contacts and roughly where those servers are |

What each version can read:

| | Android app | Mac (`node server.js`) | Web page in a browser |
|---|---|---|---|
| Wi-Fi signal and nearby networks | Yes | Yes | Estimated from connection speed only |
| Mobile signal | Yes | No | No |
| Devices on the network | Yes | Yes, with maker names | No |
| Data used | This phone, per app | This Mac | No |
| Website log | This phone, per app | Every device on the network (setup below) | No |
| Drop recorder | No | Yes | No |

Browsers cannot read Wi-Fi details or scan networks, so the web version shows estimates unless it is opened from a Mac running the server.

## Install the Android app

1. On the phone, open the **Download the Android app** link above and install `Signal.apk`. Allow installs from your browser if Android asks.
2. Open Signal and allow **Location** and **Nearby devices**. Android hides network names without them.
3. For data totals, tap **Traffic → Allow usage access** and turn Signal on.
4. For the website log, tap **Traffic → Start** and accept the VPN prompt. Only website lookups pass through it, and the log stays on the phone.

## Run it on a Mac

Requires Node.js and the Xcode command line tools.

```sh
node server.js
```

Open http://localhost:8787 on the Mac, or the address it prints on a phone on the same Wi-Fi. The first run builds a small helper app, and macOS asks for Location access so network names can be shown.

### Website log for the whole network (optional)

```sh
sudo node dns-monitor.js
```

Then set your router's DNS server to the Mac's address and reconnect your devices. Every device's website lookups appear in the Traffic tab. Devices cannot load websites while the Mac is off, so set the router back if you stop using it.

### Keep it running in the background

```sh
sudo scripts/install-services.sh             # install: both start at boot and restart if they stop
sudo scripts/install-services.sh uninstall   # remove
```

This installs the Signal server (as your user) and the website log (on port 53) as macOS background services. The server logs to `build/server.log`. The website log and its records live in `~/Library/Application Support/Signal`, because macOS blocks background services from the Documents folder. Rerun the install command after changing `dns-monitor.js`.

## Release a new APK

```sh
scripts/release-android.sh            # build and publish
scripts/release-android.sh --install  # also install on a phone connected over ADB
```

Each run publishes a new GitHub release, so the download link above picks it up automatically.
