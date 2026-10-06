#!/usr/bin/env bash
# Arrête l'application en local (docker compose).
#
# Usage, depuis n'importe où : ./scripts/stop.sh [--purger]
#   (sans option) : arrête et supprime les conteneurs, conserve les volumes (base, logos).
#   --purger      : supprime aussi les volumes (base et logos) : toutes les données sont perdues.
set -euo pipefail

cd "$(dirname "$0")/.."

options=()
for argument in "$@"; do
  case "$argument" in
    --purger) options=(--volumes) ;;
    *) echo "stop : option inconnue « $argument »" >&2; exit 1 ;;
  esac
done

docker compose down "${options[@]}"
echo "==> Application arrêtée."
