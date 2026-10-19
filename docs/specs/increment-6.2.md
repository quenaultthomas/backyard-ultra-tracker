# Increment 6.2 : Coureurs en course, abandons et vainqueurs

**Dépendance : 6.1 (endpoint public `GET /api/courses/{id}/suivi`, écran `/courses/:id/suivi`), 4.8 à 4.10 (abandon automatique, fin de course), 4.11 (abandon manuel), 4.12 (réintégration) et 3.6 (anonymisation d'un Compte) livrées et validées.** 6.2 **prolonge** la réponse de 6.1 avec trois listes de coureurs et l'écran avec trois sections. Aucun nouvel endpoint, aucune nouvelle route.

Numérotation : RG et CA repartent à 1. « 6.1 RGn » et « 4.2 RGn » désignent les specs correspondantes.

## État actuel constaté dans le dépôt

- `ConsulterSuivi` (6.1) ne lit qu'une Course (`DepotCourses`) et l'horloge ; `SuiviCourseReponse` porte 11 champs (6.1 RG2, liste fermée).
- `ConsulterPilotage` (4.2) produit déjà, pour l'administration, les inscrits `EN_COURSE`, `ABANDON` (avec `boucleAbandon` et `motifAbandon`) et `VAINQUEUR`, triés par dossard, avec pseudo ; mais il lit aussi tous les Passages et expose `inscriptionId` : **il n'est pas réutilisable tel quel en public** (les Passages ne servent pas en 6.2).
- `ListerInscritsCourse.inscritsParDossard` : tri par dossard, **une seule** recherche de pseudos pour tous les Comptes (`AnnuairePseudos.pseudosDe`), « Compte inconnu » (et un avertissement dans le journal) si un pseudo n'est pas résolu. `DepotInscriptions.parCourse(courseId)` : une requête pour toutes les Inscriptions de la Course.
- `Inscription` : `statut` (`EN_COURSE`, `ABANDON`, `VAINQUEUR`), `dossard` (unique par Course), `boucleAbandon` et `motifAbandon` (vides hors abandon, ou pour un abandon antérieur enregistré sans eux), `jetonQr`, `compteId`. `MotifAbandon` : `VOLONTAIRE`, `HORS_DELAI`, `MANUEL`, `AUTRE`.
- Compte anonymisé (3.6 RG6) : son pseudo devient « Coureur anonyme » dans l'annuaire ; ses Inscriptions aux Courses `EN_COURS` et `TERMINEE` sont conservées.
- Une Course `TERMINEE` est définitive (4.10, 4.12 RG1 : aucune réintégration). Le planificateur (`PlanificateurAbandonAutomatique`) s'exécute toutes les secondes.
- Coût de 6.1 mesuré : l'appel de 6.1 exécute déjà plusieurs instructions SQL (chargement de la Course, de ses bénévoles affectés, empreinte du logo) ; 6.2 n'y touche pas.
- Frontend : `frontend/src/app/suivi/` (composant, service, polling) ; libellés de motif dans `LIBELLES_MOTIF_ABANDON` (`administration/courses/pilotage-course.ts`) ; E2E : `e2e/tests/aide-suivi.ts`, `suivi-ecran*.spec.ts`.

## 1. Périmètre

**Inclus**
- Réponse de `GET /api/courses/{id}/suivi` enrichie de trois listes : `coureursEnCourse`, `abandons` (Boucle et motif), `vainqueurs` (RG1 à RG9).
- Écran `/courses/:id/suivi` : sections « Vainqueur(s) » (mise en avant), « Coureurs en course », « Abandons », mises à jour sans rechargement, arrêt du polling sur `TERMINEE` (RG10 à RG17).

**Exclu**
- Statistiques : nombre de Boucles terminées (par coureur ou global), distance, dénivelé, allure, durée finale figée, badge « corrigé », classement : **6.3**. Aucun Passage n'est lu en 6.2.
- Mode plein écran / projection, masquage de l'en-tête de session, jetons `--taille-projection-*` : **6.3**.
- Liste publique des Courses et liens vers leur suivi : **6.4**.
- Pagination, recherche ou filtre dans les listes (position par défaut, point ouvert 6).
- Toute modification du domaine, de la base (aucune migration), des règles d'abandon, de fin de course, de réintégration, de l'authentification. Aucune variable d'environnement, aucun changement de `docker-compose*.yml`, de `.env.example`, de Caddy, du pilotage d'administration.

**Taille estimée (production hors tests) : ~300 lignes** : cas d'usage ~70, DTO + contrôleur ~50, front (types, sections, styles) ~150, charte ~30. Dans la cible, pas de découpage.

**Modification de la spec 6.1** : RG2 (liste fermée de 11 champs) est remplacée par RG1 ci-dessous (14 champs) ; RG6 (« ne charge ni Inscriptions ni comptes ») est remplacée par RG2 et RG7 (Inscriptions et pseudos lus, jamais de Passages, rien lu en `EN_PREPARATION`) ; l'arrêt du polling sur `TERMINEE` (6.1 RG7, point ouvert 5) est confirmé par RG10. Les tests de 6.1 qui fixent le périmètre ancien (CA5 : aucun appel au port des Inscriptions ; CA6 : « seulement 11 champs ») sont **remplacés** par CA4, CA6 et CA8 de cette spec, plus stricts ; les autres CA de 6.1 restent valables et doivent rester verts.

## 2. Règles de gestion

**API** (prolongent 6.1 RG1 à RG6, inchangées sauf mention)
- **RG1** : données exposées, **liste fermée de 14 champs** : les 11 de 6.1 + `coureursEnCourse`, `abandons`, `vainqueurs`. Chaque ligne d'une liste n'a que les champs de RG4. Jamais : jeton QR, identifiant de Compte, identifiant d'Inscription, statut de l'Inscription, détail ou nombre de Passages, nombre de Boucles terminées, origine `CORRECTION`, paramètres de Boucle, nombre d'inscrits, date de naissance ou toute autre donnée du Compte. L'endpoint reste public, sans session ni CSRF (6.1 RG1), `Cache-Control: no-store`.
- **RG2** : visibilité selon le statut de la Course. `EN_PREPARATION` : les trois listes sont **vides** et **aucune Inscription n'est lue** (les inscrits d'une Course non démarrée ne sont pas publics). `EN_COURS` (avec ou sans heure de départ) et `TERMINEE` : listes renseignées selon RG3.
- **RG3** : répartition. Chaque Inscription de la Course apparaît dans **exactement une** liste, selon son `statut` enregistré : `EN_COURSE` dans `coureursEnCourse`, `ABANDON` dans `abandons`, `VAINQUEUR` dans `vainqueurs`. Le statut est **lu**, jamais recalculé (ni à partir des Passages, ni du chronométrage, ni de l'heure) : l'abandon automatique et la fin de course restent à la charge du planificateur (6.1 RG6). Un coureur `EN_COURSE` dont la Boucle est dépassée mais pas encore constatée reste dans `coureursEnCourse` jusqu'à la prochaine exécution du planificateur (au plus ~1 s).
- **RG4** : contenu d'une ligne.
  - `coureursEnCourse[]` et `vainqueurs[]` : `dossard` (entier), `pseudo` (texte).
  - `abandons[]` : `dossard`, `pseudo`, `boucleAbandon` (entier ou `null`), `motifAbandon` (`VOLONTAIRE`, `HORS_DELAI`, `MANUEL`, `AUTRE`, ou `null`). `null` seulement pour un abandon antérieur enregistré sans Boucle ni motif ; la Boucle d'abandon est la Boucle que le coureur n'a pas terminée (4.9, 4.11).
- **RG5** : pseudo. Le pseudo vient de `AnnuairePseudos` (une seule recherche pour tous les Comptes distincts, jamais une par ligne) par la logique **existante** de `ListerInscritsCourse` (pas de seconde implémentation) : un Compte anonymisé s'affiche « Coureur anonyme » (3.6 RG6) ; un pseudo non résolu s'affiche « Compte inconnu » (avertissement de journal existant, sans pseudo). Le dossard distingue deux lignes de même libellé. Un pseudo libéré puis réutilisé par un autre Compte ne rejaillit jamais sur l'ancienne Inscription (le lien est l'identifiant du Compte, interne).
- **RG6** : tri, **côté serveur uniquement** (le front n'en refait aucun) : `coureursEnCourse` et `vainqueurs` par `dossard` croissant ; `abandons` par `boucleAbandon` **décroissante** (les abandons les plus tardifs d'abord, `null` en dernier), puis `dossard` croissant. Ordre déterministe, identique d'un appel à l'autre.
- **RG7** : coût et lecture seule. 6.2 n'ajoute que **deux instructions SQL** à celles de 6.1 (chargement de la Course, de ses bénévoles et de l'empreinte du logo, inchangés) : la lecture des Inscriptions (`DepotInscriptions.parCourse`), **puis** celle des pseudos (omise si la Course n'a aucune Inscription). La Course est lue avant (RG8). Le nombre total d'instructions SQL est **indépendant du nombre d'inscrits** ; les deux instructions ajoutées sont **absentes** en `EN_PREPARATION` (le coût y reste celui de 6.1). Aucune lecture de Passage, aucune requête par ligne (pas de N+1). `@Transactional(readOnly = true)`, instant lu une seule fois sur le `Clock`. Aucune écriture, aucun changement de statut, aucune ligne de journal INFO ou supérieure en fonctionnement nominal. `toString()` d'une ligne ou de la réponse n'affiche ni pseudo ni jeton (identifiant de Course et dossard au plus).
- **RG8** : cohérence. La Course est lue **avant** les Inscriptions : une Course lue `TERMINEE` garantit que ses Inscriptions sont déjà dans leur état final (le passage en `TERMINEE` et les statuts finaux sont validés ensemble par le planificateur ou par l'abandon manuel, 4.9 à 4.11). Une Course lue `EN_COURS` peut voir ses Inscriptions un instant plus avancées : corrigé à l'appel suivant, sans conséquence.
- **RG9** : fin de course dans la réponse. `TERMINEE` : `vainqueurs` contient **un** coureur (vainqueur unique, 4.9), **plusieurs** (vainqueurs partagés, aucun passage sur la Boucle N ou dernière Boucle autorisée, 4.9 et 4.10) ou **aucun** (Course terminée sans vainqueur, quand plus personne n'est en course après un abandon manuel, 4.11). `coureursEnCourse` est alors vide. Sur `EN_COURS`, `vainqueurs` est vide. Les Inscriptions `VAINQUEUR` ne sont jamais dans `abandons`.

**Écran** (prolongent 6.1 RG7 à RG11)
- **RG10** : polling. Le comportement de 6.1 RG7 et RG8 est **inchangé** (3 s, un appel à la fois, compte à rebours local, avertissement d'actualisation). **Arrêt sur `TERMINEE` confirmé** : la réponse qui porte le statut `TERMINEE` contient déjà les listes finales (RG8, Course définitive : aucune réintégration, 4.12 RG1) ; elle est affichée **en entier**, puis le polling s'arrête. Aucun dernier chargement supplémentaire. Conséquence acceptée : un Compte supprimé après la fin n'est « anonymisé » à l'écran qu'au rechargement de la page (point ouvert 3).
- **RG11** : sections (route inchangée, sous l'en-tête de Course de 6.1). Ordre : « Vainqueur » / « Vainqueurs », « Coureurs en course », « Abandons ».
  - `EN_PREPARATION` : aucune des trois sections ; seul `suivi-message-non-demarree` de 6.1 reste.
  - `EN_COURS` : « Coureurs en course » et « Abandons » toujours présentes (même vides) ; pas de section vainqueur.
  - `TERMINEE` : « Vainqueur(s) » si `vainqueurs` non vide, sinon `suivi-message-aucun-vainqueur` « Course terminée sans vainqueur. » ; « Abandons » ; **pas** de section « Coureurs en course ».
  - Chaque section a un `h2.sous-titre` avec son effectif (« Coureurs en course (3) », « Abandons (1) ») dans `suivi-nombre-en-course` et `suivi-nombre-abandons` (effectif = longueur de la liste reçue, aucun autre calcul).
  - Liste vide : `suivi-message-aucun-coureur-en-course` « Aucun coureur en course. », `suivi-message-aucun-abandon` « Aucun abandon. ».
  - Chaque ligne est un `li` de `ul` : `suivi-coureur-<dossard>`, `suivi-abandon-<dossard>`, `suivi-vainqueur-<dossard>`. Clé de suivi des lignes : le dossard (les lignes inchangées ne sont pas recréées : pas de clignotement, de perte de focus ni de saut de mise en page).
- **RG12** : mise en avant du vainqueur (`docs/design.md` §11) : bandeau forêt à texte crème, filet orange en aplat (jamais d'orange en texte), le mot « Vainqueur » **écrit** (« Vainqueur » au singulier, « Vainqueurs » au pluriel pour des vainqueurs partagés). Chaque ligne : « Dossard 12 » et le pseudo. Un bandeau unique `suivi-vainqueurs` contient les lignes, dans l'ordre de la réponse.
- **RG13** : ligne de coureur. Texte « Dossard 12 », puis le pseudo (rendu en texte, jamais en HTML ; long pseudo coupé, 30 caractères au plus sans espace). Ligne d'abandon : « Dossard 12 », pseudo, « Boucle 3 », libellé du motif (réutilise `LIBELLES_MOTIF_ABANDON` : « Hors délai », « Volontaire », « Manuel », « Autre », sans le dupliquer). Abandon sans Boucle ni motif : « Boucle non renseignée » et « Motif non renseigné ». Information jamais portée par la couleur seule.
- **RG14** : mises à jour. Tout changement de statut survenu côté serveur (abandon automatique, abandon manuel, réintégration, fin de course) se reflète au plus tard à la réponse suivante, **sans rechargement** : la ligne passe d'une section à l'autre, les effectifs changent, aucune animation, aucune notification hors RG15. Une réintégration ne laisse **aucune trace publique** de l'abandon (le coureur redevient une ligne de « Coureurs en course »).
- **RG15** : annonce de fin. Région `role="status"` `suivi-annonce-fin`, vide au chargement : quand la réponse affichée passe d'un statut `EN_COURS` à `TERMINEE` pendant que l'écran est ouvert, elle annonce « Course terminée. Vainqueur : <pseudos séparés par une virgule>. » (« Vainqueurs : » au pluriel, « Course terminée sans vainqueur. » sans vainqueur). Rien n'est annoncé à l'ouverture d'une Course déjà `TERMINEE`, ni pour un abandon ou une réintégration. Les listes n'ont aucun `aria-live`.
- **RG16** : charte et accessibilité. Mêmes règles que 6.1 RG11 (jetons uniquement, aucune couleur ni taille en dur, aucun défilement horizontal à 320 px y compris texte à 200 %, `prefers-reduced-motion`) ; sections en `h2`, listes en `ul` ; cartes de page `--carte-largeur` conservée ; les listes **ne sont ni tronquées ni paginées** (défilement vertical de la page seulement) ; contraste AA ; `docs/design-apercu.html` complété (bandeau vainqueur, ligne de coureur, ligne d'abandon).
- **RG17** : confidentialité. Aucun pseudo dans les journaux (back et front), aucun jeton QR ni identifiant de Compte ou d'Inscription dans la réponse, le DOM, les attributs `data-*` ou les URL. `Cache-Control: no-store` conservé. Les pseudos affichés sont ceux que le coureur a choisis à la création de son Compte et sont visibles de **toute personne connaissant l'adresse du suivi** : enjeu à valider (point ouvert 1).

## 3. Cas limites

- **Course `EN_PREPARATION` avec inscrits** : 200, listes vides, aucune Inscription lue ; l'écran n'affiche aucun pseudo. Au démarrage, les coureurs apparaissent seuls (RG2, RG14). Course sans aucune Inscription démarrée : « Aucun coureur en course. », « Aucun abandon. ».
- **Course `EN_COURS` sans heure de départ** (posée par SQL, 4.1 RG13) : chronométrage vide (6.1 RG3), listes renseignées normalement.
- **Vainqueur unique** : un seul `vainqueurs`, bandeau « Vainqueur » ; les autres inscrits sont dans `abandons` (`HORS_DELAI`, Boucle N). **Vainqueurs partagés** : bandeau « Vainqueurs », tous par dossard ; aucun n'est dans `abandons`. **Dernière Boucle autorisée** : vainqueurs = ceux qui ont terminé la Boucle M, les autres en `abandons`. **Terminée sans vainqueur** : message RG11, `abandons` listés.
- **Coureur réintégré** : après réintégration (`EN_COURS` seulement), il quitte `abandons` et réapparaît dans `coureursEnCourse` à sa place de dossard ; son ancien abandon n'est plus visible. Les Passages `CORRECTION` ne sont ni lus ni signalés (badge « corrigé » : 6.3). Réintégration refusée sur Course `TERMINEE` : sans effet sur le suivi.
- **Abandon manuel** (`VOLONTAIRE`, `MANUEL`, `AUTRE`) : la ligne passe dans `abandons` avec la Boucle `nombre de Passages + 1` (4.11) et le motif choisi. L'abandon du dernier coureur en course termine la Course (RG9).
- **Abandon automatique** : `HORS_DELAI`, Boucle N, visible au plus ~4 s après la bascule (planificateur 1 s + polling 3 s).
- **Passage `SCAN` ou `CORRECTION`** : aucune différence en 6.2 (aucun Passage lu). **Inscription sans aucun Passage** : reste `EN_COURSE` tant que la Boucle 1 n'est pas dépassée ; ensuite `ABANDON` Boucle 1 (ou `VAINQUEUR` partagé si personne n'a de Passage).
- **Compte supprimé** (3.6) : Course `EN_COURS` ou `TERMINEE` : la ligne reste, pseudo « Coureur anonyme », même dossard ; son ancien pseudo n'apparaît plus ni à l'API ni au rechargement. Compte supprimé alors que l'écran est ouvert : mis à jour au poll suivant en `EN_COURS` (RG14), au rechargement seulement après `TERMINEE` (RG10). Deux comptes anonymisés dans la même Course : deux lignes « Coureur anonyme » de dossards distincts.
- **Pseudo libéré réutilisé** par un autre Compte inscrit à la même Course : deux dossards distincts, aucune confusion (RG5).
- **Plusieurs Courses en parallèle** : aucune ligne d'une Course n'apparaît dans la réponse d'une autre (lecture par `courseId`).
- **Bénévole non affecté, rôle insuffisant, utilisateur connecté** : aucun contrôle de rôle, même réponse pour tous (6.1 RG1).
- **Grand nombre d'inscrits** : toutes les lignes sont renvoyées et affichées (pas de pagination) ; le nombre d'instructions SQL reste indépendant du nombre d'inscrits (RG7).
- **Boucle changée mais statut inchangé** : la Boucle courante peut avoir avancé (6.1) avant que le planificateur ait constaté les abandons : l'écran peut montrer pendant ~1 à 4 s « Boucle 2 » et un coureur encore « en course » ; c'est attendu (RG3).
- **Abandon enregistré sans Boucle ni motif** (données antérieures) : RG13. **Chemin invalide / Course introuvable** : inchangés (6.1 RG5, RG8) ; aucune liste.

## 4. Contrat d'API

### `GET /api/courses/{id}/suivi` (modifié)

- **Accès, erreurs, en-têtes** : inchangés (6.1) : public, aucun CSRF, `Cache-Control: no-store`, 404 `COURSE_INTROUVABLE` pour identifiant inconnu, non UUID ou Course supprimée ; aucun 400, 401, 403 de rôle, 409.
- **Réponse 200** `SuiviCourseReponse` : les 11 champs de 6.1 **inchangés**, plus :

| Champ | Type | Contenu |
|---|---|---|
| `coureursEnCourse` | tableau de `CoureurSuivi`, jamais `null` | Inscriptions `EN_COURSE`, par dossard croissant ; vide si `EN_PREPARATION` ou `TERMINEE` |
| `abandons` | tableau de `AbandonSuivi`, jamais `null` | Inscriptions `ABANDON`, tri RG6 ; vide si `EN_PREPARATION` |
| `vainqueurs` | tableau de `CoureurSuivi`, jamais `null` | Inscriptions `VAINQUEUR`, par dossard croissant ; vide sauf `TERMINEE` avec vainqueur |

`CoureurSuivi` : `dossard` (entier), `pseudo` (texte, jamais vide).
`AbandonSuivi` : `dossard` (entier), `pseudo` (texte), `boucleAbandon` (entier ou `null`), `motifAbandon` (`VOLONTAIRE` | `HORS_DELAI` | `MANUEL` | `AUTRE` ou `null`).

Exemple (`EN_COURS`, 60 min, M = 5, Boucle 3) :
`{"courseId":"…","nom":"Backyard des Crêtes","logoUrl":null,"statut":"EN_COURS","heureServeur":"2026-10-09T16:28:27.000Z","nombreMaxBoucles":5,"boucleCourante":3,"derniereBoucle":false,"boucleMaxTerminee":false,"tempsAvantFinBoucleMillisecondes":2100000,"dureeEcouleeMillisecondes":8700000,"coureursEnCourse":[{"dossard":1,"pseudo":"Alice"},{"dossard":4,"pseudo":"Coureur anonyme"}],"abandons":[{"dossard":3,"pseudo":"Karim","boucleAbandon":2,"motifAbandon":"HORS_DELAI"},{"dossard":2,"pseudo":"Bruno","boucleAbandon":1,"motifAbandon":"MANUEL"}],"vainqueurs":[]}`.
Exemple (`TERMINEE`, vainqueurs partagés) : `statut` `TERMINEE`, champs de chronométrage `null`, `coureursEnCourse` `[]`, `vainqueurs` `[{"dossard":1,…},{"dossard":2,…}]`.
Exemple (`EN_PREPARATION`) : les trois listes à `[]`.

Aucun nouveau code d'erreur.

### Impacts back
- **Domaine** : aucun changement de règle. Le tri de RG6 et la répartition de RG3 sont **une seule définition**, dans le cas d'usage ou un value object du domaine (au choix du développeur, jamais dans le contrôleur ni le front).
- **Application** : `ConsulterSuivi` reçoit `DepotInscriptions` et `AnnuairePseudos` ; la résolution des pseudos réutilise `ListerInscritsCourse.inscritsParDossard` (ou son extraction), sans dupliquer « Compte inconnu ». Aucune dépendance à `DepotPassages`.
- **Exposition** : `SuiviCourseReponse` étendu (`CoureurSuivi`, `AbandonSuivi`), `toString()` sans pseudo.
- Aucune migration, aucune variable d'environnement, `SecuriteConfiguration` inchangée.

### Front (indicatif)
Types de réponse et sections dans `frontend/src/app/suivi/` ; `LIBELLES_MOTIF_ABANDON` partagé (déplacé vers un emplacement commun si l'import depuis l'administration crée un couplage indésirable). Noms libres, comportement et `data-testid` fixés par RG10 à RG17.

## 5. Écrans

### Suivi d'une Course (`/courses/:id/suivi`, modifié, public)
- **Affiché en plus** (RG11 à RG13) : bandeau `suivi-vainqueurs` (Course `TERMINEE` avec vainqueur), section « Coureurs en course (N) » (`suivi-nombre-en-course`, lignes `suivi-coureur-<dossard>`), section « Abandons (N) » (`suivi-nombre-abandons`, lignes `suivi-abandon-<dossard>` : dossard, pseudo, Boucle, motif), annonce `suivi-annonce-fin`.
- **Actions** : aucune (lecture seule, mise à jour automatique). Aucun tri, filtre ni bouton.
- **Messages** : « Aucun coureur en course. » ; « Aucun abandon. » ; « Course terminée sans vainqueur. » ; « Boucle non renseignée » / « Motif non renseigné » ; annonce « Course terminée. Vainqueur : … ». Messages de 6.1 inchangés (non démarrée, terminée, introuvable, indisponible, actualisation impossible).

La fiche d'administration et le pilotage ne changent pas.

## 6. Critères d'acceptation

Valeurs de référence : comptes de 3.1 (`Nadia` `ADMIN`, `Léo` `BENEVOLE` affecté, `Alice`, `Bruno` coureurs), `Karim` ; Course X (60 min, M = 5, `EN_COURS`, `t0 = 2026-10-09T14:03:27Z`). Inscriptions : Alice dossard 1, Bruno 2, Karim 3, Mona 4. `Clock` fixe réglable. E2E : projet `chromium` sans avance d'horloge, projet `horloge` (`*.horloge.spec.ts`) avec `avancerHorloge` ; contexte **neuf sans session** ; helpers de `e2e/tests/aide-suivi.ts`.

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné X `EN_COURS` avec Inscriptions dossard 3 et 1 `EN_COURSE`, dossard 2 `ABANDON` (`HORS_DELAI`, Boucle 2), quand `ConsulterSuivi` s'exécute, alors `coureursEnCourse` = dossards [1, 3], `abandons` = [dossard 2, Boucle 2, `HORS_DELAI`], `vainqueurs` = [] ; chaque Inscription dans une seule liste (RG3, RG4) | unitaire |
| CA2 | Étant donné des abandons : dossard 4 (Boucle 2, `MANUEL`), 2 (Boucle 5, `AUTRE`), 6 (Boucle 5, `VOLONTAIRE`), 3 (sans Boucle ni motif), alors `abandons` ressort dans l'ordre dossards [2, 6, 4, 3], `boucleAbandon` et `motifAbandon` du dossard 3 absents (null) ; un deuxième appel donne le même ordre (RG4, RG6) | unitaire |
| CA3 | Étant donné X `TERMINEE` : (a) dossards 7 et 2 `VAINQUEUR`, dossard 5 `ABANDON` ; (b) un seul `VAINQUEUR` dossard 9 ; (c) aucun `VAINQUEUR`, dossards 1 et 2 `ABANDON`, alors (a) `vainqueurs` [2, 7] et `abandons` [5] ; (b) `vainqueurs` [9] ; (c) `vainqueurs` [] et `abandons` [1, 2] ; dans les trois cas `coureursEnCourse` [] et `chronometrage` vide (RG6, RG9) | unitaire |
| CA4 | Étant donné X `EN_PREPARATION` dont les doublures contiennent 3 Inscriptions, quand `ConsulterSuivi` s'exécute, alors les trois listes sont vides et ni `DepotInscriptions` ni `AnnuairePseudos` n'ont été appelés ; pour X `EN_COURS` sans heure de départ, les listes sont renseignées et le chronométrage vide (RG2) | unitaire |
| CA5 | Étant donné 3 Inscriptions dont l'une d'un Compte anonymisé (annuaire : « Coureur anonyme ») et l'autre absente de l'annuaire, alors les pseudos sont « Alice », « Coureur anonyme », « Compte inconnu » ; l'annuaire est appelé **une seule fois** avec les Comptes distincts ; sans Inscription il n'est pas appelé (RG5, RG7) | unitaire |
| CA6 | Étant donné `ConsulterSuivi` construit **sans** `DepotPassages` (les Passages sont inconnus du cas d'usage), alors il s'exécute ; `toString()` de la réponse, d'une ligne de coureur et d'une ligne d'abandon ne contient aucun pseudo ni jeton (RG7, RG17) | unitaire |
| CA7 | Étant donné des doublures qui enregistrent l'ordre d'appel, alors la Course est lue avant les Inscriptions, puis les pseudos ; `heureServeur` et chronométrage de 6.1 (CA1 à CA3 de 6.1) restent corrects avec des Inscriptions présentes (RG7, RG8) | unitaire |
| CA8 | Étant donné X `EN_COURS` en base avec Alice et Bruno `EN_COURSE`, Karim `ABANDON` (Boucle 1, `VOLONTAIRE`), et **aucune session**, quand `GET /api/courses/{X}/suivi`, alors 200, `Cache-Control: no-store`, aucun `Set-Cookie` ; **14** champs de premier niveau exactement ; chaque ligne de `coureursEnCourse` n'a que `dossard` et `pseudo`, chaque ligne d'`abandons` que `dossard`, `pseudo`, `boucleAbandon`, `motifAbandon` ; le corps ne contient ni `jeton`, ni `compteId`, ni `inscriptionId`, ni `statut` de ligne, ni la valeur d'un jeton QR en base (RG1, RG4, RG17) | intégration |
| CA9 | Étant donné une Course `EN_PREPARATION` avec 2 Inscriptions, une `TERMINEE` avec vainqueurs partagés dossards 2 et 1, une `TERMINEE` sans vainqueur, quand `GET /suivi`, alors respectivement : trois listes `[]` (200, jamais 409) ; `vainqueurs` [1, 2] et `coureursEnCourse` [] ; `vainqueurs` [] et `abandons` renseignés ; tri de RG6 respecté sur 5 abandons de Boucles et dossards variés (RG2, RG3, RG6, RG9) | intégration |
| CA10 | Étant donné Alice inscrite à X `EN_COURS` et à Y `EN_PREPARATION`, dont le Compte est supprimé (3.6), quand `GET /suivi` de X, alors la ligne dossard 1 porte `pseudo` « Coureur anonyme », « Alice » n'apparaît nulle part dans le corps ; un nouveau Compte « Alice » inscrit à X reçoit le dossard 5 et la ligne dossard 1 reste « Coureur anonyme » (RG5) | intégration |
| CA11 | Étant donné X `EN_COURS` avec Alice et Bruno ayant un Passage Boucle 1, quand Nadia fait un abandon manuel de Bruno (`MANUEL`) puis `GET /suivi`, alors Bruno est dans `abandons` avec `boucleAbandon` 2 et `MANUEL`, Alice dans `coureursEnCourse` ; après réintégration de Bruno, il est dans `coureursEnCourse`, `abandons` est vide ; après l'abandon automatique (Boucle 1 dépassée, Karim sans Passage, planificateur exécuté) Karim est dans `abandons` `HORS_DELAI`, Boucle 1 (RG3, RG14) | intégration |
| CA12 | Étant donné X avec 3 puis 60 Inscriptions de Comptes distincts, quand `GET /suivi`, alors le nombre d'instructions SQL est **identique** dans les deux cas ; il dépasse de **exactement 2** celui d'une Course `EN_PREPARATION` (lecture des Inscriptions puis des pseudos, seules instructions ajoutées par 6.2 ; les instructions de 6.1 sur la Course, ses bénévoles et le logo sont inchangées) ; ces 2 instructions sont absentes pour une Course `EN_PREPARATION` ; aucune requête sur les Passages (RG7) | intégration |
| CA13 | Étant donné deux Courses X et Y avec des inscrits différents, quand `GET /suivi` de X avec les sessions `ADMIN`, `BENEVOLE` non affecté, `COUREUR` et sans session, alors même corps pour tous (200) et aucune ligne de Y dans la réponse de X ; les CA7 et CA9 de 6.1 restent verts (RG1) | intégration |
| CA14 | Étant donné les appels des CA8 à CA11, alors le journal capturé ne contient aucune ligne de niveau INFO ou supérieur contenant un pseudo ou un jeton (hors avertissement « Compte inconnu », sans pseudo) (RG7, RG17) | intégration |
| CA15 | Étant donné X démarrée (60 min) avec Alice (1) et Bruno (2) inscrits, un contexte neuf sans session, quand on ouvre `/courses/<X>/suivi`, alors `suivi-nombre-en-course` « 2 », `suivi-coureur-1` contient « Dossard 1 » et « Alice », `suivi-coureur-2` « Bruno » (dans cet ordre), `suivi-nombre-abandons` « 0 » et `suivi-message-aucun-abandon`, aucun `suivi-vainqueurs` ; le texte et les attributs de la page ne contiennent pas le jeton QR d'Alice (lu par l'API) (RG1, RG11, RG13, RG17) | E2E |
| CA16 | Étant donné le CA15 ouvert, quand Nadia (API) fait l'abandon manuel de Bruno (`MANUEL`), alors **sans recharger** (marqueur `window` intact) et dans les 5 s : `suivi-nombre-en-course` « 1 », `suivi-abandon-2` contient « Dossard 2 », « Bruno », « Boucle 1 », « Manuel », `suivi-nombre-abandons` « 1 », le message « Aucun abandon. » disparaît ; puis après réintégration de Bruno par Nadia (API), dans les 5 s : `suivi-coureur-2` est de retour, `suivi-abandon-2` absent, « Aucun abandon. » revient (RG13, RG14) | E2E |
| CA17 | Étant donné X 60 min avec Alice, Bruno et Karim, Alice et Bruno scannés sur la Boucle 1 par Léo (API), quand (projet `horloge`) l'horloge avance de `PT61M`, alors dans les 5 s `suivi-abandon-3` contient « Karim », « Boucle 1 », « Hors délai », `suivi-nombre-abandons` « 1 », `suivi-nombre-en-course` « 2 », sans rechargement (RG3, RG13, RG14) | E2E |
| CA18 | Étant donné le CA17 puis Alice seule scannée sur la Boucle 2, quand l'horloge avance de `PT60M`, alors dans les 5 s : pastille « Terminée », `suivi-vainqueurs` contient « Vainqueur » (singulier), « Dossard 1 », « Alice » ; `suivi-abandon-2` « Bruno » « Boucle 2 » « Hors délai » ; plus de section « Coureurs en course », plus de compte à rebours, `suivi-message-terminee` ; ensuite **aucune** requête `GET .../suivi` pendant 7 s (RG10, RG11, RG12) | E2E |
| CA19 | Étant donné X démarrée avec Alice (1) et Bruno (2) sans aucun Passage, quand l'horloge avance de `PT61M`, alors dans les 5 s `suivi-vainqueurs` contient « Vainqueurs » (pluriel), `suivi-vainqueur-1` « Alice » avant `suivi-vainqueur-2` « Bruno », « Aucun abandon. » ; polling arrêté (RG9, RG11, RG12) | E2E |
| CA20 | Étant donné X démarrée avec Alice et Bruno, quand Nadia fait l'abandon manuel des deux (`VOLONTAIRE`), alors dans les 5 s : pastille « Terminée », `suivi-message-aucun-vainqueur` « Course terminée sans vainqueur. », aucun `suivi-vainqueurs`, `suivi-nombre-abandons` « 2 » avec « Volontaire » ; polling arrêté (RG9, RG11) | E2E |
| CA21 | Étant donné X `EN_PREPARATION` avec Alice et Bruno inscrits, un contexte neuf, alors `suivi-message-non-demarree` visible, aucune des trois sections, ni « Alice » ni « Bruno » dans la page ; quand Nadia démarre X (API) sans recharger, dans les 5 s `suivi-coureur-1` « Alice » et `suivi-coureur-2` « Bruno » apparaissent (RG2, RG11, RG14) | E2E |
| CA22 | Étant donné X démarrée avec Alice (1) et Bruno (2) et le suivi ouvert, quand le Compte d'Alice est supprimé (API), alors dans les 5 s `suivi-coureur-1` affiche « Coureur anonyme » et « Alice » n'est plus dans la page ; `suivi-coureur-2` inchangé (RG5, RG14) | E2E |
| CA23 | Étant donné X déjà `TERMINEE` (CA19 ou CA18), quand on ouvre directement `/courses/<X>/suivi` dans un contexte neuf, alors dès le premier affichage vainqueurs et abandons complets sont présents ; une seule requête `GET .../suivi` part, aucune dans les 7 s qui suivent ; `suivi-annonce-fin` est **vide** (RG10, RG15) | E2E |
| CA24 | Étant donné le suivi ouvert sur X `EN_COURS` (Alice, Bruno sans Passage, projet `horloge`), quand l'horloge avance de `PT61M`, alors `suivi-annonce-fin` (`role="status"`) contient « Course terminée. Vainqueurs : Alice, Bruno. » ; un abandon manuel ou une réintégration survenus avant ne produisent aucune annonce ; les listes n'ont aucun `aria-live` (RG15) | E2E |
| CA25 | Étant donné X avec un pseudo de 30 caractères sans espace, un pseudo contenant `<b>x</b>`, 40 inscrits, 6 abandons et le suivi à 375 px, 320 px (texte normal puis 200 %) et 1280 px, alors aucun défilement horizontal, le pseudo long est coupé et lisible, `<b>x</b>` est affiché littéralement (pas de gras), toutes les lignes sont présentes, sections en `h2` et listes en `ul`, aucune perte du focus clavier posé sur l'en-tête après 2 rafraîchissements, le bandeau vainqueur (X terminée) porte le mot « Vainqueur » en texte ; aucune couleur en dur dans les styles ajoutés, contrastes AA, aucune animation avec `prefers-reduced-motion` (RG12, RG13, RG16, RG17) | E2E |
| CA26 | Étant donné le suivi de X ouvert avec ses listes, quand les requêtes `**/suivi` échouent (`page.route` `abort`) pendant 8 s, alors `suivi-avertissement-actualisation` apparaît, les trois sections et leurs lignes restent affichées inchangées ; après retrait de la coupure, l'avertissement disparaît et un abandon survenu pendant la coupure apparaît dans les 4 s (RG10, RG14) | E2E |

Répartition : unitaire 7 (CA1 à CA7), intégration 7 (CA8 à CA14), E2E 12 (CA15 à CA26). Total 26.

Couverture des RG : RG1 CA8/CA13/CA15, RG2 CA4/CA9/CA21, RG3 CA1/CA9/CA11/CA17, RG4 CA1/CA2/CA8, RG5 CA5/CA10/CA22, RG6 CA2/CA3/CA9, RG7 CA4/CA5/CA6/CA7/CA12/CA14, RG8 CA7/CA11, RG9 CA3/CA9/CA19/CA20, RG10 CA18/CA19/CA20/CA23/CA26, RG11 CA15/CA18/CA20/CA21, RG12 CA18/CA19/CA25, RG13 CA15/CA16/CA17/CA25, RG14 CA11/CA16/CA17/CA21/CA22/CA26, RG15 CA23/CA24, RG16 CA25, RG17 CA6/CA8/CA14/CA15/CA25. Écran « Suivi d'une Course » : CA15 à CA26 (E2E). Les 22 CA de 6.1 restent verts (hors CA5 et CA6 de 6.1, remplacés, voir §1).

### Stratégie de test
- Unitaire : `ConsulterSuivi` avec doublures en mémoire (Courses, Inscriptions, annuaire) et `Clock` fixe ; aucun Spring.
- Intégration : `@SpringBootTest` + Testcontainers ; comptage des instructions SQL (CA12) par l'outil de statistiques Hibernate ou un proxy de datasource, **par comparaison** (même total pour 3 et 60 inscrits, écart de 2 avec `EN_PREPARATION`), jamais par valeur absolue ; requêtes sans cookie ni CSRF.
- E2E : helpers existants (Course démarrée, scan par l'API, abandon manuel et réintégration par l'API d'administration, suppression de Compte, `avancerHorloge`). Les attentes tolèrent 5 s (planificateur 1 s + polling 3 s). Un CA instable est remonté, jamais affaibli.

## 7. Tester à la main

Prérequis : `docker compose up -d --build`, http://localhost. Comptes : `Nadia` (`mot-de-passe-admin-1`), `Léo` (`mot-de-passe-benevole-1`), `Karim` et `Mona` (`un-mot-de-passe-12`, à créer par « Créer un compte » si absents). Essais en temps réel avec une Course de **1 minute**.

1. **Préparer** : en `Nadia`, créer « Suivi 62 » (boucle 1 min, 400 m, 5 m, 5 boucles max, Léo affecté). En `Karim` puis `Mona`, s'inscrire (dossards 1 et 2). **Ne pas démarrer.** Noter l'adresse du suivi (lien de la fiche).
2. **Avant le départ** : fenêtre privée sur l'adresse : « La course n'a pas encore démarré. », **ni Karim ni Mona, aucune liste**.
3. **Départ** : démarrer en `Nadia`. Sans recharger la fenêtre privée, sous 5 s : « Coureurs en course (2) » avec « Dossard 1 Karim » et « Dossard 2 Mona », « Abandons (0) » et « Aucun abandon. ».
4. **Abandon manuel** : dans le pilotage (`Nadia`), abandon manuel de Mona, motif « Volontaire », avec confirmation. Fenêtre privée, sous 5 s : Mona passe dans « Abandons (1) » : « Dossard 2 Mona Boucle 1 Volontaire » ; « Coureurs en course (1) ».
5. **Réintégration** : réintégrer Mona dans le pilotage. Fenêtre privée : Mona redevient « Coureur en course », « Aucun abandon. » revient.
6. **Abandon automatique** : scanner Karim et Mona en `Léo` sur la Boucle 1 (jeton lu comme en 6.1), ne pas scanner Mona sur la Boucle 2 : après la bascule, sous ~5 s, Mona dans « Abandons » : « Boucle 2 Hors délai ».
7. **Vainqueur** : continuer à scanner Karim seul : à la fin d'une Boucle sans autre coureur, la page affiche le bandeau « Vainqueur » avec « Dossard 1 Karim », « Course terminée. », les abandons, plus de section « Coureurs en course » ; la page ne fait plus d'appels (onglet Réseau de F12 : plus de requête `suivi`).
8. **Vainqueurs partagés** : nouvelle Course « Suivi 62b » avec les deux coureurs, démarrer, ne scanner personne : à la fin de la minute, bandeau « Vainqueurs » avec les deux dossards, « Aucun abandon. ».
9. **Compte supprimé** : sur une Course en cours, supprimer le compte de `Mona` (Mon compte) : sous 5 s la ligne devient « Coureur anonyme » (même dossard) ; son pseudo n'apparaît plus.
10. **Ouverture d'une Course terminée** : ouvrir l'adresse de « Suivi 62 » dans une nouvelle fenêtre privée : vainqueur et abandons déjà affichés, aucune annonce.
11. **Téléphone** : ouvrir à 375 px : aucune barre de défilement horizontale, pseudo long coupé.

## 8. Points ouverts

À remonter à l'utilisateur ; chaque point a une position par défaut qui permet de commencer.

1. **Confidentialité : dossard et pseudo publics.** Le suivi est lisible par toute personne qui connaît son adresse (et, dès 6.4, par tout visiteur de la liste des Courses). Position par défaut : afficher **dossard et pseudo** pour les coureurs en course, abandons et vainqueurs, **uniquement** après le démarrage de la Course ; jamais le jeton QR, l'identifiant de Compte ou d'Inscription ; « Coureur anonyme » pour un Compte supprimé. Alternatives : dossard seul (aucune donnée de Compte, mais illisible pour le public), ou pseudo masqué sauf pour le vainqueur. À valider ; si retenu, prévoir en incrément ultérieur une mention à l'inscription et à la création de Compte (« votre pseudo et votre dossard seront visibles publiquement »), hors 6.2.
2. **Course `EN_PREPARATION` : pas de liste d'inscrits.** Position par défaut : listes vides, aucune Inscription lue (RG2) ; le nombre d'inscrits n'est pas non plus exposé. Alternative : afficher les inscrits avant le départ (plus vivant, mais publie des pseudos de personnes pouvant encore se désinscrire).
3. **Polling sur `TERMINEE` (point ouvert 5 de 6.1).** Position par défaut : la réponse qui porte `TERMINEE` contient déjà les listes finales, elle est affichée en entier, puis le polling s'arrête (RG10). Risque accepté : un Compte supprimé après la fin reste affiché sous son ancien pseudo dans un onglet déjà ouvert jusqu'au rechargement ; l'anonymisation est effective à tout nouveau chargement. Alternative : continuer à interroger toutes les 30 s (charge inutile pour une Course définitive).
4. **Atomicité de la fin de course.** RG8 suppose que le passage en `TERMINEE` et les statuts finaux des Inscriptions sont validés dans la même transaction (4.9 à 4.11). À vérifier par le développeur back avant d'implémenter ; sinon l'arrêt du polling sur `TERMINEE` n'est pas sûr (même vérification pour l'abandon manuel du dernier coureur) et le contrat doit être revu.
5. **Tri des abandons.** Position par défaut : Boucle d'abandon décroissante puis dossard (les plus récents en tête, adapté à la projection). Alternative : dossard croissant comme le pilotage d'administration. Tri décidé côté serveur, changement sans impact sur le contrat.
6. **Taille des listes.** Aucune pagination ni défilement interne : toutes les lignes sont renvoyées et affichées toutes les 3 s. À surveiller pour des Courses de plusieurs centaines d'inscrits (`nombreMaxParticipants` n'a pas de plafond documenté) ; piste : compression au reverse proxy, cache de 1 s, ou lignes compactes en 6.3. Position par défaut : rien en 6.2.
7. **Nombre de Boucles du vainqueur et des abandonnés.** Afficher « 4 boucles » pour un vainqueur ou un abandon exige de lire les Passages (coût, exposition de données dérivées). Position par défaut : exclu, renvoyé à 6.3 avec les statistiques ; la Boucle d'abandon suffit à en déduire le nombre.
8. **Libellé public du motif `MANUEL`.** « Manuel » est repris du pilotage, mais peu parlant pour le public (« décision de l'organisation »). Position par défaut : mêmes libellés que le pilotage, une seule source.
9. **Abandon antérieur sans Boucle ni motif.** Affiché « Boucle non renseignée » / « Motif non renseigné » (RG13) ; ne devrait plus exister depuis 4.9, vérifier si des données antérieures subsistent en recette.
