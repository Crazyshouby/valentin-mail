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
  r2_key      TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_messages_folder_date ON messages (folder, date DESC);
CREATE INDEX IF NOT EXISTS idx_messages_unread ON messages (folder, is_read);

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
