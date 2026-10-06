#!/bin/sh
#
# Builds CodeWitness and installs the runnable jar.
#
# Prefers the project's Maven wrapper when one is present, so that contributors get the pinned
# Maven version, and falls back to whatever `mvn` is on the PATH.

set -eu

SELF_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SELF_DIR"

if [ -x "./mvnw" ]; then
  MVN="./mvnw"
elif [ -n "${MAVEN_HOME:-}" ] && [ -x "$MAVEN_HOME/bin/mvn" ]; then
  MVN="$MAVEN_HOME/bin/mvn"
else
  MVN=mvn
fi

echo "Building CodeWitness with: $MVN"
"$MVN" -B "$@" package

JAR="codewitness-cli/target/codewitness.jar"
if [ ! -f "$JAR" ]; then
  echo "build.sh: expected $JAR to exist after the build" >&2
  exit 1
fi
echo
echo "Built $JAR"
echo "Run it with:  ./codewitness version"
