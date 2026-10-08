#!/usr/bin/env bash
set -uo pipefail
instrumentation_result=0
smoke_result=0
gradle --no-daemon connectedDebugAndroidTest || instrumentation_result=$?
bash scripts/device-smoke.sh || smoke_result=$?
echo "Instrumentation exit: $instrumentation_result; device smoke exit: $smoke_result"
if [ "$instrumentation_result" -ne 0 ] || [ "$smoke_result" -ne 0 ]; then
  exit 1
fi
