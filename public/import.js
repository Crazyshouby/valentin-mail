// Import des messages d'un compte Gmail dans cette boîte.
// Tout se passe dans ce navigateur : il lit Gmail (accès en lecture seule, valable une heure) et
// remet chaque message à ce serveur. Le jeton d'accès Google ne quitte jamais cette page.

const GMAIL = "https://gmail.googleapis.com/gmail/v1/users/me";
const GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
const SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
const REDIRECT = location.origin + "/import";
// Catégories que Gmail attribue lui-même : sans dossier choisi par toi, ces messages restent chez Google.
const LEFT_OUT = ["CATEGORY_PROMOTIONS", "CATEGORY_SOCIAL", "CATEGORY_UPDATES", "CATEGORY_FORUMS"];
const PARALLEL = 3;

const $ = (id) => document.getElementById(id);
const show = (id, on = true) => {
  $(id).hidden = !on;
};
const say = (id, text, error = false) => {
  const el = $(id);
  el.textContent = text;
  el.classList.toggle("error", error);
};
const number = (n) => n.toLocaleString("fr-FR");
const count = (n, one, many) => (n === 1 ? `1 ${one}` : `${number(n)} ${many}`);
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const hour = (ms) => new Date(ms).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" });

class Expired extends Error {} // l'autorisation Google a pris fin
class LoggedOut extends Error {} // la session sur cette boîte a pris fin
class Quota extends Error {
  constructor(retry) {
    super("quota");
    this.retry = retry;
  }
}

let clientId = "";
let token = "";
let source = ""; // « gmail:adresse » : sous ce nom, le serveur sait d'où vient chaque message importé
let labels = new Map(); // dossiers Gmail : identifiant → { name, type }
let plan = null;
let running = false;
let paused = false;
let indexing = false;
let wakeLock = null;

// ------------------------------------------------------------ ce serveur

async function api(method, path, body) {
  const response = await fetch(path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  let data = null;
  try {
    data = await response.json();
  } catch {
    /* réponse sans contenu lisible */
  }
  return { status: response.status, data: data || {} };
}

// ----------------------------------------------------------------- Gmail

async function gmail(path) {
  for (let attempt = 0; ; attempt++) {
    let response;
    try {
      response = await fetch(GMAIL + path, { headers: { Authorization: "Bearer " + token } });
    } catch {
      if (attempt >= 3) throw new Error("Gmail est injoignable. Vérifie ta connexion internet.");
      await wait(1500 * (attempt + 1));
      continue;
    }
    if (response.ok) return response.json();
    if (response.status === 401) throw new Expired();
    let detail = "";
    let reason = "";
    try {
      const data = await response.json();
      detail = data.error.message || "";
      reason = ((data.error.errors || [])[0] || {}).reason || "";
    } catch {
      /* erreur sans détail */
    }
    // Trop de demandes à la fois, ou panne passagère : on patiente et on redemande.
    const busy = response.status === 429 || response.status >= 500 || /ratelimit/i.test(reason);
    if (busy && attempt < 4) {
      await wait(2000 * (attempt + 1));
      continue;
    }
    if (response.status === 403 && /disabled|not been used/i.test(detail)) {
      throw new Error("L'API Gmail n'est pas activée dans ton projet Google (étape 2 du réglage). Active-la, attends une minute, puis recharge cette page.");
    }
    throw new Error(`Gmail a répondu par une erreur (${response.status})${detail ? " : " + detail : "."}`);
  }
}

// Les identifiants de tous les messages d'un dossier Gmail (ou de toute la boîte, hors spam et corbeille).
async function listIds(labelId) {
  const ids = [];
  let pageToken = "";
  do {
    const query = new URLSearchParams({ maxResults: "500" });
    if (labelId) query.set("labelIds", labelId);
    if (pageToken) query.set("pageToken", pageToken);
    const page = await gmail("/messages?" + query);
    for (const message of page.messages || []) ids.push(message.id);
    pageToken = page.nextPageToken || "";
  } while (pageToken);
  return ids;
}

async function pool(items, size, work) {
  let next = 0;
  const runner = async () => {
    while (next < items.length) await work(items[next++]);
  };
  await Promise.all(Array.from({ length: Math.min(size, items.length) }, runner));
}

function decodeRaw(text) {
  const binary = atob(text.replace(/-/g, "+").replace(/_/g, "/"));
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

// ------------------------------------------------------ connexion à Google

function connect() {
  const state = crypto.randomUUID();
  sessionStorage.setItem("import_state", state);
  const query = new URLSearchParams({
    client_id: clientId,
    redirect_uri: REDIRECT,
    response_type: "token",
    scope: SCOPE,
    state,
    prompt: "select_account",
  });
  location.assign(`${GOOGLE_AUTH}?${query}`);
}

// Au retour de Google, le jeton arrive dans l'adresse de la page : on le range dans l'onglet
// (il disparaît à sa fermeture) et on nettoie l'adresse.
function takeToken() {
  let error = "";
  if (location.hash.length > 1) {
    const answer = new URLSearchParams(location.hash.slice(1));
    history.replaceState(null, "", location.pathname);
    const expected = sessionStorage.getItem("import_state");
    sessionStorage.removeItem("import_state");
    if (answer.get("error")) {
      error = answer.get("error") === "access_denied" ? "Tu as refusé l'accès, ou ton adresse n'est pas dans les « utilisateurs tests » du réglage Google." : `Google a refusé la connexion (${answer.get("error")}).`;
    } else if (answer.get("access_token")) {
      if (!expected || answer.get("state") !== expected) {
        error = "Cette réponse de Google ne correspond pas à une demande faite depuis cette page. Recommence la connexion.";
      } else {
        const expires = Date.now() + (Number(answer.get("expires_in")) || 3600) * 1000 - 60000;
        sessionStorage.setItem("import_token", JSON.stringify({ token: answer.get("access_token"), expires }));
      }
    }
  }
  try {
    const saved = JSON.parse(sessionStorage.getItem("import_token") || "null");
    if (saved && saved.expires > Date.now()) return { token: saved.token, error };
  } catch {
    /* jeton illisible : on repart sans */
  }
  sessionStorage.removeItem("import_token");
  return { token: "", error };
}

function askToReconnect(message) {
  token = "";
  sessionStorage.removeItem("import_token");
  show("connexion");
  show("connecter");
  say("connexion-texte", message);
}

// ---------------------------------------------------------------- analyse

const isTechnical = (label) => label.type === "user" && label.name.startsWith("[");
const isMine = (label) => label.type === "user" && !label.name.startsWith("[");

async function analyse() {
  show("analyse");
  show("import", false);
  $("plan").replaceChildren();
  say("analyse-note", "Lecture de la liste de tes messages…");

  const found = (await gmail("/labels")).labels || [];
  labels = new Map(found.map((l) => [l.id, { name: l.name, type: l.type }]));
  const mine = found.filter(isMine).sort((a, b) => a.name.localeCompare(b.name, "fr"));
  const technical = found.filter(isTechnical);
  const oldTrash = technical.filter((l) => /\/(trash|corbeille)$/i.test(l.name)).map((l) => l.id);
  const oldSent = technical.filter((l) => /\/(sent|envoy[ée]s)$/i.test(l.name)).map((l) => l.id);

  const everything = await listIds("");
  const wanted = ["SENT", "CHAT", "DRAFT", ...LEFT_OUT, ...mine.map((l) => l.id), ...oldTrash, ...oldSent];
  const sets = new Map();
  let done = 0;
  await pool(wanted, 3, async (id) => {
    sets.set(id, new Set(await listIds(id)));
    say("analyse-note", `Lecture de la liste de tes messages… (${++done} sur ${wanted.length})`);
  });
  const within = (group, id) => group.some((label) => sets.get(label).has(id));
  const mineIds = mine.map((l) => l.id);

  // Ce que devient chaque message, selon les choix faits : tes dossiers d'abord, puis les envoyés,
  // puis tout ce que Gmail n'a pas classé comme pub, réseau social ou notification.
  const groups = { folders: [], sent: [], main: [], ads: [], oldTrash: [], other: [] };
  for (const id of everything) {
    if (within(["CHAT", "DRAFT"], id)) groups.other.push(id);
    else if (within(mineIds, id)) groups.folders.push(id);
    else if (within(oldTrash, id)) groups.oldTrash.push(id);
    else if (within(["SENT", ...oldSent], id)) groups.sent.push(id);
    else if (within(LEFT_OUT, id)) groups.ads.push(id);
    else groups.main.push(id);
  }
  const keep = [...groups.folders, ...groups.sent, ...groups.main];

  say("analyse-note", "Comparaison avec ce que la boîte contient déjà…");
  plan = { keep, known: new Set(), groups, mine: mine.map((l) => ({ id: l.id, name: l.name, ids: [...sets.get(l.id)].filter((id) => !within(["CHAT", "DRAFT"], id)) })) };
  await findKnown();
  showPlan();
}

// Lesquels des messages à importer sont déjà dans la boîte : on ne les relira pas chez Gmail.
async function findKnown() {
  const known = new Set();
  for (let i = 0; i < plan.keep.length; i += 450) {
    const answer = await api("POST", "/api/import/check", { source, refs: plan.keep.slice(i, i + 450) });
    if (answer.status === 401) throw new LoggedOut();
    if (answer.status !== 200) throw new Error(answer.data.error || "La boîte n'a pas répondu.");
    for (const ref of answer.data.known) known.add(ref);
  }
  plan.known = known;
}

function showPlan() {
  const { keep, known, groups, mine } = plan;
  const lines = [
    ["Rangés dans tes dossiers", groups.folders.length, "sub"],
    ["Envoyés", groups.sent.length, "sub"],
    ["Autres messages reçus", groups.main.length, "sub"],
    ["À importer", keep.length, "total"],
    ["Déjà dans cette boîte", keep.filter((id) => known.has(id)).length, "sub"],
    ["Pubs, réseaux sociaux et notifications, laissés chez Google", groups.ads.length, ""],
  ];
  if (groups.oldTrash.length) lines.push(["Ancienne corbeille d'une appli de mail, laissée chez Google", groups.oldTrash.length, ""]);
  if (groups.other.length) lines.push(["Discussions et brouillons, laissés chez Google", groups.other.length, ""]);
  const list = $("plan");
  list.replaceChildren();
  for (const [label, value, kind] of lines) {
    const dt = document.createElement("dt");
    dt.textContent = label;
    if (kind) dt.className = kind;
    const dd = document.createElement("dd");
    dd.textContent = number(value);
    list.append(dt, dd);
  }
  say("analyse-note", "Le spam et la corbeille de Gmail ne sont jamais lus par cette page.");

  const folders = $("dossiers");
  folders.replaceChildren();
  const pick = $("essai");
  pick.replaceChildren();
  for (const folder of mine) {
    const item = document.createElement("li");
    item.textContent = `${folder.name} — ${count(folder.ids.length, "message", "messages")}`;
    folders.append(item);
    if (!folder.ids.length) continue;
    const option = document.createElement("option");
    option.value = folder.id;
    option.textContent = `${folder.name} (${number(folder.ids.length)})`;
    pick.append(option);
  }
  show("dossiers-detail", mine.length > 0);
  // Pour un premier essai, on propose le plus petit dossier qui ait tout de même une dizaine de messages.
  const filled = mine.filter((f) => f.ids.length);
  const trial = filled.filter((f) => f.ids.length >= 10).sort((a, b) => a.ids.length - b.ids.length)[0] || filled[0];
  if (trial) pick.value = trial.id;
  show("lancer-essai", filled.length > 0);
  pick.hidden = !filled.length;
  pick.previousElementSibling.hidden = !filled.length;

  const left = keep.filter((id) => !known.has(id)).length;
  $("lancer-tout").textContent = left ? `Tout importer (${count(left, "message", "messages")})` : "Tout est déjà importé";
  $("lancer-tout").disabled = !left;
  show("import");
}

// ----------------------------------------------------------------- import

async function importOne(id) {
  const message = await gmail(`/messages/${id}?format=raw`);
  const ids = message.labelIds || [];
  const own = ids.map((l) => labels.get(l)).filter(Boolean);
  const names = own.filter(isMine).map((l) => l.name);
  const sent = ids.includes("SENT") || own.some((l) => isTechnical(l) && /\/(sent|envoy[ée]s)$/i.test(l.name));
  const query = new URLSearchParams({
    source,
    date: String(message.internalDate),
    folder: ids.includes("INBOX") ? "inbox" : sent ? "sent" : "archive",
    read: ids.includes("UNREAD") ? "0" : "1",
    thread: message.threadId || "",
    labels: JSON.stringify(names),
  });
  const bytes = decodeRaw(message.raw || "");
  for (let attempt = 0; ; attempt++) {
    let response;
    try {
      response = await fetch(`/api/import/messages/${id}?${query}`, { method: "PUT", headers: { "Content-Type": "message/rfc822" }, body: bytes });
    } catch {
      if (attempt >= 2) throw new Error("la boîte était injoignable");
      await wait(2000 * (attempt + 1));
      continue;
    }
    let data = {};
    try {
      data = await response.json();
    } catch {
      /* réponse sans contenu lisible */
    }
    if (response.ok) return data.status;
    if (response.status === 401) throw new LoggedOut();
    if (response.status === 429) throw new Quota(data.retry);
    if (response.status >= 500 && attempt < 2) {
      await wait(2000 * (attempt + 1));
      continue;
    }
    throw new Error(data.error || `erreur ${response.status}`);
  }
}

async function keepAwake() {
  try {
    wakeLock = await navigator.wakeLock.request("screen");
  } catch {
    wakeLock = null; // le téléphone peut se mettre en veille : l'import reprendra au retour
  }
}

document.addEventListener("visibilitychange", () => {
  if (running && document.visibilityState === "visible") keepAwake();
});

async function run(ids) {
  if (running || !plan) return;
  const todo = ids.filter((id) => !plan.known.has(id));
  if (!todo.length) {
    say("import-note", "Ces messages sont déjà dans la boîte.");
    return;
  }
  running = true;
  paused = false;
  say("import-note", "Démarrage…");
  for (const id of ["lancer-essai", "lancer-tout", "essai"]) $(id).disabled = true;
  show("pause");
  show("avance");
  show("echecs-detail", false);
  $("echecs").replaceChildren();
  $("avance").max = todo.length;
  $("avance").value = 0;
  await keepAwake();

  const tally = { imported: 0, merged: 0, known: 0, failed: 0 };
  let next = 0;
  let halt = null;
  const progress = () => {
    const done = tally.imported + tally.merged + tally.known + tally.failed;
    $("avance").value = done;
    say("import-note", `${number(done)} sur ${number(todo.length)} — garde cette page ouverte.`);
  };
  progress();

  const worker = async () => {
    while (!paused && !halt && next < todo.length) {
      const id = todo[next++];
      try {
        const status = await importOne(id);
        tally[status in tally ? status : "imported"]++;
        plan.known.add(id);
      } catch (err) {
        if (err instanceof Expired || err instanceof LoggedOut || err instanceof Quota) {
          halt = halt || err;
          return;
        }
        tally.failed++;
        const item = document.createElement("li");
        const link = document.createElement("a");
        link.href = `https://mail.google.com/mail/u/0/#all/${id}`;
        link.target = "_blank";
        link.rel = "noopener";
        link.textContent = "voir ce message dans Gmail";
        item.append(`${err.message} — `, link);
        $("echecs").append(item);
      }
      progress();
    }
  };
  await Promise.all(Array.from({ length: PARALLEL }, worker));

  running = false;
  if (wakeLock) wakeLock.release().catch(() => {});
  show("pause", false);
  $("pause").textContent = "Mettre en pause";
  for (const id of ["lancer-essai", "lancer-tout", "essai"]) $(id).disabled = false;
  if (tally.failed) {
    $("echecs-titre").textContent = count(tally.failed, "message non importé", "messages non importés");
    show("echecs-detail");
  }

  const parts = [count(tally.imported, "message importé", "messages importés")];
  if (tally.merged) parts.push(`${count(tally.merged, "déjà arrivé par le transfert, complété", "déjà arrivés par le transfert, complétés")}`);
  if (tally.known) parts.push(`${number(tally.known)} déjà là`);
  if (tally.failed) parts.push(count(tally.failed, "échec", "échecs"));
  const summary = parts.join(", ") + ".";

  showPlan();
  if (halt instanceof Quota) {
    say("import-note", `${summary} Le quota d'écritures du jour est atteint : reviens après ${hour(halt.retry || Date.now())} et relance, l'import reprendra où il en est.`, true);
  } else if (halt instanceof LoggedOut) {
    say("import-note", `${summary} Ta session sur la boîte a pris fin : rouvre la boîte pour te reconnecter, puis reviens sur cette page.`, true);
  } else if (halt instanceof Expired) {
    say("import-note", `${summary} L'autorisation Google a duré son heure.`);
    askToReconnect("L'autorisation Google dure une heure. Reconnecte-toi, puis relance : l'import reprendra où il en est.");
  } else if (paused) {
    say("import-note", `En pause. ${summary}`);
  } else {
    say("import-note", `Terminé. ${summary}`);
    refreshStatus().then(() => indexLoop());
  }
}

// -------------------------------------------- recherche et annulation

async function refreshStatus() {
  const status = await api("GET", "/api/import/status");
  if (status.status !== 200) return null;
  const { imported, unindexed } = status.data;
  show("recherche", unindexed > 0 || indexing);
  if (!indexing) say("recherche-note", unindexed ? `${count(unindexed, "message", "messages")} à préparer.` : "");
  show("annulation", imported > 0 && Boolean(source));
  if (imported > 0 && !$("annulation-note").textContent) $("defaire").textContent = `Défaire l'import (${count(imported, "message", "messages")})`;
  return status.data;
}

async function indexLoop() {
  if (indexing) return;
  indexing = true;
  show("recherche");
  $("indexer").textContent = "Arrêter la préparation";
  let limit = 4;
  let failures = 0;
  while (indexing) {
    const answer = await api("POST", "/api/index", { limit }).catch(() => ({ status: 0, data: {} }));
    if (answer.status === 429) {
      say("recherche-note", `Le quota d'écritures du jour est atteint. La suite se fera après ${hour(answer.data.retry || Date.now())}, ou peu à peu quand tu utilises la boîte.`);
      break;
    }
    if (answer.status !== 200) {
      // Un lot trop lourd pour le serveur : on continue par lots plus petits, puis on renonce.
      limit = 2;
      if (++failures > 4) {
        say("recherche-note", "La préparation s'est interrompue. Elle se finira peu à peu quand tu utilises la boîte.", true);
        break;
      }
      await wait(2000);
      continue;
    }
    failures = 0;
    if (!answer.data.remaining) {
      say("recherche-note", "Tous les messages sont cherchables par leur texte.");
      break;
    }
    say("recherche-note", `Encore ${count(answer.data.remaining, "message", "messages")} à préparer…`);
  }
  indexing = false;
  $("indexer").textContent = "Préparer la recherche maintenant";
}

async function undo() {
  const button = $("defaire");
  if (!button.classList.contains("armed")) {
    button.classList.add("armed");
    button.textContent = "Confirmer : effacer les messages importés";
    return;
  }
  button.disabled = true;
  indexing = false;
  let removed = 0;
  for (;;) {
    const answer = await api("POST", "/api/import/undo", { source }).catch(() => ({ status: 0, data: {} }));
    if (answer.status !== 200) {
      say("annulation-note", `Interrompu après ${count(removed, "message effacé", "messages effacés")} : ${answer.data.error || "la boîte n'a pas répondu"}. Relance pour finir.`, true);
      break;
    }
    removed += answer.data.removed;
    say("annulation-note", `${count(removed, "message effacé", "messages effacés")}…`);
    if (!answer.data.remaining) {
      say("annulation-note", `Import défait : ${count(removed, "message effacé", "messages effacés")}.`);
      break;
    }
  }
  button.disabled = false;
  button.classList.remove("armed");
  button.textContent = "Défaire l'import";
  if (plan) {
    await findKnown().catch(() => plan.known.clear());
    showPlan();
  }
  refreshStatus();
}

// --------------------------------------------------------------- démarrage

async function afterConnect() {
  try {
    const profile = await gmail("/profile");
    source = "gmail:" + String(profile.emailAddress || "").toLowerCase();
    localStorage.setItem("import_source", source);
    show("connexion");
    show("connecter", false);
    say("connexion-texte", `Connecté à ${profile.emailAddress}, en lecture seule.`);
    await refreshStatus();
    await analyse();
  } catch (err) {
    if (err instanceof Expired) return askToReconnect("L'autorisation Google a pris fin. Reconnecte-toi pour continuer.");
    if (err instanceof LoggedOut) return say("etat", "Ta session sur la boîte a pris fin : rouvre la boîte pour te reconnecter, puis reviens sur cette page.", true);
    say("analyse-note", err.message, true);
    show("analyse");
  }
}

async function start() {
  $("redirection").textContent = REDIRECT;
  const status = await api("GET", "/api/import/status").catch(() => ({ status: 0, data: {} }));
  if (status.status === 401) {
    $("etat").replaceChildren("Ouvre d'abord ta boîte pour te connecter, puis reviens sur cette page : ", Object.assign(document.createElement("a"), { href: "/", textContent: "ouvrir la boîte" }), ".");
    return;
  }
  if (status.status !== 200) return say("etat", "La boîte ne répond pas. Vérifie ta connexion, puis recharge la page.", true);
  say("etat", "");
  clientId = status.data.client_id || "";
  source = localStorage.getItem("import_source") || "";
  $("client").value = clientId;
  await refreshStatus();

  const { token: saved, error } = takeToken();
  if (!clientId) return show("reglage");
  show("connexion");
  if (error) say("connexion-texte", error, true);
  if (!saved) return;
  token = saved;
  await afterConnect();
}

$("enregistrer").addEventListener("click", async () => {
  const answer = await api("PUT", "/api/settings", { google_client_id: $("client").value.trim() });
  if (answer.status !== 200) return say("reglage-note", answer.data.error || "Enregistrement impossible.", true);
  if (!answer.data.google_client_id) return say("reglage-note", "Colle d'abord l'identifiant client.", true);
  say("reglage-note", "");
  clientId = answer.data.google_client_id;
  show("reglage", false);
  show("connexion");
});
$("changer").addEventListener("click", () => {
  show("reglage");
  $("client").focus();
});
$("connecter").addEventListener("click", connect);
$("lancer-essai").addEventListener("click", () => {
  const folder = plan && plan.mine.find((f) => f.id === $("essai").value);
  if (folder) run(folder.ids);
});
$("lancer-tout").addEventListener("click", () => plan && run(plan.keep));
$("pause").addEventListener("click", () => {
  paused = true;
  $("pause").textContent = "Mise en pause…";
});
$("indexer").addEventListener("click", () => {
  if (indexing) indexing = false;
  else indexLoop();
});
$("defaire").addEventListener("click", undo);

start();
