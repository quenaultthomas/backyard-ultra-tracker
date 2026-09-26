# Backyard Ultra Tracker

Outil de suivi de courses "backyard ultra" amateurs. Budget quasi nul, hébergement OVH VPS (PostgreSQL installée dessus).

## Contexte métier

- Tous les coureurs partent ensemble en haut de chaque yard pour boucler une boucle. Pas de passage valide sur le yard courant = DNF.
- La course s'arrête quand un seul coureur termine un yard que personne d'autre ne termine : il est vainqueur, tous les autres sont DNF.
- Plusieurs courses gérées en parallèle, chacune avec ses paramètres (nom, date, loop_distance, loop_duration, loop_elevation) et son lien d'inscription public.

## Modèle de données

- **Race** : id, name, race_date, status (setup/running/finished), started_at, loop_distance (m), loop_duration (s), loop_elevation (D+ m)
- **Runner** : id, race_id, bib, name, qr_token (opaque), status (active/dnf/winner), dnf_reason (voluntary/timeout/manual/other), dnf_yard
- **Passage** : id, runner_id, scanned_at (null si recréé), yard_number, source (scan/manual)

Principe : les passages sont une donnée brute immuable. Yard courant, tours, distance, dénivelé, allure et classement sont DÉRIVÉS des paramètres de la course et jamais stockés.

## Règles métier clés

- **Auto-DNF** : au démarrage du yard N+1, tout coureur `active` de la course sans passage valide sur le yard N passe en DNF (reason=timeout, dnf_yard=N). Un seul contrôle, uniquement sur le yard courant, indépendant par course. Côté serveur (@Scheduled).
- **DNF manuel** : action admin avec confirmation, jamais via le scan QR.
- **Réintégration** : correction d'erreur. Recréer les passages manquants (source=manual, scanned_at=null) pour chaque yard entre dnf_yard et le yard courant. Exclus du calcul d'allure, badge "corrigé".
- **Filet réseau** : le scan est enregistré localement d'abord (file + retry), puis envoyé à l'API.

## Stack

Spring Boot + Spring Data JPA, API REST, PostgreSQL + Flyway, PWA (scan QR caméra, dashboard en polling 2-3 s, admin, inscription par course).

## Exigences de qualité (non négociables)

- Couches : controller / service / repository / domain. Jamais de logique métier dans un controller.
- Logique métier testable sans Spring ni DB (méthodes pures ou services avec dépendances injectées).
- Couverture unitaire >= 80 % sur le cœur métier (domain + service : auto-DNF, calculs dérivés, réintégration). Pas d'exigence sur controllers/DTOs/config.
- Erreurs explicites : aucune exception avalée, API cohérente (400/404/409), messages exploitables.
- Code lisible : nommage métier, méthodes courtes, aucune duplication de règle métier.
- Migrations Flyway versionnées, jamais de génération auto du schéma en prod.

## Définition de "fini" (par incrément)

Compile sans warning + tests unitaires verts + couverture cœur métier >= 80 % + aucune règle métier dupliquée.

## Incréments (dans cet ordre, sans en sauter)

1. Domaine + persistance : entités JPA, repositories, migration initiale.
2. Logique métier core (tests d'abord) : calculs dérivés, auto-DNF, réintégration.
3. API REST : CRUD courses/coureurs, actions admin (DNF manuel, réintégration).
4. Frontend PWA : scan, dashboard, admin, inscription.

Ne jamais passer à l'incrément suivant tant que le courant n'a pas ses tests, que le testeur n'a pas rendu un verdict technique OK et que l'agent fonctionnel n'a pas prononcé le GO (voir « Workflow de validation d'incrément »).

## Workflow d'orchestration

Pour chaque incrément, déléguer dans cet ordre aux sous-agents :

1. `fonctionnel` : rédige la spec de l'incrément dans `docs/specs/incrementN.md` (règles, cas limites, critères d'acceptation numérotés).
2. `testeur` : écrit les tests unitaires à partir de la spec (pas à partir du code) pour l'incrément 2, ou les complète après implémentation pour les autres.
3. `developpeur` : implémente jusqu'à ce que les tests passent.
4. `testeur` : lance build + tests + couverture, rend un verdict **technique** `OK` ou `KO` (définition de « fini ») avec la liste des écarts. Ce n'est pas le verdict final.

En cas de `KO`, retour au développeur avec les écarts (3 aller-retours maximum, puis remonter la question à l'utilisateur).

Un `OK` du testeur ouvre la validation (`/valider-increment <n>`, section suivante). Le verdict final GO / GO sous réserves / NO-GO appartient exclusivement à l'agent `fonctionnel`.

## Cycle d'un incrément
1. git-publisher (start) → branche dédiée
2. fonctionnel → spec
3. dev → implémentation
4. testeur → tests unitaires + verdict technique OK/KO
5. Si OK → `/valider-increment <n>` : tests d'intégration, E2E, revue de cohérence, verdict de l'agent fonctionnel
6. Si GO → git-publisher (publish) → MR
7. Arrêt : l'humain relit et merge

## Workflow de validation d'incrément

Chaque incrément suit cette chaîne. Aucun incrément n'est « terminé » sans verdict écrit de l'agent fonctionnel.

```
Développement  →  test-integration-backend  →  test-e2e-frontend  →  revue-coherence-patrimoine  →  Rapport de synthèse  →  Agent fonctionnel (GO / NO-GO)
```

Commande : `/valider-increment <n>`

### Agents
- **test-integration-backend** : tests d'intégration API / persistance (JUnit + AssertJ, `@SpringBootTest` sur H2 en mode PostgreSQL, classes `*IT` exécutées par failsafe). Rapport `INC-<n>-integration.md`.
- **test-e2e-frontend** : parcours utilisateur dans le navigateur (Playwright par défaut, à partir de l'incrément 4 ; sans objet tant qu'il n'y a pas de front). Rapport `INC-<n>-e2e.md`.
- **revue-coherence-patrimoine** : contrôleur indépendant en lecture seule (couverture, tests orphelins, tests désactivés/affaiblis, véracité des rapports, non-régression). Rapport `INC-<n>-coherence.md`. Il constate, il ne tranche pas.
- **Agent fonctionnel** (`fonctionnel`) : porte les exigences et critères d'acceptation (`docs/specs/increment<n>.md`), relit les rapports, et est le **seul décideur** du verdict GO / GO sous réserves / NO-GO.

### Patrimoine de test
- Référentiel : `docs/tests/PATRIMOINE.md` (matrice exigence ↔ tests), mis à jour à chaque incrément.
- Rapports : `docs/tests/rapports/INC-<n>-*.md`, à partir de `TEMPLATE-rapport-increment.md`.
- Suite complète backend : `mvn -B -f backend/pom.xml clean verify` (unitaires surefire `*Test` + intégration failsafe `*IT` + couverture JaCoCo).

### Règles
1. Toute exigence a au moins un test ; sinon c'est un écart consigné.
2. Aucun test supprimé, désactivé ou assoupli sans motif écrit et accord de l'agent fonctionnel.
3. Non-régression : la suite complète est exécutée à chaque incrément.
4. Un résultat de test n'est cité que s'il a été réellement exécuté.
5. Les agents de test ne modifient pas le code de production pour faire passer un test : ils remontent le bug.
6. On ne démarre pas l'incrément n+1 tant que l'incrément n n'est pas en GO.