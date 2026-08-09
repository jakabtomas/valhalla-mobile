# Valhalla compatibility patches

This directory contains the complete, reviewable delta applied to the pinned
Valhalla submodule before native builds.

`build.sh` applies every patch idempotently. The repository keeps the submodule
itself clean so that a checkout can always be reproduced from the recorded
Valhalla commit and these files alone.

- `valhalla-inspect-decompressed-tile-header.patch` validates the graph-tile
  checksum after decompressing a remotely fetched gzip tile.
- `valhalla-streaming-shortcut-null-safety.patch` treats an unavailable
  shortcut dependency in a partial graph as no shortcut instead of
  dereferencing a missing tile.

Both changes are generic Valhalla correctness fixes. They contain no provider,
application, endpoint, or regional configuration.
