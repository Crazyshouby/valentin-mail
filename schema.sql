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
  indexed     INTEGER NOT NULL DEFAULT 0       -- 1 quand son texte est dans l'index de recherche
);

-- Base créée avant l'arrivée des conversations : ajouter la colonne à la main, une fois :
--   ALTER TABLE messages ADD COLUMN thread_id TEXT;
--   UPDATE messages SET thread_id = id WHERE thread_id IS NULL;
--   ALTER TABLE messages ADD COLUMN indexed INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_messages_folder_date ON messages (folder, date DESC);
CREATE INDEX IF NOT EXISTS idx_messages_unread ON messages (folder, is_read);
CREATE INDEX IF NOT EXISTS idx_messages_thread ON messages (thread_id);

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
  action  TEXT NOT NULL,   -- archive | trash | read | block
  created INTEGER NOT NULL
);

-- Téléphones à prévenir à l'arrivée d'un message (jetons Firebase Cloud Messaging).
CREATE TABLE IF NOT EXISTS devices (
  token   TEXT PRIMARY KEY,
  created INTEGER NOT NULL
);

-- Petites valeurs gardées un temps, comme le jeton d'accès Google (une heure).
CREATE TABLE IF NOT EXISTS cache (
  key     TEXT PRIMARY KEY,
  value   TEXT NOT NULL,
  expires INTEGER NOT NULL
);

-- Texte des messages, pour la recherche. Rempli peu à peu, à chaque consultation de la boîte.
CREATE INDEX IF NOT EXISTS idx_messages_indexed ON messages (indexed, date DESC);
CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(id UNINDEXED, body, tokenize = 'unicode61 remove_diacritics 2');

-- Réglages partagés entre l'appli et le webmail (la signature, par exemple).
CREATE TABLE IF NOT EXISTS settings (
  key   TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
