#!/usr/bin/env bash
# Essai de bout en bout : lance la boîte mail en local, y dépose deux messages,
# puis fait dérouler tout le parcours de l'appli dans l'émulateur.
set -uo pipefail
cd "$(dirname "$0")/../.."

OUT="$PWD/android/ci/out"
rm -rf "$OUT"
mkdir -p "$OUT/captures"
APP=com.valentinlarge.courrier
status=1

finish() {
  adb logcat -d -v time > "$OUT/logcat-complet.txt" 2>/dev/null || true
  grep -E "AndroidRuntime|TestRunner|$APP|chromium|System.err" "$OUT/logcat-complet.txt" | tail -n 600 > "$OUT/logcat.txt" || true
  rm -f "$OUT/logcat-complet.txt"
  adb exec-out run-as "$APP" sh -c 'cd files/captures 2>/dev/null && tar -cf - .' 2>/dev/null | tar -xf - -C "$OUT/captures" 2>/dev/null || true
  kill $(jobs -p) 2>/dev/null || true
  echo "$status" > "$OUT/resultat.txt"
  ls -la "$OUT" "$OUT/captures" || true
  exit "$status"
}
trap finish EXIT

# ---- serveur local, avec un mot de passe d'essai jetable et un faux service d'envoi
TEST_PASSWORD="essai-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
cat > .dev.vars <<VARS
APP_PASSWORD=$TEST_PASSWORD
RESEND_API_KEY=cle-d-essai
RESEND_API_URL=http://127.0.0.1:9911/emails
VARS

npm ci --no-audit --no-fund > "$OUT/npm.log" 2>&1 || { tail -n 40 "$OUT/npm.log"; exit 1; }
npx wrangler d1 execute valentin-mail --local --file=schema.sql > "$OUT/d1.log" 2>&1 || { cat "$OUT/d1.log"; exit 1; }

MOCK_LOG="$OUT/resend.log" WORKER_ORIGIN=http://127.0.0.1:8787 node android/ci/mock-resend.mjs > "$OUT/mock.log" 2>&1 &
npx wrangler dev --port 8787 --ip 127.0.0.1 > "$OUT/wrangler.log" 2>&1 &

ready=0
for _ in $(seq 1 90); do
  if curl -fs -o /dev/null http://127.0.0.1:8787/api/me; then ready=1; break; fi
  sleep 1
done
if [ "$ready" != 1 ]; then echo "Le serveur local n'a pas démarré."; tail -n 60 "$OUT/wrangler.log"; exit 1; fi

for name in devis commande; do
  curl -fs -o /dev/null -X POST "http://127.0.0.1:8787/cdn-cgi/local/email?from=test@example.org&to=valentin@valentinlarge.com" \
    --data-binary "@android/ci/fixtures/$name.eml" || { echo "Dépôt de $name.eml refusé."; exit 1; }
  sleep 1
done

# ---- l'appli et son test
APKS=android/app/build/outputs/apk
adb install -r "$APKS/debug/app-debug.apk" || exit 1
adb install -r "$APKS/androidTest/debug/app-debug-androidTest.apk" || exit 1
adb shell pm grant "$APP" android.permission.POST_NOTIFICATIONS || true
adb shell settings put secure show_ime_with_hard_keyboard 0 || true
adb logcat -c || true

adb shell am instrument -w -e motdepasse "$TEST_PASSWORD" \
  "$APP.test/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tee "$OUT/instrument.log"

# ---- vérifications côté serveur : le message est bien parti, pièce jointe comprise
if grep -q "^OK (" "$OUT/instrument.log" && ! grep -q "FAILURES" "$OUT/instrument.log"; then
  node -e '
    const fs = require("fs");
    const lines = fs.readFileSync(process.argv[1], "utf8").trim().split("\n").map((l) => JSON.parse(l));
    const sent = lines.find((l) => /^Re: Devis/.test(l.body.subject || ""));
    if (!sent) throw new Error("aucun envoi reçu par le faux service");
    const file = (sent.fetched || [])[0];
    if (!file || file.status !== 200 || file.bytes !== 1480) throw new Error("pièce jointe non récupérée : " + JSON.stringify(sent.fetched));
    if (!String(sent.body.to).includes("eloise@example.org")) throw new Error("mauvais destinataire : " + sent.body.to);
    console.log("Envoi vérifié :", sent.body.subject, "→", sent.body.to, "avec", file.filename, file.bytes, "octets");
  ' "$OUT/resend.log" && status=0
else
  echo "Le parcours dans l'appli a échoué."
fi
