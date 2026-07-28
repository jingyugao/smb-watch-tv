#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
version_file="$repo_dir/version.properties"
target_dir="${APK_TARGET_DIR:-/mnt/d/media}"

current_number="$(sed -n 's/^BUILD_NUMBER=//p' "$version_file")"
if [[ ! "$current_number" =~ ^[0-9]+$ ]]; then
    printf 'Invalid BUILD_NUMBER in %s\n' "$version_file" >&2
    exit 1
fi

next_number=$((10#$current_number + 1))
version="$(printf '1.3.%03d' "$next_number")"
apk_name="smb-watch-tv-${version}-debug.apk"

docker run --rm \
    --network host \
    -v "$repo_dir:/workspace" \
    -v smb-watch-tv-gradle:/root/.gradle \
    smb-watch-tv-builder \
    gradle -PbuildNumber="$next_number" assembleDebug

printf 'BUILD_NUMBER=%d\n' "$next_number" > "$version_file.tmp"
mv "$version_file.tmp" "$version_file"

mkdir -p "$target_dir"
find "$target_dir" -maxdepth 1 -type f \
    -name 'smb-watch-tv-*-debug.apk' ! -name "$apk_name" -delete
cp -f "$repo_dir/app/build/outputs/apk/debug/$apk_name" "$target_dir/$apk_name"

printf 'Built %s\nCopied to %s/%s\n' "$version" "$target_dir" "$apk_name"
