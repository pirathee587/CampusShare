#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
exec java -cp backend/target/campusshare-1.0.0.jar com.campusshare.app.LocalAgentMain
