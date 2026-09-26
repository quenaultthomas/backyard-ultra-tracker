# Rapport de test : INC-4 (intégration)

- **Date** : 2026-09-26
- **Agent auteur** : test-integration-backend
- **Version / commit testé** : branche `feat/increment_4` (working tree, rien de commité). Développement de l'incrément 4 terminé, verdict technique `testeur` = OK. Tests `*IT` ajoutés par cet agent, plus corrections de réserves des incréments 1 à 3 déjà présentes dans le working tree (fichiers de l'utilisateur, non modifiés par cet agent).
- **Environnement** : profil Spring `test`, H2 en mode PostgreSQL (schéma créé par Flyway), Spring Security réel (HTTP Basic, comptes de test `admin-test` / `admin-secret` et `scanner-test` / `scanner-secret`), `spring.jpa.open-in-view=false`. Fichiers de la PWA présents dans `backend/target/classes/static` (copiés par `maven-resources-plugin` en phase `process-resources`, à partir de `frontend/dist/backyard-pwa/browser`, régénéré par `frontend-maven-plugin` au même `mvn verify`). Ni Docker ni `psql` disponibles sur ce poste (RT1).

## 1. Périmètre

Ce rapport couvre :
1. Les critères d'acceptation de type backend/architecture de `docs/specs/increment4.md` (section 10,
   « Impacts backend et build ») : **CA1 à CA7**. CA8 à CA20 (`[front-unit]`) et CA5 (`[build]`) relèvent du
   `testeur` (verdict technique OK déjà rendu : 170 tests Vitest verts, couverture 99,41 % de lignes, seuil
   80 % franchi). CA21 à CA38 (`[E2E]`) relèvent de l'agent `test-e2e-frontend` (`INC-4-e2e.md`, hors
   périmètre de cet agent).
2. Les cinq réserves du patrimoine à échéance « avant la validation de l'INC-4 » : **R1-1, R1-2, R1-3, R2-1,
   R3-1** (section « Écarts ouverts » de `docs/tests/PATRIMOINE.md`).
3. La réserve transverse **RT1** (PostgreSQL réel) : environnement indisponible, consignée comme non
   exécutée.
4. La non-régression complète (`mvn -B -f backend/pom.xml clean verify`), y compris **RT3** (compile sans
   warning).

Les fichiers suivants, déjà présents ou modifiés dans le working tree avant l'intervention de cet agent, sont
des fichiers de l'utilisateur : ils n'ont **pas** été modifiés par cet agent, seulement lus et exécutés comme
preuve.
- `backend/src/test/java/fr/backyard/persistence/RacePersistenceTest.java` (R1-1, R1-2)
- `backend/src/test/java/fr/backyard/service/ReintegrationServiceTest.java` (R2-1)
- `backend/src/test/java/fr/backyard/service/YardClosingServiceTest.java` (R2-1)
- `docs/specs/increment1.md` (addendum, R1-3)
- `backend/src/test/java/fr/backyard/api/ApiSourceReviewTest.java` (R3-1)
- `backend/src/test/java/fr/backyard/config/SecurityConfigurationReviewTest.java` (R3-1)
- `backend/src/test/java/fr/backyard/api/SessionApiSliceTest.java`, `service/SessionServiceTest.java` (CA1,
  développeur)

Nouveaux tests ajoutés par cet agent (contexte Spring complet, sécurité réelle, JPA réelle) :
- `backend/src/test/java/fr/backyard/it/PwaStaticResourcesIT.java` (11 méthodes dont 2 paramétrées, 19 cas — CA3).
- `backend/src/test/java/fr/backyard/it/NoCorsIT.java` (3 méthodes — CA4).
- `backend/src/test/java/fr/backyard/it/SecurityHeadersIT.java` (2 méthodes, 1 paramétrée — CA6).
- `backend/src/test/java/fr/backyard/it/NoTestEndpointsIT.java` (2 méthodes — CA7).

## 2. Couverture exigences ↔ tests

### A. CA de `docs/specs/increment4.md` (périmètre de cet agent)

| Exigence | Critère d'acceptation | Tests | Résultat | Écart ? |
|---|---|---|---|---|
| INC4-CA1 | E19 `GET /api/scan/me` (RG52, RG6) | `api/SessionApiSliceTest.java` (3 méthodes, développeur) : `@WebMvcTest` avec `SecurityConfig` et `SessionService` réels (pas de mock), `Clock` fixe | PASS (3/3) | Non. Test déjà suffisant : 200 SCANNER/ADMIN avec le corps exact, 401 anonyme sans `WWW-Authenticate`, 401 mot de passe faux, sans `Set-Cookie` dans les deux cas |
| INC4-CA2 | E19 sans logique dans le controller ; RG29 (inc. 3) inchangée | `api/ApiArchitectureTest.java#ca45_controllersDoNotDependOnPersistenceClockOrCalculators` (scanne tout `fr.backyard.api`, donc `SessionController`) ; `api/SecuritySliceTest.java` (24 méthodes, CA46 à CA55 inc. 3), non modifié, exécuté sans erreur | PASS (1 + 24/24) | Non |
| INC4-CA3 | Fichiers de la PWA servis par Spring Boot (RG53, RG5) | **Nouveau** `it/PwaStaticResourcesIT.java` (19 cas, détail section 1) | PASS (19/19) | Non (comblé) |
| INC4-CA4 | Aucun CORS (RG54) | **Nouveau** `it/NoCorsIT.java` (3 méthodes) | PASS (3/3) | Non (comblé) |
| INC4-CA6 | En-têtes de sécurité (RG55) | **Nouveau** `it/SecurityHeadersIT.java` (3 cas) | PASS (3/3) | Non (comblé) |
| INC4-CA7 | Aucune API de test (RG56) | **Nouveau** `it/NoTestEndpointsIT.java` (2 méthodes) | PASS (2/2) | Non (comblé) |
| INC4-CA5 | Build intégré (RG59, PO4) | `mvn -B -f backend/pom.xml clean verify` (section 3) | PASS | Non, hors périmètre de cet agent (pas de test `*IT` écrit ici) : preuve d'exécution seulement |
| INC4-CA8 à CA20 | Logique front pure | Vitest (170 tests), voir section 3 | PASS | Hors périmètre de cet agent (`testeur`) |
| INC4-CA21 à CA38 | Parcours E2E | — | — | Hors périmètre de cet agent (`test-e2e-frontend`, `INC-4-e2e.md`) |

Exigences du périmètre de cet agent sans test : **aucune**.

### B. Réserves du patrimoine (échéance « avant la validation de l'INC-4 »)

| Réserve | Écart constaté | Constat (preuves) | Statut |
|---|---|---|---|
| R1-1 | Nom de test `ca23_...` laissant croire à un CA23 inexistant | `persistence/RacePersistenceTest.java` ligne 333 : méthode nommée `tech_raceAndRunnerSettersUpdateFields`, corps inchangé (mêmes assertions). Exécution : 22/22 `PASS` sur la classe entière | **LEVÉE** |
| R1-2 | Relecture depuis le cache de premier niveau (pas de `clear()`) ; CA21 acceptait n'importe quelle `Exception` | Méthode privée `flushAndClearPersistenceContext()` (`entityManager.flush()` puis `.clear()`), appelée avant chaque relecture des CA concernés (`ca1_`, `ca3_`, `ca5_`, `ca10_`, `ca11_`, `ca12_`, `ca22_`), avec assertion systématique `isNotSameAs(saved)`. `ca21_nullRaceNameThrows` restreint désormais l'exception à `ConstraintViolationException` et vérifie `getConstraintViolations()` sur la propriété `name`. Exécution : 22/22 `PASS` | **LEVÉE** |
| R1-3 | RG11, RG15, CL12, contraintes `CHECK` de RG1/RG6/RG13 sans CA dédié | `docs/specs/increment1.md`, section « Addendum (2026-09-26) » : nouveaux CA23 à CA31 rédigés, table de couverture RG/CL → CA mise à jour (section A5), point ouvert PO9 ajouté. **Aucun test n'existe pour CA23 à CA27** (recherche exhaustive de méthodes `ca23_` à `ca27_` dans `backend/src/test/java` : aucune ne correspond au contenu de l'addendum). CA30 et CA31 sont déjà couverts (ils formalisent des tests existants, `it/SchemaAndContextStartupIT.java#deletingRaceWithRunnersIsBlockedByForeignKeyRestrict` et `#deletingRunnerWithPassagesIsBlockedByForeignKeyRestrict`) | **PARTIELLEMENT LEVÉE** : spec écrite, tests CA23-CA27 manquants (voir section 7) |
| R2-1 | Assertions partielles sur CA31, CA32 (`YardClosingServiceTest`) et CA48 (`ReintegrationServiceTest`) | `service/YardClosingServiceTest.java#ca31_parallelRacesAreClosedIndependently` : assertion explicite `r1.getStatus() == FINISHED` (commentaire « sans finisher du yard 2 ») et détail complet des deux `YardClosingResult`. `#ca32_failureOnOneRaceDoesNotBlockOthers` : assertion `r2.getStatus() == RUNNING`. `service/ReintegrationServiceTest.java#ca48_noReDnfOnRecreatedYards` : `atYard5.timedOutRunnerIds()` vide, `assertStillActive(a, c)`, `r1.getStatus() == RUNNING`. Exécution : 17/17 et 11/11 `PASS` | **LEVÉE** |
| R3-1 | CA45/CA56 (inc. 3), partie « revue de code », sans test automatisé | `api/ApiSourceReviewTest.java` (3 méthodes : analyse textuelle de `fr.backyard.api`, aucune condition métier ; garde-fou anti-test-vide) et `config/SecurityConfigurationReviewTest.java` (3 méthodes : `PasswordEncoder` effectif = `BCryptPasswordEncoder` exact, aucune API interdite dans `backend/src/main`, garde-fou anti-test-vide). Exécution : 3/3 et 3/3 `PASS` | **LEVÉE** |

### C. RT1 (PostgreSQL réel)

**Non exécuté, environnement indisponible.** Vérifié explicitement sur ce poste : ni `docker` ni `psql` ne
sont disponibles (aucune tentative d'installation, conformément à la consigne). Aucun résultat n'est cité pour
RT1. La réserve reste ouverte, bloquante avant tout déploiement sur le VPS, comme déjà consigné dans
`docs/tests/PATRIMOINE.md` depuis l'INC-1.

## 3. Résultats d'exécution (réels)

| Suite | Total | Passés | Échoués | Ignorés | Durée |
|---|---|---|---|---|---|
| Tests de l'incrément, unitaires (`-Dgroups=INC-4`) | 3 | 3 | 0 | 0 | 3.91 s |
| Tests de l'incrément, intégration (`-Dgroups=INC-4`) | 27 | 27 | 0 | 0 | 8.59 s (somme des classes) |
| Non-régression — unitaires (surefire, suite complète) | 267 | 267 | 0 | 0 | — |
| Non-régression — front (Vitest, `npm test`) | 170 (14 fichiers) | 170 | 0 | 0 | — |
| Non-régression — intégration (failsafe, `*IT`, suite complète) | 41 | 41 | 0 | 0 | — |

Commande(s) exécutée(s) et réellement observées :
```
mvn -B -f backend/pom.xml verify -Dgroups=INC-4 -Djacoco.check.skip=true
mvn -B -f backend/pom.xml clean verify
```
(la première commande a été affinée avec `-Djacoco.check.skip=true` au lieu de `-Djacoco.skip=true` : le
`check` JaCoCo doit rester actif même en filtrant les tests, pour ne pas masquer une régression de couverture ;
`-Dfrontend-maven-plugin.skip=true` a été utilisé pour les itérations rapides pendant l'écriture des tests,
**jamais** pour le résultat final cité ci-dessus ni pour la commande de non-régression, exécutée intégralement).

Détail de la suite complète `mvn -B -f backend/pom.xml clean verify` (2026-09-26, code de sortie 0, durée
totale **1 min 22 s**) :
- **Backend, unitaires (surefire)** : `Tests run: 267, Failures: 0, Errors: 0, Skipped: 0`.
- **Front, build** : `npm ci` puis `ng build --configuration production` exécutés sans erreur (phase
  `generate-resources`), sortie copiée dans `target/classes/static` (`index.html`, `manifest.webmanifest`,
  `ngsw-worker.js`, `ngsw.json`, `main-EFMFOXBL.js`, `styles-VFV7HHMS.css`, tous vérifiés présents).
- **Front, unitaires (Vitest)** : `Test Files  14 passed (14)`, `Tests  170 passed (170)`. Couverture :
  Statements 99,42 % (522/525), Branches 97,38 % (373/383), Functions 99,38 % (161/162), **Lines 99,41 %
  (507/510)** — seuil bloquant 80 % largement dépassé.
- **Backend, intégration (failsafe, `*IT`)** : `Tests run: 41, Failures: 0, Errors: 0, Skipped: 0`, détail par
  classe : `EndToEndRaceLifecycleIT` 1, `NoCorsIT` 3, `NoTestEndpointsIT` 2, `PwaStaticResourcesIT` 19,
  `QrTokenUniquenessIT` 1, `RaceBoardTransactionBoundaryIT` 2, `RepositoryDerivedQueriesIT` 6,
  `SchemaAndContextStartupIT` 4, `SecurityHeadersIT` 3 (14 pré-existants + 27 nouveaux de cet agent).
- **JaCoCo, `check`** : `All coverage checks have been met.` `BUILD SUCCESS`.

**Couverture JaCoCo unitaire** (`backend/target/site/jacoco/jacoco.csv`, colonnes `LINE_MISSED` /
`LINE_COVERED`, alimentée par `target/jacoco.exec` — surefire seul, séparé de `jacoco-it.exec` depuis la
correction du `pom.xml` lors du rattrapage INC-1/2/3) :
- `fr.backyard.domain` : 180/185 lignes = **97,30 %**
- `fr.backyard.service` : 307/310 lignes = **99,03 %**
- ensemble domain+service : 487/495 lignes = **98,38 %**

Seuil exigé (CLAUDE.md) : 80 % — largement dépassé, cohérent avec l'INC-3 (les quelques lignes
supplémentaires viennent de `SessionService`, nouveau dans `fr.backyard.service`).

**RT3 — « compile sans warning »** : la sortie complète du build (`/tmp/full_verify.log`, 1216 lignes,
relecture intégrale) ne contient **aucune ligne `[WARNING]` ni `[ERROR]`** de Maven. Les seules occurrences du
mot « warning » sont des avertissements JVM/agent (`WARNING: A Java agent has been loaded dynamically...`,
attachement dynamique de Byte Buddy par Mockito pour l'`inline-mock-maker`, et un avertissement de partage de
classes du JVM HotSpot) : sans rapport avec le code de production ni le code de test, présents à l'identique
dans les exécutions précédentes de ce projet. Constat : exécution propre pour ce build. Limite : `-Xlint:all`
n'est pas rendu bloquant (`-Werror`) dans `maven-compiler-plugin` — un warning Java existant ne ferait donc
pas échouer le build ; ce n'est donc le constat d'une exécution sans warning que pour ce run précis, pas une
garantie structurelle du `pom.xml`.

## 4. Échecs et bugs détectés

**Aucun bug applicatif.** Une **limite d'environnement de test** a été rencontrée et documentée (pas un bug
de production, pas contourné en modifiant le code de production) :

| ID | Test | Attendu (avant investigation) | Obtenu | Cause | Résolution |
|---|---|---|---|---|---|
| LIM-1 | CA3 (routes du front, `GET /`) sous `MockMvc` avec `AbstractApiIT` (`@AutoConfigureMockMvc`, environnement simulé) | 200, `Content-Type: text/html`, corps = `index.html` | 200, `Content-Type` absent (`null`), corps vide, `Forwarded URL: /index.html` correct | Le renvoi interne (`forward:/index.html`, produit par `ParameterizableViewController` pour les routes de `PwaPaths.FRONT_ROUTES`) cible un `ResourceHttpRequestHandler`, pas un `@Controller`. Le dispatcher de test (`TestDispatcherServlet`) de `MockMvc` en environnement simulé ne rejoue pas ce second aller jusqu'au bout dans ce cas : le `Forwarded URL` est enregistré, mais la ressource cible n'est pas réellement servie une seconde fois. Reproduit isolément avec un test de débogage direct (`GET /index.html` fonctionne, `GET /scan` avec `forward:` ne fonctionne pas, même contexte) | `PwaStaticResourcesIT` réécrit sur un vrai serveur embarqué (`@SpringBootTest(webEnvironment = RANDOM_PORT)`, `RestTemplate` nu pointé sur `@LocalServerPort`), où le conteneur Servlet réel exécute le `forward` intégralement. `CA4` et `CA6` restent sur `MockMvc`/`AbstractApiIT` : les en-têtes vérifiés (CORS, sécurité) sont posés par les filtres Spring Security sur la requête d'origine, avant tout `forward`, donc observables même sans rejeu complet |

## 5. Modifications du patrimoine existant

Ajout uniquement par cet agent, aucune modification ni suppression d'un test existant :
- `backend/src/test/java/fr/backyard/it/PwaStaticResourcesIT.java` (nouveau).
- `backend/src/test/java/fr/backyard/it/NoCorsIT.java` (nouveau).
- `backend/src/test/java/fr/backyard/it/SecurityHeadersIT.java` (nouveau).
- `backend/src/test/java/fr/backyard/it/NoTestEndpointsIT.java` (nouveau).
- `docs/tests/PATRIMOINE.md` mis à jour : nouvelle section « Incrément 4 » (CA1 à CA7, périmètre de cet
  agent), corrections apportées aux sections INC-1 (renommage R1-1, note R1-2, addendum CA23-CA31 avec écart
  maintenu pour CA23-CA27), INC-2 (note R2-1) et INC-3 (note R3-1), et mise à jour de la table « Écarts
  ouverts » (R1-1, R1-2, R2-1, R3-1 marquées levées ; R1-3 partiellement levée ; RT1 marquée non exécutée ;
  RT3 marquée levée pour cette exécution).

Aucune dépendance Maven ajoutée. Aucun fichier de `backend/src/main` modifié. Aucun fichier appartenant à
l'utilisateur (`RacePersistenceTest.java`, `ReintegrationServiceTest.java`, `YardClosingServiceTest.java`,
`docs/specs/increment1.md`, `ApiSourceReviewTest.java`, `SecurityConfigurationReviewTest.java`,
`SessionApiSliceTest.java`, `SessionServiceTest.java`) n'a été modifié : seulement lus et exécutés.

## 6. Tests instables ou en quarantaine

Aucun. Les 30 tests tagués `INC-4` (3 unitaires + 27 d'intégration) et les 41 tests `*IT` de la suite complète
sont passés de façon reproductible sur deux exécutions consécutives.

## 7. Risques et limites

- **R1-3 seulement partiellement levée** : l'addendum de spec (`docs/specs/increment1.md`, CA23 à CA31) est
  écrit, mais **CA23 à CA27 n'ont aucun test**. C'est un écart réel, consigné dans `docs/tests/PATRIMOINE.md`
  (section « Addendum INC-1 »), à traiter par le `testeur` avant que la réserve R1-3 puisse être totalement
  levée. Cet agent n'a pas écrit ces tests : ils dépassent son périmètre (écriture de tests unitaires/
  persistance pour un incrément antérieur au sien, hors de la chaîne d'intégration de l'INC-4) et l'aurait
  fait sans mandat explicite de l'orchestrateur pour ce travail précis.
- **RT1 non exécuté** : aucune exécution sur un vrai PostgreSQL (ni Docker ni `psql` disponibles sur ce
  poste). Reste une réserve bloquante avant tout déploiement sur le VPS. Les écarts propres à PostgreSQL
  (différences de traduction d'exceptions, de contraintes `CHECK`, de types `TIMESTAMP WITH TIME ZONE`) ne
  sont donc toujours pas exclus par cette suite de tests, uniquement exécutée sur H2 en mode PostgreSQL.
- **LIM-1** (section 4) : limite de `MockMvc` en environnement simulé pour le rejeu d'un `forward:` vers un
  `ResourceHttpRequestHandler`. Contournée par un test sur serveur embarqué réel pour CA3 uniquement (CA4 et
  CA6 restent valablement sur `MockMvc`, voir justification section 4). Cette limite ne remet pas en cause le
  comportement de production (vérifié par ce même test sur un vrai conteneur Servlet), seulement l'outillage
  de test choisi.
- **RT2** (E2E) : hors périmètre de ce rapport, voir `INC-4-e2e.md` (agent `test-e2e-frontend`).
- **CA5** (build) : cité en preuve d'exécution (section 3) mais pas testé par un `*IT` de cet agent ; le
  contrôle négatif exigé par CA5 (« avec un test unitaire front volontairement en échec, la commande se
  termine avec un code différent de 0 ») n'a pas été rejoué par cet agent — à la charge du `testeur`, dont le
  verdict technique OK couvre déjà CA5 selon l'énoncé de la tâche.
- Comme pour les incréments 1 à 3, H2 en mode PostgreSQL ne remplace pas un vrai serveur PostgreSQL (RT1).

## 8. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : GO / GO sous réserves / NO-GO
- **Réserves ou motifs** :
- **Actions correctives exigées** :
- **Date** :
