# Patrimoine de test

Source de vérité de la couverture de test. Mis à jour par les agents `test-integration-backend` et `test-e2e-frontend` à chaque incrément. Relu et validé par l'agent fonctionnel.

## Règles

1. Toute exigence a au moins un test (sinon statut `ÉCART`).
2. Chaque test référence son exigence et son incrément (tags dans le code).
3. Aucun test supprimé, désactivé ou assoupli sans motif consigné dans la colonne Notes et validation de l'agent fonctionnel.
4. Types : `UNIT` (JUnit : tests unitaires purs et `@WebMvcTest` à services mockés), `INT` (intégration backend : `@DataJpaTest` et `*IT` en contexte Spring complet sur H2), `E2E` (front, à partir de l'incrément 4).
5. Statuts : `ACTIF`, `QUARANTAINE` (motif obligatoire), `OBSOLÈTE` (exigence retirée, validé par l'agent fonctionnel).
6. Exigences : `INC<n>-CA<k>` = critère `CA<k>` de `docs/specs/increment<n>.md` (la numérotation des CA recommence à 1 dans chaque spec). Tags de test correspondants : `@Tag("INC-<n>")` et `@Tag("INC<n>-CA<k>")`. Les tests des incréments 1 à 3, antérieurs à ce workflow, n'ont pas de tags : ils sont référencés par chemin et nom.
7. Chemins de test relatifs à `backend/src/test/java/fr/backyard/`.

## Mode rattrapage (2026-09-26)

Cartographie rétroactive des incréments 1 à 3 (livrés avant la mise en place de ce workflow), réalisée par
l'agent `test-integration-backend`. Les tests existants (`*Test`, `@DataJpaTest`, `@WebMvcTest`) ne portent
pas de tags (règle 6) : ils sont référencés ci-dessous par chemin de fichier et nom de méthode. Les tests
`*IT` ajoutés lors de ce rattrapage, sous `backend/src/test/java/fr/backyard/it/`, portent les tags
`@Tag("INC-<n>")` et `@Tag("INC<n>-CA<k>")` prescrits par la convention.

Nouveaux tests d'intégration ajoutés (contexte Spring complet, sécurité réelle, H2 réel via Flyway) :
- `it/SchemaAndContextStartupIT.java` (INC-1) : démarrage du contexte complet (Flyway + `ddl-auto=validate`,
  CA20) et suppression bloquée par les clés étrangères RESTRICT (CL8, CL9, non couvertes par un CA numéroté).
- `it/RepositoryDerivedQueriesIT.java` (INC-3) : méthodes de repository dérivées ajoutées par l'incrément 3
  (`existsByName`, `existsByNameAndIdNot`, `existsByRaceIdAndBib[AndIdNot]`, `existsByRunnerId`,
  `findByRunnerRaceId`), jamais exécutées contre une vraie base dans les tests existants (repositories mockés
  ou faux en mémoire).
- `it/QrTokenUniquenessIT.java` (INC-3, CA8) : violation réelle de `uq_runner_qr_token` (générateur de jeton
  de test forçant la collision) mappée en 409 `DATA_INTEGRITY` à travers la chaîne HTTP -> sécurité ->
  service -> JPA -> H2, alors que CA8 n'était vérifié qu'avec un service mocké levant une exception construite
  à la main.
- `it/RaceBoardTransactionBoundaryIT.java` (INC-3, RG2, CA37, CA44) : `RaceBoardService.runnerDetail` et
  `describePassages` chargent l'association paresseuse `Runner.race` (FetchType.LAZY) ; vérifié avec un vrai
  contexte JPA et `spring.jpa.open-in-view=false`, jamais observable avec des repositories mockés.
- `it/EndToEndRaceLifecycleIT.java` (INC-2, INC-3) : scénario de bout en bout (création de course ->
  inscription -> démarrage -> scan -> clôture de yard par `YardClosingService` avec une `Clock` de test ->
  auto-DNF -> réintégration admin -> tableau de bord), jamais exercé de bout en bout avec une vraie base et
  une vraie chaîne HTTP (les tests de service de l'incrément 2 mockent les repositories, les tests de slice
  de l'incrément 3 mockent les services).

Aucun test existant n'a été modifié, affaibli ou supprimé.

### Corrections du 2026-09-26 (suite à la revue de cohérence du patrimoine)

La revue de cohérence (`docs/tests/rapports/INC-{1,2,3}-coherence.md`) a relevé des tests réels, présents
dans le code et pertinents, mais absents de la matrice (recherche automatisée, comparaison exhaustive de
toutes les méthodes `@Test`/`@ParameterizedTest` de `backend/src/test/java` contre les références de cette
matrice). Corrigé, sans modifier ni affaiblir aucun test :
- `INC1-CA20` : rattaché à la classe entière `RacePersistenceTest.java` sans nom de méthode ; rattaché aux
  méthodes qui le vérifient réellement (`it/SchemaAndContextStartupIT.java`), `RacePersistenceTest.java`
  gardé en note de couverture implicite.
- `INC1-TECH1` (nouvelle ligne) : `persistence/RacePersistenceTest.java#ca23_raceAndRunnerSettersUpdateFields`,
  test technique hors CA numéroté (la spec `increment1.md` ne compte que 22 CA), à arbitrer par le fonctionnel.
- `INC2-CA40 (RG29, domaine)` et `INC2-CA33 (RG29, domaine)` ×2 (nouvelles lignes) :
  `domain/StateTransitionsTest.java#rg29_markDnf`, `#rg29_markWinner`, `#rg29_raceFinish`.
- `INC3-TECH1` (nouvelle ligne) : `it/RepositoryDerivedQueriesIT.java#existsByRaceIdAndBibDetectsDuplicateBib`,
  jusque-là mentionné seulement en prose dans l'introduction ci-dessus.
- `INC3-CA57` : rattaché à la classe entière `SecurityCredentialsStartupTest.java` sans nom de méthode ;
  rattaché à ses 6 méthodes réelles.

Vérification exhaustive refaite après correction : les 258 méthodes `@Test`/`@ParameterizedTest` du dépôt
(`backend/src/test/java/fr/backyard/`) sont toutes référencées au moins une fois dans la matrice, et aucune
référence de la matrice ne pointe vers une méthode inexistante.

Par ailleurs, la couverture JaCoCo citée dans les trois rapports d'intégration (`docs/tests/rapports/INC-{1,2,3}-integration.md`)
a été corrigée : la mesure précédente (domain 94,13 %, service 98,83 %, ensemble 97,36 %) n'était pas
reproductible car `target/jacoco.exec` était lu à la fois par le rapport (phase `test`) et par le `check`
(phase `verify`), ce dernier accumulant en plus la couverture des tests `*IT` exécutés entre les deux phases.
Le `pom.xml` sépare désormais cette couverture dans `target/jacoco-it.exec` (voir section correspondante de
chaque rapport). Mesure corrigée et reproductible : domain 180/185 = 97,30 %, service 294/297 = 98,99 %,
ensemble 474/482 = 98,34 % (tests unitaires uniquement, conformément à CLAUDE.md). Seuil de 80 % franchi
avant et après correction : sans conséquence sur le verdict de couverture.

### Note E2E (agent `test-e2e-frontend`)

Pas de test E2E pour INC-1 à INC-3 : `frontend/` ne contient aucune application (seul `.gitkeep`) et aucun
outil E2E n'est présent dans le dépôt. Les parcours utilisateur (inscription, scan, dashboard, admin)
seront couverts à partir de l'incrément 4, quand la PWA sera livrée. Voir `docs/tests/rapports/INC-1-e2e.md`,
`INC-2-e2e.md` et `INC-3-e2e.md`.

## Matrice exigences ↔ tests

### Incrément 1 — Domaine et persistance

| Exigence | Incrément | Description | Type | Test (chemin / nom) | Statut | Dernier résultat | Notes |
|---|---|---|---|---|---|---|---|
| INC1-CA1 | INC-1 | Persistance minimale de Race | INT | persistence/RacePersistenceTest.java#ca1_persistMinimalRace | ACTIF | PASS 2026-09-26 | |
| INC1-CA2 | INC-1 | Statut SETUP par défaut | INT | persistence/RacePersistenceTest.java#ca2_defaultStatusIsSetup | ACTIF | PASS 2026-09-26 | |
| INC1-CA3 | INC-1 | started_at null à la création | INT | persistence/RacePersistenceTest.java#ca3_startedAtNullOnCreation | ACTIF | PASS 2026-09-26 | |
| INC1-CA4 | INC-1 | Unicité du nom de Race | INT | persistence/RacePersistenceTest.java#ca4_duplicateRaceNameThrows | ACTIF | PASS 2026-09-26 | |
| INC1-CA5 | INC-1 | Persistance minimale de Runner | INT | persistence/RacePersistenceTest.java#ca5_persistMinimalRunner | ACTIF | PASS 2026-09-26 | |
| INC1-CA6 | INC-1 | Statut ACTIVE par défaut pour Runner | INT | persistence/RacePersistenceTest.java#ca6_defaultRunnerStatusIsActive | ACTIF | PASS 2026-09-26 | |
| INC1-CA7 | INC-1 | Unicité du dossard par course | INT | persistence/RacePersistenceTest.java#ca7_duplicateBibSameRaceThrows | ACTIF | PASS 2026-09-26 | |
| INC1-CA8 | INC-1 | Dossard identique dans deux courses différentes autorisé | INT | persistence/RacePersistenceTest.java#ca8_sameBibDifferentRacesAllowed | ACTIF | PASS 2026-09-26 | |
| INC1-CA9 | INC-1 | Unicité du qr_token | INT | persistence/RacePersistenceTest.java#ca9_duplicateQrTokenThrows | ACTIF | PASS 2026-09-26 | Renforcé au niveau HTTP par INC3-CA8 (it/QrTokenUniquenessIT) |
| INC1-CA10 | INC-1 | Champs DNF nullables pour un runner ACTIVE | INT | persistence/RacePersistenceTest.java#ca10_dnfFieldsNullableForActiveRunner | ACTIF | PASS 2026-09-26 | |
| INC1-CA11 | INC-1 | Persistance minimale de Passage avec scanned_at | INT | persistence/RacePersistenceTest.java#ca11_persistPassageWithScannedAt | ACTIF | PASS 2026-09-26 | |
| INC1-CA12 | INC-1 | Passage manuel avec scanned_at null | INT | persistence/RacePersistenceTest.java#ca12_manualPassageWithNullScannedAt | ACTIF | PASS 2026-09-26 | |
| INC1-CA13 | INC-1 | Unicité passage par coureur et yard | INT | persistence/RacePersistenceTest.java#ca13_duplicatePassageSameRunnerYardThrows | ACTIF | PASS 2026-09-26 | |
| INC1-CA14 | INC-1 | Runner sans passage | INT | persistence/RacePersistenceTest.java#ca14_runnerWithNoPassages | ACTIF | PASS 2026-09-26 | |
| INC1-CA15 | INC-1 | findByRaceId retourne tous les coureurs d'une course | INT | persistence/RacePersistenceTest.java#ca15_findByRaceIdReturnsAllRunners | ACTIF | PASS 2026-09-26 | |
| INC1-CA16 | INC-1 | findByQrToken retourne le bon coureur | INT | persistence/RacePersistenceTest.java#ca16_findByQrTokenReturnsCorrectRunner | ACTIF | PASS 2026-09-26 | |
| INC1-CA17 | INC-1 | findByRaceIdAndStatus filtre par statut | INT | persistence/RacePersistenceTest.java#ca17_findByRaceIdAndStatusFilters | ACTIF | PASS 2026-09-26 | |
| INC1-CA18 | INC-1 | findByStatus retourne les courses par statut | INT | persistence/RacePersistenceTest.java#ca18_findByStatusReturnsCorrectRaces | ACTIF | PASS 2026-09-26 | |
| INC1-CA19 | INC-1 | findByRunnerIdAndYardNumber retourne le bon passage | INT | persistence/RacePersistenceTest.java#ca19_findByRunnerIdAndYardNumberReturnsCorrectPassage | ACTIF | PASS 2026-09-26 | |
| INC1-CA20 | INC-1 | Validation du schéma par Flyway (ddl-auto=validate) | INT | it/SchemaAndContextStartupIT.java#fullContextStartsWithFlywayMigrationAndSchemaValidation ; #productionProfileKeepsDdlAutoValidate | ACTIF | PASS 2026-09-26 | Vérification explicite avec un contexte Spring complet (web + sécurité + JPA ensemble), corrigé lors de la revue de cohérence (le rattachement précédent à la classe entière `RacePersistenceTest.java`, sans nom de méthode, ne correspondait à aucune assertion dédiée). Couverture implicite complémentaire : `persistence/RacePersistenceTest.java` (`@DataJpaTest`) — si Flyway/Hibernate ne validaient pas le schéma, le contexte de cette classe ne démarrerait pour aucune de ses 22 méthodes, mais aucune de ces méthodes n'assert spécifiquement la validation du schéma. Limite : pas de vrai PostgreSQL disponible (H2 MODE=PostgreSQL uniquement), cf. définition de l'agent |
| INC1-CA21 | INC-1 | Champ name vide rejeté pour Race | INT | persistence/RacePersistenceTest.java#ca21_nullRaceNameThrows | ACTIF | PASS 2026-09-26 | |
| INC1-CA22 | INC-1 | loop_elevation à zéro autorisé | INT | persistence/RacePersistenceTest.java#ca22_loopElevationZeroAllowed | ACTIF | PASS 2026-09-26 | |
| INC1-TECH1 (hors CA) | INC-1 | Test technique hors CA numéroté : exercice des setters de `Race` et `Runner` (couverture JaCoCo) | INT | persistence/RacePersistenceTest.java#tech_raceAndRunnerSettersUpdateFields | ACTIF | PASS 2026-09-26 | Ajouté lors de la revue de cohérence (test réel présent en code, non référencé jusqu'ici). Accepté au patrimoine par l'agent fonctionnel (2026-09-26). **Écart R1-1 levé (validation INC-4, 2026-09-26, test-integration-backend)** : le renommage en `tech_raceAndRunnerSettersUpdateFields` est constaté dans le code (`RacePersistenceTest.java` ligne 333), sans modification d'assertion — vérifié par lecture directe du fichier et confirmé par l'exécution (`PASS`). Le nom `ca23_...` désignait un vestige historique (il n'existe désormais un vrai `INC1-CA23`, voir addendum ci-dessous, de contenu différent) |
| INC1-CL8 | INC-1 | Suppression d'une Race avec coureurs bloquée (RESTRICT) — aucun CA numéroté ne la couvre | INT | it/SchemaAndContextStartupIT.java#deletingRaceWithRunnersIsBlockedByForeignKeyRestrict | ACTIF | PASS 2026-09-26 | Nouveau (mode rattrapage). Écart comblé : cas limite non couvert par un CA de la spec |
| INC1-CL9 | INC-1 | Suppression d'un Runner avec passages bloquée (RESTRICT) — aucun CA numéroté ne la couvre | INT | it/SchemaAndContextStartupIT.java#deletingRunnerWithPassagesIsBlockedByForeignKeyRestrict | ACTIF | PASS 2026-09-26 | Nouveau (mode rattrapage). Écart comblé : cas limite non couvert par un CA de la spec |

**Écart R1-2 levé (validation INC-4, 2026-09-26, test-integration-backend).** Constaté par lecture du code
(`persistence/RacePersistenceTest.java`) et par l'exécution (22/22 `PASS`) :
- les méthodes concernées (`ca1_persistMinimalRace`, `ca3_startedAtNullOnCreation`, `ca5_persistMinimalRunner`,
  `ca10_dnfFieldsNullableForActiveRunner`, `ca11_persistPassageWithScannedAt`, `ca12_manualPassageWithNullScannedAt`,
  `ca22_loopElevationZeroAllowed`, ainsi que `ca9_duplicateQrTokenThrows` via la méthode utilitaire dédiée) appellent
  désormais `flushAndClearPersistenceContext()` (`entityManager.flush()` puis `.clear()`) avant chaque relecture, et
  chaque assertion vérifie explicitement `assertThat(found).isNotSameAs(saved)` (ou `runner`/`race`), preuve que
  l'objet relu vient bien du cache de second niveau / de la base et non du cache de premier niveau ;
- `ca21_nullRaceNameThrows` restreint désormais l'exception attendue à `ConstraintViolationException` avec
  `getConstraintViolations()` portant sur la propriété `name` (`containsExactly("name")`), au lieu de n'importe
  quelle `Exception`.

### Addendum INC-1 (2026-09-26) — réserve R1-3 : nouveaux CA23 à CA31

Origine : `docs/specs/increment1.md`, section « Addendum (2026-09-26) » (réserve **R1-3**). Ajoute des CA
numérotés pour RG11, RG15, CL12 et les contraintes `CHECK` de la migration V1 (RG1, RG6, RG13), jusque-là sans
critère d'acceptation. **Aucun test n'existe encore pour CA23 à CA31** au moment de la validation de l'INC-4
(vérifié par recherche exhaustive : aucune méthode `ca23_` à `ca31_` dans `backend/src/test/java` ne correspond
au contenu de l'addendum — les seules occurrences `ca23_`…`ca31_` trouvées appartiennent aux incréments 2 et 3,
sans rapport). La réserve R1-3 n'est donc que **partiellement levée** : l'addendum de spec est écrit (fait), mais
« puis tests (testeur) » reste à faire. Conformément au mandat de cet agent (« constate, ne modifie pas les
tests, n'écrit pas les tests manquants d'un incrément antérieur hors de son périmètre d'intégration »), ces CA
sont consignés ici comme écart ouvert plutôt que comblés par cet agent.

**Mise à jour du 2026-09-26 (testeur)** : les tests de CA23 à CA29 sont écrits à partir de l'addendum de spec
(sans modification du code de production ni des méthodes existantes de `RacePersistenceTest`) et passent dans
`mvn -B -f backend/pom.xml clean verify` (BUILD SUCCESS, surefire 297/297, failsafe 41/41). Trois nouvelles
classes : `domain/RunnerDnfConsistencyTest` (JUnit pur), `domain/DomainIntegrityArchitectureTest` (réflexion +
analyse du bytecode de `target/classes` avec l'ASM de Spring, sans ArchUnit), `persistence/Increment1AddendumPersistenceTest`
(`@DataJpaTest`, H2 mode PostgreSQL ; SQL natif par `JdbcTemplate`). Aucun bug de production révélé. RT1 s'applique.

| Exigence | Incrément | Description | Type attendu (spec) | Test (chemin / nom) | Statut | Dernier résultat | Notes |
|---|---|---|---|---|---|---|---|
| INC1-CA23 | INC-1 | Cohérence DNF portée par le domaine (`Runner.markDnf`, `Runner.markWinner`) (RG10, RG11) | UNIT + INT | domain/RunnerDnfConsistencyTest.java#ca23_markDnfWithoutReasonIsRejectedAndRunnerUnchanged ; #ca23_markDnfWithYardZeroIsRejectedAndRunnerUnchanged ; #ca23_markDnfSetsStatusReasonAndYardTogether ; #ca23_markWinnerLeavesDnfFieldsNull ; persistence/Increment1AddendumPersistenceTest.java#ca23_dnfRunnerIsPersistedWithReasonAndYard ; #ca23_winnerIsPersistedWithoutDnfFields | ACTIF | PASS 2026-09-26 | Relecture après `flush()` + `clear()` (R1-2) |
| INC1-CA24 | INC-1 | Aucun contournement de la cohérence DNF dans le code de production (RG11) | test d'architecture | domain/DomainIntegrityArchitectureTest.java#ca24_noProductionCallToRunnerDnfSettersOutsideRunner ; #ca24_bytecodeAnalysisDetectsRunnerCalls | ACTIF | PASS 2026-09-26 | Analyse du bytecode (type exact du receveur : distingue `Runner.setStatus` de `Race.setStatus`). Le second test garantit que l'analyse voit bien les appels à `Runner.markDnf` (pas de vert par construction) |
| INC1-CA25 | INC-1 | Immuabilité des passages (RG15) | UNIT + test d'architecture | domain/DomainIntegrityArchitectureTest.java#ca25_passageExposesOnlyAccessors ; #ca25_passageFieldsAreOnlyAssignedByConstructor ; #ca25_passageRepositoryDeclaresNoModifyingQuery ; #ca25_noProductionCallToPassageRepositoryDeletion ; persistence/Increment1AddendumPersistenceTest.java#ca25_savingAPersistedPassageAgainChangesNothing | ACTIF | PASS 2026-09-26 | Affectations des champs contrôlées par `PUTFIELD` : uniquement dans `Passage.<init>` |
| INC1-CA26 | INC-1 | Nom obligatoire et non vide pour Runner et Race (CL12, RG6, RG1) | INT | persistence/Increment1AddendumPersistenceTest.java#ca26_runnerWithNullNameIsRejected ; #ca26_runnerWithEmptyNameIsRejectedByDomainValidation ; #ca26_runnerWithBlankNameIsRejectedByDomainValidation ; #ca26_raceWithBlankNameIsRejectedByDomainValidation | ACTIF | PASS 2026-09-26 | Comptage « aucune ligne créée » fait en JDBC : `repository.count()` déclenche l'auto-flush Hibernate de l'entité rejetée (`AssertionFailure ... null identifier`), artefact de test et non bug |
| INC1-CA27 | INC-1 | Contraintes CHECK des paramètres de boucle (RG1, CL10) | INT (SQL natif + JPA) | persistence/Increment1AddendumPersistenceTest.java#ca27_nativeInsertWithZeroLoopDistanceIsRejected ; #ca27_nativeInsertWithZeroLoopDurationIsRejected ; #ca27_nativeInsertWithNegativeLoopElevationIsRejected ; #ca27_nativeInsertWithBoundaryLoopValuesIsAccepted ; #ca27_jpaRaceWithZeroLoopDistanceIsRejected | ACTIF | PASS 2026-09-26 | Par le chemin JPA, `loop_distance = 0` n'est rejeté que par la base (pas de `@Positive` sur `Race.loopDistance`) : conforme à la spec, qui accepte l'une ou l'autre exception |
| INC1-CA28 | INC-1 | Contrainte CHECK du dossard (RG6) | INT (SQL natif + JPA) | persistence/Increment1AddendumPersistenceTest.java#ca28_nativeInsertWithNonPositiveBibIsRejected (bib 0 et -1, paramétré) ; #ca28_nativeInsertWithBibOneIsAccepted ; #ca28_jpaRunnerWithBibZeroIsRejected | ACTIF | PASS 2026-09-26 | Aucun test préexistant (vérifié : les `ca28_` existants sont ceux de l'INC-2/INC-3) |
| INC1-CA29 | INC-1 | Contrainte CHECK du numéro de yard (RG13) | INT (SQL natif + JPA) | persistence/Increment1AddendumPersistenceTest.java#ca29_nativeInsertWithYardNumberBelowOneIsRejected (yard 0 et -1, paramétré) ; #ca29_nativeInsertWithYardNumberOneIsAccepted ; #ca29_jpaPassageWithYardZeroIsRejected | ACTIF | PASS 2026-09-26 | Aucun test préexistant (vérifié : les `ca29_` existants sont ceux de l'INC-2/INC-3) |
| INC1-CA30 | INC-1 | Suppression d'une Race avec coureurs bloquée (CL8, RG5) | INT | it/SchemaAndContextStartupIT.java#deletingRaceWithRunnersIsBlockedByForeignKeyRestrict | ACTIF | PASS 2026-09-26 | La spec signale elle-même que ce CA formalise un test déjà existant (INC1-CL8 ci-dessus) : rattachement fait, aucun nouveau test nécessaire |
| INC1-CA31 | INC-1 | Suppression d'un Runner avec passages bloquée (CL9, RG12) | INT | it/SchemaAndContextStartupIT.java#deletingRunnerWithPassagesIsBlockedByForeignKeyRestrict | ACTIF | PASS 2026-09-26 | Idem, formalise INC1-CL9 ci-dessus |

### Incrément 2 — Logique métier core

| Exigence | Incrément | Description | Type | Test (chemin / nom) | Statut | Dernier résultat | Notes |
|---|---|---|---|---|---|---|---|
| INC2-CA1 | INC-2 | Premier instant de la course | UNIT | domain/YardCalculatorTest.java#ca1_firstInstantIsYardOne | ACTIF | PASS 2026-09-26 | |
| INC2-CA2 | INC-2 | Bascule exacte | UNIT | domain/YardCalculatorTest.java#ca2_exactYardBoundary ; #ca2_nanosecondsAreTruncated | ACTIF | PASS 2026-09-26 | |
| INC2-CA3 | INC-2 | Yard en milieu de course | UNIT | domain/YardCalculatorTest.java#ca3_yardInTheMiddleOfTheRace | ACTIF | PASS 2026-09-26 | |
| INC2-CA4 | INC-2 | Avant le départ et course sans started_at | UNIT | domain/YardCalculatorTest.java#ca4_beforeStartIsYardZero ; #ca4_raceWithoutStartedAtIsYardZero | ACTIF | PASS 2026-09-26 | |
| INC2-CA5 | INC-2 | Bornes d'un yard | UNIT | domain/YardCalculatorTest.java#ca5_yardBounds ; #ca5_yardOneStartsAtStartedAt ; #ca5_yardStartBelowOneIsRejected | ACTIF | PASS 2026-09-26 | |
| INC2-CA6 | INC-2 | Yard courant selon le statut | UNIT | domain/YardCalculatorTest.java#ca6_currentYardOfRunningRace ; #ca6_currentYardOfFinishedRaceIsZero ; #ca6_currentYardOfSetupRaceIsZero | ACTIF | PASS 2026-09-26 | |
| INC2-CA7 | INC-2 | Course RUNNING sans started_at | UNIT | domain/YardCalculatorTest.java#ca7_runningRaceWithoutStartedAtIsInconsistent | ACTIF | PASS 2026-09-26 | |
| INC2-CA8 | INC-2 | Deux courses, même instant, yards différents | UNIT | domain/YardCalculatorTest.java#ca8_twoRacesHaveIndependentYards | ACTIF | PASS 2026-09-26 | |
| INC2-CA9 | INC-2 | Coureur sans passage | UNIT | domain/RunnerStatsCalculatorTest.java#ca9_noPassage | ACTIF | PASS 2026-09-26 | |
| INC2-CA10 | INC-2 | Trois scans | UNIT | domain/RunnerStatsCalculatorTest.java#ca10_loopTimes ; #ca10_threeScans | ACTIF | PASS 2026-09-26 | |
| INC2-CA11 | INC-2 | Arrondi HALF_UP de l'allure | UNIT | domain/RunnerStatsCalculatorTest.java#ca11_paceRoundsHalfUp ; #ca11_paceRoundsDownBelowHalf | ACTIF | PASS 2026-09-26 | |
| INC2-CA12 | INC-2 | Scans et passages manuels mélangés | UNIT | domain/RunnerStatsCalculatorTest.java#ca12_mixedScanAndManual | ACTIF | PASS 2026-09-26 | |
| INC2-CA13 | INC-2 | Uniquement des passages manuels | UNIT | domain/RunnerStatsCalculatorTest.java#ca13_onlyManualPassages | ACTIF | PASS 2026-09-26 | |
| INC2-CA14 | INC-2 | Passage MANUAL avec scanned_at renseigné exclu de l'allure | UNIT | domain/RunnerStatsCalculatorTest.java#ca14_manualWithScannedAtExcludedFromPace | ACTIF | PASS 2026-09-26 | |
| INC2-CA15 | INC-2 | Course plate | UNIT | domain/RunnerStatsCalculatorTest.java#ca15_flatCourse | ACTIF | PASS 2026-09-26 | |
| INC2-CA16 | INC-2 | Indépendance du statut | UNIT | domain/RunnerStatsCalculatorTest.java#ca16_statsDoNotDependOnRunnerStatus ; #ca16_statsDoNotDependOnRaceStatus | ACTIF | PASS 2026-09-26 | |
| INC2-CA17 | INC-2 | Scan nominal | UNIT | service/PassageRecordingServiceTest.java#ca17_nominalScan | ACTIF | PASS 2026-09-26 | Renforcé au niveau HTTP par it/EndToEndRaceLifecycleIT |
| INC2-CA18 | INC-2 | Bascule sur un scan | UNIT | service/PassageRecordingServiceTest.java#ca18_scanJustBeforeBellIsYardOne ; #ca18_scanOnBellIsNextYard | ACTIF | PASS 2026-09-26 | |
| INC2-CA19 | INC-2 | Scan reçu en retard, effectué dans la fenêtre, coureur encore ACTIVE | UNIT | service/PassageRecordingServiceTest.java#ca19_lateReceivedScanOfActiveRunner | ACTIF | PASS 2026-09-26 | |
| INC2-CA20 | INC-2 | Scan tardif rejeté | UNIT | service/PassageRecordingServiceTest.java#ca20_lateScanWithoutPreviousYardIsRejected | ACTIF | PASS 2026-09-26 | |
| INC2-CA21 | INC-2 | Coureur non actif hors conditions de réactivation | UNIT | service/PassageRecordingServiceTest.java#ca21_voluntaryDnfRunnerIsRejected ; #ca21_winnerRunnerIsRejected | ACTIF | PASS 2026-09-26 | |
| INC2-CA22 | INC-2 | Course non RUNNING | UNIT | service/PassageRecordingServiceTest.java#ca22_setupRaceIsRejected ; #ca22_finishedRaceIsRejected | ACTIF | PASS 2026-09-26 | |
| INC2-CA23 | INC-2 | QR inconnu | UNIT | service/PassageRecordingServiceTest.java#ca23_unknownQrToken | ACTIF | PASS 2026-09-26 | |
| INC2-CA24 | INC-2 | Entrées invalides | UNIT | service/PassageRecordingServiceTest.java#ca24_nullScannedAt ; #ca24_scanInTheFuture ; #ca24_scanAtNowIsAccepted ; #ca24_scanBeforeStart | ACTIF | PASS 2026-09-26 | |
| INC2-CA25 | INC-2 | Doublon idempotent et double scan | UNIT | service/PassageRecordingServiceTest.java#ca25_identicalDuplicateIsIdempotent ; #ca25_differentScanOnSameYardIsRejected | ACTIF | PASS 2026-09-26 | |
| INC2-CA26 | INC-2 | Rien à clôturer pendant le yard 1 | UNIT | service/YardClosingServiceTest.java#ca26_nothingToCloseDuringYardOne ; #ca26_nothingToCloseBeforeStart | ACTIF | PASS 2026-09-26 | |
| INC2-CA27 | INC-2 | Auto-DNF à la bascule exacte | UNIT | service/YardClosingServiceTest.java#ca27_autoDnfAtExactBell | ACTIF | PASS 2026-09-26 | Rejoué en persistance réelle par it/EndToEndRaceLifecycleIT (INC2-CA27) |
| INC2-CA27 (persistance) | INC-2 | Auto-DNF à la bascule exacte, service réel + JPA réelle | INT | it/EndToEndRaceLifecycleIT.java#raceLifecycleFromCreationToDashboardAfterAutoDnfAndReintegration | ACTIF | PASS 2026-09-26 | Nouveau (mode rattrapage) : YardClosingService jamais exécuté contre une vraie base dans les tests existants (repositories mockés) |
| INC2-CA28 | INC-2 | Seul le yard N est contrôlé ; passages MANUAL valides | UNIT | service/YardClosingServiceTest.java#ca28_onlyLastClosedYardIsCheckedAndManualPassagesAreValid ; #ca28_earlierMissingYardIsNotChecked | ACTIF | PASS 2026-09-26 | |
| INC2-CA29 | INC-2 | Coureurs DNF et WINNER non modifiés | UNIT | service/YardClosingServiceTest.java#ca29_dnfRunnersAreNotTouched ; #ca29_winnerRunnersAreNotTouched | ACTIF | PASS 2026-09-26 | |
| INC2-CA30 | INC-2 | Idempotence | UNIT | service/YardClosingServiceTest.java#ca30_closingTwiceIsIdempotent | ACTIF | PASS 2026-09-26 | |
| INC2-CA31 | INC-2 | Plusieurs courses en parallèle | UNIT | service/YardClosingServiceTest.java#ca31_parallelRacesAreClosedIndependently | ACTIF | PASS 2026-09-26 | |
| INC2-CA32 | INC-2 | Isolation des erreurs entre courses | UNIT | service/YardClosingServiceTest.java#ca32_failureOnOneRaceDoesNotBlockOthers | ACTIF | PASS 2026-09-26 | |
| INC2-CA33 | INC-2 | Vainqueur | UNIT | service/YardClosingServiceTest.java#ca33_singleActiveFinisherWins | ACTIF | PASS 2026-09-26 | |
| INC2-CA34 | INC-2 | Au moins deux finishers : la course continue | UNIT | service/YardClosingServiceTest.java#ca34_twoFinishersRaceGoesOn | ACTIF | PASS 2026-09-26 | |
| INC2-CA35 | INC-2 | Aucun finisher : fin sans vainqueur | UNIT | service/YardClosingServiceTest.java#ca35_noFinisherRaceEndsWithoutWinner | ACTIF | PASS 2026-09-26 | |
| INC2-CA36 | INC-2 | Le dernier actif doit courir seul un yard de plus | UNIT | service/YardClosingServiceTest.java#ca36_lastActiveRunnerMustCompleteOneMoreYardAlone | ACTIF | PASS 2026-09-26 | |
| INC2-CA37 | INC-2 | Unique finisher non actif : fin sans vainqueur | UNIT | service/YardClosingServiceTest.java#ca37_singleNonActiveFinisherEndsRaceWithoutWinner | ACTIF | PASS 2026-09-26 | |
| INC2-CA38 | INC-2 | Course terminée non retraitée | UNIT | service/YardClosingServiceTest.java#ca38_finishedRaceIsNotProcessedAgain | ACTIF | PASS 2026-09-26 | |
| INC2-CA39 | INC-2 | Course à un seul coureur | UNIT | service/YardClosingServiceTest.java#ca39_singleRunnerRaceWinsAtFirstClosing | ACTIF | PASS 2026-09-26 | |
| INC2-CA40 | INC-2 | DNF manuel en cours de boucle | UNIT | service/ManualDnfServiceTest.java#ca40_manualDnfDuringLoop | ACTIF | PASS 2026-09-26 | |
| INC2-CA41 | INC-2 | DNF manuel après avoir terminé le yard courant | UNIT | service/ManualDnfServiceTest.java#ca41_manualDnfAfterFinishingCurrentYard | ACTIF | PASS 2026-09-26 | |
| INC2-CA42 | INC-2 | DNF manuel sans passage | UNIT | service/ManualDnfServiceTest.java#ca42_manualDnfWithoutPassage | ACTIF | PASS 2026-09-26 | |
| INC2-CA43 | INC-2 | Refus du DNF manuel | UNIT | service/ManualDnfServiceTest.java#ca43_timeoutReasonIsRejected ; #ca43_nullReasonIsRejected ; #ca43_unknownRunner ; #ca43_alreadyDnfRunner ; #ca43_winnerRunner ; #ca43_setupRace ; #ca43_finishedRace | ACTIF | PASS 2026-09-26 | |
| INC2-CA44 | INC-2 | Réintégration nominale | UNIT | service/ReintegrationServiceTest.java#ca44_nominalReintegration | ACTIF | PASS 2026-09-26 | Rejouée en persistance réelle (HTTP admin, describePassages) par it/RaceBoardTransactionBoundaryIT et it/EndToEndRaceLifecycleIT |
| INC2-CA44 (persistance) | INC-2 | Réintégration nominale, orchestration réelle (service + JPA + HTTP) | INT | it/EndToEndRaceLifecycleIT.java#raceLifecycleFromCreationToDashboardAfterAutoDnfAndReintegration ; it/RaceBoardTransactionBoundaryIT.java#reintegrationDescribePassagesLoadsLazyRaceAssociation | ACTIF | PASS 2026-09-26 | Nouveau (mode rattrapage) |
| INC2-CA45 | INC-2 | Réintégration dans le yard qui suit le DNF | UNIT | service/ReintegrationServiceTest.java#ca45_reintegrationRightAfterDnf | ACTIF | PASS 2026-09-26 | |
| INC2-CA46 | INC-2 | Intervalle vide | UNIT | service/ReintegrationServiceTest.java#ca46_emptyInterval | ACTIF | PASS 2026-09-26 | |
| INC2-CA47 | INC-2 | Passage déjà présent non recréé | UNIT | service/ReintegrationServiceTest.java#ca47_existingPassageIsNotRecreated | ACTIF | PASS 2026-09-26 | |
| INC2-CA48 | INC-2 | Pas de re-DNF sur les yards recréés | UNIT | service/ReintegrationServiceTest.java#ca48_noReDnfOnRecreatedYards | ACTIF | PASS 2026-09-26 | |
| INC2-CA49 | INC-2 | Scan après réintégration | UNIT | service/ReintegrationServiceTest.java#ca49_scanAfterReintegration | ACTIF | PASS 2026-09-26 | |
| INC2-CA50 | INC-2 | Statistiques après réintégration | UNIT | domain/RunnerStatsCalculatorTest.java#ca50_statsAfterReintegration | ACTIF | PASS 2026-09-26 | |
| INC2-CA51 | INC-2 | Refus de réintégration | UNIT | service/ReintegrationServiceTest.java#ca51_activeRunnerIsRejected ; #ca51_winnerRunnerIsRejected ; #ca51_setupRaceIsRejected ; #ca51_finishedRaceIsRejected ; #ca51_unknownRunner | ACTIF | PASS 2026-09-26 | |
| INC2-CA52 | INC-2 | Réactivation nominale | UNIT | service/AutomaticReactivationTest.java#ca52_nominalReactivation | ACTIF | PASS 2026-09-26 | |
| INC2-CA53 | INC-2 | Pas de réactivation d'un DNF non-timeout | UNIT | service/AutomaticReactivationTest.java#ca53_noReactivationOfNonTimeoutDnf | ACTIF | PASS 2026-09-26 | |
| INC2-CA54 | INC-2 | Pas de réactivation si le scan ne porte pas sur dnfYard | UNIT | service/AutomaticReactivationTest.java#ca54_noReactivationWhenScanIsNotOnDnfYard | ACTIF | PASS 2026-09-26 | |
| INC2-CA55 | INC-2 | Scan reçu après la fin de course | UNIT | service/AutomaticReactivationTest.java#ca55_scanAfterRaceFinishedIsRejected | ACTIF | PASS 2026-09-26 | |
| INC2-CA56 | INC-2 | Yard N+1 déjà clos : pas de réactivation | UNIT | service/AutomaticReactivationTest.java#ca56_noReactivationWhenNextYardIsAlsoClosed | ACTIF | PASS 2026-09-26 | |
| INC2-CA57 | INC-2 | Idempotence et double scan après réactivation | UNIT | service/AutomaticReactivationTest.java#ca57_identicalRetryAfterReactivationIsIdempotent ; #ca57_differentScanAfterReactivationIsRejected | ACTIF | PASS 2026-09-26 | |
| INC2-CA58 | INC-2 | Clôtures après réactivation | UNIT | service/AutomaticReactivationTest.java#ca58_closingsAfterReactivation | ACTIF | PASS 2026-09-26 | |
| INC2-CA59 | INC-2 | Statistiques après réactivation | UNIT | domain/RunnerStatsCalculatorTest.java#ca59_statsAfterAutomaticReactivation | ACTIF | PASS 2026-09-26 | |
| INC2-CA60 | INC-2 | Règle k-1 en mode réactivation | UNIT | service/AutomaticReactivationTest.java#ca60_previousYardRuleAppliesInReactivationMode | ACTIF | PASS 2026-09-26 | |
| INC2-CA61 | INC-2 | Transition unique DNF vers ACTIVE | UNIT | domain/StateTransitionsTest.java#ca61_reactivateDnfRunner ; #ca61_reactivateActiveRunnerIsRejected ; #ca61_reactivateWinnerIsRejected | ACTIF | PASS 2026-09-26 | |
| INC2-CA40 (RG29, domaine) | INC-2 | DNF manuel : vérification complémentaire, au niveau domaine pur, de la transition `Runner.markDnf` utilisée par le DNF manuel (CA40) | UNIT | domain/StateTransitionsTest.java#rg29_markDnf | ACTIF | PASS 2026-09-26 | Ajouté lors de la revue de cohérence (test réel présent en code, non référencé jusqu'ici) ; complète service/ManualDnfServiceTest.java (CA40), pas un doublon (teste `Runner.markDnf` indépendamment du service) |
| INC2-CA33 (RG29, domaine) | INC-2 | Vainqueur : vérification complémentaire, au niveau domaine pur, de la transition `Runner.markWinner` utilisée à la désignation du vainqueur (CA33) | UNIT | domain/StateTransitionsTest.java#rg29_markWinner | ACTIF | PASS 2026-09-26 | Ajouté lors de la revue de cohérence ; complète service/YardClosingServiceTest.java (CA33), pas un doublon |
| INC2-CA33 (RG29, domaine) | INC-2 | Vainqueur : vérification complémentaire, au niveau domaine pur, de la transition `Race.finish` utilisée à la fin de course (CA33) | UNIT | domain/StateTransitionsTest.java#rg29_raceFinish | ACTIF | PASS 2026-09-26 | Ajouté lors de la revue de cohérence ; complète service/YardClosingServiceTest.java (CA33), pas un doublon |
| INC2-CA62 | INC-2 | Coureur réactivé pris en compte à la clôture suivante | UNIT | service/AutomaticReactivationTest.java#ca62_reactivatedRunnerCountsAtNextClosing | ACTIF | PASS 2026-09-26 | |

**Écart R2-1 levé (validation INC-4, 2026-09-26, test-integration-backend).** Constaté par lecture du code et
par l'exécution (17/17 `PASS` sur `YardClosingServiceTest`, 11/11 `PASS` sur `ReintegrationServiceTest`) :
- `service/YardClosingServiceTest.java#ca31_parallelRacesAreClosedIndependently` vérifie désormais explicitement
  `r1.getStatus() == FINISHED` (« sans vainqueur ») avec le commentaire dédié, et le contenu complet des deux
  `YardClosingResult` (`closedYard`, `timedOutRunnerIds` exact, `winnerRunnerId` vide, `raceFinished`) pour R1 et R2 ;
- `#ca32_failureOnOneRaceDoesNotBlockOthers` vérifie `r2.getStatus() == RUNNING` (« deux finishers ») ;
- `service/ReintegrationServiceTest.java#ca48_noReDnfOnRecreatedYards` vérifie `atYard5.timedOutRunnerIds()` vide,
  `assertStillActive(a, c)` et `r1.getStatus() == RUNNING` à la première clôture après réintégration.

### Incrément 3 — API REST

| Exigence | Incrément | Description | Type | Test (chemin / nom) | Statut | Dernier résultat | Notes |
|---|---|---|---|---|---|---|---|
| INC3-CA1 | INC-3 | 404 ProblemDetail [slice] | UNIT | api/ApiErrorsSliceTest.java#ca1_notFoundProblemDetail | ACTIF | PASS 2026-09-26 | |
| INC3-CA2 | INC-3 | 409 ProblemDetail [slice] | UNIT | api/ApiErrorsSliceTest.java#ca2_conflictProblemDetail | ACTIF | PASS 2026-09-26 | |
| INC3-CA3 | INC-3 | 400 InvalidInput [slice] | UNIT | api/ApiErrorsSliceTest.java#ca3_invalidInputProblemDetail | ACTIF | PASS 2026-09-26 | |
| INC3-CA4 | INC-3 | Validation Bean [slice] | UNIT | api/ApiErrorsSliceTest.java#ca4_beanValidation | ACTIF | PASS 2026-09-26 | |
| INC3-CA5 | INC-3 | Requêtes mal formées [slice] | UNIT | api/ApiErrorsSliceTest.java#ca5_nonNumericId ; #ca5_malformedJson ; #ca5_unknownEnum ; #ca5_missingBody | ACTIF | PASS 2026-09-26 | |
| INC3-CA6 | INC-3 | Exceptions du domaine [slice] | UNIT | api/ApiErrorsSliceTest.java#ca6_illegalStateIsInternalInconsistency ; #ca6_illegalArgumentIsInternalInconsistency | ACTIF | PASS 2026-09-26 | |
| INC3-CA7 | INC-3 | Exception inattendue [slice] | UNIT | api/ApiErrorsSliceTest.java#ca7_unexpectedException | ACTIF | PASS 2026-09-26 | |
| INC3-CA8 | INC-3 | Violation d'intégrité [slice] | UNIT | api/ApiErrorsSliceTest.java#ca8_dataIntegrityViolation | ACTIF | PASS 2026-09-26 | Service mocké, exception construite à la main |
| INC3-CA8 (réel) | INC-3 | Violation d'intégrité, vraie DataIntegrityViolationException traduite par JPA | INT | it/QrTokenUniquenessIT.java#secondRegistrationWithColldingQrTokenIsRejectedAsDataIntegrityViolation | ACTIF | PASS 2026-09-26 | Nouveau (mode rattrapage) : comble le service mocké de CA8 |
| INC3-CA9 | INC-3 | Méthode non supportée et chemin inconnu [slice] | UNIT | api/ApiErrorsSliceTest.java#ca9_methodNotAllowed ; #ca9_unknownPath | ACTIF | PASS 2026-09-26 | |
| INC3-CA10 | INC-3 | Création (Race) [slice] | UNIT | api/RaceApiSliceTest.java#ca10_createRace | ACTIF | PASS 2026-09-26 | |
| INC3-CA11 | INC-3 | Création : règles [unit] | UNIT | service/RaceServiceTest.java#ca11_createRace ; #ca11_duplicateNameIsRejected | ACTIF | PASS 2026-09-26 | |
| INC3-CA11 (réel) | INC-3 | existsByName contre un vrai schéma | INT | it/RepositoryDerivedQueriesIT.java#existsByNameDetectsExistingRaceName | ACTIF | PASS 2026-09-26 | Nouveau (mode rattrapage) |
| INC3-CA12 | INC-3 | Liste [unit + slice] | UNIT | service/RaceServiceTest.java#ca12_listIsSortedByDateThenId ; api/RaceApiSliceTest.java#ca12_listRaces | ACTIF | PASS 2026-09-26 | |
| INC3-CA13 | INC-3 | Détail [unit + slice] | UNIT | service/RaceServiceTest.java#ca13_getUnknownRace ; #ca13_getExistingRace ; api/RaceApiSliceTest.java#ca13_publicRaceDetail | ACTIF | PASS 2026-09-26 | |
| INC3-CA14 | INC-3 | Modification en SETUP [unit + slice] | UNIT | service/RaceServiceTest.java#ca14_updateSetupRace ; #ca14_updateUnknownRace ; api/RaceApiSliceTest.java#ca14_updateRace | ACTIF | PASS 2026-09-26 | |
| INC3-CA15 | INC-3 | Paramètres de boucle figés hors SETUP [unit] | UNIT | service/RaceServiceTest.java#ca15_loopParametersAreFrozenOutsideSetup ; #ca15_nameAndDateRemainEditable | ACTIF | PASS 2026-09-26 | |
| INC3-CA16 | INC-3 | Unicité du nom à la modification [unit] | UNIT | service/RaceServiceTest.java#ca16_nameTakenByAnotherRace ; #ca16_ownNameIsAccepted | ACTIF | PASS 2026-09-26 | |
| INC3-CA16 (réel) | INC-3 | existsByNameAndIdNot contre un vrai schéma | INT | it/RepositoryDerivedQueriesIT.java#existsByNameAndIdNotExcludesOwnRace | ACTIF | PASS 2026-09-26 | Nouveau (mode rattrapage) |
| INC3-CA17 | INC-3 | Suppression SETUP [unit + slice] | UNIT | service/RaceServiceTest.java#ca17_deleteSetupRaceWithRunners ; api/RaceApiSliceTest.java#ca17_deleteRace | ACTIF | PASS 2026-09-26 | |
| INC3-CA18 | INC-3 | Suppression refusée [unit] | UNIT | service/RaceServiceTest.java#ca18_deleteNonSetupRaceIsRejected ; #ca18_deleteUnknownRace | ACTIF | PASS 2026-09-26 | |
| INC3-CA19 | INC-3 | Démarrage [unit + slice] | UNIT | service/RaceServiceTest.java#ca19_startSetupRace ; api/RaceApiSliceTest.java#ca19_startRace | ACTIF | PASS 2026-09-26 | |
| INC3-CA19 (réel) | INC-3 | Démarrage, chaîne HTTP -> sécurité -> service -> JPA réels | INT | it/EndToEndRaceLifecycleIT.java#raceLifecycleFromCreationToDashboardAfterAutoDnfAndReintegration | ACTIF | PASS 2026-09-26 | Nouveau (mode rattrapage) |
| INC3-CA20 | INC-3 | Démarrage refusé [unit] | UNIT | service/RaceServiceTest.java#ca20_startNonSetupRaceIsRejected ; #ca20_startUnknownRace | ACTIF | PASS 2026-09-26 | |
| INC3-CA21 | INC-3 | Transition de domaine Race.start [unit] | UNIT | domain/RaceStartAndRegistrationTest.java#ca21_startSetupRace ; #ca21_startNonSetupRaceIsRejected | ACTIF | PASS 2026-09-26 | |
| INC3-CA22 | INC-3 | Inscriptions ouvertes [unit] | UNIT | domain/RaceStartAndRegistrationTest.java#ca22_registrationOpenForSetup ; #ca22_registrationClosedOtherwise | ACTIF | PASS 2026-09-26 | |
| INC3-CA23 | INC-3 | Inscription nominale [unit] | UNIT | service/RunnerServiceTest.java#ca23_registrationTakesMaxBibPlusOne ; #ca23_firstRegistrationGetsBibOne ; #ca23_bibIsComputedPerRace | ACTIF | PASS 2026-09-26 | |
| INC3-CA24 | INC-3 | Inscription [slice] | UNIT | api/RunnerApiSliceTest.java#ca24_publicRegistration | ACTIF | PASS 2026-09-26 | Rejouée en persistance réelle par it/EndToEndRaceLifecycleIT et it/QrTokenUniquenessIT |
| INC3-CA25 | INC-3 | Inscription refusée [unit + slice] | UNIT | service/RunnerServiceTest.java#ca25_registrationClosed ; #ca25_registrationOnUnknownRace ; api/RunnerApiSliceTest.java#ca25_blankNameIsRejected | ACTIF | PASS 2026-09-26 | |
| INC3-CA26 | INC-3 | Générateur de qr_token [unit] | UNIT | service/QrTokenGeneratorTest.java#ca26_tokensAreDistinctCanonicalUuidV4 | ACTIF | PASS 2026-09-26 | |
| INC3-CA27 | INC-3 | qr_token absent des réponses publiques et du scan [slice] | UNIT | api/ReadModelApiSliceTest.java#ca27_qrTokenNeverExposedPublicly | ACTIF | PASS 2026-09-26 | |
| INC3-CA28 | INC-3 | Liste et détail admin [unit + slice] | UNIT | service/RunnerServiceTest.java#ca28_listByRaceSortedByBib ; #ca28_listByUnknownRace ; #ca28_getRunner ; api/RunnerApiSliceTest.java#ca28_adminRunnerList ; #ca28_adminRunnerDetail | ACTIF | PASS 2026-09-26 | |
| INC3-CA29 | INC-3 | Modification d'un coureur [unit] | UNIT | service/RunnerServiceTest.java#ca29_updateBibAndNameInSetup ; #ca29_bibAlreadyTaken ; #ca29_nameEditableWhileRunning ; #ca29_bibFrozenWhileRunning ; #ca29_updateUnknownRunner | ACTIF | PASS 2026-09-26 | |
| INC3-CA29 (réel) | INC-3 | existsByRaceIdAndBibAndIdNot contre un vrai schéma | INT | it/RepositoryDerivedQueriesIT.java#existsByRaceIdAndBibAndIdNotExcludesOwnRunner | ACTIF | PASS 2026-09-26 | Nouveau (mode rattrapage) |
| INC3-TECH1 (hors CA, méthode autorisée non appelée) | INC-3 | `RunnerRepository.existsByRaceIdAndBib` est une méthode de repository autorisée par la spec (RG20 inc. 3, section « Nouvelles méthodes de repository autorisées ») mais qu'aucun service de production n'appelle (`RunnerService` n'utilise que la variante `AndIdNot`) : vérification défensive de la requête dérivée elle-même | INT | it/RepositoryDerivedQueriesIT.java#existsByRaceIdAndBibDetectsDuplicateBib | ACTIF | PASS 2026-09-26 | Corrigé lors de la revue de cohérence : n'était mentionné qu'en prose dans l'introduction « Mode rattrapage », sans ligne dédiée dans la matrice. **Arbitrage de l'agent fonctionnel (2026-09-26)** : accepté au patrimoine |
| INC3-CA30 | INC-3 | Modification d'un coureur [slice] | UNIT | api/RunnerApiSliceTest.java#ca30_invalidRunnerUpdate ; #ca30_unknownPropertiesAreIgnored | ACTIF | PASS 2026-09-26 | |
| INC3-CA31 | INC-3 | Suppression d'un coureur [unit + slice] | UNIT | service/RunnerServiceTest.java#ca31_deleteRunnerWithoutPassage ; #ca31_deleteRunnerOfRunningRace ; #ca31_deleteRunnerWithPassages ; #ca31_deleteUnknownRunner ; api/RunnerApiSliceTest.java#ca31_deleteRunner | ACTIF | PASS 2026-09-26 | |
| INC3-CA31 (réel) | INC-3 | existsByRunnerId contre un vrai schéma | INT | it/RepositoryDerivedQueriesIT.java#existsByRunnerIdDetectsPassages | ACTIF | PASS 2026-09-26 | Nouveau (mode rattrapage) |
| INC3-CA32 | INC-3 | Scan nominal [slice] | UNIT | api/RaceActionsApiSliceTest.java#ca32_nominalScan | ACTIF | PASS 2026-09-26 | Rejoué en persistance réelle par it/EndToEndRaceLifecycleIT et it/RaceBoardTransactionBoundaryIT |
| INC3-CA33 | INC-3 | Scan idempotent et réactivation [slice] | UNIT | api/RaceActionsApiSliceTest.java#ca33_idempotentScan ; #ca33_reactivationScan | ACTIF | PASS 2026-09-26 | |
| INC3-CA34 | INC-3 | Scan : erreurs [slice] | UNIT | api/RaceActionsApiSliceTest.java#ca34_blankQrToken ; #ca34_missingScannedAtIsHandledByService ; #ca34_unknownQrToken ; #ca34_doubleScan | ACTIF | PASS 2026-09-26 | |
| INC3-CA35 | INC-3 | DNF manuel [slice] | UNIT | api/RaceActionsApiSliceTest.java#ca35_manualDnf | ACTIF | PASS 2026-09-26 | |
| INC3-CA36 | INC-3 | DNF manuel sans raison [slice] | UNIT | api/RaceActionsApiSliceTest.java#ca36_manualDnfWithoutReason | ACTIF | PASS 2026-09-26 | |
| INC3-CA37 | INC-3 | Réintégration [slice] | UNIT | api/RaceActionsApiSliceTest.java#ca37_reintegration | ACTIF | PASS 2026-09-26 | Les 3 services (reintegrate/get/describePassages) sont mockés : l'orchestration RG22 n'est pas vérifiée avec de vraies associations JPA |
| INC3-CA37 (réel) | INC-3 | Réintégration, orchestration réelle des 3 services + JPA (association paresseuse Runner.race) | INT | it/RaceBoardTransactionBoundaryIT.java#reintegrationDescribePassagesLoadsLazyRaceAssociation ; it/EndToEndRaceLifecycleIT.java#raceLifecycleFromCreationToDashboardAfterAutoDnfAndReintegration | ACTIF | PASS 2026-09-26 | Nouveau (mode rattrapage) |
| INC3-CA38 | INC-3 | Réintégration sans passage et erreurs [slice] | UNIT | api/RaceActionsApiSliceTest.java#ca38_reintegrationWithoutPassage ; #ca38_reintegrationConflict ; #ca38_reintegrationNotFound | ACTIF | PASS 2026-09-26 | |
| INC3-CA39 | INC-3 | Pas de clôture manuelle [slice] | UNIT | api/RaceActionsApiSliceTest.java#ca39_noManualClosing ; api/ApiArchitectureTest.java#ca39_noApiClassDependsOnYardClosingService | ACTIF | PASS 2026-09-26 | |
| INC3-CA40 | INC-3 | Tableau de bord d'une course en cours [unit] | UNIT | service/RaceBoardServiceTest.java#ca40_boardOfRunningRace | ACTIF | PASS 2026-09-26 | Repositories mockés (dont findByRunnerRaceId, jamais exécuté contre une vraie base) |
| INC3-CA40 (réel) | INC-3 | Tableau de bord, requête groupée findByRunnerRaceId contre un vrai schéma, bout en bout | INT | it/RepositoryDerivedQueriesIT.java#findByRunnerRaceIdReturnsAllPassagesAcrossRunnersOfOneRace ; it/EndToEndRaceLifecycleIT.java#raceLifecycleFromCreationToDashboardAfterAutoDnfAndReintegration | ACTIF | PASS 2026-09-26 | Nouveau (mode rattrapage) |
| INC3-CA41 | INC-3 | Tableau de bord hors course [unit] | UNIT | service/RaceBoardServiceTest.java#ca41_boardOfSetupRace ; #ca41_boardOfFinishedRace ; #ca41_boardOfUnknownRace | ACTIF | PASS 2026-09-26 | |
| INC3-CA42 | INC-3 | Tableau de bord [slice] | UNIT | api/ReadModelApiSliceTest.java#ca42_boardOfRunningRace ; #ca42_boardOfSetupRace | ACTIF | PASS 2026-09-26 | |
| INC3-CA43 | INC-3 | Courses parallèles [unit] | UNIT | service/RaceBoardServiceTest.java#ca43_parallelRaces | ACTIF | PASS 2026-09-26 | |
| INC3-CA44 | INC-3 | Détail public d'un coureur [unit + slice] | UNIT | service/RaceBoardServiceTest.java#ca44_runnerDetail ; #ca44_runnerDetailIndependentOfRaceStatus ; #ca44_unknownRunnerDetail ; api/ReadModelApiSliceTest.java#ca44_publicRunnerDetail | ACTIF | PASS 2026-09-26 | Repositories/services mockés : le chargement paresseux réel de Runner.race (RG2) n'est pas observable |
| INC3-CA44 (réel) | INC-3 | Détail d'un coureur, association paresseuse Runner.race chargée dans la transaction du service (RG2) | INT | it/RaceBoardTransactionBoundaryIT.java#publicRunnerDetailLoadsLazyRaceAssociationWithinItsOwnTransaction | ACTIF | PASS 2026-09-26 | Nouveau (mode rattrapage) |
| INC3-CA45 | INC-3 | Controllers sans logique métier (revue de code automatisée) | UNIT | api/ApiArchitectureTest.java#ca45_controllersDoNotDependOnPersistenceClockOrCalculators ; #ca45_servicesDoNotDependOnApi | ACTIF | PASS 2026-09-26 | |
| INC3-CA46 | INC-3 | 401 sans authentification sur l'admin [slice] | UNIT | api/SecuritySliceTest.java#ca46_adminWithoutAuthentication ; #ca46_dnfWithoutAuthentication ; #ca46_deleteWithoutAuthentication | ACTIF | PASS 2026-09-26 | |
| INC3-CA47 | INC-3 | 401 sans authentification sur le scan [slice] | UNIT | api/SecuritySliceTest.java#ca47_scanWithoutAuthentication | ACTIF | PASS 2026-09-26 | |
| INC3-CA48 | INC-3 | 401 identifiants faux [slice] | UNIT | api/SecuritySliceTest.java#ca48_wrongPassword ; #ca48_unknownUser ; #ca48_sameDetailForWrongUserOrPassword | ACTIF | PASS 2026-09-26 | |
| INC3-CA49 | INC-3 | 403 SCANNER sur l'admin [slice] | UNIT | api/SecuritySliceTest.java#ca49_scannerOnAdmin ; #ca49_scannerCannotReintegrate | ACTIF | PASS 2026-09-26 | |
| INC3-CA50 | INC-3 | 200 ADMIN sur l'admin [slice] | UNIT | api/SecuritySliceTest.java#ca50_adminOnAdmin | ACTIF | PASS 2026-09-26 | |
| INC3-CA51 | INC-3 | Scan autorisé pour SCANNER et ADMIN [slice] | UNIT | api/SecuritySliceTest.java#ca51_scanAllowedForScannerAndAdmin | ACTIF | PASS 2026-09-26 | |
| INC3-CA52 | INC-3 | Public accessible sans authentification [slice] | UNIT | api/SecuritySliceTest.java#ca52_publicWithoutAuthentication ; #ca52_publicWithValidCredentials | ACTIF | PASS 2026-09-26 | |
| INC3-CA53 | INC-3 | En-tête Authorization invalide [slice] | UNIT | api/SecuritySliceTest.java#ca53_unreadableBasicHeader ; #ca53_basicHeaderWithoutColon ; #ca53_wrongCredentialsOnPublic ; #ca53_bearerHeaderIsIgnored | ACTIF | PASS 2026-09-26 | |
| INC3-CA54 | INC-3 | Sans état et sans CSRF [slice] | UNIT | api/SecuritySliceTest.java#ca54_noCsrfRequired ; #ca54_noSessionIsKept | ACTIF | PASS 2026-09-26 | |
| INC3-CA55 | INC-3 | Refus par défaut [slice] | UNIT | api/SecuritySliceTest.java#ca55_denyByDefault ; #ca55_unknownAdminPath ; #ca55_unsupportedMethodUnderAdminIsSecuredFirst | ACTIF | PASS 2026-09-26 | |
| INC3-CA56 | INC-3 | Configuration Spring Security 7 | UNIT | api/SecuritySliceTest.java#ca56_noLoginPage ; #ca56_noLogoutPage | ACTIF | PASS 2026-09-26 | Partie "revue de code" (API interdites : WebSecurityConfigurerAdapter, .and(), antMatchers, NoOpPasswordEncoder) garantie par la compilation contre Spring Security 7, non vérifiée par un test dédié |
| INC3-CA57 | INC-3 | Refus de démarrer si les identifiants sont incomplets [context] | UNIT | config/SecurityCredentialsStartupTest.java#ca57_validCredentialsStart ; #ca57_missingAdminPasswordHash ; #ca57_blankScannerUsername ; #ca57_plainTextPasswordIsRejected ; #ca57_identicalUsernames ; #ca57_noDefaultCredentialsInApplicationProperties | ACTIF | PASS 2026-09-26 | Corrigé lors de la revue de cohérence (rattaché à la classe entière sans nom de méthode). `ApplicationContextRunner`, sans base de données |
| INC3-RG34 | INC-3 | HTTPS obligatoire en production | — | non vérifiable automatiquement | N/A | N/A | Vérification à la recette d'infrastructure (reverse proxy TLS), conformément à la spec (section 8, PO24) : pas un écart |

**Écart R3-1 levé (validation INC-4, 2026-09-26, test-integration-backend).** Deux classes de test automatisé
existent désormais et sont vertes :
- `api/ApiSourceReviewTest.java` (3/3 `PASS`) : analyse textuelle de `fr.backyard.api` (hors commentaires et
  littéraux), aucune condition (`if`/`while`/`switch`/`&&`/`||`/`==`/`.equals`/`.filter`/`?:`) ne cite un statut,
  un yard, un dossard ou un rôle — couvre la partie « revue de code » de CA45 (inc. 3) ;
- `config/SecurityConfigurationReviewTest.java` (3/3 `PASS`) : le bean `PasswordEncoder` effectif du contexte
  est exactement `BCryptPasswordEncoder` (`isExactlyInstanceOf`), et aucune occurrence de
  `WebSecurityConfigurerAdapter`, `.and()` sans argument, `antMatchers`, `mvcMatchers` ou `NoOpPasswordEncoder`
  n'existe dans `backend/src/main` — couvre la partie « revue de code » de CA56 (inc. 3).
Les deux classes portent un garde-fou (« le détecteur repère chacun des motifs interdits/métier ») contre un
test vide qui passerait à tort. Non modifiées par cet agent (fichiers de l'utilisateur).

### Incrément 4 — Frontend PWA (impacts backend, `/valider-increment 4`)

Périmètre de cette section : uniquement les critères de type backend/architecture de `docs/specs/increment4.md`
relevant de l'agent `test-integration-backend` (section 10, « Impacts backend et build »). Les CA de logique
front pure (CA8 à CA20, `[front-unit]`), le build (CA5, `[build]`) et les parcours E2E (CA21 à CA38, `[E2E]`)
sont hors périmètre de cet agent : CA8 à CA20 relèvent du `testeur` (verdict technique OK déjà rendu, 170 tests
Vitest verts, couverture 99,41 % de lignes sur le périmètre déclaré, largement au-dessus du seuil de 80 %) ;
CA21 à CA38 relèvent de l'agent `test-e2e-frontend` (rapport `INC-4-e2e.md` séparé). CA5 est repris ci-dessous
à titre de preuve d'exécution réelle (`mvn -B -f backend/pom.xml clean verify` le 2026-09-26), sans nouveau
test écrit par cet agent.

| Exigence | Incrément | Description | Type | Test (chemin / nom) | Statut | Dernier résultat | Notes |
|---|---|---|---|---|---|---|---|
| INC4-CA1 | INC-4 | E19 `GET /api/scan/me` : SCANNER, ADMIN, anonyme, mauvais mot de passe [slice] | UNIT | api/SessionApiSliceTest.java#ca1_scannerSession ; #ca1_adminSession ; #ca1_unauthenticated | ACTIF | PASS 2026-09-26 | Test écrit par le développeur (hors périmètre de cet agent), revu ici : `@WebMvcTest` avec `SecurityConfig` et `SessionService` réels et une `Clock` fixe (pas de mock de service) ; couvre les 200 (SCANNER, ADMIN), le 401 anonyme sans `WWW-Authenticate` et le 401 mot de passe faux, sans `Set-Cookie`. Pas de tag `INC4-CA1` dans le code (seul `INC-4` est présent) : non modifié, conformément à la consigne de ne pas toucher aux tests existants ; référencé ici par chemin et nom |
| INC4-CA2 | INC-4 | E19 sans logique dans le controller, matrice RG29 (inc. 3) inchangée [architecture] | UNIT | api/ApiArchitectureTest.java#ca45_controllersDoNotDependOnPersistenceClockOrCalculators ; api/SecuritySliceTest.java (24 tests, classe entière, inchangée) | ACTIF | PASS 2026-09-26 | Test d'architecture de l'INC-3 (CA45), non modifié : il scanne tout `fr.backyard.api`, donc `SessionController` inclus, et vérifie qu'aucune classe n'injecte `Clock`, un repository ou un calculateur. `SecuritySliceTest` (24 méthodes, CA46 à CA55 inc. 3) exécuté sans modification : 24/24 PASS, confirmant que RG29 (inc. 3) est inchangée |
| INC4-CA3 | INC-4 | Fichiers de la PWA servis par Spring Boot : types, `Cache-Control`, renvoi des routes vers `index.html`, refus (`POST /`, `/nimporte-quoi`, `/application.properties`, traversée), `/api/**` jamais renvoyé vers `index.html`, matrice `/api/**` inchangée (ADMIN/SCANNER) [back-IT] (RG53, RG5) | INT | it/PwaStaticResourcesIT.java#ca3_rootServesIndexHtmlWithNoCache ; #ca3_headOnRootReturns200 ; #ca3_manifestServedWithCorrectMediaType ; #ca3_ngswWorkerServedAsJavascriptNoCache ; #ca3_ngswJsonServedAsJsonNoCache ; #ca3_fingerprintedJsFileServedImmutable ; #ca3_frontRoutesForwardToIndexHtml (6 routes) ; #ca3_unknownOrForbiddenPathsAreNeverServed (3 chemins) ; #ca3_postOnFrontRoutesIsNeverAccepted (2 routes) ; #ca3_apiPathsAreNeverForwardedToIndexHtml ; #ca3_apiMatrixUnchangedForScannerAccount | ACTIF | PASS 2026-09-26 (19/19) | Nouveau (test-integration-backend). Contexte Spring complet, serveur Tomcat embarqué réel (`webEnvironment = RANDOM_PORT`), contre les fichiers réellement produits par le build Angular (`target/classes/static`, régénéré à chaque `mvn verify`). **Limite d'environnement découverte et documentée** : le renvoi interne (`forward:/index.html`) d'une route du front vers un `ResourceHttpRequestHandler` n'est pas rejoué par le dispatcher de test de `MockMvc` en environnement simulé (`@AutoConfigureMockMvc`, `webEnvironment` par défaut) — le corps et le type de contenu de la ressource cible restent vides malgré un statut 200 et un `Forwarded URL` correct. D'où l'usage d'un vrai serveur embarqué pour ce test, plutôt que la classe `AbstractApiIT` (basée sur `MockMvc`) utilisée par les tests des incréments 1 à 3. `RestTemplate` nu (pas `TestRestTemplate`, absent du classpath de test avec cette configuration Spring Boot 4.1 modulaire — `spring-boot-restclient` manquant — non ajouté pour rester sans nouvelle dépendance non signalée) |
| INC4-CA4 | INC-4 | Aucun CORS : `GET /api/public/races`, `OPTIONS /api/admin/races` (preflight), `GET /` avec `Origin` étranger [back-IT] (RG54) | INT | it/NoCorsIT.java#ca4_publicEndpointNeverSendsCorsHeaderToForeignOrigin ; #ca4_preflightFromForeignOriginIsNotAccepted ; #ca4_pwaFileNeverSendsCorsHeaderToForeignOrigin | ACTIF | PASS 2026-09-26 (3/3) | Nouveau (test-integration-backend). Contexte Spring complet, `MockMvc` (pas de forward impliqué : seule l'absence d'en-tête est vérifiée) |
| INC4-CA5 | INC-4 | Build intégré et définition de « fini » du front [build] (RG59, PO4) | — | `mvn -B -f backend/pom.xml clean verify`, exécuté le 2026-09-26 | N/A | PASS 2026-09-26 | Hors périmètre de cet agent (pas un test `*IT`, pas de code écrit ici). Preuve d'exécution réelle citée en section 3 du rapport `INC-4-integration.md` : `npm ci` + build Angular + 170 tests Vitest (0 échec) + couverture 99,41 % lignes (seuil 80 %) exécutés avec succès ; jar contient `index.html`, `manifest.webmanifest`, `ngsw-worker.js`, `ngsw.json`. Contrôle négatif (test front en échec volontaire) non rejoué par cet agent : à la charge du `testeur` (verdict technique OK déjà rendu) |
| INC4-CA6 | INC-4 | En-têtes de sécurité (CSP, Permissions-Policy, Referrer-Policy, X-Content-Type-Options) sur les réponses HTML de la PWA et sur une réponse API [back-IT] (RG55) | INT | it/SecurityHeadersIT.java#ca6_pwaHtmlResponsesCarrySecurityHeaders (2 chemins) ; #ca6_apiResponseCarriesContentTypeOptions | ACTIF | PASS 2026-09-26 (3/3) | Nouveau (test-integration-backend). Ces en-têtes sont posés par les filtres Spring Security sur la requête d'origine avant tout `forward` : observables même dans l'environnement `MockMvc` simulé (contrairement à CA3) |
| INC4-CA7 | INC-4 | Aucune API de test : les correspondances Spring MVC du contexte complet (profil `test`) se limitent à E1-E19, au manifeste, à `/error` (standard Spring Boot) et aux ressources/vues de RG53 ; aucun chemin ne contient "test", "clock", "time", "reset" ou "close" [back-IT] (RG56) | INT | it/NoTestEndpointsIT.java#ca7_requestMappingsAreExactlyTheDocumentedEndpoints ; #ca7_noMvcPathContainsForbiddenWords | ACTIF | PASS 2026-09-26 (2/2) | Nouveau (test-integration-backend). Introspection réelle des beans `RequestMappingHandlerMapping` (E1 à E19 : 14 motifs uniques, 19 méthodes) et `AbstractUrlHandlerMapping` (ressources statiques, vues du front) du contexte démarré, pas une relecture des seules constantes de `PwaPaths` |

## Écarts ouverts

| Exigence | Incrément | Écart constaté | Décision agent fonctionnel | Échéance |
|---|---|---|---|---|
| INC1-TECH1 (R1-1) | INC-1 | Le nom `ca23_raceAndRunnerSettersUpdateFields` laisse croire à un CA23 qui n'existe pas | Test accepté au patrimoine. Renommage exigé en `tech_raceAndRunnerSettersUpdateFields`, sans modification d'assertion, puis mise à jour de la ligne de matrice (testeur) | Avant la validation de l'INC-4 — **LEVÉE** (test-integration-backend, 2026-09-26) : renommage constaté, `PASS` |
| INC1-CA1, CA3, CA5, CA10, CA11, CA12, CA22, CA21 (R1-2) | INC-1 | Relecture depuis le cache de premier niveau (`@DataJpaTest`, sans `clear()`) : la relecture « depuis la base » n'est pas réellement exercée (aller-retour `Instant` / `TIMESTAMP WITH TIME ZONE`). CA21 accepte n'importe quelle `Exception` | Renforcement autorisé et exigé : `flush()` + `clear()` avant relecture, et exception attendue restreinte pour CA21 (testeur) | Avant la validation de l'INC-4 — **LEVÉE** (test-integration-backend, 2026-09-26) : `flush()+clear()` et `ConstraintViolationException` sur `name` constatés, 22/22 `PASS` |
| INC1 RG11, RG15, CL12, CHECK de RG1/RG6/RG13 (R1-3) | INC-1 | Règles sans CA dédié dans `docs/specs/increment1.md` (défaut de spec) | Addendum de spec par l'agent fonctionnel (nouveaux CA numérotés après R1-1), puis tests (testeur) | Avant la validation de l'INC-4 — **PARTIELLEMENT LEVÉE** (test-integration-backend, 2026-09-26) : addendum de spec écrit (CA23 à CA31, `docs/specs/increment1.md`) ; **aucun test CA23 à CA27 n'existe encore** (CA30/CA31 déjà couverts par des tests existants) — écart maintenu pour la partie « tests », voir matrice ci-dessus. **Mise à jour testeur (2026-09-26)** : tests CA23 à CA29 écrits (CA28 et CA29 étaient eux aussi sans test) ; `mvn -B -f backend/pom.xml clean verify` BUILD SUCCESS, surefire 297/297, failsafe 41/41, dont `RunnerDnfConsistencyTest` 4/4, `DomainIntegrityArchitectureTest` 6/6, `Increment1AddendumPersistenceTest` 20/20, `RacePersistenceTest` 22/22 ; aucun bug de production révélé. CA23 à CA31 ont tous au moins un test `PASS` : **levée proposée par le testeur, à prononcer par l'agent fonctionnel** (RT1 s'applique toujours à ces tests H2) |
| INC2-CA31, CA32, CA48 (R2-1) | INC-2 | Assertions partielles : R1 `FINISHED` sans vainqueur, `raceFinished` et `timedOutRunnerIds` par course (CA31) ; R2 `RUNNING` (CA32) ; A et C `ACTIVE`, R1 `RUNNING`, `timedOutRunnerIds` vide à la première clôture (CA48). Comportements couverts ailleurs (CA34, CA35) | Renforcement autorisé et exigé : compléter les assertions (testeur) | Avant la validation de l'INC-4 — **LEVÉE** (test-integration-backend, 2026-09-26) : assertions complètes constatées, 17/17 et 11/11 `PASS` |
| INC3-CA56 et CA45, partie revue de code (R3-1) | INC-3 | Aucun test automatisé. La compilation ne garantit ni l'absence de `NoOpPasswordEncoder` ni le choix de BCrypt. Revue manuelle de l'agent fonctionnel le 2026-09-26 : conforme | Test automatisé exigé : type du `PasswordEncoder` et recherche des API interdites dans les sources (testeur) | Avant la validation de l'INC-4 — **LEVÉE** (test-integration-backend, 2026-09-26) : `ApiSourceReviewTest` (3/3) et `SecurityConfigurationReviewTest` (3/3) `PASS` |
| RT1 : INC1-CA20, INC3-CA8, INC3 RG2 (transverse) | INC-1, INC-3 (et scénario INC-2) | Aucune exécution sur un vrai PostgreSQL : schéma Flyway 12 / Hibernate 7 (Spring Boot 4.1.1) jamais validé contre PostgreSQL, alors que la production tourne sur PostgreSQL | Réserve obligatoire : suite `*IT` complète + démarrage avec le profil `prod` (Flyway V1 + `ddl-auto=validate`) contre un PostgreSQL de même version majeure que celui du VPS (Testcontainers en CI, ou base de recette), avec résultats consignés dans un rapport (test-integration-backend, environnement fourni par l'orchestrateur). Recette HTTPS (INC3-RG34) au même moment | Avant tout déploiement sur le VPS, au plus tard comme condition du GO de l'INC-4 — **NON EXÉCUTÉE (validation INC-4, 2026-09-26)** : ni Docker ni `psql` disponibles sur le poste d'exécution (vérifié, aucune tentative d'installation). Reste une réserve bloquante avant tout déploiement sur le VPS ; aucun résultat inventé |
| RT2 : E2E (transverse) | INC-1 à INC-3 → INC-4 | E2E « sans objet » accepté pour INC-1 à INC-3 (pas de frontend) | Refusé dès l'INC-4. Parcours minimaux : inscription, scan avec filet réseau (file locale, retry, FIFO), tableau de bord en polling avec bascule de yard et badge « corrigé », DNF manuel avec confirmation, réintégration, rôles 401/403, deux courses en parallèle, contrats INC-3 depuis le navigateur, CORS (PO23), ressources de la PWA (PO27) | Validation de l'INC-4 — hors périmètre de cet agent, voir `INC-4-e2e.md` (agent `test-e2e-frontend`) |
| RT3 : « compile sans warning » (transverse) | INC-1 à INC-3 | Critère de la définition de « fini » non attesté par les rapports de rattrapage (`-Xlint:all` affiché, non bloquant) | Accepté pour INC-1 à INC-3 sur la foi du verdict technique OK de livraison. Le rapport de synthèse de l'INC-4 doit citer la sortie du compilateur, ou le build doit rendre les warnings bloquants | Validation de l'INC-4 — **LEVÉE pour cette exécution** (test-integration-backend, 2026-09-26) : `mvn -B -f backend/pom.xml clean verify` intégral cité dans `INC-4-integration.md`, sortie complète relue, **aucune ligne `[WARNING]` ni `[ERROR]`** (seuls des avertissements JVM/agent Mockito sans rapport avec le code, cf. rapport). Les warnings ne sont pas rendus bloquants par le build (`-Xlint:all` toujours non `-Werror`) : le constat est celui d'une exécution propre, pas une garantie structurelle |
| RT4 : publication (transverse) | INC-1 à INC-3 | Tests `*IT`, correction JaCoCo du `pom.xml`, patrimoine et rapports non commités (testé sur `c491bb6` + working tree) | Publication de la branche `chore/workflow-validation` (MR), relue et mergée par l'humain | Avant le démarrage de l'INC-4 |

Les cas limites INC1-CL8 et INC1-CL9 (non couverts par un CA numéroté de la spec) ont été comblés par de
nouveaux tests `*IT` (voir matrice ci-dessus) et ne constituent donc plus un écart. Les tests INC1-TECH1 et
INC3-TECH1 sont acceptés au patrimoine par l'agent fonctionnel (2026-09-26).

## Tests en quarantaine

| Test | Motif | Date | Responsable | Échéance de réactivation |
|---|---|---|---|---|

Aucun test en quarantaine.

## Historique des validations

| Incrément | Rapport de synthèse | Verdict | Date |
|---|---|---|---|
| INC-1 | docs/tests/rapports/INC-1-integration.md (+ INC-1-e2e.md, INC-1-coherence.md), mode rattrapage | GO sous réserves (R1-1, R1-2, R1-3, RT1, RT2, RT3, RT4) | 2026-09-26 |
| INC-2 | docs/tests/rapports/INC-2-integration.md (+ INC-2-e2e.md, INC-2-coherence.md), mode rattrapage | GO sous réserves (R2-1, RT1, RT2, RT3, RT4) | 2026-09-26 |
| INC-3 | docs/tests/rapports/INC-3-integration.md (+ INC-3-e2e.md, INC-3-coherence.md), mode rattrapage | GO sous réserves (R3-1, RT1, RT2, RT3, RT4) | 2026-09-26 |
