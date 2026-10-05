// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "PullUpCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "PullUpCore", targets: ["PullUpCore"])],
    targets: [
        .target(name: "PullUpCore"),
        .testTarget(name: "PullUpCoreTests", dependencies: ["PullUpCore"]),
    ]
)
