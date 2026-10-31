#!/usr/bin/env bash
set -e

echo "Running MediTrack CI checks..."
./gradlew assembleDebug testDebugUnitTest lint
echo "All checks passed successfully!"
