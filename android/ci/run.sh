#!/usr/bin/env bash
# Point d'entrée unique de la construction : toute la logique vit ici,
# le fichier de workflow ne fait qu'appeler ce script.
set -euo pipefail
echo "Environnement de construction :"
java -version 2>&1 | head -1
echo "ANDROID_HOME=${ANDROID_HOME:-absent}"
ls "${ANDROID_HOME:-/nonexistent}/platforms" 2>/dev/null | tr '\n' ' ' || true
echo
ls "${ANDROID_HOME:-/nonexistent}/build-tools" 2>/dev/null | tr '\n' ' ' || true
echo
gradle --version 2>/dev/null | sed -n '2,4p' || echo "gradle absent"
ls /dev/kvm 2>/dev/null && echo "KVM présent" || echo "KVM absent"
nproc; free -g | sed -n '2p'
node --version; npm --version
