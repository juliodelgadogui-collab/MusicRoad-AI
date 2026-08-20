-- MusicRoad 1.2.0 SecureDB — MariaDB 10.5+ / MySQL 8.0+
-- Banco relacional principal. Use um usuário exclusivo com acesso apenas a este banco.

CREATE TABLE IF NOT EXISTS schema_meta (
  `key` VARCHAR(100) PRIMARY KEY,
  `value` VARCHAR(255) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS users (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  email VARCHAR(190) NOT NULL UNIQUE,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  role ENUM('admin','client') NOT NULL DEFAULT 'client',
  status ENUM('active','suspended','disabled') NOT NULL DEFAULT 'active',
  last_login_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_users_role_status(role,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


CREATE TABLE IF NOT EXISTS auth_attempts (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  login_hash CHAR(64) NOT NULL,
  ip_hash CHAR(64) NOT NULL,
  success TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_auth_login_time(login_hash,created_at),
  KEY idx_auth_ip_time(ip_hash,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS plans (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(120) NOT NULL,
  duration_days INT UNSIGNED NOT NULL,
  price_cents INT UNSIGNED NOT NULL DEFAULT 0,
  active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS licenses (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  plan_id BIGINT UNSIGNED NULL,
  source ENUM('admin','mercadopago','migration','promo') NOT NULL DEFAULT 'admin',
  status ENUM('active','expired','cancelled','pending') NOT NULL DEFAULT 'active',
  starts_at DATETIME NOT NULL,
  ends_at DATETIME NOT NULL,
  notes TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_licenses_user_status_ends(user_id,status,ends_at),
  KEY idx_licenses_ends(ends_at),
  CONSTRAINT fk_licenses_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_licenses_plan FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payment_orders (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  plan_id BIGINT UNSIGNED NOT NULL,
  provider VARCHAR(40) NOT NULL DEFAULT 'mercadopago',
  external_reference VARCHAR(190) NOT NULL UNIQUE,
  provider_preference_id VARCHAR(190) NULL,
  provider_payment_id VARCHAR(190) NULL,
  status VARCHAR(64) NOT NULL DEFAULT 'created',
  amount_cents INT UNSIGNED NOT NULL,
  checkout_url TEXT NULL,
  raw_payload LONGTEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  paid_at DATETIME NULL,
  KEY idx_payment_orders_user(user_id,created_at),
  KEY idx_payment_orders_payment(provider_payment_id),
  CONSTRAINT fk_payment_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_payment_plan FOREIGN KEY(plan_id) REFERENCES plans(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS payment_events (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  provider VARCHAR(40) NOT NULL,
  event_id VARCHAR(190) NULL,
  event_type VARCHAR(100) NULL,
  resource_id VARCHAR(190) NULL,
  signature_valid TINYINT(1) NOT NULL DEFAULT 0,
  payload LONGTEXT NULL,
  processed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_payment_event(provider,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS music_library (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NULL,
  title VARCHAR(300) NOT NULL,
  artist VARCHAR(255) NULL,
  album VARCHAR(255) NULL,
  genre VARCHAR(120) NULL,
  year INT NULL,
  duration INT NULL,
  cover_url TEXT NULL,
  origin VARCHAR(64) NOT NULL,
  origin_ref VARCHAR(255) NULL,
  mime_type VARCHAR(120) NULL,
  file_size BIGINT NULL,
  is_favorite TINYINT(1) NOT NULL DEFAULT 0,
  play_count INT UNSIGNED NOT NULL DEFAULT 0,
  skip_count INT UNSIGNED NOT NULL DEFAULT 0,
  last_played_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY idx_music_origin_ref(origin,origin_ref),
  KEY idx_music_user_origin(user_id,origin),
  KEY idx_music_favorite(is_favorite),
  CONSTRAINT fk_music_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS user_music_state (
  user_id BIGINT UNSIGNED NOT NULL,
  music_id BIGINT UNSIGNED NOT NULL,
  is_favorite TINYINT(1) NOT NULL DEFAULT 0,
  play_count INT UNSIGNED NOT NULL DEFAULT 0,
  skip_count INT UNSIGNED NOT NULL DEFAULT 0,
  last_played_at DATETIME NULL,
  PRIMARY KEY(user_id,music_id),
  CONSTRAINT fk_ums_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE,
  CONSTRAINT fk_ums_music FOREIGN KEY(music_id) REFERENCES music_library(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS playlists (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  name VARCHAR(190) NOT NULL,
  description TEXT NULL,
  is_ai TINYINT(1) NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_playlist_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS playlist_tracks (
  playlist_id BIGINT UNSIGNED NOT NULL,
  music_id BIGINT UNSIGNED NOT NULL,
  position INT NOT NULL,
  PRIMARY KEY(playlist_id,music_id),
  CONSTRAINT fk_pt_playlist FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
  CONSTRAINT fk_pt_music FOREIGN KEY(music_id) REFERENCES music_library(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS google_tokens (
  user_id BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  access_token LONGTEXT NOT NULL,
  refresh_token LONGTEXT NULL,
  expires_at BIGINT NOT NULL,
  scope TEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_gt_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS drive_folders (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(255) NULL,
  folder_id VARCHAR(255) NOT NULL UNIQUE,
  folder_link TEXT NOT NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  last_import_at DATETIME NULL,
  last_status VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS drive_link_checks (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source_link TEXT NULL,
  file_id VARCHAR(255) NULL,
  title VARCHAR(300) NULL,
  mime_type VARCHAR(120) NULL,
  status VARCHAR(80) NOT NULL,
  message TEXT NULL,
  saved_music_id BIGINT UNSIGNED NULL,
  checked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS client_device_state (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  device_id VARCHAR(190) NOT NULL,
  device_name VARCHAR(190) NULL,
  music_permission VARCHAR(40) NOT NULL DEFAULT 'unknown',
  location_permission VARCHAR(40) NOT NULL DEFAULT 'unknown',
  music_folder_count INT UNSIGNED NOT NULL DEFAULT 0,
  music_track_count INT UNSIGNED NOT NULL DEFAULT 0,
  last_latitude DOUBLE NULL,
  last_longitude DOUBLE NULL,
  last_seen_at DATETIME NOT NULL,
  UNIQUE KEY uq_device_user(user_id,device_id),
  CONSTRAINT fk_device_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS radars (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  external_id VARCHAR(190) NULL,
  latitude DOUBLE NOT NULL,
  longitude DOUBLE NOT NULL,
  uf CHAR(2) NULL,
  cidade VARCHAR(190) NULL,
  rodovia VARCHAR(100) NULL,
  km VARCHAR(40) NULL,
  sentido VARCHAR(100) NULL,
  heading DOUBLE NULL,
  velocidade INT NULL,
  tipo VARCHAR(100) NULL,
  situacao VARCHAR(100) NULL,
  fonte VARCHAR(255) NULL,
  data_fonte VARCHAR(40) NULL,
  data_importacao DATETIME NOT NULL,
  ultima_confirmacao DATETIME NULL,
  confiabilidade VARCHAR(40) NOT NULL DEFAULT 'BAIXA',
  quantidade_fontes INT UNSIGNED NOT NULL DEFAULT 1,
  ativo TINYINT(1) NOT NULL DEFAULT 1,
  expires_at DATETIME NULL,
  KEY idx_radars_lat_lon(latitude,longitude),
  KEY idx_radars_active_lat_lon(ativo,latitude,longitude),
  KEY idx_radars_active(ativo),
  KEY idx_radars_active_expires(ativo,expires_at),
  KEY idx_radars_uf(uf),
  KEY idx_radars_external(external_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS radar_sources (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  radar_id BIGINT UNSIGNED NOT NULL,
  source_name VARCHAR(190) NOT NULL,
  external_id VARCHAR(190) NULL,
  raw_payload LONGTEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_radar_sources_radar(radar_id),
  CONSTRAINT fk_rs_radar FOREIGN KEY(radar_id) REFERENCES radars(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS road_reports (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NULL,
  external_id VARCHAR(190) NULL,
  latitude DOUBLE NOT NULL,
  longitude DOUBLE NOT NULL,
  type VARCHAR(100) NOT NULL,
  speed INT NULL,
  heading DOUBLE NULL,
  source VARCHAR(80) NOT NULL DEFAULT 'USUARIO',
  payload_json LONGTEXT NULL,
  status VARCHAR(80) NOT NULL DEFAULT 'PENDENTE',
  confirmations INT UNSIGNED NOT NULL DEFAULT 0,
  reviewed_by BIGINT UNSIGNED NULL,
  reviewed_at DATETIME NULL,
  review_note TEXT NULL,
  expires_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_road_reports_expires(expires_at),
  KEY idx_road_reports_status_created(status,created_at),
  KEY idx_road_reports_user_created(user_id,created_at),
  CONSTRAINT fk_rr_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE SET NULL,
  CONSTRAINT fk_rr_reviewer FOREIGN KEY(reviewed_by) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS import_logs (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source VARCHAR(190) NOT NULL,
  format VARCHAR(80) NOT NULL,
  total_rows INT UNSIGNED NOT NULL DEFAULT 0,
  inserted_rows INT UNSIGNED NOT NULL DEFAULT 0,
  updated_rows INT UNSIGNED NOT NULL DEFAULT 0,
  duplicate_rows INT UNSIGNED NOT NULL DEFAULT 0,
  errors LONGTEXT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS app_settings (
  `key` VARCHAR(190) NOT NULL PRIMARY KEY,
  `value` LONGTEXT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS audit_logs (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NULL,
  action VARCHAR(190) NOT NULL,
  payload LONGTEXT NULL,
  ip VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_audit_created(created_at),
  KEY idx_audit_action(action),
  CONSTRAINT fk_audit_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS address_local_packs (
  municipality_code CHAR(7) NOT NULL PRIMARY KEY,
  uf CHAR(2) NOT NULL,
  city VARCHAR(190) NOT NULL,
  source_url TEXT NULL,
  zip_path TEXT NULL,
  status VARCHAR(40) NOT NULL DEFAULT 'new',
  source_rows INT UNSIGNED NOT NULL DEFAULT 0,
  street_count INT UNSIGNED NOT NULL DEFAULT 0,
  downloaded_at DATETIME NULL,
  indexed_at DATETIME NULL,
  last_error TEXT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_addr_pack_updated(updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS address_local_streets (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  municipality_code CHAR(7) NOT NULL,
  uf CHAR(2) NOT NULL,
  city VARCHAR(190) NOT NULL,
  street VARCHAR(300) NOT NULL,
  street_fold VARCHAR(240) NOT NULL,
  street_name_fold VARCHAR(240) NOT NULL,
  locality VARCHAR(240) NULL,
  locality_fold VARCHAR(180) NOT NULL DEFAULT '',
  cep VARCHAR(12) NULL,
  latitude DOUBLE NULL,
  longitude DOUBLE NULL,
  geo_level INT NULL,
  sample_count INT UNSIGNED NOT NULL DEFAULT 1,
  UNIQUE KEY uq_addr_street(municipality_code,street_fold,locality_fold),
  KEY idx_addr_street_mun_fold(municipality_code,street_fold),
  KEY idx_addr_street_mun_name(municipality_code,street_name_fold),
  KEY idx_addr_street_cep(municipality_code,cep)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS address_local_points (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  municipality_code CHAR(7) NOT NULL,
  street_fold VARCHAR(240) NOT NULL,
  street VARCHAR(300) NOT NULL,
  number_text VARCHAR(80) NOT NULL DEFAULT '',
  number_int INT NULL,
  locality VARCHAR(240) NULL,
  cep VARCHAR(12) NULL,
  latitude DOUBLE NOT NULL,
  longitude DOUBLE NOT NULL,
  geo_level INT NULL,
  UNIQUE KEY uq_addr_point(municipality_code,street_fold,number_text,latitude,longitude),
  KEY idx_addr_points_lookup(municipality_code,street_fold,number_int)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS address_local_street_loads (
  municipality_code CHAR(7) NOT NULL,
  street_fold VARCHAR(240) NOT NULL,
  loaded_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  point_count INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY(municipality_code,street_fold)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS trial_claims (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL UNIQUE,
  device_hash CHAR(64) NOT NULL UNIQUE,
  network_hash CHAR(64) NOT NULL,
  user_agent_hash CHAR(64) NULL,
  source ENUM('android','web') NOT NULL DEFAULT 'web',
  claimed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at DATETIME NOT NULL,
  KEY idx_trial_network_claimed(network_hash,claimed_at),
  KEY idx_trial_expires(expires_at),
  CONSTRAINT fk_trial_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- Dispositivos vinculados à conta para reentrada automática no mesmo aparelho.
CREATE TABLE IF NOT EXISTS registered_devices (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT UNSIGNED NOT NULL,
  device_hash CHAR(64) NOT NULL UNIQUE,
  device_label VARCHAR(190) NULL,
  platform VARCHAR(40) NOT NULL DEFAULT 'android',
  app_version VARCHAR(40) NULL,
  auto_login_enabled TINYINT(1) NOT NULL DEFAULT 1,
  last_ip_hash CHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  revoked_at DATETIME NULL,
  KEY idx_registered_devices_user(user_id,auto_login_enabled,revoked_at),
  KEY idx_registered_devices_seen(last_seen_at),
  CONSTRAINT fk_registered_devices_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
