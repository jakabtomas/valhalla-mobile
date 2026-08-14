#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"

# Binary SwiftPM/Android consumers must not fetch the native source tree. Source
# builds opt in explicitly and override the consumer-safe `update = none` rule.
git -C "$repo_root" submodule update --init --recursive --checkout src/valhalla

