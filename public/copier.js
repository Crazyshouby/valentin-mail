// Lit un fichier choisi sur l'appareil et met son contenu dans le presse-papiers.
// Tout se passe dans le navigateur : aucune requête n'est faite.
const input = document.getElementById("fichier");
const button = document.getElementById("copier");
const status = document.getElementById("etat");
const area = document.getElementById("texte");
let text = "";

function say(message, error = false) {
  status.textContent = message;
  status.className = error ? "erreur" : "";
}

input.addEventListener("change", async () => {
  const file = input.files[0];
  text = "";
  button.disabled = true;
  area.hidden = true;
  area.value = "";
  if (!file) return say("");
  if (file.size > 200000) return say("Ce fichier est trop gros pour être une clé : vérifie que c'est le bon.", true);

  const raw = (await file.text()).trim();
  if (!raw) return say("Ce fichier est vide.", true);
  try {
    const data = JSON.parse(raw);
    // Sur une seule ligne : plus simple à coller dans un champ.
    text = JSON.stringify(data);
    if (data.private_key && data.client_email) {
      say(`Clé du compte de service du projet « ${data.project_id} ». À coller dans Cloudflare, secret FCM_SERVICE_ACCOUNT.`);
    } else if (data.project_info && data.client) {
      say(`Fichier google-services.json du projet « ${data.project_info.project_id} ». À coller dans GitHub, secret GOOGLE_SERVICES_JSON.`);
    } else {
      say("Fichier lu. Vérifie que c'est bien celui attendu avant de le coller.");
    }
  } catch {
    text = raw;
    say("Ce fichier n'est pas au format attendu : son texte sera copié tel quel.");
  }
  button.disabled = false;
});

button.addEventListener("click", async () => {
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    say("Copié. Colle-le maintenant dans le champ du secret.");
  } catch {
    // Copie automatique refusée : on montre le texte, déjà sélectionné, pour une copie à la main.
    area.value = text;
    area.hidden = false;
    area.focus();
    area.select();
    say("La copie automatique est refusée ici. Le texte est sélectionné ci-dessous : appui long, puis « Copier ».");
  }
});
