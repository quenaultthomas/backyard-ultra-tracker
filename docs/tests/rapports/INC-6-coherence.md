# Revue de cohérence du patrimoine : INC-6 (séparation admin / public)

- **Date** : 2026-10-01
- **Agent** : revue-coherence-patrimoine, **joué par un agent générique faute d'enregistrement de l'agent dans la session** (même consigne, mêmes contrôles ; lecture seule, seul ce rapport est écrit)
- **Périmètre examiné** : branche `feature/increment-6-separation-admin-public`, HEAD `80fcd8a` + arbre de travail non commité (13 fichiers suivis modifiés, 25 chemins non suivis).
- **Entrées** : `docs/specs/increment6.md` (révision 4b), `docs/tests/PATRIMOINE.md`, `INC-6-integration.md`, `INC-6-e2e.md`, `INC-5-synthese.md` (R5-4, R5-5, R5-8), `git diff main` / `git status`.

## 1. Synthèse

- Constats : **7**, dont **0 bloquant**, **2 majeurs**, **5 mineurs**.
- Véracité des rapports : aucun écart. Résultats backend rejoués par le coordinateur (surefire 558/0, failsafe 219/0, BUILD SUCCESS) : le total failsafe 219/0/0 est confirmé par cet agent en sommant `backend/target/failsafe-reports/*.txt` (horodatés 09:01). Échantillons rejoués par cet agent : vitest 250/250, surefire ciblé 144/144, E2E 60/60 + CA39 (1 échec Chromium identique au commit de départ, WebKit vert).
- Patrimoine existant : aucun test supprimé, désactivé, ignoré ni assoupli ; `git diff main` vide sur `backend/src/test` (6 classes ajoutées, aucune modifiée). 4 fichiers E2E existants adaptés, nombre de `test(` inchangé.
- Réserves R5-4, R5-5, R5-8 : tests présents, actifs, verts (voir §3.5). Levée à prononcer par l'agent fonctionnel, non faite ici.
- Point RG5 (blocs `race-form` et `account-admin-actions` préchargés) : constaté en COH6-1, sans trancher.

## 2. Tableau des constats

| ID | Contrôle | Sévérité | Description | Preuve | Action recommandée |
|---|---|---|---|---|---|
| COH6-1 | C1 (RG5, CA6) | **Majeur** | Les deux blocs `chunk-kStRrTjQ.js` (`race-form`, texte « Durée de boucle ») et `chunk-CeQ4ybSt.js` (`account-admin-actions`, « Réinitialiser le mot de passe ») restent dans le groupe `app` en `prefetch` et en Cache Storage de tout visiteur. La spec ne les écarte pas explicitement : RG5 (l. 126) dit « Le code des écrans `/admin/**` … exclus du préchargement », mais sa définition (l. 127) des « blocs admin » se limite à trois textes (« Administration des courses », « Gérer la course et les coureurs », « Imprimer les QR codes ») et ne réserve le statut « public » qu'au code partagé « avec les écrans publics ou de scan ». Or ces deux composants ne sont importés que par des écrans admin (`grep` sur `frontend/src` : `admin-races-page`, `admin-race-page`, `admin-accounts-page`, `race-form`, `account-admin-actions`, aucun écran public). CA6 est donc vert à la lettre de la définition, mais l'intention de la ligne 126 n'est pas atteinte pour ces deux blocs. Fragilité associée : l'exclusion est `!/admin-*.js` (préfixe de nom de fichier) ; un futur écran admin non préfixé `admin-` serait préchargé sans que `CA6` ni `admin-separation.spec.ts` le voient. `main-*.js` porte aussi le titre de route « Administration — … » (limite assumée, spec §1). Déjà signalé par le testeur et par l'agent E2E (OBS-E2E-6A) | `frontend/ngsw-config.json` (`"!/admin-*.js"`) ; `frontend/dist/backyard-pwa/browser/` (liste des chunks) ; `INC-6-e2e.md` §4 OBS-E2E-6A ; `PATRIMOINE.md` « Écarts ouverts (INC-6, E2E) » ; `grep -rln "race-form\|account-admin-actions" frontend/src` | L'agent fonctionnel arbitre : (a) accepter explicitement (amender RG5 l. 127 pour nommer ces deux blocs comme tolérés) ou (b) élargir la définition de RG5 et faire sortir ces blocs du préchargement (changement de production + extension de CA6). Dans les deux cas, consigner la fragilité du motif `admin-*` |
| COH6-2 | C3 | **Majeur** | Adaptation E2E hors de la liste de la spec : `ca25-credentials-storage.spec.ts` (test « connexion ADMIN : ni le mot de passe ni le Basic ne sont stockés ; reconnexion redemandée ») : `toHaveText('Connexion')` devient `toHaveText('Page introuvable')` + une assertion ajoutée. La section 4 de la spec (« Tests existants à faire évoluer ») ne cite que CA24, CA21, CA39 ; CA9 dit « Les seuls tests E2E modifiés sont ceux listés en section 4 » ; la règle 2 exige accord de l'agent fonctionnel. Le motif est écrit (`INC-6-e2e.md` §5 B5), l'assertion est plus stricte (le nombre de `expect(` passe de 16 à 17, assertions de stockage `admin-secret` et Basic inchangées), le changement découle de RG3 et de CA5. Risque : accord absent au moment de ce rapport. Aurait été **bloquant** au verdict sans accord | `git diff main -- frontend/e2e/tests/ca25-credentials-storage.spec.ts` (6 lignes) ; `INC-6-e2e.md` §5 B5 et « Soumis à l'agent fonctionnel » point 3 | L'agent fonctionnel donne un accord écrit sur B5 et complète la liste de la section 4 de la spec (ou CA9) |
| COH6-3 | C3 / C4 | Mineur | Adaptations autorisées par la spec mais qui vident ou affaiblissent la valeur de deux assertions, à reclasser éventuellement en C. (a) `ca24-login-roles.spec.ts` « connexion scanner (mémorisée) puis /admin » : la boucle `for (const status of adminRequests) expect(status).toBe(403)` est conservée mais ne s'exécute plus que sur une liste vide (aucune requête admin n'est plus émise) ; l'absence de requête n'est pas affirmée dans ce test, elle l'est en CA4. (b) `ca39` WebKit, étape hors ligne : `history.pushState` + `popstate` suivi de `expect(page.locator('h1, h2').first()).toBeVisible()` ; ce locator est satisfait par le `h1` « Scan » déjà affiché si la navigation du routeur n'a pas eu lieu : l'étape ne prouve plus rien sur `/admin` (la route montre « Page introuvable » pour un anonyme). Même assertion qu'avant (donc non « assouplie » au sens littéral), mais l'objet vérifié a changé. L'ordre des visites de la branche WebKit change aussi (Administration, accueil, Scan, accueil, au lieu de Scan, Administration, accueil). Les titres de 4 tests sont renommés (B1 à B4) | `git diff main -- frontend/e2e/tests/ca24-login-roles.spec.ts` ; `ca39-installability-offline.spec.ts` lignes 94 à 138 ; `INC-6-e2e.md` §5 A1, A2, B4 et réserve de la catégorie C | Accord écrit de l'agent fonctionnel sur A1, A2, B1 à B4 ; option : renforcer (b) en assertant `getByRole('heading', { level: 1, name: 'Page introuvable' })` après la navigation du routeur |
| COH6-4 | C1 / C4 | Mineur | Incohérence de rédaction de la spec : CA11 et CL8 disent que `/admin` « ouverte ensuite par son URL liste les courses » après une connexion ADMIN, alors que CA5 et RG7 (inc. 4) imposent que les identifiants ADMIN restent en mémoire seulement (un `page.goto` complet les perd). Le test E2E joue donc « par son URL » comme une navigation du routeur (`pushState` + `popstate`), non comme une ouverture d'URL. L'agent E2E le consigne (§5 et §7) | `inc6-ca10-ca11-staff-login.spec.ts` (CA11) ; spec CA11, CL8, CL9 ; `INC-6-e2e.md` §7 | L'agent fonctionnel confirme l'interprétation (historique du navigateur, CL9) et corrige la rédaction de CA11 |
| COH6-5 | C2 | Mineur | `PATRIMOINE.md` contient des mentions périmées dans la section « Incrément 6 — tests unitaires et slice » (texte écrit avant implémentation) : « 558 tests, 5 en échec », « 250 tests, 6 en échec » (états intermédiaires) ; ligne `INC6-CA10 (volet front-unit)` au statut **ÉCART** « non écrit » alors que la spec 4b a abandonné ce volet ; ligne `INC6-CA1` portant un « Écart de spec consigné » (deux `detail` de 401) tranché par la révision 4b ; paragraphe « Vérification technique » : « failsafe 85 », « E2E de la section 4 (ca21, ca24, ca39) pas encore évolués, CA2 à CA8, CA10, CA11, CA12 non écrits », « `git diff main` vide sur les tests suivis » (faux pour les E2E désormais adaptés). Les lignes de résultat à jour existent dans les sections IT et E2E suivantes ; le référentiel se contredit donc sur l'état de l'incrément. Les statuts R5-4, R5-5, R5-8 sont annotés « levée à prononcer » (conforme, aucune levée prématurée) | `PATRIMOINE.md` §« Incrément 6 — tests unitaires et slice » (lignes INC6-CA1, INC6-CA10 et paragraphe « Vérification technique ») ; spec révision 4b | Mettre à jour ou barrer ces mentions (testeur), sans toucher aux lignes de résultats des sections IT et E2E |
| COH6-6 | C4 | Mineur | Les tests de lecture de build de `admin-separation.spec.ts` (groupe RG5) dépendent d'un `dist/` de production à jour : `npm test` seul échoue si le build est absent et peut passer ou échouer sur un build périmé ; le test RG3 est couplé au texte des sources (`not.toMatch(/\['\/connexion'\]/)`). Le testeur l'a consigné ; aucun test de cette série ne vérifie qu'il lit un build postérieur aux sources | `frontend/src/app/core/admin-separation.spec.ts` (`builtScripts`, test RG3 « la garde … ne redirige plus ») ; `PATRIMOINE.md` ligne `INC6-RG5` | Acceptable en l'état (Maven régénère le build avant `npm test`) ; noter la dépendance dans la ligne de matrice ou comparer les dates du build et des sources |
| COH6-7 | C4 (parasites) | Mineur | Fichiers parasites ou non décidés : `.angular/` (cache Angular, 864 Ko) à la racine, non suivi et absent du `.gitignore` racine (qui ne contient que `backend/target/`, `frontend/node_modules/`, `frontend/dist/`, `.idea/`, `.vscode/`, `*.log`, `.env`) ; `frontend/e2e/evidence-inc6/` (825 Ko de PNG, journaux et traces) : aucun répertoire de preuves des inc. 4 et 5 n'est suivi (`git ls-files | grep evidence` vide) ; `frontend/e2e/manual/` (2 scripts `.mjs` d'outillage, hors `testDir` Playwright donc jamais exécutés par la suite, référencés par le rapport E2E) ; `frontend/src/app/core/testing/node-fs.d.ts` (déclaration de types pour les tests). Aucun fichier de résultats Playwright (`test-results`, `playwright-report`) n'est non ignoré. Ce rapport n'a modifié aucun de ces éléments | `git status --short` ; `git check-ignore -v .angular` (aucune règle) ; `git ls-files \| grep -c evidence` = 0 | Décider avant `git-publisher` : ignorer `.angular/`, ne pas commiter les journaux et captures lourds (ou n'en garder que les `.txt` utiles), commiter `manual/` seulement si la décision est de le conserver ; confirmer que `node-fs.d.ts` est voulu |

## 3. Détail des contrôles

### 3.1 C1 : couverture exigences et critères

- RG1 à RG10 et CL1 à CL11 ont chacune au moins un test actif : RG1 (CA1 : `AccessMatrixSliceTest` 126, `AccessMatrixIT` 126), RG2 (CA2 E2E 2 cas + `admin-separation.spec.ts` RG2), RG3 (CA3, CA4, CA5, CA11 E2E), RG4 (CA4), RG5 (CA6 E2E, CA7 via CA39, `admin-separation.spec.ts` RG5), RG6 (CA10 E2E, E19 401 dans la matrice), RG7 (CA2, CA11, test RG7 front), RG8 (CA12 : 5 cas), RG9 (CA13 : 13 cas), RG10 (CA14 : 5 cas unitaires + 3 IT).
- Seule couverture non automatisée : CL5 (mise à jour du service worker), prévue manuelle par la spec (CA9), vérifiée sous Chromium par `manual/cl5-sw-update.mjs` seulement.
- Assertions pertinentes, vérifiées par lecture : CA3 et CA4 (`adminApi.urls()` égal à `[]`, adresse inchangée), CA6 (liste de blocs non vide exigée, Cache Storage inspecté, discrimination démontrée sur le build de départ : `baseline-ca6-start-commit.txt` montre l'échec 2/2 sur les blocs admin dans Cache Storage), CA14 (discrimination 3/3 rapportée sur l'ancien gestionnaire, non rejouée ici).
- Comptage des tests ajoutés : 126 (`AccessMatrixSliceTest`) = 1 + 100 + 25 ; `AccessMatrixIT` 126 ; `InternalErrorLogsIT` 3 ; `V1ToV2LegacyDataHttpIT` 5 ; `ApiExceptionHandlerLoggingTest` 5 ; `PseudoNormalizationSourceReviewTest` 13 ; vitest 15 (4 + 4 + 3 + 3 + 1), soit 235 + 15 = 250 ; E2E 9 x 2 = 18 (116 + 18 = 134). Tous cohérents avec les rapports.

### 3.2 C2 : cohérence matrice et code

- Tous les tests nommés dans `PATRIMOINE.md` existent (grep des noms de méthodes dans les six classes backend, les cinq fichiers E2E `inc6-*` et `admin-separation.spec.ts`).
- Tags : `@Tag("INC-6")` sur les six classes, `INC6-CA1`, `INC6-CA12`, `INC6-CA13`, `INC6-CA14` valides (CA1 à CA14 existent dans la spec) ; E2E `@INC-6 @INC6-CA<k>` valides, plus `@INC6-CA7` sur `ca39`. Aucun tag vers une exigence inexistante, aucun test orphelin dans le code non référencé par la matrice. `admin-separation.spec.ts` n'a pas de tag (Vitest) mais est référencé par chemin.
- Mentions périmées de la matrice : COH6-5.

### 3.3 C3 : intégrité du patrimoine existant

- Backend : `git diff main -- backend/src/test` vide ; aucun fichier de test suivi modifié (`git status` : seuls des `??` dans `backend/src/test`). Conforme à CA9 (« aucun test backend existant modifié »).
- Production : un seul fichier backend modifié, `ApiExceptionHandler.java` (+13 −3), correctif RG10 prévu par la spec.
- Frontend unitaire : aucun `*.spec.ts` existant modifié ; un seul nouveau fichier de test et un fichier de déclaration.
- Recherche de `@Disabled`, `test.skip`, `fixme`, `.only`, `xit` dans `backend/src/test`, `frontend/e2e`, `frontend/src` : aucun résultat. `waitForTimeout` présent dans `ca26` (10 s) et `ca42` (6 s) : préexistants, non touchés par cet incrément.
- E2E modifiés (4 fichiers, `git diff main`) :

| Fichier | `test(` main / maintenant | `expect(` main / maintenant | Catégorie vérifiée |
|---|---|---|---|
| `ca21-routes.spec.ts` | 9 / 9 | 15 / 19 | B1, B2 : égalité exacte `Page introuvable` + lien « Retour à l'accueil » + chemin inchangé : plus strict. Titres renommés |
| `ca24-login-roles.spec.ts` | 5 / 5 | 18 / 18 | B3 : `adminRequests === 0` conservé. B4 : voir COH6-3 (a) |
| `ca25-credentials-storage.spec.ts` | 5 / 5 | 16 / 17 | B5 : plus strict, mais hors liste de la spec (COH6-2) |
| `ca39-installability-offline.spec.ts` | 1 / 1 | 28 / 29 | A1, A2, A3 : branche Chromium inchangée ; voir COH6-3 (b) |

  Aucune catégorie C au sens de `INC-6-e2e.md` (aucune assertion retirée, aucun test désactivé). Timeouts identiques (aucune valeur de `timeout` ou `toBeVisible({ timeout })` modifiée dans les diffs). Le tag `@INC-6 @INC6-CA7` ajouté à `ca39` ne retire rien. Accord de l'agent fonctionnel : couvert par la spec §4 pour ca21, ca24, ca39 (tests nommés) ; absent pour ca25 et pour le détail de A1/A2 (COH6-2, COH6-3).
- Configuration : `frontend/angular.json` (+`namedChunks: true`) et `frontend/ngsw-config.json` (exclusion `!/admin-*.js` + groupe `admin` lazy) sont de la production, pas des tests. `pom.xml` et `src/test/resources` non modifiés.

### 3.4 C5 : véracité des rapports (exécution réelle)

| Élément | Annoncé | Constaté par cet agent |
|---|---|---|
| surefire (suite complète, coordinateur) | 558 / 0 | Confirmé par le coordinateur ; cet agent ne l'a pas rejoué en entier |
| failsafe (suite complète) | 219 / 0 | **219 / 0 / 0 ignoré** (somme des 19 fichiers `target/failsafe-reports/*.txt`, horodatés 09:01) ; `V1ToV2LegacyDataHttpIT` 5/5, `InternalErrorLogsIT` 3/3 |
| surefire ciblé INC-6 (`-Dtest=AccessMatrixSliceTest,ApiExceptionHandlerLoggingTest,PseudoNormalizationSourceReviewTest -Dskip.npm -Dskip.installnodenpm -Djacoco.skip=true`) | 126 + 5 + 13 | **126 / 5 / 13, 0 échec, 0 ignoré** ; les lignes ERROR du journal ne portent que « … : IllegalStateException » (nom de classe), sans pseudo |
| vitest | 250 (24 fichiers) | **250 passés, 24 fichiers** (`npx vitest run`) |
| E2E échantillon (`tests/inc6 tests/ca21 tests/ca24 tests/ca25`, Chromium + WebKit, backend démarré avec la commande du rapport INC-4, build `dist` de 08:58) | (compris dans 133/134) | **60 / 60 passés** (2,0 min) |
| E2E `ca39` | Chromium FAIL attendu, WebKit PASS | **1 échec, 1 passé** : Chromium `ca39-installability-offline.spec.ts:93:68`, `getByText('Hors ligne')` `Expected: visible`, `Received: <element(s) not found>`. Message identique à `evidence-inc6/baseline-ca39-start-commit.txt` (lignes 10 à 14), donc état de référence respecté (CA7) |
| E2E suite complète | 133/134, seul échec CA39 Chromium | `evidence-inc6/full-suite-final.txt` : « 1 failed » (CA39 Chromium) et « 133 passed (16.1m) » ; cohérent avec 116 + 18. **Suite complète non rejouée** (16 min) |

Notes de véracité : (1) `INC-6-integration.md` date son `clean verify` de 07:40, le coordinateur a rejoué à 08:56-09:02 : mêmes chiffres, pas d'écart. (2) Les commandes citées par les rapports existent et fonctionnent (`spring-boot:run` avec `useTestClasspath`, `npx playwright test`). (3) Backend de test arrêté en fin de session (port 8080 libre vérifié). (4) Aucune commande npm ou Maven lancée en parallèle sur `frontend/`.

Aucun écart entre résultat annoncé et résultat constaté.

### 3.5 Réserves de l'inc. 5 (R5-4, R5-5, R5-8) : couverture réelle

| Réserve | Test | Constat |
|---|---|---|
| R5-4 (CA12, RG8) | `V1ToV2LegacyDataHttpIT` (5 cas : `ca12_v2WasAppliedBeforeTheContextStarted`, `…boardsShowNeutralNamesOnMigratedData`, `…publicAndAdminRunnerViewsShowNeutralName`, `…manualDnfOnMigratedRunnerKeepsNeutralName`, `…databaseKeepsRaceDataAndHasNoNameLeft`) | Actif, vert dans le failsafe du 09:01 (5/5). Base migrée V1 puis peuplée puis V2 dans `@BeforeAll`, contexte complet ensuite ; vérification « Alice et Bob présents avant V2 » rapportée. Non rejoué sur une V2 amputée de son `UPDATE` (limite consignée par l'agent d'intégration). Reste sur H2 (R5-2 maintenue) |
| R5-5 (CA13, RG9) | `PseudoNormalizationSourceReviewTest` (13 cas, dont 5 sur sources synthétiques a à e, répertoire absent, aucun fichier lu) | Actif, 13/13 rejoué ici (surefire ciblé). Échoue si répertoire absent ou aucun fichier lu (pas de passage à vide) |
| R5-8 (CA14, RG10) | `ApiExceptionHandlerLoggingTest` (5 cas) + `InternalErrorLogsIT` (3 cas, chaîne HTTP réelle) | Actifs, verts (5/5 rejoué ici, 3/3 dans le failsafe). Production : `logInternalError` n'écrit que méthode, chemin et `getSimpleName()` ; relu dans `ApiExceptionHandler.java` (diff main). Discrimination 3/3 sur l'ancien gestionnaire : rapportée par l'agent d'intégration, non rejouée ici. Le WARN des 4xx n'écrit plus le `detail` (déjà corrigé à l'inc. 5) |

Les trois lignes de `PATRIMOINE.md` sont annotées « levée à prononcer par l'agent fonctionnel » : aucun passage prématuré à « levée ».

### 3.6 C6 : non-régression inter-incréments

- Backend : suite complète verte (558 + 219), incluant `AccountContractIT#ca13_accessMatrix`, `SecuritySliceTest`, `V2MigrationIT` (aucun modifié). Aucun endpoint ni code de réponse changé (aucune modification de contrôleur ni de `SecurityConfig` dans le diff).
- Frontend : 250 tests vitest verts, dont les 235 antérieurs inchangés.
- E2E : 133/134 rapportés ; échantillon rejoué ici (60/60 + CA39). Comportements modifiés par l'inc. 6 (« Page introuvable » à la place de la connexion, retrait du lien « Administration », coquille admin sans « Accès réservé ») : tests des inc. 4 adaptés (COH6-2, COH6-3), aucun autre test de l'inc. 4 ou 5 modifié. Inventaire « seuls tests cliquant le lien Administration » : confirmé par l'agent E2E ; non refait en entier ici, mais `ca21`, `ca24`, `ca25`, `ca39` passent.
- Régression : aucune constatée.

### 3.7 Point RG5 (blocs `race-form` et `account-admin-actions`)

Voir COH6-1. À constater : `ngsw-config.json` groupe `app` : `/*.js` moins `!/admin-*.js` ; groupe `admin` en `lazy` pour `/admin-*.js`. Le build contient 5 fichiers `admin-*.js` (`admin-accounts-page`, `admin-qr-sheet-page`, `admin-race-page`, `admin-races-page`, `admin-shell`) et les chunks partagés `chunk-kStRrTjQ.js` et `chunk-CeQ4ybSt.js` dans le groupe préchargé. La spec écarte explicitement le code « partagé avec les écrans publics ou de scan » (l. 127) ; elle ne dit rien d'un code partagé uniquement entre écrans admin. Décision à l'agent fonctionnel.

## 4. Contrôles sans constat

- C1 : exigence ou critère sans aucun test (aucun, hors CL5 manuel prévu).
- C2 : tests cités absents du code ; tags vers exigences inexistantes ; tests tagués non référencés.
- C3 : suppression, désactivation (`@Disabled`, `skip`, `fixme`, `only`) ; modification d'un test backend ; timeouts allongés ; `pom.xml` et ressources de test.
- C4 : tests sans assertion ou à assertion triviale dans les 6 classes backend et 6 fichiers front/E2E ajoutés (relecture : `AccessMatrixIT` jeu de données neuf par cas, `InternalErrorLogsIT` chaîne réelle, E2E CA6 liste non vide exigée) ; `sleep` arbitraires ; quarantaine (aucune).
- C5 : chiffres failsafe, vitest, surefire ciblé, échantillon E2E, CA39 Chromium.
- C6 : non-régression backend, front, E2E (échantillon).
- Spécification : couverture RG/CL/CA de la section 6 cohérente avec les tests ; matrice d'accès de la section 4 identique à la table de `AccessMatrixSliceTest` / `AccessMatrixIT` (100 + 25 cas, comptes vérifiés).

## 5. Limites

- Suite complète backend (`clean verify`, 9 min) non rejouée par cet agent : surefire 558/0 repose sur la déclaration du coordinateur ; failsafe 219/0 vérifié sur les rapports 09:01 ; JaCoCo (pourcentages domain et service, seuils) non recalculé (« All coverage checks have been met » non observé ici).
- Suite E2E complète (134 cas, 16 min) non rejouée : log `full-suite-final.txt` relu, échantillon de 60 cas + CA39 rejoué.
- Discrimination de CA14 (rejeu sur l'ancien gestionnaire) et de CA6 (rejeu sur le build du commit de départ) : non rejouées, seulement vérifiées dans les journaux fournis (`baseline-ca6-start-commit.txt`, `baseline-ca39-start-commit.txt` ; la discrimination de CA14 n'a pas de journal dans le dépôt, elle n'est consignée que dans `INC-6-integration.md` §4).
- CL5 (mise à jour du service worker) : non rejoué ; vérifié par l'agent E2E sous Chromium seulement, WebKit non vérifié (consigné par lui).
- Pas de compte rendu écrit du testeur dans `docs/tests/rapports/` (comme à l'inc. 5) : le verdict technique OK est mentionné dans `INC-6-integration.md` et `PATRIMOINE.md`, non vérifiable ici autrement que par les résultats rejoués.
- Le build Angular `dist/` utilisé pour l'échantillon E2E est celui de 08:58 (postérieur aux sources modifiées de 07:14) ; aucune reconstruction par cet agent.
- PostgreSQL réel (R5-2, RT1), VPS (R5-1, parties manuelles de CA39/CA40 de l'inc. 5) : hors périmètre.
- Les rapports d'intégration et E2E ont une section « Verdict de l'agent fonctionnel » vide : attendu à ce stade.

## 6. Recommandation à l'agent fonctionnel

**Prêt pour arbitrage** : aucun constat bloquant. Les chiffres annoncés sont confirmés par exécution réelle (failsafe 219/0 sur rapports, vitest 250/250, surefire ciblé 144/144, E2E 60/60 + CA39 identique au commit de départ), le patrimoine existant est intact (aucun test backend modifié, aucune assertion retirée dans les E2E adaptés), et les réserves R5-4, R5-5 et R5-8 ont chacune des tests actifs et verts.

Points à traiter dans l'arbitrage, avant ou avec le verdict :
1. COH6-1 : décider du sort des blocs `race-form` et `account-admin-actions` (RG5).
2. COH6-2 : accord écrit sur l'adaptation de `ca25` (hors liste de la spec). Sans accord, l'écart devient bloquant.
3. COH6-3 : accord écrit sur A1, A2, B1 à B4 (l'agent E2E les a soumis).
4. COH6-4 : confirmer l'interprétation de « par son URL » (CA11, CL8).
5. COH6-5 et COH6-7 : faire nettoyer `PATRIMOINE.md` et décider du sort de `.angular/`, `evidence-inc6/` et `manual/` avant publication.

Ce rapport constate et ne tranche pas : le verdict GO / GO sous réserves / NO-GO appartient à l'agent fonctionnel.
