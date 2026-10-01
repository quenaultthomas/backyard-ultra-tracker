# Revue de cohérence du patrimoine : INC-7 (écran de connexion unique et inscription autonome)

- **Date** : 2026-10-01
- **Agent** : revue-coherence-patrimoine, **joué par un agent générique faute d'enregistrement de l'agent dans la session** (même consigne, mêmes contrôles ; lecture seule, seul ce rapport est écrit)
- **Périmètre examiné** : branche `feature/increment-7-connexion-inscription`, HEAD `97f5171` + arbre de travail non commité (27 fichiers suivis modifiés ou supprimés, 30 chemins non suivis).
- **Entrées** : `docs/specs/increment7.md` (révision 2, décisions confirmées), `docs/tests/PATRIMOINE.md`, `INC-7-integration.md`, `INC-7-e2e.md`, `INC-6-coherence.md` (modèle, réserves), `INC-6-synthese.md` (réserves), `git diff main` / `git status`.

## 1. Synthèse

- Constats : **7**, dont **0 bloquant**, **1 majeur**, **6 mineurs**.
- Véracité des rapports : aucun écart. Chiffres du coordinateur (surefire 590/0, failsafe 231/0, vitest 322/322, BUILD SUCCESS, JaCoCo OK) cohérents avec les totaux par classe ; échantillons rejoués par cet agent : vitest 322/322, surefire ciblé 45/0, failsafe ciblé 35/0, E2E 68/68 (Chromium + WebKit) puis 55 passés + 1 échec (CA39 Chromium, identique à l'état de référence).
- Patrimoine existant : aucun test supprimé, désactivé, ignoré ni assoupli ; le nombre de `test(` / `@Test` est inchangé dans les 12 fichiers de test existants adaptés (seuls ajouts : +1 `PwaStaticResourcesIT`, +2 `admin-separation.spec.ts`). Aucune catégorie C constatée.
- Couverture : chaque RG, CL et CA de la spec 7 a au moins un test actif, hors volets prévus manuels (CA17 manuel, CL10). Réserves ouvertes (R4-1/R5-3, RT1/R5-2, R5-1, R5-6, R6-1, R6-5) non aggravées.
- Point d'arbitrage principal : OBS-E2E-7A (COH7-1) est un **défaut applicatif réel et reproductible**, que l'attente `expect.poll` de l'utilitaire d'audit empêche de voir.

## 2. Tableau des constats

| ID | Contrôle | Sévérité | Description | Preuve | Action recommandée |
|---|---|---|---|---|---|
| COH7-1 | C1 / C4 (RG4, CA14) | **Majeur** | **OBS-E2E-7A : défaut applicatif, pas une limite.** Au chargement direct de `/scan`, `/connexion`, `/compte/connexion`, `/inscription` (rechargement, lien externe, favori), la première image affiche « Se connecter » et « Créer un compte » avant de se corriger. Cause lue dans le code : le signal d'URL de l'en-tête est initialisé par `initialValue: this.router.url`, qui vaut `/` avant la fin de la première navigation. Sur `/scan`, deux liens « Se connecter » coexistent donc brièvement, contre la règle RG4 « un seul lien » (« Pas de doublon avec la page affichée »). L'état stable est conforme. Côté tests : `auditNoAdminLink` (`fixtures/admin-separation.ts`) attend désormais l'état stable par `expect.poll` avant ses contrôles exacts ; la vérification de RG4 / CA14 ne peut donc pas détecter l'état transitoire fautif (le rapport E2E le reconnaît). Les 15 fichiers E2E qui cliquent « Se connecter » sur `/scan` ont passé, mais restent exposés à un échec en mode strict si le clic suit un `goto` immédiat | `frontend/src/app/app.ts` (`currentUrl = toSignal(..., { initialValue: this.router.url })`) ; `frontend/e2e/evidence-inc7/header-flash-probe.txt` (6 lignes : Chromium et WebKit, première frame avec les deux liens sur `/scan`, `/connexion`, `/inscription`) ; `fixtures/admin-separation.ts` (`expect.poll`) ; `INC-7-e2e.md` §4, §6 ; échec intermittent CA14 WebKit consigné §6 | L'agent fonctionnel arbitre : (a) corriger (initialiser le signal sur l'URL réelle du navigateur, ou ne rien afficher avant la première `NavigationEnd`) puis ajouter un test qui observe la première frame (par exemple l'équivalent de `probe-header-flash.mjs` en test) ; (b) accepter explicitement la limite et amender RG4 / CA14 (« état stable »). Dans les deux cas, le poll de l'utilitaire reste un assouplissement de fait de la lecture, à noter |
| COH7-2 | C1 (RG7 / D10) | Mineur | Texte d'aide : la spec se contredit, le code suit RG7 à la lettre. RG7 (l. 176) impose « Bénévole : votre compte est créé par l'organisateur. » ; D10 (l. 379) dit « libellé « Bénévole » seul, ni « organisateur » ni « administrateur ». » D10 vise le libellé de l'entrée de connexion, mais la formulation est ambiguë. Le code affiche le texte de RG7 et le teste littéralement. « organisateur » est aussi présent ailleurs (formats, `registration-page`, `account-creation-page` l. 28 : texte de RG17 inc. 5), sans lien avec « administration ». Le point reste ouvert dans `PATRIMOINE.md` | `frontend/src/app/pages/account/account-creation-page.ts:30` ; `frontend/src/app/core/inc7-login-routes-sources.spec.ts:113` ; spec l. 176 et l. 379 ; `PATRIMOINE.md` (ligne « Mise à jour validation », fin) | L'agent fonctionnel tranche et corrige la spec (RG7 ou D10). Si D10 l'emporte : changer le texte, le test unitaire (l. 113) et la chaîne attendue par CA20 (`Bénévole : votre compte est créé…`, `inc7-ca20-no-admin-chunks.spec.ts`) |
| COH7-3 | C1 (CA12 b) | Mineur | Lecture de « une seule E21 » : l'agent E2E l'interprète comme « une seule E21 de connexion émise depuis `/inscription` » ; le test asserte 1 E21 depuis l'écran de création et 2 au total (la page `/compte` émet sa propre E21 pour sa liste). Lecture raisonnable (la seconde est le chargement de `/compte`, pas une connexion), mais l'énoncé de CA12 (b) n'est pas littéral. Le test est précis (E26 : 1 requête, 201, sans `Authorization`) | `frontend/e2e/tests/inc7-ca12-ca13-account-creation.spec.ts` l. 36-77 ; spec CA12 (b) ; `INC-7-e2e.md` §2 note E21 | L'agent fonctionnel confirme la lecture et précise CA12 (b) (« une seule E21 de connexion automatique ») |
| COH7-4 | C2 | Mineur | `PATRIMOINE.md`, section « Incrément 7 », contient des mentions périmées dans le paragraphe « Mise à jour validation » et le suivant : « E2E adaptés non encore écrits (par exemple `inc5-accessibility.spec.ts` attend encore « Connexion coureur ») » (l'adaptation B3 est faite) ; « texte d'aide RG7 « organisateur » vs D10 » (renvoie à COH7-2) ; « Écarts de testabilité … à traiter par le développeur » alors que `core/login-entry.ts` et `core/header-account-zone.ts` existent et que les specs passent ; « build complet … failsafe 221/0/0 (fin 15:54) » suivi de « 231/0/0 (16:14) » dans la même ligne ; ligne `INC7 (admin-separation, autres tests)` : « RG5 lit `dist/` (build existant, non reconstruit à ce stade) ». Les lignes de résultat à jour existent ailleurs : le référentiel se contredit sur l'état | `PATRIMOINE.md` (diff main, section INC-7, paragraphes après le tableau) ; `ls frontend/src/app/core/login-entry.ts header-account-zone.ts` | Mettre à jour ou barrer ces mentions (testeur), sans toucher aux lignes de résultats |
| COH7-5 | C5 / C3 | Mineur | Inexactitude du rapport E2E : §5 B2 dit que `inc6-ca2-no-admin-link.spec.ts` a reçu « `/inscription` ajoutée à la liste » (la spec §8 le demandait). Le diff du fichier ne contient que `/inscription/{id}` ; l'écran autonome `/inscription` est audité dans `inc7-ca14-ca15-header-and-admin.spec.ts` (entrée `/inscription`, `ANONYMOUS_CREATE_SCREEN_LINKS`). La couverture existe (CA14), mais l'énoncé du rapport est faux | `git diff main -- frontend/e2e/tests/inc6-ca2-no-admin-link.spec.ts` (aucune occurrence de `'/inscription'` seul) ; `inc7-ca14-...spec.ts` l. 145 ; `INC-7-e2e.md` §5 B2 | Corriger la phrase du rapport, ou ajouter l'écran à `auditPublicPages` comme le prévoyait la spec §8 |
| COH7-6 | C4 (parasites) | Mineur | Fichiers non suivis à décider avant `git-publisher` : `frontend/e2e/evidence-inc7/` (6 `.txt` non ignorés, dont `full-suite-run2-aborted-environment.txt`, 77 Ko, journal d'une exécution écartée ; 6 `.png` ignorés par la règle ajoutée au `.gitignore`) ; `frontend/e2e/manual/inc7-evidence.mjs` (régénération des captures) et `manual/probe-header-flash.mjs` (sonde de diagnostic ponctuelle). Précédent inc. 6 : les `.txt` d'`evidence-inc6/` et les scripts `manual/` sont suivis. `.angular/` est bien ignoré (règle `.gitignore:10`, déjà présente), `test-results/` et `playwright-report/` aussi (`frontend/e2e/.gitignore`) ; `.gitignore` racine : +3 lignes (commentaire et règle `evidence-inc7/*.png`), aucune autre modification. Aucun fichier de résultats parasite non ignoré après mes exécutions | `git status --short` ; `git ls-files \| grep -E "evidence\|e2e/manual"` ; `git check-ignore -v .angular` | Décider : garder les `.txt` utiles (au minimum `full-suite-final-203of204.txt`, `header-flash-probe.txt`, cache storage), écarter ou ignorer le journal de l'exécution abandonnée (le rapport l'écarte déjà) ; garder la sonde seulement si elle sert de base au test de COH7-1 |
| COH7-7 | C6 / réserves | Mineur | Réserves de l'inc. 6 « avant `git-publisher` » sans trace de traitement dans cet incrément : R6-2 (renforcement `ca24`, boucle sur liste vide) et R6-3 (`ca39` WebKit, assertion sur `/admin`). `ca39` n'est pas modifié (diff vide) ; `ca24` ne l'est que par `chooseStaffEntry` (boucle `for … of adminRequests` toujours sur liste vide dans « connexion scanner (mémorisée) puis /admin »). Non aggravé, mais l'échéance annoncée est antérieure à la publication. Hors périmètre de la mission, consigné pour mémoire | `PATRIMOINE.md` (ligne INC-6 de la table des verdicts) ; `git diff main --stat` | L'agent fonctionnel confirme si R6-2 et R6-3 étaient à lever dans cet incrément ou sont reportées |

## 3. Détail des contrôles

### 3.1 C1 : couverture exigences et critères

| Exigence | Tests actifs (constat par lecture du code et du patrimoine) |
|---|---|
| RG1 | `login-entry.spec.ts` (35), CA8, CA10, CA22, CA23 E2E |
| RG2 | `login-entry.spec.ts` (`loginDestination`), CA9, CA10, CA11 |
| RG3 | `inc7-login-routes-sources.spec.ts` (8), CA16, CA22 |
| RG4 | `header-account-zone.spec.ts` (27), `admin-separation.spec.ts` (2 tests), CA14 (voir COH7-1) |
| RG5 | CA1 à CA6, CA18 : slice (21), service (7), `AccountCreationSingleSourceReviewTest` (4), `PublicAccountCreationIT` (9) |
| RG6 | `DeployConfigIT.ca40` (config), CA12 (d) (429 simulé) ; volet 429 nginx réel : manuel |
| RG7 | CA12, CA13, CA21 (GET parametrisé, HEAD) |
| RG8 | CA7 (IT), CA13 |
| RG9 | CA14 (accueil et tableau de bord sans connexion) |
| RG10 | CA5, CA15, CA19 (non-régression) |
| RG11 | CA20 (build de production, service worker actif, Cache Storage réel) |
| CL1 à CL13 | CA10 (CL1 à CL3), CA9 + CA23 (CL4), CA3 (CL5), CA4 (CL6, H2 seulement), CA7 (CL7), CA12 e (CL8), CA12 d + CA17 (CL9), CL10 manuel consigné, CA19 (CL11, CL12), CA14 (CL13) |

- Clause CA21 « `GET /nimporte-quoi` reste 401 » : **couverte**. `UnknownPathUnauthorizedIT#ca21_unknownPathStaysUnauthorized` (serveur embarqué réel) asserte 401 exactement, ni `index.html`, ni `WWW-Authenticate`. Rejoué ici : 1/1. `PwaStaticResourcesIT#ca3_unknownOrForbiddenPathsAreNeverServed` n'asserte que « jamais 200 » (inchangé).
- CA12 « une seule E21 » : voir COH7-3.
- Assertions pertinentes vérifiées par lecture : CA8 (innerText, `a[href]`, 0 requête `/api/` avant soumission), CA10 (stockages, IndexedDB, cookies), CA12 (comptage de requêtes E26 et E21), CA14 (égalité exacte des textes de liens par écran), CA20 (liste de blocs non vide exigée par le rapport, Cache Storage réel).
- Seules couvertures non automatisées : CA17 [manuel] (6 requêtes E26 passent, la 7e reçoit 429 : exploitant, voir R5-1), CL10 (essai manuel consigné), CL11 sous WebKit (non couvert par un CA E2E, non rejoué).

### 3.2 C2 : cohérence matrice et code

- Les tests cités existent : `PublicAccountCreationSliceTest`, `AccountCreationWithoutRaceTest`, `AccountCreationSingleSourceReviewTest`, `PublicAccountCreationIT`, `UnknownPathUnauthorizedIT`, 5 fichiers E2E `inc7-*`, 3 fichiers front-unit, ajouts à `PwaStaticResourcesIT`.
- Tags backend : `INC7-CA1`, `CA3`, `CA4`, `CA5`, `CA6`, `CA7`, `CA18`, `CA21` ; E2E : `@INC7-CA8` à `@INC7-CA16`, `CA20`, `CA22`, `CA23`. Tous visent des CA existants (CA1 à CA23). Aucun test tagué orphelin ; pas de tag pour CA2 (slice, sans tag `INC7-CA2` : nommage `ca2_*`, référencé par chemin dans la matrice, pas d'écart).
- Arithmétique des totaux : surefire 558 -> 590 = +32 (21 + 7 + 4) ; failsafe 219 -> 231 = +12 (9 + 1 + 1 HEAD + 1 cas `/inscription` paramétré) ; vitest 250 -> 322 = +72 (35 + 27 + 8 + 2) ; E2E 134 -> 204 = +70 (35 tests x 2 navigateurs). Cohérent avec les rapports.
- Mentions périmées : COH7-4.

### 3.3 C3 : intégrité du patrimoine existant

Recherche de `@Disabled`, `skip`, `fixme`, `.only`, `xit` dans `backend/src/test`, `frontend/e2e`, `frontend/src` : aucun test désactivé (seul résultat : `skip-link` dans le gabarit de `app.ts`, sans rapport).

| Test adapté | `test(`/`@Test` main -> maintenant | Catégorie | Motif écrit | Aussi strict ? |
|---|---|---|---|---|
| `admin-separation.spec.ts` | 15 -> 17 | B (en-tête, liste des pages), additifs | Spec §8 ; commentaires dans le fichier + `INC-7` PATRIMOINE | Oui : « aucun lien /admin », « aucune Administration », liens Courses, Mes inscriptions, Scan conservés ; liste fermée des cibles ajoutée ; seul `expect(loginLinks(template)).toEqual([])` est remplacé (amendement RG2 par RG4). Test de la liste des pages : `runner-login-page.ts` retiré de `arrayContaining` (fichier supprimé, RG3), contrôle « aucun lien admin » appliqué à tous les fichiers présents + exactement un écran « Créer un compte » |
| `NoTestEndpointsIT` | 2 -> 2 | B | `INC-7-integration.md` IT-1 | Oui : égalité exacte (26 méthodes, 21 motifs), `isEqualTo(Set)` conservé |
| `DeployConfigIT.ca40` | 2 -> 2 | B | IT-3 | Oui, plus strict : 3 `limit_req`, +1 assertion sur le `location =` E26 ; assertions E3, `/api/account/`, zones, 429 conservées |
| `PwaStaticResourcesIT` | 11 -> 12 | A (additif) | IT-4, IT-5 | Oui : valeur `/inscription` ajoutée au paramétré, test HEAD ajouté |
| `auditNoAdminLink` (`fixtures/admin-separation.ts`) | n/a | B | E2E B1 | Égalité exacte des liens `/connexion` et `/inscription` attendus par écran ; contrôles `/admin` et « Administration » inchangés. Réserve : attente `expect.poll` ajoutée (COH7-1) |
| `inc6-ca2-no-admin-link` | 5 -> 5 | B | E2E B2 | Plus strict (zone « compte » exacte, « Connecté : {pseudo} ») ; `expect(` 28 -> 31 lignes. Inexactitude de rapport : COH7-5 |
| `inc5-accessibility` (l. 32) | 4 -> 4 | B | E2E B3 | Oui : `Connexion coureur` -> `Connexion` (RG3), axe inchangé |
| `ca24`, `ca25`, `ca36`, `inc6-ca5-ca8` | 7, 7, 4, 7 inchangés | A | E2E A2 à A5 | Oui : seul `chooseStaffEntry` est inséré (RG1), `expect(` 20 -> 20, 18 -> 18, 29 -> 29, 20 -> 20 ; assertions de stockage et « Page introuvable » inchangées |
| `inc6-ca10-ca11-staff-login` | 8 -> 8 | A | E2E A6, A7 | Oui : `expect(` 35 -> 35 ; le CA10 gagne l'aide D1-bis dans un helper additif ; CA11 passe à `ANONYMOUS_LOGIN_SCREEN_LINKS` (égalité exacte, aucun `/admin`, aucun « administrateur », conservés) |
| `ca21-routes` | 11 -> 11 | A (additif) | E2E A8 | Oui : +2 routes statiques |
| `ca41-accessibility` | 4 -> 4 | A (additif) | E2E A9 | Oui : `/connexion` Coureur conservé, +Bénévole, +`/inscription` ; `expect(` 14 -> 15 |

- Catégories A/B motivées dans `INC-7-integration.md` §1 et `INC-7-e2e.md` §5 ; **aucune C**. Timeouts : aucune valeur de `timeout` modifiée dans les diffs ; `retries: 0` maintenu.
- Accord de l'agent fonctionnel (règle 2) : les adaptations sont toutes prévues par la section 8 de la spec (révision 2), approuvée par l'utilisateur ; les deux rapports les soumettent pour accord. L'accord formel se donne dans leur section « Verdict » (vide à ce stade, attendu).
- Aucun test existant des inc. 1 à 6 supprimé ; `AccessMatrixSliceTest`, `AccountFlowIT`, `ApiExceptionHandlerLoggingTest`, `InternalErrorLogsIT`, `V1ToV2LegacyDataHttpIT` non modifiés (aucun diff).
- Production hors périmètre de test : `PwaPaths` (+`/inscription`), `deploy/nginx` (+ `location = /api/public/accounts`), 3 nouveaux fichiers backend (`PublicAccountController`, 2 DTO). `ngsw-config.json`, `pom.xml`, `angular.json` : non modifiés.

### 3.4 C4 : qualité des tests

- Pas de test sans assertion ni assertion triviale relevé dans les nouvelles classes lues par échantillon (`PublicAccountCreationIT`, `UnknownPathUnauthorizedIT`, `inc7-ca12`, `inc7-ca14`). CA18 vérifie que des lignes de journal sont bien capturées (pas de passage à vide) ; son pouvoir discriminant n'a pas été démontré par mutation (limite consignée par l'agent d'intégration).
- Aucun test en quarantaine. Instabilités consignées honnêtement (CA14 WebKit, CA31 Chromium, CA20 WebKit, exécution complète n° 2 écartée) ; nouvelle occurrence de CA31 ou CA38 WebKit à consigner.
- Parasites : COH7-6.

### 3.5 C5 : véracité des rapports (exécution réelle par cet agent)

| Élément | Annoncé | Constaté par cet agent |
|---|---|---|
| vitest | 322/322 (27 fichiers) | **322 passés, 27 fichiers** (`npx vitest run`, 20:30) |
| surefire ciblé (`PublicAccountCreationSliceTest`, `AccountCreationWithoutRaceTest`, `AccountCreationSingleSourceReviewTest`, `PseudoNormalizationSourceReviewTest`, `-Dskip.npm -Dskip.installnodenpm -Djacoco.skip=true`) | 21 + 7 + 4 + 13 | **21 + 7 + 4 + 13 = 45, 0 échec, 0 ignoré** |
| failsafe ciblé (`PublicAccountCreationIT` 9, `UnknownPathUnauthorizedIT` 1, `NoTestEndpointsIT` 2, `DeployConfigIT` 2, `PwaStaticResourcesIT` 21) | tous verts | **35, 0 échec, 0 ignoré**, BUILD SUCCESS |
| E2E échantillon A (`tests/inc7-ca8 inc7-ca12 inc7-ca14 inc6-ca2 inc6-ca10 ca24 ca25 inc5-accessibility`, Chromium + WebKit, backend démarré avec la commande du rapport INC-4, build `dist` de 20:22) | (compris dans 142/142) | **68 / 68 passés** (4,0 min) |
| E2E échantillon B (`tests/inc7-ca20 inc7-ca16 ca39 ca21`) | CA39 Chromium en échec attendu | **55 passés, 1 échec** : CA39 Chromium, `getByText('Hors ligne')`, `Expected: visible`, `Received: <element(s) not found>` : même étape et même message que l'état de référence (R4-1) ; WebKit vert |
| E2E suite complète | 203/204 | Journal `evidence-inc7/full-suite-final-203of204.txt` relu : « 1 failed » (CA39 Chromium), « 203 passed (24.7m) » ; cohérent avec 134 + 70. **Suite non rejouée** (25 min) |
| 142 tests inc. 7 + évolués | 142/142 | Journal `inc7-and-evolved-142-pass.txt` relu : « 142 passed (8.2m) » ; échantillon A (68) rejoué |
| `clean verify` complet (surefire 590, failsafe 231, JaCoCo) | BUILD SUCCESS | Déclaré par le coordinateur (2026-10-01, 20:20-20:29) ; **non rejoué en entier ici** ; cohérence arithmétique vérifiée (§3.2) |

Notes de véracité : (1) `INC-7-integration.md` date son `clean verify` de 16:14, le coordinateur l'a rejoué à 20:20-20:29 : mêmes chiffres. (2) Les commandes citées existent et fonctionnent (`spring-boot:run` avec `useTestClasspath`, `npx playwright test`). (3) Écart d'énoncé : COH7-5 (rapport E2E, B2) ; aucun écart de résultat. (4) Backend de test arrêté, `java`, `node`, navigateurs : plus aucun processus ; port 8080 libre (aucun état LISTEN). (5) Aucune commande npm ou Maven lancée en parallèle sur `frontend/`, aucun `&` pour Maven (usage de `run_in_background`).

Aucun écart entre résultat annoncé et résultat constaté.

### 3.6 C6 : non-régression inter-incréments

- Backend : surefire 590 et failsafe 231 verts (déclarés), 45 + 35 rejoués ; `AccessMatrixSliceTest` (E1 à E25) et `AccessMatrixIT` non modifiés ; E26 a sa table (CA5). Aucun contrôleur existant modifié, aucune modification de `SecurityConfig`.
- Frontend : 322 vitest verts, les 250 antérieurs inchangés hors les 2 tests de `admin-separation.spec.ts` adaptés (B).
- E2E : 203/204 rapportés (journal relu), échantillons 68/68 et 55 + CA39 rejoués. Parcours inc. 4 à 6 modifiés (connexion « Bénévole », `Connexion` h1, zone « compte ») : adaptations A/B justifiées, aucune autre modification.
- Comportements modifiés par l'incrément : h1 « Connexion coureur » -> « Connexion », fichier `runner-login-page.ts` supprimé (RG3), en-tête enrichi (RG4) : tests mis à jour avec motif.
- Régression : aucune constatée.

### 3.7 Réserves déjà ouvertes (non aggravées)

| Réserve | Constat |
|---|---|
| R4-1 / R5-3 (CA39 Chromium) | Inchangée : même étape (ligne 93), même message (échantillon B et journal complet) ; l'en-tête n'ajoute aucune requête réseau observée |
| RT1 / R5-2 (H2, pas PostgreSQL) | Inchangée ; étendue de fait à CA4 (10 appels concurrents) et à CA1 (volet base), consigné dans `INC-7-integration.md` §7 |
| R5-1 (exploitant, VPS) | Inchangée ; un volet s'y ajoute : CA17 [manuel] (429 nginx réel de E26) |
| R5-6 (doublon « J'ai déjà un compte ») | Non aggravée : `account-creation-page.ts` ne porte qu'un lien (l. 65) ; `getByRole('link', { name: "J'ai déjà un compte" })` en mode strict dans CA12 (c) |
| R6-1 (motif `admin-*`) | Respectée : écrans `/connexion` et `/inscription` non préfixés `admin-`, 0 bloc `admin-*` et 0 texte RG5 chargé ou en cache (CA20, inventaire `07-cache-storage-ecrans-connexion-inscription.txt` : 21 fichiers JS, 0 `admin-*.js`). La revérification complète reste reportée à l'inc. 8 (RG11) |
| R6-5 (CL5 WebKit) | Inchangée ; CL11 de l'inc. 7 (ancienne PWA en cache) non rejoué sous WebKit, hors CA |
| R6-2 / R6-3 | Voir COH7-7 |

## 4. Contrôles sans constat

- C1 : exigence ou critère sans aucun test (aucun, hors volets manuels prévus par la spec).
- C2 : tests cités absents du code ; tags vers exigences inexistantes ; tests tagués non référencés.
- C3 : suppression, désactivation, `@Disabled`, `skip`, `fixme`, `only` ; catégorie C ; timeouts allongés ; nombre de `test(` modifié sauf ajouts ; `pom.xml`, `ngsw-config.json` modifiés.
- C4 : tests sans assertion, assertions triviales, quarantaine.
- C5 : chiffres vitest, surefire ciblé, failsafe ciblé, échantillons E2E, CA39 Chromium.
- C6 : non-régression backend, front, E2E.
- Clause CA21 « `GET /nimporte-quoi` reste 401 » : couverte (`UnknownPathUnauthorizedIT`, 1/1 rejoué).
- CA12 (b) : voir COH7-3 (pas d'écart de couverture).

## 5. Limites

- `mvn -B -f backend/pom.xml clean verify` (suite complète) non rejoué par cet agent : surefire 590/0 et failsafe 231/0 reposent sur la déclaration du coordinateur et sur la cohérence arithmétique ; JaCoCo (« All coverage checks have been met », domain 98,58 %, service 99,23 % rapportés) non recalculé.
- Suite E2E complète (204 cas, 25 min) non rejouée : journal relu ; échantillons rejoués : 68 + 56 cas, soit 124 exécutions sur 204.
- `-Dskip.npm -Dskip.installnodenpm` : les rejeux Maven ciblés n'ont pas reconstruit le build Angular ; les E2E ont tourné sur le `dist/` de 20:22 produit par le coordinateur (postérieur aux sources).
- Discrimination de CA18 (mutation), de CA14 (détection de l'état transitoire) et de CA20 (rejeu sur build fautif) : non rejouée ; CA14 ne peut pas détecter OBS-E2E-7A (COH7-1).
- CA17 [manuel] (VPS, nginx réel), CL10 (réseau coupé pendant la création), CL11 sous WebKit : non vérifiables ici.
- PostgreSQL réel (RT1, R5-2, concurrence de CA4) : hors périmètre.
- Pas de compte rendu écrit du testeur dans `docs/tests/rapports/` : le verdict technique OK est cité dans `PATRIMOINE.md`, vérifié seulement par les résultats rejoués.
- Les sections « Verdict de l'agent fonctionnel » des rapports d'intégration et E2E sont vides : attendu à ce stade.

## 6. Recommandation à l'agent fonctionnel

**Prêt pour arbitrage** : aucun constat bloquant. Les chiffres annoncés sont confirmés par exécution réelle (vitest 322/322, surefire ciblé 45/0, failsafe ciblé 35/0, E2E 68/68 et 55 + CA39 Chromium identique à l'état de référence), le patrimoine existant est intact (aucune catégorie C, nombre de tests inchangé, assertions conservées ou renforcées), et les réserves ouvertes ne sont pas aggravées.

Points à traiter dans l'arbitrage, avant ou avec le verdict :
1. COH7-1 (majeur) : OBS-E2E-7A est un défaut applicatif reproductible (RG4, « un seul lien ») masqué par l'attente `expect.poll` de l'audit : corriger et tester la première frame, ou accepter et amender RG4 / CA14.
2. COH7-2 : trancher RG7 contre D10 pour le texte « Bénévole : votre compte est créé par l'organisateur. ».
3. COH7-3 : confirmer la lecture « une seule E21 de connexion » (CA12 b).
4. COH7-4 et COH7-5 : faire nettoyer `PATRIMOINE.md` et corriger la phrase B2 du rapport E2E.
5. COH7-6 et COH7-7 : décider du sort de `evidence-inc7/` (notamment le journal de l'exécution abandonnée), de `manual/` ; confirmer le statut de R6-2 et R6-3 avant `git-publisher`.

Ce rapport constate et ne tranche pas : le verdict GO / GO sous réserves / NO-GO appartient à l'agent fonctionnel.
