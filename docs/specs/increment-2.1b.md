# Increment 2.1b : Données de démonstration

Seconde moitié de l'ancien incrément 2.1 (découpage validé par l'utilisateur). **Suppose l'incrément 2.1a mergé** : l'API `POST` et `GET /api/administration/courses`, la table `course` et l'écran `/administration/courses` existent. L'incrément livre `scripts/donnees-demo.sh`, qui crée par l'API de l'application lancée les Comptes et les Courses nécessaires au test manuel des incréments suivants. Aucun code applicatif (back ou front) n'est modifié.

Taille estimée (production hors tests) : script ~90 lignes de shell, plus quelques lignes de documentation (`docs/deploiement.md`, commentaires de `.env.example`). Sous la cible de 400 lignes. Aucun changeset, aucune variable d'environnement lue par `api`, aucune modification de `docker-compose*.yml`.

## 1. Périmètre

**Inclus**
- `scripts/donnees-demo.sh` (Bash et `curl` uniquement, idempotent) : un admin, deux bénévoles, un coureur, trois Courses de démonstration.
- Garde-fou contre une cible autre que locale (`BASE_URL`, `DEMO_FORCER`).
- Documentation : en-tête du script, section dans `docs/deploiement.md` (« à ne pas utiliser en production »), commentaires dans `.env.example` pour `BASE_URL` et `DEMO_FORCER` (variables lues par le script, pas par `docker compose` ni par `api`).

**Exclu**
- Tout changement de l'API, du front, du schéma (2.1a et antérieurs).
- Inscriptions, affectation des bénévoles aux Courses, Courses démarrées, Passages : le script sera enrichi par les incréments concernés (2.4, 3.x, 4.x), à partir du jalon 2 comme prévu par `CLAUDE.md`.
- Suppression ou remise à zéro des données de démonstration (une Course ne se supprime qu'à partir de 2.5, et seulement par l'admin master).
- Mise à jour d'une Course de démonstration déjà présente (RG3).
- Portabilité hors Linux et Bash (point ouvert 2).

## 2. Règles de gestion

- **RG1** : lancement et configuration. `scripts/donnees-demo.sh` est lancé depuis l'hôte à la racine du dépôt. Il vise l'application lancée à l'adresse `BASE_URL` (défaut `http://localhost`) et crée les données de la RG2 par l'API publiée par `web`, sans accès direct à la base. Il lit `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE` dans l'environnement, à défaut dans le fichier `.env` (lecture ligne à ligne, sans `source` ni `eval` : un `$` ou un guillemet du mot de passe ne doit pas être interprété). Variable introuvable : arrêt avec le code de sortie 1, message sur la sortie d'erreur nommant la variable manquante, sans aucune valeur secrète. Connexion de l'admin master refusée (mauvais mot de passe, 401) : code de sortie 1, message mentionnant le statut HTTP, aucune donnée créée.
- **RG2** : données créées, dans cet ordre, toutes par l'API (CSRF géré : `GET /api/csrf` puis en-tête `X-XSRF-TOKEN`, jeton relu après chaque connexion). Avec la session de l'admin master : admin `Nadia` / `mot-de-passe-admin-1` (`POST` de 1.5) ; bénévoles `Léo` et `Marc` / `mot-de-passe-benevole-1` (`POST` de 1.6a) ; Courses de démonstration (`POST /api/administration/courses` de 2.1a, dates relatives au jour de l'exécution) :

  | Nom | Date | Distance | Durée | Dénivelé | Participants max | Boucles max |
  |---|---|---|---|---|---|---|
  | Backyard de démo | jour + 30 | 6706 m | 60 min | 120 m | 50 | 24 |
  | Backyard express | jour + 7 | 400 m | 1 min | 5 m | 10 | 5 |
  | Backyard mini | jour + 14 | 1000 m | 2 min | 10 m | 2 | 2 |

  Puis, dans une session anonyme (`POST /api/comptes` refuse une session ouverte, 1.1) : coureur `Alice` / `un-mot-de-passe-12`. « Backyard express » (boucles de 1 min) et « Backyard mini » (2 places, 2 boucles) servent aux tests des jalons 3 et 4. Le jour de référence est la date de l'hôte au format `aaaa-mm-jj` ; les dates (jour + 7 au minimum) restent valides pour la règle « date du jour ou après » de 2.1a RG4 quel que soit le décalage entre le fuseau de l'hôte et `Europe/Paris`.
- **RG3** : idempotence. Relancé, le script ne crée rien en double et réussit (code de sortie 0). Un Compte dont la création répond 409 `PSEUDO_DEJA_UTILISE` est signalé « déjà présent » (limite documentée dans l'en-tête du script : un pseudo déjà pris par un Compte d'un autre rôle n'est pas détecté). Une Course est signalée « déjà présente » si une Course du même `nom` figure dans `GET /api/administration/courses` ; elle n'est ni comparée ni modifiée (une Course de même nom mais de paramètres différents reste inchangée). Chaque élément est affiché « créé » ou « déjà présent », un par ligne, nom de l'élément inclus. Aucun mot de passe n'est affiché ni écrit sur disque (le fichier de cookies est temporaire et supprimé en sortie, y compris en cas d'échec). Toute réponse inattendue (autre que 201, ou 409 `PSEUDO_DEJA_UTILISE` attendu pour un Compte) arrête le script avec le code de sortie 1 et le statut HTTP en message (sans corps de réponse brut ni secret).
- **RG4** : garde-fou et documentation. Les Comptes de démonstration ont des mots de passe connus : le script refuse (code de sortie 2, message explicite, **avant tout appel réseau**) de viser une `BASE_URL` dont l'hôte n'est ni `localhost` ni `127.0.0.1`, sauf si `DEMO_FORCER=oui`. `BASE_URL` et `DEMO_FORCER` sont documentés dans l'en-tête du script, dans `docs/deploiement.md` (mention « à ne pas utiliser en production ») et en commentaire dans `.env.example` (variables non lues par `docker compose`, ni par `api`, aucune valeur secrète ajoutée).

## 3. Cas limites

- **Premier lancement sur base neuve** : sept lignes « créé » (`Nadia`, `Léo`, `Marc`, `Alice`, `Backyard de démo`, `Backyard express`, `Backyard mini`), sortie 0 (RG2).
- **Relance** : sept lignes « déjà présent », sortie 0, une seule Course de chaque nom dans l'API (RG3).
- **Lancement partiel** : par exemple `Nadia` et `Backyard express` déjà créés à la main : ces deux éléments sont « déjà présent », les cinq autres « créé » (RG3).
- **Course de même nom, paramètres différents** (créée à la main par un admin) : « déjà présente », inchangée (RG3).
- **Pseudo pris par un Compte d'un autre rôle** (par exemple un coureur nommé `Nadia`) : vu comme « déjà présent », non détecté (limite documentée, RG3).
- **Variable absente** (`ADMIN_MASTER_PSEUDO` ou `ADMIN_MASTER_MOT_DE_PASSE` ni dans l'environnement ni dans `.env`, ou `.env` absent) : code 1, nom de la variable dans le message, aucune valeur (RG1).
- **Mot de passe contenant `$`, des guillemets ou des espaces** dans `.env` : lu tel quel, la connexion réussit (RG1).
- **Mauvais mot de passe d'admin master** : code 1, statut HTTP 401 mentionné, rien créé (RG1). **Compte bloqué par la limitation des tentatives** (1.3, plusieurs relances avec un mauvais mot de passe) : réponse inattendue, code 1 et statut HTTP (RG3).
- **Cible distante** (`BASE_URL=http://exemple.invalid`) sans `DEMO_FORCER` : code 2, aucun appel réseau ; avec `DEMO_FORCER=oui` : le script poursuit vers la cible (RG4). `DEMO_FORCER` à une autre valeur que `oui` (par exemple `1`, `true`) : n'autorise pas (RG4).
- **API arrêtée ou injoignable** : réponse inattendue (statut `000`), code 1 (RG3).
- **Interruption (Ctrl-C) ou échec en cours de route** : le fichier de cookies temporaire est supprimé ; une relance reprend là où il manque des éléments (RG3).
- **Rôle insuffisant, bénévole non affecté, instants de bascule de boucle, vainqueurs partagés, course complète, passages `CORRECTION` ou `SCAN`, inscription sans passage, coureur réintégré, Course démarrée, plusieurs Courses en parallèle** : non applicables à ce script (aucune Inscription, affectation, démarrage ni Passage n'est créé). Les trois Courses créées coexistent en `EN_PREPARATION` avec leurs paramètres propres.

## 4. Contrat d'API

Aucun nouvel endpoint ni DTO. Le script consomme uniquement des endpoints existants, dont le contrat est défini par les incréments précédents :

| Usage | Endpoint | Spec |
|---|---|---|
| Jeton CSRF | `GET /api/csrf` | 1.1 |
| Connexion de l'admin master | `POST /api/connexion` | 1.2 |
| Création de l'admin `Nadia` | `POST` de création d'un admin | 1.5 |
| Création des bénévoles `Léo`, `Marc` | `POST` de création d'un bénévole | 1.6a |
| Création du coureur `Alice` (session anonyme) | `POST /api/comptes` | 1.1 |
| Liste puis création des Courses | `GET` et `POST /api/administration/courses` | 2.1a (`DeclarerCourseRequete`, `CourseReponse`) |

Les codes attendus par le script : 201 (création), 409 `PSEUDO_DEJA_UTILISE` (Compte déjà existant), 200 pour la liste, tout autre statut arrête le script (RG3). Les chemins exacts de création d'un admin et d'un bénévole sont ceux des specs 1.5 et 1.6a, non redéfinis ici.

## 5. Écrans

Aucun écran créé ni modifié. Les données créées sont visibles sur des écrans existants : la liste des Courses de `/administration/courses` (2.1a) et la connexion (1.2). Les contrôles de ces écrans sont couverts par les CA de 2.1a (CA21 à CA25) ; aucun CA E2E de 2.1b n'est un test d'écran.

## 6. Critères d'acceptation

Valeurs de référence : admin master `Patron` / `mot-de-passe-patron-1` (variables `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE` de l'environnement de test). Tous les CA sont des tests Playwright qui pilotent le script comme processus enfant (`BASE_URL=http://localhost`, stack e2e) et qui contrôlent le résultat par l'API et par les écrans existants.

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné la stack e2e où `Nadia`, `Léo`, `Marc`, `Alice` et les trois noms de Courses de démonstration n'existent pas encore (ou les supprimer n'étant pas possible avant 2.5 : stack neuve pour ce test), quand `scripts/donnees-demo.sh` s'exécute avec les identifiants de l'admin master dans l'environnement, alors code de sortie 0, une ligne « créé » par élément (sept lignes), aucun mot de passe en sortie ni sur disque (aucun fichier de cookies résiduel) ; `Nadia`, `Léo`, `Marc` et `Alice` peuvent se connecter avec les mots de passe de RG2 (rôles `ADMIN`, `BENEVOLE`, `BENEVOLE`, `COUREUR`) ; `Patron` voit sur `/administration/courses` les Courses `Backyard de démo` (J+30, 6706 m, 60 min, 120 m, 50, 24), `Backyard express` (J+7, 400 m, 1 min, 5 m, 10, 5), `Backyard mini` (J+14, 1000 m, 2 min, 10 m, 2, 2), toutes « En préparation », dans l'ordre `Backyard de démo`, `Backyard mini`, `Backyard express` (RG1, RG2) | E2E |
| CA2 | Étant donné le CA1 exécuté, quand le script est relancé, alors code de sortie 0, sept lignes « déjà présent » et aucune « créé », `GET /api/administration/courses` ne contient toujours qu'une Course de chaque nom de démonstration ; étant donné `Nadia` et `Backyard express` déjà présents seuls (stack neuve où ils ont été créés par l'API avant le script), le script sort 0 avec ces deux lignes « déjà présent » et cinq « créé » ; une Course `Backyard mini` créée au préalable avec d'autres paramètres (par exemple 2000 m) n'est pas modifiée par le script (RG3) | E2E |
| CA3 | Quand le script s'exécute avec `BASE_URL=http://exemple.invalid` sans `DEMO_FORCER` (ou avec `DEMO_FORCER=true`), alors code de sortie 2, message explicite et aucun appel réseau (aucune connexion émise, vérifiable par la durée quasi nulle et l'absence de résolution de l'hôte invalide) ; sans `ADMIN_MASTER_PSEUDO` ni `.env` : code 1 et message nommant la variable manquante, sans valeur secrète ; avec un mauvais mot de passe d'admin master : code 1 mentionnant le statut HTTP 401, aucune donnée créée ; avec un mot de passe contenant un `$` fourni via un fichier `.env` temporaire : il est lu tel quel (RG1, RG3, RG4) | E2E |
| CA4 | Quand on lit `scripts/donnees-demo.sh`, `docs/deploiement.md` et `.env.example`, alors l'en-tête du script décrit `BASE_URL`, `DEMO_FORCER` et les limites de RG3 ; `docs/deploiement.md` contient la mention « à ne pas utiliser en production » ; `.env.example` contient `BASE_URL` et `DEMO_FORCER` en commentaire, sans valeur secrète ; `.env.example` ne contient toujours aucune valeur de mot de passe ; `docker compose config` reste valide avec le `.env.example` copié en `.env` (RG4) | E2E |

Répartition : unitaire 0, intégration 0, E2E 4 (CA1 à CA4). Total 4. Les CA sont des tests de bout en bout du script, non d'un écran ; ils sont portés par `testeur-e2e` (aucune classe Java à tester, donc pas de test unitaire ni d'intégration).

Couverture des RG : RG1 CA1/CA3, RG2 CA1, RG3 CA2/CA3, RG4 CA3/CA4. Écrans : aucun écran dans cet incrément.

Notes pour les testeurs : un Compte ne se supprime pas librement et une Course ne se supprime qu'à partir de 2.5 ; les tests qui supposent un état « rien n'existe » (CA1, cas partiel de CA2) s'exécutent sur une stack neuve (`docker compose down -v` puis `up`), à ordonner en début de suite ou isoler dans un projet Playwright dédié. Les tests des autres incréments ne doivent pas supposer que les données de démonstration existent. Le test de non-appel réseau de CA3 peut s'appuyer sur une adresse `.invalid` dont la résolution échouerait de toute façon, en vérifiant le code de sortie 2 (et non un code d'échec réseau) et le message de refus.

## 7. Tester à la main

Prérequis : incrément 2.1a mergé, stack lancée avec `docker compose up -d --build` (`base`, `api`, `web` `healthy`), `.env` avec `ADMIN_MASTER_PSEUDO=Patron` et `ADMIN_MASTER_MOT_DE_PASSE=mot-de-passe-patron-1`, http://localhost. `bash` et `curl` sont requis sur la machine hôte (rien d'autre à installer). Pour repartir d'une base neuve : `docker compose down -v` puis `docker compose up -d --build`.

1. Premier lancement : à la racine du dépôt, `./scripts/donnees-demo.sh; echo $?`. Attendu : sept lignes « créé » (`Nadia`, `Léo`, `Marc`, `Alice`, `Backyard de démo`, `Backyard express`, `Backyard mini`), sortie `0`, aucun mot de passe affiché.
2. Relance : même commande. Attendu : sept lignes « déjà présent », sortie `0`.
3. Comptes : se connecter à http://localhost avec `Nadia` / `mot-de-passe-admin-1` (accès à `/administration`), `Léo` / `mot-de-passe-benevole-1` et `Marc` / `mot-de-passe-benevole-1` (bénévoles), `Alice` / `un-mot-de-passe-12` (coureur).
4. Courses : connexion `Patron` / `mot-de-passe-patron-1`, « Gérer les courses » : trois lignes dans l'ordre `Backyard de démo` (dans 30 jours, 6706 m, 60 min, 120 m, 50, 24), `Backyard mini` (14 jours, 1000 m, 2 min, 10 m, 2, 2), `Backyard express` (7 jours, 400 m, 1 min, 5 m, 10, 5), toutes « En préparation ».
5. Course existante non modifiée : sur une base où `Backyard mini` a été déclarée à la main avec 2000 m avant le script, relancer le script : « Backyard mini » est « déjà présent » et la liste affiche toujours 2000 m.
6. Garde-fou : `BASE_URL=http://exemple.test ./scripts/donnees-demo.sh; echo $?` : message de refus, sortie `2`, aucune requête envoyée. `DEMO_FORCER=true` n'autorise pas non plus.
7. Variable manquante : `ADMIN_MASTER_PSEUDO= ADMIN_MASTER_MOT_DE_PASSE= ./scripts/donnees-demo.sh` depuis un répertoire de copie sans `.env` (ou `.env` renommé temporairement) : sortie `1`, message nommant la variable manquante, sans valeur. Mauvais mot de passe : `ADMIN_MASTER_PSEUDO=Patron ADMIN_MASTER_MOT_DE_PASSE=faux-mot-de-passe-1 ./scripts/donnees-demo.sh` : sortie `1`, message avec le statut HTTP 401.
8. Documentation : ouvrir l'en-tête de `scripts/donnees-demo.sh`, `docs/deploiement.md` (mention « à ne pas utiliser en production ») et `.env.example` (`BASE_URL`, `DEMO_FORCER` en commentaire).

## 8. Points ouverts

Bloquants : aucun. Positions par défaut retenues, à confirmer.

1. **Limites du script** : un pseudo déjà pris par un Compte d'un autre rôle est vu comme « déjà présent » ; une Course de même nom mais de paramètres différents n'est pas corrigée ; Bash et `curl` requis sur l'hôte. Garde-fou `BASE_URL` local par défaut, contournable par `DEMO_FORCER=oui` (à ne jamais utiliser en production).
2. **Portabilité du calcul de date** : « jour + N » s'obtient avec `date -d` (GNU, Linux, Git Bash / WSL) ; macOS (BSD `date`) n'est pas garanti. À confirmer que la machine de test est sous Linux, sinon prévoir une variante (`date -v`).
3. **Mots de passe de démonstration connus** : acceptable car le script refuse les cibles non locales sans `DEMO_FORCER=oui` ; en production, les Comptes `Nadia`, `Léo`, `Marc`, `Alice` ne doivent jamais exister (à rappeler dans `docs/deploiement.md`).
4. **Noms** : Comptes `Nadia`, `Léo`, `Marc`, `Alice` et Courses « Backyard de démo / express / mini » (valeurs de la spec 2.1 d'origine). À confirmer.
