#!/bin/bash

set -e

DIR=app/src/main/assets/sing-box
rm -rf $DIR
mkdir -p $DIR
cd $DIR

get_latest_release() {
  local repo="$1"
  local tag=""
  local auth_args=()

  if [ -n "$GITHUB_TOKEN" ]; then
    auth_args=(-H "Authorization: Bearer $GITHUB_TOKEN")
  fi

  # 1. Try GitHub API
  tag=$(curl --silent "${auth_args[@]}" "https://api.github.com/repos/$repo/releases/latest" 2>/dev/null | \
    grep '"tag_name":' | \
    sed -E 's/.*"([^"]+)".*/\1/' | head -n 1 || true)

  # 2. Fallback to redirect Location header from releases/latest (avoids API rate limits)
  if [ -z "$tag" ]; then
    tag=$(curl -sI "https://github.com/$repo/releases/latest" 2>/dev/null | tr -d '\r' | awk -F'/' '/^[Ll]ocation:/ {print $NF}' | head -n 1 || true)
  fi

  if [ -z "$tag" ]; then
    echo "Error: Failed to determine latest release tag for $repo" >&2
    exit 1
  fi

  echo "$tag"
}

####
VERSION_GEOIP=$(get_latest_release "SagerNet/sing-geoip")
echo "VERSION_GEOIP=$VERSION_GEOIP"
echo -n "$VERSION_GEOIP" > geoip.version.txt
curl -fLSs --retry 3 --retry-delay 2 -O "https://github.com/SagerNet/sing-geoip/releases/download/$VERSION_GEOIP/geoip.db"
xz -9 geoip.db

####
VERSION_GEOSITE=$(get_latest_release "SagerNet/sing-geosite")
echo "VERSION_GEOSITE=$VERSION_GEOSITE"
echo -n "$VERSION_GEOSITE" > geosite.version.txt
curl -fLSs --retry 3 --retry-delay 2 -O "https://github.com/SagerNet/sing-geosite/releases/download/$VERSION_GEOSITE/geosite.db"
xz -9 geosite.db
