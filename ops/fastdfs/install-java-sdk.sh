#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$script_dir/versions.env"
build_dir=$(mktemp -d)
trap 'rm -rf -- "$build_dir"' EXIT

curl -fsSL --retry 3 --connect-timeout 15 \
    "https://codeload.github.com/happyfish100/fastdfs-client-java/tar.gz/$FASTDFS_JAVA_REVISION" \
    -o "$build_dir/sdk.tar.gz"
tar -xzf "$build_dir/sdk.tar.gz" -C "$build_dir" --strip-components=1
sed -i "s|<version>1.40-SNAPSHOT</version>|<version>$FASTDFS_JAVA_VERSION</version>|" "$build_dir/pom.xml"
grep -Fq "<version>$FASTDFS_JAVA_VERSION</version>" "$build_dir/pom.xml"
cd "$build_dir"
mvn -B -ntp -Djdk.version=8 -Dmaven.test.skip=true install
