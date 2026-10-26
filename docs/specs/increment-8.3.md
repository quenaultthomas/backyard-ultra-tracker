# Increment 8.3 : Exposition minimale de /api/version (PEN-03, SEC-17)

`GET /api/version` reste public (vérification de la version déployée après un déploiement) mais sa réponse est **réduite à la seule version applicative** : `{"app":{"version":"<VERSION>"}}`. Le bloc `build` (`group`, `artifact`, `name`, `time`, `version` du pom) disparaît de `/actuator/info`. Changement de configuration uniquement : aucun code applicatif, aucun écran, aucun nouvel endpoint.

Origine : `docs/audits/audit-2026-10-26-pentest.md`, constat PEN-03 (SUGGESTION : divulgation de version et de métadonnées de build, qui facilite le ciblage de CVE). Décision de l'utilisateur, non rouverte : garder l'alias public, réduire la réponse. Nouveau point de référentiel **SEC-17** (SEC-14 et SEC-15 sont réservés aux incréments 8.4 et 8.5, SEC-16 est pris par 8.2).

## Ce qui est déjà livré, donc non respécifié

Vérifié dans le code :
- `frontend/Caddyfile` : `handle /api/version` (en-tête `Cache-Control: no-store` appliqué `defer`, `rewrite * /actuator/info`, `reverse_proxy api:8080`), seul avec `handle /api/sante` (réécrit vers `/actuator/health`) à atteindre l'actuator. Toute autre route `/api/*` est relayée **sans réécriture** (`/api/actuator/info` répond 401 en anonyme, Spring Security bloquant avant la résolution de route) ; tout chemin hors `/api/*`, y compris `/actuator/info`, est traité par le repli SPA (`index.html`, 200 `text/html`) et n'atteint jamais l'api.
- `application.yml` : `management.endpoints.web.exposure.include: health,info`, `management.info.env.enabled: true`, `info.app.version: ${APP_VERSION:dev}`. `APP_VERSION` vient de `ENV APP_VERSION=${VERSION}` du `backend/Dockerfile` (`ARG VERSION=dev`), lui-même alimenté par l'argument de build `VERSION` (CI de release : version du tag, `-rc.N` compris ; `.env` : `VERSION` en production).
- `backend/pom.xml` : exécution `informations-de-build` (goal `build-info`) du `spring-boot-maven-plugin`, qui produit `META-INF/build-info.properties` ; **rien d'autre ne le lit** (aucun `BuildProperties` dans `backend/src`), seul `/actuator/info` l'expose.
- Le pied de page du front affiche la version via la constante de compilation `frontend/src/app/version.ts`, **pas** via `/api/version`. Aucun test E2E, aucun script (`scripts/`) ni workflow (`.github/`) ne lit `/api/version` ou `/actuator/info`.
- Tests qui lisent `/actuator/info` : `SqueletteIntegrationTest.info_expose_la_version` (attend `build.version = 0.5.0` : à mettre à jour, RG5). `LimiteDebitPublicTest` cite `/api/version` comme chemin non compté (inchangé). `ReleaseDepotIntegrationTest.ca16_sections_de_documentation` exige que la section « Restauration » de `docs/deploiement.md` contienne `/api/version` (à conserver, RG7).
- Réponse actuelle : `{"app":{"version":"dev"},"build":{"artifact":"backyard-ultra-tracker","name":"Backyard Ultra Tracker","time":"…","version":"0.5.0","group":"fr.backyard"}}`.

Taille estimée (production hors tests) : ~10 lignes (`application.yml`, `pom.xml`), documentation et référentiel en plus.

## 1. Périmètre

**Inclus**
- Réponse de `/actuator/info` et `GET /api/version` réduite à `app.version` (RG1, RG2).
- Désactivation du bloc `build` et retrait de l'exécution `build-info` devenue inutile (RG3).
- Vérification que l'actuator n'est joignable de l'extérieur que par les deux alias (RG4).
- Mise à jour du test existant, nouveaux tests d'intégration (RG5), documentation (RG7), CHANGELOG (RG8), référentiel SEC-17 (RG9).

**Exclu**
- Restriction de `/api/version` aux utilisateurs authentifiés ou au réseau interne : décision de l'utilisateur de garder l'alias public.
- Changement de `Caddyfile` : aucun (RG4). Changement de `docker-compose*.yml`, `.env.example`, `backend/Dockerfile` : aucun.
- Affichage de la version dans le front : inchangé (constante de compilation).
- Durée de session des rôles privilégiés : **8.2**. Plafonds par IP : **8.4**, **8.5**. Désactivation des comptes : **8.1** (livré).
- Limitation de débit de `/api/version` : hors besoin (réponse constante, non comptée par `LimiteDebitPublic`, inchangé).
- Autres informations de `/actuator/info` (`git`, `java`, `os`, `process`, `ssl`) : restent désactivées, rien n'est ajouté.
- Nouvel écran : aucun (voir « Écrans »).

**Fichiers modifiés (indicatif)** : `backend/src/main/resources/application.yml`, `backend/pom.xml`, `SqueletteIntegrationTest`, nouveau `VersionPubliqueIntegrationTest`, `docs/deploiement.md`, `CHANGELOG.md`, `docs/audits/referentiel.md`.

## 2. Règles de gestion

- **RG1** : forme de la réponse. `GET /actuator/info` et `GET /api/version` (200, JSON) renvoient **exactement** `{"app":{"version":"<valeur>"}}` : un seul champ racine `app`, contenant un seul champ `version` (texte non vide). `<valeur>` = la propriété `info.app.version`, soit `APP_VERSION` (argument de build `VERSION`) et `dev` par défaut. Aucun autre champ, nulle part, y compris `build`, `group`, `artifact`, `name`, `time`, `git`, `java`, `os`. Le `Content-Type` est celui, standard et inchangé, de l'actuator : `application/vnd.spring-boot.actuator.v3+json` sans en-tête `Accept` explicite ; `application/json` si le client envoie `Accept: application/json`.
- **RG2** : choix de la version conservée. `app.version` est la version **déployée** (celle de l'image, du tag de release et de `VERSION` dans `.env`) : c'est l'information utile à la vérification post-déploiement (`deploiement.md` : « doit valoir celle de `.env` »). `build.version` (version du pom) disparaît : elle n'apporte rien de plus (la CI de release exige déjà que le tag corresponde au pom, au front et à l'e2e), et elle ne distingue pas un `-rc.N`. Conséquence : une api construite **sans** argument `VERSION` répond `dev` (et non plus `0.5.0`), ce qui est le comportement voulu hors release (la spec 7.4 CA2, qui attendait la version du pom, est remplacée sur ce point par RG5).
- **RG3** : désactivation propre. (a) `application.yml` : `management.info.build.enabled: false` (garde explicite : même si `build-info.properties` revient, `/actuator/info` n'expose pas `build`) ; `management.info.env.enabled: true` et `info.app.version` inchangés ; commentaire de la section mis à jour (version déployée uniquement). (b) `pom.xml` : l'exécution `informations-de-build` (goal `build-info`) est **supprimée** du `spring-boot-maven-plugin` : plus rien ne lit `build-info.properties`, et son horodatage (`time`) n'a pas de raison d'exister dans l'artefact. Le plugin lui-même reste (repackage). Les endpoints exposés restent `health,info`.
- **RG4** : l'actuator n'est joignable que par les deux alias. Depuis le port public (Caddy) : `/api/version` -> `/actuator/info` et `/api/sante` -> `/actuator/health` sont les **seuls** chemins qui atteignent l'actuator. `/actuator/info`, `/actuator/health` (sans `/api`) répondent le repli SPA (`index.html`, 200 `text/html`, jamais du JSON de l'actuator) ; `/api/actuator/info` et `/api/actuator/env` répondent 401 en anonyme (Spring Security bloque avant la résolution de route ; comportement préexistant, jamais du JSON de l'actuator). Directement sur l'api (tests d'intégration), `/actuator/env`, `/actuator/beans` etc. restent absents (404, 0.1 CA4). `frontend/Caddyfile` est donc **inchangé**. Seul l'alias `/api/version` filtre : sa réponse est réduite à la source (RG1), pas par le proxy.
- **RG5** : tests. (a) `SqueletteIntegrationTest.info_expose_la_version` est mis à jour : plus d'assertion sur `build.version = 0.5.0` ; il vérifie `app.version` non vide et l'absence de `build` (seule modification d'attente autorisée, imposée par RG1/RG2). (b) Nouveau `VersionPubliqueIntegrationTest` (CA2 à CA5). (c) Un test de lecture de fichiers (comme `ReleaseDepotIntegrationTest`) vérifie `pom.xml`, `Caddyfile`, `referentiel.md`, `CHANGELOG.md` (CA6 à CA8). Aucun test n'est supprimé ni affaibli.
- **RG6** : sécurité. `GET /actuator/info` et `/api/version` restent publics (sans session, sans CSRF) ; les autres méthodes (`POST`, `PUT`, `DELETE`) répondent comme aujourd'hui (jamais 2xx). Aucun secret, pseudo, hôte ni chemin dans la réponse. `Cache-Control: no-store` et en-têtes de sécurité du Caddyfile inchangés.
- **RG7** : documentation. `docs/deploiement.md` : les commandes `curl https://<domaine>/api/version` (étapes de mise à jour, restauration, répétition générale) **restent valides** ; on précise, à l'endroit où `app.version` est mentionné (vérification du site après déploiement) : la réponse est `{"app":{"version":"<VERSION de .env>"}}` et ne contient que cette version. Les chaînes exigées par `ReleaseDepotIntegrationTest` (dont `/api/version` dans « Restauration ») sont conservées. Aucun autre fichier de déploiement n'est touché.
- **RG8** : `CHANGELOG.md`, section « Non publié », sous « Sécurité » : ligne indiquant que `/api/version` ne renvoie plus que la version applicative déployée (plus de `group`, `artifact`, `name`, `time` ni version du pom) (SEC-17, constat PEN-03 du pentest du 2026-10-26). La ligne de la version 0.5.0 (« `/actuator/info` et `/api/version` indiquent la version déployée ») n'est pas modifiée (historique).
- **RG9** : référentiel. Ajouter à la section SEC de `docs/audits/referentiel.md` la ligne `| SEC-17 | Exposition publique minimale de la version : /api/version et /actuator/info ne renvoient que la version applicative déployée (aucun groupe, artefact, nom ni date de build), et l'actuator n'est joignable de l'extérieur que par /api/sante et /api/version | P2 | 8.3 |`. Identifiant jamais réutilisé ; SEC-14 à SEC-16 laissés aux incréments 8.4, 8.5 et 8.2 (si SEC-14 à SEC-16 ne sont pas encore présents, la ligne SEC-17 est ajoutée sans les créer).

## 3. Cas limites

- **Sans `VERSION`** (build local, `docker compose up` sans `.env` renseigné) : `{"app":{"version":"dev"}}` (RG1, RG2).
- **Version de release** `0.5.1` ou pré-version `0.5.1-rc.1` : la valeur est celle de `VERSION`, `-rc.N` compris, ce que ne faisait pas `build.version` (RG2).
- **`APP_VERSION` vide** (variable définie à vide) : non spécifié par 7.4 ; le champ reste présent. Position : hors besoin (le Dockerfile impose `dev` par défaut, la CI fournit toujours la version).
- **Plugin `build-info` réintroduit plus tard** : `management.info.build.enabled=false` empêche l'exposition du bloc `build` (RG3a).
- **`/actuator/info` appelé hors alias** (via l'api directement, réseau interne Docker, ou `localhost:8080` dans les tests d'intégration) : même réponse réduite ; le réseau interne n'est pas public (seul `web` publie un port).
- **Chemins voisins** : `/api/version/` , `/api/version?x=1`, `/api/versions`, `/api/actuator/info`, `/actuator/info` côté Caddy : le comportement actuel est inchangé (voir RG4), non modifié par l'incrément.
- **Méthode non GET** sur `/api/version` : comportement actuel conservé (RG6).
- **Rôle** : sans objet, endpoint public ; un `BENEVOLE`, un `COUREUR` ou un anonyme reçoivent la même réponse.
- **Cas du métier sans objet ici** (boucle courante, bascule de Boucle, dernière boucle, vainqueurs partagés, Course complète, Réintégration, `CORRECTION` vs `SCAN`, Inscription sans Passage, Course non démarrée, plusieurs Courses en parallèle, bénévole non affecté) : l'incrément ne touche ni Course, ni Inscription, ni Passage, ni Compte.

## 4. Contrat d'API

Un seul endpoint concerné, public, sans authentification, sans CSRF, sans corps de requête. Les erreurs éventuelles (500 `ERREUR_INTERNE`) gardent le format `ProblemDetail` existant ; aucun nouveau code d'erreur.

### GET /api/version (modifié) = GET /actuator/info (modifié, côté api)
- Rôle : aucun (public). `/api/version` est un alias Caddy de `/actuator/info` ; le front ne l'appelle pas.
- **200**, `Content-Type` : `application/vnd.spring-boot.actuator.v3+json` sans en-tête `Accept` explicite (standard de l'actuator, inchangé), `application/json` avec `Accept: application/json` ; corps `{ "app": { "version": texte } }` (RG1).

Avant :
```json
{"app":{"version":"dev"},"build":{"artifact":"backyard-ultra-tracker","name":"Backyard Ultra Tracker","time":"2026-10-26T08:00:00.000Z","version":"0.5.0","group":"fr.backyard"}}
```
Après :
```json
{"app":{"version":"dev"}}
```
(en production : `"version":"0.5.1"` ou `"0.5.1-rc.1"`, valeur de `VERSION`).

- 400, 401, 403, 404, 409 : non applicables à la ressource. Rupture de contrat : les clients qui lisaient `build.*` (aucun dans le dépôt) doivent lire `app.version`. Aucun changement de DTO pour le front.

## 5. Écrans

Aucun écran nouveau ni modifié. Le pied de page du front affiche une constante de compilation, pas la réponse de `/api/version` (vérifié) ; aucun écran n'affiche ni ne consomme `build.*`. Il n'y a donc **pas de CA de niveau E2E** : la règle « chaque écran de l'incrément est couvert en E2E » est vide, et aucun test E2E existant ne lit `/api/version`. Non-régression : la suite E2E existante reste verte sans modification (CA9).

## 6. Critères d'acceptation

Valeurs de référence : api démarrée avec `info.app.version` = `1.2.3-rc.1` pour CA2 ; sans valeur (défaut `dev`) pour CA1.

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné l'api de test démarrée sans `APP_VERSION` (Testcontainers, comme `SqueletteIntegrationTest`), quand un anonyme appelle `GET /actuator/info`, alors 200 (JSON), le champ `app.version` vaut `dev`, aucun champ `build` n'est présent ; `SqueletteIntegrationTest.info_expose_la_version` est mis à jour en ce sens et ne contient plus d'attente sur `0.5.0` (RG1, RG2, RG5) | intégration |
| CA2 | Étant donné l'api démarrée avec `info.app.version=1.2.3-rc.1`, quand un anonyme appelle `GET /actuator/info`, alors 200 et le corps JSON est **égal** à `{"app":{"version":"1.2.3-rc.1"}}` (un seul champ racine `app`, un seul champ `version`) (RG1) | intégration |
| CA3 | Étant donné le même contexte, quand on lit le corps brut de `GET /actuator/info`, alors il ne contient aucune des chaînes `group`, `artifact`, `name`, `time`, `build`, `fr.backyard`, `backyard-ultra-tracker`, `Backyard Ultra Tracker`, `0.5.0`, ni `password` / `motDePasse` (RG1, RG3, RG6) | intégration |
| CA4 | Étant donné le contexte de CA2 avec la propriété `management.info.build.enabled` laissée à sa valeur de l'application, quand on fournit un bean `BuildProperties` de test (simulant le retour du plugin), alors `GET /actuator/info` n'expose toujours pas `build` (garde de RG3a) | intégration |
| CA5 | Étant donné le même contexte (appels directs à l'api), quand un anonyme appelle `GET /actuator/health`, `/actuator/env`, `/actuator/beans`, alors respectivement 200 `{"status":"UP"}`, 404, 404 ; quand il appelle `POST /actuator/info` sans session, alors la réponse n'est jamais 2xx ; quand il appelle `GET /api/actuator/info`, alors 401 (jamais du JSON de l'actuator) ; aucun cookie de session n'est créé par `GET /actuator/info` (RG4, RG6) | intégration |
| CA6 | Étant donné les fichiers du dépôt, quand on lit `backend/pom.xml`, alors il ne contient plus l'exécution `informations-de-build` ni le goal `build-info` et conserve le `spring-boot-maven-plugin` ; `application.yml` contient `management.info.build.enabled: false`, `management.info.env.enabled: true` et `info.app.version: ${APP_VERSION:dev}` (RG3) | intégration |
| CA7 | Étant donné `frontend/Caddyfile`, quand on le lit, alors le bloc `handle /api/version` réécrit toujours vers `/actuator/info` avec `Cache-Control no-store`, `handle /api/sante` réécrit vers `/actuator/health`, et **aucun autre** `rewrite` ne cible `/actuator/` ; le fichier est inchangé par rapport à `main` (RG4) | intégration |
| CA8 | Étant donné `docs/audits/referentiel.md`, `CHANGELOG.md` et `docs/deploiement.md`, quand on les lit, alors le référentiel contient la ligne `SEC-17` (priorité P2, incrément 8.3) sans doublon d'identifiant ; la section « Non publié » du CHANGELOG mentionne `/api/version`, `SEC-17` et PEN-03 ; `deploiement.md` contient `{"app":{"version"` dans la vérification du site après déploiement et conserve `/api/version` dans les sections « Restauration » et « Répétition générale d'une version » ; `ReleaseDepotIntegrationTest` reste vert sans modification (RG7, RG8, RG9) | intégration |
| CA9 | Étant donné la stack `docker compose up -d --build` (profil `e2e`) et la suite E2E existante, quand on la rejoue sans modification, alors tous les scénarios restent verts (aucun ne lit `/api/version`) ; `curl http://localhost/api/version` répond 200 `{"app":{"version":"dev"}}` ; `curl -s http://localhost/actuator/info` répond du HTML (`<app-root`), pas du JSON de l'actuator (RG1, RG4) | E2E |

Répartition : unitaire 0 (aucune logique : de la configuration, couverte par l'intégration), intégration 8 (CA1 à CA8), E2E 1 (CA9, non-régression de la stack, sans écran). Total 9.

Couverture des RG : RG1 CA1/CA2/CA3/CA9, RG2 CA1/CA2, RG3 CA3/CA4/CA6, RG4 CA5/CA7/CA9, RG5 CA1/CA2 à CA8, RG6 CA3/CA5, RG7 CA8, RG8 CA8, RG9 CA8. Écrans : aucun (voir §5).

Notes pour les testeurs : CA2 et CA4 utilisent un `@SpringBootTest(properties = …)` dédié ; CA4 ajoute un bean `BuildProperties` (propriétés `group`, `artifact`, `name`, `version`, `time`) via `@TestConfiguration`. Pour CA6 à CA8, lecture de fichiers relative à la racine du dépôt, comme `ReleaseDepotIntegrationTest`. Les assertions sur le corps parsent le JSON sans dépendre du `Content-Type`. Tests en JUnit 5 + AssertJ, noms en français.

## 7. Tester à la main

Prérequis : `docker compose up -d --build` (`base`, `api`, `web` sains), sans `VERSION` dans `.env` (version `dev`) ; aucune donnée de démo nécessaire.

1. Réponse réduite : `curl -s http://localhost/api/version` -> `{"app":{"version":"dev"}}`. Aucun `build`, `group`, `artifact`, `name`, `time`.
2. En-têtes : `curl -sI http://localhost/api/version` -> `200`, `Content-Type: application/vnd.spring-boot.actuator.v3+json…` (type par défaut de l'actuator ; `application/json` si on ajoute `-H 'Accept: application/json'`), `Cache-Control: no-store`.
3. Chemins hors alias : `curl -s http://localhost/actuator/info | head -c 200` -> du HTML (page de l'application), pas du JSON ; `curl -s -o /dev/null -w "%{http_code}\n" http://localhost/api/actuator/info` -> `401` ; `curl -s -o /dev/null -w "%{http_code}\n" http://localhost/api/actuator/env` -> `401` (l'actuator reste inaccessible par ces chemins).
4. Santé inchangée : `curl -s http://localhost/api/sante` -> `{"status":"UP"}`.
5. Version déployée : arrêter, mettre `VERSION=0.5.1-test` dans `.env`, puis construire l'api avec cet argument (`docker compose build --build-arg VERSION=0.5.1-test api && docker compose up -d`, ou, en production, `$PROD pull && $PROD up -d`) : `curl -s http://localhost/api/version` -> `{"app":{"version":"0.5.1-test"}}`. Remettre ensuite `.env` à l'état initial.
6. Artefact : dans le dépôt, `cd backend && mvn -q clean package -DskipTests` (ou le build du Dockerfile), puis `unzip -l target/*.jar | grep -c build-info` (exécuté sur la machine hôte, `unzip` n'étant pas dans l'image d'exécution) affiche `0` : `BOOT-INF/classes/META-INF/build-info.properties` est absent. Le jar de l'image d'exécution (`/application/application.jar`) n'est pas inspecté.
7. Application : ouvrir http://localhost, se connecter (`Patron`), parcourir les écrans : rien ne change ; le pied de page affiche toujours la version de l'application.
8. Documentation : `docs/deploiement.md` (vérification du site après déploiement) mentionne la réponse `{"app":{"version":"…"}}` ; `CHANGELOG.md` « Non publié » contient la ligne de sécurité ; `docs/audits/referentiel.md` contient SEC-17.

## 8. Points ouverts

Bloquants : aucun. Positions par défaut appliquées dans la spec.

1. **Suppression de `build.version`.** Retenu : disparition complète du bloc `build` (RG2), parce que l'information utile post-déploiement est `app.version`. Alternative : conserver `build.version` seul (la version du pom), au prix d'un second numéro qui peut diverger de la version déployée (`-rc.N`). À confirmer.
2. **Retrait de l'exécution `build-info` du pom (RG3b).** Retenu car plus rien ne lit `build-info.properties`. Alternative : la garder (inoffensive, désactivée côté exposition). À confirmer.
3. **Valeur par défaut `dev`.** Une api construite sans `VERSION` répond `dev` et non plus `0.5.0` (pom) ; la spec 7.4 (CA2) est donc modifiée sur ce point (RG5). À confirmer.
4. **Priorité de SEC-17.** Retenu P2 (constat de niveau SUGGESTION). À confirmer.
5. **Alias public conservé.** Décision de l'utilisateur ; le risque résiduel est l'indication de la version déployée (`app.version`) à un tiers, assumé pour la vérification post-déploiement.
6. **Numérotation SEC-14 à SEC-16.** SEC-17 est attribué d'avance ; selon l'ordre de fusion de 8.2, 8.4 et 8.5, le référentiel peut contenir des lignes manquantes temporairement (RG9). Aucune dépendance bloquante avec 8.2.
