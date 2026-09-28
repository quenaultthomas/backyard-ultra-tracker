# Rapport de cohérence du patrimoine : INC-4 (revalidation post-NO-GO)

- **Date** : 2026-09-28
- **Agent auteur** : revue-coherence-patrimoine (lecture seule, contrôleur indépendant)
- **Version examinée** : branche `feat/increment_4`, dernier commit `dc415a0`, plus working tree (voir `git
  status`/`git diff dc415a0` ci-dessous pour le détail exact des fichiers touchés depuis ce commit).
- **Contexte** : revalidation de l'INC-4 après le verdict **NO-GO** du 2026-09-27 (`docs/tests/rapports/INC-4-synthese.md`,
  section 8, et section 7 de ce rapport, conservée en annexe A sans modification). L'utilisateur a tranché le
  2026-09-27 : RT1 en option (b) (n'est plus une condition du GO de l'INC-4), fusion en squash, lancement des
  actions correctives 1 à 8 et 11 à 12. La présente revue exécute l'action corrective 11 : confronter chaque
  assertion E2E (CA21 à CA44) et front-unit (CA45, CA46) à l'énoncé chiffré de son critère d'acceptation, en
  priorité sur les écarts du motif 3 du NO-GO.
- **Entrées lues** : `docs/specs/increment4.md` (version amendée du 2026-09-27, avec les arbitrages OBS-T1/OBS-T2),
  `docs/tests/PATRIMOINE.md`, `docs/tests/rapports/INC-4-integration.md` (section 7bis), `docs/tests/rapports/INC-4-e2e.md`
  (section « Reprise 2026-09-27 »), `docs/tests/rapports/INC-4-synthese.md`, `CLAUDE.md`, `git log`/`git diff dc415a0`,
  les 24 fichiers `frontend/e2e/tests/ca21-*.spec.ts` à `ca44-*.spec.ts`, les fichiers front-unit
  `frontend/src/app/core/*.spec.ts` (18 fichiers), `frontend/src/app/app.routes.ts`, `frontend/src/app/pages/scan/scan-page.ts`,
  et un rejeu Vitest indépendant.
- **Contrainte d'exécution reçue** : ne pas lancer `mvn`, ne pas relancer la suite E2E complète. Seul
  `timeout 300 npx vitest run` a été exécuté par cet agent, dans `frontend/`.

---

## 1. Synthèse (5 lignes)

Aucun constat **bloquant**. Les corrections revendiquées (BUG-1, BUG-3, alignement CA29/CA31/CA37/CA43 sur la
spec amendée, comblement des écarts du motif 3 sur CA24, CA25, CA26, CA27, CA31, CA32, CA36, CA41, CA42, CA43,
CA44, CA45, CA46) sont vérifiées **assertion par assertion contre le texte chiffré de chaque CA** et sont
fidèles à la spec ; le diff `ApiSliceTest.java` se limite exactement à l'ajout du `@MockitoBean SessionService`
annoncé, et les diffs des fichiers de test antérieurs (E2E `ca21`/`ca24`/`ca25`/`ca26`, backend déjà confirmés
le 2026-09-27) ne sont que des renforcements. L'échec réel de CA39 sous Chromium est honnêtement déclaré comme
écart (jamais comme succès), conforme à l'énoncé amendé. Deux constats **majeurs** sont relevés : quatre
fichiers de tests front-unit réels et verts (`outcomes.spec.ts`, `polling.spec.ts`, `scan-feedback.spec.ts`,
`validation.spec.ts`) restent absents de `PATRIMOINE.md` malgré la correction COH4-1 du 2026-09-26, et
l'assertion de CA31 sur le `detail` du serveur reste partielle (seul le code « 409 » est contrôlé, pas le
texte). Trois constats **mineurs** portent sur des incohérences de documentation (commentaires obsolètes dans
`ca44-effective-emitter.spec.ts`, table « Écarts ouverts » de `PATRIMOINE.md` non mise à jour après les
corrections, section 14 de la spec non amendée pour RT1). **Recommandation : prêt pour arbitrage.**

---

## 2. Tableau des constats

| ID | Contrôle | Sévérité | Description | Preuve | Action recommandée |
|---|---|---|---|---|---|
| COH4B-1 | C1 / C2 | **Majeur** | Quatre fichiers de tests front-unit, réels et verts, ne sont référencés **nulle part** dans `docs/tests/PATRIMOINE.md` (recherche exhaustive par nom de fichier : 0 occurrence, hors un faux positif de `e2e/tests/ca42-scan-feedback.spec.ts`, fichier E2E distinct). Ils testent des règles réelles de `docs/specs/increment4.md`, non rattachées explicitement à un CA numéroté : `outcomes.spec.ts` (RG13 inscription, RG45 échec de chargement, RG43 échec d'une action admin), `polling.spec.ts` (RG29 rythme du polling, RG33 fraîcheur), `scan-feedback.spec.ts` (RG50 retour immédiat/serveur, RG24/RG25 textes, RG26 état réseau), `validation.spec.ts` (RG1 validation de format, RG8 en-tête `Authorization`). Ces 4 fichiers faisaient déjà partie des « 170 tests, 14 fichiers » cités par COH4-1 (2026-09-26) : la correction de COH4-1 (ajout des 13 lignes CA8 à CA20) n'a couvert que 10 des 14 fichiers, laissant ces 4-là orphelins sans que cela soit détecté par la revue de cohérence précédente (2026-09-27). | `docs/tests/PATRIMOINE.md` (grep sur `outcomes.spec.ts`, `polling.spec.ts`, `scan-feedback.spec.ts`, `validation.spec.ts` : aucune occurrence) ; fichiers réels : `frontend/src/app/core/outcomes.spec.ts:13,41,50` (`describe('RG13 ...')`, `describe('RG45 ...')`, `describe('RG43 ...')`), `polling.spec.ts:41,116` (`RG29`, `RG33`), `scan-feedback.spec.ts:27,54,91,122` (`RG50` ×2, `RG24/RG25`, `RG26`), `validation.spec.ts:5,32` (`RG1`, `RG8`) ; confirmé exécutés et verts par le rejeu Vitest (section 4) | Ajouter des lignes de matrice pour ces 4 fichiers (par RG couvert, avec renvoi au CA E2E le plus proche s'il existe), avant le GO définitif — sans réécrire les tests |
| COH4B-2 | C1 | **Majeur** | CA31 (« Chloé : REJETÉ, avec le detail du serveur (409 BUSINESS_CONFLICT)... ») : le test vérifie que `.rejections li` contient la sous-chaîne `'409'`, mais ne compare jamais le texte affiché au `detail` réellement renvoyé par le serveur. Le motif 3 du NO-GO avait déjà exigé et obtenu un renforcement pour l'ordre Chloé/Dan et pour « Renvoyer » sur ce même fichier ; ce point-ci n'avait pas été relevé et reste un attendu du CA non entièrement vérifié. Le patron de vérification existe déjà ailleurs dans le même incrément (CA36, ligne `expect(page.locator('.banner-error')).toHaveText(duplicateDetail as string)`), ce qui rend l'écart facile à combler | `frontend/e2e/tests/ca31-rejection.spec.ts:92-94` (`toHaveCount(1)`, `toContainText('409')`, rien sur le texte du `detail`) ; template affichant réellement le detail : `frontend/src/app/pages/scan/scan-page.ts:126` (`{{ item.lastError?.code ?? '' }} : {{ rejection(item) }}`) ; contre-exemple de bonne pratique : `frontend/e2e/tests/ca36-admin-crud.spec.ts:83-85` | Capturer le corps de la réponse 409 de Chloé (comme déjà fait pour Dan/« Renvoyer » dans le même fichier) et comparer le texte de `.rejections li` au `detail` exact, avant le GO définitif |
| COH4B-3 | C4 | Mineur | `frontend/e2e/tests/ca44-effective-emitter.spec.ts` contient des commentaires devenus obsolètes et trompeurs : l'en-tête (lignes 10-13) et le commentaire au-dessus de l'étape 4 (lignes 116-119) affirment que la correction de production de l'étape 4 (OBS-T2) est « en cours de développement » et qu'« un échec de cette étape précise est attendu et doit être consigné comme tel », alors que le test **passe réellement** sur les deux navigateurs, confirmé sur 4 exécutions complètes (2026-09-27 et 2026-09-28, `INC-4-e2e.md` section « Reprise », `PATRIMOINE.md` ligne INC4-CA44 : « PASS 2026-09-28 ... confirmé sur 3 exécutions consécutives »). Ne change rien à la validité du test (l'assertion elle-même est stricte et fidèle à CA44), mais induit en erreur un futur lecteur du code | `frontend/e2e/tests/ca44-effective-emitter.spec.ts:10-13,116-119` ; contredit par `docs/tests/rapports/INC-4-e2e.md` (section Reprise, tableau action 4, ligne CA44) et `docs/tests/PATRIMOINE.md` ligne `INC4-CA44` | Mettre à jour les commentaires pour refléter l'état final (correction terminée, étape 4 verte), avant la publication de la MR |
| COH4B-4 | C2 | Mineur | La table « Écarts ouverts » de `docs/tests/PATRIMOINE.md` (lignes 432 à 437) n'a pas été mise à jour après les corrections de cette reprise, à l'exception de la ligne `INC4-CA2 (OBS-1)` qui porte explicitement `**LEVÉE**`. Les lignes `INC4-CA21 (BUG-1)`, `INC4-CA44, INC4-CA45 (BUG-3)`, `INC4-CA29, CA31, CA37, CA43 (BUG-2)`, `INC4-E2E (fidélité)` et `INC4-CA26 (COH4-2)` restent rédigées comme si elles étaient encore ouvertes (« Avant la reprise de `/valider-increment 4` »), alors que la matrice détaillée (lignes 387-410) et les rapports d'intégration/E2E montrent ces points corrigés et vérifiés — à la seule exception réelle de CA39 sous Chromium, qui reste ouvert et se trouve noyé dans la même ligne `INC4-E2E (fidélité)` que CA31/CA32/CA41/CA42 (désormais résolus). Un lecteur qui ne consulterait que cette table pourrait croire à tort que CA31/CA32/CA41/CA42 sont encore en écart | `docs/tests/PATRIMOINE.md:432-437` (aucune mention « LEVÉE ») comparé aux lignes 387-410 (matrice E2E, statuts PASS détaillés) et à `docs/tests/rapports/INC-4-e2e.md` section Reprise | Mettre à jour la table « Écarts ouverts » : marquer LEVÉE les lignes BUG-1, BUG-2, BUG-3, COH4-2 ; scinder la ligne « INC4-E2E (fidélité) » pour isoler CA39/Chromium (seul point réellement encore ouvert) des CA31/CA32/CA41/CA42 (résolus) |
| COH4B-5 | C2 | Mineur | La section 14 de `docs/specs/increment4.md` (« Conditions de validation de l'incrément ») liste toujours RT1 comme condition du GO de l'INC-4, sans refléter la décision de l'utilisateur du 2026-09-27 (option b, consignée dans `INC-4-synthese.md` section 9 et dans `PATRIMOINE.md` ligne RT1) qui retire RT1 des conditions du GO de cet incrément. Incohérence purement textuelle entre deux sources normatives de l'incrément ; la décision réelle appliquée est correctement documentée ailleurs et c'est elle qui prévaut (traçabilité de la décision utilisateur) | `docs/specs/increment4.md:1131-1140` (« Le GO de l'INC-4 exige ... RT1 (PostgreSQL réel et recette HTTPS) ») vs. `docs/tests/rapports/INC-4-synthese.md` section 9 et `docs/tests/PATRIMOINE.md:428` | L'agent fonctionnel amende formellement la section 14 pour cohérence documentaire (non bloquant pour le verdict) |

---

## 3. Vérifications détaillées par contrôle

### C1 — Couverture

- **CA1 à CA7** (backend) : chacun a au moins une ligne de matrice avec test réel, rejoué le 2026-09-28 d'après
  `INC-4-integration.md` §7bis (297/297 surefire, 41/41 failsafe, incluant les 4 classes `*IT` de CA3/CA4/CA6/CA7
  et `SessionApiSliceTest`/`ApiArchitectureTest` pour CA1/CA2). Non rejoué indépendamment par cet agent (contrainte
  « ne pas lancer `mvn` ») — voir section 5.
- **CA8 à CA20** (front-unit) : 13 lignes de matrice présentes (COH4-1 levée, confirmé). Rejoué indépendamment
  (section 4) : verts.
- **CA21 à CA44** (E2E) : chacun a une ligne de matrice avec un résultat daté du 2026-09-27/28. Chaque fichier a
  été lu et son ou ses `expect(...)` confrontés au texte de son CA (détail ci-dessous, action corrective 11) :
  - **CA24, CA25, CA26** : plus aucun `waitForTimeout(500)`. Seul `waitForTimeout(10_000)` subsiste dans CA26
    (`ca26-no-auth-to-public.spec.ts:41`), justifié par la fenêtre explicite de 10 s imposée par l'énoncé du CA
    et couvert par l'amendement de RG57.4 — conforme.
  - **CA27** : texte complet « Dossard {bib} — {runnerName} — yard 1 » vérifié (`toBeVisible`, pas seulement une
    sous-chaîne partielle), et égalité exacte à la milliseconde entre le `scannedAt` d'E5 et le corps de la
    requête E6 capturée (`ca27-scan-camera.spec.ts:103-106`) — conforme à l'énoncé.
  - **CA31, CA32** : bornes, absence de recouvrement, compte à rebours, allure et « 1 tour » tous vérifiés
    littéralement (détail au tableau 2, COH4B-2 pour le seul point résiduel sur le `detail`).
  - **CA36** : dossard 5 relu par E13, `detail` du 409 comparé exactement, erreur de distance ciblée par
    `aria-describedby` — conforme.
  - **CA39** : assertion tautologique retirée ; « Hors ligne » vérifié sans repli sur les deux navigateurs ;
    Chromium prouve la navigation via `fromServiceWorker() === true` sur 4 routes, mais l'assertion finale
    échoue réellement (voir section « CA39 » ci-dessous, point 4 de la mission) — écart honnêtement déclaré,
    jamais présenté comme un succès.
  - **CA41 (32 px)** : `getComputedStyle(...).fontSize` sur `.scan-result-text` comparé à `>= 32`
    (`ca41-accessibility.spec.ts:106-112`) — conforme, valeur numérique réellement mesurée, pas une supposition
    de style CSS.
  - **CA42 (comptes exacts, 4e point)** : `toEqual` strict sur les tableaux de fréquences et de motifs de
    vibration (pas de « au moins ») ; 3e test dédié au 4e point (capture hors ligne, acceptation à plus de 5 s,
    aucun son/vibration supplémentaire) — conforme.
  - **CA43 (T0 + 38 s)** : `waitUntil(t0 + 38_000)` avant le scan du yard 2, cloche à `T0 + 30 s`, marge de 8 s
    (`ca43-runner-detail.spec.ts:38-41`) — conforme à RG57.4.
  - **CA37 (onglets du même navigateur)** : `boardP1`/`boardP2` créés par `page.context().newPage()` (même
    contexte que la page `/scan`), ouverts avant la navigation vers `/scan` — conforme littéralement à l'énoncé
    amendé.
  - **CA44, dont l'étape 4** : les 4 étapes suivent littéralement l'énoncé (onglets A, B, C, D ; A ouvert en
    premier ; D connecté par les identifiants mémorisés à l'étape 3 sans nouvelle saisie ; assertion sur le
    bandeau vert et « 0 en attente » affichés par D, non émetteur). Exactement 4 requêtes E6 comptées, jamais
    deux simultanées (vérifié par tri des instants de départ/fin sur tout le contexte) — conforme. Seul un
    défaut de commentaire subsiste (COH4B-3), sans effet sur la validité de l'assertion.
- **CA45, CA46 [front-unit]** : lus intégralement (`scan-queue-cross-context.spec.ts`, `scan-queue-role-transfer.spec.ts`,
  `scan-feedback-cross-context.spec.ts`, `emitter-role.spec.ts`, `testing/device.spec-support.ts`). Chaque cas de
  la spec (RG21 émetteur effectif, RG22 backoff propre à l'émetteur et transfert de rôle, RG50 retour donné par
  le contexte de capture) a un test correspondant avec des valeurs chiffrées exactes (délai(1) = 1000/1100/1200 ms
  selon le tirage, fenêtre de 5 s, `t + 6 s` pour le résultat différé, corps identiques comparés par égalité
  stricte). `device.spec-support.ts` est un fichier de support (aucun `describe`/`it` propre), correctement non
  référencé comme test à part entière.
- **Quatre fichiers orphelins** : voir COH4B-1.

### C2 — Cohérence matrice ↔ code

- Les 24 fichiers `frontend/e2e/tests/ca21-*.spec.ts` à `ca44-*.spec.ts` cités par la matrice existent tous aux
  chemins indiqués (`ls frontend/e2e/tests` : correspondance exacte, aucun manquant, aucun surnuméraire).
- Les 18 fichiers front-unit de `frontend/src/app/core/*.spec.ts` existent tous ; 14 sont référencés par CA
  numéroté, 4 ne le sont pas (COH4B-1).
- Aucun tag de la matrice ne pointe vers une exigence ou un CA inexistant.
- La table « Écarts ouverts » n'est pas synchronisée avec l'état réel des corrections (COH4B-4).

### C3 — Intégrité du patrimoine existant

Diff `dc415a0` → working tree, fichiers de test existants modifiés (hors nouveaux fichiers) :

- `backend/src/test/java/fr/backyard/api/ApiSliceTest.java` : **exactement** l'ajout annoncé — un import
  `SessionService` et un champ `@MockitoBean SessionService sessionService` dans la classe de base abstraite.
  Aucune assertion touchée, aucune méthode de test modifiée. Conforme à l'exception explicitement autorisée par
  la mission et à l'accord de l'agent fonctionnel (refus de l'`@Lazy`, accord pour compléter le contexte des
  tests de slice).
- `frontend/e2e/tests/ca21-routes.spec.ts` : ajout du tag `@smoke` uniquement. Renforcement neutre.
- `frontend/e2e/tests/ca24-login-roles.spec.ts` : le contrôle de stockage après échec de connexion est étendu
  de `localStorage`/cookies seuls à `localStorage` + `sessionStorage` + contenu d'IndexedDB (pas seulement la
  liste des bases) + contenu de Cache Storage, plus une comparaison à la chaîne base64 exacte des identifiants
  invalides. Strictement plus strict, aucune assertion retirée.
- `frontend/e2e/tests/ca25-credentials-storage.spec.ts` : ajout de l'inspection du contenu de Cache Storage au
  même utilitaire de dump. Renforcement.
- `frontend/e2e/tests/ca26-no-auth-to-public.spec.ts` : les deux `waitForTimeout(500)` sont remplacés par des
  assertions sur l'écran affiché (`toContainText` sur le titre `h1`). Renforcement conforme à l'arbitrage
  COH4-2 du 2026-09-27.
- `frontend/src/app/app.routes.ts` : la route `**` est retirée des enfants de `admin` (elle n'existait que là
  auparavant, dupliquée par la route `**` de premier niveau qui gère déjà « Page introuvable »). C'est un
  changement de code de production, pas de test ; il corrige BUG-1 sans introduire de route non documentée par
  RG5.
- `frontend/src/app/core/scan-feedback.ts`, `scan-queue.ts`, `frontend/src/app/infra/scan-queue.service.ts` :
  code de production (BUG-3, OBS-T2), hors périmètre de ce contrôle C3 (qui porte sur les tests).

**Aucun `@Disabled`, `@Ignore`, `test.skip`, `test.fixme`, `.only(`, timeout allongé au sens d'un affaiblissement,
ou donnée de test affaiblie constatée** dans l'ensemble des fichiers de test modifiés ou nouveaux (recherche
textuelle sur `frontend/e2e/tests/`, `frontend/src/app/core/*.spec.ts` et les fichiers backend cités). Les deux
délais fixes restants (`waitForTimeout(10_000)` en CA26, `waitForTimeout(6_000)` en CA42) sont l'un et l'autre
justifiés par un énoncé de CA qui fixe explicitement cette durée, conformément à l'amendement de RG57.4 — ce
n'est pas un contournement de flakiness.

**Aucun écart C3.**

### C4 — Qualité des tests

- Recherche exhaustive de `test.skip`, `test.fixme`, `.only(`, `@Disabled`, `@Ignore` sur l'ensemble des
  fichiers de test touchés ou nouveaux : aucune occurrence.
- `retries: 0` dans `playwright.config.ts` (inchangé) : aucun masquage de flakiness par nouvel essai automatique.
- Commentaires obsolètes dans `ca44-effective-emitter.spec.ts` (COH4B-3) : dette de lisibilité, sans effet sur
  la rigueur de l'assertion.
- Pas de nouveau doublon constaté entre les fichiers front-unit et E2E ajoutés à cette reprise (`scan-queue-role-transfer.spec.ts`,
  `scan-feedback-cross-context.spec.ts`, `ca44-effective-emitter.spec.ts`) : chacun couvre un aspect distinct
  (transfert de rôle et backoff propre à l'émetteur, retour donné par le contexte de capture, parcours navigateur
  multi-onglets).
- Aucun test en quarantaine (table vide dans `PATRIMOINE.md`, confirmé inchangée).

### C5 — Véracité des rapports

Conformément à la contrainte reçue, **`mvn` n'a pas été relancé**. Vérification faite par un seul rejeu réel :

```
cd frontend && timeout 300 npx vitest run
```

Résultat obtenu par cet agent (2026-09-28) : **`Test Files 18 passed (18)`, `Tests 209 passed (209)`**, incluant
`scan-queue-cross-context.spec.ts` (5), `scan-feedback-cross-context.spec.ts` (7), `emitter-role.spec.ts` (20),
`scan-queue-role-transfer.spec.ts` (7). **Concorde exactement** avec le chiffre annoncé par `INC-4-integration.md`
§7bis (« Front, unitaires (Vitest ...) : 209 (18 fichiers), 209 passés ») et par `PATRIMOINE.md` (ligne CA46,
« Suite front npx vitest run --coverage : 209 tests verts (18 fichiers) »).

Pour le reste (suite backend complète, suite E2E complète), cet agent s'appuie sur la lecture intégrale des
rapports `INC-4-integration.md` §7bis et `INC-4-e2e.md` section « Reprise 2026-09-27 », sans pouvoir les
rejouer lui-même (contrainte d'exécution) — voir les limites en section 5 du présent rapport pour le détail de
ce qui n'a pas pu être vérifié indépendamment à cette reprise.

Le diff `ApiSliceTest.java` (C3) confirme littéralement, ligne par ligne, l'affirmation de `INC-4-integration.md`
§7bis selon laquelle le retrait du `@Lazy` s'accompagne d'un unique ajout de `@MockitoBean SessionService`, sans
modification d'assertion — chiffre et description cohérents entre le rapport et le code réel.

**Aucun écart entre un résultat annoncé et un résultat constaté**, dans la limite de ce qui a pu être vérifié
sans relancer `mvn` ni la suite E2E complète.

### C6 — Non-régression inter-incréments

- Le rapport `INC-4-integration.md` §7bis cite 297/297 en surefire (dont toutes les classes des incréments 1 à 3,
  déjà confirmées par la revue de cohérence du 2026-09-27) et 41/41 en failsafe. Non rejoué indépendamment à
  cette reprise (contrainte d'exécution), mais cohérent avec la revue précédente et avec le diff `git` (aucune
  suppression de test antérieur constatée).
- Aucun comportement des incréments 1 à 3 modifié par les changements de cette reprise (BUG-1, BUG-3, OBS-T2)
  qui ne touchent que le routage front (`app.routes.ts`) et la logique de file de scan (`core/scan-*.ts`,
  `infra/scan-queue.service.ts`), hors périmètre des incréments 1 à 3.

**Aucun écart C6 constaté**, dans la limite de la non-réexécution de `mvn` à cette reprise.

---

## Point spécifique — CA39 sous Chromium (mission, point 4)

Constat vérifié directement dans le code du test et dans les trois documents source :

- `ca39-installability-offline.spec.ts` (lignes 25-34 en commentaire, lignes 67-93 en code) prouve la navigation
  hors ligne réellement servie par le service worker (`response.fromServiceWorker() === true` sur 4 routes,
  `context.setOffline(true)` actif), **puis** fait échouer intentionnellement l'assertion finale
  `expect(page.getByText('Hors ligne', ...)).toBeVisible(...)` — cette assertion n'est ni retirée, ni affaiblie,
  ni contournée par un `try/catch`, un `test.fail()` ou une branche alternative.
- `PATRIMOINE.md` ligne `INC4-CA39` déclare explicitement, dans la colonne Statut : « **Chromium : ÉCHEC réel,
  constaté et non contourné** » — jamais « PASS ».
- `INC-4-e2e.md` section « Écart persistant : CA39 sous Chromium (non contourné) » documente le diagnostic
  réseau (deux réponses 200 réelles du serveur malgré `setOffline(true)`, à ~1,1 s et ~11,1 s) et conclut :
  « cet agent ne contourne pas l'assertion. Elle reste intacte, échoue réellement, et l'écart est consigné ici
  pour l'agent fonctionnel. »

Ce traitement est **conforme à l'énoncé amendé de CA39** (« Si l'outil ne peut pas faire échouer E4 sous
Chromium avec le service worker actif, le rapport le déclare comme écart à arbitrer. Ce n'est jamais un
succès. »). Conformément à mon rôle, je ne me prononce pas sur l'arbitrage (accepter LIM-E2E-1 pour ce cas
précis, ou exiger une autre preuve technique) : c'est à l'agent fonctionnel de trancher.

---

## 4. Contrôles sans constat

- **C3** (intégrité du patrimoine existant) : aucun écart. Tous les changements sur les tests antérieurs à
  cette reprise sont des renforcements documentés et vérifiés ligne à ligne (y compris `ApiSliceTest.java`,
  seule exception prévue par la mission).
- **C6** (non-régression inter-incréments) : aucun écart constaté, dans la limite de non-réexécution de `mvn`.

## 5. Limites

- **`mvn -B -f backend/pom.xml clean verify` non relancé** par cet agent à cette reprise (consigne explicite,
  session précédente bloquée). Les chiffres backend (297/297 surefire, 41/41 failsafe, couverture JaCoCo
  98,38 %/99,03 %, 0 `[WARNING]`/`[ERROR]`) proviennent uniquement de la lecture de `INC-4-integration.md` §7bis
  et n'ont pas été confrontés à un rapport XML ou un log de référence par cet agent à cette reprise (à la
  différence de la revue du 2026-09-27, qui avait pu les confronter à `mvn-nonreg.log` et aux rapports XML
  Surefire/Failsafe alors disponibles). Seule la partie front (Vitest) a été rejouée indépendamment.
- **Suite E2E complète non relancée** par cet agent (consigne explicite). Le constat sur les 24 CA E2E s'appuie
  sur la lecture intégrale du code des tests (confrontation directe à l'énoncé de chaque CA, mission de cette
  reprise) et sur les résultats cités par `INC-4-e2e.md` et `PATRIMOINE.md`, cohérents entre eux et avec le
  code lu, mais non revérifiés par une exécution indépendante à cette date.
- **RT1 (PostgreSQL réel)** : non exécutée, décision de l'utilisateur du 2026-09-27 (option b) qui en fait une
  réserve hors GO de l'INC-4 plutôt qu'une condition bloquante. Ce n'est pas à cet agent de trancher si ce
  traitement est correctement appliqué par l'agent fonctionnel ; le constat se limite à noter que
  `PATRIMOINE.md` reflète cette décision (ligne RT1) alors que `docs/specs/increment4.md` section 14 ne
  l'a pas encore été amendée en conséquence (COH4B-5).
- **Traces Playwright** (`trace.zip`, rapport HTML) non ouvertes par cet agent : le constat sur CA39/Chromium et
  sur les autres CA E2E s'appuie sur la lecture du code source des tests et des rapports texte, cohérents entre
  eux, mais sans relecture visuelle des traces d'exécution elles-mêmes.
- **Code de production des corrections BUG-3/OBS-T2** (`core/scan-queue.ts`, `core/scan-feedback.ts`,
  `core/emitter-role.ts`, `infra/scan-queue.service.ts`) : lu partiellement (uniquement pour confirmer la
  cohérence du diff `app.routes.ts` pour BUG-1, et l'existence de `emitter-role.ts`) ; une revue complète de ce
  code de production n'entre pas dans le périmètre de cet agent (lecture des tests et de la matrice, pas du
  code de production, sauf pour élucider un constat).

## 6. Recommandation

**Prêt pour arbitrage.**

Aucun constat bloquant. Les corrections revendiquées pour BUG-1, BUG-3, BUG-2 et les écarts du motif 3 (CA24,
CA25, CA26, CA27, CA31, CA32, CA36, CA41, CA42, CA43, CA44) sont vérifiées fidèles au texte chiffré de chaque
CA, sans attendu retiré ni élargi. CA45 et CA46 (front-unit) sont fidèlement testés, y compris la variante
« transfert de rôle » de CA45. L'échec de CA39 sous Chromium reste honnêtement déclaré comme écart, jamais
comme succès. Deux constats majeurs (COH4B-1 : 4 fichiers de tests front-unit orphelins de la matrice ;
COH4B-2 : assertion partielle sur le `detail` de CA31) et trois constats mineurs de documentation (COH4B-3,
COH4B-4, COH4B-5) sont soumis à l'agent fonctionnel, qui reste seul décideur de leur traitement (correction
avant GO, réserve, ou acceptation en l'état) et du verdict GO / GO sous réserves / NO-GO.

---

## Annexe A — Verdict de l'agent fonctionnel du 2026-09-27 (conservé sans modification)

*(Section 7 de la version précédente de ce rapport, datée du 2026-09-27, reproduite ici telle quelle pour
mémoire. Elle ne constitue pas le verdict de la présente revalidation.)*

### 7. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : **NO-GO** (verdict unique de l'INC-4, détail complet et arbitrages : `INC-4-synthese.md`, section 8)
- **Motifs** :
  1. CA21 n'est pas satisfait (BUG-1) ;
  2. BUG-3 est bloquant (filet réseau, RG21 et RG22) ;
  3. des attendus de CA E2E ne sont pas vérifiés, sans que ce soit déclaré : CA39, CA29 étape 2, CA31, CA32, CA41, CA42, plus des écarts mineurs. **Ils ont échappé au contrôle C3/C4** : ce contrôle a comparé les tests à leur version précédente, pas à l'énoncé chiffré des CA ;
  4. RT1 n'est pas levée ;
  5. le contrôle négatif de CA5 n'a pas été fait avec `mvn`. La limite relevée en section 5 est confirmée.
- **Suite donnée aux constats de ce rapport** :
  - COH4-1 : levée confirmée (13 lignes CA8 à CA20 présentes dans la matrice) ;
  - COH4-2 : RG57.4 amendée, `waitForTimeout(10_000)` accepté, les deux `waitForTimeout(500)` refusés ;
  - COH4-3 : BUG-1 arbitré (corriger le code, CA21 inchangé) ;
  - C3 : les renforcements de `RacePersistenceTest`, `ReintegrationServiceTest` et `YardClosingServiceTest` sont acceptés ;
  - R1-3 : levée prononcée.
- **Actions correctives exigées** (responsable, échéance : avant la reprise de `/valider-increment 4`, sauf mention contraire) :
  1. corriger BUG-1 (développeur) ;
  2. corriger BUG-3 (développeur) ;
  3. tests CA45 (testeur) ;
  4. test CA44 et tests alignés sur la spec amendée (test-e2e-frontend) ;
  5. combler les écarts du motif 3 (test-e2e-frontend) ;
  6. retirer le `@Lazy` (développeur) ;
  7. contrôle négatif de CA5 avec `mvn` (testeur) ;
  8. nettoyer l'index git (git-publisher, avant la MR) ;
  9. décision sur RT1 (utilisateur) ;
  10. exécuter RT1 si un environnement est fourni (test-integration-backend) ;
  11. **lors de la reprise, cet agent confronte chaque assertion E2E à l'énoncé chiffré de son CA** : tous les points, bornes, textes et issues acceptées (revue-coherence-patrimoine) ;
  12. relancer toute la chaîne (orchestrateur).
- **Date** : 2026-09-27

## Verdict de l'agent fonctionnel : revalidation du 2026-09-28
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : **GO SOUS RÉSERVES** (verdict unique de l'INC-4, détail complet et arbitrages : `INC-4-synthese.md`, section R7)
- **Suite donnée aux constats de ce rapport** :
  - aucun constat bloquant : la recommandation « prêt pour arbitrage » est suivie ;
  - COH4B-1 : **levé** (11 lignes `INC4-RG<k>` ajoutées à la matrice). Les anomalies de libellé des lignes `INC4-RG1`, `INC4-RG8` et `INC4-RG24` deviennent la réserve R4-2, non bloquante ;
  - COH4B-2 : **levé** (le `detail` exact du 409 est comparé, 4/4 sur deux exécutions) ;
  - COH4B-3 : **levé** (commentaires de CA44 mis à jour, sans assertion modifiée) ;
  - COH4B-4 : **traité par l'agent fonctionnel** (table « Écarts ouverts » de `PATRIMOINE.md` resynchronisée, CA39 sous Chromium isolé dans la ligne R4-1) ;
  - COH4B-5 : **traité par l'agent fonctionnel** (section 14 de la spec amendée selon l'option b) ;
  - CA39 sous Chromium : écart accepté comme **réserve R4-1**, bloquante avant tout déploiement sur le VPS (conditions de levée dans la spec, CA39) ;
  - action corrective 11 : **levée**. La confrontation assertion par assertion à l'énoncé chiffré des CA est faite et consignée.
- **Limite relevée et acceptée** : cette revue n'a pas relancé `mvn` ni les E2E complets. Les chiffres viennent d'exécutions réelles de l'agent d'intégration et de l'orchestrateur, cohérentes entre elles et avec le rejeu Vitest indépendant (209/209).
- **Réserves** :
  1. RT1 : bloquante avant le déploiement ;
  2. R4-1 : bloquante avant le déploiement ;
  3. R4-2 : non bloquante.
- **Date** : 2026-09-28
