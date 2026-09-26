# Rapport de revue de cohérence du patrimoine : INC-2

- **Date** : 2026-09-26
- **Agent auteur** : revue-coherence-patrimoine
- **Périmètre** : incrément 2 (Logique métier core), en mode rattrapage
- **Référence de comparaison (C3)** : main (commit c491bb6), comparé au working tree de chore/workflow-validation via `git diff main` / `git status --untracked-files=all`
- **Entrées relues** : `CLAUDE.md`, `docs/tests/PATRIMOINE.md`, `docs/tests/rapports/INC-2-integration.md`, `docs/tests/rapports/INC-2-e2e.md`, `docs/specs/increment2.md`, code de test sous `backend/src/test/java/fr/backyard/`, historique git
- **Suite rejouée** : `mvn -B -f backend/pom.xml clean verify` (une exécution, valable pour les 3 incréments) + `mvn -B -f backend/pom.xml verify -Dgroups=INC-2 -Djacoco.skip=true`

## 1. Synthèse du premier passage (historique - statut à jour en section 6)

- **Bloquant** : 1
- **Majeur** : 1
- **Mineur** : 0
- **Contrôles sans constat** : C1, C3, C4, C6
- **Recommandation** : non prêt (1 constat bloquant, hérité du même écart de mesure JaCoCo que INC-1), avec la même nuance : le seuil de 80 % reste largement dépassé.

## 2. Tableau des constats

| ID | Contrôle | Sévérité | Description | Preuve | Action recommandée |
|---|---|---|---|---|---|
| INC2-C5-01 | C5 | Bloquant | Le rapport `INC-2-integration.md` cite, pour la suite complète, une couverture JaCoCo domaine 94,13 %, service 98,83 %, ensemble 97,36 %. En rejouant `mvn -B -f backend/pom.xml clean verify` une fois (BUILD SUCCESS, 269 tests dont 255 surefire + 14 failsafe, 0 échec, chiffres de tests identiques au rapport), le fichier `backend/target/site/jacoco/jacoco.csv` généré donne : domaine 180/185 lignes = 97,30 %, service 294/297 = 98,99 %, ensemble 474/482 = 98,34 %. Une régénération du rapport à partir des mêmes données donne un quatrième jeu de chiffres (476/482 = 98,76 % pour l'ensemble), ce qui confirme que le chiffre exact n'est pas parfaitement reproductible dans cet environnement, mais reste systématiquement au-dessus des chiffres cités dans le rapport, y compris pour la répartition domaine/service. Ce même écart est documenté en détail dans `INC-1-coherence.md` (constat INC1-C5-01) ; il n'est pas propre à l'incrément 2 mais affecte les trois rapports d'intégration qui citent les mêmes chiffres. Le seuil de 80 % exigé est franchi dans toutes les mesures (jacoco:check en BUILD SUCCESS à chaque fois). | `backend/target/site/jacoco/jacoco.csv` (généré le 2026-09-26) ; `docs/tests/rapports/INC-2-integration.md` section 3 ; `docs/tests/rapports/INC-1-coherence.md` constat INC1-C5-01 | Même action que INC1-C5-01 : l'agent fonctionnel arbitre si cet écart de précision (sans conséquence sur le franchissement du seuil) doit être corrigé avant GO ou simplement noté en réserve. Une correction unique, dans les 3 rapports d'intégration à la fois, est recommandée si l'agent fonctionnel choisit de la demander. |
| INC2-C2-01 | C2 | Majeur | `backend/src/test/java/fr/backyard/domain/StateTransitionsTest.java` contient trois méthodes de test (`rg29_markDnf`, `rg29_markWinner`, `rg29_raceFinish`) qui vérifient au niveau domaine des transitions déjà rattachées à CA40 (DNF manuel) et CA33 (vainqueur) via leur @DisplayName ("CA40 / RG29 - markDnf...", "CA33 / RG29 - markWinner...", "CA33 / RG29 - Race.finish()..."), mais qui ne sont référencées nulle part dans `docs/tests/PATRIMOINE.md` : la matrice ne cite, pour ce fichier, que les méthodes `ca61_...` (INC2-CA61). Ce sont des tests réels avec des assertions pertinentes (pas des doublons triviaux : ils testent la transition de domaine pure, indépendamment du service), mais non tracés dans le patrimoine. | `backend/src/test/java/fr/backyard/domain/StateTransitionsTest.java` lignes 52-82 ; `docs/tests/PATRIMOINE.md` (ligne INC2-CA61, seule référence à ce fichier) ; recherche exhaustive confirmant l'absence des trois noms de méthode dans la matrice | Ajouter ces trois méthodes à la matrice, en complément de service/ManualDnfServiceTest.java (CA40) et service/YardClosingServiceTest.java (CA33), avec une note précisant qu'il s'agit d'une vérification complémentaire au niveau domaine pur (Runner.markDnf, Runner.markWinner, Race.finish), pas d'un doublon inutile. |

## 3. Contrôles sans constat

- C1 : les 62 CA de `docs/specs/increment2.md` (CA1 à CA62, confirmé par comptage direct des occurrences dans le fichier) ont chacun au moins un test ACTIF avec des assertions pertinentes. Échantillon vérifié en détail : YardCalculatorTest (CA1-CA8), RunnerStatsCalculatorTest (CA9-CA16, CA50, CA59), PassageRecordingServiceTest (CA17-CA25), YardClosingServiceTest (CA26-CA39, revue exhaustive demandée pour l'auto-DNF : assertions réelles sur le statut, la raison, le yard, le compteur d'écriture du repository, pas de trivialité), ManualDnfServiceTest (CA40-CA43), ReintegrationServiceTest (CA44-CA51), AutomaticReactivationTest (CA52-CA62), StateTransitionsTest (CA61). Le scénario de bout en bout `it/EndToEndRaceLifecycleIT.java` rejoue CA27 et CA44 avec un service réel et une vraie base H2, avec des assertions détaillées (statuts, badge corrigé, allure, temps de boucle) et non de simples vérifications d'absence d'erreur.
- C3 : aucune modification de `backend/src/main` ni d'un test existant par rapport à main. Seul ajout : `it/EndToEndRaceLifecycleIT.java` (nouveau), réutilisant `it/support/AbstractApiIT.java` introduit lors du rattrapage INC-1.
- C4 : `it/EndToEndRaceLifecycleIT.java` utilise exclusivement une horloge de test contrôlée (MutableClock, aucun sleep), des données isolées (course nommée de façon unique, nettoyée en fin de test via trackRaceForCleanup), pas de dépendance à l'ordre d'exécution d'autres tests. Pas de test en quarantaine.
- C6 : les tests de persistance de l'incrément 1 (RacePersistenceTest, it/SchemaAndContextStartupIT) continuent de passer dans la suite complète rejouée (269/269, 0 échec) : pas de régression détectée sur les parcours de l'incrément 1.

## 4. Limites

- Même limite que INC-1 concernant la non-reproductibilité exacte du pourcentage JaCoCo affiché : je ne peux pas trancher si l'écart vient de la chaîne de build (timing des phases report/check) ou d'une mesure ponctuelle différente lors de la rédaction du rapport initial.
- Je n'ai pas rejoué individuellement, contre une vraie base, les 62 CA de l'incrément 2 : seul le scénario de bout en bout (`it/EndToEndRaceLifecycleIT.java`) l'a été, ce qui est le choix assumé et documenté par test-integration-backend (section 7 de son rapport). Je n'ai pas de moyen de vérifier que ce choix de proportionnalité est suffisant au-delà de la lecture du code et du rejeu réel de ce test.
- Revue de qualité (C4) faite en profondeur sur YardClosingServiceTest (auto-DNF, demandé explicitement) et sur les tests d'intégration nouveaux ; échantillonnage sur les autres fichiers de service (PassageRecordingServiceTest, ReintegrationServiceTest, AutomaticReactivationTest) sans relecture exhaustive méthode par méthode de leurs assertions.

## 5. Recommandation à l'issue du premier passage (historique - voir section 7 pour la mise à jour)

Non prêt, au sens strict de la règle C5, pour la même raison que INC-1 (écart de précision sur le chiffre de couverture JaCoCo, sans conséquence sur le franchissement du seuil de 80 %). S'ajoute un constat majeur propre à cet incrément : trois tests de transition de domaine (rg29_markDnf, rg29_markWinner, rg29_raceFinish dans StateTransitionsTest.java) existent, sont pertinents, mais ne sont pas référencés dans la matrice.

Aucun des deux constats ne remet en cause la qualité de la logique métier testée ni la véracité du nombre de tests exécutés (269/269, 0 échec, confirmé). Ce sont des écarts de tenue de patrimoine et de précision de rapport, à arbitrer par l'agent fonctionnel.

## 6. Revue 2 (2026-09-26)

### Statut des constats du premier passage

| ID | Statut | Preuve |
|---|---|---|
| INC2-C5-01 (Bloquant, couverture JaCoCo, hérité de INC-1) | **Levé** | Même correction que documentée dans `INC-1-coherence.md` (section Revue 2) : `backend/pom.xml` sépare désormais `target/jacoco.exec` (unitaire, surefire, utilisé par `report` et `check`) de `target/jacoco-it.exec` (intégration, failsafe, via `prepare-agent-integration` et `<argLine>@{failsafeArgLine}</argLine>`). Rejoué `mvn -B -f backend/pom.xml clean verify` : les deux fichiers `.exec` coexistent (634 820 et 613 537 octets respectivement). `jacoco.csv` régénéré : domain 97,30 %, service 98,99 %, ensemble 98,34 %, **identique** aux chiffres corrigés dans `INC-2-integration.md` section 3, et reproductible sur une seconde régénération (`mvn -o jacoco:report`, chiffre inchangé). |
| INC2-C2-01 (Majeur, tests orphelins StateTransitionsTest) | **Levé** | `docs/tests/PATRIMOINE.md` contient désormais trois nouvelles lignes référençant `domain/StateTransitionsTest.java#rg29_markDnf` (INC2-CA40, RG29, domaine), `#rg29_markWinner` et `#rg29_raceFinish` (INC2-CA33, RG29, domaine ×2), avec une note précisant qu'il s'agit d'une vérification complémentaire au niveau domaine pur, pas d'un doublon des tests de service déjà cités (ManualDnfServiceTest pour CA40, YardClosingServiceTest pour CA33). |

### Vérification indépendante du chiffre « 258/258 »

Même vérification que documentée dans `INC-1-coherence.md` (extraction indépendante par décompte des annotations `@Test`/`@ParameterizedTest` = 258, recoupée avec la liste de toutes les méthodes de test du dépôt moins les méthodes utilitaires = 258, comparée bijectivement aux 258 références uniques de `docs/tests/PATRIMOINE.md`) : confirmée, aucune méthode non référencée, aucune référence orpheline.

### C3 — Effet de la modification JaCoCo sur la règle de couverture de CLAUDE.md

Identique à l'analyse de `INC-1-coherence.md` : la séparation `jacoco.exec` / `jacoco-it.exec` fait que `jacoco:check` ne mesure plus que la couverture unitaire réelle des tests `*Test` (surefire), strictement conforme au texte de `CLAUDE.md` (« couverture unitaire >= 80 % sur le cœur métier »). Cette correction renforce la règle, elle ne l'affaiblit pas. Aucune autre modification de `backend/pom.xml` par rapport à `main` que celles déjà relevées (plugin failsafe + exécution JaCoCo dédiée).

## 7. Recommandation finale (Revue 2)

**Prêt pour arbitrage.** Les 2 constats du premier passage (1 bloquant, 1 majeur) sont tous levés, avec preuve reproductible. Aucun nouveau constat bloquant ou majeur identifié lors de ce second passage. Ce n'est pas un verdict : le GO / GO sous réserves / NO-GO reste à l'agent fonctionnel.

## 8. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : **GO sous réserves** (verdict unique de l'incrément, détaillé dans `INC-2-integration.md`, section 8)
- **Réserves ou motifs** :
  - INC2-C5-01 (bloquant) : **levée acceptée**, pour la même cause et avec la même preuve que INC1-C5-01.
    Aucun constat bloquant ne reste ouvert.
  - INC2-C2-01 (majeur) : levée acceptée. Les trois tests `rg29_*` sont acceptés au patrimoine comme
    compléments de domaine.
  - Constat supplémentaire de l'agent fonctionnel (hors revue, dans la partie échantillonnée) : les assertions
    de CA31, CA32 et CA48 ne couvrent pas tout l'« Alors » de la spec (R2-1).
  - Réserves : R2-1, RT1, RT3, RT4 (voir `INC-2-integration.md`).
- **Actions correctives exigées** : aucune avant GO.
- **Date** : 2026-09-26 (agent fonctionnel)
