# Increment 1.3 : Limiter les tentatives de connexion

Après plusieurs échecs de connexion consécutifs sur un même pseudo, `POST /api/connexion` refuse temporairement toute nouvelle tentative (429). Le comportement est identique que le pseudo existe ou non (pas d'énumération des pseudos). Cet incrément remplace la règle « aucune limitation » de 1.2 (RG9, CA27, point ouvert 4 de 1.2). Taille estimée : environ 200 lignes de production, sous la cible de 400 : aucun découpage nécessaire.

## 1. Périmètre

**Inclus**
- Compteur d'échecs de connexion par pseudo normalisé, blocage temporaire, remise à zéro après succès ou expiration, dans le contexte `comptes` (domaine, cas d'usage `Connecter`, port + adaptateur en mémoire).
- Réponse 429 `TENTATIVES_EXCESSIVES` (`ProblemDetail`, en-tête `Retry-After`), message identique pour un pseudo existant ou inexistant.
- Deux paramètres configurables par variables d'environnement (`CONNEXION_ECHECS_MAX`, `CONNEXION_BLOCAGE_SECONDES`), transmis à `api` par `docker-compose.yml`, documentés dans `.env.example` et `docs/deploiement.md`.
- Front : affichage du message de blocage sur l'écran `/connexion`.
- Mise à jour des tests de 1.2 devenus faux (CA27) ou dépendants d'un registre partagé (voir section 6).

**Exclu**
- Déblocage manuel par un admin, notification de l'utilisateur, CAPTCHA : non prévus (points ouverts 1, 2).
- Limitation par adresse IP ou globale, et limitation de `POST /api/comptes` : hors périmètre (point ouvert 2).
- Persistance du compteur en base (nouveau changeset Liquibase) : non, le compteur est en mémoire (RG13, point ouvert 3). Aucun changement de schéma.
- Compte à rebours ou désactivation du bouton côté écran : non (RG17).
- Horloge pilotable `/api/test/horloge` et test E2E de la fin du blocage : 4.3. Avant, la fin du blocage est couverte en unitaire et en intégration (`Clock` injecté) et à la main (délai raccourci).
- Création de l'admin master, rôles par endpoint, changement de mot de passe : 1.4 à 1.6, inchangés.
- Aucun autre endpoint modifié : `POST /api/deconnexion`, `GET /api/comptes/moi`, `POST /api/comptes`, `GET /api/csrf` inchangés (RG18).

## 2. Règles de gestion

**Compteur et blocage**
- **RG1** : clé de limitation = pseudo normalisé (RG2 de 1.2 : `trim` puis `toLowerCase(Locale.ROOT)`), que le Compte existe ou non. `ALICE`, `alice` et ` Alice ` partagent le même compteur. Chaque clé a un compteur d'échecs consécutifs et, une fois le seuil atteint, un instant `bloqueJusqu`.
- **RG2** : paramètres. `echecsMax` (entier ≥ 1, défaut 5) et `blocageSecondes` (entier ≥ 1, défaut 900, soit 15 min). Variables d'environnement `CONNEXION_ECHECS_MAX` et `CONNEXION_BLOCAGE_SECONDES`, lues dans `application.yml` (`backyard.connexion.echecs-max: ${CONNEXION_ECHECS_MAX:5}`, `backyard.connexion.blocage-secondes: ${CONNEXION_BLOCAGE_SECONDES:900}`) et passées à `api` par `docker-compose.yml` avec les mêmes défauts. Une valeur non entière ou hors borne fait échouer le démarrage de `api` avec un message explicite (nom du paramètre et borne), sans autre effet. Le domaine porte la validation (`PolitiqueBlocage`), jamais Spring.
- **RG3** : échecs comptés. Est compté un échec d'authentification (exception `IdentifiantsInvalidesException` de 1.2 RG4) pour un pseudo et un mot de passe dans les bornes de 1.2 RG6 : pseudo inconnu, mot de passe faux, Compte sans empreinte, pseudo au format invalide mais de 30 caractères au plus (`a b`). Ne sont pas comptés : 400 (champs requis, corps illisible), 415, 403 CSRF, 409 `DEJA_CONNECTE`, 401 hors bornes (pseudo de plus de 30 caractères ou mot de passe de plus de 128), 429, et les succès.
- **RG4** : déclenchement. L'échec qui porte le compteur à `echecsMax` reçoit encore la réponse 401 `IDENTIFIANTS_INVALIDES` de 1.2 et fixe `bloqueJusqu = maintenant + blocageSecondes`. La tentative suivante est la première refusée en 429. Exemple (défauts) : 5 échecs en 401, le 6e appel reçoit 429.
- **RG5** : pendant le blocage (`maintenant < bloqueJusqu`), toute tentative sur la clé est refusée en 429, même avec le bon mot de passe. Aucune vérification Argon2id n'est exécutée et le dépôt de Comptes n'est pas interrogé. Une tentative refusée ne compte pas et ne prolonge pas le blocage.
- **RG6** : fin du blocage. À `maintenant >= bloqueJusqu` (instant exact inclus), la clé est débloquée et son compteur vaut 0 : il faut de nouveau `echecsMax` échecs consécutifs pour bloquer. La première tentative après l'expiration est traitée normalement (succès ou 401 compté).
- **RG7** : oubli des échecs partiels. Si le compteur est inférieur à `echecsMax` et que le dernier échec date de `blocageSecondes` ou plus (`maintenant - dernierEchec >= blocageSecondes`), le compteur est considéré comme 0 avant d'enregistrer le nouvel échec. Exemple : 4 échecs à T, un échec à T+899 s : 5e échec, blocage ; un échec à T+900 s : compteur 1, pas de blocage.
- **RG8** : un succès de connexion supprime l'entrée de la clé (compteur 0).
- **RG9** : indiscernabilité. Le comportement (code, `title`, `detail`, `code`, en-têtes, `reessayerDansSecondes`) ne dépend que de la clé et de l'instant, jamais de l'existence du Compte, de son empreinte ou de la justesse du mot de passe. Un pseudo inexistant, un Compte existant et un Compte sans empreinte sont bloqués et débloqués exactement de la même façon.
- **RG10** : réponse de blocage : 429, `application/problem+json`, `type` `about:blank`, `title` « Trop de tentatives », `detail` « Trop de tentatives de connexion. Réessayez plus tard. », `code` `TENTATIVES_EXCESSIVES`, propriété `reessayerDansSecondes` (entier ≥ 1), en-tête `Retry-After` de même valeur (secondes). Aucun `Set-Cookie`, aucune session créée (maintient 1.2 RG10), ni pseudo ni mot de passe dans la réponse.
- **RG11** : ordre de contrôle de `POST /api/connexion` : CSRF (403), déjà connecté (409), lecture du corps (415 / 400), champs requis (400), bornes (401), blocage (429), authentification (200 / 401 compté). Le 429 ne précède donc jamais un 403, 409 ou 400, et ne concerne que des valeurs dans les bornes.
- **RG12** : le temps vient d'un `Clock` injecté dans `Connecter` (jamais `Instant.now()`). `reessayerDansSecondes = max(1, ceil(bloqueJusqu - maintenant))` en secondes.
- **RG13** : stockage en mémoire de `api`, borné à 10 000 clés. Les entrées dont le blocage ou la fenêtre d'oubli (RG7) est expiré sont purgées ; si la table est encore pleine à l'insertion, l'entrée au dernier échec le plus ancien est évincée. Un redémarrage de `api` remet tous les compteurs à zéro (comme les sessions, 1.2 RG13). Pas de partage entre instances.
- **RG14** : journal. Au déclenchement du blocage, une ligne WARN au message fixe « Connexion bloquée temporairement » ; pour une tentative refusée, une ligne INFO « Connexion refusée : blocage en cours ». Ces lignes, comme toute ligne de niveau INFO ou supérieur, ne contiennent ni pseudo saisi ni mot de passe (étend 1.2 RG8). Le journal « Échec de connexion » de 1.2 est inchangé.
- **RG15** : isolation. Les clés sont indépendantes : le blocage d'un pseudo n'affecte aucun autre pseudo. Il n'y a pas de limite par adresse IP (point ouvert 2). Le blocage ne ferme pas une session déjà ouverte sur ce Compte.
- **RG16** : architecture. Les règles (seuil, durée, oubli, remise à zéro) sont uniquement dans le domaine (`PolitiqueBlocage`, `TentativesConnexion`, exception métier typée `ConnexionBloqueeException`, port `RegistreTentativesConnexion`). L'adaptateur mémoire est dans `comptes.infrastructure`. `Connecter` orchestre. Les opérations « constater le blocage », « enregistrer un échec » et « effacer » sont atomiques par clé (accès concurrents sans perte d'incrément). Le contrôleur ne contient aucune logique ; `GestionnaireErreurs` traduit l'exception en 429. ArchUnit passe sans exception ; `courses` n'est pas touché.

**Front**
- **RG17** : sur `/connexion`, un 429 `TENTATIVES_EXCESSIVES` affiche dans `erreur-generale` « Trop de tentatives de connexion. Réessayez dans N minute(s). » avec `N = ceil(reessayerDansSecondes / 60)` (« 1 minute » si N vaut 1, « N minutes » sinon). Si `reessayerDansSecondes` est absent ou invalide : « Trop de tentatives de connexion. Réessayez plus tard. ». Comme pour tout échec : mot de passe vidé, pseudo conservé, bouton « Se connecter » réactivé, URL inchangée, état anonyme. Les autres messages de 1.2 sont inchangés. Le message est identique que le pseudo existe ou non.
- **RG18** : non-régression. `GET /api/comptes/moi`, `POST /api/deconnexion`, `POST /api/comptes`, `GET /api/csrf` et les règles d'accès de 1.2 (RG17 de 1.2) sont inchangés ; le 401 `IDENTIFIANTS_INVALIDES` de 1.2 est inchangé pour tout échec non bloqué.

## 3. Cas limites

- **Seuil exact** : avec `echecsMax = 5`, les échecs 1 à 4 donnent 401 et n'empêchent rien ; l'échec 5 donne 401 et bloque ; l'appel 6 donne 429 (RG4). Avec `echecsMax = 1`, le premier échec bloque.
- **Instant exact de fin** : à `bloqueJusqu - 0,5 s` : 429 avec `reessayerDansSecondes` = 1 ; à `bloqueJusqu` exactement : tentative normale (RG6, RG12).
- **Bon mot de passe pendant le blocage** : 429 quand même, sans vérification (RG5).
- **Pseudo inexistant** : mêmes seuil, durée et réponse qu'un pseudo existant (RG9). Un pseudo inexistant occupe une entrée du registre (RG13).
- **Compte sans empreinte** (simulé : `update compte set empreinte_mot_de_passe = null`) : compté et bloqué comme un pseudo inconnu (RG3, RG9).
- **Casse et espaces** : `ALICE` et ` alice ` alimentent le même compteur (RG1).
- **Valeurs hors bornes, champs vides, corps illisible, CSRF invalide, déjà connecté** : ne comptent pas et ne sont jamais en 429 (RG3, RG11). Un pseudo de 30 caractères exactement est dans les bornes et compte.
- **Mot de passe correct après 4 échecs** : succès, compteur remis à 0 ; il faut de nouveau 5 échecs pour bloquer (RG8).
- **Échecs espacés** : 4 échecs puis une longue pause (≥ `blocageSecondes`) puis 1 échec : compteur 1 (RG7).
- **Tentatives répétées pendant le blocage** : ne prolongent pas le blocage (RG5) ; un client qui insiste n'attend jamais plus que `blocageSecondes` depuis le 5e échec.
- **Requêtes concurrentes** : 20 échecs simultanés sur un même pseudo avec `echecsMax = 5` : au plus des 401 pour les requêtes déjà dans le contrôle, aucun compteur perdu, puis 429 ; aucune réponse autre que 401 / 429 (RG16).
- **Déni de service ciblé** : n'importe qui peut bloquer un pseudo connu en envoyant des échecs, y compris le Compte d'un tiers (point ouvert 1). Une session déjà ouverte reste valable (RG15).
- **Redémarrage de `api`** : compteurs et blocages remis à zéro (RG13).
- **Registre plein** : l'ajout d'une 10 001e clé purge l'expiré, sinon évince la plus ancienne (RG13).
- **Paramètres invalides** : `CONNEXION_ECHECS_MAX=0`, `abc` ou `CONNEXION_BLOCAGE_SECONDES=0` : `api` ne démarre pas (RG2). Variables absentes : défauts 5 et 900.
- **Plusieurs onglets / appareils** : le compteur est par pseudo, pas par session ni par navigateur.
- **Course, Boucle, Passage, Inscription, Abandon, Réintégration, bénévole non affecté, rôle insuffisant** : non applicables en 1.3.

## 4. Contrat d'API

Aucun nouvel endpoint. Un seul endpoint voit son contrat étendu. Toutes les erreurs sont des `ProblemDetail` (`application/problem+json`) : `{ "type": "about:blank", "title", "status", "detail", "code" }`.

### POST /api/connexion (étendu)
- Rôle requis : aucun (public). Requête `ConnexionRequete` et réponse 200 `CompteReponse` : inchangées (1.2).
- **Nouveau, 429** `TENTATIVES_EXCESSIVES` :
  - En-tête `Retry-After: <secondes>` (entier ≥ 1).
  - Corps : `{ "type": "about:blank", "title": "Trop de tentatives", "status": 429, "detail": "Trop de tentatives de connexion. Réessayez plus tard.", "code": "TENTATIVES_EXCESSIVES", "reessayerDansSecondes": <entier ≥ 1> }`.
  - Sans `Set-Cookie`. Identique pour pseudo existant, inexistant ou Compte sans empreinte (RG9).
- Inchangés : 200, 400 `VALIDATION_ECHOUEE` / `CORPS_ILLISIBLE`, 401 `IDENTIFIANTS_INVALIDES` (y compris pour les 5 premiers échecs, le 5e déclenchant le blocage), 403 `CSRF_INVALIDE`, 409 `DEJA_CONNECTE`, 415.
- Ordre de priorité : 403, 409, 415 / 400, 401 hors bornes, 429, 200 / 401 (RG11).

### Configuration (variables d'environnement de `api`)

| Variable | Type | Défaut | Borne | Rôle |
|---|---|---|---|---|
| `CONNEXION_ECHECS_MAX` | entier | 5 | ≥ 1 | échecs consécutifs avant blocage |
| `CONNEXION_BLOCAGE_SECONDES` | entier (secondes) | 900 | ≥ 1 | durée du blocage et de l'oubli des échecs partiels |

`.env.example` : les deux variables, facultatives, avec leur défaut, leur effet et la mention « valeur basse (30) utile pour tester à la main ». Les fichiers `docker-compose*.yml` les transmettent à `api` (`${CONNEXION_ECHECS_MAX:-5}`, `${CONNEXION_BLOCAGE_SECONDES:-900}`).

## 5. Écrans

### Se connecter (`/connexion`) : évolution
- **Affiché** : inchangé (1.2). Un nouveau message d'erreur possible dans `erreur-generale`.
- **Actions** : inchangées. Le bouton `bouton-connexion` reste utilisable après un 429 (une nouvelle tentative donne de nouveau 429 tant que le blocage dure).
- **Messages d'erreur** (`erreur-generale`) :
  - 429 `TENTATIVES_EXCESSIVES` avec `reessayerDansSecondes` = 900 : « Trop de tentatives de connexion. Réessayez dans 15 minutes. » ; 90 : « ... dans 2 minutes. » ; 30 ou 60 : « ... dans 1 minute. » ; valeur absente ou invalide : « Trop de tentatives de connexion. Réessayez plus tard. » (RG17).
  - 401 `IDENTIFIANTS_INVALIDES` : « Pseudo ou mot de passe incorrect. » (inchangé, y compris pour le 5e échec).
  - Les autres erreurs de 1.2 sont inchangées. Jamais de code HTTP ni de détail technique.
- En-tête global, Accueil, Créer un compte : inchangés.

## 6. Critères d'acceptation

Valeurs par défaut sauf mention : `echecsMax = 5`, `blocageSecondes = 900`. Mot de passe de référence : `un-mot-de-passe-12` ; mot de passe faux : `mauvais-mot-de-passe-1`. `Clock` fixe (unitaire) ou mutable injecté par test (intégration, bean de test `@Primary`). Les tests E2E créent leurs comptes par l'API avec un suffixe aléatoire (le registre en mémoire persiste entre exécutions : un pseudo ne sert qu'à un test).

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné le Compte `Alice` et un registre en mémoire, quand `Connecter` reçoit 5 fois `Alice` / mot de passe faux, alors 5 `IdentifiantsInvalidesException` ; quand il reçoit ensuite `Alice` / `un-mot-de-passe-12`, alors `ConnexionBloqueeException` avec un temps restant de 900 s, 0 appel à l'encodeur et 0 interrogation du dépôt de Comptes pour cette tentative (RG1, RG4, RG5) | unitaire |
| CA2 | Étant donné `Alice` bloquée à T, quand `Connecter` est appelé à T+899,5 s alors `ConnexionBloqueeException` avec restant 1 s ; à T+900 s exactement avec le bon mot de passe, alors le Compte est retourné ; puis 4 échecs ne bloquent pas, le 5e bloque (RG6, RG12) | unitaire |
| CA3 | Étant donné `Alice`, `Inconnu` (absent du dépôt) et `Anonyme1` (empreinte nulle), quand chacun subit 5 échecs puis une 6e tentative, alors la 6e lève la même exception avec le même temps restant dans les trois cas (RG1, RG3, RG9) | unitaire |
| CA4 | Étant donné `Alice`, quand 3 échecs avec `ALICE` puis 2 avec ` alice ` puis une tentative avec `Alice`, alors `ConnexionBloqueeException` (compteur commun) (RG1) | unitaire |
| CA5 | Étant donné `Alice`, quand 4 échecs, un succès, puis 4 échecs, alors aucun blocage (le 5e appel de la deuxième série, avec le bon mot de passe, réussit) ; et 4 échecs, un succès, 5 échecs : le 6e appel est bloqué (RG8) | unitaire |
| CA6 | Étant donné 4 échecs de `Alice` à T, quand un 5e échec à T+899 s, alors le blocage est déclenché ; étant donné 4 échecs à T et un échec à T+900 s, alors le compteur vaut 1 et la tentative suivante n'est pas bloquée (RG7) | unitaire |
| CA7 | Étant donné `Alice` bloquée à T, quand 10 tentatives à T+10 s, T+20 s, etc., alors toutes lèvent `ConnexionBloqueeException` sans changer `bloqueJusqu` ; à T+900 s la tentative est traitée normalement (RG5, RG6) | unitaire |
| CA8 | Étant donné `Alice`, quand 10 appels avec pseudo de 31 caractères, 10 avec `Alice` / mot de passe de 129 caractères, 10 avec pseudo vide et 10 avec mot de passe vide, puis `Alice` / `un-mot-de-passe-12`, alors le Compte est retourné (aucun de ces appels n'est compté) ; un pseudo de 30 caractères inexistant compte comme un échec (RG3) | unitaire |
| CA9 | Étant donné `Alice` bloquée, quand `Bob` se connecte avec le bon mot de passe, alors le Compte `Bob` est retourné (RG15) | unitaire |
| CA10 | Quand `PolitiqueBlocage` est créée avec `echecsMax` 0 ou -1, ou `blocageSecondes` 0 ou -5, alors une exception métier typée est levée, dont le message nomme le paramètre ; avec 1 et 1 la création réussit, et avec `echecsMax = 1` le premier échec bloque (RG2) | unitaire |
| CA11 | Quand une `ConnexionBloqueeException` est levée pour le pseudo `Alice` avec le mot de passe `secret-de-test-123`, alors son message et son `toString()` ne contiennent ni `Alice` ni `secret-de-test-123`, et le temps restant est exposé comme `Duration` (RG10, RG14) | unitaire |
| CA12 | Quand `./mvnw test`, alors les règles ArchUnit passent avec les classes ajoutées : `PolitiqueBlocage`, `TentativesConnexion`, `ConnexionBloqueeException` et le port dans `comptes.domaine` sans Spring/JPA/Jackson, adaptateur dans `comptes.infrastructure`, `courses` indépendant (RG16) | unitaire |
| CA13 | Étant donné `Alice` en base et un encodeur espionné, quand 5 appels `POST /api/connexion` avec le mot de passe faux, puis un 6e avec `un-mot-de-passe-12`, alors les 5 premiers répondent 401 `IDENTIFIANTS_INVALIDES`, le 6e répond 429 `application/problem+json` `{title:"Trop de tentatives", status:429, detail:"Trop de tentatives de connexion. Réessayez plus tard.", code:"TENTATIVES_EXCESSIVES", reessayerDansSecondes:900}` avec `Retry-After: 900`, sans `Set-Cookie`, sans `Alice` ni mot de passe dans le corps, et le 6e n'a provoqué aucune vérification Argon2id. Remplace 1.2 CA27 (RG1, RG4, RG5, RG10) | intégration |
| CA14 | Étant donné `Alice`, `Anonyme1` (empreinte nulle) et `Inconnu` inexistant, un `Clock` fixe, quand chacun subit 5 échecs puis un 6e appel, alors les trois 429 ont des corps strictement identiques et les mêmes en-têtes (hors date) (RG9) | intégration |
| CA15 | Étant donné `Alice` bloquée à T avec un `Clock` mutable, quand la requête est rejouée à T+899,5 s, alors 429 avec `reessayerDansSecondes` = 1 et `Retry-After: 1` ; à T+900 s avec le bon mot de passe, alors 200 `CompteReponse` ; et 5 nouveaux échecs sont de nouveau nécessaires pour bloquer (RG6, RG12) | intégration |
| CA16 | Étant donné `Alice`, quand 4 échecs, une connexion réussie (200), une déconnexion, 4 échecs puis une connexion réussie, alors les deux connexions réussissent (RG8) | intégration |
| CA17 | Étant donné un contexte sans la propriété, alors `echecsMax` = 5 et `blocageSecondes` = 900 ; avec `backyard.connexion.echecs-max=3` et `blocage-secondes=60`, 3 échecs bloquent et le 4e appel donne 429 avec `Retry-After` compris entre 1 et 60 ; avec `echecs-max=0` ou `blocage-secondes=0`, le démarrage du contexte échoue avec un message nommant le paramètre (RG2) | intégration |
| CA18 | Étant donné `Alice` bloquée, quand `POST /api/connexion` sans jeton CSRF, alors 403 `CSRF_INVALIDE` ; avec une session ouverte (autre compte) et jeton, alors 409 `DEJA_CONNECTE` ; avec `{}`, alors 400 `VALIDATION_ECHOUEE` ; avec un pseudo de 31 caractères ou `Alice` / mot de passe de 129 caractères, alors 401 `IDENTIFIANTS_INVALIDES` ; jamais 429 dans ces cas (RG11) | intégration |
| CA19 | Étant donné `Alice` non bloquée, quand 10 requêtes `{}`, 10 requêtes sans jeton CSRF, 10 avec un pseudo de 31 caractères, 10 avec mot de passe de 129 caractères et 10 en 409 (session ouverte), puis une connexion correcte, alors la connexion répond 200 (RG3) | intégration |
| CA20 | Étant donné un journal capturé, quand `Inconnu` subit 5 échecs puis 2 tentatives refusées, alors une ligne WARN « Connexion bloquée temporairement » et deux lignes INFO « Connexion refusée : blocage en cours » existent, et aucune ligne de niveau INFO ou supérieur ne contient `Inconnu`, `mauvais-mot-de-passe-1` ni `un-mot-de-passe-12` ; aucune ligne de niveau quelconque ne contient les mots de passe (RG14) | intégration |
| CA21 | Étant donné `echecsMax = 5` et `Alice`, quand 20 requêtes d'échec sont envoyées en parallèle, alors chaque réponse est 401 ou 429, au moins 5 réponses sont 401, et la requête suivante répond 429 (RG16) | intégration |
| CA22 | Étant donné l'adaptateur mémoire du registre (sans base de données), quand 10 001 pseudos distincts échouent, alors le registre contient au plus 10 000 clés ; les entrées expirées sont purgées avant toute éviction ; une clé bloquée non expirée n'est évincée qu'en dernier recours (RG13) | intégration |
| CA23 | Étant donné une session ouverte d'`Alice` et `Alice` bloquée depuis un autre client, quand `GET /api/comptes/moi` avec la session, alors 200 ; `POST /api/deconnexion` répond 204 ; `Bob` se connecte normalement (200) ; `POST /api/comptes` anonyme valide répond 201 (RG15, RG18) | intégration |
| CA24 | Étant donné un compte `coureur-<suffixe>` et l'écran `/connexion`, quand on saisit 5 fois le pseudo avec `mauvais-mot-de-passe-1` puis une 6e fois avec `un-mot-de-passe-12`, alors les 5 premiers essais affichent dans `erreur-generale` « Pseudo ou mot de passe incorrect. » et le 6e affiche exactement « Trop de tentatives de connexion. Réessayez dans 15 minutes. » ; le pseudo est conservé, `champ-mot-de-passe` est vide, `bouton-connexion` est actif, l'URL reste `/connexion` et `entete-pseudo` est absent (RG4, RG5, RG10, RG17) | E2E |
| CA25 | Étant donné un pseudo inexistant `fantome-<suffixe>`, quand 5 échecs puis une 6e tentative, alors `erreur-generale` affiche exactement le même texte que pour un pseudo existant bloqué (CA24) et les 5 premiers messages sont identiques à ceux d'un pseudo existant (RG9, RG17) | E2E |
| CA26 | Étant donné un compte `coureur-<suffixe>`, quand 4 échecs puis une connexion correcte, alors l'URL devient `/` et `entete-pseudo` affiche le pseudo ; après déconnexion, 4 nouveaux échecs puis une connexion correcte aboutissent aussi à `/` (RG8) | E2E |
| CA27 | Étant donné deux comptes `a-<suffixe>` et `b-<suffixe>` et `a-<suffixe>` bloqué par 5 échecs, quand `b-<suffixe>` se connecte avec le bon mot de passe, alors l'URL devient `/` et `entete-pseudo` affiche `b-<suffixe>` (RG15) | E2E |
| CA28 | Étant donné `/connexion` et la réponse de `POST /api/connexion` interceptée en 429 `TENTATIVES_EXCESSIVES`, quand `reessayerDansSecondes` vaut 90 puis 30 puis est absent, alors `erreur-generale` affiche « Trop de tentatives de connexion. Réessayez dans 2 minutes. », puis « ... dans 1 minute. », puis « Trop de tentatives de connexion. Réessayez plus tard. » ; le bouton reste actif et aucun code HTTP n'apparaît (RG17) | E2E |

Répartition : unitaire 12 (CA1 à CA12), intégration 11 (CA13 à CA23), E2E 5 (CA24 à CA28). Total 28.

Couverture des RG : RG1 CA1/CA3/CA4/CA13, RG2 CA10/CA17, RG3 CA3/CA8/CA19, RG4 CA1/CA13/CA24, RG5 CA1/CA7/CA13/CA24, RG6 CA2/CA7/CA15, RG7 CA6, RG8 CA5/CA16/CA26, RG9 CA3/CA14/CA25, RG10 CA11/CA13/CA24, RG11 CA18, RG12 CA2/CA15, RG13 CA22, RG14 CA11/CA20, RG15 CA9/CA23/CA27, RG16 CA12/CA21, RG17 CA24/CA25/CA28, RG18 CA23. Écran : Se connecter (CA24 à CA28), tous en E2E ; les autres écrans ne changent pas.

Impacts sur les tests de 1.2 (même PR) : CA27 de 1.2 remplacé par CA13 ; les tests d'intégration et E2E de 1.2 qui répètent des échecs sur un même pseudo (CA12, CA13, CA14, CA16, CA29) doivent utiliser un pseudo distinct par test ou vider le registre (méthode de test de l'adaptateur mémoire, hors contrat du domaine), afin de ne pas atteindre le seuil. Les E2E tournent avec les valeurs par défaut (5 échecs, 900 s) : ne pas les surcharger dans la stack E2E.

## 7. Tester à la main

Prérequis : Docker, `docker compose`, racine du dépôt, http://localhost. Pour ne pas attendre 15 minutes, créer ou compléter `.env` avec `CONNEXION_BLOCAGE_SECONDES=30` (puis le retirer ou le remettre à 900 après le test).

1. `docker compose up -d --build` puis `docker compose ps` : `base`, `api`, `web` en `healthy`. `docker compose exec api printenv CONNEXION_BLOCAGE_SECONDES` affiche `30` ; sans la ligne dans `.env`, la valeur par défaut est `900`.
2. Créer les comptes `Alice` et `Bob` (`un-mot-de-passe-12`) via « Créer un compte ».
3. Sur `/connexion`, saisir `Alice` / `mauvais-mot-de-passe-1` 5 fois (le pseudo reste saisi, le mot de passe est vidé) : chaque fois « Pseudo ou mot de passe incorrect. ».
4. 6e essai, avec cette fois le bon mot de passe `un-mot-de-passe-12` : « Trop de tentatives de connexion. Réessayez dans 1 minute. » (avec 900 s : « dans 15 minutes »). Toujours sur `/connexion`, en-tête « Se connecter ». Réessayer plusieurs fois : même message.
5. Pseudo inexistant : `Fantome` / n'importe quel mot de passe, 5 fois puis une 6e : mêmes 5 messages et exactement le même message de blocage qu'à l'étape 4 (pas de différence visible entre pseudo existant et inexistant). Essayer aussi `ALICE` en majuscules : toujours bloqué (même compteur).
6. Autre pseudo : `Bob` / `un-mot-de-passe-12` se connecte normalement (redirection vers `/`, en-tête `Bob`). Se déconnecter.
7. Attendre 30 secondes (ou 15 minutes par défaut) sans insister, puis `Alice` / `un-mot-de-passe-12` : connexion réussie, redirection vers `/`. Se déconnecter.
8. Remise à zéro : 4 échecs sur `Alice`, puis le bon mot de passe : connexion réussie ; se déconnecter, 4 nouveaux échecs, bon mot de passe : réussie aussi (le compteur est reparti de 0).
9. Prolongation : bloquer `Bob` (5 échecs), puis insister toutes les 5 secondes pendant 25 secondes ; à 30 secondes après le 5e échec, `Bob` / bon mot de passe réussit (les tentatives refusées ne prolongent pas).
10. API en ligne de commande (jeton CSRF puis connexion, voir 1.2 étape 15) : `for i in 1 2 3 4 5 6; do curl -s -i -b /tmp/jar -c /tmp/jar -X POST http://localhost/api/connexion -H "Content-Type: application/json" -H "X-XSRF-TOKEN: <valeur>" -d '{"pseudo":"Fantome2","motDePasse":"mauvais-mot-de-passe-1"}' | head -n 12; done` : cinq `401`, puis un `429` avec `Retry-After: 30` (ou 900), `Content-Type: application/problem+json`, corps `code":"TENTATIVES_EXCESSIVES"` et `reessayerDansSecondes`. Même séquence avec `Alice` : corps et en-têtes identiques à la valeur de `reessayerDansSecondes` près (qui dépend de l'instant).
11. Logs : `docker compose logs api | grep -c -e "Fantome" -e "mauvais-mot-de-passe-1" -e "un-mot-de-passe-12"` renvoie `0` ; `docker compose logs api | grep "Connexion bloquée temporairement"` montre une ligne par blocage, sans pseudo.
12. Redémarrage : bloquer `Alice`, `docker compose restart api`, attendre `healthy` : `Alice` peut de nouveau se connecter (compteurs en mémoire remis à zéro, RG13).
13. Paramètre invalide : `CONNEXION_ECHECS_MAX=0` dans `.env`, `docker compose up -d --build` : `api` ne devient pas `healthy` et `docker compose logs api` nomme `CONNEXION_ECHECS_MAX` (ou la propriété correspondante) et la borne. Retirer la ligne et relancer.

## 8. Points ouverts

Bloquants : aucun.

Non bloquants (position par défaut retenue, à confirmer) :
1. **Déni de service ciblé** : n'importe qui peut bloquer le pseudo d'un tiers (ou d'un bénévole en pleine course) en envoyant des échecs pendant 15 minutes. Inhérent au blocage par pseudo. Pas de déblocage admin en 1.3 ; l'attendre ou le prévoir en 1.6 (gestion des comptes). Alternative : délai croissant ou blocage couplé à l'IP, plus complexe. Défaut : blocage par pseudo seul.
2. **Pas de limite par IP ni globale** : un attaquant peut essayer 5 mots de passe par pseudo et par fenêtre sur autant de pseudos qu'il veut, et `POST /api/comptes` n'est pas limité. Derrière Caddy, l'IP source n'est connue que via `X-Forwarded-For`. À traiter plus tard si besoin (limitation Caddy ou filtre dédié).
3. **Compteur en mémoire** : un redémarrage de `api` remet tout à zéro (un attaquant qui provoque un redémarrage gagne de nouveaux essais) et deux instances ne partageraient pas l'état. Alternative : table en base avec nouveau changeset, hors budget de 1.3. Défaut : mémoire, déploiement mono-instance (VPS Docker).
4. **Valeurs par défaut** : 5 échecs, 15 minutes (900 s) de blocage et d'oubli des échecs partiels, une seule durée pour les deux. À valider ; la durée est en secondes pour faciliter le test à la main.
5. **Réponse au 5e échec** : 401 (le blocage n'est annoncé qu'au 6e appel), pour ne rien révéler du moment exact du déclenchement et garder un seul comportement par échec. Alternative : 429 dès le 5e échec, meilleure information de l'utilisateur. Défaut : 401.
6. **Information donnée** : `reessayerDansSecondes` et `Retry-After` indiquent le temps restant exact ; sans conséquence sur l'énumération (identique pour tout pseudo, RG9), mais le front n'affiche qu'une durée en minutes arrondie au supérieur.
7. **Test E2E de la fin du blocage** : impossible sans horloge pilotable (4.3) ; couverte en unitaire (CA2, CA6, CA7), en intégration (CA15) et à la main (étape 7). À ajouter en E2E à partir de 4.3 si souhaité.
8. **Entrées évincées** : en cas de saturation (10 000 pseudos distincts, signe d'une attaque), l'éviction peut libérer un pseudo bloqué. Accepté tant que le registre n'est qu'une protection de moindre niveau.
9. **Taille de l'incrément** : estimée à environ 200 lignes de production (domaine et exception ~70, cas d'usage ~25, adaptateur mémoire et configuration ~60, exposition ~10, front ~25, compose et `.env.example` ~10) : sous la cible, pas de découpage.
