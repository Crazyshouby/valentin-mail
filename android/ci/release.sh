#!/usr/bin/env bash
# Construit l'APK signé et le publie dans les « Releases » du dépôt.
#
# La clé de signature est créée une seule fois, puis rangée dans le dépôt sous forme
# chiffrée : seule la phrase secrète SIGNING_PASSPHRASE (secret GitHub) permet de l'ouvrir.
# Toutes les versions doivent être signées avec la même clé, sinon Android refuse la mise à jour.
set -euo pipefail
cd "$(dirname "$0")/../.."

if [ -z "${SIGNING_PASSPHRASE:-}" ]; then
  echo "Pas de phrase de signature : aucun APK n'est publié."
  exit 0
fi

ENC=android/signing/courrier.keystore.enc
KEYSTORE="${RUNNER_TEMP:-/tmp}/courrier.p12"
COURRIER_KEYSTORE_PASSWORD="$(printf 'courrier:%s' "$SIGNING_PASSPHRASE" | sha256sum | cut -c1-40)"
export COURRIER_KEYSTORE_PASSWORD
rm -f "$KEYSTORE"

if [ -f "$ENC" ]; then
  if ! openssl enc -d -aes-256-cbc -pbkdf2 -iter 600000 -in "$ENC" -out "$KEYSTORE" -pass env:SIGNING_PASSPHRASE 2>/dev/null; then
    echo "La phrase secrète ne correspond pas à la clé de signature déjà enregistrée."
    exit 1
  fi
else
  echo "Première publication : création de la clé de signature."
  keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias courrier \
    -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=Courrier, O=valentinlarge.com" \
    -storepass:env COURRIER_KEYSTORE_PASSWORD -keypass:env COURRIER_KEYSTORE_PASSWORD
  mkdir -p android/signing
  openssl enc -aes-256-cbc -pbkdf2 -iter 600000 -salt -in "$KEYSTORE" -out "$ENC" -pass env:SIGNING_PASSPHRASE
  git add -f "$ENC"
  git -c user.name="github-actions[bot]" -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
    commit -qm "Clé de signature de l'appli (chiffrée)"
  # Si l'envoi échoue, on s'arrête : publier un APK signé avec une clé perdue bloquerait les mises à jour.
  git push -q origin "HEAD:${GITHUB_REF_NAME}"
fi

export COURRIER_KEYSTORE="$KEYSTORE"
gradle -p android assembleRelease
rm -f "$KEYSTORE"

VERSION="0.1.${COURRIER_BUILD:-1}"
APK="${RUNNER_TEMP:-/tmp}/courrier.apk"
cp android/app/build/outputs/apk/release/app-release.apk "$APK"
APKSIGNER="$(ls "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -n 1)"
"$APKSIGNER" verify --verbose "$APK" | head -n 6

cat > "${RUNNER_TEMP:-/tmp}/notes.md" <<NOTES
Appli Android de la boîte mail, version $VERSION.

**Installer ou mettre à jour**

1. Sur ton téléphone, touche le fichier **courrier.apk** ci-dessous pour le télécharger.
2. Ouvre-le. Android demande d'autoriser ton navigateur à installer des applis : accepte, puis touche « Installer ».
3. Ouvre « Courrier » et entre le mot de passe de ta boîte.

Une mise à jour s'installe par-dessus l'ancienne version, sans rien perdre.
NOTES

gh release create "appli-$VERSION" "$APK" --target "$GITHUB_SHA" --title "Courrier $VERSION" \
  --notes-file "${RUNNER_TEMP:-/tmp}/notes.md" --latest
echo "APK publié : version $VERSION."
