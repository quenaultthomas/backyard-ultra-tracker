# Rapport de test : INC-3 (intégration, mode rattrapage)

- **Date** : 2026-09-26
- **Agent auteur** : test-integration-backend
- **Version / commit testé** : c491bb6 (branche `chore/workflow-validation`), + tests `*IT` ajoutés par ce rattrapage (non commités au moment du test)
- **Environnement** : profil Spring `test`, base H2 en mode PostgreSQL, Spring Security réel (HTTP Basic, comptes de test `admin-test` / `admin-secret` et `scanner-test` / `scanner-secret`), `spring.jpa.open-in-view=false`, `MockMvc` sur contexte `@SpringBootTest` complet (pas de slice).

## 1. Périmètre

Mode rattrapage : les 57 critères d'acceptation (CA1 à CA57) de `docs/specs/increment3.md` sont déjà
couverts (voir cartographie complète dans `docs/tests/PATRIMOINE.md`) par :
- des tests de slice `@WebMvcTest` avec la configuration de sécurité réelle importée mais les **services
  mockés** (`api/ApiErrorsSliceTest.java`, `api/RaceApiSliceTest.java`, `api/RunnerApiSliceTest.java`,
  `api/RaceActionsApiSliceTest.java`, `api/ReadModelApiSliceTest.java`, `api/SecuritySliceTest.java`,
  `api/ApiArchitectureTest.java`) ;
- des tests unitaires de service avec **repositories mockés** (`service/RaceServiceTest.java`,
  `service/RunnerServiceTest.java`, `service/RaceBoardServiceTest.java`, `service/QrTokenGeneratorTest.java`) ;
- un test `ApplicationContextRunner` sans base de données pour CA57
  (`config/SecurityCredentialsStartupTest.java`).

Ce rattrapage cible précisément ce que ces tests **ne peuvent pas voir** parce qu'ils mockent la couche
service ou la couche persistance :
1. **CA8 / RG6** : la conversion d'une `DataIntegrityViolationException` en 409 `DATA_INTEGRITY` n'était
   vérifiée qu'avec un service mocké levant une exception construite à la main, jamais avec une vraie
   violation de contrainte traduite par Spring Data JPA.
2. **RG2** (frontière de transaction, `open-in-view=false`) : `RaceBoardService.runnerDetail` et
   `describePassages` accèdent à l'association paresseuse `Runner.race` (`FetchType.LAZY`) ; avec des
   repositories mockés, cette association n'est jamais un vrai proxy Hibernate, donc un chargement paresseux
   cassé (accès hors transaction) ne peut pas être détecté par les tests existants.
3. **Méthodes de repository ajoutées par l'incrément 3** (`existsByName`, `existsByNameAndIdNot`,
   `existsByRaceIdAndBib[AndIdNot]`, `existsByRunnerId`, `findByRunnerRaceId`) : jamais exécutées contre un
   vrai schéma (seulement mockées ou simulées par `FakeRepositories`, une implémentation en mémoire).
4. **Démarrage du contexte complet** : aucun test existant ne démarre ensemble la sécurité, les
   contrôleurs, les services et la persistance (les slices `@WebMvcTest` excluent la persistance réelle, les
   `@DataJpaTest`/tests unitaires excluent le web et la sécurité réelle).

Nouveaux tests ajoutés :
- `backend/src/test/java/fr/backyard/it/QrTokenUniquenessIT.java` (1 méthode, CA8).
- `backend/src/test/java/fr/backyard/it/RaceBoardTransactionBoundaryIT.java` (2 méthodes, RG2 / CA37, CA44).
- `backend/src/test/java/fr/backyard/it/RepositoryDerivedQueriesIT.java` (6 méthodes).
- `backend/src/test/java/fr/backyard/it/EndToEndRaceLifecycleIT.java` (1 méthode, partagée avec INC-2 : voir
  `INC-2-integration.md`).

## 2. Couverture exigences ↔ tests

| Exigence | Critère d'acceptation | Tests | Résultat | Écart ? |
|---|---|---|---|---|
| INC3-CA1 à INC3-CA57 (hors CA8, CA37, CA44 détaillés ci-dessous) | Erreurs, courses, coureurs, actions de course, lecture des valeurs dérivées, sécurité (détail complet dans `docs/tests/PATRIMOINE.md`) | Tests de slice et unitaires existants | PASS | Non |
| INC3-CA8 | Violation d'intégrité mappée en 409 `DATA_INTEGRITY` | `api/ApiErrorsSliceTest.java#ca8_dataIntegrityViolation` (service mocké) + **nouveau** `it/QrTokenUniquenessIT.java#secondRegistrationWithColldingQrTokenIsRejectedAsDataIntegrityViolation` (vraie violation de `uq_runner_qr_token`, générateur de jeton de test forcé à collision, chaîne HTTP → sécurité → service → JPA → H2 réelle) | PASS | Non (comblé) |
| INC3-CA37 | Réintégration : orchestration de 3 services (`reintegrate`, `get`, `describePassages`) | `api/RaceActionsApiSliceTest.java#ca37_reintegration` (3 services mockés) + **nouveau** `it/RaceBoardTransactionBoundaryIT.java#reintegrationDescribePassagesLoadsLazyRaceAssociation` (les 3 services réels, association paresseuse `Runner.race` réellement chargée) | PASS | Non (comblé) |
| INC3-CA44 | Détail public d'un coureur | `service/RaceBoardServiceTest.java` + `api/ReadModelApiSliceTest.java#ca44_publicRunnerDetail` (mockés) + **nouveau** `it/RaceBoardTransactionBoundaryIT.java#publicRunnerDetailLoadsLazyRaceAssociationWithinItsOwnTransaction` | PASS | Non (comblé) |
| Méthodes de repository de l'incrément 3 | `existsByName`, `existsByNameAndIdNot`, `existsByRaceIdAndBib[AndIdNot]`, `existsByRunnerId`, `findByRunnerRaceId` | **Nouveau** `it/RepositoryDerivedQueriesIT.java` (6 méthodes) | PASS | Non (comblé) |
| INC3-RG34 | HTTPS obligatoire en production | Non vérifiable automatiquement (vérification à la recette d'infrastructure, reverse proxy TLS) | N/A | Non : documenté comme tel dans la spec elle-même (section 8, PO24), pas un écart de test |

Exigences sans test : aucune.

## 3. Résultats d'exécution (réels)

| Suite | Total | Passés | Échoués | Ignorés | Durée |
|---|---|---|---|---|---|
| Tests de l'incrément (`-Dgroups=INC-3`) | 10 | 10 | 0 | 0 | 11.54 s |
| Non-régression (suite complète, `clean verify`) | 269 (255 unitaires + 14 intégration) | 269 | 0 | 0 | 32.78 s |

Commande(s) exécutée(s) :
```
mvn -B -f backend/pom.xml verify -Dgroups=INC-3 -Djacoco.skip=true
mvn -B -f backend/pom.xml clean verify
```

Résultats détaillés (`backend/target/failsafe-reports/`, lors de l'exécution filtrée `-Dgroups=INC-3`) :
- `fr.backyard.it.EndToEndRaceLifecycleIT.txt` : 1/1 (également compté dans INC-2).
- `fr.backyard.it.QrTokenUniquenessIT.txt` : 1/1.
- `fr.backyard.it.RaceBoardTransactionBoundaryIT.txt` : 2/2.
- `fr.backyard.it.RepositoryDerivedQueriesIT.txt` : 6/6.

**Couverture JaCoCo (corrigée le 2026-09-26, voir section 7 pour la cause de la correction)** — mesure
**unitaire uniquement** (`backend/target/jacoco.exec`, alimenté par surefire seul depuis la correction du
`pom.xml` séparant `jacoco.exec` de `jacoco-it.exec`), source `backend/target/site/jacoco/jacoco.csv`
(colonnes `LINE_COVERED` / `LINE_MISSED`), calcul `LINE_COVERED / (LINE_COVERED + LINE_MISSED)`, reproduit à
l'identique sur deux exécutions consécutives de `mvn -B -f backend/pom.xml clean verify` :
- `fr.backyard.domain` : 180/185 lignes = **97,30 %**
- `fr.backyard.service` : 294/297 lignes = **98,99 %**
- ensemble domain+service : 474/482 lignes = **98,34 %**

Seuil exigé (CLAUDE.md) : 80 % — largement dépassé. `jacoco:check` en `BUILD SUCCESS`.

## 4. Échecs et bugs détectés

Aucun. Tous les tests sont passés dès la première exécution. En particulier :
- la violation réelle de `uq_runner_qr_token` produit bien un `DataIntegrityViolationException` (traduit par
  Spring depuis `JdbcSQLIntegrityConstraintViolationException` de H2) correctement mappé en 409
  `DATA_INTEGRITY`, sans fuite du nom de la contrainte SQL dans le corps de réponse (conforme à RG6) ;
- aucune `LazyInitializationException` n'a été observée sur `Runner.race` malgré
  `spring.jpa.open-in-view=false`, confirmant que `RaceBoardService` charge bien l'association dans sa propre
  transaction (RG2) avant que le controller ne lise les champs simples de la vue.

## 5. Modifications du patrimoine existant

Ajout uniquement, aucune modification ni suppression d'un test existant :
- `backend/src/test/java/fr/backyard/it/QrTokenUniquenessIT.java` (nouveau).
- `backend/src/test/java/fr/backyard/it/RaceBoardTransactionBoundaryIT.java` (nouveau).
- `backend/src/test/java/fr/backyard/it/RepositoryDerivedQueriesIT.java` (nouveau).
- `backend/src/test/java/fr/backyard/it/EndToEndRaceLifecycleIT.java` (nouveau, partagé avec INC-2).
- Réutilisation de `backend/src/test/java/fr/backyard/it/support/AbstractApiIT.java` (classe de base
  introduite lors du rattrapage INC-1).
- `docs/tests/PATRIMOINE.md` mis à jour (matrice complète INC-3).

Aucune dépendance Maven ajoutée, aucun fichier de `backend/src/main` modifié.

**Compléments du 2026-09-26 suite à la revue de cohérence du patrimoine** (aucun test modifié, uniquement
`docs/tests/PATRIMOINE.md`) :
- `it/RepositoryDerivedQueriesIT.java#existsByRaceIdAndBibDetectsDuplicateBib` n'était mentionné qu'en prose
  dans l'introduction de la section « Mode rattrapage » de la matrice, sans ligne dédiée : ligne ajoutée
  (exigence `INC3-TECH1`), avec la même note que le commentaire du code (méthode de repository autorisée par
  la spec RG20 mais non appelée par le code de production, vérification défensive).
- `INC3-CA57` était rattaché, dans la matrice, à la classe entière `config/SecurityCredentialsStartupTest.java`
  sans nom de méthode : ligne corrigée pour lister les 6 méthodes réelles (`ca57_validCredentialsStart`,
  `ca57_missingAdminPasswordHash`, `ca57_blankScannerUsername`, `ca57_plainTextPasswordIsRejected`,
  `ca57_identicalUsernames`, `ca57_noDefaultCredentialsInApplicationProperties`).

## 6. Tests instables ou en quarantaine

Aucun. Le générateur de jeton QR fixé pour `QrTokenUniquenessIT` utilise sa propre configuration de test
(`@TestConfiguration` importée uniquement par cette classe), isolée du contexte partagé par les autres tests
`*IT`, pour ne pas perturber la génération aléatoire normale des autres scénarios.

## 7. Risques et limites

- CA56 (revue de code : absence de `WebSecurityConfigurerAdapter`, `.and()`, `antMatchers`,
  `NoOpPasswordEncoder`) reste garantie par la compilation contre les API de Spring Security 7 (ces classes
  n'existent plus / sont supprimées), pas par un test de revue de code automatisé dédié : risque résiduel
  faible, non traité dans ce rattrapage.
- Le générateur de jeton QR de production (`java.util.UUID.randomUUID()`) reste probabiliste : une collision
  naturelle en production a une probabilité négligeable (RG16). Le test `QrTokenUniquenessIT` la force
  volontairement pour vérifier le comportement de l'application dans ce cas, il ne mesure pas la probabilité
  réelle de collision (déjà couverte par `service/QrTokenGeneratorTest.java#ca26_...`).
- Comme pour INC-1, les tests contre H2 en mode PostgreSQL ne remplacent pas une vérification contre un vrai
  serveur PostgreSQL (absence de Testcontainers/Docker dans cet environnement).

**Correction du 2026-09-26 (suite à la revue de cohérence, constat INC3-C5-01, bloquant, identique à
INC1-C5-01 et INC2-C5-01)** : la première version de ce rapport citait une couverture JaCoCo de domain
94,13 %, service 98,83 %, ensemble 97,36 %, chiffres que le relecteur n'a pas pu reproduire. **Cause
identifiée** : avant correction du `pom.xml`, le rapport JaCoCo (phase `test`) et le `jacoco:check` (phase
`verify`) lisaient tous les deux le même fichier `target/jacoco.exec`, enrichi en continu pendant tout le
build, y compris pendant les tests `*IT` de failsafe (phase `integration-test`, entre `test` et `verify`).
**Correction apportée par l'orchestrateur** : `target/jacoco-it.exec` dédié aux tests `*IT`
(`prepare-agent-integration`, `argLine` de failsafe), si bien que `target/jacoco.exec` — et donc le rapport
et le `check` — ne mesurent plus que les tests unitaires, conformément à CLAUDE.md. Chiffres corrigés et
reproductibles en section 3. Le seuil de 80 % était déjà franchi avant et après correction : aucune
conséquence sur le verdict de couverture, seulement sur la précision du chiffre rapporté.

## 8. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : **GO sous réserves** (mode rattrapage, incrément déjà mergé dans `main`)
- **Réserves ou motifs** :
  - Vérification CA par CA (spec `docs/specs/increment3.md`, 57 CA) : chaque CA a au moins un test `ACTIF` ;
    CA57 est désormais rattaché à ses 6 méthodes réelles. Les points que les slices mockées ne pouvaient pas
    voir sont comblés par des `*IT` en contexte complet : CA8 (vraie violation `uq_runner_qr_token` → 409
    `DATA_INTEGRITY`, sans fuite du nom de contrainte), RG2 / CA37 / CA44 (association paresseuse
    `Runner.race` avec `open-in-view=false`), et les requêtes dérivées de CA11, CA16, CA29, CA31 et CA40.
    La revue de cohérence a relu exhaustivement les tests de sécurité (CA46 à CA57).
  - Résultats cités jugés réels : 10/10 pour `-Dgroups=INC-3` (1 + 2 + 6 + 1), 269/269 pour la suite
    complète, couverture unitaire 98,34 %. Le constat bloquant INC3-C5-01 est levé avec preuve.
  - **Arbitrage INC3-TECH1** (`existsByRaceIdAndBibDetectsDuplicateBib`) : **accepté au patrimoine**. La méthode
    est autorisée par la spec (RG20 inc. 3) et le test vérifie réellement la requête dérivée. Si une future
    spec retire cette méthode, le test passera `OBSOLÈTE` avec l'accord de l'agent fonctionnel.
  - RG34 (HTTPS) : « non vérifiable automatiquement » accepté, conformément à la spec (PO24). À contrôler
    lors de la recette d'infrastructure du VPS, en même temps que RT1.
  - **R3-1** : la partie « revue de code » de CA56 n'a pas de test automatisé, et la justification « garantie
    par la compilation contre Spring Security 7 » est inexacte. La compilation ne garantit ni l'absence de
    `NoOpPasswordEncoder` (classe dépréciée, à ma connaissance non retirée) ni le choix de BCrypt. J'ai fait
    le 2026-09-26 une revue manuelle de `backend/src/main` : aucune occurrence de
    `WebSecurityConfigurerAdapter`, `.and()`, `antMatchers`, `mvcMatchers` ni `NoOpPasswordEncoder` ;
    `BCryptPasswordEncoder` est en `config/SecurityConfig.java:64` ; `formLogin` et `logout` sont désactivés
    (l. 48-49). CA56 est donc satisfait à ce jour. De même, la partie « aucune condition sur un statut, un
    yard, un dossard ou un rôle dans `fr.backyard.api` » de CA45 n'est couverte que par une revue. Action :
    ajouter un test automatisé qui affirme que le `PasswordEncoder` exposé est un `BCryptPasswordEncoder` et
    qui recherche les occurrences interdites dans les sources. Responsable : testeur. Échéance : avant la
    validation de l'INC-4.
  - **RT1** (transverse) : la traduction d'une violation de contrainte PostgreSQL (SQLState 23505) en 409
    (CA8) et le chargement paresseux (RG2) n'ont été vérifiés que sur H2. Action et échéance : voir
    `INC-1-integration.md` (avant tout déploiement sur le VPS, au plus tard comme condition du GO de l'INC-4).
  - **RT2** (transverse) : les contrats de l'API n'ont jamais été consommés depuis un navigateur. Il faut
    vérifier en E2E à l'INC-4 : HTTP Basic, ProblemDetail, CORS (PO23), ouverture des ressources de la PWA
    face au `denyAll()` (PO27). Échéance : validation de l'INC-4.
  - **RT3**, **RT4** (transverses) : voir `INC-1-integration.md`.
- **Actions correctives exigées** : aucune avant GO. R3-1 est suivie dans « Écarts ouverts » de
  `docs/tests/PATRIMOINE.md`.
- **Date** : 2026-09-26 (agent fonctionnel)
