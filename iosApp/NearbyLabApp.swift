import NearbyShared
import SwiftUI

@main
struct NearbyLabApp: App {
  private let transport: AppleNearbyTransport
  init() {
    let transport = AppleNearbyTransport()
    self.transport = transport
    LabViewModelKt.initializeLab(transport: transport)
  }
  var body: some Scene { WindowGroup { LabView().ignoresSafeArea() } }
}
private struct LabView: UIViewControllerRepresentable {
  func makeUIViewController(context: Context) -> UIViewController {
    MainViewControllerKt.MainViewController()
  }
  func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
