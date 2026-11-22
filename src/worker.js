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
const BATCH_SLICE = 80;
const MAX_LABELS = 200;
// La base gratuite accepte 100 000 écritures par jour, puis refuse tout jusqu'à minuit UTC — y compris
// le courrier qui arrive. Les travaux de masse (import, indexation) s'arrêtent donc bien avant.
const DAILY_WRITE_CAP = 60000;
const IMPORT_MAX_BYTES = 40 * 1024 * 1024;
const HEAD_BYTES = 128 * 1024;
const PART_HEAD_BYTES = 16 * 1024;
const MAX_PARTS = 60; // pièces jointes relevées par message
// Relever les pièces jointes d'un message, c'est le parcourir en entier : quelques millièmes de seconde de
// calcul pour un message ordinaire, bien plus pour un message de 25 Mo — or l'offre gratuite n'accorde que
// 10 ms par requête, avec une tolérance pour les dépassements isolés. Les petits messages sont donc relevés
// dès leur arrivée ; les lourds attendent une passe d'inventaire, où ils sont traités un par un.
const INLINE_SCAN_BYTES = 2 * 1024 * 1024;
const HEAVY_SCAN_BYTES = 4 * 1024 * 1024;
const SCAN_ATTEMPTS = 3;
// Ce qui reste à relever : jamais relu (0), ou relecture interrompue (2, 3…) tant qu'il reste des essais.
const TO_SCAN = `scanned != 1 AND scanned <= ${SCAN_ATTEMPTS}`;
const PART_HEAD_MAX = 256 * 1024; // le plus long début de pièce jointe qu'on puisse demander
const SESSION_SEEN_MS = 3600 * 1000; // la dernière activité d'un appareil est notée une fois par heure
const SESSION_CACHE_MS = 60 * 1000;
const ATTACHMENT_KINDS = ["image", "pdf", "doc", "media", "other"];

export default {
  async email(message, env, ctx) {
    const fresh = await receive(message, env);
    // Un nouveau message en réception : on prévient le téléphone, sans retarder la réponse au serveur expéditeur.
    if (fresh) ctx.waitUntil(pushNewMail(env).catch((err) => console.error("Notification non envoyée :", err)));
  },

  async fetch(request, env, ctx) {
    try {
      const response = await route(request, env);
      // Chaque consultation de la boîte fait avancer l'index de recherche de quelques messages,
      // une fois la réponse partie. La réception du courrier, elle, n'attend jamais ce travail.
      const path = new URL(request.url).pathname;
      if (response.status === 200 && (path === "/api/messages" || path === "/api/attachments")) {
        ctx.waitUntil(upkeep(env).catch((err) => console.error("Entretien impossible :", err)));
      }
      return response;
    } catch (err) {
      console.error("Erreur non gérée :", err && err.stack ? err.stack : err);
      return json({ error: "Erreur interne du serveur." }, 500);
    }
  },
};

// Petit entretien de fond : le texte de quelques messages entre dans la recherche, puis leurs pièces
// jointes dans l'inventaire.
async function upkeep(env) {
  await indexSome(env, 2);
  await scanSome(env, 2);
}

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
      env.DB.prepare("SELECT field, value, action, target FROM rules ORDER BY created"),
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
  // « file » range dans un dossier sans passer par la réception ; « label » range et laisse en réception.
  const skipInbox = rule && (rule.action === "archive" || rule.action === "file");
  const folder = skipInbox ? "archive" : rule && rule.action === "trash" ? "trash" : "inbox";
  const read = rule && rule.action === "read" ? 1 : 0;

  const id = crypto.randomUUID();
  const key = `raw/${id}.eml`;
  const raw = await new Response(message.raw).arrayBuffer();
  const now = Date.now();

  await env.MAIL_BUCKET.put(key, raw, {
    httpMetadata: { contentType: "message/rfc822" },
  });

  // La boîte d'où vient le message (une boîte Gmail qui transfère, sinon l'adresse visée) et ses pièces jointes.
  const box = forwardedBox(headers.get("x-forwarded-for"), recipient) || recipient;
  let parts = null;
  try {
    if (raw.byteLength <= INLINE_SCAN_BYTES) parts = scanParts(new Uint8Array(raw));
  } catch (err) {
    console.error("Pièces jointes non relevées :", err);
  }

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
         (id, folder, direction, from_addr, from_name, to_addr, subject, message_id, date, size, is_read, r2_key, thread_id, box, scanned)
       VALUES (?1, ?11, 'in', ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?12, ?9, ?10, ?13, ?14)`
    )
      .bind(
        id,
        from.address.slice(0, 320),
        from.name.slice(0, 200),
        recipient.slice(0, 320),
        subject.slice(0, 500),
        (headers.get("message-id") || "").trim().slice(0, 500) || null,
        now,
        raw.byteLength,
        key,
        thread || id,
        folder,
        read,
        box.slice(0, 320),
        parts ? 1 : 0
      )
      .run();
  } catch (err) {
    // Sans ligne d'index, le message serait invisible : on nettoie et on laisse
    // l'erreur remonter pour que l'expéditeur reçoive un avis d'échec.
    await env.MAIL_BUCKET.delete(key).catch(() => {});
    throw err;
  }

  if (parts && parts.length) {
    await env.DB.batch(attachmentStatements(env, id, now, parts)).catch((err) => console.error("Pièces jointes non inscrites :", err));
  }

  if (rule && (rule.action === "label" || rule.action === "file") && rule.target) {
    await env.DB.prepare("INSERT OR IGNORE INTO message_labels (message_id, label_id) SELECT ?1, id FROM labels WHERE id = ?2")
      .bind(id, rule.target)
      .run()
      .catch((err) => console.error("Message non rangé dans son dossier :", err));
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

// ------------------------------------------------- recherche dans le texte

const INDEX_BYTES = 256 * 1024; // on ne lit que le début d'un message : le texte y est, les pièces jointes suivent
const INDEX_CHARS = 20000;

function binaryString(bytes) {
  let out = "";
  for (let i = 0; i < bytes.length; i += 8192) out += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192));
  return out;
}

function binaryBytes(text) {
  const bytes = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i) & 255;
  return bytes;
}

function quotedPrintableBytes(text) {
  const clean = text.replace(/=\r?\n/g, "");
  const bytes = new Uint8Array(clean.length);
  let n = 0;
  for (let i = 0; i < clean.length; i++) {
    if (clean[i] === "=" && /^[0-9A-Fa-f]{2}$/.test(clean.substr(i + 1, 2))) {
      bytes[n++] = parseInt(clean.substr(i + 1, 2), 16);
      i += 2;
    } else {
      bytes[n++] = clean.charCodeAt(i) & 255;
    }
  }
  return bytes.subarray(0, n);
}

function htmlToText(html) {
  return html
    .replace(/<(script|style|head|title)[^>]*>[\s\S]*?<\/\1>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'");
}

// Lecture volontairement simple d'un message brut : son premier corps en texte, à défaut son
// corps HTML sans les balises. Les pièces jointes sont ignorées.
function readableText(raw, depth = 0) {
  const split = raw.search(/\r?\n\r?\n/);
  const head = (split < 0 ? raw : raw.slice(0, split)).replace(/\r?\n[ \t]+/g, " ");
  const body = split < 0 ? "" : raw.slice(split).replace(/^\r?\n\r?\n/, "");
  const typeLine = (/^content-type:([^\r\n]*)/im.exec(head) || [, " text/plain"])[1];
  const type = typeLine.split(";")[0].trim().toLowerCase() || "text/plain";
  const boundary = (/boundary="?([^";]+)"?/i.exec(typeLine) || [])[1];

  if (type.startsWith("multipart/")) {
    if (!boundary || depth > 5) return null;
    let html = null;
    for (const part of body.split("--" + boundary).slice(1)) {
      if (part.startsWith("--")) break;
      const found = readableText(part.replace(/^[ \t]*\r?\n/, ""), depth + 1);
      if (found && found.plain) return found;
      if (found && !html) html = found;
    }
    return html;
  }
  if ((type !== "text/plain" && type !== "text/html") || /^content-disposition:\s*attachment/im.test(head)) return null;

  const encoding = (/^content-transfer-encoding:\s*([^\s;]+)/im.exec(head) || [, "7bit"])[1].toLowerCase();
  const charset = (/charset="?([^";\s]+)"?/i.exec(typeLine) || [, "utf-8"])[1];
  let bytes;
  if (encoding === "base64") {
    const clean = body.replace(/[^A-Za-z0-9+/]/g, "");
    bytes = binaryBytes(atob(clean.slice(0, clean.length - (clean.length % 4))));
  } else if (encoding === "quoted-printable") {
    bytes = quotedPrintableBytes(body);
  } else {
    bytes = binaryBytes(body);
  }
  let text;
  try {
    text = new TextDecoder(charset).decode(bytes);
  } catch {
    text = new TextDecoder("utf-8").decode(bytes);
  }
  return { plain: type === "text/plain", text: type === "text/html" ? htmlToText(text) : text };
}

const tidyText = (text) => String(text || "").replace(/\s+/g, " ").trim().slice(0, INDEX_CHARS);

// Indexe quelques messages pas encore passés par là ; renvoie combien il en reste.
async function indexSome(env, limit) {
  const rows = await env.DB.prepare("SELECT rowid AS n, id, r2_key FROM messages WHERE indexed = 0 ORDER BY date DESC LIMIT ?1")
    .bind(limit)
    .all();
  const statements = [];
  for (const row of rows.results || []) {
    let text = "";
    try {
      const object = await env.MAIL_BUCKET.get(row.r2_key, { range: { offset: 0, length: INDEX_BYTES } });
      if (object) {
        const found = readableText(binaryString(new Uint8Array(await object.arrayBuffer())));
        text = found ? tidyText(found.text) : "";
      }
    } catch (err) {
      console.error("Texte illisible pour la recherche :", row.id, err);
    }
    statements.push(
      env.DB.prepare("INSERT OR REPLACE INTO messages_fts (rowid, id, body) VALUES (?1, ?2, ?3)").bind(row.n, row.id, text),
      env.DB.prepare("UPDATE messages SET indexed = 1 WHERE id = ?1").bind(row.id)
    );
  }
  if (!statements.length) return 0;
  await spend(env, rowsWritten(await env.DB.batch(statements)));
  const left = await env.DB.prepare("SELECT COUNT(*) AS n FROM messages WHERE indexed = 0").first();
  return left ? left.n : 0;
}

// Les mots cherchés, sous la forme attendue par l'index : tous présents, chacun pouvant être un début de mot.
function searchQuery(q) {
  const words = (q.toLowerCase().match(/[\p{L}\p{N}]+/gu) || []).slice(0, 6);
  return words.length ? words.map((w) => `"${w}"*`).join(" ") : "";
}

// Indexation demandée par la page d'import : quelques messages par appel, tant que le budget du jour le permet.
async function runIndex(request, env) {
  const body = (await readJson(request)) || {};
  const limit = Math.min(Math.max(Math.floor(Number(body.limit)) || 2, 1), 4);
  if (limit > 2 && (await spent(env)) >= DAILY_WRITE_CAP) return json({ error: QUOTA_REACHED, retry: nextReset() }, 429);
  const unindexed = await indexSome(env, limit);
  const unscanned = await scanSome(env, limit);
  return json({ ok: true, remaining: unindexed + unscanned });
}

// ------------------------------------------------ budget d'écritures du jour

const QUOTA_REACHED = "Le quota d'écritures du jour est atteint : la suite reprendra après minuit (heure universelle).";
const dayKey = (now = Date.now()) => "writes:" + new Date(now).toISOString().slice(0, 10);
const nextReset = (now = Date.now()) => Date.UTC(new Date(now).getUTCFullYear(), new Date(now).getUTCMonth(), new Date(now).getUTCDate() + 1);

// Lignes écrites par une ou plusieurs requêtes, telles que la base les compte.
function rowsWritten(results) {
  return (Array.isArray(results) ? results : [results]).reduce((n, r) => n + ((r && r.meta && r.meta.rows_written) || 0), 0);
}

async function spend(env, rows) {
  if (!(rows > 0)) return;
  const now = Date.now();
  await env.DB.prepare(
    `INSERT INTO cache (key, value, expires) VALUES (?1, ?2, ?3)
     ON CONFLICT(key) DO UPDATE SET value = CAST(value AS INTEGER) + ?2`
  )
    .bind(dayKey(now), rows + 1, now + 3 * 24 * 3600 * 1000)
    .run()
    .catch((err) => console.error("Compteur d'écritures non tenu :", err));
}

async function spent(env) {
  const row = await env.DB.prepare("SELECT value FROM cache WHERE key = ?1").bind(dayKey()).first();
  return row ? Number(row.value) || 0 : 0;
}

// ------------------------------------------------------------- réglages

async function readSettings(env) {
  try {
    const rows = await env.DB.prepare("SELECT key, value FROM settings").all();
    return Object.fromEntries((rows.results || []).map((r) => [r.key, r.value]));
  } catch {
    return {};
  }
}

async function saveSettings(request, env) {
  const body = await readJson(request);
  if (!body) return json({ error: "Requête illisible." }, 400);
  if (typeof body.signature === "string") {
    await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('signature', ?1)")
      .bind(body.signature.replace(/\r\n/g, "\n").trim().slice(0, 2000))
      .run();
  }
  // Identifiant du « client OAuth » créé dans Google Cloud pour l'import Gmail. Ce n'est pas un secret.
  if (typeof body.google_client_id === "string") {
    const clientId = body.google_client_id.trim();
    if (clientId && !/^[0-9]{6,20}-[a-z0-9]{10,60}\.apps\.googleusercontent\.com$/.test(clientId)) {
      return json({ error: "Cet identifiant ne ressemble pas à un identifiant client Google (il finit par .apps.googleusercontent.com)." }, 400);
    }
    await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('google_client_id', ?1)").bind(clientId).run();
  }
  const saved = await readSettings(env);
  return json({ ok: true, signature: saved.signature || "", google_client_id: saved.google_client_id || "" });
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
async function pushNewMail(env, type = "new-mail") {
  if (!env.FCM_SERVICE_ACCOUNT) return { sent: 0, devices: 0, refused: [] };
  let account;
  try {
    account = JSON.parse(env.FCM_SERVICE_ACCOUNT);
  } catch {
    throw new Error("Le secret FCM_SERVICE_ACCOUNT n'est pas un fichier de clé valide.");
  }
  const devices = await env.DB.prepare(`SELECT token FROM devices ORDER BY created DESC LIMIT ${MAX_DEVICES}`).all();
  const tokens = (devices.results || []).map((d) => d.token);
  if (!tokens.length) return { sent: 0, devices: 0, refused: [] };

  const access = await googleAccessToken(env, account);
  const url = `${env.FCM_API_URL || FCM_URL}/v1/projects/${account.project_id}/messages:send`;
  let sent = 0;
  const refused = [];
  for (const token of tokens) {
    const response = await fetch(url, {
      method: "POST",
      headers: { Authorization: `Bearer ${access}`, "Content-Type": "application/json" },
      body: JSON.stringify({ message: { token, android: { priority: "HIGH" }, data: { type } } }),
    });
    if (response.ok) {
      sent++;
      continue;
    }
    let reason = "";
    try {
      const data = await response.json();
      reason = data && data.error ? String(data.error.status || data.error.message || "") : "";
    } catch {
      /* réponse non JSON */
    }
    refused.push(`${response.status}${reason ? " " + reason : ""}`);
    if (response.status === 404) {
      // L'appli a été désinstallée ou son jeton a changé : ce téléphone n'est plus à prévenir.
      await env.DB.prepare("DELETE FROM devices WHERE token = ?1").bind(token).run();
    } else {
      if (response.status === 401) await env.DB.prepare("DELETE FROM cache WHERE key = 'fcm_token'").run();
      console.error("Notification refusée :", response.status, reason);
    }
  }
  return { sent, devices: tokens.length, refused };
}

// Essai demandé depuis l'appli : envoie un signal d'essai et dit ce que Google a répondu.
async function testPush(env) {
  if (!env.FCM_SERVICE_ACCOUNT) {
    return json({ ok: false, error: "Le serveur n'a pas la clé Firebase (secret FCM_SERVICE_ACCOUNT)." });
  }
  try {
    const result = await pushNewMail(env, "test");
    if (!result.devices) return json({ ok: false, error: "Aucun téléphone n'est déclaré auprès du serveur." });
    if (!result.sent) return json({ ok: false, error: `Google a refusé le signal (${result.refused.join(", ")}).` });
    return json({ ok: true, sent: result.sent, devices: result.devices });
  } catch (err) {
    return json({ ok: false, error: String((err && err.message) || err) });
  }
}

async function saveDevice(request, env, remove, session) {
  const body = await readJson(request);
  const token = body && typeof body.token === "string" ? body.token.trim() : "";
  if (!/^[A-Za-z0-9_:.\-]{20,4096}$/.test(token)) return json({ error: "Identifiant d'appareil invalide." }, 400);
  if (remove) {
    await env.DB.prepare("DELETE FROM devices WHERE token = ?1").bind(token).run();
  } else {
    // On retient par quelle session l'appareil s'est inscrit : la fermer à distance coupe aussi ses notifications.
    await env.DB.prepare("INSERT OR REPLACE INTO devices (token, created, session) VALUES (?1, ?2, ?3)")
      .bind(token, Date.now(), session ? session.id : null)
      .run();
  }
  return json({ ok: true, push: Boolean(env.FCM_SERVICE_ACCOUNT) });
}

// ------------------------------------------------------- adresses et règles

const ADDRESS = /^[a-z0-9._+-]{1,64}@[a-z0-9.-]{1,255}$/;
const RULE_FIELDS = ["from", "to", "subject"];
const RULE_ACTIONS = ["archive", "trash", "read", "block", "label", "file"];
const UUID = /^[0-9a-f-]{36}$/;
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
  const rows = await env.DB.prepare("SELECT id, field, value, action, target FROM rules ORDER BY created").all();
  return json({ rules: rows.results || [] });
}

async function saveRule(id, request, env) {
  const body = await readJson(request);
  if (!body) return json({ error: "Requête illisible." }, 400);
  const value = String(body.value || "").replace(/[\r\n]+/g, " ").trim().slice(0, 200);
  if (!RULE_FIELDS.includes(body.field) || !RULE_ACTIONS.includes(body.action)) return json({ error: "Règle inconnue." }, 400);
  if (!value) return json({ error: "Indique le texte à repérer." }, 400);
  // Ranger dans un dossier : il faut dire lequel, et qu'il existe.
  let target = null;
  if (body.action === "label" || body.action === "file") {
    target = typeof body.target === "string" && UUID.test(body.target) ? body.target : null;
    const found = target ? await env.DB.prepare("SELECT id FROM labels WHERE id = ?1").bind(target).first() : null;
    if (!found) return json({ error: "Choisis le dossier où ranger ces messages." }, 400);
  }
  const existing = await env.DB.prepare("SELECT created FROM rules WHERE id = ?1").bind(id).first();
  if (!existing) {
    const count = await env.DB.prepare("SELECT COUNT(*) AS n FROM rules").first();
    if (count && count.n >= MAX_RULES) return json({ error: `Pas plus de ${MAX_RULES} règles.` }, 400);
  }
  await env.DB.prepare("INSERT OR REPLACE INTO rules (id, field, value, action, target, created) VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
    .bind(id, body.field, value, body.action, target, existing ? existing.created : Date.now())
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

  const session = await currentSession(request, env);
  const authed = Boolean(session);

  if (path === "/api/logout" && method === "POST") {
    if (session) await revokeSessions(env, [session.id]).catch((err) => console.error("Session non fermée :", err));
    return logout();
  }

  if (path === "/api/me" && method === "GET") {
    if (!authed) return json({ authenticated: false });
    const from = parseAddress(env.MAIL_FROM || "");
    return json({
      authenticated: true,
      from: env.MAIL_FROM || "",
      address: from.address,
      addresses: await sendingAddresses(env),
      signature: (await readSettings(env)).signature || "",
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
  if (path === "/api/push/test" && method === "POST") return testPush(env);
  if (path === "/api/settings" && method === "PUT") return saveSettings(request, env);
  if (path === "/api/index" && method === "POST") return runIndex(request, env);
  if (path === "/api/labels" && method === "GET") return listLabels(env);
  if (path === "/api/import/status" && method === "GET") return importStatus(env);
  if (path === "/api/import/check" && method === "POST") return importCheck(request, env);
  if (path === "/api/import/undo" && method === "POST") return importUndo(request, env);
  if (path === "/api/devices" && (method === "PUT" || method === "DELETE")) return saveDevice(request, env, method === "DELETE", session);
  if (path === "/api/sessions" && method === "GET") return listSessions(session, request, env);
  if (path === "/api/sessions/others" && method === "DELETE") return revokeOthers(session, env);
  if (path === "/api/boxes" && method === "GET") return listBoxes(env);
  if (path === "/api/attachments" && method === "GET") return listAttachments(url, env);
  if (path === "/api/attachments/scan" && method === "POST") return runScan(env);
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

  const label = path.match(/^\/api\/labels\/([0-9a-f-]{36})$/);
  if (label) {
    if (method === "PUT") return saveLabel(label[1], request, env);
    if (method === "DELETE") return deleteLabel(label[1], env);
  }

  const other = path.match(/^\/api\/sessions\/([A-Za-z0-9_-]{20,64})$/);
  if (other && method === "DELETE") return revokeSession(other[1], session, env);

  const piece = path.match(/^\/api\/messages\/([0-9a-f-]{36})\/attachments\/(\d{1,4})$/);
  if (piece && method === "GET") return readAttachment(piece[1], Number(piece[2]), url, env);

  const imported = path.match(/^\/api\/import\/messages\/([0-9a-f]{6,32})$/);
  if (imported && method === "PUT") return importMessage(imported[1], request, url, env);

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
  if (fromApp) return json({ ok: true, token: await createSession(env, APP_SESSION_DAYS, request, "app") });

  const token = await createSession(env, SESSION_DAYS, request, "web");
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

// Chaque connexion crée une session : un jeton signé, propre à l'appareil, et une ligne qui permet de
// la voir dans la liste des appareils et de la fermer à distance. Le jeton lui-même n'est gardé nulle
// part : la ligne ne porte que son empreinte.
async function createSession(env, days, request, kind) {
  const now = Date.now();
  const expires = now + days * 24 * 3600 * 1000;
  const payload = `${expires}.${toBase64Url(crypto.getRandomValues(new Uint8Array(12)))}`;
  const signature = await crypto.subtle.sign("HMAC", await sessionKey(env), new TextEncoder().encode(payload));
  const token = `${payload}.${toBase64Url(new Uint8Array(signature))}`;
  await env.DB.batch([
    env.DB.prepare("INSERT INTO sessions (id, kind, name, place, created, seen, expires, revoked) VALUES (?1, ?2, ?3, ?4, ?5, ?5, ?6, 0)").bind(
      await sessionId(token),
      kind,
      deviceName(request, kind),
      placeOf(request),
      now,
      expires
    ),
    env.DB.prepare("DELETE FROM sessions WHERE expires < ?1").bind(now),
  ]);
  return token;
}

async function sessionId(token) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode("valentin-mail-appareil:" + token));
  return toBase64Url(new Uint8Array(digest));
}

// Le nom sous lequel un appareil apparaît : l'appli donne son modèle, un navigateur se reconnaît à sa signature.
function deviceName(request, kind) {
  if (kind === "app") {
    const given = (request.headers.get("X-Courrier-Device") || "").replace(/[^\x20-\x7e]/g, "").replace(/\s+/g, " ").trim();
    return given.slice(0, 80) || "Appli Courrier";
  }
  const ua = request.headers.get("User-Agent") || "";
  const browser = /Edg\//.test(ua) ? "Edge" : /OPR\//.test(ua) ? "Opera" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "Navigateur";
  const system = /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : /Windows/.test(ua) ? "Windows" : /Mac OS X/.test(ua) ? "macOS" : /Linux/.test(ua) ? "Linux" : "";
  return system ? `${browser} sur ${system}` : browser;
}

function placeOf(request) {
  const cf = request.cf || {};
  return [cf.city, cf.country].filter(Boolean).join(", ").slice(0, 80);
}

// État des sessions vues récemment par ce serveur, pour ne pas interroger la base à chaque requête.
// Une session fermée ailleurs est donc refusée au plus tard une minute après.
const sessionCache = new Map();

// La session de celui qui demande ({ id, kind }), ou null s'il n'est pas connecté.
async function currentSession(request, env) {
  const bearer = (request.headers.get("Authorization") || "").match(/^Bearer\s+(\S+)$/i);
  const token = bearer ? bearer[1] : readCookie(request, SESSION_COOKIE);
  if (!token) return null;
  // Deux formes de jeton : « échéance.aléa.signature », et l'ancienne « échéance.signature ».
  const parts = token.split(".");
  if (parts.length !== 2 && parts.length !== 3) return null;
  const signature = parts.pop();
  const expires = parts[0];
  if (!/^\d{10,16}$/.test(expires) || Number(expires) < Date.now()) return null;
  if (parts.length === 2 && !/^[A-Za-z0-9_-]{8,40}$/.test(parts[1])) return null;
  try {
    const valid = await crypto.subtle.verify("HMAC", await sessionKey(env), fromBase64Url(signature), new TextEncoder().encode(parts.join(".")));
    if (!valid) return null;
  } catch {
    return null;
  }

  const kind = bearer ? "app" : "web";
  const id = await sessionId(token);
  const now = Date.now();
  const cached = sessionCache.get(id);
  if (cached && now - cached.at < SESSION_CACHE_MS) return cached.revoked ? null : { id, kind };

  let revoked = false;
  try {
    const row = await env.DB.prepare("SELECT revoked, seen FROM sessions WHERE id = ?1").bind(id).first();
    if (row) {
      revoked = Boolean(row.revoked);
      if (!revoked && now - row.seen > SESSION_SEEN_MS) {
        await env.DB.prepare("UPDATE sessions SET seen = ?1, place = ?2 WHERE id = ?3").bind(now, placeOf(request), id).run();
      }
    } else {
      // Pas de ligne : un jeton d'aujourd'hui en a toujours une, donc celui-ci a été effacé. Un jeton
      // d'avant la liste des appareils, lui, s'y inscrit à son premier passage — tant qu'on n'a pas
      // demandé de déconnecter tous les autres appareils.
      const legacy = parts.length === 1;
      if (!legacy || (await readSettings(env)).legacy_sessions === "closed") return null;
      await env.DB.prepare(
        "INSERT OR IGNORE INTO sessions (id, kind, name, place, created, seen, expires, revoked) VALUES (?1, ?2, ?3, ?4, ?5, ?5, ?6, 0)"
      )
        .bind(id, kind, deviceName(request, kind), placeOf(request), now, Number(expires))
        .run();
    }
  } catch (err) {
    // La liste des appareils est indisponible : la signature du jeton suffit, on ne ferme pas la boîte pour autant.
    console.error("Liste des appareils illisible :", err);
    return { id, kind };
  }
  if (sessionCache.size > 200) sessionCache.clear();
  sessionCache.set(id, { revoked, at: now });
  return revoked ? null : { id, kind };
}

async function revokeSessions(env, ids) {
  for (let i = 0; i < ids.length; i += BATCH_SLICE) {
    const part = ids.slice(i, i + BATCH_SLICE);
    const marks = part.map(() => "?").join(",");
    await env.DB.batch([
      env.DB.prepare(`UPDATE sessions SET revoked = 1 WHERE id IN (${marks})`).bind(...part),
      env.DB.prepare(`DELETE FROM devices WHERE session IN (${marks})`).bind(...part),
    ]);
    for (const id of part) sessionCache.set(id, { revoked: true, at: Date.now() });
  }
}

async function listSessions(session, request, env) {
  const now = Date.now();
  const rows = await env.DB.prepare(
    "SELECT id, kind, name, place, created, seen FROM sessions WHERE revoked = 0 AND expires > ?1 ORDER BY seen DESC LIMIT 50"
  )
    .bind(now)
    .all();
  const list = (rows.results || []).map((r) => (r.id === session.id ? { ...r, seen: now, current: true } : { ...r, current: false }));
  // Une appli inscrite avant de savoir dire son modèle apparaît sous un nom vague : elle le corrige ici.
  const mine = list.find((r) => r.current);
  const given = session.kind === "app" && request.headers.get("X-Courrier-Device") ? deviceName(request, "app") : "";
  if (mine && given && mine.name !== given) {
    mine.name = given;
    await env.DB.prepare("UPDATE sessions SET name = ?1 WHERE id = ?2").bind(given, mine.id).run().catch(() => {});
  }
  list.sort((a, b) => Number(b.current) - Number(a.current));
  return json({ sessions: list });
}

// Ferme une session à distance. Fermer la sienne revient à se déconnecter.
async function revokeSession(id, session, env) {
  const row = await env.DB.prepare("SELECT id FROM sessions WHERE id = ?1 AND revoked = 0").bind(id).first();
  if (!row) return json({ error: "Cet appareil n'est plus connecté." }, 404);
  await revokeSessions(env, [id]);
  if (id === session.id) return json({ ok: true, current: true }, 200, { "Set-Cookie": sessionCookie("", 0) });
  return json({ ok: true, current: false });
}

// Ferme toutes les sessions sauf celle qui le demande — y compris celles ouvertes avant la liste des
// appareils et jamais revues depuis : elles ne pourront plus s'y inscrire.
async function revokeOthers(session, env) {
  const rows = await env.DB.prepare("SELECT id FROM sessions WHERE revoked = 0 AND id != ?1").bind(session.id).all();
  const ids = (rows.results || []).map((r) => r.id);
  await revokeSessions(env, ids);
  await env.DB.prepare("INSERT OR REPLACE INTO settings (key, value) VALUES ('legacy_sessions', 'closed')").run();
  return json({ ok: true, closed: ids.length });
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
  "id, folder, direction, from_addr, from_name, to_addr, subject, message_id, date, size, is_read, thread_id, box";

// Dossiers personnels où le message est rangé (leurs identifiants).
const LABEL_IDS = "(SELECT group_concat(label_id) FROM message_labels ml WHERE ml.message_id = messages.id) AS labels";

// Pour la liste : le début du texte du message (pris dans l'index de recherche, donc absent tant que
// le message n'y est pas) et le nombre de ses pièces jointes relevées.
const ITEM_EXTRAS =
  "(SELECT substr(body, 1, 240) FROM messages_fts WHERE rowid = messages.rowid) AS snippet, " +
  "(SELECT COUNT(*) FROM attachments a WHERE a.message_id = messages.id) AS files";

function withLabels(row) {
  if (row) {
    row.labels = row.labels ? String(row.labels).split(",") : [];
    if ("snippet" in row) row.snippet = String(row.snippet || "").replace(/\s+/g, " ").trim().slice(0, 160);
  }
  return row;
}

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
    `SELECT ${LIST_COLUMNS}, ${LABEL_IDS}, ${ITEM_EXTRAS} FROM messages WHERE thread_id = ?1 AND folder != 'trash' ORDER BY date ASC LIMIT 200`
  )
    .bind(id)
    .all();
  return json({ messages: (rows.results || []).map(withLabels) });
}

async function listMessages(url, env) {
  const folder = url.searchParams.get("folder") || "inbox";
  if (!FOLDERS.includes(folder)) return json({ error: "Dossier inconnu." }, 400);
  // Un dossier personnel montre ce qu'on y a rangé, où que ce soit par ailleurs — sauf la corbeille.
  const label = url.searchParams.get("label") || "";
  if (label && !UUID.test(label)) return json({ error: "Dossier inconnu." }, 400);

  const q = (url.searchParams.get("q") || "").trim().slice(0, 100);
  const before = Number(url.searchParams.get("before")) || 0;
  const after = Number(url.searchParams.get("after")) || 0;

  // Une boîte montre le courrier reçu à cette adresse (ou venu de ce compte Gmail), où qu'il soit rangé.
  const box = (url.searchParams.get("box") || "").toLowerCase();
  if (box && !ADDRESS.test(box)) return json({ error: "Boîte inconnue." }, 400);

  const where = label
    ? ["id IN (SELECT message_id FROM message_labels WHERE label_id = ?)", "folder != 'trash'"]
    : box
      ? ["box = ?", "direction = 'in'", "folder != 'trash'"]
      : ["folder = ?"];
  const params = [label || box || folder];
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
    const match = searchQuery(q);
    // En-têtes du message, ou texte du message quand l'index le connaît déjà.
    where.push(
      "(subject LIKE ? ESCAPE '\\' OR from_addr LIKE ? ESCAPE '\\' OR from_name LIKE ? ESCAPE '\\' OR to_addr LIKE ? ESCAPE '\\'" +
        (match ? " OR id IN (SELECT id FROM messages_fts WHERE messages_fts MATCH ?)" : "") +
        ")"
    );
    params.push(like, like, like, like);
    if (match) params.push(match);
  }

  const [list, unread] = await env.DB.batch([
    env.DB.prepare(
      `SELECT ${LIST_COLUMNS}, ${LABEL_IDS}, ${ITEM_EXTRAS}, ${THREAD_COUNT} FROM messages WHERE ${where.join(" AND ")} ORDER BY date DESC LIMIT ${PAGE_SIZE + 1}`
    ).bind(...params),
    env.DB.prepare("SELECT COUNT(*) AS n FROM messages WHERE folder = 'inbox' AND is_read = 0"),
  ]);

  const rows = (list.results || []).map(withLabels);
  const hasMore = rows.length > PAGE_SIZE;
  return json({
    messages: hasMore ? rows.slice(0, PAGE_SIZE) : rows,
    hasMore,
    unread: unread.results && unread.results[0] ? unread.results[0].n : 0,
  });
}

async function findMessage(id, env) {
  return withLabels(await env.DB.prepare(`SELECT ${LIST_COLUMNS}, ${LABEL_IDS}, ${ITEM_EXTRAS}, r2_key FROM messages WHERE id = ?1`).bind(id).first());
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
    ...forgetStatements(env, [id]),
    env.DB.prepare("DELETE FROM messages WHERE id = ?1").bind(id),
  ]);
  return json({ ok: true, deleted: true });
}

// Ce qui accompagne un message qu'on efface pour de bon : son texte dans l'index de recherche et ses
// rangements. À placer avant l'effacement du message lui-même, dont l'index a besoin pour se repérer.
function forgetStatements(env, ids) {
  const marks = ids.map(() => "?").join(",");
  return [
    env.DB.prepare(`DELETE FROM messages_fts WHERE rowid IN (SELECT rowid FROM messages WHERE id IN (${marks}))`).bind(...ids),
    env.DB.prepare(`DELETE FROM message_labels WHERE message_id IN (${marks})`).bind(...ids),
    env.DB.prepare(`DELETE FROM attachments WHERE message_id IN (${marks})`).bind(...ids),
  ];
}

// Une même action sur plusieurs messages à la fois : lu ou non lu, changement de dossier, rangement, suppression.
async function batchMessages(request, env) {
  const body = await readJson(request);
  if (!body || !Array.isArray(body.ids)) return json({ error: "Requête illisible." }, 400);
  const ids = [...new Set(body.ids.map(String))];
  if (!ids.length) return json({ ok: true, changed: 0, deleted: 0 });
  if (ids.length > MAX_BATCH) return json({ error: `Pas plus de ${MAX_BATCH} messages à la fois.` }, 400);
  if (ids.some((id) => !/^[0-9a-f-]{36}$/.test(id))) return json({ error: "Message invalide." }, 400);
  // La base n'accepte qu'une centaine de valeurs par requête : on avance par tranches.
  let changed = 0;
  let deleted = 0;
  for (let i = 0; i < ids.length; i += BATCH_SLICE) {
    const part = await batchSlice(ids.slice(i, i + BATCH_SLICE), body, env);
    if (part.error) return json({ error: part.error }, part.status);
    changed += part.changed;
    deleted += part.deleted;
  }
  return json({ ok: true, changed, deleted });
}

async function batchSlice(ids, body, env) {
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
        ...forgetStatements(env, goneIds),
        env.DB.prepare(`DELETE FROM messages WHERE id IN (${goneMarks})`).bind(...goneIds),
      ]);
    }
    return { changed: toTrash.length, deleted: gone.length };
  }

  const sets = [];
  const params = [];
  if (typeof body.is_read === "boolean") {
    sets.push("is_read = ?");
    params.push(body.is_read ? 1 : 0);
  }
  if (typeof body.folder === "string") {
    if (!FOLDERS.includes(body.folder)) return { error: "Dossier inconnu.", status: 400 };
    sets.push("folder = ?");
    params.push(body.folder);
  }

  // Rangement : ajouter ou retirer des dossiers personnels ; « leave_inbox » sort de la réception ce qu'on range.
  const idList = (value) => (Array.isArray(value) ? [...new Set(value.map(String))] : []);
  const add = idList(body.add_labels);
  const remove = idList(body.remove_labels);
  if ([...add, ...remove].some((l) => !UUID.test(l)) || add.length + remove.length > 20) {
    return { error: "Dossier inconnu.", status: 400 };
  }
  if (add.length) {
    const found = await env.DB.prepare(`SELECT id FROM labels WHERE id IN (${add.map(() => "?").join(",")})`).bind(...add).all();
    if ((found.results || []).length !== add.length) return { error: "Ce dossier n'existe plus.", status: 404 };
  }

  const statements = [];
  if (sets.length) {
    statements.push(env.DB.prepare(`UPDATE messages SET ${sets.join(", ")} WHERE id IN (${marks})`).bind(...params, ...ids));
  }
  for (const l of add) {
    statements.push(
      env.DB.prepare(`INSERT OR IGNORE INTO message_labels (message_id, label_id) SELECT id, ? FROM messages WHERE id IN (${marks})`).bind(l, ...ids)
    );
  }
  for (const l of remove) {
    statements.push(env.DB.prepare(`DELETE FROM message_labels WHERE label_id = ? AND message_id IN (${marks})`).bind(l, ...ids));
  }
  if (body.leave_inbox === true) {
    statements.push(env.DB.prepare(`UPDATE messages SET folder = 'archive' WHERE folder = 'inbox' AND id IN (${marks})`).bind(...ids));
  }
  if (!statements.length) return { error: "Rien à modifier.", status: 400 };
  const results = await env.DB.batch(statements);
  const changed = Math.max(0, ...results.map((r) => (r.meta ? r.meta.changes || 0 : 0)));
  return { changed, deleted: 0 };
}

// ------------------------------------------------------ dossiers personnels

// Un nom de dossier propre : « Parent/Enfant » pour un sous-dossier, cinq niveaux au plus.
function cleanLabelName(value) {
  const parts = String(value || "")
    .replace(/[\u0000-\u001f\u007f\u202a-\u202e\u2066-\u2069]/g, "")
    .split("/")
    .map((p) => p.replace(/\s+/g, " ").trim())
    .filter(Boolean);
  if (!parts.length || parts.length > 5 || parts.some((p) => p.length > 60)) return "";
  return parts.join("/");
}

async function listLabels(env) {
  const rows = await env.DB.prepare(
    `SELECT l.id, l.name, COUNT(m.id) AS total, COALESCE(SUM(m.is_read = 0), 0) AS unread
     FROM labels l
     LEFT JOIN message_labels ml ON ml.label_id = l.id
     LEFT JOIN messages m ON m.id = ml.message_id AND m.folder != 'trash'
     GROUP BY l.id ORDER BY l.name COLLATE NOCASE`
  ).all();
  return json({ labels: rows.results || [] });
}

// Crée un dossier, ou le renomme ; ses sous-dossiers suivent le nouveau nom.
async function saveLabel(id, request, env) {
  const body = await readJson(request);
  const name = cleanLabelName(body && body.name);
  if (!name) return json({ error: "Donne un nom au dossier : 60 caractères au plus, et « / » pour un sous-dossier." }, 400);
  const [own, clash, count] = await env.DB.batch([
    env.DB.prepare("SELECT name FROM labels WHERE id = ?1").bind(id),
    env.DB.prepare("SELECT id FROM labels WHERE name = ?1 COLLATE NOCASE AND id != ?2").bind(name, id),
    env.DB.prepare("SELECT COUNT(*) AS n FROM labels"),
  ]);
  if ((clash.results || []).length) return json({ error: "Un dossier porte déjà ce nom." }, 409);
  const existing = (own.results || [])[0];
  if (!existing) {
    if (count.results[0].n >= MAX_LABELS) return json({ error: `Pas plus de ${MAX_LABELS} dossiers.` }, 400);
    await env.DB.prepare("INSERT INTO labels (id, name, source, created) VALUES (?1, ?2, NULL, ?3)").bind(id, name, Date.now()).run();
  } else if (existing.name !== name) {
    const prefix = existing.name.replace(/[\\%_]/g, "\\$&") + "/%";
    try {
      await env.DB.batch([
        env.DB.prepare("UPDATE labels SET name = ?1 || substr(name, ?2) WHERE name LIKE ?3 ESCAPE '\\'").bind(name, existing.name.length + 1, prefix),
        env.DB.prepare("UPDATE labels SET name = ?1 WHERE id = ?2").bind(name, id),
      ]);
    } catch (err) {
      console.error("Dossier non renommé :", err);
      return json({ error: "Un sous-dossier porterait le même nom qu'un dossier existant." }, 409);
    }
  }
  return json({ ok: true, id, name });
}

// Efface le dossier, pas les messages : ils restent là où ils sont par ailleurs.
async function deleteLabel(id, env) {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM message_labels WHERE label_id = ?1").bind(id),
    env.DB.prepare("DELETE FROM rules WHERE target = ?1").bind(id),
    env.DB.prepare("DELETE FROM labels WHERE id = ?1").bind(id),
  ]);
  return json({ ok: true });
}

// ------------------------------------------------------------------ boîtes

// La boîte Gmail d'où vient un message transféré automatiquement : Gmail l'écrit dans un en-tête,
// « X-Forwarded-For: adresse-d'origine adresse-de-destination ».
function forwardedBox(forwardedFor, recipient) {
  const parts = String(forwardedFor || "").toLowerCase().split(/[\s,]+/).filter(Boolean);
  for (let i = 0; i + 1 < parts.length; i++) {
    if (parts[i + 1] === recipient && parts[i] !== recipient && ADDRESS.test(parts[i])) return parts[i];
  }
  return "";
}

// Les boîtes qui ont reçu du courrier : l'adresse principale, les autres adresses du domaine, et les
// comptes extérieurs (Gmail) dont le courrier arrive ici par transfert ou par import.
async function listBoxes(env) {
  const rows = await env.DB.prepare(
    `SELECT box AS address, COUNT(*) AS total, COALESCE(SUM(is_read = 0), 0) AS unread
     FROM messages WHERE direction = 'in' AND folder != 'trash' AND box IS NOT NULL AND box != '' GROUP BY box`
  ).all();
  const main = mainAddress(env);
  const domain = ownDomain(env);
  const rank = { main: 0, alias: 1, outside: 2 };
  const boxes = (rows.results || []).map((r) => ({
    address: r.address,
    kind: r.address === main ? "main" : r.address.endsWith("@" + domain) ? "alias" : "outside",
    total: r.total,
    unread: r.unread,
  }));
  boxes.sort((a, b) => rank[a.kind] - rank[b.kind] || a.address.localeCompare(b.address));
  return json({ boxes });
}

// ------------------------------------------------- inventaire des pièces jointes

// « valeur; clé=texte; clé*=utf-8''texte%20cod%C3%A9; clé*0=début; clé*1=suite »
function parseParams(value) {
  const pieces = [];
  let current = "";
  let quoted = false;
  for (const ch of String(value || "")) {
    if (ch === '"') quoted = !quoted;
    if (ch === ";" && !quoted) {
      pieces.push(current);
      current = "";
    } else {
      current += ch;
    }
  }
  pieces.push(current);
  const base = pieces.shift().trim().toLowerCase();
  const found = {};
  for (const piece of pieces) {
    const eq = piece.indexOf("=");
    if (eq < 1) continue;
    const key = piece.slice(0, eq).trim().toLowerCase().match(/^([^*]+)(?:\*(\d+))?(\*)?$/);
    if (!key) continue;
    let text = piece.slice(eq + 1).trim();
    if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) text = text.slice(1, -1).replace(/\\(.)/g, "$1");
    (found[key[1]] = found[key[1]] || []).push({ n: key[2] ? Number(key[2]) : 0, text, encoded: Boolean(key[3]), plain: !key[2] && !key[3] });
  }
  const params = {};
  for (const [name, list] of Object.entries(found)) {
    const extended = list.filter((x) => !x.plain).sort((a, b) => a.n - b.n);
    if (!extended.length) {
      params[name] = decodeMimeWords(list[0].text);
      continue;
    }
    let charset = "utf-8";
    const bytes = [];
    extended.forEach((segment, index) => {
      let text = segment.text;
      if (segment.encoded && index === 0) {
        const quote = text.split("'");
        if (quote.length >= 3) {
          charset = quote[0] || "utf-8";
          text = quote.slice(2).join("'");
        }
      }
      for (let i = 0; i < text.length; i++) {
        if (segment.encoded && text[i] === "%" && /^[0-9a-f]{2}$/i.test(text.slice(i + 1, i + 3))) {
          bytes.push(parseInt(text.slice(i + 1, i + 3), 16));
          i += 2;
        } else {
          bytes.push(text.charCodeAt(i) & 255);
        }
      }
    });
    params[name] = decodeBytes(Uint8Array.from(bytes), charset);
  }
  return { base, params };
}

// La famille d'un fichier, pour les filtres de la galerie.
function kindOf(mime, name) {
  const ext = ((String(name).match(/\.([a-z0-9]{1,8})$/i) || [])[1] || "").toLowerCase();
  if (mime.startsWith("image/") || ["jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "tif", "tiff", "avif"].includes(ext)) return "image";
  if (mime === "application/pdf" || ext === "pdf") return "pdf";
  if (mime.startsWith("audio/") || mime.startsWith("video/") || ["mp3", "m4a", "wav", "ogg", "flac", "aac", "mp4", "mov", "avi", "mkv", "webm"].includes(ext)) return "media";
  if (/word|excel|powerpoint|spreadsheet|presentation|opendocument|officedocument|rtf/.test(mime) || mime.startsWith("text/")) return "doc";
  if (["doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "txt", "csv", "md", "pages", "numbers", "key", "ics", "html", "htm"].includes(ext)) return "doc";
  return "other";
}

// Taille d'un contenu en base64 une fois décodé, d'après sa longueur écrite et celle de ses lignes.
function base64Size(bytes, start, end) {
  const length = end - start;
  if (length <= 0) return 0;
  const firstBreak = bytes.indexOf(10, start);
  let chars = length;
  if (firstBreak >= 0 && firstBreak < end) {
    const line = firstBreak - start + 1;
    const eol = firstBreak > start && bytes[firstBreak - 1] === 13 ? 2 : 1;
    const fullLines = Math.floor(length / line);
    chars = length - fullLines * eol;
  }
  let padding = 0;
  for (let i = end - 1; i >= start && i > end - 6; i--) {
    if (bytes[i] === 61) padding++;
    else if (bytes[i] !== 10 && bytes[i] !== 13) break;
  }
  return Math.max(0, Math.floor((chars * 3) / 4) - padding);
}

// Une partie du message est-elle une pièce jointe ? Les mêmes règles que l'appli quand elle affiche un
// message, à ceci près que le texte sans nom et les images incluses dans le texte n'en sont jamais.
function describePart(bytes, head, type, start, end) {
  const disposition = parseParams(head.get("content-disposition"));
  const attached = disposition.base === "attachment";
  const base = (type.base || "text/plain").slice(0, 100);
  let name = cleanFileName(disposition.params.filename || type.params.name || "");
  if (!attached && !name) {
    if (base === "text/plain" || base === "text/html") return null;
    if (/^(application\/(pgp-|pkcs7-|x-pkcs7-)|message\/delivery-status|message\/disposition-notification|text\/rfc822-headers)/.test(base)) return null;
  }
  if (!attached && head.get("content-id") && base.startsWith("image/")) return null;
  if (!name) {
    name = base === "message/rfc822" ? "message.eml" : base === "text/calendar" ? "invitation.ics" : base.startsWith("image/") ? `image.${base.slice(6).replace(/[^a-z0-9]/g, "") || "jpg"}` : "piece-jointe";
  }
  const encoding = (head.get("content-transfer-encoding") || "7bit").trim().toLowerCase().slice(0, 20);
  const length = Math.max(0, end - start);
  return {
    name,
    mime: base,
    kind: kindOf(base, name),
    size: encoding === "base64" ? base64Size(bytes, start, end) : length,
    start,
    length,
    encoding,
  };
}

// Relève les pièces jointes d'un message brut, avec l'endroit où chacune se trouve dans le fichier.
// Le contenu des pièces n'est jamais décodé : on saute de limite en limite. Une pièce en base64 ne
// contient aucun tiret, si bien que la suivante se trouve d'un seul bond, quelle que soit sa taille.
function scanParts(bytes) {
  const found = [];
  const top = rawHead(bytes.subarray(0, Math.min(bytes.length, HEAD_BYTES)));
  if (top.size < 0) return found;
  const topType = parseParams(top.get("content-type") || "text/plain");
  if (!topType.base.startsWith("multipart/")) {
    const only = describePart(bytes, top, topType, top.size, bytes.length);
    if (only) found.push(only);
    return found;
  }
  const boundaries = [];
  if (topType.params.boundary) boundaries.push(topType.params.boundary);
  let open = null;
  let pos = top.size;
  while (pos < bytes.length && found.length < MAX_PARTS) {
    const dash = bytes.indexOf(45, pos);
    if (dash < 0) break;
    if (bytes[dash + 1] !== 45 || (dash > 0 && bytes[dash - 1] !== 10)) {
      pos = dash + 1;
      continue;
    }
    // Une ligne qui commence par deux tirets : est-ce une limite connue ?
    let eol = bytes.indexOf(10, dash);
    if (eol < 0) eol = bytes.length;
    const line = eol - dash > 300 ? "" : binaryString(bytes.subarray(dash + 2, eol)).replace(/\s+$/, "");
    const closing = boundaries.some((b) => line === b + "--");
    if (!closing && !boundaries.includes(line)) {
      pos = dash + 2;
      continue;
    }
    if (open) {
      let end = dash - 1; // le saut de ligne qui précède la limite ne fait pas partie du contenu
      if (end > open.start && bytes[end - 1] === 13) end--;
      const part = describePart(bytes, open.head, open.type, open.start, Math.max(open.start, end));
      if (part) found.push(part);
      open = null;
    }
    pos = eol + 1;
    if (closing) continue;
    const head = rawHead(bytes.subarray(pos, Math.min(pos + PART_HEAD_BYTES, bytes.length)));
    if (head.size < 0) break;
    const type = parseParams(head.get("content-type") || "text/plain");
    pos += head.size;
    if (type.base.startsWith("multipart/")) {
      if (type.params.boundary && boundaries.length < 20) boundaries.push(type.params.boundary);
    } else {
      open = { head, type, start: pos };
    }
  }
  // Message coupé avant sa dernière limite : la partie en cours va jusqu'au bout.
  if (open && found.length < MAX_PARTS) {
    const part = describePart(bytes, open.head, open.type, open.start, bytes.length);
    if (part) found.push(part);
  }
  return found;
}

function attachmentStatements(env, messageId, date, parts) {
  return parts.map((p, n) =>
    env.DB.prepare(
      `INSERT OR REPLACE INTO attachments (message_id, part, name, mime, kind, size, start, length, encoding, upload, date)
       VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)`
    ).bind(messageId, n, p.name, p.mime, p.kind, p.size, p.start, p.length, p.encoding, p.upload || null, date)
  );
}

// Inventaire des messages qui n'y sont pas encore — arrivés avant qu'il existe, ou trop lourds pour être
// relevés à l'arrivée : pièces jointes, et boîte d'origine quand on ne la connaît pas. Chaque message est
// relu en entier. Un message lourd, ou une reprise après interruption, se traite seul dans sa passe.
// Renvoie le nombre de messages qui restent à relire.
async function scanSome(env, limit) {
  const rows = await env.DB.prepare(
    `SELECT id, r2_key, size, date, direction, to_addr, box, scanned FROM messages WHERE ${TO_SCAN} ORDER BY scanned, date DESC LIMIT ?1`
  )
    .bind(limit)
    .all();
  let written = 0;
  let done = 0;
  for (const row of rows.results || []) {
    const alone = row.size > HEAVY_SCAN_BYTES || row.scanned > 0;
    if (done && alone) break;
    done++;
    // On note l'essai avant de commencer : si la relecture est interrompue (calcul trop long), le message
    // sera repris plus tard, seul, et abandonné après quelques essais plutôt que de bloquer les suivants.
    written += rowsWritten(await env.DB.prepare("UPDATE messages SET scanned = MAX(scanned, 1) + 1 WHERE id = ?1").bind(row.id).run());
    try {
      const object = await env.MAIL_BUCKET.get(row.r2_key);
      const bytes = object ? new Uint8Array(await object.arrayBuffer()) : new Uint8Array(0);
      const parts = scanParts(bytes);
      let box = row.box;
      if (!box && row.direction === "in") {
        const head = rawHead(bytes.subarray(0, Math.min(bytes.length, HEAD_BYTES)));
        box = forwardedBox(head.get("x-forwarded-for"), row.to_addr) || row.to_addr;
      }
      written += rowsWritten(
        await env.DB.batch([
          env.DB.prepare("DELETE FROM attachments WHERE message_id = ?1 AND upload IS NULL").bind(row.id),
          ...attachmentStatements(env, row.id, row.date, parts),
          env.DB.prepare("UPDATE messages SET scanned = 1, box = ?2 WHERE id = ?1").bind(row.id, box || null),
        ])
      );
    } catch (err) {
      // Un message illisible le restera : inutile d'y revenir.
      console.error("Message non relu pour l'inventaire :", row.id, err);
      await env.DB.prepare("UPDATE messages SET scanned = 9 WHERE id = ?1").bind(row.id).run().catch(() => {});
    }
    if (alone) break;
  }
  if (!done) return 0;
  await spend(env, written);
  const left = await env.DB.prepare(`SELECT COUNT(*) AS n FROM messages WHERE ${TO_SCAN}`).first();
  return left ? left.n : 0;
}

// Fait avancer l'inventaire à la demande (la galerie de l'appli s'en sert tant qu'il reste à faire).
async function runScan(env) {
  if ((await spent(env)) >= DAILY_WRITE_CAP) return json({ error: QUOTA_REACHED, retry: nextReset() }, 429);
  return json({ ok: true, remaining: await scanSome(env, 6) });
}

// La galerie : toutes les pièces jointes, des plus récentes aux plus anciennes, hors corbeille.
async function listAttachments(url, env) {
  const kind = url.searchParams.get("kind") || "";
  if (kind && !ATTACHMENT_KINDS.includes(kind)) return json({ error: "Famille de fichiers inconnue." }, 400);
  const q = (url.searchParams.get("q") || "").trim().slice(0, 100);
  const where = ["m.folder != 'trash'"];
  const params = [];
  if (kind) {
    where.push("a.kind = ?");
    params.push(kind);
  }
  if (q) {
    const like = "%" + q.replace(/[\\%_]/g, "\\$&") + "%";
    where.push("(a.name LIKE ? ESCAPE '\\' OR m.subject LIKE ? ESCAPE '\\' OR m.from_name LIKE ? ESCAPE '\\' OR m.from_addr LIKE ? ESCAPE '\\')");
    params.push(like, like, like, like);
  }
  // Suite de la liste : après la pièce « date.message.rang ».
  const after = (url.searchParams.get("after") || "").match(/^(\d{1,16})\.([0-9a-f-]{36})\.(\d{1,4})$/);
  if (after) {
    where.push("(a.date < ? OR (a.date = ? AND (a.message_id > ? OR (a.message_id = ? AND a.part > ?))))");
    params.push(Number(after[1]), Number(after[1]), after[2], after[2], Number(after[3]));
  }
  const [list, pending] = await env.DB.batch([
    env.DB.prepare(
      `SELECT a.message_id, a.part, a.name, a.mime, a.kind, a.size, a.date,
              m.subject, m.from_name, m.from_addr, m.to_addr, m.direction
       FROM attachments a JOIN messages m ON m.id = a.message_id
       WHERE ${where.join(" AND ")}
       ORDER BY a.date DESC, a.message_id, a.part LIMIT ${PAGE_SIZE + 1}`
    ).bind(...params),
    env.DB.prepare(`SELECT COUNT(*) AS n FROM messages WHERE ${TO_SCAN}`),
  ]);
  const rows = list.results || [];
  const hasMore = rows.length > PAGE_SIZE;
  return json({ attachments: hasMore ? rows.slice(0, PAGE_SIZE) : rows, hasMore, pending: pending.results[0].n });
}

// Une pièce jointe, telle qu'elle est écrite dans le message : le serveur n'en lit que cette tranche et
// ne la décode pas. L'en-tête X-Part-Encoding dit à l'appli comment la décoder (base64, le plus souvent).
//
// « ?head=N » n'envoie que les N premiers octets écrits : une photo range une miniature d'elle-même au
// début de son fichier, et l'appli s'en sert pour la galerie sans télécharger la photo entière.
async function readAttachment(id, part, url, env) {
  const row = await env.DB.prepare(
    `SELECT a.name, a.mime, a.size, a.start, a.length, a.encoding, a.upload, m.r2_key
     FROM attachments a JOIN messages m ON m.id = a.message_id WHERE a.message_id = ?1 AND a.part = ?2`
  )
    .bind(id, part)
    .first();
  if (!row) return json({ error: "Pièce jointe introuvable." }, 404);
  const asked = url.searchParams.get("head");
  const head = asked === null ? 0 : Number(asked);
  if (asked !== null && !(Number.isInteger(head) && head >= 1 && head <= PART_HEAD_MAX)) {
    return json({ error: "Début de pièce jointe mal demandé." }, 400);
  }
  const headers = {
    "Content-Type": "application/octet-stream",
    "X-Part-Encoding": row.upload ? "identity" : row.encoding,
    "X-Content-Type-Options": "nosniff",
    "Cache-Control": "private, no-store",
  };
  const whole = row.upload ? row.size : row.length;
  if (!whole) return new Response(new Uint8Array(0), { headers });
  const length = head ? Math.min(head, whole) : whole;
  const object = row.upload
    ? await env.MAIL_BUCKET.get(`uploads/${row.upload}`, head ? { range: { offset: 0, length } } : undefined)
    : await env.MAIL_BUCKET.get(row.r2_key, { range: { offset: row.start, length } });
  if (!object) return json({ error: "Le fichier n'est plus sur le serveur." }, 404);
  return new Response(object.body, { headers });
}

// ------------------------------------------------- import d'une autre boîte

const IMPORT_SOURCE = /^gmail:[a-z0-9._+-]{1,64}@[a-z0-9.-]{1,255}$/;

// Une conversation Gmail devient un identifiant de conversation de la même forme que les nôtres,
// toujours le même pour une même conversation.
function gmailThread(value) {
  const hex = String(value || "").toLowerCase();
  if (!/^[0-9a-f]{1,16}$/.test(hex)) return null;
  const g = hex.padStart(16, "0");
  return `00000000-0000-4000-${g.slice(0, 4)}-${g.slice(4)}`;
}

// Un en-tête écrit en UTF-8 sans encodage, lu octet par octet, retrouve ses accents.
function utf8ish(value) {
  if (!/[\u0080-\u00ff]/.test(value)) return value;
  try {
    return new TextDecoder("utf-8", { fatal: true }).decode(binaryBytes(value));
  } catch {
    return value;
  }
}

// Les en-têtes d'un message brut (son début suffit).
function rawHead(bytes) {
  const text = binaryString(bytes);
  // end : fin des en-têtes ; size : où commence ce qui les suit (-1 si la ligne vide n'est pas dans ce début).
  let end = -1;
  let size = -1;
  const empty = /^\r?\n/.exec(text);
  const blank = empty ? null : /\r?\n\r?\n/.exec(text);
  if (empty) {
    end = 0;
    size = empty[0].length;
  } else if (blank) {
    end = blank.index;
    size = blank.index + blank[0].length;
  }
  const map = new Map();
  for (const line of (end < 0 ? text : text.slice(0, end)).replace(/\r?\n[ \t]+/g, " ").split(/\r?\n/)) {
    const colon = line.indexOf(":");
    if (colon < 1) continue;
    const name = line.slice(0, colon).trim().toLowerCase();
    if (!map.has(name)) map.set(name, utf8ish(line.slice(colon + 1).trim()));
  }
  return { get: (name) => map.get(name) || "", size };
}

async function importStatus(env) {
  const [imported, unindexed] = await env.DB.batch([
    env.DB.prepare("SELECT COUNT(*) AS n FROM messages WHERE import_ref IS NOT NULL AND source IS NOT NULL"),
    env.DB.prepare(`SELECT COUNT(*) AS n FROM messages WHERE indexed = 0 OR (${TO_SCAN})`),
    env.DB.prepare("DELETE FROM cache WHERE key LIKE 'writes:%' AND expires < ?1").bind(Date.now()),
  ]);
  return json({
    ok: true,
    imported: imported.results[0].n,
    unindexed: unindexed.results[0].n,
    used: await spent(env),
    cap: DAILY_WRITE_CAP,
    resets: nextReset(),
    client_id: (await readSettings(env)).google_client_id || "",
  });
}

// Lesquels de ces messages sont déjà là ? Évite de les retélécharger quand on reprend un import.
async function importCheck(request, env) {
  const body = await readJson(request);
  const source = body && typeof body.source === "string" ? body.source.toLowerCase() : "";
  const refs = body && Array.isArray(body.refs) ? [...new Set(body.refs.map(String))] : null;
  if (!IMPORT_SOURCE.test(source) || !refs || refs.length > 450 || refs.some((r) => !/^[0-9a-f]{6,32}$/.test(r))) {
    return json({ error: "Requête illisible." }, 400);
  }
  const known = [];
  const statements = [];
  for (let i = 0; i < refs.length; i += 90) {
    const part = refs.slice(i, i + 90).map((r) => `${source}:${r}`);
    statements.push(
      env.DB.prepare(`SELECT import_ref FROM messages WHERE import_ref IN (${part.map(() => "?").join(",")})`).bind(...part)
    );
  }
  if (statements.length) {
    for (const result of await env.DB.batch(statements)) {
      for (const row of result.results || []) known.push(row.import_ref.slice(source.length + 1));
    }
  }
  return json({ ok: true, known });
}

// Reçoit un message tel qu'il existe dans l'autre boîte. Rien de ce qui accompagne une arrivée
// normale ne se produit : ni notification, ni règle de tri, ni adresse créée. Le message garde sa
// date, son état lu ou non lu, sa conversation et ses dossiers.
async function importMessage(ref, request, url, env) {
  const p = url.searchParams;
  const source = (p.get("source") || "").toLowerCase();
  if (!IMPORT_SOURCE.test(source)) return json({ error: "Origine de l'import invalide." }, 400);
  const folder = p.get("folder") || "archive";
  if (!["inbox", "sent", "archive"].includes(folder)) return json({ error: "Dossier inconnu." }, 400);
  const date = Number(p.get("date"));
  if (!(date > 0) || date > Date.now() + 24 * 3600 * 1000) return json({ error: "Date du message invalide." }, 400);
  let names = [];
  try {
    names = JSON.parse(p.get("labels") || "[]");
  } catch {
    return json({ error: "Liste de dossiers illisible." }, 400);
  }
  names = [...new Set((Array.isArray(names) ? names : []).map(cleanLabelName).filter(Boolean))].slice(0, 20);

  const length = Number(request.headers.get("Content-Length"));
  if (!request.body || !(length > 0)) return json({ error: "Message vide ou de taille inconnue." }, 400);
  if (length > IMPORT_MAX_BYTES) return json({ error: "Message trop lourd pour être importé." }, 413);
  if ((await spent(env)) >= DAILY_WRITE_CAP) return json({ error: QUOTA_REACHED, retry: nextReset() }, 429);

  const raw = await request.arrayBuffer();
  const head = rawHead(new Uint8Array(raw, 0, Math.min(raw.byteLength, HEAD_BYTES)));
  const messageId = head.get("message-id").slice(0, 500) || null;
  const importRef = `${source}:${ref}`;
  const thread = gmailThread(p.get("thread"));
  const now = Date.now();

  const [byRef, byId] = await env.DB.batch([
    env.DB.prepare("SELECT id FROM messages WHERE import_ref = ?1").bind(importRef),
    env.DB.prepare("SELECT id, thread_id, import_ref FROM messages WHERE message_id = ?1 ORDER BY date LIMIT 1").bind(messageId || ""),
  ]);
  if ((byRef.results || []).length) return json({ ok: true, status: "known", id: byRef.results[0].id });

  // Les dossiers cités sont créés au besoin, puis le message y est rangé.
  const filing = (messageRow) =>
    names.flatMap((name) => [
      env.DB.prepare("INSERT OR IGNORE INTO labels (id, name, source, created) VALUES (?1, ?2, ?3, ?4)").bind(crypto.randomUUID(), name, source, now),
      env.DB.prepare("INSERT OR IGNORE INTO message_labels (message_id, label_id) SELECT ?1, id FROM labels WHERE name = ?2").bind(messageRow, name),
    ]);

  // Déjà là sous un autre jour — arrivé par le transfert automatique, par exemple : on ne le double pas.
  // Il gagne seulement ses dossiers, et sa conversation rejoint celle de l'autre boîte.
  // Il retient aussi sa référence là-bas, sans devenir pour autant un message importé : défaire l'import
  // ne l'effacera pas, et une reprise le reconnaîtra sans le retélécharger.
  const twin = messageId ? (byId.results || [])[0] : null;
  if (twin) {
    const statements = filing(twin.id);
    if (!twin.import_ref) statements.push(env.DB.prepare("UPDATE messages SET import_ref = ?1 WHERE id = ?2").bind(importRef, twin.id));
    if (thread && !twin.import_ref && twin.thread_id !== thread) {
      statements.push(
        twin.thread_id
          ? env.DB.prepare("UPDATE messages SET thread_id = ?1 WHERE thread_id = ?2").bind(thread, twin.thread_id)
          : env.DB.prepare("UPDATE messages SET thread_id = ?1 WHERE id = ?2").bind(thread, twin.id)
      );
    }
    if (statements.length) await spend(env, rowsWritten(await env.DB.batch(statements)));
    return json({ ok: true, status: "merged", id: twin.id });
  }

  const id = crypto.randomUUID();
  const key = `raw/${id}.eml`;
  await env.MAIL_BUCKET.put(key, raw, { httpMetadata: { contentType: "message/rfc822" } });

  const out = folder === "sent";
  const from = parseAddress(decodeMimeWords(head.get("from")));
  const account = source.slice(source.indexOf(":") + 1);
  let parts = null;
  try {
    if (raw.byteLength <= INLINE_SCAN_BYTES) parts = scanParts(new Uint8Array(raw));
  } catch (err) {
    console.error("Pièces jointes non relevées :", err);
  }
  const to = out ? parseRecipients(decodeMimeWords(head.get("to"))).list.join(", ") : account;
  let results;
  try {
    results = await env.DB.batch([
      env.DB.prepare(
        `INSERT INTO messages
           (id, folder, direction, from_addr, from_name, to_addr, subject, message_id, date, size, is_read, r2_key, thread_id, indexed, source, import_ref, box, scanned)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, 0, ?14, ?15, ?16, ?17)`
      ).bind(
        id,
        folder,
        out ? "out" : "in",
        (from.address || (out ? account : "")).slice(0, 320),
        from.name.slice(0, 200),
        to.slice(0, 320),
        decodeMimeWords(head.get("subject")).trim().slice(0, 500),
        messageId,
        date,
        raw.byteLength,
        out || p.get("read") !== "0" ? 1 : 0,
        key,
        thread || id,
        source,
        importRef,
        account,
        parts ? 1 : 0
      ),
      ...filing(id),
      ...attachmentStatements(env, id, date, parts || []),
    ]);
  } catch (err) {
    await env.MAIL_BUCKET.delete(key).catch(() => {});
    throw err;
  }
  await spend(env, rowsWritten(results));
  return json({ ok: true, status: "imported", id });
}

// Défait un import : efface tout ce qu'il a apporté, par petits lots. Les messages arrivés autrement
// ne sont pas touchés. À rappeler tant que « remaining » n'est pas à zéro.
async function importUndo(request, env) {
  const body = await readJson(request);
  const source = body && typeof body.source === "string" ? body.source.toLowerCase() : "";
  if (!IMPORT_SOURCE.test(source)) return json({ error: "Origine de l'import invalide." }, 400);
  let removed = 0;
  for (let round = 0; round < 3; round++) {
    const batch = await env.DB.prepare("SELECT id, r2_key FROM messages WHERE import_ref IS NOT NULL AND source = ?1 LIMIT 90").bind(source).all();
    const rows = batch.results || [];
    if (!rows.length) break;
    const ids = rows.map((r) => r.id);
    await env.MAIL_BUCKET.delete(rows.map((r) => r.r2_key));
    const results = await env.DB.batch([
      ...forgetStatements(env, ids),
      env.DB.prepare(`DELETE FROM messages WHERE id IN (${ids.map(() => "?").join(",")})`).bind(...ids),
    ]);
    await spend(env, rowsWritten(results));
    removed += rows.length;
    if (rows.length < 90) break;
  }
  const left = await env.DB.prepare("SELECT COUNT(*) AS n FROM messages WHERE import_ref IS NOT NULL AND source = ?1").bind(source).first();
  const remaining = left ? left.n : 0;
  if (!remaining) {
    // Les dossiers que l'import avait créés et qui se retrouvent vides partent aussi.
    await env.DB.batch([
      env.DB.prepare("DELETE FROM labels WHERE source = ?1 AND id NOT IN (SELECT label_id FROM message_labels)").bind(source),
      env.DB.prepare("DELETE FROM rules WHERE target IS NOT NULL AND target NOT IN (SELECT id FROM labels)"),
    ]);
  }
  return json({ ok: true, removed, remaining });
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
      ...forgetStatements(env, ids),
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
         (id, folder, direction, from_addr, from_name, to_addr, subject, message_id, date, size, is_read, r2_key, thread_id, indexed, box, scanned)
       VALUES (?1, 'sent', 'out', ?2, ?3, ?4, ?5, NULL, ?6, ?7, 1, ?8, ?9, 1, ?2, 1)`
    )
      .bind(id, fromAddress, sender.name, to.list.join(", ").slice(0, 320), payload.subject, now, bytes.byteLength, key, thread || id)
      .run();
    // Le texte d'un message qu'on envoie est connu : il entre tout de suite dans la recherche.
    await env.DB.prepare("INSERT OR REPLACE INTO messages_fts (rowid, id, body) VALUES ((SELECT rowid FROM messages WHERE id = ?1), ?1, ?2)")
      .bind(id, tidyText(text))
      .run()
      .catch((err) => console.error("Message envoyé non indexé :", err));
    if (files.length) {
      await env.DB.prepare(`UPDATE uploads SET message_id = ? WHERE id IN (${files.map(() => "?").join(",")})`)
        .bind(id, ...files.map((f) => f.id))
        .run();
      // Les fichiers joints entrent dans l'inventaire des pièces jointes, comme ceux des messages reçus.
      const listed = files.map((f) => ({ name: f.name, mime: f.type, kind: kindOf(f.type, f.name), size: f.size, start: 0, length: f.size, encoding: "identity", upload: f.id }));
      await env.DB.batch(attachmentStatements(env, id, now, listed)).catch((err) => console.error("Pièces jointes non inscrites :", err));
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
