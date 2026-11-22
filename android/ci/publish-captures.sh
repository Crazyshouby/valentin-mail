#!/usr/bin/env bash
# Range les captures d'écran et les journaux de l'essai dans la branche « ci-captures ».
set -euo pipefail
cd "$(dirname "$0")"
[ -d out ] || { echo "Rien à publier."; exit 0; }
cd out
rm -rf .git
git init -q -b ci-captures
git add -A
git -c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
  commit -qm "Captures du build ${GITHUB_RUN_NUMBER:-local} (${GITHUB_SHA:-?})"
git push -qf "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git" ci-captures
echo "Captures publiées."
