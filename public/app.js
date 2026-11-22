// Webmail : toute l'interface, sans dépendance autre que postal-mime
// (qui décode les messages bruts dans le navigateur).
import PostalMime from "/vendor/postal-mime.js";

const FOLDERS = [
  { id: "inbox", label: "Boîte de réception", empty: "Aucun message pour l'instant. Les mails envoyés à ton adresse arriveront ici." },
  { id: "sent", label: "Envoyés", empty: "Tu n'as encore rien envoyé depuis cette boîte." },
  { id: "archive", label: "Archives", empty: "Aucun message archivé." },
  { id: "trash", label: "Corbeille", empty: "La corbeille est vide." },
];
const REFRESH_MS = 90 * 1000;

const state = {
  me: null,
  folder: "inbox",
  query: "",
  messages: [],
  hasMore: false,
  unread: 0,
  loading: false,
  listError: "",
  loadedFolder: null,
  listToken: 0,
  readerToken: 0,
  current: null, // { meta, mail } du message ouvert
  replyToId: null,
  objectUrls: [],
};

const app = document.getElementById("app");
const $ = (selector, root = document) => root.querySelector(selector);

// ------------------------------------------------------------------ outils

function h(tag, props, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value === false || value == null) continue;
    if (key === "class") el.className = value;
    else if (key.startsWith("on") && typeof value === "function") el.addEventListener(key.slice(2), value);
    else el.setAttribute(key, value === true ? "" : value);
  }
  for (const child of children.flat(Infinity)) {
    if (child == null || child === false) continue;
    el.append(child.nodeType ? child : document.createTextNode(String(child)));
  }
  return el;
}

class ApiError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status;
  }
}

async function api(path, { method = "GET", body, raw = false } = {}) {
  const init = { method, headers: {}, credentials: "same-origin" };
  if (body !== undefined) {
    init.headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(body);
  }

  let response;
  try {
    response = await fetch(path, init);
  } catch {
    throw new ApiError("Serveur injoignable. Vérifie ta connexion internet, puis réessaie.", 0);
  }

  if (raw && response.ok) return response;

  let data = null;
  try {
    data = await response.json();
  } catch {
    /* réponse vide ou non JSON */
  }

  if (response.status === 401 && path !== "/api/login") {
    state.me = null;
    renderLogin("Ta session a expiré. Reconnecte-toi.");
    throw new ApiError("Session expirée.", 401);
  }
  if (!response.ok) {
    throw new ApiError((data && data.error) || `Le serveur a répondu par une erreur (${response.status}).`, response.status);
  }
  return data;
}

const timeFormat = new Intl.DateTimeFormat("fr-FR", { hour: "2-digit", minute: "2-digit" });
const dayFormat = new Intl.DateTimeFormat("fr-FR", { day: "numeric", month: "short" });
const yearFormat = new Intl.DateTimeFormat("fr-FR", { day: "numeric", month: "short", year: "numeric" });
const longFormat = new Intl.DateTimeFormat("fr-FR", {
  weekday: "long",
  day: "numeric",
  month: "long",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
});

function shortDate(ms) {
  const date = new Date(ms);
  const now = new Date();
  if (date.toDateString() === now.toDateString()) return timeFormat.format(date);
  if (date.getFullYear() === now.getFullYear()) return dayFormat.format(date);
  return yearFormat.format(date);
}

function fileSize(bytes) {
  if (bytes < 1024) return `${bytes} o`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} Ko`;
  return `${(bytes / (1024 * 1024)).toFixed(1).replace(".", ",")} Mo`;
}

let toastTimer = 0;
function toast(text) {
  const el = $("#toast");
  if (!el) return;
  el.textContent = text;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (el.hidden = true), 4000);
}

function folderInfo(id) {
  return FOLDERS.find((f) => f.id === id) || FOLDERS[0];
}

// --------------------------------------------------------------- connexion

function renderLogin(notice = "") {
  document.title = "Courrier";
  const error = h("p", { class: "form-error", role: "alert" }, notice);
  const input = h("input", {
    class: "input",
    id: "password",
    type: "password",
    autocomplete: "current-password",
    autocapitalize: "none",
    autocorrect: "off",
    spellcheck: "false",
    required: true,
  });
  const button = h("button", { class: "btn btn-primary", type: "submit" }, "Ouvrir la boîte");
  const reveal = h("input", { type: "checkbox", id: "reveal-password" });
  reveal.addEventListener("change", () => {
    input.type = reveal.checked ? "text" : "password";
  });

  const form = h(
    "form",
    {
      class: "login-form",
      onsubmit: async (event) => {
        event.preventDefault();
        error.textContent = "";
        button.disabled = true;
        button.textContent = "Ouverture…";
        try {
          await api("/api/login", { method: "POST", body: { password: input.value } });
          state.me = await api("/api/me");
          startMailbox();
        } catch (err) {
          error.textContent = err.message;
          button.disabled = false;
          button.textContent = "Ouvrir la boîte";
          input.select();
        }
      },
    },
    h("div", { class: "field" }, h("label", { for: "password" }, "Mot de passe"), input),
    h("label", { class: "check", for: "reveal-password" }, reveal, "Afficher le mot de passe"),
    error,
    button
  );

  app.replaceChildren(
    h(
      "div",
      { class: "login" },
      h(
        "div",
        { class: "login-envelope" },
        h(
          "div",
          { class: "login-card" },
          h("h1", null, "Courrier"),
          h("p", null, "Entre le mot de passe pour ouvrir ta boîte."),
          form
        )
      )
    )
  );
  input.focus();
}

// -------------------------------------------------------------- structure

function renderShell() {
  const folders = h(
    "nav",
    { class: "folders", "aria-label": "Dossiers" },
    FOLDERS.map((folder) =>
      h(
        "a",
        { class: "folder", href: `#/${folder.id}`, "data-folder": folder.id },
        h("span", null, folder.label),
        folder.id === "inbox" ? h("span", { class: "folder-count", id: "unread-count" }) : null
      )
    )
  );

  const searchInput = h("input", {
    class: "input",
    id: "search-input",
    type: "search",
    placeholder: "Chercher un expéditeur ou un objet",
    "aria-label": "Chercher dans ce dossier",
    enterkeyhint: "search",
  });
  let searchTimer = 0;
  searchInput.addEventListener("input", () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      state.query = searchInput.value.trim();
      loadList();
    }, 300);
  });

  const listPane = h(
    "section",
    { class: "list-pane", "aria-label": "Messages" },
    h(
      "div",
      { class: "list-tools" },
      h(
        "div",
        { class: "list-head" },
        h("h1", { id: "folder-title" }),
        h("div", { class: "list-head-actions", id: "list-actions" })
      ),
      h("form", { role: "search", onsubmit: (e) => e.preventDefault() }, searchInput)
    ),
    h("ul", { class: "message-list", id: "message-list" }),
    h("div", { class: "list-foot", id: "list-foot" })
  );

  const shell = h(
    "div",
    { class: "shell" },
    h(
      "header",
      { class: "topbar" },
      h("span", { class: "brand-name" }, "Courrier"),
      h(
        "div",
        { class: "topbar-actions" },
        h("button", { class: "btn btn-primary", type: "button", onclick: () => openCompose() }, "Écrire"),
        h("button", { class: "btn btn-quiet", type: "button", onclick: logout }, "Déconnexion")
      ),
      h("span", { class: "brand-address" }, state.me.address || "Adresse d'envoi non configurée")
    ),
    h(
      "div",
      { class: "layout", id: "layout", "data-view": "list" },
      folders,
      listPane,
      h("section", { class: "reader-pane", id: "reader", "aria-label": "Message ouvert" })
    ),
    buildCompose(),
    h("div", { class: "toast", id: "toast", role: "status", hidden: true })
  );

  app.replaceChildren(shell);
}

function startMailbox() {
  state.loadedFolder = null;
  renderShell();
  handleRoute();
}

async function logout() {
  try {
    await api("/api/logout", { method: "POST" });
  } catch {
    /* on efface la session locale de toute façon */
  }
  state.me = null;
  state.messages = [];
  state.current = null;
  renderLogin();
}

// ----------------------------------------------------------------- routes

function parseHash() {
  const parts = location.hash.replace(/^#\/?/, "").split("/");
  const folder = FOLDERS.some((f) => f.id === parts[0]) ? parts[0] : "inbox";
  const id = /^[0-9a-f-]{36}$/.test(parts[1] || "") ? parts[1] : null;
  return { folder, id };
}

async function handleRoute() {
  const { folder, id } = parseHash();
  const folderChanged = folder !== state.loadedFolder;
  state.folder = folder;

  $("#layout").dataset.view = id ? "reader" : "list";
  updateFolders();

  if (folderChanged) {
    state.query = "";
    $("#search-input").value = "";
    state.messages = [];
    loadList();
  } else {
    renderList();
  }

  if (id) openMessage(id);
  else renderReaderEmpty();
}

function updateFolders() {
  for (const link of document.querySelectorAll(".folder")) {
    if (link.dataset.folder === state.folder) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  }
  const count = $("#unread-count");
  if (count) {
    count.textContent = state.unread > 0 ? String(state.unread) : "";
    if (state.unread > 0) count.setAttribute("aria-label", `${state.unread} non lus`);
  }
  document.title = state.unread > 0 ? `(${state.unread}) Courrier` : "Courrier";
}

// ------------------------------------------------------------------ liste

async function loadList({ more = false, quiet = false } = {}) {
  const token = ++state.listToken;
  const folder = state.folder;
  const params = new URLSearchParams({ folder });
  if (state.query) params.set("q", state.query);
  if (more && state.messages.length) params.set("before", String(state.messages[state.messages.length - 1].date));

  if (!quiet) {
    state.loading = true;
    state.listError = "";
    renderList();
  }

  try {
    const data = await api(`/api/messages?${params}`);
    if (token !== state.listToken) return;
    state.messages = more ? state.messages.concat(data.messages) : data.messages;
    state.hasMore = data.hasMore;
    state.unread = data.unread;
    state.loadedFolder = folder;
    state.listError = "";
  } catch (err) {
    if (token !== state.listToken) return;
    if (err.status === 401) return;
    if (!quiet) state.listError = err.message;
  }
  state.loading = false;
  renderList();
  updateFolders();
}

function renderList() {
  const info = folderInfo(state.folder);
  const { id: openId } = parseHash();
  $("#folder-title").textContent = info.label;

  const actions = [h("button", { class: "btn btn-quiet", type: "button", onclick: () => loadList() }, "Actualiser")];
  if (state.folder === "trash" && state.messages.length) {
    actions.unshift(armedButton("Vider la corbeille", "Confirmer : tout supprimer", emptyTrash, "btn btn-quiet btn-danger"));
  }
  $("#list-actions").replaceChildren(...actions);

  $("#message-list").replaceChildren(
    ...state.messages.map((message) => {
      const outgoing = message.direction === "out";
      const who = outgoing ? `À : ${message.to_addr}` : message.from_name || message.from_addr || "Expéditeur inconnu";
      const classes = ["row"];
      if (!message.is_read) classes.push("is-unread");
      if (message.id === openId) classes.push("is-selected");
      return h(
        "li",
        null,
        h(
          "a",
          { class: classes.join(" "), href: `#/${state.folder}/${message.id}` },
          h("span", { class: "row-who" }, who),
          h("time", { class: "row-date", datetime: new Date(message.date).toISOString() }, shortDate(message.date)),
          h("span", { class: "row-subject" }, message.subject || "(sans objet)")
        )
      );
    })
  );

  const foot = $("#list-foot");
  if (state.listError) {
    foot.replaceChildren(
      h("p", { class: "form-error" }, state.listError),
      h("button", { class: "btn", type: "button", onclick: () => loadList() }, "Réessayer")
    );
  } else if (state.loading && !state.messages.length) {
    foot.replaceChildren(h("p", null, "Chargement des messages…"));
  } else if (!state.messages.length) {
    foot.replaceChildren(
      h("p", null, state.query ? `Aucun message ne correspond à « ${state.query} » dans ce dossier.` : info.empty)
    );
  } else if (state.hasMore) {
    foot.replaceChildren(
      h(
        "button",
        { class: "btn", type: "button", disabled: state.loading, onclick: () => loadList({ more: true }) },
        state.loading ? "Chargement…" : "Afficher les messages plus anciens"
      )
    );
  } else {
    foot.replaceChildren();
  }
}

// Bouton à double détente pour les actions sans retour en arrière.
function armedButton(label, confirmLabel, action, className = "btn btn-danger") {
  let timer = 0;
  const button = h("button", { class: className, type: "button" }, label);
  button.addEventListener("click", async () => {
    if (!button.classList.contains("is-armed")) {
      button.classList.add("is-armed");
      button.textContent = confirmLabel;
      timer = setTimeout(() => {
        button.classList.remove("is-armed");
        button.textContent = label;
      }, 5000);
      return;
    }
    clearTimeout(timer);
    button.disabled = true;
    await action();
  });
  return button;
}

async function emptyTrash() {
  try {
    const result = await api("/api/trash/empty", { method: "POST" });
    toast(result.remaining ? `${result.removed} messages supprimés, il en reste ${result.remaining}.` : "Corbeille vidée.");
    if (state.current && state.current.meta.folder === "trash") location.hash = "#/trash";
  } catch (err) {
    toast(err.message);
  }
  loadList();
}

// ---------------------------------------------------------------- lecture

function releaseObjectUrls() {
  for (const url of state.objectUrls) URL.revokeObjectURL(url);
  state.objectUrls = [];
}

function renderReaderEmpty() {
  state.readerToken++;
  state.current = null;
  releaseObjectUrls();
  $("#reader").replaceChildren(h("p", { class: "reader-note" }, "Choisis un message dans la liste pour le lire."));
}

async function openMessage(id) {
  const token = ++state.readerToken;
  const reader = $("#reader");
  releaseObjectUrls();
  reader.replaceChildren(h("p", { class: "reader-note" }, "Ouverture du message…"));

  try {
    let meta = state.messages.find((m) => m.id === id);
    if (!meta) meta = (await api(`/api/messages/${id}`)).message;
    const response = await api(`/api/messages/${id}/raw`, { raw: true });
    const mail = await PostalMime.parse(await response.arrayBuffer());
    if (token !== state.readerToken) return;

    state.current = { meta, mail };
    renderMessage(meta, mail);

    if (!meta.is_read) {
      meta.is_read = 1;
      if (meta.folder === "inbox") state.unread = Math.max(0, state.unread - 1);
      renderList();
      updateFolders();
      api(`/api/messages/${id}`, { method: "PATCH", body: { is_read: true } }).catch(() => {});
    }
  } catch (err) {
    if (token !== state.readerToken || err.status === 401) return;
    reader.replaceChildren(
      backBar(),
      h("p", { class: "reader-note form-error" }, `Impossible d'ouvrir ce message. ${err.message}`)
    );
  }
}

function backBar(primary = null, secondary = []) {
  return h(
    "div",
    { class: "reader-bar" },
    h(
      "div",
      { class: "reader-bar-main" },
      h("a", { class: "btn btn-quiet back", href: `#/${state.folder}` }, "Retour"),
      h("span", { class: "spacer" }),
      primary
    ),
    secondary.length ? h("div", { class: "reader-bar-more" }, secondary) : null
  );
}

function person(entry) {
  if (!entry) return null;
  // Un groupe d'adresses : on affiche ses membres.
  if (Array.isArray(entry.group)) return entry.group.map(person);
  const name = entry.name && entry.name !== entry.address ? entry.name : "";
  return h(
    "span",
    null,
    name ? h("span", { class: "who-name" }, name) : null,
    name ? " " : null,
    h("span", { class: name ? "who-address" : "who-name" }, name ? `<${entry.address || ""}>` : entry.address || "")
  );
}

function people(list) {
  const items = (list || []).flatMap((entry) => [person(entry), ", "]);
  items.pop();
  return items;
}

function renderMessage(meta, mail) {
  const reader = $("#reader");
  const actions = [];
  const inTrash = meta.folder === "trash";
  const primary =
    !inTrash && meta.direction === "in"
      ? h("button", { class: "btn btn-primary", type: "button", onclick: () => openCompose({ reply: true }) }, "Répondre")
      : null;

  if (meta.folder === "inbox") {
    actions.push(h("button", { class: "btn", type: "button", onclick: () => moveMessage(meta, "archive", "Message archivé.") }, "Archiver"));
    actions.push(h("button", { class: "btn", type: "button", onclick: () => markUnread(meta) }, "Marquer non lu"));
  }
  if (meta.folder === "archive") {
    actions.push(
      h("button", { class: "btn", type: "button", onclick: () => moveMessage(meta, "inbox", "Message remis dans la boîte de réception.") }, "Remettre en réception")
    );
  }
  if (inTrash) {
    const home = meta.direction === "out" ? "sent" : "inbox";
    actions.push(h("button", { class: "btn", type: "button", onclick: () => moveMessage(meta, home, "Message restauré.") }, "Restaurer"));
    actions.push(armedButton("Supprimer définitivement", "Confirmer la suppression", () => removeMessage(meta, true)));
  } else {
    actions.push(h("button", { class: "btn btn-danger", type: "button", onclick: () => removeMessage(meta, false) }, "Corbeille"));
  }

  const subject = mail.subject || meta.subject || "(sans objet)";
  const sentAt = mail.date ? new Date(mail.date) : new Date(meta.date);
  const metaRows = [
    h("dt", null, "De"),
    h("dd", null, mail.from ? person(mail.from) : meta.from_addr),
    h("dt", null, "À"),
    h("dd", null, mail.to && mail.to.length ? people(mail.to) : meta.to_addr),
  ];
  if (mail.cc && mail.cc.length) metaRows.push(h("dt", null, "Cc"), h("dd", null, people(mail.cc)));
  metaRows.push(
    h("dt", null, "Date"),
    h("dd", null, h("time", { datetime: sentAt.toISOString() }, longFormat.format(sentAt)))
  );

  const letter = h(
    "article",
    { class: "letter" },
    h("header", { class: "letter-head" }, h("h2", { class: "letter-subject" }, subject), h("dl", { class: "letter-meta" }, metaRows))
  );

  const attachments = mail.attachments || [];
  if (mail.html) {
    const html = sanitizeHtml(mail.html);
    const inlineImages = inlineImageMap(attachments);
    const frame = h("iframe", {
      class: "letter-frame",
      title: "Contenu du message",
      sandbox: "allow-same-origin allow-popups allow-popups-to-escape-sandbox",
      referrerpolicy: "no-referrer",
    });
    const show = (allowRemote) => {
      frame.srcdoc = buildFrameDocument(html, inlineImages, allowRemote);
    };
    frame.addEventListener("load", () => fitFrame(frame));

    if (hasRemoteContent(html)) {
      const notice = h(
        "div",
        { class: "images-notice" },
        h("span", null, "Les images distantes sont masquées pour éviter le pistage."),
        h(
          "button",
          {
            class: "btn",
            type: "button",
            onclick: () => {
              show(true);
              notice.remove();
            },
          },
          "Afficher les images"
        )
      );
      letter.append(notice);
    }
    letter.append(frame);
    show(false);
  } else {
    letter.append(h("pre", { class: "letter-plain" }, mail.text || "(message vide)"));
  }

  const files = attachments.filter((a) => !(a.related && a.contentId && mail.html));
  if (files.length) {
    letter.append(
      h(
        "section",
        { class: "attachments" },
        h("h3", null, files.length > 1 ? `${files.length} pièces jointes` : "1 pièce jointe"),
        h(
          "ul",
          null,
          files.map((file, index) => {
            const blob = new Blob([file.content], { type: "application/octet-stream" });
            const url = URL.createObjectURL(blob);
            state.objectUrls.push(url);
            const name = cleanFilename(file.filename) || `piece-jointe-${index + 1}`;
            return h(
              "li",
              null,
              h("a", { class: "attachment", href: url, download: name }, name, h("small", null, fileSize(blob.size)))
            );
          })
        )
      )
    );
  }

  reader.replaceChildren(backBar(primary, actions), letter);
  reader.scrollTop = 0;
  if ($("#layout").dataset.view === "reader") window.scrollTo(0, 0);
}

function cleanFilename(name) {
  return String(name || "")
    .replace(/[\u0000-\u001f\u007f‪-‮⁦-⁩]/g, "")
    .replace(/[\\/]/g, "_")
    .trim()
    .slice(0, 150);
}

// Le HTML d'un mail est du contenu inconnu : on retire ce qui peut agir
// (scripts, formulaires, redirections), puis on l'affiche dans un cadre isolé
// qui ne peut ni exécuter de script ni charger de ressource distante.
function sanitizeHtml(html) {
  const doc = new DOMParser().parseFromString(String(html), "text/html");
  doc
    .querySelectorAll(
      "script, meta, link, base, iframe, frame, frameset, object, embed, applet, form, input, button, textarea, select, noscript, template, svg, math"
    )
    .forEach((node) => node.remove());
  for (const el of doc.querySelectorAll("*")) {
    for (const attr of [...el.attributes]) {
      const name = attr.name.toLowerCase();
      if (name.startsWith("on") || name === "srcdoc" || name === "formaction") {
        el.removeAttribute(attr.name);
      } else if (["href", "src", "xlink:href", "action", "background"].includes(name) && /^\s*(javascript|vbscript|data:text\/html)/i.test(attr.value)) {
        el.removeAttribute(attr.name);
      }
    }
    if (el.tagName === "A") el.setAttribute("rel", "noopener noreferrer");
  }
  const styles = [...doc.querySelectorAll("style")].map((style) => style.outerHTML).join("");
  doc.querySelectorAll("style").forEach((style) => style.remove());
  return styles + doc.body.innerHTML;
}

function hasRemoteContent(html) {
  return /(?:src|background|poster)\s*=\s*["']?\s*https?:|url\(\s*["']?\s*https?:/i.test(html);
}

// Images intégrées au message (cid:...) → données en ligne.
function inlineImageMap(attachments) {
  const map = new Map();
  for (const file of attachments) {
    if (!file.contentId || !/^image\/(png|jpe?g|gif|webp|bmp)$/i.test(file.mimeType || "")) continue;
    const id = String(file.contentId).replace(/^<|>$/g, "").trim();
    if (!id || map.has(id)) continue;
    const bytes = new Uint8Array(file.content);
    let binary = "";
    for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    map.set(id, `data:${file.mimeType.toLowerCase()};base64,${btoa(binary)}`);
  }
  return map;
}

function buildFrameDocument(html, inlineImages, allowRemote) {
  const body = html.replace(/cid:([^"'\s)>]+)/gi, (whole, id) => {
    let key = id;
    try {
      key = decodeURIComponent(id);
    } catch {
      /* identifiant laissé tel quel */
    }
    return inlineImages.get(key) || inlineImages.get(id) || whole;
  });
  const policy = [
    "default-src 'none'",
    "style-src 'unsafe-inline'",
    `img-src data:${allowRemote ? " https:" : ""}`,
    "font-src data:",
  ].join("; ");
  return `<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="${policy}">
<base target="_blank">
<style>
html{color-scheme:light}
body{margin:0;padding:20px 16px;font:16px/1.55 "Segoe UI",system-ui,-apple-system,sans-serif;color:#17223b;background:#fff;overflow-wrap:anywhere}
img{max-width:100%;height:auto}
table{max-width:100%}
pre{white-space:pre-wrap}
a{color:#1d57b8}
blockquote{margin:0 0 0 .5em;padding-left:.9em;border-left:3px solid #d8dfea;color:#5a6880}
</style></head><body>${body}</body></html>`;
}

// Le cadre prend la hauteur de son contenu (et la suit si des images arrivent).
function fitFrame(frame) {
  const doc = frame.contentDocument;
  if (!doc || !doc.documentElement) return;
  const resize = () => {
    frame.style.height = "0px";
    frame.style.height = `${Math.max(doc.documentElement.scrollHeight, doc.body ? doc.body.scrollHeight : 0)}px`;
  };
  resize();
  if (doc.body && "ResizeObserver" in window) new ResizeObserver(resize).observe(doc.body);
}

// ---------------------------------------------------------------- actions

async function moveMessage(meta, folder, done) {
  try {
    await api(`/api/messages/${meta.id}`, { method: "PATCH", body: { folder } });
    toast(done);
    leaveMessage(meta);
  } catch (err) {
    toast(err.message);
  }
}

async function markUnread(meta) {
  try {
    await api(`/api/messages/${meta.id}`, { method: "PATCH", body: { is_read: false } });
    meta.is_read = 0;
    toast("Message marqué non lu.");
    location.hash = `#/${state.folder}`;
    loadList({ quiet: true });
  } catch (err) {
    toast(err.message);
  }
}

async function removeMessage(meta, forever) {
  try {
    await api(`/api/messages/${meta.id}`, { method: "DELETE" });
    toast(forever ? "Message supprimé définitivement." : "Message mis à la corbeille.");
    leaveMessage(meta);
  } catch (err) {
    toast(err.message);
  }
}

// Après un déplacement : le message quitte la liste et on revient au dossier.
function leaveMessage(meta) {
  state.messages = state.messages.filter((m) => m.id !== meta.id);
  location.hash = `#/${state.folder}`;
  loadList({ quiet: true });
}

// -------------------------------------------------------------- rédaction

function buildCompose() {
  const error = h("p", { class: "form-error", id: "compose-error", role: "alert" });
  const send = h("button", { class: "btn btn-primary", type: "submit", id: "compose-send" }, "Envoyer");

  const form = h(
    "form",
    { class: "compose-form", id: "compose-form", novalidate: true },
    h(
      "div",
      { class: "compose-head" },
      h("h2", { id: "compose-title" }, "Nouveau message"),
      h("button", { class: "btn btn-quiet", type: "button", onclick: () => $("#compose").close() }, "Fermer")
    ),
    h("p", { class: "compose-from", id: "compose-from" }),
    h(
      "div",
      { class: "field" },
      h("label", { for: "compose-to" }, "À"),
      h("input", {
        class: "input",
        id: "compose-to",
        type: "text",
        inputmode: "email",
        autocomplete: "off",
        autocapitalize: "none",
        spellcheck: "false",
        placeholder: "nom@exemple.fr, autre@exemple.fr",
      })
    ),
    h(
      "div",
      { class: "field" },
      h("label", { for: "compose-cc" }, "Cc (facultatif)"),
      h("input", { class: "input", id: "compose-cc", type: "text", inputmode: "email", autocomplete: "off", autocapitalize: "none", spellcheck: "false" })
    ),
    h("div", { class: "field" }, h("label", { for: "compose-subject" }, "Objet"), h("input", { class: "input", id: "compose-subject", type: "text", maxlength: "500" })),
    h("div", { class: "field" }, h("label", { for: "compose-text" }, "Message"), h("textarea", { class: "input", id: "compose-text", rows: "10" })),
    error,
    h("div", { class: "compose-actions" }, send)
  );

  form.addEventListener("submit", async (event) => {
    event.preventDefault();
    error.textContent = "";
    const to = $("#compose-to").value.trim();
    if (!to) {
      error.textContent = "Indique au moins un destinataire.";
      $("#compose-to").focus();
      return;
    }
    send.disabled = true;
    send.textContent = "Envoi…";
    try {
      const result = await api("/api/send", {
        method: "POST",
        body: {
          to,
          cc: $("#compose-cc").value.trim(),
          subject: $("#compose-subject").value,
          text: $("#compose-text").value,
          reply_to_id: state.replyToId || undefined,
        },
      });
      form.reset();
      state.replyToId = null;
      $("#compose").close();
      toast(result.stored ? "Message envoyé." : "Message envoyé, mais la copie n'a pas pu être rangée dans Envoyés.");
      if (state.folder === "sent") loadList({ quiet: true });
    } catch (err) {
      error.textContent = err.message;
    }
    send.disabled = false;
    send.textContent = "Envoyer";
  });

  return h("dialog", { class: "compose", id: "compose", "aria-labelledby": "compose-title" }, form);
}

function openCompose({ reply = false } = {}) {
  const dialog = $("#compose");
  const fromLine = $("#compose-from");
  const error = $("#compose-error");
  error.textContent = "";

  if (state.me.canSend) {
    fromLine.textContent = `De : ${state.me.from}`;
  } else {
    fromLine.textContent = "";
    error.textContent = "L'envoi n'est pas encore configuré : ajoute la clé Resend et l'adresse d'expédition (voir le guide).";
  }
  $("#compose-send").disabled = !state.me.canSend;

  if (reply && state.current) {
    const { meta, mail } = state.current;
    const target = (mail.replyTo && mail.replyTo[0] && mail.replyTo[0].address) || (mail.from && mail.from.address) || meta.from_addr;
    const subject = mail.subject || meta.subject || "";
    $("#compose-title").textContent = "Réponse";
    $("#compose-to").value = target || "";
    $("#compose-cc").value = "";
    $("#compose-subject").value = /^\s*re\s*:/i.test(subject) ? subject : `Re: ${subject}`;
    $("#compose-text").value = quoteFor(meta, mail);
    state.replyToId = meta.id;
  } else {
    $("#compose-title").textContent = "Nouveau message";
    if (state.replyToId) {
      // On passe d'une réponse à un nouveau message : on repart d'une page blanche.
      $("#compose-form").reset();
      state.replyToId = null;
    }
  }

  if (!dialog.open) dialog.showModal();
  const field = reply ? $("#compose-text") : $("#compose-to");
  field.focus();
  if (reply) field.setSelectionRange(0, 0);
}

function quoteFor(meta, mail) {
  const source = mail.text || htmlToText(mail.html || "");
  const who = (mail.from && (mail.from.name || mail.from.address)) || meta.from_addr || "";
  const when = longFormat.format(mail.date ? new Date(mail.date) : new Date(meta.date));
  const quoted = source
    .trim()
    .split(/\r?\n/)
    .map((line) => (line ? `> ${line}` : ">"))
    .join("\n");
  return `\n\nLe ${when}, ${who} a écrit :\n${quoted}\n`;
}

function htmlToText(html) {
  const doc = new DOMParser().parseFromString(String(html), "text/html");
  doc.querySelectorAll("script, style, head, title").forEach((node) => node.remove());
  if (!doc.body) return "";
  doc.body.querySelectorAll("br").forEach((node) => node.replaceWith("\n"));
  doc.body
    .querySelectorAll("p, div, h1, h2, h3, h4, h5, h6, li, tr, blockquote, table, ul, ol, hr")
    .forEach((node) => node.append("\n"));
  return (doc.body.textContent || "")
    .replace(/[ \t]+\n/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

// -------------------------------------------------------------- démarrage

window.addEventListener("hashchange", () => {
  if (state.me && state.me.authenticated) handleRoute();
});

// Relève discrète du courrier quand l'onglet est visible.
setInterval(() => {
  if (!state.me || !state.me.authenticated || document.hidden || state.loading) return;
  if (state.messages.length <= 50) loadList({ quiet: true });
}, REFRESH_MS);

document.addEventListener("visibilitychange", () => {
  if (!document.hidden && state.me && state.me.authenticated && state.loadedFolder && state.messages.length <= 50) {
    loadList({ quiet: true });
  }
});

(async function boot() {
  try {
    state.me = await api("/api/me");
  } catch (err) {
    app.replaceChildren(h("p", { class: "fatal" }, err.message));
    return;
  }
  if (state.me.authenticated) startMailbox();
  else renderLogin();
})();
