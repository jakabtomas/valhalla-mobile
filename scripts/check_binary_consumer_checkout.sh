#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
temporary_root="$(mktemp -d "${TMPDIR:-/tmp}/valhalla-mobile-consumer.XXXXXX")"
trap 'rm -rf "$temporary_root"' EXIT

consumer_checkout="$temporary_root/repository"
git clone --quiet --no-local "$repo_root" "$consumer_checkout"
git -C "$consumer_checkout" submodule update --init --recursive

if [[ -e "$consumer_checkout/src/valhalla/.git" ]]; then
    echo "Binary consumer checkout unexpectedly initialized src/valhalla."
    exit 1
fi

if find "$consumer_checkout/src/valhalla" -mindepth 1 -print -quit 2>/dev/null | grep -q .; then
    echo "Binary consumer checkout unexpectedly populated src/valhalla."
    exit 1
fi

echo "Binary consumer checkout skipped native Valhalla sources."

