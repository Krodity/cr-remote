// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "CRRemote",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
    ],
    products: [
        // xtool wants exactly one library product: the app itself.
        .library(name: "CRRemote", targets: ["CRRemote"]),
    ],
    targets: [
        .target(name: "CRRemote"),
    ]
)
