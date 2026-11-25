#!/bin/bash
# Compile l'app Android Arche. Sortie : dist/arche-<version>.apk
# Usage : ./build.sh [--bump]     (--bump incrémente la version avant de compiler)
set -euo pipefail
cd "$(dirname "$0")"
GRADLE="${GRADLE:-gradle}"

if [ "${1:-}" = "--bump" ]; then
  code=$(grep ^code= version.properties | cut -d= -f2); name=$(grep ^name= version.properties | cut -d= -f2)
  new=$((code+1)); IFS=. read -r M m p <<< "$name"; newname="$M.$m.$((p+1))"
  sed -i "s/^code=.*/code=$new/; s/^name=.*/name=$newname/" version.properties
fi

"$GRADLE" --console=plain -q assembleRelease

APK=app/build/outputs/apk/release/app-release.apk
if [ ! -f "$APK" ]; then
  echo "APK signée introuvable : crée signing.properties (voir README)." >&2; exit 1
fi
name=$(grep ^name= version.properties | cut -d= -f2)
mkdir -p dist
cp "$APK" "dist/arche-$name.apk"
sha256sum "dist/arche-$name.apk"
