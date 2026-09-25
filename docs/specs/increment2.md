# Spec Incrément 2 — Logique métier core

> Projet : Backyard Ultra Tracker
> Date de rédaction : 2026-09-25 (révision 2 : arbitrages utilisateur sur PO2, PO3, PO5, PO9 ; ajout de la réactivation automatique)
> Statut : en attente de validation des nouveaux points ouverts PO20 à PO24 (section 7, hypothèses par défaut déjà appliquées)
> Prérequis : incrément 1 (entités `Race`, `Runner`, `Passage`, enums `RaceStatus`, `RunnerStatus`, `DnfReason`, `PassageSource`, repositories, migration `V1__init.sql`)

---

## 1. Périmètre

### Inclus

- **Calculs temporels de yard** (fonctions pures) : numéro du yard contenant un instant, yard courant d'une course, bornes de début et de fin d'un yard.
- **Statistiques dérivées d'un coureur** (fonctions pures) : tours complétés, distance cumulée, dénivelé cumulé, temps de boucle par passage, allure moyenne, badge "corrigé".
- **Enregistrement d'un scan** (service) : attribution du `yard_number` à partir de `scanned_at`, règles d'acceptation / rejet (scan tardif, doublon, coureur non actif, course non démarrée), et **réactivation automatique** d'un coureur mis DNF par timeout dont le scan, effectué à temps, arrive après la clôture du yard (décision utilisateur sur PO5).
  Justification : c'est la règle qui décide qu'un passage est "valide sur le yard N" ; l'auto-DNF en dépend directement. Son exposition HTTP reste en incrément 3.
- **Clôture de yard** (service appelé par le déclencheur périodique) : auto-DNF puis détection de fin de course / vainqueur.
  Justification pour la fin de course : c'est une règle métier pure (CLAUDE.md, "Contexte métier"), évaluée au même instant que l'auto-DNF et à partir des mêmes données ; la séparer dupliquerait la notion de "yard clôturé".
- **DNF manuel** (service uniquement, sans API) : transition ACTIVE vers DNF avec raison et calcul de `dnf_yard`.
  Justification : la réintégration est l'inverse du DNF ; la sémantique de `dnf_yard` doit être définie une seule fois, au même endroit que l'auto-DNF, sinon règle dupliquée. La confirmation admin est un sujet UI/API (incréments 3 et 4).
- **Réintégration** (service) : recréation des passages manquants, retour du coureur en ACTIVE.
- Tests unitaires sans Spring ni base de données (repositories mockés, `Clock` fixe).

### Explicitement exclu

- Controllers, DTOs, codes HTTP (incrément 3). Les erreurs métier sont typées ici (RG28) pour permettre le mapping 400/404/409 en incrément 3.
- Le déclencheur `@Scheduled` lui-même (fréquence, configuration) : détail d'infrastructure. Seule la méthode métier qu'il appelle est spécifiée (RG17).
- Démarrage d'une course (SETUP vers RUNNING, renseignement de `started_at`) : incrément 3 (voir PO19). Les tests de cet incrément positionnent `status` et `started_at` directement via les setters existants.
- CRUD courses / coureurs, génération du `qr_token`, inscription.
- Classement (voir PO16).
- Frontend, file locale de scans et retry côté client (incrément 4). Seule la conséquence côté serveur (scan reçu en retard) est traitée.
- Verrouillage / concurrence transactionnelle fine (voir PO17).
- Outil d'arbitrage admin d'une course déjà terminée (voir PO22).

### Impact schéma

**Aucune migration n'est nécessaire pour cet incrément.** Le badge "corrigé" est dérivé de `passage.source = MANUAL` (RG10) ; la réactivation automatique ne laisse pas de trace persistée spécifique (PO21) ; toutes les autres valeurs sont dérivées. Si une option alternative d'un point ouvert était retenue (ex. PO10 : stocker `finished_at`, PO21 : tracer la réactivation), elle ferait l'objet d'une migration `V2__...sql` ; `V1__init.sql` n'est jamais modifiée.

---

## 2. Conventions

- **Fixture de référence** utilisée dans les critères d'acceptation, sauf mention contraire :
  course **R1** : `loopDistance = 6706` (m), `loopDuration = 3600` (s), `loopElevation = 50` (m), `status = RUNNING`, `startedAt = T0 = 2026-10-03T08:00:00Z`.
  Tous les instants sont en UTC.
- Les unités sont celles de l'incrément 1 : `loop_distance` en mètres, `loop_duration` en secondes, `loop_elevation` en mètres D+, `started_at` et `scanned_at` en `Instant` (`TIMESTAMP WITH TIME ZONE`).
- "Passage sur le yard N" = un `Passage` du coureur avec `yardNumber = N`, quelle que soit sa `source` (SCAN ou MANUAL). C'est la définition unique de "passage valide" dans tout le document (l'unicité `(runner_id, yard_number)` de l'incrément 1 garantit au plus un passage par yard).
- "Yard 0" / valeur `0` = aucun yard (course non démarrée, instant antérieur au départ, ou course non RUNNING).
- "Dernier yard clôturé" d'une course RUNNING à l'instant `now` = `currentYard(race, now) - 1`.
- Numérotation : les RG30 à RG32 (révision 2) sont regroupées en section G ; les règles transverses RG27 à RG29 restent en section H. Aucune règle existante n'a été renumérotée.

---

## 3. Règles de gestion

### A. Temps et yards

**RG1 — Horloge injectée**
Aucune règle ne lit l'heure système directement (`Instant.now()` sans argument interdit dans le cœur métier). Les fonctions pures reçoivent l'instant de référence en paramètre (`Instant now` ou `Instant instant`). Les services reçoivent une `java.time.Clock` par injection de dépendance et en tirent `now = Instant.now(clock)` une seule fois par appel.

**RG2 — Fenêtre d'un yard**
Pour une course démarrée et un entier `k >= 1` :
- `debut(k) = started_at + (k - 1) × loop_duration`
- `fin(k) = started_at + k × loop_duration`
- La fenêtre du yard `k` est l'intervalle **semi-ouvert** `[debut(k), fin(k))`. Le yard 1 commence exactement à `started_at`. L'instant exact `started_at + k × loop_duration` appartient au yard `k + 1` (c'est le départ du yard suivant).
- `debut(k)` pour `k < 1` : erreur d'argument (`IllegalArgumentException`).

**RG3 — Yard contenant un instant**
`yardAt(race, t)` :
- `0` si `started_at` est null ou si `t < started_at` ;
- sinon `floor( Δms / (loop_duration × 1000) ) + 1`, avec `Δms = durée en millisecondes entre started_at et t` (division entière, précision milliseconde, les nanosecondes au-delà de la milliseconde sont tronquées).
Cette fonction ne dépend pas du statut de la course. Elle est indépendante par course (elle n'utilise que `started_at` et `loop_duration` de la course passée en paramètre).

**RG4 — Yard courant**
`currentYard(race, now)` :
- si `status = RUNNING` : `yardAt(race, now)` ; si `started_at` est null, lever une `IllegalStateException` explicite (donnée incohérente : "course RUNNING sans started_at", avec l'id de la course) ;
- si `status = SETUP` ou `FINISHED` : `0` (voir PO10 pour FINISHED).

### B. Statistiques dérivées d'un coureur

Ces calculs ne dépendent que des paramètres de la course et de la liste des passages du coureur ; ils ne prennent pas `now` et ne dépendent pas du statut du coureur ni de la course.

**RG5 — Tours complétés**
`toursCompletes = nombre de passages du coureur` (SCAN et MANUAL confondus).

**RG6 — Distance cumulée**
`distanceMetres = toursCompletes × loop_distance` (entier `long`, en mètres, sans arrondi).

**RG7 — Dénivelé cumulé**
`denivelePositifMetres = toursCompletes × loop_elevation` (entier `long`, en mètres D+).

**RG8 — Temps de boucle d'un passage**
- Passage `source = SCAN` avec `scanned_at` non null : `tempsBoucleMs = durée en millisecondes entre debut(yard_number) et scanned_at`.
- Passage `source = MANUAL` (quelle que soit la valeur de `scanned_at`) : temps de boucle **non défini** (valeur vide).

**RG9 — Allure moyenne**
- Ensemble retenu : les passages `source = SCAN` avec `scanned_at` non null (y compris un passage SCAN ayant déclenché une réactivation automatique, RG30). Les passages `MANUAL` (réintégration) sont exclus.
- Si l'ensemble est vide : allure **non définie** (valeur vide, jamais 0).
- Sinon, avec `n` = nombre de passages retenus et `Σ` = somme de leurs `tempsBoucleMs` :
  `allureSecParKm = arrondi_HALF_UP( Σ / (n × loop_distance) )`
  (des millisecondes par mètre équivalent exactement à des secondes par kilomètre). Résultat : entier de secondes par kilomètre. Le formatage `mm:ss` est un sujet d'affichage (incrément 4).

**RG10 — Badge "corrigé"**
- Un passage est "corrigé" si et seulement si `source = MANUAL`.
- Un coureur est "corrigé" si et seulement s'il a au moins un passage "corrigé".
- Aucune colonne n'est ajoutée : le badge est dérivé. Une réactivation automatique (RG30) ne crée que des passages SCAN et ne rend donc pas le coureur "corrigé" (PO21).

**RG11 — Regroupement des statistiques**
Le calcul des statistiques d'un coureur renvoie en un seul résultat : tours complétés, distance, dénivelé, allure moyenne (optionnelle), indicateur "corrigé". Une seule implémentation de chaque formule (aucune duplication entre services).

### C. Enregistrement d'un scan

**RG12 — Conditions d'acceptation d'un scan** *(révisée)*
`recordScan(qrToken, scannedAt)` avec `now` issu de la `Clock`. Contrôles dans cet ordre, le premier en échec détermine l'erreur :
1. `scannedAt` null : entrée invalide.
2. Aucun coureur pour `qrToken` : ressource introuvable.
3. Course du coureur non `RUNNING` (SETUP ou FINISHED) : conflit d'état (message précisé par RG31 pour FINISHED).
4. `scannedAt > now` : entrée invalide (scan dans le futur, voir PO6).
5. `yardAt(race, scannedAt) = 0` (scan antérieur à `started_at`) : entrée invalide.
6. Règle de doublon (RG15), sur `k = yardAt(race, scannedAt)` (RG13).
7. Statut du coureur :
   - `ACTIVE` : on poursuit ;
   - `WINNER` : conflit d'état ;
   - `DNF` : si les conditions de réactivation automatique (RG30.2, RG30.3) et de fraîcheur (RG32) sont remplies, on poursuit en mode réactivation ; sinon conflit d'état.
8. Règle de scan tardif (RG14).
En cas de succès : un nouveau `Passage(runner, k, SCAN, scannedAt)` est enregistré et renvoyé ; en mode réactivation, le coureur est en outre réactivé (RG30).
Remarque : le doublon (étape 6) est contrôlé avant le statut (étape 7) pour qu'un retry client d'un scan déjà enregistré reste idempotent quel que soit le statut actuel du coureur.

**RG13 — Attribution du yard d'un scan** *(révisée)*
`yard_number = k = yardAt(race, scannedAt)`. C'est `scannedAt` (instant du scan, transmis par le client) qui compte, pas l'instant de réception par le serveur : un scan effectué dans la fenêtre du yard N mais reçu pendant le yard N+1 est attribué au yard N (filet réseau), que le coureur soit encore `ACTIVE` (clôture pas encore passée) ou déjà mis DNF par la clôture du yard N (réactivation, RG30).

**RG14 — Scan tardif (passage sur k-1 obligatoire)** *(révisée)*
Si `k >= 2` et que le coureur n'a **aucun** passage sur le yard `k - 1`, le scan est rejeté (conflit d'état), sans création de passage ni réactivation. Message : le coureur n'a pas de passage valide sur le yard `k - 1`.
Conséquences :
- un coureur qui termine la boucle N après `fin(N)` (son scan tombe dans la fenêtre N+1) n'obtient pas de passage ; il est mis DNF par la clôture du yard N (RG19) ;
- la règle s'applique aussi en mode réactivation : un coureur mis DNF au yard N n'est réactivé que s'il a un passage sur N-1 (toujours vrai en fonctionnement normal, puisqu'il était ACTIVE pendant le yard N ; voir PO8 pour l'ordre de la file locale).

**RG15 — Doublon et idempotence** *(révisée)*
Si le coureur a déjà un passage sur le yard `k` :
- si ce passage a le même `scanned_at` (égalité stricte d'`Instant`) : l'appel est idempotent, le passage existant est renvoyé, rien n'est enregistré et **le statut du coureur n'est pas modifié** (s'il avait été réactivé par le premier appel, il est déjà ACTIVE ; voir PO24 pour le cas d'un coureur encore DNF) ;
- sinon : conflit d'état (double scan sur le même yard), rien n'est enregistré, statut inchangé.

### D. Clôture de yard (auto-DNF et fin de course)

**RG16 — Yard clôturé**
Pour une course `RUNNING` à l'instant `now` : `C = currentYard(race, now)`, yard clôturé `N = C - 1`. Si `N < 1` (course pas encore dans son yard 2, ou `now < started_at`), il n'y a rien à clôturer : aucune modification.
Un seul yard est contrôlé par appel : le yard `N` (jamais les yards antérieurs, conformément à CLAUDE.md ; voir PO4).

**RG17 — Point d'entrée périodique et indépendance des courses**
- `closeElapsedYards()` : traite chaque course de statut `RUNNING` (via `RaceRepository.findByStatus(RUNNING)`), en appliquant `closeYard` course par course avec le même `now`.
- Les courses `SETUP` et `FINISHED` ne sont jamais traitées.
- Le traitement d'une course n'utilise que ses propres paramètres, coureurs et passages.
- Isolation des erreurs : l'échec du traitement d'une course n'empêche pas le traitement des suivantes. Après avoir traité toutes les courses, si au moins une a échoué, `closeElapsedYards()` lève une exception unique qui référence l'id de chaque course en échec et porte les causes d'origine (en exceptions supprimées ou en cause) ; aucune exception n'est avalée.

**RG18 — Ordre des étapes de clôture d'une course**
`closeYard(race)` exécute, pour le yard `N` : 1) l'auto-DNF (RG19), puis 2) l'évaluation de fin de course (RG21). Il renvoie un résultat indiquant : id de la course, yard clôturé `N` (0 si rien à clôturer), ids des coureurs passés DNF par timeout, id du vainqueur (vide si aucun), et si la course est passée FINISHED.

**RG19 — Auto-DNF** *(révisée)*
Tout coureur de la course de statut `ACTIVE` n'ayant **aucun** passage sur le yard `N` passe, via `Runner.markDnf` (RG29), à : `status = DNF`, `dnfReason = TIMEOUT`, `dnfYard = N`.
Les passages `MANUAL` comptent comme des passages valides (un coureur réintégré n'est donc jamais re-DNF pour un yard recréé).
Les coureurs `DNF` (quelle que soit leur raison) et `WINNER` ne sont jamais modifiés par l'auto-DNF.
Ce DNF par timeout est **révisable** par la réactivation automatique (RG30) tant que le yard `N` reste le dernier yard clôturé (RG32) et que la course est RUNNING (RG31). Un coureur réactivé a un passage sur `N` et n'est donc pas remis DNF par une nouvelle clôture de `N` (RG20).

**RG20 — Idempotence de la clôture** *(révisée)*
Appeler `closeYard` plusieurs fois pendant le même yard courant `C` produit le même état qu'un seul appel : les coureurs déjà DNF ne sont pas modifiés (ni raison, ni `dnf_yard`), la fin de course n'est pas réévaluée si la course est déjà `FINISHED` (RG17 : elle n'est plus traitée), et une course encore `RUNNING` après le premier appel le reste. Un coureur réactivé entre deux appels (RG30) reste ACTIVE au second appel.

**RG21 — Fin de course et vainqueur**
Après l'auto-DNF du yard `N`, avec :
- `F` = ensemble des coureurs de la course ayant un passage sur le yard `N`, quel que soit leur statut actuel ;
- `A` = ensemble des coureurs de la course de statut `ACTIVE` (après l'auto-DNF ; par construction `A ⊆ F`).
Alors (lecture littérale de CLAUDE.md, confirmée par l'utilisateur, PO9) :
- si `|F| = 1` et que ce coureur est `ACTIVE` : il passe `WINNER` (`dnfReason` et `dnfYard` restent null), et la course passe `FINISHED` ;
- sinon, si `|A| = 0` : la course passe `FINISHED` **sans vainqueur** (tous les coureurs sont DNF) ;
- sinon : la course reste `RUNNING`.
La fin de course n'est évaluée qu'à la clôture d'un yard (jamais au moment d'un scan, y compris un scan de réactivation).
Remarque : si `|F| >= 2` et qu'un seul d'entre eux est encore `ACTIVE` (les autres ont abandonné après avoir terminé le yard N), la course continue : le dernier coureur doit terminer seul le yard `N + 1` pour être déclaré vainqueur.

### E. DNF manuel (service, sans API)

**RG22 — DNF manuel**
`declareDnf(runnerId, reason)` avec `now` issu de la `Clock`. Contrôles dans cet ordre :
1. `reason` null ou `reason = TIMEOUT` : entrée invalide (TIMEOUT est réservé à l'auto-DNF).
2. Coureur introuvable : ressource introuvable.
3. Course non `RUNNING` : conflit d'état (voir PO13).
4. Coureur non `ACTIVE` : conflit d'état.
Effet (via `Runner.markDnf`, RG29) : `status = DNF`, `dnfReason = reason`, `dnfYard = (plus grand yard_number des passages du coureur, ou 0 s'il n'en a aucun) + 1`, c'est-à-dire le premier yard non terminé (confirmé par l'utilisateur, PO3). Aucun passage n'est créé ni modifié.
Un DNF manuel (VOLUNTARY, MANUAL, OTHER) n'est jamais annulé par un scan (RG30.2) ; seule la réintégration l'annule.

### F. Réintégration

**RG23 — Conditions de réintégration**
`reintegrate(runnerId)` avec `now` issu de la `Clock`. Contrôles dans cet ordre :
1. Coureur introuvable : ressource introuvable.
2. Course non `RUNNING` : conflit d'état (voir PO11).
3. Coureur non `DNF` (ACTIVE ou WINNER) : conflit d'état.
Toutes les raisons de DNF sont réintégrables (voir PO12).

**RG24 — Passages recréés**
Avec `C = currentYard(race, now)` : pour chaque yard `k` tel que `dnfYard <= k <= C - 1` **et** pour lequel le coureur n'a pas déjà de passage, créer `Passage(runner, k, MANUAL, scannedAt = null)`. Le yard courant `C` n'est pas recréé : le coureur le court (ou le scannera) normalement (confirmé par l'utilisateur, PO2). Si l'intervalle est vide, aucun passage n'est créé. Les passages existants ne sont ni modifiés ni supprimés. Le service renvoie la liste des passages créés, triée par `yard_number` croissant.

**RG25 — Retour en course** *(révisée)*
Après création des passages, le coureur est réactivé par la transition unique `Runner.reactivate()` (RG29) : `status = ACTIVE`, `dnfReason = null`, `dnfYard = null` (cohérent avec RG10/RG11 de l'incrément 1). La trace de la correction est portée par les passages `MANUAL` (badge "corrigé", RG10).

**RG26 — Pas de re-DNF sur les yards recréés**
Conséquence de RG19 + RG24 : après réintégration pendant le yard `C`, une clôture pendant le yard `C` (yard clôturé `C - 1`) ne remet pas le coureur DNF. À la clôture du yard `C` (au début du yard `C + 1`), la règle normale s'applique : il lui faut un passage sur `C`.

### G. Réactivation automatique par scan reçu après la clôture (révision 2, décision utilisateur sur PO5)

**RG30 — Conditions et effet de la réactivation automatique** *(nouvelle)*
Un scan dont `k = yardAt(race, scannedAt)` et qui vise un coureur de statut `DNF` est accepté en mode réactivation si et seulement si **toutes** les conditions suivantes sont vraies :
1. la course est `RUNNING` (sinon rejet RG12.3 / RG31) ;
2. `dnfReason = TIMEOUT` (un DNF `VOLUNTARY`, `MANUAL` ou `OTHER` n'est jamais annulé par un scan, PO20) ;
3. `dnfYard = k` (le scan porte exactement sur le yard dont l'absence de passage a causé le DNF, PO20) ;
4. RG32 (fraîcheur) est respectée ;
5. RG15 (pas de passage déjà présent sur `k`) et RG14 (passage sur `k - 1` si `k >= 2`) sont respectées.
Effet, dans la même unité de traitement :
- création de `Passage(runner, k, SCAN, scannedAt)` (passage SCAN normal, compté dans l'allure, non "corrigé") ;
- réactivation du coureur par la transition unique `Runner.reactivate()` (RG29) : `status = ACTIVE`, `dnfReason = null`, `dnfYard = null` (PO21), puis sauvegarde du coureur.
Si la condition 2 ou 3 n'est pas remplie : conflit d'état (`BusinessConflictException`) dont le message contient l'id du coureur, sa raison de DNF, son `dnfYard` et le yard `k` du scan ; aucun passage créé, statut inchangé.
La réactivation ne déclenche aucune réévaluation de fin de course (RG21) : celle-ci n'a lieu qu'à la clôture suivante.

**RG31 — Scan reçu après la fin de course** *(nouvelle)*
Si la course est `FINISHED`, tout scan est rejeté (RG12.3) par une `BusinessConflictException` dont le message contient l'id de la course, le statut `FINISHED` et le yard `k = yardAt(race, scannedAt)` (calculé à titre informatif, `started_at` étant renseigné), afin que l'admin puisse arbitrer (PO22). Aucun passage n'est créé ; le vainqueur éventuel, le statut de la course et les statuts des coureurs ne sont **jamais** modifiés.

**RG32 — Fraîcheur de la réactivation** *(nouvelle)*
La réactivation n'est possible que si `k` est le **dernier yard clôturé** : `k = currentYard(race, now) - 1`. Si `k < currentYard(race, now) - 1` (le yard `k + 1` est lui aussi déjà clos, alors que le coureur, DNF, n'a pas pu y être contrôlé), le scan est rejeté par un conflit d'état dont le message contient le yard `k`, le yard courant, et indique que seule une réintégration admin peut corriger (PO23). Aucun passage n'est créé, statut inchangé.

### H. Transverse

**RG27 — Immuabilité des passages**
Aucun service de cet incrément ne modifie ni ne supprime un `Passage` existant ; seule la création est utilisée (RG15 de l'incrément 1).

**RG28 — Erreurs métier explicites**
Trois catégories d'exceptions métier, non vérifiées (`RuntimeException`), chacune avec un message exploitable contenant l'identifiant concerné (id course, id coureur ou qr_token, numéro de yard) et l'état constaté :
- `ResourceNotFoundException` : ressource introuvable (mappée 404 en incrément 3) ;
- `BusinessConflictException` : action incompatible avec l'état courant (mappée 409) ;
- `InvalidInputException` : paramètre invalide (mappée 400).
Aucune exception n'est avalée. Package proposé : `fr.backyard.service.exception`.

**RG29 — Transition d'état unique** *(révisée)*
Chaque transition de statut coureur et course est implémentée à un seul endroit, dans le domaine, et réutilisée par tous les services :
- `Runner.markDnf(DnfReason reason, int yard)` : ACTIVE vers DNF, utilisée par l'auto-DNF (RG19) et le DNF manuel (RG22) ;
- `Runner.reactivate()` : DNF vers ACTIVE (remet `dnfReason` et `dnfYard` à null). C'est la **seule** méthode faisant passer un coureur de DNF à ACTIVE ; elle est utilisée par la réintégration (RG25) **et** par la réactivation automatique (RG30). Appelée sur un coureur non DNF : `IllegalStateException`, statut inchangé ;
- `Runner.markWinner()` : ACTIVE vers WINNER (RG21) ;
- `Race.finish()` : RUNNING vers FINISHED (RG21).
Aucune affectation directe de `status`, `dnfReason` ou `dnfYard` dans les services.

---

## 4. Contrat technique proposé (pour l'écriture des tests avant le code)

Noms proposés, à respecter par le développeur pour que les tests écrits depuis cette spec compilent. Signatures indicatives ; toute divergence doit être signalée.

Domaine pur (`fr.backyard.domain`), sans dépendance Spring :
- `YardCalculator` : `int yardAt(Race race, Instant instant)`, `int currentYard(Race race, Instant now)`, `Instant yardStart(Race race, int yard)`, `Instant yardEnd(Race race, int yard)`.
- `RunnerStatsCalculator` : `RunnerStats compute(Race race, List<Passage> passages)`, `OptionalLong loopTimeMillis(Race race, Passage passage)`.
- `RunnerStats` (record) : `int completedLoops`, `long distanceMeters`, `long elevationMeters`, `OptionalInt averagePaceSecondsPerKm`, `boolean corrected`.
- Méthodes de transition (RG29) : `Runner.markDnf(DnfReason, int)`, `Runner.reactivate()`, `Runner.markWinner()`, `Race.finish()`.

Services (`fr.backyard.service`), dépendances par constructeur (repositories de l'incrément 1 + `Clock`) :
- `PassageRecordingService(RunnerRepository, PassageRepository, Clock)` : `Passage recordScan(String qrToken, Instant scannedAt)` (inclut la réactivation automatique ; le coureur réactivé est sauvegardé via `RunnerRepository.save`).
- `YardClosingService(RaceRepository, RunnerRepository, PassageRepository, Clock)` : `List<YardClosingResult> closeElapsedYards()`, `YardClosingResult closeYard(Race race)`.
- `YardClosingResult` (record) : `Long raceId`, `int closedYard`, `List<Long> timedOutRunnerIds`, `Optional<Long> winnerRunnerId`, `boolean raceFinished`.
- `ManualDnfService(RunnerRepository, PassageRepository, Clock)` : `Runner declareDnf(Long runnerId, DnfReason reason)`.
- `ReintegrationService(RunnerRepository, PassageRepository, Clock)` : `List<Passage> reintegrate(Long runnerId)`.

Méthodes de repository utilisées (toutes existantes depuis l'incrément 1) : `RaceRepository.findByStatus`, `RunnerRepository.findById`, `findByQrToken`, `findByRaceId`, `findByRaceIdAndStatus`, `save`, `PassageRepository.findByRunnerId`, `findByRunnerIdAndYardNumber`, `save`. Aucune nouvelle méthode n'est requise ; le développeur peut en ajouter (ex. requête groupée par course) sans changer le comportement spécifié.

Tests : JUnit 5 + Mockito (repositories mockés), `Clock.fixed(instant, ZoneOffset.UTC)`. Les entités étant sans setter d'id, les tests peuvent utiliser des mocks, `ReflectionTestUtils` ou un constructeur de test ; ce choix appartient au testeur.

---

## 5. Cas limites

**CL1 — Bascule exacte de yard** : `started_at + k × loop_duration` appartient au yard `k + 1` (RG2). Un scan à `08:59:59.999` est sur le yard 1, un scan à `09:00:00.000` sur le yard 2. Une clôture à `08:59:59.999` ne clôture rien, une clôture à `09:00:00.000` clôture le yard 1.

**CL2 — Course non démarrée (SETUP)** : yard courant 0 ; clôture ignorée ; scan, DNF manuel et réintégration rejetés (conflit) ; statistiques calculables (0 passage).

**CL3 — Course RUNNING avec `now < started_at`** (départ programmé dans le futur) : yard courant 0 ; clôture sans effet ; scan rejeté (entrée invalide, RG12.5 ou RG12.4).

**CL4 — Course RUNNING sans `started_at`** : incohérence de données, `IllegalStateException` explicite (RG4).

**CL5 — Course terminée (FINISHED)** : yard courant 0 (PO10) ; clôture jamais appliquée ; scan (y compris un scan qui aurait réactivé un coureur, RG31), DNF manuel, réintégration rejetés ; statistiques toujours calculables.

**CL6 — Coureur sans aucun passage** : tours 0, distance 0, dénivelé 0, allure non définie, non corrigé ; DNF timeout dès la clôture du yard 1 ; DNF manuel avec `dnf_yard = 1`.

**CL7 — Coureur réintégré** : ses passages MANUAL comptent pour l'auto-DNF (pas de re-DNF), pour les tours, la distance et le dénivelé, mais pas pour l'allure ; badge corrigé.

**CL8 — Passage manuel vs scan** : même valeur pour la validité et les cumuls ; seule différence : exclusion de l'allure et badge corrigé.

**CL9 — Plusieurs courses RUNNING en parallèle** : chacune a son propre yard courant (paramètres différents), sa propre clôture, et une erreur sur l'une n'empêche pas le traitement des autres.

**CL10 — Scan tardif** : scan dont la fenêtre est `N + 1` alors que le coureur n'a pas de passage sur `N` : rejeté (RG14).

**CL11 — Scan reçu en retard mais effectué dans la fenêtre** (filet réseau) *(révisé)* : accepté si le coureur est encore ACTIVE (RG13) ; s'il a déjà été mis DNF TIMEOUT par la clôture de ce yard, il est accepté et le coureur est réactivé automatiquement (RG30), à condition que le yard du scan soit le dernier clôturé (RG32) et que la course soit RUNNING (RG31).

**CL12 — Clôture appelée deux fois dans le même yard** : sans effet supplémentaire (RG20), y compris si un coureur a été réactivé entre les deux appels.

**CL13 — Coureur déjà DNF ou WINNER** *(révisé)* : jamais touché par l'auto-DNF ; scan rejeté pour WINNER et pour DNF hors conditions de réactivation (RG30) ; DNF manuel rejeté ; réintégration possible pour DNF seulement.

**CL14 — Aucun finisher d'un yard** : tous DNF, course FINISHED sans vainqueur.

**CL15 — Double scan** : même `scanned_at` : idempotent ; `scanned_at` différent sur le même yard : conflit (RG15). Un second scan juste après la bascule est attribué au yard suivant (PO7).

**CL16 — Scan d'un DNF non-timeout** : un coureur DNF VOLUNTARY / MANUAL / OTHER qui scanne n'est jamais réactivé (RG30.2).

**CL17 — Scan tardif reçu après désignation du vainqueur** : rejet explicite, vainqueur inchangé (RG31).

**CL18 — Scan tardif reçu alors que le yard N+1 est aussi clos** : rejet explicite, réintégration admin nécessaire (RG32).

**CL19 — Retry d'un scan de réactivation** : idempotent, passage existant renvoyé, coureur toujours ACTIVE (RG15).

**CL20 — Scan de réactivation sans passage sur k-1** : rejeté par RG14, pas de réactivation.

---

## 6. Critères d'acceptation

Sauf mention contraire, course R1 (section 2) et `Clock` fixe à l'instant indiqué.

### Yards (RG1 à RG4)

**CA1 — Premier instant de la course (RG2, RG3)**
Donné R1. Quand `yardAt(R1, 2026-10-03T08:00:00Z)`. Alors `1`.

**CA2 — Bascule exacte (RG2, RG3, CL1)**
Donné R1. Quand `yardAt` à `08:59:59.999Z`, puis à `09:00:00.000Z`, puis à `09:00:00.001Z`. Alors `1`, `2`, `2`.

**CA3 — Yard en milieu de course (RG3)**
Donné R1. Quand `yardAt(R1, 13:30:00Z)`. Alors `6`.

**CA4 — Avant le départ et course sans started_at (RG3, CL3)**
Donné R1. Quand `yardAt(R1, 07:59:59Z)`. Alors `0`. Donné une course SETUP avec `startedAt = null`. Quand `yardAt(course, 13:30:00Z)`. Alors `0`.

**CA5 — Bornes d'un yard (RG2)**
Donné R1. Quand `yardStart(R1, 3)` et `yardEnd(R1, 3)`. Alors `10:00:00Z` et `11:00:00Z`. Quand `yardStart(R1, 0)`. Alors `IllegalArgumentException`.

**CA6 — Yard courant selon le statut (RG4, CL2, CL5)**
Donné `now = 13:30:00Z`. Quand `currentYard` sur R1 (RUNNING). Alors `6`. Quand `currentYard` sur une copie de R1 en `FINISHED` (même `startedAt`). Alors `0`. Quand `currentYard` sur une course `SETUP` sans `startedAt`. Alors `0`.

**CA7 — Course RUNNING sans started_at (RG4, CL4)**
Donné une course `RUNNING` avec `startedAt = null`. Quand `currentYard(course, 13:30:00Z)`. Alors `IllegalStateException` dont le message contient l'id de la course.

**CA8 — Deux courses, même instant, yards différents (RG3, CL9)**
Donné R1 et R2 (`loopDuration = 1800`, `startedAt = 2026-10-03T09:30:00Z`, RUNNING). Quand `yardAt` à `10:10:00Z` sur chacune. Alors R1 : `3`, R2 : `2`.

### Statistiques (RG5 à RG11)

**CA9 — Coureur sans passage (RG5 à RG11, CL6)**
Donné R1 et une liste de passages vide. Quand `compute`. Alors `completedLoops = 0`, `distanceMeters = 0`, `elevationMeters = 0`, `averagePaceSecondsPerKm` vide, `corrected = false`.

**CA10 — Trois scans (RG5, RG6, RG7, RG8, RG9)**
Donné R1 et 3 passages SCAN : yard 1 à `08:45:00Z`, yard 2 à `09:50:00Z`, yard 3 à `10:55:00Z`. Quand `compute` et `loopTimeMillis` sur chaque passage. Alors temps de boucle `2 700 000`, `3 000 000`, `3 300 000` ms ; `completedLoops = 3`, `distanceMeters = 20118`, `elevationMeters = 150`, `averagePaceSecondsPerKm = 447` (9 000 000 / 20 118 = 447,36).

**CA11 — Arrondi HALF_UP de l'allure (RG9)**
Donné une course `loopDistance = 5000`, `loopDuration = 3600`, `startedAt = T0`, et un passage SCAN yard 1 à `08:45:02.500Z`. Quand `compute`. Alors allure `541` (2 702 500 / 5000 = 540,5). Donné R1 et un passage SCAN yard 1 à `08:45:00Z`. Alors allure `403` (2 700 000 / 6706 = 402,62).

**CA12 — Scans et passages manuels mélangés (RG5, RG8, RG9, RG10, CL8)**
Donné R1 et les passages : yard 1 SCAN `08:45:00Z`, yard 2 MANUAL `null`, yard 3 MANUAL `null`, yard 4 SCAN `11:50:00Z`. Quand `compute`. Alors `completedLoops = 4`, `distanceMeters = 26824`, `elevationMeters = 200`, `averagePaceSecondsPerKm = 425` (5 700 000 / 13 412 = 424,99), `corrected = true` ; `loopTimeMillis` du passage yard 2 est vide.

**CA13 — Uniquement des passages manuels (RG9, RG10)**
Donné R1 et 2 passages MANUAL (yards 1 et 2, `scannedAt = null`). Quand `compute`. Alors `completedLoops = 2`, allure vide, `corrected = true`.

**CA14 — Passage MANUAL avec scanned_at renseigné exclu de l'allure (RG8, RG9)**
Donné R1 et : yard 1 SCAN `08:45:00Z`, yard 2 MANUAL avec `scannedAt = 09:10:00Z`. Quand `compute`. Alors allure `403` (seul le yard 1 compte), `loopTimeMillis` du yard 2 vide.

**CA15 — Course plate (RG7)**
Donné une course `loopElevation = 0` et 5 passages. Quand `compute`. Alors `elevationMeters = 0`, `completedLoops = 5`.

**CA16 — Indépendance du statut (RG11)**
Donné un coureur `DNF` (TIMEOUT, `dnfYard = 4`) avec 3 passages SCAN (yards 1 à 3). Quand `compute`. Alors `completedLoops = 3`, `distanceMeters = 20118`.

### Enregistrement d'un scan (RG12 à RG15)

**CA17 — Scan nominal (RG1, RG12, RG13)**
Donné R1, coureur ACTIVE `qrToken = "tok-a"` sans passage, horloge `08:46:00Z`. Quand `recordScan("tok-a", 08:45:00Z)`. Alors un passage est enregistré avec `yardNumber = 1`, `source = SCAN`, `scannedAt = 08:45:00Z`, et il est renvoyé.

**CA18 — Bascule sur un scan (RG13, CL1)**
Donné coureur A sans passage, horloge `09:00:30Z`. Quand `recordScan(A, 08:59:59.999Z)`. Alors passage yard 1. Donné coureur B avec passage yard 1, horloge `09:00:30Z`. Quand `recordScan(B, 09:00:00.000Z)`. Alors passage yard 2.

**CA19 — Scan reçu en retard, effectué dans la fenêtre, coureur encore ACTIVE (RG13, CL11)**
Donné coureur ACTIVE avec passages yards 1 et 2, horloge `11:00:40Z` (yard 4), clôture non encore exécutée. Quand `recordScan(tok, 10:59:50Z)`. Alors passage enregistré sur le yard 3, coureur toujours ACTIVE, aucun `save` du coureur.

**CA20 — Scan tardif rejeté (RG14, CL10)** *(révisé)*
Donné coureur ACTIVE avec passage yard 1 seulement, horloge `10:00:10Z`. Quand `recordScan(tok, 10:00:05Z)` (yard 3). Alors `BusinessConflictException` dont le message mentionne le yard `2` ; aucun `save` de passage ni de coureur ; coureur toujours ACTIVE. Ce rejet n'est pas lié à la réactivation : le coureur est ACTIVE et seule RG14 s'applique.

**CA21 — Coureur non actif hors conditions de réactivation (RG12, RG30, CL13)** *(révisé)*
Donné coureur `DNF` `VOLUNTARY` `dnfYard = 2` avec passage yard 1, horloge `10:00:30Z` (yard 3, dernier yard clôturé = 2). Quand `recordScan(tok, 09:59:00Z)` (yard 2). Alors `BusinessConflictException` dont le message contient l'id du coureur, le statut `DNF` et la raison `VOLUNTARY`. Donné un coureur `WINNER` (course RUNNING, donnée de test). Alors `BusinessConflictException` avec le statut `WINNER` dans le message. Aucun `save` dans les deux cas ; statuts inchangés.

**CA22 — Course non RUNNING (RG12, CL2, CL5)**
Donné une course `SETUP` puis une course `FINISHED`, coureur ACTIVE. Quand `recordScan`. Alors `BusinessConflictException` dans les deux cas, aucun `save`.

**CA23 — QR inconnu (RG12, RG28)**
Donné aucun coureur pour `"tok-inconnu"`. Quand `recordScan("tok-inconnu", 08:45:00Z)`. Alors `ResourceNotFoundException` dont le message contient `tok-inconnu`.

**CA24 — Entrées invalides (RG12, CL3)**
Donné R1, coureur ACTIVE, horloge `08:45:00Z`. Quand `recordScan(tok, null)`. Alors `InvalidInputException`. Quand `recordScan(tok, 08:45:01Z)` (futur). Alors `InvalidInputException`. Quand `recordScan(tok, 07:59:59Z)` (avant départ). Alors `InvalidInputException`. Aucun `save` dans les trois cas.

**CA25 — Doublon idempotent et double scan (RG15, CL15)**
Donné coureur avec passage yard 1 `scannedAt = 08:45:00Z`, horloge `08:50:30Z`. Quand `recordScan(tok, 08:45:00Z)`. Alors le passage existant est renvoyé, aucun `save`. Quand `recordScan(tok, 08:50:00Z)`. Alors `BusinessConflictException`, aucun `save`.

### Clôture de yard (RG16 à RG21)

**CA26 — Rien à clôturer pendant le yard 1 (RG1, RG16, CL1)**
Donné R1, coureur A ACTIVE sans passage, horloge `08:59:59.999Z`. Quand `closeYard(R1)`. Alors `closedYard = 0`, A reste ACTIVE, aucun `save`.

**CA27 — Auto-DNF à la bascule exacte (RG16, RG19, CL1)**
Donné R1, horloge `09:00:00.000Z`, coureurs ACTIVE : A (passage yard 1), B (aucun passage), C (passage yard 1). Quand `closeYard(R1)`. Alors B : `DNF`, `TIMEOUT`, `dnfYard = 1` ; A et C : ACTIVE ; R1 : RUNNING ; résultat `closedYard = 1`, `timedOutRunnerIds = [B]`, pas de vainqueur, `raceFinished = false`.

**CA28 — Seul le yard N est contrôlé ; passages MANUAL valides (RG16, RG19, RG26, CL7)**
Donné R1, horloge `11:00:00Z` (yard 4, N = 3), coureurs ACTIVE : E (SCAN 1, 2, 3), F (SCAN 1, 2), G (SCAN 1, MANUAL 2, MANUAL 3). Quand `closeYard(R1)`. Alors F : DNF TIMEOUT `dnfYard = 3` ; E et G : ACTIVE ; R1 RUNNING.

**CA29 — Coureurs DNF et WINNER non modifiés (RG19, CL13)**
Donné R1, horloge `10:00:00Z` (N = 2), coureur D `DNF` `VOLUNTARY` `dnfYard = 1` sans passage, coureurs A et B ACTIVE avec passages 1 et 2. Quand `closeYard(R1)`. Alors D reste `VOLUNTARY`, `dnfYard = 1`, et n'est pas sauvegardé ; A et B ACTIVE.

**CA30 — Idempotence (RG20, CL12)**
Donné l'état de CA27. Quand `closeYard(R1)` à `09:00:00Z` puis de nouveau à `09:00:05Z`. Alors après le second appel : B toujours `TIMEOUT` `dnfYard = 1`, A et C ACTIVE, R1 RUNNING, `timedOutRunnerIds` du second résultat vide, B sauvegardé une seule fois au total.

**CA31 — Plusieurs courses en parallèle (RG17, RG21, CL9)** *(révisé)*
Donné horloge `10:00:00Z` ; R1 (yard 3, N = 2) avec un seul coureur X ACTIVE (passage 1 seulement) ; R2 (`loopDuration = 1800`, `startedAt = 09:30:00Z`, yard 2, N = 1) avec Y ACTIVE (passage 1), V ACTIVE (passage 1) et Z ACTIVE (aucun passage) ; R3 `SETUP` avec W ACTIVE ; R4 `FINISHED`. `findByStatus(RUNNING)` renvoie R1 et R2. Quand `closeElapsedYards()`. Alors :
- R1 : X DNF `TIMEOUT` `dnfYard = 2` ; aucun finisher du yard 2 (`F` vide, `A` vide), donc R1 passe `FINISHED` **sans vainqueur** (RG21, PO9) ; résultat R1 : `closedYard = 2`, `timedOutRunnerIds = [X]`, `winnerRunnerId` vide, `raceFinished = true` ;
- R2 : Z DNF `TIMEOUT` `dnfYard = 1` ; Y et V ACTIVE (`F = {Y, V}`, deux finishers), R2 reste `RUNNING` ; résultat R2 : `closedYard = 1`, `timedOutRunnerIds = [Z]`, `winnerRunnerId` vide, `raceFinished = false` ;
- W non modifié ; R3 et R4 jamais traitées ; exactement deux résultats.

**CA32 — Isolation des erreurs entre courses (RG17, RG28)** *(révisé)*
Donné horloge `10:00:00Z`, R1 et R2 RUNNING ; R2 (`loopDuration = 1800`, `startedAt = 09:30:00Z`, N = 1) avec Y ACTIVE (passage 1), V ACTIVE (passage 1) et Z ACTIVE (aucun passage) ; le repository lève une exception lors du traitement de R1. Quand `closeElapsedYards()`. Alors R2 est entièrement traitée (Z DNF `TIMEOUT` `dnfYard = 1`, Y et V ACTIVE, R2 reste `RUNNING`), puis une exception est levée dont le message contient l'id de R1 et qui porte l'exception d'origine.

**CA33 — Vainqueur (RG21)**
Donné R1, horloge `13:00:00Z` (N = 5), A ACTIVE (passages 1 à 5), B ACTIVE (passages 1 à 4). Quand `closeYard(R1)`. Alors B : DNF TIMEOUT `dnfYard = 5` ; A : `WINNER`, `dnfReason = null`, `dnfYard = null` ; R1 : `FINISHED` ; résultat `winnerRunnerId = A`, `raceFinished = true`.

**CA34 — Au moins deux finishers : la course continue (RG21)**
Donné R1, horloge `13:00:00Z`, A et B ACTIVE avec passages 1 à 5, C ACTIVE avec passages 1 à 4. Quand `closeYard(R1)`. Alors C DNF `dnfYard = 5`, A et B ACTIVE, R1 RUNNING, pas de vainqueur.

**CA35 — Aucun finisher : fin sans vainqueur (RG21, CL14)**
Donné R1, horloge `13:00:00Z`, A et B ACTIVE avec passages 1 à 4. Quand `closeYard(R1)`. Alors A et B DNF TIMEOUT `dnfYard = 5`, R1 `FINISHED`, pas de vainqueur, `raceFinished = true`.

**CA36 — Le dernier actif doit courir seul un yard de plus (RG21)**
Donné R1, A ACTIVE (passages 1 à 5), B `DNF` `VOLUNTARY` `dnfYard = 6` (passages 1 à 5). Quand `closeYard(R1)` à `13:00:00Z` (N = 5). Alors R1 RUNNING, A ACTIVE, pas de vainqueur. Puis A obtient un passage yard 6. Quand `closeYard(R1)` à `14:00:00Z` (N = 6). Alors A `WINNER`, R1 `FINISHED`.

**CA37 — Unique finisher non actif : fin sans vainqueur (RG21)**
Donné R1, horloge `13:00:00Z`, A `DNF` `VOLUNTARY` `dnfYard = 6` avec passages 1 à 5, B ACTIVE avec passages 1 à 4. Quand `closeYard(R1)`. Alors B DNF `dnfYard = 5`, A reste `VOLUNTARY`, R1 `FINISHED` sans vainqueur.

**CA38 — Course terminée non retraitée (RG17, RG20, CL5)**
Donné R1 passée `FINISHED` par CA33, `findByStatus(RUNNING)` ne la renvoie plus. Quand `closeElapsedYards()` à `13:00:10Z`. Alors aucun coureur de R1 n'est modifié, aucun résultat pour R1.

**CA39 — Course à un seul coureur (RG21, PO9)**
Donné R1 avec un seul coureur A ACTIVE ayant un passage yard 1, horloge `09:00:00Z`. Quand `closeYard(R1)`. Alors A `WINNER`, R1 `FINISHED`.

### DNF manuel (RG22)

**CA40 — DNF manuel en cours de boucle (RG22, RG29)**
Donné R1, horloge `10:20:00Z` (yard 3), A ACTIVE avec passages 1 et 2. Quand `declareDnf(A, VOLUNTARY)`. Alors A : `DNF`, `VOLUNTARY`, `dnfYard = 3` ; aucun passage créé.

**CA41 — DNF manuel après avoir terminé le yard courant (RG22)**
Donné horloge `10:50:00Z`, A ACTIVE avec passages 1, 2, 3. Quand `declareDnf(A, OTHER)`. Alors `dnfYard = 4`.

**CA42 — DNF manuel sans passage (RG22, CL6)**
Donné horloge `08:30:00Z`, A ACTIVE sans passage. Quand `declareDnf(A, MANUAL)`. Alors `dnfYard = 1`.

**CA43 — Refus du DNF manuel (RG22, RG28, CL13)**
Quand `declareDnf(A, TIMEOUT)` ou `declareDnf(A, null)`. Alors `InvalidInputException`. Quand `declareDnf(idInconnu, VOLUNTARY)`. Alors `ResourceNotFoundException` (message contenant l'id). Quand le coureur est `DNF` ou `WINNER`. Alors `BusinessConflictException`. Quand la course est `SETUP` ou `FINISHED`. Alors `BusinessConflictException`. Aucun `save` dans tous ces cas.

### Réintégration (RG23 à RG27)

**CA44 — Réintégration nominale (RG23, RG24, RG25, RG27)**
Donné R1, B avec passages SCAN yards 1 et 2, `DNF` `TIMEOUT` `dnfYard = 3`, horloge `12:15:00Z` (yard 5). Quand `reintegrate(B)`. Alors deux passages créés : yard 3 et yard 4, `source = MANUAL`, `scannedAt = null`, renvoyés dans cet ordre ; B : `ACTIVE`, `dnfReason = null`, `dnfYard = null` ; les passages yards 1 et 2 ne sont ni modifiés ni supprimés.

**CA45 — Réintégration dans le yard qui suit le DNF (RG24)**
Donné B `DNF` `TIMEOUT` `dnfYard = 3` (clôture à `11:00:00Z`), horloge `11:10:00Z` (yard 4). Quand `reintegrate(B)`. Alors un seul passage créé : yard 3 MANUAL.

**CA46 — Intervalle vide (RG24)**
Donné A `DNF` `VOLUNTARY` `dnfYard = 3` avec passages 1 et 2, horloge `10:30:00Z` (yard 3). Quand `reintegrate(A)`. Alors aucun passage créé, liste vide renvoyée, A `ACTIVE`.

**CA47 — Passage déjà présent non recréé (RG24, RG27)**
Donné C `DNF` `TIMEOUT` `dnfYard = 2` avec passages SCAN 1 et 2 (donnée issue d'une concurrence), horloge `10:30:00Z` (yard 3). Quand `reintegrate(C)`. Alors aucun passage créé (le yard 2 existe déjà et n'est pas modifié), C `ACTIVE`.

**CA48 — Pas de re-DNF sur les yards recréés (RG19, RG26, CL7)** *(révisé)*
Donné l'état final de CA44 (B ACTIVE, passages 1, 2 SCAN et 3, 4 MANUAL), et dans R1 deux autres coureurs A et C ACTIVE avec passages SCAN yards 1 à 4. Quand `closeYard(R1)` à `12:20:00Z` (N = 4). Alors B, A et C restent ACTIVE (`F = {A, B, C}`), R1 reste `RUNNING`, `timedOutRunnerIds` vide. Puis A et C obtiennent un passage SCAN yard 5, B n'en a pas. Quand `closeYard(R1)` à `13:00:00Z` (N = 5). Alors B `DNF` `TIMEOUT` `dnfYard = 5` ; A et C ACTIVE (`F = {A, C}`) ; R1 reste `RUNNING`.

**CA49 — Scan après réintégration (RG14, RG26)**
Donné l'état final de CA44, horloge `12:55:00Z`. Quand `recordScan(B, 12:50:00Z)`. Alors passage SCAN yard 5 enregistré.

**CA50 — Statistiques après réintégration (RG9, RG10, CL7)**
Donné l'état final de CA44 avec B scanné yard 1 à `08:45:00Z` et yard 2 à `09:50:00Z`. Quand `compute`. Alors `completedLoops = 4`, `distanceMeters = 26824`, `elevationMeters = 200`, allure `425` (5 700 000 / 13 412), `corrected = true`.

**CA51 — Refus de réintégration (RG23, RG28)**
Quand le coureur est `ACTIVE` ou `WINNER`. Alors `BusinessConflictException` (message avec id et statut). Quand la course est `SETUP` ou `FINISHED`. Alors `BusinessConflictException`. Quand l'id est inconnu. Alors `ResourceNotFoundException`. Aucun `save` dans tous ces cas.

### Réactivation automatique (RG29 à RG32) — ajoutés en révision 2

**Situation de départ S** (utilisée par CA52 à CA58 et CA62, sauf mention contraire) : course R1 RUNNING avec trois coureurs :
- A : ACTIVE, passages SCAN yards 1, 2, 3 ;
- C : ACTIVE, passages SCAN yards 1, 2, 3 ;
- B (`qrToken = "tok-b"`) : passages SCAN yard 1 à `08:45:00Z` et yard 2 à `09:50:00Z`, aucun passage yard 3 ; `DNF` `TIMEOUT` `dnfYard = 3` (résultat de `closeYard(R1)` à `11:00:00Z`, où `F = {A, C}` : la course est restée RUNNING).

**CA52 — Réactivation nominale (RG13, RG29, RG30)**
Donné S, horloge `11:02:00Z` (yard 4, dernier yard clôturé = 3). Quand `recordScan("tok-b", 10:59:30Z)` (yard 3). Alors un passage est enregistré : `yardNumber = 3`, `source = SCAN`, `scannedAt = 10:59:30Z`, et renvoyé ; B : `ACTIVE`, `dnfReason = null`, `dnfYard = null` ; B est sauvegardé ; A, C et R1 inchangés (R1 reste RUNNING, aucune évaluation de fin de course).

**CA53 — Pas de réactivation d'un DNF non-timeout (RG22, RG30, CL16)**
Donné S modifiée : B `DNF` `VOLUNTARY` `dnfYard = 3` (passages 1 et 2), horloge `11:02:00Z`. Quand `recordScan("tok-b", 10:59:30Z)`. Alors `BusinessConflictException` dont le message contient l'id de B, `VOLUNTARY` et `3` ; aucun `save` ; B reste `DNF` `VOLUNTARY` `dnfYard = 3`. Même résultat avec `dnfReason = MANUAL` et `dnfReason = OTHER`.

**CA54 — Pas de réactivation si le scan ne porte pas sur dnfYard (RG30)**
Donné S, horloge `11:10:00Z` (yard 4). Quand `recordScan("tok-b", 11:05:00Z)` (yard 4, alors que `dnfYard = 3`). Alors `BusinessConflictException` dont le message contient `3` (dnfYard) et `4` (yard du scan) ; aucun `save` ; B reste `DNF` `TIMEOUT` `dnfYard = 3`.

**CA55 — Scan reçu après la fin de course (RG31, CL17)**
Donné R1 : A (passages 1 à 5) et B (`qrToken = "tok-b"`, passages 1 à 4) ; `closeYard(R1)` à `13:00:00Z` (N = 5) a désigné A `WINNER`, mis B `DNF` `TIMEOUT` `dnfYard = 5` et passé R1 `FINISHED`. Horloge `13:00:20Z`. Quand `recordScan("tok-b", 12:59:58Z)` (yard 5). Alors `BusinessConflictException` dont le message contient l'id de R1, `FINISHED` et `5` ; aucun `save` (ni passage, ni coureur, ni course) ; A reste `WINNER`, B reste `DNF` `TIMEOUT` `dnfYard = 5`, R1 reste `FINISHED`.

**CA56 — Yard N+1 déjà clos : pas de réactivation (RG32, CL18)**
Donné S, puis A et C obtiennent un passage SCAN yard 4 et `closeYard(R1)` est exécuté à `12:00:00Z` (N = 4 : B, déjà DNF, n'est pas touché ; `F = {A, C}`, R1 reste RUNNING). Horloge `12:00:10Z` (yard 5, dernier yard clôturé = 4). Quand `recordScan("tok-b", 10:59:30Z)` (yard 3). Alors `BusinessConflictException` dont le message contient `3` (yard du scan), `5` (yard courant) et le mot "réintégration" ; aucun `save` ; B reste `DNF` `TIMEOUT` `dnfYard = 3`.

**CA57 — Idempotence et double scan après réactivation (RG15, CL19)**
Donné l'état final de CA52 (B ACTIVE, passage yard 3 `scannedAt = 10:59:30Z`), horloge `11:03:00Z`. Quand `recordScan("tok-b", 10:59:30Z)` (retry). Alors le passage existant est renvoyé, aucun `save`, B ACTIVE. Quand `recordScan("tok-b", 10:59:40Z)`. Alors `BusinessConflictException`, aucun `save`, B ACTIVE.

**CA58 — Clôtures après réactivation (RG19, RG20, CL12)**
Donné l'état final de CA52. Quand `closeYard(R1)` à `11:05:00Z` (N = 3). Alors B reste ACTIVE, `timedOutRunnerIds` vide, R1 RUNNING. Puis A et C obtiennent un passage yard 4, B n'en a pas. Quand `closeYard(R1)` à `12:00:00Z` (N = 4). Alors B `DNF` `TIMEOUT` `dnfYard = 4`, A et C ACTIVE, R1 RUNNING.

**CA59 — Statistiques après réactivation (RG9, RG10)**
Donné l'état final de CA52 (B : passages SCAN yard 1 `08:45:00Z`, yard 2 `09:50:00Z`, yard 3 `10:59:30Z`). Quand `compute`. Alors `completedLoops = 3`, `distanceMeters = 20118`, `elevationMeters = 150`, allure `461` (9 270 000 / 20 118 = 460,78), `corrected = false`.

**CA60 — Règle k-1 en mode réactivation (RG14, RG30, CL20)**
Donné R1, B (`"tok-b"`) `DNF` `TIMEOUT` `dnfYard = 3` avec un seul passage yard 1 (donnée incohérente), horloge `11:02:00Z`. Quand `recordScan("tok-b", 10:59:30Z)` (yard 3). Alors `BusinessConflictException` dont le message mentionne le yard `2` ; aucun `save` ; B reste `DNF` `TIMEOUT` `dnfYard = 3`.

**CA61 — Transition unique DNF vers ACTIVE (RG25, RG29, RG30)**
Donné un `Runner` `DNF` `TIMEOUT` `dnfYard = 3`. Quand `reactivate()`. Alors `ACTIVE`, `dnfReason = null`, `dnfYard = null`. Donné un `Runner` `ACTIVE`, puis un `Runner` `WINNER`. Quand `reactivate()`. Alors `IllegalStateException` dans les deux cas, statut inchangé. Complété en revue de code : `ReintegrationService` et `PassageRecordingService` appellent tous deux `Runner.reactivate()` et n'affectent jamais `status`, `dnfReason` ou `dnfYard` directement.

**CA62 — Coureur réactivé pris en compte à la clôture suivante (RG21, RG30)**
Donné l'état final de CA52 (A, B, C ACTIVE avec passages 1 à 3). À `11:30:00Z`, `declareDnf(A, VOLUNTARY)` (A : `dnfYard = 4`). Ensuite, seul B obtient un passage SCAN yard 4 ; A et C n'en ont pas. Quand `closeYard(R1)` à `12:00:00Z` (N = 4). Alors C `DNF` `TIMEOUT` `dnfYard = 4` ; A reste `VOLUNTARY` ; `F = {B}` et B est ACTIVE : B `WINNER`, R1 `FINISHED`.

### Couverture RG / CA

| RG | CA |
|---|---|
| RG1 | CA17, CA26 (et toutes les CA de service à horloge fixe) |
| RG2 | CA1, CA2, CA5 |
| RG3 | CA1 à CA4, CA8 |
| RG4 | CA6, CA7 |
| RG5 à RG7 | CA9, CA10, CA12, CA15, CA59 |
| RG8 | CA10, CA12, CA14 |
| RG9 | CA10, CA11, CA12, CA13, CA14, CA50, CA59 |
| RG10 | CA9, CA12, CA13, CA50, CA59 |
| RG11 | CA9, CA16 |
| RG12 | CA17, CA21 à CA24 |
| RG13 | CA17, CA18, CA19, CA52 |
| RG14 | CA20, CA49, CA60 |
| RG15 | CA25, CA57 |
| RG16 | CA26, CA27, CA28 |
| RG17 | CA31, CA32, CA38 |
| RG18 | CA27, CA33 |
| RG19 | CA27, CA28, CA29, CA48, CA58 |
| RG20 | CA30, CA38, CA58 |
| RG21 | CA31, CA33 à CA37, CA39, CA62 |
| RG22 | CA40 à CA43, CA53 |
| RG23 | CA44, CA51 |
| RG24 | CA44 à CA47 |
| RG25 | CA44, CA46, CA61 |
| RG26 | CA28, CA48, CA49 |
| RG27 | CA44, CA47 |
| RG28 | CA7, CA21, CA23, CA32, CA43, CA51, CA53 à CA56 |
| RG29 | CA40, CA52, CA61 (et revue de code : aucune affectation directe de statut dans les services) |
| RG30 | CA21, CA52, CA53, CA54, CA60, CA62 |
| RG31 | CA55 |
| RG32 | CA56 |

---

## 7. Points ouverts

Chaque point propose une **hypothèse par défaut (HYPOTHÈSE)**, déjà appliquée dans les RG/CA ci-dessus. Les points tranchés par l'utilisateur sont marqués **TRANCHÉ**. Pour PO1, PO4, PO6 à PO8 et PO10 à PO19, l'utilisateur a demandé de conserver l'hypothèse par défaut.

**PO1 — Bornes des yards.** HYPOTHÈSE conservée : intervalle semi-ouvert, l'instant `started_at + k × loop_duration` appartient au yard k+1 (RG2).

**PO2 — Plage de réintégration.** TRANCHÉ : `[dnf_yard, C - 1]` (RG24).

**PO3 — `dnf_yard` d'un DNF manuel.** TRANCHÉ : premier yard non terminé = `dernier yard_number + 1` (RG22).

**PO4 — Déclencheur manqué sur plusieurs yards.** HYPOTHÈSE conservée : application littérale de CLAUDE.md (seul N = C - 1 est contrôlé, `dnf_yard = N`) ; l'admin corrige manuellement si besoin.

**PO5 — Scan effectué dans la fenêtre mais reçu après l'auto-DNF.** TRANCHÉ : réactivation automatique (RG30 à RG32). Les modalités de détail font l'objet de PO20 à PO24.

**PO6 — Horloge de référence du scan.** HYPOTHÈSE conservée : `scanned_at` client utilisé tel quel, rejet strict si `scanned_at > now` serveur, aucune tolérance. Remarque : avec la réactivation automatique, le `scanned_at` client décide désormais aussi de l'annulation d'un DNF ; une horloge d'appareil mal réglée a donc plus d'impact.

**PO7 — Double scan juste après la cloche / temps de boucle minimal.** HYPOTHÈSE conservée : aucun temps minimal.

**PO8 — Règle du scan tardif (RG14) et ordre de la file locale.** HYPOTHÈSE conservée : ordre FIFO garanti par la PWA (incrément 4). S'applique aussi à la réactivation (RG30.5).

**PO9 — Détails de fin de course.** TRANCHÉ : lecture littérale (RG21) : aucun finisher ou unique finisher non actif : FINISHED sans vainqueur ; course à un coureur : vainqueur dès la clôture du yard 1 ; détection à la cloche uniquement.

**PO10 — Yard courant d'une course FINISHED et date de fin.** HYPOTHÈSE conservée : `currentYard = 0`, pas de migration.

**PO11 — Réintégration sur course FINISHED.** HYPOTHÈSE conservée : refusée.

**PO12 — Raisons de DNF réintégrables.** HYPOTHÈSE conservée : toutes.

**PO13 — DNF manuel sur course SETUP.** HYPOTHÈSE conservée : refusé.

**PO14 — Définition de l'allure affichée.** HYPOTHÈSE conservée : moyenne sur les passages SCAN, secondes entières par km, HALF_UP ; pas de saisie manuelle hors réintégration.

**PO15 — Modification des paramètres d'une course RUNNING.** HYPOTHÈSE conservée : immuables hors SETUP, contrôle en incrément 3.

**PO16 — Classement.** HYPOTHÈSE conservée : hors incrément 2.

**PO17 — Concurrence scan / clôture.** HYPOTHÈSE conservée : pas de verrou en incrément 2. Remarque : la réactivation automatique rend le résultat final cohérent quel que soit l'ordre (scan avant clôture : pas de DNF ; scan après clôture : réactivation), sauf lectures simultanées non isolées (voir PO24).

**PO18 — `dnf_reason` / `dnf_yard` d'un WINNER.** HYPOTHÈSE conservée : null.

**PO19 — Démarrage de la course.** HYPOTHÈSE conservée : hors incrément 2, `started_at = now`.

### Nouveaux points ouverts (révision 2, réactivation automatique)

**PO20 — Périmètre de la réactivation.** La réactivation concerne-t-elle uniquement `dnf_reason = TIMEOUT` avec `dnf_yard = k` ? HYPOTHÈSE : oui (RG30.2 et RG30.3). Un DNF VOLUNTARY / MANUAL / OTHER résulte d'une décision humaine explicite et n'est jamais annulé par un scan ; seule la réintégration l'annule. Un scan sur un autre yard que `dnf_yard` ne réactive pas.

**PO21 — État après réactivation et traçabilité.** HYPOTHÈSE : `dnf_reason` et `dnf_yard` remis à null, par la même transition `Runner.reactivate()` que la réintégration (RG29). Aucune trace persistée de la réactivation : le passage est un SCAN normal, compté dans l'allure, sans badge "corrigé". Alternative : tracer la réactivation (journal applicatif, ou colonne dédiée via une migration V2) si l'organisation veut pouvoir auditer les DNF annulés.

**PO22 — Scan tardif reçu après la fin de course.** HYPOTHÈSE : rejet par `BusinessConflictException` explicite (id course, statut FINISHED, yard du scan), sans aucune modification du vainqueur ni des statuts (RG31). Conséquence à arbitrer : aucun outil ne permet aujourd'hui à l'admin de corriger une course terminée (la réintégration est refusée sur FINISHED, PO11). Un outil d'arbitrage (réouverture de course, révocation de vainqueur) serait à spécifier en incrément 3 si besoin. Le signalement à l'admin (notification, liste des scans rejetés) relève aussi des incréments 3/4.

**PO23 — Scan tardif alors que le yard N+1 est aussi clos.** HYPOTHÈSE : rejet (RG32) ; l'admin peut réintégrer (passages MANUAL de `dnf_yard` à C-1 ; le vrai temps du yard N est alors perdu pour l'allure). Alternative : accepter le passage N, réactiver, puis appliquer immédiatement l'auto-DNF sur N+1 (`dnf_yard = N + 1` si pas de passage N+1) ; plus fidèle aux données mais crée une seconde logique d'auto-DNF hors clôture.

**PO24 — Doublon identique alors que le coureur est encore DNF.** Cas issu d'une concurrence : un passage SCAN sur `dnf_yard` existe déjà (enregistré pendant la clôture) mais le coureur est `DNF` `TIMEOUT`. HYPOTHÈSE : l'appel reste strictement idempotent (passage existant renvoyé, statut inchangé, RG15) ; la correction passe par la réintégration (qui ne recréera pas ce yard, RG24). Alternative : réactiver dans ce cas précis.

### Points ouverts ajoutés après relecture du testeur

**PO25 — Transitions appelées hors de leur état source.** Le comportement de `Runner.markDnf` (ex. sur un coureur WINNER ou déjà DNF), `Runner.markWinner` (ex. sur un coureur DNF) et `Race.finish` (ex. sur une course SETUP ou déjà FINISHED) appelés hors de leur état source n'est pas spécifié ; seul `Runner.reactivate()` lève une `IllegalStateException` (RG29, CA61). Les services ne les appellent jamais hors état source (préconditions RG19, RG21, RG22). HYPOTHÈSE : à traiter plus tard ; aucun test n'est exigé sur ces cas dans l'incrément 2.

**PO26 — `closeYard(race)` appelé directement sur une course non RUNNING.** Seul `closeElapsedYards()` filtre les courses `RUNNING` (RG17) ; le comportement de `closeYard` appelé directement sur une course `SETUP` ou `FINISHED` n'est pas spécifié. HYPOTHÈSE : non spécifié dans l'incrément 2 (aucun test exigé) ; à trancher avant l'exposition d'une action de clôture en incrément 3.
