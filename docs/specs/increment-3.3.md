# Increment 3.3 : Mes inscriptions et QR code

Un compte `COUREUR` ouvre un nouvel écran `/coureur/inscriptions` « Mes inscriptions » qui liste **ses** Inscriptions, à toutes les Courses et quel que soit le statut : Course, dossard, statut, et un **QR code qui encode le jeton QR** de l'Inscription (opaque, aléatoire, distinct du dossard ; le dossard n'est pas dans le QR). Un nouvel endpoint `GET /api/coureur/inscriptions` fournit les données, jeton compris : c'est le **premier et seul endroit** où le jeton sort du serveur. Le jeton est renvoyé **uniquement pour que le front génère le QR** (RG6) : il n'est **jamais affiché en texte** au coureur (RG16). Aucune migration.

## Ce qui est déjà livré (3.1 et 3.2), donc non respécifié

Vérifié dans le code : `Inscription` (id, courseId, compteId, dossard, `JetonQr`, statut), `JetonQr` (32 octets `SecureRandom`, base64url sans remplissage, 43 caractères, `toString()` masqué), `GenerateurJetonQr` et son adaptateur, `InscriptionJpaEntity` avec colonne `jeton_qr` unique, changeset `0007-inscription` avec l'index `ix_inscription_compte (compte_id)`, port `DepotInscriptions.parCompte(compteId)` (déjà présent, non utilisé par une lecture de jeton), `ListerCourses.avecEmpreinteLogo`, `Course.ORDRE_DE_LISTE`, `InscriptionsCoureurController` (`/api/coureur/courses`), règle de sécurité `/api/coureur/**` réservée à `COUREUR`, garde `reserveAuxCoureurs`, lien d'en-tête `lien-espace-coureur`, route `/coureur` (`frontend/src/app/inscriptions/accueil-coureur/`). `InscriptionReponse` et `CourseOuverteReponse` ne contiennent aucun jeton et **restent ainsi**. Le **delta** de 3.3 : un cas d'usage de lecture, un DTO, un endpoint `GET`, un écran, une dépendance front de génération de QR.

Hypothèse de lecture : 1.1 à 3.2 mergées. Leurs règles sont référencées, pas recopiées.

Taille estimée (production hors tests) : back ~80 lignes (application ~30 : `ListerMesInscriptions` ; exposition ~50 : contrôleur `MesInscriptionsController` ou méthode ajoutée, DTO `MonInscriptionReponse`), front ~150 lignes (écran, service, route, lien d'en-tête, composant QR). Total ~230 : sous la cible, pas de découpage. **Aucune migration, aucune variable d'environnement.**

## 1. Périmètre

**Inclus**
- `GET /api/coureur/inscriptions` : Inscriptions du coureur connecté, avec les informations de leur Course et le jeton QR (destiné au seul dessin du QR). Rôle `COUREUR` seul (RG1 à RG4, RG7 à RG9).
- Cas d'usage `ListerMesInscriptions` (application), DTO `MonInscriptionReponse` (exposition).
- Écran `/coureur/inscriptions` « Mes inscriptions » : course, dossard, statut, QR code ; jeton jamais affiché en texte (RG13 à RG18).
- Lien d'en-tête `lien-mes-inscriptions` « Mes inscriptions », visible du seul `COUREUR`.
- Génération du QR côté front (RG6).

**Exclu**
- Désinscription (bouton, confirmation, libération du dossard et de la place) : **3.4**. L'écran de 3.3 n'a aucune action destructive.
- Vue admin des inscrits, places restantes : **3.5**. Suppression du compte et anonymisation : **3.6**, changement de mot de passe : écran « Mon compte » existant.
- Lecture du jeton par le bénévole, scan, saisie du jeton, caméra, refus de scan : **4.4, 4.5, 4.6**. Aucun endpoint de recherche d'une Inscription par jeton n'est créé ici.
- Motif et boucle d'abandon, boucle courante, distance, passages affichés : **jalon 4** (les colonnes n'existent pas ; l'écran n'affiche que le statut).
- Régénération ou révocation du jeton : hors besoin (point ouvert 4).
- Affichage du jeton en texte, bouton « Afficher/Masquer le jeton », bouton « Copier » : **décision utilisateur, supprimés** (RG16). Impact sur la roadmap 4.4 : point ouvert 5.
- Mode hors ligne de l'écran (QR disponible sans réseau) : **5.x / PWA** (point ouvert 3).
- Modification de `InscriptionReponse` (201 du `POST`) et de `CourseOuverteReponse` : inchangés, **sans jeton**.
- Aucun changement de `SecuriteConfiguration`, de `docker-compose*.yml`, de `.env.example`, de `docs/deploiement.md`, du schéma.

**Fichiers modifiés (indicatif)** : nouveaux `ListerMesInscriptions`, `MonInscriptionReponse`, contrôleur de lecture (ou méthode dans `InscriptionsCoureurController`), `frontend/src/app/inscriptions/mes-inscriptions/` (écran), composant ou directive de QR, `app.routes.ts`, `entete.html`, service API coureur, `frontend/package.json` (bibliothèque QR). Commentaire de `InscriptionReponse` (« pas avant 3.3 ») à corriger : le jeton n'est livré que par le DTO dédié.

## 2. Règles de gestion

**Données (domaine / application)**
- **RG1** : contenu de la liste. `GET /api/coureur/inscriptions` renvoie **toutes** les Inscriptions du Compte connecté (`DepotInscriptions.parCompte(compteId)`), quelle que soit la Course (`EN_PREPARATION`, `EN_COURS`, `TERMINEE`) et quel que soit le statut (`EN_COURSE`, `ABANDON`, `VAINQUEUR`). Une Course démarrée ou terminée reste visible. Aucune Inscription : `[]`.
- **RG2** : ordre. Les éléments sont triés selon `Course.ORDRE_DE_LISTE` de leur Course (date décroissante, nom, id). Une seule Inscription par (Course, Compte) : l'ordre est total et stable. Ordre défini dans le domaine (`Course.ORDRE_DE_LISTE`), jamais retrié à l'écran.
- **RG3** : jeton exposé par un seul endpoint, **à seule fin de générer le QR côté front**. `jetonQr` apparaît dans `MonInscriptionReponse` et nulle part ailleurs : ni dans `InscriptionReponse` (201 du `POST`), ni dans `CourseOuverteReponse`, ni dans un `ProblemDetail`, ni dans un journal, ni dans l'URL. Les `toString()` de `MonInscriptionReponse` et de `Inscription` ne montrent pas sa valeur (`JetonQr.toString()` est déjà masqué).
- **RG4** : jeton stable et authentique. La valeur renvoyée est exactement celle stockée à la création de l'Inscription (43 caractères base64url `[A-Za-z0-9_-]`, 3.1 RG7). Deux appels successifs renvoient la même valeur (le QR imprimé ou capturé reste valable). Jeton distinct entre Inscriptions, distinct du dossard, non calculable à partir de lui.
- **RG5** : contenu du QR. Le QR encode **exactement la chaîne `jetonQr`**, rien d'autre : pas de préfixe, pas d'URL, pas de dossard, pas de pseudo, pas de nom de Course, pas de nouvelle ligne. Un lecteur de QR quelconque affiche donc un texte de 43 caractères. Le dossard n'est jamais encodé (il se lit à côté, imprimé en clair, RG14).
- **RG6** : génération du QR côté front (choix, justification au §2 bis). Le back ne renvoie aucune image ; le front dessine le QR localement à partir de `jetonQr` (aucun appel réseau, aucun service tiers). C'est la **seule** utilisation du jeton côté front.
- **RG7** : isolation. L'identité vient de la session (principal : identifiant du Compte), jamais d'un paramètre, d'un en-tête ni d'un corps ; l'endpoint n'a aucun paramètre (un `?compteId=…` éventuel est ignoré). Un coureur ne reçoit jamais le jeton, le dossard ni l'existence d'une Inscription d'un autre Compte. Pas d'endpoint `GET /api/coureur/inscriptions/{id}` (aucune lecture par identifiant : pas de risque d'accès à l'Inscription d'autrui).

**Contrat et sécurité**
- **RG8** : rôle. `/api/coureur/**` : `COUREUR` seul (règle existante, 3.1 RG9) ; `ADMIN`, `ADMIN_MASTER`, `BENEVOLE` : 403 `ACCES_REFUSE` (même un bénévole affecté, même un admin) ; anonyme : 401 `NON_AUTHENTIFIE`. `GET` : pas de jeton CSRF requis. Le cas d'usage ne connaît pas les rôles.
- **RG9** : lecture seule, immédiate, non mise en cache. Aucune écriture, aucun effet de bord ; calculée à chaque appel. La réponse n'est jamais mise en cache (`Cache-Control` contenant `no-store`, valeur par défaut de Spring Security, à vérifier) : elle contient un secret.

**Persistance**
- **RG10** : aucune migration. Les colonnes (`jeton_qr`, `dossard`, `statut`, `course_id`, `compte_id`) et l'index `ix_inscription_compte` de 3.1 suffisent. `databasechangelog` garde exactement `0002` à `0007`. `ddl-auto=validate` inchangé. La suppression d'une Course (cascade, 3.1 RG12) retire l'Inscription de la liste.

**Architecture et journal**
- **RG11** : `ListerMesInscriptions` dans `courses.application` (transactionnel en lecture, `compteId` en paramètre, retourne des objets du domaine : pas de DTO, pas de dépendance à `comptes`) ; `MonInscriptionReponse` et l'endpoint dans `courses.exposition`, sans logique métier (mapping, `ListerCourses.avecEmpreinteLogo` pour `logoUrl`). Une règle n'existe qu'une fois : ordre = `Course.ORDRE_DE_LISTE`. Règles ArchUnit existantes inchangées, aucune nouvelle exception.
- **RG12** : journal. La lecture ne produit aucune ligne de niveau INFO ou supérieur ; ni jeton, ni pseudo, ni identifiant de Compte, ni nom de Course n'apparaissent dans les journaux. Les refus (401, 403) non plus.

**Front**
- **RG13** : accès et navigation. `/coureur/inscriptions` : rôle `COUREUR` (garde `reserveAuxCoureurs`, aide d'affichage ; le contrôle réel est serveur). Anonyme : `/connexion?retour=%2Fcoureur%2Finscriptions` ; autre rôle : `/acces-refuse`. En-tête : `lien-mes-inscriptions` « Mes inscriptions » visible si et seulement si le rôle est `COUREUR`, à côté de `lien-espace-coureur` « Espace coureur » (inchangé, mène toujours à `/coureur`). Aucune redirection spécifique après connexion (3.1 RG15). Route lazy (la bibliothèque de QR n'alourdit pas le bundle principal). Titre de page « Mes inscriptions - Backyard Ultra Tracker ».
- **RG14** : affichage d'une Inscription (`inscriptions-ligne`) : logo de la Course (si `logoUrl`), nom, date, statut de la Course (« En préparation », « En cours », « Terminée »), dossard (« Dossard 12 », en grand, en clair), statut de l'Inscription (« En course », « Abandon », « Vainqueur »), puis le QR (RG15). Titre : « Mes inscriptions ». Pendant la lecture : « Chargement… ». Aucun appel à `GET /api/coureur/courses` n'est nécessaire.
- **RG15** : QR. Un QR par Inscription, quel que soit son statut (point ouvert 2), noir sur fond blanc, zone de silence d'au moins 4 modules, niveau de correction d'erreur M, affiché à au moins 200 px de côté, avec un texte alternatif « QR code de l'inscription, dossard 12 » (le dossard n'est que dans le libellé d'accessibilité, pas dans le QR). Jamais de chargement réseau pour le dessiner. Si la génération échoue : message `inscriptions-erreur-qr` « Impossible d'afficher le QR code. », le dossard reste visible (aucun repli en texte du jeton, RG16).
- **RG16** : le jeton n'est **jamais visible en texte** pour le coureur (décision utilisateur : non nécessaire). Aucun bouton « Afficher/Masquer le jeton », aucun bouton « Copier », aucun élément `inscriptions-jeton` ou `inscriptions-bouton-jeton`. Le jeton ne figure dans le DOM ni comme contenu texte, ni dans un attribut (`alt`, `title`, `aria-label`, `data-*`, `value`), ni dans l'URL, ni dans le stockage local ou de session du navigateur ; il n'existe que dans la réponse JSON et dans l'image du QR (le dessin SVG ou canvas ne contient pas le jeton comme texte). Le texte alternatif du QR reste « QR code de l'inscription, dossard 12 » (RG15). Le coureur ne peut donc lire son jeton qu'en scannant son propre QR.
- **RG17** : états et erreurs. Liste vide : `inscriptions-vide` « Vous n'êtes inscrit à aucune course. » avec le lien `inscriptions-lien-courses-ouvertes` « Voir les courses ouvertes » vers `/coureur`. Échec de chargement (5xx, réseau) : `inscriptions-erreur-chargement` « Impossible de charger vos inscriptions. Réessayez plus tard. ». 401 : `/connexion?retour=%2Fcoureur%2Finscriptions`. 403 `ACCES_REFUSE` : `/acces-refuse`. Aucune erreur d'action (écran en lecture seule).
- **RG18** : non-régression. Comportements de 1.1 à 3.2 inchangés. `GET /api/coureur/courses` et le 201 du `POST` ne contiennent toujours aucun `jetonQr`. L'écran `/coureur` est inchangé. `GET /api/coureur/courses/{id}/inscriptions` reste 404 ou 405. `/api/courses/{id}/logo`, `/api/sante`, `/api/csrf` restent publics ; `/api/administration/**`, `/api/benevole/**` gardent leurs rôles.

### 2 bis. Où générer le QR : front, pas back

**Choix : côté front**, avec une bibliothèque MIT de génération de QR (ex. `qrcode`, ou `qrcode-generator`) dessinant en SVG ou canvas à partir de `jetonQr`.

Justification :
1. **Pas d'image porteuse du secret.** Une image servie par le back (`/api/.../qr.png`) est un nouvel objet à protéger (cache navigateur, cache du proxy Caddy, historique, URL partageable, journaux d'accès), alors que le jeton est un secret de lecture. Générer côté front ne fait transiter que le JSON déjà protégé (RG9).
2. **Moins de surface côté back.** Pas de dépendance ZXing côté Java, pas de nouveau endpoint binaire, pas de format de contenu à négocier, couche domaine et exposition inchangées. Le back reste « un jeton, une chaîne ».
3. **Cohérent avec la PWA (jalon 5).** Un QR dessiné localement fonctionnera hors ligne dès que l'écran sera mis en cache ; une image serveur exigerait un cache d'images contenant des secrets.
4. **Testable de bout en bout** : le QR est décodé dans l'E2E (CA9) et comparé au jeton lu en base ; il n'y a donc aucune règle de génération non vérifiée.
Contrepartie assumée : une dépendance front de plus (poids modeste, route lazy) et un décodage à faire dans l'E2E (bibliothèque de décodage en dépendance de développement de `e2e/`, ex. `jsqr`). Alternative rejetée : image PNG du back (points 1 à 3).

## 3. Cas limites

- **Aucune Inscription** : `[]`, `inscriptions-vide` + lien vers les Courses ouvertes (RG17). Un compte neuf, ou ayant vu sa seule Course supprimée.
- **Inscription sans aucun Passage** : état normal de toute Inscription avant le jalon 4 ; l'écran n'affiche aucun Passage.
- **Plusieurs Courses en parallèle** : une ligne par Inscription, dossards indépendants par Course (deux « Dossard 1 » possibles dans deux Courses), ordre RG2, jeton différent par ligne.
- **Course démarrée ou terminée** (statut posé par SQL avant 4.1) : l'Inscription reste listée, statut de Course « En cours » / « Terminée », QR toujours affiché (RG15).
- **Statuts `ABANDON` et `VAINQUEUR`** (posés par SQL avant le jalon 4) : listés, libellés « Abandon » et « Vainqueur », QR affiché (point ouvert 2). Aucun motif ni boucle d'abandon n'existe encore.
- **Course supprimée** (2.5, cascade) : l'Inscription disparaît de la liste au rechargement ; pas d'erreur. Course supprimée pendant que la page est ouverte : la page reste affichée jusqu'au rechargement (pas de polling).
- **Réintégration, Passage `CORRECTION` ou `SCAN`, boucle courante, dernière boucle, vainqueurs partagés, course complète (toutes boucles), instants de bascule** : non applicables (jalon 4). **Course complète au sens « remplie »** : sans effet ici, l'Inscription existe.
- **Course non démarrée** : cas nominal de l'incrément (`EN_PREPARATION`).
- **Rôle insuffisant** : `ADMIN`, `ADMIN_MASTER`, `BENEVOLE` : 403 ; anonyme : 401 (RG8). Un coureur ne peut pas voir les Inscriptions d'un autre (aucun paramètre, RG7).
- **Bénévole non affecté / affecté** : sans objet (un bénévole n'a pas d'Inscription et n'a pas accès à cet écran ; l'affectation concerne le scan, jalon 4).
- **Compte anonymisé** (3.6) : ne peut plus se connecter ; sans objet ici.
- **Session expirée** pendant l'affichage : au rechargement, 401 puis `/connexion?retour=%2Fcoureur%2Finscriptions`.
- **Jeton stable après redémarrage de `api`** : même valeur (stockée en base, RG4).
- **Mot de passe** : aucun n'est manipulé par cet incrément.
- **QR lu par un téléphone** : renvoie la chaîne de 43 caractères sous forme de texte. Ce n'est ni une URL ni un identifiant : il n'ouvre rien tant que 4.4 n'existe pas. C'est le seul moyen, pour le coureur, de voir son jeton (RG16).
- **Échec de génération du QR** : message `inscriptions-erreur-qr`, pas de repli en texte du jeton (RG15, RG16).

## 4. Contrat d'API

Toutes les erreurs sont des `ProblemDetail` (`application/problem+json`) : `{ "type": "about:blank", "title", "status", "detail", "instance", "code" }`, formats de 1.1 (CSRF), 1.2 RG19 (401, 403) et 3.1 §4.

### DTO

`MonInscriptionReponse` :

| Champ | Type | Contenu |
|---|---|---|
| `id` | uuid | identifiant de l'Inscription |
| `courseId` | uuid | identifiant de la Course |
| `courseNom` | string | nom de la Course |
| `courseDate` | string `aaaa-mm-jj` | date de la Course (même format que `CourseReponse.date`) |
| `courseStatut` | `"EN_PREPARATION"` \| `"EN_COURS"` \| `"TERMINEE"` | statut de la Course |
| `logoUrl` | string \| null | même format que `CourseReponse.logoUrl` (avec empreinte), `null` sans logo |
| `dossard` | integer ≥ 1 | dossard attribué (3.1 RG3) |
| `statut` | `"EN_COURSE"` \| `"ABANDON"` \| `"VAINQUEUR"` | statut de l'Inscription |
| `jetonQr` | string, 43 caractères `[A-Za-z0-9_-]`, jamais `null` | jeton opaque (RG3, RG4). **Fourni uniquement pour que le front génère le QR (RG6) ; ne doit jamais être affiché en texte, ni copié, ni placé dans le DOM, une URL ou un stockage navigateur (RG16).** |

Jamais renvoyés : identifiant ou pseudo du Compte, bénévoles affectés, autres Inscriptions, nombre d'inscrits. `toString()` : `MonInscriptionReponse[id=<id>]` (ni jeton, ni nom).

### GET /api/coureur/inscriptions (nouveau)
- Rôle : `COUREUR`. Aucun paramètre, pas de CSRF.
- **200** `application/json` : `MonInscriptionReponse[]` (RG1, RG2), `[]` si aucune Inscription. En-têtes : `Cache-Control` contient `no-store` (RG9).
- **401** `NON_AUTHENTIFIE` (anonyme). **403** `ACCES_REFUSE` (`ADMIN`, `ADMIN_MASTER`, `BENEVOLE`).
- 400, 404, 409 : non applicables (aucun identifiant, aucun état refusable).

### Autres chemins
- `POST`, `PUT`, `PATCH`, `DELETE` sur `/api/coureur/inscriptions` : 404 `RESSOURCE_INTROUVABLE` ou 405, jamais 2xx (3.4 ajoutera la désinscription). `GET /api/coureur/inscriptions/{id}` : 404 ou 405 (RG7).
- `GET /api/coureur/courses` et `POST /api/coureur/courses/{id}/inscriptions` : inchangés, sans jeton (RG3, RG18).

## 5. Écrans

### Mes inscriptions (`/coureur/inscriptions`, nouveau)
- **Accès** : `COUREUR` (RG13). Code Angular indicatif : `frontend/src/app/inscriptions/mes-inscriptions/`, méthode `listerMesInscriptions` du service API coureur, garde `reserveAuxCoureurs`.
- **Affiché** : `inscriptions-titre` « Mes inscriptions » ; `inscriptions-liste` avec une `inscriptions-ligne` par Inscription : `inscriptions-course-logo` (si présent), `inscriptions-course-nom`, `inscriptions-course-date`, `inscriptions-course-statut` (« En préparation » / « En cours » / « Terminée »), `inscriptions-dossard` « Dossard 12 », `inscriptions-statut` (« En course » / « Abandon » / « Vainqueur »), `inscriptions-qr` (le QR, RG15). Aucun jeton en texte (RG16). « Chargement… » pendant la lecture. En-tête : `lien-mes-inscriptions`.
- **Actions** : lien « Voir les courses ouvertes » (état vide) seulement. Pas d'affichage ni de copie du jeton (RG16). Pas de désinscription (3.4).
- **Messages d'erreur** : `inscriptions-vide` « Vous n'êtes inscrit à aucune course. » ; `inscriptions-erreur-chargement` « Impossible de charger vos inscriptions. Réessayez plus tard. » ; `inscriptions-erreur-qr` « Impossible d'afficher le QR code. ». 401 : redirection connexion ; 403 : `/acces-refuse`.
- **Navigation** : par `lien-mes-inscriptions`, ou par l'URL. L'écran `/coureur` ne change pas.

### Écrans existants
Inchangés (connexion, création de compte, mon compte, administration, bénévole, Courses ouvertes), hors lien d'en-tête supplémentaire pour un coureur.

## 6. Critères d'acceptation

Valeurs de référence : comptes de 3.1 (`Patron`, `Nadia`, `Alice`, `Bruno` coureurs `un-mot-de-passe-12`, `Léo` bénévole affecté à X) ; Course X (`Backyard des Crêtes`, date 2026-10-10, avec logo, `EN_PREPARATION`), Course Y (`Backyard express`, date 2026-11-15, sans logo, `EN_PREPARATION`), Course Z (`Backyard hiver`, date 2025-12-01, `EN_COURS` ou `TERMINEE`, statut placé par SQL). `Alice` inscrite à X (dossard 1), Y (dossard 1) et Z (dossard 3) ; `Bruno` inscrit à X (dossard 2).

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné des dépôts en mémoire (X, Y, Z ; Inscriptions d'`Alice` à X, Y, Z avec jetons `JX`, `JY`, `JZ` ; `Bruno` à X), quand `ListerMesInscriptions` est exécuté pour `Alice`, alors 3 éléments dans l'ordre `ORDRE_DE_LISTE` (Y 2026-11-15, X 2026-10-10, Z 2025-12-01), chacun avec la Course et l'Inscription d'`Alice` (dossards 1, 1, 3, jetons `JY`, `JX`, `JZ` inchangés) ; aucun élément de `Bruno` ; Z `EN_COURS` et une Inscription `ABANDON` et une `VAINQUEUR` placées dans le double sont aussi renvoyées (tous statuts, toutes Courses) ; pour `Bruno` : 1 élément (X, dossard 2) ; pour un compte sans Inscription : liste vide ; deux appels successifs renvoient les mêmes jetons (RG1, RG2, RG4, RG7) | unitaire |
| CA2 | Quand `MonInscriptionReponse.toString()` est appelé, alors il vaut `MonInscriptionReponse[id=<id>]` : ni jeton, ni nom de Course, ni dossard ; quand `./mvnw test`, alors les règles ArchUnit existantes passent sans exception supplémentaire : `ListerMesInscriptions` sans DTO ni dépendance à `comptes`, contrôleur sans accès à `courses.infrastructure`, aucun tri ni règle métier hors `Course.ORDRE_DE_LISTE` (RG3, RG11) | unitaire |
| CA3 | Étant donné `Alice` connectée (X, Y, Z comme ci-dessus), quand `GET /api/coureur/inscriptions`, alors 200 `application/json`, 3 éléments dans l'ordre Y, X, Z ; l'élément X vaut `{ id, courseId = X, courseNom = "Backyard des Crêtes", courseDate = "2026-10-10", courseStatut = "EN_PREPARATION", logoUrl non nul, dossard = 1, statut = "EN_COURSE", jetonQr }` ; Y a `logoUrl` `null` ; Z a `courseStatut` `EN_COURS`, `dossard` 3 ; chaque `jetonQr` fait 43 caractères `[A-Za-z0-9_-]`, est égal à la colonne `jeton_qr` de la ligne en base, les trois sont distincts entre eux et aucun n'est égal au dossard en texte ; un second appel renvoie les mêmes jetons ; `Cache-Control` contient `no-store` ; aucun champ `compteId`, `pseudo`, `benevoleIds` (RG1, RG2, RG4, RG9) | intégration |
| CA4 | Étant donné `Alice` et `Bruno` inscrits comme ci-dessus, quand `Bruno` fait `GET /api/coureur/inscriptions`, alors 1 élément (X, dossard 2, son jeton, différent de celui d'`Alice`), aucune trace d'Y ni de Z ; `?compteId=<id d'Alice>` ignoré : même réponse ; un compte coureur sans Inscription : 200 `[]` ; les statuts `ABANDON` et `VAINQUEUR` posés par SQL sont renvoyés tels quels ; `GET /api/coureur/inscriptions/<id d'une Inscription d'Alice>` par `Bruno` : 404 ou 405 ; une Course supprimée par `Patron` (`DELETE`, 204) disparaît de la liste d'`Alice` et le `GET` répond toujours 200 (RG1, RG7, RG10) | intégration |
| CA5 | Quand un anonyme envoie `GET /api/coureur/inscriptions`, alors 401 `NON_AUTHENTIFIE` ; quand `Nadia` (`ADMIN`), `Patron` (`ADMIN_MASTER`) et `Léo` (`BENEVOLE`, affecté à X) l'envoient, alors 403 `ACCES_REFUSE` (`application/problem+json`, `title`, `status`, `detail`, `code`), sans aucun jeton dans la réponse ; `POST`, `PUT`, `PATCH`, `DELETE` sur `/api/coureur/inscriptions` par `Alice` (CSRF valide) : 404 ou 405, aucune Inscription modifiée ; `Alice` sans `X-XSRF-TOKEN` : `GET` en 200 (pas de CSRF sur une lecture) (RG8, RG18) | intégration |
| CA6 | Étant donné un journal capturé (tous niveaux) et les Inscriptions ci-dessus, quand `Alice` s'inscrit à une Course W par `POST` (201), puis lit `GET /api/coureur/inscriptions`, `GET /api/coureur/courses`, et que l'anonyme et `Nadia` subissent 401 et 403, alors le `jeton_qr` de W lu en base apparaît dans la réponse de `GET /api/coureur/inscriptions` seulement ; il est absent du corps du 201 du `POST`, de `GET /api/coureur/courses`, des deux `ProblemDetail` et du journal ; aucune ligne INFO ou supérieure pour les lectures et les refus ; ni `Alice`, ni son UUID, ni un jeton, ni le nom `Backyard des Crêtes` dans le journal (RG3, RG9, RG12, RG18) | intégration |
| CA7 | Étant donné une base migrée, quand l'application démarre, alors `databasechangelog` contient exactement `0002-compte` à `0007-inscription` (aucun nouveau changeset) et `ddl-auto=validate` passe ; la suite d'intégration de 1.1 à 3.2 passe sans modification de ses attentes ; `.env.example` et `docker-compose*.yml` sont inchangés ; `GET /api/courses/X/logo`, `/api/sante`, `/api/csrf` restent publics ; `/api/administration/**` et `/api/benevole/**` gardent leurs rôles (RG10, RG18) | intégration |
| CA8 | Étant donné `Alice <suffixe>` et deux Courses créées par l'API (`Course A <suffixe>` avec logo, `Course B <suffixe>` sans logo, dates différentes) où elle s'est inscrite par l'API (dossards 1 et 1), quand elle se connecte par l'écran (sans `retour`, arrivée sur `/`) et clique `lien-mes-inscriptions`, alors l'URL est `/coureur/inscriptions`, `inscriptions-titre` affiche « Mes inscriptions » ; la requête observée est un `GET /api/coureur/inscriptions` en 200 ; deux `inscriptions-ligne` dans l'ordre date décroissante ; chaque ligne montre le nom, la date, `inscriptions-course-statut` « En préparation », `inscriptions-dossard` « Dossard 1 », `inscriptions-statut` « En course » et un `inscriptions-qr` visible d'au moins 200 px ; le logo n'est présent que sur la ligne de `Course A` ; aucun élément `inscriptions-jeton` ni `inscriptions-bouton-jeton` n'existe, aucun bouton « Afficher le jeton » ou « Copier » ; la valeur de `jeton_qr` (lue en base) n'apparaît ni dans le texte de la page ni dans aucun attribut du DOM (`page.content()` ne la contient pas) ; après F5 l'affichage est identique ; `lien-espace-coureur` mène toujours à `/coureur` dont le contenu est inchangé (RG13, RG14, RG15, RG16) | E2E |
| CA9 | Étant donné l'écran de CA8, quand le QR de `Course A` est capturé (capture d'écran de `inscriptions-qr`) et décodé par une bibliothèque de lecture de QR (dépendance de `e2e/`), alors le texte décodé est **exactement** le `jeton_qr` de l'Inscription lu en base (43 caractères) ; il est différent de la chaîne « 1 » (dossard), ne contient ni le nom de la Course ni le pseudo ; le QR de `Course B` décode un jeton différent, égal à sa propre colonne `jeton_qr` ; après F5 le texte décodé est inchangé ; aucune requête réseau vers un service externe ni vers un endpoint d'image n'est émise pour dessiner le QR (RG4, RG5, RG6) | E2E |
| CA10 | Étant donné l'écran de CA8, la Course A passée à `EN_COURS` et l'Inscription de `Course B` à `ABANDON` par SQL (helper SQL, aucune réponse interceptée), quand la page est rechargée, alors `inscriptions-course-statut` « En cours » sur la ligne A, `inscriptions-statut` « Abandon » sur la ligne B, et le QR de chacune est toujours affiché et décode toujours le même jeton ; avec une Inscription `VAINQUEUR` posée par SQL : « Vainqueur » ; après chaque rechargement, le jeton n'est toujours visible nulle part en texte (RG4, RG14, RG15, RG16) | E2E |
| CA11 | Étant donné `Alice <suffixe>` et `Bruno <suffixe>` dans deux contextes de navigateur, `Alice` inscrite à A et B, `Bruno` à A seule, quand chacun ouvre `/coureur/inscriptions`, alors `Alice` voit 2 lignes et `Bruno` 1 ligne ; les QR de `Bruno` et d'`Alice` pour A décodent des jetons différents (chacun égal à sa colonne en base) ; `Bruno` ne voit pas B ; un coureur sans Inscription (`Chloé <suffixe>`) voit `inscriptions-vide` « Vous n'êtes inscrit à aucune course. » et `inscriptions-lien-courses-ouvertes` qui mène à `/coureur` ; après suppression de la Course A par `Patron` par l'écran de 2.5, le rechargement ne montre plus A chez `Alice` (RG1, RG7, RG10, RG17) | E2E |
| CA12 | Étant donné un anonyme, quand il ouvre `/coureur/inscriptions`, alors redirection vers `/connexion?retour=%2Fcoureur%2Finscriptions` puis, après connexion en coureur, arrivée sur `/coureur/inscriptions` ; `Léo` (bénévole), `Nadia` et `Patron` ouvrent l'URL : `/acces-refuse` ; `lien-mes-inscriptions` est présent dans l'en-tête pour `Alice` seule (avec `lien-espace-coureur`), absent pour l'anonyme et pour les autres rôles ; étant donné `Alice` sur l'écran avec `GET` intercepté : en 500, `inscriptions-erreur-chargement` « Impossible de charger vos inscriptions. Réessayez plus tard. » ; en 401 : redirection vers `/connexion?retour=%2Fcoureur%2Finscriptions` ; en 403 `ACCES_REFUSE` : `/acces-refuse` ; en `[]` : `inscriptions-vide` ; avec une réponse interceptée dont le `jetonQr` est invalide pour la génération (chaîne vide), `inscriptions-erreur-qr` « Impossible d'afficher le QR code. », le dossard reste affiché et aucun jeton en texte n'est proposé en repli ; les suites E2E de 1.x à 3.2 passent sans modification de leurs attentes (RG13, RG15, RG16, RG17, RG18) | E2E |

Répartition : unitaire 2 (CA1, CA2), intégration 5 (CA3 à CA7), E2E 5 (CA8 à CA12). Total 12 (inchangé : numérotation stable, la partie « jeton » de CA10 est supprimée et l'absence de jeton en texte est vérifiée dans CA8, CA10 et CA12).

Couverture des RG : RG1 CA1/CA3/CA4/CA11, RG2 CA1/CA3/CA8, RG3 CA2/CA6, RG4 CA1/CA3/CA9/CA10, RG5 CA9, RG6 CA9, RG7 CA1/CA4/CA11, RG8 CA5, RG9 CA3/CA6, RG10 CA4/CA7/CA11, RG11 CA2, RG12 CA6, RG13 CA8/CA12, RG14 CA8/CA10, RG15 CA8/CA9/CA10/CA12, RG16 CA8/CA10/CA12, RG17 CA11/CA12, RG18 CA5/CA6/CA7/CA12. Écran « Mes inscriptions » : CA8 à CA12 (E2E).

Notes pour les testeurs : réutiliser les helpers de 3.1 et 3.2 (`creerCourseParApi`, création de coureurs par l'API, connexion en coureur, « s'inscrire par l'API », helper SQL pour les statuts `EN_COURS`, `TERMINEE`, `ABANDON`, `VAINQUEUR`). Lire `jeton_qr` en base (`JdbcTemplate` en intégration, helper SQL en E2E) : c'est la référence indépendante de l'API. Pour décoder le QR en E2E : capture d'écran de l'élément `inscriptions-qr`, puis décodage avec une bibliothèque de lecture de QR ajoutée aux dépendances de `e2e/` (ex. `jsqr` avec `pngjs`) ; le jeton n'est pas dans le DOM (RG16), il faut donc décoder l'image ; dans CA8, vérifier l'absence du jeton dans `page.content()` et l'absence des `data-testid` `inscriptions-jeton` et `inscriptions-bouton-jeton`. Deux coureurs : deux `browser.newContext()`. Aucune règle temporelle : `/api/test/horloge` inutile.

## 7. Tester à la main

Prérequis : stack lancée par `docker compose up -d --build` (`base`, `api`, `web` `healthy`), http://localhost, `.env` avec `ADMIN_MASTER_PSEUDO=Patron` et `ADMIN_MASTER_MOT_DE_PASSE=mot-de-passe-patron-1`. `./scripts/donnees-demo.sh` crée `Nadia`, `Léo`, `Marc`, `Alice` (coureuse, `un-mot-de-passe-12`) et les Courses de démo (`Backyard de démo`, `Backyard express`, `Backyard mini`). `Bruno` se crée par « Créer un compte » (`un-mot-de-passe-12`), dans une fenêtre de navigation privée.

1. Aucune inscription : connexion `Alice`, clic « Mes inscriptions » dans l'en-tête : « Mes inscriptions », message « Vous n'êtes inscrit à aucune course. » et lien « Voir les courses ouvertes » qui mène à `/coureur`.
2. Première inscription : « Espace coureur », « S'inscrire » à `Backyard de démo` (« Dossard 1 »), puis à `Backyard express` (« Dossard 1 »). Clic « Mes inscriptions » : deux lignes, la plus récente en haut (date décroissante), chacune avec le nom, la date, « En préparation », « Dossard 1 », « En course », le logo si la Course en a un, et un QR carré d'au moins 200 px. Les deux QR sont visiblement différents.
3. Le QR contient un jeton, pas le dossard : avec la caméra d'un téléphone (ou une application de lecture de QR), viser le QR de `Backyard de démo` : le texte lu est une chaîne de 43 caractères (lettres, chiffres, `-`, `_`), pas « 1 ». Comparer avec la base : `docker compose exec base psql -U backyard -d backyard -c "select c.nom, i.dossard, i.jeton_qr from inscription i join course c on c.id = i.course_id order by 1"` : le texte lu est identique au `jeton_qr` de `Backyard de démo`. (Le QR ne contient aucune URL : le téléphone propose seulement d'afficher ou de copier le texte.)
4. Jeton jamais visible : sur l'écran, aucun bouton « Afficher le jeton » ni « Copier », aucune chaîne de 43 caractères affichée ; en sélectionnant toute la page (Ctrl+A) ou en inspectant la page (clic droit, Inspecter), le jeton n'apparaît dans aucun texte ni attribut. F5 : les QR sont identiques à avant (même jeton, rien n'est régénéré).
5. Deux coureurs : navigation privée, création du compte `Bruno`, connexion, « Espace coureur », inscription à `Backyard de démo` (« Dossard 2 »), « Mes inscriptions » : une seule ligne (`Backyard de démo`, « Dossard 2 »), son QR décode un jeton différent de celui d'`Alice` ; pas de `Backyard express`. Retour chez `Alice` (F5) : elle voit toujours ses deux lignes et son « Dossard 1 ».
6. Statuts (avant le jalon 4, par SQL) : `docker compose exec base psql -U backyard -d backyard -c "update course set statut = 'EN_COURS' where nom = 'Backyard de démo'"` puis `update inscription set statut = 'ABANDON' where dossard = 1 and course_id = (select id from course where nom = 'Backyard express')`. F5 chez `Alice` : « En cours » sur `Backyard de démo`, « Abandon » sur `Backyard express`, les QR sont toujours affichés. Remettre `'EN_PREPARATION'` et `'EN_COURSE'` ensuite.
7. Rôles : connexion `Nadia` puis `Léo` : le lien « Mes inscriptions » est absent ; `http://localhost/coureur/inscriptions` redirige vers « Accès refusé ». Déconnecté : la même URL redirige vers la connexion (`?retour=%2Fcoureur%2Finscriptions`) ; après connexion en `Alice`, arrivée sur `/coureur/inscriptions`.
8. API (jar de cookies comme en 3.1 §7 étape 6) : en `Alice`, `curl -s -b /tmp/jar http://localhost/api/coureur/inscriptions` : 200, tableau de deux éléments avec `jetonQr` de 43 caractères (renvoyé uniquement pour dessiner le QR), sans `compteId` ni pseudo ; l'en-tête `Cache-Control` contient `no-store` (`curl -i`). En `Nadia` ou `Léo` : `403` ; sans session : `401`. `GET /api/coureur/courses` : toujours aucun `jetonQr`.
9. Journal : `docker compose logs api | grep -c "<un jeton lu en base>"` : 0 ; aucune ligne INFO produite par la lecture. `select id from databasechangelog` : toujours `0002` à `0007`.
10. Suppression d'une Course : en `Patron`, supprimer `Backyard express` (écran de 2.5). `Alice` rafraîchit « Mes inscriptions » : la ligne a disparu, l'autre est inchangée (même QR).
11. Persistance : `docker compose down` (sans `-v`) puis `docker compose up -d`, attendre `healthy`, connexion `Alice` : même dossard, même jeton (le texte du QR n'a pas changé).

## 8. Points ouverts

Bloquants : aucun. Positions par défaut appliquées dans la spec.

1. **QR généré côté front (RG6, §2 bis).** Retenu pour ne pas servir d'image porteuse du secret, ne pas alourdir le back et rester utilisable hors ligne en PWA. Alternative : image PNG fournie par le back. À confirmer. Choix de la bibliothèque (`qrcode`, `qrcode-generator`) laissé au développeur front : MIT, sans appel réseau, rendu SVG ou canvas.
2. **QR affiché pour tous les statuts (RG15).** Un coureur `ABANDON` ou `VAINQUEUR`, ou une Course `TERMINEE`, voit quand même son QR (le scan d'un coureur en abandon sera refusé en 4.6). Alternative : le masquer hors `EN_COURSE`, ce qui introduirait une règle d'affichage à placer dans le domaine. À confirmer.
3. **Hors ligne.** Un QR disponible sans réseau le jour de la course (coureur dans une zone mal couverte) suppose la mise en cache de l'écran et du jeton par le service worker, donc un stockage du secret sur l'appareil : traité au jalon PWA, pas en 3.3. À confirmer, ou à avancer dans la roadmap. Conseil au coureur : capture d'écran du QR, en attendant.
4. **Régénération du jeton.** Aucune : un jeton partagé par erreur reste valable jusqu'à la désinscription (3.4). Hors besoin ; à confirmer. Le jeton n'étant jamais affiché en texte (RG16), le risque de partage accidentel par copier-coller est réduit, mais une capture d'écran du QR le partage quand même.
5. **Impact sur la roadmap 4.4 (à trancher par l'utilisateur, `docs/roadmap.md` non modifiée).** Décision utilisateur : le jeton n'est jamais affiché en texte au coureur (RG16). Or `docs/roadmap.md` 4.4 « Scanner un passage (saisie du jeton) » prévoit « Tu testes : copier le jeton d'un coureur, le saisir → passage accepté ». Cette recette n'est plus possible depuis l'écran « Mes inscriptions ». Options : (a) en 4.4, lire le jeton en base (`select i.dossard, i.jeton_qr from inscription i ...`) et le saisir ; (b) avancer le scan caméra (4.5) et supprimer la saisie du jeton ; (c) fournir le jeton au testeur par `scripts/donnees-demo.sh` ou par un écran d'administration réservé aux admins (vue des inscrits, 3.5), ce qui expose le jeton à un autre rôle ; (d) saisie du seul dossard par le bénévole en 4.4 (le jeton restant pour le QR). La ligne 3.3 de la roadmap (« il contient un jeton, pas le dossard ») reste valable : vérifiée en scannant le QR avec un téléphone. L'endpoint continue de renvoyer `jetonQr`, uniquement pour dessiner le QR.
6. **Accès à l'écran.** Retenu : second lien d'en-tête « Mes inscriptions » (route `/coureur/inscriptions`), l'écran « Courses ouvertes » restant à `/coureur`. 3.1 point ouvert 8 évoquait que `/coureur` accueillerait « Mes inscriptions » ; la séparation évite de mélanger inscription et consultation du QR. Alternative : fusionner dans `/coureur`. À confirmer, ainsi que les libellés et `data-testid` `inscriptions-*`, `lien-mes-inscriptions`.
7. **Libellés de statut.** « En course », « Abandon », « Vainqueur » (Inscription) et « En préparation », « En cours », « Terminée » (Course) : à confirmer. Pas de motif ni de boucle d'abandon avant le jalon 4.
8. **Contrat.** À confirmer : chemin `GET /api/coureur/inscriptions`, nom `MonInscriptionReponse`, champs `courseNom`, `courseDate`, `courseStatut`, `logoUrl`, `jetonQr`.
9. **Données de démo.** `scripts/donnees-demo.sh` non modifié (aucune Inscription préparée ; la roadmap en prévoit pour 3.5). À confirmer.
10. **Test avec un téléphone.** La lecture du QR par l'appareil photo d'un téléphone (étape 3) ne demande ni HTTPS ni caméra dans le navigateur ; seul 4.5 (caméra dans l'application) a besoin de HTTPS ou de `localhost`.

## 9. Changements par rapport à la version précédente

Cause : décision utilisateur, le jeton QR n'est pas visible en texte pour le coureur. Le QR reste affiché ; `GET /api/coureur/inscriptions` renvoie toujours `jetonQr` (uniquement pour générer le QR côté front). Numérotation des RG et des CA inchangée (12 CA : 2 unitaires, 5 intégration, 5 E2E).

**Supprimé**
- Bouton `inscriptions-bouton-jeton` « Afficher le jeton » / « Masquer le jeton » et élément `inscriptions-jeton` (texte du jeton), ainsi que l'idée d'un bouton « Copier ».
- Partie « jeton » de CA10 (affichage, masquage, retour au masqué après F5, autre ligne restée masquée).
- Étape 4 « Jeton en texte » de « Tester à la main » (remplacée).
- Ancien point ouvert 5 (jeton en texte et bouton « copier ») : remplacé par l'impact sur la roadmap 4.4.
- Repli « le jeton reste affichable » dans RG15 en cas d'échec du QR.

**Modifié**
- RG16 : devient « jeton jamais visible en texte » (ni contenu, ni attribut, ni URL, ni stockage navigateur) ; même numéro.
- RG3, RG6, DTO `jetonQr` (§4), introduction et périmètre : précisent que le jeton ne sert qu'à générer le QR.
- RG15 : pas de repli en texte en cas d'échec de génération.
- CA8 : ajoute l'absence de `inscriptions-jeton` et `inscriptions-bouton-jeton`, de bouton « Copier », et l'absence de la valeur de `jeton_qr` dans `page.content()` (texte et attributs).
- CA10 : ne garde que les statuts (Course « En cours », Inscription « Abandon » et « Vainqueur », QR toujours affiché et décodant le même jeton) et ajoute la vérification que le jeton reste invisible en texte après rechargement.
- CA12 : cas du `jetonQr` invalide : vérifie qu'aucun jeton en texte n'est proposé en repli.
- Couverture : RG16 couverte par CA8, CA10, CA12 (avant : CA10).
- Écran §5 : liste des éléments et actions sans jeton ; cas limite « QR lu par un téléphone » et « Échec de génération du QR » ajustés.
- « Tester à la main » étape 4 réécrite (vérifier l'absence de jeton, QR inchangé après F5) ; étape 8 précise que `jetonQr` sert au seul QR.
- Point ouvert 4 (risque de partage réduit) ; point ouvert 6 (libellé) ; taille front estimée à ~150 lignes.
- Notes aux testeurs : le jeton n'étant pas dans le DOM, seul le décodage de l'image du QR le fournit.

**Inchangés** : CA1 à CA7, CA9, CA11, RG1, RG2, RG4, RG5, RG7 à RG14, RG17, RG18, contrat de l'endpoint (champs, codes), statuts et le reste de l'écran.

**Impact sur les autres incréments** : roadmap 4.4 (« copier le jeton d'un coureur, le saisir ») non réalisable depuis l'écran ; voir point ouvert 5, à trancher par l'utilisateur. La roadmap n'a pas été modifiée.
