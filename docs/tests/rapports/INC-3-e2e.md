# Rapport de test : INC-3 (e2e)

- **Date** : 2026-09-26
- **Agent auteur** : test-e2e-frontend
- **Version / commit testé** : branche `chore/workflow-validation`, HEAD au moment du rattrapage (`c491bb6` et suivants non commités)
- **Environnement** : sans objet (voir section 1)

## 1. Périmètre

Sans objet. L'incrément 3 (« API REST », `docs/specs/increment3.md`) livre l'API REST (CRUD courses/coureurs, actions admin, sécurité) mais aucune interface : la PWA est explicitement réservée à l'incrément 4 (`CLAUDE.md`, section « Incréments »). Sans interface navigateur, il n'existe aucun parcours utilisateur E2E à tester.

Preuve constatée :
- Contenu de `frontend/` : un unique fichier `.gitkeep`, aucun code applicatif (commande utilisée : `ls -la frontend`, résultat : `total 4` / `.gitkeep` seul).
- Absence de tout outil E2E dans le dépôt (commande utilisée : `find . -iname "package.json" -not -path "*/node_modules/*"`, `find . -iname "playwright.config.*"`, `find . -iname "cypress.config.*"` : aucun résultat). Les seules mentions de « playwright »/« cypress » dans le dépôt sont textuelles, dans `CLAUDE.md` et `.claude/agents/test-e2e-frontend.md` (définitions d'agent/process), pas une installation.

Conformément à la définition de l'agent `test-e2e-frontend`, aucun outil E2E n'a été installé et aucun test n'a été écrit.

## 2. Couverture exigences ↔ tests

| Exigence | Critère d'acceptation | Tests | Résultat | Écart ? |
|---|---|---|---|---|
| — | — | — | — | Non applicable : les CA de l'incrément 3 portent sur des contrats HTTP (statuts, payloads, sécurité), vérifiables sans navigateur, et sont déjà couverts en `UNIT`/`INT` |

Exigences sans test : aucune — les CA de l'incrément 3 sont couverts en `UNIT` (slices `@WebMvcTest`) et en `INT` (contexte Spring complet, H2 réel via Flyway, ex. `it/EndToEndRaceLifecycleIT.java`, `it/QrTokenUniquenessIT.java`) par `test-integration-backend`, cf. `docs/tests/PATRIMOINE.md`.

## 3. Résultats d'exécution (réels)

Aucune exécution n'a eu lieu : il n'y a ni application front à démarrer ni suite E2E à lancer.

| Suite | Total | Passés | Échoués | Ignorés | Durée |
|---|---|---|---|---|---|
| Tests de l'incrément | — | — | — | — | — |
| Non-régression (INC précédents) | — | — | — | — | — |

Commande(s) exécutée(s) : aucune commande de test E2E. Commandes de constat uniquement :
- `ls -la frontend`
- `find . -iname "package.json" -not -path "*/node_modules/*"`
- `find . -iname "playwright.config.*"`
- `find . -iname "cypress.config.*"`

## 4. Échecs et bugs détectés

Aucun (aucune exécution).

## 5. Modifications du patrimoine existant

Aucun test ajouté, modifié, désactivé ou supprimé. Une note de synthèse a été ajoutée à `docs/tests/PATRIMOINE.md` indiquant l'absence de test E2E pour INC-1 à INC-3 faute d'interface, sans toucher aux lignes existantes de la matrice.

## 6. Tests instables ou en quarantaine

Aucun.

## 7. Risques et limites

- Aucun parcours utilisateur (inscription, scan, dashboard, admin) n'est encore vérifié de bout en bout dans le navigateur : ce sera à couvrir en E2E dès l'incrément 4, quand la PWA sera livrée dans `frontend/`.
- La compatibilité des contrats API de l'incrément 3 (formats de réponse JSON, codes d'erreur `ProblemDetail`, sécurité Basic Auth par rôle SCANNER/ADMIN, absence du `qr_token` dans les réponses publiques) avec les besoins réels du frontend n'a été vérifiée qu'à travers des tests HTTP directs (slices et `*IT`), jamais depuis un navigateur ; cette compatibilité devra être explicitement revérifiée par les premiers parcours E2E de l'incrément 4 (scan, dashboard en polling, inscription, actions admin).

## 8. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : **GO sous réserves** (verdict unique de l'incrément, détaillé dans `INC-3-integration.md`, section 8)
- **Réserves ou motifs** :
  - E2E « sans objet » **accepté** pour INC-3. Les CA portent sur des contrats HTTP, vérifiés sans
    navigateur (slices + `*IT` en contexte complet), et `frontend/` est vide (constat vérifiable).
  - **RT2** (transverse, condition de l'INC-4) : voir `INC-1-e2e.md`. Dès l'INC-4, les premiers parcours E2E
    devront consommer réellement ces contrats depuis le navigateur : HTTP Basic SCANNER/ADMIN, ProblemDetail
    401/403/404/409, absence de `qrToken` hors inscription et admin, CORS (PO23), ouverture des ressources de
    la PWA face au `denyAll()` (PO27). Un E2E « sans objet » à l'INC-4 entraînera un NO-GO.
  - Réserves propres à l'incrément : voir `INC-3-integration.md` (R3-1, RT1, RT3, RT4).
- **Actions correctives exigées** : aucune avant GO.
- **Date** : 2026-09-26 (agent fonctionnel)
