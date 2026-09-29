# INC-5 — Arbitrage des adaptations de tests existants (note N1, étape 2)

> Décideur : agent fonctionnel. Date : 2026-09-28.
> Référence : `docs/specs/increment5.md`, révision 5, CA21 et note N1.
> Entrée examinée : diff des 19 fichiers de tests existants, produit par le développeur sur une copie hors dépôt (`scratchpad/N1-adaptations.diff`). J'ai relu le diff ligne à ligne, et j'ai comparé les `@DisplayName` d'origine du dépôt.
> Résultat indicatif du développeur : `mvn -B -f backend/pom.xml clean verify` sur la copie, 2026-09-28T20:33:29Z. BUILD SUCCESS ; 414 tests unitaires, 0 échec, 0 ignoré ; 52 IT, 0 échec ; 231 tests front verts ; JaCoCo domain 98,6 %, service 99,2 %. **Ce résultat ne vaut ni verdict technique ni GO** (N1, étape 1). Seule l'exécution du testeur dans le dépôt comptera.

## 0. Synthèse des décisions

| Sujet | Décision |
|---|---|
| Catégorie A (hors reclassements) | **Accord global** |
| Adaptations reclassées | SecuritySliceTest : A → **C**, acceptée. RunnerServiceTest `ca29_nameEditableWhileRunning` : B → **C**, acceptée avec renommage. RacePersistenceTest `tech_raceAndRunnerSettersUpdateFields` : A → **B**, acceptée. RunnerApiSliceTest `ca30_invalidRunnerUpdate` : précisé en **B** |
| Catégorie B | **Accord ligne à ligne** pour toutes les lignes (§2) |
| Catégorie C | **Toutes acceptées sous conditions** (§3) : renommages obligatoires, et nettoyage d'`AbstractApiIT` à rendre robuste |
| `@DisplayName` et noms de méthodes devenus faux | **À renommer**, selon une liste fermée (§4). Tous les autres restent inchangés |
| 11 points d'interprétation | Acceptés : 1, 2, 3, 4, 5, 7, 10. Acceptés avec correction : **6, 8, 9, 11** (§5) |
| Écarts connus | Les adaptations E2E devront être arbitrées séparément. CA39 et CA40 (manuel) restent des réserves prévisibles (§6) |

Aucune adaptation n'est refusée. Aucun test n'est supprimé, désactivé ni exclu.

---

## 1. Catégorie A — accord global

Ces adaptations sont admises : elles ne changent que la compilation ou le jeu de données. Elles ne touchent aucune assertion, aucune valeur attendue, aucun nom de méthode ni aucun `@DisplayName`.

| # | Fichier | Adaptation |
|---|---|---|
| A1 | `testsupport/TestData.java` | `runner(long, Race, int, String name, String)` devient `runner(long, Race, int, String qrToken)`. `runner(long, Race, String)` ne passe plus `"Coureur " + id`. Javadoc mise à jour |
| A2 | `api/RaceActionsApiSliceTest.java` | Fabriques sans nom : champ `alice`, `ca33_reactivationScan`, `ca35_manualDnf`, `ca37_reintegration`, `ca38_reintegrationWithoutPassage` |
| A3 | `api/ReadModelApiSliceTest.java` | `runner(12L, r1, 6, SECRET_TOKEN)` |
| A4 | `api/RunnerApiSliceTest.java` | Champ `alice` sans nom ; `ca28_adminRunnerList` (fabriques sans nom) ; import de `AccountFakes.account` |
| A5 | `service/RaceBoardServiceTest.java` | Fabriques sans nom dans `ca40`, `ca41`, `ca43`, `ca44_runnerDetail`, `ca44_runnerDetailIndependentOfRaceStatus`. Les assertions sur le nom relèvent de B (B9, B10) |
| A6 | `service/RaceServiceTest.java` | `ca17`, `ca18` : fabriques sans nom |
| A7 | `service/RunnerServiceTest.java` | Construction avec un `AccountService` réel (`AccountFakes`, `BCryptPasswordEncoder(4)`) et ses imports ; `register(1L, "X", "motdepasse-1")` dans `ca23_…` (hors assertion du nom, voir B1), dans le test du premier dossard, dans le test « autre course », `ca25` course fermée et `ca25` course inconnue ; fabriques sans nom dans `ca28`, `ca31` ; `update(id, bib)` dans `ca29_bibAlreadyTaken`, `ca29_bibFrozenWhileRunning`, `ca29_updateUnknownRunner` |
| A8 | `domain/RunnerDnfConsistencyTest.java` | `new Runner(race, bib, token)` (4 tests CA23) |
| A9 | `it/RepositoryDerivedQueriesIT.java` | `new Runner(race, bib, token)` (4 tests) |
| A10 | `it/SchemaAndContextStartupIT.java` | `new Runner(race, bib, token)` (CL8, CL9) |
| A11 | `persistence/Increment1AddendumPersistenceTest.java` | `new Runner(race, bib, token)` dans `ca23` (x2), `ca25`, `ca28` (JPA bib 0) et `persistedRunner()`. Hors CA26, voir B13 et C2 |
| A12 | `persistence/RacePersistenceTest.java` | `new Runner(race, bib, token)` dans `ca6`, `ca7`, `ca8`, `ca9`, `ca10` à `ca17`, `ca19`. Hors `ca5` (B11) et hors test TECH (B12) |
| A13 | `api/ApiSliceTest.java` | `@MockitoBean AccountService` et son import |
| A14 | `api/ApiErrorsSliceTest.java` | `ca8_dataIntegrityViolation` : bouchon `register(1L, "Alice", "motdepasse-1")`, corps E3 `{"pseudo","password"}`. L'objet du test est la traduction d'une `DataIntegrityViolationException` en 409, pas le corps de E3 : c'est bien A |
| A15 | `it/QrTokenUniquenessIT.java` | Corps E3 `{"pseudo":"Alice"…}` et `{"pseudo":"Bob"…}`. L'objet du test est l'unicité du `qrToken`, et le 409 `DATA_INTEGRITY` attendu est inchangé |
| A16 | `it/support/AbstractApiIT.java` | `register(...)` : corps E3 `{"pseudo","password":"motdepasse-1"}`. **Le nettoyage des comptes est traité en C3** |

Le seul reclassement vers C concerne `SecuritySliceTest` (C1).

---

## 2. Catégorie B — accord ligne à ligne

Critère appliqué : la nouvelle assertion reste une égalité exacte (ou un JSON `STRICT`, ou un ensemble exact). Elle porte sur la valeur prévue par la spec et cite la RG.

| # | Fichier, méthode | Avant → après | RG | Aussi stricte ? | Décision |
|---|---|---|---|---|---|
| B1 | `RunnerServiceTest.ca23_registrationTakesMaxBibPlusOne` | `getName() == "Alice"` → `displayName() == "alice"` | RG2, RG7, RG18 | Oui : égalité exacte, sur la saisie `Alice` normalisée | **Accord** |
| B2 | `RunnerServiceTest.ca29_updateBibAndNameInSetup` | `update(12,7,"Alice B.")` et `getName() == "Alice B."` → `update(12,7)` et `displayName() == "Coureur n°7"` | RG18 (E15 sans nom ; le libellé suit le dossard) | Oui : égalité exacte, qui vérifie en plus que le libellé suit le nouveau dossard | **Accord**, renommage R5 |
| B3 | `RunnerApiSliceTest.ca24_publicRegistration` | Corps `{"name"}` → `{"pseudo":"Alice","password":…}` ; bouchon `register(1L,"Alice","motdepasse-1")` renvoyant un coureur lié au compte 5 `alice` ; JSON `STRICT` avec `name:"alice"`, `pseudo:"alice"` | RG7, RG18, RG2 | Oui : `STRICT` conservé, un champ exigé en plus | **Accord** |
| B4 | `RunnerApiSliceTest.ca25_blankNameIsRejected` | `{"name":"  "}` → `{"pseudo":"  ","password":"motdepasse-1"}` ; erreurs exactement `{"pseudo"}` ; `never().register(anyLong, anyString, anyString)` | RG2, RG7 | Oui : ensemble exact d'une erreur, service non appelé | **Accord**, renommage R1 |
| B5 | `RunnerApiSliceTest.ca28_adminRunnerDetail` | JSON `STRICT` : `name:"Coureur n°6"`, plus `accountId:null` et `pseudo:null` | RG12, RG18 | Oui : `STRICT` conservé, deux champs exigés en plus | **Accord** |
| B6 | `RunnerApiSliceTest.ca30_invalidRunnerUpdate` | Erreurs `{"bib","name"}` → `{"bib"}` ; `never().update(anyLong, anyInt)`. Le corps envoie toujours `"name":""` | RG18 (E15 ne porte plus que `bib` ; `name` ignoré) | Oui : l'ensemble reste exact. Il vérifie en plus qu'un `name` invalide n'est plus validé | **Accord**, renommage R2 |
| B7 | `RunnerApiSliceTest.ca30_unknownPropertiesAreIgnored` | Bouchon et `verify` `update(12L, 7)` ; `$.name == "Coureur n°7"` ; `verifyNoMoreInteractions` conservé ; corps inchangé (dont `"name":"Alice B."`) | RG18 | Oui : égalité exacte. Le `name` du corps doit être ignoré, ce qui est prouvé par `update(12,7)` et `verifyNoMoreInteractions` | **Accord**, renommage R3 |
| B8 | `RaceActionsApiSliceTest` (scan nominal, CA32 inc. 3) | `runnerName:"Alice"` → `"Coureur n°6"`, JSON `STRICT` | RG18, avec le point d'interprétation 2 (§5) | Oui : `STRICT` conservé | **Accord** (lié à l'acceptation du point 2) |
| B9 | `RaceBoardServiceTest.ca40_boardOfRunningRace` | `entryA.name() == "A"` → `"Coureur n°1"` | RG18 | Oui : égalité exacte. Les autres assertions (calculs dérivés) sont inchangées | **Accord** |
| B10 | `RaceBoardServiceTest.ca44_runnerDetail` | `detail.name() == "Alice"` → `"Coureur n°6"` | RG18 | Oui : égalité exacte | **Accord** |
| B11 | `RacePersistenceTest.ca5_persistMinimalRunner` | `found.getName() == "Alice"` → `found.displayName() == "Coureur n°1"` (relu depuis la base) | RG6, RG18 | Oui : égalité exacte | **Accord** |
| B12 | `RacePersistenceTest.tech_raceAndRunnerSettersUpdateFields` *(reclassé de A en B)* | `setName("UpdatedName")` retiré ; `getName() == "UpdatedName"` → `displayName() == "Coureur n°20"` | RG18 (plus de nom écrit ; le libellé suit le dossard) | Oui : égalité exacte. L'assertion vérifie maintenant l'effet de `setBib(20)` sur le nom affiché | **Accord**. Reclassé parce que la valeur de `setName` était lue par une assertion : ce n'était pas une simple préparation (N1, A) |
| B13 | `Increment1AddendumPersistenceTest.ca26_runnerWithNullNameIsRejected` | Rejet (validation ou NOT NULL) et nombre de lignes inchangé → coureur sans nom accepté, nombre de lignes `+1` exact, `SELECT name … = NULL` | RG6 (`runner.name` nullable), RG18 | Oui : nombre exact et valeur exacte en base | **Accord**, renommage R6 |
| B14 | `EndToEndRaceLifecycleIT` | `runners[i].name` : `"Alice"`, `"Bob"`, `"Charlie"` → `"alice"`, `"bob"`, `"charlie"` | RG2, RG18 (inscription par `AbstractApiIT.register`, pseudo normalisé) | Oui : égalité exacte, les autres assertions sont inchangées | **Accord** |

---

## 3. Catégorie C — arbitrages motivés

### C1 — `SecuritySliceTest` *(reclassé de A en C)* : **accepté**
Modifications : champ `alice` sans nom ; bouchon `register(1L, "Alice", "motdepasse-1")` dans `@BeforeEach` ; corps E3 `{"pseudo","password"}` dans le test d'absence de `Set-Cookie` (CA52 inc. 3).
- **Motif du reclassement** : N1 range en C toute adaptation qui touche aux tests de sécurité de l'inc. 3.
- **Motif de l'accord** :
  - aucun code HTTP, aucun `code`, aucun `detail` et aucun en-tête attendu n'est modifié ;
  - les tests du `detail` des 401 anonymes sont intacts (vérifié dans le diff : aucune ligne de ces tests ne change) ;
  - l'objet du test (pas de `Set-Cookie` sur E3) est conservé avec le nouveau corps, qui est le seul corps valide de E3.
- Pas de renommage.

### C2 — `Increment1AddendumPersistenceTest` : CA26 `name ""` et `name "   "` : **accepté, renommage obligatoire (R7, R8)**
Ces deux cas n'ont plus d'objet : sans paramètre de nom, un nom vide ou blanc n'est plus exprimable. RG6 et RG18 abolissent la règle d'origine (nom non vide).
Deux options étaient possibles :
- supprimer les deux tests ;
- les convertir, comme le propose le développeur.

**Je retiens la conversion.** Aucun test n'est supprimé (règle 2), et chacun porte une vérification exacte et distincte :
- `…Empty…` vérifie le nom affiché **relu depuis la base** : `displayName() == "Coureur n°2"`, après `entityManager.clear()` ;
- `…Blank…` vérifie `runner.name` NULL en base, lu par `id` (et non par `qr_token` comme B13).

Le recouvrement avec B13 est assumé et sans coût.
**Condition** : les `@DisplayName` et noms de méthodes qui décrivent un rejet sont faux et doivent être renommés (§4). Les tags `INC1-CA26` sont conservés. Le 4e test CA26 (`Race` avec nom blanc) n'est pas touché.

### C3 — `AbstractApiIT` : nettoyage des comptes : **accepté avec correction obligatoire**
- **Classement** : C. C'est de l'infrastructure de test commune à 8 classes IT.
- **Motif de l'accord** :
  - l'unicité globale du pseudo (RG5) rend le nettoyage des comptes nécessaire entre tests, sinon on obtient un 409 parasite ;
  - aucune assertion n'est touchée ;
  - l'ordre passages, puis coureurs, puis comptes, puis courses respecte les FK RESTRICT (RG4).
- **Défaut à corriger** : le nettoyage supprime les comptes course par course. Un compte inscrit à deux courses suivies (E20) garde, au moment de sa suppression, un coureur dans la seconde course : la FK RESTRICT fait alors échouer le nettoyage. Aucune classe actuelle ne le déclenche : `AccountFlowIT` n'hérite pas d'`AbstractApiIT`. Mais les IT de l'incrément à venir y seraient exposées.
- **Correction exigée** :
  1. supprimer les passages et les coureurs de **toutes** les courses suivies, en collectant les `accountId` non nuls ;
  2. supprimer ensuite les comptes collectés (sans doublon) ;
  3. supprimer enfin les courses.

  La javadoc décrit cet ordre.
- **Limite acceptée** : un compte dont tous les coureurs ont été supprimés pendant le test (E16) n'est pas collecté. Une IT qui crée ce cas nettoie elle-même son compte.
- **Renommage** : le paramètre `name` de `register(Long raceId, String name)` devient `pseudo`. Ce nom de paramètre est trompeur, et la signature reste inchangée pour les appelants.

### C4 — `NoTestEndpointsIT` : 6 motifs E20 à E25, 19 → 25 méthodes : **accepté, libellés à mettre à jour (R9)**
- **Classement** : C. La valeur attendue change, et ce n'est pas un des cas de B.
- **Motif de l'accord** :
  - les six endpoints E20 à E25 sont créés par la spec (§0) ;
  - l'assertion reste une égalité exacte, sur le nombre de méthodes et sur l'ensemble des motifs, sans aucun affaiblissement ;
  - le contrôle des mots interdits est inchangé, et `reset` reste absent (CA19 inc. 5) ;
  - les motifs hors `/api/**` sont inchangés.
- **Condition** : la javadoc de classe, la javadoc de `EXPECTED_API_PATTERNS` (« 14 motifs uniques pour 19 méthodes »), le `@DisplayName` et le message `as(...)` citent « E1 à E19 ». Ils passent à « E1 à E25 », avec « 20 motifs uniques, 25 méthodes » et la référence à `docs/specs/increment5.md` (§0). Les tags `INC-4` et `INC4-CA7` sont conservés.
- L'inc. 7 fera évoluer cette liste de la même manière (E26 à E30).

### C5 — `RunnerServiceTest.ca29_nameEditableWhileRunning` *(reclassé de B en C)* : **accepté, renommage obligatoire (R4)**
- **Motif du reclassement** : l'assertion d'origine vérifiait l'**effet** de l'appel (le nom modifié). La nouvelle assertion, `displayName() == "Coureur n°6"`, porte sur une valeur que l'appel ne modifie pas. Elle ne peut donc pas échouer à cause de `update`. L'objet d'origine (nom modifiable en `RUNNING`) a disparu : ce n'est pas un simple changement de valeur attendue.
- **Motif de l'accord** : le test garde un objet utile. En `RUNNING`, E15 avec le **même** dossard est accepté sans exception, et le dossard reste 6. Cela protège la comparaison des valeurs dans le contrôle « dossard figé hors SETUP ». Les deux assertions sont conservées.
- **Condition** : renommage (§4).

---

## 4. `@DisplayName` et noms de méthodes : décision

**Règle retenue.**
- Catégorie A : on ne renomme rien, conformément à N1 (condition cumulative de A).
- Catégories B et C : on renomme **uniquement** les méthodes et `@DisplayName` qui décrivent un comportement disparu. Un libellé faux trompe le lecteur, le rapport de cohérence et la matrice du patrimoine : c'est une atteinte à la véracité (CLAUDE.md, « Code lisible », et règle 4 du workflow).
- Chaque libellé renommé garde sa référence de CA d'origine et ajoute la RG de l'inc. 5 qui justifie le changement. Les tags JUnit (`@Tag`) ne changent pas.
- Tout autre libellé reste inchangé.

Liste **fermée** des renommages :

| # | Fichier | Méthode actuelle → nouvelle | `@DisplayName` nouveau |
|---|---|---|---|
| R1 | `RunnerApiSliceTest` | `ca25_blankNameIsRejected` → `ca25_blankPseudoIsRejected` | `CA25 (inc. 3) / RG2 (inc. 5) - inscription avec un pseudo blanc : 400 VALIDATION_FAILED sur pseudo seul, service non appele` |
| R2 | `RunnerApiSliceTest` | `ca30_invalidRunnerUpdate` (inchangé) | `CA30 (inc. 3) / RG18 (inc. 5) - PUT coureur avec bib 0 et nom vide : 400 VALIDATION_FAILED sur bib seul (name ignore), service non appele` |
| R3 | `RunnerApiSliceTest` | `ca30_unknownPropertiesAreIgnored` (inchangé) | `CA30 (inc. 3) / RG18 (inc. 5) - PUT coureur avec name, status et qrToken dans le JSON : 200, service appele avec (12, 7) uniquement, name « Coureur n°7 »` |
| R4 | `RunnerServiceTest` | `ca29_nameEditableWhileRunning` → `ca29_sameBibAcceptedWhileRunning` | `CA29 (inc. 3) / RG18 (inc. 5) - course RUNNING, meme dossard : accepte sans effet, bib 6, nom affiche « Coureur n°6 »` |
| R5 | `RunnerServiceTest` | `ca29_updateBibAndNameInSetup` → `ca29_updateBibInSetup` | `CA29 (inc. 3) / RG18 (inc. 5) - course SETUP, dossard 7 libre : bib 7, nom affiche « Coureur n°7 »` |
| R6 | `Increment1AddendumPersistenceTest` | `ca26_runnerWithNullNameIsRejected` → `ca26_runnerWithoutNameIsAccepted` | `CA26 (inc. 1) / RG6 (inc. 5) - Runner sans nom : accepte, une ligne de plus, runner.name NULL en base` |
| R7 | `Increment1AddendumPersistenceTest` | `ca26_runnerWithEmptyNameIsRejectedByDomainValidation` → `ca26_runnerWithoutAccountIsReloadedWithNeutralName` | `CA26 (inc. 1) / RG18 (inc. 5) - Runner sans compte relu depuis la base : nom affiche « Coureur n°2 »` |
| R8 | `Increment1AddendumPersistenceTest` | `ca26_runnerWithBlankNameIsRejectedByDomainValidation` → `ca26_runnerNameColumnStaysNull` | `CA26 (inc. 1) / RG6 (inc. 5) - Runner sans compte : runner.name NULL en base (relu par id)` |
| R9 | `NoTestEndpointsIT` | `ca7_requestMappingsAreExactlyTheDocumentedEndpoints` (inchangé) | `CA7 (inc. 4) - les correspondances @RequestMapping sous /api/** sont exactement E1 à E25 (20 motifs uniques, 25 méthodes) ; les autres se limitent au manifeste et à /error`. Mêmes mises à jour dans les deux javadocs et dans le message `as(...)` (C4) |

Le testeur peut ajuster l'orthographe ou les accents de ces libellés, pour suivre la convention du fichier. Il ne change ni leur sens ni leurs références.

---

## 5. Points d'interprétation du développeur

| # | Point | Décision | Motif, et correction éventuelle |
|---|---|---|---|
| 1 | `runner.name` non mappée par JPA ; nom affiché calculé seulement par `Runner.displayName()` | **Accepté** | Conforme à RG6 (colonne conservée, vide, jamais écrite) et à RG18 (calcul en un seul endroit, dans le domaine, testable sans Spring). La colonne est nullable après V2, donc les insertions restent valides. CA30 et CA31 vérifient `runner.name` NULL en base |
| 2 | E6 (scan) : `runnerName` = nom affiché | **Accepté** | La liste de RG18 omet E6, mais son principe est général : le nom affiché est le **seul** nom d'un coureur, et `runner.name` est toujours NULL. Sans cette lecture, E6 renverrait `null`, ce qui dégraderait l'écran de scan (inc. 4) sans aucune justification. Le pseudo est déjà public (tableau de bord), donc aucun enjeu RGPD supplémentaire. **Clarification actée ici** : E6 fait partie des réponses de RG18. Elle sera reportée comme erratum dans la spec à la prochaine révision, sans nouveau CA. B8 couvre le cas sans compte. Le cas avec compte (runnerName = pseudo) est à ajouter par test-integration-backend, en complément de CA33 (scan du coureur avant et après détachement) |
| 3 | Compte chargé avec le coureur (`@EntityGraph` sur les listes) | **Accepté** | Évite le N+1 sur le tableau de bord interrogé toutes les 2 à 3 s. Condition, déjà remplie par l'architecture : aucune entité n'est sérialisée directement, seuls les DTO explicites le sont. CA5 vérifie l'absence de hash dans les réponses |
| 4 | Suppression de compte : `flush` explicite entre le détachement et la suppression | **Accepté** | C'est nécessaire avec la FK RESTRICT pour garantir l'ordre SQL dans la transaction unique de RG20. CA33 le couvre |
| 5 | E25 : recherche littérale via `Containing` de Spring Data | **Accepté** | `Containing` échappe `%`, `_` et `\` (clause `ESCAPE`), ce qu'exige RG24 (CL22). C'est vérifié sur H2 par `AccountFlowIT` (CA43). Rappel de la revue de CA2 : aucun `IgnoreCase`, `lower`, `upper` ni `ILIKE` ; la valeur du filtre passe par la fonction unique de normalisation |
| 6 | Front : 409 `BUSINESS_CONFLICT` non distingué par `code` ; `detail` affiché avec le lien, puis relecture de la course ; cas `TOO_MANY_ATTEMPTS` sur 429 | **Accepté avec correction** | Afficher le `detail` puis relire la course est conforme à RG17. Le cas 429 est exigé par RG23 (CA41). Le code actuel (`registration-page.ts`) n'affiche déjà pas le lien quand l'utilisateur est connecté (E20), c'est correct. **Correction exigée** : après la relecture de la course, si elle n'est plus en `SETUP` (inscriptions fermées), **ne pas** afficher le lien « J'ai déjà un compte ». Se connecter ne permettrait pas de s'inscrire, et RG17 ne prévoit ce lien que pour le 409 « Pseudo déjà utilisé ». Il faut un test front-unit ou E2E du cas « 409 sur une course relue en RUNNING : pas de lien » |
| 7 | E20 jamais rejoué, même sur `DATA_INTEGRITY` | **Accepté** | RG17 : « une seule requête E20 » ; CA28 ; RG43 (inc. 4). Le coureur peut recliquer lui-même. Le nouvel essai automatique de E3 sur `DATA_INTEGRITY` (RG12, RG13 inc. 4) reste inchangé |
| 8 | Page admin d'une course : E25 sans filtre à chaque rechargement, pour obtenir `{n}` | **Accepté avec correction** | Récupérer `{n}` (`runnerCount`) par E25 est admis : RG17 n'impose qu'**une seule requête E24** à la confirmation, et CA38 ne compte que E24. **Correction exigée** : E25 ne doit **jamais** faire partie d'un rafraîchissement périodique. Il est appelé au chargement de la page ou après une action, et au plus une fois par chargement. Si la page admin interroge périodiquement la liste des coureurs, E25 doit être sorti de ce cycle, ou appelé à l'ouverture de la confirmation de suppression. Le développeur confirme le comportement retenu dans son rapport |
| 9 | Formulaire admin : dossard seul, bouton « Modifier » absent hors SETUP ; noms `EDIT_BIB_AND_NAME` et `EDIT_NAME` inchangés | **Bouton : accepté. Nommage : correction exigée** | Masquer « Modifier » hors SETUP est cohérent : le nom n'est plus modifiable (RG18) et le dossard est figé hors SETUP (inc. 3). En revanche, la table `action-visibility.ts` déclare encore une action `EDIT_NAME` en RUNNING et FINISHED, alors qu'elle n'existe plus, et la page l'ignore. Cette règle d'affichage est fausse et contredit l'écran (CLAUDE.md : nommage métier, aucune règle incohérente). **Correction exigée (production)** : `EDIT_BIB_AND_NAME` devient `EDIT_BIB` ; `EDIT_NAME` est retiré du type `RunnerAction` et de toutes les lignes de la table. **Adaptation de test pré-acceptée (catégorie B, RG18 : E15 ne modifie plus le nom)** : dans `frontend/src/app/core/action-visibility.spec.ts`, ligne 14 `'EDIT_BIB_AND_NAME'` devient `'EDIT_BIB'`, et `'EDIT_NAME'` est retiré des lignes 17, 18, 20, 21 et 22. Les listes attendues restent comparées à l'identique, sur les nouvelles valeurs |
| 10 | Ajouts non exigés : lien « Mes inscriptions » dans l'en-tête, lien vers `/compte` dans la confirmation | **Accepté** | Tous deux mènent à une route publique `/compte` (RG21), sans lien vers l'admin (dépendance inc. 6). Le lien de confirmation matérialise l'avertissement de RG17. Ils ne doivent ni transporter d'identifiants ni modifier le texte exigé de l'avertissement |
| 11 | Journaux : motif quotidien par défaut de Spring Boot | **Accepté avec correction** | Le comportement est juste : le motif par défaut `${LOG_FILE}.%d{yyyy-MM-dd}.%i.gz` et `max-history=6` donnent au plus 7 jours calendaires. Mais CA39 [config] exige que `application-prod.properties` **définisse** la rotation quotidienne. Un défaut implicite n'est pas vérifiable par lecture du fichier, et pourrait changer avec une version de Spring Boot. **Correction exigée** : ajouter `logging.logback.rollingpolicy.file-name-pattern=${LOG_FILE}.%d{yyyy-MM-dd}.%i.gz` (ou un motif équivalent avec `%d{yyyy-MM-dd}`) |

### Écarts connus signalés par le développeur

- **Hash staff de coût 4 ré-encodés en mémoire en coût 12 après la première connexion** (Spring Security, `upgradeEncoding` et `InMemoryUserDetailsManager`) : **accepté**.
  - Rien n'est persisté ni exposé, la configuration n'est pas modifiée, et la vérification reste faite quel que soit le coût (RG3, révision 5). CA4 reste vérifiable.
  - Point de vigilance pour l'**inc. 7** : les scanneurs sont stockés en base, et une mise à niveau automatique **réécrirait** leur hash. Ce comportement devra y être spécifié ou désactivé explicitement.
- **Tests E2E Playwright non adaptés ni écrits** (fixture `e2e/fixtures/api.ts` `register(raceId, name)` ; CA25, CA26, CA28, CA37, CA38, CA41, CA44) : c'est hors de ce diff.
  - L'adaptation de la fixture suivra la même démarche N1 : liste fournie par test-e2e-frontend, puis mon arbitrage avant application.
  - Le passage d'un corps `{"name"}` à `{"pseudo","password"}` relèvera en principe de A. Toute assertion sur un nom relèvera de B.
- **CA39 et CA40, parties manuelles VPS** : elles ne peuvent pas être exécutées dans ce cycle. Elles seront des **réserves** du verdict final, avec une action pour l'exploitant.

---

## 6. Liste finale des adaptations acceptées (à appliquer par le testeur)

Le testeur applique dans le dépôt **exactement** les éléments suivants, et rien d'autre. Il vérifie que le diff des tests existants correspond à cette liste. Tout écart me revient avant le verdict technique (N1, étape 3).

**Backend — `backend/src/test/java/fr/backyard/`**

1. Le diff du développeur, **tel quel**, pour les 19 fichiers : A1 à A16, B1 à B14, C1, C2, C4, C5, ainsi que la partie « corps E3 et comptes » de C3.
2. En plus, sur ce diff :
   - a. `it/support/AbstractApiIT.java` : nettoyage en deux phases (C3). Les passages et coureurs de toutes les courses suivies sont supprimés d'abord, avec collecte des `accountId` non nuls ; puis les comptes collectés, sans doublon ; puis les courses. La javadoc est mise à jour, et le paramètre `name` de `register` devient `pseudo`.
   - b. Les renommages R1 à R9 (§4), et eux seuls.
3. Aucune autre modification de test existant. En particulier :
   - aucun test de `detail` de 401 anonyme ;
   - aucun test de règle de course, hors fabriques sans nom ;
   - aucune configuration surefire, failsafe ou JaCoCo.

**Frontend — `frontend/src/`**

4. `app/core/action-visibility.spec.ts`, **après** la correction de production du point 9 : ligne 14 `'EDIT_BIB_AND_NAME'` devient `'EDIT_BIB'` ; `'EDIT_NAME'` est retiré des lignes 17, 18, 20, 21 et 22.
5. Aucune autre modification de test front existant. Les 231 tests front passent déjà sans adaptation, selon le développeur.

**Hors liste** (nouveaux tests, qui ne sont pas des adaptations) : le test du point 6 (pas de lien sur une course fermée) et le test de `runnerName` d'un coureur lié à un compte (point 2) sont à écrire, respectivement par le testeur (front) et par test-integration-backend.

---

## 7. Corrections demandées au développeur (production)

| # | Point | Correction |
|---|---|---|
| D1 | 6 | `registration-page.ts` : après un 409 `BUSINESS_CONFLICT` sur E3, ne pas afficher « J'ai déjà un compte » si la course relue n'est plus en `SETUP` |
| D2 | 8 | Page admin d'une course : E25 hors de tout rafraîchissement périodique, au plus une fois par chargement ou par action. Confirmer le comportement dans le rapport |
| D3 | 9 | `action-visibility.ts` : `EDIT_BIB_AND_NAME` devient `EDIT_BIB` ; `EDIT_NAME` est retiré du type et de la table ; `admin-race-page.ts` est aligné |
| D4 | 11 | `application-prod.properties` : `logging.logback.rollingpolicy.file-name-pattern` explicite, avec un motif quotidien `%d{yyyy-MM-dd}` |

Ces corrections ne modifient aucune RG ni aucun CA. Elles ne rouvrent pas l'arbitrage des adaptations backend.
