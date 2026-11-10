#!/usr/bin/env bash
# Télécharge la bibliothèque Whisper/VAD (sherpa-onnx) et le modèle VAD.
# Utilisé par GitHub Actions ET en local avant d'ouvrir le projet dans Android Studio.
set -euo pipefail

SHERPA_VERSION="2.0.1"
cd "$(dirname "$0")/.."
mkdir -p app/libs app/src/main/assets

if [ ! -s app/libs/sherpa-onnx.aar ]; then
  echo "Téléchargement de sherpa-onnx ${SHERPA_VERSION}..."
  curl -fL --retry 3 -o app/libs/sherpa-onnx.aar \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"
fi

if [ ! -s app/src/main/assets/silero_vad.onnx ]; then
  echo "Téléchargement du modèle VAD..."
  curl -fL --retry 3 -o app/src/main/assets/silero_vad.onnx \
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
fi

echo "OK : dépendances natives prêtes."
