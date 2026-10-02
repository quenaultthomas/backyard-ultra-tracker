# Backyard Ultra Tracker

Application de gestion de courses « backyard ultra » amateurs : organisation des courses, inscriptions des coureurs, scan des passages par les bénévoles et écran de suivi en direct. Budget quasi nul, déploiement Docker sur un VPS.

## Contexte métier

- Une **boucle** (yard) démarre à heure fixe : tous les coureurs encore en course partent ensemble et doivent boucler avant le départ de la boucle suivante.
- Pas de passage valide sur la boucle courante = **abandon** (DNF).
- La course s'arrête quand un seul coureur termine une boucle que personne d'autre ne termine : il est **vainqueur**, tous les autres sont en abandon.
- Plusieurs courses sont gérées en parallèle, chacune avec ses propres paramètres et sa propre page d'inscription.

## Langage ubiquitaire

Ces termes sont la référence pour le code, les specs, l'API et l'interface. Pas de synonyme.

| Terme | Définition |
|---|---|
| Course | Une édition de backyard : nom, date, logo, paramètres de boucle, limites, bénévoles affectés, statut |
| Boucle | Un yard : distance (m), durée (min), dénivelé positif (m), identiques pour toute la course |
| Compte | Identité d'une personne qui se connecte (pseudo, mot de passe, rôle) |
| Inscription | Participation d'un compte coureur à une course : dossard, jeton QR, statut, motif et boucle d'abandon |
| Passage | Enregistrement brut du franchissement de la ligne pour une boucle donnée |
| Abandon | Sortie de course d'un coureur (DNF), avec un motif |
| Réintégration | Correction d'un abandon enregistré à tort |
| Bénévole | Compte chargé de scanner les passages |

Statuts et énumérations :
- Course : `EN_PREPARATION`, `EN_COURS`, `TERMINEE`
- Inscription : `EN_COURSE`, `ABANDON`, `VAINQUEUR`
- Motif d'abandon : `VOLONTAIRE`, `HORS_DELAI`, `MANUEL`, `AUTRE`
- Origine d'un passage : `SCAN`, `CORRECTION`
- Rôle : `ADMIN_MASTER`, `ADMIN`, `BENEVOLE`, `COUREUR`

## Modèle du domaine

- **Course** (racine d'agrégat) : id, nom, date, logo, statut, démarréeLe, paramètres de boucle (distance en m, durée en min, dénivelé en m), nombreMaxParticipants, nombreMaxBoucles, bénévoles affectés (identifiants de comptes `BENEVOLE`)
- **Inscription** : id, course, compte, dossard (unique par course), jetonQr (opaque, aléatoire), statut, motifAbandon, boucleAbandon
- **Passage** : id, inscription, numeroBoucle, scanneLe (null si correction), origine
- **Compte** (contexte séparé) : id, pseudo (unique), empreinte du mot de passe, rôle, créeLe

Principe : les passages sont une donnée brute immuable. Boucle courante, nombre de boucles, distance, dénivelé, allure, durée de course et classement sont **dérivés** et jamais stockés.

## Règles métier

- **Abandon automatique** : au départ de la boucle N+1, toute inscription `EN_COURSE` sans passage valide sur la boucle N passe en `ABANDON` (motif `HORS_DELAI`, boucleAbandon = N). Un seul contrôle, sur la boucle qui vient de se terminer, indépendant par course. Déclenché côté serveur.
- **Fin de course** : contrôlée au départ de la boucle N+1, en même temps que l'abandon automatique.
  - Une seule inscription a un passage valide sur la boucle N : elle passe `VAINQUEUR`, les autres `ABANDON`, la course `TERMINEE`.
  - Aucune inscription n'a de passage valide sur la boucle N : toutes les inscriptions encore `EN_COURSE` au départ de la boucle N passent `VAINQUEUR` (vainqueurs partagés), la course `TERMINEE`.
  - La boucle N est la dernière autorisée (N = nombreMaxBoucles) : toutes les inscriptions avec un passage valide sur la boucle N passent `VAINQUEUR` (vainqueurs partagés), les autres `ABANDON`, la course `TERMINEE`.
- **Nombre max de participants** : une inscription est refusée quand la course est complète.
- **Paramètres figés** : distance, durée, dénivelé et nombre max de boucles ne sont plus modifiables une fois la course `EN_COURS`.
- **Bénévoles affectés** : seuls les bénévoles affectés à une course peuvent scanner des passages pour cette course.
- **Abandon manuel** : action admin avec confirmation, jamais via le scan QR.
- **Réintégration** : action admin. Recrée les passages manquants (origine `CORRECTION`, scanneLe null) pour chaque boucle entre boucleAbandon et la boucle courante, et remet l'inscription `EN_COURSE`. Ces passages sont exclus du calcul d'allure et affichés avec un badge « corrigé ».
- **Scan** : un passage est refusé s'il existe déjà pour cette inscription et cette boucle, ou si l'inscription n'est pas `EN_COURSE`.
- **Filet réseau** : côté bénévole, le scan est d'abord enregistré localement (file d'attente + nouvelles tentatives), puis envoyé à l'API. L'API est idempotente sur (inscription, boucle).
- **Désinscription** : possible uniquement tant que la course est `EN_PREPARATION`.
- **Suppression d'une course** : uniquement par l'admin master, et uniquement tant que la course est `EN_PREPARATION`.
- **Suppression d'un compte coureur** : les inscriptions aux courses `EN_PREPARATION` sont annulées ; le compte est anonymisé (pseudo remplacé, mot de passe effacé, connexion impossible) et ses inscriptions et passages aux courses `EN_COURS` ou `TERMINEE` sont conservés pour ne pas fausser les résultats. Le pseudo libéré peut être réutilisé.

## Fonctionnalités par écran

**Connexion / création de compte**
- Connexion par pseudo + mot de passe.
- Sans compte : création d'un compte coureur (pseudo + mot de passe). Seuls les comptes coureurs se créent librement.

**Administration** (`ADMIN_MASTER`, `ADMIN`)
- Déclaration des courses : nom, date, distance d'une boucle (m), durée d'une boucle (min), dénivelé d'une boucle (m), logo, nombre max de participants, nombre max de boucles, liste des bénévoles intervenant sur la course.
- Pilotage d'une course : démarrage, suivi des coureurs, abandon manuel, réintégration.
- Gestion des comptes : l'admin master crée les admins ; les admins créent les bénévoles.

**Bénévole** (`BENEVOLE`)
- Scan des QR codes des coureurs à la caméra, avec retour visuel immédiat et file hors ligne.

**Suivi d'une course** (public)
- Écran soigné, logo de la course intégré, pensé pour être projeté.
- Boucle en cours et compte à rebours, durée écoulée, nombre de boucles, distance et dénivelé cumulés, coureurs en course, coureurs en abandon (avec boucle et motif), vainqueur.
- Rafraîchissement automatique (polling 2-3 s).

**Coureur** (`COUREUR`)
- Inscription aux courses ouvertes.
- Mes inscriptions : dossard et QR code, désinscription.
- Mon compte : changement de mot de passe, suppression du compte.

## Stack

- Java 25, Spring Boot 4.1.1 (Spring Web, Spring Data JPA, Spring Security, Actuator)
- PostgreSQL, migrations Liquibase
- Frontend : Angular en PWA (service worker pour la file de scan hors ligne), scan QR caméra
- Déploiement : Docker + `docker compose` (voir section Déploiement)

## Structure du dépôt

```
backend/              application Spring Boot + tests unitaires et d'intégration + Dockerfile
frontend/             application Angular (PWA) + Dockerfile (build Angular servi par Caddy)
e2e/                  tests Playwright
docs/roadmap.md       roadmap des incréments
docs/specs/           specs des incréments (increment-X.Y.md)
scripts/              scripts utilitaires (données de démo)
docs/deploiement.md   procédure de déploiement sur le VPS
docker-compose.yml    stack commune (dev, tests E2E, production)
docker-compose.prod.yml  surcharges production (domaine, HTTPS, redémarrage)
docker-compose.e2e.yml   surcharges pour les tests E2E (profil `e2e`, horloge pilotable)
.env.example          toutes les variables d'environnement, sans valeur secrète
```

## Déploiement

Toute l'application se lance avec Docker, en local comme sur le VPS. Aucune installation de Java, Node ou PostgreSQL n'est nécessaire sur la machine hôte.

**Services `docker compose`** :

| Service | Image | Rôle |
|---|---|---|
| `base` | `postgres` (version fixée) | Base de données, volume nommé `donnees-postgres` |
| `api` | construite depuis `backend/` | Spring Boot, non exposé directement à l'extérieur |
| `web` | construite depuis `frontend/` | Caddy : sert le front Angular, relaie `/api` vers `api`, HTTPS automatique (Let's Encrypt) en production |

**Images** :
- Dockerfiles multi-étapes : une étape de build (JDK 25 / Node), une étape d'exécution minimale (JRE 25 / Caddy).
- Exécution en utilisateur non root, versions d'images de base fixées (jamais `latest`).
- `.dockerignore` pour ne pas embarquer `target/`, `node_modules/`, secrets.

**Configuration** :
- Tout passe par variables d'environnement (`.env`, jamais commité) : connexion base, identifiants de l'admin master, domaine, profil Spring. `.env.example` est maintenu à jour à chaque nouvelle variable.
- Les logos de course sont stockés sur un volume nommé `logos` (ou en base), jamais dans l'image.
- Liquibase s'applique automatiquement au démarrage de `api`.

**Santé et démarrage** :
- Healthchecks sur les trois services (`pg_isready`, `/actuator/health`, page d'accueil).
- `api` attend que `base` soit saine ; `web` attend que `api` soit saine (`depends_on: condition: service_healthy`).
- En production : `restart: unless-stopped`, seul `web` expose des ports (80/443).

**Commandes** :
- Local : `docker compose up -d --build` puis http://localhost
- Production : `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build`
- Les tests E2E tournent sur exactement la même stack que la production (hors HTTPS).

**Sauvegarde** : `docs/deploiement.md` décrit la sauvegarde et la restauration de la base (`pg_dump`) et du volume des logos, ainsi que la mise à jour de l'application.

## Architecture : Clean Architecture + DDD

Quatre couches, dépendances uniquement vers l'intérieur :

1. **domaine** : agrégats, entités, value objects (records), règles métier, ports sortants (interfaces de dépôt). Java pur : aucune dépendance Spring, JPA, Jackson ou autre framework.
2. **application** : un cas d'usage par action métier (`DemarrerCourse`, `EnregistrerPassage`, `ReintegrerCoureur`…). Orchestre le domaine via les ports. Gère les transactions.
3. **infrastructure** : adaptateurs (persistance JPA avec entités distinctes des objets du domaine, Liquibase, sécurité, planificateur, stockage du logo).
4. **exposition** : contrôleurs REST et DTO. Aucune logique métier : validation de format, appel du cas d'usage, mapping.

Règles :
- Les règles d'architecture sont vérifiées par des tests ArchUnit (le domaine ne dépend de rien, l'exposition ne touche pas l'infrastructure, etc.).
- Une règle métier n'existe qu'à un seul endroit, dans le domaine.
- Contextes délimités : `comptes` (identité, authentification, rôles) et `courses` (organisation, inscriptions, déroulement, suivi). Le contexte `courses` ne référence un compte que par son identifiant.

## Conventions de code

- Domaine, cas d'usage et méthodes en **français**, avec les termes du langage ubiquitaire : `course.demarrer()`, `inscription.abandonner(MotifAbandon.MANUEL)`, `course.boucleCourante(instant)`.
- Les suffixes techniques imposés par les frameworks restent en anglais : `CourseController`, `CourseJpaEntity`, `CourseJpaAdapter`.
- Noms de tests en français, décrivant le comportement : `doit_passer_en_abandon_un_coureur_sans_passage_sur_la_boucle_precedente`.
- Code lisible : nommage métier, méthodes courtes, aucune duplication de règle métier.
- Erreurs explicites : exceptions métier typées, aucune exception avalée, API cohérente (400 validation, 401/403 sécurité, 404 introuvable, 409 conflit métier), messages exploitables au format `ProblemDetail`.
- Temps injecté via `Clock` pour que les règles temporelles soient testables.

## Sécurité

Rien de critique, mais rien de ridicule :
- Spring Security, mots de passe hachés en Argon2id (ou BCrypt), paramètres documentés, jamais stockés ni journalisés en clair.
- Un mot de passe en clair n'apparaît jamais dans les logs, les exceptions, les `ProblemDetail`, les traces ni les messages d'erreur. Tout objet qui le transporte (value object `MotDePasse`, DTO) masque sa valeur dans `toString()`.
- Mot de passe : 12 caractères minimum.
- Limitation des tentatives de connexion (blocage temporaire après échecs répétés).
- Message d'erreur de connexion générique : pas d'énumération des pseudos.
- Session côté serveur, cookie `HttpOnly`, `Secure`, `SameSite=Lax`, protection CSRF active.
- HTTPS obligatoire via le reverse proxy.
- Contrôle des rôles côté serveur sur chaque endpoint, jamais uniquement côté front.
- Jeton QR aléatoire (`SecureRandom`), distinct du dossard et non devinable.
- Admin master créé au déploiement (premier démarrage) à partir de variables d'environnement ; unique, non supprimable, jamais créable depuis l'interface.
- Secrets uniquement en variables d'environnement, jamais commités.

## Persistance

- Schéma géré exclusivement par Liquibase (changelogs versionnés). `spring.jpa.hibernate.ddl-auto=validate`.
- Un changeset appliqué n'est jamais modifié : toute évolution passe par un nouveau changeset.

## Tests et qualité

**Back** : couverture minimale **85 %** (lignes et branches), mesurée par JaCoCo en agrégeant tests unitaires et tests d'intégration. Le build échoue en dessous.

**Front** : pas de tests unitaires. Chaque écran doit être couvert par au moins un test E2E.

- **Tests unitaires** : domaine et cas d'usage, sans Spring ni base de données. JUnit 5 + AssertJ, `Clock` fixe.
- **Tests d'intégration** : JUnit 5, `@SpringBootTest` + Testcontainers PostgreSQL, migrations Liquibase réelles, API REST et sécurité (rôles, CSRF, blocage de connexion).
- **Tests E2E** : Playwright sur la stack `docker compose` complète, lancée avec le profil `e2e` (`docker-compose.e2e.yml`). Chaque écran a au moins un parcours nominal, et chaque règle métier visible à l'écran (abandon automatique, réintégration, vainqueur, scan refusé, scan hors ligne) a son scénario.
- **Tests d'architecture** : ArchUnit.
- **Horloge pilotable** : dans le profil `e2e` uniquement, l'horloge de l'application peut être avancée via `/api/test/horloge` pour tester les règles temporelles en quelques secondes. Cet endpoint n'existe dans aucun autre profil, ce qui est vérifié par un test d'intégration. Les essais en temps réel se font en recette sur le terrain.

## Définition de « fini » (par incrément)

Compile sans warning + tous les tests verts (unitaires, intégration, E2E) + couverture back ≥ 85 % + chaque écran livré couvert en E2E + règles ArchUnit respectées + aucune règle métier dupliquée + `docker compose up -d --build` démarre la stack complète avec tous les services sains + `.env.example` à jour + validation de l'utilisateur après test à la main.

## Incréments

La roadmap est dans `docs/roadmap.md` : mini-incréments numérotés `X.Y`, regroupés en jalons, chacun avec ce qui est livré et ce que l'utilisateur teste à la main en déploiement local.

- On les réalise dans l'ordre, sans en sauter, un à la fois.
- Un incrément = une branche `increment/X.Y-slug` = une pull request = une spec `docs/specs/increment-X.Y.md`.
- Cible : relisible en 15 à 20 minutes (environ 400 lignes de production hors tests). Sinon, découper avant de coder.
- Les « Décisions en attente » de la roadmap sont tranchées avec l'utilisateur avant l'incrément qui en dépend.
- `scripts/donnees-demo.sh` crée par l'API les données nécessaires au test manuel ; il est tenu à jour à partir du jalon 2.
- Ne jamais passer à l'incrément suivant tant que le courant n'est pas validé par l'utilisateur.

## Workflow d'orchestration

Sous-agents :

| Agent | Rôle | Écrit |
|---|---|---|
| `fonctionnel` | Rédige la spec de l'incrément | `docs/specs/increment-X.Y.md` |
| `testeur-unitaire` | Tests unitaires du domaine et des cas d'usage, à partir de la spec | JUnit 5, sans Spring |
| `developpeur-back` | Implémente domaine, cas d'usage, persistance et API ; responsable du déploiement (`docker-compose*.yml`, `.env.example`, `docs/deploiement.md`) | Code Java, `backend/Dockerfile`, fichiers de déploiement |
| `developpeur-front` | Implémente les écrans Angular à partir du contrat d'API | Code Angular, `frontend/Dockerfile`, configuration Caddy |
| `testeur-it` | Tests d'intégration (API, persistance, sécurité), après le code | JUnit 5 + Testcontainers |
| `testeur-e2e` | Tests de bout en bout des écrans livrés, après le code | Playwright |

La spec de chaque incrément contient :
- les règles et cas limites ;
- les critères d'acceptation numérotés ;
- le contrat d'API (endpoints, DTO, codes d'erreur), pour que back et front avancent en parallèle ;
- une section « Tester à la main » : les étapes pour vérifier l'incrément dans l'application lancée.

Un incrément se lance avec la skill `/demarrer-increment X.Y`, qui porte le déroulé détaillé (préconditions, gates, commits, gestion des KO, restitution). En résumé, dans cet ordre :

1. `fonctionnel` : rédige la spec à partir de la ligne correspondante de `docs/roadmap.md`.
2. `testeur-unitaire` : écrit les tests unitaires à partir de la spec, pas à partir du code.
3. `developpeur-back` et `developpeur-front` : implémentent en parallèle, sur la base du contrat d'API.
4. `testeur-it` : écrit et lance les tests d'intégration, rend un verdict `OK` / `KO`.
5. `testeur-e2e` : écrit et lance les tests E2E des écrans de l'incrément, rend un verdict `OK` / `KO`.
6. L'orchestrateur vérifie la définition de « fini », puis présente l'incrément à l'utilisateur : résumé des changements, commande de lancement, étapes « Tester à la main ».
7. L'utilisateur relit, lance l'application et valide. Pas d'incrément suivant sans sa validation.

Chaque test référence le numéro du critère d'acceptation qu'il couvre. En cas de `KO`, retour au développeur concerné (back ou front) avec la liste des écarts : 3 allers-retours maximum par testeur, puis remonter le problème à l'utilisateur. Un test n'est jamais supprimé ni affaibli pour obtenir un verdict vert.

Si un développeur constate que le contrat d'API de la spec est incomplet ou faux, il le signale ; l'orchestrateur rappelle le `fonctionnel`, qui met à jour la spec, puis le développement reprend. Personne d'autre ne modifie la spec.
