#!/usr/bin/env bash
# Essai de charge du suivi public (incrément 7.2, RG11). Local uniquement. À NE PAS UTILISER EN PRODUCTION.
#
# Simule ECRANS écrans de suivi sur une Course pendant DUREE secondes : chaque écran démarre après un décalage
# aléatoire (0 à 3 s) puis interroge GET /api/courses/{COURSE}/suivi toutes les 3 s. Affiche les requêtes envoyées,
# le nombre de 200, de 429, de 5xx ou erreurs réseau, la latence médiane et le p95 (ms).
#
# Usage, à la racine du dépôt, stack lancée :
#   PEUPLER=30 ./scripts/charge-suivi.sh            crée (si absente) la Course « Backyard de charge » (400 m, 1 min,
#                                                   5 m, 50 participants, 5 Boucles max, date du jour à Paris) et y
#                                                   inscrit charge01…chargeNN (mot de passe un-mot-de-passe-12),
#                                                   puis affiche son identifiant ; idempotent.
#   COURSE=<id> ECRANS=50 DUREE=60 ./scripts/charge-suivi.sh
# Variables :
#   COURSE   : identifiant de la Course suivie (requis sans PEUPLER).
#   PEUPLER  : nombre de coureurs à inscrire (1 à 50) ; avec COURSE, la charge est lancée ensuite sur COURSE.
#   ECRANS   : écrans simulés (défaut 50).  DUREE : durée en secondes (défaut 60).
#   ADMIN_MASTER_PSEUDO, ADMIN_MASTER_MOT_DE_PASSE : pour PEUPLER, lues dans l'environnement, à défaut dans ./.env.
#   BASE_URL : adresse de l'application (défaut http://localhost) ; seuls localhost et 127.0.0.1 sont acceptés,
#              sauf DEMO_FORCER=oui (jamais en production).
# Codes de sortie : 0 succès ; 1 variable manquante, réponse inattendue, p95 > 500 ms ou au moins un 5xx ;
#   2 cible refusée. Aucun mot de passe n'est affiché ni écrit sur disque hors du répertoire temporaire.
# Prérequis : bash, curl, awk, date GNU (Linux) ou BSD (macOS).
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost}"
BASE_URL="${BASE_URL%/}"
ECRANS="${ECRANS:-50}"
DUREE="${DUREE:-60}"
NOM_COURSE="Backyard de charge"
MOT_DE_PASSE_COUREUR="un-mot-de-passe-12"
P95_MAX_MS=500

echouer() { echo "charge-suivi : $1" >&2; exit "${2:-1}"; }

hote_cible() {
  local reste="${BASE_URL#*://}"
  reste="${reste%%/*}"
  reste="${reste##*@}"
  echo "${reste%%:*}"
}

verifier_cible() {
  local hote
  hote="$(hote_cible)"
  if [[ "$hote" != "localhost" && "$hote" != "127.0.0.1" && "${DEMO_FORCER:-}" != "oui" ]]; then
    echouer "cible refusée (hôte « $hote ») : seuls localhost et 127.0.0.1 sont autorisés ; DEMO_FORCER=oui pour forcer, jamais en production." 2
  fi
}

verifier_entier() {
  [[ "$2" =~ ^[0-9]+$ ]] && (( $2 >= $3 && $2 <= $4 )) || echouer "$1 doit être un entier de $3 à $4 (reçu : « $2 »)."
}

lire_variable() {
  local nom="$1" ligne valeur=""
  if [[ -n "${!nom:-}" ]]; then printf '%s' "${!nom}"; return; fi
  if [[ -f .env ]]; then
    while IFS= read -r ligne || [[ -n "$ligne" ]]; do
      ligne="${ligne%$'\r'}"
      [[ "$ligne" == "$nom="* ]] && valeur="${ligne#"$nom="}"
    done < .env
  fi
  [[ -n "$valeur" ]] || echouer "variable $nom manquante (ni dans l'environnement, ni dans .env)."
  printf '%s' "$valeur"
}

echapper_json() {
  local texte="${1//\\/\\\\}"
  printf '%s' "${texte//\"/\\\"}"
}

aujourdhui() {
  TZ=Europe/Paris date +%F
}

# requete METHODE CHEMIN [fichier du corps] : écrit le statut HTTP, le corps dans $REPONSE.
requete() {
  local options=(-s -o "$REPONSE" -w '%{http_code}' -b "$COOKIES" -c "$COOKIES" -X "$1")
  if [[ "$1" == "POST" || "$1" == "PUT" ]]; then
    options+=(-H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(jeton_csrf)" --data-binary @-)
  fi
  curl "${options[@]}" "$BASE_URL$2" < "${3:-/dev/stdin}" || true
}

jeton_csrf() {
  awk '$6 == "XSRF-TOKEN" { jeton = $7 } END { print jeton }' "$COOKIES"
}

attendre() {
  [[ "$2" == "$1" ]] || echouer "$3 : réponse inattendue (HTTP $2)."
}

renouveler_csrf() {
  attendre 204 "$(requete GET /api/csrf /dev/null)" "jeton CSRF"
}

nouvelle_session() {
  COOKIES="$TEMPORAIRE/cookies-$1"
  : > "$COOKIES"
  renouveler_csrf
}

connecter() {
  attendre 200 "$(printf '{"pseudo":"%s","motDePasse":"%s"}' "$(echapper_json "$1")" "$(echapper_json "$2")" \
    | requete POST /api/connexion)" "connexion de $1"
  renouveler_csrf
}

# identifiant : id du premier objet (plat) du tableau JSON de $REPONSE dont le nom est celui de la Course de charge.
identifiant_course() {
  tr '{' '\n' < "$REPONSE" | grep -F "\"nom\":\"$NOM_COURSE\"" | grep -o '"id":"[0-9a-f-]*"' | head -n 1 \
    | cut -d '"' -f 4 || true
}

declarer_course_de_charge() {
  nouvelle_session admin
  connecter "$(lire_variable ADMIN_MASTER_PSEUDO)" "$(lire_variable ADMIN_MASTER_MOT_DE_PASSE)"
  attendre 200 "$(requete GET /api/administration/courses /dev/null)" "liste des courses"
  ID_COURSE="$(identifiant_course)"
  if [[ -n "$ID_COURSE" ]]; then
    echo "déjà présent : $NOM_COURSE (course)"
    return
  fi
  attendre 201 "$(printf '{"nom":"%s","date":"%s","distanceBoucleMetres":400,"dureeBoucleMinutes":1,"denivelePositifBoucleMetres":5,"nombreMaxParticipants":50,"nombreMaxBoucles":5}' \
    "$NOM_COURSE" "$(aujourdhui)" | requete POST /api/administration/courses)" "déclaration de $NOM_COURSE"
  ID_COURSE="$(grep -o '"id":"[0-9a-f-]*"' "$REPONSE" | head -n 1 | cut -d '"' -f 4)"
  echo "créé : $NOM_COURSE (course)"
}

# delai_limite_creation : secondes annoncées par le 429 de POST /api/comptes (reessayerDansSecondes de $REPONSE),
# 60 par défaut, bornées de 1 à 60.
delai_limite_creation() {
  local secondes
  secondes="$(grep -o '"reessayerDansSecondes" *: *[0-9]*' "$REPONSE" | grep -o '[0-9]*$' || true)"
  secondes="${secondes:-60}"
  (( 10#$secondes < 1 )) && secondes=1
  (( 10#$secondes > 60 )) && secondes=60
  printf '%d' "$(( 10#$secondes ))"
}

# creer_coureur PSEUDO : sur 429 (plafond de créations par adresse, SEC-15), attend la durée annoncée et renouvelle la
# même création, trois fois au plus.
creer_coureur() {
  local statut essais=0 delai
  while :; do
    statut="$(printf '{"pseudo":"%s","motDePasse":"%s"}' "$1" "$MOT_DE_PASSE_COUREUR" | requete POST /api/comptes)"
    [[ "$statut" == "429" && $essais -lt 3 ]] || break
    essais=$(( essais + 1 ))
    delai="$(delai_limite_creation)"
    echo "limite de création de comptes atteinte, nouvel essai dans $delai s"
    sleep "$delai"
  done
  if [[ "$statut" == "201" ]]; then
    echo "créé : $1 (compte COUREUR)"
  elif [[ "$statut" == "409" ]] && grep -q '"code" *: *"PSEUDO_DEJA_UTILISE"' "$REPONSE"; then
    echo "déjà présent : $1 (compte COUREUR)"
  else
    echouer "création du compte $1 : réponse inattendue (HTTP $statut)."
  fi
}

inscrire_coureur() {
  local statut
  nouvelle_session "$1"
  connecter "$1" "$MOT_DE_PASSE_COUREUR"
  statut="$(requete POST "/api/coureur/courses/$ID_COURSE/inscriptions" /dev/null)"
  if [[ "$statut" == "201" ]]; then
    echo "créé : $1 (inscription à $NOM_COURSE)"
  elif [[ "$statut" == "409" ]] && grep -q '"code" *: *"INSCRIPTION_DEJA_EXISTANTE"' "$REPONSE"; then
    echo "déjà présent : $1 (inscription à $NOM_COURSE)"
  else
    echouer "inscription de $1 : réponse inattendue (HTTP $statut)."
  fi
}

peupler() {
  local rang pseudo
  declarer_course_de_charge
  nouvelle_session anonyme
  for (( rang = 1; rang <= PEUPLER; rang++ )); do
    printf -v pseudo 'charge%02d' "$rang"
    creer_coureur "$pseudo"
  done
  for (( rang = 1; rang <= PEUPLER; rang++ )); do
    printf -v pseudo 'charge%02d' "$rang"
    inscrire_coureur "$pseudo"
  done
  echo "Course « $NOM_COURSE » : $ID_COURSE (à démarrer dans l'interface, puis COURSE=$ID_COURSE ./scripts/charge-suivi.sh)"
}

# pause_aleatoire MAX_MS : attend un délai uniforme dans [0 ; MAX_MS[ millisecondes.
pause_aleatoire() {
  local ms=$(( (RANDOM * 32768 + RANDOM) % $1 ))
  sleep "$(printf '%d.%03d' $(( ms / 1000 )) $(( ms % 1000 )))"
}

# ecran RANG : une ligne « statut durée_secondes » par requête dans $TEMPORAIRE/ecran-RANG.
ecran() {
  local fichier="$TEMPORAIRE/ecran-$1"
  pause_aleatoire 3000
  while (( $(date +%s) < FIN )); do
    curl -s -o /dev/null -w '%{http_code} %{time_total}\n' --max-time 10 \
      "$BASE_URL/api/courses/$COURSE/suivi" >> "$fichier" || true
    sleep 3
  done
}

lancer_charge() {
  local rang
  echo "Charge : $ECRANS écran(s) pendant $DUREE s sur $BASE_URL/api/courses/$COURSE/suivi"
  FIN=$(( $(date +%s) + DUREE ))
  for (( rang = 1; rang <= ECRANS; rang++ )); do
    ecran "$rang" &
  done
  wait
}

# Synthèse et code de sortie : 1 si p95 > P95_MAX_MS ou au moins un 5xx / erreur réseau.
synthese() {
  cat "$TEMPORAIRE"/ecran-* 2>/dev/null > "$TEMPORAIRE/toutes" || true
  awk '{ print int($2 * 1000) }' "$TEMPORAIRE/toutes" | sort -n > "$TEMPORAIRE/latences"
  awk -v max="$P95_MAX_MS" -v latences="$TEMPORAIRE/latences" '
    { total++ }
    $1 == "200" { ok++ }
    $1 == "429" { limitees++ }
    $1 == "000" || $1 >= 500 { erreurs++ }
    END {
      n = 0
      while ((getline valeur < latences) > 0) { tri[++n] = valeur }
      mediane = n ? tri[int((n + 1) / 2)] : 0
      rang95 = int(n * 0.95 + 0.999999); if (rang95 < 1) rang95 = 1
      p95 = n ? tri[rang95] : 0
      printf "requêtes envoyées : %d\n200 : %d\n429 : %d\n5xx ou erreurs réseau : %d\n", total, ok, limitees, erreurs
      printf "latence médiane : %d ms\nlatence p95 : %d ms\n", mediane, p95
      exit (total == 0 || erreurs > 0 || p95 > max) ? 1 : 0
    }' "$TEMPORAIRE/toutes"
}

verifier_cible
[[ -n "${COURSE:-}" || -n "${PEUPLER:-}" ]] || echouer "COURSE (identifiant de la Course) ou PEUPLER (nombre de coureurs) est requis."
TEMPORAIRE="$(mktemp -d)"
trap 'rm -rf "$TEMPORAIRE"' EXIT
trap 'exit 130' INT TERM
REPONSE="$TEMPORAIRE/reponse"
COOKIES="$TEMPORAIRE/cookies"

if [[ -n "${PEUPLER:-}" ]]; then
  verifier_entier PEUPLER "$PEUPLER" 1 50
  peupler
  [[ -n "${COURSE:-}" ]] || exit 0
fi
[[ "$COURSE" =~ ^[0-9a-f-]{36}$ ]] || echouer "COURSE doit être un identifiant de Course (UUID)."
verifier_entier ECRANS "$ECRANS" 1 1000
verifier_entier DUREE "$DUREE" 1 3600
lancer_charge
synthese
