# Rapport de test : INC-5 (e2e)

- **Date** : 2026-09-29
- **Agent auteur** : test-e2e-frontend
- **Version / commit testé** : branche `feature/increment-5-comptes-pseudo`, working tree (dernier commit `2774bdf`, rien de commité par cet agent). Aucune modification du code de production.
- **Environnement** :
  - Backend : `mvn -B -o spring-boot:run -Dspring-boot.run.profiles=test -Dspring-boot.run.useTestClasspath=true` (lance aussi le build de la PWA depuis `frontend/src`), profil `test`, H2 en mode PostgreSQL en mémoire, comptes `admin-test` et `scanner-test`, clôture de yard à 500 ms. Variables et commande identiques à `INC-4-e2e.md`, section 3. Le backend est arrêté en fin de session.
  - Front : PWA servie par Spring Boot sur `http://localhost:8080`.
  - Outil : Playwright 1.55.1, `@axe-core/playwright` 4.13.0, Chromium et WebKit. Aucune nouvelle dépendance.

## 1. Périmètre

CA `[E2E]` de `docs/specs/increment5.md` (révision 5) : **CA25, CA26, CA28, CA37, CA38, CA41, CA44** (liste vérifiée dans la spec, ce sont les seuls marqués `[E2E]`). Non-régression : les 49 parcours E2E de l'incrément 4 (CA21 à CA44 inc. 4). Un test transverse d'accessibilité des nouveaux écrans a été ajouté (sans CA numéroté).

Tags : `@INC-5` et `@INC5-CA<k>` (`@INC5-A11Y` pour l'accessibilité). Sous-ensemble `@smoke` : CA25, CA26, CA28, CA37, CA38, CA44.

## 2. Couverture exigences ↔ tests

| Exigence | Critère d'acceptation | Tests | Résultat (Chromium / WebKit) | Écart ? |
|---|---|---|---|---|
| INC5-CA25 | Inscription, réaffichage du QR (RG17, RG7, RG11) | `e2e/tests/inc5-ca25-registration-qr.spec.ts` : mots de passe différents (aucune E3), création (pseudo saisi `Lievre-{run}` affiché en minuscules, QR), connexion `/compte/connexion`, `/compte` (QR décodé = qrToken), second compte même pseudo (detail 409 égal au texte affiché, lien « J'ai déjà un compte ») | PASS / PASS | Observation OBS-E2E-1 |
| INC5-CA26 | Réinitialisation par l'admin (RG14, RG17) | `inc5-ca26-admin-reset.spec.ts` : colonne Pseudo en minuscules, Annuler sans requête, Confirmer = 1 requête E22 (204), ancien mot de passe « Identifiants invalides », nouveau accepté, sans demande de changement | PASS / PASS | Non |
| INC5-CA28 | Inscription avec un compte existant (RG8, RG17, CL1) | `inc5-ca28-existing-account.spec.ts` : lien, connexion, retour, « Vous êtes connecté en tant que lievre-{run} », 1 requête E20 (201), 0 E3, `/compte` liste R et R' | PASS / PASS | Non |
| INC5-CA37 | Changement de mot de passe (RG17, RG19, RG21) | `inc5-ca37-password-change.spec.ts` : 1 requête E23 (204), rechargement sans reconnexion, pseudo minuscules, déconnexion, ancien refusé, nouveau accepté, aucun mot de passe en clair dans localStorage, sessionStorage, cookies, IndexedDB, Cache Storage, URL | PASS / PASS | Observation OBS-E2E-2 |
| INC5-CA38 | Suppression d'un compte par l'admin (RG17, RG20) | `inc5-ca38-admin-delete-account.spec.ts` : confirmation « Supprimer le compte lievre-{run} ? », Annuler sans requête, 1 requête E24 (204), coureur conservé, Pseudo vide, « Coureur n°{bib} », connexion refusée | PASS / PASS | Non |
| INC5-CA41 | Affichage d'un 429 (RG23, CL20) | `inc5-ca41-too-many-attempts.spec.ts` (2 cas) : E3 puis E21 interceptées en 429 à corps HTML, message « Trop de tentatives. Réessayez dans une minute. », 1 seule requête | PASS / PASS | Non |
| INC5-CA44 | Écran « Comptes » (RG17, RG24, RG14, RG20, CL21) | `inc5-ca44-admin-accounts.spec.ts` : lien « Comptes », recherche `-{run}` (1 requête E25, 2 lignes), réinitialisation (Annuler, puis E22 204), suppression (E24 204), liste = `lievre-{run}`, recherche `Oublie-{run}` = « Aucun compte » | PASS / PASS | Non |
| (transverse) | Accessibilité de base des écrans ajoutés | `inc5-accessibility.spec.ts` : axe-core WCAG A/AA sur `/inscription/{id}`, `/compte/connexion`, `/compte`, `/admin/comptes` : 0 violation serious/critical | PASS / PASS | Non |

Exigences E2E sans test : **aucune**. CA27, CA35, CA36 sont `[front-unit]` (hors périmètre de cet agent).

## 3. Résultats d'exécution (réels)

| Suite | Total | Passés | Échoués | Ignorés | Durée |
|---|---|---|---|---|---|
| Tests de l'incrément (9 cas x 2 navigateurs = 18) | 18 | 18 | 0 | 0 | inclus ci-dessous (Chromium seul : 8 puis 9 cas en 1,1 min) |
| Suite complète `npx playwright test` (Chromium + WebKit, dernière exécution) | 116 | 115 | 1 (CA39 Chromium, préexistant) | 0 | 17,4 min |

Historique honnête des exécutions :
1. Après le seul changement de fixture (A), Chromium seul : 26 passés, 23 échoués (tous à cause des noms affichés, du libellé « Nom » et de la fixture), log `frontend/e2e/baseline-after-fixture-chromium.log`.
2. Première suite complète après adaptations : 113/116. Échecs : CA36 (Chromium et WebKit, voir adaptations A2 ci-dessous, corrigés) et CA39 Chromium. Log `frontend/e2e/full-inc5-run1-3fail.log`.
3. Après correction de CA36, rejouée seule : 2/2. Suite complète finale : **115/116**, log `frontend/e2e/full-inc5-final.log`. Le seul échec est CA39 sous Chromium.

Échec CA39 (Chromium) : identique à celui constaté sur 4 exécutions à l'incrément 4 (`INC-4-e2e.md`, reprise, LIM-E2E-1 : E4 obtient un 200 malgré `setOffline(true)` avec le service worker actif). L'assertion échouante (« Hors ligne ») n'a pas été touchée par cette validation. Aucune régression nouvelle n'est constatée.

Commandes exécutées (depuis `frontend/e2e`) : `npx playwright test --project=chromium tests/inc5 --reporter=list` ; `npx playwright test tests/ca36` ; `npx playwright test --reporter=list` (suite complète, deux projets). Traces, captures, vidéos des échecs : `frontend/e2e/test-results/` et `playwright-report/` (ignorés par git, `.gitignore` de `frontend/e2e/`).

## 4. Échecs et bugs détectés

Aucun bug applicatif nouveau bloquant. Observations :

| ID | Test | Attendu | Obtenu | Reproduction | Sévérité | Preuve |
|---|---|---|---|---|---|---|
| OBS-E2E-1 | INC5-CA25 (second compte, même pseudo) | Le lien « J'ai déjà un compte » (CA25) | Il s'affiche deux fois après le 409 : celui, permanent, du formulaire et celui du bloc d'erreur (`showAccountLink`). Le test vérifie sa présence avec `.first()` sans figer le doublon | Ouvrir `/inscription/{R}`, créer un pseudo déjà pris | Mineure (doublon d'un lien pour lecteurs d'écran) | Reproductible (erreur de mode strict Playwright lors de la première exécution, constat consigné ici) |
| OBS-E2E-2 | INC5-CA37 | « Ni l'un ni l'autre mot de passe n'apparaît » | Avec « Rester connecté », IndexedDB contient la valeur `Authorization` (base64 de `pseudo:motdepasse`), donc le mot de passe y est récupérable. C'est prévu par RG21 (« la valeur `Authorization` est enregistrée dans IndexedDB »). Aucun mot de passe **en clair** n'est stocké, ni dans l'URL. Le test vérifie la valeur remplacée par la nouvelle après E23 (avec le pseudo en minuscules, pas la saisie) | Se connecter avec « Rester connecté », inspecter IndexedDB | Information : à confirmer par l'agent fonctionnel (interprétation « en clair » de CA37) | Test `inc5-ca37-password-change.spec.ts` |
| PRE-1 | INC4-CA39 (Chromium) | « Hors ligne » visible | Échec préexistant, voir section 3 | Suite complète Chromium | Connue (LIM-E2E-1) | `full-inc5-final.log` |

## 5. Modifications du patrimoine existant (N1 : soumises à l'accord de l'agent fonctionnel)

**Aucun test supprimé, désactivé, ignoré ni assoupli.** Toutes les assertions restent des égalités ou des présences exactes.

### Catégorie A (fixture / jeu de données, assertions non touchées)

| # | Fichier | Adaptation |
|---|---|---|
| A1 | `e2e/fixtures/api.ts` | `register(raceId, hint)` crée un compte : E3 avec `{"pseudo","password":"motdepasse-1"}`, pseudo dérivé de `hint` (minuscules, sans accent, `[a-z0-9-]`, 16 caractères au plus) suffixé d'un aléa (unicité globale RG5, base partagée entre exécutions). Il vérifie que `name` et `pseudo` renvoyés égalent le pseudo envoyé (RG18), sinon échec. Types `RegistrationResponse.pseudo`, `AdminRunnerResponse.accountId/pseudo` ajoutés. Nouvelles méthodes : `registerAccount`, `registerExistingAccount` (E20), `accountMeStatus` (E21), `adminAccounts` (E25), `deleteRunner` (E16) |
| A2 | `e2e/tests/ca36-admin-crud.spec.ts` | Sélecteurs de boutons : `{ name: 'Supprimer', exact: true }` (3 endroits), car la carte du coureur porte désormais aussi « Supprimer le compte » (RG17), qui correspondait à la sous-chaîne (mode strict). Même bouton visé, aucune valeur attendue changée. Et le filtre des vignettes QR passe de `hasText: String(runner.bib)` à `hasText: runner.name` (le dossard `1` ou `2` en sous-chaîne devenait ambigu avec le suffixe aléatoire du pseudo). Le contrôle « QR décodé = qrToken de ce coureur » est inchangé |
| A3 | `fixtures/ui.ts`, `fixtures/storage.ts` | Nouveaux utilitaires (ajouts, pas d'adaptation) |

### Catégorie B (attendu changé par une règle de l'inc. 5 ; remplacement aussi strict)

| # | Fichier, test | Avant | Après | RG |
|---|---|---|---|---|
| B1 | `ca21-routes` « /coureurs/{id} affiche le détail » | `toContainText('Alice Routes')` (x2) | `toContainText(runnerName)` (nom affiché renvoyé par E3, égal au pseudo envoyé) | RG18 |
| B2 | `ca26-no-auth-to-public` | `'Public Runner'` | `registration.name` | RG18 |
| B3 | `ca27-scan-camera` (2 assertions) | `Dossard {bib} — Alice — yard 1` | `Dossard {bib} — ${alice.name} — yard 1` | RG18 |
| B4 | `ca28-scan-manual` | `Dossard {bib} — Bob` | `— ${bob.name}` | RG18 |
| B5 | `ca29-offline-fifo` | `— Bob Fifo` | `— ${bob.name}` | RG18 |
| B6 | `ca30-backoff` | `— Eve Backoff` | `— ${eve.name}` | RG18 |
| B7 | `ca31-rejection` (2 assertions) | `— Dan Rej`, `— Dan Rej — yard 3` | `${dan.name}` | RG18 |
| B8 | `ca32-dashboard` (toutes les lignes `runnerRow`, le vainqueur) | `'Alice Dash'`, `'Bob Dash'`, `'Chloé Dash'`, `Vainqueur : dossard 1 — Alice Dash — 2 tours` | `alice.name`, `bob.name`, `chloe.name`, gabarit avec `alice.name` | RG18 |
| B9 | `ca33-freshness` | `getByText('Runner Fresh')` | `getByText(runner.name)` | RG18 |
| B10 | `ca34-ca35-dnf-reintegration` | `hasText: 'Bob Adm'`, `— Bob Adm` | `bob.name` | RG18 |
| B11 | `ca36-admin-crud` | `hasText: 'Coureur Un'`, `'Coureur Deux'` (x5) | `runner1.name`, `runner2.name` | RG18 |
| B12 | `ca37-parallel-races` (6 assertions) | `Alice P1`, `Alice P2` | `aliceP1.name`, `aliceP2.name` | RG18 |
| B13 | `ca40-clock-correction`, `ca42-scan-feedback` (2), `ca43-runner-detail`, `ca41-accessibility` | `Runner Clock`, `Runner Feedback`, `Runner Silence`, `Runner Detail`, `Runner A11y` | `runner.name` / `a11yRunner.name` | RG18 |
| B14 | `ca44-effective-emitter` (4 assertions) | `Dossard 1 — Alice — yard 1`, `Bob`, `Chloé`, `Dan` | `${alice.name}`, etc. | RG18 |
| B15 | `ca22-registration` (3 cas réécrits) | `getByLabel('Nom')` ; saisie `Alice`, `Bob`, `Chloé`, `Decodage QR` ; `getByText('Alice')` ; `runner.name === 'Decodage QR'` | `getByLabel('Pseudo')` + mot de passe + confirmation ; pseudos `Alice-{run}`, `Bob-{run}`, `Chloe-{run}`, `Decodage-QR-{run}` ; `getByText('alice-{run}', exact)` ; `runner.name === 'decodage-qr-{run}'`. Dossards 1, 2, 3, présence du QR, décodage = qrToken : inchangés | RG17, RG7, RG2, RG18 |
| B16 | `ca23-registration-errors` (4 cas) | `getByLabel('Nom')`, `'   '` blanc, `#runner-name-error` | `getByLabel('Pseudo')`, mêmes saisies (blanc), `#registration-pseudo-error`, + mots de passe valides ; test renommé « pseudo blanc » ; double clic (1 requête E3), inscriptions fermées, course inconnue : inchangés | RG17, RG7 |

Note : les noms de fixtures (« Alice Dash ») ne peuvent plus figurer dans l'interface (RG2 interdit espaces, accents et majuscules stockées). Les valeurs attendues sont donc les pseudos réellement créés.

### Catégorie C (assertion retirée, affaiblie, test désactivé)

**Aucune.** Réserve sur B15/B16 : le renommage « nom blanc » en « pseudo blanc » (titre de test) suit le changement d'objet du champ ; l'agent fonctionnel peut le reclasser en C s'il l'estime.

## 6. Tests instables ou en quarantaine

Aucun test en quarantaine. Un échec de mise au point en cours de session (CA37 : le pseudo mémorisé après E23 est en minuscules, alors que le test attendait la casse saisie) a été traité par une correction de l'attente, pas par une relance. Aucune stabilité multi-exécution n'a été mesurée pour les nouveaux cas au-delà de 2 exécutions Chromium (`tests/inc5`) et de la suite complète finale (Chromium + WebKit).

## 7. Risques et limites

- CA39 Chromium reste en échec (préexistant, LIM-E2E-1).
- CA41 : le 429 est simulé par interception réseau (nginx absent) : le service worker est bloqué pour que l'interception soit fiable (LIM-E2E-1). Le comportement réel de la limitation de débit relève de CA40 [manuel] sur le VPS.
- CA25/CA37 : les captures « Rester connecté 24 h » (expiration glissante) ne sont pas vérifiées de bout en bout en E2E (couvertes en `front-unit`).
- Base H2 (RT1), aucun PostgreSQL réel.
- Pas de nettoyage des données de test (comme à l'inc. 4, RG58) : la base en mémoire du backend contient les comptes et courses créés.
- Un incident d'outillage (classificateur de sécurité de l'outil Bash indisponible pendant l'exécution) a retardé la session, sans effet sur les résultats.

## 8. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : GO / GO sous réserves / NO-GO
- **Réserves ou motifs** :
- **Actions correctives exigées** :
- **Date** :
