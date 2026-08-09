# Third-party notices

Valhalla Mobile is distributed under the MIT License in `LICENSE.md`.
Its native binaries include or link third-party software whose own license
terms continue to apply.

The release workflow packages the license files installed by vcpkg alongside
the Valhalla `COPYING` file. Consumers redistributing the binary artifacts
should include that generated license bundle with their application notices.

Direct native dependencies include Valhalla, Boost, Protocol Buffers,
RapidJSON, LZ4, Abseil, robin-hood-hashing, and unordered_dense. Platform
source dependencies and their versions are declared in `Package.swift` and
`android/gradle/libs.versions.toml`.

The routing fixtures under the Apple and Android test directories are derived
from OpenStreetMap data for Andorra. © OpenStreetMap contributors; data is
available under the Open Database License.
