---
description: Exécute le workflow de validation d'un incrément (tests intégration + E2E, patrimoine, rapport, verdict de l'agent fonctionnel).
argument-hint: <numéro d'incrément, ex. 4>
---

Valide l'incrément **INC-$ARGUMENTS** en suivant strictement ce workflow. Ne saute aucune étape.

## 1. Préparation
- Récupère les exigences et critères d'acceptation de INC-$ARGUMENTS : spec `docs/specs/increment$ARGUMENTS.md`.
- Vérifie que le testeur a rendu `VERDICT: OK` (définition de « fini » de `CLAUDE.md`). Sinon, arrête-toi : l'incrément n'est pas prêt pour la validation.
- Lis `docs/tests/PATRIMOINE.md`.
- Vérifie que le développement de l'incrément est terminé et compile.

## 2. Tests d'intégration backend
- Lance l'agent `test-integration-backend` sur INC-$ARGUMENTS.
- Attendu : `docs/tests/rapports/INC-$ARGUMENTS-integration.md` complété, patrimoine mis à jour.

## 3. Tests E2E frontend
- Lance l'agent `test-e2e-frontend` sur INC-$ARGUMENTS (uniquement si l'étape 2 n'a aucun échec bloquant non expliqué).
- Attendu : `docs/tests/rapports/INC-$ARGUMENTS-e2e.md` complété, patrimoine mis à jour.

## 4. Non-régression globale
- Exécute la suite complète (intégration + E2E) et note les résultats réels :
  - backend (unitaires + intégration + couverture) : `mvn -B -f backend/pom.xml clean verify`, avec un timeout ;
  - E2E : commande indiquée dans `.claude/agents/test-e2e-frontend.md` (sans objet tant que `frontend/` ne contient pas d'application).

## 5. Revue de cohérence du patrimoine
- Lance l'agent `revue-coherence-patrimoine` sur INC-$ARGUMENTS.
- Attendu : `docs/tests/rapports/INC-$ARGUMENTS-coherence.md`.
- S'il y a des constats **bloquants** : fais corriger par les agents de test concernés, puis relance cette étape. Maximum 2 boucles, ensuite arrête-toi et remonte-moi la situation.

## 6. Rapport de synthèse
- Consolide dans `docs/tests/rapports/INC-$ARGUMENTS-synthese.md` (gabarit : `TEMPLATE-rapport-increment.md`) : matrice exigence ↔ tests, résultats, écarts, bugs, tests modifiés/désactivés avec motif, et référence au rapport de cohérence.

## 7. Validation fonctionnelle
- Soumets la synthèse **et** le rapport de cohérence à l'**agent fonctionnel** : agent `fonctionnel`.
- Il rend un verdict écrit : **GO**, **GO sous réserves** (liste des réserves) ou **NO-GO** (motifs).
- Consigne le verdict dans le rapport.

## 8. Conclusion
- **GO** : l'incrément est validé, le patrimoine est à jour. Le `git-publisher` (mode publish) peut ouvrir la MR. Résume-moi le résultat en 5 lignes.
- **GO sous réserves / NO-GO** : liste les actions correctives et arrête-toi. N'enchaîne pas sur l'incrément suivant.

## Règles
- Ne déclare jamais un test « passé » sans l'avoir exécuté.
- Seul l'agent fonctionnel peut prononcer le GO.
- Ne modifie pas le code de production pour faire passer un test : remonte le bug.
