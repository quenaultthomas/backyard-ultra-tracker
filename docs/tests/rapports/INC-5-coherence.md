# Revue de cohérence du patrimoine : INC-5 (comptes pseudo coureurs)

- **Date** : 2026-09-29
- **Agent** : revue-coherence-patrimoine (lecture seule, aucun code ni document modifié hors ce rapport)
- **Périmètre examiné** : branche `feature/increment-5-comptes-pseudo`, HEAD `2774bdf` (fin de l'inc. 4) + arbre de travail non commité (127 chemins modifiés ou nouveaux).
- **Entrées** : `docs/specs/increment5.md` (révision 5), `docs/tests/rapports/INC-5-arbitrage-N1.md`, `INC-5-integration.md`, `INC-5-e2e.md`, `docs/tests/PATRIMOINE.md`, `git diff HEAD` / `git status`.

## 1. Synthèse

- Constats : **8**, dont **0 bloquant**, **4 majeurs**, **4 mineurs**.
- Suite backend rejouée par cet agent : chiffres du rapport confirmés (surefire 414/0, failsafe 76/0, vitest 235/235 sur 23 fichiers, JaCoCo OK, 0 `[WARNING]` Maven).
- E2E : log `full-inc5-final.log` cohérent avec le rapport (115 passés, 1 échec CA39 Chromium préexistant) ; échantillon rejoué (38 cas passés, 0 échec).
- Patrimoine existant : aucun test supprimé, désactivé, ignoré ni assoupli (nombre de tests inchangé dans les fichiers de tests existants modifiés). Le diff des tests backend et front correspond à la liste de l'arbitrage N1 (§6).
- Les adaptations E2E (catégories A et B) n'ont pas reçu l'accord de l'agent fonctionnel : constaté (COH5-2), classement vérifié, aucune assertion affaiblie.

## 2. Tableau des constats

| ID | Contrôle | Sévérité | Description | Preuve | Action recommandée |
|---|---|---|---|---|---|
| COH5-1 | C2 (C1) | **Majeur** | La matrice `PATRIMOINE.md` omet les tests front-unit de l'incrément. CA27, CA35 et CA36 (critères `[front-unit]`) n'ont aucune ligne. Les fichiers `account-validation.spec.ts` (CA27), `runner-credentials.spec.ts` (CA35), `credential-routing.spec.ts` (CA36), `account-outcomes.spec.ts` (CA41 côté unité, RG21, RG17), `registration-conflict.spec.ts` (correction D1) ne sont cités nulle part. `domain/RunnerAccountLinkTest` (tagué `INC-5`) est aussi absent. La ligne `INC4-CA20` (`action-visibility.spec.ts`) garde « PASS 2026-09-27 (13/13) » et ne mentionne pas l'adaptation N1 du fichier (`EDIT_BIB_AND_NAME` → `EDIT_BIB`, `EDIT_NAME` retiré). Les tests existent et passent (vitest 235/235) : c'est un écart de référentiel, pas de couverture. Même nature que COH4B-1 à l'inc. 4 | `grep -c` sur `PATRIMOINE.md` : 0 occurrence pour `account-validation`, `runner-credentials`, `credential-routing`, `account-outcomes`, `registration-conflict`, `RunnerAccountLinkTest`, `action-visibility` hors ligne INC4-CA20 ; §« Incrément 5 — backend » écrit « CA27, CA35, CA36 (front-unit) : hors de cette section » sans section de remplacement | Ajouter une section « Incrément 5 — front-unit » (CA27, CA35, CA36, CA41 unité, D1) avec les résultats vitest, référencer `RunnerAccountLinkTest`, et mettre à jour la ligne INC4-CA20 (adaptation N1 du 2026-09-29, renvoi à l'arbitrage §5 point 9) |
| COH5-2 | C3 | **Majeur** | Les adaptations des E2E existants (A1 à A3, B1 à B16 de `INC-5-e2e.md` §5) sont appliquées dans l'arbre de travail sans accord écrit de l'agent fonctionnel (règle 2 du workflow, note N1). Le motif est écrit et la démarche N1 est respectée. Aurait été **bloquant** au moment du verdict si l'accord n'était pas donné. Classement vérifié (voir §3.1) : aucune catégorie C, aucune assertion affaiblie, aucun test désactivé ; deux points appellent une décision expresse : le renommage du titre de test CA23 « nom blanc » → « pseudo blanc » (B16, cas analogue à R1 du backend, qui a exigé un accord) et le remplacement du filtre de vignette QR `hasText: String(runner.bib)` → `hasText: runner.name` dans `ca36-admin-crud.spec.ts` (classé A2, en réalité un changement de sélecteur de cible, sans effet sur l'assertion « QR décodé = qrToken ») | `git diff HEAD -- frontend/e2e/tests` (18 fichiers modifiés, nombre de `test(` inchangé, assertions `expect(` : mêmes valeurs modulo les noms, timeouts identiques 4/5/11/15 s avant et après) ; `INC-5-e2e.md` §5 « soumis à l'agent fonctionnel » | L'agent fonctionnel donne un accord écrit ligne à ligne (A) et en bloc (B), avec décision sur B16 et sur le filtre `qr-card` de A2, avant le verdict |
| COH5-3 | C1 / C5 | **Majeur** | Constat E-INC5-1 confirmé par lecture du code : `ApiExceptionHandler.respond` (ligne 148) écrit en WARN le `detail` de toute réponse 4xx. Pour un 409 sur E3 le journal contient donc le pseudo normalisé, avec IP et horodatage côté serveur (« Pseudo déjà utilisé : lievre ... »). CA20 est satisfait à la lettre (il ne vise que le WARN d'authentification et les INFO de E22, E23 et E24), mais RG16 et la minimisation du CLAUDE.md sont en cause, et aucun test n'interdit ce comportement | `backend/src/main/java/fr/backyard/api/error/ApiExceptionHandler.java:148` ; `AccountFlowIT#ca20_logsWithoutSecretsNorPseudo` (assertions limitées aux lignes attendues) | L'agent fonctionnel tranche : soit retirer le `detail` de la ligne journalisée pour les 409 de E3 (et ajouter un test), soit amender RG16 / CA20 |
| COH5-4 | C1 | **Majeur** | Deux clauses explicites de critères d'acceptation n'ont aucun test : (a) CA1 exige que « la contrainte d'unicité `(race_id, account_id)` existe » ; `AccountFlowIT#ca1_schema` vérifie les colonnes, les nullités et l'unicité de `pseudo`, mais ni cette contrainte ni la clé étrangère `RESTRICT`. (b) CA8 exige « même résultat si les deux requêtes visent R1 » et le cas `Tortue` / `Tortue` en parallèle sur R1 et R2 ; `ca8_concurrentCreations` ne joue que `Tortue` sur R1 et `TORTUE` sur R2 | `AccountFlowIT.java:138-155` (aucune requête sur `uq_runner_race_account`) ; `AccountFlowIT.java:225-254` ; aveu partiel dans `INC-5-integration.md` §7 | test-integration-backend : ajouter la vérification de la contrainte `(race_id, account_id)` (catalogue ou tentative de doublon SQL natif) et la variante « même course » de CA8 |
| COH5-5 | C1 | Mineur | Clauses secondaires non exécutées, déjà déclarées : CA17 « puis E23 et E20 acceptés » (E20 non enchaîné après E22) ; CA34 E5 d'un coureur détaché non asserté (E4, E13 et scan le sont) ; CA31, volet HTTP joué sur une base déjà en V2 avec une ligne `name` renseignée, et non sur la base migrée depuis V1 (la migration est prouvée séparément par `V2MigrationIT`) | `INC-5-integration.md` §2 (lignes CA17, CA34), §7 | Compléter à l'occasion ; à consigner comme réserves connues |
| COH5-6 | C4 | Mineur | `inc5-ca25-registration-qr.spec.ts` vérifie le lien « J'ai déjà un compte » avec `.first()` : le doublon du lien après un 409 (formulaire + bloc d'erreur, OBS-E2E-1) n'est pas figé. Le doublon lui-même est un défaut d'accessibilité mineur côté application | `inc5-ca25-registration-qr.spec.ts` (dernier `expect`), `INC-5-e2e.md` OBS-E2E-1 | Décider si le doublon est un défaut ; sinon laisser tel quel |
| COH5-7 | C1 | Mineur | La « revue » de CA2 (mise en minuscules à un seul endroit, aucune requête `lower`/`upper`/`IgnoreCase`/`ILIKE`) n'est vérifiée par aucun test automatisé. Vérifiée manuellement par cet agent : une seule occurrence de `toLowerCase` dans la production (`domain/Pseudo.java:44`), aucune des autres formes dans `src/main` (java, resources) | `grep -rn "toLowerCase\|toUpperCase\|IgnoreCase\|ILIKE\|lower(\|upper("` sur `backend/src/main` | Envisager un test d'architecture (ArchUnit ou lecture de sources) pour figer la règle |
| COH5-8 | C1 | Mineur | Incohérence de rédaction de la spec : CA10 attend `accountId` dans la réponse E20, or `RegistrationResponse` ne l'expose pas (RG13 l'interdit côté public) ; le lien est vérifié en base. Déjà consigné (E-INC5-2) | `INC-5-integration.md` E-INC5-2 ; `PATRIMOINE.md` « Écarts ouverts » | Correction de rédaction de la spec par l'agent fonctionnel |

## 3. Détail des contrôles

### 3.1 C3 — Intégrité du patrimoine existant

Méthode : `git diff HEAD` sur `backend/src/test`, `frontend/e2e`, `frontend/src` ; comptage des méthodes de test et des assertions avant / après pour chaque fichier modifié ; recherche de `@Disabled`, `skip`, `fixme`, `only`, `waitForTimeout`.

Backend (19 fichiers de tests existants, arbitrage §6 point 1) :
- Aucun test supprimé : le nombre de méthodes de test est identique avant et après dans chaque fichier. Aucun `@Disabled`, `skip` ni `fixme` dans le dépôt de tests.
- Assertions : nombre identique partout, sauf `Increment1AddendumPersistenceTest` (39 → 42) : les trois cas CA26 convertis (R6, R7, R8) portent chacun deux assertions exactes (nombre de lignes `+1` et valeur relue), conformément à l'arbitrage C2. Aucun affaiblissement.
- Renommages : les 9 renommages R1 à R9 sont appliqués, avec les `@DisplayName` de l'arbitrage. Aucun autre nom de méthode ni `@DisplayName` modifié (les lignes `-` du diff se limitent aux cas listés).
- Catégories B : B1 à B14 conformes (égalités exactes sur `alice`, `Coureur n°6`, etc.). `SecuritySliceTest` (C1) : seuls le bouchon `register` et le corps E3 changent ; aucun `detail` de 401 anonyme touché. `NoTestEndpointsIT` (C4) : 25 méthodes, 20 motifs, égalité exacte conservée.
- `AbstractApiIT` (C3) : nettoyage en deux phases conforme (passages et coureurs de toutes les courses, collecte des `accountId`, puis comptes sans doublon, puis courses ; paramètre `pseudo`). Une simplification cosmétique non listée (variable locale `passages` supprimée avec son import) est sans effet fonctionnel.
- `pom.xml`, configuration surefire / failsafe / JaCoCo et `src/test/resources` : inchangés (absents du `git status`).

Frontend unitaire : `action-visibility.spec.ts` conforme au point 4 de la liste (ligne 14 `EDIT_BIB`, `EDIT_NAME` retiré des lignes 17, 18, 20, 21, 22 ; listes comparées à l'identique). Aucun autre `spec.ts` existant modifié.

E2E (18 fichiers `ca21` à `ca44` + `fixtures/api.ts`) :
- Catégorie A : fixture `register` (E3 avec pseudo + mot de passe, vérification que `name` et `pseudo` renvoyés égalent le pseudo), sélecteurs `exact: true` sur « Supprimer ». Le passage à `exact: true` dans `.toolbar ... toHaveCount(0)` de `ca36` est nécessaire, non un assouplissement : les fiches coureur portent désormais « Supprimer le compte » (RG17), qui satisfaisait la correspondance par sous-chaîne. Bien classé.
- Catégorie B : B1 à B14 remplacent un nom fixe par la valeur renvoyée par l'API (`runner.name`, égale au pseudo créé) : égalité exacte conservée. B15 (`ca22`) : `getByText('Alice', {exact:false})` devient `getByText('alice-{run}', {exact:true})`, donc plus strict. B16 (`ca23`) : mêmes saisies blanches, mêmes 4 cas, sélecteur d'erreur renommé.
- Aucune catégorie C constatée ; aucun test désactivé, aucun timeout modifié (valeurs 4, 5, 11, 15 s identiques avant et après).
- Accord de l'agent fonctionnel : **absent** (COH5-2).

### 3.2 C1 / C2 — Couverture et matrice

- Spec : CA1 à CA44 examinés. Tout critère `[unit]`, `[slice]`, `[IT]`, `[config]` a au moins un test actif ; les `[E2E]` (CA25, CA26, CA28, CA37, CA38, CA41, CA44) ont un fichier chacun ; les `[front-unit]` (CA27, CA35, CA36) ont des tests réels mais aucune ligne de matrice (COH5-1). Les `[manuel]` de CA39 et CA40 ne sont pas exécutables ici (réserves prévues).
- Tests cités dans la matrice : les 35 méthodes IT et unitaires nommées existent (vérification par recherche de `void <nom>` : une occurrence chacune, deux pour `ca23_accountSurvivesRunnerDeletion` qui est cité en deux fichiers).
- Tags : les classes du backend portent `INC-5` ; les méthodes de `AccountContractIT`, `DeployConfigIT` et `FlywayV1ChecksumIT` portent `INC5-CA<k>` valides (CA3 à CA42 existent dans la spec). Aucun tag vers une exigence inexistante. `AccountFlowIT` et les classes unitaires n'ont que le tag de classe `INC-5` : acceptable, la matrice les référence par nom.
- Tests E2E : tags `@INC-5` et `@INC5-CA<k>` valides.
- Lignes de matrice hors CA numérotés (`INC5-RG18 (E6, E4)`, `INC5-C3`, `INC5-A11Y`) : justifiées par l'arbitrage N1 et le rapport E2E, pas d'écart.
- Arbitrage N1 : le test de `runnerName` d'un coureur lié (point 2) existe (`rg18_scanResponseAndBoardCarryDisplayName`) ; le test de D1 (pas de lien sur course fermée) existe (`registration-conflict.spec.ts`) ; D4 vérifié par `ProdLoggingConfigIT` (motif quotidien résolu, `max-history` 6) et présent dans `application-prod.properties`. D2 (E25 hors rafraîchissement périodique) : aucun test dédié, ce n'est pas une exigence de test de l'arbitrage (correction de production, à confirmer dans le rapport du développeur, non fourni ici).

### 3.3 C4 — Qualité des tests

Relus : `DeployConfigIT`, `ProdLoggingConfigIT`, `AccountFlowIT#ca8`, `#ca20`, `#ca1_schema`, `inc5-ca25-registration-qr.spec.ts` (et comptage `expect(` de tous les fichiers `inc5-*` : 6 à 20 assertions par fichier). Assertions pertinentes, aucun `sleep`, aucun test sans assertion, aucun `waitForTimeout`. Aucun test en quarantaine. Remarque de forme (sans constat) : la dernière assertion de `ca20` porte sur les constantes attendues (`expected.values()`) et non sur les lignes capturées ; elle reste valide car `containsAll` impose l'égalité exacte de ces lignes avec les lignes capturées.

### 3.4 C5 — Véracité des rapports (exécution réelle)

| Élément | Annoncé | Constaté par cet agent |
|---|---|---|
| `mvn -B -f backend/pom.xml clean verify` (2026-09-29, ~9 min, npm ci + build PWA inclus) | BUILD SUCCESS | **BUILD SUCCESS**, « All coverage checks have been met », 0 ligne `[WARNING]` Maven (les 4 lignes `WARNING: A Java agent...` sont émises par la JVM Mockito, hors Maven) |
| surefire | 414 / 0 échec | **414 / 0 / 0 ignoré** |
| failsafe | 76 / 0 | **76 / 0 / 0 ignoré** |
| vitest (dans le build Maven) | 235 (23 fichiers) | **235 passés, 23 fichiers** |
| JaCoCo | OK | OK (seuils du `pom.xml`, non modifiés) ; pourcentages détaillés non recalculés |
| E2E suite complète | 115/116, échec CA39 Chromium | Log `frontend/e2e/full-inc5-final.log` (2026-09-29 09:36) : « 1 failed : chromium › ca39-installability-offline » et « 115 passed (17.4m) ». **Suite complète non rejouée** (17 min) |
| E2E échantillon `@INC-5` | 18/18 | **Rejoué : 18 passés** (9 cas x Chromium et WebKit, 2,1 min), backend démarré avec la commande du rapport INC-4 |
| E2E échantillon adapté (ca22, ca23, ca32, ca36, ca44) | (compris dans 115/116) | **Rejoué : 20 passés** (5 fichiers x 2 navigateurs, 4,2 min) |

Aucun écart entre résultat annoncé et résultat constaté. Commandes citées existantes et fonctionnelles. Backend de test arrêté après l'échantillon (port 8080 fermé, vérifié).

Les rapports d'intégration et E2E contiennent une section « Verdict de l'agent fonctionnel » vide, ce qui est attendu à ce stade.

### 3.5 C6 — Non-régression inter-incréments

- Suite complète verte, 414 unitaires et 76 IT, incluant les tests des incréments 1 à 4 (aucun supprimé, voir 3.1). Front : 235 tests dont ceux des 209 de l'inc. 4 (inchangés hors `action-visibility.spec.ts`).
- E2E : les 49 parcours de l'inc. 4 rejoués dans la suite complète (115/116, CA39 Chromium préexistant, LIM-E2E-1) ; échantillon rejoué ci-dessus.
- Comportements modifiés par l'inc. 5 avec mise à jour des tests justifiée par l'arbitrage : corps de E3, nom affiché, E15 sans nom, E6 `runnerName`, liste des endpoints (E1 à E25). Tests de `detail` de 401 anonyme de l'inc. 3 non modifiés (CA21, RG10).
- Aucune régression constatée.

## 4. Contrôles sans constat

- C3 : suppression, désactivation, ignorance de test ; timeouts ; configuration surefire / failsafe / JaCoCo ; renommages hors liste R1 à R9 ; tests de sécurité de l'inc. 3 ; `AbstractApiIT` conforme à la correction C3 ; `action-visibility.spec.ts` conforme.
- C2 : existence des 35 tests cités dans la matrice ; tags vers exigences inexistantes ; validité des tags E2E.
- C4 : `sleep`, tests sans assertion, assertions triviales, quarantaine.
- C5 : chiffres surefire, failsafe, vitest, JaCoCo, log E2E, échantillons E2E, commandes citées.
- C6 : non-régression backend, front et E2E.

## 5. Limites

- Suite E2E complète (116 cas, 17 min) non rejouée : vérifiée par le log daté du jour et par deux échantillons rejoués (38 cas).
- Pourcentages JaCoCo par paquet (domain 98,6 %, service 99,2 %) non recalculés ; seul le contrôle de seuils du `pom.xml` a été observé (« All coverage checks have been met »).
- Aucun compte rendu écrit du testeur (vérification de N1 étape 3, verdict technique OK / KO) n'est présent dans `docs/tests/rapports/` : je ne peux pas confirmer sa vérification ; j'ai refait la comparaison diff / liste de l'arbitrage moi-même (conforme).
- Rapport du développeur sur D2 (E25 hors rafraîchissement périodique) non fourni : non vérifié.
- Parties `[manuel]` de CA39 et CA40 (VPS) non vérifiables ; RT1 (PostgreSQL réel : `V2__account.sql`, `LIKE ... ESCAPE`, index unique concurrent, `uq_runner_race_account`) reste ouverte ; tout est joué sur H2.
- Pas d'exécution de tests de stabilité multi-exécution des nouveaux E2E au-delà des exécutions ci-dessus.
- Le contenu des fichiers `deploy/` (nginx, logrotate, journald) n'a été relu qu'à travers les assertions de `DeployConfigIT`.

## 6. Recommandation à l'agent fonctionnel

**Prêt pour arbitrage** : aucun constat bloquant. Les chiffres annoncés sont confirmés par exécution réelle, le patrimoine existant est intact, et le diff des tests existants correspond à la liste arbitrée.

Points à traiter dans l'arbitrage, avant ou avec le verdict :
1. COH5-2 : donner l'accord écrit sur les adaptations E2E (A et B), en tranchant B16 et le filtre `qr-card` de A2. Sans cet accord, l'écart devient bloquant.
2. COH5-3 : trancher E-INC5-1 (pseudo dans le WARN des 409 de E3).
3. COH5-1 : faire compléter la matrice (front-unit, `RunnerAccountLinkTest`, ligne INC4-CA20).
4. COH5-4 : faire ajouter les tests manquants de CA1 (`(race_id, account_id)`) et de CA8 (variante « même course »), ou les consigner comme réserves.

Ce rapport constate et ne tranche pas : le verdict GO / GO sous réserves / NO-GO appartient à l'agent fonctionnel.
