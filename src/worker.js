// Boîte mail maison sur Cloudflare.
// - email() : chaque mail entrant est rangé tel quel dans R2, avec une ligne d'index dans D1.
// - fetch() : API du webmail (connexion, liste, lecture, envoi via Resend).
// Le décodage complet des messages (MIME) se fait dans le navigateur, pour rester
// sous la limite de calcul du forfait gratuit.

const FOLDERS = ["inbox", "sent", "archive", "trash"];
const SESSION_COOKIE = "vm_session";
const SESSION_DAYS = 30;
const LOGIN_WINDOW_MS = 15 * 60 * 1000;
const MAX_LOGIN_FAILURES = 8; // par adresse IP et par quart d'heure
const PAGE_SIZE = 50;
const MAX_RECIPIENTS = 20;
const RESEND_URL = "https://api.resend.com/emails";
const APP_SESSION_DAYS = 365; // l'appli reste connectée plus longtemps qu'un navigateur
const MAX_UPLOAD_BYTES = 25 * 1024 * 1024; // par fichier joint
const MAX_ATTACHMENTS = 10;
const MAX_ATTACHMENTS_BYTES = 25 * 1024 * 1024; // total par message
const ATTACHMENT_LINK_MS = 6 * 3600 * 1000;
const ORPHAN_UPLOAD_MS = 24 * 3600 * 1000;
const THREAD_WINDOW_MS = 60 * 24 * 3600 * 1000; // au-delà, un même objet ouvre une nouvelle conversation
const MAX_BATCH = 100;

export default {
  async email(message, env, ctx) {
    const fresh = await receive(message, env);
    // Un nouveau message en réception : on prévient le téléphone, sans retarder la réponse au serveur expéditeur.
    if (fresh) ctx.waitUntil(pushNewMail(env).catch((err) => console.error("Notification non envoyée :", err)));
  },

  async fetch(request, env) {
    try {
      return await route(request, env);
    } catch (err) {
      console.error("Erreur non gérée :", err && err.stack ? err.stack : err);
      return json({ error: "Erreur interne du serveur." }, 500);
    }
  },
};

// ---------------------------------------------------------------- réception

async function receive(message, env) {
  const headers = message.headers;
  const from = parseAddress(decodeMimeWords(headers.get("from") || ""));
  if (!from.address) from.address = (message.from || "").toLowerCase();
  const subject = decodeMimeWords(headers.get("subject") || "").trim();
  const recipient = (message.to || "").toLowerCase();

  // Avant de garder quoi que ce soit : l'adresse visée est-elle coupée, une règle s'applique-t-elle ?
  let alias = null;
  let rule = null;
  try {
    const [found, rules] = await env.DB.batch([
      env.DB.prepare("SELECT enabled FROM aliases WHERE address = ?1").bind(recipient),
      env.DB.prepare("SELECT field, value, action FROM rules ORDER BY created"),
    ]);
    alias = (found.results || [])[0] || null;
    rule = matchRule(rules.results || [], { from: `${from.name} ${from.address}`, to: recipient, subject });
  } catch (err) {
    console.error("Lecture des adresses et des règles impossible :", err);
  }
  if (alias && !alias.enabled) {
    message.setReject("Cette adresse n'accepte plus de courrier.");
    return;
  }
  if (rule && rule.action === "block") {
    message.setReject("Message refusé par le destinataire.");
    return;
  }
  const folder = rule && rule.action === "archive" ? "archive" : rule && rule.action === "trash" ? "trash" : "inbox";
  const read = rule && rule.action === "read" ? 1 : 0;

  const id = crypto.randomUUID();
  const key = `raw/${id}.eml`;
  const raw = await new Response(message.raw).arrayBuffer();

  await env.MAIL_BUCKET.put(key, raw, {
    httpMetadata: { contentType: "message/rfc822" },
  });

  // Une réponse rejoint la conversation du message auquel elle répond.
  let thread = null;
  try {
    thread = await findThread(env, { refs: referencedIds(headers), subject, correspondent: from.address });
  } catch (err) {
    console.error("Recherche de la conversation impossible :", err);
  }

  try {
    await env.DB.prepare(
      `INSERT INTO messages
         (id, folder, direction, from_addr, from_name, to_addr, subject, message_id, date, size, is_read, r2_key, thread_id)
       VALUES (?1, ?11, 'in', ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?12, ?9, ?10)`
    )
      .bind(
        id,
        from.address.slice(0, 320),
        from.name.slice(0, 200),
        recipient.slice(0, 320),
        subject.slice(0, 500),
        (headers.get("message-id") || "").trim().slice(0, 500) || null,
        Date.now(),
        raw.byteLength,
        key,
        thread || id,
        folder,
        read
      )
      .run();
  } catch (err) {
    // Sans ligne d'index, le message serait invisible : on nettoie et on laisse
    // l'erreur remonter pour que l'expéditeur reçoive un avis d'échec.
    await env.MAIL_BUCKET.delete(key).catch(() => {});
    throw err;
  }

  // Toute adresse qui reçoit du courrier apparaît dans la liste des adresses, où l'on peut la couper.
  if (!alias && ADDRESS.test(recipient)) {
    await env.DB.prepare("INSERT OR IGNORE INTO aliases (address, label, enabled, created) VALUES (?1, '', 1, ?2)")
      .bind(recipient, Date.now())
      .run()
      .catch((err) => console.error("Adresse non enregistrée :", err));
  }

  return folder === "inbox" && !read;
}

// ------------------------------------------------- notifications poussées

const GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";
const FCM_URL = "https://fcm.googleapis.com";
const MAX_DEVICES = 10;

// Jeton d'accès Google, obtenu en signant une demande avec la clé du compte de service
// (secret FCM_SERVICE_ACCOUNT). Il vaut une heure : on le garde pour ne pas signer à chaque mail.
async function googleAccessToken(env, account) {
  const now = Date.now();
  const cached = await env.DB.prepare("SELECT value FROM cache WHERE key = 'fcm_token' AND expires > ?1")
    .bind(now + 60000)
    .first();
  if (cached) return cached.value;

  const enc = new TextEncoder();
  const issued = Math.floor(now / 1000);
  const header = toBase64Url(enc.encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claims = toBase64Url(
    enc.encode(
      JSON.stringify({
        iss: account.client_email,
        scope: "https://www.googleapis.com/auth/firebase.messaging",
        aud: account.token_uri || GOOGLE_TOKEN_URL,
        iat: issued,
        exp: issued + 3600,
      })
    )
  );
  const pem = String(account.private_key || "").replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "");
  const key = await crypto.subtle.importKey(
    "pkcs8",
    Uint8Array.from(atob(pem), (c) => c.charCodeAt(0)),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, enc.encode(`${header}.${claims}`)));

  const response = await fetch(env.FCM_TOKEN_URL || account.token_uri || GOOGLE_TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: `${header}.${claims}.${toBase64Url(signature)}`,
    }).toString(),
  });
  if (!response.ok) throw new Error(`Google a refusé la clé du compte de service (${response.status}).`);
  const data = await response.json();
  await env.DB.prepare("INSERT OR REPLACE INTO cache (key, value, expires) VALUES ('fcm_token', ?1, ?2)")
    .bind(data.access_token, now + (Number(data.expires_in) || 3600) * 1000)
    .run();
  return data.access_token;
}

// Prévient chaque téléphone déclaré qu'un message est arrivé. Le signal ne contient rien du
// message : l'appli vient ensuite le chercher elle-même sur ce serveur.
async function pushNewMail(env) {
  if (!env.FCM_SERVICE_ACCOUNT) return { sent: 0 };
  let account;
  try {
    account = JSON.parse(env.FCM_SERVICE_ACCOUNT);
  } catch {
    throw new Error("Le secret FCM_SERVICE_ACCOUNT n'est pas un fichier de clé valide.");
  }
  const devices = await env.DB.prepare(`SELECT token FROM devices ORDER BY created DESC LIMIT ${MAX_DEVICES}`).all();
  const tokens = (devices.results || []).map((d) => d.token);
  if (!tokens.length) return { sent: 0 };

  const access = await googleAccessToken(env, account);
  const url = `${env.FCM_API_URL || FCM_URL}/v1/projects/${account.project_id}/messages:send`;
  let sent = 0;
  for (const token of tokens) {
    const response = await fetch(url, {
      method: "POST",
      headers: { Authorization: `Bearer ${access}`, "Content-Type": "application/json" },
      body: JSON.stringify({ message: { token, android: { priority: "HIGH" }, data: { type: "new-mail" } } }),
    });
    if (response.ok) {
      sent++;
    } else if (response.status === 404) {
      // L'appli a été désinstallée ou son jeton a changé : ce téléphone n'est plus à prévenir.
      await env.DB.prepare("DELETE FROM devices WHERE token = ?1").bind(token).run();
    } else {
      if (response.status === 401) await env.DB.prepare("DELETE FROM cache WHERE key = 'fcm_token'").run();
      console.error("Notification refusée :", response.status, (await response.text()).slice(0, 300));
    }
  }
  return { sent };
}

async function saveDevice(request, env, remove) {
  const body = await readJson(request);
  const token = body && typeof body.token === "string" ? body.token.trim() : "";
  if (!/^[A-Za-z0-9_:.\-]{20,4096}$/.test(token)) return json({ error: "Identifiant d'appareil invalide." }, 400);
  if (remove) {
    await env.DB.prepare("DELETE FROM devices WHERE token = ?1").bind(token).run();
  } else {
    await env.DB.prepare("INSERT OR REPLACE INTO devices (token, created) VALUES (?1, ?2)").bind(token, Date.now()).run();
  }
  return json({ ok: true, push: Boolean(env.FCM_SERVICE_ACCOUNT) });
}

// ------------------------------------------------------- adresses et règles

const ADDRESS = /^[a-z0-9._+-]{1,64}@[a-z0-9.-]{1,255}$/;
const RULE_FIELDS = ["from", "to", "subject"];
const RULE_ACTIONS = ["archive", "trash", "read", "block"];
const MAX_RULES = 100;

const mainAddress = (env) => parseAddress(env.MAIL_FROM || "").address.toLowerCase();
const ownDomain = (env) => mainAddress(env).split("@")[1] || "";

// La première règle dont le texte figure dans le champ visé s'applique ; les suivantes sont ignorées.
function matchRule(rules, fields) {
  for (const rule of rules) {
    const needle = String(rule.value || "").trim().toLowerCase();
    if (needle && String(fields[rule.field] || "").toLowerCase().includes(needle)) return rule;
  }
  return null;
}

async function listAliases(env) {
  const rows = await env.DB.prepare(
    `SELECT a.address, a.label, a.enabled,
            (SELECT COUNT(*) FROM messages m WHERE m.to_addr = a.address AND m.direction = 'in') AS received
     FROM aliases a ORDER BY a.created`
  ).all();
  return json({
    main: mainAddress(env),
    domain: ownDomain(env),
    aliases: (rows.results || []).map((r) => ({
      address: r.address,
      label: r.label,
      enabled: Boolean(r.enabled),
      received: r.received,
    })),
  });
}

async function saveAlias(rawAddress, request, env) {
  let address = "";
  try {
    address = decodeURIComponent(rawAddress).trim().toLowerCase();
  } catch {
    /* adresse mal encodée : refusée juste après */
  }
  const domain = ownDomain(env);
  if (!ADDRESS.test(address) || !domain || !address.endsWith("@" + domain)) {
    return json({ error: `L'adresse doit se terminer par @${domain} et ne contenir que des lettres, chiffres, points ou tirets.` }, 400);
  }
  const body = (await readJson(request)) || {};
  const existing = await env.DB.prepare("SELECT label, enabled, created FROM aliases WHERE address = ?1").bind(address).first();
  const enabled = typeof body.enabled === "boolean" ? body.enabled : existing ? Boolean(existing.enabled) : true;
  if (!enabled && address === mainAddress(env)) {
    return json({ error: "L'adresse principale ne peut pas être coupée." }, 400);
  }
  const label = typeof body.label === "string" ? body.label.replace(/[\r\n]+/g, " ").trim().slice(0, 100) : existing ? existing.label : "";
  await env.DB.prepare("INSERT OR REPLACE INTO aliases (address, label, enabled, created) VALUES (?1, ?2, ?3, ?4)")
    .bind(address, label, enabled ? 1 : 0, existing ? existing.created : Date.now())
    .run();
  return json({ ok: true, address, label, enabled });
}

async function deleteAlias(rawAddress, env) {
  let address = "";
  try {
    address = decodeURIComponent(rawAddress).trim().toLowerCase();
  } catch {
    /* rien à effacer */
  }
  await env.DB.prepare("DELETE FROM aliases WHERE address = ?1").bind(address).run();
  return json({ ok: true });
}

async function listRules(env) {
  const rows = await env.DB.prepare("SELECT id, field, value, action FROM rules ORDER BY created").all();
  return json({ rules: rows.results || [] });
}

async function saveRule(id, request, env) {
  const body = await readJson(request);
  if (!body) return json({ error: "Requête illisible." }, 400);
  const value = String(body.value || "").replace(/[\r\n]+/g, " ").trim().slice(0, 200);
  if (!RULE_FIELDS.includes(body.field) || !RULE_ACTIONS.includes(body.action)) return json({ error: "Règle inconnue." }, 400);
  if (!value) return json({ error: "Indique le texte à repérer." }, 400);
  const existing = await env.DB.prepare("SELECT created FROM rules WHERE id = ?1").bind(id).first();
  if (!existing) {
    const count = await env.DB.prepare("SELECT COUNT(*) AS n FROM rules").first();
    if (count && count.n >= MAX_RULES) return json({ error: `Pas plus de ${MAX_RULES} règles.` }, 400);
  }
  await env.DB.prepare("INSERT OR REPLACE INTO rules (id, field, value, action, created) VALUES (?1, ?2, ?3, ?4, ?5)")
    .bind(id, body.field, value, body.action, existing ? existing.created : Date.now())
    .run();
  return json({ ok: true });
}

async function deleteRule(id, env) {
  await env.DB.prepare("DELETE FROM rules WHERE id = ?1").bind(id).run();
  return json({ ok: true });
}

// Les adresses depuis lesquelles on peut écrire : la principale, puis les autres encore actives.
async function sendingAddresses(env) {
  const main = mainAddress(env);
  const domain = ownDomain(env);
  try {
    const rows = await env.DB.prepare("SELECT address FROM aliases WHERE enabled = 1 ORDER BY created").all();
    const others = (rows.results || []).map((r) => r.address).filter((a) => a !== main && a.endsWith("@" + domain));
    return [main, ...others].filter(Boolean);
  } catch {
    return [main].filter(Boolean);
  }
}

// ------------------------------------------------------------------- routes

async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;

  const method = request.method.toUpperCase();

  // Lien signé et temporaire vers une pièce jointe : c'est par là que le
  // service d'envoi vient chercher les fichiers au moment de l'expédition.
  if (path.startsWith("/att/")) {
    if (method === "GET" && env.APP_PASSWORD) return signedAttachment(path.slice(5), url, env);
    return new Response("Introuvable", { status: 404 });
  }

  if (!path.startsWith("/api/")) {
    if (env.ASSETS) return env.ASSETS.fetch(request);
    return new Response("Introuvable", { status: 404 });
  }

  if (!env.APP_PASSWORD) {
    return json({ error: "Le mot de passe du webmail n'est pas configuré (secret APP_PASSWORD)." }, 503);
  }

  // Les requêtes qui modifient quelque chose doivent venir de la page elle-même,
  // ou de l'appli : elle n'est pas un navigateur (pas d'en-tête Origin) et envoie
  // un en-tête maison qu'une page web tierce ne peut pas ajouter.
  const origin = request.headers.get("Origin");
  const fromApp = !origin && Boolean(request.headers.get("X-Courrier-App"));
  if (method !== "GET" && method !== "HEAD") {
    if (origin !== url.origin && !fromApp) return json({ error: "Origine de la requête refusée." }, 403);
  }

  if (path === "/api/login" && method === "POST") return login(request, env, fromApp);
  if (path === "/api/logout" && method === "POST") return logout();

  const authed = await isAuthenticated(request, env);

  if (path === "/api/me" && method === "GET") {
    if (!authed) return json({ authenticated: false });
    const from = parseAddress(env.MAIL_FROM || "");
    return json({
      authenticated: true,
      from: env.MAIL_FROM || "",
      address: from.address,
      addresses: await sendingAddresses(env),
      push: Boolean(env.FCM_SERVICE_ACCOUNT),
      canSend: Boolean(env.RESEND_API_KEY && from.address),
    });
  }

  if (!authed) return json({ error: "Connexion requise." }, 401);

  if (path === "/api/messages" && method === "GET") return listMessages(url, env);
  if (path === "/api/send" && method === "POST") return sendMessage(request, url, env);
  if (path === "/api/trash/empty" && method === "POST") return emptyTrash(env);
  if (path === "/api/messages/batch" && method === "POST") return batchMessages(request, env);
  if (path === "/api/drafts" && method === "GET") return listDrafts(env);
  if (path === "/api/devices" && (method === "PUT" || method === "DELETE")) return saveDevice(request, env, method === "DELETE");
  if (path === "/api/aliases" && method === "GET") return listAliases(env);
  if (path === "/api/rules" && method === "GET") return listRules(env);

  const alias = path.match(/^\/api\/aliases\/([^/]{3,400})$/);
  if (alias) {
    if (method === "PUT") return saveAlias(alias[1], request, env);
    if (method === "DELETE") return deleteAlias(alias[1], env);
  }

  const rule = path.match(/^\/api\/rules\/([0-9a-f-]{36})$/);
  if (rule) {
    if (method === "PUT") return saveRule(rule[1], request, env);
    if (method === "DELETE") return deleteRule(rule[1], env);
  }

  const draft = path.match(/^\/api\/drafts\/([0-9a-f-]{36})$/);
  if (draft) {
    if (method === "PUT") return saveDraft(draft[1], request, env);
    if (method === "DELETE") return deleteDraft(draft[1], env);
  }

  const thread = path.match(/^\/api\/threads\/([0-9a-f-]{36})$/);
  if (thread && method === "GET") return listThread(thread[1], env);

  const upload = path.match(/^\/api\/uploads\/([0-9a-f-]{36})$/);
  if (upload) {
    if (method === "PUT") return storeUpload(upload[1], request, url, env);
    if (method === "GET") return readUpload(upload[1], env);
    if (method === "DELETE") return removeUpload(upload[1], env);
  }

  const match = path.match(/^\/api\/messages\/([0-9a-f-]{36})(\/raw)?$/);
  if (match) {
    const id = match[1];
    if (match[2]) {
      if (method === "GET") return rawMessage(id, env);
    } else {
      if (method === "GET") return getMessage(id, env);
      if (method === "PATCH") return updateMessage(id, request, env);
      if (method === "DELETE") return deleteMessage(id, env);
    }
  }

  return json({ error: "Route inconnue." }, 404);
}

// ---------------------------------------------------------------- connexion

async function login(request, env, fromApp = false) {
  const ip = request.headers.get("CF-Connecting-IP") || "inconnue";
  const now = Date.now();

  const recent = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM login_failures WHERE ip = ?1 AND ts > ?2"
  )
    .bind(ip, now - LOGIN_WINDOW_MS)
    .first();
  if (recent && recent.n >= MAX_LOGIN_FAILURES) {
    return json({ error: "Trop d'essais. Réessaie dans un quart d'heure." }, 429);
  }

  const body = await readJson(request);
  const password = body && typeof body.password === "string" ? body.password : "";

  if (!(await sameSecret(tidySecret(password), tidySecret(env.APP_PASSWORD)))) {
    await env.DB.batch([
      env.DB.prepare("INSERT INTO login_failures (ip, ts) VALUES (?1, ?2)").bind(ip, now),
      env.DB.prepare("DELETE FROM login_failures WHERE ts < ?1").bind(now - LOGIN_WINDOW_MS),
    ]);
    return json({ error: "Mot de passe incorrect." }, 401);
  }

  // L'appli reçoit son jeton dans la réponse ; le navigateur, dans un cookie
  // que les scripts de la page ne peuvent pas lire.
  if (fromApp) return json({ ok: true, token: await createSession(env, APP_SESSION_DAYS) });

  const token = await createSession(env, SESSION_DAYS);
  return json(
    { ok: true },
    200,
    { "Set-Cookie": sessionCookie(token, SESSION_DAYS * 24 * 3600) }
  );
}

function logout() {
  return json({ ok: true }, 200, { "Set-Cookie": sessionCookie("", 0) });
}

function sessionCookie(value, maxAge) {
  return `${SESSION_COOKIE}=${value}; Path=/; Max-Age=${maxAge}; HttpOnly; Secure; SameSite=Strict`;
}

async function sessionKey(env) {
  // La clé de signature dérive du mot de passe : le changer déconnecte toutes les sessions.
  const material = new TextEncoder().encode("valentin-mail-session:" + env.APP_PASSWORD);
  const digest = await crypto.subtle.digest("SHA-256", material);
  return crypto.subtle.importKey("raw", digest, { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}

async function createSession(env, days = SESSION_DAYS) {
  const expires = String(Date.now() + days * 24 * 3600 * 1000);
  const signature = await crypto.subtle.sign("HMAC", await sessionKey(env), new TextEncoder().encode(expires));
  return `${expires}.${toBase64Url(new Uint8Array(signature))}`;
}

async function isAuthenticated(request, env) {
  const bearer = (request.headers.get("Authorization") || "").match(/^Bearer\s+(\S+)$/i);
  const token = bearer ? bearer[1] : readCookie(request, SESSION_COOKIE);
  if (!token) return false;
  const dot = token.indexOf(".");
  if (dot < 1) return false;
  const expires = token.slice(0, dot);
  const signature = token.slice(dot + 1);
  if (!/^\d{10,16}$/.test(expires) || Number(expires) < Date.now()) return false;
  try {
    return await crypto.subtle.verify(
      "HMAC",
      await sessionKey(env),
      fromBase64Url(signature),
      new TextEncoder().encode(expires)
    );
  } catch {
    return false;
  }
}

// Un espace ou un retour à la ligne glissé au début ou à la fin (clavier de
// téléphone, copier-coller) ne doit pas fausser la comparaison.
function tidySecret(value) {
  return String(value == null ? "" : value).normalize("NFC").trim();
}

async function sameSecret(a, b) {
  // Comparaison à temps constant, sur des empreintes de même longueur.
  const enc = new TextEncoder();
  const [da, db] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(String(a))),
    crypto.subtle.digest("SHA-256", enc.encode(String(b))),
  ]);
  const x = new Uint8Array(da);
  const y = new Uint8Array(db);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

function readCookie(request, name) {
  const header = request.headers.get("Cookie") || "";
  for (const part of header.split(";")) {
    const eq = part.indexOf("=");
    if (eq === -1) continue;
    if (part.slice(0, eq).trim() === name) return part.slice(eq + 1).trim();
  }
  return "";
}

// ----------------------------------------------------------------- messages

const LIST_COLUMNS =
  "id, folder, direction, from_addr, from_name, to_addr, subject, message_id, date, size, is_read, thread_id";

// Nombre de messages de la même conversation, tous dossiers confondus sauf la corbeille.
const THREAD_COUNT =
  "(SELECT COUNT(*) FROM messages t WHERE t.thread_id = messages.thread_id AND t.folder != 'trash') AS thread_count";

// Identifiants de message cités par un mail : celui auquel il répond, et le fil qui précède.
function referencedIds(headers) {
  const all = `${headers.get("in-reply-to") || ""} ${headers.get("references") || ""}`;
  return [...new Set(all.match(/<[^<>\s]+>/g) || [])].slice(-30);
}

const REPLY_PREFIX = /^\s*(re|ré|rép|aw|tr|fwd?|wg)\s*(\[\d+\])?\s*:\s*/i;

// L'objet sans ses « Re: » et « Fwd: » accumulés, pour comparer deux messages d'un même échange.
function subjectKey(subject) {
  let s = String(subject || "");
  for (let i = 0; i < 10 && REPLY_PREFIX.test(s); i++) s = s.replace(REPLY_PREFIX, "");
  return s.replace(/\s+/g, " ").trim().toLowerCase();
}

async function findThread(env, { refs, subject, correspondent }) {
  if (refs.length) {
    const row = await env.DB.prepare(
      `SELECT COALESCE(thread_id, id) AS thread FROM messages
       WHERE message_id IN (${refs.map(() => "?").join(",")}) ORDER BY date DESC LIMIT 1`
    )
      .bind(...refs)
      .first();
    if (row) return row.thread;
  }

  // Une réponse à l'un de nos envois cite un identifiant attribué par le service
  // d'envoi, que ce serveur ne connaît pas. On se rabat alors sur « même
  // correspondant, même objet, récemment » — seulement pour ce qui est une réponse.
  const key = subjectKey(subject);
  if (!key || !correspondent || !(refs.length || REPLY_PREFIX.test(subject))) return null;
  const like = "%" + correspondent.replace(/[\\%_]/g, "\\$&") + "%";
  const rows = await env.DB.prepare(
    `SELECT COALESCE(thread_id, id) AS thread, subject FROM messages
     WHERE date > ?1 AND folder != 'trash' AND (from_addr = ?2 OR to_addr LIKE ?3 ESCAPE '\\')
     ORDER BY date DESC LIMIT 40`
  )
    .bind(Date.now() - THREAD_WINDOW_MS, correspondent, like)
    .all();
  const hit = (rows.results || []).find((r) => subjectKey(r.subject) === key);
  return hit ? hit.thread : null;
}

async function listThread(id, env) {
  const rows = await env.DB.prepare(
    `SELECT ${LIST_COLUMNS} FROM messages WHERE thread_id = ?1 AND folder != 'trash' ORDER BY date ASC LIMIT 200`
  )
    .bind(id)
    .all();
  return json({ messages: rows.results || [] });
}

async function listMessages(url, env) {
  const folder = url.searchParams.get("folder") || "inbox";
  if (!FOLDERS.includes(folder)) return json({ error: "Dossier inconnu." }, 400);

  const q = (url.searchParams.get("q") || "").trim().slice(0, 100);
  const before = Number(url.searchParams.get("before")) || 0;
  const after = Number(url.searchParams.get("after")) || 0;

  const where = ["folder = ?"];
  const params = [folder];
  if (before > 0) {
    where.push("date < ?");
    params.push(before);
  }
  if (after > 0) {
    where.push("date > ?");
    params.push(after);
  }
  if (q) {
    const like = "%" + q.replace(/[\\%_]/g, "\\$&") + "%";
    where.push(
      "(subject LIKE ? ESCAPE '\\' OR from_addr LIKE ? ESCAPE '\\' OR from_name LIKE ? ESCAPE '\\' OR to_addr LIKE ? ESCAPE '\\')"
    );
    params.push(like, like, like, like);
  }

  const [list, unread] = await env.DB.batch([
    env.DB.prepare(
      `SELECT ${LIST_COLUMNS}, ${THREAD_COUNT} FROM messages WHERE ${where.join(" AND ")} ORDER BY date DESC LIMIT ${PAGE_SIZE + 1}`
    ).bind(...params),
    env.DB.prepare("SELECT COUNT(*) AS n FROM messages WHERE folder = 'inbox' AND is_read = 0"),
  ]);

  const rows = list.results || [];
  const hasMore = rows.length > PAGE_SIZE;
  return json({
    messages: hasMore ? rows.slice(0, PAGE_SIZE) : rows,
    hasMore,
    unread: unread.results && unread.results[0] ? unread.results[0].n : 0,
  });
}

async function findMessage(id, env) {
  return env.DB.prepare(`SELECT ${LIST_COLUMNS}, r2_key FROM messages WHERE id = ?1`).bind(id).first();
}

async function getMessage(id, env) {
  const row = await findMessage(id, env);
  if (!row) return json({ error: "Message introuvable." }, 404);
  delete row.r2_key;
  const files = await env.DB.prepare("SELECT id, name, type, size FROM uploads WHERE message_id = ?1 ORDER BY rowid")
    .bind(id)
    .all();
  return json({ message: row, attachments: files.results || [] });
}

async function rawMessage(id, env) {
  const row = await findMessage(id, env);
  if (!row) return json({ error: "Message introuvable." }, 404);
  const object = await env.MAIL_BUCKET.get(row.r2_key);
  if (!object) return json({ error: "Le contenu de ce message est introuvable dans le stockage." }, 404);
  return new Response(object.body, {
    headers: {
      // Jamais interprété comme une page : le navigateur le reçoit comme un fichier.
      "Content-Type": "application/octet-stream",
      "Content-Disposition": 'attachment; filename="message.eml"',
      "X-Content-Type-Options": "nosniff",
      "Cache-Control": "private, no-store",
    },
  });
}

async function updateMessage(id, request, env) {
  const body = await readJson(request);
  if (!body) return json({ error: "Requête illisible." }, 400);

  const sets = [];
  const params = [];
  if (typeof body.is_read === "boolean") {
    sets.push("is_read = ?");
    params.push(body.is_read ? 1 : 0);
  }
  if (typeof body.folder === "string") {
    if (!FOLDERS.includes(body.folder)) return json({ error: "Dossier inconnu." }, 400);
    sets.push("folder = ?");
    params.push(body.folder);
  }
  if (!sets.length) return json({ error: "Rien à modifier." }, 400);

  const result = await env.DB.prepare(`UPDATE messages SET ${sets.join(", ")} WHERE id = ?`)
    .bind(...params, id)
    .run();
  if (!result.meta || !result.meta.changes) return json({ error: "Message introuvable." }, 404);
  return json({ ok: true });
}

async function deleteMessage(id, env) {
  const row = await findMessage(id, env);
  if (!row) return json({ error: "Message introuvable." }, 404);

  if (row.folder !== "trash") {
    await env.DB.prepare("UPDATE messages SET folder = 'trash' WHERE id = ?1").bind(id).run();
    return json({ ok: true, deleted: false });
  }

  const files = await env.DB.prepare("SELECT id FROM uploads WHERE message_id = ?1").bind(id).all();
  await env.MAIL_BUCKET.delete([row.r2_key, ...(files.results || []).map((f) => `uploads/${f.id}`)]);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM uploads WHERE message_id = ?1").bind(id),
    env.DB.prepare("DELETE FROM messages WHERE id = ?1").bind(id),
  ]);
  return json({ ok: true, deleted: true });
}

// Une même action sur plusieurs messages à la fois : lu ou non lu, changement de dossier, suppression.
async function batchMessages(request, env) {
  const body = await readJson(request);
  if (!body || !Array.isArray(body.ids)) return json({ error: "Requête illisible." }, 400);
  const ids = [...new Set(body.ids.map(String))];
  if (!ids.length) return json({ ok: true, changed: 0, deleted: 0 });
  if (ids.length > MAX_BATCH) return json({ error: `Pas plus de ${MAX_BATCH} messages à la fois.` }, 400);
  if (ids.some((id) => !/^[0-9a-f-]{36}$/.test(id))) return json({ error: "Message invalide." }, 400);
  const marks = ids.map(() => "?").join(",");

  if (body.delete === true) {
    // Comme pour un seul message : d'abord la corbeille, puis la suppression pour de bon.
    const rows = await env.DB.prepare(`SELECT id, folder, r2_key FROM messages WHERE id IN (${marks})`).bind(...ids).all();
    const gone = (rows.results || []).filter((r) => r.folder === "trash");
    const toTrash = (rows.results || []).filter((r) => r.folder !== "trash").map((r) => r.id);
    if (toTrash.length) {
      await env.DB.prepare(`UPDATE messages SET folder = 'trash' WHERE id IN (${toTrash.map(() => "?").join(",")})`)
        .bind(...toTrash)
        .run();
    }
    if (gone.length) {
      const goneIds = gone.map((r) => r.id);
      const goneMarks = goneIds.map(() => "?").join(",");
      const files = await env.DB.prepare(`SELECT id FROM uploads WHERE message_id IN (${goneMarks})`).bind(...goneIds).all();
      await env.MAIL_BUCKET.delete([...gone.map((r) => r.r2_key), ...(files.results || []).map((f) => `uploads/${f.id}`)]);
      await env.DB.batch([
        env.DB.prepare(`DELETE FROM uploads WHERE message_id IN (${goneMarks})`).bind(...goneIds),
        env.DB.prepare(`DELETE FROM messages WHERE id IN (${goneMarks})`).bind(...goneIds),
      ]);
    }
    return json({ ok: true, changed: toTrash.length, deleted: gone.length });
  }

  const sets = [];
  const params = [];
  if (typeof body.is_read === "boolean") {
    sets.push("is_read = ?");
    params.push(body.is_read ? 1 : 0);
  }
  if (typeof body.folder === "string") {
    if (!FOLDERS.includes(body.folder)) return json({ error: "Dossier inconnu." }, 400);
    sets.push("folder = ?");
    params.push(body.folder);
  }
  if (!sets.length) return json({ error: "Rien à modifier." }, 400);
  const result = await env.DB.prepare(`UPDATE messages SET ${sets.join(", ")} WHERE id IN (${marks})`)
    .bind(...params, ...ids)
    .run();
  return json({ ok: true, changed: result.meta ? result.meta.changes : 0, deleted: 0 });
}

// --------------------------------------------------------------- brouillons

// Les fichiers d'un brouillon portent cette marque à la place d'un identifiant de message.
const draftTag = (id) => `draft:${id}`;

async function listDrafts(env) {
  const rows = await env.DB.prepare(
    "SELECT id, to_addr, cc, subject, text, reply_to_id, updated FROM drafts ORDER BY updated DESC LIMIT 200"
  ).all();
  const drafts = rows.results || [];
  const files = drafts.length
    ? await env.DB.prepare(
        `SELECT id, message_id, name, type, size FROM uploads WHERE message_id IN (${drafts.map(() => "?").join(",")}) ORDER BY rowid`
      )
        .bind(...drafts.map((d) => draftTag(d.id)))
        .all()
    : { results: [] };
  return json({
    drafts: drafts.map((d) => ({
      id: d.id,
      to: d.to_addr,
      cc: d.cc,
      subject: d.subject,
      text: d.text,
      reply_to_id: d.reply_to_id,
      updated: d.updated,
      attachments: (files.results || [])
        .filter((f) => f.message_id === draftTag(d.id))
        .map((f) => ({ id: f.id, name: f.name, type: f.type, size: f.size })),
    })),
  });
}

async function saveDraft(id, request, env) {
  const body = await readJson(request);
  if (!body) return json({ error: "Requête illisible." }, 400);
  const text = String(body.text || "");
  if (text.length > 500000) return json({ error: "Le message est trop long." }, 400);
  const replyTo = typeof body.reply_to_id === "string" && /^[0-9a-f-]{36}$/.test(body.reply_to_id) ? body.reply_to_id : null;
  const fileIds = Array.isArray(body.attachment_ids) ? [...new Set(body.attachment_ids.map(String))] : [];
  if (fileIds.length > MAX_ATTACHMENTS) {
    return json({ error: `Pas plus de ${MAX_ATTACHMENTS} pièces jointes par message.` }, 400);
  }
  if (fileIds.some((f) => !/^[0-9a-f-]{36}$/.test(f))) return json({ error: "Pièce jointe invalide." }, 400);

  const tag = draftTag(id);
  if (fileIds.length) {
    const found = await env.DB.prepare(
      `SELECT id FROM uploads WHERE (message_id IS NULL OR message_id = ?) AND id IN (${fileIds.map(() => "?").join(",")})`
    )
      .bind(tag, ...fileIds)
      .all();
    if ((found.results || []).length !== fileIds.length) {
      return json({ error: "Une pièce jointe est introuvable sur le serveur. Rajoute-la, puis enregistre à nouveau." }, 400);
    }
  }

  const now = Date.now();
  const statements = [
    env.DB.prepare(
      `INSERT INTO drafts (id, to_addr, cc, subject, text, reply_to_id, updated) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
       ON CONFLICT(id) DO UPDATE SET to_addr = ?2, cc = ?3, subject = ?4, text = ?5, reply_to_id = ?6, updated = ?7`
    ).bind(
      id,
      String(body.to || "").slice(0, 2000),
      String(body.cc || "").slice(0, 2000),
      String(body.subject || "").replace(/[\r\n]+/g, " ").slice(0, 500),
      text,
      replyTo,
      now
    ),
    // Un fichier retiré du brouillon redevient orphelin : le ménage habituel l'effacera.
    env.DB.prepare("UPDATE uploads SET message_id = NULL WHERE message_id = ?1").bind(tag),
  ];
  if (fileIds.length) {
    statements.push(
      env.DB.prepare(`UPDATE uploads SET message_id = ? WHERE id IN (${fileIds.map(() => "?").join(",")})`).bind(tag, ...fileIds)
    );
  }
  await env.DB.batch(statements);
  return json({ ok: true, updated: now });
}

async function deleteDraft(id, env) {
  const tag = draftTag(id);
  const files = await env.DB.prepare("SELECT id FROM uploads WHERE message_id = ?1").bind(tag).all();
  const keys = (files.results || []).map((f) => `uploads/${f.id}`);
  if (keys.length) await env.MAIL_BUCKET.delete(keys);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM uploads WHERE message_id = ?1").bind(tag),
    env.DB.prepare("DELETE FROM drafts WHERE id = ?1").bind(id),
  ]);
  return json({ ok: true });
}

async function emptyTrash(env) {
  let removed = 0;
  // Par lots, pour rester dans les limites d'une seule exécution.
  for (let round = 0; round < 5; round++) {
    const batch = await env.DB.prepare("SELECT id, r2_key FROM messages WHERE folder = 'trash' LIMIT 100").all();
    const rows = batch.results || [];
    if (!rows.length) break;
    const marks = rows.map(() => "?").join(",");
    const ids = rows.map((r) => r.id);
    const files = await env.DB.prepare(`SELECT id FROM uploads WHERE message_id IN (${marks})`).bind(...ids).all();
    await env.MAIL_BUCKET.delete([...rows.map((r) => r.r2_key), ...(files.results || []).map((f) => `uploads/${f.id}`)]);
    await env.DB.batch([
      env.DB.prepare(`DELETE FROM uploads WHERE message_id IN (${marks})`).bind(...ids),
      env.DB.prepare(`DELETE FROM messages WHERE id IN (${marks})`).bind(...ids),
    ]);
    removed += rows.length;
    if (rows.length < 100) break;
  }
  const left = await env.DB.prepare("SELECT COUNT(*) AS n FROM messages WHERE folder = 'trash'").first();
  return json({ ok: true, removed, remaining: left ? left.n : 0 });
}

// -------------------------------------------------------------------- envoi

async function sendMessage(request, url, env) {
  const sender = parseAddress(env.MAIL_FROM || "");
  if (!env.RESEND_API_KEY || !sender.address) {
    return json(
      { error: "L'envoi n'est pas configuré : il manque la clé Resend (RESEND_API_KEY) ou l'adresse d'expédition (MAIL_FROM)." },
      503
    );
  }

  const body = await readJson(request);
  if (!body) return json({ error: "Requête illisible." }, 400);

  const to = parseRecipients(body.to);
  const cc = parseRecipients(body.cc);
  if (to.invalid.length || cc.invalid.length) {
    return json({ error: "Adresse invalide : " + [...to.invalid, ...cc.invalid].join(", ") }, 400);
  }
  if (!to.list.length) return json({ error: "Indique au moins un destinataire." }, 400);
  if (to.list.length + cc.list.length > MAX_RECIPIENTS) {
    return json({ error: `Pas plus de ${MAX_RECIPIENTS} destinataires par message.` }, 400);
  }

  const subject = String(body.subject || "").replace(/[\r\n]+/g, " ").trim().slice(0, 500);
  const text = String(body.text || "");
  if (text.length > 500000) return json({ error: "Le message est trop long." }, 400);

  // Pièces jointes : déjà déposées une à une par PUT /api/uploads/:id.
  const fileIds = Array.isArray(body.attachment_ids) ? [...new Set(body.attachment_ids.map(String))] : [];
  if (fileIds.length > MAX_ATTACHMENTS) {
    return json({ error: `Pas plus de ${MAX_ATTACHMENTS} pièces jointes par message.` }, 400);
  }
  // Un brouillon qu'on envoie apporte les fichiers qui lui étaient rattachés.
  const draftId = typeof body.draft_id === "string" && /^[0-9a-f-]{36}$/.test(body.draft_id) ? body.draft_id : null;
  let files = [];
  if (fileIds.length) {
    if (fileIds.some((id) => !/^[0-9a-f-]{36}$/.test(id))) return json({ error: "Pièce jointe invalide." }, 400);
    const found = await env.DB.prepare(
      `SELECT id, name, type, size FROM uploads
       WHERE (message_id IS NULL OR message_id = ?) AND id IN (${fileIds.map(() => "?").join(",")})`
    )
      .bind(draftId ? draftTag(draftId) : "", ...fileIds)
      .all();
    files = fileIds.map((id) => (found.results || []).find((f) => f.id === id)).filter(Boolean);
    if (files.length !== fileIds.length) {
      return json({ error: "Une pièce jointe est introuvable sur le serveur. Rajoute-la, puis renvoie le message." }, 400);
    }
    if (files.reduce((total, f) => total + f.size, 0) > MAX_ATTACHMENTS_BYTES) {
      return json({ error: "Les pièces jointes dépassent 25 Mo au total." }, 400);
    }
  }

  if (!subject && !text.trim() && !files.length) return json({ error: "Le message est vide." }, 400);

  // Réponse : on rattache le message à la conversation d'origine.
  let inReplyTo = null;
  let thread = null;
  if (typeof body.reply_to_id === "string" && /^[0-9a-f-]{36}$/.test(body.reply_to_id)) {
    const original = await findMessage(body.reply_to_id, env);
    if (original && original.message_id) inReplyTo = original.message_id;
    if (original) thread = original.thread_id || original.id;
  }

  // Par défaut l'adresse principale ; sinon l'une des autres adresses actives du domaine.
  let fromAddress = sender.address;
  const wanted = typeof body.from === "string" ? body.from.trim().toLowerCase() : "";
  if (wanted && wanted !== sender.address.toLowerCase()) {
    if (!(await sendingAddresses(env)).includes(wanted)) {
      return json({ error: "Cette adresse d'expédition ne fait pas partie des tiennes." }, 400);
    }
    fromAddress = wanted;
  }
  const fromHeader = sender.name ? `${sender.name} <${fromAddress}>` : fromAddress;

  const payload = {
    from: fromHeader,
    to: to.list,
    subject: subject || "(sans objet)",
    text,
  };
  if (cc.list.length) payload.cc = cc.list;
  if (inReplyTo) payload.headers = { "In-Reply-To": inReplyTo, References: inReplyTo };
  if (files.length) {
    // Le service d'envoi télécharge lui-même chaque fichier par un lien signé :
    // rien de lourd ne transite par ce Worker.
    const expires = Date.now() + ATTACHMENT_LINK_MS;
    payload.attachments = await Promise.all(
      files.map(async (f) => ({ filename: f.name, path: await attachmentLink(url.origin, f.id, expires, env) }))
    );
  }

  let response;
  try {
    response = await fetch(env.RESEND_API_URL || RESEND_URL, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${env.RESEND_API_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
    });
  } catch (err) {
    console.error("Resend injoignable :", err);
    return json({ error: "Le service d'envoi est injoignable. Le message n'est pas parti." }, 502);
  }

  if (!response.ok) {
    let detail = "";
    try {
      const data = await response.json();
      detail = data && (data.message || data.error || data.name) ? String(data.message || data.error || data.name) : "";
    } catch {
      /* réponse non JSON */
    }
    console.error("Refus Resend :", response.status, detail);
    return json(
      { error: `Le service d'envoi a refusé le message (${response.status})${detail ? " : " + detail : "."}` },
      502
    );
  }

  // Le message est parti : on en garde une copie dans « Envoyés ».
  const id = crypto.randomUUID();
  const key = `raw/${id}.eml`;
  const now = Date.now();
  let stored = true;
  try {
    const eml = buildEml({
      from: fromHeader,
      to: to.list,
      cc: cc.list,
      subject: payload.subject,
      text,
      date: new Date(now),
      messageId: `<${id}@${sender.address.split("@")[1] || "local"}>`,
      inReplyTo,
    });
    const bytes = new TextEncoder().encode(eml);
    await env.MAIL_BUCKET.put(key, bytes, { httpMetadata: { contentType: "message/rfc822" } });
    await env.DB.prepare(
      `INSERT INTO messages
         (id, folder, direction, from_addr, from_name, to_addr, subject, message_id, date, size, is_read, r2_key, thread_id)
       VALUES (?1, 'sent', 'out', ?2, ?3, ?4, ?5, NULL, ?6, ?7, 1, ?8, ?9)`
    )
      .bind(id, fromAddress, sender.name, to.list.join(", ").slice(0, 320), payload.subject, now, bytes.byteLength, key, thread || id)
      .run();
    if (files.length) {
      await env.DB.prepare(`UPDATE uploads SET message_id = ? WHERE id IN (${files.map(() => "?").join(",")})`)
        .bind(id, ...files.map((f) => f.id))
        .run();
    }
  } catch (err) {
    stored = false;
    console.error("Copie dans Envoyés impossible :", err);
  }

  if (draftId) {
    // Le brouillon a servi : on l'efface, et ses fichiers restés sans message partiront au ménage.
    try {
      await env.DB.batch([
        env.DB.prepare("UPDATE uploads SET message_id = NULL WHERE message_id = ?1").bind(draftTag(draftId)),
        env.DB.prepare("DELETE FROM drafts WHERE id = ?1").bind(draftId),
      ]);
    } catch (err) {
      console.error("Effacement du brouillon impossible :", err);
    }
  }

  return json({ ok: true, stored });
}

// ----------------------------------------------------------- pièces jointes

function cleanFileName(value) {
  return String(value || "")
    .replace(/[\u0000-\u001f\u007f\u202a-\u202e\u2066-\u2069]/g, "")
    .replace(/[\\/]/g, "_")
    .trim()
    .slice(0, 150);
}

// Le fichier arrive en flux et part tel quel dans R2 : il n'est jamais chargé
// en mémoire ici.
async function storeUpload(id, request, url, env) {
  const name = cleanFileName(url.searchParams.get("name")) || "piece-jointe";
  const asked = (url.searchParams.get("type") || "").slice(0, 120);
  const type = /^[\w.+-]+\/[\w.+-]+$/.test(asked) ? asked : "application/octet-stream";
  const length = Number(request.headers.get("Content-Length"));

  if (!request.body || !Number.isFinite(length) || length <= 0) {
    return json({ error: "Fichier vide ou de taille inconnue." }, 400);
  }
  if (length > MAX_UPLOAD_BYTES) return json({ error: "Fichier trop lourd : 25 Mo maximum par pièce jointe." }, 413);

  const existing = await env.DB.prepare("SELECT id FROM uploads WHERE id = ?1").bind(id).first();
  if (existing) return json({ error: "Cette pièce jointe existe déjà." }, 409);

  const object = await env.MAIL_BUCKET.put(`uploads/${id}`, request.body, { httpMetadata: { contentType: type } });
  await env.DB.prepare(
    "INSERT INTO uploads (id, message_id, name, type, size, created) VALUES (?1, NULL, ?2, ?3, ?4, ?5)"
  )
    .bind(id, name, type, object.size, Date.now())
    .run();

  // Ménage : les fichiers déposés mais jamais envoyés disparaissent au bout d'un jour.
  const stale = await env.DB.prepare("SELECT id FROM uploads WHERE message_id IS NULL AND created < ?1 LIMIT 20")
    .bind(Date.now() - ORPHAN_UPLOAD_MS)
    .all();
  const staleIds = (stale.results || []).map((r) => r.id);
  if (staleIds.length) {
    await env.MAIL_BUCKET.delete(staleIds.map((x) => `uploads/${x}`));
    await env.DB.prepare(`DELETE FROM uploads WHERE id IN (${staleIds.map(() => "?").join(",")})`)
      .bind(...staleIds)
      .run();
  }

  return json({ ok: true, id, name, type, size: object.size });
}

function fileResponse(object, name) {
  const headers = {
    "Content-Type": (object.httpMetadata && object.httpMetadata.contentType) || "application/octet-stream",
    "X-Content-Type-Options": "nosniff",
    "Cache-Control": "private, no-store",
  };
  headers["Content-Disposition"] = name
    ? `attachment; filename*=UTF-8''${encodeURIComponent(name)}`
    : "attachment";
  return new Response(object.body, { headers });
}

async function readUpload(id, env) {
  const row = await env.DB.prepare("SELECT name FROM uploads WHERE id = ?1").bind(id).first();
  if (!row) return json({ error: "Pièce jointe introuvable." }, 404);
  const object = await env.MAIL_BUCKET.get(`uploads/${id}`);
  if (!object) return json({ error: "Pièce jointe introuvable dans le stockage." }, 404);
  return fileResponse(object, row.name);
}

// Retirer une pièce jointe d'un brouillon : seulement tant que le message n'est pas parti.
async function removeUpload(id, env) {
  const row = await env.DB.prepare("SELECT message_id FROM uploads WHERE id = ?1").bind(id).first();
  if (!row) return json({ ok: true });
  if (row.message_id && !row.message_id.startsWith("draft:")) {
    return json({ error: "Cette pièce jointe fait partie d'un message envoyé." }, 409);
  }
  await env.MAIL_BUCKET.delete(`uploads/${id}`);
  await env.DB.prepare("DELETE FROM uploads WHERE id = ?1").bind(id).run();
  return json({ ok: true });
}

async function attachmentKey(env) {
  const material = new TextEncoder().encode("valentin-mail-attachment:" + env.APP_PASSWORD);
  const digest = await crypto.subtle.digest("SHA-256", material);
  return crypto.subtle.importKey("raw", digest, { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}

async function attachmentLink(origin, id, expires, env) {
  const signature = await crypto.subtle.sign(
    "HMAC",
    await attachmentKey(env),
    new TextEncoder().encode(`${id}.${expires}`)
  );
  return `${origin}/att/${id}?e=${expires}&s=${toBase64Url(new Uint8Array(signature))}`;
}

async function signedAttachment(id, url, env) {
  const missing = new Response("Introuvable", { status: 404 });
  const expires = url.searchParams.get("e") || "";
  const signature = url.searchParams.get("s") || "";
  if (!/^[0-9a-f-]{36}$/.test(id) || !/^\d{10,16}$/.test(expires) || Number(expires) < Date.now()) return missing;
  let valid = false;
  try {
    valid = await crypto.subtle.verify(
      "HMAC",
      await attachmentKey(env),
      fromBase64Url(signature),
      new TextEncoder().encode(`${id}.${expires}`)
    );
  } catch {
    valid = false;
  }
  if (!valid) return missing;
  const object = await env.MAIL_BUCKET.get(`uploads/${id}`);
  if (!object) return missing;
  return fileResponse(object, "");
}

function parseRecipients(value) {
  const parts = Array.isArray(value) ? value : String(value || "").split(/[,;\n]+/);
  const list = [];
  const invalid = [];
  for (const part of parts) {
    const raw = String(part || "").trim();
    if (!raw) continue;
    const { address } = parseAddress(raw);
    if (/^[^\s@<>(),;:"]+@[^\s@<>(),;:"]+\.[^\s@<>(),;:"]{2,}$/.test(address)) {
      if (!list.includes(address)) list.push(address);
    } else {
      invalid.push(raw.slice(0, 80));
    }
  }
  return { list, invalid };
}

// Fabrique un message texte simple (RFC 5322) pour la copie « Envoyés ».
function buildEml({ from, to, cc, subject, text, date, messageId, inReplyTo }) {
  const sender = parseAddress(from);
  const lines = [
    `From: ${sender.name ? encodeHeaderWord(sender.name) + " " : ""}<${sender.address}>`,
    `To: ${to.join(", ")}`,
  ];
  if (cc && cc.length) lines.push(`Cc: ${cc.join(", ")}`);
  lines.push(`Subject: ${encodeHeaderWord(subject)}`);
  lines.push(`Date: ${date.toUTCString().replace("GMT", "+0000")}`);
  lines.push(`Message-ID: ${messageId}`);
  if (inReplyTo) {
    lines.push(`In-Reply-To: ${inReplyTo}`);
    lines.push(`References: ${inReplyTo}`);
  }
  lines.push("MIME-Version: 1.0");
  lines.push("Content-Type: text/plain; charset=UTF-8");
  lines.push("Content-Transfer-Encoding: base64");
  lines.push("");
  const encoded = bytesToBase64(new TextEncoder().encode(text));
  for (let i = 0; i < encoded.length; i += 76) lines.push(encoded.slice(i, i + 76));
  return lines.join("\r\n") + "\r\n";
}

// Encode un en-tête non ASCII en « mots encodés » (RFC 2047), par morceaux courts.
function encodeHeaderWord(value) {
  const clean = String(value).replace(/[\r\n]+/g, " ");
  if (/^[\x20-\x7e]*$/.test(clean)) return clean;
  const words = [];
  let chunk = "";
  let chunkBytes = 0;
  const encoder = new TextEncoder();
  for (const char of clean) {
    const size = encoder.encode(char).length;
    if (chunkBytes + size > 42) {
      words.push(chunk);
      chunk = "";
      chunkBytes = 0;
    }
    chunk += char;
    chunkBytes += size;
  }
  if (chunk) words.push(chunk);
  return words.map((w) => `=?UTF-8?B?${bytesToBase64(encoder.encode(w))}?=`).join("\r\n ");
}

// ------------------------------------------------------- en-têtes et outils

// Décode les « mots encodés » des en-têtes (=?UTF-8?B?...?= ou ?Q?).
function decodeMimeWords(input) {
  const value = String(input || "").replace(/\r?\n[ \t]+/g, " ");
  if (!value.includes("=?")) return value;
  // Les espaces entre deux mots encodés voisins ne comptent pas.
  const joined = value.replace(/(\?=)\s+(=\?)/g, "$1$2");
  return joined.replace(/=\?([^?\s]+)\?([bBqQ])\?([^?]*)\?=/g, (whole, charset, encoding, text) => {
    try {
      let bytes;
      if (encoding.toLowerCase() === "b") {
        const binary = atob(text.replace(/\s/g, ""));
        bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
      } else {
        const out = [];
        const source = text.replace(/_/g, " ");
        for (let i = 0; i < source.length; i++) {
          const hex = source.slice(i + 1, i + 3);
          if (source[i] === "=" && /^[0-9A-Fa-f]{2}$/.test(hex)) {
            out.push(parseInt(hex, 16));
            i += 2;
          } else {
            out.push(source.charCodeAt(i) & 0xff);
          }
        }
        bytes = Uint8Array.from(out);
      }
      return decodeBytes(bytes, charset.split("*")[0]);
    } catch {
      return whole;
    }
  });
}

function decodeBytes(bytes, charset) {
  for (const label of [charset, "utf-8"]) {
    try {
      return new TextDecoder(label).decode(bytes);
    } catch {
      /* jeu de caractères inconnu : on essaie le suivant */
    }
  }
  let text = "";
  for (const byte of bytes) text += String.fromCharCode(byte);
  return text;
}

// « Nom <adresse> » ou « adresse » → { name, address }
function parseAddress(value) {
  const v = String(value || "").replace(/\r?\n[ \t]*/g, " ").trim();
  const angle = v.match(/^(.*?)<([^<>]+)>[^<>]*$/);
  if (angle) {
    const name = angle[1]
      .trim()
      .replace(/^"(.*)"$/, "$1")
      .replace(/\\(.)/g, "$1")
      .trim();
    return { name, address: angle[2].trim().toLowerCase() };
  }
  const bare = v.match(/[^\s<>(),;:"]+@[^\s<>(),;:"]+/);
  return { name: "", address: bare ? bare[0].toLowerCase() : "" };
}

async function readJson(request) {
  try {
    const data = await request.json();
    return data && typeof data === "object" ? data : null;
  } catch {
    return null;
  }
}

function json(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
      ...extraHeaders,
    },
  });
}

function bytesToBase64(bytes) {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary);
}

function toBase64Url(bytes) {
  return bytesToBase64(bytes).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fromBase64Url(value) {
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((value.length + 3) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}
