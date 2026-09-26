# Rapport de test : INC-2 (intégration, mode rattrapage)

- **Date** : 2026-09-26
- **Agent auteur** : test-integration-backend
- **Version / commit testé** : c491bb6 (branche `chore/workflow-validation`), + tests `*IT` ajoutés par ce rattrapage (non commités au moment du test)
- **Environnement** : profil Spring `test`, base H2 en mode PostgreSQL, schéma Flyway `V1__init.sql`, planificateur désactivé (`backyard.scheduling.enabled=false`), horloge de test contrôlée (`MutableClock`, bean `@Primary`).

## 1. Périmètre

Mode rattrapage : les 62 critères d'acceptation (CA1 à CA62) de `docs/specs/increment2.md` sont déjà
couverts par des tests unitaires JUnit 5 + Mockito (`Clock` fixe, repositories mockés) : `YardCalculatorTest`,
`RunnerStatsCalculatorTest`, `PassageRecordingServiceTest`, `YardClosingServiceTest`, `ManualDnfServiceTest`,
`ReintegrationServiceTest`, `AutomaticReactivationTest`, `StateTransitionsTest` (voir la cartographie complète
dans `docs/tests/PATRIMOINE.md`).

Aucun de ces tests n'exécute la logique métier de l'incrément 2 contre une vraie base de données ni à travers
la chaîne HTTP de l'incrément 3. Ce rapport ajoute un scénario de bout en bout demandé explicitement par
l'orchestrateur : création de course → inscription → démarrage → scan → clôture de yard par
`YardClosingService` avec une `Clock` de test (auto-DNF réel) → réintégration admin → tableau de bord, le
tout avec un contexte Spring complet (sécurité réelle, services réels, persistance réelle H2 via Flyway).

Nouveau test ajouté : `backend/src/test/java/fr/backyard/it/EndToEndRaceLifecycleIT.java` (1 méthode, tagué
`INC-2`, `INC-3`, `INC2-CA27`, `INC2-CA44`, `INC3-CA19`, `INC3-CA32`, `INC3-CA37`, `INC3-CA40`).

## 2. Couverture exigences ↔ tests

| Exigence | Critère d'acceptation | Tests | Résultat | Écart ? |
|---|---|---|---|---|
| INC2-CA1 à INC2-CA62 | Calculs de yard, statistiques dérivées, scan, clôture de yard, DNF manuel, réintégration, réactivation automatique (détail complet dans `docs/tests/PATRIMOINE.md`) | Tests unitaires existants (`domain/YardCalculatorTest.java`, `domain/RunnerStatsCalculatorTest.java`, `domain/StateTransitionsTest.java`, `service/PassageRecordingServiceTest.java`, `service/YardClosingServiceTest.java`, `service/ManualDnfServiceTest.java`, `service/ReintegrationServiceTest.java`, `service/AutomaticReactivationTest.java`) | PASS | Non |
| INC2-CA27 (auto-DNF à la bascule exacte) | Auto-DNF, rejoué avec un service réel et une vraie base | **Nouveau** `it/EndToEndRaceLifecycleIT.java#raceLifecycleFromCreationToDashboardAfterAutoDnfAndReintegration` : `YardClosingService.closeYard(race)` appelé directement (bean réel autowiré), avec une `Clock` de test avancée à la bascule du yard 1, contre H2 réel | PASS | Non (comble l'absence d'exercice de `YardClosingService` contre une vraie persistance) |
| INC2-CA44 (réintégration nominale) | Réintégration, rejouée en persistance réelle via l'API admin | **Nouveau** même test, étape réintégration (`POST /api/admin/runners/{id}/reintegration`) | PASS | Non |

Exigences sans test : aucune.

## 3. Résultats d'exécution (réels)

| Suite | Total | Passés | Échoués | Ignorés | Durée |
|---|---|---|---|---|---|
| Tests de l'incrément (`-Dgroups=INC-2`) | 1 | 1 | 0 | 0 | 5.81 s |
| Non-régression (suite complète, `clean verify`) | 269 (255 unitaires + 14 intégration) | 269 | 0 | 0 | 32.78 s |

Commande(s) exécutée(s) :
```
mvn -B -f backend/pom.xml verify -Dgroups=INC-2 -Djacoco.skip=true
mvn -B -f backend/pom.xml clean verify
```

Résultat détaillé (`backend/target/failsafe-reports/fr.backyard.it.EndToEndRaceLifecycleIT.txt`) :
`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.455 s` (lors du `clean verify` complet).

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

Aucun. Le scénario de bout en bout est passé dès la première exécution, ce qui confirme que la logique
métier de l'incrément 2 (déjà validée en isolation par les tests unitaires) se comporte correctement une
fois branchée sur une vraie base H2 et sur les services de l'incrément 3 (aucune divergence entre le
comportement mocké et le comportement réel n'a été détectée : ordre de sauvegarde, arrondi de l'allure,
plage de réintégration, badge « corrigé »).

## 5. Modifications du patrimoine existant

Ajout uniquement, aucune modification ni suppression d'un test existant :
- `backend/src/test/java/fr/backyard/it/EndToEndRaceLifecycleIT.java` (nouveau).
- Réutilisation de `backend/src/test/java/fr/backyard/it/support/AbstractApiIT.java` (classe de base
  introduite lors du rattrapage INC-1, voir `INC-1-integration.md`).
- `docs/tests/PATRIMOINE.md` mis à jour (matrice complète INC-2).

**Complément du 2026-09-26 suite à la revue de cohérence du patrimoine** (aucun test modifié, uniquement
`docs/tests/PATRIMOINE.md`) : trois méthodes de `domain/StateTransitionsTest.java`
(`rg29_markDnf`, `rg29_markWinner`, `rg29_raceFinish`) étaient absentes de la matrice, alors qu'elles
vérifient au niveau domaine pur les transitions `Runner.markDnf` (CA40) et `Runner.markWinner` /
`Race.finish` (CA33), en complément des tests de service déjà cités. Trois lignes ajoutées
(`INC2-CA40 (RG29, domaine)`, `INC2-CA33 (RG29, domaine)` ×2).

## 6. Tests instables ou en quarantaine

Aucun. Le scénario multi-étapes utilise exclusivement une horloge de test contrôlée (`MutableClock`, aucun
`sleep`) et des données isolées (course nommée de façon unique, nettoyée en fin de test) : aucune source de
non-déterminisme identifiée.

## 7. Risques et limites

- Le scénario de bout en bout couvre un seul chemin représentatif (3 coureurs, un DNF timeout, une
  réintégration, une course qui reste `RUNNING`). Il ne rejoue pas l'exhaustivité des 62 CA de la spec contre
  une vraie base : ce choix est volontaire (mode rattrapage, effort proportionné), les 62 CA restant
  entièrement couverts par les tests unitaires existants dont la fiabilité n'est pas remise en cause.
- La clôture de yard est appelée directement (`YardClosingService.closeYard(race)`), pas via le planificateur
  `@Scheduled` (désactivé en test, RG17 inc. 2) : conforme à la définition de l'agent et à la spec (RG25 inc.
  3 : aucun endpoint n'expose la clôture manuelle).
- Concurrence scan / clôture (PO17 inc. 2, PO6 inc. 3) : explicitement hors périmètre de la spec, non testée.

**Correction du 2026-09-26 (suite à la revue de cohérence, constat INC2-C5-01, bloquant, identique à
INC1-C5-01)** : la première version de ce rapport citait une couverture JaCoCo de domain 94,13 %, service
98,83 %, ensemble 97,36 %, chiffres que le relecteur n'a pas pu reproduire. **Cause identifiée** : avant
correction du `pom.xml`, le rapport JaCoCo (phase `test`, juste après les tests unitaires) et le
`jacoco:check` (phase `verify`, après les tests `*IT` de failsafe) lisaient tous les deux le même fichier
`target/jacoco.exec`, que l'agent de couverture continue d'enrichir pendant toute la durée du build — y
compris pendant les tests d'intégration (phase `integration-test`, entre `test` et `verify`). Le chiffre
exact dépendait donc de l'instant précis de lecture du fichier `.exec`. **Correction apportée par
l'orchestrateur** : `target/jacoco-it.exec` dédié aux tests `*IT` (exécution `prepare-agent-integration` de
JaCoCo, propagée à failsafe via `argLine`), si bien que `target/jacoco.exec` — et donc le rapport et le
`check` — ne mesurent plus que les tests unitaires, conformément à CLAUDE.md. Chiffres corrigés et
reproductibles en section 3. Le seuil de 80 % était déjà franchi avant et après correction : aucune
conséquence sur le verdict de couverture, seulement sur la précision du chiffre rapporté.

## 8. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : **GO sous réserves** (mode rattrapage, incrément déjà mergé dans `main`)
- **Réserves ou motifs** :
  - Vérification CA par CA (spec `docs/specs/increment2.md`, 62 CA) : les intitulés de la matrice
    correspondent un à un aux CA de la spec. Chaque CA a au moins un test unitaire `ACTIF`. J'ai relu en détail
    CA10, CA11, CA12, CA27, CA31, CA32, CA33, CA39, CA44, CA48, CA50, CA52, CA56, CA59 et CA62 : les valeurs
    chiffrées de la spec (allures 447 / 541 / 403 / 425 / 461, distances 20 118 / 26 824 m, `dnfYard`, message
    contenant « réintégration »…) sont assertées exactement. Le calcul dérivé reste pur et l'auto-DNF ne
    contrôle que le yard N, conformément à CLAUDE.md. CA27 et CA44 sont en plus rejoués en persistance et HTTP
    réels (`EndToEndRaceLifecycleIT`).
  - Résultats cités jugés réels : 269/269, et 1/1 pour `-Dgroups=INC-2`. Couverture unitaire domain 97,30 %,
    service 98,99 %, reproduite. Le constat bloquant INC2-C5-01 est levé avec preuve.
  - Tests `rg29_markDnf`, `rg29_markWinner` et `rg29_raceFinish` : acceptés au patrimoine comme compléments de
    domaine de CA40 et CA33 (pas de doublon). Aucun test supprimé, désactivé ni assoupli.
  - **R2-1** : certaines assertions ne couvrent pas tout l'« Alors » du CA.
    - `ca31_parallelRacesAreClosedIndependently` n'asserte ni R1 `FINISHED` **sans vainqueur**, ni
      `raceFinished = true` et `timedOutRunnerIds = [X]` pour R1, ni R2 `RUNNING` avec
      `raceFinished = false`.
    - `ca32_failureOnOneRaceDoesNotBlockOthers` n'asserte pas R2 `RUNNING`.
    - `ca48_noReDnfOnRecreatedYards` n'asserte ni A et C `ACTIVE`, ni R1 `RUNNING`, ni `timedOutRunnerIds`
      vide à la première clôture.

    Ces comportements sont couverts ailleurs (CA35 pour « aucun finisher → FINISHED sans vainqueur », CA34
    pour « deux finishers → RUNNING »). Il n'y a donc pas de trou fonctionnel, mais la vérification de ces CA
    est incomplète. Action : compléter ces assertions. Il s'agit d'un renforcement, autorisé par l'agent
    fonctionnel. Responsable : testeur. Échéance : avant la validation de l'INC-4.
  - **RT1** (transverse) : le scénario `EndToEndRaceLifecycleIT` fait partie de la suite `*IT` à rejouer
    contre un vrai PostgreSQL (voir `INC-1-integration.md`). La logique de l'INC-2 est indépendante de la base
    et n'est pas directement exposée à ce risque. Échéance : avant tout déploiement sur le VPS.
  - **RT3**, **RT4** (transverses) : voir `INC-1-integration.md`.
  - Limite acceptée : la concurrence scan / clôture (PO17 inc. 2, PO6 inc. 3) reste hors périmètre, par
    décision antérieure de l'utilisateur.
- **Actions correctives exigées** : aucune avant GO. R2-1 est suivie dans « Écarts ouverts » de
  `docs/tests/PATRIMOINE.md`.
- **Date** : 2026-09-26 (agent fonctionnel)
