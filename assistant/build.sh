#!/bin/bash
# THE ROUTER LANE'S DATA REBUILT (specs/shop.md "The assistant"): the static table distilled over intents.json's
# examples, vocabulary.txt and MASSIVE's words, then the probe fitted on it — by okay-facts' tools (scala-cli, the ONNX
# encoder on the JVM). Needs okay-facts and okay-chat (its encoder and MASSIVE) beside this repository.
#   assistant/build.sh [cache file]
set -euo pipefail
cd "$(dirname "$0")"
tools=../../okay-facts/tools
models=../../okay-chat/models
cache=${1:-${TMPDIR:-/tmp}/okay-shop-router-cache.tsv}
scala-cli run "$tools/table.scala" -- --model "$models/paraphrase-multilingual-MiniLM-L12-v2" \
  --intents intents.json --words vocabulary.txt --massive "$models/massive/1.1/data" --dim 256 \
  --cache "$cache" --out static.safetensors
scala-cli run "$tools/fit.scala" -- intents.json static.safetensors probe.json
