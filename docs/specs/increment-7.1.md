# Increment 7.1 : Scan tardif et fin de course (MET-02)

**Dépendance : 4.8 à 4.12 (abandon automatique, fin de course, pilotage, réintégration), 5.1a/5.1b (scan avec âge, file hors ligne), 6.1 à 6.3 (suivi public) livrées.** Branche `increment/7.1-scan-tardif`. Source : `docs/audits/audit-2026-10-21-pre-release.md` (MET-02, bloquant) et la décision de la roadmap : délai de grâce dans le domaine.

Numérotation : RG et CA repartent à 1.

## État actuel constaté

- `AbandonnerCoureursHorsDelai` tranche la Boucle N (abandon automatique, fin de Course) dès `Course.bouclesTerminees(maintenant) >= N`, soit au premier tick du planificateur après le départ de la Boucle N+1.
- Un scan fait pendant N mais reçu après ce tick est refusé pour toujours (`INSCRIPTION_NON_EN_COURSE`, `COURSE_TERMINEE`). La réintégration ne le rattrape pas si la Course est `TERMINEE` ou si le temps de la dernière Boucle est écoulé (`Course.autoriserReintegration`).
- L'écran « scans à vérifier » affiche pourtant l'aide « demandez à un administrateur de réintégrer le coureur » pour `COURSE_TERMINEE` et `TEMPS_DE_COURSE_ECOULE` (`frontend/src/app/scan/refus-scan.ts`).
- Le scan est déjà attribué à la Boucle de son instant réel (`reçu - ageDuScanMillisecondes`, `Course.boucleDePassage`) : cela ne change pas.

## 1. Périmètre

**Inclus**
- Délai de grâce dans le domaine : la Boucle N n'est tranchée qu'à `départ(N+1) + délai` (RG1 à RG6).
- Dernière Boucle autorisée (RG5), impact sur le suivi public et le pilotage (RG7, RG8).
- Retrait de l'aide de réintégration pour `COURSE_TERMINEE` et `TEMPS_DE_COURSE_ECOULE` (RG9).
- Procédure terrain dans `docs/deploiement.md` (RG10).

**Exclu**
- Correction admin d'une Course `TERMINEE` (rouvrir, corriger la décision) : orientation (b) de l'audit, non retenue. Une Course `TERMINEE` n'est jamais rouverte (4.12 inchangé).
- Délai configurable par Course ou par variable d'environnement (point ouvert 1 : constante).
- Priorité de pool pour les scans, cache du suivi (PER-04) : 7.2.
- Toute migration, variable d'environnement, modification de `docker-compose*.yml`, `.env.example`. Aucun nouveau code d'erreur.

**Taille estimée (hors tests) : ~120 lignes** (domaine ~35, application ~10, DTO ~10, front ~40, doc ~25). Pas de découpage.

## 2. Règles de gestion

- **RG1 : délai de grâce.** `Course.DELAI_DE_GRACE` = **3 minutes** ; délai effectif d'une Course `G = min(3 min, durée de Boucle / 4)` (`Course.delaiDeGrace()`). Seule définition, dans le domaine ; constante, **non configurable** (aucune variable d'environnement). Borne : `G < durée de Boucle` toujours (durée min. 1 min, donc G >= 15 s), donc au plus une Boucle est en attente de décision à un instant donné. Valeur choisie pour couvrir un redémarrage de `api` suivi d'une reconnexion des bénévoles, sans retarder notablement l'annonce du vainqueur.
- **RG2 : Boucles tranchées.** Pour une Course `EN_COURS` avec heure de départ, à l'instant `t` (à la milliseconde, ramené au départ s'il le précède) :
  `bouclesTranchees(t) = max(0, min(nombreMaxBoucles, floor((t − demarreeLe − G) / durée de Boucle)))`. Zéro pour toute autre Course. À l'instant exact `demarreeLe + N × durée + G`, la Boucle N est tranchée (borne incluse). Méthode `Course.bouclesTranchees(Instant)`, seule définition.
- **RG3 : décision.** Abandon automatique et fin de Course (CLAUDE.md, 4.8 à 4.10) s'appliquent sur les Boucles `1..bouclesTranchees(t)` et non plus `1..bouclesTerminees(t)`. Les règles de `AbandonAutomatique` sont inchangées (vainqueur unique, vainqueurs partagés, dernière Boucle). `Course.bouclesTerminees` reste le calcul d'affichage et n'est plus utilisé pour décider.
- **RG4 : scan pendant la grâce.** Entre `départ(N+1)` et `départ(N+1) + G` (exclu), un scan dont l'instant réel (`reçu − âge`) est dans la Boucle N est enregistré sur la Boucle N (inchangé : `Course.boucleDePassage`), tant que l'Inscription est `EN_COURSE` et la Course `EN_COURS` (inchangé, rendu vrai plus longtemps par RG3). Un scan dont l'instant réel est dans la Boucle N+1 est attribué à N+1 et refusé `PASSAGE_PRECEDENT_MANQUANT` si le coureur n'a pas de Passage sur N (inchangé). À partir de `départ(N+1) + G`, la Boucle N est tranchée : les refus habituels s'appliquent.
- **RG5 : dernière Boucle autorisée.** Pour N = nombreMaxBoucles, la Course reste `EN_COURS` jusqu'à `demarreeLe + N × durée + G` ; la décision (vainqueurs partagés sur les Inscriptions ayant un Passage sur N, les autres en `ABANDON`) est prise à cet instant. Un scan dont l'instant réel est avant `demarreeLe + N × durée` est accepté pendant la grâce ; un scan dont l'instant réel est à cet instant ou après reste refusé `TEMPS_DE_COURSE_ECOULE` (inchangé). `Course.autoriserReintegration` est inchangé : la réintégration est refusée dès `boucleMaxTerminee`, y compris pendant la grâce (le recours est le scan).
- **RG6 : réintégration, abandon manuel.** Inchangés (4.11, 4.12) : aucune règle supplémentaire.
- **RG7 : indicateur d'attente de décision.** `Course.boucleEnAttenteDeDecision(t)` = `bouclesTerminees(t)` si `bouclesTerminees(t) > bouclesTranchees(t)`, sinon vide (Course non `EN_COURS` : vide). Exposé par le suivi public et le pilotage (champ `boucleEnAttenteDeDecision`, entier ou `null`). Boucle courante, compte à rebours, durée écoulée, Boucles terminées, distance, dénivelé et allure sont inchangés : une Boucle terminée compte dès le départ de N+1, indépendamment de la décision. Les coureurs encore `EN_COURSE` pendant la grâce restent listés « en course ».
- **RG8 : affichage.** Suivi public et pilotage affichent, quand `boucleEnAttenteDeDecision` n'est pas `null` **et** que `boucleMaxTerminee` est faux, un message d'information (`.message--info`) : « Contrôle de la boucle N en cours : les scans de cette boucle sont encore acceptés. » (`suivi-message-decision-en-attente`, `pilotage-message-decision-en-attente`). Si `boucleMaxTerminee` est vrai, seul le message existant « Le temps de la dernière boucle est écoulé. La fin de course sera constatée automatiquement. » reste affiché.
- **RG9 : aide de scan refusé.** L'aide « Si ce scan a été fait dans les temps, demandez à un administrateur de réintégrer le coureur. » n'est affichée que pour `INSCRIPTION_NON_EN_COURSE`. Elle n'est plus affichée pour `COURSE_TERMINEE` ni `TEMPS_DE_COURSE_ECOULE` (aucun recours dans l'application). Motif et `detail` de ces refus sont inchangés. Choix par `code`, comme le motif (4.6 RG8).
- **RG10 : procédure terrain.** `docs/deploiement.md` gagne une section « Fin de course et scans tardifs » : délai de grâce (3 min, 15 s pour une Boucle de 1 min) et son effet (scan accepté jusqu'à `départ(N+1) + G` s'il a été fait pendant N) ; consignes (poste de scan en ligne près de la ligne ; compteur « en attente » à zéro avant chaque départ en fin de course ; ne pas mettre à jour ni redémarrer `api` pendant une course, sinon reconnecter les bénévoles dans la grâce) ; limite (au-delà, aucune correction dans l'application sur une Course `TERMINEE` : noter le résultat à la main) ; le vainqueur est annoncé jusqu'à G après la fin de la Boucle.
- **RG11 : API inchangée hors RG7.** Aucun code, statut HTTP ni `ProblemDetail` ne change. Planificateur, verrou de Course et idempotence du scan sont inchangés.

## 3. Cas limites

- **Instants de bascule** : `t = départ(N+1)` : Boucle N+1 courante, N en attente. `t = départ(N+1) + G − 1 ms` : N non tranchée, scan de N accepté. `t = départ(N+1) + G` : N tranchée.
- **Durées de Boucle** : 1 min, G = 15 s ; 12 min ou plus, G = 3 min.
- **Vainqueur unique retardé** : A et B en lice, A scanne N à temps, le scan de B (fait pendant N) est reçu 2 min après départ(N+1) : B est enregistré, la décision voit deux Passages sur N, la Course continue (avant 7.1 : A vainqueur à tort).
- **Aucun passage sur N** : vainqueurs partagés à `départ(N+1) + G`.
- **Dernière Boucle** : RG5 ; deux coureurs, l'un reçu en retard dans la grâce : deux vainqueurs partagés.
- **Scan après la grâce** : refusé comme avant (`INSCRIPTION_NON_EN_COURSE` si la Course continue, `COURSE_TERMINEE` si terminée). Réintégration possible seulement si la Course est `EN_COURS` et avant `boucleMaxTerminee`.
- **Scan réellement fait en N+1 reçu pendant la grâce de N** : attribué à N+1 (âge), refusé sans Passage sur N.
- **Rejeu d'un scan** (même identifiant) : même résultat (inchangé), même après la décision.
- **Passage `CORRECTION` vs `SCAN`** : un Passage `CORRECTION` compte comme valide pour la décision, comme avant ; l'allure ne retient que les `SCAN`.
- **Coureur réintégré** : inchangé. **Plusieurs Courses en parallèle** : grâce calculée par Course avec sa durée. **Course non démarrée, sans heure de départ, `TERMINEE`** : `bouclesTranchees` = 0, pas d'attente. **Inscription sans aucun passage** : abandon en Boucle 1 à `départ(2) + G`. **Bénévole non affecté, rôle insuffisant** : inchangés.
- **Redémarrage de `api`** : une grâce écoulée pendant l'arrêt est tranchée au premier tick suivant.

## 4. Contrat d'API

Aucun nouvel endpoint, aucun nouveau code d'erreur. Deux réponses existantes gagnent un champ en lecture seule.

### `GET /api/courses/{id}/suivi` (public) : 200 `SuiviCourseReponse`

Champ ajouté, juste après `boucleMaxTerminee` : `boucleEnAttenteDeDecision` (entier >= 1 ou `null`) : numéro de la Boucle terminée dont la décision est en attente (RG7) ; `null` sinon (Course non démarrée, `TERMINEE`, hors grâce). Autres champs, codes (200/404) et `ProblemDetail` inchangés.

### `GET /api/administration/courses/{id}/pilotage` (`ADMIN_MASTER`, `ADMIN`) : 200 `PilotageCourseReponse`

Même champ, même position. Autres champs et codes (200/401/403/404) inchangés.

### `POST /api/benevole/courses/{courseId}/passages` (`BENEVOLE`)

Requête (`jetonQr`, `identifiantScan`, `ageDuScanMillisecondes`), réponses et refus : **inchangés**. Seul le moment où `INSCRIPTION_NON_EN_COURSE` / `COURSE_TERMINEE` apparaissent change (RG3, RG4).

## 5. Écrans

- **Suivi public** (`/courses/:id/suivi`) : message `suivi-message-decision-en-attente` (RG8) sous le chronomètre. Rien d'autre.
- **Pilotage** (`/administration/courses/:id/pilotage`) : message `pilotage-message-decision-en-attente` (RG8). Aucune action nouvelle.
- **Scan** et **scans à vérifier** (bénévole) : `scan-a-verifier-aide` absent pour `COURSE_TERMINEE` et `TEMPS_DE_COURSE_ECOULE`, présent pour `INSCRIPTION_NON_EN_COURSE` (RG9). Le `detail` du refus reste affiché tel quel.

## 6. Critères d'acceptation

Sauf mention, Boucle de 60 min (G = 3 min), départ à 10:00:00, 5 Boucles max.

- **CA1** (RG1, unitaire) : étant donné des Boucles de 60, 12, 8 et 1 min, quand on calcule le délai de grâce, alors 3 min, 3 min, 2 min, 15 s.
- **CA2** (RG2, unitaire) : quand `bouclesTranchees` à 10:59:59.999, 11:00:00, 11:02:59.999, 11:03:00, alors 0, 0, 0, 1.
- **CA3** (RG2, unitaire) : avec 3 Boucles max, à 13:03:00 et 14:00:00, alors 3 et 3 (plafonné) ; Course `EN_PREPARATION`, `TERMINEE` ou sans heure de départ : 0 ; `t` avant le départ : 0.
- **CA4** (RG3, unitaire) : étant donné A (Passage sur 1) et B (aucun) `EN_COURSE`, quand le contrôle est exécuté à 11:02:59.999, alors aucune décision ; à 11:03:00, B `ABANDON` (`HORS_DELAI`, boucle 1), A `VAINQUEUR`, Course `TERMINEE`.
- **CA5** (RG3, RG4, unitaire) : étant donné A (Passage sur 1) et B sans ; quand B reçoit un Passage sur 1 (instant réel 10:59:00) à 11:01:00 puis le contrôle est exécuté à 11:03:00, alors A et B restent `EN_COURSE`, la Course reste `EN_COURS`.
- **CA6** (RG3, unitaire) : étant donné A et B sans Passage sur 1, quand le contrôle est exécuté à 11:02:59.999 puis 11:03:00, alors aucune décision, puis A et B `VAINQUEUR` (partagés), Course `TERMINEE`.
- **CA7** (RG5, unitaire) : étant donné 2 Boucles max, A et B avec Passage sur 1, A seul avec Passage sur 2 ; quand le contrôle est exécuté à 12:02:59.999 puis 12:03:00, alors aucune décision, puis A `VAINQUEUR`, B `ABANDON` (boucle 2), Course `TERMINEE`. Si B reçoit un Passage sur 2 (instant réel 11:59:00) à 12:01:00, alors A et B `VAINQUEUR` à 12:03:00.
- **CA8** (RG5, unitaire) : étant donné 2 Boucles max, un scan d'instant réel 11:59:59.999 reçu à 12:01:00 est attribué à la Boucle 2 ; un scan d'instant réel 12:00:00 lève `TempsDeCourseEcouleException` ; une réintégration à 12:01:00 lève `TempsDeCourseEcouleException`.
- **CA9** (RG7, unitaire) : étant donné 5 Boucles max, `boucleEnAttenteDeDecision` à 10:59:59.999, 11:00:00, 11:02:59.999, 11:03:00 donne vide, 1, 1, vide ; à 15:00:00 donne 5 et à 15:03:00 vide ; Course `TERMINEE` ou non démarrée : vide.
- **CA10** (RG4, unitaire) : étant donné une Inscription sans Passage sur 1, quand un scan d'instant réel 11:00:30 (Boucle 2) est traité à 11:01:00, alors refus `PASSAGE_PRECEDENT_MANQUANT`.
- **CA11** (RG3, RG4, intégration) : étant donné une Course démarrée à 10:00:00 (horloge pilotable) avec A scanné sur 1 et B non, quand l'horloge est à 11:01:00 et que B est scanné avec `ageDuScanMillisecondes` = 120000, alors 201 (Boucle 1) ; à 11:03:01 après le planificateur, A et B sont `EN_COURSE` et la Course `EN_COURS`.
- **CA12** (RG3, intégration) : étant donné A scanné sur 1 et B non, quand l'horloge est à 11:02:59 puis 11:03:01 et que le planificateur a tourné, alors à 11:02:59 B `EN_COURSE` ; à 11:03:01 B `ABANDON` (`HORS_DELAI`, boucle 1), A `VAINQUEUR`, Course `TERMINEE`.
- **CA13** (RG4, intégration) : étant donné la décision de CA12 prise, quand B est scanné (âge 120000) à 11:03:30, alors 409 avec le `code` `COURSE_TERMINEE` (inchangé).
- **CA14** (RG5, intégration) : étant donné 2 Boucles max, A et B avec Passage sur 1, A sur 2, quand B est scanné sur 2 (âge 120000) à 12:01:00, alors 201 ; à 12:03:01 A et B sont `VAINQUEUR`, Course `TERMINEE`.
- **CA15** (RG7, intégration) : étant donné une Course démarrée, quand `GET /suivi` (anonyme) et `GET /pilotage` (ADMIN) sont appelés à 11:00:30, alors 200 avec `boucleEnAttenteDeDecision` = 1 ; à 11:04:00, `null` ; Course non démarrée : `null`. Aucun champ nominatif ajouté au suivi public.
- **CA16** (RG11, intégration) : `GET /suivi` d'une Course inexistante, `GET /pilotage` sans session puis en `COUREUR` : 404, 401, 403 comme avant.
- **CA17** (RG8, E2E) : étant donné la Course de démo « Backyard du jour » (Boucle 1 min, G = 15 s) démarrée, quand l'horloge est avancée de 65 s, alors le suivi affiche `suivi-message-decision-en-attente` contenant « boucle 1 » et le pilotage `pilotage-message-decision-en-attente` ; après 20 s de plus, les deux ont disparu.
- **CA18** (RG3, RG4, E2E) : étant donné Karim et Léa en course, Karim scanné sur 1 par un bénévole affecté, quand l'horloge est avancée de 65 s puis Léa est scannée à l'écran de scan avec un âge de scan simulé de 15 s (requête de scan avec `ageDuScanMillisecondes`), alors le retour de scan est un succès « Boucle 1 » ; 20 s plus tard, le suivi montre les deux coureurs en course et aucun vainqueur.
- **CA19** (RG9, E2E) : étant donné un scan refusé `COURSE_TERMINEE`, un refusé `TEMPS_DE_COURSE_ECOULE` et un refusé `INSCRIPTION_NON_EN_COURSE` (coureur en abandon), quand on ouvre « scans à vérifier », alors `scan-a-verifier-aide` est absent pour les deux premiers, présent pour le troisième, et le `detail` reste affiché.
- **CA20** (RG10, intégration) : relecture de revue : `docs/deploiement.md` contient la section « Fin de course et scans tardifs » avec la valeur 3 minutes (vérification manuelle, étape 8 de « Tester à la main »).

Couverture des RG : RG1 CA1 ; RG2 CA2-3 ; RG3 CA4-6, 11-12 ; RG4 CA5, 10, 11, 13, 18 ; RG5 CA7, 8, 14 ; RG6 CA8 ; RG7 CA9, 15 ; RG8 CA17 ; RG9 CA19 ; RG10 CA20 ; RG11 CA16. Écrans : suivi CA17, pilotage CA17, scan CA18, scans à vérifier CA19.

## 7. Tester à la main

Prérequis : `docker compose up -d --build` avec le profil de démo à horloge pilotable (comme 4.x), http://localhost, puis `scripts/donnees-demo.sh`. Comptes : l'admin (`ADMIN_MASTER_PSEUDO`), le bénévole Léo (affecté à « Backyard du jour », Boucle 1 min), les coureurs Karim et Léa inscrits.

1. Connecté en admin, démarrer « Backyard du jour » (G = 15 s). Ouvrir le pilotage et, dans un autre onglet, le suivi public.
2. En bénévole, scanner le QR de Karim pendant la Boucle 1. Attendu : succès « Boucle 1 ».
3. Avancer l'horloge de 65 s. Attendu : suivi et pilotage affichent « Contrôle de la boucle 1 en cours : les scans de cette boucle sont encore acceptés. » ; Karim et Léa sont toujours « en course ».
4. Scanner Léa avec un scan fait avant 60 s (mode hors ligne, ou âge de scan de la démo). Attendu : succès, Boucle 1.
5. Avancer l'horloge de 20 s. Attendu : le message disparaît ; Karim et Léa toujours en course, aucune fin de course (avant 7.1, Karim était déclaré vainqueur à tort).
6. Recommencer avec une nouvelle Course sans scanner Léa à l'étape 4. Attendu : après le délai, Léa en abandon (boucle 1, hors délai), Karim vainqueur, Course terminée.
7. Après la fin, scanner Léa. Attendu : refus « Course terminée » dans « scans à vérifier », sans phrase sur la réintégration. Sur une Course encore en cours, scanner un coureur en abandon : la phrase de réintégration est présente.
8. Lire `docs/deploiement.md`, section « Fin de course et scans tardifs ».

## 8. Points ouverts (tranchés par l'analyste, utilisateur indisponible)

1. **Durée du délai** : 3 min plafonné au quart de la durée de Boucle, constante du domaine, non configurable. À revoir en recette ; changement d'une seule constante.
2. **Correction admin d'une Course `TERMINEE`** (orientation (b)) : non retenue (position la plus simple) ; au-delà de la grâce, résultat corrigé à la main (RG10).
3. **Annonce du vainqueur retardée de G** : acceptée ; le suivi indique l'attente (RG8), sans compte à rebours dédié.
4. **Dernière Boucle et réintégration** : la réintégration reste refusée dès `boucleMaxTerminee`, même pendant la grâce (le scan est le recours).
5. **Panne plus longue que la grâce** : non couverte, procédure terrain seulement (RG10).
