#!/usr/bin/env bash
set -euo pipefail

# Serves a prebuilt MBTiles/PMTiles road map from the Estrada Play VPS.
# Put a production tileset at /opt/estradaplay-tiles/data (or override TILE_DATA_DIR).
# This script does not download copyrighted/proprietary map data.

DATA_DIR="${TILE_DATA_DIR:-/opt/estradaplay-tiles/data}"
PORT="${TILESERVER_PORT:-8088}"
IMAGE="${TILESERVER_IMAGE:-maptiler/tileserver-gl:latest}"
mkdir -p "$DATA_DIR"

if ! command -v docker >/dev/null 2>&1; then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io
  systemctl enable --now docker
fi

if ! find "$DATA_DIR" -maxdepth 1 \( -name '*.mbtiles' -o -name '*.pmtiles' \) | grep -q .; then
  echo "Nenhum .mbtiles/.pmtiles encontrado em $DATA_DIR" >&2
  echo "Adicione seu tileset licenciado/próprio antes de iniciar o servidor." >&2
  exit 2
fi

docker pull "$IMAGE"
docker rm -f estradaplay-tiles >/dev/null 2>&1 || true
docker run -d --name estradaplay-tiles --restart unless-stopped \
  -p "127.0.0.1:${PORT}:8080" -v "$DATA_DIR:/data:ro" "$IMAGE"

echo "TileServer ativo em http://127.0.0.1:${PORT}."
echo "Publique-o atrás do HTTPS/reverse proxy e configure map_style_url com a URL /styles/<estilo>/style.json."
