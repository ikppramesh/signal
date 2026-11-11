// Background helper: reads the Wi-Fi radio through CoreWLAN and writes JSON for server.js.
// It is an app bundle because macOS only reveals network names to apps granted Location access.
import AppKit
import CoreLocation
import CoreWLAN

let outPath = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "/tmp/signal-wifi.json"
let alivePath = outPath + ".alive"

func bandName(_ c: CWChannel?) -> String {
    switch c?.channelBand {
    case .band2GHz?: return "2.4 GHz"
    case .band5GHz?: return "5 GHz"
    case .band6GHz?: return "6 GHz"
    default: return ""
    }
}

func widthMHz(_ c: CWChannel?) -> Int {
    switch c?.channelWidth {
    case .width20MHz?: return 20
    case .width40MHz?: return 40
    case .width80MHz?: return 80
    case .width160MHz?: return 160
    default: return 0
    }
}

final class Helper: NSObject, CLLocationManagerDelegate {
    let location = CLLocationManager()
    let queue = DispatchQueue(label: "scan")
    var nearby: [[String: Any]] = []
    var lastScan = Date.distantPast
    var scanning = false
    var scanError = ""

    func start() {
        location.delegate = self
        location.requestWhenInUseAuthorization()
        Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
        tick()
    }

    var authorized: Bool {
        let s = location.authorizationStatus
        return s == .authorizedAlways || s == .authorized
    }

    func scanIfDue(_ wifi: CWInterface) {
        // Scanning briefly takes the radio off-channel, so it runs far less often than the signal read.
        guard !scanning, Date().timeIntervalSince(lastScan) > 15 else { return }
        scanning = true
        queue.async {
            var found = Set<CWNetwork>()
            var failure = ""
            do { found = try wifi.scanForNetworks(withName: nil) } catch { failure = "\(error)" }
            let rows: [[String: Any]] = found.map { n in
                [
                    "ssid": n.ssid ?? NSNull(),
                    "rssi": n.rssiValue,
                    "channel": n.wlanChannel?.channelNumber ?? 0,
                    "band": bandName(n.wlanChannel),
                    "width": widthMHz(n.wlanChannel),
                ]
            }
            DispatchQueue.main.async {
                if !rows.isEmpty { self.nearby = rows }
                self.scanError = failure
                self.lastScan = Date()
                self.scanning = false
            }
        }
    }

    func tick() {
        // Stop if the server has gone away, so the helper never lingers.
        let attrs = try? FileManager.default.attributesOfItem(atPath: alivePath)
        if let m = attrs?[.modificationDate] as? Date, Date().timeIntervalSince(m) > 30 { exit(0) }

        guard let wifi = CWWiFiClient.shared().interface() else { return }
        let channel = wifi.wlanChannel()
        let connected = wifi.powerOn() && channel != nil && wifi.rssiValue() != 0
        if wifi.powerOn() { scanIfDue(wifi) }

        let payload: [String: Any] = [
            "time": Date().timeIntervalSince1970,
            "powerOn": wifi.powerOn(),
            "connected": connected,
            "ssid": wifi.ssid() ?? NSNull(),
            "rssi": wifi.rssiValue(),
            "noise": wifi.noiseMeasurement(),
            "txRate": wifi.transmitRate(),
            "channel": channel?.channelNumber ?? 0,
            "band": bandName(channel),
            "width": widthMHz(channel),
            "interface": wifi.interfaceName ?? "en0",
            "namesAvailable": authorized,
            "nearby": nearby,
            "scanError": scanError,
        ]
        if let data = try? JSONSerialization.data(withJSONObject: payload) {
            try? data.write(to: URL(fileURLWithPath: outPath), options: .atomic)
        }
    }
}

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let helper = Helper()
helper.start()
app.run()
