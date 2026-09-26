# Rapport de revue de cohérence du patrimoine : INC-1

- **Date** : 2026-09-26
- **Agent auteur** : revue-coherence-patrimoine
- **Périmètre** : incrément 1 (Domaine + Persistance), en mode rattrapage
- **Référence de comparaison (C3)** : main (commit c491bb6), comparé au working tree de chore/workflow-validation via `git diff main` / `git status --untracked-files=all`
- **Entrées relues** : `CLAUDE.md`, `docs/tests/PATRIMOINE.md`, `docs/tests/rapports/INC-1-integration.md`, `docs/tests/rapports/INC-1-e2e.md`, `docs/specs/increment1.md`, code de test sous `backend/src/test/java/fr/backyard/`, historique git
- **Suite rejouée** : `mvn -B -f backend/pom.xml clean verify` (une exécution, valable pour les 3 incréments) + `mvn -B -f backend/pom.xml verify -Dgroups=INC-1 -Djacoco.skip=true`

## 1. Synthèse du premier passage (historique - statut à jour en section 6)

- **Bloquant** : 1
- **Majeur** : 1
- **Mineur** : 1
- **Contrôles sans constat** : C1 (hors nuance mineure), C3, C4, C6
- **Recommandation** : non prêt (1 constat bloquant au sens strict de C5), avec une nuance importante détaillée en section 5.

## 2. Tableau des constats

| ID | Contrôle | Sévérité | Description | Preuve | Action recommandée |
|---|---|---|---|---|---|
| INC1-C5-01 | C5 | Bloquant | Le rapport `INC-1-integration.md` cite une couverture JaCoCo (domain+service) de 97,36 % pour la suite complète. En rejouant `mvn -B -f backend/pom.xml clean verify` une fois (build réel confirmé : BUILD SUCCESS, 269 tests dont 255 surefire + 14 failsafe, 0 échec, ce qui correspond exactement au rapport), le fichier `backend/target/site/jacoco/jacoco.csv` généré donne un ratio différent : domain+service = 474/482 lignes = 98,34 % (domain seul 180/185 = 97,30 %, service seul 294/297 = 98,99 %). Une régénération du rapport à partir des mêmes données (`mvn -o jacoco:report`) donne un troisième chiffre (476/482 = 98,76 %), ce qui montre que le pourcentage exact affiché n'est pas parfaitement reproductible dans cet environnement, mais qu'il est systématiquement supérieur aux chiffres cités dans les rapports (94,13 % domain / 98,83 % service / 97,36 % ensemble, mêmes chiffres cités dans INC-2 et INC-3). Le seuil de 80 % exigé par CLAUDE.md est franchi dans toutes les mesures (jacoco:check en BUILD SUCCESS à chaque fois), donc pas de risque de faux-positif sur le seuil, mais le chiffre précis annoncé n'a pas pu être reproduit à l'identique. | `backend/target/site/jacoco/jacoco.csv` (généré le 2026-09-26 par ma propre exécution) ; `docs/tests/rapports/INC-1-integration.md` section 3 ; `docs/tests/rapports/INC-2-integration.md` section 3 ; `docs/tests/rapports/INC-3-integration.md` section 3 ; sortie de build : All coverage checks have been met / BUILD SUCCESS | L'agent fonctionnel arbitre : soit il documente cet écart de précision comme acceptable (seuil de 80 % largement dépassé dans les deux mesures), soit il demande à test-integration-backend de rejouer la mesure dans un run isolé et de corriger le chiffre exact et sa méthode de calcul dans les 3 rapports. |
| INC1-C2-01 | C2 | Majeur | `backend/src/test/java/fr/backyard/persistence/RacePersistenceTest.java` contient une méthode `ca23_raceAndRunnerSettersUpdateFields` (section commentée CA23 - Setters Race et Runner exerces, couverture JaCoCo) qui n'est référencée nulle part dans `docs/tests/PATRIMOINE.md`, alors que la spec `docs/specs/increment1.md` ne compte que 22 CA (CA1 à CA22) et que toutes les autres méthodes caN de ce même fichier sont citées individuellement dans la matrice. C'est un test présent dans le code, non tagué (normal pour un test antérieur au workflow) mais aussi non référencé par chemin/nom comme l'exige la règle 6 de PATRIMOINE.md pour les tests des incréments 1 à 3. | `backend/src/test/java/fr/backyard/persistence/RacePersistenceTest.java` lignes 296-314 ; recherche exhaustive confirmant l'absence de toute mention dans `docs/tests/PATRIMOINE.md` | Ajouter une ligne dans la matrice (section incrément 1) référençant ce test, avec une note honnête (test de couverture des setters, non rattaché à un CA numéroté de la spec), comme cela a déjà été fait pour CL8/CL9. |
| INC1-C1-01 | C1 | Mineur | INC1-CA20 est rattaché dans la matrice à `persistence/RacePersistenceTest.java` (slice @DataJpaTest, profil test) sans nom de méthode : aucune méthode de cette classe n'assert explicitement la validation du schéma (pas de méthode ca20 dans ce fichier). La vérification est un effet de bord implicite du démarrage du contexte @DataJpaTest (si Hibernate ne validait pas le schéma, tous les tests de la classe échoueraient au chargement du contexte), pas une assertion dédiée. La spec CA20 demande explicitement une vérification du démarrage sans erreur de validation de schéma. | `docs/tests/PATRIMOINE.md` (ligne INC1-CA20) ; `docs/specs/increment1.md` (section CA20) ; absence de méthode ca20 dans `RacePersistenceTest.java` | Déjà atténué par le rattrapage : `it/SchemaAndContextStartupIT.java#fullContextStartsWithFlywayMigrationAndSchemaValidation` (contexte complet, assertion explicite) et `#productionProfileKeepsDdlAutoValidate` comblent le manque d'assertion dédiée. Aucune action supplémentaire nécessaire, sinon clarifier dans la matrice que cette ligne est une couverture implicite. |

## 3. Contrôles sans constat

- C1 (hormis la nuance mineure ci-dessus sur CA20) : les 22 CA de `docs/specs/increment1.md` ont chacun au moins un test ACTIF avec des assertions pertinentes (vérifié en détail sur CA1 à CA22, CL8, CL9). Le nombre de CA de la matrice (22 lignes + 1 complément CA20 + 2 CL) correspond exactement au nombre de CA de la spec (22, confirmé par comptage direct des occurrences dans le fichier) : aucun CA oublié, aucun CA inventé.
- C3 : `git diff main -- backend/src/main --stat` et `git diff main -- backend/src/test --stat` (hors `backend/src/test/java/fr/backyard/it/`) ne montrent aucune modification. Seuls ajouts par rapport à main : les tests `it/` (nouveau), `backend/pom.xml` (ajout du plugin maven-failsafe-plugin, sans configuration additionnelle, conforme à l'attendu), `.claude/`, `CLAUDE.md`, `docs/tests/`. Aucun test existant supprimé, désactivé ou assoupli.
- C4 : `it/SchemaAndContextStartupIT.java` et `it/support/AbstractApiIT.java` ont des assertions réelles, pas de sleep, données isolées et nettoyées (@AfterEach cleanUpCreatedRaces, identifiants uniques suffixés par l'id de la course ou l'instant courant), horloge de test contrôlée (MutableClock). Pas de test en quarantaine (section correspondante de PATRIMOINE.md vide, cohérent avec le code).
- C6 : sans objet pour INC-1 (pas d'incrément antérieur).

## 4. Limites

- Je n'ai pas pu isoler la cause exacte de la non-reproductibilité du pourcentage JaCoCo exact (deux régénérations successives dans le même environnement donnent 98,34 % puis 98,76 %) : cela peut venir de la configuration multi-exécutions du plugin (report lié à la phase test, check lié à la phase verify, donc pas nécessairement les mêmes données accumulées au même instant) plutôt que d'une erreur volontaire du rapport de test-integration-backend. Je livre le constat brut ; le diagnostic fin de la chaîne de build JaCoCo revient à l'agent fonctionnel ou au développeur.
- Je n'ai pas pu tester contre un vrai PostgreSQL (pas de Docker/Testcontainers disponible dans cet environnement) : je ne peux ni confirmer ni infirmer la limite déjà documentée par test-integration-backend sur CA20 (H2 en mode PostgreSQL uniquement).
- Revue de code exhaustive méthode par méthode faite pour CA1 à CA22, CL8, CL9 et le test orphelin détecté ; pas relu ligne à ligne l'intégralité du fichier RacePersistenceTest.java au-delà des zones citées par la matrice et d'une recherche exhaustive des noms de méthodes de test dans tout l'arbre de test.

## 5. Recommandation à l'issue du premier passage (historique - voir section 7 pour la mise à jour)

Non prêt, au sens strict de la règle C5. Le chiffre de couverture JaCoCo cité (97,36 %) n'a pas pu être reproduit à l'identique (mesures réelles obtenues : 97,3 % à 98,8 % selon la méthode de régénération, toujours au-dessus du seuil de 80 % et jamais en dessous des chiffres cités dans les rapports). Ce n'est pas un verdict : c'est à l'agent fonctionnel de juger si cet écart de précision, sans conséquence sur le franchissement du seuil de 80 %, justifie une correction des rapports avant GO ou une simple réserve documentée.

Le second constat (test orphelin CA23 dans RacePersistenceTest.java) est un écart de tenue de la matrice, facilement comblé par l'ajout d'une ligne, sans remise en cause de la qualité du test lui-même.

## 6. Revue 2 (2026-09-26)

### Statut des constats du premier passage

| ID | Statut | Preuve |
|---|---|---|
| INC1-C5-01 (Bloquant, couverture JaCoCo) | **Levé** | Cause racine confirmée : `backend/pom.xml` séparait mal la couverture unitaire de la couverture d'intégration (`target/jacoco.exec` unique, lu à la fois par `report` en phase test et `check` en phase verify, ce dernier accumulant en plus la couverture des `*IT` exécutés entre les deux phases). L'orchestrateur a ajouté une exécution `prepare-agent-integration` dédiée (propriété `failsafeArgLine`, propagée à failsafe via `<argLine>@{failsafeArgLine}</argLine>`), qui écrit désormais la couverture des `*IT` dans `target/jacoco-it.exec` séparé. Rejoué `mvn -B -f backend/pom.xml clean verify` : `target/jacoco.exec` (634 820 octets) et `target/jacoco-it.exec` (613 537 octets) coexistent bien comme deux fichiers distincts. Le `jacoco.csv` régénéré donne domain 180/185 = 97,30 %, service 294/297 = 98,99 %, ensemble 474/482 = 98,34 % — **identique** aux chiffres désormais cités dans `INC-1-integration.md` section 3. Rejoué une seconde fois `mvn -o jacoco:report` (sans rebuild) sur le même `jacoco.exec` : chiffre strictement identique (98,34 %), confirmant la reproductibilité. Le log de `jacoco:check` confirme explicitement `Loading execution data file ...\target\jacoco.exec` (le fichier unitaire seul). |
| INC1-C2-01 (Majeur, test orphelin CA23) | **Levé** | `docs/tests/PATRIMOINE.md` contient désormais la ligne `INC1-TECH1` référençant `persistence/RacePersistenceTest.java#ca23_raceAndRunnerSettersUpdateFields`, avec une note honnête expliquant que ce nom est un vestige historique (pas de CA23 dans la spec) et un statut « à arbitrer par l'agent fonctionnel » (conserver ou renommer). Le test est désormais tracé, ce qui lève l'écart de cohérence C2 ; la question de fond (conserver le nom `ca23_...` ou le renommer) reste une décision fonctionnelle, pas un écart de patrimoine. |
| INC1-C1-01 (Mineur, CA20 rattaché à la classe entière) | **Levé** | `docs/tests/PATRIMOINE.md` ligne INC1-CA20 rattache désormais explicitement `it/SchemaAndContextStartupIT.java#fullContextStartsWithFlywayMigrationAndSchemaValidation` et `#productionProfileKeepsDdlAutoValidate`, avec `RacePersistenceTest.java` gardé en note de couverture implicite complémentaire (transparence conservée, pas de perte d'information). |

### Vérification indépendante du chiffre « 258/258 »

Extraction indépendante, sans script fourni par l'agent d'intégration : décompte de toutes les annotations `@Test`/`@ParameterizedTest` dans `backend/src/test/java/fr/backyard/` (249 `@Test` + 9 `@ParameterizedTest` = **258**), puis liste de toutes les méthodes `void nomDeMethode(` du même arbre (265), moins les 7 méthodes utilitaires qui ne sont pas des tests (`assertNoPassageDeletion`, `assertNoWrite`, `cleanUpCreatedRaces`, `clearWriteLog`, `resetClock`, `servicesReturnValidResults` (`@BeforeEach`), `set`) = **258**, cohérent avec le premier décompte. Comparaison exhaustive de ces 258 noms de méthode contre toutes les références `#nomDeMethode` de `docs/tests/PATRIMOINE.md` (258 références uniques trouvées) : correspondance bijective exacte, aucune méthode de test non référencée, aucune référence pointant vers une méthode inexistante. **L'annonce « 258/258 » est confirmée de façon indépendante.**

### C3 — Effet de la modification JaCoCo sur la règle de couverture de CLAUDE.md

`CLAUDE.md` exige explicitement une « couverture **unitaire** >= 80 % sur le cœur métier ». Avant correction, le `jacoco:check` pouvait mesurer un mélange couverture unitaire + couverture apportée par les tests `*IT` (selon l'instant de lecture du fichier `.exec` partagé), ce qui aurait pu, dans d'autres circonstances, masquer une couverture unitaire réellement insuffisante derrière une couverture d'intégration suffisante. La séparation `jacoco.exec` (unitaire, surefire) / `jacoco-it.exec` (intégration, failsafe) fait que `jacoco:check` ne mesure plus que la couverture unitaire réelle, strictement conforme au texte de `CLAUDE.md`. **Cette modification renforce la règle, elle ne l'affaiblit pas.** Le reste de la modification de `backend/pom.xml` (déclaration du plugin `maven-failsafe-plugin` avec `argLine`) est cohérent avec l'objectif déjà validé en premier passage (exécution des `*IT` en phase `integration-test`).

## 7. Recommandation finale (Revue 2)

**Prêt pour arbitrage.** Les 3 constats du premier passage (1 bloquant, 1 majeur, 1 mineur) sont tous levés, avec preuve reproductible. Aucun nouveau constat bloquant ou majeur identifié lors de ce second passage. Ce n'est pas un verdict : le GO / GO sous réserves / NO-GO reste à l'agent fonctionnel.

## 8. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : **GO sous réserves** (verdict unique de l'incrément, détaillé dans `INC-1-integration.md`, section 8)
- **Réserves ou motifs** :
  - INC1-C5-01 (bloquant) : **levée acceptée**. La cause racine est identifiée, le `pom.xml` est corrigé et
    les chiffres (98,34 %) sont reproduits deux fois à l'identique. La correction renforce la règle de
    couverture unitaire de CLAUDE.md. Aucun constat bloquant ne reste ouvert.
  - INC1-C2-01 (majeur) : levée acceptée. Test INC1-TECH1 accepté au patrimoine, avec renommage exigé (R1-1).
  - INC1-C1-01 (mineur) : levée acceptée. CA20 est bien rattaché aux méthodes de `SchemaAndContextStartupIT`,
    avec la limite H2 reprise en réserve RT1.
  - Constat supplémentaire de l'agent fonctionnel (hors revue) : relecture depuis le cache de premier niveau
    dans `RacePersistenceTest` et assertion trop large pour CA21 (R1-2).
  - Réserves : R1-1, R1-2, R1-3, RT1, RT3, RT4 (voir `INC-1-integration.md`).
- **Actions correctives exigées** : aucune avant GO.
- **Date** : 2026-09-26 (agent fonctionnel)
