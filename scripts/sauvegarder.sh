#!/usr/bin/env bash
# Sauvegarde de la base PostgreSQL (pg_dump -Fc exécuté dans le conteneur base), planifiable par cron (incrément 7.4).
#
# Usage, à la racine du dépôt : ./scripts/sauvegarder.sh [--verifier-restauration]
#   --verifier-restauration : restaure aussi le dump dans une base temporaire restauration_verif du conteneur base
#     (jamais dans la base de production), compare les lignes des tables course, inscription et passage avec la
#     base source, puis supprime la base temporaire dans tous les cas.
# Variables, toutes facultatives :
#   SAUVEGARDE_DOSSIER   : dossier de destination (défaut ./sauvegardes, créé s'il manque, droits 700).
#   SAUVEGARDE_CONSERVER : nombre de fichiers sauvegarde-*.dump gardés après succès (défaut 14, entier >= 1).
#   COMPOSE_FICHIERS     : options passées à docker compose (défaut -f docker-compose.yml -f docker-compose.prod.yml ;
#                          en dev : COMPOSE_FICHIERS="-f docker-compose.yml").
# Le fichier est écrit sous un nom temporaire, contrôlé par pg_restore --list, puis renommé
# sauvegarde-AAAAMMJJ-HHMM.dump (suffixe des secondes si ce nom existe déjà). Seuls les fichiers sauvegarde-*.dump
# sont concernés par la rotation ; le fichier qui vient d'être créé n'est jamais supprimé.
# Aucun mot de passe n'est lu ni affiché : utilisateur et base viennent du conteneur (POSTGRES_USER, POSTGRES_DB).
# Codes de sortie : 0 succès, 1 échec de la sauvegarde ou de la vérification, 2 option ou variable invalide.
set -euo pipefail

BASE_VERIFICATION=restauration_verif
TABLES_COMPTEES=(course inscription passage)

erreur() { echo "sauvegarder.sh : $*" >&2; }

verifier_restauration=non
for argument in "$@"; do
  case "$argument" in
    --verifier-restauration) verifier_restauration=oui ;;
    *) erreur "option inconnue : $argument (seule --verifier-restauration est acceptée)"; exit 2 ;;
  esac
done

dossier="${SAUVEGARDE_DOSSIER:-./sauvegardes}"
conserver="${SAUVEGARDE_CONSERVER:-14}"
if ! [[ "$conserver" =~ ^[0-9]+$ ]] || [ "$((10#$conserver))" -lt 1 ]; then
  erreur "SAUVEGARDE_CONSERVER doit être un entier >= 1 (valeur reçue : '$conserver')"
  exit 2
fi
conserver=$((10#$conserver))
read -r -a fichiers_compose <<< "${COMPOSE_FICHIERS:--f docker-compose.yml -f docker-compose.prod.yml}"

compose() { docker compose "${fichiers_compose[@]}" "$@"; }
# Exécute une commande sh dans base ; $POSTGRES_USER et $POSTGRES_DB y sont ceux du conteneur.
dans_base() { compose exec -T base sh -c "$@"; }

temporaire=""
base_verification_creee=non
nettoyer() {
  if [ -n "$temporaire" ] && [ -e "$temporaire" ]; then rm -f -- "$temporaire"; fi
  if [ "$base_verification_creee" = oui ]; then supprimer_base_verification || erreur "base $BASE_VERIFICATION non supprimée"; fi
}
trap nettoyer EXIT
trap 'exit 130' INT TERM

supprimer_base_verification() {
  dans_base 'PGOPTIONS="-c client_min_messages=warning" dropdb -U "$POSTGRES_USER" --if-exists "$1"' sh "$BASE_VERIFICATION"
}

compter_lignes() { # $1 : base (vide = base de production), $2 : table
  dans_base 'psql -U "$POSTGRES_USER" -d "${1:-$POSTGRES_DB}" -tAc "select count(*) from $2"' sh "$1" "$2"
}

nom_disponible() {
  local horodatage candidat
  horodatage="$(date +%Y%m%d-%H%M)"
  candidat="$dossier/sauvegarde-$horodatage.dump"
  [ -e "$candidat" ] && candidat="$dossier/sauvegarde-$horodatage-$(date +%S).dump"
  local rang=1 base_candidat="${candidat%.dump}"
  while [ -e "$candidat" ]; do candidat="$base_candidat-$rang.dump"; rang=$((rang + 1)); done
  echo "$candidat"
}

sauvegarder() {
  if ! dans_base 'pg_isready -q -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > /dev/null 2>&1; then
    erreur "le conteneur base n'est pas démarré ou pas prêt (docker compose ${fichiers_compose[*]} ps)"
    exit 1
  fi
  mkdir -p "$dossier"
  chmod 700 "$dossier"
  temporaire="$(mktemp "$dossier/.en-cours-XXXXXX")"
  if ! dans_base 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$temporaire"; then
    erreur "échec de pg_dump (disque plein ?) : aucune sauvegarde créée, les précédentes sont conservées"
    exit 1
  fi
  if [ ! -s "$temporaire" ] || ! dans_base 'pg_restore --list' < "$temporaire" > /dev/null; then
    erreur "dump vide ou illisible (pg_restore --list en échec) : aucune sauvegarde créée"
    exit 1
  fi
  fichier="$(nom_disponible)"
  mv -- "$temporaire" "$fichier"
  temporaire=""
}

verifier_restauration_du_dump() {
  supprimer_base_verification
  base_verification_creee=oui
  dans_base 'createdb -U "$POSTGRES_USER" "$1"' sh "$BASE_VERIFICATION"
  if ! dans_base 'pg_restore -U "$POSTGRES_USER" -d "$1" --no-owner --exit-on-error' sh "$BASE_VERIFICATION" < "$fichier"; then
    erreur "restauration de $fichier dans $BASE_VERIFICATION en échec"
    exit 1
  fi
  local table source restauree ecart=non
  for table in "${TABLES_COMPTEES[@]}"; do
    source="$(compter_lignes "" "$table")"
    restauree="$(compter_lignes "$BASE_VERIFICATION" "$table")"
    echo "$table : $source ligne(s) en base, $restauree ligne(s) restaurée(s)"
    [ "$source" = "$restauree" ] || ecart=oui
  done
  supprimer_base_verification
  base_verification_creee=non
  if [ "$ecart" = oui ]; then
    erreur "les comptages diffèrent entre la base et la restauration de $fichier (des écritures ont pu avoir lieu pendant la sauvegarde : relancer)"
    exit 1
  fi
  echo "Restauration vérifiée, base $BASE_VERIFICATION supprimée."
}

appliquer_rotation() {
  local ancien garde=1
  while IFS= read -r ancien; do
    [ "$ancien" = "$fichier" ] && continue
    if [ "$garde" -lt "$conserver" ]; then garde=$((garde + 1)); else rm -f -- "$ancien"; fi
  done < <(ls -1t -- "$dossier"/sauvegarde-*.dump)
}

sauvegarder
[ "$verifier_restauration" = oui ] && verifier_restauration_du_dump
appliquer_rotation
echo "Sauvegarde créée : $fichier ($(wc -c < "$fichier" | tr -d ' ') octets)"
