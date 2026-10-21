#!/usr/bin/env bash
# Données de démonstration pour le test manuel (incréments 2.1b, 2.4, 3.5, 4.1, 4.4b et 6.4). À NE PAS UTILISER EN PRODUCTION.
#
# Crée par l'API de l'application lancée (jamais d'accès direct à la base), dans cet ordre :
#   - admin Nadia, bénévoles Léo et Marc (session de l'admin master) ;
#   - Courses « Backyard de démo » (J+30), « Backyard express » (J+7), « Backyard mini » (J+14),
#     « Backyard du jour » (date du jour à Paris, 400 m, 1 min, 5 m, 10 participants, 5 Boucles max) et
#     « Backyard du soir » (date du jour, 400 m, 5 min, 5 m, 10 participants, 5 Boucles max), seules
#     démarrables le jour même (4.1) : « du jour » se termine vite, « du soir » reste en cours au moins
#     5 minutes, ce qui montre à la fois les rubriques « En cours » et « Terminées » de l'accueil (6.4) ;
#     les dates sont calculées dans le fuseau Europe/Paris, celui de l'API ;
#   - affectations du bénévole Léo à « Backyard de démo » et à « Backyard du jour » (les autres bénévoles
#     déjà affectés sont conservés ; Léo n'est pas affecté à « Backyard du soir ») ;
#   - coureurs Alice, Karim, Sophie et Tom (session anonyme) ;
#   - Inscriptions, chacune dans la session de son coureur et dans cet ordre : Karim, Sophie, Tom à
#     « Backyard de démo » ; Karim, Sophie à « Backyard express » ; Karim, Sophie, Tom (dossards 1, 2, 3) à
#     « Backyard du jour » ; Karim à « Backyard du soir ». Alice et « Backyard mini » n'en reçoivent aucune.
# Soit 23 éléments : 7 Comptes, 5 Courses, 2 affectations, 9 Inscriptions.
# Mots de passe connus : Nadia mot-de-passe-admin-1, Léo et Marc mot-de-passe-benevole-1,
#   Alice, Karim, Sophie et Tom un-mot-de-passe-12.
#
# Usage, à la racine du dépôt, stack lancée : ./scripts/donnees-demo.sh
# Variables :
#   ADMIN_MASTER_PSEUDO, ADMIN_MASTER_MOT_DE_PASSE : lues dans l'environnement, à défaut dans ./.env
#     (répertoire courant), ligne à ligne et telles quelles (ni guillemets retirés, ni $ interprété).
#   BASE_URL    : adresse de l'application (défaut http://localhost). Seuls les hôtes localhost et
#                 127.0.0.1 sont acceptés, sauf DEMO_FORCER=oui.
#   DEMO_FORCER : « oui » autorise une autre cible (toute autre valeur n'autorise rien). Jamais en production.
# Codes de sortie : 0 succès, 1 variable manquante ou réponse inattendue de l'API, 2 cible refusée.
#
# Idempotent : relancé, il ne crée rien en double. Limites :
#   - un Compte dont le pseudo est déjà pris (409 PSEUDO_DEJA_UTILISE) est « déjà présent », même s'il
#     a un autre rôle (non détecté) ;
#   - une Course est « déjà présente » si une Course du même nom existe ; elle n'est ni comparée ni modifiée ;
#   - Léo déjà affecté à une Course : rien n'est envoyé pour cette Course ;
#   - une Inscription déjà existante (409 INSCRIPTION_DEJA_EXISTANTE) est « déjà présente » ; une Course qui n'est
#     plus ouverte aux inscriptions (EN_COURS, TERMINEE) fait échouer le script ;
#   - « Backyard du jour » et « Backyard du soir » gardent la date de leur première création : un autre jour,
#     elles ne sont plus démarrables (repartir d'une base neuve par docker compose down -v, ou changer leur date
#     par « Modifier ») ; une fois démarrées, la relance du script échoue sur leurs inscriptions.
# Aucun mot de passe n'est affiché ni écrit sur disque ; les cookies vont dans un répertoire temporaire
# supprimé en sortie. Prérequis : bash, curl, date GNU (Linux) ou BSD (macOS).
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost}"
BASE_URL="${BASE_URL%/}"

echouer() { echo "donnees-demo : $1" >&2; exit "${2:-1}"; }

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
    echouer "cible refusée (hôte « $hote ») : seuls localhost et 127.0.0.1 sont autorisés. Les comptes de démonstration ont des mots de passe connus ; DEMO_FORCER=oui pour forcer, jamais en production." 2
  fi
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

# jour_plus N : date du jour + N jours à Paris (fuseau des Courses côté API), quel que soit le fuseau de l'hôte.
jour_plus() {
  TZ=Europe/Paris date -d "+$1 days" +%F 2>/dev/null || TZ=Europe/Paris date -v "+$1d" +%F
}

# requete METHODE CHEMIN [corps sur l'entrée standard] : écrit le statut HTTP, le corps dans $REPONSE.
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
  local attendu="$1" statut="$2" action="$3"
  [[ "$statut" == "$attendu" ]] || echouer "$action : réponse inattendue (HTTP $statut)."
}

renouveler_csrf() {
  attendre 204 "$(requete GET /api/csrf /dev/null)" "jeton CSRF"
}

connecter_admin_master() {
  local pseudo mot_de_passe
  pseudo="$(lire_variable ADMIN_MASTER_PSEUDO)"
  mot_de_passe="$(lire_variable ADMIN_MASTER_MOT_DE_PASSE)"
  renouveler_csrf
  attendre 200 "$(printf '{"pseudo":"%s","motDePasse":"%s"}' "$(echapper_json "$pseudo")" \
    "$(echapper_json "$mot_de_passe")" | requete POST /api/connexion)" "connexion de l'admin master"
  renouveler_csrf
}

creer_compte() {
  local chemin="$1" pseudo="$2" mot_de_passe="$3" role="$4" statut
  statut="$(printf '{"pseudo":"%s","motDePasse":"%s"}' "$pseudo" "$mot_de_passe" | requete POST "$chemin")"
  if [[ "$statut" == "201" ]]; then
    echo "créé : $pseudo (compte $role)"
  elif [[ "$statut" == "409" ]] && grep -q '"code" *: *"PSEUDO_DEJA_UTILISE"' "$REPONSE"; then
    echo "déjà présent : $pseudo (compte $role)"
  else
    echouer "création du compte $pseudo : réponse inattendue (HTTP $statut)."
  fi
}

declarer_course() {
  local nom="$1" jours="$2" distance="$3" duree="$4" denivele="$5" participants="$6" boucles="$7"
  if grep -qF "\"nom\":\"$nom\"" "$COURSES"; then
    echo "déjà présent : $nom (course)"
    return
  fi
  attendre 201 "$(printf '{"nom":"%s","date":"%s","distanceBoucleMetres":%d,"dureeBoucleMinutes":%d,"denivelePositifBoucleMetres":%d,"nombreMaxParticipants":%d,"nombreMaxBoucles":%d}' \
    "$nom" "$(jour_plus "$jours")" "$distance" "$duree" "$denivele" "$participants" "$boucles" \
    | requete POST /api/administration/courses)" "déclaration de la course $nom"
  echo "créé : $nom (course)"
}

declarer_courses() {
  attendre 200 "$(requete GET /api/administration/courses /dev/null)" "liste des courses"
  cp "$REPONSE" "$COURSES"
  declarer_course "Backyard de démo" 30 6706 60 120 50 24
  declarer_course "Backyard express" 7 400 1 5 10 5
  declarer_course "Backyard mini" 14 1000 2 10 2 2
  declarer_course "Backyard du jour" 0 400 1 5 10 5
  declarer_course "Backyard du soir" 0 400 5 5 10 5
}

# identifiant FICHIER CHAMP VALEUR : id du premier objet (plat) du tableau JSON dont CHAMP vaut VALEUR.
identifiant() {
  tr '{' '\n' < "$1" | grep -F "\"$2\":\"$3\"" | grep -o '"id":"[0-9a-f-]*"' | head -n 1 | cut -d '"' -f 4
}

affecter_benevole() {
  local benevole="$1" course="$2" id_benevole id_course deja
  attendre 200 "$(requete GET /api/administration/benevoles /dev/null)" "liste des bénévoles"
  id_benevole="$(identifiant "$REPONSE" pseudo "$benevole")"
  attendre 200 "$(requete GET /api/administration/courses /dev/null)" "liste des courses"
  id_course="$(identifiant "$REPONSE" nom "$course")"
  [[ -n "$id_benevole" && -n "$id_course" ]] || echouer "affectation de $benevole à $course : introuvable."
  attendre 200 "$(requete GET "/api/administration/courses/$id_course" /dev/null)" "fiche de la course $course"
  deja="$(grep -o '"benevoleIds":\[[^]]*\]' "$REPONSE" | sed 's/^"benevoleIds":\[//; s/\]$//')"
  if [[ "$deja" == *"\"$id_benevole\""* ]]; then
    echo "déjà présent : $benevole affecté à $course"
    return
  fi
  attendre 200 "$(printf '{"benevoleIds":[%s"%s"]}' "${deja:+$deja,}" "$id_benevole" \
    | requete PUT "/api/administration/courses/$id_course/benevoles")" "affectation de $benevole à $course"
  echo "créé : $benevole affecté à $course"
}

connecter_coureur() {
  local pseudo="$1"
  COOKIES="$TEMPORAIRE/cookies-$pseudo"
  : > "$COOKIES"
  renouveler_csrf
  attendre 200 "$(printf '{"pseudo":"%s","motDePasse":"%s"}' "$(echapper_json "$pseudo")" \
    "$MOT_DE_PASSE_COUREUR" | requete POST /api/connexion)" "connexion du coureur $pseudo"
  renouveler_csrf
}

# inscrire COUREUR COURSE... : inscrit le coureur, dans sa propre session, à chaque Course ouverte nommée.
inscrire() {
  local pseudo="$1" course id_course statut
  shift
  connecter_coureur "$pseudo"
  for course in "$@"; do
    attendre 200 "$(requete GET /api/coureur/courses /dev/null)" "courses ouvertes de $pseudo"
    id_course="$(identifiant "$REPONSE" nom "$course")"
    [[ -n "$id_course" ]] || echouer "inscription de $pseudo à $course : course introuvable ou plus ouverte."
    statut="$(requete POST "/api/coureur/courses/$id_course/inscriptions" /dev/null)"
    if [[ "$statut" == "201" ]]; then
      echo "créé : $pseudo (inscription à $course)"
    elif [[ "$statut" == "409" ]] && grep -q '"code" *: *"INSCRIPTION_DEJA_EXISTANTE"' "$REPONSE"; then
      echo "déjà présent : $pseudo (inscription à $course)"
    else
      echouer "inscription de $pseudo à $course : réponse inattendue (HTTP $statut)."
    fi
  done
}

verifier_cible
MOT_DE_PASSE_COUREUR="un-mot-de-passe-12"
TEMPORAIRE="$(mktemp -d)"
trap 'rm -rf "$TEMPORAIRE"' EXIT
trap 'exit 130' INT TERM
REPONSE="$TEMPORAIRE/reponse"
COURSES="$TEMPORAIRE/courses"
COOKIES="$TEMPORAIRE/cookies-admin"
: > "$COOKIES"

connecter_admin_master
creer_compte /api/administration/admins Nadia mot-de-passe-admin-1 ADMIN
creer_compte /api/administration/benevoles "Léo" mot-de-passe-benevole-1 BENEVOLE
creer_compte /api/administration/benevoles Marc mot-de-passe-benevole-1 BENEVOLE
declarer_courses
affecter_benevole "Léo" "Backyard de démo"
affecter_benevole "Léo" "Backyard du jour"

COOKIES="$TEMPORAIRE/cookies-anonyme"
: > "$COOKIES"
renouveler_csrf
for coureur in Alice Karim Sophie Tom; do
  creer_compte /api/comptes "$coureur" "$MOT_DE_PASSE_COUREUR" COUREUR
done

inscrire Karim "Backyard de démo" "Backyard express" "Backyard du jour" "Backyard du soir"
inscrire Sophie "Backyard de démo" "Backyard express" "Backyard du jour"
inscrire Tom "Backyard de démo" "Backyard du jour"
