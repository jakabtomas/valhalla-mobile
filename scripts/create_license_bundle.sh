#!/usr/bin/env bash

set -euo pipefail

output_directory=${1:?"Usage: create_license_bundle.sh OUTPUT_DIRECTORY [SEARCH_ROOT ...]"}
shift

mkdir -p "$output_directory"
cp LICENSE.md "$output_directory/valhalla-mobile-MIT.md"
cp THIRD_PARTY_NOTICES.md "$output_directory/THIRD_PARTY_NOTICES.md"
cp src/valhalla/COPYING "$output_directory/valhalla-MIT.txt"

for search_root in "$@"; do
    [[ -d "$search_root" ]] || continue
    while IFS= read -r copyright_file; do
        package_name=$(basename "$(dirname "$copyright_file")")
        cp "$copyright_file" "$output_directory/${package_name}-copyright.txt"
    done < <(find "$search_root" -type f -path '*/share/*/copyright' | sort -u)
done

(
    cd "$output_directory"
    find . -type f -print0 | sort -z | xargs -0 shasum -a 256 > SHA256SUMS
)
