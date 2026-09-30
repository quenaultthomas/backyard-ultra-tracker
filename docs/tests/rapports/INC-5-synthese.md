# Rapport de test : INC-5 (synthèse)

- **Date** : 2026-09-30
- **Agent auteur** : testeur (passe N2, actions 8, 9 et 10 de `INC-5-arbitrage-N2.md` §6)
- **Version / commit testé** : branche `feature/increment-5-comptes-pseudo`, HEAD `b09a064` + working tree non commité (correction de `ApiExceptionHandler`, ajouts dans `AccountFlowIT` et `AccountUniquenessIT`, spec, patrimoine et rapports)
- **Environnement** : Windows 11, JDK 25 (release 21), Maven, Node v24.21.0 (déjà installé dans `frontend/node`), Java 21 pour le bytecode, backend profil `test`, H2 2.4 en mode PostgreSQL. Aucun PostgreSQL disponible (RT1).

Rapports sources :
- Intégration : `INC-5-integration.md` (mis à jour N2)
- E2E : `INC-5-e2e.md` (exécution du 2026-09-29, non rejouée ici : voir §3)
- Cohérence : `INC-5-coherence.md` (2026-09-29)
- Arbitrages : `INC-5-arbitrage-N1.md`, `INC-5-arbitrage-N2.md`

**Verdict technique du testeur : OK** (définition de « fini » : voir §3 et §4). Ce n'est pas le verdict final : il appartient à l'agent fonctionnel (§8).

## 1. Périmètre

Spec `docs/specs/increment5.md` (révision 5) : comptes pseudo coureurs (CA1 à CA44). Liste fermée de l'arbitrage N2 §6 : points 1 à 7 faits par les autres agents (constatés dans le working tree), points 8, 9, 10 faits par ce rapport.

## 2. Couverture exigences <-> tests

Référentiel : `docs/tests/PATRIMOINE.md` (sections « Incrément 5 — backend », « Incrément 5 — front-unit », « Incrément 5 — Parcours E2E »). Changements de cette passe :
- section « Incrément 5 — front-unit » ajoutée (CA27, CA35, CA36, CA41 unité, RG21, RG17, D1) : COH5-1 levé ;
- `domain/RunnerAccountLinkTest` (5 tests) référencé ;
- ligne `INC4-CA20` mise à jour (adaptation N1 de `action-visibility.spec.ts`, renvoi arbitrage N2 §5 point 9) ;
- ligne CA20 N2 : échec avant correction observé (§5).

| Critères | Type | Tests | Résultat | Écart ? |
|---|---|---|---|---|
| CA1 à CA24, CA29 à CA34, CA42, CA43, RG18 | IT / UNIT / slice | `AccountFlowIT`, `AccountContractIT`, `AccountUniquenessIT`, `V2MigrationIT`, `FlywayV1ChecksumIT`, classes unitaires | PASS | Non (points 3 à 6 de N2 vérifiés : voir `PATRIMOINE.md`) |
| CA20 (dont 409 sur E3 sans pseudo) | IT | `AccountFlowIT#ca20_logsWithoutSecretsNorPseudo`, `#ca20_conflictOnRegistrationLeavesNoPseudoInLogs` | PASS | Non |
| CA39, CA40 [config] | IT | `ProdLoggingConfigIT`, `DeployConfigIT` | PASS | Parties [manuel] VPS non exécutées : réserve |
| CA27, CA35, CA36, CA41 (unité), RG17, RG21, D1 | front-unit | 5 fichiers `frontend/src/app/core/*.spec.ts` | PASS (235/235 au total) | Non |
| CA25, CA26, CA28, CA37, CA38, CA41, CA44 | E2E | `frontend/e2e/tests/inc5-*.spec.ts` | PASS 2026-09-29 (rapport E2E), non rejoué | Voir §3 |

Exigences sans test : **aucune**.

## 3. Résultats d'exécution (réels)

Commande unique, sans aucune option de contournement : `mvn -B -f backend/pom.xml clean verify`, lancée le **2026-09-30 à 15:13, terminée à 15:19 (5 min 32 s), code de sortie 0, BUILD SUCCESS** (journal `mvn1.log` du scratchpad de la session).

| Suite | Total | Passés | Échoués | Ignorés |
|---|---|---|---|---|
| Backend unitaires + slice (surefire) | 414 | 414 | 0 | 0 |
| Backend intégration `*IT` (failsafe) | 85 | 85 | 0 | 0 |
| Front unitaires (Vitest 5.0.2, 23 fichiers, dans le build Maven) | 235 | 235 | 0 | 0 |
| **Total build Maven** | **734** | **734** | **0** | **0** |

Détail failsafe des classes de l'incrément : `AccountContractIT` 20, `AccountFlowIT` 14, `AccountUniquenessIT` 5. `RunnerAccountLinkTest` 5/5 (surefire).

Couverture JaCoCo (lignes, tests unitaires uniquement, lu dans `backend/target/site/jacoco/jacoco.csv`) : `domain` 208/211 = **98,58 %**, `service` 386/389 = **99,23 %**, `service.exception` 15/16 = 93,75 % ; domain + service + exception : 609/616 = **98,86 %**. Seuil 80 % : « All coverage checks have been met ». Front, `src/app/core` (v8) : 668/671 lignes = **99,55 %**, 689/692 instructions = 99,56 % (seuil bloquant 80 %).

**Compilation sans warning** : `Compiling 92 source files with javac [debug parameters release 21]` puis `Compiling 57 source files` (tests) ; **0 ligne `[WARNING]` et 0 ligne `[ERROR]`** dans le journal Maven (`grep '^\[WARNING\]\|^\[ERROR\]'` vide). Build Angular (`ng build --configuration production`) et `tsc -p tsconfig.spec.json --noEmit` sans avertissement. Lignes `WARNING:` sans crochets : messages de la JVM (agent Byte Buddy de Mockito, partage de classes), hors compilation. Lignes `WARN` applicatives des journaux de test (Flyway H2 plus récent que la version vérifiée, configuration de contexte de test) : sans lien avec la compilation, non nouvelles.

**Build front (`Cannot find module 'ajv'`) : non reproduit, aucun contournement utilisé.** Diagnostic :
- Le build complet ci-dessus a exécuté `npm ci --no-audit --no-fund` (245 paquets, 55 s) puis `npm run build` puis `npm test` avec succès : **pas de `-Dskip.npm`, pas d'écart à compter**.
- Le journal npm (`AppData/Local/npm-cache/_logs`, 2026-09-30) montre que `ajv@8.20.0` et `ajv-formats` sont bien installés (`silly ADD node_modules/ajv`) par chaque `npm ci` terminé avec `exit 0` (13:05:04, 13:05:52, 13:13:40).
- Le journal montre un `npm run build` terminé en `exit 127` à 13:05:47, entre deux `npm ci` démarrés à 13:05:04 et 13:05:52. Or `npm ci` supprime puis recrée `frontend/node_modules`. Hypothèse la plus probable (non prouvée, la trace exacte de l'erreur `ajv` n'est pas dans les journaux) : deux invocations concurrentes ou imbriquées (`npm ci` d'un build Maven et `npm run build` / `npm ci` lancés en parallèle par un autre agent ou un terminal) sur le même dossier `node_modules`, qui a laissé l'arbre incomplet à ce moment. Rien dans le dépôt (`package.json`, `package-lock.json`, aucun fichier front modifié depuis HEAD) n'explique un défaut structurel.
- Recommandation : ne jamais lancer deux commandes npm/Maven sur `frontend/` en même temps ; si l'erreur revient sur un poste seul, joindre le journal npm complet.

**E2E `@INC-5` non rejoués** : `git diff HEAD --stat -- frontend` est vide (aucun fichier du front, ni des tests E2E, modifié depuis HEAD ; `frontend/dist` reconstruit par le build est ignoré par git). Les derniers résultats E2E restent ceux de `INC-5-e2e.md` (2026-09-29 : 115/116, seul échec CA39 Chromium préexistant, LIM-E2E-1) : cités, non réexécutés.

## 4. Définition de « fini »

| Critère | Constat |
|---|---|
| Compile sans warning | Oui (§3) |
| Tests unitaires verts | Oui : 414/414, 85/85, 235/235 |
| Couverture cœur >= 80 % | Oui : domain 98,58 %, service 99,23 % |
| Aucune règle métier dupliquée | Oui. Recherche ciblée : `toLowerCase` / `IgnoreCase` / `ILIKE` / `lower(` : une seule occurrence dans `src/main` (`domain/Pseudo.java:44`), donc normalisation du pseudo en un seul endroit (RG2, CA2) ; le nom affiché (RG18) est dérivé dans le domaine (`RunnerAccountLinkTest`) |
| Erreurs jamais avalées | Oui. Deux `catch` sans relance dans `src/main` relus : `ProblemDetailFactory.toInstanceUri` (repli documenté par encodage de l'URI) et `YardClosingService.closeElapsedYards` (l'exception est conservée dans `failuresByRaceId` puis remontée) |
| Vérification N2 point 9 (aucun test ne dépendait du `detail` du WARN) | Oui : le seul test qui lit « Requête refusée » est le nouveau `ca20_conflictOnRegistrationLeavesNoPseudoInLogs` (recherche `Requête refusée` dans `backend/src`, `frontend/src`, `frontend/e2e/tests`) ; les autres classes qui capturent les journaux (`AccountServiceTest`, `QrTokenUniquenessIT`) sont sans rapport avec le message |

## 5. Test de journal CA20 : échec avant correction rejoué

L'arbitrage N2 §2 exigeait un test qui échoue avant la correction. Il ne l'avait pas été observé (rapport d'intégration §3). **Rejoué le 2026-09-30, sans toucher au dépôt** : copie de `backend/src`, `pom.xml` et `frontend/dist` dans le scratchpad (`.../scratchpad/replay`), `ApiExceptionHandler.java` remplacé par la version de `HEAD` (`git show HEAD:...`, `diff` vide, ligne 149 : `problem.getDetail()`), commande `mvn -B -f <copie>/backend/pom.xml verify -Dskip.npm -Dskip.installnodenpm -Djacoco.skip=true -Dtest=NoSuch -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='AccountFlowIT#ca20*'` (contournement npm limité à cette copie de rejeu, sans effet sur le build officiel du §3).

Résultat : **`ca20_conflictOnRegistrationLeavesNoPseudoInLogs` en échec** (`AccountFlowIT.java:422`, `Tests run: 2, Failures: 1`), sortie :

```
[WARN] Requête refusée POST /api/public/races/1/registrations : 409 Pseudo déjà utilisé : lievre. Si c'est votre compte, connectez-vous pour vous inscrire avec.
Expecting actual: "requête refusée post /api/public/races/1/registrations : 409 pseudo déjà utilisé : lievre. ..." not to contain: "lievre"
```

`ca20_logsWithoutSecretsNorPseudo` reste vert sur l'ancienne version (attendu, il ne visait pas ce cas). Avec le correctif du working tree, les deux passent (§3). L'état du dépôt est inchangé par ce rejeu (`git status` identique avant et après). Le test est donc **discriminant** : point 2 de l'arbitrage N2 satisfait, et le point à soumettre à l'agent fonctionnel (§7) est levé en fait.

## 6. Compte rendu de la vérification N1 étape 3 (diff des tests existants comparé à la liste)

Méthode de cette passe :
1. Comparaison de `2774bdf` (fin de l'inc. 4) au working tree pour les 51 fichiers de tests préexistants modifiés (`backend/src/test`, `frontend/src`, `frontend/e2e/tests`) : **nombre de `@Test` / `@ParameterizedTest` / `test(` / `it(` identique avant et après pour chacun** ; **0 occurrence** de `@Disabled`, `.skip(`, `.fixme(`, `.only(`, `xit(`, `xdescribe` dans les tests suivis ;
2. Différence entre `HEAD` et le working tree sur les tests : 3 lignes supprimées au total, toutes dans `AccountUniquenessIT` (renommage du helper, §7) ; le reste est un ajout (5 `@Test` : 4 dans `AccountFlowIT`, 1 dans `AccountUniquenessIT`) ;
3. Le diff détaillé par ligne de l'arbitrage N1 (R1 à R9, B1 à B14, C1 à C4, catégories A et B de l'E2E) n'a **pas été refait ligne à ligne** par cet agent : je m'appuie sur la revue de cohérence §3.1 (`INC-5-coherence.md`, 2026-09-29), qui l'a fait et l'a trouvé conforme. Limite à lire comme telle : je confirme les comptes et l'absence de désactivation, pas chaque assertion.

Aucun test supprimé, désactivé ou assoupli constaté.

## 7. À soumettre expressément à l'agent fonctionnel (règle 2 du workflow)

1. **Renommage d'un helper dans un test existant** : `it/AccountUniquenessIT` (fichier introduit par le commit `b09a064`, jamais vert avant cette passe car il ne compilait pas). Le helper privé `register(Long raceId, String pseudo)` redéfinissait `AbstractApiIT.register` (protégé) avec un accès plus faible, erreur de compilation « attempting to assign weaker access privileges ». Il a été renommé `registerStatus` (1 déclaration, 2 appels dans `concurrently`). Constaté par `git diff HEAD` : les 3 seules lignes supprimées de tout le diff des tests. Corps, assertions, données et `@DisplayName` des 4 tests d'origine inchangés (`ca1_raceAccountUniqueConstraint`, `ca8_samePseudoOnTwoRaces`, `ca8_samePseudoOnSameRace`, `ca8_differentCaseOnSameRace`) ; un 5e test (`ca1_accountForeignKeyIsRestrict`) a été ajouté à N2. Effet : ces 4 tests passent de « non compilés, donc jamais exécutés » à « exécutés et verts ». Demande d'accord écrit (correction de compilation, sans affaiblissement).
2. **Test CA20 « pas de pseudo dans les journaux » jamais vu en échec avant correction** : levé par le rejeu du §5 (échec observé sur l'ancien `ApiExceptionHandler`). À consigner comme satisfait ; rien d'autre à décider, sauf si l'agent fonctionnel exige un autre mode de rejeu.

## 8. Confirmation `V2MigrationIT` (réserve CA31, arbitrage N2 §3)

Condition posée : `V2MigrationIT` vérifie `count(name IS NOT NULL) = 0`, `account_id = null`, dossard, statut, `qrToken`, 2 passages, et Bob (`dnf_reason`, `dnf_yard`). Lecture du fichier `it/V2MigrationIT.java` (lignes 41 à 64) et exécution (1/1 `PASS`, 2026-09-30, failsafe) :

| Élément exigé | Assertion constatée |
|---|---|
| `count(name IS NOT NULL) = 0` | `SELECT COUNT(*) FROM runner WHERE name IS NOT NULL` `isZero()` (données V1 : Alice et Bob avec un nom renseigné avant la migration V2) |
| `account_id = null` | `containsEntry("ACCOUNT_ID", null)` pour Alice et pour Bob ; `COUNT(*) FROM account` `isZero()` |
| dossard, statut, `qrToken` | Alice : `BIB` 1, `STATUS` ACTIVE, `QR_TOKEN` `tok-alice` |
| 2 passages | `COUNT(*) FROM passage WHERE runner_id = alice` `isEqualTo(2)` (un scan, un MANUAL avec `scanned_at` null) |
| Bob (`dnf_reason`, `dnf_yard`) | `BIB` 3, `STATUS` DNF, `DNF_REASON` TIMEOUT, `DNF_YARD` 4 |

**Confirmé : `V2MigrationIT` prouve la purge et la conservation des données. La réserve CA31 tient** (elle ne retombe pas en écart bloquant). Le volet HTTP sur base migrée depuis V1 reste à faire avant le verdict de l'inc. 6.

## 9. Réserves connues (à porter au verdict)

| Réserve | Contenu | Échéance (arbitrage N2) |
|---|---|---|
| CA39 et CA40 `[manuel]` | Journaux de plus de 7 jours et 429 nginx réel sur le VPS, non exécutables ici (`DeployConfigIT` et `ProdLoggingConfigIT` couvrent les fichiers de configuration) | Avant la mise en service de l'inc. 5 (action de l'exploitant, installation des fichiers `deploy/`) |
| RT1 | Aucun PostgreSQL réel : `V2__account.sql`, `LIKE ... ESCAPE` (CA43), index unique concurrent (CA8), `uq_runner_race_account`, `uq_...` de `pseudo` | Avant tout déploiement sur le VPS |
| LIM-E2E-1, CA39 Chromium | Échec préexistant depuis l'inc. 4 (émulation réseau et service worker) ; test actif et en échec déclaré, sans régression nouvelle | Réserve R4-1 de l'inc. 4 (avant déploiement) |
| COH5-5 CA31 | Volet HTTP joué sur une base déjà en V2 avec une ligne SQL nommée ; la migration est prouvée par `V2MigrationIT` (§8) | Avant le verdict de l'inc. 6 |
| COH5-6 | Doublon du lien « J'ai déjà un compte » après un 409 (test avec `.first()`), non bloquant | Au gré du développeur ; si corrigé, resserrer en `toHaveCount(1)` |
| COH5-7 | Revue de CA2 (une seule occurrence de `toLowerCase`, aucune requête `lower` / `upper` / `IgnoreCase` / `ILIKE`) sans test automatisé : constatée à la main ici (§4) | Test de lecture de sources avant le verdict de l'inc. 6 |
| D2 | Confirmation écrite du développeur (E25 hors rafraîchissement périodique) : **non fournie à cet agent**, non vérifiée ici | Action 7 de l'arbitrage N2, bloquante pour le verdict |
| E2E non rejoués | Résultats E2E de la synthèse = ceux du 2026-09-29 (front non modifié) | À rejouer si le développeur touche le front pour D2 |
| Adaptations E2E (COH5-2) | Accord de l'agent fonctionnel donné dans l'arbitrage N2 §1 | Levé |

## 10. Modifications du patrimoine existant

- Ajouts : lignes de matrice front-unit (8), `RunnerAccountLinkTest` (1), note de rejeu CA20 ; aucun test ajouté, modifié ni supprimé par cette passe (le testeur n'a modifié ni code de production ni test).
- Aucun test désactivé, supprimé ou assoupli (§6). Seul le renommage de helper du §7 est soumis à accord.
- Aucun code de production modifié par le testeur. `retries: 0` inchangé côté E2E.

## 11. Verdict de l'agent fonctionnel (emplacement réservé : la « section 8 » du modèle)
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : **GO sous réserves**
- **Date** : 2026-09-30

### Vérifications faites par l'agent fonctionnel
- Chiffres du §3 pris comme réels (commande `mvn -B -f backend/pom.xml clean verify`, 2026-09-30, 734/734, JaCoCo domain 98,58 %, service 99,23 %, 0 `[WARNING]`), sans contournement npm dans le build officiel. Cohérent avec la revue de cohérence (2026-09-29, 0 bloquant) et l'absence de modification du front depuis HEAD.
- Actions 1 à 10 de l'arbitrage N2 : constatées dans le working tree. `ApiExceptionHandler.respond` (l. 147-151) n'écrit plus le `detail` (méthode, chemin, statut, `code`). Tests présents : `AccountFlowIT#ca20_conflictOnRegistrationLeavesNoPseudoInLogs`, `#ca17_changePasswordThenRegisterExistingAfterReset` et `#ca17_registerExistingDirectlyAfterReset`, `#ca34_publicRunnerOfDetachedRunnerShowsBibName`, `AccountUniquenessIT#ca1_raceAccountUniqueConstraint`, `#ca1_accountForeignKeyIsRestrict`, `#ca8_samePseudoOnSameRace`, `#ca8_samePseudoOnTwoRaces`, `#ca8_differentCaseOnSameRace`.
- **D2 vérifié par moi dans `frontend/src`** (la confirmation du développeur n'est pas retenue sur parole) : `API_PATHS.adminAccounts` n'est appelé que dans `admin-accounts-page.ts` (`load()`, atteint par `ngOnInit`, la recherche explicite et `reload()` après suppression) et dans `admin-race-page.ts` (`reload()`, atteint par `ngOnInit` et après une action). Aucun `setInterval`, `setTimeout` ni `Poller` dans ces deux pages. `startPagePolling`, `core/polling.ts` et `infra/browser-scheduler.ts` ne sont utilisés que par `board-page.ts` et `runner-page.ts`. E25 n'est donc pas rafraîchi périodiquement : conforme. Aucun front modifié, donc pas de rejeu E2E exigé.
- Aucun CA sans test actif (matrice §2, PATRIMOINE.md). COH5-1, 3, 4, 5(CA17, CA34), 8 levés.

### Arbitrages de la §7
1. **Renommage `register` -> `registerStatus` (`it/AccountUniquenessIT`) : ACCORD.** Correction d'une erreur de compilation (réduction d'accès sur `AbstractApiIT.register`), 1 déclaration + 2 appels, aucune assertion, donnée ni `@DisplayName` modifiés (3 lignes supprimées, constaté par `git diff HEAD`). Ce n'est ni un affaiblissement ni une suppression. Remarque : les 4 tests d'origine de ce fichier n'avaient jamais été exécutés avant cette passe ; ils le sont et sont verts.
2. **Test CA20 rejoué en échec sur l'ancien `ApiExceptionHandler` : SUFFISANT.** Le rejeu (§5) est reproductible (copie hors dépôt, `git show HEAD:`, échec à `AccountFlowIT.java:422`, sortie citée avec le pseudo dans la ligne WARN), et le test passe avec le correctif dans le build officiel. Il est discriminant. Point 2 de l'arbitrage N2 satisfait.

### Réserves (chacune avec action et échéance)
| # | Réserve | Action | Échéance |
|---|---|---|---|
| R5-1 | CA39 et CA40 `[manuel]` : journaux > 7 jours et 429 nginx réel | Exploitant : installer `deploy/` sur le VPS et exécuter les deux vérifications | Avant la mise en service de l'inc. 5 (bloquante) |
| R5-2 | RT1 : aucun PostgreSQL réel (V2, `LIKE ... ESCAPE`, index unique concurrent, `uq_runner_race_account`) | Suite `*IT` + profil `prod` sur PostgreSQL de même version majeure | Avant tout déploiement sur le VPS (bloquante), fusionnée avec RT1 |
| R5-3 | LIM-E2E-1, CA39 Chromium (R4-1), échec préexistant, aucune régression | Lever par D2 ou D3 de la spec inc. 4 | Avant tout déploiement sur le VPS (bloquante) |
| R5-4 | CA31 : volet HTTP joué sur base déjà en V2 (purge prouvée par `V2MigrationIT`, condition de l'arbitrage remplie, §8) | Volet HTTP sur base migrée depuis V1 | Avant le verdict de l'inc. 6 |
| R5-5 | COH5-7 : revue de CA2 sans test automatisé | Test de lecture de sources figeant la règle (une seule occurrence de `toLowerCase`, aucun `lower/upper/IgnoreCase/ILIKE`) | Avant le verdict de l'inc. 6 |
| R5-6 | COH5-6 : doublon du lien « J'ai déjà un compte » après un 409 | Optionnel ; si corrigé, resserrer `.first()` en `toHaveCount(1)` | Sans échéance (non bloquante) |
| R5-7 | Limite du testeur : vérification ligne à ligne N1 §6 non refaite (comptes et absence de désactivation confirmés) ; la revue de cohérence l'avait faite, conforme | Aucune | Levée |
| R5-8 | Observation : `ApiExceptionHandler.handleInconsistency` (l. 118) journalise l'exception complète en ERROR ; un message d'exception pourrait contenir un pseudo (RG16) | Le développeur vérifie qu'aucun message d'`IllegalState/IllegalArgumentException` n'inclut de pseudo, ou ajoute un test | Avant le verdict de l'inc. 6 (non bloquante) |

### Actions correctives exigées
Aucune avant GO. Les E2E ne sont pas rejoués depuis le 2026-09-29 : à rejouer si le front est touché avant la MR.
