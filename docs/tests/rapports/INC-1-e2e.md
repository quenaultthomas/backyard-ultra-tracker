# Rapport de test : INC-1 (e2e)

- **Date** : 2026-09-26
- **Agent auteur** : test-e2e-frontend
- **Version / commit testé** : branche `chore/workflow-validation`, HEAD au moment du rattrapage (`c491bb6` et suivants non commités)
- **Environnement** : sans objet (voir section 1)

## 1. Périmètre

Sans objet. L'incrément 1 (« Domaine + Persistance », `docs/specs/increment1.md`) est explicitement circonscrit aux entités JPA, repositories et à la migration Flyway ; la spec exclut elle-même « tout frontend ou PWA — couvert par l'incrément 4 » (section 1, « Explicitement exclu »). Il n'existe donc, à ce stade, aucun parcours utilisateur navigateur à vérifier.

Preuve constatée :
- Contenu de `frontend/` : un unique fichier `.gitkeep`, aucun code applicatif (commande utilisée : `ls -la frontend`, résultat : `total 4` / `.gitkeep` seul).
- Absence de tout outil E2E dans le dépôt (commande utilisée : `find . -iname "package.json" -not -path "*/node_modules/*"`, `find . -iname "playwright.config.*"`, `find . -iname "cypress.config.*"` : aucun résultat).

Conformément à la définition de l'agent `test-e2e-frontend` (« Tant que `frontend/` ne contient pas d'application ... il n'y a aucun parcours navigateur à tester. Le rapport E2E constate alors « sans objet » ... et tu n'installes aucun outil. »), aucun outil E2E n'a été installé et aucun test n'a été écrit.

## 2. Couverture exigences ↔ tests

| Exigence | Critère d'acceptation | Tests | Résultat | Écart ? |
|---|---|---|---|---|
| — | — | — | — | Non applicable : aucun CA de l'incrément 1 ne s'exprime en parcours navigateur (spec 100 % backend) |

Exigences sans test : aucune — les CA de l'incrément 1 sont des critères de persistance (couverts en `INT` par `test-integration-backend`, cf. `docs/tests/PATRIMOINE.md`), pas des parcours utilisateur relevant du présent agent.

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
- La compatibilité des contrats API exposés à l'incrément 3 (formats de réponse, codes d'erreur, sécurité Basic Auth par rôle) avec les besoins réels du frontend n'a pas été vérifiée par des appels navigateur réels ; elle devra l'être à l'incrément 4 lors de l'écriture des premiers parcours E2E.

## 8. Verdict de l'agent fonctionnel
*(rempli uniquement par l'agent fonctionnel)*

- **Verdict** : **GO sous réserves** (verdict unique de l'incrément, détaillé dans `INC-1-integration.md`, section 8)
- **Réserves ou motifs** :
  - E2E « sans objet » **accepté** pour INC-1. La spec exclut tout frontend (section 1), aucun CA ne décrit un
    parcours navigateur et `frontend/` ne contient que `.gitkeep` (constat vérifiable, commandes citées).
    Aucun test n'a été supprimé ni assoupli.
  - **RT2** (transverse, condition de l'INC-4) : dès l'INC-4, le statut « sans objet » ne sera plus accepté.
    Le rapport E2E de l'INC-4 devra couvrir au minimum : inscription publique par course (lien d'inscription,
    affichage du QR) ; scan caméra avec filet réseau (enregistrement local, file d'attente, retry, rejeu dans
    l'ordre FIFO après coupure réseau, PO8 inc. 2) ; tableau de bord en polling 2-3 s, avec bascule de yard et
    badge « corrigé » ; actions admin (DNF manuel **avec confirmation**, réintégration) ; refus d'accès selon
    le rôle (401/403) ; deux courses en parallèle. Il devra aussi vérifier la compatibilité navigateur des
    contrats de l'INC-3 (ProblemDetail, HTTP Basic, absence de `qrToken` hors inscription et admin), l'ouverture
    des ressources de la PWA (PO27 inc. 3) et la question CORS (PO23 inc. 3). Échéance : validation de l'INC-4.
  - Réserves propres à l'incrément : voir `INC-1-integration.md` (R1-1, R1-2, R1-3, RT1, RT3, RT4).
- **Actions correctives exigées** : aucune avant GO.
- **Date** : 2026-09-26 (agent fonctionnel)
