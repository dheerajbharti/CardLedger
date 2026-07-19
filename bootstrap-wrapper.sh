#!/usr/bin/env sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_DIR="$PROJECT_ROOT/gradle/wrapper"
JAR_PATH="$WRAPPER_DIR/gradle-wrapper.jar"
URL="https://raw.githubusercontent.com/gradle/gradle/v8.9.0/gradle/wrapper/gradle-wrapper.jar"
EXPECTED_SHA256="497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7"

mkdir -p "$WRAPPER_DIR"
printf '%s\n' "Downloading the official Gradle 8.9 wrapper JAR..."
if command -v curl >/dev/null 2>&1; then
  curl -fL "$URL" -o "$JAR_PATH"
elif command -v wget >/dev/null 2>&1; then
  wget -O "$JAR_PATH" "$URL"
else
  printf '%s\n' "Install curl or wget, then rerun this script." >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL_SHA256=$(sha256sum "$JAR_PATH" | awk '{print $1}')
elif command -v shasum >/dev/null 2>&1; then
  ACTUAL_SHA256=$(shasum -a 256 "$JAR_PATH" | awk '{print $1}')
else
  printf '%s\n' "No SHA-256 utility found; refusing to use an unverified wrapper JAR." >&2
  rm -f "$JAR_PATH"
  exit 1
fi

if [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
  rm -f "$JAR_PATH"
  printf '%s\n' "Checksum mismatch. Expected $EXPECTED_SHA256 but received $ACTUAL_SHA256." >&2
  exit 1
fi

printf '%s\n' "Gradle wrapper installed and verified: $JAR_PATH"
