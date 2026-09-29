# Rapport de test : INC-5 (intégration)

- **Date** : 2026-09-29
- **Agent auteur** : test-integration-backend
- **Version / commit testé** : branche `feature/increment-5-comptes-pseudo`, HEAD `2774bdf` + working tree non commité (production + tests de l'incrément 5, adaptations N1 déjà appliquées)
- **Environnement** : profil `test`, H2 2.x en mode PostgreSQL, schéma Flyway V1 + V2, MockMvc avec vrais en-têtes `Authorization` (HTTP Basic), Java 21, Spring Boot 4.1.1, maven-failsafe 3.5.6. Pas de PostgreSQL réel (voir §7).

## 1. Périmètre

Critères de `docs/specs/increment5.md` (révision 5) marqués [IT], [slice], [unit + IT], [config] ou dont la vérification dépend de la persistance, de la sécurité réelle ou de l'enchaînement HTTP -> service -> base. Les CA [E2E] (CA25, CA26, CA28, CA37, CA38, CA41, CA44) et [front-unit] (CA27, CA35, CA36) relèvent d'autres agents. Les parties [manuel] de CA39 et CA40 (VPS) ne sont pas exécutables ici.

IT déjà écrits par le développeur (relus, exécutés, non modifiés) : `AccountFlowIT` (10), `V2MigrationIT` (1), `ProdLoggingConfigIT` (1).

**IT ajoutés par cet agent (20 + 2 + 1 = 23 tests, 3 classes)** :
- `backend/src/test/java/fr/backyard/it/AccountContractIT.java` (20 tests, hérite d'`AbstractApiIT`, donc nettoyage en deux phases exercé avec des comptes inscrits à deux courses par E20) ;
- `backend/src/test/java/fr/backyard/it/DeployConfigIT.java` (2 tests, [config] de CA39 et CA40) ;
- `backend/src/test/java/fr/backyard/it/FlywayV1ChecksumIT.java` (1 test, somme de contrôle de V1, CA1).

## 2. Couverture exigences <-> tests

Légende : `AF` = `it/AccountFlowIT`, `AC` = `it/AccountContractIT` (ajouté), `V2` = `it/V2MigrationIT`, `SL` = `api/AccountApiSliceTest`, `AS` = `service/AccountServiceTest`, `RR` = `service/RunnerAccountRegistrationTest`. Toutes les cases IT ci-dessous sont `PASS` (exécution du 2026-09-29).

| Exigence | CA | Tests | Résultat | Écart ? |
|---|---|---|---|---|
| RG1, RG4, RG5, RG6, RG16 | CA1 | AF#ca1_schema ; FlywayV1ChecksumIT#ca1_v1ChecksumUnchanged | PASS | Non. Contrainte `(race_id, account_id)` non testée en tentative de doublon (voir §7) |
| RG2 | CA2 | SL#ca2_acceptedPseudos / #ca2_refusedPseudos ; `domain/PseudoTest` | PASS (UNIT/slice) | Non. Revue « minuscules en un seul endroit » : revue de code, aucun test automatisé de cette revue n'a été vérifié ici |
| RG3, CL11 | CA3 | AC#ca3_passwordLengthsThroughRealChain (E3, E22, E23 sur la vraie chaîne) ; SL#ca3_* ; `PasswordPolicyTest` | PASS | Non |
| RG3 | CA4 | AF#ca6_registration... (hash `$2a$12$`, 60 car.) ; AF#ca17_passwordResetAndChange (E22, E23 en coût 12) ; AF#ca4_staffHashOfCost4StillVerified (bean unique, hash coût 4, ADMIN 200) | PASS | Non |
| RG3, RG24 | CA5 | AC#ca5_noSecretInAnyResponse (E3, E20, E21, E13, E14, E25 + E22/E23/E24 en 204 sans corps) ; SL#ca5 | PASS | Non |
| RG7, RG18 | CA6 | AF#ca6_registration... ; AC#rg18_scan... ; RR (test CA6), SL#ca6 | PASS | Non |
| RG2, RG5 | CA7 | AF#ca7_duplicatePseudoAndCaseInsensitiveLogin (409 BUSINESS_CONFLICT applicatif, detail exact, 1 compte, R2 sans coureur) | PASS | Non |
| RG5, CL4 | CA8 | AF#ca8_concurrentCreations (Tortue / TORTUE en parallèle : une 201, une 409) | PASS | Variante « même course » non testée (voir §7) |
| RG4, RG8, CL1, CL7 | CA9 | AC#ca9_registerExistingAccountToAnotherRace (201, doublon 409 detail exact, 404, RUNNING 409, 2 coureurs) | PASS | Non |
| RG5, RG7, RG8, CL13 | CA10 | AC#ca10_ownerCannotCreateSecondAccountButRegistersWithE20 | PASS | Voir E-INC5-2 (spec : « accountId » absent de la réponse E20) |
| RG7 | CA11 | SL#CA11 (400, service non appelé) | PASS (slice) | Non |
| RG2, RG9 | CA12 | AC#ca12_basicAuthenticationOutcomes (401 « Identifiants invalides », sans WWW-Authenticate ni Set-Cookie ; Lievre/lievre/LIEVRE en 200) | PASS | Non |
| RG9, RG10 | CA13 | AC#ca13_accessMatrix (matrice complète en réel, dont anonyme « Authentification requise ») ; SL#CA13 | PASS | Non |
| RG11 | CA14 | AC#ca14_myRegistrationsSortedByRaceDate (tri par `raceDate`, qrToken de B absent) | PASS | Non |
| RG12, CL5 | CA15 | AC#ca15_adminSeesAccountOfRunner (coureur lié + coureur sans compte, en base) | PASS | Non |
| RG13 | CA16 | AC#ca16_noPublicTechnicalCorrelation | PASS | Non |
| RG14, CL9 | CA17 | AF#ca17_passwordResetAndChange (204, ancien refusé, nouveau accepté, coût 12) ; AF#ca20 (journal) | PASS | « E23 et E20 sans changement préalable » : E23 couvert, E20 non enchaîné après E22 (mineur) |
| RG14 | CA18 | AC#ca18_resetPasswordErrors (404, 400, 401 anonyme, 403 SCANNER, 401 RUNNER) | PASS | Non |
| RG15 | CA19 | AF#ca19_noAutomatedPasswordProcedure ; `NoTestEndpointsIT` (E1 à E25) | PASS | Non |
| RG3, RG16, RG19, RG20 | CA20 | AF#ca20_logsWithoutSecretsNorPseudo | PASS | **E-INC5-1** : WARN 409 de E3 avec le pseudo (hors lignes visées par CA20) |
| RG6, CL5, CL12 | CA21 | Suite complète verte (§3) ; AC#ca31_legacyRunner... (scan 200, DNF manuel, E4 « Coureur n°1 ») ; `EndToEndRaceLifecycleIT` | PASS | Réintégration d'un coureur sans compte non rejouée en IT (couverte inc. 2/3, via comptes) |
| RG9, RG10, CL10 | CA22 | AC#ca22_pseudoEqualToTechnicalAccount ; SL#CA22 | PASS | Non |
| RG4, CL6, CL21 | CA23 | AC#ca23_accountSurvivesRunnerDeletion ; RR#CA23 | PASS | Non |
| RG11, CL8 | CA24 | AC#ca24_runnerStatusPerRace (2 courses RUNNING, DNF manuel) ; AS#CA24 | PASS | Non |
| RG5, CL14 | CA29 | AC#ca29_twoDifferentPseudosInSameRace | PASS | Non |
| RG18 | CA30 | AF#ca6_registration... (E4, E5, E13, E15 `{bib:2,name:"Autre"}`, runner.name NULL) | PASS | Non |
| RG6, RG18, CL5 | CA31 | V2#ca31 (migration V2 sur données V1 : purge, account_id null, passages, DNF) ; AC#ca31_legacyRunner... (E4, E5, E13, E6 « Coureur n°1 », jamais « Alice ») | PASS | Voir §7 : la partie HTTP n'utilise pas la base migrée V1 -> V2 mais une ligne SQL avec `name` renseigné sur base V2 |
| RG19, CL17 | CA32 | AF#ca17 (E23 204, ancien 401, nouveau 200) ; AC#ca32_changePasswordErrorsAndRoles (400 + hash inchangé, 401 anonyme/SCANNER/ADMIN) ; AF#ca20 (journal E23) | PASS | Non |
| RG20, RG4, CL15, CL16 | CA33 | AF#ca33_deleteAccountDetachesRunners ; AC#ca33_deleteAccountAccessRules (401/403/401, 404) | PASS | Non |
| RG18, RG20 | CA34 | AF#ca33 (nom « Coureur n°1 » en E4, E13, scan ; stable après reprise du pseudo) ; AC#ca34_detachedRunnerDisplayNameFollowsBib (E15 bib 5, E13) | PASS | E5 d'un détaché non asserté directement (E4, E13 et scan le sont) |
| RG22 | CA39 [config] | ProdLoggingConfigIT#ca39 (application, profil prod) ; DeployConfigIT#ca39_logRetentionConfigFiles (logrotate `daily`, `rotate 6` ; journald `MaxRetentionSec=7day`) | PASS | [manuel] VPS non exécuté : réserve prévisible |
| RG23, CL20 | CA40 [config] | DeployConfigIT#ca40_nginxRateLimiting | PASS | [manuel] VPS non exécuté : réserve prévisible. « Aucun code applicatif ne compte » : revue, hors IT |
| RG24, CL21 | CA42 | AC#ca42_listAndSearchAccounts (tri, runnerCount 2/0/1, LIE, ` tor `, vide, zzz, 3 propriétés, 401/403/401) ; SL#CA42 ; AS#CA42 | PASS | Non |
| RG24, CL22 | CA43 | AF#ca43_literalSearch (a_b, %, \, A_B, A_B2) sur H2 | PASS | Non |
| RG18 (point d'interprétation 2, arbitrage N1) | (E6, E4) | AC#rg18_scanResponseAndBoardCarryDisplayName (E6 `runnerName` = « lievre » pour un coureur lié, « Coureur n°9 » sans compte ; E4 identique ; `runner.name` NULL en base) | PASS | Non |
| C3 (arbitrage N1) : nettoyage d'un compte inscrit à deux courses (E20) | (infrastructure) | AC#ca5, #ca9, #ca14, #ca16, #ca24, #ca42 (comptes inscrits à 2 courses suivies) : aucune violation de FK au nettoyage, base propre entre tests | PASS | Non |

CA non couverts par un IT (renvoyés à d'autres agents ou hors périmètre IT) : CA25, CA26, CA28, CA37, CA38, CA41, CA44 (E2E) ; CA27, CA35, CA36 (front-unit) ; parties [manuel] de CA39 et CA40 (VPS).

Exigences [IT]/[slice]/[config] sans aucun test : **aucune**.

## 3. Résultats d'exécution (réels)

Exécution du 2026-09-29 (07:29 à 07:49), tous BUILD SUCCESS, 0 ligne `[WARNING]` dans les journaux Maven.

| Suite | Total | Passés | Échoués | Ignorés | Durée |
|---|---|---|---|---|---|
| Tests de l'incrément (`verify -Dgroups=INC-5 -Djacoco.skip=true`) : surefire (tags INC-5) | 117 | 117 | 0 | 0 | - |
| Tests de l'incrément : failsafe (tags INC-5 : `AccountFlowIT` 10, `AccountContractIT` 20, `DeployConfigIT` 2, `FlywayV1ChecksumIT` 1, `V2MigrationIT` 1, `ProdLoggingConfigIT` 1) | 35 | 35 | 0 | 0 | - |
| Suite complète (`clean verify`) : surefire | 414 | 414 | 0 | 0 | - |
| Suite complète : failsafe (non-régression INC-1 à INC-4 comprise) | 76 | 76 | 0 | 0 | 3 min 36 s (total build) |

Avant mon ajout : 53 IT (constat du testeur). Après : 76 (+23, dont 20 + 2 + 1). JaCoCo : « All coverage checks have been met » (seuils du `pom.xml`, non modifiés). Les pourcentages détaillés (domain 98,6 %, service 99,2 %) sont ceux du testeur, non recalculés ici : mes tests n'ajoutent pas de code de production.

Commandes exécutées :
- `mvn -B -f backend/pom.xml verify -Dgroups=INC-5 -Djacoco.skip=true`
- `mvn -B -f backend/pom.xml clean verify` (3 exécutions au fil de l'ajout des classes ; la dernière, à 76 IT, est celle citée ci-dessus)

## 4. Échecs et bugs détectés

Aucun échec sur du code de production. Un échec de mise au point de mon propre test (`rg18_...`, scan « dans le futur » : horloge de test à 08:30 pour un scan à 08:31) a été corrigé dans le test ; ce n'est pas un bug applicatif.

**Écarts à consigner pour l'agent fonctionnel (aucun code de production modifié) :**

| ID | Test | Attendu | Obtenu | Reproduction | Sévérité | Preuve |
|---|---|---|---|---|---|---|
| E-INC5-1 | (aucun test ; constat testeur, confirmé par lecture du code et par les journaux d'exécution) | RG16 / esprit de la minimisation : pas de pseudo dans les journaux | `ApiExceptionHandler.respond` écrit en WARN le `detail` de toute 4xx. Pour un 409 sur E3, la ligne est : `Requête refusée POST /api/public/races/6/registrations : 409 Pseudo déjà utilisé : lievre. Si c'est votre compte, ...` | `POST /api/public/races/{id}/registrations` avec un pseudo existant, lire le journal | Faible à moyenne (RGPD : pseudo + horodatage + IP dans un journal conservé 7 jours). CA20 ne vise que les lignes WARN d'échec d'authentification et les INFO de E22, E23, E24 : il est donc **satisfait**, mais RG16 (« Un échec d'authentification est journalisé sans le pseudo ») et le principe de minimisation appellent une décision | Journal de `AccountContractIT#ca10`/`AccountFlowIT#ca7` : ligne « 409 Pseudo déjà utilisé : lievre » |
| E-INC5-2 | AC#ca10 | CA10 : « 201, coureur de R2 `bib = 1`, `accountId` égal à celui de A » | La réponse E20 (même corps que E3, RG7 : `runnerId, raceId, bib, name, pseudo, qrToken`) ne contient pas `accountId`. J'ai vérifié le lien en base (`runner.accountId()`), l'attendu est donc tenu sur le fond | Lire `RegistrationResponse` | Nulle (incohérence de rédaction de la spec : RG13 interdit aussi `accountId` côté public) | `RegistrationResponse.java` |

## 5. Modifications du patrimoine existant

- Ajouts uniquement : `AccountContractIT`, `DeployConfigIT`, `FlywayV1ChecksumIT`.
- Aucun test existant modifié, supprimé, désactivé ou assoupli. Aucun code de production modifié. Aucune dépendance ajoutée (`com.jayway.jsonpath.JsonPath` et Hamcrest viennent déjà de `spring-boot-starter-test`).
- Vérifié : `V1__init.sql` identique à HEAD (`git diff HEAD` vide) ; la somme de contrôle `-1184617864` figée dans `FlywayV1ChecksumIT` est celle relevée sur ce fichier.

## 6. Tests instables ou en quarantaine

Aucun. Aucun `sleep`. Le seul test concurrent (`AccountFlowIT#ca8`) est synchronisé par un `CountDownLatch` et son résultat (une 201, une 409) est déterministe quelle que soit l'entrelacement (l'un des deux chemins est refusé par le contrôle applicatif ou par `DATA_INTEGRITY`). Chaque test de `AccountContractIT` est isolé par le nettoyage d'`AbstractApiIT` (base propre : vérifié par l'absence de 409 parasites sur les pseudos réutilisés `lievre`, `tortue` d'un test à l'autre).

## 7. Risques et limites

- **RT1 (transverse, maintenue)** : tout est exécuté sur H2 en mode PostgreSQL. Non validé sur PostgreSQL réel : `V2__account.sql` (`GENERATED ALWAYS AS IDENTITY`, `UNIQUE`, `ALTER COLUMN name DROP NOT NULL`, `UPDATE runner SET name = NULL`), l'échappement `LIKE ... ESCAPE` de la recherche E25 (CA43), le comportement concurrent de l'index unique (CA8), la contrainte `uq_runner_race_account`.
- CA31 : `V2MigrationIT` prouve la migration sur données V1 (base dédiée, sans HTTP). La partie « E4, E5, E13 donnent « Coureur n°1 » et jamais « Alice » » est vérifiée dans `AccountContractIT#ca31_...` sur une base déjà migrée, avec une ligne SQL dont `name` vaut « Alice » (colonne non mappée par JPA, donc ignorée par l'API). Elle prouve le calcul du nom affiché, pas l'enchaînement migration puis API sur la même base : c'est une limite assumée (deux contextes Spring distincts ne partagent pas de base H2 migrée en deux temps).
- Contrainte `(race_id, account_id)` : jamais testée par une insertion en doublon (rendue inatteignable par le contrôle applicatif de RG8 ; existence vérifiée seulement indirectement par CA1, qui teste `pseudo`). Non demandée explicitement comme test de rejet par CA1 (énoncé : « existe »).
- CA8, variante « même course » : seule la variante « deux courses différentes, casse différente » est en IT.
- Aucun test ne mesure le délai BCrypt coût 12 (une IT dure plus longtemps : `AccountContractIT` ~61 s pour 20 tests) ; la suite reste raisonnable (3 min 36 s pour le build complet).
- Rappels des réserves déjà prévues par l'arbitrage : CA39 et CA40 [manuel] (VPS).
- E-INC5-1 et E-INC5-2 : voir §4, à trancher par l'agent fonctionnel.

## 8. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** :
- **Réserves ou motifs** :
- **Actions correctives exigées** :
- **Date** :
