# Rapport de test : INC-6 (synthèse)

- **Date** : 2026-10-01
- **Agent auteur** : testeur (étape 6 de `/valider-increment 6` : rapport de synthèse)
- **Version / commit testé** : branche `feature/increment-6-separation-admin-public`, HEAD `80fcd8a` + working tree non commité (front de l'inc. 6, `ApiExceptionHandler` corrigé, `ngsw-config.json` et `angular.json`, tests du testeur, de l'agent d'intégration et de l'agent E2E)
- **Environnement** : Windows 11, JDK 25 (release 21), Maven, Node installé dans `frontend/node`, backend profil `test`, H2 2.4 en mode PostgreSQL, Playwright 1.55.1 (Chromium et WebKit). Aucun PostgreSQL réel (RT1).

Rapports sources :
- Intégration : `INC-6-integration.md`
- E2E : `INC-6-e2e.md`
- Cohérence : `INC-6-coherence.md` (voir mention en §8)
- Spec : `docs/specs/increment6.md`, révision 4b

**Verdict technique du testeur : OK** (compile sans warning, tests verts, couverture cœur >= 80 %, aucune règle métier dupliquée : §3 et §4). Ce n'est pas le verdict final : il appartient à l'agent fonctionnel (§11).

## 1. Périmètre

Spec `docs/specs/increment6.md` (révision 4b) : séparation admin / public (RG1 à RG7, CA1 à CA11) et réserves de l'inc. 5 portées par cet incrément (RG8 à RG10, CA12 à CA14 : R5-4, R5-5, R5-8). Aucun endpoint ne change ; un seul fichier de production backend modifié (`ApiExceptionHandler`, correctif RG10) ; le reste du changement est du front et de la configuration du service worker.

## 2. Couverture exigences <-> tests

Référentiel : `docs/tests/PATRIMOINE.md` (sections « Incrément 6 » : unitaires et slice, intégration, E2E). Chemins backend relatifs à `backend/src/test/java/fr/backyard/`, front unitaire à `frontend/src/app/core/`, E2E à `frontend/e2e/`.

| Exigence | Critère | Type | Tests | Résultat | Écart ? |
|---|---|---|---|---|---|
| RG1 | CA1 : matrice E1 à E25 x 4 profils (100 cas), `admin-test:mauvais` 401 (25 cas), 401 sans `WWW-Authenticate`, deux `detail` (révision 4b) | UNIT (slice) + INT | `api/AccessMatrixSliceTest` (126 : `ca1_accessMatrix`, `ca1_wrongCredentialsAreAlways401`, `ca1_theTableCoversE1ToE25Once`) ; `it/AccessMatrixIT` (126, chaîne réelle) | PASS | Non (écart `detail` tranché par la 4b) |
| RG2 | CA2 : aucun lien public vers l'admin, en-tête compris | E2E + UNIT (lecture de sources) | `tests/inc6-ca2-no-admin-link.spec.ts` (2 cas) ; `admin-separation.spec.ts` « RG2 » (4 tests + 4 synthétiques) | PASS | Non |
| RG3 | CA3 : `/admin/**` anonyme = « Page introuvable » | E2E | `tests/inc6-ca3-ca4-admin-not-found.spec.ts` (@INC6-CA3) | PASS | Non |
| RG3, RG4 | CA4 : coureur et SCANNER = « Page introuvable », `/scan` 200, `/compte` intact | E2E | idem (@INC6-CA4) | PASS | Non |
| RG3 | CA5 : accès ADMIN, rechargement = « Page introuvable » | E2E | `tests/inc6-ca5-ca8-admin-access.spec.ts` (@INC6-CA5) ; `admin-separation.spec.ts` « RG3 » (3 tests) | PASS | Non |
| RG5 | CA6 : blocs admin non préchargés ni en Cache Storage | E2E + UNIT (lecture du build) | `tests/inc6-ca6-admin-chunks-not-cached.spec.ts` ; `admin-separation.spec.ts` « RG5 » (3 tests) | PASS, discriminant (FAIL 2/2 sur le commit de départ) | Voir §9, point COH6-1 |
| RG5 | CA7 : scan hors ligne préservé | E2E | `tests/ca39-installability-offline.spec.ts` (tag `@INC6-CA7`) | WebKit PASS ; Chromium FAIL identique au commit de départ | Non (état de référence respecté) |
| CL4 | CA8 : 401 pendant une action admin = écran de connexion « Session expirée » | E2E | `tests/inc6-ca5-ca8-admin-access.spec.ts` (@INC6-CA8) | PASS | Non |
| RG1, CL5, CL6 | CA9 : non-régression, tests évolués, CL5 | INT + E2E + manuel | suite complète backend ; suite E2E ; `manual/cl5-sw-update.mjs` (CL5) | PASS | CL5 vérifié sous Chromium seulement |
| RG6, CL7 | CA10 : identifiants coureur sur la connexion staff | E2E seul (volet front-unit abandonné, 4b) | `tests/inc6-ca10-ca11-staff-login.spec.ts` (@INC6-CA10) ; backend : E19 x RUN = 401 dans la matrice | PASS | Non (volet front-unit retiré par la 4b) |
| RG3, RG7, CL8 | CA11 : connexion ADMIN depuis `/scan` | E2E + UNIT (lecture de sources) | `tests/inc6-ca10-ca11-staff-login.spec.ts` (@INC6-CA11) ; `admin-separation.spec.ts` « RG7 » (1 test) | PASS | Interprétation de « par son URL » à confirmer (COH6-4) |
| RG8, CL10 | CA12 : volet HTTP sur base migrée V1 -> V2 (R5-4) | INT | `it/V1ToV2LegacyDataHttpIT` (5 : `ca12_v2WasAppliedBeforeTheContextStarted`, `…boardsShowNeutralNamesOnMigratedData`, `…publicAndAdminRunnerViewsShowNeutralName`, `…manualDnfOnMigratedRunnerKeepsNeutralName`, `…databaseKeepsRaceDataAndHasNoNameLeft`) | PASS | Non (H2 seulement, R5-2) |
| RG9 | CA13 : normalisation du pseudo figée par un test de lecture de sources (R5-5) | UNIT | `config/PseudoNormalizationSourceReviewTest` (13, dont 5 sources synthétiques a à e) | PASS | Non |
| RG10, CL11 | CA14 : aucun pseudo dans les journaux d'erreur interne (R5-8) | UNIT + INT | `api/ApiExceptionHandlerLoggingTest` (5) ; `it/InternalErrorLogsIT` (3, chaîne HTTP réelle) | PASS ; discriminant (FAIL avant correctif : 5/5 en unitaire, 3/3 en IT) | Non |

Exigences sans test : **aucune**, hors CL5 dont la vérification est manuelle par construction (spec, CA9) et n'a été jouée que sous Chromium.

Nombres : 144 tests surefire ajoutés (AccessMatrixSliceTest 126, ApiExceptionHandlerLoggingTest 5, PseudoNormalizationSourceReviewTest 13) et 134 tests failsafe ajoutés (AccessMatrixIT 126, InternalErrorLogsIT 3, V1ToV2LegacyDataHttpIT 5) ; 15 tests Vitest ajoutés (`admin-separation.spec.ts`) ; 9 cas E2E x 2 navigateurs = 18. Les comptes sont ceux de la revue de cohérence (§3.1).

## 3. Résultats d'exécution (réels)

| Suite | Total | Passés | Échoués | Ignorés | Source |
|---|---|---|---|---|---|
| Backend unitaires + slice (surefire) | 558 | 558 | 0 | 0 | Rejeu du coordinateur, 2026-10-01 de 08:56 à 09:02, `mvn -B -f backend/pom.xml clean verify` : BUILD SUCCESS |
| Backend intégration `*IT` (failsafe) | 219 | 219 | 0 | 0 | Idem (85 avant l'inc. 6 + 134) ; total confirmé par la revue de cohérence en sommant `target/failsafe-reports/*.txt` |
| Front unitaires (Vitest, 24 fichiers) | 250 | 250 | 0 | 0 | Verdict technique du testeur ; 250/250 rejoué par la revue de cohérence |
| E2E, suite complète (Chromium + WebKit) | 134 | 133 | 1 (CA39 Chromium, attendu) | 0 | `INC-6-e2e.md` §3, log `evidence-inc6/full-suite-final.txt` (16,1 min) ; non rejouée en entier |
| E2E, rejeu partiel par la revue de cohérence (`inc6`, `ca21`, `ca24`, `ca25`) | 60 | 60 | 0 | 0 | `INC-6-coherence.md` §3.4 ; plus `ca39` : 1 échec Chromium identique au commit de départ, WebKit vert |

**Couverture** (tests unitaires uniquement, selon le verdict technique du testeur) : JaCoCo instructions, `domain` **96,8 %** (branches 92,2 %), `service` **99,1 %** (branches 98,9 %), seuil 80 % franchi (« All coverage checks have been met » dans le journal d'intégration). Front, `src/app/core` : **99,56 %** instructions, 98,18 % branches (seuil bloquant 80 %).

**Compilation sans warning** : build Maven, build Angular de production et compilation des tests sans ligne `[WARNING]` ni `[ERROR]` (journaux du rapport d'intégration et du verdict technique).

Commandes : `mvn -B -f backend/pom.xml clean verify` (unitaires + intégration + JaCoCo + build Angular + Vitest) ; `npx playwright test` depuis `frontend/e2e` (voir `INC-6-e2e.md` §3 pour les commandes de rejeu sur le commit de départ).

Limites de cette synthèse : les chiffres surefire et failsafe sont ceux du rejeu du coordinateur ; JaCoCo et Vitest viennent de mon verdict technique et n'ont pas été relancés pour la rédaction de ce rapport ; la suite E2E complète (16 min) n'a pas été rejouée depuis le 2026-10-01 matin.

## 4. Définition de « fini »

| Critère | Constat |
|---|---|
| Compile sans warning | Oui (§3) |
| Tests unitaires verts | Oui : 558/558, 219/219, 250/250 ; E2E 133/134 avec échec connu et inchangé |
| Couverture cœur >= 80 % | Oui : domain 96,8 %, service 99,1 % ; front `core` 99,56 % |
| Aucune règle métier dupliquée | Oui. La règle de normalisation du pseudo est figée par `PseudoNormalizationSourceReviewTest` (une seule occurrence de `toLowerCase`, dans `domain/Pseudo.java`) ; aucune règle de course n'est touchée par l'incrément |
| Erreurs jamais avalées | Oui. Le correctif RG10 n'avale rien : `logInternalError` écrit méthode, chemin et nom de classe, la réponse 500 est inchangée |

## 5. Écarts et bugs détectés

Aucun bug applicatif nouveau.

| ID | Test | Constat | Sévérité | Statut |
|---|---|---|---|---|
| PRE-1 | INC4-CA39 / INC6-CA7 (Chromium) | « Hors ligne » non visible, étape et message identiques au commit de départ (`evidence-inc6/baseline-ca39-start-commit.txt`) | Connue, inchangée (R4-1, R5-3, LIM-E2E-1) | Réserve ouverte, aucune régression |
| OBS-E2E-6A / COH6-1 | INC6-CA6 | Deux blocs partagés par les écrans admin (`race-form`, `account-admin-actions`) restent préchargés | Majeur (revue de cohérence) | À arbitrer (§10) |
| Observation CA36 WebKit | `ca36-admin-crud` (hors périmètre) | Échec isolé dans la première suite complète, non reproduit (3/3 puis 1/1), cause non établie, aucun test touché | Information | Consigné, non mis en quarantaine |

Historique des échecs de l'agent E2E : première suite complète 130/134 (CA39 Chromium attendu, CA25 Chromium et WebKit à adapter, CA36 WebKit isolé) ; après adaptation de CA25, 133/134.

## 6. Tests modifiés, désactivés ou supprimés (règle 2 du workflow)

**Aucun test supprimé, désactivé (`@Disabled`, `skip`, `fixme`, `only`), ignoré ni mis en quarantaine.** Aucun test backend existant modifié (`git diff main` vide sur `backend/src/test`). Aucun `*.spec.ts` existant modifié côté front unitaire. `retries: 0` inchangé.

Adaptations E2E de l'inc. 4 (4 fichiers, nombre de `test(` inchangé, aucune assertion retirée, aucune catégorie C selon `INC-6-e2e.md` §5). **À soumettre à l'agent fonctionnel pour accord écrit** :

| Fichier | Adaptation | Motif | Dans la liste de la spec (section 4) ? |
|---|---|---|---|
| `ca21-routes.spec.ts` (B1, B2) | « Page introuvable » à la place de « Connexion » sur `/admin` et `/admin/courses/{id}/qr` sans connexion ; lien « Retour à l'accueil » et adresse inchangée ajoutés ; titres renommés | RG3 | Oui |
| `ca24-login-roles.spec.ts` (B3, B4) | « Page introuvable » à la place de « Connexion » et d'« Accès réservé » ; `adminRequests === 0` conservé ; la boucle des statuts 403 de B4 ne porte plus que sur une liste vide (l'absence de requête admin est affirmée en CA4) ; titres renommés | RG3 | Oui |
| `ca25-credentials-storage.spec.ts` (B5) | Après rechargement sur `/admin` : « Page introuvable » à la place de « Connexion », assertion ajoutée (titre « Administration des courses » absent) ; assertions de stockage inchangées | RG3, CA5 | **Non** (COH6-2 : la liste de la section 4 et CA9 sont à compléter) |
| `ca39-installability-offline.spec.ts` (A1, A2, A3) | Branche WebKit : clic sur le lien « Administration » remplacé par `page.goto('/admin')` en ligne, puis par une navigation du routeur (`pushState` + `popstate`) hors ligne ; ordre des visites modifié ; tag `@INC6-CA7` ajouté ; branche Chromium inchangée | RG2 (le lien n'existe plus) | Oui (CA39), détail A1 et A2 à accepter (COH6-3) |

Réserve du testeur sur A2 : l'assertion hors ligne `h1, h2` visible est satisfaite par le `h1` « Scan » déjà affiché si la navigation du routeur n'a pas eu lieu ; elle ne prouve donc plus rien sur `/admin`. Renforcement possible : asserter le titre « Page introuvable » (COH6-3 b).

Ajouts (pas des modifications) : 6 classes de test backend, `admin-separation.spec.ts` et `core/testing/node-fs.d.ts` (Vitest), 5 fichiers E2E `inc6-*`, `fixtures/admin-separation.ts`, `manual/cl5-sw-update.mjs`, `manual/inc6-evidence.mjs`.

## 7. Tests instables ou en quarantaine

Aucun. CA36 WebKit : voir §5.

## 8. Revue de cohérence (renvoi) et réserves connues

**Revue de cohérence** : `INC-6-coherence.md` (2026-10-01) : **0 bloquant, 2 majeurs (COH6-1, COH6-2), 5 mineurs (COH6-3 à COH6-7)**. Véracité des rapports : aucun écart (failsafe 219/0/0 confirmé par somme des rapports, vitest 250/250, surefire ciblé 144/144, E2E 60/60 et CA39 identique au commit de départ). **Mention : cette revue a été jouée par un agent générique, faute d'enregistrement de l'agent `revue-coherence-patrimoine` dans la session** (même consigne, mêmes contrôles, lecture seule). Limites de la revue : suite complète backend, JaCoCo et suite E2E complète non rejoués par elle.

Réserves connues à porter au verdict :

| Réserve | Contenu | Échéance |
|---|---|---|
| R4-1 / R5-3 (CA39 Chromium) | Échec préexistant (LIM-E2E-1), inchangé, étape et message identiques au commit de départ | Avant tout déploiement sur le VPS (bloquante) |
| RT1 / R5-2 | Aucun PostgreSQL réel : tout est joué sur H2 en mode PostgreSQL (dont CA12 : migration V1 -> V2) | Avant tout déploiement sur le VPS (bloquante) |
| CA39 / CA40 [manuel] de l'inc. 5 (R5-1) | Journaux de plus de 7 jours et 429 nginx réel : à vérifier sur le VPS | Avant la mise en service (bloquante) |
| CL5 | Mise à jour du service worker : vérifiée manuellement sous **Chromium seulement** (`manual/cl5-sw-update.mjs`) ; WebKit non vérifié (Cache Storage non peuplé dans un contexte persistant) | Information |
| Discrimination de CA14 et CA6 | Rejeux sur l'ancien gestionnaire et sur l'ancien build consignés par les agents, non rejoués par la revue de cohérence | Information |
| CA12 | Non rejoué contre une V2 amputée de son `UPDATE` (limite consignée par l'agent d'intégration) ; la preuve repose sur la présence préalable des noms bruts | Information |
| COH6-6 | Les tests de lecture de build de `admin-separation.spec.ts` (RG5) dépendent d'un `dist/` à jour (Maven le régénère avant `npm test`) | Acceptable en l'état |

### État des réserves de l'inc. 5 portées par l'inc. 6

Tests présents, actifs et verts. **La levée est à prononcer par l'agent fonctionnel** ; elle n'est pas faite ici.

| Réserve | Critère | Tests | Résultat |
|---|---|---|---|
| R5-4 | CA12 (RG8) | `V1ToV2LegacyDataHttpIT` (5) | PASS (failsafe 219/0/0) |
| R5-5 | CA13 (RG9) | `PseudoNormalizationSourceReviewTest` (13) | PASS (surefire 558/0/0), discriminant démontré par les sources synthétiques a à d |
| R5-8 | CA14 (RG10) | `ApiExceptionHandlerLoggingTest` (5) + `InternalErrorLogsIT` (3) | PASS ; FAIL avant correctif sur l'ancien gestionnaire |

## 9. Modifications du patrimoine de référence

`docs/tests/PATRIMOINE.md`, section « Incrément 6 — tests unitaires et slice » : mentions périmées relevées par COH6-5 corrigées par cette passe (558 tests « 5 en échec » remplacé par le résultat réel 558/0/0, Vitest 250/0, failsafe 85 remplacé par 219, ligne `INC6-CA10 (volet front-unit)` passée de ÉCART à « couvert par l'E2E seul » conformément à la révision 4b, « Écart de spec consigné » de CA1 marqué tranché par la 4b, « E2E pas encore évolués » remplacé par l'état réel). Les sections « Historique des validations » et « Écarts ouverts » (réservées à l'agent fonctionnel) n'ont pas été touchées. Aucun code de production ni test modifié par cette passe.

## 10. Points à arbitrer par l'agent fonctionnel

1. **COH6-1 (majeur) : blocs partagés préchargés.** `chunk-kStRrTjQ.js` (`race-form`, « Durée de boucle ») et `chunk-CeQ4ybSt.js` (`account-admin-actions`, « Réinitialiser le mot de passe ») restent dans le groupe `app` et dans Cache Storage de tout visiteur. CA6 est vert à la lettre de la définition de RG5 (trois textes ; le code partagé avec les écrans publics n'est pas admin), mais ces deux composants ne sont importés que par des écrans admin. Choix : (a) accepter explicitement et amender RG5, ou (b) élargir RG5 et sortir ces blocs du préchargement (changement de production + extension de CA6). Fragilité à consigner dans les deux cas : l'exclusion repose sur le préfixe de nom de fichier `admin-*` (`!/admin-*.js`).
2. **COH6-2 (majeur) : adaptation de `ca25`** hors liste de la section 4 (B5). Accord écrit demandé ; sans accord, l'écart deviendrait bloquant. Compléter la liste de la section 4 ou CA9.
3. **COH6-3 (mineur) : adaptations A1, A2, B1 à B4** (voir §6), dont la boucle vide de B4 et l'assertion devenue faible de A2 ; reclasser éventuellement en catégorie C.
4. **COH6-4 (mineur) : « par son URL ».** CA11 et CL8 disent que `/admin` « ouverte par son URL » liste les courses après une connexion ADMIN, alors que CA5 impose des identifiants ADMIN en mémoire seulement (un `goto` complet les perd). Le test joue ce parcours comme une navigation du routeur (historique, CL9). Confirmer l'interprétation et corriger la rédaction de CA11.
5. **COH6-7 (mineur) : fichiers parasites ou non décidés** avant publication : `.angular/` (non ignoré), `frontend/e2e/evidence-inc6/` (825 Ko de PNG, journaux, traces), `frontend/e2e/manual/` (2 scripts d'outillage hors `testDir`), `frontend/src/app/core/testing/node-fs.d.ts`. Décision demandée : ignorer, ne pas commiter ou conserver.
6. Lever, ou non, R5-4, R5-5 et R5-8 (§8).

## 11. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : **GO SOUS RÉSERVES**
- **Date** : 2026-10-01

### Vérifications faites par l'agent fonctionnel

- Spec 4b relue en entier, rapports de synthèse et de cohérence relus, `PATRIMOINE.md` (sections inc. 6, écarts, historique) relu.
- Code relu : `frontend/ngsw-config.json` (groupe `app` = `/*.js` moins `!/admin-*.js`, groupe `admin` en `lazy`), `angular.json` (`namedChunks: true` en production, condition du motif de nom), `app.routes.ts` (`canMatch: matchAdminOnly`, ADMIN seulement, repli sur `**`, aucune redirection vers `/connexion`), `admin-shell.ts` (aucun texte « Accès réservé », rien rendu si le rôle disparaît), `race-form.ts` et `account-admin-actions.ts` (importés uniquement par `admin-races-page`, `admin-race-page`, `admin-accounts-page` : confirmé), `ca24` et `ca39` (lignes citées), `ApiExceptionHandler` (le `detail` du 500 est toujours `ex.getMessage()`, ligne 120 : exclusion de spec respectée).
- Chiffres : je n'ai pas rejoué les suites ; je retiens les exécutions réelles citées (coordinateur 2026-10-01 : surefire 558/0, failsafe 219/0, BUILD SUCCESS ; revue de cohérence : failsafe sommé 219/0/0, Vitest 250/250, surefire ciblé 144/144, E2E 60/60, CA39 Chromium identique au commit de départ ; suite E2E complète 133/134 consignée dans `full-suite-final.txt`).
- Couverture CA par CA : CA1 (AccessMatrixSliceTest + AccessMatrixIT), CA2 à CA8, CA10, CA11 (E2E `inc6-*`, Chromium et WebKit), CA6 démontré discriminant sur le build de départ, CA7 (CA39 : WebKit vert, Chromium état de référence), CA9 (non-régression, aucun test backend existant modifié), CA12 (V1ToV2LegacyDataHttpIT), CA13 (PseudoNormalizationSourceReviewTest), CA14 (unitaire + IT, discriminant 5/5 et 3/3). Chaque CA a au moins un test ACTIF. Aucune règle RG1 à RG10 sans CA. Constat bloquant de la revue de cohérence : aucun.
- Aucun test supprimé, désactivé ou en quarantaine ; `retries: 0` inchangé.

### Arbitrages du §10

1. **COH6-1 / RG5 : ACCEPTÉ comme limite assumée, sans correctif.** Les fichiers de route admin (`admin-*.js`) sont bien exclus du préchargement et CA6 est discriminant. `race-form` et `account-admin-actions` ne sont importés que par des écrans admin mais, partagés entre plusieurs d'entre eux, ils tombent dans des `chunk-<hash>.js` non préfixés ; les exclure imposerait de changer la production (fusion dans un fichier `admin-*`) pour des libellés de formulaire sans donnée, alors que l'API reste la barrière et que le nom des routes admin est déjà lisible dans le code principal (limite assumée, spec §1, PO3 et PO5). Spec révisée (révision 5) : RG5 définit les blocs admin comme les fichiers de route `admin-*` et nomme ces deux composants comme limite, avec la fragilité du motif de nom. Réserve R6-1 pour l'inc. 7.
2. **COH6-2 : ACCORD sur `ca25`** (B5) : l'attendu découle de RG3 et CA5, l'assertion ajoutée est plus stricte, les assertions de stockage sont inchangées. La section 4 de la spec est complétée (révision 5) ; CA9 cite désormais ca21, ca24, ca25, ca39.
3. **COH6-3 : accord ligne à ligne.**
   - `ca21` B1, B2 (« Page introuvable », lien d'accueil et adresse ajoutés) : accord.
   - `ca24` B3 (`adminRequests === 0` conservé) : accord. B4 : accord sur le remplacement de « Accès réservé » par « Page introuvable », **mais la boucle 403 devenue vide ne prouve plus rien dans ce test** : accord conditionné à l'ajout de `expect(adminRequests).toEqual([])` dans ce même test (réserve R6-2). Les titres renommés : accord.
   - `ca39` A1 (`page.goto('/admin')` en ligne) et A3 (tag `@INC6-CA7`, branche Chromium inchangée) : accord ; l'ordre des visites WebKit modifié : accord (motif LIM-E2E-2, assertions hors ligne de `/scan` intactes).
   - `ca39` A2 (navigation du routeur hors ligne puis `h1, h2` visible) : **accord sur la technique, refus de l'assertion comme preuve** : un `h1` « Scan » déjà affiché la satisfait. Le test doit asserter le `h1` « Page introuvable » (réserve R6-3). Ce n'est pas un assouplissement de la couverture de CA7 (la partie hors ligne de `/scan` est vérifiée avant), d'où pas de NO-GO.
   - Aucune adaptation de catégorie C (assertion retirée) : confirmé.
4. **COH6-4 : interprétation CONFIRMÉE.** « Par son URL » = navigation du routeur sans rechargement ; un rechargement perd les identifiants ADMIN (CA5, CL9). CA11 et CL8 réécrits dans la spec (révision 5).
5. **COH6-7 : fichiers parasites. Liste pour le coordinateur, avant `git-publisher` :**
   - `.angular/` (racine) : **ignorer** (ajouter `.angular/` au `.gitignore` racine), ne pas commiter.
   - `frontend/e2e/evidence-inc6/` : **commiter seulement les `.txt`** (`baseline-ca39-start-commit.txt`, `baseline-ca6-start-commit.txt`, `cl5-phase1/2/3.txt`, `full-suite-final.txt`, `full-suite-run1-4fail.txt`, `06-cache-storage-anonyme.txt` : preuves citées par les rapports, quelques Ko) ; **ignorer** les `*.png`, le sous-dossier `ca39-chromium-failure/` (trace.zip, capture) : ajouter `frontend/e2e/evidence-inc6/**/*.png` et `frontend/e2e/evidence-inc6/ca39-chromium-failure/` au `.gitignore`. Les rapports renvoient aux captures : le coordinateur ajoute une ligne « captures non versionnées, régénérables par `manual/inc6-evidence.mjs` » dans `INC-6-e2e.md`.
   - `frontend/e2e/manual/` (2 scripts) : **garder et commiter** : ils sont la seule preuve rejouable de CL5 (vérification manuelle exigée par CA9) et des captures.
   - `frontend/src/app/core/testing/node-fs.d.ts` : **garder** (déclaration de types des tests de lecture de sources, nécessaire à la compilation de Vitest).
6. **R5-4, R5-5, R5-8 : LEVÉES.** CA12 (`V1ToV2LegacyDataHttpIT`, 5/5, base migrée V1 vers V2 déjà peuplée, noms bruts présents avant V2), CA13 (`PseudoNormalizationSourceReviewTest`, 13/13, discriminant par les sources synthétiques a à d), CA14 (`ApiExceptionHandlerLoggingTest` 5/5 + `InternalErrorLogsIT` 3/3, FAIL avant correctif, ligne ERROR exploitable) sont actifs, verts dans l'exécution réelle du 2026-10-01 et consignés. Limites conservées ailleurs : CA12 reste sur H2 (R5-2/RT1) ; la discrimination de CA12 sur une V2 amputée n'est pas rejouée (information).
7. **Décision UTILISATEUR laissée ouverte (non tranchée par l'agent fonctionnel).** `ApiExceptionHandler.handleInconsistency` renvoie `ex.getMessage()` dans le `detail` du **corps** du 500 (ligne 120), alors que RG10 ne protège que les **journaux**. Un message d'`IllegalStateException` ou `IllegalArgumentException` pourrait donc contenir un pseudo et le renvoyer au client (les messages actuels ne contiennent pas de pseudo d'après les rapports, mais rien ne le fige). L'exclusion « corps des réponses 500 » est dans la spec (§2) : la question est de savoir si l'utilisateur veut un `detail` générique pour ces 500. À poser à l'utilisateur ; sans décision, rien ne change.

### Réserves (chacune avec action et échéance)

| ID | Réserve | Action | Agent | Échéance |
|---|---|---|---|---|
| R6-1 | Fragilité du motif `!/admin-*.js` et blocs partagés préchargés (COH6-1) | Chaque nouvel écran admin de l'inc. 7 est un fichier de route `admin-*` ; le rapport de l'inc. 7 liste les blocs partagés préchargés qu'il introduit ; CA6 est rejoué | `developpeur`, `test-e2e-frontend` | Verdict de l'inc. 7 |
| R6-2 | Boucle 403 vide dans `ca24` (B4) | Ajouter `expect(adminRequests).toEqual([])` dans le test « connexion scanner (mémorisée) puis `/admin` » ; rejouer `ca24` sur Chromium et WebKit | `test-e2e-frontend` | Avant `git-publisher` (MR de l'inc. 6) |
| R6-3 | Assertion `h1, h2` faible dans `ca39` WebKit (A2) | Remplacer par l'assertion du `h1` « Page introuvable » après la navigation du routeur hors ligne ; rejouer `ca39` WebKit (doit rester vert) ; si le test échoue, remonter comme bug, ne pas l'élargir | `test-e2e-frontend` | Avant `git-publisher` |
| R6-4 | Fichiers parasites (arbitrage 5) | Mettre à jour `.gitignore` et exclure les PNG et traces ; vérifier `git status` avant publication | coordinateur / `git-publisher` | Avant `git-publisher` |
| R6-5 | CL5 vérifié sous Chromium seulement (Cache Storage non peuplé en contexte persistant WebKit) | Information ; à refaire sur la recette VPS (D4) | `test-e2e-frontend` | Recette d'avant déploiement |
| R4-1 / R5-3 | CA39 sous Chromium (LIM-E2E-1), inchangé (CA7 respecté) | Voir R4-1 | `test-e2e-frontend` | Avant tout déploiement sur le VPS (bloquante) |
| RT1 / R5-2 | Aucun PostgreSQL réel (dont CA12) | Voir RT1 | `test-integration-backend` | Avant tout déploiement sur le VPS (bloquante) |
| R5-1 | CA39 / CA40 manuels de l'inc. 5 (journaux 7 jours, 429 nginx) | Voir inc. 5 | exploitant | Avant la mise en service (bloquante) |
| R5-6 | Doublon du lien « J'ai déjà un compte » | Voir inc. 5 | `developpeur` | Sans échéance |
| D-U1 | Décision utilisateur sur le `detail` du 500 (arbitrage 7) | Poser la question à l'utilisateur ; si oui, amender la spec (nouvelle RG) puis corriger | utilisateur, puis `fonctionnel` | Avant le déploiement (non bloquante pour la MR) |

R6-2 et R6-3 sont des renforcements de tests, sans changement de production : leur exécution et la vérification de leur résultat par le testeur sont des conditions de publication de la MR, pas un nouveau cycle de validation complet.

### Actions correctives exigées

Aucune action corrective de production. Pas de NO-GO : aucun constat bloquant, tous les CA couverts par un test actif, résultats réels. Les renforcements R6-2 et R6-3 et le nettoyage R6-4 sont à faire avant la publication (voir réserves).
