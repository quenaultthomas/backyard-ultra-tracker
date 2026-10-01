# Rapport de test : INC-7 (e2e)

- **Date** : 2026-10-01
- **Agent auteur** : test-e2e-frontend
- **Version / commit testé** : branche `feature/increment-7-connexion-inscription`, HEAD `97f5171` + working tree non commité (front de l'inc. 7, E26, tests du testeur et de l'agent d'intégration, tests E2E de cette passe). Aucune modification du code de production par cet agent. Étape d'intégration : `INC-7-integration.md` (failsafe 231/0).
- **Environnement** :
  - Backend : `mvn -B -f backend/pom.xml spring-boot:run -Dspring-boot.run.profiles=test -Dspring-boot.run.useTestClasspath=true` (reconstruit la PWA de production depuis `frontend/src`), profil `test`, H2 en mode PostgreSQL en mémoire, comptes `admin-test` / `scanner-test`, clôture de yard à 500 ms ; variables `SPRING_DATASOURCE_*` et `BACKYARD_*` identiques à `INC-4-e2e.md` §3. Lancé par `run_in_background` (jamais `&`), arrêté en fin de session (java, node, chrome ; port 8080 vérifié libre).
  - Front : PWA de production servie par Spring Boot sur `http://localhost:8080` (service worker actif seulement dans CA20, bloqué ailleurs, LIM-E2E-1).
  - Outil : Playwright 1.55.1, `@axe-core/playwright` 4.13.0, Chromium et WebKit. **Aucune nouvelle dépendance.**

## 1. Périmètre

CA `[E2E]` de `docs/specs/increment7.md` (révision 2) : **CA8, CA9, CA10, CA11, CA12, CA13, CA14, CA15, CA16, CA20, CA22, CA23**, plus le volet E2E de CA19 (non-régression : suite complète, tests évolués section 8). Hors périmètre : CA1 à CA7, CA17, CA18, CA21 (voir `INC-7-integration.md`). Tags `@INC-7` et `@INC7-CA<k>`. `@smoke` : CA8 à CA16, CA22 (sauf CA20, CA23).

## 2. Couverture exigences ↔ tests

| Exigence | Tests (`frontend/e2e/tests/`) | Résultat (Chromium / WebKit) | Écart ? |
|---|---|---|---|
| INC7-CA8 | `inc7-ca8-ca11-login-screen.spec.ts` « @INC7-CA8 » : h1 exact, groupe de 2 radios, libellés par entrée, aucun texte « Administration/administrateur » (`innerText`), aucun `a[href]` `/admin`, 0 requête `/api/` avant soumission | PASS / PASS | Non |
| INC7-CA9 | idem « @INC7-CA9 » (4 cas a à d) | PASS / PASS | Non |
| INC7-CA10 | idem « @INC7-CA10 » (3 cas a à c) : stockages, IndexedDB (clés `scanner` et `runner`), cookies, message et aide identiques | PASS / PASS | Non |
| INC7-CA11 | idem « @INC7-CA11 » : `Authorization` de E6 = Basic bénévole, `/compte` intact, déconnexion coureur sans effet sur le bénévole | PASS / PASS | Non |
| INC7-CA12 | `inc7-ca12-ca13-account-creation.spec.ts` « @INC7-CA12 » (5 cas a à e) | PASS / PASS | Non (voir note E21) |
| INC7-CA13 | idem « @INC7-CA13 » | PASS / PASS | Non |
| INC7-CA14 | `inc7-ca14-ca15-header-and-admin.spec.ts` « @INC7-CA14 » (3 cas : anonyme sur 8 écrans, coureur connecté sur 9 écrans, bénévole puis admin) | PASS / PASS | Non (voir OBS-E2E-7A) |
| INC7-CA15 | idem « @INC7-CA15 » | PASS / PASS | Non |
| INC7-CA16 | `inc7-ca16-ca22-ca23-routes-a11y-expiry.spec.ts` « @INC7-CA16 » (2 cas) | PASS / PASS | Non |
| INC7-CA20 | `inc7-ca20-no-admin-chunks.spec.ts` (**build de production, service worker actif, Cache Storage réel**) | PASS / PASS | Non |
| INC7-CA22 | idem `inc7-ca16-ca22-ca23-...` « @INC7-CA22 » : (a) axe sur `/connexion` Coureur, Bénévole, état d'échec, `/inscription`, avec erreurs de champs ; (b) 7 URL de présélection ; additif dans `ca41-accessibility.spec.ts` | PASS / PASS | Non |
| INC7-CA23 | idem « @INC7-CA23 » (3 cas : E6, `/api/account/**`, ADMIN) | PASS / PASS | Non |
| INC7-CA19 (volet E2E) | suite complète (§3) ; tests évolués (§5) | voir §3 | Non (CA39 Chromium attendu) |

Exigences E2E sans test : **aucune**.

Notes de lecture :
- **CA12 (b), E21** : le test constate une E21 émise depuis `/inscription` (connexion automatique, exactement une) puis une seconde émise par la page `/compte` elle-même (liste « Mes inscriptions », total 2). L'énoncé « une seule E21 » est lu comme « une seule E21 de connexion » ; à confirmer par l'agent fonctionnel.
- **CA20** : preuve en trois volets, sur le build servi : (1) les blocs des deux écrans sont repérés par un texte propre (`Je me connecte en tant que`, `Bénévole : votre compte est créé…`), ne s'appellent pas `admin-*` et ne contiennent aucun des trois textes de RG5 ; (2) après la fin du préchargement (tous les fichiers `prefetch` de `ngsw.json` en Cache Storage), la visite de `/connexion` (deux entrées, `retour` sous `/admin`), `/inscription` et `/compte/connexion` ne charge ni ne met en cache aucun fichier `admin-*.js` ni aucun fichier contenant un texte RG5, et émet 0 requête `/api/admin/**` ; (3) les deux blocs des nouveaux écrans sont bien préchargés (voulu, RG11). R6-1 : aucun bloc partagé nouveau ne contient de texte admin. Inventaire de Cache Storage : `evidence-inc7/07-cache-storage-ecrans-connexion-inscription.txt`.

## 3. Résultats d'exécution (réels)

| Suite | Total | Passés | Échoués | Ignorés | Durée |
|---|---|---|---|---|---|
| Tests de l'inc. 7 + tests évolués (`tests/inc7 ca21 ca24 ca25 ca36 ca41 inc5-accessibility inc5-ca41 inc6`), Chromium + WebKit | 142 | 142 | 0 | 0 | 8,2 min |
| Suite complète finale `npx playwright test` (Chromium + WebKit : 134 anciens + 70 nouveaux) | 204 | 203 | 1 (CA39 Chromium, attendu) | 0 | 24,7 min |

Backend redémarré à neuf avant ces deux exécutions. **CA39 Chromium** : même étape et même message que l'état de référence (R4-1) : `expect(getByText('Hors ligne')).toBeVisible()`, ligne 93, `Received: <element(s) not found>`, timeout 15000 ms. Logs : `frontend/e2e/evidence-inc7/inc7-and-evolved-142-pass.txt`, `full-suite-final-203of204.txt` (autres journaux, §6). Commandes (depuis `frontend/e2e`) : `npx playwright test tests/inc7 tests/ca21 tests/ca24 tests/ca25 tests/ca36 tests/ca41 tests/inc5-accessibility tests/inc5-ca41 tests/inc6 --reporter=list` ; `npx playwright test --reporter=list`.

Preuves visuelles (`frontend/e2e/evidence-inc7/`, produites par `manual/inc7-evidence.mjs`, PNG non versionnés) : `01-connexion-entree-coureur.png`, `02-connexion-entree-benevole.png`, `03-connexion-401-aide-type-de-compte.png`, `04-inscription-autonome.png`, `05-compte-cree-connecte-en-tete.png`, `06-scan-un-seul-se-connecter.png` ; `.txt` gardés : `07-cache-storage-ecrans-connexion-inscription.txt` (21 fichiers JS, 0 `admin-*.js`), `header-flash-probe.txt`.

## 4. Échecs et bugs détectés

| ID | Test | Attendu | Obtenu | Reproduction | Sévérité | Preuve |
|---|---|---|---|---|---|---|
| PRE-1 | INC4-CA39 / INC6-CA7 (Chromium) | « Hors ligne » visible | Échec identique à celui de l'inc. 6 : `expect(getByText('Hors ligne')).toBeVisible()`, ligne 93, `Received: <element(s) not found>`, timeout 15000 ms (LIM-E2E-1, R4-1, R5-3) | `npx playwright test tests/ca39 --project=chromium` | Connue, inchangée | `evidence-inc7/` (journaux de suite) |
| OBS-E2E-7A | INC7-CA14 (RG4) | Zone « compte » conforme dès l'affichage de l'écran | **Défaut applicatif mineur** : au chargement direct (rechargement, lien externe, favori) de `/scan`, `/connexion`, `/compte/connexion` et `/inscription`, l'en-tête affiche une image (une frame) avec « Se connecter » et « Créer un compte », puis se corrige (le signal d'URL est initialisé à `/` avant la fin de la première navigation, `app.ts`). Sur `/scan`, deux liens « Se connecter » coexistent donc brièvement : risque d'échec en mode strict pour les tests qui cliquent « Se connecter » sur `/scan` immédiatement après un `goto` (15 fichiers). Cause trouvée par un échec intermittent de CA14 sous WebKit (réponse tardive de la zone « compte ») | `node manual/probe-header-flash.mjs http://localhost:8080` | Faible (état stable conforme à RG4) ; à arbitrer | `evidence-inc7/header-flash-probe.txt` |

Aucun autre bug applicatif. Les tests CA14 attendent explicitement l'état stable (`expect.poll`) ; ce n'est pas un retry aveugle : le contrôle final reste une égalité exacte des liens, mais un état transitoire fautif ne serait pas détecté par le test, d'où le signalement ci-dessus.

## 5. Modifications du patrimoine existant (démarche N1 : soumises à l'accord de l'agent fonctionnel)

**Aucun test supprimé, désactivé (`skip`, `fixme`), ignoré ni assoupli. Aucune catégorie C.** Aucune modification du code de production.

### Catégorie A (mécanique de parcours, assertions conservées à l'identique)

| # | Fichier, test | Avant | Après | Motif |
|---|---|---|---|---|
| A1 | `fixtures/ui.ts` | | ajout de `chooseStaffEntry` (choix de « Bénévole » et attente du champ « Nom d'utilisateur ») et `submitLogin` | Utilitaires, aucun test existant touché |
| A2 | `ca24-login-roles.spec.ts`, « identifiants admin invalides » et « connexion admin » | `goto('/connexion')` puis « Nom d'utilisateur » | `chooseStaffEntry` inséré entre les deux | RG1 : sans `retour` sous `/admin` ou `/scan`, « Coureur » est présélectionnée. Assertions (stockage, « Identifiants invalides », titre) inchangées |
| A3 | `ca25-credentials-storage.spec.ts`, 1er test (ADMIN) | idem | idem | idem ; assertions de stockage et « Page introuvable » inchangées |
| A4 | `ca36-admin-crud.spec.ts`, helper `loginAdmin` local | sans `retour` : `goto('/connexion')` | sans `retour` : `chooseStaffEntry` ; avec `retour` : inchangé | RG1 |
| A5 | `inc6-ca5-ca8-admin-access.spec.ts`, `loginAdminFromConnexion` | idem | `chooseStaffEntry` inséré | RG1 ; `h1 « Connexion »` inchangé |
| A6 | `inc6-ca10-ca11-staff-login.spec.ts`, CA10 | | **ajout** de l'assertion de l'aide « Vérifiez le type de compte choisi » (D1-bis) ; aucune ligne changée | Additif prévu par la spec |
| A7 | `inc6-ca10-ca11-staff-login.spec.ts`, CA11 | `auditNoAdminLink(..., { scanLoginLinkAllowed: false })` (deux appels) | `auditNoAdminLink(..., ANONYMOUS_LOGIN_SCREEN_LINKS)` | L'utilitaire évolue (B1) ; sur ces écrans, l'en-tête a désormais « Créer un compte » (seul lien admis en plus) ; « aucun `/admin`, aucun « administrateur » » conservé |
| A8 | `ca21-routes.spec.ts` | 3 routes statiques | + `/inscription` (« Créer un compte ») et `/compte/connexion` (« Connexion ») ; rechargement inclus | Additif |
| A9 | `ca41-accessibility.spec.ts` | `/connexion` (Coureur) | idem **conservé**, + entrée « Bénévole » et `/inscription` | Additif (CA22) |

### Catégorie B (attendu changé par une règle de l'inc. 7 ; remplacement aussi strict)

| # | Fichier, test | Avant | Après | RG |
|---|---|---|---|---|
| B1 | `fixtures/admin-separation.ts`, `auditNoAdminLink` (+ constantes `ANONYMOUS_*`, `RUNNER_CONNECTED_LINKS`, `headerAccountText`) | option `scanLoginLinkAllowed` : aucun lien `/connexion` sauf « Se connecter » de `/scan` | l'option devient `{ loginLinks, createAccountLinks }` : **égalité exacte** des textes des liens vers `/connexion` et `/inscription` attendus pour l'écran (donc aussi stricte : tout lien en trop ou doublon échoue) ; contrôles « aucun lien `/admin` », actions de formulaire, « aucun texte, lien ou bouton Administration » **inchangés** | RG4, CA14 |
| B2 | `inc6-ca2-no-admin-link.spec.ts` | zone d'en-tête sans lien de connexion ; phase coureur : mêmes audits | zones attendues par écran (CA14) ; `/inscription` (écran autonome) n'est **pas** ajoutée à ce fichier : elle est auditée dans `inc7-ca14-ca15-header-and-admin.spec.ts` (CA14) ; phase coureur : « Connecté : {pseudo} » exact, ni « Se connecter » ni « Créer un compte » en en-tête (le bandeau « Se connecter » de `/scan` reste, connexion staff) ; liens Courses, Mes inscriptions, Scan (2 « Scan » sur `/`) inchangés | RG4 |
| B3 | `inc5-accessibility.spec.ts` ligne 32 | `toHaveText('Connexion coureur')` | `toHaveText('Connexion')` ; axe inchangé | RG3 |

Tests listés par la spec « sans changement » (ca26, ca27 à ca31, ca37, ca39, ca40, ca42, ca44, `inc5-ca41`, `inc6-ca3-ca4`, `inc6-ca6`, `ca34-ca35`) : **aucune modification**, tous verts (voir §3). `ca39` : aucune requête réseau supplémentaire de l'en-tête observée (même échec Chromium que l'inc. 6).

### Soumis à l'agent fonctionnel (récapitulatif)
1. A2 à A5, A7 : choix explicite de « Bénévole » (catégorie A).
2. A6, A8, A9 : ajouts.
3. B1, B2, B3 : `auditNoAdminLink` et `inc6-ca2` (zone « compte » exacte par écran) ; `inc5-accessibility` (h1).
4. Lecture de « une seule E21 » (CA12 b), voir §2.
5. OBS-E2E-7A (§4).
6. `.gitignore` racine : ajout de `frontend/e2e/evidence-inc7/*.png` (captures régénérables par `manual/inc7-evidence.mjs`).

## 6. Tests instables ou en quarantaine

Aucun test en quarantaine, `retries: 0`. Historique honnête :
- Mise au point : CA12 (b) comptait deux E21 (celle de `/compte` incluse) : défaut de test corrigé (comptage séparé). CA14 (bénévole puis admin) : mon attendu pour `/inscription` était faux : corrigé.
- CA14 anonyme, WebKit : un échec intermittent (zone « compte » lue avant sa mise à jour), cause établie (OBS-E2E-7A) ; attente explicite ajoutée ; 12/12 en répétition.
- Première suite complète (204 tests) : 3 échecs : CA39 Chromium (attendu) ; **CA31 Chromium** (`getByText('2 en attente')` non trouvé, ligne 98 ; test non modifié, non reproduit : PASS Chromium et WebKit à la réexécution isolée) ; **CA20 WebKit** (`navigator.serviceWorker.controller` non nul après rechargement, 60 s : même motif que l'observation INC4-CA38 WebKit ; 6/6 PASS en répétition ; cause traitée : `serviceWorker.ready` attendu avant le rechargement). Journal : `evidence-inc7/full-suite-run1-3fail.txt`.
- Seconde suite complète : interrompue après environ 2,8 h de durée affichée (poste ou serveur figé, 57 échecs en cascade à partir de `inc5-ca28`, 94 non exécutés) : résultat **écarté**, environnement redémarré (backend, java, node, chrome arrêtés), journal conservé : `evidence-inc7/full-suite-run2-aborted-environment.txt`. Aucun résultat de cette exécution n'est cité.
- Toute nouvelle occurrence de CA31 ou CA38 WebKit est à consigner.

## 7. Risques et limites

- **CA39 sous Chromium** : échec attendu inchangé (R4-1, R5-3, bloquantes avant déploiement).
- **OBS-E2E-7A** (§4) : à arbitrer.
- **CA17 [manuel]** (nginx : 6 requêtes passent, la 7e reçoit 429) : hors E2E (aucun nginx local) ; CA12 (d) simule le 429 par interception.
- Base H2 (RT1), pas de nettoyage des données de test.
- Service worker : CA20 est le seul test avec service worker actif ; Cache Storage ne liste que les URL, le journal réseau de Playwright ne voit pas les requêtes du service worker (OBS-E2E-6B) : le Cache Storage est la preuve décisive.
- WebKit : CL11 (ancienne PWA en cache, mise à jour) non rejoué pour l'inc. 7 (CL5 inc. 6 vérifié sous Chromium seulement) ; non couvert par un CA E2E de la spec.
- Le journal de la première suite montre une durée de 19,6 min pour 204 tests.

## 8. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : GO / GO sous réserves / NO-GO
- **Réserves ou motifs** :
- **Actions correctives exigées** :
- **Date** :
