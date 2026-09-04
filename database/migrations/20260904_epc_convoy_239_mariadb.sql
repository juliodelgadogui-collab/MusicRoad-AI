-- EPC_CONVOY_SCHEMA_V239
-- MariaDB / MySQL migration for Estrada Play Comboio.
-- Run ONCE during deployment (after a database backup) before replacing api/convoy.php.
-- It is non-destructive: creates missing tables/columns and does not delete user data.

CREATE TABLE IF NOT EXISTS estrada_convoys (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
  code VARCHAR(8) NOT NULL UNIQUE,
  owner_user_id BIGINT NULL,
  leader_device_token VARCHAR(160) NULL,
  title VARCHAR(80) NULL,
  destination_label VARCHAR(120) NULL,
  destination_lat DECIMAL(10,7) NULL,
  destination_lon DECIMAL(10,7) NULL,
  route_points_json MEDIUMTEXT NULL,
  route_updated_at DATETIME NULL,
  created_at DATETIME NOT NULL,
  expires_at DATETIME NOT NULL,
  INDEX idx_convoy_exp (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS estrada_convoy_members (
  convoy_id BIGINT UNSIGNED NOT NULL,
  user_id BIGINT NULL,
  device_token VARCHAR(160) NOT NULL,
  nickname VARCHAR(60) NOT NULL,
  latitude DECIMAL(10,7) NULL,
  longitude DECIMAL(10,7) NULL,
  speed_kmh DECIMAL(7,2) NULL,
  heading DECIMAL(7,2) NULL,
  joined_at DATETIME NOT NULL,
  last_seen DATETIME NOT NULL,
  PRIMARY KEY (convoy_id, device_token),
  INDEX idx_convoy_member_seen (convoy_id, last_seen),
  INDEX idx_convoy_member_device (device_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS estrada_convoy_blocks (
  convoy_id BIGINT UNSIGNED NOT NULL,
  device_token VARCHAR(160) NOT NULL,
  blocked_at DATETIME NOT NULL,
  PRIMARY KEY (convoy_id, device_token),
  INDEX idx_convoy_blocked_at (blocked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Existing installations from Comboio 2.3.x may have an older estrada_convoys table.
-- MariaDB supports ADD COLUMN IF NOT EXISTS, making this migration safe to re-run.
ALTER TABLE estrada_convoys ADD COLUMN IF NOT EXISTS leader_device_token VARCHAR(160) NULL AFTER owner_user_id;
ALTER TABLE estrada_convoys ADD COLUMN IF NOT EXISTS destination_label VARCHAR(120) NULL AFTER title;
ALTER TABLE estrada_convoys ADD COLUMN IF NOT EXISTS destination_lat DECIMAL(10,7) NULL AFTER destination_label;
ALTER TABLE estrada_convoys ADD COLUMN IF NOT EXISTS destination_lon DECIMAL(10,7) NULL AFTER destination_lat;
ALTER TABLE estrada_convoys ADD COLUMN IF NOT EXISTS route_points_json MEDIUMTEXT NULL AFTER destination_lon;
ALTER TABLE estrada_convoys ADD COLUMN IF NOT EXISTS route_updated_at DATETIME NULL AFTER route_points_json;
