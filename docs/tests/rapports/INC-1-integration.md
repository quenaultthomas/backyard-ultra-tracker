# Rapport de test : INC-1 (intégration, mode rattrapage)

- **Date** : 2026-09-26
- **Agent auteur** : test-integration-backend
- **Version / commit testé** : c491bb6 (branche `chore/workflow-validation`), + tests `*IT` ajoutés par ce rattrapage (non commités au moment du test)
- **Environnement** : profil Spring `test`, base H2 en mode PostgreSQL (`jdbc:h2:mem:testdb;MODE=PostgreSQL`), schéma créé par Flyway (`V1__init.sql`), `ddl-auto=validate`. Tests exécutés en local (Windows), Java 21 (build), JVM d'exécution des tests OpenJDK 25.

## 1. Périmètre

Mode rattrapage : incrément déjà livré et mergé dans `main` avant la mise en place du workflow de test
d'intégration. Ce rapport couvre :
- la cartographie des 22 critères d'acceptation (CA1 à CA22) de `docs/specs/increment1.md` vers les tests
  existants (`persistence/RacePersistenceTest.java`, `@DataJpaTest`) ;
- les cas limites CL8 et CL9 (suppression bloquée par les contraintes de clé étrangère RESTRICT), qui ne sont
  couverts par aucun CA numéroté de la spec ;
- CA20 (validation du schéma par Flyway + `ddl-auto=validate`), jusqu'ici vérifié seulement par un slice
  `@DataJpaTest` (contexte JPA partiel), pas par un contexte Spring complet (web + sécurité + JPA ensemble).

Nouveaux tests ajoutés : `backend/src/test/java/fr/backyard/it/SchemaAndContextStartupIT.java` (4 méthodes).

## 2. Couverture exigences ↔ tests

| Exigence | Critère d'acceptation | Tests | Résultat | Écart ? |
|---|---|---|---|---|
| INC1-CA1 à INC1-CA19, CA21, CA22 | Persistance minimale, contraintes d'unicité, requêtes de repository (voir détail dans `docs/tests/PATRIMOINE.md`) | `persistence/RacePersistenceTest.java` (`@DataJpaTest`, 22 méthodes `caN_...`) | PASS | Non |
| INC1-CA20 | Validation du schéma par Flyway, `ddl-auto=validate` | `persistence/RacePersistenceTest.java` (slice JPA) + **nouveau** `it/SchemaAndContextStartupIT.java#fullContextStartsWithFlywayMigrationAndSchemaValidation` (contexte Spring complet) et `#productionProfileKeepsDdlAutoValidate` (revue statique des propriétés de production) | PASS | Non (limite documentée : pas de vrai PostgreSQL disponible dans cet environnement, seulement H2 en mode PostgreSQL) |
| INC1-CL8 | Suppression d'une Race avec coureurs bloquée par `fk_runner_race` (RESTRICT) | **Nouveau** `it/SchemaAndContextStartupIT.java#deletingRaceWithRunnersIsBlockedByForeignKeyRestrict` | PASS | Non (comblé par ce rattrapage ; aucun CA numéroté de la spec ne couvrait ce cas limite) |
| INC1-CL9 | Suppression d'un Runner avec passages bloquée par `fk_passage_runner` (RESTRICT) | **Nouveau** `it/SchemaAndContextStartupIT.java#deletingRunnerWithPassagesIsBlockedByForeignKeyRestrict` | PASS | Non (comblé par ce rattrapage) |

Exigences sans test : aucune.

## 3. Résultats d'exécution (réels)

| Suite | Total | Passés | Échoués | Ignorés | Durée |
|---|---|---|---|---|---|
| Tests de l'incrément (`-Dgroups=INC-1`) | 4 | 4 | 0 | 0 | 4.97 s |
| Non-régression (suite complète, `clean verify`) | 269 (255 unitaires + 14 intégration) | 269 | 0 | 0 | 32.78 s |

Commande(s) exécutée(s) :
```
mvn -B -f backend/pom.xml verify -Dgroups=INC-1 -Djacoco.skip=true
mvn -B -f backend/pom.xml clean verify
```

Résultats détaillés (extraits de `backend/target/failsafe-reports/fr.backyard.it.SchemaAndContextStartupIT.txt`) :
`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`.

Résultats de la suite complète (`clean verify`) : `backend/target/surefire-reports/` = 255 tests, 0 échec ;
`backend/target/failsafe-reports/` = 14 tests, 0 échec.

**Couverture JaCoCo (corrigée le 2026-09-26, voir section 7 pour la cause de la correction)** — mesure
**unitaire uniquement** (`backend/target/jacoco.exec`, alimenté par surefire seul depuis la correction du
`pom.xml` séparant `jacoco.exec` de `jacoco-it.exec`), source `backend/target/site/jacoco/jacoco.csv`
(colonnes `LINE_COVERED` / `LINE_MISSED`), calcul `LINE_COVERED / (LINE_COVERED + LINE_MISSED)`, reproduit à
l'identique sur deux exécutions consécutives de `mvn -B -f backend/pom.xml clean verify` :
- `fr.backyard.domain` : 180/185 lignes = **97,30 %**
- `fr.backyard.service` : 294/297 lignes = **98,99 %**
- ensemble domain+service : 474/482 lignes = **98,34 %**

Seuil exigé (CLAUDE.md, couverture unitaire du cœur métier) : 80 % — largement dépassé. `jacoco:check` en
`BUILD SUCCESS`.

## 4. Échecs et bugs détectés

Aucun. Tous les tests (existants et nouveaux) sont passés dès la première exécution.

## 5. Modifications du patrimoine existant

Ajout uniquement, aucune modification ni suppression d'un test existant :
- `backend/src/test/java/fr/backyard/it/SchemaAndContextStartupIT.java` (nouveau, 4 méthodes de test).
- `backend/src/test/java/fr/backyard/it/support/AbstractApiIT.java` (nouveau, classe de base partagée par
  tous les tests `*IT` du rattrapage : contexte `@SpringBootTest` + `@AutoConfigureMockMvc`, horloge de test
  `MutableClock` réinitialisée avant chaque test, nettoyage des données créées après chaque test, helpers
  HTTP réutilisés par les tests des incréments 2 et 3).
- `docs/tests/PATRIMOINE.md` mis à jour (nouvelle section « Mode rattrapage » et matrice complète INC-1).

**Compléments du 2026-09-26 suite à la revue de cohérence du patrimoine** (aucun test modifié, uniquement
`docs/tests/PATRIMOINE.md`) :
- `persistence/RacePersistenceTest.java#ca23_raceAndRunnerSettersUpdateFields` était absent de la matrice ;
  ligne ajoutée (exigence `INC1-TECH1`), avec une note explicite : ce test exerce les setters de `Race` et
  `Runner` (couverture technique) et son nom `ca23_...` est un vestige historique, la spec `increment1.md` ne
  comptant que 22 CA (CA1 à CA22, pas de CA23). Statut à arbitrer par l'agent fonctionnel.
- `INC1-CA20` était rattaché, dans la matrice, à la classe entière `persistence/RacePersistenceTest.java`
  sans nom de méthode (aucune assertion dédiée n'y vérifie explicitement CA20). La ligne a été corrigée pour
  se rattacher aux méthodes qui le vérifient réellement
  (`it/SchemaAndContextStartupIT.java#fullContextStartsWithFlywayMigrationAndSchemaValidation` et
  `#productionProfileKeepsDdlAutoValidate`), `RacePersistenceTest.java` restant cité en note comme couverture
  implicite (si le schéma ne validait pas, son contexte `@DataJpaTest` ne démarrerait pas).

## 6. Tests instables ou en quarantaine

Aucun.

## 7. Risques et limites

- CA20 (validation du schéma) n'est vérifié qu'avec H2 en mode compatibilité PostgreSQL, jamais avec un vrai
  serveur PostgreSQL (Testcontainers non disponible dans cet environnement, pas de Docker). Un écart de
  dialecte SQL spécifique à PostgreSQL (ex. une contrainte `CHECK` mal supportée par H2) resterait invisible
  tant que ce test n'est pas rejoué contre un vrai PostgreSQL (recette d'infrastructure ou CI avec Docker).
- CL8/CL9 vérifient le comportement RESTRICT uniquement par des appels directs aux repositories (pas de
  chemin HTTP, puisque l'incrément 1 n'a pas de couche API) : c'est le niveau de test le plus adapté pour cet
  incrément.

**Correction du 2026-09-26 (suite à la revue de cohérence, constat INC1-C5-01, bloquant)** : la première
version de ce rapport citait une couverture JaCoCo de domain 94,13 %, service 98,83 %, ensemble 97,36 %,
chiffres que le relecteur n'a pas pu reproduire. **Cause identifiée** : avant correction du `pom.xml`, le
rapport JaCoCo (`jacoco:report`, exécuté en phase `test`, donc juste après les tests unitaires) et le
`jacoco:check` (exécuté en phase `verify`, donc après les tests `*IT` de failsafe) lisaient tous les deux le
même fichier `target/jacoco.exec` — un fichier que l'agent Java de couverture continue d'enrichir pendant
toute la durée du build, y compris pendant l'exécution des tests d'intégration en phase `integration-test`
(entre les phases `test` et `verify`). Le chiffre exact obtenu dépendait donc de l'instant précis où le
fichier `.exec` était lu, expliquant la non-reproductibilité observée par le relecteur (97,3 % à 98,8 %
selon le moment de la mesure). **Correction apportée par l'orchestrateur** : le `pom.xml` sépare désormais
l'agent JaCoCo des tests `*IT` dans un fichier dédié (`target/jacoco-it.exec`, exécution
`prepare-agent-integration`, propagé à failsafe via `argLine`), si bien que `target/jacoco.exec` — et donc le
rapport et le `check` — ne mesurent plus que les tests unitaires (surefire), conformément à la règle « couverture
unitaire ≥ 80 % » de CLAUDE.md. Les chiffres corrigés et reproductibles figurent en section 3. Le seuil de
80 % était déjà franchi dans les deux mesures (avant et après correction) : cette correction n'a donc aucune
conséquence sur le verdict de couverture, seulement sur la précision du chiffre rapporté.

## 8. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : **GO sous réserves** (mode rattrapage, incrément déjà mergé dans `main`)
- **Réserves ou motifs** :
  - Vérification CA par CA (spec `docs/specs/increment1.md`, 22 CA) : CA1 à CA19, CA21, CA22 ont chacun un test
    `ACTIF` dans `persistence/RacePersistenceTest.java`, relu méthode par méthode, avec des valeurs conformes à
    la spec. CA20 est vérifié par `it/SchemaAndContextStartupIT.java` (contexte complet + propriétés de
    production), mais sur H2 en mode PostgreSQL et non avec le profil de production sur PostgreSQL (voir RT1).
    CL8 et CL9 (hors CA) sont couverts par des tests `*IT` : accepté.
  - Résultats cités jugés réels : 269 tests (255 surefire + 14 failsafe), 0 échec, rejoués de façon indépendante
    par la revue de cohérence. Couverture unitaire domain+service 474/482 = 98,34 %, reproduite à l'identique
    après la séparation `jacoco.exec` / `jacoco-it.exec`. Le constat bloquant INC1-C5-01 est levé avec preuve.
    J'accepte la correction du `pom.xml` : elle renforce la règle de couverture **unitaire** de CLAUDE.md.
  - Aucun test supprimé, désactivé ni assoupli.
  - **Arbitrage INC1-TECH1** (`ca23_raceAndRunnerSettersUpdateFields`) : **accepté au patrimoine** comme test
    technique hors CA (couverture des setters, assertions réelles). Son nom laisse croire à un CA23 qui
    n'existe pas : renommage exigé (R1-1).
  - **R1-1** : renommer `ca23_raceAndRunnerSettersUpdateFields` en `tech_raceAndRunnerSettersUpdateFields`,
    sans toucher aux assertions, puis mettre à jour la ligne INC1-TECH1 de la matrice. Responsable : testeur.
    Échéance : avant la validation de l'INC-4.
  - **R1-2** : les tests `@DataJpaTest` relisent les entités depuis le cache de premier niveau (même
    transaction, sans `clear()`). La relecture « depuis la base » demandée par CA1, CA3, CA5, CA10, CA11, CA12
    et CA22 n'est donc pas réellement exercée, par exemple l'aller-retour `Instant` ↔ `TIMESTAMP WITH TIME
    ZONE` (PO2). Les `*IT` non transactionnels compensent en partie, sans le couvrir explicitement. De plus,
    CA21 accepte n'importe quelle `Exception` (`isInstanceOf(Exception.class)`). Action : insérer
    `flush()` + `clear()` avant chaque relecture et restreindre CA21 à l'exception de contrainte ou de
    validation attendue. Ce renforcement est autorisé par l'agent fonctionnel. Responsable : testeur.
    Échéance : avant la validation de l'INC-4.
  - **R1-3** (défaut de la spec, imputable à l'agent fonctionnel) : RG11, RG15 (immuabilité de `Passage`,
    respectée dans le code, qui n'a pas de setter), CL12 et les contraintes `CHECK` (RG1 `loop_* > 0`, RG6
    `bib > 0`, RG13 `yard_number >= 1`) n'ont pas de CA dédié. Action : addendum à `docs/specs/increment1.md`
    (nouveaux CA numérotés après le renommage R1-1), puis tests correspondants. Responsables : fonctionnel,
    puis testeur. Échéance : avant la validation de l'INC-4.
  - **RT1** (transverse, avec INC-3) : aucune validation sur un vrai PostgreSQL. Le schéma Flyway 12 /
    Hibernate 7 (Spring Boot 4.1.1) n'a jamais été validé contre PostgreSQL, alors que la production tourne sur
    PostgreSQL (VPS OVH). Le dialecte H2 ne garantit ni la validation de schéma (types `TIMESTAMP WITH TIME
    ZONE`, identity, enums en `VARCHAR`) ni la traduction des violations de contraintes. Ce n'est pas un motif
    de NO-GO : la spec (section 1, PO7) et CLAUDE.md autorisent H2, et `flyway-database-postgresql` ainsi que le
    pilote `postgresql` sont bien déclarés dans le `pom.xml`. En revanche, c'est une **réserve obligatoire**.
    Action : rejouer la suite `*IT` complète et un démarrage avec le profil `prod` (Flyway V1 +
    `ddl-auto=validate`) contre un PostgreSQL de même version majeure que celui du VPS (Testcontainers en CI,
    ou base de recette), avec résultats consignés dans un rapport. Responsables : test-integration-backend
    et orchestrateur (fourniture de l'environnement). Échéance : **avant tout déploiement sur le VPS**, et au
    plus tard comme condition du GO de l'INC-4.
  - **RT3** (transverse) : le critère « compile sans warning » de la définition de « fini » n'est attesté par
    aucun des rapports de rattrapage. Le `pom.xml` affiche les warnings (`-Xlint:all`), mais sans les rendre
    bloquants. Je m'appuie, pour INC-1 à INC-3, sur le verdict technique OK rendu à la livraison. Action : le
    rapport de synthèse de l'INC-4 cite la sortie du compilateur (0 warning), ou le build les rend bloquants.
    Échéance : validation de l'INC-4.
  - **RT4** (transverse) : les `*IT`, la correction JaCoCo du `pom.xml`, le patrimoine et les rapports ne sont
    pas encore commités (commit testé : `c491bb6` + working tree). Action : publication de la branche
    `chore/workflow-validation` (MR), relue et mergée par l'humain. Échéance : avant le démarrage de l'INC-4.
- **Actions correctives exigées** : aucune avant GO. Les réserves R1-1, R1-2, R1-3, RT1, RT3 et RT4 sont
  suivies dans « Écarts ouverts » de `docs/tests/PATRIMOINE.md`.
- **Date** : 2026-09-26 (agent fonctionnel)
