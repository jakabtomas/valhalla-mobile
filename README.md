# Valhalla Mobile

[![Valhalla](https://img.shields.io/badge/Valhalla-3.6.3-blue)](https://github.com/valhalla/valhalla/releases/tag/3.6.3)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE.md)

This project builds [Valhalla](https://github.com/valhalla/valhalla) as a static iOS or
shared Android library.

It exposes route generation over local graph tiles and over application-provided graph
transports. A consumer can inject memory, disk, offline-package, HTTP, authenticated,
or multi-tier cache implementations without placing application configuration in this
library.

We welcome contributions to expand the functionality of this library. See our [CONTRIBUTING.md](CONTRIBUTING.md)
for more information.
If you've got questions, would like to have informal discussions, or just want to ping us about a question, PR. Feel free 
to reach out on the OpenStreetMap Slack (osmus.slack.com) under the [#valhalla-mobile](`https://osmus.slack.com/archives/C08N6SUNZTJ`) channel.

## Setup

### Android

Release AARs are public GitHub release assets. Add the artifact-only Ivy repository in
`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        ivy {
            name = "ValhallaMobileReleases"
            url = uri(
                "https://github.com/jakabtomas/valhalla-mobile/releases/download"
            )
            patternLayout {
                artifact("[revision]/[artifact]-[revision].[ext]")
            }
            metadataSources { artifact() }
            content {
                includeModule("io.github.jakabtomas", "valhalla-mobile")
            }
        }
    }
}
```

Then add the immutable release:

```kotlin
implementation("io.github.jakabtomas:valhalla-mobile:0.6.0@aar")
implementation("io.github.rallista:valhalla-models:0.2.0")
implementation("io.github.rallista:valhalla-models-config:0.2.0")
```

### iOS

In a swift package:

```swift
let package = Package(
    dependencies: [
        .package(
            url: "https://github.com/jakabtomas/valhalla-mobile.git",
            exact: "0.6.0"
        ),
    ],
    targets: [
        .target(
            dependencies: [
                .product(name: "Valhalla", package: "valhalla-mobile")
            ]
        ),
    ]
)
```

## Injecting graph transport

The library owns the Valhalla bridge, not an application's network or storage policy.
Android applications implement `ValhallaHttpClient`; Apple applications implement
`ValhallaHTTPClient`. Both interfaces receive synchronous `GET` and `HEAD` operations,
including exact byte ranges used by indexed tar archives.

```kotlin
val engine = ValhallaActor(configPath, applicationTileProvider)
val response = engine.route(requestJson)
```

```swift
let engine = try Valhalla(config, httpClient: applicationTileProvider)
let response = engine.route(rawRequest: requestJSON)
```

Provider URLs and Valhalla configuration are consumer data. The library contains no
service endpoint, credentials, cache policy, region catalog, or application-specific
fallback behavior.

## Manually building Valhalla C++

Fetching submodules

```sh
git submodule update --init --recursive
```

Set up VCPKG

```sh
git clone https://github.com/microsoft/vcpkg && git -C vcpkg checkout 2025.12.12
./vcpkg/bootstrap-vcpkg.sh
export VCPKG_ROOT=`pwd`/vcpkg
```

### iOS Swift package

On iOS, you must pre-build the xcframework using the command:

```sh
./build.sh ios clean
```

### Android

**Prerequisites:** See [development.md](docs/development.md), specifically 
setting up NDK `29.0.14206865` to match CI.

Build every Android architecture explicitly with either command:

```sh
./build.sh android clean
# or
cd android && ./gradlew buildValhallaAll
```

Native compilation is deliberately not attached to every Gradle `preBuild` invocation;
unit tests and source-only checks therefore stay fast and deterministic.

## Valhalla compatibility patches

The repository pins the official Valhalla submodule. Small compatibility changes live as
reviewable files under `patches/` and are applied idempotently by `build.sh`; the
submodule itself remains clean.

Run the generic-library boundary check before committing:

```sh
./scripts/check_genericity.sh
```

## References

- Valhalla <https://github.com/valhalla/valhalla>
- Swift Package Manager C++ (for fun - this repo takes the old approach) <https://www.swift.org/documentation/articles/wrapping-c-cpp-library-in-swift.html>
