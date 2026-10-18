# Increment 6.1 : Écran de suivi, l'essentiel

**Dépendance : 4.2 (chronométrage de la Course, horloge serveur), 4.3 (horloge pilotable en profil `e2e`), 4.10 (fin de course) et 2.x (logo) livrées et validées.** 6.1 ouvre le jalon 6 : une page **publique**, sans connexion, qui montre une Course en direct. Elle réutilise le chronométrage existant (`Course.chronometrage`) et le principe d'affichage du pilotage (4.2 RG12, RG13) sans exposer l'administration.

Numérotation : RG et CA repartent à 1. « 4.2 RGn » désigne la spec correspondante.

## État actuel constaté dans le dépôt

- `SecuriteConfiguration` : seule lecture publique sous `/api/courses` : `GET /api/courses/*/logo` (`LogoPublicController`, `Cache-Control: public, no-cache`, 404 pour inconnu ou id invalide). Toute autre route `/api/**` hors liste est `authenticated()`. `GET` : pas de CSRF.
- `Course.chronometrage(Instant)` (domaine) calcule Boucle courante, début et fin de Boucle, temps restant, durée écoulée, `derniereBoucle`, `boucleMaxTerminee`, à la milliseconde ; **vide** si la Course n'est pas `EN_COURS` ou sans `demarreeLe`. `Course.demarreeLe` est tronqué à la seconde.
- `GET /api/administration/courses/{id}/pilotage` (4.2) fait déjà ce calcul, mais charge les inscrits et leurs pseudos et répond 409 `COURSE_NON_DEMARREE` avant démarrage : **il ne doit pas être ouvert au public**. Son `heureServeur` (UTC, millisecondes) et la logique d'affichage local du compte à rebours (4.2 RG12) sont repris tels quels.
- `ListerCourses.avecEmpreinteLogo` fournit l'adresse versionnée du logo (`logoUrl`, null sans logo) ; `GestionnaireErreursCourses` fournit déjà 404 `COURSE_INTROUVABLE`.
- Frontend : `app.routes.ts` n'a aucune route de suivi ; l'en-tête (`entete.html`) affiche le menu de session sur toutes les routes. Visuel de substitution de Course et pastilles de statut existent (`docs/design.md` §8, fiche de Course).
- Horloge pilotable (`/api/test/horloge/avance`, helper E2E `avancerHorloge`) disponible en profil `e2e`.

## 1. Périmètre

**Inclus**
- Endpoint public `GET /api/courses/{id}/suivi` : nom, logo, statut, Boucle courante, temps avant la fin de la Boucle, durée écoulée, heure serveur (RG1 à RG6).
- Écran public `/courses/:id/suivi` : nom et logo, Boucle courante, compte à rebours, durée écoulée, rafraîchissement automatique toutes les 3 secondes (RG7 à RG11).
- Lien « Ouvrir le suivi public » sur la fiche d'administration d'une Course (seul moyen de trouver l'adresse en 6.1, RG12).

**Exclu**
- Coureurs en course, coureurs en abandon (Boucle et motif), vainqueur(s) : **6.2**.
- Nombre de Boucles terminées, distance et dénivelé cumulés, allure, durée finale figée d'une Course terminée, mode plein écran / projection, jetons `--taille-projection-*`, masquage de l'en-tête de session : **6.3**.
- Liste publique des Courses (à venir, en cours, terminées) et liens vers leur suivi : **6.4**.
- Toute modification du domaine, de la base (aucune migration), des règles d'abandon et de fin de course, de l'authentification. Aucune variable d'environnement, aucun changement de `docker-compose*.yml`, de `.env.example`, de Caddy.

**Taille estimée (production hors tests) : ~330 lignes** : cas d'usage ~45, DTO + contrôleur ~65, sécurité ~2, route et écran Angular (composant, service de polling, styles) ~190, lien fiche ~15, charte ~10. Dans la cible, pas de découpage.

## 2. Règles de gestion

**API**
- **RG1** : `GET /api/courses/{id}/suivi` est **public** : aucune authentification, aucune session créée, aucun CSRF (GET). Un Compte connecté, quel que soit son rôle, reçoit la même réponse. Règle ajoutée dans `SecuriteConfiguration` à côté de celle du logo (`GET /api/courses/*/suivi`, `permitAll`). Les autres méthodes sur ce chemin ne répondent jamais 2xx.
- **RG2** : données exposées, **liste fermée** : `courseId`, `nom`, `logoUrl`, `statut`, `heureServeur`, `nombreMaxBoucles`, `boucleCourante`, `derniereBoucle`, `boucleMaxTerminee`, `tempsAvantFinBoucleMillisecondes`, `dureeEcouleeMillisecondes`. Jamais d'inscrit, de pseudo, de dossard, de jeton QR, de bénévole, d'identifiant de Compte, de paramètres de Boucle (distance, dénivelé : 6.3), de `demarreeLe`, de date ni de nombre d'inscrits.
- **RG3** : états de la réponse, tous en **200** :
  - `EN_PREPARATION` : `boucleCourante`, `tempsAvantFinBoucleMillisecondes`, `dureeEcouleeMillisecondes` null ; `derniereBoucle` et `boucleMaxTerminee` faux.
  - `EN_COURS` avec chronométrage : champs de RG4 renseignés.
  - `EN_COURS` sans heure de départ (ligne posée par SQL, 4.1 RG13) : mêmes champs null qu'`EN_PREPARATION`, statut `EN_COURS`.
  - `TERMINEE` : mêmes champs null (le chronométrage n'existe que pour `EN_COURS`, 4.2) ; la durée finale figée est hors périmètre (6.3, point ouvert 2).
- **RG4** : calcul, **unique définition dans le domaine** (`Course.chronometrage`), aucune règle dupliquée dans le cas d'usage ni dans le front. Soit `t0 = demarreeLe`, `D` = durée de Boucle en ms, `M = nombreMaxBoucles`, `now` = instant lu **une seule fois** sur le `Clock` injecté (jamais l'heure du client, aucun paramètre de requête lu) :
  - `e = max(0, now - t0)` (un instant antérieur au départ compte comme le départ) ; `dureeEcouleeMillisecondes = e`, **sans plafond** ;
  - `boucleCourante = min(floor(e / D) + 1, M)` ; la Boucle N commence à `t0 + (N - 1) x D` ; **à la milliseconde exacte de la frontière, la nouvelle Boucle est courante** ;
  - `tempsAvantFinBoucleMillisecondes = max(0, t0 + boucleCourante x D - now)` ;
  - `derniereBoucle = (boucleCourante == M)` ; `boucleMaxTerminee = (e >= M x D)` : la Boucle courante reste alors M et le temps restant est 0 ;
  - `heureServeur` : `now` en ISO-8601 UTC avec millisecondes (`2026-10-09T14:04:27.000Z`), comme 4.2.
- **RG5** : introuvable : identifiant inconnu, non UUID ou Course supprimée : **404** `COURSE_INTROUVABLE`, sans distinction. Pas de 409 : une Course non démarrée est un état normal (RG3), pas une erreur.
- **RG6** : lecture seule : aucune écriture, aucun changement de statut (la bascule de Boucle, l'abandon automatique et la fin de course restent à la charge du planificateur, 4.8 à 4.10). Réponse en `Cache-Control: no-store`. Aucune ligne de journal INFO ou supérieure en fonctionnement nominal (lecture fréquente, publique) ; `toString()` de la réponse n'affiche que l'identifiant de la Course. Le cas d'usage `ConsulterSuivi` ne charge **ni Inscriptions ni Passages ni comptes** (une seule lecture de Course). Plusieurs Courses en parallèle : chaque réponse ne dépend que de sa Course.

**Écran**
- **RG7** : rafraîchissement. Polling toutes les **3 secondes** de `GET /api/courses/{id}/suivi` (CLAUDE.md : 2-3 s), un seul appel à la fois, **et** compte à rebours et durée écoulée **avancés localement** entre deux réponses, à partir des **durées** reçues (`tempsAvantFinBoucleMillisecondes`, `dureeEcouleeMillisecondes`) et d'une horloge **monotone** du navigateur (`performance.now()`), comme 4.2 RG12 : `reste = max(0, reste0 - delta)`, `ecoule = ecoule0 + delta`. L'heure du navigateur n'est **jamais** comparée à celle du serveur : une horloge client fausse ou décalée ne change rien à l'affichage ; la Boucle n'est jamais recalculée par le front. Quand le reste local atteint 0 et que `boucleMaxTerminee` est faux, le front redemande **immédiatement** une réponse (puis reprend le rythme de 3 s ; si la Boucle n'a pas changé, nouvelle demande après 1 s). Une réponse dont `heureServeur` est antérieur à celui de la réponse affichée est ignorée. Le polling s'arrête à la sortie de l'écran **et** dès qu'une réponse porte le statut `TERMINEE` (une Course terminée n'est jamais rouverte).
- **RG8** : erreurs de l'écran. Réseau coupé ou 5xx : l'affichage conserve la dernière réponse, le compte à rebours continue localement, `suivi-avertissement-actualisation` « Actualisation impossible, nouvelle tentative… » apparaît, les tentatives continuent toutes les 3 s, il disparaît au premier succès. Premier chargement en erreur réseau ou 5xx : `suivi-erreur` « Service indisponible, veuillez réessayer plus tard. » avec tentatives toutes les 3 s. **404** : arrêt du polling, `suivi-introuvable` « Cette course n'existe pas. » (aucune redirection, aucun lien vers l'administration). Une réponse 200 revient toujours à l'état nominal sans rechargement.
- **RG9** : formats (présentation, front uniquement), identiques à 4.2 RG13 : compte à rebours `HH:MM:SS` arrondi à la seconde **supérieure** (`ceil(reste / 1000)`, `00:01:00` au départ d'une Boucle de 1 min, `00:00:01` pendant sa dernière seconde, `00:00:00` seulement si le reste est nul) ; durée écoulée `HH:MM:SS` arrondie à la seconde **inférieure**, heures sur au moins deux chiffres et sans limite (`27:05:09`) ; Boucle : « Boucle 3 sur 24 ».
- **RG10** : états affichés (route publique `/courses/:id/suivi`, sans garde, titre « Suivi de la course - Backyard Ultra Tracker ») :
  - Dès qu'une réponse est reçue : `suivi-nom` (`h1.titre`, nom de la Course, mots coupés) et `suivi-logo` (visuel 16/9 : image de `logoUrl`, ou visuel de substitution existant si `logoUrl` est null ou si l'image ne charge pas) ; pastille de statut de la Course (§8 : « En préparation », « En cours », « Terminée »).
  - `EN_COURS` nominal : `suivi-boucle` « Boucle N sur M », libellé « Prochain départ dans » + `suivi-compte-a-rebours`, libellé « Durée écoulée » + `suivi-duree-ecoulee`.
  - `derniereBoucle` (et non `boucleMaxTerminee`) : le libellé devient « Fin de la dernière boucle dans » et `suivi-message-derniere-boucle` « Dernière boucle autorisée. » s'affiche.
  - `boucleMaxTerminee` : compte à rebours figé à `00:00:00`, `suivi-message-boucle-max-terminee` « Le temps de la dernière boucle est écoulé. La fin de course sera constatée automatiquement. » ; la durée écoulée continue d'avancer jusqu'au passage en `TERMINEE`.
  - `EN_PREPARATION` : `suivi-message-non-demarree` « La course n'a pas encore démarré. » ; ni Boucle, ni compte à rebours, ni durée. Le polling continue : au démarrage, l'écran passe à l'état nominal **sans rechargement**.
  - `EN_COURS` sans heure de départ : `suivi-message-sans-depart` « Le chronométrage n'est pas disponible. ».
  - `TERMINEE` : `suivi-message-terminee` « La course est terminée. », ni Boucle ni compte à rebours ni durée (6.2 y ajoutera les vainqueurs).
  - Chargement initial : « Chargement du suivi… ».
  - Annonce : région `role="status"` `suivi-annonce-boucle` qui annonce « Boucle N en cours » **uniquement** quand la Boucle change entre deux réponses (vide au premier affichage). Le compte à rebours et la durée écoulée n'ont **aucun** `aria-live`.
- **RG11** : charte (`docs/design.md` §11 « Pilotage » et « Suivi public », sans le mode projection de 6.3). `main.page.page--cartes` ; Boucle, compte à rebours et durée en `--taille-titre`, chiffres tabulaires, largeur réservée en `ch` ; messages en `.message--info` / `.message--avertissement` / `.message--erreur` avec libellé (jamais la couleur seule) ; aucune couleur ni taille en dur ; aucun défilement horizontal à 320 px (texte à 200 % compris) ; aucune animation autre que le changement de couleur de fond ; aucun clignotement, perte de focus ni saut de mise en page à chaque rafraîchissement (valeurs remplacées en place) ; `docs/design-apercu.html` complété si un composant est ajouté.
- **RG12** : accès. La fiche d'administration d'une Course (`/administration/courses/:id`), pour **tout statut**, affiche un lien `fiche-lien-suivi` « Ouvrir le suivi public » (`a.bouton.bouton--secondaire`, `href` `/courses/<id>/suivi`, ouvert dans le même onglet), dans une section « Suivi public » placée sous les sections existantes ; aucun autre changement de la fiche (attentes E2E de 4.1 et 4.2 RG18 inchangées).

## 3. Cas limites

- **Instants de bascule** (Course de 60 min, M = 5, `t0 = 14:03:27Z`) : à `15:03:26.999Z`, Boucle 1, reste 1 ms, écoulé 3 599 999 ; à `15:03:27.000Z`, Boucle 2, reste 3 600 000, écoulé 3 600 000 ; à `15:03:27.001Z`, Boucle 2, reste 3 599 999. Affichage : `00:00:01`, puis `01:00:00`, puis `01:00:00`.
- **Dernière Boucle autorisée** : à `t0 + 4 x D`, Boucle 5 sur 5, `derniereBoucle` vrai ; à `t0 + 5 x D - 1 ms`, reste 1 ; à `t0 + 5 x D`, `boucleMaxTerminee` vrai, Boucle 5, reste 0 ; plus tard, Boucle 5 toujours (jamais 6). `M = 1` : `derniereBoucle` vrai dès le départ.
- **Course non démarrée** : 200, statut `EN_PREPARATION`, champs de chronométrage null (RG3) ; l'écran attend et bascule seul au démarrage.
- **Course terminée** : 200, statut `TERMINEE`, chronométrage null ; le polling s'arrête (RG7). Les trois sortes de fin (vainqueur unique, vainqueurs partagés, dernière Boucle autorisée, 4.9 et 4.10) donnent le même écran en 6.1 : aucun affichage propre avant 6.2.
- **Course introuvable ou supprimée** (suppression possible seulement en `EN_PREPARATION`, 2.5) : 404 ; l'écran déjà ouvert sur une Course supprimée passe à `suivi-introuvable` au tick suivant.
- **Horloge client fausse** (avance ou retard de plusieurs heures) : sans effet (RG7). **Onglet en arrière-plan** : les navigateurs ralentissent les minuteurs ; à la reprise, la première réponse recale l'affichage (le compte à rebours local se base sur `performance.now()`, donc rattrape l'écart écoulé).
- **Réponse reçue en retard ou dans le désordre** : ignorée si `heureServeur` est plus ancien que celui affiché (RG7).
- **Plusieurs Courses en parallèle** : deux onglets de suivi sur deux Courses démarrées à des instants différents, durées de Boucle différentes : Boucles indépendantes (RG6).
- **Course sans logo** : `logoUrl` null, visuel de substitution. **Logo en erreur de chargement** : même substitution, sans message.
- **Passages, Inscriptions, Abandon, Réintégration, `CORRECTION` vs `SCAN`, coureur réintégré, inscription sans Passage, bénévole non affecté, rôle insuffisant** : aucune règle propre à 6.1, l'endpoint n'en lit aucun et n'a pas de contrôle de rôle ; ces cas sont traités en 6.2 et 6.3.
- **Utilisateur connecté (admin, bénévole, coureur)** : même écran et mêmes données qu'un anonyme ; l'en-tête de l'application reste celui de sa session (masquage de l'en-tête : 6.3).
- **Chemin invalide** : `/courses/abc/suivi` : l'appel API répond 404, l'écran affiche `suivi-introuvable`.

## 4. Contrat d'API

### `GET /api/courses/{id}/suivi`

- **Accès** : public (aucune authentification, aucun CSRF). Aucun corps, aucun paramètre de requête lu.
- **Réponse 200** `SuiviCourseReponse`, `Cache-Control: no-store`, `Content-Type: application/json` :

| Champ | Type | Contenu |
|---|---|---|
| `courseId` | UUID (texte) | identifiant de la Course |
| `nom` | texte | nom de la Course |
| `logoUrl` | texte ou `null` | adresse publique versionnée du logo (`/api/courses/{id}/logo?v=…`), même valeur que le pilotage ; `null` sans logo |
| `statut` | `EN_PREPARATION`, `EN_COURS` ou `TERMINEE` | statut de la Course |
| `heureServeur` | texte, ISO-8601 UTC avec millisecondes | instant du `Clock` utilisé pour tous les calculs |
| `nombreMaxBoucles` | entier | M (pour « Boucle N sur M ») |
| `boucleCourante` | entier ou `null` | Boucle en cours, plafonnée à M ; `null` sauf `EN_COURS` avec chronométrage |
| `derniereBoucle` | booléen | `boucleCourante == nombreMaxBoucles` ; faux si pas de chronométrage |
| `boucleMaxTerminee` | booléen | le temps de la dernière Boucle est écoulé ; faux si pas de chronométrage |
| `tempsAvantFinBoucleMillisecondes` | entier long ou `null` | millisecondes avant la fin de la Boucle courante, 0 une fois dépassée ; `null` sans chronométrage |
| `dureeEcouleeMillisecondes` | entier long ou `null` | millisecondes depuis le départ de la Boucle 1, sans plafond ; `null` sans chronométrage |

Exemple (`EN_COURS`, Course de 60 min, M = 5, à 3 h 25 min du départ) :
`{"courseId":"…","nom":"Backyard des Crêtes","logoUrl":null,"statut":"EN_COURS","heureServeur":"2026-10-09T17:28:27.000Z","nombreMaxBoucles":5,"boucleCourante":4,"derniereBoucle":false,"boucleMaxTerminee":false,"tempsAvantFinBoucleMillisecondes":2100000,"dureeEcouleeMillisecondes":12300000}`.
Exemple (`EN_PREPARATION`) : mêmes champs, `boucleCourante`, `tempsAvantFinBoucleMillisecondes` et `dureeEcouleeMillisecondes` à `null`, `derniereBoucle` et `boucleMaxTerminee` à `false`.

- **Erreurs** (`ProblemDetail`, `application/problem+json`, champ `code`) :

| Code HTTP | Cas | `code` |
|---|---|---|
| 404 | identifiant inconnu, non UUID, Course supprimée | `COURSE_INTROUVABLE` |
| 405 / 404 / 403 | autre méthode sur ce chemin (jamais 2xx ; un `POST` sans CSRF donne 403 `CSRF_INVALIDE`) | existants |

Aucun 400, 401, 403 de rôle, 409 : ni paramètre, ni authentification, ni état refusé. Les erreurs ne contiennent aucune donnée d'une autre Course.

### Impacts back
- **Domaine** : aucun changement ; `Course.chronometrage` est réutilisé tel quel.
- **Application** : cas d'usage `ConsulterSuivi` (lecture seule, `@Transactional(readOnly = true)`), une lecture de Course par appel, instant lu une seule fois sur le `Clock`.
- **Exposition** : contrôleur `SuiviPublicController` (`GET /api/courses/{id}/suivi`), DTO `SuiviCourseReponse`, identifiant lu par `IdentifiantCourse.deCourse`.
- **Sécurité** : une ligne `permitAll` pour `GET /api/courses/*/suivi`.
- Aucune migration Liquibase, aucune variable d'environnement.

### Front (indicatif)
Route `courses/:id/suivi` dans `app.routes.ts` (sans garde), composant `suivi/suivi-course`, service `SuiviApiService.consulter(id)`, calcul local d'affichage partagé avec le pilotage s'il existe déjà (ne pas dupliquer le code de 4.2 RG12), lien `fiche-lien-suivi` sur la fiche. Noms libres, comportement fixé par RG7 à RG12.

## 5. Écrans

### Suivi d'une Course (`/courses/:id/suivi`, nouveau, public)
- **Affiché** : nom (`suivi-nom`), logo ou visuel de substitution (`suivi-logo`), pastille de statut, puis selon l'état (RG10) : Boucle courante « Boucle N sur M » (`suivi-boucle`), compte à rebours (`suivi-compte-a-rebours`) avec son libellé, durée écoulée (`suivi-duree-ecoulee`), messages `suivi-message-*`, annonce de changement de Boucle (`suivi-annonce-boucle`).
- **Actions** : aucune (lecture seule, rafraîchissement automatique). Aucun bouton « Actualiser ».
- **Messages** : « Chargement du suivi… » ; « La course n'a pas encore démarré. » ; « La course est terminée. » ; « Dernière boucle autorisée. » ; « Le temps de la dernière boucle est écoulé. La fin de course sera constatée automatiquement. » ; « Le chronométrage n'est pas disponible. » ; « Cette course n'existe pas. » (`suivi-introuvable`) ; « Service indisponible, veuillez réessayer plus tard. » (`suivi-erreur`) ; « Actualisation impossible, nouvelle tentative… » (`suivi-avertissement-actualisation`).

### Fiche de la Course d'administration (`/administration/courses/:id`, modifié)
- **Affiché en plus** : section « Suivi public » avec le lien `fiche-lien-suivi` « Ouvrir le suivi public », pour tout statut.
- **Actions** : le lien mène à `/courses/:id/suivi`. Aucune autre modification.

## 6. Critères d'acceptation

Valeurs de référence : comptes de 3.1 (`Nadia` `ADMIN`, `Léo` `BENEVOLE`, `Alice`, `Bruno` coureurs). Course X (« Backyard des Crêtes », **60 min**, M = 5, 400 m, 5 m, `EN_COURS`, `t0 = 2026-10-09T14:03:27Z`, avec ou sans Inscriptions : aucune n'est lue). `Clock` fixe réglable en unitaire et en intégration. En E2E : projet `chromium` pour les CA sans avance d'horloge, projet `horloge` pour les CA qui utilisent `avancerHorloge` (`*.horloge.spec.ts`). Les tests E2E de l'écran s'ouvrent dans un **contexte neuf sans session** (équivalent d'une fenêtre privée).

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné `ConsulterSuivi` sur X, alors avec le `Clock` à `t0` : Boucle 1, reste 3 600 000, écoulé 0 ; `t0 + 30 min` : Boucle 1, reste 1 800 000, écoulé 1 800 000 ; `t0 + 3 h 25 min` : Boucle 4, reste 2 100 000, écoulé 12 300 000 ; `t0 - 5 s` : Boucle 1, reste 3 600 000, écoulé 0 ; `heureServeur` égale l'instant du `Clock` en UTC avec millisecondes (RG4) | unitaire |
| CA2 | Étant donné X, alors `t0 + 3 599 999 ms` : Boucle 1, reste 1, écoulé 3 599 999 ; `t0 + 3 600 000 ms` : Boucle 2, reste 3 600 000 ; `t0 + 3 600 001 ms` : Boucle 2, reste 3 599 999 ; une Course de 90 min passe en Boucle 2 à `t0 + 90 min` exactement (RG4) | unitaire |
| CA3 | Étant donné X (M = 5), alors `t0 + 4 x 60 min` : Boucle 5, `derniereBoucle` vrai, `boucleMaxTerminee` faux, reste 3 600 000 ; `t0 + 5 x 60 min - 1 ms` : Boucle 5, reste 1 ; `t0 + 5 x 60 min` : `boucleMaxTerminee` vrai, Boucle 5, reste 0, écoulé 18 000 000 ; `t0 + 6 h` : Boucle 5 (jamais 6), reste 0, écoulé 21 600 000 ; avec M = 1 : `derniereBoucle` vrai dès `t0` (RG4) | unitaire |
| CA4 | Étant donné une Course `EN_PREPARATION`, une Course `EN_COURS` sans heure de départ et une Course `TERMINEE`, quand `ConsulterSuivi` s'exécute, alors chacune renvoie son statut, son nom et son identifiant, avec `boucleCourante`, `tempsAvantFinBoucleMillisecondes` et `dureeEcouleeMillisecondes` absents, `derniereBoucle` et `boucleMaxTerminee` faux, sans lever d'exception (RG3) | unitaire |
| CA5 | Étant donné un identifiant sans Course, quand `ConsulterSuivi` s'exécute, alors `CourseIntrouvableException` est levée ; le cas d'usage ne lit ni Inscriptions, ni Passages, ni annuaire de pseudos (aucun appel à ces ports sur des doublures de test) (RG5, RG6) | unitaire |
| CA6 | Étant donné X en base (Boucle 2 au `Clock` de test) et **aucune session**, quand `GET /api/courses/{X}/suivi`, alors 200, `Cache-Control: no-store`, aucun `Set-Cookie`, corps : `courseId` = X, `nom`, `statut` `EN_COURS`, `nombreMaxBoucles` 5, `boucleCourante` 2, `derniereBoucle` false, `boucleMaxTerminee` false, `tempsAvantFinBoucleMillisecondes` et `dureeEcouleeMillisecondes` conformes au `Clock`, `heureServeur` avec millisecondes ; le corps ne contient que les 11 champs de RG2 (aucun `inscrits`, `pseudo`, `dossard`, `jeton`, `benevoles`, `demarreeLe`) (RG1, RG2, RG4, RG6) | intégration |
| CA7 | Étant donné X, quand la même requête est faite avec une session `ADMIN`, `BENEVOLE` affecté, `BENEVOLE` non affecté et `COUREUR`, alors 200 et le même corps pour tous ; sans session et sans jeton CSRF la requête n'est jamais refusée (RG1) | intégration |
| CA8 | Étant donné une Course `EN_PREPARATION`, une `TERMINEE`, et une `EN_COURS` au `Clock` `t0 + 5 x 60 min` (planificateur non exécuté dans le test), quand `GET /suivi`, alors 200 pour chacune : les deux premières avec leur statut et les trois champs de chronométrage `null` (jamais 409), la troisième avec `boucleMaxTerminee` true, Boucle 5, `tempsAvantFinBoucleMillisecondes` 0 ; une Course avec logo renvoie `logoUrl` commençant par `/api/courses/<id>/logo?v=`, sans logo `null` (RG2, RG3, RG4) | intégration |
| CA9 | Quand `GET /api/courses/<UUID inconnu>/suivi`, `/api/courses/abc/suivi`, et celui d'une Course supprimée, alors 404 `application/problem+json`, `code` `COURSE_INTROUVABLE`, sans donnée d'une autre Course ; `POST`, `PUT`, `DELETE` sur ce chemin ne répondent jamais 2xx (un `POST` sans CSRF : 403 `CSRF_INVALIDE`) (RG1, RG5) | intégration |
| CA10 | Étant donné deux Courses `EN_COURS` (1 min démarrée à 14:03:27Z, 60 min démarrée à 14:03:57Z) lues au `Clock` `14:04:27Z`, alors la première est en Boucle 2, la seconde en Boucle 1 ; la lecture de l'une ne modifie rien chez l'autre ni en base (aucun statut, aucun Passage, aucune Inscription modifiés après lecture) (RG6) | intégration |
| CA11 | Étant donné X démarrée (60 min, M = 5, logo envoyé) et un contexte **neuf sans session**, quand on ouvre `/courses/<X>/suivi`, alors on voit sans se connecter : `suivi-nom` « Course E2E … », `suivi-logo` avec une image dont `src` contient `/api/courses/<X>/logo`, pastille « En cours », `suivi-boucle` « Boucle 1 sur 5 », `suivi-compte-a-rebours` valant `01:00:00` ou `00:59:5x` (jamais plus de `01:00:00`), `suivi-duree-ecoulee` entre `00:00:00` et `00:00:09` ; aucune redirection vers `/connexion` (RG1, RG7, RG9, RG10) | E2E |
| CA12 | Étant donné le CA11 ouvert, quand on lit `suivi-compte-a-rebours` et `suivi-duree-ecoulee` à 2 s d'intervalle sans rien faire, alors le compte à rebours a diminué de 1 à 3 s, la durée écoulée a augmenté d'autant ; au moins une requête `GET .../suivi` est partie dans les 3,5 s suivant l'ouverture ; **aucun rechargement** (un marqueur posé dans `window` est toujours présent) ; le focus clavier posé sur un élément de l'en-tête n'est pas perdu (RG7, RG11) | E2E |
| CA13 | Étant donné le CA11 ouvert (projet `horloge`), quand l'horloge applicative avance de `PT59M` puis de `PT1M`, alors dans les 4 s suivant chaque avance : après `PT59M` « Boucle 1 sur 5 » et un compte à rebours de `00:01:00` au plus ; après `PT1M` `suivi-boucle` « Boucle 2 sur 5 », `suivi-annonce-boucle` « Boucle 2 en cours », durée écoulée proche de `01:00:00` ; tout cela sans rechargement (marqueur `window` intact) ; après `PT3H` de plus : « Boucle 5 sur 5 », `suivi-message-derniere-boucle` « Dernière boucle autorisée. » et libellé « Fin de la dernière boucle dans » (RG4, RG7, RG10) | E2E |
| CA14 | Étant donné le navigateur dont `Date` est décalé de +3 h par `addInitScript` (`performance.now` intact) et un contexte neuf, quand on ouvre le suivi de X, alors l'affichage est le même qu'au CA11 (Boucle 1, compte à rebours `00:59:5x` à `01:00:00`) ; avec `Date` décalé de -3 h, idem (RG7) | E2E |
| CA15 | Étant donné une Course `EN_PREPARATION` ouverte en suivi dans un contexte neuf, alors `suivi-message-non-demarree` « La course n'a pas encore démarré. », nom, logo ou substitution et pastille « En préparation » sont visibles, ni `suivi-boucle`, ni compte à rebours ; quand la Course est démarrée par l'API (Nadia) **sans recharger**, alors dans les 5 s `suivi-boucle` « Boucle 1 sur 5 » et le compte à rebours apparaissent, le message disparaît (RG3, RG7, RG10) | E2E |
| CA16 | Étant donné une Course démarrée **sans Inscription ni Passage** (projet `horloge`), quand l'horloge avance de `PT61M` et que la fin de course est constatée (comme 4.10), alors dans les 5 s `suivi-message-terminee` « La course est terminée. » s'affiche, la pastille est « Terminée », `suivi-boucle` et le compte à rebours disparaissent ; ensuite aucune requête `GET .../suivi` ne part pendant 7 s (polling arrêté) (RG3, RG7, RG10) | E2E |
| CA17 | Quand on ouvre `/courses/<UUID inconnu>/suivi` puis `/courses/abc/suivi` dans un contexte neuf, alors `suivi-introuvable` « Cette course n'existe pas. », aucun nom ni compte à rebours, aucune redirection ; après 7 s aucune nouvelle requête (polling arrêté) (RG5, RG8) | E2E |
| CA18 | Étant donné le suivi de X ouvert, quand les requêtes `**/suivi` échouent (`page.route` en `abort`) pendant 8 s, alors `suivi-avertissement-actualisation` apparaît, la dernière Boucle reste affichée et le compte à rebours continue de diminuer ; après retrait de la coupure, l'avertissement disparaît dans les 4 s sans rechargement. Premier chargement avec une réponse 503 : `suivi-erreur` « Service indisponible, veuillez réessayer plus tard. », puis état nominal après retour à 200 (RG8) | E2E |
| CA19 | Étant donné le suivi ouvert et la réponse de `**/suivi` remplacée par `page.route` par une réponse `EN_COURS` avec `boucleCourante` 5, `derniereBoucle` true, `boucleMaxTerminee` true, `tempsAvantFinBoucleMillisecondes` 0 et `dureeEcouleeMillisecondes` 18 060 000, alors `suivi-message-boucle-max-terminee` « Le temps de la dernière boucle est écoulé. La fin de course sera constatée automatiquement. » s'affiche, `suivi-compte-a-rebours` vaut `00:00:00` et reste à `00:00:00` pendant 5 s, `suivi-duree-ecoulee` passe de `05:01:00` et avance ; le front ne redemande pas de réponse immédiate à 0 (une seule requête par tick de 3 s) (RG4, RG7, RG10) | E2E |
| CA20 | Étant donné Nadia connectée, quand elle ouvre la fiche d'une Course `EN_PREPARATION`, puis d'une `EN_COURS`, puis d'une `TERMINEE`, alors `fiche-lien-suivi` « Ouvrir le suivi public » est présent dans les trois cas avec `href` `/courses/<id>/suivi` ; le clic ouvre l'écran de suivi avec le nom de la Course ; les attentes de la section « Démarrage » de 4.1 et du lien « Pilotage » de 4.2 sont inchangées (RG12) | E2E |
| CA21 | Étant donné le suivi de X à 375 px, 320 px (texte normal puis 200 %) et 1280 px, alors aucun défilement horizontal ; un nom de 60 caractères sans espace est coupé et lisible ; `suivi-boucle`, `suivi-compte-a-rebours` et `suivi-duree-ecoulee` sont en `--taille-titre` à chiffres tabulaires, et leur largeur ne change pas quand les valeurs changent (aucun saut de mise en page mesuré sur 5 rafraîchissements) ; compte à rebours et durée sans `aria-live`, `suivi-annonce-boucle` en `role="status"` ; les messages portent un libellé en plus de la couleur ; contrastes AA ; `prefers-reduced-motion` : aucune animation ; aucune couleur en dur dans les styles ajoutés (RG10, RG11) | E2E |
| CA22 | Étant donné deux Courses démarrées (X de 60 min, Y de 1 min, à des instants différents) ouvertes dans deux onglets d'un même contexte, quand l'horloge avance de `PT61M`, alors X est en « Boucle 2 sur 5 » et Y affiche sa propre Boucle ou son état de fin, chacun indépendamment de l'autre (RG6) | E2E |

Répartition : unitaire 5 (CA1 à CA5), intégration 5 (CA6 à CA10), E2E 12 (CA11 à CA22). Total 22.

Couverture des RG : RG1 CA6/CA7/CA9/CA11, RG2 CA6/CA8, RG3 CA4/CA8/CA15/CA16, RG4 CA1/CA2/CA3/CA6/CA8/CA13/CA19, RG5 CA5/CA9/CA17, RG6 CA5/CA6/CA10/CA22, RG7 CA11/CA12/CA13/CA14/CA15/CA16/CA19, RG8 CA17/CA18, RG9 CA11, RG10 CA11/CA13/CA15/CA16/CA19/CA21, RG11 CA12/CA21, RG12 CA20. Écrans : « Suivi d'une Course » CA11 à CA19, CA21, CA22 ; « Fiche de la Course » CA20 (tous en E2E).

### Stratégie de test
- Unitaire : `ConsulterSuivi` avec doublures des ports (dépôt de Courses) et un `Clock` fixe ; aucun Spring. Le calcul étant dans `Course.chronometrage` (déjà couvert par 4.2), CA1 à CA3 vérifient le cas d'usage et la forme de la réponse, pas une seconde implémentation de la règle.
- Intégration : `@SpringBootTest` + Testcontainers, `Clock` de test réglable ; requêtes **sans** cookie ni CSRF.
- E2E : helpers existants (Course créée, démarrée par l'API, `avancerHorloge`, `reinitialiserHorloge`). Les délais d'attente tolèrent 4 à 5 s (polling de 3 s). Un CA instable est remonté, jamais affaibli.

## 7. Tester à la main

Prérequis : `docker compose up -d --build` (services sains), http://localhost. Comptes : `Nadia` (`mot-de-passe-admin-1`), `Léo` (`mot-de-passe-benevole-1`), `Karim` (`un-mot-de-passe-12`). Pas d'horloge pilotable hors profil `e2e` : les essais se font en temps réel avec une Course de **1 minute** (ou « Backyard du jour » si elle existe dans vos données de démo).

1. **Préparer** : en `Nadia`, créer « Suivi test » (boucle 1 min, 400 m, 5 m de dénivelé, 5 boucles max, logo au choix, Léo affecté). Inscrire `Karim` (depuis son compte). **Ne pas démarrer.**
2. **Lien** : sur la fiche de « Suivi test », cliquer « Ouvrir le suivi public » : l'adresse est `/courses/<id>/suivi`. La copier.
3. **Fenêtre privée, course non démarrée** : coller l'adresse dans une fenêtre privée (aucune connexion) : nom, logo (ou visuel de substitution), pastille « En préparation », message « La course n'a pas encore démarré. », ni Boucle ni compte à rebours.
4. **Démarrage sans recharger** : en `Nadia` (autre fenêtre), démarrer la Course. Dans la fenêtre privée, sans toucher à la page : sous 5 s, « Boucle 1 sur 5 », compte à rebours `00:00:5x` qui diminue seconde par seconde, durée écoulée qui augmente.
5. **Les informations avancent seules** : laisser tourner. À la fin de la minute, si personne n'a bouclé la Boucle 1, la Course se termine (aucun Passage, vainqueurs partagés) : « La course est terminée. » et la pastille « Terminée » apparaissent sans recharger.
6. **Passer plusieurs Boucles** : refaire avec une nouvelle Course identique (« Suivi test 2 ») ; lire le jeton de Karim (`docker compose exec base psql -U backyard -d backyard -c "select i.dossard, i.jeton_qr from inscription i join course c on c.id = i.course_id where c.nom = 'Suivi test 2'"`) et, en `Léo`, scanner Karim à chaque Boucle : le suivi affiche « Boucle 2 », « Boucle 3 »… (le compte à rebours repart de `00:01:00`), « Dernière boucle autorisée. » en Boucle 5, puis « Le temps de la dernière boucle est écoulé… » et la fin.
7. **Horloge d'ordinateur fausse** : changer l'heure du poste de plusieurs heures puis recharger le suivi : la Boucle et les durées restent justes (elles viennent du serveur). Rétablir l'heure.
8. **Course introuvable** : ouvrir `http://localhost/courses/00000000-0000-0000-0000-000000000000/suivi` : « Cette course n'existe pas. », sans redirection.
9. **Coupure réseau** : fenêtre privée sur une Course en cours, F12 > Réseau > Hors connexion pendant 10 s : « Actualisation impossible, nouvelle tentative… » apparaît, le compte à rebours continue ; rétablir le réseau : l'avertissement disparaît seul.
10. **Téléphone et grand écran** : ouvrir le suivi sur un téléphone (largeur 375 px) puis plein écran sur un ordinateur : aucune barre de défilement horizontale, chiffres lisibles (le mode projection est livré en 6.3).

## 8. Points ouverts

À remonter à l'utilisateur ; chaque point a une position par défaut qui permet de commencer.

1. **Course `EN_PREPARATION` visible publiquement** : l'endpoint répond 200 avec le nom et le logo d'une Course non démarrée (pratique pour un écran de salle d'attente qui bascule au départ). Alternative : 404 tant que la Course n'est pas démarrée, ce qui empêche d'afficher l'attente. Position par défaut : 200 (RG3). Le nom d'une Course non démarrée n'est pas confidentiel (les coureurs la voient dans les inscriptions), mais la page est accessible à qui connaît l'identifiant (UUID).
2. **Durée de course et dernière Boucle d'une Course `TERMINEE`** : aucune heure de fin n'est stockée (CLAUDE.md : tout est dérivé) et `Course.chronometrage` est vide pour `TERMINEE`, donc 6.1 n'affiche ni durée figée ni Boucle finale. Les afficher exige soit un champ `termineeLe` (migration et changement de domaine), soit une dérivation à partir de `demarreeLe` et des Passages. Position par défaut : rien d'affiché en 6.1, à trancher avant 6.3.
3. **Adresse du suivi** : sans liste publique avant 6.4, l'adresse n'est trouvable que par le lien de la fiche d'administration ou en copiant l'URL. Position par défaut : lien sur la fiche (RG12, quelques lignes de front) ; alternative : ne rien ajouter et passer l'adresse à la main jusqu'à 6.4.
4. **Charge et abus** : endpoint public, sans authentification, appelé toutes les 3 s par chaque écran ouvert et par n'importe quel client. Le coût est d'une lecture de Course. Position par défaut : aucune limitation de débit en 6.1 (le blocage de connexion ne s'applique pas), `no-store` ; à reconsidérer (limite au reverse proxy, cache de 1 s côté API) si l'usage réel le justifie. À valider.
5. **Polling arrêté sur `TERMINEE`** : l'écran cesse d'interroger le serveur une fois la Course terminée. En 6.2, vainqueurs et abandons arriveront avec le même statut ; si une correction ultérieure devait modifier l'affichage après la fin, il faudra poursuivre le polling. Position par défaut : arrêt en 6.1, à revoir en 6.2.
6. **En-tête de l'application** : `docs/design.md` §11 prévoit un écran de suivi « sans en-tête de session » ; 6.1 conserve l'en-tête existant (menu visiteur ou compte) et reporte le masquage au mode projection (6.3). Position par défaut : reporté à 6.3.
7. **Intervalle de polling** : 3 s retenu dans la fourchette 2-3 s de la roadmap (même valeur que le pilotage). Position par défaut : 3 s.
