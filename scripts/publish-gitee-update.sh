#!/usr/bin/env bash
set -euo pipefail

: "${GITEE_ACCESS_TOKEN:?GITEE_ACCESS_TOKEN is required}"
: "${GITEE_OWNER:?GITEE_OWNER is required}"
: "${GITEE_REPO:?GITEE_REPO is required}"
: "${RELEASE_TAG:?RELEASE_TAG is required}"
: "${RELEASE_VERSION:?RELEASE_VERSION is required}"
: "${PRERELEASE:?PRERELEASE is required}"
: "${ARTIFACT_DIR:?ARTIFACT_DIR is required}"

apk=("$ARTIFACT_DIR"/*.apk)
checksum="$ARTIFACT_DIR/smb-watch-tv-$RELEASE_VERSION.sha256"
[[ -f "${apk[0]}" ]]
[[ -f "$checksum" ]]

mirror_root="$(mktemp -d)"
trap 'rm -rf "$mirror_root"' EXIT
repository="$mirror_root/repository"
repository_url="https://gitee.com/$GITEE_OWNER/$GITEE_REPO.git"
credential_helper='!f() { echo "username=$GITEE_OWNER"; echo "password=$GITEE_ACCESS_TOKEN"; }; f'

if ! git -c credential.helper="$credential_helper" clone \
    --depth 1 --branch update-mirror "$repository_url" "$repository"; then
  git -c credential.helper="$credential_helper" clone \
    --depth 1 "$repository_url" "$repository"
  git -C "$repository" checkout --orphan update-mirror
  git -C "$repository" rm -rf --ignore-unmatch .
fi

find "$repository" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf -- {} +

apk_name="$(basename "${apk[0]}")"
checksum_name="$(basename "$checksum")"
install -m 0644 "${apk[0]}" "$repository/$apk_name"
install -m 0644 "$checksum" "$repository/$checksum_name"
apk_size="$(stat -c '%s' "${apk[0]}")"

jq -n \
  --arg tag "$RELEASE_TAG" \
  --arg apk_name "$apk_name" \
  --arg apk_url "https://gitee.com/$GITEE_OWNER/$GITEE_REPO/raw/update-mirror/$apk_name" \
  --arg checksum_name "$checksum_name" \
  --arg checksum_url "https://gitee.com/$GITEE_OWNER/$GITEE_REPO/raw/update-mirror/$checksum_name" \
  --argjson prerelease "$PRERELEASE" \
  --argjson apk_size "$apk_size" \
  '[{
    tag_name: $tag,
    draft: false,
    prerelease: $prerelease,
    assets: [
      {
        name: $apk_name,
        browser_download_url: $apk_url,
        size: $apk_size
      },
      {
        name: $checksum_name,
        browser_download_url: $checksum_url
      }
    ]
  }]' >"$repository/releases.json"

git -C "$repository" config user.name github-actions
git -C "$repository" config user.email github-actions@github.com
git -C "$repository" add --all
git -C "$repository" commit -m "Publish $RELEASE_TAG"
git -C "$repository" -c credential.helper="$credential_helper" \
  push origin HEAD:update-mirror
