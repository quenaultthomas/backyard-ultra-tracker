# Rapport de test : INC-6 (e2e)

- **Date** : 2026-10-01
- **Agent auteur** : test-e2e-frontend
- **Version / commit testé** : branche `feature/increment-6-separation-admin-public`, HEAD `80fcd8a` (commit de départ) + working tree non commité (front de l'inc. 6, `ApiExceptionHandler` corrigé, tests du testeur et de l'agent d'intégration, tests E2E de cette passe). Aucune modification du code de production par cet agent. Verdict d'intégration : `INC-6-integration.md` (étape IT sans échec).
- **Environnement** :
  - Backend : `mvn -B -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=test -Dspring-boot.run.useTestClasspath=true` (reconstruit la PWA de production depuis `frontend/src`), profil `test`, H2 en mode PostgreSQL en mémoire, comptes `admin-test` et `scanner-test`, clôture de yard à 500 ms. Variables `SPRING_DATASOURCE_*` et `BACKYARD_*` identiques à `INC-4-e2e.md`, section 3 et `INC-5-e2e.md`. Lancé avec `run_in_background` (jamais `&`), arrêté en fin de session (ports 8080 et 8081 vérifiés libres).
  - Front : PWA de production servie par Spring Boot sur `http://localhost:8080` (service worker actif sauf dans les tests qui le bloquent, LIM-E2E-1).
  - Outil : Playwright 1.55.1, `@axe-core/playwright` 4.13.0, Chromium et WebKit. **Aucune nouvelle dépendance.**
  - Commit de départ rejoué dans un `git worktree` temporaire (`C:\b6`, retiré en fin de session), backend sur le port 8081 : sert la référence de CA6 (discrimination), de CA7 (état de référence de CA39) et de CL5.

## 1. Périmètre

CA `[E2E]` de `docs/specs/increment6.md` (révision 4b) : **CA2, CA3, CA4, CA5, CA6, CA7, CA8, CA10, CA11**, plus le volet E2E de CA9 (suite verte, tests évolués, CL5 manuel). Hors périmètre de cet agent : CA1, CA12 (IT), CA13, CA14 (unit), voir `INC-6-integration.md`.

Tags : `@INC-6` et `@INC6-CA<k>`. Sous-ensemble `@smoke` : CA2, CA3, CA5, CA10, CA11 (parcours critiques, quelques secondes chacun).

## 2. Couverture exigences ↔ tests

| Exigence | Critère d'acceptation | Tests | Résultat (Chromium / WebKit) | Écart ? |
|---|---|---|---|---|
| INC6-CA2 | Aucun lien public vers l'admin, en-tête compris (RG2, RG7, CL1) | `e2e/tests/inc6-ca2-no-admin-link.spec.ts` (2 cas) : audit (liens `a[href]`/`area[href]` vers `/admin`, vers `/connexion` sauf « Se connecter » de `/scan`, actions de formulaire, texte visible, nom des liens et boutons) de `/`, `/courses/{id}`, `/coureurs/{id}`, `/inscription/{id}`, `/compte`, `/compte/connexion`, `/scan`, `/connexion` et de deux confirmations d'inscription (E3 par l'interface, E20), en anonyme puis connecté `Lievre-{run}` ; liens Courses, Mes inscriptions, Scan présents, les deux « Scan » ciblent `/scan`, le clic ouvre `/scan` | PASS / PASS | Non |
| INC6-CA3 | `/admin/**` pour un anonyme (RG3, CL2) | `inc6-ca3-ca4-admin-not-found.spec.ts` « @INC6-CA3 » : 5 URL (`/admin`, `/admin/courses/{id}`, `.../qr`, `/admin/comptes`, `/admin/inconnu`) : « Page introuvable », lien « Retour à l'accueil », aucun champ de mot de passe, adresse inchangée, 0 requête `/api/admin/**`, 0 bloc `admin-*.js` | PASS / PASS | Non |
| INC6-CA4 | `/admin/**` pour un coureur et un SCANNER (RG3, RG4, CL3) | `inc6-ca3-ca4-admin-not-found.spec.ts` « @INC6-CA4 » : coureur connecté puis SCANNER (même contexte) : « Page introuvable », ni « Accès réservé » ni « Se connecter en administrateur », 0 requête admin ; `/scan` capture reçue avec 200 ; `/compte` garde les inscriptions | PASS / PASS | Non |
| INC6-CA5 | Accès ADMIN (RG3, CL9) | `inc6-ca5-ca8-admin-access.spec.ts` « @INC6-CA5 » : `/connexion` directe, `/admin` liste les courses, `/admin/courses/{id}` liste les coureurs, rechargement = « Page introuvable » (même adresse), nouvelle connexion rend l'accès | PASS / PASS | Non |
| INC6-CA6 | Code admin non préchargé ni mis en cache (RG5) | `inc6-ca6-admin-chunks-not-cached.spec.ts` (1 cas, **build de production, service worker actif, Cache Storage réel**) : blocs admin repérés dans `frontend/dist/backyard-pwa/browser` par les textes de RG5 (liste non vide exigée, chacun servi par le backend) ; attente de la fin du préchargement (tous les fichiers `prefetch` de `ngsw.json` présents dans Cache Storage) ; parcours anonyme `/`, `/courses/{id}`, `/inscription/{id}`, `/scan`, `/admin` ; aucun bloc admin ni dans le réseau ni dans Cache Storage ; après connexion ADMIN et ouverture de `/admin`, au moins un bloc admin chargé | PASS / PASS ; **discriminant : FAIL 2/2 sur le build du commit de départ** (§4, preuve) | Non (voir OBS-E2E-6A) |
| INC6-CA7 | Scan hors ligne préservé (RG5) | `ca39-installability-offline.spec.ts` (même test qu'INC4-CA39, tag `@INC6-CA7` ajouté, évolué en §5) | WebKit : PASS ; Chromium : FAIL attendu, **identique au commit de départ** (étape et message, §4) | Non (état de référence respecté) |
| INC6-CA8 | Identifiants ADMIN refusés pendant une action (CL4) | `inc6-ca5-ca8-admin-access.spec.ts` « @INC6-CA8 » : ADMIN sur `/admin/courses/{id}`, E13 remplacée par un 401 après « Modifier » puis « Enregistrer » : titre « Connexion », « Session expirée… », pas « Page introuvable », `retour` = la course | PASS / PASS | Non |
| INC6-CA10 | Identifiants coureur sur la connexion staff (RG6, CL7) | `inc6-ca10-ca11-staff-login.spec.ts` « @INC6-CA10 » : connecté `Lievre-{run}` (mémorisé), « Scan » depuis l'accueil, « Se connecter », saisie des identifiants coureur : E19 401, « Identifiants invalides », emplacement `scanner` d'IndexedDB vide, emplacement `runner` inchangé (identifiants identiques, seule l'échéance glissante peut avancer), ni mot de passe ni encodage Basic ailleurs que dans l'emplacement coureur (localStorage, sessionStorage, cookies, IndexedDB, Cache Storage), `/scan` redemande une connexion, `/compte` intact (navigation interne puis complète) | PASS / PASS | Non |
| INC6-CA11 | Connexion ADMIN depuis `/scan` (RG3, RG7, CL8) | `inc6-ca10-ca11-staff-login.spec.ts` « @INC6-CA11 » : écran de connexion sans « Administration » ni « administrateur » ni lien `/admin` (en-tête compris), connexion `admin-test`, retour sur `/scan` (adresse et titre), `/scan` sans mention ni lien, capture 200, `/admin` par son URL liste les courses | PASS / PASS | Non (interprétation de « par son URL », §5 et §7) |
| INC6-CA9 (volet E2E) | Non-régression, tests évolués, CL5 | Suite complète (§3) ; adaptations (§5) ; CL5 par `e2e/manual/cl5-sw-update.mjs` (§3) | 133/134 (Chromium + WebKit) | Non (CA39 Chromium attendu) |

Exigences E2E sans test : **aucune**.

## 3. Résultats d'exécution (réels)

| Suite | Total | Passés | Échoués | Ignorés | Durée |
|---|---|---|---|---|---|
| Tests de l'incrément (9 cas x 2 navigateurs = 18) | 18 | 18 | 0 | 0 | 1,1 min (Chromium + WebKit, hors correctifs de mise au point) |
| Suite complète finale `npx playwright test` (Chromium + WebKit, 116 anciens + 18 nouveaux) | 134 | 133 | 1 (CA39 Chromium, attendu, §4) | 0 | 16,1 min |

Historique honnête des exécutions :
1. Tests de l'incrément, première exécution : 16/18. Deux échecs, tous deux des défauts de test, corrigés : CA10 Chromium (mode strict : « Non connecté » correspond à deux éléments, l'indicateur et le bandeau) et CA10 WebKit (l'échéance glissante `expiresAt` de l'emplacement coureur avance entre deux lectures : comparaison faite hors `expiresAt`, avec `expiresAt` après >= avant). Puis CA10 et les tests évolués : 37/38 (CA39 Chromium attendu).
2. Première suite complète : 130/134, log `frontend/e2e/evidence-inc6/full-suite-run1-4fail.txt`. Échecs : CA39 Chromium (attendu) ; **CA25 Chromium et WebKit** (test de l'inc. 4 non listé dans la spec, voir §5 adaptation B5) ; **CA36 WebKit** (échec isolé, §6).
3. Suite complète finale après adaptation de CA25 : **133/134**, log `frontend/e2e/evidence-inc6/full-suite-final.txt`. Seul échec : CA39 Chromium.

**CA7, état de référence de CA39 sous Chromium (méthode demandée).** Le test d'origine (copie du fichier du commit de départ, fixtures identiques) a été joué sur un backend construit depuis `80fcd8a` (port 8081, avant tout changement de mes tests) : Chromium FAIL, WebKit PASS, log `evidence-inc6/baseline-ca39-start-commit.txt`. Après les changements de l'inc. 6, le test évolué donne **la même étape et le même message** :

| | Commit de départ `80fcd8a` | Inc. 6 (working tree) |
|---|---|---|
| Étape en échec | `expect(page.getByText('Hors ligne', { exact: false })).toBeVisible({ timeout: 15_000 })`, ligne 93, dernière assertion de la branche Chromium | idem, ligne 93 |
| Message | `Error: expect(locator).toBeVisible() failed ; Locator: getByText('Hors ligne') ; Expected: visible ; Received: <element(s) not found> ; Timeout: 15000ms` | identique, mot pour mot |
| Étapes antérieures | `fromServiceWorker() === true` sur `/`, `/scan`, `/admin`, `/courses/{id}` et mise en file hors ligne de `/scan` : passées | idem (`/admin` est maintenant « Page introuvable », un `h1` visible) |

C'est LIM-E2E-1 inchangée (E4 reçoit un 200 malgré `setOffline(true)` avec le service worker actif ; R4-1 et R5-3). Aucun autre échec, aucun échec plus tôt dans le parcours. Preuves : `evidence-inc6/ca39-chromium-failure/` (capture, contexte d'erreur, trace).

**CA6, discrimination.** Le même test joué sur le build du commit de départ (port 8081, `E2E_BUILD_DIR` pointant sur le build de départ) échoue sur les deux navigateurs : `blocs admin dans Cache Storage` reçoit `["http://localhost:8081/chunk-_3R_h-ZL.js", "http://localhost:8081/chunk-oSE9bB_J.js"]` (les deux blocs admin repérés par leurs textes), et l'assertion souple « Page introuvable » échoue aussi (`Connexion` affiché, ancien comportement). Log : `evidence-inc6/baseline-ca6-start-commit.txt`. Sur l'inc. 6 : PASS, Cache Storage d'un visiteur anonyme dans `evidence-inc6/06-cache-storage-anonyme.txt` (21 fichiers JS, aucun `admin-*.js` ni bloc admin ; le groupe `admin` de `ngsw.json` est en `installMode: lazy`).

**CL5 (CA9) : vérification manuelle outillée, Chromium** (`e2e/manual/cl5-sw-update.mjs`, profil persistant, logs `evidence-inc6/cl5-phase1.txt`, `cl5-phase2.txt`, `cl5-phase3.txt`) :
1. Phase 1 sur l'ancienne version (commit de départ, port 8081) : service worker installé, 26 fichiers JS en cache, en-tête « Courses, Mes inscriptions, Scan, Administration », 2 liens `/admin` sur l'accueil.
2. Arrêt de l'ancien backend, démarrage du backend de l'inc. 6 **sur le même port**, même profil de navigateur. Phase 2 : l'ancienne PWA s'affiche encore avec « Administration » dans l'en-tête, puis le bandeau « Nouvelle version disponible » apparaît (RG46) ; après « Mettre à jour » : en-tête « Courses, Mes inscriptions, Scan », 0 lien `/admin`, texte « Administration » absent.
3. Immédiatement après la mise à jour, Cache Storage contient encore les deux versions (anciens blocs admin compris, dont `chunk-_3R_h-ZL.js` et `chunk-oSE9bB_J.js`). Phase 3, à la réouverture : seuls les caches de la nouvelle version subsistent (`ngsw:/:319ef9...:assets:app`, `assets:admin`, `assets:assets`), les anciens blocs admin sont retirés. Conforme à CL5 (« retirés avec l'ancienne version »), avec ce délai.
WebKit non vérifié pour CL5 : le contexte persistant de Playwright ne remplit pas Cache Storage sous WebKit dans cet environnement (0 fichier en cache après 2 minutes), voir §7.

**Preuves visuelles** (`frontend/e2e/evidence-inc6/`, produites par `e2e/manual/inc6-evidence.mjs` sur la PWA servie) : `01-accueil-sans-lien-administration.png`, `02-admin-anonyme-page-introuvable.png`, `03-connexion-depuis-scan-sans-mention-admin.png`, `04-scan-apres-connexion-admin-sans-lien-admin.png`, `05-admin-par-url-apres-connexion-admin.png`, `06-cache-storage-anonyme.txt`. Les tests réussis ne conservent ni capture ni vidéo (configuration `retain-on-failure`).

Commandes exécutées (depuis `frontend/e2e`) : `npx playwright test tests/inc6 --reporter=list` ; `npx playwright test tests/inc6-ca10 tests/ca21 tests/ca24 tests/ca39` ; `npx playwright test tests/ca36 --project=webkit --repeat-each=3` ; `npx playwright test --reporter=list` (suite complète, deux fois) ; avec `E2E_BASE_URL=http://localhost:8081 E2E_BUILD_DIR=C:/b6/frontend/dist/backyard-pwa/browser` pour les rejeux sur le commit de départ ; `node manual/cl5-sw-update.mjs phase1|phase2|phase3 chromium <profil> http://localhost:8081` ; `node manual/inc6-evidence.mjs http://localhost:8080 evidence-inc6`.

## 4. Échecs et bugs détectés

Aucun bug applicatif nouveau.

| ID | Test | Attendu | Obtenu | Reproduction | Sévérité | Preuve |
|---|---|---|---|---|---|---|
| PRE-1 | INC4-CA39 / INC6-CA7 (Chromium) | « Hors ligne » visible | Échec identique au commit de départ (tableau §3) | Suite complète ou `npx playwright test tests/ca39 --project=chromium` | Connue (LIM-E2E-1, R4-1, R5-3), inchangée | `evidence-inc6/baseline-ca39-start-commit.txt`, `evidence-inc6/ca39-chromium-failure/`, `evidence-inc6/full-suite-final.txt` |
| OBS-E2E-6A | INC6-CA6 | Aucun code des écrans admin préchargé (RG5) | Le code des écrans admin proprement dits (`admin-*.js`) n'est plus préchargé. Mais deux blocs partagés par les écrans admin restent dans le groupe `app` et dans Cache Storage de tout visiteur : `chunk-kStRrTjQ.js` (formulaire de course, texte « Durée de boucle ») et `chunk-CeQ4ybSt.js` (actions de compte, « Réinitialiser le mot de passe »). Le bloc partagé `chunk-D_bE_M-W.js` porte aussi les chemins `/api/admin/**`, et `main-*.js` le titre de route « Administration — Backyard Ultra Tracker » | `grep` sur `frontend/dist/backyard-pwa/browser` ; `evidence-inc6/06-cache-storage-anonyme.txt` | Information : conforme à la définition de RG5 (« blocs admin » = les fichiers repérés par les trois textes ; le code partagé n'est pas admin) et à la limite assumée (« nom des routes dans le code principal »). Signalé car le testeur l'avait déjà noté ; l'agent fonctionnel arbitre | Même fichier |
| OBS-E2E-6B | INC6-CA6 (outillage) | Journal réseau complet | `context.on('request')` de Playwright ne rapporte pas les requêtes émises par le service worker : sur le commit de départ, l'assertion « journal réseau » passe alors que le préchargement a bien eu lieu. C'est Cache Storage qui est décisif ; le journal réseau reste vérifié (blocs demandés par la page après connexion ADMIN) | Rejeu du test sur le commit de départ | Information (limite de l'outil) | `evidence-inc6/baseline-ca6-start-commit.txt` |

## 5. Modifications du patrimoine existant (démarche N1 : soumises à l'accord de l'agent fonctionnel)

**Aucun test supprimé, désactivé (`skip`, `fixme`), ignoré ni assoupli.** Toutes les assertions de remplacement sont des égalités exactes ou des présences exactes, au moins aussi strictes que l'original. Les trois fichiers listés par la section 4 de la spec (CA24, CA21, CA39) sont adaptés ; **CA25 ne figure pas dans la liste de la spec** et est signalé comme ajout à cette liste.

### Catégorie A (mécanique de parcours, assertions conservées à l'identique)

| # | Fichier, test | Avant | Après | Motif |
|---|---|---|---|---|
| A1 | `ca39-installability-offline.spec.ts`, branche WebKit, ligne 100 | Clic sur le lien « Administration » de l'en-tête (RG28 inc. 4), puis `await expect(page.locator('h1, h2').first()).toBeVisible()` | `await page.goto('/admin')` (navigation directe **en ligne**, servie par le service worker), même assertion `h1, h2` visible | RG2 inc. 6 : le lien n'existe plus. Spec section 4 : « ouvre `/admin` par navigation directe ». La navigation complète remplace le document : elle est placée **avant** les autres écrans pour que chaque bloc de route soit importé dans le document courant avant la coupure (LIM-E2E-2). L'ordre des visites passe de Scan, Administration, accueil à Administration, accueil, Scan, accueil ; toutes les autres assertions (« Courses », « Scan », bloc du tableau de bord, « Mis à jour à », « Hors ligne ») sont inchangées |
| A2 | `ca39-installability-offline.spec.ts`, branche WebKit, ligne 122 (étape **hors ligne**) | Clic sur le lien « Administration » réseau coupé, puis `h1, h2` visible | Navigation du routeur sans lien ni rechargement (`history.pushState` puis `popstate`), même assertion `h1, h2` visible | Une navigation complète hors ligne est impossible sous WebKit (LIM-E2E-2) ; le routeur n'émet aucune requête pour naviguer, comme le clic d'origine ; le bloc (« Page introuvable ») a été chargé en ligne à l'étape A1. **L'assertion hors ligne de `/admin` est donc conservée** (la spec autorisait aussi sa suppression). Les assertions hors ligne de `/scan` (« 1 en attente ») sont inchangées |
| A3 | `ca39-installability-offline.spec.ts`, titre | `@INC-4 @INC4-CA39` | `@INC-4 @INC4-CA39 @INC-6 @INC6-CA7` | Tag demandé par la règle d'étiquetage (CA7 reprend CA39) |
| A4 | `fixtures/admin-separation.ts` (nouveau) | | | Utilitaires des nouveaux tests, aucun test existant touché |

La branche Chromium de CA39 (navigations complètes, `fromServiceWorker()`, `page.goto('/admin')`, `h1, h2` visible, « Hors ligne ») est **inchangée**.

### Catégorie B (attendu changé par une règle de l'inc. 6 ; remplacement aussi strict)

| # | Fichier, test | Avant | Après | RG |
|---|---|---|---|---|
| B1 | `ca21-routes.spec.ts`, « /admin sans connexion affiche l'écran de connexion » | `toHaveText('Connexion')` | Renommé « … affiche Page introuvable (inc. 6, RG3) » : `toHaveText('Page introuvable')`, lien « Retour à l'accueil » visible, adresse restée `/admin` | RG3 |
| B2 | `ca21-routes.spec.ts`, « /admin/courses/{id}/qr sans connexion affiche l'écran de connexion » | `toHaveText('Connexion')` | Renommé de même : `toHaveText('Page introuvable')`, lien visible, adresse inchangée | RG3 |
| B3 | `ca24-login-roles.spec.ts`, « /admin sans connexion : écran de connexion, aucune requête /api/admin/** » | `toHaveText('Connexion')` ; `adminRequests === 0` | Renommé : `toHaveText('Page introuvable')` ; `adminRequests === 0` **conservé** | RG3 |
| B4 | `ca24-login-roles.spec.ts`, « connexion scanner (mémorisée) puis accès admin refusé sans donnée admin » | `toHaveText('Accès réservé à l\'administrateur')` ; « Gérer les courses » absent ; statuts `/api/admin/**` à 403 | Renommé : `toHaveText('Page introuvable')` ; « Gérer les courses » absent et boucle des statuts à 403 **conservés** (elle ne s'exécute plus que sur une liste vide, aucune requête admin n'étant plus émise : CA4 contrôle cette absence explicitement) | RG3 |
| B5 | `ca25-credentials-storage.spec.ts`, « connexion ADMIN : ni le mot de passe ni le Basic ne sont stockés ; reconnexion redemandée » (**hors liste de la spec**) | Après `page.reload()` sur `/admin` : `toHaveText('Connexion')` | `toHaveText('Page introuvable')` et, en plus, le titre « Administration des courses » est absent. Les assertions de stockage (ni `admin-secret` ni l'encodage Basic) sont inchangées | RG3 et CA5 (« après rechargement, `/admin` affiche Page introuvable, car les identifiants ADMIN sont en mémoire seulement »). Même principe vérifié : l'accès admin n'est pas rendu sans nouvelle connexion |

### Catégorie C (assertion retirée, affaiblie, test désactivé)

**Aucune.** Réserve à l'agent fonctionnel : B3 et B4 conservent leurs assertions de sécurité (« aucune requête `/api/admin/**` » et « 403 éventuels ») mais la boucle de B4 est désormais vide par construction ; l'agent fonctionnel peut la reclasser s'il l'estime nécessaire. Le renommage des quatre titres de test (B1 à B4) suit le changement d'objet de l'assertion ; il peut aussi être reclassé en C.

**Autres tests E2E qui naviguent vers `/admin` : inventaire confirmé.** Aucun autre test ne clique le lien « Administration » (recherche sur `frontend/e2e/tests` et `fixtures`) : seuls les deux clics de `ca39` (lignes 100 et 122, A1 et A2). Les tests qui ouvrent `/connexion?retour=…` (helper `loginAdmin`) ne changent pas, ni ceux qui suivent le lien « Scan ». CA25 (B5) est le seul test supplémentaire impacté, non listé par la spec.

Ajouts (non des modifications) : `fixtures/admin-separation.ts`, `tests/inc6-ca2-no-admin-link.spec.ts`, `tests/inc6-ca3-ca4-admin-not-found.spec.ts`, `tests/inc6-ca5-ca8-admin-access.spec.ts`, `tests/inc6-ca6-admin-chunks-not-cached.spec.ts`, `tests/inc6-ca10-ca11-staff-login.spec.ts`, `manual/cl5-sw-update.mjs`, `manual/inc6-evidence.mjs`, `evidence-inc6/`. `PATRIMOINE.md` : section « Incrément 6 — Parcours E2E » et écarts INC-6 ajoutés (aucune ligne existante touchée).

### 5 bis. Renforcements R6-2 et R6-3 (2026-10-01, accord conditionné de l'agent fonctionnel)

| # | Fichier, test | Renforcement |
|---|---|---|
| R6-2 (B4) | `ca24-login-roles.spec.ts`, « connexion scanner (mémorisée) puis /admin » | `expect(adminRequests).toEqual([])` ajouté avant la boucle des statuts : aucune requête `/api/admin/**` pour un non-admin. La boucle 403 est conservée. Le test passe : le front n'émet aucune requête admin. |
| R6-3 (A2) | `ca39-installability-offline.spec.ts`, branche WebKit, étape hors ligne | `expect(page.locator('h1, h2').first()).toBeVisible()` remplacé par `expect(page.getByRole('heading', { level: 1 })).toHaveText('Page introuvable')` après le `pushState` + `popstate`. Le test passe sur WebKit : aucun bug remonté. |

Production non touchée, aucun test supprimé ni assoupli ; B4 et A2 sont désormais plus stricts qu'avant (la mention « boucle vide par construction » de la catégorie C est levée par R6-2).

Rejeu du 2026-10-01 (backend profil `test` H2 sur le port 8080, arrêté ensuite, port libre) : `npx playwright test tests/ca24 tests/ca39 tests/inc6 tests/ca21 tests/ca25 --reporter=list` (Chromium + WebKit) : **61 passés, 1 échec sur 62**. L'échec est CA39 Chromium, attendu (R4-1) : `expect(getByText('Hors ligne')).toBeVisible()` ligne 93, `Received: <element(s) not found>`, timeout 15000 ms, identique au §3. ca24 : 10/10 ; ca39 WebKit : PASS ; inc6-* : 18/18 ; ca21 et ca25 : PASS.

Les captures (`*.png`) de `frontend/e2e/evidence-inc6/` ne sont pas versionnées ; elles sont régénérables par `frontend/e2e/manual/inc6-evidence.mjs`.

### Soumis à l'agent fonctionnel (récapitulatif)
1. A1 et A2 (CA39 WebKit) : navigation directe en ligne puis navigation du routeur hors ligne ; l'assertion hors ligne de `/admin` est conservée, l'ordre des visites change.
2. B1 à B4 (CA21, CA24) : « Page introuvable » à la place de l'écran de connexion et d'« Accès réservé », conforme à la section 4 de la spec ; renommage des titres.
3. B5 (CA25) : adaptation non prévue par la spec, même motif (RG3, CA5). La liste de la section 4 est à compléter.
4. Interprétation de « `/admin` ouverte par son URL » (CA11, CL8, CL9) comme navigation du routeur sans rechargement (voir §7).
5. OBS-E2E-6A (blocs partagés préchargés).

## 6. Tests instables ou en quarantaine

Aucun test en quarantaine, aucun retry (`retries: 0`).

**Observation : CA36 sous WebKit.** Un échec isolé dans la première suite complète (`ca36-admin-crud`, timeout de 60 s à l'assertion `li.card` du coureur 2 portant « 5 » après « Enregistrer » ; le contexte d'erreur montre pourtant la carte avec le dossard 5 en fin de test). Non reproduit : 3/3 PASS en répétition isolée (`--repeat-each=3`, WebKit), puis 1/1 dans la seconde suite complète. Cause non établie (le test n'est pas dans le périmètre de l'inc. 6 et n'est pas modifié) ; non mis en quarantaine. Toute nouvelle occurrence est à consigner et soumettre à l'agent fonctionnel (même règle que CA38 en inc. 4). Fichier : `evidence-inc6/full-suite-run1-4fail.txt`.

Deux échecs de mise au point de CA10 (mode strict, échéance glissante) ont été corrigés par une correction de l'attente et de la comparaison, pas par une relance.

## 7. Risques et limites

- **CA39 sous Chromium** reste en échec attendu (R4-1, R5-3, bloquantes avant déploiement) : état de référence respecté, aucune régression.
- **« `/admin` ouverte par son URL » (CA11, CL8, CL9).** Les identifiants ADMIN ne sont gardés qu'en mémoire (RG7 inc. 4, CA5) : un `page.goto('/admin')` les perdrait et afficherait « Page introuvable ». CA11 est donc joué par une navigation du routeur sans lien (`pushState` + `popstate`), équivalent de l'historique du navigateur cité par CL9. Un `goto` complet serait un test faux. À confirmer par l'agent fonctionnel.
- **CA8** : E13 est remplacée juste après « Modifier » puis « Enregistrer » (l'action fait d'abord un E10 réel, puis recharge les coureurs par E13). L'énoncé n'impose pas l'action déclenchante.
- **CA6** : l'attente de fin de préchargement s'appuie sur `ngsw.json` servi (fichiers `prefetch`) ; les blocs admin sont repérés par les textes de RG5 dans `frontend/dist/backyard-pwa/browser` (variable `E2E_BUILD_DIR` pour un autre répertoire), et vérifiés servis par le backend sous le même nom. Cache Storage ne liste que les clés (URL), pas les contenus. Le journal réseau ne voit pas les requêtes du service worker lui-même (OBS-E2E-6B). L'émulation hors ligne n'est pas utilisée (R5-3).
- **CL5 sous WebKit** non vérifié : le contexte persistant de Playwright ne peuple pas Cache Storage sous WebKit dans cet environnement (0 fichier en cache après 2 minutes, alors que le test CA6 en contexte normal le peuple). CL5 est vérifié sous Chromium seulement. Les profils de navigateur temporaires sont dans `C:\b6` (supprimé avec le worktree) ; un chemin de profil en nom court Windows (8.3) empêchait Cache Storage de se remplir sous Chromium, d'où le chemin court.
- **CA10** : le volet front-unit est abandonné par la spec 4b ; le comportement est prouvé de bout en bout (E19 401, emplacements d'IndexedDB lus directement par clé `scanner` et `runner`, vidage de stockage).
- **Accessibilité** : aucun nouveau test axe (aucun écran ajouté ; « Page introuvable » existait déjà et est couvert par la suite d'accessibilité de l'inc. 4 seulement pour les écrans qu'elle cite).
- **Base H2** (RT1) : aucun PostgreSQL réel. Pas de nettoyage des données de test (comme aux inc. 4 et 5).
- **Dépôt** : `.angular/` (cache Angular) apparaît en non suivi à la racine du dépôt ; non créé volontairement par cet agent, à ignorer ou supprimer avant publication. `frontend/e2e/*.log` est ignoré par git (`.gitignore` de `frontend/e2e/`) : les journaux conservés comme preuves sont copiés en `.txt` dans `evidence-inc6/`.
- Un classificateur d'outil a plusieurs fois refusé des commandes `sleep` : les attentes de fin de suite ont été faites par boucles d'interrogation.

## 8. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : GO / GO sous réserves / NO-GO
- **Réserves ou motifs** :
- **Actions correctives exigées** :
- **Date** :
