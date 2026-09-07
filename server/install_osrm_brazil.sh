#!/usr/bin/env bash
set -euo pipefail

# Builds a private Brazil OSRM backend with Docker.
# Requires substantial disk/RAM and can take hours on the first import.
# Usage: sudo bash server/install_osrm_brazil.sh

DATA_DIR="${OSRM_DATA_DIR:-/opt/estradaplay-osrm}"
PORT="${OSRM_PORT:-5000}"
PBF_URL="${OSRM_PBF_URL:-https://download.geofabrik.de/south-america/brazil-latest.osm.pbf}"
IMAGE="${OSRM_IMAGE:-osrm/osrm-backend:v5.27.1}"

mkdir -p "$DATA_DIR"
cd "$DATA_DIR"

if ! command -v docker >/dev/null 2>&1; then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io curl
  systemctl enable --now docker
fi

if [[ ! -s brazil-latest.osm.pbf ]]; then
  curl -fL --retry 4 --retry-delay 5 "$PBF_URL" -o brazil-latest.osm.pbf
fi

docker pull "$IMAGE"
docker run --rm -t -v "$DATA_DIR:/data" "$IMAGE" osrm-extract -p /opt/car.lua /data/brazil-latest.osm.pbf
docker run --rm -t -v "$DATA_DIR:/data" "$IMAGE" osrm-partition /data/brazil-latest.osrm
docker run --rm -t -v "$DATA_DIR:/data" "$IMAGE" osrm-customize /data/brazil-latest.osrm

docker rm -f estradaplay-osrm >/dev/null 2>&1 || true
docker run -d --name estradaplay-osrm --restart unless-stopped \
  -p "127.0.0.1:${PORT}:5000" -v "$DATA_DIR:/data" "$IMAGE" \
  osrm-routed --algorithm mld /data/brazil-latest.osrm

echo
echo "OSRM privado ativo em http://127.0.0.1:${PORT}"
echo "Configure routing_osrm_url=http://127.0.0.1:${PORT} no app_settings do servidor."
echo "Mantenha routing_public_fallback=0 em produção."
