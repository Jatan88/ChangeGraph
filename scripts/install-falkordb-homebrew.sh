#!/usr/bin/env bash
# Install FalkorDB as a Redis 8 module (no Docker). See backend/src/main/resources/application.properties.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODULE_DIR="$ROOT/local/falkordb"
VERSION="${FALKORDB_VERSION:-v4.22.1}"

arch="${FALKORDB_ARCH:-$(uname -m)}"
if [[ "$arch" == "x86_64" ]] && [[ "$(sysctl -n hw.optional.arm64 2>/dev/null || echo 0)" == "1" ]]; then
  arch="arm64"
fi
case "$arch" in
  arm64) ASSET="falkordb-macos-arm64v8.so" ;;
  x86_64)
    echo "No official macOS Intel module in release $VERSION."
    echo "Use FalkorDB Cloud (https://docs.falkordb.com/) or build FalkorDB from source."
    exit 1
    ;;
  *) echo "Unsupported architecture: $arch"; exit 1 ;;
esac

URL="https://github.com/FalkorDB/FalkorDB/releases/download/${VERSION}/${ASSET}"
MODULE_PATH="$MODULE_DIR/falkordb.dylib"

mkdir -p "$MODULE_DIR"
echo "Downloading FalkorDB module ($VERSION)..."
curl -fsSL "$URL" -o "$MODULE_PATH"
chmod 755 "$MODULE_PATH"

if ! brew list libomp &>/dev/null 2>&1; then
  echo "If the module fails to load, install OpenMP: brew install libomp"
fi

REDIS_CONF="${REDIS_CONF:-$(brew --prefix)/etc/redis.conf}"
LOAD_LINE="loadmodule $MODULE_PATH"

if [[ -f "$REDIS_CONF" ]] && grep -Fq "$MODULE_PATH" "$REDIS_CONF"; then
  echo "redis.conf already loads $MODULE_PATH"
else
  echo ""
  echo "Add this line to $REDIS_CONF (or an included file):"
  echo "  $LOAD_LINE"
  echo ""
  echo "Then restart Redis:"
  echo "  brew services restart redis"
fi

echo ""
echo "Verify:"
echo "  redis-cli MODULE LIST | grep -i falkor"
echo "  redis-cli GRAPH.QUERY changegraph \"RETURN 1 AS ok\""
