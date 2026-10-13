# Increment 4.5b : Dossard imprimable (admin)

Un coureur a oublié d'imprimer son dossard : depuis la **liste des inscrits de la fiche d'une Course** (`/administration/courses/:id`, section « Inscrits » de 3.5), l'admin ouvre un écran **« Dossard »** qui montre, en grand, le numéro de dossard, le pseudo du coureur, le nom de la Course et le **QR code** de son Inscription. L'écran est **prêt à imprimer** (feuille de style d'impression, A4 ou A5) et **lisible sur un téléphone** (le coureur peut le photographier ou le présenter tel quel). Le jeton QR n'étant jamais dans la liste des inscrits, un **nouvel endpoint dédié** `GET /api/administration/courses/{id}/inscriptions/{inscriptionId}/dossard` le renvoie **à la demande**, aux seuls `ADMIN` et `ADMIN_MASTER`. Le QR est dessiné côté front par le composant existant (`app-qr-code`, 3.3). Aucune régénération de jeton, aucune migration, aucune variable d'environnement.

Numérotation : RG et CA repartent à 1 dans ce fichier. « 3.3 RGn » désigne la spec 3.3, etc.

## Ce qui est déjà livré, donc non respécifié

Vérifié dans le code : `Inscription` (id, courseId, compteId, dossard, `JetonQr` à `toString()` masqué, statut) ; `DepotInscriptions` (`parCourse`, `parIdEtCompte`, `parCourseEtJeton`...) ; `ListerInscritsCourse` (pseudos en une recherche via le port `AnnuairePseudos`, « Compte inconnu » + un WARN sans pseudo, partagé avec le pilotage) ; `FicheCourseController` (`/api/administration/courses/{id}`, sous-chemins `/inscriptions`, `/pilotage`) ; règle de sécurité `/api/administration/**` réservée à `ADMIN` et `ADMIN_MASTER` ; `IdentifiantCourse` (non UUID = 404) ; exceptions `CourseIntrouvableException` (`COURSE_INTROUVABLE`) et `InscriptionIntrouvableException` (`INSCRIPTION_INTROUVABLE`) ; `GestionnaireErreursCourses.probleme` ; composant front `inscrits-course` (`fiche-inscrits-*`), composant `app-qr-code` (`frontend/src/app/inscriptions/qr-code/`, SVG local, `uqr`, correction d'erreur M, zone de silence 4 modules, fond blanc) ; charte `docs/design.md` §10 (QR noir sur blanc, 200 px minimum) et §11 ; helpers E2E (`preparerCourseDeScan`, `lireJetonQrEnBase`, décodage de QR par capture d'écran de 3.3, `placerStatutInscriptionEnBase`, `placerStatutCourseEnBase`).

Le **delta** : un cas d'usage `LireDossard`, une méthode de port `DepotInscriptions.parIdEtCourse`, un DTO `DossardReponse`, un `GET`, un écran, un lien par ligne de la liste des inscrits, une feuille de style d'impression, la charte.

Hypothèse : 1.1 à 4.5 mergées.

Taille estimée (production hors tests) : back ~90 lignes (application ~40, port + adaptateur ~10, exposition ~40), front ~170 lignes (écran, service, route, lien, styles d'impression). Total ~260 : sous la cible, pas de découpage. **Aucune migration, aucune variable d'environnement** ; `.env.example`, `docker-compose*.yml`, `docs/deploiement.md`, `scripts/donnees-demo.sh` inchangés.

## 1. Périmètre

**Inclus**
- `GET /api/administration/courses/{id}/inscriptions/{inscriptionId}/dossard` : numéro, pseudo, nom de la Course, jeton QR, statuts (RG1 à RG9).
- Écran `/administration/courses/:id/inscriptions/:inscriptionId/dossard` : dossard, QR, impression, vue téléphone (RG12 à RG17).
- Lien « Dossard » sur chaque ligne de la section « Inscrits » de la fiche (RG14).
- Mise à jour de `docs/design.md` §11 et `docs/design-apercu.html` (RG16).

**Exclu**
- Régénération, révocation ou rotation du jeton : aucune (RG6, point ouvert 3).
- Jeton dans la liste des inscrits, le pilotage, la fiche ou n'importe quelle autre réponse existante : exclu (RG10).
- Accès du `BENEVOLE` (même affecté) ou du `COUREUR` à ce dossard : exclu. Le coureur garde « Mes inscriptions » (3.3).
- Impression groupée de tous les dossards d'une Course, export PDF ou envoi par courriel généré par le serveur : hors besoin (point ouvert 9).
- Logo, date de la Course, boucle, distance : non imprimés (point ouvert 8).
- Lien depuis l'écran de pilotage : possible plus tard (point ouvert 7).
- Mise en cache hors ligne (PWA, 5.2) : le jeton ne doit pas y entrer (point ouvert 10).
- Scan et refus de scan : 4.4a, 4.5, 4.6 (inchangés).

## 2. Règles de gestion

**Lecture et contenu**
- **RG1** : lecture à la demande. `GET .../inscriptions/{inscriptionId}/dossard` renvoie le dossard de **l'Inscription `inscriptionId` appartenant à la Course `id`**. Lecture seule : aucune écriture, aucun effet de bord, calcul à chaque appel. Le jeton n'est lu qu'ici (et par `GET /api/coureur/inscriptions`, 3.3, pour son propriétaire).
- **RG2** : rôle. `ADMIN` et `ADMIN_MASTER` (règle existante `/api/administration/**`, aucune modification de `SecuriteConfiguration`). `BENEVOLE` (même affecté à la Course) et `COUREUR` : 403 `ACCES_REFUSE`. Anonyme : 401 `NON_AUTHENTIFIE`. Pas de CSRF (`GET`). Les rôles sont contrôlés côté serveur ; le cas d'usage ne les connaît pas. Un admin a accès aux dossards de **toutes** les Courses, y compris créées par un autre admin.
- **RG3** : contenu de `DossardReponse` : `inscriptionId`, `courseId`, `courseNom`, `courseStatut`, `dossard`, `pseudo`, `statut`, `jetonQr` (§4). Jamais : identifiant du Compte, rôle, empreinte, autres Inscriptions, bénévoles. `jetonQr` est celui stocké à la création de l'Inscription (43 caractères base64url, 3.1 RG7), **strictement identique** à celui renvoyé au coureur par `GET /api/coureur/inscriptions` : le QR de l'admin et celui du coureur sont interchangeables.
- **RG4** : statuts de Course. Fonctionne pour `EN_PREPARATION` (cas nominal : impression avant le départ), `EN_COURS` (le coureur a oublié son dossard au départ ou en cours de route) et `TERMINEE`. Aucune règle de statut de Course ne refuse la lecture.
- **RG5** : statuts d'Inscription. Fonctionne pour `EN_COURSE`, `ABANDON` et `VAINQUEUR` (le dossard n'est pas un droit de passer : le serveur refuse le scan d'un coureur qui n'est plus `EN_COURSE`, 4.4a). `statut` est renvoyé pour que l'écran prévienne l'admin (RG13).
- **RG6** : pas de régénération. Lire le dossard ne modifie ni ne renouvelle le jeton ; deux lectures renvoient la même valeur ; un dossard déjà imprimé reste valable. Aucun endpoint de régénération n'existe.
- **RG7** : pseudo. Obtenu par le port `AnnuairePseudos` (contextes séparés), via la **même** logique que `ListerInscritsCourse` (une seule implémentation, pas de duplication) : pseudo tel que saisi ; « Compte inconnu » + un WARN sans pseudo ni identifiant de Compte si le Compte n'est pas résolu ; « Coureur anonyme » pour un Compte anonymisé (3.6), sans règle propre : l'écran l'affiche tel quel.
- **RG8** : introuvable. Identifiant de Course inconnu, non UUID ou Course supprimée : 404 `COURSE_INTROUVABLE`. Identifiant d'Inscription inconnu, non UUID, ou **appartenant à une autre Course** : 404 `INSCRIPTION_INTROUVABLE` (l'existence d'une Inscription d'une autre Course n'est pas révélée). Ordre : sécurité, puis Course, puis Inscription.

**Secret : cache, journal, exposition**
- **RG9** : non mis en cache. La réponse porte `Cache-Control` contenant `no-store` (défaut de Spring Security, à vérifier ; aucun cache dans Caddy pour `/api`). Le jeton n'est jamais placé dans une URL, un en-tête, un `ProblemDetail` (404, 403, 401 compris).
- **RG10** : jeton jamais dans la liste. `GET /api/administration/courses/{id}/inscriptions` (3.5), `.../pilotage`, `GET /api/administration/courses/{id}`, `/api/coureur/courses`, `POST .../inscriptions` (201), `/api/benevole/**` et les routes publiques ne contiennent **aucun** `jetonQr` (inchangé). Dans la fiche, le jeton n'entre dans le navigateur que lorsque l'admin ouvre l'écran Dossard. 3.3 RG3 (« le jeton apparaît dans `MonInscriptionReponse` et nulle part ailleurs ») est **étendu** : `MonInscriptionReponse` et `DossardReponse` seulement.
- **RG11** : journal. Une seule ligne INFO par lecture réussie : `Dossard consulté (course <courseId>, dossard <n>)`. Ni jeton, ni pseudo, ni identifiant de Compte, ni nom de Course, ni identité de l'admin. Les refus (401, 403, 404) n'ajoutent aucune ligne INFO ou supérieure. `toString()` de `DossardReponse` et du résultat du cas d'usage : `Dossard[inscription=<id>]` (ni jeton ni pseudo). Le jeton n'apparaît dans aucune exception ni trace (`JetonQr.toString()` masqué).

**Architecture**
- **RG12** : `LireDossard` dans `courses.application` (`@Transactional(readOnly = true)`, ports `DepotCourses`, `DepotInscriptions.parIdEtCourse(inscriptionId, courseId)` (nouveau), `AnnuairePseudos` ; retourne des objets du domaine, aucun DTO ni dépendance à `comptes`) ; `DossardReponse` et le `GET` dans `courses.exposition`, sans logique métier (lecture des identifiants par `IdentifiantCourse`, appel, mapping). Règles ArchUnit inchangées, aucune exception nouvelle. Aucune migration : `databasechangelog` garde `0002` à `0007` (et les changesets existants), `ddl-auto=validate`.

**Front**
- **RG13** : écran. Titre « Dossard » ; bloc imprimable `dossard-feuille` : `dossard-numero` (numéro seul, très grand), `dossard-pseudo`, `dossard-course-nom`, `dossard-qr` (QR de `jetonQr`, `app-qr-code`, libellé d'accessibilité « QR code du dossard 12 », jamais le jeton). Hors de la feuille (non imprimés) : lien `dossard-lien-retour` « Retour à la Course », bouton `dossard-bouton-imprimer` « Imprimer le dossard » (appelle l'impression du navigateur), et, si `statut` ≠ `EN_COURSE` ou `courseStatut` = `TERMINEE`, un message `dossard-avertissement` (`.message--avertissement`) : « Ce coureur est en abandon : son QR code sera refusé au scan. » / « Ce coureur est vainqueur : son QR code sera refusé au scan. » / « Cette course est terminée : le QR code ne servira plus. ». Pseudo, nom de Course : texte, jamais HTML ; mots longs coupés.
- **RG14** : accès. Dans la section « Inscrits » de la fiche, chaque `fiche-inscrits-ligne` reçoit un lien `fiche-inscrit-lien-dossard` « Dossard » (libellé accessible « Dossard du coureur <pseudo> », 44 px) vers `/administration/courses/:id/inscriptions/:inscriptionId/dossard`, pour tous les statuts de Course et d'Inscription. La liste ne charge ni n'affiche aucun jeton ni QR. Route lazy, garde d'administration existante (rôle `ADMIN`/`ADMIN_MASTER` ; anonyme : `/connexion?retour=<url encodée>` ; autre rôle : `/acces-refuse` ; le contrôle réel est serveur). Titre de page « Dossard - Backyard Ultra Tracker ». Un identifiant d'Inscription dans l'URL n'est pas un secret.
- **RG15** : jeton jamais visible. Le jeton n'est ni affiché en texte, ni copiable par un bouton, ni présent dans le DOM (contenu, `alt`, `title`, `aria-label`, `data-*`, `value`), ni dans l'URL, ni dans `localStorage`/`sessionStorage`/état de navigation du routeur/`history.state`. Il vit uniquement dans la mémoire du composant (signal) et dans le dessin du QR (SVG de tracés, sans texte). Il est abandonné à la sortie de l'écran. Le QR encode **exactement** `jetonQr` (3.3 RG5). Échec de dessin : `dossard-erreur-qr` « Impossible d'afficher le QR code. », le numéro et le pseudo restent affichés, aucun repli en texte du jeton. QR généré localement : aucun appel réseau tiers.
- **RG16** : impression et affichage téléphone (charte `docs/design.md` §11 « Dossard », recommandations à confirmer ; `docs/design-apercu.html` complété).
  - À l'écran : mobile d'abord, aucun défilement horizontal à 320 px (texte à 200 % compris) ; le QR fait 240 px quand la place le permet, jamais moins de 200 px, fond blanc plein cadre, zone de silence conservée (§10) ; numéro en très grand gras (`--taille-titre` ou plus), chiffres tabulaires ; bouton « Imprimer le dossard » en `.bouton`, 44 px au moins. La feuille tient dans l'écran d'un téléphone en portrait sans défiler pour voir numéro et QR (à 375 × 667, numéro et QR entièrement visibles après l'en-tête).
  - À l'impression (`@media print`, une seule feuille) : sont **masqués** l'en-tête du site, le pied, le fil d'Ariane, les liens, les boutons, `dossard-avertissement` ; reste uniquement `dossard-feuille`, noir sur blanc sans fond ni ombre (`print-color-adjust: exact` pour le QR), centrée, sans coupure de page à l'intérieur (`break-inside: avoid`). Taille de page **non imposée** (l'utilisateur choisit A4 ou A5 dans la boîte d'impression) : `@page { margin: 10mm }`, la feuille mesure au plus 128 mm de large (tient en A5 portrait, 148 mm moins marges) et 190 mm de haut ; le QR fait **au moins 70 mm** de côté, le numéro au moins 60 pt. Sur A4 la feuille reste au haut de la page, sans mise à l'échelle surprenante (tient en une page). Aucune URL imprimée (pas de `a::after`), aucun jeton imprimé en texte.
  - Couleurs : aucune couleur en dur hors `variables.css` ; l'encre et le papier d'impression sont des jetons dédiés (point ouvert 11).
- **RG17** : états et erreurs de l'écran. « Chargement du dossard… » pendant la lecture. 404 `COURSE_INTROUVABLE` : `dossard-erreur` « Cette course n'existe plus. » ; 404 `INSCRIPTION_INTROUVABLE` : `dossard-erreur` « Cette inscription n'existe plus (le coureur s'est peut-être désinscrit). » ; chacun avec le lien de retour (vers la liste des Courses pour le premier, vers la fiche pour le second). 5xx ou réseau : `dossard-erreur` « Impossible de charger le dossard. Réessayez plus tard. » et bouton `dossard-bouton-reessayer` « Réessayer ». 401 : `/connexion?retour=...` ; 403 `ACCES_REFUSE` : `/acces-refuse` (via `RefusAccesService`). Aucun QR ni numéro tant que la réponse n'est pas arrivée ou en cas d'erreur.
- **RG18** : non-régression. 1.1 à 4.5 inchangés ; la section « Inscrits » reste identique hors le lien ajouté (`fiche-inscrit-dossard` reste la cellule du numéro) ; `POST`, `PUT`, `PATCH`, `DELETE` sur `.../dossard` : 404 ou 405, jamais 2xx ; aucune Inscription modifiée par un appel. La désinscription (3.4) d'un coureur dont le dossard est ouvert donne 404 `INSCRIPTION_INTROUVABLE` à la lecture suivante.

## 3. Cas limites

- **Course `EN_PREPARATION`, `EN_COURS`, `TERMINEE`** : 200 dans les trois cas (RG4) ; avertissement « Cette course est terminée… » pour `TERMINEE`.
- **Inscription `ABANDON` ou `VAINQUEUR`** : 200, avertissement à l'écran, absent de la feuille imprimée (RG5, RG16). Réintégration (4.x) : l'Inscription redevient `EN_COURSE`, le même dossard et le même QR servent de nouveau, sans nouvelle lecture particulière.
- **Inscription sans aucun Passage** : état normal ; les Passages ne sont ni lus ni affichés.
- **Passage `SCAN` ou `CORRECTION`, boucle courante, instants de bascule de Boucle, dernière boucle, vainqueurs partagés, course complète (toutes boucles)** : sans effet sur la lecture du dossard ; seul le statut d'Inscription est renvoyé.
- **Course complète (inscriptions)** : sans effet.
- **Plusieurs Courses en parallèle** : dossards indépendants par Course (deux « Dossard 1 » possibles) ; une Inscription d'une autre Course est un 404 `INSCRIPTION_INTROUVABLE` (RG8).
- **Bénévole affecté à la Course** : 403 (RG2) ; **non affecté** : 403 ; **rôle insuffisant** : `COUREUR` 403 ; anonyme 401.
- **Coureur anonymisé (3.6)** : seules les Inscriptions des Courses `EN_COURS`/`TERMINEE` subsistent ; l'écran s'ouvre avec le pseudo « Coureur anonyme » (RG7) ; il n'y a plus personne à qui remettre le dossard, aucune règle supplémentaire (point ouvert 6).
- **Compte introuvable** : pseudo « Compte inconnu », un WARN, 200 (RG7).
- **Désinscription pendant que l'écran est ouvert** : le dossard déjà affiché reste (lecture unique) ; au rechargement, 404 (RG18). **Course supprimée** : 404 `COURSE_INTROUVABLE`.
- **Rechargement (F5) de l'écran** : nouvel appel, même QR (RG6).
- **Jeton stable après redémarrage** : stocké en base, même valeur.
- **Impression** : l'utilisateur annule ou choisit « Enregistrer au format PDF » : sans effet côté serveur. Papier A5 ou A4 : une page (RG16). Navigateur qui n'applique pas `@page` : la feuille reste correcte, les marges sont celles du navigateur.
- **Téléphone** : le coureur photographie ou présente l'écran de l'admin ; le QR est identique à celui de « Mes inscriptions ».
- **Session expirée** : au rechargement, 401 puis `/connexion?retour=...`.
- **Mot de passe** : aucun n'est manipulé.

## 4. Contrat d'API

Toutes les erreurs sont des `ProblemDetail` (`application/problem+json`) : `{ "type": "about:blank", "title", "status", "detail", "instance", "code" }`, formats de 1.1, 1.2 RG19 et `GestionnaireErreursCourses.probleme`.

### GET /api/administration/courses/{id}/inscriptions/{inscriptionId}/dossard (nouveau)
- Rôles : `ADMIN`, `ADMIN_MASTER`. Aucun corps, aucun paramètre de requête, pas de CSRF.
- Paramètres de chemin : `id` (uuid de la Course), `inscriptionId` (uuid de l'Inscription).
- **200** `application/json`, en-tête `Cache-Control` contenant `no-store` (RG9) : `DossardReponse`

| Champ | Type | Contenu |
|---|---|---|
| `inscriptionId` | uuid | identifiant de l'Inscription |
| `courseId` | uuid | identifiant de la Course |
| `courseNom` | string | nom de la Course |
| `courseStatut` | `"EN_PREPARATION"` \| `"EN_COURS"` \| `"TERMINEE"` | statut de la Course |
| `dossard` | integer ≥ 1 | dossard, unique dans la Course |
| `pseudo` | string | pseudo du coureur, « Compte inconnu » ou « Coureur anonyme » (RG7) |
| `statut` | `"EN_COURSE"` \| `"ABANDON"` \| `"VAINQUEUR"` | statut de l'Inscription |
| `jetonQr` | string, 43 caractères `[A-Za-z0-9_-]`, jamais `null` | jeton opaque, **uniquement pour dessiner le QR ; jamais affiché, copié, stocké** (RG15) |

Exactement ces 8 champs. `toString()` : `Dossard[inscription=<id>]`.

Exemple : `{ "inscriptionId": "…", "courseId": "…", "courseNom": "Backyard du jour", "courseStatut": "EN_PREPARATION", "dossard": 2, "pseudo": "Sophie", "statut": "EN_COURSE", "jetonQr": "<43 caractères>" }`.

- **401** `NON_AUTHENTIFIE` (anonyme). **403** `ACCES_REFUSE` (`BENEVOLE`, `COUREUR`).
- **404** `COURSE_INTROUVABLE` (`"La course est introuvable."`) ; **404** `INSCRIPTION_INTROUVABLE` (`"L'inscription est introuvable."`), `title` « Introuvable » dans les deux cas (RG8).
- 400 et 409 : non applicables (aucun corps ; aucun refus métier de lecture, RG4 et RG5). 5xx : `ProblemDetail` générique de 1.x, sans jeton ni pseudo.

### Autres chemins
- `POST`, `PUT`, `PATCH`, `DELETE` sur `.../dossard` : 404 ou 405 (RG18). Aucun endpoint de régénération.
- Inchangés et **sans `jetonQr`** : `GET /api/administration/courses/{id}`, `.../inscriptions`, `.../pilotage`, `GET /api/coureur/courses`, `POST /api/coureur/courses/{id}/inscriptions`, `/api/benevole/**`, routes publiques. `GET /api/coureur/inscriptions` (3.3) inchangé.

### Port interne (non exposé)
`DepotInscriptions.parIdEtCourse(UUID inscriptionId, UUID courseId) : Optional<Inscription>` : vide si l'Inscription est inconnue ou d'une autre Course.

## 5. Écrans

### Dossard (`/administration/courses/:id/inscriptions/:inscriptionId/dossard`, nouveau)
- **Accès** : `ADMIN`, `ADMIN_MASTER` (RG14). Code indicatif : `frontend/src/app/administration/courses/dossard/`, méthode `lireDossard` du service API d'administration.
- **Affiché** : « Chargement du dossard… » ; puis `dossard-feuille` avec `dossard-numero` (« 2 »), `dossard-pseudo`, `dossard-course-nom`, `dossard-qr` ; hors feuille : `dossard-lien-retour`, `dossard-bouton-imprimer`, `dossard-avertissement` éventuel. Aucun jeton en texte (RG15).
- **Actions** : « Imprimer le dossard » (boîte d'impression du navigateur), « Retour à la Course », « Réessayer » en cas d'erreur de chargement.
- **Messages d'erreur** : ceux de RG17 ; « Impossible d'afficher le QR code. » (RG15) ; avertissements de RG13.
- **À l'impression** : feuille seule (RG16).

### Fiche d'une Course (`/administration/courses/:id`, modifié)
Section « Inscrits » : lien `fiche-inscrit-lien-dossard` « Dossard » par ligne (RG14). Le reste est inchangé.

### Écrans existants
Inchangés.

## 6. Critères d'acceptation

Valeurs de référence : comptes de 3.1 (`Patron` `ADMIN_MASTER`, `Nadia` `ADMIN`, `Léo` `BENEVOLE` affecté à X, `Marc` `BENEVOLE` non affecté, `Alice`, `Bruno`, `Chloé` coureurs `un-mot-de-passe-12`). Course X (`Backyard des Crêtes`, `EN_PREPARATION`) : `Alice` dossard 1 (jeton `JA`), `Bruno` dossard 2, `Chloé` dossard 3 ; Course Y (`Backyard express`) : `Alice` dossard 1 (jeton `JY`).

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné des dépôts en mémoire (X, Y, Inscriptions ci-dessus avec jetons connus) et un annuaire factice, quand `LireDossard` est exécuté pour (X, Inscription d'`Alice`), alors le résultat contient la Course X, dossard 1, statut `EN_COURSE`, pseudo « Alice » et le jeton `JA` tel que stocké ; deux appels renvoient le même jeton, aucune Inscription n'est modifiée ; pour une Inscription `ABANDON`, `VAINQUEUR`, et X `EN_COURS` puis `TERMINEE` : résultat renvoyé sans exception (statuts conservés) ; l'annuaire n'est appelé qu'**une fois** avec un seul identifiant de Compte (RG1, RG3, RG4, RG5, RG6, RG7) | unitaire |
| CA2 | Étant donné les mêmes dépôts, quand `LireDossard` est exécuté pour : une Course inconnue, l'Inscription d'`Alice` dans Y mais demandée dans X, une Inscription inconnue, alors `CourseIntrouvableException` pour la première, `InscriptionIntrouvableException` pour les deux autres ; l'Inscription d'Y n'est pas révélée ; `toString()` des exceptions sans identifiant (RG8) | unitaire |
| CA3 | Étant donné un annuaire qui ne connaît pas le Compte de `Chloé` et un autre qui renvoie « Coureur anonyme » pour `Bruno`, quand `LireDossard` est exécuté, alors pseudo « Compte inconnu » (un WARN sans pseudo ni identifiant de Compte) et « Coureur anonyme » respectivement ; `toString()` du résultat vaut `Dossard[inscription=<id>]` : ni le jeton, ni le pseudo, ni le nom de la Course ; la ligne INFO « Dossard consulté (course <id>, dossard <n>) » ne contient ni jeton, ni pseudo, ni identifiant de Compte ; `./mvnw test` : règles ArchUnit sans exception supplémentaire (`LireDossard` sans DTO ni dépendance à `comptes`) (RG7, RG11, RG12) | unitaire |
| CA4 | Étant donné `Patron` (puis `Nadia`) connecté et les Inscriptions de référence (Testcontainers, migrations réelles), quand `GET /api/administration/courses/<X>/inscriptions/<Inscription d'Alice>/dossard`, alors 200 `application/json`, exactement 8 champs : `inscriptionId` et `courseId` égaux à ceux de la base, `courseNom` « Backyard des Crêtes », `courseStatut` `EN_PREPARATION`, `dossard` 1, `pseudo` « Alice », `statut` `EN_COURSE`, `jetonQr` de 43 caractères **égal à la colonne `jeton_qr`** de la ligne ; il est égal au `jetonQr` renvoyé à `Alice` par `GET /api/coureur/inscriptions` pour X ; un second appel renvoie la même valeur ; `Cache-Control` contient `no-store` ; aucun `compteId`, UUID de Compte, empreinte ; la ligne d'Inscription est inchangée (RG1, RG3, RG6, RG9) | intégration |
| CA5 | Étant donné X placée par SQL à `EN_COURS` puis `TERMINEE`, et l'Inscription d'`Alice` placée à `ABANDON` puis `VAINQUEUR`, quand le `GET` est envoyé après chaque changement, alors 200 à chaque fois avec `courseStatut`/`statut` correspondants et le même `jetonQr` ; avec un Compte anonymisé (SQL ou via la suppression 3.6, X `EN_COURS`) : 200 avec `pseudo` « Coureur anonyme » ; avec un Compte supprimé par SQL : « Compte inconnu » et un seul WARN (RG4, RG5, RG7) | intégration |
| CA6 | Quand `Nadia` fait `GET` pour : une Course aléatoire, une Course `pas-un-uuid`, une Course supprimée par `Patron` (204), une Inscription aléatoire de X, `pas-un-uuid` comme Inscription, l'Inscription d'`Alice` dans Y demandée avec X, alors 404 `COURSE_INTROUVABLE` pour les trois premiers et 404 `INSCRIPTION_INTROUVABLE` pour les trois suivants, `title` « Introuvable », aucun jeton ni pseudo dans les corps ; après désinscription de `Bruno` (3.4), le `GET` de son Inscription donne 404 (RG8, RG18) | intégration |
| CA7 | Quand un anonyme envoie le `GET`, alors 401 `NON_AUTHENTIFIE` ; `Léo` (affecté à X), `Marc` et `Alice` : 403 `ACCES_REFUSE` (`application/problem+json`), corps sans jeton ni pseudo ; 403 même pour une Inscription ou une Course inexistante (la sécurité passe avant la recherche) ; `Alice` et `Bruno` ne voient jamais le dossard d'un autre ; `Patron` et `Nadia` : 200 identiques ; `GET` sans `X-XSRF-TOKEN` : 200 ; `POST`, `PUT`, `PATCH`, `DELETE` (CSRF valide) par `Nadia` sur `.../dossard` : 404 ou 405, base inchangée (RG2, RG18) | intégration |
| CA8 | Étant donné X avec ses Inscriptions et X démarrée par SQL, quand `Nadia` lit `GET /api/administration/courses/<X>`, `.../inscriptions`, `.../pilotage`, `GET /api/administration/courses`, que `Alice` lit `GET /api/coureur/courses` et fait un `POST` d'inscription (201), que `Léo` lit `GET /api/benevole/courses`, et que les routes publiques (suivi, logo, santé) sont lues, alors aucune des réponses ne contient un `jeton_qr` lu en base ; seuls `GET /api/coureur/inscriptions` (propriétaire) et le `GET .../dossard` (admin) le contiennent (RG10) | intégration |
| CA9 | Étant donné un journal capturé (tous niveaux), quand `Nadia` lit un dossard (200) puis subit 404, 401 et 403, alors le `jeton_qr`, le pseudo, l'UUID du Compte et le nom de la Course n'apparaissent dans aucune ligne ; la lecture réussie produit exactement une ligne INFO « Dossard consulté (course <id>, dossard 1) » ; les refus n'ajoutent aucune ligne INFO ou supérieure ; aucun `ProblemDetail` ne contient le jeton (RG9, RG11) | intégration |
| CA10 | Étant donné une base migrée, quand l'application démarre, alors `databasechangelog` n'a aucun nouveau changeset et `ddl-auto=validate` passe ; `.env.example` et `docker-compose*.yml` sont inchangés ; les suites d'intégration de 1.1 à 4.5 passent sans modification de leurs attentes ; le `GET` avec 30 Inscriptions dans la Course n'exécute qu'une recherche d'Inscription et une de Compte (compteur d'instructions) (RG12, RG18) | intégration |
| CA11 | Étant donné `Admin <suffixe>` connecté, une Course A `<suffixe>` avec trois coureurs inscrits par l'API (dossards 1 à 3), quand il ouvre la fiche, alors chaque `fiche-inscrits-ligne` a un `fiche-inscrit-lien-dossard` « Dossard » et **le jeton d'aucun coureur n'est dans la page** (`page.content()` et attributs) ; au clic sur le lien du dossard 2, l'URL est `/administration/courses/<A>/inscriptions/<id>/dossard`, la requête observée est un `GET .../dossard` en 200, `dossard-numero` « 2 », `dossard-pseudo` du coureur 2, `dossard-course-nom` « Course A <suffixe> », `dossard-qr` visible d'au moins 200 px (240 px sur bureau) sur fond blanc ; le QR capturé et décodé donne **exactement** le `jeton_qr` lu en base (43 caractères), identique au QR décodé de « Mes inscriptions » du coureur 2 ; le jeton n'est ni dans le texte, ni dans un attribut du DOM, ni dans l'URL, ni dans `localStorage`/`sessionStorage`/`history.state` ; F5 : même affichage et même QR ; « Retour à la Course » ramène à la fiche ; même parcours en `Patron` (RG1, RG3, RG6, RG13, RG14, RG15) | E2E |
| CA12 | Étant donné la Course A démarrée par l'API (`preparerCourseDeScan`, 60 min, `Léo` affecté), quand l'admin ouvre le dossard du coureur 1 et décode son QR, puis que `Léo` saisit ce texte décodé dans le champ de l'écran de scan de A, alors `scan-resultat-succes` « Dossard 1 » et `lirePassagesEnBase` renvoie `1:1:SCAN` : le QR du dossard imprimable sert bien de jeton de scan ; avec la caméra simulée de 4.5, la présentation du QR décodé donne le même succès (RG3, RG6) | E2E |
| CA13 | Étant donné l'écran Dossard ouvert, quand `page.emulateMedia({ media: 'print' })`, alors l'en-tête du site, `dossard-lien-retour`, `dossard-bouton-imprimer` et `dossard-avertissement` ne sont pas visibles, `dossard-feuille` est visible avec `dossard-numero`, `dossard-pseudo`, `dossard-course-nom` et `dossard-qr` d'au moins 265 px de côté (70 mm) ; `page.pdf` en **A4 puis en A5** produit exactement **1 page** chaque fois (comptage des objets `/Type /Page` du PDF) ; le jeton n'est pas dans le texte du PDF ni aucune URL ; le décodage du QR rendu en mode impression redonne le `jeton_qr` ; `emulateMedia` revenu à `screen` : tous les éléments redeviennent visibles ; clic sur `dossard-bouton-imprimer` : `window.print` appelé une fois (espion injecté par `addInitScript`) (RG16) | E2E |
| CA14 | Étant donné la Course A `EN_COURS` et l'Inscription du coureur 3 `ABANDON` (SQL), puis `VAINQUEUR`, puis la Course `TERMINEE`, quand l'admin ouvre son dossard, alors le dossard et le QR s'affichent (même jeton) et `dossard-avertissement` affiche respectivement « Ce coureur est en abandon : son QR code sera refusé au scan. », « Ce coureur est vainqueur : son QR code sera refusé au scan. », « Cette course est terminée : le QR code ne servira plus. » ; en `EN_COURSE` sur Course `EN_PREPARATION` : aucun avertissement ; pour un coureur dont le Compte est supprimé par `Alice` (3.6) pendant que A est `EN_COURS` : `dossard-pseudo` « Coureur anonyme » ; QR toujours affiché (RG4, RG5, RG7, RG13) | E2E |
| CA15 | Étant donné l'écran Dossard avec le `GET` intercepté, quand la réponse est 500, alors `dossard-erreur` « Impossible de charger le dossard. Réessayez plus tard. » et `dossard-bouton-reessayer` (qui recharge, 200 : le dossard s'affiche) ; 404 `COURSE_INTROUVABLE` : « Cette course n'existe plus. » ; 404 `INSCRIPTION_INTROUVABLE` : « Cette inscription n'existe plus (le coureur s'est peut-être désinscrit). » avec lien de retour ; 401 : `/connexion?retour=...` puis retour sur le dossard après connexion ; 403 `ACCES_REFUSE` : `/acces-refuse` ; jeton `""` intercepté : `dossard-erreur-qr` « Impossible d'afficher le QR code. », numéro et pseudo affichés, aucun jeton en texte ; l'URL du dossard ouverte par `Léo` (bénévole), `Alice` (coureur) : `/acces-refuse`, par un anonyme : `/connexion?retour=...` ; aucun QR ni numéro n'apparaît dans ces cas (RG2, RG14, RG15, RG17) | E2E |
| CA16 | Étant donné l'écran Dossard à 375 px puis 320 px (texte normal puis 200 %), alors aucun défilement horizontal, numéro et QR entièrement visibles sans défiler à 375 × 667 après l'en-tête, QR de 200 px au moins sur fond blanc, `dossard-bouton-imprimer` et `fiche-inscrit-lien-dossard` de 44 px au moins de haut, focus visible au clavier, un pseudo de 30 caractères sans espace reste dans la feuille ; les suites E2E de 1.x à 4.5 (dont les attentes de `fiche-inscrits-*` et de `donnees-demo.spec.ts`) passent sans modification d'attente (RG16, RG18) | E2E |

Répartition : unitaire 3 (CA1 à CA3), intégration 7 (CA4 à CA10), E2E 6 (CA11 à CA16). Total 16.

Couverture des RG : RG1 CA1/CA4, RG2 CA7/CA15, RG3 CA1/CA4/CA11/CA12, RG4 CA1/CA5/CA14, RG5 CA1/CA5/CA14, RG6 CA1/CA4/CA11/CA12, RG7 CA1/CA3/CA5/CA14, RG8 CA2/CA6, RG9 CA4/CA9, RG10 CA8, RG11 CA3/CA9, RG12 CA3/CA10, RG13 CA11/CA14, RG14 CA11/CA15/CA16, RG15 CA11/CA15, RG16 CA13/CA16, RG17 CA15, RG18 CA6/CA7/CA10/CA16. Écran « Dossard » : CA11 à CA16 ; fiche modifiée : CA11, CA16.

Notes pour les testeurs : réutiliser les helpers de 3.1 à 4.5 (création de Courses et coureurs par l'API, inscription par l'API, `preparerCourseDeScan`, `lireJetonQrEnBase`, décodage de QR par capture d'écran de l'élément `dossard-qr` avec la bibliothèque de lecture déjà en dépendance de `e2e/`, `aide-camera.ts`). Le jeton n'étant pas dans le DOM, seul le décodage de l'image le fournit. Pour CA13, `page.pdf` n'existe que sous Chromium (projet `chromium`). Aucune règle temporelle : `/api/test/horloge` inutile. Le test CA10 « une recherche » s'appuie sur un compteur d'instructions Hibernate ou un proxy de `DataSource`.

## 7. Tester à la main

Prérequis : `docker compose up -d --build` (services sains), http://localhost, `.env` avec `ADMIN_MASTER_PSEUDO=Patron`. `./scripts/donnees-demo.sh` (idempotent) a créé `Nadia` (`mot-de-passe-admin-1`), `Léo` (`mot-de-passe-benevole-1`), `Marc`, `Alice`, `Karim`, `Sophie`, `Tom` (`un-mot-de-passe-12`) et les Courses de démo, dont `Backyard du jour` (boucles de 1 min, Karim 1, Sophie 2, Tom 3, `Léo` affecté). Un téléphone ou un second navigateur sert à afficher/scanner un QR.

1. **Ouvrir un dossard** : en `Nadia`, « Administration », « Courses », fiche de `Backyard du jour`, section « Inscrits » : chaque ligne a un lien « Dossard ». Cliquer celui de `Sophie` : écran « Dossard » avec « 2 », « Sophie », « Backyard du jour » et un QR carré noir sur blanc d'au moins 200 px. Aucun jeton en texte ; Ctrl+A et « Inspecter » ne le montrent pas ; l'URL ne contient que les identifiants de Course et d'Inscription.
2. **Même QR que le coureur** : en `Sophie` (fenêtre privée), « Mes inscriptions » : son QR est identique à celui du dossard (avec la caméra d'un téléphone ou en comparant le texte lu au `jeton_qr` en base : `docker compose exec base psql -U backyard -d backyard -c "select i.dossard, i.jeton_qr from inscription i join course c on c.id = i.course_id where c.nom = 'Backyard du jour' order by 1"`). F5 sur le dossard : même QR.
3. **Impression** : sur l'écran Dossard, « Imprimer le dossard » (ou Ctrl+P) : l'aperçu ne montre que la feuille (numéro, pseudo, nom de la Course, QR), sans en-tête, bouton ni lien. Choisir A4 puis A5 : une seule page chaque fois, QR d'environ 7 cm. « Enregistrer au format PDF » : le PDF ne contient pas de texte de jeton.
4. **Sur téléphone** : ouvrir l'écran Dossard dans un navigateur à largeur de téléphone (outils de développement, mode mobile 375 px) : le numéro et le QR tiennent à l'écran, sans défilement horizontal.
5. **Le QR sert au scan** : en `Nadia`, démarrer `Backyard du jour` (fiche, « Démarrer la course », « Démarrer maintenant ») ; en `Léo`, « Scanner » sur `Backyard du jour`, « Activer la caméra » ; présenter à la webcam le dossard de `Sophie` (affiché sur l'écran du téléphone ou imprimé) : bloc vert « Passage enregistré », « Dossard 2 », « Sophie », « Boucle 1 » (à faire dans la première minute). Le représenter après 2 s : refus « déjà un passage ».
6. **Statuts** : le coureur est en abandon : `docker compose exec base psql -U backyard -d backyard -c "update inscription set statut = 'ABANDON' where dossard = 3 and course_id = (select id from course where nom = 'Backyard du jour')"` ; en `Nadia`, dossard de `Tom` : l'écran s'affiche avec « Ce coureur est en abandon : son QR code sera refusé au scan. » ; à l'impression (Ctrl+P), l'avertissement n'apparaît pas. Remettre `'EN_COURSE'`.
7. **Course non démarrée** : fiche d'une autre Course de démo avec inscrits (`Backyard express`, Karim 1) : le dossard s'ouvre de même.
8. **Rôles** : en `Léo` ou `Alice`, coller l'URL du dossard : page « Accès refusé ». Déconnecté : redirection vers la connexion (`?retour=...`), puis retour sur le dossard après connexion en `Nadia`. API : jar de cookies comme en 3.1 §7 ; en `Nadia`, `curl -si -b /tmp/jar http://localhost/api/administration/courses/<id>/inscriptions/<inscriptionId>/dossard` : 200, `Cache-Control` contenant `no-store`, 8 champs dont `jetonQr` ; en `Léo` : 403 ; sans session : 401 ; avec l'identifiant d'une Inscription d'une autre Course : 404.
9. **Pas de jeton ailleurs** : `curl -s -b /tmp/jar http://localhost/api/administration/courses/<id>/inscriptions` (en `Nadia`) : aucun `jetonQr`.
10. **Désinscription** : en `Alice`, s'inscrire à `Backyard mini`, noter son dossard côté admin, se désinscrire ; l'écran Dossard rechargé : « Cette inscription n'existe plus ».
11. **Journal** : `docker compose logs api | grep -c "<un jeton lu en base>"` : 0 ; `docker compose logs api | grep "Dossard consulté"` : une ligne par ouverture, avec l'identifiant de Course et le numéro seulement, sans pseudo. `select id from databasechangelog` : aucun nouveau changeset.

## 8. Points ouverts

Bloquants : aucun. Position par défaut appliquée dans la spec ; à confirmer.

1. **Rôles (RG2)**. Retenu : `ADMIN` et `ADMIN_MASTER` (position proposée). `BENEVOLE` exclu même affecté, `COUREUR` exclu. Alternative : autoriser le bénévole affecté (poste d'accueil le jour J) ; cela expose le jeton à un rôle de plus.
2. **Endpoint dédié (RG1)**. Retenu : `GET .../inscriptions/{inscriptionId}/dossard`, sous la Course, ressource en lecture sans effet de bord. Rejeté : mettre `jetonQr` dans la liste des inscrits (le jeton atterrirait dans le DOM et les réponses de chaque ouverture de fiche).
3. **Pas de régénération (RG6)**. Un dossard imprimé, photographié ou perdu reste valable jusqu'à la désinscription (déjà le cas en 3.3). Une régénération (si un dossard fuite) serait un incrément à part, avec invalidation de l'ancien QR.
4. **Journal d'audit (RG11)**. Retenu : une ligne INFO « Dossard consulté (course, dossard) », sans identité de l'admin (aucun pseudo ni identifiant de Compte au journal, règle de 3.5). Alternatives : ne rien journaliser (comme les autres lectures), ou ajouter l'identifiant du Compte de l'admin pour tracer qui lit les jetons. À confirmer le niveau de traçabilité souhaité.
5. **Course `TERMINEE` et Inscription `ABANDON`/`VAINQUEUR` (RG4, RG5)**. Retenu : toujours lisibles, avec avertissement non imprimé. Alternative : 409 (refuser le dossard d'un coureur qui ne peut plus scanner). Le choix « lisible » évite de dupliquer la règle de scan et sert à réimprimer après une réintégration.
6. **Compte anonymisé (RG7)**. Retenu : aucune règle propre, affichage « Coureur anonyme ». Alternative : masquer le lien dans la liste ou répondre 409 puisque plus personne ne peut utiliser ce dossard.
7. **Autres points d'entrée**. Retenu : seulement la liste des inscrits de la fiche (décision utilisateur). Le pilotage (4.7) pourrait aussi proposer le lien ; non prévu ici.
8. **Contenu de la feuille (RG13, RG16)**. Retenu : numéro, pseudo, nom de la Course, QR (décision utilisateur). Ni date, ni logo, ni jeton en texte. Ajouter le logo et la date est possible sans changement d'API majeur (`courseDate`, `logoUrl`).
9. **Impression groupée**. Aucune ; l'admin imprime un dossard à la fois. Une page « tous les dossards d'une Course » (plusieurs dossards par feuille A4) est un incrément possible si la demande apparaît.
10. **PWA (jalon 5)**. Le service worker de 5.x ne doit jamais mettre en cache `/api/administration/**` ni cet écran avec ses données (le jeton y entrerait dans un stockage de l'appareil). À reporter dans la spec de 5.2.
11. **Charte (RG16)**. Impression noir sur blanc : la règle « pas de noir ni de blanc purs » (design §10) a pour exception le QR ; l'encre et le papier d'impression sont proposés comme **deux jetons dédiés** ajoutés à `variables.css` et à la charte, à confirmer. Dimensions imprimées (QR ≥ 70 mm, feuille ≤ 128 × 190 mm, numéro ≥ 60 pt) : valeurs de départ à ajuster après un essai d'impression réel.
12. **Libellés et `data-testid`** : `dossard-*`, `fiche-inscrit-lien-dossard`, textes des avertissements et des erreurs : à confirmer.
13. **Mise à jour de 3.3 RG3** : « nulle part ailleurs » devient « seulement `MonInscriptionReponse` et `DossardReponse` » (RG10). Le texte de la spec 3.3 n'est pas modifié ; la nouvelle règle fait foi à partir de 4.5b.
