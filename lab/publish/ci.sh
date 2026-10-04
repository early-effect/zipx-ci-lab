#!/usr/bin/env bash
set -euo pipefail
export ZIPX_UNDER_TEST="${GITHUB_WORKSPACE}/zipx"
exec scala-cli run --server=false lab/publish
