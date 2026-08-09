#!/usr/bin/env bash

set -euo pipefail

release_tag=${1:?"Usage: write_xcframework_spm.sh VERSION [XCFRAMEWORK_ZIP]"}
xcframework_zip=${2:-valhalla-wrapper.xcframework.zip}

if [[ ! "$release_tag" =~ ^[0-9]+\.[0-9]+\.[0-9]+([+-][0-9A-Za-z.-]+)?$ ]]; then
    echo "Release version is not valid semantic versioning: $release_tag"
    exit 1
fi
if [[ ! -f "$xcframework_zip" ]]; then
    echo "XCFramework archive does not exist: $xcframework_zip"
    exit 1
fi

xcframework_checksum=$(swift package compute-checksum "$xcframework_zip")

RELEASE_TAG="$release_tag" \
XCFRAMEWORK_CHECKSUM="$xcframework_checksum" \
perl -0pi -e '
    s/^let version: String = .*$/let version: String = "$ENV{RELEASE_TAG}"/m;
    s/^let binaryChecksum: String = .*$/let binaryChecksum: String = "$ENV{XCFRAMEWORK_CHECKSUM}"/m;
' Package.swift

printf '%s\n' "$release_tag" > version.txt

grep -F "let version: String = \"$release_tag\"" Package.swift >/dev/null
grep -F "let binaryChecksum: String = \"$xcframework_checksum\"" Package.swift >/dev/null

echo "Prepared release $release_tag with SwiftPM checksum $xcframework_checksum."
