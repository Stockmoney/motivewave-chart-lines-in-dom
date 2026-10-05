#!/usr/bin/env bash
# Builds build/DomLines.jar from the sources and runs the tests.
#
#   bash build.sh            build + tests
#   bash build.sh install    build + tests, then copy into your "MotiveWave Extensions" folder
#
# Needs a JDK (17+; compiled for Java 21 bytecode), the MotiveWave SDK jar and the JavaFX jars of your
# MotiveWave installation (none of them is part of this project). Override with environment variables:
#   MW_SDK   path to mwave_sdk.jar   (default: macOS app bundle)
#   MW_EXT   extensions folder       (default: ~/MotiveWave Extensions)
#   JAVA_HOME
set -euo pipefail
cd "$(dirname "$0")"

SDK="${MW_SDK:-/Applications/MotiveWave.app/Contents/Java/mwave_sdk.jar}"
EXT="${MW_EXT:-$HOME/MotiveWave Extensions}"

JAVAC=""; JAR=""; JAVA=""
candidates=()
[[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME/bin")
if [[ -x /usr/libexec/java_home ]]; then
  jh="$(/usr/libexec/java_home 2>/dev/null || true)"; [[ -n "$jh" ]] && candidates+=("$jh/bin")
fi
candidates+=(/opt/homebrew/opt/openjdk/bin /usr/local/opt/openjdk/bin)
if command -v javac >/dev/null 2>&1; then candidates+=("$(dirname "$(command -v javac)")"); fi
for d in "${candidates[@]}"; do
  if [[ -x "$d/javac" && -x "$d/jar" && -x "$d/java" ]] && "$d/javac" -version >/dev/null 2>&1; then
    JAVAC="$d/javac"; JAR="$d/jar"; JAVA="$d/java"; break
  fi
done
[[ -n "$JAVAC" ]] || { echo "No working JDK found. Install one (e.g. 'brew install openjdk') or set JAVA_HOME." >&2; exit 1; }
[[ -f "$SDK" ]] || { echo "MotiveWave SDK jar not found: $SDK (set MW_SDK)" >&2; exit 1; }

rm -rf build && mkdir -p build/classes/dom_lines/nls build/test-classes

FX="$(dirname "$SDK")/../javafx"
CP="$SDK"; for j in "$FX"/javafx.*.jar; do [[ -f "$j" ]] && CP="$CP:$j"; done
# shellcheck disable=SC2046
"$JAVAC" --release 21 -encoding UTF-8 -Xlint:all,-auxiliaryclass -cp "$CP" -d build/classes $(find dom_lines -name '*.java')
cp dom_lines/nls/strings.properties build/classes/dom_lines/nls/

# shellcheck disable=SC2046
"$JAVAC" --release 21 -encoding UTF-8 -Xlint:all,-auxiliaryclass -cp "build/classes:$SDK" -d build/test-classes $(find test -name '*.java')
# the SDK's own classes call into MotiveWave.jar (and JavaFX), so the tests run with the platform on the class path
TCP="build/classes:build/test-classes"
for j in "$(dirname "$SDK")"/*.jar "$FX"/javafx.*.jar; do [[ -f "$j" ]] && TCP="$TCP:$j"; done
"$JAVA" --enable-native-access=ALL-UNNAMED -cp "$TCP" dom_lines.Tests 2>&1 | grep -v "time zone ID"

"$JAR" cf build/DomLines.jar -C build/classes .
echo "built build/DomLines.jar"

if [[ "${1:-}" == "install" ]]; then
  mkdir -p "$EXT"
  # A new file name each time: a running MotiveWave caches an open jar by path.
  rm -f "$EXT"/DomLines*.jar
  cp build/DomLines.jar "$EXT/DomLines-$(date +%s).jar"
  touch "$EXT/.last_updated"   # MotiveWave rescans the folder when this file changes
  echo "installed into: $EXT"
fi
