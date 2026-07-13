#!/usr/bin/env bash
# Restore the side-loaded own-model (self-quantized LiteRT Gemma 3 1B INT4) to the app's external files
# dir. `connectedAndroidTest` uninstalls the app, which wipes getExternalFilesDir — taking the
# side-loaded .litertlm (and the debug App Check token, which can't be scripted — re-register from logcat
# if App Check enforcement is ever on). Re-run this after any device-test batch so the presence-gated
# "Your Gemma" engine is selectable again.
#
# The chmod 644 is required: an adb-copied file lands mode 640 (shell-owned) and the app's presence check
# treats an unreadable file as absent (Week-7 gotcha). Relaunch Arcana afterwards so the check re-runs.
#
# Usage: bash tools/restore-model.sh            # defaults to the Pixel 10 Pro XL
#        ADB_SERIAL=<serial> bash tools/restore-model.sh
set -euo pipefail

SERIAL="${ADB_SERIAL:-57130DLCQ000ZJ}"          # Pixel 10 Pro XL; override for a second device
PKG="com.aashishgodambe.arcana"
SRC="/data/local/tmp/w6/gemma3-1b-it-int4.litertlm"
DST_DIR="/sdcard/Android/data/${PKG}/files/models"

if ! MSYS_NO_PATHCONV=1 adb -s "$SERIAL" shell "test -f '$SRC'"; then
  echo "ERROR: source model not found on device at $SRC" >&2
  exit 1
fi

echo "Restoring model to ${SERIAL}:${DST_DIR} ..."
MSYS_NO_PATHCONV=1 adb -s "$SERIAL" shell \
  "mkdir -p '$DST_DIR' && cp '$SRC' '$DST_DIR/' && chmod 644 '$DST_DIR'/*.litertlm && ls -la '$DST_DIR'"
echo "Done. Relaunch Arcana (or reopen Settings) so the presence check re-evaluates."
