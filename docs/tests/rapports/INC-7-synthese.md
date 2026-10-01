# Rapport de test : INC-7 (synthèse)

- **Date** : 2026-10-01
- **Agent auteur** : testeur (étape 6 de `/valider-increment 7` : rapport de synthèse)
- **Version / commit testé** : branche `feature/increment-7-connexion-inscription`, HEAD `97f5171` + working tree non commité (front de l'inc. 7, E26 et ses 2 DTO, `PwaPaths`, `deploy/nginx`, tests du testeur, de l'agent d'intégration et de l'agent E2E)
- **Environnement** : Windows 11, JDK 25 (release 21), Maven, Node installé dans `frontend/node`, backend profil `test`, H2 en mode PostgreSQL, Playwright 1.55.1 (Chromium et WebKit). Aucun PostgreSQL réel (RT1).

Rapports sources :
- Intégration : `INC-7-integration.md`
- E2E : `INC-7-e2e.md`
- Cohérence : `INC-7-coherence.md` (voir §8)
- Spec : `docs/specs/increment7.md`, révision 2

**Verdict technique du testeur : OK** (compile sans warning, tests verts, couverture cœur >= 80 %, aucune règle métier dupliquée : §3 et §4). Ce n'est pas le verdict final : il appartient à l'agent fonctionnel (§11).

## 1. Périmètre

Spec `docs/specs/increment7.md` (révision 2) : écran de connexion unique à deux entrées, inscription autonome (E26 `POST /api/public/accounts`), zone « compte » de l'en-tête, limitation nginx de E26. RG1 à RG11, CL1 à CL13, CA1 à CA23. Un seul endpoint créé (E26) ; aucune migration ; `ngsw-config.json` non modifié ; aucune règle de course touchée (CL12).

## 2. Couverture exigences <-> tests

Référentiel : `docs/tests/PATRIMOINE.md`, section « Incrément 7 ». Chemins backend relatifs à `backend/src/test/java/fr/backyard/`, front unitaire à `frontend/src/app/core/`, E2E à `frontend/e2e/tests/`.

### 2.1 Critères d'acceptation

| Critère | Type spec | Tests | Résultat | Écart ? |
|---|---|---|---|---|
| CA1 : E26 crée un compte seul | slice + IT | `api/PublicAccountCreationSliceTest` (`ca1_*`, 3) ; `service/AccountCreationWithoutRaceTest` (`ca1_*`, 2) ; `it/PublicAccountCreationIT#ca1_createsOneAccountAndNoRunnerInDatabase` | PASS | Non |
| CA2 : validations de E26 | slice | `PublicAccountCreationSliceTest` (`ca2_*`, 13) | PASS | Non |
| CA3 : pseudo déjà pris | IT | slice (`ca3_*`, 3) ; `AccountCreationWithoutRaceTest` (`ca3_*`, `cl5_*`, `rg5_*`) ; `PublicAccountCreationIT#ca3_existingPseudoIsRefusedWhateverTheCase`, `#ca3_secondCreationOfAnE26AccountIsRefused` | PASS | Non |
| CA4 : créations simultanées | IT | `PublicAccountCreationIT#ca4_tenConcurrentCreationsGiveOneAccount` | PASS | H2 seulement (RT1) |
| CA5 : matrice d'accès de E26 | slice | `PublicAccountCreationSliceTest` (`ca5_*`, 5) ; `PublicAccountCreationIT#ca5_anonymousScannerAndAdminAreAccepted`, `#ca5_runnerAccountAndWrongCredentialsAreRefused` (chaîne réelle) | PASS | Non (`AccessMatrixSliceTest` E1 à E25 inchangé) |
| CA6 : compte créé = RUNNER seulement | IT | `PublicAccountCreationIT#ca6_createdAccountIsRunnerOnly` | PASS | Non |
| CA7 : compte vide inscrit à une course | IT | `PublicAccountCreationIT#ca7_emptyAccountRegistersByE20ButNotByE3` | PASS | Non |
| CA8 : écran unique, deux entrées | E2E | `inc7-ca8-ca11-login-screen.spec.ts` « @INC7-CA8 » | PASS Chromium + WebKit | Non |
| CA9 : orientation par profil | E2E | idem « @INC7-CA9 » (4 cas) | PASS | Non |
| CA10 : mauvaise entrée | E2E | idem « @INC7-CA10 » (3 cas) | PASS | Non |
| CA11 : indépendance des emplacements | E2E | idem « @INC7-CA11 » | PASS | Non |
| CA12 : inscription autonome | E2E | `inc7-ca12-ca13-account-creation.spec.ts` « @INC7-CA12 » (5 cas) | PASS | Lecture de « une seule E21 » : COH7-3 (§10) |
| CA13 : enchaînement avec une course | E2E | idem « @INC7-CA13 » | PASS | Non |
| CA14 : liens publics de l'en-tête | E2E | `inc7-ca14-ca15-header-and-admin.spec.ts` « @INC7-CA14 » (3 cas) ; front-unit `header-account-zone.spec.ts` (27) ; `admin-separation.spec.ts` (RG2 adapté) | PASS | Frame d'en-tête au chargement direct : COH7-1 / OBS-E2E-7A (§10) |
| CA15 : `/admin` toujours introuvable | E2E | idem « @INC7-CA15 » | PASS | Non |
| CA16 : routes conservées | E2E | `inc7-ca16-ca22-ca23-routes-a11y-expiry.spec.ts` « @INC7-CA16 » (2 cas) | PASS | Non |
| CA17 : limitation nginx de E26 | config + manuel | `it/DeployConfigIT#ca40_nginxRateLimiting` (3 `limit_req`, `location = /api/public/accounts`) | PASS (config) | Volet [manuel] (6 passent, la 7e = 429) non exécuté : réserve exploitant |
| CA18 : journaux et minimisation | unit | `AccountCreationWithoutRaceTest#ca18_noPseudoNorSecretInServiceLogs` ; `PublicAccountCreationSliceTest#ca18_noSecretNorPseudoInLogs` ; `PublicAccountCreationIT#ca18_noPseudoPasswordNorHashInLogsEndToEnd` | PASS | Pouvoir discriminant non démontré par mutation |
| CA19 : non-régression | IT + E2E + front-unit | suite complète (§3) ; tests évolués (§6) | PASS (CA39 Chromium attendu) | Non |
| CA20 : aucun bloc admin sur `/connexion` et `/inscription` | E2E | `inc7-ca20-no-admin-chunks.spec.ts` (build de production, service worker actif, Cache Storage réel) ; `inc7-login-routes-sources.spec.ts` | PASS | Non |
| CA21 : `/inscription` servi par Spring Boot | IT | `it/PwaStaticResourcesIT#ca3_frontRoutesForwardToIndexHtml[/inscription]`, `#ca21_headOnInscriptionReturns200` ; `it/UnknownPathUnauthorizedIT#ca21_unknownPathStaysUnauthorized` | PASS | Non |
| CA22 : accessibilité et présélection | E2E | `inc7-ca16-ca22-ca23-...` « @INC7-CA22 » (1 + 7 cas) ; `ca41-accessibility.spec.ts` (additif) ; front-unit `login-entry.spec.ts` | PASS | Non |
| CA23 : messages d'expiration par entrée | E2E | idem « @INC7-CA23 » (3 cas) | PASS | Non |

### 2.2 Règles de gestion

| RG | Critères et tests | Résultat |
|---|---|---|
| RG1 (écran unique, deux entrées, libellés, présélection) | CA8, CA10, CA22, CA23 ; `login-entry.spec.ts` (35) | PASS |
| RG2 (orientation après connexion, 401, 429) | CA9, CA10, CA11 ; `login-entry.spec.ts` (`loginDestination`) | PASS |
| RG3 (routes de connexion existantes) | CA16, CA22 ; `inc7-login-routes-sources.spec.ts` (8) | PASS |
| RG4 (liens publics de l'en-tête) | CA14 ; `header-account-zone.spec.ts` (27) ; `admin-separation.spec.ts` (2 tests adaptés) | PASS (réserve COH7-1) |
| RG5 (E26 : validations, 409, aucun secret) | CA1 à CA6, CA18, CA12 (b) ; `AccountCreationSingleSourceReviewTest` (4) : aucune règle réécrite, un seul endroit pour le message de conflit | PASS |
| RG6 (limitation de débit) | CA17 [config] ; CA12 (d) (429 simulé) | PASS (volet nginx réel : manuel) |
| RG7 (écran `/inscription`) | CA12, CA13, CA21 ; `inc7-login-routes-sources.spec.ts` | PASS (texte d'aide : COH7-2) |
| RG8 (inscription à une course inchangée) | CA7, CA13 | PASS |
| RG9 (point d'entrée, pas de barrière) | CA14 | PASS |
| RG10 (non-régression de la sécurité) | CA5, CA15, CA19, CA20 ; `AccessMatrixSliceTest` et `AccessMatrixIT` inchangés | PASS |
| RG11 (blocs publics, service worker, R6-1) | CA20 ; `inc7-login-routes-sources.spec.ts` ; `admin-separation.spec.ts` (RG5 inc. 6) | PASS |

### 2.3 Cas limites

| CL | Couverture |
|---|---|
| CL1, CL2, CL3 | CA10 |
| CL4 | CA9, CA23 |
| CL5 | CA3 (slice, service, IT) |
| CL6 | CA4 (H2 seulement) |
| CL7 | CA7 |
| CL8 | CA12 (e) |
| CL9 | CA12 (d), CA17 |
| CL10 | Essai manuel prévu par la spec, non exécuté (réseau coupé pendant la création) |
| CL11 | CA19 ; non rejoué sous WebKit, hors CA E2E |
| CL12 | CA19 (aucune règle de course modifiée ; suite complète verte) |
| CL13 | CA14 |

Exigences sans test automatisé : CA17 [manuel] et CL10 (volets manuels prévus par la spec). Toute autre exigence a au moins un test actif.

## 3. Résultats d'exécution (réels)

| Suite | Total | Passés | Échoués | Ignorés | Source |
|---|---|---|---|---|---|
| Backend unitaires + slice (surefire) | 590 | 590 | 0 | 0 | Rejeu du coordinateur, 2026-10-01 de 20:20 à 20:29, `mvn -B -f backend/pom.xml clean verify` : BUILD SUCCESS en 8 min 11 s |
| Backend intégration `*IT` (failsafe) | 231 | 231 | 0 | 0 | Idem (221 avant la dernière passe d'intégration, +10 nouveaux IT) |
| Front unitaires (Vitest, 27 fichiers, via Maven) | 322 | 322 | 0 | 0 | Idem ; 322/322 aussi rejoué par la revue de cohérence |
| E2E, suite complète (Chromium + WebKit) | 204 | 203 | 1 (CA39 Chromium, attendu) | 0 | `INC-7-e2e.md` §3, journal `evidence-inc7/full-suite-final-203of204.txt` (24,7 min) ; non rejouée en entier |
| E2E, rejeu partiel par la revue de cohérence | 124 | 123 | 1 (CA39 Chromium) | 0 | `INC-7-coherence.md` §3.5 : 68/68, puis 55 passés + 1 échec CA39 Chromium identique à l'état de référence |

**Couverture** (tests unitaires, selon le verdict technique) : JaCoCo lignes, `domain` **98,58 %**, `service` **99,23 %**, seuil 80 % franchi (« All coverage checks have been met »). Front, `src/app/core` : **99,28 %** lignes (99,3 % instructions, 97,71 % branches, 99,11 % fonctions), seuil bloquant 80 %.

**Compilation** : 0 `[WARNING]` javac, build Angular de production et compilation des tests sans avertissement (journal du rejeu du coordinateur et rapport d'intégration).

Commandes : `mvn -B -f backend/pom.xml clean verify` ; `npx playwright test` depuis `frontend/e2e` (voir `INC-7-e2e.md` §3).

Limites de cette synthèse : les chiffres surefire, failsafe, vitest et JaCoCo sont ceux du rejeu du coordinateur et du verdict technique ; la suite E2E complète (25 min) n'a pas été rejouée en entier (journal relu, échantillons rejoués par la revue de cohérence).

## 4. Définition de « fini »

| Critère | Constat |
|---|---|
| Compile sans warning | Oui (§3) |
| Tests unitaires verts | Oui : 590/590, 231/231, 322/322 ; E2E 203/204 avec échec connu et inchangé (CA39 Chromium) |
| Couverture cœur >= 80 % | Oui : domain 98,58 %, service 99,23 % ; front `core` 99,28 % |
| Aucune règle métier dupliquée | Oui. E26 délègue à `AccountService.create` (`AccountCreationSingleSourceReviewTest`, 4 cas) ; `PseudoNormalizationSourceReviewTest` (inc. 6) reste vert ; aucune règle de course touchée |
| Erreurs jamais avalées | Oui. 400, 409 et 429 explicites ; aucun rejeu silencieux côté front (CA12 c, d) |

## 5. Écarts et bugs détectés

Aucun bug applicatif bloquant.

| ID | Test | Constat | Sévérité | Statut |
|---|---|---|---|---|
| PRE-1 | INC4-CA39 / INC6-CA7 (Chromium) | « Hors ligne » non visible, ligne 93, étape et message identiques à l'état de référence (LIM-E2E-1, R4-1, R5-3) | Connue, inchangée | Réserve ouverte, aucune régression |
| OBS-E2E-7A / COH7-1 | INC7-CA14 | Au chargement direct de `/scan`, `/connexion`, `/compte/connexion`, `/inscription`, l'en-tête affiche une frame avec « Se connecter » et « Créer un compte » avant correction (signal d'URL initialisé à `/`, `app.ts`). Sur `/scan`, deux liens « Se connecter » coexistent brièvement. L'état stable est conforme à RG4 ; l'attente `expect.poll` de l'audit masque l'état transitoire | Majeur selon la revue de cohérence, faible selon l'agent E2E | À arbitrer (§10) |
| Instabilités consignées | CA14 WebKit, CA31 Chromium, CA20 WebKit | Causes établies ou non reproduites, corrigées dans le test (CA14, CA20) ou non reproduites (CA31) ; 0 retry (`retries: 0`) ; exécution complète n° 2 écartée (environnement figé) | Information | Consigné dans `INC-7-e2e.md` §6 |

## 6. Tests modifiés, désactivés ou supprimés (règle 2 du workflow)

**Aucun test supprimé, désactivé (`@Disabled`, `skip`, `fixme`, `only`), ignoré ni mis en quarantaine.** `retries: 0` inchangé, aucun timeout modifié. **Aucune catégorie C** (confirmé par la revue de cohérence : le nombre de `test(` / `@Test` est inchangé dans les 12 fichiers existants adaptés, hors ajouts : +1 `PwaStaticResourcesIT`, +2 `admin-separation.spec.ts`).

Tous ces tests figent un attendu qui change par une règle de l'inc. 7 ; chacun reste au moins aussi strict. **À soumettre à l'agent fonctionnel pour accord écrit (règle 2).**

| Fichier / test | Adaptation | Cat. | Motif | Aussi strict ? |
|---|---|---|---|---|
| `admin-separation.spec.ts` (en-tête ; liste des pages) | « aucun lien `/connexion` » devient « seuls `/connexion` et `/inscription` s'ajoutent, aucun lien `/admin`, aucune mention Administration, liens conservés » ; `runner-login-page.ts` retiré de `arrayContaining` (fichier supprimé), exactement un écran « Créer un compte » ; 2 tests additifs | B | RG4 amende RG2 inc. 6 ; RG3 (spec §8) | Oui : contrôles admin conservés, liste fermée des cibles ajoutée, détecteur démontré sur source synthétique |
| `it/NoTestEndpointsIT` | E1 à E26 : 26 méthodes, 21 motifs (ajout de `/api/public/accounts`) | B | E26 créé par l'inc. 7 | Oui : égalité exacte conservée |
| `it/DeployConfigIT#ca40_nginxRateLimiting` | 3 `limit_req zone=` au lieu de 2 ; assertion sur `location = /api/public/accounts` (zone, `burst=5 nodelay`) | B | RG6, CA17 | Oui, plus strict (une assertion de plus, compte exact conservé) |
| `it/PwaStaticResourcesIT` | Valeur `/inscription` ajoutée ; test `HEAD` ajouté | A (additif) | CA21 | Oui |
| E2E `fixtures/admin-separation.ts` (`auditNoAdminLink`) | L'option `scanLoginLinkAllowed` devient `{ loginLinks, createAccountLinks }` : égalité exacte des liens attendus par écran ; contrôles `/admin` et « Administration » inchangés. Ajout d'une attente `expect.poll` de l'état stable (voir COH7-1) | B | RG4, CA14 | Oui, sauf l'attente de l'état stable, qui masque l'état transitoire (COH7-1) |
| E2E `inc6-ca2-no-admin-link.spec.ts` | Zone « compte » exacte par écran ; phase coureur : « Connecté : {pseudo} » exact, aucun des deux liens. `/inscription` (écran autonome) **n'est pas** ajoutée à ce fichier : elle est auditée dans `inc7-ca14` (rapport E2E corrigé, COH7-5) | B | RG4 | Oui, plus strict (31 `expect(` contre 28) |
| E2E `inc5-accessibility.spec.ts` (ligne 32) | h1 « Connexion coureur » devient « Connexion » ; axe inchangé | B | RG3 | Oui |
| E2E `ca21-routes.spec.ts` | + `/inscription` (« Créer un compte ») et `/compte/connexion` (« Connexion ») ; rechargement inclus | A (additif) | CA16, CA21 | Oui |
| E2E `ca24-login-roles.spec.ts` (2 tests) | `chooseStaffEntry` inséré avant « Nom d'utilisateur » | A | RG1 (sans `retour`, « Coureur » est présélectionnée) | Oui : assertions de stockage et `adminRequests` conservées |
| E2E `ca25-credentials-storage.spec.ts` (1er test) | idem | A | RG1 | Oui |
| E2E `ca36-admin-crud.spec.ts` (helper `loginAdmin`, sans `retour`) | idem | A | RG1 | Oui |
| E2E `ca41-accessibility.spec.ts` | `/connexion` (Coureur) conservé ; + entrée « Bénévole » et `/inscription` | A (additif) | CA22 | Oui |
| E2E `inc6-ca5-ca8-admin-access.spec.ts` (`loginAdminFromConnexion`) | `chooseStaffEntry` inséré ; h1 « Connexion » inchangé | A | RG1 | Oui |
| E2E `inc6-ca10-ca11-staff-login.spec.ts` | CA10 : ajout de l'aide « Vérifiez le type de compte choisi » ; CA11 : `auditNoAdminLink(..., ANONYMOUS_LOGIN_SCREEN_LINKS)` (aucun `/admin`, aucun « administrateur » conservés) | A | D1-bis ; suit l'évolution de l'utilitaire | Oui |
| E2E `fixtures/ui.ts` | Ajout de `chooseStaffEntry` et `submitLogin` (utilitaires) | A | RG1 | Aucun test touché |

Ajouts (pas des modifications) : `PublicAccountCreationSliceTest`, `AccountCreationWithoutRaceTest`, `AccountCreationSingleSourceReviewTest`, `PublicAccountCreationIT`, `UnknownPathUnauthorizedIT`, `login-entry.spec.ts`, `header-account-zone.spec.ts`, `inc7-login-routes-sources.spec.ts`, 5 fichiers E2E `inc7-*`, scripts `manual/inc7-evidence.mjs` et `manual/probe-header-flash.mjs`.

## 7. Tests instables ou en quarantaine

Aucun en quarantaine. Voir §5 (instabilités consignées, causes traitées). Toute nouvelle occurrence de CA31 ou CA38 WebKit est à consigner.

## 8. Revue de cohérence (renvoi) et réserves connues

**Revue de cohérence** : `INC-7-coherence.md` (2026-10-01) : **0 bloquant, 1 majeur (COH7-1), 6 mineurs (COH7-2 à COH7-7)**. Véracité des rapports : aucun écart entre résultat annoncé et constaté. **Mention : cette revue a été jouée par un agent générique, faute d'enregistrement de l'agent `revue-coherence-patrimoine` dans la session** (même consigne, mêmes contrôles, lecture seule). Limites de la revue : suite complète backend, JaCoCo et suite E2E complète non rejoués par elle.

COH7-4 et COH7-5 sont **corrigés par cette passe** (§9). COH7-1, COH7-2, COH7-3, COH7-6 et COH7-7 sont à arbitrer (§10).

Réserves connues à porter au verdict :

| Réserve | Contenu | Échéance |
|---|---|---|
| R4-1 / R5-3 (CA39 Chromium) | Échec préexistant (LIM-E2E-1), inchangé, même étape et même message | Avant tout déploiement sur le VPS (bloquante) |
| RT1 / R5-2 | Aucun PostgreSQL réel : tout est joué sur H2 en mode PostgreSQL, dont CA4 (10 appels concurrents) et CA1 (volet base) | Avant tout déploiement sur le VPS (bloquante) |
| R5-1 + 429 nginx de E26 (CA17 [manuel]) | Journaux de plus de 7 jours et 429 nginx réel à vérifier sur le VPS ; la limite de E26 (6 requêtes passent, la 7e reçoit 429) s'y ajoute | Avant la mise en service (bloquante) |
| R5-6 | Doublon du lien « J'ai déjà un compte » : non aggravé (un seul lien dans `account-creation-page.ts`) | Sans échéance |
| R6-1 | Motif `!/admin-*.js` : respecté (aucun nouvel écran admin ; CA20 : 21 fichiers JS, 0 `admin-*.js`, aucun texte RG5 chargé). Revérification complète reportée à l'inc. 8 (RG11) | Verdict de l'inc. 8 |
| R6-5 | CL5 (mise à jour du service worker) vérifié sous Chromium seulement ; CL11 de l'inc. 7 non rejoué sous WebKit | Recette d'avant déploiement |
| Discrimination de CA18, CA14, CA20 | Pas de mutation du code de production (interdit) ; CA14 ne peut pas détecter l'état transitoire (COH7-1) | Information |

## 9. Modifications du patrimoine de référence

- `docs/tests/PATRIMOINE.md`, section « Incrément 7 » uniquement (COH7-4) : mentions périmées corrigées. « E2E adaptés non encore écrits » remplacé par l'état réel (203/204) ; « écarts de testabilité à traiter » marqués levés (`core/login-entry.ts` et `core/header-account-zone.ts` existent, 35 et 27 tests verts) ; ligne de résultats unifiée (failsafe 231/0/0, rejeu du coordinateur 20:20-20:29, 8 min 11 s, au lieu de « 221 puis 231 ») ; notes « Écart de testabilité » et « build non reconstruit » mises à jour ; mention « lignes À ÉCRIRE » retirée. Les sections « Historique des validations » et « Écarts ouverts » (réservées à l'agent fonctionnel) n'ont pas été touchées.
- `docs/tests/rapports/INC-7-e2e.md`, §5 B2 (COH7-5) : la phrase fausse est corrigée (`/inscription` n'est pas ajoutée à `inc6-ca2`, elle est auditée dans `inc7-ca14`). Rien d'autre modifié.
- Aucun code de production ni test modifié par cette passe.

## 10. Points à arbitrer par l'agent fonctionnel

1. **COH7-1 / OBS-E2E-7A (majeur) : frame d'en-tête au chargement direct.** Défaut applicatif reproductible, état stable conforme. Choix : (a) corriger (initialiser le signal sur l'URL réelle du navigateur, ou ne rien afficher avant la première navigation) puis ajouter un test qui observe la première frame ; (b) accepter explicitement la limite et amender RG4 / CA14 (« état stable »). Dans les deux cas, noter que l'attente `expect.poll` de l'audit est un assouplissement de fait de la lecture. Le risque d'échec en mode strict pour les 15 fichiers qui cliquent « Se connecter » sur `/scan` est lié.
2. **COH7-2 : texte d'aide RG7 contre D10.** RG7 impose « Bénévole : votre compte est créé par l'organisateur. » ; D10 dit « ni « organisateur » ni « administrateur » ». Le code suit RG7 et le teste littéralement. Trancher et corriger la spec ; si D10 l'emporte : changer le texte, le test unitaire (`inc7-login-routes-sources.spec.ts`, l. 113) et la chaîne attendue par CA20.
3. **COH7-3 : CA12 (b), « une seule E21 ».** Le test constate une E21 de connexion automatique émise depuis `/inscription`, puis une seconde émise par la page `/compte` (liste), soit 2 au total. Confirmer la lecture « une seule E21 de connexion » et préciser CA12 (b).
4. **COH7-6 : fichiers parasites avant `git-publisher`.** `frontend/e2e/evidence-inc7/` (6 `.txt` non ignorés, dont `full-suite-run2-aborted-environment.txt`, 77 Ko, journal d'une exécution écartée ; PNG ignorés), `frontend/e2e/manual/inc7-evidence.mjs` et `manual/probe-header-flash.mjs`. Décider : garder les `.txt` utiles (au minimum `full-suite-final-203of204.txt`, `header-flash-probe.txt`, inventaire Cache Storage), écarter ou ignorer le journal de l'exécution abandonnée ; garder la sonde seulement si elle sert de base au test de COH7-1.
5. **Adaptations de catégorie B à approuver (règle 2)** : `admin-separation.spec.ts`, `NoTestEndpointsIT`, `DeployConfigIT.ca40`, `auditNoAdminLink`, `inc6-ca2`, `inc5-accessibility` (§6) ; et accord sur les catégories A (`ca21`, `ca24`, `ca25`, `ca36`, `ca41`, `inc6-ca5-ca8`, `inc6-ca10-ca11`, `PwaStaticResourcesIT`). Aucune C.
6. **COH7-7 : R6-2 et R6-3 de l'inc. 6.** Réserves « avant `git-publisher` » sans trace de traitement : `ca39` non modifié (assertion `h1, h2` sur `/admin`) ; `ca24` modifié seulement par `chooseStaffEntry` (boucle `adminRequests` toujours sur liste vide). Non aggravées. À clore ou à reporter explicitement.
7. Pas de compte rendu écrit du verdict technique avant ce rapport : il est cité dans `PATRIMOINE.md` et confirmé ici par les résultats du rejeu du coordinateur.

## 11. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : **GO sous réserves**
- **Date** : 2026-10-01
- **Vérification CA par CA** : CA1 à CA23 ont chacun au moins un test ACTIF qui les vérifie réellement (§2.1) ; résultats cités réels (rejeu du coordinateur 20:20-20:29 : surefire 590/0, failsafe 231/0, vitest 322/322, JaCoCo OK ; revue de cohérence : échantillons rejoués, journal E2E complet relu, 203/204 avec seul échec CA39 Chromium préexistant R4-1). Volets manuels non automatisés admis par la spec : CA17 [manuel], CL10. Aucun constat bloquant dans `INC-7-coherence.md` (0 bloquant, 1 majeur, 6 mineurs). CA24 est ajouté à la spec par ce verdict (voir décision 1) et n'a pas encore de test.

### Décisions écrites

1. **COH7-1 / OBS-E2E-7A : écart à RG4 / CA14, à corriger ; pas bloquant.** Constat vérifié dans `frontend/src/app/app.ts` : `toSignal(NavigationEnd…, { initialValue: this.router.url })` vaut `/` avant la première navigation, donc `headerAccountZone('/', …)` rend « Se connecter » et « Créer un compte » même sur `/scan`, `/connexion`, `/compte/connexion` et `/inscription`. C'est un défaut applicatif réel, pas une limite acceptable : RG4 pose « un seul lien « Se connecter » » sur `/scan` et la règle de non-doublon n'est pas conditionnée à un état stable. Sévérité : faible à l'usage (une frame, aucune fuite admin ni de rôle, aucun effet de sécurité ou de données), mais exposition réelle des 15 fichiers E2E qui cliquent « Se connecter » sur `/scan` en mode strict. D'où **GO sous réserves** et non NO-GO. L'attente `expect.poll` ajoutée à `auditNoAdminLink` est un assouplissement de fait de la lecture pour cet état transitoire : **accord limité aux audits d'état stable**, à condition que le test de première frame (CA24, ajouté à la spec) existe ; sans lui, l'audit ne prouve pas RG4 sur ce point. Action : voir réserve RES7-1.
2. **COH7-2 : texte d'aide conservé.** D10 vise le libellé de l'entrée « Bénévole ». Le texte d'aide « Bénévole : votre compte est créé par l'organisateur. » est conforme (« organisateur » est le terme du produit, seuls « administrateur » et « Administration » sont interdits). Spec corrigée (précision sous D10). Aucun changement de code ni de test.
3. **COH7-3 : lecture confirmée.** CA12 (b) = une seule E21 **de connexion automatique** émise depuis l'écran de création ; la E21 de chargement de `/compte` n'est pas une connexion. Spec corrigée. Le test (1 depuis l'écran, 2 au total) est conforme.
4. **Adaptations de tests existants (règle 2) : accord sur l'ensemble des lignes du §6**, aucune catégorie C, nombres de `test(`/`@Test` vérifiés inchangés hors ajouts. Catégorie B : `admin-separation.spec.ts` (accord ; contrôles admin et liste fermée des cibles conservés), `NoTestEndpointsIT` (accord ; égalité exacte E1 à E26), `DeployConfigIT#ca40` (accord ; 3 `limit_req` exact + `location =`), `inc6-ca2` (accord ; plus strict), `inc5-accessibility` (accord ; h1 « Connexion » par RG3), `auditNoAdminLink` (**accord conditionné** : l'attente `expect.poll` n'est admise que si CA24 existe, RES7-1). Catégorie A : `ca21`, `ca24`, `ca25`, `ca36`, `ca41`, `inc6-ca5-ca8`, `inc6-ca10-ca11`, `PwaStaticResourcesIT`, `fixtures/ui.ts` : accord (mécanique de parcours, assertions conservées).
5. **COH7-6 : fichiers parasites.** `frontend/e2e/evidence-inc7/` : **garder** `full-suite-final-203of204.txt`, `inc7-and-evolved-142-pass.txt`, `header-flash-probe.txt`, `07-cache-storage-ecrans-connexion-inscription.txt` et les autres `.txt` utiles de preuve (même précédent qu'`evidence-inc6/`) ; **supprimer** `full-suite-run2-aborted-environment.txt` (77 Ko, exécution écartée, sans valeur de preuve) ; PNG **ignorés** (règle `.gitignore` déjà ajoutée, la modification de `.gitignore` est **gardée**). `frontend/e2e/manual/inc7-evidence.mjs` : **garder** (régénération des preuves, précédent inc. 6). `frontend/e2e/manual/probe-header-flash.mjs` : **garder** (base du test CA24 b ; à supprimer seulement si CA24 l'intègre). `.angular/`, `test-results/`, `playwright-report/` : ignorés (déjà). Consigne au coordinateur avant `git-publisher`.
6. **COH7-7 : R6-2 et R6-3 closes.** Vérifié dans le code : `ca24-login-roles.spec.ts` l. 117 `expect(adminRequests).toEqual([])` ; `ca39-installability-offline.spec.ts` l. 138 `toHaveText('Page introuvable')` ; traitement tracé dans `INC-6-e2e.md` §5 bis et `PATRIMOINE.md` (levées le 2026-10-01). Elles ne figurent pas dans le diff de l'inc. 7 parce qu'elles sont déjà dans la base de la branche. Le constat de la revue venait d'une lecture du diff, pas d'un défaut. COH7-4 et COH7-5 : corrigés par la passe de synthèse (§9), clos.
7. **D-U1 : rappel, toujours non tranchée.** `handleInconsistency` renvoie `ex.getMessage()` dans le `detail` du 500. Décision de l'utilisateur requise avant le déploiement ; l'agent fonctionnel ne la tranche pas.

### Réserves (GO sous réserves)

| Réf. | Réserve | Action | Agent | Échéance |
|---|---|---|---|---|
| RES7-1 (COH7-1) | Première frame d'en-tête erronée au chargement direct (RG4) | (1) `developpeur` : n'afficher la zone « compte » qu'après la première `NavigationEnd`, ou l'initialiser sur l'URL réelle du navigateur (`Location.path()`), sans dupliquer la règle de `headerAccountZone` ; (2) `testeur` : écrire CA24 (a) front-unit **qui échoue avant** le correctif ; (3) `test-e2e-frontend` : écrire CA24 (b) (première frame, Chromium et WebKit), rejouer `inc7-ca14`, `inc6-ca2`, `ca26` et les fichiers cliquant « Se connecter » sur `/scan` ; (4) `testeur` : `clean verify` vert | developpeur, testeur, test-e2e-frontend | Avant `git-publisher` (MR de l'inc. 7) |
| RES7-2 (COH7-6) | Dépôt : suppression du journal abandonné, conservation des preuves utiles | Le coordinateur applique la liste de la décision 5 | coordinateur | Avant `git-publisher` |
| R4-1 / R5-3 | CA39 Chromium préexistant | Inchangée | developpeur / testeur | Avant tout déploiement sur le VPS (bloquante) |
| RT1 / R5-2 | Pas de PostgreSQL réel (dont CA4 concurrence et CA1 volet base) | Inchangée | exploitant / testeur | Avant tout déploiement sur le VPS (bloquante) |
| R5-1 + CA17 [manuel] | Journaux 7 jours et 429 nginx réel de E26 (6 passent, la 7e = 429) sur le VPS ; CL10 essai manuel | Exécution par l'exploitant | exploitant | Avant la mise en service (bloquante) |
| R6-1 | Motif `admin-*` : respecté ici (CA20) | Revérification complète à l'inc. 8 (RG11) | fonctionnel / test-e2e-frontend | Verdict de l'inc. 8 |
| R6-5 | CL5 / CL11 sous WebKit non rejoués | Recette d'avant déploiement | test-e2e-frontend | Avant déploiement |
| R5-6 | Doublon « J'ai déjà un compte » | Non aggravée | testeur | Sans échéance |
| D-U1 | Détail du 500 | Décision utilisateur | utilisateur | Avant le déploiement |

### Actions correctives exigées

Un correctif de **production** est exigé : RES7-1, `frontend/src/app/app.ts` (signal d'URL de l'en-tête), agent **developpeur**, avec test qui échoue avant (CA24). Pas de nouveau cycle complet de validation : le testeur rejoue la suite, `test-e2e-frontend` rejoue les parcours listés, et l'agent fonctionnel constate la levée dans PATRIMOINE ; la publication de la MR est conditionnée à cette levée.
