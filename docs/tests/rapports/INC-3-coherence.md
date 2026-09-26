# Rapport de revue de cohérence du patrimoine : INC-3

- **Date** : 2026-09-26
- **Agent auteur** : revue-coherence-patrimoine
- **Périmètre** : incrément 3 (API REST), en mode rattrapage
- **Référence de comparaison (C3)** : main (commit c491bb6), comparé au working tree de chore/workflow-validation via `git diff main` / `git status --untracked-files=all`
- **Entrées relues** : `CLAUDE.md`, `docs/tests/PATRIMOINE.md`, `docs/tests/rapports/INC-3-integration.md`, `docs/tests/rapports/INC-3-e2e.md`, `docs/specs/increment3.md`, code de test sous `backend/src/test/java/fr/backyard/`, historique git
- **Suite rejouée** : `mvn -B -f backend/pom.xml clean verify` (une exécution, valable pour les 3 incréments) + `mvn -B -f backend/pom.xml verify -Dgroups=INC-3 -Djacoco.skip=true`

## 1. Synthèse du premier passage (historique - statut à jour en section 6)

- **Bloquant** : 1
- **Majeur** : 0
- **Mineur** : 1
- **Contrôles sans constat** : C1, C2 (hors nuance mineure), C3, C4, C6
- **Recommandation** : non prêt (1 constat bloquant, hérité du même écart de mesure JaCoCo que INC-1 et INC-2), avec la même nuance : le seuil de 80 % reste largement dépassé.

## 2. Tableau des constats

| ID | Contrôle | Sévérité | Description | Preuve | Action recommandée |
|---|---|---|---|---|---|
| INC3-C5-01 | C5 | Bloquant | Le rapport `INC-3-integration.md` cite, pour la suite complète, une couverture JaCoCo domaine 94,13 %, service 98,83 %, ensemble 97,36 % (chiffres identiques à INC-2). En rejouant `mvn -B -f backend/pom.xml clean verify` une fois (BUILD SUCCESS, 269 tests dont 255 surefire + 14 failsafe, 0 échec, chiffres de tests identiques au rapport ; et confirmation spécifique par `mvn verify -Dgroups=INC-3 -Djacoco.skip=true` : 10/10 tests, répartis QrTokenUniquenessIT 1/1, RaceBoardTransactionBoundaryIT 2/2, RepositoryDerivedQueriesIT 6/6, EndToEndRaceLifecycleIT 1/1, exactement comme cité), le fichier `backend/target/site/jacoco/jacoco.csv` généré donne : domaine 97,30 %, service 98,99 %, ensemble 98,34 % (474/482 lignes). Écart identique à celui déjà détaillé dans `INC-1-coherence.md` (INC1-C5-01) et `INC-2-coherence.md` (INC2-C5-01) : ce n'est pas un écart propre à l'incrément 3, les trois rapports citent les mêmes chiffres de couverture pour la même suite complète. Le seuil de 80 % exigé est franchi dans toutes les mesures. | `backend/target/site/jacoco/jacoco.csv` (généré le 2026-09-26) ; `docs/tests/rapports/INC-3-integration.md` section 3 ; sortie `mvn verify -Dgroups=INC-3 -Djacoco.skip=true` : Tests run 10, Failures 0 | Même action que INC1-C5-01/INC2-C5-01 : correction unique et commune aux 3 rapports si l'agent fonctionnel la juge nécessaire, sinon réserve documentée. |
| INC3-C2-01 | C2 | Mineur | `it/RepositoryDerivedQueriesIT.java` contient une méthode `existsByRaceIdAndBibDetectsDuplicateBib`, explicitement commentée comme test de "revue" ("methode autorisee par la spec, non appelee par le code de production"), sans @Tag de CA et sans ligne dédiée dans la matrice : elle n'est mentionnée que dans le paragraphe d'introduction de la section "Mode rattrapage" de PATRIMOINE.md ("existsByRaceIdAndBib[AndIdNot]"), de façon groupée avec `existsByRaceIdAndBibAndIdNot` qui, elle, a sa propre ligne (INC3-CA29). Le test est légitime et bien écrit (assertions réelles, données isolées), ce n'est pas un vrai test orphelin au sens où son existence et sa justification sont documentées en commentaire de code et en prose dans PATRIMOINE.md, mais il n'a pas de ligne propre dans le tableau "Matrice exigences <-> tests" comme tous les autres tests du fichier. | `backend/src/test/java/fr/backyard/it/RepositoryDerivedQueriesIT.java` lignes 84-100 ; `docs/tests/PATRIMOINE.md` ligne 27 (mention groupée en prose, pas de ligne de tableau dédiée) | Ajouter une ligne dans la matrice (section incrément 3), type INT, statut ACTIF, avec la note déjà présente dans le code du test ("méthode autorisée par la spec RG20, non appelée par le code de production, vérification défensive"), pour uniformiser avec le reste du fichier. |

## 3. Contrôles sans constat

- C1 : les 57 CA de `docs/specs/increment3.md` (CA1 à CA57, confirmé par comptage direct des occurrences dans le fichier) ont chacun au moins un test ACTIF avec des assertions pertinentes. Échantillon vérifié en détail, avec revue exhaustive demandée pour la sécurité : `api/SecuritySliceTest.java` (CA46 à CA57) a des assertions réelles et non triviales sur chaque cas (vérification du corps ProblemDetail, absence d'en-tête WWW-Authenticate, non-fuite du nom d'utilisateur ou du mot de passe dans le detail, vérification que le service métier n'est jamais invoqué via Mockito.verify(...).never(), absence de Set-Cookie, absence de session conservée). RG34 (HTTPS obligatoire) est correctement documenté dans la matrice comme non testable automatiquement, cohérent avec la spec elle-même (section 8, PO24) : ce n'est pas un écart.
- C2 (hors nuance mineure ci-dessus) : tous les tests cités dans la matrice pour l'incrément 3 existent dans le code avec les bons noms de méthode (vérifié par comparaison exhaustive automatisée entre les références `#caN_...` de PATRIMOINE.md et les méthodes réellement présentes dans l'arbre `backend/src/test/java/fr/backyard/` : 234 références dans la matrice, toutes retrouvées dans le code). Les @Tag des nouveaux tests `*IT` (INC-3, INC3-CAxx) correspondent à des CA existants dans la spec et à des lignes de la matrice.
- C3 : aucune modification de `backend/src/main` ni d'un test existant par rapport à main. Seuls ajouts : `it/QrTokenUniquenessIT.java`, `it/RaceBoardTransactionBoundaryIT.java`, `it/RepositoryDerivedQueriesIT.java` (nouveaux), réutilisation de `it/EndToEndRaceLifecycleIT.java` (partagé avec INC-2) et de `it/support/AbstractApiIT.java`. Aucune dépendance Maven ajoutée en dehors du plugin failsafe déjà signalé en INC-1.
- C4 : les nouveaux tests d'intégration ont des assertions réelles et détaillées (ex. QrTokenUniquenessIT vérifie l'absence de fuite du nom de contrainte SQL dans le detail HTTP, le nombre exact de coureurs persistés après le rejet), données isolées par un générateur de jeton QR fixé dans une @TestConfiguration propre à la classe (pas de pollution des autres tests), pas de sleep, pas de dépendance à l'ordre d'exécution. Pas de test en quarantaine.
- C6 : les tests de persistance de l'incrément 1 et de logique métier de l'incrément 2 continuent de passer dans la suite complète rejouée (269/269, 0 échec) : pas de régression détectée.

## 4. Limites

- Même limite que INC-1 et INC-2 concernant la non-reproductibilité exacte du pourcentage JaCoCo affiché.
- Je n'ai pas testé contre un vrai serveur PostgreSQL ni contre un vrai reverse proxy TLS (RG34) : conforme aux limites déjà documentées par test-integration-backend et à ce que la spec elle-même prévoit (vérification à la recette d'infrastructure).
- Revue de qualité (C4) faite en profondeur sur les 4 fichiers `it/` nouveaux et sur `api/SecuritySliceTest.java` (demande explicite d'exhaustivité sur la sécurité) ; échantillonnage sur les autres fichiers de slice (RaceApiSliceTest, RunnerApiSliceTest, RaceActionsApiSliceTest, ReadModelApiSliceTest, ApiErrorsSliceTest) sans relecture exhaustive méthode par méthode de leurs assertions.
- Je n'ai pas vérifié manuellement l'absence des classes interdites par CA56 (WebSecurityConfigurerAdapter, .and(), antMatchers, NoOpPasswordEncoder) au-delà de constater que le code compile contre Spring Security 7 (ces classes n'existent plus dans cette version) : je m'appuie sur la même limite déjà documentée par test-integration-backend (risque résiduel faible, non traité).

## 5. Recommandation à l'issue du premier passage (historique - voir section 7 pour la mise à jour)

Non prêt, au sens strict de la règle C5, pour la même raison que INC-1 et INC-2 (écart de précision sur le chiffre de couverture JaCoCo, sans conséquence sur le franchissement du seuil de 80 %). Le seul autre constat de cet incrément est mineur (un test de revue défensif non listé dans la matrice, bien que documenté en prose et dans le code).

La couverture des 57 CA de la spec, la cohérence matrice/code et la qualité des tests de sécurité et des nouveaux tests d'intégration sont jugées satisfaisantes sur cet incrément.

## 6. Revue 2 (2026-09-26)

### Statut des constats du premier passage

| ID | Statut | Preuve |
|---|---|---|
| INC3-C5-01 (Bloquant, couverture JaCoCo, hérité de INC-1/INC-2) | **Levé** | Même correction que documentée dans `INC-1-coherence.md` : `backend/pom.xml` sépare `target/jacoco.exec` (unitaire) de `target/jacoco-it.exec` (intégration, via `prepare-agent-integration` et `<argLine>@{failsafeArgLine}</argLine>` de failsafe). Rejoué `mvn -B -f backend/pom.xml clean verify` puis `mvn -B -f backend/pom.xml verify -Dgroups=INC-3 -Djacoco.skip=true` : 10/10 tests (QrTokenUniquenessIT 1/1, RaceBoardTransactionBoundaryIT 2/2, RepositoryDerivedQueriesIT 6/6, EndToEndRaceLifecycleIT 1/1), exactement comme cité. `jacoco.csv` régénéré : domain 97,30 %, service 98,99 %, ensemble 98,34 %, **identique** aux chiffres corrigés dans `INC-3-integration.md` section 3, reproductible sur une seconde régénération. |
| INC3-C2-01 (Mineur, existsByRaceIdAndBibDetectsDuplicateBib non listé) | **Levé** | `docs/tests/PATRIMOINE.md` contient désormais la ligne `INC3-TECH1` référençant `it/RepositoryDerivedQueriesIT.java#existsByRaceIdAndBibDetectsDuplicateBib`, avec la note déjà présente dans le code du test (méthode de repository autorisée par la spec RG20 mais non appelée par le code de production, vérification défensive). |

### Vérification indépendante du chiffre « 258/258 »

Même vérification que documentée dans `INC-1-coherence.md` (extraction indépendante par décompte des annotations `@Test`/`@ParameterizedTest` = 258, recoupée avec la liste de toutes les méthodes de test du dépôt moins les méthodes utilitaires = 258, comparée bijectivement aux 258 références uniques de `docs/tests/PATRIMOINE.md`, incluant les 6 méthodes de `config/SecurityCredentialsStartupTest.java` désormais listées individuellement pour INC3-CA57) : confirmée, aucune méthode non référencée, aucune référence orpheline.

### C3 — Effet de la modification JaCoCo sur la règle de couverture de CLAUDE.md

Identique à l'analyse de `INC-1-coherence.md` : la séparation `jacoco.exec` / `jacoco-it.exec` fait que `jacoco:check` ne mesure plus que la couverture unitaire réelle, strictement conforme au texte de `CLAUDE.md`. Cette correction renforce la règle, elle ne l'affaiblit pas. Confirmé par `git diff main -- backend/pom.xml` : seules modifications par rapport à `main` = déclaration du plugin `maven-failsafe-plugin` (avec `argLine`) et l'exécution `prepare-agent-integration` de JaCoCo ; aucune autre section du fichier n'est touchée.

## 7. Recommandation finale (Revue 2)

**Prêt pour arbitrage.** Les 2 constats du premier passage (1 bloquant, 1 mineur) sont tous levés, avec preuve reproductible. Aucun nouveau constat bloquant ou majeur identifié lors de ce second passage. Ce n'est pas un verdict : le GO / GO sous réserves / NO-GO reste à l'agent fonctionnel.

## 8. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : **GO sous réserves** (verdict unique de l'incrément, détaillé dans `INC-3-integration.md`, section 8)
- **Réserves ou motifs** :
  - INC3-C5-01 (bloquant) : **levée acceptée** (même cause et même preuve que INC1-C5-01). Aucun constat
    bloquant ne reste ouvert.
  - INC3-C2-01 (mineur) : levée acceptée. Test INC3-TECH1 accepté au patrimoine.
  - Limite §4 de la revue sur CA56 (API interdites non vérifiées au-delà de la compilation) : l'agent
    fonctionnel a fait lui-même la revue manuelle le 2026-09-26 (conforme). L'automatisation est exigée en
    réserve R3-1.
  - Réserves : R3-1, RT1, RT2, RT3, RT4 (voir `INC-3-integration.md`).
- **Actions correctives exigées** : aucune avant GO.
- **Date** : 2026-09-26 (agent fonctionnel)
