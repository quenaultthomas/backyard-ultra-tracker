# Rapport de test : INC-6 (intégration)

- **Date** : 2026-10-01
- **Agent auteur** : test-integration-backend
- **Version / commit testé** : branche `feature/increment-6-separation-admin-public`, HEAD `80fcd8a` + working tree non commité (production : `ApiExceptionHandler` corrigé par le développeur, front de l'inc. 6 ; tests du testeur et de cet agent). Verdict technique du testeur : OK (2026-10-01).
- **Environnement** : profil `test`, H2 2.4 en mode PostgreSQL, schéma Flyway V1 + V2, MockMvc avec vrais en-têtes `Authorization` (HTTP Basic), Java 25.0.4 (compilation cible 21), Spring Boot 4.1.1, maven-failsafe 3.5.6. Pas de PostgreSQL réel (voir §7).

## 1. Périmètre

Critères [IT] de `docs/specs/increment6.md` (révision 4b) et ceux qui dépendent de la persistance, de la sécurité réelle ou de l'enchaînement HTTP -> service -> base : **CA12** (R5-4), **CA1** (version en réel de la matrice), **CA14** (version de bout en bout), **CA9** (partie backend : non-régression). Hors périmètre : CA2 à CA8, CA10, CA11 (E2E), CA13 (unitaire, déjà écrit par le testeur).

**Tests ajoutés (3 classes, 134 tests), aucun code de production ni test existant modifié :**

| Classe (`backend/src/test/java/fr/backyard/it/`) | Tests | Rôle |
|---|---|---|
| `V1ToV2LegacyDataHttpIT` | 5 | CA12. Base `legacyv1v2` migrée en V1, peuplée (fixtures SQL de `V2MigrationIT` reprises à l'identique, sans toucher ce test), migrée en V2 dans `@BeforeAll`, puis contexte Spring complet démarré sur cette base. Aucune insertion après V2. Hérite d'`AbstractApiIT` (MockMvc, horloge, ADMIN) avec `@SpringBootTest(properties = datasource.url)` |
| `AccessMatrixIT` | 126 | CA1 en réel : 1 test de table (E1 à E25 une fois, 100 cas) + 100 cas paramétrés (25 endpoints x anonyme, `Lievre`, SCANNER, ADMIN) + 25 cas `admin-test:mauvais`. Table unique reprise de la section 4. Jeu de données neuf par cas (scénarios SETUP, RUNNING, DNF) créé par l'API réelle ; nettoyage SQL complet après chaque cas |
| `InternalErrorLogsIT` | 3 | CA14 de bout en bout : `@MockitoSpyBean RaceBoardService` force une exception citant `lievre` sous `GET /api/public/runners/1` ; la requête traverse la vraie chaîne (sécurité, MVC, `ApiExceptionHandler`) ; capture logback sur le logger racine |

## 2. Couverture exigences <-> tests

| Exigence | Critère d'acceptation | Tests | Résultat | Écart ? |
|---|---|---|---|---|
| RG8, CL10 (R5-4) | CA12 : V1 et V2 en succès **avant** le démarrage du contexte | V1ToV2LegacyDataHttpIT#ca12_v2WasAppliedBeforeTheContextStarted (historique relevé dans `@BeforeAll` après V2, identique après démarrage ; `context.getStartupDate()` >= instant de fin de V2 ; le journal Spring du démarrage dit « Schema "PUBLIC" is up to date. No migration necessary ») | PASS | Non |
| RG8 | CA12 : E4 R1 « Coureur n°1 » ; E4 R0 « Coureur n°3 », DNF ; sans Alice ni Bob | #ca12_boardsShowNeutralNamesOnMigratedData | PASS | Non |
| RG8 | CA12 : E5 « Coureur n°1 » ; E13 (ADMIN) « Coureur n°1 », `pseudo` null (et `accountId` null) | #ca12_publicAndAdminRunnerViewsShowNeutralName | PASS | Non |
| RG8 | CA12 : DNF manuel E17 (ADMIN, VOLUNTARY) 200, DNF, « Coureur n°1 » | #ca12_manualDnfOnMigratedRunnerKeepsNeutralName | PASS | Non |
| RG8 | CA12 : `count(name non nul)` = 0 ; qr_token, dossard, 2 passages d'Alice inchangés (bonus : ligne de Bob) | #ca12_databaseKeepsRaceDataAndHasNoNameLeft | PASS | Non |
| RG1 | CA1 : 25 endpoints x 4 profils = code de la colonne « Accès », statut réel du service si autorisé, 401 sans `WWW-Authenticate`, `detail` « Authentification requise » (anonyme) ou « Identifiants invalides » | AccessMatrixIT#ca1_accessMatrixOnTheRealChain (100) ; #ca1_theTableCoversE1ToE25Once | PASS (101/101) | Non |
| RG1 | CA1 : `admin-test:mauvais` donne 401 sur E1 à E25 | AccessMatrixIT#ca1_wrongCredentialsAreAlways401 (25) | PASS (25/25) | Non |
| RG10, CL11 (R5-8) | CA14 (a) `IllegalStateException`, (b) `IllegalArgumentException` avec cause, (c) `RuntimeException` | InternalErrorLogsIT#ca14_a_… ; #ca14_b_… ; #ca14_c_… | PASS (3/3) | Non. Discriminant : 3/3 en échec sur l'ancien gestionnaire (§4) |
| RG1, CL5, CL6 | CA9 (partie backend) : `clean verify` vert, aucun test existant modifié | suite complète (§3) ; `git status` : seuls des fichiers `??` de test ajoutés | PASS | Non. Le contrôle « `git diff` contre le commit de départ » est vide sur `backend/src/test` (aucun fichier suivi modifié) |

Complément déjà fourni par le testeur et revérifié dans la suite complète : `api/AccessMatrixSliceTest` (126/126, CA1 services mockés), `config/PseudoNormalizationSourceReviewTest` (13/13, CA13), `api/ApiExceptionHandlerLoggingTest` (5/5, CA14 appel direct).

Exigences sans test : **aucune** pour le périmètre [IT]. CA2 à CA8, CA10 et CA11 (E2E) et CA9 volet E2E relèvent de l'agent E2E.

## 3. Résultats d'exécution (réels)

| Suite | Total | Passés | Échoués | Ignorés | Durée |
|---|---|---|---|---|---|
| Tests de l'incrément, ciblés (`verify -Dgroups=INC-6 -Djacoco.skip=true`, failsafe ; surefire sans test tagué INC-6) | 134 | 134 | 0 | 0 | build 3 min 56 s |
| Non-régression : suite complète (`clean verify`), surefire | 558 | 558 | 0 | 0 | build complet 4 min 51 s |
| Non-régression : suite complète, failsafe (INC-1 à INC-6) | 219 | 219 | 0 | 0 | (idem) |

Détail failsafe de la suite complète : AccessMatrixIT 126, AccountContractIT 20, AccountFlowIT 14, AccountUniquenessIT 5, DeployConfigIT 2, EndToEndRaceLifecycleIT 1, FlywayV1ChecksumIT 1, InternalErrorLogsIT 3, NoCorsIT 3, NoTestEndpointsIT 2, ProdLoggingConfigIT 1, PwaStaticResourcesIT 19, QrTokenUniquenessIT 1, RaceBoardTransactionBoundaryIT 2, RepositoryDerivedQueriesIT 6, SchemaAndContextStartupIT 4, SecurityHeadersIT 3, V1ToV2LegacyDataHttpIT 5, V2MigrationIT 1. Passage de 85 à 219 : +134 (cette passe). `All coverage checks have been met` (JaCoCo, seuils du `pom.xml` non modifiés). Build Angular, `npm test` et compilation sans ligne `[WARNING]` ni `[ERROR]` dans le journal Maven de la suite complète.

Commandes exécutées :
- `mvn -B -f backend/pom.xml verify -Dgroups=INC-6 -Djacoco.skip=true`
- `mvn -B -f backend/pom.xml clean verify` (succès : 07:40, journal complet relu)
- Rejeu de discrimination (hors dépôt, copie du backend dans le répertoire temporaire de session, `ApiExceptionHandler` remplacé par la version `git show HEAD`, `-Dit.test=InternalErrorLogsIT -Dskip.npm -Dskip.installnodenpm`) : voir §4.

## 4. Échecs et bugs détectés

Aucun bug applicatif. Aucun échec sur la version testée.

**Discrimination de CA14 (demandée par la spec, rejeu avant correctif)** : sur une copie hors dépôt du backend dont `ApiExceptionHandler` est celui de `HEAD` (`LOG.error("Incohérence interne sur {} {}", method, uri, ex)`), `InternalErrorLogsIT` donne **3 échecs sur 3** : la ligne ERROR porte la trace (donc le message citant `lievre` et le nom du test) et le gestionnaire n'écrit pas le nom de la classe de l'exception. Avec le correctif en place (`logInternalError`), 3/3 PASS. Le test n'est donc pas vacueux.

**Discrimination de CA12** : l'assertion « aucun `Alice` » n'est pas vacueuse : `@BeforeAll` vérifie, avant V2, que `name IN ('Alice','Bob')` compte 2 lignes, et l'historique Flyway est relevé avant le chargement du contexte. Je n'ai pas rejoué le test contre une V2 amputée de son `UPDATE runner SET name = NULL` (cela exigerait de modifier une migration, hors de mon périmètre) ; la preuve repose sur la présence préalable du nom brut et sur `ca12_databaseKeepsRaceDataAndHasNoNameLeft` (0 nom non nul).

**Observation (aucun écart de code)** : `AccessMatrixIT` s'appuie sur le fait que E15, E16, E10, E11, E12, E17 et E18 aboutissent (200/204) avec les jeux de données SETUP, RUNNING et DNF décrits ; tous les statuts autorisés réels sont ceux de la table (200, 201, 204), sans écart avec la spec.

**Incident d'environnement (mon fait, réparé)** : une première exécution de `clean verify` lancée en tâche de fond a été interrompue pendant `npm ci`, ce qui a laissé `frontend/node_modules` incomplet (`Cannot find module './ranges/intersects'` au build suivant). Réparé : `frontend/node_modules` supprimé (dossier non suivi par git) puis `npm ci` (245 paquets), et la suite complète relancée avec succès. Aucune commande npm ou Maven n'a été lancée en parallèle sur `frontend/` ; le rejeu de discrimination a utilisé `-Dskip.npm -Dskip.installnodenpm` et s'est terminé avant le lancement de la suite complète.

## 5. Modifications du patrimoine existant

- Ajouts uniquement : `V1ToV2LegacyDataHttpIT`, `AccessMatrixIT`, `InternalErrorLogsIT` (3 fichiers non suivis).
- Aucun test existant modifié, supprimé, désactivé ou assoupli (`V2MigrationIT` et `AccountContractIT` inchangés ; leurs fixtures et leurs valeurs attendues sont reprises par copie). Aucun code de production modifié. Aucune dépendance ajoutée (`MockitoSpyBean`, logback et JsonPath viennent de `spring-boot-starter-test`).
- `PATRIMOINE.md` : sous-section « Incrément 6 — tests d'intégration » ajoutée dans la section Incrément 6 ; lignes R5-4, R5-5, R5-8 des écarts ouverts annotées (tests écrits et verts, levée à prononcer par l'agent fonctionnel).

## 6. Tests instables ou en quarantaine

Aucun. Pas de `sleep`, pas de dépendance à l'ordre : chaque cas d'`AccessMatrixIT` repart d'un jeu de données neuf, et `V1ToV2LegacyDataHttpIT` n'a qu'une dépendance d'état (le DNF manuel d'Alice) que n'affirme aucun autre test (le seul test qui relit Alice en base ne regarde ni son statut ni ses passages modifiables). Durée notable : `AccessMatrixIT` 118 s (BCrypt coût 12 à chaque inscription de `Lievre`, 126 jeux de données).

## 7. Risques et limites

- **RT1 maintenue** : tout est exécuté sur H2 en mode PostgreSQL. CA12 prouve la migration V1 -> V2 puis l'API sur H2 ; la même preuve sur PostgreSQL réel (R5-2) reste à faire avant déploiement.
- CA12 : la « preuve que V2 est appliquée avant le contexte » combine l'historique Flyway relevé avant le démarrage, la comparaison avec l'historique après démarrage, `getStartupDate()` (précision à la milliseconde, comparaison `>=`) et le journal Flyway du contexte. Elle ne repose pas sur un crochet interne de Spring.
- CA14 : l'exception est injectée par un spy sur un service réel (aucun chemin de production connu ne produit un message citant un pseudo). La chaîne HTTP, la sécurité, MVC et le gestionnaire sont réels. Le corps de la réponse n'est pas modifié (exclusion de la spec) : pour (a) et (b) il reprend `ex.getMessage()`, ce que ce test n'assert pas.
- CA1 en réel : un 403 SCANNER est affirmé par le code seul ; les corps d'erreur 403 ne sont pas inspectés (la spec ne les fige pas).
- Hors périmètre : CA2 à CA8, CA10, CA11 (E2E), CA9 volet E2E, CL5 (vérification manuelle de la mise à jour du service worker).

## 8. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** :
- **Réserves ou motifs** :
- **Actions correctives exigées** :
- **Date** :
