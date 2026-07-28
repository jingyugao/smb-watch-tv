#!/usr/bin/env bash
set -euo pipefail

readonly GITEE_OWNER="ggyy00"
readonly GITEE_REPO="smb-watch-tv"
readonly STAGING_ROOT="/srv/smb-watch-tv/incoming"
readonly TOKEN_FILE="${GITEE_TOKEN_FILE:-$HOME/.config/smb-watch-tv/gitee-token}"

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <release-tag> <prerelease> <artifact-directory>" >&2
  exit 2
fi

release_tag="$1"
prerelease="$2"
artifact_directory="$(realpath "$3")"

[[ "$release_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9._-]+)?$ ]]
[[ "$prerelease" == "true" || "$prerelease" == "false" ]]
[[ "$artifact_directory" == "$STAGING_ROOT/"* ]]
[[ -d "$artifact_directory" ]]
[[ -r "$TOKEN_FILE" ]]

apk=("$artifact_directory"/*.apk)
checksum=("$artifact_directory"/*.sha256)
[[ ${#apk[@]} -eq 1 && -f "${apk[0]}" ]]
[[ ${#checksum[@]} -eq 1 && -f "${checksum[0]}" ]]

(
  cd "$artifact_directory"
  sha256sum --check --strict "$(basename "${checksum[0]}")"
)

gitee_token="$(<"$TOKEN_FILE")"
api="https://gitee.com/api/v5/repos/$GITEE_OWNER/$GITEE_REPO"
release="$(
  curl --fail --silent --show-error \
    --connect-timeout 15 \
    --max-time 60 \
    -H "Authorization: token $gitee_token" \
    "$api/releases/tags/$release_tag"
)"

if ! release_id="$(jq -er '.id' <<<"$release")"; then
  payload="$(
    jq -n \
      --arg tag "$release_tag" \
      --argjson prerelease "$prerelease" \
      '{
        tag_name: $tag,
        name: $tag,
        body: "Mirrored from the signed GitHub Actions build.",
        prerelease: $prerelease,
        target_commitish: "master"
      }'
  )"
  release="$(
    curl --fail-with-body --silent --show-error \
      --connect-timeout 15 \
      --max-time 60 \
      -X POST \
      -H "Authorization: token $gitee_token" \
      -H "Content-Type: application/json" \
      --data "$payload" \
      "$api/releases"
  )"
  release_id="$(jq -er '.id' <<<"$release")"
fi

attachments="$(
  curl --fail --silent --show-error \
    --connect-timeout 15 \
    --max-time 60 \
    -H "Authorization: token $gitee_token" \
    "$api/releases/$release_id/attach_files"
)"

for artifact in "${apk[0]}" "${checksum[0]}"; do
  name="$(basename "$artifact")"
  if jq -e --arg name "$name" 'any(.[]; .name == $name)' \
      <<<"$attachments" >/dev/null; then
    continue
  fi
  curl --fail-with-body --silent --show-error \
    --connect-timeout 15 \
    --max-time 180 \
    -X POST \
    -H "Authorization: token $gitee_token" \
    -H "Expect:" \
    -F "file=@$artifact" \
    "$api/releases/$release_id/attach_files" >/dev/null
done

mirror_root="$(mktemp -d)"
trap 'rm -rf "$mirror_root"' EXIT
mirror_repository="$mirror_root/repository"
git init --quiet --initial-branch=update-mirror "$mirror_repository"
git -C "$mirror_repository" remote add origin \
  "https://gitee.com/$GITEE_OWNER/$GITEE_REPO.git"

apk_name="$(basename "${apk[0]}")"
checksum_name="$(basename "${checksum[0]}")"
install -m 0644 "${apk[0]}" "$mirror_repository/$apk_name"
install -m 0644 "${checksum[0]}" "$mirror_repository/$checksum_name"
apk_size="$(stat -c '%s' "${apk[0]}")"

jq -n \
  --arg tag "$release_tag" \
  --arg apk_name "$apk_name" \
  --arg apk_url "https://gitee.com/$GITEE_OWNER/$GITEE_REPO/raw/update-mirror/$apk_name" \
  --arg checksum_name "$checksum_name" \
  --arg checksum_url "https://gitee.com/$GITEE_OWNER/$GITEE_REPO/raw/update-mirror/$checksum_name" \
  --argjson prerelease "$prerelease" \
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
  }]' >"$mirror_repository/releases.json"

git -C "$mirror_repository" config user.name aliyun-release-relay
git -C "$mirror_repository" config user.email release-relay@gaojingyu.top
git -C "$mirror_repository" add --all
git -C "$mirror_repository" commit --quiet -m "Publish $release_tag"
export GITEE_GIT_TOKEN="$gitee_token"
credential_helper='!f() { echo "username=ggyy00"; echo "password=$GITEE_GIT_TOKEN"; }; f'
git -C "$mirror_repository" -c credential.helper="$credential_helper" \
  push --quiet --force origin HEAD:update-mirror
unset GITEE_GIT_TOKEN

find "$artifact_directory" -mindepth 1 -maxdepth 1 -type f -delete
rmdir "$artifact_directory"
