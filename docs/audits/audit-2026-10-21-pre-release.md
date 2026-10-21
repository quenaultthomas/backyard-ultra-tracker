# Audit 2026-10-21 : pré-release (back, front, performances, sécurité, CVE, préparation production)

- Commit audité : `0675663`
- Périmètre : axes SEC, MET, ARC, PER, DEP, FRT sur `backend/src/main`, `frontend/`, `docker-compose*.yml`, Dockerfiles, `frontend/Caddyfile`, `.env.example`, `docs/deploiement.md`, workflows de release. Exclus à la demande : axe TST, `backend/src/test`, `e2e/`. DEP limité à ce qui bloque un test réel.
- Incréments livrés pris en compte : jusqu'à 6.4 (specs `increment-0.1` à `increment-6.4` et `R.1` à `R.9b` présentes, commits `Increment 5.1a` à `Increment 6.4` sur `main`)
- Rapport précédent : `audit-2026-10-16-backend-securite-performance.md` (commit `e411ccd`), suivi dans la section « Suivi de l'audit du 2026-10-16 ».
- Limites de l'outillage : l'auditeur est en lecture seule et n'exécute aucun outil du dépôt ni aucun outil réseau. `npm audit`, `dependency-check` (Maven), le scan des images et la lecture des alertes Dependabot (`gh api .../dependabot/alerts`) **n'ont pas été lancés**. Les versions ont été relevées dans les fichiers, mais elles sont postérieures aux connaissances de l'auditeur : aucun avis CVE n'a pu être confronté. Voir « CVE et dépendances ».

## Synthèse

| Sévérité | Nombre |
|---|---|
| BLOQUANT | 1 |
| A_CORRIGER | 2 |
| SUGGESTION | 7 |

Le code est en bon état. Le contrôle des rôles est fait côté serveur sur chaque route, y compris les trois nouvelles routes publiques. Le reste est solide aussi : Argon2id borné par un sémaphore et calculé hors transaction, CSRF actif, session protégée contre la fixation, jeton QR `SecureRandom`, logo contrôlé par signature d'octets. Les règles métier ne sont définies qu'à un seul endroit, dans le domaine, et les changesets n'ont jamais été modifiés. La file de scan hors ligne ne perd ni ne duplique aucun scan.

Un seul point bloque la release, et il touche le résultat de la course : un scan fait dans les temps mais reçu après le départ de la boucle suivante est refusé pour toujours (MET-02). L'aide affichée au bénévole renvoie vers une réintégration. Or la réintégration est impossible si ce retard a terminé la course : mauvais vainqueur, et aucune correction possible. Le cas se produit justement en fin de course, au pire moment : réseau faible en forêt, redémarrage de l'`api` qui déconnecte les bénévoles, ou saturation du pool de connexions par le suivi public.

À traiter ensuite :
- le coût du suivi public sous charge. Chaque appel relit tous les passages, rien n'est en cache, aucune limite de débit, et tous les écrans relancent leur requête au même instant à chaque changement de boucle (PER-04).
- la publication d'une version contenant les jalons 5 et 6. Les sources, `.env.example` et le CHANGELOG sont encore en 0.4.0 (DEP-03).

Les durcissements de l'audit du 16 qui avaient été planifiés (jalon A : HSTS, planificateur allégé, N+1, ARC-03, ARC-07) ont disparu de la roadmap avec le commit `ddd0383`. Ils sont toujours présents dans le code.

**Ordre de correction conseillé** : MET-02 (décision métier, puis code ou procédure), PER-04 suivi public, DEP-03 (publication), puis les suggestions.

## Couverture

| ID | Statut | Périmètre |
|---|---|---|
| SEC-01 | OK | complet |
| SEC-02 | OK | complet |
| SEC-03 | OK | complet |
| SEC-04 | REMARQUE | complet |
| SEC-05 | OK | complet |
| SEC-06 | REMARQUE | complet |
| SEC-07 | OK | complet |
| SEC-08 | OK | complet |
| SEC-09 | OK | complet |
| SEC-10 | OK | complet |
| SEC-11 | OK | complet |
| SEC-12 | OK | complet |
| MET-01 | OK | complet |
| MET-02 | REMARQUE | complet |
| MET-03 | OK | complet |
| MET-04 | OK | complet |
| MET-05 | OK | complet |
| MET-06 | OK | complet |
| MET-07 | OK | complet |
| MET-08 | OK | complet |
| MET-09 | OK | complet |
| MET-10 | OK | complet |
| MET-11 | OK | complet |
| ARC-01 | OK | complet |
| ARC-02 | OK | complet |
| ARC-03 | REMARQUE | complet |
| ARC-04 | OK | complet |
| ARC-05 | OK | complet |
| ARC-06 | OK | complet |
| ARC-07 | REMARQUE | complet |
| ARC-08 | OK | complet |
| ARC-09 | OK | complet |
| PER-01 | OK | complet |
| PER-02 | OK | complet |
| PER-03 | OK | complet |
| PER-04 | REMARQUE | complet |
| PER-05 | OK | complet |
| DEP-01 | OK | chemins |
| DEP-02 | OK | chemins |
| DEP-03 | REMARQUE | chemins |
| DEP-04 | OK | chemins |
| DEP-05 | OK | chemins |
| DEP-06 | OK | chemins |
| DEP-07 | REMARQUE | chemins |
| FRT-01 | OK | complet |
| FRT-02 | OK | chemins |
| FRT-03 | NON_VERIFIE (contrats des specs non comparés champ par champ aux interfaces TypeScript) | complet |
| FRT-04 | OK | complet |
| FRT-05 | OK | complet |
| FRT-06 | OK | complet |

Preuves des `OK` (code lu) :
- SEC-01 : `SecuriteConfiguration.java:48-71`. Les 44 mappings (`git grep Mapping`) tombent tous sous une règle. Les routes publiques ajoutées depuis le 16 sont limitées à `GET /api/courses`, `/api/courses/*/logo` et `/api/courses/*/suivi` (`:66-67`). `/actuator/**` n'est joignable depuis Caddy que par `/api/sante` et `/api/version` (`Caddyfile:25-43`).
- SEC-02 : `SecuriteConfiguration.java:37-42,98-102` (Argon2id 19 456 Kio, 2 itérations, p=1).
- SEC-03 : `MotDePasse.java:76-79` et `SupprimerCompteCoureur.Commande` (`:36-42`) masquent la valeur. `ConnexionController.java:34` ne journalise que l'id. `GestionnaireErreursInattendues.java:32,42-49` ne journalise aucun message d'exception.
- SEC-05 : `SecuriteConfiguration.java:72-75`. Côté front, `app.config.ts:23-26` renvoie l'en-tête XSRF ; la file de scan renouvelle le jeton puis retente une fois (`file-scans.service.ts:334-341`).
- SEC-07 : `JetonQr.java:14-30` (32 octets `SecureRandom`, base64url) ; `toString` masqué (`:50-53`) ; `uq_inscription_jeton_qr` (`0007-inscription.yaml`).
- SEC-08 : `Compte.creerAdminMaster` n'est appelé que par `InitialiserAdminMaster.java:80` ; index unique partiel `0003-admin-master-unique.yaml:9-10`.
- SEC-09 : `git log --all -- .env .env.prod` est vide. `git grep` ne trouve aucun secret littéral hors tests (seuls les défauts de dev `backyard`, `docker-compose.yml:18,35`, remplacés par des variables obligatoires en production, `docker-compose.prod.yml:11,23`).
- SEC-10 : `Logo.java:23-34` (taille, puis format par signature), `FormatLogo.java:13-16` (SVG exclu), type servi = type reconnu + `nosniff` (`LogoPublicController.java:47-53`), stockage `bytea` sans chemin de fichier, `request_body max_size 4MiB` dans Caddy (`Caddyfile:49-51`).
- SEC-11 : `HorlogeController.java:26` et `HorlogeConfiguration.java:16-26` sont en `@Profile`. `GardeFouProfilE2e.java:25-31` refuse le profil e2e en production. `BACKYARD_ENVIRONNEMENT: production` est fixé dans `docker-compose.prod.yml:27`.
- SEC-12 : `LireDossard.java:52-54` (`parIdEtCourse`), `SeDesinscrire.java:38-43` (`parIdEtCompte` relu sous verrou), `InscriptionJpaAdapter.java:94-114`. Le suivi public n'expose ni jeton ni identifiant (`SuiviCourseReponse.java:20-35`).
- MET-01 : `AbandonnerCoureursHorsDelai.java:71-93` (verrou, horloge lue une fois), `PlanificateurAbandonAutomatique.java:42-77` (une transaction par course, échec isolé).
- MET-03 : `EnregistrerPassage.java:70-122` et `Course.java:348-393` couvrent tous les refus (affectation, jeton, statut, course non démarrée ou terminée, doublon, continuité, anti-doublon).
- MET-04 : `EnregistrerPassage.java:76-79,101-106` (rejeu par identifiant de scan), `PassageJpaAdapter.java:80-96` (`on conflict do nothing`), `uq_passage_inscription_boucle` (`0009-passage.yaml`). Le comportement est conforme à CLAUDE.md, mis à jour sur l'identifiant de scan.
- MET-05 : `Reintegration.java:23-31`, `ReintegrerCoureur.java:64-84` (passages `CORRECTION`), allure calculée sur les seuls passages `SCAN` (`Course.java:315-327`).
- MET-06 : `Course.java:90-102,222-226`.
- MET-07 : `InscrireCoureur.executer` (verrou de course, doublon, place, dossard), contraintes `uq_inscription_course_dossard` et `uq_inscription_course_compte`.
- MET-08 : `SeDesinscrire.java:36-45`, `Course.java:468-481`.
- MET-09 : `SupprimerCompteCoureur.java:71-82` (anonymisation, annulation, sessions invalidées).
- MET-10 : `SupprimerCourse.java:29-34`, `Course.java:233-237`, cascades dans `0006`/`0007`/`0009`, route `DELETE` réservée à l'admin master (`SecuriteConfiguration.java:55-56`).
- MET-11 : `Course.java:259-303` (chronométrage et statistiques calculés à la demande) ; aucune colonne dérivée dans les changesets 0004 à 0010.
- ARC-01, ARC-02, ARC-08 : `git grep` vide pour les imports Spring/JPA/Jackson/Hibernate dans `*/domaine/*`, pour `.infrastructure.` dans `*/exposition/*`, et pour `Instant.now()`, `LocalDate.now()` et `System.currentTimeMillis` nus. Le fuseau des courses est unique (`CalendrierDesCourses.java:10-17`).
- ARC-04 : aucune FK vers `compte` (`0006`, `0007`) ; le contexte courses ne connaît que `compteId`, et les pseudos passent par `AnnuairePseudos`.
- ARC-05 : entités JPA et mapping explicite (`CourseJpaAdapter.java:107-121`, `InscriptionJpaAdapter.java:163-172`, `PassageJpaAdapter.java:98-101`).
- ARC-06 : un cas d'usage par action, `@Transactional` dans `application` (fichiers lus ci-dessus).
- ARC-09 : nommage métier français et suffixes techniques anglais dans tous les fichiers lus.
- PER-01 : chaque fichier de `changesets/` a un seul commit (`git log` par fichier).
- PER-02 : `application.yml:17`.
- PER-03 : `0007-inscription.yaml` (dossard, compte, jeton), `0009-passage.yaml` (inscription + boucle), `0003` (admin master), FK en cascade.
- PER-05 : `PassageJpaAdapter.java:17-21` (insertion et lecture seulement ; suppression uniquement par cascade d'une inscription en préparation).
- DEP-01 : `postgres:17.11-alpine`, `eclipse-temurin:25.0.4.1_1-jdk/jre-noble`, `node:24.22.0-alpine3.24`, `caddy:2.11.7-alpine`, actions épinglées par SHA (`release.yml`, `ci.yml`).
- DEP-02 : `backend/Dockerfile:18-25`, `frontend/Dockerfile:18-23`.
- DEP-04 : `docker-compose.yml:21-26,44-63,69-83`, `docker-compose.prod.yml:47-49`.
- DEP-05 : `docker-compose.prod.yml:7,18,29,34,40-42` (api non publié, `restart` sur les trois services, `mem_limit`) ; journaux bornés (`docker-compose.yml:5-9`) ; HTTPS par Caddy (`Caddyfile:9-11`). Le point HSTS est traité en SEC-04.
- DEP-06 : `backend/.dockerignore` (`target/`, `.env*`, clés), `frontend/.dockerignore` (`node_modules/`, `dist/`, `.env*`).
- FRT-01 : `app.routes.ts` (toutes les routes protégées ont une garde), `reserve-aux-roles.guard.ts:8-27` (aide d'affichage seulement, contrôle réel côté serveur), `retour.ts:5-13` (pas de redirection ouverte).
- FRT-02 (chemins : scan, suivi, refus d'accès) : `issue-envoi.ts:28-52`, `refus-acces.service.ts:18-30`, `rafraichissement-chronometrage.ts:185-193`.
- FRT-04 : `git grep` vide pour `innerHTML`, `bypassSecurityTrust`, `DomSanitizer` et `eval` dans `frontend/src`. Les gabarits n'utilisent que l'interpolation (`suivi-course.html`). La CSP n'autorise aucun script inline (`Caddyfile:20`).
- FRT-05 : `file-scans.service.ts` (écriture avant envoi, envoi un par un dans l'ordre, identifiant de scan réutilisé à chaque tentative, panne et refus distingués, reprise sur `online` et `visibilitychange`), `stockage-scans.ts` (IndexedDB, repli en mémoire signalé par `persistant`), `scan-en-file.ts:108-112` (âge monotone ou mural).
- FRT-06 : `ngsw-config.json` (aucun `dataGroups`, `/api/**` exclu de la navigation) ; seul le compte bénévole et sa liste de courses vont dans `localStorage`, sans secret (`memoire-locale.service.ts:3-6`).

## Suivi de l'audit du 2026-10-16

| Remarque du 16 | État au `0675663` | Où |
|---|---|---|
| [A_CORRIGER] SEC-06 Argon2id sans plafond | **Corrigée** : sémaphore global (4 calculs, 1 s d'attente, puis 503), vérification hors transaction pour la connexion et la création de compte, `mem_limit` et `MaxRAMPercentage=60`. Limite par IP absente : compromis documenté et accepté (`deploiement.md:66`), non re-signalé. Effet de bord nouveau : voir SEC-06 ci-dessous. | `LimiteurHachage.java:27-50`, `Connecter.java:28-30`, `docker-compose.prod.yml:21`, `backend/Dockerfile:30` |
| [SUGGESTION] SEC-06 blocage de l'admin master par un tiers | **Acceptée** (compromis documenté, conduite à tenir le jour de course), non re-signalée. Le correctif prévu en A.3 a disparu de la roadmap. | `deploiement.md:58-64`, `.env.example:37-38` |
| [SUGGESTION] SEC-04 HSTS, `Secure` non forcé | **Ouverte**, RÉCURRENTE (A.3 retiré de la roadmap) | ci-dessous |
| [SUGGESTION] PER-04 planificateur à chaque seconde | **Ouverte**, RÉCURRENTE (A.1 retiré) | ci-dessous |
| [SUGGESTION] PER-04 N+1 sur deux listes | **Ouverte**, RÉCURRENTE (A.1 retiré) | ci-dessous |
| [SUGGESTION] DEP-05 journaux et mémoire | **Corrigée** | `docker-compose.yml:5-9`, `docker-compose.prod.yml:9,21,35`, `PlanificateurAbandonAutomatique.java:21-24` |
| [SUGGESTION] ARC-03 borne de 24 h dans le DTO | **Ouverte**, RÉCURRENTE et aggravée (la borne est aussi recopiée dans le front) | ci-dessous |
| [SUGGESTION] ARC-07 gestionnaire lié à une liste de contrôleurs | **Ouverte**, RÉCURRENTE (`SuiviPublicController` ajouté à la main, `CoursesPubliquesController` absent) | ci-dessous |
| [SUGGESTION] MET-04 idempotence | **Tranchée** : idempotence par identifiant de scan, CLAUDE.md aligné, file conforme (5.1a). Le cas « scan reçu après la décision » a été tranché lui aussi, mais il ouvre la remarque MET-02 ci-dessous. | `EnregistrerPassage.java:26-31`, `scan-en-file.ts:21`, `file-scans.service.ts:343-348` |

## Remarques

### [BLOQUANT] MET-02 : un scan reçu en retard peut fausser la fin de course, sans correction possible
- **Bloque la release** : oui, sauf décision explicite de l'utilisateur assortie d'une procédure terrain (voir la correction suggérée).
- **Où** : `backend/.../courses/domaine/Course.java:170-183` (réintégration refusée si la course est `TERMINEE` ou si le temps de la dernière boucle est écoulé), `AbandonAutomatique.java:67-79` (décision au premier tick suivant la frontière), `PlanificateurAbandonAutomatique.java:42` (tick de 1 s), `EnregistrerPassage.java:110-113` (refus si la course n'est plus en cours), `frontend/src/app/scan/refus-scan.ts:52-57` et `scans-a-verifier/scans-a-verifier.html:33-37` (aide « demandez à un administrateur de réintégrer le coureur » affichée aussi pour `COURSE_TERMINEE` et `TEMPS_DE_COURSE_ECOULE`), `docs/specs/increment-5.1a.md:94`, `docs/specs/increment-5.1b.md:67`
- **Règle** : CLAUDE.md, « Fin de course » et « Filet réseau » ; BLOQUANT au sens « résultats de course faussés »
- **Constat** : le planificateur tranche la boucle N dans la seconde qui suit le départ de N+1. Un scan fait pendant N mais reçu après ce tick est refusé pour toujours. C'est la décision de l'utilisateur dans 5.1a et 5.1b, qui renvoie le rattrapage à la réintégration (4.12). Ce rattrapage ne fonctionne pas dans deux cas :
  - le retard a terminé la course. Exemple : deux coureurs encore en lice, le scan de l'un arrive en retard. L'autre est déclaré seul `VAINQUEUR` et la course passe `TERMINEE`. `Course.autoriserReintegration` refuse alors toute réintégration (`:171-173`).
  - le retard concerne la dernière boucle autorisée. La réintégration est refusée dès que le temps est écoulé (`:180-182`).

  Dans ces deux cas, le résultat est faux et ne peut plus être corrigé dans l'application. L'aide affichée au bénévole renvoie pourtant vers la réintégration.

  Trois causes ordinaires d'une vraie course rendent ce retard probable, et toutes au moment décisif :
  - réseau faible sur la ligne ;
  - redémarrage de l'`api` (OOM, mise à jour). Les sessions sont en mémoire, tous les bénévoles sont déconnectés et leur file se met en pause `SESSION_EXPIREE` jusqu'à leur reconnexion (`application.yml:38-39`, `file-scans.service.ts:392-413`, `deploiement.md:190`) ;
  - saturation du pool par le suivi public (PER-04 ci-dessous).
- **Correction suggérée** : faire trancher par l'utilisateur, avant la release, l'une de ces orientations :
  - (a) un délai de grâce dans le domaine : la boucle N n'est tranchée qu'à `départ(N+1) + délai`. Le scan reste attribué à N grâce à son âge, ce que le code fait déjà ;
  - (b) une correction admin possible juste après la fin de course (rouvrir une course `TERMINEE` depuis peu, ou corriger la décision) ;
  - (c) au minimum, une procédure terrain écrite dans `docs/deploiement.md` (poste de scan en ligne et vérification du compteur « en attente » avant chaque départ en fin de course), et l'aide de réintégration retirée pour `COURSE_TERMINEE` et `TEMPS_DE_COURSE_ECOULE`, car elle annonce un recours inexistant.
- **Récurrence** : première fois (prolonge la remarque MET-04 du 16, qui posait la question sans ce constat)

### [A_CORRIGER] PER-04 : suivi public sous charge (pas de cache, relecture complète, requêtes synchronisées, aucune limite de débit)
- **Bloque la release** : non pour un test à faible audience (quelques dizaines d'écrans). Oui si le lien de suivi est diffusé largement (réseaux sociaux, centaines de spectateurs) : à mesurer par un essai de charge avant diffusion.
- **Où** : `SuiviPublicController.java:35-39` (`no-store`), `ConsulterSuivi.java:97-111` (course, inscriptions, pseudos, tous les passages de la course), `CourseJpaEntity.java:61-64` (bénévoles en chargement paresseux : une requête de plus), `SuiviPublicController.java:38` et `ListerCourses.java:48-50` (empreinte du logo : une requête de plus), soit 6 requêtes SQL par appel ; `PassageJpaAdapter.java:63-73` ; `frontend/src/app/partage/chronometrage/rafraichissement-chronometrage.ts:5,106-119` (appel toutes les 3 s, plus un appel déclenché par chaque écran au zéro du compte à rebours) et `:185-193` (nouvel essai toutes les 3 s, sans espacement progressif, y compris sur 5xx) ; `application.yml:11-13` (pool Hikari par défaut, 10 connexions partagées avec les scans et le planificateur) ; `Caddyfile:46-53` (aucun cache ni limite de débit) ; `SecuriteConfiguration.java:66-67` (route anonyme)
- **Règle** : référentiel PER-04, et proposition du 16 « coût du polling du suivi public »
- **Constat** : chaque écran de suivi relit l'intégralité des passages de la course, soit n inscrits x k boucles lignes (1 000 lignes pour 50 coureurs à la boucle 20). La réponse est recalculée et jamais mise en cache. Les écrans se calent sur le temps serveur, donc ils relancent tous leur requête dans la même seconde, à chaque départ de boucle. C'est aussi la seconde où le planificateur prend le verrou de la course et où les scans de fin de boucle arrivent. N'importe qui peut en outre interroger cette route anonyme en boucle. Un pool de 10 connexions saturé fait attendre les scans (jusqu'à 5 s de `connection-timeout`, puis 500). Ils repartent alors en file et peuvent arriver après la décision, ce qui renvoie à MET-02.
- **Correction suggérée** :
  - micro-cache de 1 à 2 s de la réponse de suivi par course, côté application ou Caddy (même réponse pour tous les spectateurs) ;
  - limite de débit par IP sur `/api/courses/*` (module `caddy-ratelimit`, déjà évoqué dans `deploiement.md:66`) ;
  - côté front, un léger décalage aléatoire de la relance au zéro, et un espacement progressif sur 5xx ;
  - éventuellement, un pool dédié ou réservé aux scans.
- **Récurrence** : première fois (proposition d'ajout du 16, applicable depuis 6.1)

### [A_CORRIGER] DEP-03 : aucune version publiable ne contient les jalons 5 et 6
- **Bloque la release** : oui (étape de publication à faire, pas un défaut de code)
- **Où** : `backend/pom.xml:16`, `frontend/package.json:3`, `e2e/package.json:3` (`0.4.0`), `.env.example:22` (`VERSION=0.4.0`), `CHANGELOG.md:6-8` (la 0.4.0 exclut explicitement le scan hors ligne, la PWA et le suivi public), `docs/deploiement.md:137-149`, `.github/workflows/release.yml:43-56`
- **Règle** : CLAUDE.md « `.env.example` maintenu à jour » ; procédure « Publier une version »
- **Constat** : la production tire les images `ghcr.io/...:${VERSION}` (`docker-compose.prod.yml:15,32`). La seule version publiable est la 0.4.0, d'avant 5.1a. Un déploiement qui suit `.env.example` tel quel installe une application sans file hors ligne ni suivi public. Le workflow de release refusera tout tag tant que les sources et le CHANGELOG ne portent pas la nouvelle version.
- **Correction suggérée** : branche `release/0.5.0` (ou suivante) : versions dans le pom et les deux `package.json`, section `## [0.5.0]` du CHANGELOG, `VERSION` d'exemple à jour. Mentionner dans les notes de version les variables apparues depuis la 0.4.0 (aucune nouvelle variable depuis `d73d066`, vérifié dans `.env.example`). Poser d'abord un tag `-rc.1` pour la répétition générale.
- **Récurrence** : première fois

### [SUGGESTION] SEC-04 : HSTS toujours absent
- **Bloque la release** : non
- **Où** : `frontend/Caddyfile:14-22`, `backend/src/main/resources/application.yml:47`
- **Règle** : CLAUDE.md « HTTPS obligatoire via le reverse proxy »
- **Constat** : la remarque du 16 n'est pas corrigée. Aucun `Strict-Transport-Security` n'est émis, et le drapeau `Secure` des cookies dépend toujours de `X-Forwarded-Proto`. L'incrément A.3 qui devait le traiter a été retiré de la roadmap (`ddd0383`).
- **Correction suggérée** : celle du 16 (HSTS uniquement sur le site nommé de production, `cookie.secure=true` en production).
- **Récurrence** : RÉCURRENTE (audit du 2026-10-16)

### [SUGGESTION] SEC-06 : le plafond global de hachage permet de bloquer toutes les connexions
- **Bloque la release** : non (sessions déjà ouvertes non affectées)
- **Où** : `comptes/infrastructure/LimiteurHachage.java:13,27,43-45`, `application.yml:62-63`, `docs/deploiement.md:66`
- **Règle** : CLAUDE.md « Limitation des tentatives de connexion » ; disponibilité le jour de course
- **Constat** : le correctif du 16 protège bien la mémoire et le pool. En contrepartie, quatre calculs simultanés suffisent à occuper le sémaphore global. Un client anonyme qui envoie en continu une poignée de `POST /api/connexion` concurrents fait répondre 503 `SERVICE_SATURE` à toutes les connexions légitimes. Cela vise par exemple les bénévoles qui se reconnectent après un redémarrage de l'`api` (voir MET-02). `deploiement.md:66` décrit les 503 de la rafale, pas l'impact sur les utilisateurs légitimes.
- **Correction suggérée** : la limite par IP dans Caddy, déjà identifiée comme protection restante ; à défaut, documenter l'effet et la consigne « bénévoles connectés avant le départ, ne pas se déconnecter », déjà donnée pour l'admin master.
- **Récurrence** : première fois (effet du correctif de la remarque A_CORRIGER SEC-06 du 16)

### [SUGGESTION] PER-04 : planificateur à chaque seconde sous verrou, sans délai de verrou, et N+1 restants
- **Bloque la release** : non
- **Où** : `PlanificateurAbandonAutomatique.java:42`, `AbandonnerCoureursHorsDelai.java:71-86`, `CourseJpaAdapter.java:57-62` (`PESSIMISTIC_WRITE` sans `lock.timeout`) ; `ListerCoursesOuvertes.java:47`, `ListerMesInscriptions.java:47`, `MesInscriptionsController.java:58`, `InscriptionsCoureurController.java:58`
- **Règle** : référentiel PER-04
- **Constat** : rien n'a changé depuis le 16. A.1 a été retiré de la roadmap.
- **Correction suggérée** : celle du 16.
- **Récurrence** : RÉCURRENTE (audit du 2026-10-16)

### [SUGGESTION] ARC-03 : borne de 24 h d'un scan différé hors du domaine, désormais en double
- **Bloque la release** : non
- **Où** : `courses/exposition/PassageRequete.java:19,90`, `frontend/src/app/scan/file-scans/scan-en-file.ts:4-5,119-121`
- **Règle** : CLAUDE.md « une règle métier n'existe qu'à un seul endroit, dans le domaine »
- **Constat** : la borne reste dans le DTO, et 5.1a l'a recopiée dans le front (`AGE_MAXIMAL_MILLISECONDES`). Modifier l'une sans l'autre ferait diverger le classement « trop ancien » local et le refus serveur.
- **Correction suggérée** : porter la borne dans le domaine, puis l'exposer au front (champ du contrat ou constante documentée de la spec) au lieu de la recopier.
- **Récurrence** : RÉCURRENTE (audit du 2026-10-16)

### [SUGGESTION] ARC-07 : gestionnaire d'erreurs des courses toujours lié à une liste
- **Bloque la release** : non
- **Où** : `courses/exposition/GestionnaireErreursCourses.java:46-49`
- **Règle** : codes HTTP cohérents, `ProblemDetail`
- **Constat** : `SuiviPublicController` a été ajouté à la main, `CoursesPubliquesController` n'y est pas. Il ne lève aujourd'hui aucune exception métier, mais le risque signalé le 16 reste entier.
- **Correction suggérée** : celle du 16 (`basePackages`, ou règle ArchUnit).
- **Récurrence** : RÉCURRENTE (audit du 2026-10-16)

### [SUGGESTION] DEP-07 : aucune sauvegarde planifiée pour le jour de course
- **Bloque la release** : non
- **Où** : `docs/deploiement.md:198-206`
- **Règle** : référentiel DEP-07 ; protection des résultats d'une course réelle
- **Constat** : la sauvegarde et la restauration sont correctes et cohérentes avec les volumes réels (`donnees-postgres`, logos en base, `donnees-caddy`). La sauvegarde reste entièrement manuelle (`:206`) : une panne du disque du VPS pendant ou juste après la course emporte les seuls résultats.
- **Correction suggérée** : ajouter à la procédure une sauvegarde juste avant le départ et juste après la fin, copiée hors du VPS. Option : une tâche `cron` d'exemple avec rotation.
- **Récurrence** : première fois

## CVE et dépendances

Aucun outil d'analyse n'a été exécuté (contrainte de lecture seule de l'auditeur, voir en-tête). Statut : **NON_VERIFIE**. À lancer par l'utilisateur ou un développeur avant la release :
- `cd frontend && npm audit --omit=dev`, puis `npm audit` (outillage de build) ;
- `cd backend && ./mvnw org.owasp:dependency-check-maven:check` (ou `mvn versions:display-dependency-updates`) ;
- `docker scout cves` ou `trivy image` sur les deux images publiées et sur `postgres:17.11-alpine` ;
- `gh api repos/quenaultthomas/backyard-ultra-tracker/dependabot/alerts --jq '.[] | select(.state=="open") | [.security_advisory.severity, .dependency.package.name, .security_advisory.summary] | @tsv'` pour les 3 alertes signalées.

Versions relevées (fichiers lus) :

| Composant | Version | Où |
|---|---|---|
| Spring Boot (et ses versions gérées : Spring Security, Tomcat, Hibernate, Liquibase, pilote PostgreSQL, Jackson) | 4.1.1 | `backend/pom.xml:10` |
| Bouncy Castle `bcprov-jdk18on` (hors BOM Spring) | 1.86 | `backend/pom.xml:26` |
| Angular (core, router, service-worker, build, cli) | ^22.2.3 | `frontend/package.json:13-28` |
| `jsqr` (décodage QR caméra) | 1.4.0 (épinglé) | `frontend/package.json:20` |
| `uqr` (génération QR) | 0.1.3 (épinglé) | `frontend/package.json:23` |
| Images de base | `eclipse-temurin:25.0.4.1_1-jre-noble`, `caddy:2.11.7-alpine`, `node:24.22.0-alpine3.24` (build), `postgres:17.11-alpine` | Dockerfiles, `docker-compose.yml:13` |

Remarques sans sévérité :
- Surface de dépendances réduite : trois dépendances d'exécution côté front, aucune bibliothèque tierce côté back hors Spring et Bouncy Castle.
- Dependabot couvre Maven, npm, Docker et Actions (`.github/dependabot.yml`). En revanche, les montées de majeure de `eclipse-temurin` et `node` sont ignorées : les correctifs de sécurité ne seront reçus que sur la majeure courante, ce qui est le comportement voulu.
- `jsqr` 1.4.0 n'évolue plus depuis plusieurs années. Il ne traite que des images de la caméra locale, donc l'exposition est faible ; à surveiller dans le résultat de `npm audit`.
- Bouncy Castle étant hors BOM, Dependabot (groupe `maven`) est le seul mécanisme qui le met à jour.

## Propositions d'ajout au référentiel

- **DOC (nouvel axe ou rattachement DEP, P2)** : la roadmap et les décisions ne régressent pas sans décision tracée. Constat qui motive ce point : le commit `ddd0383` (« update logo PWA ») a supprimé 121 lignes de `docs/roadmap.md`, dont le jalon A (A.1 à A.3 : corrections planifiées de l'audit du 16), le jalon R, les découpages 1.6a/1.6b, 2.1a/2.1b et 4.4a/4.4b, et plusieurs « Décisions prises » (continuité des boucles 4.4a, accès à l'horloge pilotable, abandon manuel du dernier coureur 4.11). Les remarques ouvertes du 16 ne sont plus planifiées nulle part. À rétablir par l'utilisateur (`git show d73d066:docs/roadmap.md` comme base).
- **MET (P1, à partir de 5.1)** : un scan fait dans les temps mais reçu en retard ne peut pas fausser de façon irréversible une décision de fin de course (délai de grâce ou correction possible après fin).
- **PER (P1, à partir de 6.1)** : coût du suivi public sous charge (requêtes par appel, cache court, synchronisation des clients, limite de débit), déjà proposé le 16 et applicable depuis 6.1.
- **SEC (P2)** : limite de débit par IP sur les routes anonymes (`/api/connexion`, `/api/comptes`, `/api/courses/**`), et absence de déni de service applicatif par le plafond global de hachage.
- **DEP (P2)** : observabilité minimale pour une course réelle. Une erreur 500 doit être diagnosticable : `GestionnaireErreursInattendues.java:42-49` ne garde que le type et la première ligne de pile, souvent dans le framework. Un redémarrage de l'`api` doit être détecté : aucune alerte, `RestartCount` à consulter à la main.
- **DEP (P2)** : une version publiable correspond à la `main` livrée (versions des sources, CHANGELOG, `VERSION` d'exemple) avant tout déploiement réel.
- **DEP (P3)** : sauvegarde planifiée ou procédure de sauvegarde encadrant chaque course réelle.
- **ARC (P3)** : une constante métier partagée entre back et front (borne de 24 h, délai anti-doublon) n'est définie qu'une fois et transmise par le contrat, jamais recopiée.
