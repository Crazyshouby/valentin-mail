-- Boîte mail maison : index des messages (le contenu brut vit dans R2).
CREATE TABLE IF NOT EXISTS messages (
  id          TEXT PRIMARY KEY,
  folder      TEXT NOT NULL DEFAULT 'inbox',   -- inbox | sent | archive | trash
  direction   TEXT NOT NULL DEFAULT 'in',      -- in | out
  from_addr   TEXT NOT NULL DEFAULT '',
  from_name   TEXT NOT NULL DEFAULT '',
  to_addr     TEXT NOT NULL DEFAULT '',
  subject     TEXT NOT NULL DEFAULT '',
  message_id  TEXT,
  date        INTEGER NOT NULL,                -- millisecondes (réception ou envoi)
  size        INTEGER NOT NULL DEFAULT 0,
  is_read     INTEGER NOT NULL DEFAULT 0,
  r2_key      TEXT NOT NULL,
  thread_id   TEXT,                            -- conversation : identifiant de son premier message
  indexed     INTEGER NOT NULL DEFAULT 0,      -- 0 : texte à indexer ; 1 : indexé ; -1, -2 : essais interrompus ; -3 : laissé de côté
  source      TEXT,                            -- boîte d'origine d'un message importé (gmail:adresse)
  import_ref  TEXT,                            -- son identifiant là-bas : un message n'est importé qu'une fois.
                                               -- Un message arrivé normalement et retrouvé là-bas le porte aussi, sans source.
  box         TEXT,                            -- boîte d'où il vient : adresse du domaine, ou compte Gmail (transfert, import)
  scanned     INTEGER NOT NULL DEFAULT 0       -- 0 : pièces jointes à relever ; 1 : relevées ; 2 à 4 : relectures
                                               -- interrompues (abandon après la troisième) ; 9 : message illisible
);

-- Base créée avant l'arrivée des conversations : ajouter la colonne à la main, une fois :
--   ALTER TABLE messages ADD COLUMN thread_id TEXT;
--   UPDATE messages SET thread_id = id WHERE thread_id IS NULL;
--   ALTER TABLE messages ADD COLUMN indexed INTEGER NOT NULL DEFAULT 0;
-- Base créée avant l'import et les dossiers personnels :
--   ALTER TABLE messages ADD COLUMN source TEXT;
--   ALTER TABLE messages ADD COLUMN import_ref TEXT;
--   ALTER TABLE rules ADD COLUMN target TEXT;
-- Base créée avant les boîtes, la galerie des pièces jointes et la liste des appareils :
--   ALTER TABLE messages ADD COLUMN box TEXT;
--   ALTER TABLE messages ADD COLUMN scanned INTEGER NOT NULL DEFAULT 0;
--   ALTER TABLE devices ADD COLUMN session TEXT;
--   UPDATE messages SET box = substr(source, 7) WHERE source IS NOT NULL AND box IS NULL;
--   UPDATE messages SET box = from_addr WHERE direction = 'out' AND box IS NULL;

CREATE INDEX IF NOT EXISTS idx_messages_folder_date ON messages (folder, date DESC);
CREATE INDEX IF NOT EXISTS idx_messages_unread ON messages (folder, is_read);
CREATE INDEX IF NOT EXISTS idx_messages_thread ON messages (thread_id);
-- Retrouver un message par son identifiant d'origine : conversations, et doublons à l'import.
CREATE INDEX IF NOT EXISTS idx_messages_message_id ON messages (message_id) WHERE message_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_import_ref ON messages (import_ref) WHERE import_ref IS NOT NULL;
-- Le courrier d'une boîte, du plus récent au plus ancien.
CREATE INDEX IF NOT EXISTS idx_messages_box ON messages (box, date DESC);
-- Les messages dont les pièces jointes restent à relever.
CREATE INDEX IF NOT EXISTS idx_messages_unscanned ON messages (scanned, date DESC) WHERE scanned != 1;

-- Inventaire des pièces jointes, pour la galerie. Pour un message reçu ou importé : où la pièce se
-- trouve dans le message brut (start, length) et comment elle y est écrite (encoding). Pour un
-- message envoyé d'ici : le fichier déposé (upload).
CREATE TABLE IF NOT EXISTS attachments (
  message_id TEXT NOT NULL,
  part       INTEGER NOT NULL,
  name       TEXT NOT NULL,
  mime       TEXT NOT NULL,
  kind       TEXT NOT NULL,      -- image | pdf | doc | media | other
  size       INTEGER NOT NULL,   -- une fois décodée (approchée pour le base64)
  start      INTEGER NOT NULL,
  length     INTEGER NOT NULL,
  encoding   TEXT NOT NULL,
  upload     TEXT,
  date       INTEGER NOT NULL,   -- celle du message
  PRIMARY KEY (message_id, part)
) WITHOUT ROWID;

CREATE INDEX IF NOT EXISTS idx_attachments_date ON attachments (date DESC, message_id, part);

-- Appareils connectés. id : empreinte du jeton de session (le jeton lui-même n'est gardé nulle part).
CREATE TABLE IF NOT EXISTS sessions (
  id      TEXT PRIMARY KEY,
  kind    TEXT NOT NULL,         -- app | web
  name    TEXT NOT NULL,
  place   TEXT NOT NULL DEFAULT '',
  created INTEGER NOT NULL,
  seen    INTEGER NOT NULL,
  expires INTEGER NOT NULL,
  revoked INTEGER NOT NULL DEFAULT 0
);

-- Dossiers personnels. Un message peut être rangé dans plusieurs ; « Parent/Enfant » fait un sous-dossier.
-- source : rempli quand le dossier a été créé par un import, pour pouvoir défaire l'import proprement.
CREATE TABLE IF NOT EXISTS labels (
  id      TEXT PRIMARY KEY,
  name    TEXT NOT NULL UNIQUE,
  source  TEXT,
  created INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS message_labels (
  message_id TEXT NOT NULL,
  label_id   TEXT NOT NULL,
  PRIMARY KEY (message_id, label_id)
) WITHOUT ROWID;

CREATE INDEX IF NOT EXISTS idx_message_labels_label ON message_labels (label_id);

CREATE TABLE IF NOT EXISTS drafts (
  id          TEXT PRIMARY KEY,
  to_addr     TEXT NOT NULL DEFAULT '',
  cc          TEXT NOT NULL DEFAULT '',
  subject     TEXT NOT NULL DEFAULT '',
  text        TEXT NOT NULL DEFAULT '',
  reply_to_id TEXT,
  updated     INTEGER NOT NULL
);

-- Échecs de connexion, pour bloquer les essais de mot de passe en rafale.
CREATE TABLE IF NOT EXISTS login_failures (
  ip TEXT NOT NULL,
  ts INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_login_failures_ip_ts ON login_failures (ip, ts);

-- Pièces jointes des messages envoyés (le fichier lui-même vit dans R2, sous uploads/<id>).
-- message_id reste vide tant que le message n'est pas parti.
CREATE TABLE IF NOT EXISTS uploads (
  id          TEXT PRIMARY KEY,
  message_id  TEXT,
  name        TEXT NOT NULL,
  type        TEXT NOT NULL DEFAULT 'application/octet-stream',
  size        INTEGER NOT NULL DEFAULT 0,
  created     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_uploads_message ON uploads (message_id);

-- Adresses du domaine qui reçoivent du courrier ; une adresse coupée refuse les messages.
CREATE TABLE IF NOT EXISTS aliases (
  address TEXT PRIMARY KEY,
  label   TEXT NOT NULL DEFAULT '',
  enabled INTEGER NOT NULL DEFAULT 1,
  created INTEGER NOT NULL
);

-- Règles de tri appliquées à l'arrivée : la première qui correspond l'emporte.
CREATE TABLE IF NOT EXISTS rules (
  id      TEXT PRIMARY KEY,
  field   TEXT NOT NULL,   -- from | to | subject
  value   TEXT NOT NULL,   -- texte à repérer, sans tenir compte des majuscules
  action  TEXT NOT NULL,   -- archive | trash | read | block | label | file
  target  TEXT,            -- pour label et file : le dossier personnel où ranger
  created INTEGER NOT NULL
);

-- Téléphones à prévenir à l'arrivée d'un message (jetons Firebase Cloud Messaging).
CREATE TABLE IF NOT EXISTS devices (
  token   TEXT PRIMARY KEY,
  created INTEGER NOT NULL,
  session TEXT              -- session qui l'a inscrit : la fermer coupe aussi ses notifications
);

-- Petites valeurs gardées un temps, comme le jeton d'accès Google (une heure).
CREATE TABLE IF NOT EXISTS cache (
  key     TEXT PRIMARY KEY,
  value   TEXT NOT NULL,
  expires INTEGER NOT NULL
);

-- Texte des messages, pour la recherche. Rempli peu à peu, à chaque consultation de la boîte.
-- Chaque ligne porte le même numéro (rowid) que son message : on la retrouve sans parcourir l'index.
CREATE INDEX IF NOT EXISTS idx_messages_indexed ON messages (indexed, date DESC);
CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(id UNINDEXED, body, tokenize = 'unicode61 remove_diacritics 2');

-- Réglages partagés entre l'appli et le webmail (la signature, par exemple).
CREATE TABLE IF NOT EXISTS settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
