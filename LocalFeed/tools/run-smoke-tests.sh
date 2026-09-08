#!/usr/bin/env bash
set -euo pipefail
KOTLIN_DIR=$(mktemp -d)
trap 'rm -rf "$KOTLIN_DIR"' EXIT
CORE=app/src/main/java/com/localfeed/app/core
kotlinc "$CORE/MediaRecord.kt" "$CORE/WeightedFeedEngine.kt" tools/FeedEngineSmokeTest.kt -include-runtime -d "$KOTLIN_DIR/feed.jar"
java -jar "$KOTLIN_DIR/feed.jar"
kotlinc "$CORE/MediaRecord.kt" "$CORE/WeightedFeedEngine.kt" app/src/main/java/com/localfeed/app/feed/FeedSession.kt tools/FeedSessionStateTest.kt -include-runtime -d "$KOTLIN_DIR/session.jar"
java -jar "$KOTLIN_DIR/session.jar"
kotlinc "$CORE/MediaRecord.kt" app/src/main/java/com/localfeed/app/ui/AlbumQuery.kt tools/AlbumQueryTest.kt -include-runtime -d "$KOTLIN_DIR/album.jar"
java -jar "$KOTLIN_DIR/album.jar"
# These stubs exercise cached signatures/algorithm only; Android decoder is compiled by assembleDebug.
kotlinc -nowarn "$CORE/MediaRecord.kt" app/src/main/java/com/localfeed/app/data/SimilarVideoScanner.kt tools/scanner-stubs/*.kt tools/SimilarVideoScannerSmokeTest.kt -include-runtime -d "$KOTLIN_DIR/scanner.jar"
java -jar "$KOTLIN_DIR/scanner.jar"
