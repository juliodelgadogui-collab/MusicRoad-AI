CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  email TEXT UNIQUE NOT NULL,
  username TEXT UNIQUE,
  password_hash TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'client',
  status TEXT NOT NULL DEFAULT 'active',
  last_login_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT
);

CREATE TABLE IF NOT EXISTS music_library (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER,
  title TEXT NOT NULL,
  artist TEXT,
  album TEXT,
  genre TEXT,
  year INTEGER,
  duration INTEGER,
  cover_url TEXT,
  origin TEXT NOT NULL,
  origin_ref TEXT,
  mime_type TEXT,
  file_size INTEGER,
  created_at TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_music_origin_ref ON music_library(origin, origin_ref);

CREATE TABLE IF NOT EXISTS user_music_state (
  user_id INTEGER NOT NULL,
  music_id INTEGER NOT NULL,
  is_favorite INTEGER NOT NULL DEFAULT 0,
  play_count INTEGER NOT NULL DEFAULT 0,
  skip_count INTEGER NOT NULL DEFAULT 0,
  last_played_at TEXT,
  PRIMARY KEY (user_id, music_id)
);

CREATE TABLE IF NOT EXISTS playlists (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  name TEXT NOT NULL,
  description TEXT,
  is_ai INTEGER DEFAULT 0,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS playlist_tracks (
  playlist_id INTEGER NOT NULL,
  music_id INTEGER NOT NULL,
  position INTEGER NOT NULL,
  PRIMARY KEY (playlist_id, music_id)
);

CREATE TABLE IF NOT EXISTS drive_folders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT,
  folder_id TEXT UNIQUE NOT NULL,
  folder_link TEXT NOT NULL,
  active INTEGER NOT NULL DEFAULT 1,
  last_import_at TEXT,
  last_status TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS drive_link_checks (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  source_link TEXT,
  file_id TEXT,
  title TEXT,
  mime_type TEXT,
  status TEXT NOT NULL,
  message TEXT,
  saved_music_id INTEGER,
  checked_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS client_device_state (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  device_name TEXT,
  music_permission TEXT NOT NULL DEFAULT 'unknown',
  location_permission TEXT NOT NULL DEFAULT 'unknown',
  music_folder_count INTEGER NOT NULL DEFAULT 0,
  music_track_count INTEGER NOT NULL DEFAULT 0,
  last_latitude REAL,
  last_longitude REAL,
  last_seen_at TEXT NOT NULL,
  UNIQUE(user_id, device_id)
);

CREATE TABLE IF NOT EXISTS radars (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  external_id TEXT,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  uf TEXT,
  cidade TEXT,
  rodovia TEXT,
  km TEXT,
  sentido TEXT,
  heading REAL,
  velocidade INTEGER,
  tipo TEXT,
  situacao TEXT,
  fonte TEXT,
  data_fonte TEXT,
  data_importacao TEXT NOT NULL,
  ultima_confirmacao TEXT,
  confiabilidade TEXT NOT NULL DEFAULT 'BAIXA',
  quantidade_fontes INTEGER NOT NULL DEFAULT 1,
  ativo INTEGER NOT NULL DEFAULT 1
);

CREATE TABLE IF NOT EXISTS radar_sources (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  radar_id INTEGER NOT NULL,
  source_name TEXT NOT NULL,
  external_id TEXT,
  raw_payload TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS road_reports (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER,
  latitude REAL NOT NULL,
  longitude REAL NOT NULL,
  type TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'NAO_CONFIRMADO',
  confirmations INTEGER NOT NULL DEFAULT 0,
  expires_at TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS import_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  source TEXT NOT NULL,
  format TEXT NOT NULL,
  total_rows INTEGER DEFAULT 0,
  inserted_rows INTEGER DEFAULT 0,
  updated_rows INTEGER DEFAULT 0,
  duplicate_rows INTEGER DEFAULT 0,
  errors TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS app_settings (
  key TEXT PRIMARY KEY,
  value TEXT
);

CREATE TABLE IF NOT EXISTS audit_logs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  action TEXT NOT NULL,
  payload TEXT,
  ip TEXT,
  created_at TEXT NOT NULL
);
