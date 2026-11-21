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

export default {
  async email(message, env) {
    await receive(message, env);
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
  const id = crypto.randomUUID();
  const key = `raw/${id}.eml`;
  const raw = await new Response(message.raw).arrayBuffer();

  await env.MAIL_BUCKET.put(key, raw, {
    httpMetadata: { contentType: "message/rfc822" },
  });

  const headers = message.headers;
  const from = parseAddress(decodeMimeWords(headers.get("from") || ""));
  if (!from.address) from.address = (message.from || "").toLowerCase();
  const subject = decodeMimeWords(headers.get("subject") || "").trim();

  try {
    await env.DB.prepare(
      `INSERT INTO messages
         (id, folder, direction, from_addr, from_name, to_addr, subject, message_id, date, size, is_read, r2_key)
       VALUES (?1, 'inbox', 'in', ?2, ?3, ?4, ?5, ?6, ?7, ?8, 0, ?9)`
    )
      .bind(
        id,
        from.address.slice(0, 320),
        from.name.slice(0, 200),
        (message.to || "").toLowerCase().slice(0, 320),
        subject.slice(0, 500),
        (headers.get("message-id") || "").trim().slice(0, 500) || null,
        Date.now(),
        raw.byteLength,
        key
      )
      .run();
  } catch (err) {
    // Sans ligne d'index, le message serait invisible : on nettoie et on laisse
    // l'erreur remonter pour que l'expéditeur reçoive un avis d'échec.
    await env.MAIL_BUCKET.delete(key).catch(() => {});
    throw err;
  }
}

// ------------------------------------------------------------------- routes

async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;

  if (!path.startsWith("/api/")) {
    if (env.ASSETS) return env.ASSETS.fetch(request);
    return new Response("Introuvable", { status: 404 });
  }

  if (!env.APP_PASSWORD) {
    return json({ error: "Le mot de passe du webmail n'est pas configuré (secret APP_PASSWORD)." }, 503);
  }

  const method = request.method.toUpperCase();

  // Les requêtes qui modifient quelque chose doivent venir de la page elle-même.
  if (method !== "GET" && method !== "HEAD") {
    const origin = request.headers.get("Origin");
    if (origin !== url.origin) return json({ error: "Origine de la requête refusée." }, 403);
  }

  if (path === "/api/login" && method === "POST") return login(request, env);
  if (path === "/api/logout" && method === "POST") return logout();

  const authed = await isAuthenticated(request, env);

  if (path === "/api/me" && method === "GET") {
    if (!authed) return json({ authenticated: false });
    const from = parseAddress(env.MAIL_FROM || "");
    return json({
      authenticated: true,
      from: env.MAIL_FROM || "",
      address: from.address,
      canSend: Boolean(env.RESEND_API_KEY && from.address),
    });
  }

  if (!authed) return json({ error: "Connexion requise." }, 401);

  if (path === "/api/messages" && method === "GET") return listMessages(url, env);
  if (path === "/api/send" && method === "POST") return sendMessage(request, env);
  if (path === "/api/trash/empty" && method === "POST") return emptyTrash(env);

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

async function login(request, env) {
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

  if (!(await sameSecret(password, env.APP_PASSWORD))) {
    await env.DB.batch([
      env.DB.prepare("INSERT INTO login_failures (ip, ts) VALUES (?1, ?2)").bind(ip, now),
      env.DB.prepare("DELETE FROM login_failures WHERE ts < ?1").bind(now - LOGIN_WINDOW_MS),
    ]);
    return json({ error: "Mot de passe incorrect." }, 401);
  }

  const token = await createSession(env);
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

async function createSession(env) {
  const expires = String(Date.now() + SESSION_DAYS * 24 * 3600 * 1000);
  const signature = await crypto.subtle.sign("HMAC", await sessionKey(env), new TextEncoder().encode(expires));
  return `${expires}.${toBase64Url(new Uint8Array(signature))}`;
}

async function isAuthenticated(request, env) {
  const token = readCookie(request, SESSION_COOKIE);
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
  "id, folder, direction, from_addr, from_name, to_addr, subject, message_id, date, size, is_read";

async function listMessages(url, env) {
  const folder = url.searchParams.get("folder") || "inbox";
  if (!FOLDERS.includes(folder)) return json({ error: "Dossier inconnu." }, 400);

  const q = (url.searchParams.get("q") || "").trim().slice(0, 100);
  const before = Number(url.searchParams.get("before")) || 0;

  const where = ["folder = ?"];
  const params = [folder];
  if (before > 0) {
    where.push("date < ?");
    params.push(before);
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
      `SELECT ${LIST_COLUMNS} FROM messages WHERE ${where.join(" AND ")} ORDER BY date DESC LIMIT ${PAGE_SIZE + 1}`
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
  return json({ message: row });
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

  await env.MAIL_BUCKET.delete(row.r2_key);
  await env.DB.prepare("DELETE FROM messages WHERE id = ?1").bind(id).run();
  return json({ ok: true, deleted: true });
}

async function emptyTrash(env) {
  let removed = 0;
  // Par lots, pour rester dans les limites d'une seule exécution.
  for (let round = 0; round < 5; round++) {
    const batch = await env.DB.prepare("SELECT id, r2_key FROM messages WHERE folder = 'trash' LIMIT 100").all();
    const rows = batch.results || [];
    if (!rows.length) break;
    await env.MAIL_BUCKET.delete(rows.map((r) => r.r2_key));
    await env.DB.batch(rows.map((r) => env.DB.prepare("DELETE FROM messages WHERE id = ?1").bind(r.id)));
    removed += rows.length;
    if (rows.length < 100) break;
  }
  const left = await env.DB.prepare("SELECT COUNT(*) AS n FROM messages WHERE folder = 'trash'").first();
  return json({ ok: true, removed, remaining: left ? left.n : 0 });
}

// -------------------------------------------------------------------- envoi

async function sendMessage(request, env) {
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
  if (!subject && !text.trim()) return json({ error: "Le message est vide." }, 400);
  if (text.length > 500000) return json({ error: "Le message est trop long." }, 400);

  // Réponse : on rattache le message à la conversation d'origine.
  let inReplyTo = null;
  if (typeof body.reply_to_id === "string" && /^[0-9a-f-]{36}$/.test(body.reply_to_id)) {
    const original = await findMessage(body.reply_to_id, env);
    if (original && original.message_id) inReplyTo = original.message_id;
  }

  const payload = {
    from: env.MAIL_FROM,
    to: to.list,
    subject: subject || "(sans objet)",
    text,
  };
  if (cc.list.length) payload.cc = cc.list;
  if (inReplyTo) payload.headers = { "In-Reply-To": inReplyTo, References: inReplyTo };

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
      from: env.MAIL_FROM,
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
         (id, folder, direction, from_addr, from_name, to_addr, subject, message_id, date, size, is_read, r2_key)
       VALUES (?1, 'sent', 'out', ?2, ?3, ?4, ?5, NULL, ?6, ?7, 1, ?8)`
    )
      .bind(id, sender.address, sender.name, to.list.join(", ").slice(0, 320), payload.subject, now, bytes.byteLength, key)
      .run();
  } catch (err) {
    stored = false;
    console.error("Copie dans Envoyés impossible :", err);
  }

  return json({ ok: true, stored });
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
