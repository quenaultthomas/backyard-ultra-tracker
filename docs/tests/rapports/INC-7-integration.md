# Rapport de test : INC-7 (intégration)

- **Date** : 2026-10-01
- **Agent auteur** : test-integration-backend
- **Version / commit testé** : branche `feature/increment-7-connexion-inscription` (working tree non commité, sur `97f5171`)
- **Environnement** : profil `test`, H2 en mode PostgreSQL, schéma Flyway ; failsafe ; Node v24.21.0 (build Angular lancé par Maven)

> **État du rapport** : complet (étape `/valider-increment 7`). Section 1 : adaptations des IT existants (étape préalable au verdict technique). Sections 2 à 7 : couverture, résultats et risques après ajout des nouveaux IT (2026-10-01).

## 1. Adaptations des IT existants (démarche N1, `INC-5-arbitrage-N1.md`)

Aucun code de production modifié. Aucun test supprimé, désactivé ou assoupli. Aucune catégorie C. Les tests de l'agent testeur (slice, front-unit) n'ont pas été touchés.

| # | Test | Modification | Catégorie | Motif écrit | Aussi strict que l'original ? |
|---|---|---|---|---|---|
| IT-1 | `it/NoTestEndpointsIT#ca7_requestMappingsAreExactlyTheDocumentedEndpoints` (CA7 inc. 4) | `EXPECTED_API_PATTERNS` : ajout de `/api/public/accounts` (20 -> 21 motifs) ; nombre de méthodes 25 -> 26 ; `@DisplayName`, message `as(...)`, javadoc de classe et de la constante : « E1 à E26 », « 21 motifs uniques, 26 méthodes », référence `docs/specs/increment7.md` RG2 | **B** | E26 (`POST /api/public/accounts`) est créé par la spec inc. 7 (RG2, section 8 ; C14). Le libellé « E1 à E25 » devient faux sans mise à jour (véracité, N1 §4) | Oui : égalité exacte sur le nombre de méthodes (26) et sur l'ensemble des motifs (`isEqualTo(Set)`) ; motifs hors `/api/**` inchangés |
| IT-2 | `it/NoTestEndpointsIT#ca7_noMvcPathContainsForbiddenWords` | Aucune modification | - | Passe sans changement : `/api/public/accounts` ne contient aucun mot interdit (`test`, `clock`, `time`, `reset`, `close`) | Inchangé |
| IT-3 | `it/DeployConfigIT#ca40_nginxRateLimiting` (CA40 inc. 5, CA17 inc. 7) | Nombre de `limit_req zone=` 2 -> 3 (message `as(...)` mis à jour) ; ajout de l'assertion sur le `location` exact `= /api/public/accounts` (zone `backyard_registration`, `burst=5 nodelay`) ; `@DisplayName` mis à jour (E26, 3 au total). Les assertions sur les zones (2 zones, taux), `limit_req_status 429`, `location` E3 (burst 5) et `location /api/account/` (burst 10) sont conservées telles quelles | **B** | RG2 inc. 7 (section 8, CA17) : E26 partage la zone `backyard_registration` sur un `location` exact. Le total de 3 est prescrit par la spec | Oui, et plus strict : une assertion de plus, compte exact conservé (`isEqualTo(3)`), regex ancrée sur `location = /api/public/accounts` |
| IT-4 | `it/PwaStaticResourcesIT#ca3_frontRoutesForwardToIndexHtml` | Ajout de la valeur `/inscription` au `@ValueSource` (additif ; 6 -> 7 cas) | **A (additif)** | CA21 : `GET /inscription` anonyme doit être servi avec le contenu de `index.html` (200, text/html, no-cache) | Oui : mêmes assertions que les autres routes |
| IT-5 | `it/PwaStaticResourcesIT#ca21_headOnInscriptionReturns200` (**nouveau**, tags `INC-7`, `INC7-CA21`) | Ajout : `HEAD /inscription` anonyme -> 200 | Ajout | CA21 exige GET **et** HEAD ; seul `HEAD /` était testé | - |

Constat : `PwaPaths.FRONT_ROUTES` contient déjà `/inscription` explicitement (modification du développeur). `GET /nimporte-quoi` : le test existant `ca3_unknownOrForbiddenPathsAreNeverServed` n'asserte que « jamais 200 » (inchangé) ; la clause « reste 401 » de CA21 est désormais assertée par un test dédié additif (section 2, `UnknownPathUnauthorizedIT`).

**À soumettre à l'agent fonctionnel (règle 2)** : adaptations de catégorie **B** IT-1 et IT-3 (accord attendu). IT-4 et IT-5 sont additives (A).

## 2. Couverture exigences ↔ tests (périmètre [IT] et config)

Nouveaux IT (additifs, aucun test existant modifié) : `it/PublicAccountCreationIT` (9 cas, hérite de `AbstractApiIT` ; vraie chaîne HTTP Basic, sécurité, `AccountService`, JPA, H2 mode PostgreSQL) et `it/UnknownPathUnauthorizedIT` (1 cas, serveur embarqué réel).

| Exigence | Critère | Tests | Résultat | Écart ? |
|---|---|---|---|---|
| INC7-CA1 (volet base) | 201 corps strict `{pseudo}` ; 1 `account` (hash `$2a$12$` vérifiant `motdepasse-9`), 0 `runner`, 0 `race` | `PublicAccountCreationIT#ca1_createsOneAccountAndNoRunnerInDatabase` | PASS | Non |
| INC7-CA3 (comptage) | `Lievre`/`LIEVRE`/`lievre`/bon mot de passe : 409 `BUSINESS_CONFLICT`, `detail` exact, `count(pseudo='lievre')` = 1, hash inchangé ; compte E26 redemandé = 409 | `#ca3_existingPseudoIsRefusedWhateverTheCase`, `#ca3_secondCreationOfAnE26AccountIsRefused` | PASS | Non |
| INC7-CA4 | 10 appels concurrents : un 201, neuf 409, un compte, 0 coureur | `#ca4_tenConcurrentCreationsGiveOneAccount` | PASS | H2 seulement (RT1) |
| INC7-CA5 (chaîne réelle) | anonyme, SCANNER, ADMIN = 201 ; `Lievre`, `admin-test:mauvais`, `scanner-test:mauvais`, inconnu = 401 « Identifiants invalides » sans `WWW-Authenticate`, aucun compte créé | `#ca5_anonymousScannerAndAdminAreAccepted`, `#ca5_runnerAccountAndWrongCredentialsAreRefused` | PASS | Non |
| INC7-CA6 | `nouveau-1` : E21 200 liste vide ; E19 et `/api/admin/races` 401 sans `WWW-Authenticate` | `#ca6_createdAccountIsRunnerOnly` | PASS | Non |
| INC7-CA7 | E20 sur course SETUP : 201 + dossard + `qrToken` ; E3 avec ce pseudo : 409 ; un coureur | `#ca7_emptyAccountRegistersByE20ButNotByE3` | PASS | Non |
| INC7-CA18 (bout en bout) | création, 409, 400 (mot de passe court, pseudo invalide), 401 : aucun pseudo, mot de passe, `$2` ni hash dans les journaux | `#ca18_noPseudoPasswordNorHashInLogsEndToEnd` | PASS | Limite : section 7 |
| INC7-CA21 (401) | `GET /nimporte-quoi` anonyme = 401 exactement, ni `index.html`, ni `WWW-Authenticate` | `UnknownPathUnauthorizedIT#ca21_unknownPathStaysUnauthorized` | PASS | Non (clause « reste 401 » désormais assertée ; `PwaStaticResourcesIT` conservé) |
| INC4-CA7 (adapté) | E1 à E26 | `NoTestEndpointsIT` (2) | PASS | Non |
| INC7-CA17 [config] | 3 `limit_req` | `DeployConfigIT#ca40_nginxRateLimiting` | PASS | [manuel] VPS non exécutable |
| INC7-CA21 (`/inscription`) | GET et HEAD servis | `PwaStaticResourcesIT` | PASS | Non |

Critères hors périmètre de cet agent (couverts par ailleurs) : CA1 HTTP, CA2, CA5 (slice), CA18 (slice et service) : tests du testeur ; CA8 à CA16, CA20, CA22, CA23 : E2E. Aucune exigence [IT] sans test.

## 3. Résultats d'exécution (réels)

| Suite | Total | Passés | Échoués | Ignorés |
|---|---|---|---|---|
| Nouveaux IT (`PublicAccountCreationIT` 9, `UnknownPathUnauthorizedIT` 1), run isolé | 10 | 10 | 0 | 0 |
| IT adaptés (section 1, run de l'étape préalable) | 25 | 25 | 0 | 0 |
| Suite complète `clean verify` : surefire | 590 | 590 | 0 | 0 |
| Suite complète : failsafe | 231 | 231 | 0 | 0 |
| Suite complète : front-unit (vitest, via Maven) | 322 | 322 | 0 | 0 |

Commandes : `mvn -B -f backend/pom.xml verify -Dit.test="PublicAccountCreationIT,UnknownPathUnauthorizedIT" -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false -Djacoco.skip=true` : BUILD SUCCESS, 10/10. Puis `mvn -B -f backend/pom.xml clean verify` : BUILD SUCCESS, exit 0 (fin 16:14, 6 min 33 s environ), JaCoCo « All coverage checks have been met ». Failsafe passe de 221 à 231 (+10 nouveaux IT), surefire inchangé à 590, front-unit inchangé à 322 : non-régression des incréments 1 à 6 constatée. Compilation : 95 sources main et 68 sources test, 0 `[WARNING]` dans le journal du dernier run. Un premier `clean verify` (BUILD SUCCESS, mêmes totaux) avait relevé un avertissement de dépréciation `Level.ALL` dans mon propre test ; corrigé en `Level.TRACE`, puis suite complète rejouée en entier.

## 4. Échecs et bugs détectés
Aucun échec, aucun bug applicatif. Les 10 nouveaux IT ont réussi dès la première exécution. CA18 vérifie que des lignes sont bien capturées (pas de passage à vide) ; son pouvoir discriminant n'a pas été démontré par une mutation du code de production (interdit).

## 5. Modifications du patrimoine existant
Section 1 (IT-1, IT-3 en B ; IT-4 en A ; IT-5 ajout). Cette étape : uniquement des ajouts, aucun test existant modifié, supprimé ni assoupli. `docs/tests/PATRIMOINE.md` mis à jour (section « Incrément 7 » : lignes CA1 base, CA3 base, CA4, CA5 chaîne réelle, CA6, CA7, CA18 bout en bout, CA21 401).

## 6. Tests instables ou en quarantaine
Aucun. CA4 (concurrence) : le résultat attendu (un 201, neuf 409) ne dépend pas de l'ordonnancement des threads ; il a passé 2 fois sur 2 (run isolé et suite complète), sans relance sélective.

## 7. Risques et limites
- H2 en mode PostgreSQL, non PostgreSQL réel (RT1) : la contrainte UNIQUE sur `account.pseudo` et la concurrence de CA4 ne sont pas exercées sur le moteur de production.
- CA18 : le logger `fr.backyard` est élevé à TRACE pendant le test ; les journaux de bibliothèques (Hibernate bind TRACE, Spring) restent aux niveaux du profil `test`. La configuration de journalisation `prod` et la conservation 7 jours ne sont pas vérifiées ici.
- CA17 : la config nginx est vérifiée par lecture du fichier versionné, pas par un nginx réel ; le volet [manuel] (6 requêtes passent, la 7e reçoit 429) reste une réserve exploitant.
- CA21 : `GET /nimporte-quoi` testé en anonyme seulement.
- Les adaptations de catégorie B (IT-1, IT-3) attendent l'accord de l'agent fonctionnel (règle 2).

## 8. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** :
- **Réserves ou motifs** :
- **Actions correctives exigées** :
- **Date** :
