import CoreLocation
import Foundation
import NearbyConnections
import NearbyShared
import UIKit

final class AppleNearbyTransport: NSObject, NearbyTransport, ConnectionManagerDelegate,
  AdvertiserDelegate, DiscovererDelegate
{
  private let manager = ConnectionManager(serviceID: "org.bigmeco.nearbylab.v1", strategy: .star)
  private lazy var advertiser = Advertiser(connectionManager: manager)
  private lazy var discoverer = Discoverer(connectionManager: manager)
  private let location = CLLocationManager()
  #if NEARBY_PERSONAL_TEAM
    private let mediums: Set<Medium> = [.ble, .wifiLAN]
    private let profile =
      "Personal Team; requested mediums=BLE,WiFiLAN; no hotspot or Wi-Fi-info entitlement"
  #else
    private let mediums: Set<Medium> = [
      .bluetooth, .ble, .webRTC, .wifiLAN, .wifiAware, .wifiHotspot, .wifiDirect, .awdl,
    ]
    private let profile = "Full capabilities; SDK default mediums"
  #endif
  private var observer: TransportObserver?
  private var name = Data()
  private var verifications: [String: (Bool) -> Void] = [:]
  private var endpoints = Set<String>()
  private let logURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    .appendingPathComponent("nearby-\(Int(Date().timeIntervalSince1970)).ndjson")
  override init() {
    super.init()
    manager.delegate = self
    manager.enableBLEV2()
    advertiser.delegate = self
    discoverer.delegate = self
  }
  func setObserver(observer: TransportObserver) {
    self.observer = observer
    event(
      "environment",
      value:
        "\(UIDevice.current.model); iOS \(UIDevice.current.systemVersion); Nearby a95eba75022cc1d00f0898bbde5e6ad03ee71ba2; BLEV2 enabled; \(profile)"
    )
  }
  private func event(_ kind: String, _ id: String = "", value: String = "") {
    if Thread.isMainThread {
      observer?.onEvent(kind: kind, endpointId: id, value: value)
    } else {
      DispatchQueue.main.async { [weak self] in self?.event(kind, id, value: value) }
    }
  }
  private func completion(_ op: String, _ id: String = "") -> (Error?) -> Void {
    { [weak self] error in
      self?.event(
        error == nil ? "info" : "error", id,
        value: "\(op): \(error.map(String.init(describing:)) ?? "ok")")
    }
  }
  func host(name: String) {
    UIApplication.shared.isIdleTimerDisabled = true
    self.name = Data(name.utf8)
    #if !NEARBY_PERSONAL_TEAM
      location.requestWhenInUseAuthorization()
    #endif
    advertiser.startAdvertising(
      using: self.name, mediums: mediums, completionHandler: completion("advertising_started"))
  }
  func discover(name: String) {
    UIApplication.shared.isIdleTimerDisabled = true
    self.name = Data(name.utf8)
    #if !NEARBY_PERSONAL_TEAM
      location.requestWhenInUseAuthorization()
    #endif
    discoverer.startDiscovery(mediums: mediums, completionHandler: completion("discovery_started"))
  }
  func connect(endpointId: String) {
    endpoints.insert(endpointId)
    discoverer.requestConnection(to: endpointId, using: name, mediums: mediums) {
      [weak self] error in
      self?.completion("connect", endpointId)(error)
      if error != nil { self?.event("disconnected", endpointId) }
    }
  }
  func verify(endpointId: String, accept: Bool) {
    verifications.removeValue(forKey: endpointId)?(accept)
  }
  func send(endpointId: String, message: String) {
    _ = manager.send(
      Data(message.utf8), to: [endpointId], completionHandler: completion("send", endpointId))
  }
  func disconnect(endpointId: String) {
    verifications.removeValue(forKey: endpointId)?(false)
    manager.disconnect(from: endpointId, completionHandler: completion("disconnect", endpointId))
    endpoints.remove(endpointId)
  }
  func stop() {
    UIApplication.shared.isIdleTimerDisabled = false
    advertiser.stopAdvertising(completionHandler: completion("advertising_stopped"))
    discoverer.stopDiscovery(completionHandler: completion("discovery_stopped"))
    let handlers = Array(verifications.values)
    verifications.removeAll()
    handlers.forEach { $0(false) }
    let ids = endpoints
    endpoints.removeAll()
    ids.forEach { manager.disconnect(from: $0, completionHandler: completion("disconnect", $0)) }
  }
  func appendLog(entry: String) {
    do {
      if !FileManager.default.fileExists(atPath: logURL.path) {
        FileManager.default.createFile(atPath: logURL.path, contents: nil)
      }
      let handle = try FileHandle(forWritingTo: logURL)
      defer { try? handle.close() }
      if try handle.seekToEnd() < 10_000_000 {
        let utc = ISO8601DateFormatter().string(from: Date())
        try handle.write(contentsOf: Data("{\"utc\":\"\(utc)\",\"event\":\(entry)}\n".utf8))
      }
    } catch { NSLog("NearbyLab log write: %@", error.localizedDescription) }
  }
  func shareLog() {
    guard
      let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first,
      let root = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController
    else { return }
    var presenter = root
    while let presented = presenter.presentedViewController { presenter = presented }
    let share = UIActivityViewController(activityItems: [logURL], applicationActivities: nil)
    share.popoverPresentationController?.sourceView = presenter.view
    share.popoverPresentationController?.sourceRect = CGRect(
      x: presenter.view.bounds.midX, y: presenter.view.bounds.midY, width: 1, height: 1)
    presenter.present(share, animated: true)
  }
  func advertiser(
    _ advertiser: Advertiser, didReceiveConnectionRequestFrom endpointID: EndpointID,
    with context: Data, connectionRequestHandler: @escaping (Bool) -> Void
  ) {
    let label = String(data: context, encoding: .utf8) ?? ""
    guard label.hasPrefix("NearbyLab|") else {
      connectionRequestHandler(false)
      return
    }
    endpoints.insert(endpointID)
    event("name", endpointID, value: label)
    connectionRequestHandler(true)  // Transport handshake only; verification below requires user action.
  }
  func discoverer(_ discoverer: Discoverer, didFind endpointID: EndpointID, with context: Data) {
    event("found", endpointID, value: String(data: context, encoding: .utf8) ?? "")
  }
  func discoverer(_ discoverer: Discoverer, didLose endpointID: EndpointID) {
    event("lost", endpointID)
  }
  func connectionManager(
    _ connectionManager: ConnectionManager, didReceive verificationCode: String,
    from endpointID: EndpointID, verificationHandler: @escaping (Bool) -> Void
  ) {
    endpoints.insert(endpointID)
    verifications[endpointID] = verificationHandler
    event("verify", endpointID, value: verificationCode)
  }
  func connectionManager(
    _ connectionManager: ConnectionManager, didChangeTo state: ConnectionState,
    for endpointID: EndpointID
  ) {
    switch state {
    case .connecting: break
    case .connected:
      verifications.removeValue(forKey: endpointID)
      endpoints.insert(endpointID)
      event("connected", endpointID)
    case .disconnected:
      verifications.removeValue(forKey: endpointID)
      endpoints.remove(endpointID)
      event("disconnected", endpointID)
    case .rejected:
      verifications.removeValue(forKey: endpointID)
      endpoints.remove(endpointID)
      event("rejected", endpointID)
    }
  }
  func connectionManager(
    _ connectionManager: ConnectionManager, didReceive data: Data, withID payloadID: PayloadID,
    from endpointID: EndpointID
  ) {
    guard let text = String(data: data, encoding: .utf8) else {
      event("error", endpointID, value: "Invalid UTF-8")
      return
    }
    event("bytes", endpointID, value: text)
  }
  func connectionManager(
    _ connectionManager: ConnectionManager, didReceive stream: InputStream,
    withID payloadID: PayloadID, from endpointID: EndpointID,
    cancellationToken token: CancellationToken
  ) {
    token.cancel()
    event("error", endpointID, value: "Unexpected stream")
  }
  func connectionManager(
    _ connectionManager: ConnectionManager, didStartReceivingResourceWithID payloadID: PayloadID,
    from endpointID: EndpointID, at localURL: URL, withName name: String,
    cancellationToken token: CancellationToken
  ) {
    token.cancel()
    event("error", endpointID, value: "Unexpected file")
  }
  func connectionManager(
    _ connectionManager: ConnectionManager, didReceiveTransferUpdate update: TransferUpdate,
    from endpointID: EndpointID, forPayload payloadID: PayloadID
  ) {
    if case .failure = update {
      event("error", endpointID, value: "transfer \(payloadID): \(update)")
    } else {
      event("transfer", endpointID, value: "\(payloadID): \(update)")
    }
  }
}
