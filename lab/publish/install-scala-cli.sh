#!/usr/bin/env bash
set -euo pipefail
case "$(uname -m)" in
  x86_64) arch=x86_64-pc-linux ;;
  aarch64) arch=aarch64-pc-linux ;;
  *) echo "unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac
curl -fL -o /tmp/scala-cli.gz "https://github.com/VirtusLab/scala-cli/releases/download/v1.17.1/scala-cli-${arch}.gz"
gzip -d /tmp/scala-cli.gz
sudo install -m 755 /tmp/scala-cli /usr/local/bin/scala-cli
scala-cli version
