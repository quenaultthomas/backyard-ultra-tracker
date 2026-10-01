# Increment 0.1 : Squelette back et base

Socle technique sans fonctionnalité métier : une API Spring Boot vide, connectée à PostgreSQL, qui répond `UP`. Aucun écran, aucun domaine.

## 1. Périmètre

**Inclus**
- Projet Spring Boot 4.1.1 / Java 25 dans `backend/`, avec Maven wrapper (`mvnw`).
- `backend/Dockerfile` multi-étapes (build JDK 25, exécution JRE 25), utilisateur non root, versions d'images fixées (jamais `latest`), `backend/.dockerignore` (`target/`, secrets).
- `docker-compose.yml` avec deux services :
  - `base` : `postgres` en version fixée, volume nommé `donnees-postgres`, healthcheck `pg_isready`.
  - `api` : construit depuis `backend/`, healthcheck sur `/actuator/health`, `depends_on: base: condition: service_healthy`, port 8080 publié en local (fermé en production en 0.4).
- Changelog Liquibase maître et initial vide, appliqué au démarrage.
- `spring.jpa.hibernate.ddl-auto=validate`.
- `/actuator/health` répond `UP` (inclut l'état de la base).
- Configuration par variables d'environnement (connexion base).
- Dépendances de test : JUnit 5, AssertJ, Spring Boot Test, Testcontainers PostgreSQL (pour les tests d'intégration de cet incrément).

**Exclu**
- Front Angular et service `web` : 0.2.
- JaCoCo, ArchUnit, Playwright, projet `e2e/`, test de démonstration et outillage qualité : 0.3.
- `docker-compose.prod.yml`, `.env.example`, `docs/deploiement.md`, fermeture du port 8080 en production : 0.4.
- Spring Security, comptes, rôles, CSRF : 1.x.
- Tout domaine, cas d'usage, endpoint métier, entité JPA.

**Unitaire** : aucun test ni CA de niveau `unitaire` (pas de logique à tester).

## 2. Règles de gestion

- **RG1** : le backend est un projet Maven Spring Boot 4.1.1 compilé avec Java 25, build possible via `./mvnw` sans Maven installé.
- **RG2** : la configuration de connexion à la base (URL, utilisateur, mot de passe) provient exclusivement de variables d'environnement ; aucun secret n'est commité en dur dans les sources ni dans l'image. Le compose fournit des valeurs de développement par défaut non secrètes (`${VAR:-valeur}`), fichier `.env` facultatif en 0.1.
- **RG3** : le schéma est géré uniquement par Liquibase (`db/changelog/db.changelog-master.yaml`, plus un changelog initial sans changeset de table), appliqué automatiquement au démarrage de `api`. `spring.jpa.hibernate.ddl-auto=validate`.
- **RG4** : `GET /actuator/health` répond 200 avec `{"status":"UP"}` quand la base est joignable, et 503 `DOWN` sinon. Aucun détail (`show-details=never`).
- **RG5** : seul l'endpoint actuator `health` est exposé (`management.endpoints.web.exposure.include=health`) ; `info`, `env`, `beans`, etc. répondent 404.
- **RG6** : `api` ne démarre que lorsque `base` est saine (`depends_on` avec `service_healthy`). Les deux services ont un healthcheck.
- **RG7** : les images de base sont fixées à une version précise (postgres et JDK/JRE 25) ; l'image d'exécution `api` tourne en utilisateur non root et n'embarque ni `target/` hors jar, ni secrets (`.dockerignore`).
- **RG8** : les données PostgreSQL sont dans le volume nommé `donnees-postgres` ; elles survivent à `docker compose down` (sans `-v`).
- **RG9** : aucun Spring Security en 0.1 : `/actuator/health` est accessible sans authentification.

## 3. Cas limites

- Base indisponible au démarrage de `api` (hors compose) : l'application échoue au démarrage avec une erreur explicite, pas de démarrage dégradé.
- Base arrêtée après démarrage : `/actuator/health` répond 503 `DOWN`, le healthcheck docker passe `unhealthy`.
- Redémarrage de `api` : Liquibase est idempotent, aucun changeset rejoué (table `databasechangelog` présente).
- `docker compose down` puis `up` : volume conservé ; `docker compose down -v` repart d'une base vierge.
- Première exécution : `api` attend que `base` soit `healthy` (le délai de démarrage de postgres ne doit pas faire échouer `api`).
- Endpoint inexistant (`/api/x`) : 404 (comportement Spring par défaut, pas de format imposé en 0.1).

## 4. Contrat d'API

Un seul endpoint, public (aucun rôle, pas de Spring Security).

### GET /actuator/health
- Requête : aucun corps, aucun paramètre.
- Réponse 200 : `{ "status": "UP" }` (type `application/vnd.spring-boot.actuator.v3+json` par défaut, `application/json` si la requête envoie `Accept: application/json` ; un champ `status` de type chaîne ; aucun autre champ).
- Réponse 503 : `{ "status": "DOWN" }` si la base est injoignable.
- Autres codes 400/401/403/404/409 : non applicables (pas d'authentification ; 404 uniquement pour les endpoints actuator non exposés, corps `ProblemDetail` non exigé en 0.1).

Le chemin racine de l'API métier (`/api`) sera introduit en 1.x ; en 0.2 le front passera par le proxy Caddy, la route exacte de santé côté `web` sera définie en 0.2.

## 5. Écrans

Aucun.

## 6. Critères d'acceptation

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné un PostgreSQL Testcontainers, quand le contexte Spring démarre, alors il démarre sans erreur avec `ddl-auto=validate` (RG1, RG2, RG3) | intégration |
| CA2 | Étant donné ce contexte, quand on lit la table `databasechangelog`, alors Liquibase est appliqué : la table existe et contient 0 changeset de schéma applicatif (changelog initial vide), et aucune table métier n'existe (RG3) | intégration |
| CA3 | Étant donné ce contexte sur port aléatoire, quand `GET /actuator/health` sans authentification, alors 200 et corps exactement `{"status":"UP"}` sans clé `components` (RG4, RG9) | intégration |
| CA4 | Étant donné ce contexte, quand `GET /actuator/info`, `/actuator/env`, `/actuator/beans`, alors 404 pour chacun (RG5) | intégration |
| CA5 | Étant donné le conteneur PostgreSQL arrêté après démarrage, quand `GET /actuator/health`, alors 503 et `status` = `DOWN` (RG4) | intégration |
| CA6 | Étant donné un dépôt propre, quand `docker compose up -d --build`, alors `base` et `api` passent `healthy` en moins de 120 s, `api` ayant démarré après `base` (RG6, RG7) | E2E (stack) |
| CA7 | Étant donné la stack lancée, quand `curl http://localhost:8080/actuator/health`, alors 200 et `{"status":"UP"}` (RG4) | E2E (stack) |
| CA8 | Étant donné la stack lancée, quand `docker compose exec base psql -U <utilisateur> -d <base> -c '\dt'`, alors `databasechangelog` et `databasechangeloglock` sont listées (RG3) | E2E (stack) |
| CA9 | Étant donné la stack lancée, quand `docker compose exec api id -u`, alors le résultat n'est pas `0` (RG7) | E2E (stack) |
| CA10 | Étant donné la stack lancée, quand `docker volume ls`, alors un volume `donnees-postgres` (préfixé du projet compose) existe, et après `docker compose down` puis `up -d`, `databasechangelog` est toujours présente (RG8) | E2E (stack) |
| CA11 | Étant donné la stack lancée, quand `curl -i http://localhost:8080/actuator/env`, alors 404 (RG5) | E2E (stack) |
| CA12 | Étant donné la stack lancée puis `docker compose stop base`, quand `curl -i http://localhost:8080/actuator/health`, alors 503 (RG4) | E2E (stack) |
| CA13 | Étant donné le dépôt, quand `cd backend && ./mvnw -q verify`, alors le build réussit sous Java 25 (Docker requis pour Testcontainers) (RG1) | intégration |

Répartition : unitaire 0, intégration 6 (CA1 à CA5, CA13), E2E (stack) 7 (CA6 à CA12). Pas de CA E2E d'écran : il n'y a pas d'écran. Les CA E2E (stack) sont vérifiés par script ou à la main (projet Playwright en 0.3). Couverture : RG1 CA1/CA13, RG2 CA1, RG3 CA1/CA2/CA8, RG4 CA3/CA5/CA7/CA12, RG5 CA4/CA11, RG6 CA6, RG7 CA6/CA9, RG8 CA10, RG9 CA3.

## 7. Tester à la main

Prérequis : Docker et `docker compose`, rien d'autre. Depuis la racine du dépôt.

1. `docker compose up -d --build` : construit l'image `api`, démarre `base` puis `api`.
2. `docker compose ps` : `base` et `api` en statut `healthy` (patienter jusqu'à ~1 min).
3. `curl -i http://localhost:8080/actuator/health` : `HTTP/1.1 200`, corps `{"status":"UP"}`.
4. `curl -i http://localhost:8080/actuator/env` : `404`.
5. `docker compose exec base psql -U backyard -d backyard -c '\dt'` : tables `databasechangelog` et `databasechangeloglock` (utilisateur et base par défaut du compose ; adapter si différents).
6. `docker compose exec api id -u` : un nombre différent de `0`.
7. `docker compose down` puis `docker compose up -d` : retour à `healthy`, étape 5 donne le même résultat (volume `donnees-postgres` conservé, vérifiable par `docker volume ls`).
8. `docker compose stop base`, puis `curl -i http://localhost:8080/actuator/health` : `503` et `{"status":"DOWN"}` ; `docker compose start base` : retour à `UP`.
9. Nettoyage facultatif : `docker compose down -v` (supprime les données).

## 8. Points ouverts

Non bloquants (position par défaut retenue, à confirmer) :
1. **Absence de Spring Security en 0.1** : par défaut, ne pas l'ajouter avant 1.x, `/actuator/health` reste public et simple. À 1.x, il faudra l'autoriser explicitement sans authentification (healthcheck docker).
2. **Valeurs par défaut du compose sans `.env.example`** (livré en 0.4) : par défaut, `${VAR:-défaut}` avec des valeurs de développement non secrètes (base, utilisateur et mot de passe `backyard`). CLAUDE.md exige `.env` non commité : le mot de passe par défaut est acceptable en local uniquement ; la production devra imposer des valeurs en 0.4.
3. **Version exacte de postgres** (par défaut une version majeure stable fixée par le développeur, ex. 17, documentée dans le compose) et des images JDK/JRE 25 : choix laissé au développeur, sans `latest`.
4. **Port 8080 publié** : retenu pour le test manuel ; à fermer en 0.4 (seul `web` exposé en production).
5. **Healthcheck de `api`** : la JRE minimale n'a pas forcément `curl` ; par défaut le développeur choisit un moyen disponible dans l'image (installation de `curl`, ou sonde Java), sans alourdir l'image.
6. **Test d'intégration en 0.1 sans JaCoCo** : la couverture de 85 % n'est mesurée qu'à partir de 0.3 ; en 0.1 aucun seuil n'est appliqué.

Bloquants : aucun.
