#!/usr/bin/env bash
set -euo pipefail

DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if [ ! -x "$DIR/runtime/bin/java" ]; then
    echo "[UCL] 首次启动：解压 Java 运行时..."
    mkdir -p "$DIR/runtime"
    tar -xzf "$DIR/jre-linux-x64.tar.gz" -C "$DIR/runtime" --strip-components=1
fi

export JAVA_HOME="$DIR/runtime"
export UCL_DATA_DIR="$DIR/data"
export UCL_GAME_DIR="$DIR/minecraft"
export PATH="$JAVA_HOME/bin:$PATH"

exec "$JAVA_HOME/bin/java" \
    -Dfile.encoding=UTF-8 \
    -cp "$DIR/app/UnionCraftLauncher.jar" \
    org.unioncraft.launcher.Main "$@"
