# Increment 4.7 : Passages sur l'écran de pilotage

Sur l'écran de pilotage d'une Course (`/administration/courses/:id/pilotage`, 4.2), l'admin voit, **pour la Boucle courante**, quels coureurs `EN_COURSE` ont **bouclé** (Passage enregistré sur la Boucle courante) et lesquels sont **encore en boucle** (pas encore de Passage), ainsi que le **nombre de Boucles terminées par coureur**. L'incrément **enrichit** l'instantané existant `GET /api/administration/courses/{id}/pilotage` (champs ajoutés, aucun retiré ni renommé) et le tableau « Coureurs en course ». Tout est **dérivé des Passages** (4.4a) à chaque appel : rien n'est stocké, aucune migration, aucune variable d'environnement, aucun nouvel endpoint.

Numérotation : RG et CA repartent à 1 dans ce fichier. « 4.2 RGn » et « 4.4a RGn » désignent ces specs.

## Ce qui est déjà livré, donc non respécifié

- **4.2** : `ConsulterPilotage` (lecture seule, `Clock` lu une fois, 404 `COURSE_INTROUVABLE`, 409 `COURSE_NON_DEMARREE`, `TERMINEE` et `EN_COURS` sans `demarreeLe` sans chronométrage), `Course.chronometrage(instant)` (Boucle courante plafonnée à `nombreMaxBoucles`, `boucleMaxTerminee`), `PilotageCourseReponse` (17 champs, `inscritsEnCourse[]` triés par dossard), écran avec polling de 3 s, avancement local du compte à rebours et rafraîchissement immédiat au zéro (4.2 RG12), `data-testid` `pilotage-*`, charte §11 « Pilotage ».
- **4.4a** : `Passage` (immuable, `numeroBoucle`, origine `SCAN` ou `CORRECTION`), unicité (Inscription, Boucle), `DepotPassages`, `Course.boucleDePassage` (un scan pendant la Boucle N donne un Passage numéroté N), continuité des Boucles (un Passage de la Boucle N > 1 exige un Passage de la Boucle N-1).
- **4.4b, 4.5, 4.6** : écran de scan, caméra, refus. Les Passages se créent donc déjà (écran ou API) ; seul leur affichage au pilotage manque.
- Helpers E2E `aide-scan.ts` (`preparerCourseDeScan`, `scannerParEntree`, `lirePassagesEnBase`), `aide-horloge.ts` (`avancerHorloge`), `aide-admin.ts` (`demarrerCourseParApi`, `affecterBenevolesParApi`).

Le **delta** de 4.7 : une règle de progression dans le domaine, une lecture de plus dans `DepotPassages`, deux champs par inscrit et deux compteurs dans l'instantané, deux colonnes et un résumé dans le tableau.

**Taille estimée (production hors tests) : ~225 lignes** (domaine ~35, application ~30, persistance ~25, exposition ~25, front ~110). Sous la cible de 400, aucun découpage.

## 1. Périmètre

**Inclus**
- Par inscrit `EN_COURSE` : nombre de Boucles terminées et indicateur « a bouclé la Boucle courante » (RG1 à RG6).
- Deux compteurs (ont bouclé / encore en boucle) pour la Boucle courante (RG4).
- Comportement aux bascules, au plafond, sans chronométrage (RG5, RG7).
- Affichage dans le tableau de l'écran de pilotage (RG11 à RG13).

**Exclu**
- Abandon automatique (un coureur sans Passage sur la Boucle précédente reste `EN_COURSE` jusqu'à 4.8), fin de Course, vainqueurs : **4.8 à 4.10**. Abandon manuel : **4.11**. Réintégration et badge « corrigé » : **4.12** (les Passages `CORRECTION` sont comptés ici mais non distingués, RG6).
- Liste des abandons et des vainqueurs au pilotage : 4.8 à 4.12 ; leurs Boucles terminées pourront réutiliser la règle de RG2.
- Distance, dénivelé, allure cumulés : **6.3**. Suivi public : **jalon 6**.
- Heure du Passage, historique des Passages d'un coureur, détail par Boucle : non exposés (position par défaut, point ouvert 4).
- Vue pour le bénévole : exclue (écran réservé aux admins, 4.2 RG11).
- Aucune action de modification sur l'écran.

## 2. Règles de gestion

Notation : `B` = `boucleCourante` de l'instantané (4.2 RG1, plafonnée à `nombreMaxBoucles`), calculée sur l'instant unique du `Clock` (4.2 RG8).

**Dérivations (règles uniques, dans le domaine)**
- **RG1** : Passage valide. Tout Passage de l'Inscription, d'origine `SCAN` **ou** `CORRECTION`, est valide. Les coureurs concernés sont les seules Inscriptions `EN_COURSE` (`inscritsEnCourse`, 4.2 RG10) ; une Inscription `ABANDON` ou `VAINQUEUR` n'apparaît pas et n'entre dans aucun compteur, même avec des Passages.
- **RG2** : Boucles terminées. `nombreBouclesTerminees` = nombre de Passages de l'Inscription (entier >= 0). Par la continuité des Boucles (4.4a RG7) et la réintégration (4.12), les numéros sont contigus de 1 à ce nombre, donc cette valeur est aussi le plus grand `numeroBoucle`. Jamais stockée. Fourni même sans chronométrage (RG7).
- **RG3** : a bouclé. `boucleCouranteTerminee` (booléen) = vrai si et seulement si l'Inscription a un Passage de `numeroBoucle = B`. Faux sinon (le coureur est « encore en boucle »). `null` quand `B` est `null` (RG7). Un Passage dont le numéro est supérieur à `B` (anomalie d'horloge, retour arrière en test) est compté par RG2 mais ne vaut pas « a bouclé ».
- **RG4** : compteurs. `nombreAyantBoucle` = nombre d'inscrits `EN_COURSE` avec `boucleCouranteTerminee` vrai ; `nombreEnBoucle` = nombre avec `boucleCouranteTerminee` faux. Leur somme vaut `inscritsEnCourse.length`. Tous deux `null` quand `B` est `null`. Calculés côté serveur : le front n'additionne ni ne recalcule rien (RG12).

**Instants et cohérence**
- **RG5** : bascule, plafond, cohérence avec le scan. La Boucle courante et les Passages se lisent au même instant `t` du `Clock`. Un scan accepté à `t` porte le numéro de la Boucle courante à `t` (4.4a RG5) : il rend donc l'inscrit « a bouclé » à l'instantané suivant, sans exception. Au départ de la Boucle `k + 1` (`t = t0 + k x D`, 4.2 RG2), `B` passe à `k + 1` et tous les inscrits sans Passage `k + 1` redeviennent « encore en boucle », alors que `nombreBouclesTerminees` ne bouge pas ; à `t0 + k x D - 1 ms`, la Boucle `k` est encore courante et ses Passages comptent. Après `boucleMaxTerminee`, `B = nombreMaxBoucles` reste figée : un inscrit avec un Passage sur cette Boucle reste « a bouclé ».
- **RG6** : Passage `CORRECTION` (réintégration, 4.12). Compté par RG2 et RG3 comme un Passage `SCAN`. Aucun badge « corrigé » en 4.7 (4.12). Pas d'instant de scan exposé (non nécessaire).
- **RG7** : états sans chronométrage. `TERMINEE` ou `EN_COURS` sans `demarreeLe` (4.2 RG6, RG7) : `boucleCourante` et donc `boucleCouranteTerminee`, `nombreAyantBoucle`, `nombreEnBoucle` valent `null` ; `nombreBouclesTerminees` reste renseigné (la colonne « Boucles terminées » reste donc affichée). `EN_PREPARATION` : 409 inchangé, aucun Passage possible.
- **RG8** : aucun effet de bord. L'instantané n'écrit rien et ne change aucun statut (4.2 RG9). Un coureur sans Passage sur la Boucle `B - 1` (donc `nombreBouclesTerminees < B - 1`) est **toujours `EN_COURSE`** jusqu'à 4.8 et s'affiche « encore en boucle » comme les autres, sans alerte particulière.

**Contrat et lecture**
- **RG9** : contrat additif. `GET /api/administration/courses/{id}/pilotage` garde ses 17 champs, leur sens, ses rôles, ses codes d'erreur et son ordre d'évaluation (4.2 RG11). S'ajoutent les champs du §4 (2 au premier niveau, 2 par inscrit). Tri des inscrits inchangé : `dossard` croissant (lignes stables d'un rafraîchissement à l'autre). Aucun jeton QR, identifiant de Passage, identifiant de Compte ni heure de scan dans la réponse.
- **RG10** : lecture. `ConsulterPilotage` lit les Passages des Inscriptions `EN_COURSE` de la Course **dans la même transaction `readOnly`** que le reste, avec un nombre de requêtes **constant** (indépendant du nombre d'inscrits : pas de N+1). `Clock` lu une seule fois. Aucune ligne de journal INFO ou supérieure en fonctionnement nominal (4.2 RG11). `toString()` de la réponse inchangé (identifiant de la Course seul).

**Écran**
- **RG11** : tableau « Coureurs en course » complété. Colonnes : Dossard, Coureur, Statut (inchangées), **« Boucle B »** (pastille `pilotage-inscrit-boucle` : « A bouclé » si `boucleCouranteTerminee` vrai, « En boucle » si faux ; colonne **absente** si `B` est `null`), **« Boucles terminées »** (`pilotage-inscrit-boucles-terminees`, entier, `0` compris, toujours présente). Au-dessus du tableau, le résumé `pilotage-bouclage` avec deux éléments `pilotage-ayant-boucle` « A bouclé : X » et `pilotage-en-boucle` « En boucle : Y » (absent si `B` est `null` ou si la liste est vide). Le compteur existant `pilotage-compteur` (« N coureur(s) en course sur P inscrit(s) ») est inchangé. Le libellé de colonne est « Boucle 3 » avec le numéro courant (mis à jour à la bascule).
- **RG12** : rafraîchissement inchangé (4.2 RG12) : mêmes polling de 3 s et rafraîchissement immédiat au zéro du compte à rebours ; les nouvelles valeurs arrivent avec l'instantané et sont remplacées en place (sans clignotement, sans perte de focus, sans saut de mise en page). Une bascule de Boucle remet tous les inscrits à « En boucle » dès l'instantané qui porte la nouvelle Boucle. Le front **n'infère jamais** « a bouclé » (ni localement, ni par une comparaison d'heures) : il affiche les champs reçus.
- **RG13** : charte et accessibilité (`docs/design.md` §8 et §11 « Pilotage », recommandations à confirmer). « A bouclé » et « En boucle » se distinguent par le **libellé** et par la couleur de pastille (variables de la charte, aucune couleur en dur), jamais par la couleur seule ; contraste AA ; pas de défilement horizontal à 320 px (texte à 200 % compris), le tableau se contient dans la carte ; **aucun `aria-live` ajouté** (seule la région `pilotage-annonce-boucle` de 4.2 reste). L'aperçu des composants est complété dans `docs/design-apercu.html`.

**Transverses**
- **RG14** : plusieurs Courses en parallèle. Chaque instantané ne lit que les Passages de ses propres Inscriptions ; deux Courses (durées et départs différents) ont des Boucles courantes et des compteurs indépendants au même `Clock`.
- **RG15** : architecture et non-régression. Règle de RG1 à RG4 dans le domaine (par exemple une classe de progression d'un coureur construite à partir de ses Passages et de `B`), jamais dans le contrôleur, la persistance ni le front ; port de lecture `DepotPassages` complété ; entité JPA distincte ; contrôleur sans logique (mapping seul) ; aucune dérogation ArchUnit ; aucune migration (`databasechangelog` inchangé), aucune variable, `docker-compose*.yml`, `.env.example` et `scripts/donnees-demo.sh` inchangés. Comportements de 1.x à 4.6 et `data-testid` de 4.2 inchangés ; les assertions « exactement 17 champs » de 4.2 CA6 et de 4.4a CA14 (« aucun champ de Passage ») deviennent « les 17 champs d'origine plus les champs de 4.7 » : changement de contrat additif voulu, pas un affaiblissement (point ouvert 7).

## 3. Cas limites

- **Instants exacts de bascule** (Boucle de 1 min, M = 5, `t0 = 14:00:00Z`, Bruno a des Passages 1 et 2, Chloé un Passage 1, Tom aucun) : à `14:00:59.999Z`, Boucle 1 : Bruno et Chloé « A bouclé », Tom « En boucle » (2 / 1) ; à `14:01:00.000Z`, Boucle 2 : Bruno « A bouclé » (Passage 2), Chloé et Tom « En boucle » (1 / 2), `nombreBouclesTerminees` 2, 1, 0 ; à `14:02:00.000Z`, Boucle 3 : tous « En boucle » (0 / 3), Boucles terminées inchangées.
- **Dernière Boucle autorisée** (M = 5) : à `t0 + 4 min`, Boucle 5, `derniereBoucle` vrai : un inscrit avec Passage 5 est « A bouclé ». À `t0 + 5 min` (`boucleMaxTerminee`) et au-delà : `B` = 5 figée, le scan est refusé (`TEMPS_DE_COURSE_ECOULE`, 4.4a) ; les inscrits avec Passage 5 restent « A bouclé », les autres « En boucle ». La Course reste `EN_COURS` (4.10).
- **Aucun passage sur une Boucle** : tous « En boucle », `nombreAyantBoucle` 0. État normal (la suite, vainqueurs partagés ou abandons, relève de 4.8 à 4.10).
- **Course complète / vainqueurs / abandon** : une Inscription qui n'est plus `EN_COURSE` quitte le tableau et les compteurs (RG1) ; l'effet sur les listes relève de 4.8 à 4.12.
- **Coureur réintégré** (4.12) : ses Passages `CORRECTION` comptent (RG6) ; il peut être « A bouclé » sans Passage `SCAN` sur la Boucle courante.
- **Passage `CORRECTION` vs `SCAN`** : sans distinction à l'écran en 4.7 (badge en 4.12).
- **Inscription sans aucun Passage** : `nombreBouclesTerminees` 0, « En boucle » (si `B` non nul).
- **Course non démarrée** : 409 inchangé. **`TERMINEE`** et **`EN_COURS` sans heure de départ** : RG7 (pas de colonne « Boucle B », pas de résumé, colonne « Boucles terminées » conservée).
- **Passage de numéro supérieur à `B`** (anomalie de test) : compté, jamais « a bouclé » (RG3).
- **Plusieurs Courses en parallèle** : RG14. **Compte anonymisé** (3.6) : pseudo « Coureur anonyme », Passages conservés et comptés.
- **Scan concurrent à la lecture** : l'instantané reflète les Passages validés avant la lecture ; un scan validé juste après apparaît au polling suivant (au plus 3 s).
- **Bénévole ou coureur** : 403 inchangé. **Réseau coupé** : avertissement de 4.2, dernières valeurs conservées.

## 4. Contrat d'API

**Un seul endpoint touché, modifié de façon additive.** Tout le reste (rôles, CSRF, `Cache-Control: no-store`, erreurs 401/403/404/409/500, ordre d'évaluation, champs existants) est défini par `increment-4.2.md` §4 et inchangé.

### GET /api/administration/courses/{id}/pilotage (modifié : champs ajoutés)

- Rôle : `ADMIN` ou `ADMIN_MASTER`. Pas de corps, pas de CSRF. **200** `PilotageCourseReponse` : les 17 champs de 4.2, plus :

| Champ | Type | Description |
|---|---|---|
| `nombreAyantBoucle` | entier >= 0 ou `null` | inscrits `EN_COURSE` ayant un Passage sur `boucleCourante` (RG4) ; `null` si `boucleCourante` est `null` |
| `nombreEnBoucle` | entier >= 0 ou `null` | inscrits `EN_COURSE` sans Passage sur `boucleCourante` (RG4) ; `null` si `boucleCourante` est `null` |

- `inscritsEnCourse[]` : `inscriptionId`, `dossard`, `pseudo`, `statut` (inchangés, 4.2), plus :

| Champ | Type | Description |
|---|---|---|
| `nombreBouclesTerminees` | entier >= 0 | nombre de Passages de l'Inscription (RG2), toujours renseigné |
| `boucleCouranteTerminee` | booléen ou `null` | RG3 ; `null` si `boucleCourante` est `null` |

Total : 19 champs au premier niveau, 6 par inscrit.

Exemple (Boucle de 1 min, M = 5, `t0 = 2026-10-12T14:00:00Z`, `Clock` à `14:01:30Z`) :
`{ "courseId": "…", "nom": "Backyard du jour", "statut": "EN_COURS", "boucleCourante": 2, "tempsAvantFinBoucleMillisecondes": 30000, "dureeEcouleeMillisecondes": 90000, "nombreInscrits": 3, "nombreAyantBoucle": 1, "nombreEnBoucle": 2, "inscritsEnCourse": [ { "inscriptionId": "…", "dossard": 1, "pseudo": "Karim", "statut": "EN_COURSE", "nombreBouclesTerminees": 2, "boucleCouranteTerminee": true }, { "inscriptionId": "…", "dossard": 2, "pseudo": "Sophie", "statut": "EN_COURSE", "nombreBouclesTerminees": 1, "boucleCouranteTerminee": false }, { "inscriptionId": "…", "dossard": 3, "pseudo": "Tom", "statut": "EN_COURSE", "nombreBouclesTerminees": 0, "boucleCouranteTerminee": false } ], … }` (autres champs de 4.2 omis ici).

Cas `TERMINEE` ou sans `demarreeLe` : `nombreAyantBoucle`, `nombreEnBoucle` et chaque `boucleCouranteTerminee` valent `null`, `nombreBouclesTerminees` est renseigné (RG7).

Codes de retour et `ProblemDetail` : inchangés (401 `NON_AUTHENTIFIE`, 403 `ACCES_REFUSE`, 404 `COURSE_INTROUVABLE`, 409 `COURSE_NON_DEMARREE`, 500 `ERREUR_INTERNE`). Aucun nouveau code d'erreur. Aucun autre endpoint ne change.

### Port interne (indicatif, non exposé)
`DepotPassages` : une lecture groupée des Passages des Inscriptions d'une Course (par exemple `parCourse(courseId)` ou, pour limiter le volume, le nombre de Passages par Inscription et l'ensemble des Inscriptions ayant un Passage de la Boucle `B`) ; domaine : règle de progression d'un coureur (RG1 à RG3) et comptage (RG4). Noms libres, comportement fixé par RG1 à RG10.

### Front
Type `PilotageCourse` (`pilotage-course.ts`) complété : `nombreAyantBoucle: number | null`, `nombreEnBoucle: number | null`, et par inscrit `nombreBouclesTerminees: number`, `boucleCouranteTerminee: boolean | null`. Aucun nouvel appel d'API.

## 5. Écrans

### Pilotage d'une Course (`/administration/courses/:id/pilotage`, modifié)

- **Accès**, en-tête, chronométrage, polling, messages d'état et d'erreur : inchangés (4.2).
- **Affiché en plus** : résumé `pilotage-bouclage` (`pilotage-ayant-boucle` « A bouclé : X », `pilotage-en-boucle` « En boucle : Y ») sous le compteur de coureurs ; dans le tableau `pilotage-inscrits`, par ligne `pilotage-inscrit` : `pilotage-inscrit-boucle` (pastille « A bouclé » ou « En boucle », colonne intitulée « Boucle N ») et `pilotage-inscrit-boucles-terminees` (colonne « Boucles terminées »). Sans `boucleCourante` (Course terminée ou sans heure de départ) : la colonne « Boucles terminées » reste affichée, le résumé et la colonne « Boucle N » disparaissent.
- **Actions** : aucune nouvelle (abandon et réintégration : 4.11, 4.12).
- **Messages** : aucun nouveau. Les états existants (« Aucun coureur en course. », avertissement d'actualisation, « La course n'est pas démarrée. », etc.) sont inchangés.

Aucun autre écran n'est modifié.

## 6. Critères d'acceptation

Valeurs de référence : comptes de 3.1 (`Patron` `ADMIN_MASTER`, `Nadia` `ADMIN`, `Léo` `BENEVOLE` affecté, `Alice` `COUREUR`, `Bruno`, `Chloé`, `Tom`). Course X : Boucle de **1 min**, M = 5, `t0 = 2026-10-12T14:00:00Z`, Inscriptions `Bruno` (dossard 1), `Chloé` (2), `Tom` (3) `EN_COURSE`. En unitaire et en intégration, `Clock` fixe réglable ; Passages posés par SQL ou par fixture (l'anti-doublon de 4.6 ne concerne que le scan) ; en E2E, horloge pilotable (projet `horloge`).

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné la règle de progression (domaine) : Passages {1, 2, 3} et `B` = 3 -> 3 Boucles terminées, a bouclé ; mêmes Passages et `B` = 4 -> 3, n'a pas bouclé ; aucun Passage et `B` = 1 -> 0, n'a pas bouclé ; Passages {1, 2} `SCAN` plus {3} `CORRECTION` et `B` = 3 -> 3, a bouclé (RG6) ; Passages {1, 2, 3, 4} et `B` = 3 -> 4 Boucles terminées (anomalie), a bouclé (Passage 3 présent) ; Passages {1, 2, 4} et `B` = 3 -> 3, n'a pas bouclé (le Passage 4 ne vaut pas la Boucle 3) ; `B` = `null` -> a bouclé `null`, Boucles terminées renseigné ; Boucle plafonnée (`B` = M = 5, Passage 5 présent) -> a bouclé (RG1, RG2, RG3, RG5, RG6, RG7) | unitaire |
| CA2 | Étant donné `ConsulterPilotage` avec dépôts en mémoire, X `EN_COURS`, Passages {1, 2} pour Bruno, {1} pour Chloé, aucun pour Tom, une Inscription `ABANDON` et une `VAINQUEUR` ayant des Passages : `Clock` à `t0 + 90 s` -> Bruno (2, vrai), Chloé (1, faux), Tom (0, faux), `nombreAyantBoucle` 1, `nombreEnBoucle` 2, somme = nombre d'inscrits `EN_COURSE`, abandon et vainqueur absents du tableau et des compteurs ; `Clock` à `t0 + 59 999 ms` -> Bruno vrai, Chloé vrai, Tom faux (2 / 1) ; à `t0 + 60 000 ms` -> 1 / 2 ; à `t0 + 120 s` -> 0 / 3 ; Z `TERMINEE` et U `EN_COURS` sans heure -> compteurs et indicateurs `null`, Boucles terminées renseignées ; deux Courses de durées différentes au même `Clock` -> compteurs indépendants ; le `Clock` n'est lu qu'une fois ; aucune écriture, aucun changement de statut (même Tom reste `EN_COURSE`) (RG1, RG4, RG5, RG7, RG8, RG10, RG14) | unitaire |
| CA3 | Étant donné la base Testcontainers, X démarrée par l'API (`demarreeLe` = `t0`), Passages posés (Bruno 1 et 2, Chloé 1 ; Tom aucun), `Clock` à `t0 + 90 s`, `Patron` : `GET .../pilotage` -> **200** `application/json`, `Cache-Control: no-store`, exactement **19** champs au premier niveau (les 17 de 4.2 inchangés en valeur et en type, plus `nombreAyantBoucle` 1 et `nombreEnBoucle` 2) ; `inscritsEnCourse` trié par dossard avec **6** champs par ligne : Bruno (`nombreBouclesTerminees` 2, `boucleCouranteTerminee` true), Chloé (1, false), Tom (0, false) ; aucun jeton, identifiant de Passage, identifiant de Compte ni heure de scan dans le corps ; `Nadia` obtient le même résultat ; aucune ligne modifiée en base (RG2, RG3, RG4, RG9) | intégration |
| CA4 | Étant donné CA3 : `Clock` à `t0 + 59 999 ms` -> `boucleCourante` 1, Bruno true, Chloé true, Tom false, 2 / 1 ; à `t0 + 60 000 ms` -> Boucle 2, Bruno true, Chloé false, Tom false, 1 / 2, `nombreBouclesTerminees` inchangés 2, 1, 0 ; à `t0 + 120 000 ms` -> Boucle 3, 0 / 3 ; Passages 1 à 5 posés pour Bruno, `Clock` à `t0 + 240 000 ms` (Boucle 5) puis `t0 + 300 000 ms` (`boucleMaxTerminee`) et `t0 + 3 600 000 ms` : `boucleCourante` 5, Bruno true avec 5 Boucles terminées, Chloé false ; Passage `CORRECTION` (`scanne_le` nul) posé pour Chloé en Boucle 2 à `t0 + 90 s` -> Chloé true, 2 Boucles terminées ; Passage de numéro 4 posé avec `Clock` à `t0 + 90 s` -> compté, jamais true (RG3, RG5, RG6) | intégration |
| CA5 | Étant donné X démarrée par l'API, `Léo` affecté : `POST .../passages` (jeton de Bruno) puis `GET .../pilotage` -> Bruno `boucleCouranteTerminee` true, 1 Boucle terminée, `nombreAyantBoucle` 1, `nombreEnBoucle` 2 (un scan accepté rend l'inscrit « a bouclé », RG5) ; Bruno passé `ABANDON` en base (SQL) -> absent de `inscritsEnCourse` et des compteurs (0 / 2), `nombreInscrits` 3 ; Course Z `TERMINEE` (SQL) -> 200, `nombreAyantBoucle` et `nombreEnBoucle` `null`, `boucleCouranteTerminee` `null`, `nombreBouclesTerminees` renseigné ; U `EN_COURS` sans `demarree_le` -> idem ; Y `EN_PREPARATION` -> 409 `COURSE_NON_DEMARREE` ; anonyme -> 401, `Alice` et `Léo` -> 403 `ACCES_REFUSE` (comportements de 4.2 inchangés) ; deux Courses de durées différentes au même `Clock` -> compteurs indépendants (RG1, RG5, RG7, RG8, RG9, RG14) | intégration |
| CA6 | Étant donné X avec 3 puis 30 inscrits ayant des Passages, `Clock` fixe : le nombre de requêtes SQL émises par un `GET .../pilotage` (statistiques Hibernate) est **identique** dans les deux cas (pas de N+1) ; journal capturé après plusieurs `GET` (200, 403, 404, 409) : aucune ligne INFO ou supérieure, ni `Bruno`, ni nom de Course, ni identifiant de Compte ; `databasechangelog` se termine par le même dernier changeset qu'avant 4.7 (aucun ajouté) ; `.env.example`, `docker-compose*.yml` et `scripts/donnees-demo.sh` inchangés ; ArchUnit : règles inchangées sans dérogation, la règle de progression ne dépend ni de Spring ni de la persistance (RG9, RG10, RG15) | intégration |
| CA7 | Étant donné `Course E2E <suffixe>` de **60 min**, M = 5, Bruno (1), Chloé (2), Tom (3), `Léo` affecté, démarrée par l'API, `Nadia` sur l'écran de pilotage et `Léo` (autre contexte) sur l'écran de scan : au chargement `pilotage-bouclage` « A bouclé : 0 » et « En boucle : 3 », les trois lignes « En boucle » et `pilotage-inscrit-boucles-terminees` « 0 » ; `Léo` scanne Bruno puis Chloé (vert) ; sans recharger la page et dans les 5 s, Bruno et Chloé passent « A bouclé » avec « 1 » Boucle terminée, Tom reste « En boucle » avec « 0 », « A bouclé : 2 » et « En boucle : 1 », l'en-tête de colonne affiche « Boucle 1 » ; `lirePassagesEnBase` contient exactement `1:1:SCAN` et `2:1:SCAN` ; le compteur `pilotage-compteur` reste « 3 coureurs en course sur 3 inscrits » (RG2, RG3, RG4, RG11, RG12) | E2E |
| CA8 | Étant donné la stack `e2e` (projet `horloge`), Course de **60 min**, M = 2, Bruno, Chloé, Tom, `Léo` affecté, démarrée, pilotage ouvert : `avancerHorloge(PT10M)`, scan de Bruno et de Chloé (Boucle 1) ; `avancerHorloge(PT50M)` (Boucle 2) -> dans les 5 s, **les trois** sont « En boucle », en-tête « Boucle 2 », « A bouclé : 0 » et « En boucle : 3 », « Boucles terminées » 1, 1, 0 ; scan de Bruno (Boucle 2) -> Bruno « A bouclé », « Boucles terminées » 2, « A bouclé : 1 » ; `avancerHorloge(PT60M)` (`boucleMaxTerminee`) -> message de fin de temps de 4.2, Bruno toujours « A bouclé » (2), Chloé « En boucle » (1), Tom « En boucle » (0), la Course est toujours « En cours » ; deuxième Course de **1 min** pilotée dans un autre onglet au même moment : ses compteurs ne bougent pas avec ceux de la première (RG5, RG11, RG14) | E2E |
| CA9 | Étant donné des réponses de `GET .../pilotage` **interceptées** (`page.route`) : `TERMINEE` -> aucun `pilotage-bouclage`, aucune colonne « Boucle N », colonne « Boucles terminées » présente pour les lignes fournies, message « La course est terminée. » ; `EN_COURS` sans `demarreeLe` -> « Heure de départ inconnue… », pas de résumé, pas de colonne « Boucle N », « Boucles terminées » affichée ; aucun inscrit -> « Aucun coureur en course. » et aucun `pilotage-bouclage` ; `nombreBouclesTerminees` 0 et 27 -> « 0 » et « 27 » ; trois instantanés successifs où une ligne passe « En boucle » puis « A bouclé » : la ligne est mise à jour en place (même élément, focus conservé sur le lien de retour, aucune requête supplémentaire hors rythme de 3 s) ; l'en-tête de colonne suit `boucleCourante` (« Boucle 3 » puis « Boucle 4 ») (RG7, RG11, RG12) | E2E |
| CA10 | Étant donné l'écran de pilotage avec des réponses interceptées (3 inscrits dont un pseudo de 30 caractères), à 375 px puis 320 px (texte normal puis 200 %) : aucun défilement horizontal, tableau contenu dans la carte ; « A bouclé » et « En boucle » distinguables par le seul texte (la pastille est lisible sans couleur) ; contraste AA des deux pastilles ; aucune couleur en dur ; aucun nouvel attribut `aria-live` ni `role="status"` dans le tableau et le résumé (seul `pilotage-annonce-boucle` reste) ; `prefers-reduced-motion` : aucune animation ; un rafraîchissement ne fait pas perdre le focus (RG13) | E2E |
| CA11 | Étant donné les suites E2E existantes du pilotage (4.2 CA13 à CA19), de la fiche, du scan (4.4b, 4.5, 4.6), `donnees-demo.spec.ts` (21 éléments) et `theme-*` rejouées : elles passent sans changer une attente métier ; les `data-testid` `pilotage-*` de 4.2 sont inchangés ; la ligne `pilotage-inscrit` de 4.2 CA13 garde dossard, pseudo et pastille « En course » (RG15) | E2E |

Répartition : unitaire 2 (CA1, CA2), intégration 4 (CA3 à CA6), E2E 5 (CA7 à CA11). Total 11.

Couverture des RG : RG1 CA1/CA2/CA5, RG2 CA1/CA3/CA7, RG3 CA1/CA3/CA4/CA7, RG4 CA2/CA3/CA7, RG5 CA1/CA2/CA4/CA5/CA8, RG6 CA1/CA4, RG7 CA1/CA2/CA5/CA9, RG8 CA2/CA5, RG9 CA3/CA5/CA6, RG10 CA2/CA6, RG11 CA7/CA8/CA9, RG12 CA7/CA9, RG13 CA10, RG14 CA2/CA5/CA8, RG15 CA6/CA11. Écran « Pilotage d'une Course » : CA7 à CA11 (E2E).

### Stratégie E2E
- Courses créées, affectées et démarrées par l'API (`preparerCourseDeScan`, `affecterBenevolesParApi`, `demarrerCourseParApi`) ; les scans passent par l'écran de scan (`scannerParEntree`) dans CA7 (seul test qui relie les deux écrans) et par l'API ailleurs ; jetons par `lireJetonQrEnBase`.
- CA7, CA9, CA10, CA11 : projet `chromium`, Courses de **60 min** (aucune bascule de Boucle). CA8 : projet `horloge` (un seul worker, remise à zéro automatique, 4.3) : les avances d'horloge restent sous M x D = 120 min sauf la dernière, voulue (`boucleMaxTerminee`), et le scan de Boucle 2 se fait à plus de `d` = 2 min du Passage 1 (anti-doublon de 4.6 respecté). CA9 et CA10 : réponses fabriquées.
- Les attentes sur le polling tolèrent 5 s (rythme de 3 s plus latence).

## 7. Tester à la main

Prérequis : 4.6 livrée. `docker compose up -d --build` (services sains), http://localhost. **Base neuve** de préférence (`docker compose down -v`), puis `./scripts/donnees-demo.sh` (inchangé : 21 éléments, Léo affecté à « Backyard du jour », boucles de **1 min**, 5 Boucles max, Karim 1, Sophie 2, Tom 3). Comptes : `Nadia` (`mot-de-passe-admin-1`), `Léo` (`mot-de-passe-benevole-1`), `Alice` (`un-mot-de-passe-12`). Pour les jetons : `docker compose exec base psql -U backyard -d backyard -c "select i.dossard, i.jeton_qr from inscription i join course c on c.id = i.course_id where c.nom = 'Backyard du jour' order by i.dossard"`. Utiliser deux fenêtres (une navigation privée pour `Léo`).

1. **Démarrer** : en `Nadia`, fiche de « Backyard du jour » > « Démarrer la course » > « Démarrer maintenant » > « Ouvrir le pilotage ». Attendu : « Boucle 1 sur 5 », « 3 coureurs en course sur 3 inscrits », résumé « A bouclé : 0 » et « En boucle : 3 », trois lignes « En boucle » avec « 0 » Boucle terminée, colonne « Boucle 1 ».
2. **Deux coureurs sur trois** (dans la première minute) : en `Léo`, « Scanner » sur « Backyard du jour », coller le jeton de Karim (vert), puis celui de Sophie (vert). Attendu au pilotage, **sans recharger**, en 3 secondes environ : Karim et Sophie « A bouclé » avec « 1 » ; Tom **« En boucle »** avec « 0 » ; « A bouclé : 2 », « En boucle : 1 ».
3. **Bascule de Boucle** : à la fin de la minute (compte à rebours à zéro), « Boucle 2 sur 5 » s'affiche et les trois lignes repassent « En boucle », colonne « Boucle 2 », « A bouclé : 0 » et « En boucle : 3 », tandis que « Boucles terminées » reste 1, 1, 0.
4. **Boucle 2** : en `Léo`, scanner Karim (vert « Boucle 2 » ; si « Scan trop rapproché » s'affiche, attendre le délai de 15 s et rescanner). Attendu : Karim « A bouclé » avec « 2 », Sophie et Tom « En boucle » (1 et 0), « A bouclé : 1 », « En boucle : 2 ».
5. **Coureur en retard** : scanner Tom (Boucle 2) est refusé « Boucle précédente manquante ». Tom reste « En boucle » avec « 0 » et **reste « En course »** : l'abandon automatique n'existe pas encore (4.8).
6. **Actualisation** : rafraîchir la page (F5) : mêmes valeurs. Contrôler en base : `docker compose exec base psql -U backyard -d backyard -c "select i.dossard, count(*) from passage p join inscription i on i.id = p.inscription_id group by 1 order by 1"` : les « Boucles terminées » affichées correspondent aux nombres de lignes.
7. **Dernière Boucle** (facultatif) : laisser tourner jusqu'à la Boucle 5 puis la fin du temps : les inscrits ayant un Passage sur la Boucle 5 restent « A bouclé » après le message « Le temps de la dernière boucle est écoulé… », la Course reste « En cours ».
8. **Rôles** : en `Alice` ou `Léo`, l'URL de pilotage est refusée ; déconnecté : redirection vers la connexion (inchangé).
9. **Téléphone** (facultatif) : largeur 375 px : tableau lisible, pastilles lisibles sans la couleur.

## 8. Points ouverts

À remonter à l'utilisateur ; chaque point a une position par défaut qui permet de commencer.

1. **Définition des « Boucles terminées »**. Position par défaut : nombre de Passages `SCAN` et `CORRECTION` du coureur (RG2), équivalent au plus grand numéro de Boucle grâce à la continuité (4.4a RG7). À confirmer : une Boucle terminée est bien une Boucle avec un Passage, même corrigé.
2. **Coureur en retard avant 4.8**. Un coureur sans Passage sur la Boucle précédente reste `EN_COURSE` et « En boucle », sans alerte (RG8). Position par défaut : aucun signal particulier ; 4.8 l'abandonnera.
3. **Tri du tableau**. Position par défaut : par dossard (lignes stables). Alternative : « En boucle » en premier, mais les lignes sauteraient à chaque scan et à chaque bascule.
4. **Heure du Passage** (dernier passage, temps de Boucle). Position par défaut : non exposée en 4.7 (donnée non demandée ; allure et statistiques en 6.3).
5. **Libellés** « A bouclé » / « En boucle » (le « en boucle » vient de la roadmap) et textes des résumés : choix de formulation, modifiables sans impact sur le contrat.
6. **Annonce accessible** des changements de bouclage (`aria-live`). Position par défaut : aucune, pour ne pas surcharger les lecteurs d'écran à chaque scan (comme le compte à rebours, 4.2 RG14) ; seule l'annonce de changement de Boucle existe.
7. **Contrat modifié de façon additive**. Les tests existants qui comptent « exactement 17 champs » (4.2 CA6) ou affirment « aucun champ de Passage » (4.4a CA14) sont étendus par le testeur-it, jamais supprimés ni affaiblis.
8. **Charte §11 « Pilotage »** : pastilles « A bouclé » (succès) et « En boucle » (neutre), recommandation à confirmer au premier incrément qui la met en œuvre ; aperçu ajouté à `docs/design-apercu.html`.
9. **Vue bénévole ou public** du bouclage : exclue (écran admin seul, 4.2 RG11) ; le suivi public relève de 6.2.
10. **Découpage** : ~225 lignes, aucune découpe nécessaire.
