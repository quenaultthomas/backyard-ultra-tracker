# Spec Incrément 1 — Domaine + Persistance

> Projet : Backyard Ultra Tracker
> Date de rédaction : 2026-09-25
> Statut : en attente de validation

---

## 1. Périmètre

### Inclus

- Définition des trois entités JPA du domaine : `Race`, `Runner`, `Passage`.
- Définition des types énumérés (enums) associés aux statuts et aux raisons de DNF.
- Définition des associations JPA entre entités (clés étrangères, relations).
- Repositories Spring Data JPA pour chacune des trois entités, avec les méthodes de requête nécessaires aux incréments suivants.
- Migration Flyway initiale (`V1__init.sql`) créant le schéma PostgreSQL complet.
- Structure du projet Maven sous `backend/`, package racine `fr.backyard`.
- Tests d'intégration de persistance (Spring Data + base embarquée H2 ou Testcontainers PostgreSQL) vérifiant que le mapping JPA est cohérent avec le schéma Flyway.

### Explicitement exclu

- Toute logique métier (calculs dérivés, auto-DNF, réintégration) — couverte par l'incrément 2.
- Toute couche API REST ou DTO — couverte par l'incrément 3.
- Tout frontend ou PWA — couvert par l'incrément 4.
- Génération automatique du schéma par JPA/Hibernate (`ddl-auto` autre que `validate` ou `none` en production).
- Données de référence ou jeux de données initiaux (aucun `INSERT` dans la migration V1).

---

## 2. Règles de gestion

### Entité Race

**RG1 — Champs obligatoires de Race**
Une `Race` doit obligatoirement avoir : `name` (non vide), `race_date` (date calendaire), `loop_distance` (entier > 0, en mètres), `loop_duration` (entier > 0, en secondes), `loop_elevation` (entier >= 0, en mètres D+).

**RG2 — Statut initial de Race**
À la création, le statut d'une `Race` est toujours `SETUP`. Les valeurs autorisées sont : `SETUP`, `RUNNING`, `FINISHED`.

**RG3 — Champ started_at**
`started_at` est nullable. Il n'est renseigné que lorsque la course passe en statut `RUNNING`. Il reste null tant que la course est en `SETUP`.

**RG4 — Unicité du nom de Race**
Deux courses ne peuvent pas avoir le même `name`. Contrainte d'unicité en base sur la colonne `name`.

### Entité Runner

**RG5 — Appartenance obligatoire à une Race**
Un `Runner` appartient obligatoirement à exactement une `Race` (clé étrangère `race_id NOT NULL`).

**RG6 — Champs obligatoires de Runner**
Un `Runner` doit obligatoirement avoir : `bib` (numéro de dossard, entier > 0), `name` (non vide), `qr_token` (chaîne opaque, non vide).

**RG7 — Unicité du dossard par course**
Deux coureurs d'une même course ne peuvent pas avoir le même `bib`. Contrainte d'unicité composite en base sur `(race_id, bib)`.

**RG8 — Unicité du qr_token**
Le `qr_token` est unique toutes courses confondues. Contrainte d'unicité en base sur la colonne `qr_token`.

**RG9 — Statut initial de Runner**
À la création, le statut d'un `Runner` est toujours `ACTIVE`. Les valeurs autorisées sont : `ACTIVE`, `DNF`, `WINNER`.

**RG10 — Champs DNF de Runner**
`dnf_reason` et `dnf_yard` sont nullables. Ils ne sont renseignés que lorsque le statut est `DNF` ou `WINNER`. Les valeurs autorisées pour `dnf_reason` sont : `VOLUNTARY`, `TIMEOUT`, `MANUAL`, `OTHER`.

**RG11 — Cohérence DNF**
Si le statut est `DNF`, alors `dnf_reason` doit être non null et `dnf_yard` doit être non null (entier >= 1). Cette contrainte est portée par le domaine applicatif (non en base de données).

### Entité Passage

**RG12 — Appartenance obligatoire à un Runner**
Un `Passage` appartient obligatoirement à exactement un `Runner` (clé étrangère `runner_id NOT NULL`).

**RG13 — Champs obligatoires de Passage**
Un `Passage` doit obligatoirement avoir : `yard_number` (entier >= 1), `source` (valeur parmi `SCAN` ou `MANUAL`).

**RG14 — Champ scanned_at**
`scanned_at` est nullable. Il est renseigné lors d'un scan physique (`source = SCAN`). Il est null lorsque le passage est recréé manuellement lors d'une réintégration (`source = MANUAL`).

**RG15 — Immuabilité des passages**
Les passages sont une donnée brute immuable. Aucune modification d'un passage existant n'est permise au niveau de la persistance. Seule l'insertion est autorisée. Il n'existe pas de mécanisme de mise à jour des champs d'un `Passage`.

**RG16 — Unicité du passage par coureur et par yard**
Un coureur ne peut pas avoir deux passages valides pour le même `yard_number`. Contrainte d'unicité composite en base sur `(runner_id, yard_number)`.

### Schéma et migrations

**RG17 — Migration Flyway versionnée**
Le schéma est entièrement créé par la migration `V1__init.sql` placée dans `src/main/resources/db/migration/`. Aucune autre migration ne doit exister dans cet incrément.

**RG18 — Pas de génération auto en production**
La propriété `spring.jpa.hibernate.ddl-auto` est positionnée à `validate` (ou `none`) dans le profil de production. Elle peut être `create-drop` uniquement dans le profil de test avec base embarquée.

**RG19 — Clés primaires auto-générées**
Les identifiants (`id`) des trois entités sont des `BIGINT` auto-incrémentés (séquence PostgreSQL ou `GENERATED ALWAYS AS IDENTITY`).

### Repositories

**RG20 — Requêtes minimales exposées par les repositories**
Les repositories doivent exposer au minimum les méthodes suivantes, nécessaires aux incréments suivants :

- `RaceRepository` : `findById`, `findAll`, `save`, `findByStatus(RaceStatus)`.
- `RunnerRepository` : `findById`, `findByRaceId(Long)`, `findByQrToken(String)`, `findByRaceIdAndStatus(Long, RunnerStatus)`, `save`.
- `PassageRepository` : `findById`, `findByRunnerId(Long)`, `findByRunnerIdAndYardNumber(Long, int)`, `save`.

---

## 3. Cas limites

**CL1 — Race avec started_at null**
Une course en statut `SETUP` a `started_at` à null. L'entité doit se persister sans erreur avec ce champ null.

**CL2 — Passage avec scanned_at null**
Un passage de source `MANUAL` a `scanned_at` à null. L'entité doit se persister sans erreur avec ce champ null.

**CL3 — Runner sans passage**
Un coureur peut être persisté sans aucun passage associé. La collection de passages est vide mais non null.

**CL4 — Conflit de dossard dans la même course**
Tenter d'insérer deux coureurs avec le même `bib` dans la même `race_id` doit lever une exception de contrainte d'unicité (DataIntegrityViolationException ou équivalent JPA).

**CL5 — Conflit de dossard entre courses différentes**
Deux coureurs de deux courses différentes peuvent avoir le même `bib` sans erreur.

**CL6 — Conflit de qr_token**
Tenter d'insérer deux coureurs avec le même `qr_token`, même dans des courses différentes, doit lever une exception de contrainte d'unicité.

**CL7 — Conflit de passage sur le même yard**
Tenter d'insérer deux passages pour le même `runner_id` et le même `yard_number` doit lever une exception de contrainte d'unicité.

**CL8 — Suppression d'une Race avec coureurs**
La suppression d'une `Race` ayant des coureurs associés doit être bloquée par la contrainte de clé étrangère (ou cascade définie explicitement). Ce comportement doit être documenté dans la migration.

**CL9 — Suppression d'un Runner avec passages**
La suppression d'un `Runner` ayant des passages associés doit être bloquée par la contrainte de clé étrangère (ou cascade définie explicitement). Ce comportement doit être documenté dans la migration.

**CL10 — Valeurs loop_elevation à zéro**
Une course avec `loop_elevation = 0` (course plate) est valide et doit se persister sans erreur.

**CL11 — Champ name de Race vide**
Tenter de persister une `Race` avec `name` null ou vide doit être rejeté par la contrainte de validation (annotation `@NotBlank` ou contrainte `NOT NULL` en base).

**CL12 — Champ name de Runner vide**
Tenter de persister un `Runner` avec `name` null ou vide doit être rejeté par la validation.

---

## 4. Critères d'acceptation

Les critères suivants sont vérifiables par des tests d'intégration de couche persistance (avec base de données).

**CA1 — Persistance minimale de Race (couvre RG1, RG2, RG19)**
Donnée : un objet `Race` avec name="Test Race", race_date=2026-10-01, status=SETUP, started_at=null, loop_distance=6700, loop_duration=3600, loop_elevation=100.
Quand : on appelle `raceRepository.save(race)`.
Alors : la race est persistée avec un `id` non null, et `raceRepository.findById(id)` retourne la race avec tous les champs égaux aux valeurs initiales.

**CA2 — Statut SETUP par défaut (couvre RG2)**
Donnée : une `Race` construite sans préciser le statut.
Quand : on lit le statut de l'objet construit via le constructeur ou builder par défaut.
Alors : le statut est `SETUP`.

**CA3 — started_at null à la création (couvre RG3, CL1)**
Donnée : une `Race` en statut `SETUP` persistée sans `started_at`.
Quand : on relit la race depuis la base.
Alors : `started_at` est null.

**CA4 — Unicité du nom de Race (couvre RG4)**
Donnée : une `Race` nommée "Doublon" déjà persistée.
Quand : on tente de persister une seconde `Race` avec name="Doublon".
Alors : une exception de type `DataIntegrityViolationException` (ou sous-type) est levée.

**CA5 — Persistance minimale de Runner (couvre RG5, RG6, RG9, RG19)**
Donnée : une `Race` persistée, un `Runner` avec bib=1, name="Alice", qr_token="tok-alice-001", status=ACTIVE, rattaché à cette race.
Quand : on appelle `runnerRepository.save(runner)`.
Alors : le runner est persisté avec un `id` non null, et `runnerRepository.findById(id)` retourne le runner avec tous les champs attendus.

**CA6 — Statut ACTIVE par défaut pour Runner (couvre RG9)**
Donnée : un `Runner` construit sans préciser le statut.
Quand : on lit le statut de l'objet construit via le constructeur ou builder par défaut.
Alors : le statut est `ACTIVE`.

**CA7 — Unicité du dossard par course (couvre RG7, CL4)**
Donnée : une `Race` persistée, un `Runner` avec bib=1 déjà persisté dans cette race.
Quand : on tente de persister un second `Runner` avec bib=1 dans la même race.
Alors : une `DataIntegrityViolationException` est levée.

**CA8 — Dossard identique dans deux courses différentes autorisé (couvre RG7, CL5)**
Donnée : deux `Race` persistées (race1, race2), un runner avec bib=1 persisté dans race1.
Quand : on persiste un runner avec bib=1 dans race2.
Alors : aucune exception n'est levée, et les deux runners ont des `id` distincts.

**CA9 — Unicité du qr_token (couvre RG8, CL6)**
Donnée : un `Runner` avec qr_token="tok-dup" persisté.
Quand : on tente de persister un second `Runner` avec qr_token="tok-dup" (même ou autre course).
Alors : une `DataIntegrityViolationException` est levée.

**CA10 — Champs DNF nullables pour un runner ACTIVE (couvre RG10)**
Donnée : un `Runner` persisté en statut `ACTIVE` sans `dnf_reason` ni `dnf_yard`.
Quand : on relit le runner depuis la base.
Alors : `dnf_reason` est null et `dnf_yard` est null.

**CA11 — Persistance minimale de Passage avec scanned_at (couvre RG12, RG13, RG19)**
Donnée : un `Runner` persisté, un `Passage` avec yard_number=1, source=SCAN, scanned_at=2026-10-01T08:00:00Z.
Quand : on appelle `passageRepository.save(passage)`.
Alors : le passage est persisté avec un `id` non null, et `passageRepository.findById(id)` retourne le passage avec tous les champs attendus.

**CA12 — Passage manuel avec scanned_at null (couvre RG14, CL2)**
Donnée : un `Runner` persisté, un `Passage` avec yard_number=2, source=MANUAL, scanned_at=null.
Quand : on appelle `passageRepository.save(passage)`.
Alors : le passage est persisté sans erreur, et `scanned_at` est null à la relecture.

**CA13 — Unicité passage par coureur et yard (couvre RG16, CL7)**
Donnée : un `Runner` persisté, un `Passage` avec yard_number=1 déjà persisté pour ce runner.
Quand : on tente de persister un second `Passage` avec yard_number=1 pour le même runner.
Alors : une `DataIntegrityViolationException` est levée.

**CA14 — Runner sans passage (couvre CL3)**
Donnée : un `Runner` persisté sans aucun passage.
Quand : on appelle `passageRepository.findByRunnerId(runnerId)`.
Alors : la liste retournée est vide (non null).

**CA15 — findByRaceId retourne tous les coureurs d'une course (couvre RG20)**
Donnée : une `Race` persistée avec 3 runners, une autre race avec 1 runner.
Quand : on appelle `runnerRepository.findByRaceId(race1.getId())`.
Alors : la liste retourne exactement 3 runners, tous appartenant à race1.

**CA16 — findByQrToken retourne le bon coureur (couvre RG20)**
Donnée : un `Runner` persisté avec qr_token="tok-unique-xyz".
Quand : on appelle `runnerRepository.findByQrToken("tok-unique-xyz")`.
Alors : l'Optional retourné est présent et contient le runner attendu.

**CA17 — findByRaceIdAndStatus filtre par statut (couvre RG20)**
Donnée : une `Race` avec 2 runners ACTIVE et 1 runner DNF.
Quand : on appelle `runnerRepository.findByRaceIdAndStatus(raceId, RunnerStatus.ACTIVE)`.
Alors : la liste retourne exactement 2 runners.

**CA18 — findByStatus retourne les courses par statut (couvre RG20)**
Donnée : 2 races en statut SETUP, 1 race en statut RUNNING.
Quand : on appelle `raceRepository.findByStatus(RaceStatus.RUNNING)`.
Alors : la liste retourne exactement 1 race.

**CA19 — findByRunnerIdAndYardNumber retourne le bon passage (couvre RG20)**
Donnée : un `Runner` avec un passage sur yard_number=3 et un passage sur yard_number=4.
Quand : on appelle `passageRepository.findByRunnerIdAndYardNumber(runnerId, 3)`.
Alors : l'Optional retourné est présent et correspond au passage du yard 3.

**CA20 — Validation du schéma par Flyway (couvre RG17, RG18)**
Donnée : l'application démarrée avec le profil de production (`ddl-auto=validate`).
Quand : Flyway applique la migration V1 puis Hibernate valide le schéma contre les entités JPA.
Alors : l'application démarre sans erreur de validation de schéma.

**CA21 — Champ name vide rejeté pour Race (couvre RG1, CL11)**
Donnée : un objet `Race` avec name=null.
Quand : on tente de persister cette race.
Alors : une exception de validation ou de contrainte est levée avant ou lors du flush.

**CA22 — loop_elevation à zéro autorisé (couvre CL10)**
Donnée : une `Race` avec loop_elevation=0.
Quand : on persiste et relit cette race.
Alors : aucune exception n'est levée et loop_elevation vaut 0 à la relecture.

---

## 5. Points ouverts

**PO1 — Stratégie de cascade sur Race -> Runner et Runner -> Passage**
CLAUDE.md ne précise pas si la suppression d'une `Race` doit supprimer en cascade ses coureurs et leurs passages (CASCADE DELETE en base), ou si elle doit être bloquée (RESTRICT). Idem pour la suppression d'un `Runner`. La spec retient RESTRICT par défaut (CL8, CL9), mais ce choix doit être confirmé.

**PO2 — Type de la colonne scanned_at**
CLAUDE.md ne précise pas si `scanned_at` doit être stocké avec timezone (`TIMESTAMP WITH TIME ZONE`) ou sans. Impacte le mapping JPA (`Instant` vs `LocalDateTime`). La recommandation est `TIMESTAMP WITH TIME ZONE` / `Instant` pour éviter les ambiguïtés, mais à confirmer.

**PO3 — Type de la colonne race_date**
CLAUDE.md indique `race_date` comme "date" sans préciser si c'est une date calendaire pure (`DATE` SQL / `LocalDate` Java) ou un timestamp. La spec retient `DATE` / `LocalDate`, mais à confirmer.

**PO4 — Longueur maximale des champs texte**
Aucune longueur maximale n'est précisée pour `Race.name`, `Runner.name` et `Runner.qr_token`. La migration devra choisir une taille (ex. `VARCHAR(255)`). Confirmer si des contraintes métier existent.

**PO5 — Format et génération du qr_token**
CLAUDE.md décrit `qr_token` comme "opaque" mais ne précise pas qui le génère (base de données, couche service, client) ni son format (UUID, token aléatoire, etc.). La génération sera traitée en incrément 3 (API), mais la contrainte de longueur en base doit être anticipée dès V1.

**PO6 — Valeur minimale de bib**
La spec retient `bib > 0` (entier strictement positif) d'après le contexte métier, mais CLAUDE.md ne le précise pas explicitement. À confirmer si le dossard 0 est possible.

**PO7 — Base de test : H2 embarqué ou Testcontainers PostgreSQL**
Les tests d'intégration de persistance peuvent utiliser H2 (plus rapide, dialecte différent) ou Testcontainers avec PostgreSQL (fidèle à la prod). CLAUDE.md ne tranche pas. Ce choix impacte la fiabilité des tests de contraintes (ex. types PostgreSQL spécifiques). Recommandation : Testcontainers, mais à confirmer selon les contraintes d'environnement CI.

**PO8 — Comportement de `findByRunnerIdAndYardNumber` en cas d'absence**
La méthode peut retourner `Optional<Passage>` ou `Passage` nullable. Le choix `Optional` est recommandé pour la cohérence avec les conventions Spring Data, mais doit être validé avec l'équipe.
