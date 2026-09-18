#!/usr/bin/env bash

set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "Usage: $0 <release-tag> <asset>..." >&2
  exit 2
fi

release_tag="$1"
shift

repository="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
max_attempts=3
upload_timeout_seconds="${GITHUB_RELEASE_UPLOAD_TIMEOUT_SECONDS:-600}"

if ! [[ "$upload_timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "::error::GITHUB_RELEASE_UPLOAD_TIMEOUT_SECONDS must be a positive integer" >&2
  exit 2
fi

release_id="$(gh api "repos/$repository/releases/tags/$release_tag" --jq '.id')"

upload_asset_once() {
  local asset_path="$1"
  local asset_name local_digest assets_json asset_json remote_digest remote_asset_id
  local remote_state upload_status

  asset_name="$(basename "$asset_path")"
  local_digest="sha256:$(sha256sum "$asset_path" | awk '{print $1}')"

  if ! assets_json="$(gh api "repos/$repository/releases/$release_id/assets?per_page=100")"; then
    echo "::warning::Failed to list GitHub Release assets before uploading $asset_name"
    return 1
  fi

  asset_json="$(
    jq -c --arg name "$asset_name" \
      '[.[] | select(.name == $name)][0] // empty' <<<"$assets_json"
  )"

  if [ -n "$asset_json" ]; then
    remote_digest="$(jq -r '.digest // ""' <<<"$asset_json")"
    if [ "$remote_digest" = "$local_digest" ]; then
      echo "GitHub Release asset already matches: $asset_name"
      return 0
    fi

    remote_asset_id="$(jq -r '.id' <<<"$asset_json")"
    remote_state="$(jq -r '.state // ""' <<<"$asset_json")"
    if [ "$remote_state" = "uploaded" ]; then
      echo "Deleting stale GitHub Release asset: $asset_name"
    else
      echo "Deleting incomplete GitHub Release asset: $asset_name ($remote_state)"
    fi
    if ! gh api --method DELETE "repos/$repository/releases/assets/$remote_asset_id"; then
      echo "::warning::Failed to delete existing GitHub Release asset: $asset_name"
      return 1
    fi
  fi

  echo "Uploading GitHub Release asset: $asset_name "\
    "(timeout: ${upload_timeout_seconds}s)"
  upload_status=0
  timeout --signal=TERM --kill-after=30s "${upload_timeout_seconds}s" \
    gh release upload "$release_tag" "$asset_path" --repo "$repository" || \
    upload_status=$?

  if [ "$upload_status" -eq 124 ] || [ "$upload_status" -eq 137 ]; then
    echo "::warning::GitHub Release asset upload timed out: $asset_name "\
      "(${upload_timeout_seconds}s)"
  fi

  return "$upload_status"
}

for asset_path in "$@"; do
  if [ ! -s "$asset_path" ]; then
    echo "::error::GitHub Release asset is missing or empty: $asset_path"
    exit 1
  fi

  asset_name="$(basename "$asset_path")"
  uploaded=false
  for attempt in $(seq 1 "$max_attempts"); do
    if upload_asset_once "$asset_path"; then
      uploaded=true
      break
    fi

    if [ "$attempt" -lt "$max_attempts" ]; then
      delay_seconds=$((attempt * 10))
      echo "::warning::GitHub Release asset upload failed: $asset_name "\
        "(attempt $attempt/$max_attempts); retrying in ${delay_seconds}s"
      sleep "$delay_seconds"
    fi
  done

  if [ "$uploaded" != true ]; then
    echo "::error::GitHub Release asset upload failed after $max_attempts attempts: "\
      "$asset_name"
    exit 1
  fi
done
