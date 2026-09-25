# Spec Incrément 3 — API REST

> Projet : Backyard Ultra Tracker
> Date de rédaction : 2026-09-25 (révision 2 : PO1 tranché, option B Spring Security HTTP Basic ; PO8, PO10, PO17 confirmés. Révision 3 : alignement sur le code après verdict OK du testeur, RG1, RG6, RG22, RG32, E18, CA37, CA38)
> Statut : en attente de validation des nouveaux points ouverts de sécurité PO19 à PO28 (section 8, hypothèses par défaut déjà appliquées)
> Prérequis : incréments 1 et 2 mergés dans `main` (entités, repositories, `YardCalculator`, `RunnerStatsCalculator`, `PassageRecordingService`, `ManualDnfService`, `ReintegrationService`, `YardClosingService`, exceptions `fr.backyard.service.exception`)
> Numérotation RG / CA / PO propre à cet incrément. Les références à l'incrément 2 sont notées « RG12 (inc. 2) », « PO15 (inc. 2) », etc. Révision 2 : RG28 à RG34 et CA46 à CA57 ajoutés, rien n'est renuméroté ; les éléments modifiés sont marqués « (révisé) ».

---

## 1. Périmètre *(révisé)*

### Inclus

- API REST JSON (Spring MVC) :
  - courses : création, liste, détail, modification, suppression, démarrage ;
  - coureurs : inscription publique par course, liste, détail, modification, suppression ;
  - scan d'un passage par `qr_token` (utilisé par la PWA de l'incrément 4), délégué à `PassageRecordingService` ;
  - actions admin : DNF manuel (délégué à `ManualDnfService`), réintégration (déléguée à `ReintegrationService`) ;
  - lecture des valeurs dérivées : tableau de bord d'une course (yard courant, tours, distance, D+, allure, badge corrigé par coureur) et détail d'un coureur avec ses passages.
- Nouveaux services métier nécessaires à l'API (CRUD, démarrage, inscription, lecture du tableau de bord), dans `fr.backyard.service`, testables sans Spring ni base de données.
- Nouvelles méthodes de domaine : `Race.start(Instant)` (transition unique SETUP → RUNNING, PO19 inc. 2) et `Race.isRegistrationOpen()`.
- Gestion d'erreurs unique au format RFC 9457 (`ProblemDetail`).
- **Sécurité (PO1 tranché, option B)** : Spring Security en HTTP Basic, deux comptes ADMIN et SCANNER définis par variables d'environnement, matrice d'accès par préfixe, API sans état (section 3.G).
- Tranchage ou report explicite des points ouverts PO15, PO16, PO17, PO22 et PO26 de l'incrément 2 (section 7).

### Explicitement exclu

- Frontend PWA, file locale de scans, lecture caméra (incrément 4).
- Terminaison TLS : l'HTTPS obligatoire en production est assuré par l'infrastructure (RG34, PO24).
- CORS (PO23).
- Comptes nominatifs, gestion des utilisateurs en base, rotation automatique des mots de passe (PO26).
- Endpoint de clôture manuelle d'un yard (RG25, PO4).
- Outil d'arbitrage d'une course terminée (PO5).
- Verrouillage de concurrence scan / clôture (PO6).
- Classement (PO7).
- Toute modification des règles métier de l'incrément 2 : l'API délègue et ne réimplémente rien.

### Impact schéma

**Aucune migration n'est nécessaire.** Les comptes sont définis par variables d'environnement, pas en base. Une migration `V2__...sql` ne serait requise que si l'une des alternatives suivantes était retenue : identifiant public opaque d'inscription (PO16), verrou de version (PO6). `V1__init.sql` n'est jamais modifiée.

### Dépendances Maven *(révisé)*

Le `pom.xml` actuel (Spring Boot 4.1.1) ne contient ni starter web ni starter de sécurité. À ajouter (le développeur vérifie les coordonnées exactes pour Boot 4.1.1) :
- Spring MVC : `spring-boot-starter-webmvc` ; test de slice web : `spring-boot-starter-webmvc-test` (scope test) ;
- Spring Security : `spring-boot-starter-security` ; test : `spring-boot-starter-security-test` (scope test ; à défaut `org.springframework.security:spring-security-test`).
Bean Validation est déjà présent.

---

## 2. Architecture et contrat technique *(révisé)*

### Packages

| Package | Contenu | Couverture 80 % exigée |
|---|---|---|
| `fr.backyard.api` | Controllers REST | non |
| `fr.backyard.api.dto` | DTO de requête et de réponse (records) | non |
| `fr.backyard.api.error` | `ApiExceptionHandler` (`@RestControllerAdvice`), fabrique commune des `ProblemDetail` utilisée aussi par la sécurité | non |
| `fr.backyard.config` | `SecurityConfig` (bean `SecurityFilterChain`), `BackyardSecurityProperties`, gestionnaires 401/403 | non |
| `fr.backyard.service` | Services existants + nouveaux services ci-dessous | **oui** |
| `fr.backyard.domain` | Entités, calculateurs, nouvelles méthodes `Race.start`, `Race.isRegistrationOpen` | **oui** |

### Services existants appelés par l'API (signatures réelles, inchangées)

- `PassageRecordingService.recordScan(String qrToken, Instant scannedAt) : Passage`
- `ManualDnfService.declareDnf(Long runnerId, DnfReason reason) : Runner`
- `ReintegrationService.reintegrate(Long runnerId) : List<Passage>`
- `YardCalculator.currentYard(Race, Instant) : int`, `YardCalculator.yardEnd(Race, int) : Instant`
- `RunnerStatsCalculator.compute(Race, List<Passage>) : RunnerStats`, `RunnerStatsCalculator.loopTimeMillis(Race, Passage) : OptionalLong`, `RunnerStatsCalculator.isCorrected(Passage) : boolean` (statique)
- `YardClosingService` : **non exposé** par l'API (RG25).

### Nouveaux services (noms proposés, à respecter) *(révisé)*

- `RaceService(RaceRepository, RunnerRepository, Clock)` :
  `Race create(RaceCommand)`, `List<Race> list()`, `Race get(Long raceId)`, `Race update(Long raceId, RaceCommand)`, `void delete(Long raceId)`, `Race start(Long raceId)`.
- `RaceCommand` (record, package service) : `String name`, `LocalDate raceDate`, `int loopDistance`, `int loopDuration`, `int loopElevation`.
- `RunnerService(RaceRepository, RunnerRepository, PassageRepository, QrTokenGenerator)` :
  `Runner register(Long raceId, String name)`, `List<Runner> listByRace(Long raceId)`, `Runner get(Long runnerId)`, `Runner update(Long runnerId, int bib, String name)`, `void delete(Long runnerId)`.
- `QrTokenGenerator` : `String generate()` (bean injecté, remplaçable en test).
- `RaceBoardService(RaceRepository, RunnerRepository, PassageRepository, Clock)` (lecture, `@Transactional(readOnly = true)`) :
  `RaceBoardView board(Long raceId)`, `RunnerDetailView runnerDetail(Long runnerId)`, `List<PassageView> describePassages(Long runnerId, List<Passage> passages)` (vues de passages avec `loopTimeMillis` et `corrected` calculés par `RunnerStatsCalculator`, utilisée par la réintégration, RG22).
- Vues de lecture (records, package service) : `RaceBoardView`, `RunnerBoardEntry`, `RunnerDetailView`, `PassageView`. Leurs champs sont ceux des DTO de réponse correspondants (section 4).

### Nouvelles méthodes de repository autorisées

- `RaceRepository` : `boolean existsByName(String name)`, `boolean existsByNameAndIdNot(String name, Long id)`, `findAll(Sort)` (hérité).
- `RunnerRepository` : `boolean existsByRaceIdAndBib(Long raceId, int bib)`, `boolean existsByRaceIdAndBibAndIdNot(Long raceId, int bib, Long id)`.
- `PassageRepository` : `boolean existsByRunnerId(Long runnerId)`, `List<Passage> findByRunnerRaceId(Long raceId)` (tous les passages d'une course en une requête, pour le tableau de bord interrogé toutes les 2 à 3 s).

### Tests *(révisé)*

- Controllers : tests de slice `@WebMvcTest` avec services mockés (`@MockitoBean`), sans base de données. **Chaque classe de test de slice importe la configuration de sécurité réelle** (`@Import({SecurityConfig.class, ...})`, `@WebMvcTest` ne chargeant pas les classes `@Configuration`) et active le profil `test` pour disposer des comptes de test (RG32). Les requêtes des CA de sécurité (CA46 à CA55) portent un vrai en-tête `Authorization` ; les autres CA de slice peuvent utiliser `httpBasic(...)` de `spring-security-test` avec les comptes de test. `@WithMockUser` n'est pas utilisé pour les CA de sécurité (il contournerait le filtre HTTP Basic).
- Nouveaux services et méthodes de domaine : tests unitaires JUnit 5 + Mockito, `Clock.fixed(..., ZoneOffset.UTC)`.
- Validation des identifiants au démarrage : `ApplicationContextRunner` (sans base de données, CA57).
- Tout test qui démarre le contexte complet (`@SpringBootTest`) doit fournir les propriétés de sécurité de test, sinon le démarrage échoue par construction (RG32).

---

## 3. Règles de gestion

### A. Architecture

**RG1 — Controllers sans logique métier** *(révisé)*
Un controller : (1) désérialise et valide le DTO de requête (Bean Validation, RG8), (2) appelle une méthode de service métier, éventuellement suivie d'autres appels de service (lecture) pour construire la réponse, (3) copie les champs des résultats dans le DTO de réponse, (4) renvoie le statut HTTP. Plusieurs appels de service successifs sont autorisés à condition qu'aucune logique ne s'intercale entre eux : le résultat d'un appel peut être transmis tel quel à l'appel suivant, sans condition, calcul ni transformation (révision 3, cf. RG22). Il n'injecte ni repository, ni `Clock`, ni calculateur, et ne contient aucune condition métier (statut, yard, dossard...), ni aucun contrôle d'accès (porté par la configuration de sécurité, RG29).

**RG2 — Frontière de transaction**
`spring.jpa.open-in-view=false` est conservé. Les controllers ne lisent que des champs simples (id, valeurs, enums) d'objets retournés par les services, ou des vues de lecture construites par un service dans sa transaction. Aucune association paresseuse n'est initialisée hors transaction. Les services ne dépendent jamais du package `fr.backyard.api` (ils reçoivent des types primitifs, des enums du domaine ou des `...Command`).

**RG3 — Format JSON**
- `Instant` : chaîne ISO-8601 en UTC suffixée `Z` (ex. `"2026-10-03T08:00:00Z"`).
- `LocalDate` : `"yyyy-MM-dd"`.
- Enums : leur nom (`"SETUP"`, `"DNF"`, `"VOLUNTARY"`, `"SCAN"`...).
- Valeur dérivée non définie (allure sans scan, temps de boucle d'un passage MANUAL, `startedAt` d'une course SETUP, `dnfReason` d'un coureur ACTIVE) : champ présent avec la valeur `null`.
- Réponses : `application/json` ; erreurs : `application/problem+json`.

**RG4 — Espaces d'URL** *(révisé)*
Toutes les URL de l'API sont regroupées sous trois préfixes, chacun associé à un niveau d'accès défini par la matrice de RG29 :
- `/api/public/**` : consultation et inscription, sans donnée secrète, accès libre ;
- `/api/scan/**` : enregistrement des passages, rôles SCANNER et ADMIN ;
- `/api/admin/**` : gestion et actions sensibles, rôle ADMIN.
Toute URL hors de ces préfixes est refusée par défaut (RG29).

### B. Erreurs

**RG5 — Corps d'erreur unique (RFC 9457)** *(révisé)*
Toute erreur, y compris les refus d'authentification (401) et d'autorisation (403) produits par la chaîne de filtres de sécurité avant l'appel d'un controller, renvoie un `ProblemDetail` (`application/problem+json`) avec : `type` (`about:blank`), `title`, `status`, `detail` (message exploitable), `instance` (chemin de la requête) et une propriété d'extension `code` (chaîne stable, utilisable par la PWA). Les réponses de sécurité utilisent la même fabrique de `ProblemDetail` que `ApiExceptionHandler` (pas de format dupliqué). La PWA de l'incrément 4 s'appuie sur ce statut : 401/403 = identifiants à corriger, autres 4xx = rejet définitif (pas de nouvel essai), 5xx = nouvel essai possible.

**RG6 — Table de correspondance des erreurs** *(révisé, révision 3 : exceptions Spring MVC 4xx non listées)*

| Exception / situation | Statut | `code` | `detail` |
|---|---|---|---|
| Aucun identifiant, identifiants faux ou en-tête `Authorization: Basic` illisible (RG30) | 401 | `UNAUTHENTICATED` | « authentification requise » ou « identifiants invalides », sans préciser si c'est le nom ou le mot de passe qui est faux |
| Authentifié avec un rôle insuffisant (RG30) | 403 | `ACCESS_DENIED` | « accès refusé pour le rôle X sur ce chemin » |
| `ResourceNotFoundException` | 404 | `RESOURCE_NOT_FOUND` | message de l'exception |
| `BusinessConflictException` | 409 | `BUSINESS_CONFLICT` | message de l'exception |
| `InvalidInputException` | 400 | `INVALID_INPUT` | message de l'exception |
| `MethodArgumentNotValidException` (Bean Validation) | 400 | `VALIDATION_FAILED` | résumé + propriété `errors` (RG8) |
| `HttpMessageNotReadableException` (JSON mal formé, enum inconnu, date invalide, corps absent) | 400 | `MALFORMED_REQUEST` | description de l'erreur de lecture, sans trace technique |
| `MethodArgumentTypeMismatchException` (ex. id non numérique) | 400 | `MALFORMED_REQUEST` | nom du paramètre et valeur reçue |
| Toute autre exception Spring MVC de statut 4xx non listée dans cette table (ex. 415 type de contenu non supporté, paramètre ou en-tête requis manquant, type de réponse non acceptable) *(révisé)* | 400 | `MALFORMED_REQUEST` | description de l'erreur de requête, sans trace technique |
| `DataIntegrityViolationException` (contrainte en base non anticipée, ex. inscriptions simultanées) | 409 | `DATA_INTEGRITY` | message générique « conflit d'intégrité des données, réessayer » (sans SQL) |
| `IllegalStateException`, `IllegalArgumentException` | 500 | `INTERNAL_INCONSISTENCY` | message de l'exception (RG7) |
| Chemin inconnu **sous un préfixe auquel l'appelant a accès** | 404 | `RESOURCE_NOT_FOUND` | chemin demandé |
| Méthode HTTP non supportée (appelant autorisé sur le préfixe) | 405 | `METHOD_NOT_ALLOWED` | méthode et chemin |
| Toute autre exception, réellement inattendue (hors exceptions Spring MVC de statut 4xx) *(révisé)* | 500 | `INTERNAL_ERROR` | message générique, sans message ni trace de l'exception |

Les contrôles de sécurité passent avant tout le reste : un chemin inconnu ou une méthode non supportée sous `/api/admin/**` donne 401 sans authentification et 403 avec le compte SCANNER. Toutes les erreurs 5xx sont journalisées au niveau ERROR avec la trace ; les 4xx au niveau WARN sans trace (les 401 sans le mot de passe ni l'en-tête `Authorization`). Aucune exception n'est avalée.

**RG7 — Exceptions du domaine (IllegalStateException / IllegalArgumentException)**
Les services de l'incrément 2 et de cet incrément vérifient les préconditions **avant** d'appeler les méthodes de transition du domaine et lèvent alors les exceptions métier (404/409/400). Une `IllegalStateException` ou `IllegalArgumentException` qui atteint l'API signale donc une donnée incohérente en base (ex. « course RUNNING sans started_at ») ou une erreur de programmation. Elle est renvoyée en **500** avec `code = INTERNAL_INCONSISTENCY` et son message en `detail` (le message du domaine est explicite et ne contient pas de donnée sensible), et journalisée en ERROR. Elle n'est jamais convertie silencieusement en 4xx (PO17, confirmé).

**RG8 — Validation des requêtes**
Les DTO de requête portent des contraintes Bean Validation de **format** (présence, bornes, longueur) ; les règles métier restent dans les services. En cas d'échec, le service n'est pas appelé et la réponse 400 contient `errors` : liste de `{ "field": <nom du champ>, "message": <message> }`, un élément par violation.

### C. Courses

**RG9 — Création d'une course**
`RaceService.create(RaceCommand)` :
1. si une course porte déjà ce `name` (`existsByName`) : `BusinessConflictException` (409) citant le nom ;
2. sinon, création par `new Race(name, raceDate, loopDistance, loopDuration, loopElevation)` : `status = SETUP`, `startedAt = null`.
Validation de format (RG8) : `name` non vide, 255 caractères max ; `raceDate` obligatoire ; `loopDistance` obligatoire et > 0 ; `loopDuration` obligatoire et > 0 ; `loopElevation` obligatoire et >= 0 (cohérent avec les contraintes de V1).
Réponse : 201, en-tête `Location: /api/admin/races/{id}`.

**RG10 — Liste et détail des courses**
- `list()` : toutes les courses, triées par `raceDate` croissante puis `id` croissant.
- `get(raceId)` : course ou `ResourceNotFoundException` (404) « Course introuvable : id N ».
Les mêmes données sont exposées en public (`/api/public/races`) et en admin : une course ne contient aucune donnée secrète.

**RG11 — Modification d'une course (tranche PO15 inc. 2)**
`RaceService.update(raceId, RaceCommand)`, remplacement complet (PUT) :
1. course introuvable : 404 ;
2. `name` déjà porté par une **autre** course (`existsByNameAndIdNot`) : 409 ;
3. si la course n'est pas `SETUP` et qu'au moins un de `loopDistance`, `loopDuration`, `loopElevation` diffère de la valeur actuelle : 409 (« paramètres de boucle non modifiables : course au statut X ») ; renvoyer les mêmes valeurs est accepté ;
4. sinon : mise à jour de `name`, `raceDate` et, le cas échéant, des paramètres de boucle. `name` et `raceDate` restent modifiables quel que soit le statut.
`status` et `startedAt` ne sont jamais modifiables par cet endpoint (ils ne figurent pas dans la requête).

**RG12 — Suppression d'une course**
`RaceService.delete(raceId)` :
1. introuvable : 404 ;
2. statut `RUNNING` ou `FINISHED` : 409 (les données de course sont conservées) ;
3. statut `SETUP` : suppression des coureurs de la course (aucun passage ne peut exister en SETUP, les scans et la réintégration exigeant RUNNING), puis de la course (PO10, confirmé). Réponse 204 sans corps.

**RG13 — Démarrage d'une course (tranche PO19 inc. 2)**
- Domaine : `Race.start(Instant startedAt)` est la **seule** transition SETUP → RUNNING ; elle positionne `status = RUNNING` et `startedAt`. Appelée sur une course non SETUP : `IllegalStateException`, état inchangé.
- Service : `RaceService.start(raceId)` : 404 si introuvable ; 409 si la course n'est pas `SETUP` (contrôle avant l'appel au domaine) ; sinon `race.start(Instant.now(clock))` et sauvegarde. Aucune condition sur le nombre de coureurs ni sur `raceDate` (PO12).
- Réponse : 200 avec la course (`status = RUNNING`, `startedAt` = instant de l'horloge).

**RG14 — Inscriptions ouvertes**
`Race.isRegistrationOpen()` est l'unique définition : vrai si et seulement si `status = SETUP`. Elle est exposée dans la réponse course (`registrationOpen`) et utilisée par l'inscription (RG15).

### D. Coureurs

**RG15 — Inscription publique**
`RunnerService.register(raceId, name)` :
1. course introuvable : 404 ;
2. inscriptions fermées (`!race.isRegistrationOpen()`) : 409 (« inscriptions fermées : course au statut X ») ;
3. dossard attribué automatiquement : `bib = (plus grand bib des coureurs de la course, ou 0 s'il n'y en a aucun) + 1` (PO8, confirmé) ;
4. `qrToken = qrTokenGenerator.generate()` (RG16) ;
5. création par `new Runner(race, bib, name, qrToken)` : `status = ACTIVE`, champs DNF null.
Validation de format : `name` non vide, 255 caractères max.
Réponse : 201, `Location: /api/public/runners/{id}`, corps contenant le `qrToken` (seule réponse publique qui le contient : c'est le coureur inscrit qui le reçoit pour son QR code).
Une inscription simultanée produisant le même dossard est rejetée par la contrainte `uq_runner_race_bib` et renvoyée en 409 `DATA_INTEGRITY` (RG6) ; le client peut réessayer.

**RG16 — Génération et confidentialité du qr_token**
- Algorithme : `java.util.UUID.randomUUID().toString()` (UUID version 4 tiré d'un `SecureRandom`, **122 bits d'entropie**), forme canonique minuscule de 36 caractères, compatible avec la colonne `qr_token VARCHAR(36)` de V1. Probabilité de collision négligeable ; une collision éventuelle est rejetée par `uq_runner_qr_token` (409 `DATA_INTEGRITY`).
- Le token n'est jamais modifiable via l'API et n'est dérivé d'aucune donnée du coureur (non devinable).
- Il n'apparaît que dans : la réponse d'inscription (RG15) et les réponses `/api/admin/**` sur les coureurs (pour imprimer les QR codes). Il n'apparaît **jamais** dans les autres réponses `/api/public/**` ni dans la réponse de scan.

**RG17 — Liste et détail des coureurs (admin)**
- `listByRace(raceId)` : 404 si la course n'existe pas ; sinon coureurs de la course triés par `bib` croissant.
- `get(runnerId)` : coureur ou 404 « Coureur introuvable : id N ».

**RG18 — Modification d'un coureur**
`RunnerService.update(runnerId, bib, name)` :
1. coureur introuvable : 404 ;
2. si `bib` diffère du dossard actuel : 409 si la course n'est pas `SETUP` (le dossard physique est figé une fois la course partie, PO11) ; 409 si un autre coureur de la course a déjà ce dossard (`existsByRaceIdAndBibAndIdNot`) ;
3. `name` modifiable quel que soit le statut de la course (correction de faute de frappe).
`status`, `dnfReason`, `dnfYard`, `qrToken` et la course de rattachement ne sont pas modifiables par cet endpoint (absents de la requête ; les propriétés inconnues du JSON sont ignorées). Validation de format : `bib` obligatoire et > 0 ; `name` non vide, 255 caractères max.

**RG19 — Suppression d'un coureur**
`RunnerService.delete(runnerId)` :
1. introuvable : 404 ;
2. course non `SETUP` : 409 (en course, un abandon passe par le DNF manuel) ;
3. coureur ayant au moins un passage (`existsByRunnerId`) : 409 (les passages sont immuables et ne sont jamais supprimés) ;
4. sinon suppression, 204.

### E. Actions de course

**RG20 — Scan**
`POST /api/scan/passages` délègue à `PassageRecordingService.recordScan(qrToken, scannedAt)` sans aucun contrôle supplémentaire : attribution du yard, doublon idempotent, scan tardif, réactivation automatique et erreurs sont ceux de RG12 à RG15 et RG30 à RG32 (inc. 2).
- Validation de format : `qrToken` non vide (sinon 400 `VALIDATION_FAILED`, service non appelé). `scannedAt` n'est pas annoté : son absence est traitée par le service (`InvalidInputException`, 400), sans dupliquer la règle.
- Réponse : **200** dans tous les cas de succès, y compris le renvoi idempotent d'un passage existant (PO15), avec le passage et l'état du coureur **après** le scan (`runnerStatus = ACTIVE` après une réactivation automatique).

**RG21 — DNF manuel**
`POST /api/admin/runners/{runnerId}/dnf` délègue à `ManualDnfService.declareDnf(runnerId, reason)`. La raison est un nom d'enum `DnfReason` ; une valeur inconnue donne 400 `MALFORMED_REQUEST` sans appel au service ; `null` et `TIMEOUT` sont rejetés par le service (400 `INVALID_INPUT`). La confirmation demandée par CLAUDE.md est portée par l'interface admin (incrément 4, PO14). Réponse : 200 avec le coureur (`status = DNF`, `dnfReason`, `dnfYard`).

**RG22 — Réintégration** *(révisé)*
`POST /api/admin/runners/{runnerId}/reintegration` appelle successivement, sans logique entre les appels (RG1) :
1. `ReintegrationService.reintegrate(runnerId)`, qui renvoie les passages recréés ;
2. `RunnerService.get(runnerId)`, pour l'état du coureur après réintégration ;
3. `RaceBoardService.describePassages(runnerId, passages)`, avec les passages renvoyés à l'étape 1, qui construit les vues de passages en calculant `loopTimeMillis` et `corrected` par `RunnerStatsCalculator` (RG27) ; le controller ne fait aucun calcul.
Si l'étape 1 lève une exception, les étapes 2 et 3 ne sont pas exécutées et l'erreur est propagée (RG23).
Réponse : 200 avec le coureur (`status = ACTIVE`) et la liste des passages recréés (yard croissant, `source = MANUAL`, `scannedAt = null`, `loopTimeMillis = null`, `corrected = true`), éventuellement vide.

**RG23 — Propagation des erreurs des services existants**
Les exceptions des services de l'incrément 2 sont propagées telles quelles au gestionnaire d'erreurs (RG6) ; leur message devient le `detail`. Aucun controller ne les intercepte.

### F. Lecture des valeurs dérivées

**RG24 — Tableau de bord d'une course**
`RaceBoardService.board(raceId)`, avec `now = Instant.now(clock)` :
- 404 si la course n'existe pas ;
- `serverTime = now` (permet à la PWA de mesurer le décalage de son horloge, cf. PO6 inc. 2) ;
- `currentYard = YardCalculator.currentYard(race, now)` (0 si SETUP ou FINISHED) ;
- `currentYardEndsAt = YardCalculator.yardEnd(race, currentYard)` si `currentYard >= 1`, sinon `null` (heure de la prochaine cloche) ;
- pour chaque coureur de la course, trié par `bib` croissant : identité (`runnerId`, `bib`, `name`), `status`, `dnfReason`, `dnfYard`, et les champs de `RunnerStatsCalculator.compute(race, passagesDuCoureur)` : `completedLoops`, `distanceMeters`, `elevationMeters`, `averagePaceSecondsPerKm` (`null` si non défini), `corrected` ;
- les passages sont lus en une seule requête pour la course (`findByRunnerRaceId`) puis regroupés par coureur.
Pas de `qrToken`. Endpoint public.

**RG25 — Pas de clôture manuelle exposée (tranche PO26 inc. 2)**
Aucun endpoint n'appelle `YardClosingService`. La clôture reste exclusivement déclenchée par le planificateur (`YardClosingScheduler` → `closeElapsedYards()`, qui ne traite que les courses RUNNING). Le comportement de `closeYard(race)` appelé directement sur une course non RUNNING reste non spécifié et non exposé.

**RG26 — Détail public d'un coureur**
`RaceBoardService.runnerDetail(runnerId)` : 404 si introuvable ; sinon `runnerId`, `raceId`, `bib`, `name`, `status`, `dnfReason`, `dnfYard`, les statistiques de RG24, et la liste des passages triée par `yardNumber` croissant, chacun avec `yardNumber`, `source`, `scannedAt`, `loopTimeMillis` (`RunnerStatsCalculator.loopTimeMillis`, `null` si non défini) et `corrected` (`RunnerStatsCalculator.isCorrected`). Pas de `qrToken`.

**RG27 — Aucune valeur dérivée recalculée hors des calculateurs**
Yard courant, fin de yard, tours, distance, D+, allure, temps de boucle et badge corrigé sont exclusivement obtenus par `YardCalculator` et `RunnerStatsCalculator` (incrément 2). Aucun DTO, controller ou nouveau service ne réimplémente une de ces formules, et aucune n'est stockée.

### G. Sécurité (révision 2, PO1 tranché : option B)

**RG28 — Authentification HTTP Basic, deux comptes** *(nouvelle)*
- Mécanisme unique : HTTP Basic (en-tête `Authorization: Basic base64(nom:motdepasse)`) via Spring Security. Pas de formulaire de connexion, pas de page de déconnexion, pas de « remember-me », pas de jeton.
- Exactement deux comptes, en mémoire (`InMemoryUserDetailsManager`), définis par variables d'environnement (RG32) :
  - compte administrateur, rôle `ADMIN` (autorité `ROLE_ADMIN`) ;
  - compte scanner partagé par les bénévoles, rôle `SCANNER` (autorité `ROLE_SCANNER`).
- Les mots de passe sont vérifiés par un `BCryptPasswordEncoder` contre le hash fourni (RG32). Aucun mot de passe en clair n'est stocké ni journalisé.

**RG29 — Matrice d'accès, refus par défaut** *(nouvelle)*

| Chemin | Anonyme | SCANNER | ADMIN |
|---|---|---|---|
| `/api/public/**` | autorisé | autorisé | autorisé |
| `/api/scan/**` | 401 | autorisé | autorisé (PO19) |
| `/api/admin/**` | 401 | 403 | autorisé |
| toute autre URL | 401 | 403 | 403 |

- Règles déclarées dans cet ordre dans le `SecurityFilterChain` : `/api/public/**` → `permitAll()` ; `/api/scan/**` → `hasAnyRole("SCANNER", "ADMIN")` ; `/api/admin/**` → `hasRole("ADMIN")` ; `anyRequest()` → `denyAll()`.
- Les dispatchs internes de type `ERROR` sont autorisés (`dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()`), pour qu'une erreur déjà autorisée ne soit pas transformée en 401 lors du rendu de l'erreur.
- L'autorisation est entièrement portée par cette configuration : aucun controller ni service ne teste le rôle de l'appelant (RG1).

**RG30 — Réponses 401 et 403, sans challenge navigateur** *(nouvelle)*
- 401 `UNAUTHENTICATED` : requête sans identifiants sur un chemin protégé, identifiants faux (nom inconnu ou mot de passe incorrect), ou en-tête `Authorization: Basic` illisible (base64 invalide, absence de `:`). Des identifiants Basic **présentés mais invalides** donnent 401 **sur tous les chemins, y compris `/api/public/**`** (le filtre d'authentification rejette la requête avant l'autorisation, PO21). Un en-tête `Authorization` d'un autre schéma (ex. `Bearer ...`) est ignoré : la requête est traitée comme anonyme.
- 403 `ACCESS_DENIED` : requête authentifiée dont le rôle n'est pas autorisé par RG29.
- Corps : `ProblemDetail` de RG5, produit par un `AuthenticationEntryPoint` (401) et un `AccessDeniedHandler` (403) dédiés, réutilisant la fabrique commune.
- **Aucun en-tête `WWW-Authenticate` n'est envoyé** (ni `Basic`, ni autre schéma) : un challenge `Basic` déclencherait la fenêtre native d'identification du navigateur dans la PWA et la mise en cache automatique des identifiants par le navigateur. C'est un écart assumé à RFC 9110 (PO20).

**RG31 — API sans état, CSRF désactivé** *(nouvelle)*
- `SessionCreationPolicy.STATELESS` : aucune session HTTP n'est créée ni lue ; aucune réponse ne contient de cookie de session (`Set-Cookie: JSESSIONID`). Chaque requête protégée porte ses identifiants.
- Protection CSRF désactivée. Justification : le CSRF exploite des identifiants que le navigateur ajoute automatiquement (cookie de session, identifiants Basic mis en cache après un challenge). Ici, il n'y a ni cookie (sans état), ni challenge `WWW-Authenticate` (RG30), donc le navigateur n'ajoute jamais d'identifiants de lui-même : l'en-tête `Authorization` est posé explicitement par le code de la PWA, ce qu'un site tiers ne peut pas faire.
- `formLogin`, `logout` et la page de connexion par défaut sont désactivés.

**RG32 — Identifiants par variables d'environnement, refus de démarrer si incomplets** *(nouvelle ; révisé en révision 3)*
- Variables d'environnement (liées aux propriétés `backyard.security.*` d'une classe `BackyardSecurityProperties`, `@ConfigurationProperties`) :

| Variable d'environnement | Propriété | Contenu |
|---|---|---|
| `BACKYARD_SECURITY_ADMIN_USERNAME` | `backyard.security.admin.username` | nom du compte ADMIN |
| `BACKYARD_SECURITY_ADMIN_PASSWORD_HASH` | `backyard.security.admin.password-hash` | hash BCrypt du mot de passe ADMIN |
| `BACKYARD_SECURITY_SCANNER_USERNAME` | `backyard.security.scanner.username` | nom du compte SCANNER |
| `BACKYARD_SECURITY_SCANNER_PASSWORD_HASH` | `backyard.security.scanner.password-hash` | hash BCrypt du mot de passe SCANNER |

- Les variables `..._PASSWORD_HASH` contiennent un **hash BCrypt** (format `$2a$`, `$2b$` ou `$2y$`, coût à deux chiffres, 60 caractères), jamais le mot de passe en clair (PO22). Coût recommandé en production : 12.
- Au démarrage, l'application **refuse de démarrer** (exception au chargement du contexte) si : une des quatre valeurs est absente ou vide ; un hash ne respecte pas le format BCrypt ; les deux noms de compte sont identiques. Le message d'erreur nomme la **variable d'environnement** concernée (ex. « Variable d'environnement BACKYARD_SECURITY_ADMIN_PASSWORD_HASH manquante ou vide ») et ne contient jamais la valeur fournie.
- `application.properties` ne contient **aucune** valeur par défaut pour ces propriétés ; il n'existe aucun mot de passe par défaut, ni en production ni en développement. Spring Boot ne doit pas générer son utilisateur par défaut (`user` avec mot de passe aléatoire) : la présence du `UserDetailsService` défini par RG28 l'empêche.
- Valeurs de test *(révisé)* : `src/test/resources/application-test.properties` (profil `test`) contient déjà les deux comptes de test et leurs hash BCrypt : ADMIN `admin-test` / mot de passe `admin-secret` ; SCANNER `scanner-test` / mot de passe `scanner-secret`. Ces valeurs n'existent que dans les ressources de test.

**RG33 — Configuration Spring Security 7** *(nouvelle)*
- Une classe `fr.backyard.config.SecurityConfig` annotée `@Configuration` et `@EnableWebSecurity` déclare : un bean `SecurityFilterChain` construit avec le DSL lambda (`http.authorizeHttpRequests(auth -> ...)`, `http.httpBasic(basic -> basic.authenticationEntryPoint(...))`, `http.sessionManagement(s -> s.sessionCreationPolicy(STATELESS))`, `http.csrf(csrf -> csrf.disable())`, `http.formLogin(f -> f.disable())`, `http.logout(l -> l.disable())`, `http.exceptionHandling(e -> ...)`), un bean `UserDetailsService`, un bean `PasswordEncoder` (`BCryptPasswordEncoder`).
- Interdits : `WebSecurityConfigurerAdapter` (supprimé), les méthodes chaînées sans lambda (`.and()`), `antMatchers`/`mvcMatchers`, `NoOpPasswordEncoder`.

**RG34 — HTTPS obligatoire en production** *(nouvelle)*
En production, l'API n'est accessible qu'en HTTPS. HYPOTHÈSE (PO24) : la terminaison TLS et la redirection HTTP → HTTPS sont assurées par un reverse proxy sur le VPS ; l'application n'écoute que sur l'interface locale (`server.address=127.0.0.1` en production) et prend en compte les en-têtes du proxy (`server.forward-headers-strategy=framework`). HTTP Basic sans TLS exposerait les mots de passe en clair : ce point est une condition de mise en production, vérifiée à la recette d'infrastructure et non par les tests unitaires.

---

## 4. Endpoints *(révisé : colonne Accès ; révision 3 : E18)*

Légende des DTO (records dans `fr.backyard.api.dto`).

**Requêtes**
- `RaceRequest` : `name` String, `raceDate` LocalDate, `loopDistance` Integer, `loopDuration` Integer, `loopElevation` Integer.
- `RegistrationRequest` : `name` String.
- `RunnerUpdateRequest` : `bib` Integer, `name` String.
- `ScanRequest` : `qrToken` String, `scannedAt` Instant.
- `DnfRequest` : `reason` DnfReason.

**Réponses**
- `RaceResponse` : `id` Long, `name` String, `raceDate` LocalDate, `status` RaceStatus, `startedAt` Instant|null, `loopDistance` int, `loopDuration` int, `loopElevation` int, `registrationOpen` boolean.
- `RegistrationResponse` : `runnerId` Long, `raceId` Long, `bib` int, `name` String, `qrToken` String.
- `AdminRunnerResponse` : `id` Long, `raceId` Long, `bib` int, `name` String, `qrToken` String, `status` RunnerStatus, `dnfReason` DnfReason|null, `dnfYard` Integer|null.
- `ScanResponse` : `passageId` Long, `runnerId` Long, `bib` int, `runnerName` String, `runnerStatus` RunnerStatus, `yardNumber` int, `source` PassageSource, `scannedAt` Instant.
- `PassageResponse` : `yardNumber` int, `source` PassageSource, `scannedAt` Instant|null, `loopTimeMillis` Long|null, `corrected` boolean.
- `ReintegrationResponse` : `runner` AdminRunnerResponse, `recreatedPassages` List<PassageResponse>.
- `RunnerBoardEntryResponse` : `runnerId` Long, `bib` int, `name` String, `status` RunnerStatus, `dnfReason` DnfReason|null, `dnfYard` Integer|null, `completedLoops` int, `distanceMeters` long, `elevationMeters` long, `averagePaceSecondsPerKm` Integer|null, `corrected` boolean.
- `RaceBoardResponse` : `race` RaceResponse, `serverTime` Instant, `currentYard` int, `currentYardEndsAt` Instant|null, `runners` List<RunnerBoardEntryResponse>.
- `RunnerDetailResponse` : `runnerId` Long, `raceId` Long, `bib` int, `name` String, `status` RunnerStatus, `dnfReason` DnfReason|null, `dnfYard` Integer|null, `completedLoops` int, `distanceMeters` long, `elevationMeters` long, `averagePaceSecondsPerKm` Integer|null, `corrected` boolean, `passages` List<PassageResponse>.
- Erreurs : `ProblemDetail` (RG5).

| # | Méthode | Chemin | Accès (RG29) | Requête | Réponse succès | Erreurs | Service | RG |
|---|---|---|---|---|---|---|---|---|
| E1 | GET | `/api/public/races` | public | — | 200 `RaceResponse[]` | — | `RaceService.list` | RG10 |
| E2 | GET | `/api/public/races/{raceId}` | public | — | 200 `RaceResponse` | 400, 404 | `RaceService.get` | RG10, RG14 |
| E3 | POST | `/api/public/races/{raceId}/registrations` | public | `RegistrationRequest` | 201 `RegistrationResponse` + `Location` | 400, 404, 409 | `RunnerService.register` | RG15, RG16 |
| E4 | GET | `/api/public/races/{raceId}/board` | public | — | 200 `RaceBoardResponse` | 400, 404 | `RaceBoardService.board` | RG24, RG27 |
| E5 | GET | `/api/public/runners/{runnerId}` | public | — | 200 `RunnerDetailResponse` | 400, 404 | `RaceBoardService.runnerDetail` | RG26, RG27 |
| E6 | POST | `/api/scan/passages` | SCANNER, ADMIN | `ScanRequest` | 200 `ScanResponse` | 400, 401, 404, 409 | `PassageRecordingService.recordScan` | RG20, RG23 |
| E7 | POST | `/api/admin/races` | ADMIN | `RaceRequest` | 201 `RaceResponse` + `Location` | 400, 401, 403, 409 | `RaceService.create` | RG9 |
| E8 | GET | `/api/admin/races` | ADMIN | — | 200 `RaceResponse[]` | 401, 403 | `RaceService.list` | RG10 |
| E9 | GET | `/api/admin/races/{raceId}` | ADMIN | — | 200 `RaceResponse` | 400, 401, 403, 404 | `RaceService.get` | RG10 |
| E10 | PUT | `/api/admin/races/{raceId}` | ADMIN | `RaceRequest` | 200 `RaceResponse` | 400, 401, 403, 404, 409 | `RaceService.update` | RG11 |
| E11 | DELETE | `/api/admin/races/{raceId}` | ADMIN | — | 204 | 400, 401, 403, 404, 409 | `RaceService.delete` | RG12 |
| E12 | POST | `/api/admin/races/{raceId}/start` | ADMIN | — | 200 `RaceResponse` | 400, 401, 403, 404, 409 | `RaceService.start` | RG13 |
| E13 | GET | `/api/admin/races/{raceId}/runners` | ADMIN | — | 200 `AdminRunnerResponse[]` | 400, 401, 403, 404 | `RunnerService.listByRace` | RG17 |
| E14 | GET | `/api/admin/runners/{runnerId}` | ADMIN | — | 200 `AdminRunnerResponse` | 400, 401, 403, 404 | `RunnerService.get` | RG17 |
| E15 | PUT | `/api/admin/runners/{runnerId}` | ADMIN | `RunnerUpdateRequest` | 200 `AdminRunnerResponse` | 400, 401, 403, 404, 409 | `RunnerService.update` | RG18 |
| E16 | DELETE | `/api/admin/runners/{runnerId}` | ADMIN | — | 204 | 400, 401, 403, 404, 409 | `RunnerService.delete` | RG19 |
| E17 | POST | `/api/admin/runners/{runnerId}/dnf` | ADMIN | `DnfRequest` | 200 `AdminRunnerResponse` | 400, 401, 403, 404, 409 | `ManualDnfService.declareDnf` | RG21, RG23 |
| E18 | POST | `/api/admin/runners/{runnerId}/reintegration` | ADMIN | — | 200 `ReintegrationResponse` | 400, 401, 403, 404, 409 | `ReintegrationService.reintegrate`, puis `RunnerService.get`, puis `RaceBoardService.describePassages` *(révisé)* | RG22, RG23 |

(400 sur les chemins avec identifiant = identifiant non numérique, RG6. Les endpoints publics peuvent aussi renvoyer 401 si des identifiants Basic invalides sont présentés, RG30.)

---

## 5. Cas limites *(révisé)*

**CL1 — Identifiant non numérique** (`/api/admin/races/abc`, en ADMIN) : 400 `MALFORMED_REQUEST`, service non appelé.
**CL2 — Corps JSON absent ou mal formé** : 400 `MALFORMED_REQUEST`.
**CL3 — Enum inconnu** (`"reason": "ABANDON"`) : 400 `MALFORMED_REQUEST`, service non appelé.
**CL4 — Modification des paramètres de boucle en course** : 409 ; même valeurs renvoyées : accepté.
**CL5 — Renommage d'une course avec son propre nom** : accepté (unicité vérifiée hors elle-même).
**CL6 — Suppression d'une course SETUP avec coureurs** : coureurs puis course supprimés.
**CL7 — Suppression d'une course RUNNING/FINISHED ou d'un coureur en course / avec passages** : 409.
**CL8 — Démarrage d'une course déjà RUNNING ou FINISHED** : 409.
**CL9 — Inscription sur une course RUNNING/FINISHED** : 409 ; première inscription : dossard 1 ; trous dans les dossards : max + 1.
**CL10 — Deux inscriptions simultanées** : l'une réussit, l'autre reçoit 409 `DATA_INTEGRITY`.
**CL11 — Scan renvoyé à l'identique** (retry de la PWA) : 200 avec le passage existant.
**CL12 — Scan avec qrToken vide** : 400 sans appel au service ; qrToken inconnu : 404.
**CL13 — Tableau de bord d'une course SETUP ou FINISHED** : `currentYard = 0`, `currentYardEndsAt = null`.
**CL14 — Coureur sans passage ou uniquement MANUAL** : `averagePaceSecondsPerKm = null`.
**CL15 — Incohérence de données** (course RUNNING sans `started_at`) : 500 `INTERNAL_INCONSISTENCY` avec message explicite.
**CL16 — Plusieurs courses en parallèle** : chaque tableau de bord ne contient que les coureurs et passages de sa course, avec son propre yard courant.
**CL17 — Réintégration sans passage à recréer** : 200, `recreatedPassages` vide, coureur ACTIVE.
**CL18 — Chemin inconnu sous `/api/admin/**`** : 401 anonyme, 403 SCANNER, 404 ADMIN.
**CL19 — Identifiants faux sur un endpoint public** : 401 (RG30, PO21).
**CL20 — En-tête `Authorization: Bearer ...`** : ignoré ; public accessible, protégé en 401.
**CL21 — Variable d'environnement de sécurité manquante** : l'application ne démarre pas.

---

## 6. Critères d'acceptation *(révisé : convention d'authentification)*

Sauf mention contraire : course **R1** = `id 1`, `name "Backyard Test"`, `raceDate 2026-10-03`, `loopDistance 6706`, `loopDuration 3600`, `loopElevation 50`. Les CA marqués **[slice]** se testent en `@WebMvcTest` avec services mockés et la configuration de sécurité réelle importée ; **[unit]** en test unitaire du service ou du domaine avec repositories mockés et `Clock` fixe ; **[context]** avec `ApplicationContextRunner`.

**Convention d'authentification des CA [slice] CA1 à CA45** *(révisé)* : les requêtes vers `/api/admin/**` sont authentifiées avec le compte ADMIN de test (`admin-test` / `admin-secret`), celles vers `/api/scan/**` avec le compte SCANNER de test (`scanner-test` / `scanner-secret`), celles vers `/api/public/**` sans en-tête `Authorization`. Les comportements sans authentification ou avec un mauvais rôle sont couverts par CA46 à CA56.

### Erreurs (RG5 à RG8, RG23)

**CA1 — 404 ProblemDetail [slice] (RG5, RG6, RG10)**
Donné `RaceService.get(99)` lève `ResourceNotFoundException("Course introuvable : id 99")`. Quand `GET /api/admin/races/99`. Alors 404, `Content-Type: application/problem+json`, `status = 404`, `code = "RESOURCE_NOT_FOUND"`, `detail = "Course introuvable : id 99"`, `instance = "/api/admin/races/99"`.

**CA2 — 409 ProblemDetail [slice] (RG6, RG23)**
Donné `ManualDnfService.declareDnf(7, VOLUNTARY)` lève `BusinessConflictException("Impossible de déclarer DNF : le coureur 7 est au statut DNF (attendu ACTIVE)")`. Quand `POST /api/admin/runners/7/dnf` avec `{"reason":"VOLUNTARY"}`. Alors 409, `code = "BUSINESS_CONFLICT"`, `detail` égal au message.

**CA3 — 400 InvalidInput [slice] (RG6, RG21, RG23)**
Donné `declareDnf(7, TIMEOUT)` lève `InvalidInputException(...)`. Quand `POST /api/admin/runners/7/dnf` avec `{"reason":"TIMEOUT"}`. Alors 400, `code = "INVALID_INPUT"`, `detail` contient `TIMEOUT`.

**CA4 — Validation Bean [slice] (RG8, RG9)**
Quand `POST /api/admin/races` avec `{"name":"","raceDate":"2026-10-03","loopDistance":0,"loopDuration":3600,"loopElevation":-1}`. Alors 400, `code = "VALIDATION_FAILED"`, `errors` contient exactement les champs `name`, `loopDistance`, `loopElevation` ; `RaceService.create` n'est pas appelé.

**CA5 — Requêtes mal formées [slice] (RG6, CL1 à CL3)**
Quand `GET /api/admin/races/abc`. Alors 400 `MALFORMED_REQUEST`. Quand `POST /api/admin/races` avec le corps `{`. Alors 400 `MALFORMED_REQUEST`. Quand `POST /api/admin/runners/7/dnf` avec `{"reason":"ABANDON"}`. Alors 400 `MALFORMED_REQUEST`. Aucun service appelé dans les trois cas.

**CA6 — Exceptions du domaine [slice] (RG7, CL15)**
Donné `RaceBoardService.board(1)` lève `IllegalStateException("Donnée incohérente : course RUNNING sans started_at (course 1)")`. Quand `GET /api/public/races/1/board`. Alors 500, `code = "INTERNAL_INCONSISTENCY"`, `detail` égal au message. Même résultat (500, `INTERNAL_INCONSISTENCY`) si le service lève `IllegalArgumentException("Numéro de yard invalide : 0 (minimum 1)")`.

**CA7 — Exception inattendue [slice] (RG6)**
Donné `RaceService.list()` lève `RuntimeException("boom")`. Quand `GET /api/admin/races`. Alors 500, `code = "INTERNAL_ERROR"`, `detail` ne contient pas `boom`.

**CA8 — Violation d'intégrité [slice] (RG6, RG15, CL10)**
Donné `RunnerService.register(1, "Alice")` lève `DataIntegrityViolationException("... uq_runner_race_bib ...")`. Quand `POST /api/public/races/1/registrations` avec `{"name":"Alice"}`. Alors 409, `code = "DATA_INTEGRITY"`, `detail` ne contient pas `uq_runner_race_bib`.

**CA9 — Méthode non supportée et chemin inconnu [slice] (RG6)** *(révisé)*
Avec le compte ADMIN : quand `PATCH /api/admin/races/1`. Alors 405, `code = "METHOD_NOT_ALLOWED"`. Quand `GET /api/admin/inconnu`. Alors 404, `code = "RESOURCE_NOT_FOUND"`. (Sans authentification ou avec SCANNER, ces requêtes donnent 401 ou 403 : CA55.)

### Courses (RG9 à RG14)

**CA10 — Création [slice] (RG3, RG9)**
Donné `RaceService.create(RaceCommand("Backyard Test", 2026-10-03, 6706, 3600, 50))` renvoie R1 (SETUP, `startedAt` null). Quand `POST /api/admin/races` avec ce corps. Alors 201, `Location: /api/admin/races/1`, corps `{"id":1,"name":"Backyard Test","raceDate":"2026-10-03","status":"SETUP","startedAt":null,"loopDistance":6706,"loopDuration":3600,"loopElevation":50,"registrationOpen":true}`.

**CA11 — Création : règles [unit] (RG9)**
Donné `existsByName("Backyard Test") = false`. Quand `create(...)`. Alors la course sauvegardée a `status = SETUP`, `startedAt = null`. Donné `existsByName("Backyard Test") = true`. Alors `BusinessConflictException` dont le message contient `Backyard Test`, aucun `save`.

**CA12 — Liste [unit + slice] (RG10)**
[unit] Donné le repository contenant C1 (`raceDate 2026-11-01`, id 1), C2 (`2026-10-03`, id 3), C3 (`2026-10-03`, id 2). Quand `list()`. Alors ordre C3, C2, C1. [slice] Donné `list()` renvoie 2 courses. Quand `GET /api/admin/races` puis `GET /api/public/races`. Alors 200 et un tableau de 2 éléments dans les deux cas.

**CA13 — Détail [unit + slice] (RG10, RG14)**
[unit] `get(99)` sur repository vide : `ResourceNotFoundException` contenant `99`. [slice] Donné `get(1)` renvoie R1 RUNNING `startedAt 2026-10-03T08:00:00Z`. Quand `GET /api/public/races/1`. Alors 200, `status = "RUNNING"`, `startedAt = "2026-10-03T08:00:00Z"`, `registrationOpen = false`.

**CA14 — Modification en SETUP [unit + slice] (RG11)**
[unit] Donné R1 SETUP. Quand `update(1, RaceCommand("Backyard 2026", 2026-10-04, 5000, 3000, 30))`. Alors R1 a ces cinq valeurs. [slice] Quand `PUT /api/admin/races/1` avec ce corps et le service renvoie la course modifiée. Alors 200 avec `name = "Backyard 2026"`, `loopDuration = 3000`.

**CA15 — Paramètres de boucle figés hors SETUP [unit] (RG11, CL4)**
Donné R1 RUNNING. Quand `update(1, RaceCommand("Backyard Test", 2026-10-03, 6706, 1800, 50))`. Alors `BusinessConflictException` mentionnant `RUNNING`, aucun `save`, `loopDuration` reste 3600. Quand `update(1, RaceCommand("Backyard renommée", 2026-10-05, 6706, 3600, 50))`. Alors succès : `name` et `raceDate` modifiés. Même comportement pour une course FINISHED.

**CA16 — Unicité du nom à la modification [unit] (RG11, CL5)**
Donné R1 et `existsByNameAndIdNot("Autre course", 1) = true`. Quand `update(1, ... name "Autre course" ...)`. Alors `BusinessConflictException`. Donné `existsByNameAndIdNot("Backyard Test", 1) = false`. Quand `update(1, ... name "Backyard Test" ...)`. Alors succès.

**CA17 — Suppression SETUP [unit + slice] (RG12, CL6)**
[unit] Donné R1 SETUP avec 2 coureurs. Quand `delete(1)`. Alors les 2 coureurs sont supprimés puis la course. [slice] Quand `DELETE /api/admin/races/1`. Alors 204 sans corps.

**CA18 — Suppression refusée [unit] (RG12, CL7)**
Donné R1 RUNNING (puis FINISHED). Quand `delete(1)`. Alors `BusinessConflictException` mentionnant le statut, aucune suppression. Donné aucune course 99. Quand `delete(99)`. Alors `ResourceNotFoundException`.

**CA19 — Démarrage [unit + slice] (RG13)**
[unit] Donné R1 SETUP et `Clock` fixe à `2026-10-03T08:00:00Z`. Quand `start(1)`. Alors `status = RUNNING`, `startedAt = 2026-10-03T08:00:00Z`, course sauvegardée. [slice] Quand `POST /api/admin/races/1/start` et le service renvoie cette course. Alors 200, `status = "RUNNING"`, `startedAt = "2026-10-03T08:00:00Z"`, `registrationOpen = false`.

**CA20 — Démarrage refusé [unit] (RG13, CL8)**
Donné R1 RUNNING. Quand `start(1)`. Alors `BusinessConflictException` mentionnant `RUNNING`, `startedAt` inchangé, aucun `save`. Même résultat pour FINISHED. Course 99 absente : `ResourceNotFoundException`.

**CA21 — Transition de domaine Race.start [unit] (RG13)**
Donné une `Race` SETUP. Quand `start(2026-10-03T08:00:00Z)`. Alors RUNNING et `startedAt` positionné. Donné une `Race` RUNNING. Quand `start(...)`. Alors `IllegalStateException`, statut et `startedAt` inchangés.

**CA22 — Inscriptions ouvertes [unit] (RG14)**
`Race.isRegistrationOpen()` vaut `true` pour SETUP, `false` pour RUNNING et FINISHED.

### Coureurs (RG15 à RG19)

**CA23 — Inscription nominale [unit] (RG15, RG16, CL9)**
Donné R1 SETUP avec des coureurs de dossards 1, 2 et 5, et `QrTokenGenerator` mocké renvoyant `"3f2c9a4e-8b1d-4c7e-9f00-1a2b3c4d5e6f"`. Quand `register(1, "Alice")`. Alors coureur sauvegardé : `bib = 6`, `name = "Alice"`, `qrToken` = la valeur du générateur, `status = ACTIVE`, `dnfReason = null`, `dnfYard = null`. Donné R1 SETUP sans coureur. Alors `bib = 1`.

**CA24 — Inscription [slice] (RG15, RG16)**
Donné `register(1, "Alice")` renvoie le coureur id 12, bib 6, token ci-dessus. Quand `POST /api/public/races/1/registrations` avec `{"name":"Alice"}`. Alors 201, `Location: /api/public/runners/12`, corps `{"runnerId":12,"raceId":1,"bib":6,"name":"Alice","qrToken":"3f2c9a4e-8b1d-4c7e-9f00-1a2b3c4d5e6f"}`.

**CA25 — Inscription refusée [unit + slice] (RG14, RG15, CL9)**
[unit] Donné R1 RUNNING (puis FINISHED). Quand `register(1, "Bob")`. Alors `BusinessConflictException` mentionnant les inscriptions fermées, aucun `save`. Course 99 absente : `ResourceNotFoundException`. [slice] Quand `POST /api/public/races/1/registrations` avec `{"name":"  "}`. Alors 400 `VALIDATION_FAILED` sur `name`, service non appelé.

**CA26 — Générateur de qr_token [unit] (RG16)**
Quand `generate()` est appelé 1 000 fois. Alors chaque valeur fait 36 caractères, respecte `^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`, et les 1 000 valeurs sont distinctes.

**CA27 — qr_token absent des réponses publiques et du scan [slice] (RG16, RG24, RG26, RG20)**
Donné des services renvoyant des données complètes. Quand `GET /api/public/races/1/board`, `GET /api/public/runners/12`, `POST /api/scan/passages`. Alors aucun des corps JSON ne contient la clé `qrToken` ni la valeur du token.

**CA28 — Liste et détail admin [unit + slice] (RG17)**
[unit] Donné R1 avec coureurs de dossards 5, 1, 3. Quand `listByRace(1)`. Alors ordre 1, 3, 5. Course 99 absente : `ResourceNotFoundException`. `get(99)` sans coureur : `ResourceNotFoundException`. [slice] Quand `GET /api/admin/races/1/runners`. Alors 200, 3 éléments, chacun avec `qrToken`. Quand `GET /api/admin/runners/12`. Alors 200 `{"id":12,"raceId":1,"bib":6,"name":"Alice","qrToken":"3f2c...","status":"ACTIVE","dnfReason":null,"dnfYard":null}`.

**CA29 — Modification d'un coureur [unit] (RG18)**
Donné R1 SETUP, coureur 12 (bib 6), `existsByRaceIdAndBibAndIdNot(1, 7, 12) = false`. Quand `update(12, 7, "Alice B.")`. Alors bib 7 et nom `"Alice B."`. Donné `existsByRaceIdAndBibAndIdNot(1, 3, 12) = true`. Quand `update(12, 3, "Alice")`. Alors `BusinessConflictException` mentionnant le dossard 3. Donné R1 RUNNING. Quand `update(12, 6, "Alice C.")`. Alors succès (nom modifié). Quand `update(12, 9, "Alice")`. Alors `BusinessConflictException` mentionnant `RUNNING`, bib reste 6.

**CA30 — Modification d'un coureur [slice] (RG8, RG18)**
Quand `PUT /api/admin/runners/12` avec `{"bib":0,"name":""}`. Alors 400 `VALIDATION_FAILED` sur `bib` et `name`, service non appelé. Quand `PUT` avec `{"bib":7,"name":"Alice B.","status":"WINNER","qrToken":"x"}` et le service renvoie le coureur modifié. Alors 200 ; le service a été appelé avec `(12, 7, "Alice B.")` uniquement.

**CA31 — Suppression d'un coureur [unit + slice] (RG19, CL7)**
[unit] Donné R1 SETUP, coureur 12 sans passage. Quand `delete(12)`. Alors coureur supprimé. Donné R1 RUNNING. Alors `BusinessConflictException`. Donné R1 SETUP et `existsByRunnerId(12) = true`. Alors `BusinessConflictException` mentionnant les passages. Aucune suppression dans ces deux cas. [slice] Quand `DELETE /api/admin/runners/12`. Alors 204.

### Actions de course (RG20 à RG23, RG25)

**CA32 — Scan nominal [slice] (RG20)**
Donné `recordScan("tok-b", 2026-10-03T08:45:00Z)` renvoie un passage id 40, yard 1, SCAN, `scannedAt 08:45:00Z`, du coureur 12 (bib 6, "Alice", ACTIVE). Quand `POST /api/scan/passages` avec `{"qrToken":"tok-b","scannedAt":"2026-10-03T08:45:00Z"}`. Alors 200, corps `{"passageId":40,"runnerId":12,"bib":6,"runnerName":"Alice","runnerStatus":"ACTIVE","yardNumber":1,"source":"SCAN","scannedAt":"2026-10-03T08:45:00Z"}` ; le service est appelé exactement avec `("tok-b", 2026-10-03T08:45:00Z)`.

**CA33 — Scan idempotent et réactivation [slice] (RG20, CL11)**
Donné le service renvoie le passage existant id 40 pour un second envoi identique. Alors 200 avec `passageId = 40`. Donné le service renvoie un passage yard 3 d'un coureur réactivé (statut ACTIVE). Alors 200 avec `yardNumber = 3`, `runnerStatus = "ACTIVE"`.

**CA34 — Scan : erreurs [slice] (RG6, RG20, RG23, CL12)**
Quand `{"qrToken":"","scannedAt":"2026-10-03T08:45:00Z"}`. Alors 400 `VALIDATION_FAILED` sur `qrToken`, service non appelé. Donné le service lève `InvalidInputException` pour `scannedAt` absent. Quand `{"qrToken":"tok-b"}`. Alors 400 `INVALID_INPUT`. Donné le service lève `ResourceNotFoundException`. Alors 404. Donné le service lève `BusinessConflictException("Double scan refusé ...")`. Alors 409 `BUSINESS_CONFLICT`.

**CA35 — DNF manuel [slice] (RG21)**
Donné `declareDnf(7, VOLUNTARY)` renvoie le coureur 7 (bib 3, "Bob", DNF, VOLUNTARY, dnfYard 3). Quand `POST /api/admin/runners/7/dnf` avec `{"reason":"VOLUNTARY"}`. Alors 200, `status = "DNF"`, `dnfReason = "VOLUNTARY"`, `dnfYard = 3` ; service appelé avec `(7, VOLUNTARY)`.

**CA36 — DNF manuel sans raison [slice] (RG21)**
Donné `declareDnf(7, null)` lève `InvalidInputException`. Quand `POST /api/admin/runners/7/dnf` avec `{}`. Alors 400 `INVALID_INPUT` ; le service a bien été appelé avec `(7, null)` (la règle n'est pas dupliquée dans le DTO).

**CA37 — Réintégration [slice] (RG1, RG22, RG27)** *(révisé)*
Donné `reintegrate(7)` renvoie 2 passages P3 et P4 (yard 3 et yard 4, MANUAL, `scannedAt` null), `RunnerService.get(7)` renvoie le coureur 7 ACTIVE, et `RaceBoardService.describePassages(7, [P3, P4])` renvoie les deux vues correspondantes (`loopTimeMillis` vide, `corrected` vrai). Quand `POST /api/admin/runners/7/reintegration`. Alors 200, `runner.status = "ACTIVE"`, `runner.dnfReason = null`, `recreatedPassages` = `[{"yardNumber":3,"source":"MANUAL","scannedAt":null,"loopTimeMillis":null,"corrected":true},{"yardNumber":4,...}]` ; les trois services sont appelés dans l'ordre `reintegrate(7)`, `get(7)`, `describePassages(7, [P3, P4])`, ce dernier avec exactement la liste renvoyée par `reintegrate`.

**CA38 — Réintégration sans passage et erreurs [slice] (RG22, RG23, CL17)** *(révisé)*
Donné `reintegrate(7)` renvoie une liste vide et `describePassages(7, [])` renvoie une liste vide. Alors 200, `recreatedPassages = []`. Donné `reintegrate(7)` lève `BusinessConflictException` (coureur ACTIVE). Alors 409. Donné `ResourceNotFoundException`. Alors 404. En cas d'erreur de `reintegrate`, ni `RunnerService.get` ni `RaceBoardService.describePassages` ne sont appelés.

**CA39 — Pas de clôture manuelle [slice] (RG25)** *(révisé)*
Avec le compte ADMIN : quand `POST /api/admin/races/1/close`. Alors 404 `RESOURCE_NOT_FOUND`. Revue de code : aucune classe de `fr.backyard.api` ne dépend de `YardClosingService`.

### Lecture des valeurs dérivées (RG24, RG26, RG27)

**CA40 — Tableau de bord d'une course en cours [unit] (RG24, RG27)**
Donné R1 RUNNING, `startedAt 2026-10-03T08:00:00Z`, `Clock` fixe à `2026-10-03T10:20:00Z` ; coureurs A (bib 1, ACTIVE, passages SCAN yard 1 à `08:45:00Z`, yard 2 à `09:50:00Z`) et B (bib 2, DNF VOLUNTARY dnfYard 2, passage SCAN yard 1 à `08:45:00Z`) ; les passages sont renvoyés par un unique appel `findByRunnerRaceId(1)`. Quand `board(1)`. Alors `serverTime = 10:20:00Z`, `currentYard = 3`, `currentYardEndsAt = 2026-10-03T11:00:00Z` ; entrée A : `completedLoops 2`, `distanceMeters 13412`, `elevationMeters 100`, `averagePaceSecondsPerKm 425`, `corrected false` ; entrée B : `status DNF`, `dnfReason VOLUNTARY`, `dnfYard 2`, `completedLoops 1`, `distanceMeters 6706`, `averagePaceSecondsPerKm 403` ; ordre A puis B ; `findByRunnerId` n'est jamais appelé.

**CA41 — Tableau de bord hors course [unit] (RG24, CL13, CL14)**
Donné R1 SETUP avec un coureur sans passage. Quand `board(1)`. Alors `currentYard = 0`, `currentYardEndsAt = null`, coureur `completedLoops 0`, `averagePaceSecondsPerKm` vide. Donné R1 FINISHED. Alors `currentYard = 0`, `currentYardEndsAt = null`. Course 99 absente : `ResourceNotFoundException`.

**CA42 — Tableau de bord [slice] (RG3, RG24)**
Donné `board(1)` renvoie la vue de CA40. Quand `GET /api/public/races/1/board`. Alors 200, `serverTime = "2026-10-03T10:20:00Z"`, `currentYard = 3`, `currentYardEndsAt = "2026-10-03T11:00:00Z"`, `runners[0].averagePaceSecondsPerKm = 425`, `runners[1].dnfReason = "VOLUNTARY"`. Donné la vue de CA41 (SETUP). Alors `currentYardEndsAt = null` et `runners[0].averagePaceSecondsPerKm = null` (clés présentes).

**CA43 — Courses parallèles [unit] (RG24, CL16)**
Donné R1 (`loopDuration 3600`) et R2 (`loopDuration 1800`, `startedAt 09:30:00Z`), RUNNING, horloge `10:10:00Z`. Quand `board(1)` et `board(2)`. Alors `currentYard` 3 et 2, `currentYardEndsAt` `11:00:00Z` et `10:30:00Z`, et chaque tableau ne contient que les coureurs de sa course.

**CA44 — Détail public d'un coureur [unit + slice] (RG26, RG27)**
[unit] Donné R1 RUNNING, coureur 12 avec passages yard 2 MANUAL (`scannedAt` null) et yard 1 SCAN `08:45:00Z`. Quand `runnerDetail(12)`. Alors passages dans l'ordre yard 1, yard 2 ; yard 1 : `loopTimeMillis 2700000`, `corrected false` ; yard 2 : `loopTimeMillis` vide, `corrected true` ; stats : `completedLoops 2`, `distanceMeters 13412`, `averagePaceSecondsPerKm 403`, `corrected true`. `runnerDetail(99)` : `ResourceNotFoundException`. [slice] Quand `GET /api/public/runners/12`. Alors 200 avec `passages[1].loopTimeMillis = null`, `passages[1].corrected = true`.

**CA45 — Controllers sans logique métier (RG1, RG2, RG27)**
Revue de code, vérifiée par le testeur : aucun controller n'injecte de repository, de `Clock`, de `YardCalculator` ni de `RunnerStatsCalculator` ; aucune condition sur un statut, un yard, un dossard ou un rôle dans `fr.backyard.api` ; aucun service ne dépend de `fr.backyard.api`.

### Sécurité (RG28 à RG34) — ajoutés en révision 2

Comptes de test (RG32) : ADMIN `admin-test` / `admin-secret` → en-tête `Authorization: Basic YWRtaW4tdGVzdDphZG1pbi1zZWNyZXQ=` ; SCANNER `scanner-test` / `scanner-secret` → `Authorization: Basic c2Nhbm5lci10ZXN0OnNjYW5uZXItc2VjcmV0`. Les services sont mockés et renvoient des résultats valides.

**CA46 — 401 sans authentification sur l'admin [slice] (RG28, RG29, RG30, RG5)**
Quand `GET /api/admin/races` sans en-tête `Authorization`. Alors 401, `Content-Type: application/problem+json`, `status = 401`, `code = "UNAUTHENTICATED"`, `instance = "/api/admin/races"` ; **aucun en-tête `WWW-Authenticate`** ; `RaceService.list` n'est pas appelé. Même résultat pour `POST /api/admin/runners/7/dnf` avec `{"reason":"VOLUNTARY"}` (`ManualDnfService` non appelé) et `DELETE /api/admin/races/1`.

**CA47 — 401 sans authentification sur le scan [slice] (RG29, RG30)**
Quand `POST /api/scan/passages` avec `{"qrToken":"tok-b","scannedAt":"2026-10-03T08:45:00Z"}` sans `Authorization`. Alors 401 `UNAUTHENTICATED`, pas de `WWW-Authenticate`, `PassageRecordingService.recordScan` non appelé.

**CA48 — 401 identifiants faux [slice] (RG28, RG30)**
Quand `GET /api/admin/races` avec `Authorization: Basic YWRtaW4tdGVzdDptYXV2YWlz` (`admin-test:mauvais`). Alors 401 `UNAUTHENTICATED`, `detail` ne contient ni `mauvais` ni `admin-test`. Même résultat avec un nom inconnu `Basic aW5jb25udTphZG1pbi1zZWNyZXQ=` (`inconnu:admin-secret`).

**CA49 — 403 SCANNER sur l'admin [slice] (RG29, RG30)**
Quand `GET /api/admin/races` avec le compte SCANNER. Alors 403, `Content-Type: application/problem+json`, `code = "ACCESS_DENIED"`, `detail` contient `SCANNER` ; `RaceService.list` non appelé. Même résultat pour `POST /api/admin/runners/7/reintegration` (`ReintegrationService` non appelé).

**CA50 — 200 ADMIN sur l'admin [slice] (RG28, RG29)**
Donné `RaceService.list()` renvoie 2 courses. Quand `GET /api/admin/races` avec le compte ADMIN. Alors 200, tableau de 2 éléments.

**CA51 — Scan autorisé pour SCANNER et ADMIN [slice] (RG29, PO19)**
Donné `recordScan` renvoie le passage de CA32. Quand `POST /api/scan/passages` avec le compte SCANNER. Alors 200. Quand la même requête avec le compte ADMIN. Alors 200.

**CA52 — Public accessible sans authentification [slice] (RG29)**
Sans en-tête `Authorization` : `GET /api/public/races` → 200 ; `POST /api/public/races/1/registrations` avec `{"name":"Alice"}` → 201 ; `GET /api/public/races/1/board` → 200 ; `GET /api/public/runners/12` → 200. Avec le compte SCANNER ou ADMIN : `GET /api/public/races` → 200.

**CA53 — En-tête Authorization invalide [slice] (RG30, CL19, CL20)**
Quand `GET /api/public/races` avec `Authorization: Basic !!!pas-du-base64`. Alors 401 `UNAUTHENTICATED`. Quand `GET /api/public/races` avec `Authorization: Basic YWRtaW4tdGVzdDptYXV2YWlz` (identifiants faux). Alors 401. Quand `GET /api/public/races` avec `Authorization: Bearer abc`. Alors 200 (en-tête ignoré). Quand `GET /api/admin/races` avec `Authorization: Bearer abc`. Alors 401.

**CA54 — Sans état et sans CSRF [slice] (RG31)**
Donné `RaceService.create(...)` renvoie R1. Quand `POST /api/admin/races` avec le compte ADMIN, un corps valide et **sans jeton CSRF**. Alors 201. Aucune réponse de CA50 à CA54 ne contient d'en-tête `Set-Cookie`. Deux requêtes successives `GET /api/admin/races`, la première avec le compte ADMIN, la seconde sans `Authorization` : la seconde reçoit 401 (aucune session conservée).

**CA55 — Refus par défaut [slice] (RG6, RG29, CL18)**
Quand `GET /api/autre` sans authentification. Alors 401. Avec le compte ADMIN. Alors 403 `ACCESS_DENIED`. Quand `GET /api/admin/inconnu` sans authentification. Alors 401 ; avec SCANNER : 403 ; avec ADMIN : 404 (CA9).

**CA56 — Configuration Spring Security 7 (RG33, RG28)**
Revue de code : un bean `SecurityFilterChain` construit avec le DSL lambda ; aucune occurrence de `WebSecurityConfigurerAdapter`, `.and()`, `antMatchers`, `mvcMatchers`, `NoOpPasswordEncoder` ; `PasswordEncoder` = `BCryptPasswordEncoder` ; `formLogin` et `logout` désactivés. [slice] Quand `GET /login`. Alors 401 (pas de page de connexion générée).

**CA57 — Refus de démarrer si les identifiants sont incomplets [context] (RG32, CL21)**
Avec `ApplicationContextRunner` chargeant `BackyardSecurityProperties` et `SecurityConfig`, et les quatre propriétés de test valides : le contexte démarre. Puis, chaque fois en retirant ou modifiant **une seule** valeur :
- `backyard.security.admin.password-hash` absente : échec de démarrage, message contenant `BACKYARD_SECURITY_ADMIN_PASSWORD_HASH` ;
- `backyard.security.scanner.username` vide (`""`) : échec, message contenant `BACKYARD_SECURITY_SCANNER_USERNAME` ;
- `backyard.security.admin.password-hash = motdepasse` (pas un hash BCrypt) : échec, message contenant `BACKYARD_SECURITY_ADMIN_PASSWORD_HASH` et `BCrypt`, ne contenant pas `motdepasse` ;
- deux noms identiques (`admin-test` pour les deux comptes) : échec, message mentionnant les noms de compte identiques.
Revue : `src/main/resources/application.properties` ne contient aucune des quatre propriétés.

### Couverture RG / CA *(révisé)*

| RG | CA |
|---|---|
| RG1 | CA30, CA36, CA37, CA45 |
| RG2 | CA45 |
| RG3 | CA10, CA42 |
| RG4 | CA46 à CA55 (et chemins de tous les CA) |
| RG5 | CA1, CA46, CA49 |
| RG6 | CA1 à CA9, CA34, CA46, CA48, CA49, CA55 |
| RG7 | CA6 |
| RG8 | CA4, CA25, CA30, CA34 |
| RG9 | CA4, CA10, CA11 |
| RG10 | CA1, CA12, CA13 |
| RG11 | CA14, CA15, CA16 |
| RG12 | CA17, CA18 |
| RG13 | CA19, CA20, CA21 |
| RG14 | CA13, CA22, CA25 |
| RG15 | CA8, CA23, CA24, CA25 |
| RG16 | CA23, CA24, CA26, CA27 |
| RG17 | CA28 |
| RG18 | CA29, CA30 |
| RG19 | CA31 |
| RG20 | CA27, CA32, CA33, CA34 |
| RG21 | CA3, CA35, CA36 |
| RG22 | CA37, CA38 |
| RG23 | CA2, CA3, CA34, CA38 |
| RG24 | CA27, CA40, CA41, CA42, CA43 |
| RG25 | CA39 |
| RG26 | CA27, CA44 |
| RG27 | CA37, CA40, CA44, CA45 |
| RG28 | CA46, CA48, CA50, CA56 |
| RG29 | CA46, CA47, CA49, CA50, CA51, CA52, CA55 |
| RG30 | CA46, CA47, CA48, CA49, CA53 |
| RG31 | CA54 |
| RG32 | CA57 |
| RG33 | CA56 |
| RG34 | aucun CA automatisé : vérification à la recette d'infrastructure (PO24) |

| Endpoint | CA |
|---|---|
| E1 | CA12, CA52, CA53 |
| E2 | CA13 |
| E3 | CA8, CA24, CA25, CA52 |
| E4 | CA6, CA27, CA42, CA52 |
| E5 | CA27, CA44, CA52 |
| E6 | CA27, CA32, CA33, CA34, CA47, CA51 |
| E7 | CA4, CA5, CA10, CA54 |
| E8 | CA7, CA12, CA46, CA48, CA49, CA50, CA53, CA54 |
| E9 | CA1, CA5 |
| E10 | CA14 |
| E11 | CA17, CA46 |
| E12 | CA19 |
| E13 | CA28 |
| E14 | CA28 |
| E15 | CA30 |
| E16 | CA31 |
| E17 | CA2, CA3, CA5, CA35, CA36, CA46 |
| E18 | CA37, CA38, CA49 |

---

## 7. Points ouverts de l'incrément 2 : décision pour l'incrément 3

| PO inc. 2 | Sujet | Décision | Où |
|---|---|---|---|
| PO15 | Paramètres d'une course RUNNING | **Inclus** : `loopDistance`, `loopDuration`, `loopElevation` figés hors SETUP ; `name` et `raceDate` modifiables | RG11, PO2 |
| PO19 | Démarrage de course | **Inclus** : `Race.start(now)`, SETUP → RUNNING | RG13 |
| PO26 | `closeYard` sur course non RUNNING | **Inclus (par exclusion)** : aucune clôture manuelle exposée ; comportement direct laissé non spécifié | RG25, PO4 |
| PO22 | Arbitrage d'une course FINISHED | **Reporté** | PO5 |
| PO17 | Concurrence scan / clôture | **Reporté** | PO6 |
| PO16 | Classement | **Reporté** | PO7 |

---

## 8. Points ouverts *(révisé)*

Chaque point propose une **HYPOTHÈSE** par défaut, déjà appliquée dans les RG/CA. Les points tranchés par l'utilisateur sont marqués **TRANCHÉ** ; l'utilisateur a demandé de conserver l'hypothèse par défaut pour tous les autres points de la révision 1.

**PO1 — Sécurité : authentification et autorisation.** TRANCHÉ : option B, Spring Security en HTTP Basic, deux comptes ADMIN et SCANNER définis par variables d'environnement, `/api/public` ouvert, HTTPS obligatoire en production (RG28 à RG34). Les modalités de détail font l'objet de PO19 à PO28.

**PO2 — Modification des courses en cours (ex-PO15 inc. 2).** HYPOTHÈSE conservée : paramètres de boucle figés hors SETUP (409 s'ils changent), `name` et `raceDate` modifiables quel que soit le statut (RG11).

**PO3 — Démarrage (ex-PO19 inc. 2).** HYPOTHÈSE conservée : `startedAt = heure serveur` au moment de l'appel (RG13).

**PO4 — Clôture manuelle (ex-PO26 inc. 2).** HYPOTHÈSE conservée : non exposée (RG25).

**PO5 — Arbitrage d'une course terminée (ex-PO22 inc. 2).** HYPOTHÈSE conservée : reporté.

**PO6 — Concurrence scan / clôture (ex-PO17 inc. 2).** HYPOTHÈSE conservée : reporté, pas de migration (un `@Version` ne couvrirait pas le cas ; il faudrait un verrou pessimiste).

**PO7 — Classement (ex-PO16 inc. 2).** HYPOTHÈSE conservée : reporté, tri par dossard.

**PO8 — Attribution du dossard à l'inscription publique.** TRANCHÉ : automatique, max + 1 (RG15).

**PO9 — Inscription tardive ou ajout par l'admin après le départ.** HYPOTHÈSE conservée : impossible.

**PO10 — Suppression d'une course SETUP ayant des coureurs.** TRANCHÉ : suppression en cascade par le service (RG12).

**PO11 — Changement de dossard en course.** HYPOTHÈSE conservée : interdit hors SETUP, nom modifiable (RG18).

**PO12 — Conditions de démarrage.** HYPOTHÈSE conservée : aucune.

**PO13 — Régénération d'un qr_token.** HYPOTHÈSE conservée : hors périmètre.

**PO14 — Confirmation du DNF manuel.** HYPOTHÈSE conservée : portée par l'interface admin, pas de champ de confirmation dans l'API.

**PO15 — Code HTTP du scan.** HYPOTHÈSE conservée : 200 pour tout succès.

**PO16 — Lien d'inscription public.** HYPOTHÈSE conservée : id numérique de la course.

**PO17 — Statut HTTP des IllegalStateException / IllegalArgumentException.** TRANCHÉ : 500 `INTERNAL_INCONSISTENCY` avec message explicite (RG7).

**PO18 — Protection contre les inscriptions abusives** *(révisé)*. La décision PO1 laisse `/api/public/**` ouvert, donc l'inscription reste anonyme : la décision ne change pas ce point. HYPOTHÈSE conservée : aucune protection dans cet incrément (ni limitation de débit, ni captcha) ; l'admin peut supprimer les inscriptions abusives tant que la course est SETUP (RG19). Une limitation de débit au niveau du reverse proxy (PO24) est la piste recommandée.

### Nouveaux points ouverts (révision 2, sécurité)

**PO19 — Le compte ADMIN peut-il scanner ?** HYPOTHÈSE : oui, `/api/scan/**` est ouvert aux rôles SCANNER et ADMIN (RG29), pour que l'organisateur puisse scanner lui-même. Alternative : SCANNER uniquement.

**PO20 — Pas d'en-tête `WWW-Authenticate` sur les 401.** RFC 9110 impose cet en-tête sur une réponse 401 ; l'envoyer avec le schéma `Basic` déclencherait la fenêtre native d'identification du navigateur dans la PWA et la mise en cache des identifiants (ce qui rouvrirait le risque CSRF, RG31). HYPOTHÈSE : aucun en-tête `WWW-Authenticate` (écart assumé, RG30). Alternative : un schéma non standard (ex. `WWW-Authenticate: BackyardBasic realm="api"`), conforme à la lettre de la RFC sans déclencher la fenêtre du navigateur.

**PO21 — Identifiants invalides sur un endpoint public.** HYPOTHÈSE : 401 (comportement natif du filtre HTTP Basic, RG30), ce qui signale tôt à la PWA des identifiants périmés. Alternative : ignorer les identifiants invalides sur `/api/public/**` (demande un filtre spécifique).

**PO22 — Format des mots de passe dans les variables d'environnement.** TRANCHÉ par la demande (hash BCrypt), à confirmer sur les modalités. HYPOTHÈSE : hash BCrypt, coût 12 en production, généré hors application (ex. `htpasswd -bnBC 12 "" motdepasse | tr -d ':\n'`, préfixe `$2y$` accepté) ; changement de mot de passe = nouvelle valeur de la variable puis redémarrage. Alternative : mot de passe en clair dans la variable, haché au démarrage (plus simple mais le secret en clair reste dans l'environnement du processus).

**PO23 — CORS.** HYPOTHÈSE : **reporté à l'incrément 4**. La PWA sera servie depuis la même origine que l'API (même domaine derrière le reverse proxy), donc aucune configuration CORS n'est nécessaire ; en l'absence de configuration, Spring refuse les requêtes cross-origin. Si la PWA est hébergée sur une autre origine, il faudra une configuration CORS explicite (origine autorisée par variable d'environnement, en-tête `Authorization` autorisé, pas d'identifiants par cookie).

**PO24 — Mise en œuvre de l'HTTPS obligatoire.** HYPOTHÈSE : reverse proxy (ex. nginx avec certificat Let's Encrypt) sur le VPS, redirection HTTP → HTTPS, application liée à `127.0.0.1`, `server.forward-headers-strategy=framework` ; pas de `requiresChannel` dans l'application (RG34). Alternative : TLS terminé par Spring Boot (`server.ssl.*`), plus de configuration applicative et gestion du renouvellement des certificats.

**PO25 — Protection contre les attaques par force brute sur HTTP Basic.** HYPOTHÈSE : aucune dans l'application (pas de verrouillage de compte ni de limitation de débit) ; le coût BCrypt ralentit chaque essai. Piste : limitation de débit ou fail2ban au niveau du reverse proxy.

**PO26 — Compte SCANNER unique et partagé.** HYPOTHÈSE : un seul compte SCANNER pour tous les bénévoles et toutes les courses (pas de restriction par course, pas de traçabilité par bénévole). Un mot de passe divulgué impose de changer la variable et de redémarrer (ce qui interrompt les scans pendant le redémarrage ; les scans restent dans la file locale de la PWA). Alternative : comptes nominatifs en base (migration V2).

**PO27 — Refus par défaut et ressources de la PWA.** `anyRequest().denyAll()` (RG29) bloquera les fichiers statiques de la PWA s'ils sont servis par Spring Boot. HYPOTHÈSE : l'incrément 4 ouvrira explicitement les chemins de la PWA (ex. `/`, `/assets/**`, `manifest.webmanifest`, service worker) en `permitAll()` ; rien n'est ouvert dans cet incrément.

**PO28 — Endpoints techniques (santé, actuator).** Aucun n'existe (actuator absent du `pom.xml`) ; tout chemin hors préfixe est refusé. HYPOTHÈSE : pas d'endpoint de santé dans cet incrément. Si un endpoint de santé est ajouté pour la supervision, il sera ouvert explicitement et limité à l'état `UP`/`DOWN`.
