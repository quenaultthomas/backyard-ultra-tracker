#!/usr/bin/env bash
# Démarre l'application en local (docker compose) puis peuple les données de démonstration.
# À NE PAS UTILISER EN PRODUCTION (voir docs/deploiement.md).
#
# Usage, depuis n'importe où : ./scripts/start.sh [--sans-demo] [--sans-build]
#   --sans-demo  : ne lance pas scripts/donnees-demo.sh
#   --sans-build : ne reconstruit pas les images
# Prérequis : docker compose, fichier .env (cp .env.example .env) avec ADMIN_MASTER_PSEUDO
# et ADMIN_MASTER_MOT_DE_PASSE renseignés (nécessaires au peuplement).
set -euo pipefail

cd "$(dirname "$0")/.."

demo=oui
options=(--build)
for argument in "$@"; do
  case "$argument" in
    --sans-demo) demo=non ;;
    --sans-build) options=() ;;
    *) echo "start : option inconnue « $argument »" >&2; exit 1 ;;
  esac
done

[[ -f .env ]] || { echo "start : .env absent (cp .env.example .env, puis renseigner l'admin master)." >&2; exit 1; }

echo "==> Démarrage de la stack (attente que tous les services soient sains)"
docker compose up -d --wait "${options[@]}"

if [[ "$demo" == "oui" ]]; then
  echo "==> Peuplement des données de démonstration"
  ./scripts/donnees-demo.sh
fi

echo "==> Application prête : http://localhost"
