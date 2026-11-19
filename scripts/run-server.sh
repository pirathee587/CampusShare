#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
export CAMPUSSHARE_STORAGE="${CAMPUSSHARE_STORAGE:-data/shared}"
exec java -cp backend/target/campusshare-1.0.0.jar com.campusshare.app.ServerMain
