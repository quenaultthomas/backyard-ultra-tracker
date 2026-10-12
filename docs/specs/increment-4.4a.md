# Increment 4.4a : API de passage

Un Bénévole affecté à une Course `EN_COURS` enregistre le **Passage** d'un coureur pour la **Boucle courante** en envoyant son **jeton QR**. Un nouvel endpoint `POST /api/benevole/courses/{courseId}/passages` crée le Passage (nouvelle table `passage`, migration `0009-passage`). L'API refuse déjà proprement tous les cas d'erreur : les codes et `ProblemDetail` ci-dessous sont **définitifs** (4.4b, 4.5, 4.6 et 5.1 ne changent pas le contrat). **Aucun écran** : l'incrément se teste par l'API (voir « Tester à la main »). Il est suivi de **4.4b** (écran de scan, spec `increment-4.4b.md`). Cette spec découpe l'ancienne spec `increment-4.4.md` ; la numérotation des RG et des CA est conservée.

## Ce qui est déjà livré, donc non respécifié

- `Course` (4.2) : `demarreeLe()`, `chronometrage(instant)` (RG1 à RG4 de 4.2 : Boucle courante plafonnée à `nombreMaxBoucles`, `boucleMaxTerminee`, `t < t0` donne Boucle 1), `CourseNonDemarreeException` (409 `COURSE_NON_DEMARREE`), `benevolesAffectes()`, `DepotCourses.parId`.
- `Inscription` (3.1) : `jetonQr` (43 caractères base64url, unique en base par `uq_inscription_jeton_qr`, `toString` masqué), statut, dossard ; `DepotInscriptions` ; `JetonQr`. `GET /api/coureur/inscriptions` (3.3) renvoie `jetonQr` au seul coureur concerné (pour dessiner le QR ; le jeton n'est jamais affiché en texte).
- `AnnuairePseudos` (3.5 : « Coureur anonyme » après 3.6, « Compte inconnu » si non résolu).
- `Clock` unique, pilotable en profil `e2e` (4.3).
- Sécurité : `/api/benevole/**` réservé au rôle `BENEVOLE`, CSRF actif (aucune modification de `SecuriteConfiguration`). `GestionnaireErreursCourses` (`probleme(...)`).
- `scripts/donnees-demo.sh` : Léo (bénévole) n'est affecté qu'à « Backyard de démo » ; Marc n'est affecté à aucune Course. **Inchangé en 4.4a** (évolution en 4.4b).

Le **delta** de 4.4a : `Passage` et `DepotPassages`, règle de Boucle de passage dans `Course`, `Inscription.autoriserPassage()`, normalisation du jeton, 10 exceptions métier, cas d'usage `EnregistrerPassage`, persistance JPA et migration, un `POST` et ses DTO, handlers 403/404/409.

**Taille estimée (production hors tests) : ~380 lignes** (domaine ~120, application ~60, persistance et migration ~110, exposition ~90). Sous la cible de 400. Aucun front, aucune variable d'environnement.

## 1. Périmètre

**Inclus**
- `POST /api/benevole/courses/{courseId}/passages` : enregistre un Passage d'origine `SCAN` (RG1 à RG13).
- Table `passage`, entité de domaine `Passage`, port `DepotPassages` (RG11).

**Exclu**
- Écran de scan, bouton « Scanner » de l'accueil bénévole, évolution de `scripts/donnees-demo.sh` : **4.4b**.
- Lecture caméra du QR : **4.5**.
- Retour visuel de refus plein cadre, libellés par motif, dossard et pseudo du coureur refusé mis en avant, **anti-doublon** (délai minimal entre deux passages d'un même coureur, valeur à fixer alors) : **4.6**. L'API renvoie déjà tous les motifs ; le contrat ne change pas.
- Passages visibles au pilotage (qui a bouclé, boucles terminées) : **4.7**. Le pilotage n'est pas modifié (aucun champ de Passage dans `GET .../pilotage`).
- Abandon automatique, fin de Course, vainqueurs : **4.8 à 4.10**. Abandon manuel : **4.11**. Réintégration et Passages `CORRECTION` : **4.12** (la table les prévoit, aucun code ne les crée ici).
- File hors ligne, nouvelles tentatives automatiques : **5.1** (le contrat l'anticipe : RG4, RG9).
- Boucle courante sur une vue bénévole, mémorisation du choix de Course, limitation des essais de jetons (jeton de 256 bits), scan par un `ADMIN` ou `ADMIN_MASTER` : exclus.

## 2. Règles de gestion

**Endpoint et sécurité**
- **RG1** : `POST /api/benevole/courses/{courseId}/passages`, rôle `BENEVOLE` **seul** (règle existante `/api/benevole/**`) **et affecté à la Course** (`courseId` dans `benevolesAffectes`). `ADMIN`, `ADMIN_MASTER`, `COUREUR` : 403 `ACCES_REFUSE` ; anonyme : 401 `NON_AUTHENTIFIE` ; `POST` sans CSRF valide : 403 `CSRF_INVALIDE`. L'identité du bénévole vient de la session, jamais du corps. Le jeton est dans le **corps**, jamais dans l'URL ni un paramètre (il ne doit pas se retrouver dans les journaux d'accès du proxy). Un bénévole non affecté, une Course inconnue, supprimée ou un `courseId` non UUID donnent **le même** 403 `BENEVOLE_NON_AFFECTE` (aucune fuite sur l'existence d'une Course ; le chemin se lit donc comme texte, sans conversion UUID automatique). L'affectation est relue à chaque requête : un bénévole retiré de la Course est refusé immédiatement.

**Jeton**
- **RG2** : normalisation et forme (une seule règle, dans `JetonQr.depuisSaisie`). La saisie est nettoyée de **tous** les caractères d'espacement (espaces, tabulations, retours à la ligne, espaces insécables), y compris à l'intérieur (collage d'un jeton coupé sur deux lignes). **La casse est significative** (base64url : `a` et `A` diffèrent), aucune mise en minuscules. Contrôle de forme côté exposition : `jetonQr` présent, non vide après nettoyage, au plus 200 caractères bruts ; sinon 400. Un jeton bien formé mais de longueur ou de caractères inattendus n'est pas un 400 : c'est un jeton inconnu (404, RG3).
- **RG3** : un jeton n'identifie qu'**une** Inscription d'**une** Course. La recherche se fait **dans la Course du chemin** : le jeton d'une Inscription d'une autre Course (même bénévole affecté aux deux) donne 404 `JETON_INCONNU`, comme un jeton qui n'existe pas (aucune distinction possible). Un jeton inconnu ne révèle jamais de dossard ni de pseudo.

**Instant et Boucle du Passage**
- **RG4** : instant du scan. `scanneLe = instantDuClock - ageDuScan`, tronqué à la milliseconde, où `ageDuScan` = champ optionnel `ageDuScanMillisecondes` (défaut 0, entier de 0 à 86 400 000 = 24 h). Pour un scan en direct l'âge est 0. La file hors ligne de 5.1 enverra l'**âge du scan mesuré par une horloge monotone du téléphone**, jamais l'heure du téléphone : le serveur ne se fie à aucune horloge de client. La Boucle du Passage se calcule sur `scanneLe`, pas sur l'heure de réception : un scan fait pendant la Boucle 2 et reçu pendant la Boucle 3 reste un Passage de la Boucle 2. `scanneLe` antérieur à `demarreeLe` : 409 `SCAN_ANTERIEUR_AU_DEPART`.
- **RG5** : Boucle du Passage (règle unique dans le domaine, `Course.boucleDePassage(instantDuScan)`, qui réutilise `chronometrage`, 4.2). Notation de 4.2 : `D` durée de Boucle en ms, `M = nombreMaxBoucles`, `t0 = demarreeLe`, `e = scanneLe - t0`. Tant que `e < M x D` : `numeroBoucle = floor(e / D) + 1`. Un coureur qui franchit la ligne **pendant** la Boucle N **termine** la Boucle N : le Passage porte le numéro N. Frontières (4.2 RG2) : à `e = k x D` exactement (k < M), la Boucle `k + 1` est courante, donc le Passage vaut `k + 1` ; à `e = k x D - 1 ms`, il vaut `k`. À `e = 0` : Boucle 1. Passé `e >= M x D` (`boucleMaxTerminee`), **aucune Boucle n'est calculable** : `boucleDePassage` lève `TempsDeCourseEcouleException` et le Passage n'est pas accepté, 409 `TEMPS_DE_COURSE_ECOULE` (jamais de Passage numéroté `M + 1`, ni un Passage de la Boucle `M` enregistré après la limite). Ce refus est donc évalué **avant** « déjà passé » (RG10).
- **RG6** : états refusés. Course `EN_PREPARATION` : 409 `COURSE_NON_DEMARREE` ; Course `TERMINEE` : 409 `COURSE_TERMINEE` (code existant de l'affectation des bénévoles, `detail` propre au scan) ; Course `EN_COURS` sans `demarreeLe` (ligne posée par SQL) : 409 `HEURE_DE_DEPART_INCONNUE` ; Inscription `ABANDON` ou `VAINQUEUR` (via `Inscription.autoriserPassage()`) : 409 `INSCRIPTION_NON_EN_COURSE`. Le scan n'abandonne, ne réintègre et ne change jamais le statut d'une Inscription (abandon manuel : jamais par le scan, CLAUDE.md).
- **RG7** : continuité des Boucles (**décidée par l'utilisateur**). Un Passage de la Boucle `N > 1` exige un Passage valide (origine `SCAN` ou `CORRECTION`) de la même Inscription sur la Boucle `N - 1` ; sinon 409 `PASSAGE_PRECEDENT_MANQUANT`. Justification : entre le départ de la Boucle `N + 1` et l'abandon automatique (planificateur de 4.8, quelques secondes, ou s'il est arrêté), un coureur qui a manqué la Boucle N ne doit pas obtenir un Passage sur la Boucle `N + 1`. La Boucle 1 n'a pas de précédente. Compatible avec la réintégration (4.12 recrée les Passages manquants).
- **RG8** : unicité. Au plus un Passage par (Inscription, `numeroBoucle`) ; un second scan de la même Inscription sur la même Boucle (avec un autre `identifiantScan`) est refusé : 409 `PASSAGE_DEJA_ENREGISTRE`. L'unicité est garantie **en base** (contrainte `uq_passage_inscription_boucle`) : deux bénévoles qui scannent le même coureur en même temps produisent exactement un Passage ; le perdant reçoit le même 409 (la violation de contrainte est traduite en exception métier, jamais en 500). Aucun délai minimal entre deux passages en 4.4a (reporté à 4.6).

**Idempotence**
- **RG9** : CLAUDE.md demande à la fois une API idempotente sur (Inscription, Boucle) (filet réseau) et un refus du Passage déjà existant (« déjà passé »). Les deux se concilient par un **identifiant de scan** (`identifiantScan`, UUID tiré par le client à chaque scan et conservé pour ses nouvelles tentatives) qui devient l'identifiant du Passage :
  - même `identifiantScan` pour la même Inscription (et la même Course) que celui d'un Passage existant : c'est un **rejeu**. **200** avec le Passage déjà enregistré (mêmes valeurs, même `scanneLe`, même `numeroBoucle`), aucune écriture, quel que soit l'état actuel (Boucle suivante démarrée, Course terminée, Inscription entre-temps en abandon) ;
  - un `identifiantScan` nouveau pour une (Inscription, Boucle) déjà passée : un **autre** scan, 409 `PASSAGE_DEJA_ENREGISTRE` ;
  - un `identifiantScan` qui désigne le Passage d'une autre Inscription ou d'une autre Course : 409 `IDENTIFIANT_SCAN_DEJA_UTILISE` (aucune donnée du Passage existant n'est divulguée).

  Le client qui perd la réponse (coupure) renvoie donc le même `identifiantScan` et obtient 200 au lieu d'un faux « déjà passé ». Cas rarissime assumé : deux requêtes **simultanées** avec le même `identifiantScan` (l'une 201, l'autre 409 `PASSAGE_DEJA_ENREGISTRE` si elle perd la course sur la contrainte) ; une nouvelle tentative du client obtient alors 200.
- **RG10** : ordre d'évaluation, figé (chaque étape suppose les précédentes passées) : (1) CSRF, 401, 403 `ACCES_REFUSE` ; (2) 400 `CORPS_ILLISIBLE` / `VALIDATION_ECHOUEE` ; (3) 403 `BENEVOLE_NON_AFFECTE` ; (4) 404 `JETON_INCONNU` ; (5) rejeu : 200, ou 409 `IDENTIFIANT_SCAN_DEJA_UTILISE` ; (6) 409 `COURSE_NON_DEMARREE`, `COURSE_TERMINEE`, `HEURE_DE_DEPART_INCONNUE` ; (7) 409 `INSCRIPTION_NON_EN_COURSE` ; (8) 409 `SCAN_ANTERIEUR_AU_DEPART` ou `TEMPS_DE_COURSE_ECOULE`, calcul de la Boucle (RG5) ; (9) 409 `PASSAGE_DEJA_ENREGISTRE` ; (10) 409 `PASSAGE_PRECEDENT_MANQUANT` ; (11) création, 201. (L'étape 4 précède l'étape 6 pour que le rejeu d'un scan puisse réussir même après la fin de la Course ; un jeton inconnu sur une Course non démarrée donne donc 404.)

**Modèle et réponse**
- **RG11** : `Passage` (domaine, Java pur) : `id` (UUID = `identifiantScan`), `inscriptionId`, `numeroBoucle >= 1`, `scanneLe` (instant ; obligatoire pour `SCAN`, **vide** pour `CORRECTION`), `origine` (`SCAN`, `CORRECTION`). **Immuable** : aucune modification, aucune suppression (donnée brute, CLAUDE.md). Migration **`0009-passage`** (nouveau changeset, rien d'existant modifié) : table `passage` (`id uuid` PK, `inscription_id uuid` non nul, FK `fk_passage_inscription` vers `inscription(id)` `ON DELETE CASCADE`, `numero_boucle integer` non nul, `scanne_le timestamptz`, `origine varchar(20)` non nul), contrainte unique `uq_passage_inscription_boucle (inscription_id, numero_boucle)`, `ck_passage_numero_boucle` (`numero_boucle > 0`), `ck_passage_origine` (`origine in ('SCAN','CORRECTION')`), `ck_passage_scanne_le` (`(origine = 'SCAN' and scanne_le is not null) or (origine = 'CORRECTION' and scanne_le is null)`). Aucune colonne dérivée (boucle courante, nombre de boucles, distance, dénivelé, allure : jamais stockés). La suppression d'une Course `EN_PREPARATION` supprime ses Inscriptions (aucun Passage possible à ce stade) ; la suppression d'un Compte anonymise sans toucher aux Passages (3.6).
- **RG12** : réponse. 201 (création) ou 200 (rejeu) avec `PassageReponse` (§4) : `dossard` et `pseudo` de l'Inscription (pseudo par `AnnuairePseudos`), `numeroBoucle`, `scanneLe`, `origine`. Jamais de jeton, d'identifiant de Compte, ni d'autre Inscription. `Cache-Control: no-store`. `toString()` : `PassageReponse[id=<id>]`.
- **RG13** : confidentialité. Le jeton n'apparaît dans aucun journal, `ProblemDetail`, exception, `toString()` ni URL ; pas de ligne INFO (ni supérieure) pour un scan ou un refus nominal (chemin fréquent) ; aucun pseudo ni identifiant de Compte au journal. Les `ProblemDetail` ne contiennent ni jeton ni nom de Course ; `dossard`, `pseudo` et `numeroBoucle` n'y figurent (propriétés d'extension) que pour les refus liés à une Inscription connue (§4), visibles du seul bénévole affecté qui détient le jeton.

**Transverses**
- **RG19** (partie API) : plusieurs Courses en parallèle. Le Passage ne dépend que de sa Course (chronométrage, bénévoles, Inscriptions) : un bénévole affecté à deux Courses les scanne indépendamment ; le jeton de l'une est inconnu dans l'autre (RG3). Le pilotage (4.2), la fiche et les listes sont **inchangés** : aucun champ ajouté à `GET .../pilotage`. (La partie écran de RG19 est dans 4.4b.)
- **RG20** : architecture. Règle de Boucle, états et continuité dans le domaine (`Course`, `Inscription`, `Passage`, `JetonQr`), jamais dans le contrôleur ni la persistance ; cas d'usage `EnregistrerPassage` (application, `Clock` injecté, transaction d'écriture, sans DTO ni rôle ; il reçoit l'identifiant du bénévole pour vérifier l'affectation) ; entité JPA distincte de `Passage` ; contrôleur sans logique (validation de forme, appel, mapping) ; handlers dans `GestionnaireErreursCourses` (ajouter le nouveau contrôleur à `assignableTypes`) ; aucune dérogation ArchUnit ; le contexte `courses` ne référence le Compte que par identifiant ; aucun `Instant.now()` (4.3 RG9).
- **RG21** (partie non-régression) : comportements de 1.x à 4.3 et R.x inchangés. `.env.example`, `docker-compose*.yml` et `scripts/donnees-demo.sh` inchangés (aucune variable). (L'évolution du script de démo est dans 4.4b.)

## 3. Cas limites

- **Instant exact de bascule** (Boucles de 1 min, M = 5, `t0 = 14:03:27Z`) : scan à `14:04:26.999Z` : Boucle 1 ; à `14:04:27.000Z` : Boucle 2 (exige un Passage de la Boucle 1, RG7) ; à `14:03:27.000Z` : Boucle 1. Un doublon accidentel scanné juste après un départ (par exemple `14:04:26.500Z` puis `14:04:27.200Z`) obtient un Passage de la Boucle 2 : **non détecté en 4.4a**, anti-doublon reporté à 4.6.
- **Dernière Boucle autorisée** : `14:07:27Z` à `14:08:26.999Z` : Boucle 5 acceptée ; à `14:08:27.000Z` (`e = M x D`) : 409 `TEMPS_DE_COURSE_ECOULE`, **même pour un coureur déjà passé en Boucle 5** (RG5, RG10).
- **Scan après `boucleMaxTerminee`, Course encore `EN_COURS`** (avant 4.9/4.10) : 409 `TEMPS_DE_COURSE_ECOULE`. Course `TERMINEE` : 409 `COURSE_TERMINEE`. Course `EN_PREPARATION` : 409 `COURSE_NON_DEMARREE`. `EN_COURS` sans `demarreeLe` : 409 `HEURE_DE_DEPART_INCONNUE`.
- **Aucun passage sur une Boucle, vainqueurs partagés, course complète** : l'enregistrement du Passage est le seul effet ; l'abandon, le vainqueur et la fin de Course sont constatés par 4.8 à 4.10 (aucun changement de statut ici).
- **Coureur en abandon / vainqueur / réintégré** : `ABANDON` et `VAINQUEUR` refusés (409 `INSCRIPTION_NON_EN_COURSE`, `dossard`, `pseudo` et `statut` en extension). Après une réintégration (4.12), l'Inscription est `EN_COURSE` avec des Passages `CORRECTION` sur les Boucles manquées : le scan de la Boucle courante respecte alors RG7 et RG8 sans changement.
- **Passage `CORRECTION` vs `SCAN`** : seuls des Passages `SCAN` sont créés ici ; un Passage `CORRECTION` existant compte pour RG7 et RG8 (« déjà passé » s'il porte la même Boucle).
- **Inscription sans aucun Passage** : état normal ; son premier scan en Boucle 1 est accepté ; en Boucle 2 ou plus, refusé (RG7).
- **Rejeu** : même `identifiantScan` renvoyé après la perte de la réponse, même après le départ de la Boucle suivante : 200, même corps (RG9). Deux bénévoles qui scannent le même coureur : un 201, un 409 `PASSAGE_DEJA_ENREGISTRE`.
- **Scan hors ligne arrivé tard** (5.1) : `ageDuScanMillisecondes` replace le scan sur sa Boucle (RG4). Un scan arrivé après la fin de la Course (`TERMINEE`) ou pour un coureur déjà en abandon est refusé définitivement (409) ; la suite à donner côté file est décidée en 5.1.
- **Bénévole non affecté, bénévole retiré en cours de Course, rôle insuffisant** : RG1 (403). **Course inconnue / supprimée / non UUID** : 403 `BENEVOLE_NON_AFFECTE`.
- **Compte du coureur anonymisé** (3.6) : le scan réussit, pseudo « Coureur anonyme ».
- **Jeton collé avec espaces, retour à la ligne, coupé en deux** : nettoyé (RG2). Jeton en minuscules alors qu'il contient des majuscules : inconnu (404).
- **Jeton d'une autre Course** : 404 `JETON_INCONNU` (RG3). **Jeton très long (> 200 caractères)** : 400.
- **Plusieurs Courses en parallèle** : RG19.
- **Redémarrage de `api`** : rien en mémoire, Passages en base.

## 4. Contrat d'API

Erreurs au format `ProblemDetail` (`application/problem+json`) : `{ "type": "about:blank", "title", "status", "detail", "instance", "code" }`, formats existants (401 `NON_AUTHENTIFIE`, 403 `ACCES_REFUSE` et `CSRF_INVALIDE`, 400 `VALIDATION_ECHOUEE` avec `erreurs[]` au format `ErreurChamp` `{champ, code, message}`, 400 `CORPS_ILLISIBLE`, 500 `ERREUR_INTERNE`). Aucun `detail` ne contient de jeton, de nom de Course, de pseudo ni de Compte.

### POST /api/benevole/courses/{courseId}/passages (nouveau)
- Rôle : `BENEVOLE` affecté à la Course (RG1). CSRF requis (`X-XSRF-TOKEN`). `Content-Type: application/json`. Réponses en `Cache-Control: no-store`.
- Corps `PassageRequete` :

| Champ | Type | Obligatoire | Description |
|---|---|---|---|
| `jetonQr` | texte, 1 à 200 caractères bruts | oui | jeton lu ou saisi ; nettoyé des espaces (RG2) ; `toString()` masqué |
| `identifiantScan` | UUID (texte) | oui | tiré par le client à chaque scan, conservé pour les nouvelles tentatives ; devient `passageId` (RG9) |
| `ageDuScanMillisecondes` | entier long de 0 à 86 400 000 | non (défaut 0) | temps écoulé entre le scan et l'envoi, mesuré par horloge monotone (RG4) |

Exemple : `{ "jetonQr": "Xk3...43 caractères", "identifiantScan": "3b6f0d5e-0f6a-4d7b-9e1c-5a2d8c7e1f40" }`.

- **201** (Passage créé) et **200** (rejeu, RG9) `PassageReponse` :

| Champ | Type | Description |
|---|---|---|
| `passageId` | UUID | = `identifiantScan` |
| `inscriptionId` | UUID | Inscription du coureur |
| `dossard` | entier >= 1 | dossard du coureur |
| `pseudo` | texte | pseudo (« Coureur anonyme » si anonymisé, « Compte inconnu » si non résolu) |
| `numeroBoucle` | entier >= 1 | Boucle terminée (RG5) |
| `scanneLe` | texte, instant ISO-8601 UTC avec millisecondes (`2026-10-12T14:04:26.500Z`) | RG4 |
| `origine` | `SCAN` | toujours `SCAN` ici |

Exemple : `{ "passageId": "3b6f0d5e-…", "inscriptionId": "…", "dossard": 1, "pseudo": "Karim", "numeroBoucle": 1, "scanneLe": "2026-10-12T14:03:57.250Z", "origine": "SCAN" }`.

- **Erreurs** (ordre d'évaluation RG10) :

| Statut | `code` | `title` | `detail` | Propriétés d'extension |
|---|---|---|---|---|
| 400 | `VALIDATION_ECHOUEE` | Requête invalide | (existant) + `erreurs[]` : `jetonQr`/`JETON_INVALIDE` « Le jeton est obligatoire (200 caractères au plus). » ; `identifiantScan`/`IDENTIFIANT_SCAN_INVALIDE` « L'identifiant de scan doit être un UUID. » ; `ageDuScanMillisecondes`/`AGE_DU_SCAN_INVALIDE` « L'âge du scan doit être compris entre 0 et 86400000 millisecondes. » | |
| 400 | `CORPS_ILLISIBLE` | (existant) | (existant) : corps absent ou JSON invalide | |
| 401 | `NON_AUTHENTIFIE` | (existant) | (existant) | |
| 403 | `ACCES_REFUSE` / `CSRF_INVALIDE` | (existants) | rôle autre que `BENEVOLE` / jeton CSRF absent | |
| 403 | `BENEVOLE_NON_AFFECTE` | Accès refusé | « Vous n'êtes pas affecté à cette course. » | |
| 404 | `JETON_INCONNU` | Introuvable | « Jeton inconnu pour cette course. » | |
| 409 | `IDENTIFIANT_SCAN_DEJA_UTILISE` | Conflit | « Cet identifiant de scan correspond à un autre passage. » | |
| 409 | `COURSE_NON_DEMARREE` | Conflit | « La course n'est pas démarrée. » | |
| 409 | `COURSE_TERMINEE` | Conflit | « La course est terminée. » | |
| 409 | `HEURE_DE_DEPART_INCONNUE` | Conflit | « L'heure de départ de la course est inconnue : le passage ne peut pas être placé sur une boucle. » | |
| 409 | `INSCRIPTION_NON_EN_COURSE` | Conflit | « Ce coureur n'est plus en course. » | `dossard`, `pseudo`, `statut` (`ABANDON` ou `VAINQUEUR`) |
| 409 | `SCAN_ANTERIEUR_AU_DEPART` | Conflit | « Le scan est antérieur au départ de la course. » | |
| 409 | `TEMPS_DE_COURSE_ECOULE` | Conflit | « Le temps de la dernière boucle est écoulé. » | `dossard`, `pseudo` |
| 409 | `PASSAGE_DEJA_ENREGISTRE` | Conflit | « Ce coureur a déjà un passage sur la boucle N. » (N en chiffres) | `dossard`, `pseudo`, `numeroBoucle` |
| 409 | `PASSAGE_PRECEDENT_MANQUANT` | Conflit | « Ce coureur n'a pas de passage sur la boucle précédente. » | `dossard`, `pseudo`, `numeroBoucle` (= la Boucle visée) |
| 500 | `ERREUR_INTERNE` | (existant) | (existant) | |

Les propriétés d'extension sont des champs de premier niveau du `ProblemDetail`, comme `code`. Les noms de `code` et ces textes sont **définitifs**. 415 : comme l'existant. Autres méthodes sur ce chemin (`GET`, `PUT`, `DELETE`) : 404 `RESSOURCE_INTROUVABLE` ou 405, jamais 2xx.

### Endpoints existants
Inchangés, dont `GET /api/benevole/courses` et `GET /api/administration/courses/{id}/pilotage`.

### Port interne (indicatif, non exposé)
`Course.boucleDePassage(Instant)` (lève `CourseNonDemarreeException`, `HeureDeDepartInconnueException`, `ScanAnterieurAuDepartException`, `TempsDeCourseEcouleException`, et, pour une Course `TERMINEE`, `CourseTermineeException` existante ou une exception dédiée au choix du développeur) ; `Inscription.autoriserPassage()` ; `JetonQr.depuisSaisie(String)` ; `Passage.scanner(UUID id, Inscription, int numeroBoucle, Instant scanneLe)` ; `DepotPassages` : `parId(UUID)`, `existe(inscriptionId, numeroBoucle)`, `enregistrer(Passage)` (lève `PassageDejaEnregistreException` sur violation de `uq_passage_inscription_boucle`) ; `DepotInscriptions.parCourseEtJeton(courseId, JetonQr)`. Exceptions nouvelles : `BenevoleNonAffecteException`, `JetonInconnuException`, `IdentifiantScanDejaUtiliseException`, `HeureDeDepartInconnueException`, `InscriptionNonEnCourseException`, `ScanAnterieurAuDepartException`, `PassageDejaEnregistreException`, `TempsDeCourseEcouleException`, `PassagePrecedentManquantException` (la Course terminée réutilise `CourseTermineeException` ou en crée une dédiée ; seul compte le contrat code `COURSE_TERMINEE` et `detail` « La course est terminée. » du tableau ci-dessus). Noms libres, comportement fixé par RG1 à RG12.

## 5. Écrans

**Aucun écran n'est créé ni modifié** (écran de scan et bouton de l'accueil bénévole : 4.4b). Aucun CA de niveau E2E : la règle « chaque écran de l'incrément est couvert en E2E » ne s'applique pas (aucun écran livré). Les suites E2E existantes doivent rester vertes (RG21).

## 6. Critères d'acceptation

Valeurs de référence : comptes de 3.1 (`Patron` `ADMIN_MASTER`, `Nadia` `ADMIN`, `Léo` `BENEVOLE`, `Marc` `BENEVOLE` non affecté, `Alice`, `Bruno`, `Chloé` coureurs). Course X (`Backyard des Crêtes`, 400 m, **1 min**, 5 m, 10 participants, **M = 5**, bénévole `{Léo}`), Inscriptions `Bruno` (dossard 1) et `Chloé` (2) `EN_COURSE`, `EN_COURS`, `t0 = 2026-10-12T14:03:27Z`. Jetons `jB` (Bruno) et `jC` (Chloé). En unitaire et en intégration, `Clock` fixe réglable.

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné `Course.boucleDePassage(t)` pour X : `t0` -> 1 ; `t0 + 59 999 ms` -> 1 ; `t0 + 60 000 ms` -> 2 ; `t0 + 240 000 ms` -> 5 ; `t0 + 299 999 ms` -> 5 ; `t0 + 300 000 ms` -> `TempsDeCourseEcouleException` ; `t0 - 1 ms` -> `ScanAnterieurAuDepartException` ; Course de 90 min : `t0 + 90 min` -> 2 ; `EN_PREPARATION` -> `CourseNonDemarreeException` ; `TERMINEE` -> exception de Course terminée ; `EN_COURS` sans `demarreeLe` -> `HeureDeDepartInconnueException` ; deux Courses de durées et départs différents donnent des Boucles indépendantes au même instant (RG4, RG5, RG6, RG19) | unitaire |
| CA2 | Étant donné `JetonQr.depuisSaisie` : `"  AbC_-  "` -> `AbC_-` ; une saisie contenant un retour à la ligne, une tabulation, des espaces et une espace insécable au milieu (`"Ab\nCd\t Ef Gh"`) -> `AbCdEfGh` ; `"abc"` et `"ABC"` restent distincts (aucune mise en minuscules) ; vide, blanc ou `null` -> exception (« jeton obligatoire ») ; `toString()` ne contient pas la valeur ; deux saisies égales après nettoyage donnent des jetons égaux (RG2, RG13) | unitaire |
| CA3 | Étant donné `Inscription.autoriserPassage()` : `EN_COURSE` passe, `ABANDON` et `VAINQUEUR` lèvent `InscriptionNonEnCourseException` ; `Passage.scanner(...)` crée un Passage d'origine `SCAN` avec `scanneLe` tronqué à la milliseconde et `numeroBoucle >= 1` (0 et négatif refusés) ; un Passage `CORRECTION` a `scanneLe` vide et un `SCAN` sans `scanneLe` est refusé ; `toString()` sans jeton ; aucun mutateur (RG6, RG11) | unitaire |
| CA4 | Étant donné `EnregistrerPassage` avec dépôts en mémoire, X, Léo affecté, `Clock` à `t0 + 30 s` : le scan de `jB` crée un Passage Boucle 1 (`scanneLe` = `t0 + 30 s`, origine `SCAN`) et renvoie dossard 1 et le pseudo restitué par un double d'`AnnuairePseudos` ; `Clock` à `t0 + 90 s` (Passage de Boucle 1 présent) : le scan de `jB` crée la Boucle 2 ; `ageDuScanMillisecondes` 40 000 avec `Clock` à `t0 + 70 s` crée une Boucle 1 de `scanneLe` = `t0 + 30 s` (la Boucle vient de `scanneLe`, pas de l'heure de réception) ; un `ageDuScanMillisecondes` plaçant le scan avant `t0` lève `ScanAnterieurAuDepartException` ; pseudo « Coureur anonyme » restitué tel quel ; le `Clock` n'est lu qu'une fois (RG4, RG5, RG12) | unitaire |
| CA5 | Étant donné `EnregistrerPassage` : bénévole non affecté et Course inconnue -> la même `BenevoleNonAffecteException` ; jeton inconnu et jeton d'une autre Course affectée au même bénévole -> `JetonInconnuException` ; `EN_PREPARATION`, `TERMINEE`, `EN_COURS` sans heure -> les exceptions de RG6 ; Inscription `ABANDON` -> `InscriptionNonEnCourseException` ; `jB` déjà passé en Boucle 1 -> `PassageDejaEnregistreException` ; `Clock` à `t0 + 300 s` -> `TempsDeCourseEcouleException` ; `jC` sans Passage de Boucle 1 à `t0 + 90 s` -> `PassagePrecedentManquantException` ; `jC` avec un Passage `CORRECTION` en Boucle 1 à `t0 + 90 s` -> accepté (Boucle 2) ; ordre RG10 vérifié par combinaisons (jeton inconnu + Course `EN_PREPARATION` -> `JetonInconnuException` ; Course `EN_PREPARATION` + Inscription `ABANDON` -> `CourseNonDemarreeException` ; Inscription `ABANDON` + déjà passé -> `InscriptionNonEnCourseException` ; déjà passé en Boucle 5 + temps écoulé (`Clock` à `t0 + 300 s`) -> `TempsDeCourseEcouleException` ; déjà passé + Passage précédent manquant (Passage `CORRECTION` seul en Boucle 2, aucun en Boucle 1) -> `PassageDejaEnregistreException`) ; aucune écriture lors d'un refus (RG1, RG3, RG5, RG6, RG7, RG8, RG10) | unitaire |
| CA6 | Étant donné `EnregistrerPassage` : même `identifiantScan` renvoyé pour `jB` -> renvoie le Passage existant (rejeu) sans écriture, y compris `Clock` en Boucle 2, Course `TERMINEE` ou Inscription passée en `ABANDON` entre-temps ; le même `identifiantScan` avec le jeton `jC` -> `IdentifiantScanDejaUtiliseException` ; un `identifiantScan` nouveau pour `jB` en Boucle 1 -> `PassageDejaEnregistreException` ; un dépôt qui lève `PassageDejaEnregistreException` à `enregistrer` (course de deux scans simultanés) -> l'exception métier remonte telle quelle (jamais une autre erreur) (RG8, RG9) | unitaire |
| CA7 | Étant donné la base Testcontainers, X démarrée par l'API (`demarreeLe` = `t0`), `Clock` à `t0 + 30 s`, Léo connecté : `POST /api/benevole/courses/X/passages` `{ jetonQr: jB, identifiantScan: u1 }` avec CSRF -> **201** `application/json`, `Cache-Control: no-store`, exactement les 7 champs du contrat, `passageId` = `u1`, `dossard` 1, `pseudo` « Bruno », `numeroBoucle` 1, `scanneLe` `2026-10-12T14:03:57.000Z`, `origine` `SCAN`, aucun jeton dans le corps ; une ligne `passage` en base (`inscription_id`, `numero_boucle` 1, `origine` `SCAN`, `scanne_le` renseigné) ; un second `POST` avec `jB` et un autre `identifiantScan` -> 409 `PASSAGE_DEJA_ENREGISTRE`, `detail` « Ce coureur a déjà un passage sur la boucle 1. », extensions `dossard` 1, `pseudo` « Bruno », `numeroBoucle` 1, toujours une seule ligne ; le statut de l'Inscription et de X est inchangé (RG1, RG8, RG11, RG12) | intégration |
| CA8 | Étant donné CA7 puis le même corps (même `identifiantScan` `u1`, même jeton) rejoué -> **200**, corps identique à celui du 201 (même `scanneLe`), toujours une ligne ; après avance du `Clock` à `t0 + 90 s` (Boucle 2), le rejeu renvoie encore 200 avec `numeroBoucle` 1 ; Course X passée `TERMINEE` par SQL : le rejeu renvoie 200 et un nouveau scan 409 `COURSE_TERMINEE` ; `u1` avec le jeton de Chloé -> 409 `IDENTIFIANT_SCAN_DEJA_UTILISE` sans donnée de Bruno dans le corps ; `u1` sur une autre Course affectée à Léo -> 409 `IDENTIFIANT_SCAN_DEJA_UTILISE` (RG9) | intégration |
| CA9 | Étant donné X et Léo : `Clock` à `t0 + 59 999 ms` -> Boucle 1 ; à `t0 + 60 000 ms`, Chloé sans Passage de Boucle 1 -> 409 `PASSAGE_PRECEDENT_MANQUANT` (`numeroBoucle` 2, `dossard` 2) et aucune ligne créée ; Bruno avec Passage de Boucle 1 -> 201 Boucle 2 ; Bruno à `t0 + 4 x 60 000 ms` avec Passages 1 à 4 posés -> 201 Boucle 5 ; à `t0 + 300 000 ms` -> 409 `TEMPS_DE_COURSE_ECOULE`, y compris pour Bruno déjà passé en Boucle 5 (et non `PASSAGE_DEJA_ENREGISTRE`) ; `ageDuScanMillisecondes` 1000 à `t0 + 60 500 ms` -> 201 Boucle 1 de `scanneLe` `t0 + 59 500 ms` (Chloé, sans Passage préalable) ; `ageDuScanMillisecondes` plaçant le scan avant `t0` -> 409 `SCAN_ANTERIEUR_AU_DEPART` ; `ageDuScanMillisecondes` omis = 0 (RG4, RG5, RG7, RG10) | intégration |
| CA10 | Étant donné Y `EN_PREPARATION`, Z `TERMINEE` (SQL), U `EN_COURS` sans `demarree_le` (SQL), tous avec Léo affecté et une Inscription : `POST` -> Y 409 `COURSE_NON_DEMARREE`, Z 409 `COURSE_TERMINEE` (`detail` « La course est terminée. »), U 409 `HEURE_DE_DEPART_INCONNUE` ; sur X, Inscription de Bruno passée `ABANDON` puis `VAINQUEUR` (SQL) -> 409 `INSCRIPTION_NON_EN_COURSE` avec `dossard` 1, `pseudo` « Bruno », `statut` ; jeton inexistant -> 404 `JETON_INCONNU` ; jeton d'une Inscription de Y envoyé sur X -> 404 `JETON_INCONNU` ; aucune ligne `passage` créée dans tous ces cas ; ordre RG10 : Y avec jeton inexistant -> 404 (avant 409) ; Inscription `ABANDON` d'une Course Y -> `COURSE_NON_DEMARREE` (RG3, RG6, RG10) | intégration |
| CA11 | Étant donné X : anonyme -> 401 `NON_AUTHENTIFIE` ; `Alice` (`COUREUR`), `Nadia` (`ADMIN`), `Patron` (`ADMIN_MASTER`) -> 403 `ACCES_REFUSE` ; `Léo` sans `X-XSRF-TOKEN` -> 403 `CSRF_INVALIDE` ; `Marc` (non affecté), `Léo` sur une Course inconnue (`00000000-0000-0000-0000-000000000000`) et sur `inexistant` (non UUID) -> 403 `BENEVOLE_NON_AFFECTE`, **corps identiques** ; `Léo` retiré de X par `PUT .../benevoles` -> 403 `BENEVOLE_NON_AFFECTE` à la requête suivante ; `GET`, `PUT`, `DELETE` sur le chemin -> 404 `RESSOURCE_INTROUVABLE` ou 405 ; aucune ligne créée (RG1) | intégration |
| CA12 | Étant donné X et Léo : `jetonQr` absent, `""`, `"   "`, de 201 caractères -> 400 `VALIDATION_ECHOUEE` avec `erreurs` = `[{champ: jetonQr, code: JETON_INVALIDE, message}]` ; `identifiantScan` absent, `"abc"`, `123` -> 400 `IDENTIFIANT_SCAN_INVALIDE` ; `ageDuScanMillisecondes` `-1`, `86400001`, `"x"`, `1.5` -> 400 `AGE_DU_SCAN_INVALIDE` ; corps absent et JSON invalide -> 400 `CORPS_ILLISIBLE` ; jeton `" " + jB.substring(0,20) + "\n" + jB.substring(20) + " "` (espaces et retour à la ligne) -> 201 (nettoyé) ; jeton `jB` passé en minuscules (le jeton de test est choisi avec au moins une majuscule) -> 404 ; aucune ligne créée par les 400 (RG2) | intégration |
| CA13 | Étant donné X : 20 `POST` simultanés de `jB` avec 20 `identifiantScan` différents -> exactement un 201 et dix-neuf 409 `PASSAGE_DEJA_ENREGISTRE` (aucun 500), une seule ligne ; migration : `databasechangelog` se termine par `0009-passage`, les changesets `0001` à `0008` sont inchangés ; la table a la clé primaire, la clé étrangère `ON DELETE CASCADE`, `uq_passage_inscription_boucle` et les contraintes `ck_passage_*` : une insertion SQL directe d'un doublon (inscription, boucle), d'un `numero_boucle` 0, d'une origine `X`, d'un `SCAN` sans `scanne_le` et d'une `CORRECTION` avec `scanne_le` échoue ; `ddl-auto=validate` passe ; la suppression d'une Course `EN_PREPARATION` ne laisse aucune ligne orpheline (RG8, RG11) | intégration |
| CA14 | Étant donné un journal capturé, X et une Course W affectée à Léo, avec plusieurs scans (201, 200, 403, 404, 409, 400) : aucune ligne INFO ou supérieure ; ni `jB`, ni `Bruno`, ni `Backyard des Crêtes`, ni UUID de Compte au journal, aucun jeton dans un `ProblemDetail` ni une `toString()` ; un jeton de W est inconnu sur X et inversement, Léo scanne X et W indépendamment (Boucles différentes au même `Clock`) ; `GET .../pilotage` de X renvoie exactement les mêmes 17 champs qu'avant 4.4a (aucun champ de Passage) ; ArchUnit : règles inchangées sans dérogation, `EnregistrerPassage` sans DTO ni dépendance à `comptes` ; `.env.example`, `docker-compose*.yml` et `scripts/donnees-demo.sh` inchangés (RG13, RG19, RG20, RG21) | intégration |

Répartition : unitaire 6 (CA1 à CA6), intégration 8 (CA7 à CA14), E2E 0 (aucun écran). Total 14. Les CA15 à CA22 sont dans 4.4b.

Couverture des RG : RG1 CA5/CA7/CA11, RG2 CA2/CA12, RG3 CA5/CA10, RG4 CA1/CA4/CA9, RG5 CA1/CA4/CA5/CA9, RG6 CA1/CA3/CA5/CA10, RG7 CA5/CA9, RG8 CA5/CA6/CA7/CA13, RG9 CA6/CA8, RG10 CA5/CA9/CA10, RG11 CA3/CA7/CA13, RG12 CA4/CA7, RG13 CA2/CA14, RG19 (API) CA1/CA14, RG20 CA14, RG21 (non-régression) CA14. Les RG14 à RG18 et la partie démo de RG21 sont dans 4.4b.

## 7. Tester à la main

Aucun écran en 4.4a : on appelle l'API depuis la console du navigateur connecté en bénévole (la session et le jeton CSRF sont ceux du navigateur).

Prérequis : `docker compose up -d --build` (services sains), http://localhost. **Base neuve** de préférence (`docker compose down -v`), puis `./scripts/donnees-demo.sh` (**inchangé** : Léo n'est affecté qu'à « Backyard de démo »). Comptes : `Léo` et `Marc` (`mot-de-passe-benevole-1`), `Nadia` (`mot-de-passe-admin-1`), `Karim`, `Sophie`, `Tom`, `Alice` (`un-mot-de-passe-12`). « Backyard du jour » : date du jour, boucles de **1 min**, 5 Boucles max, Karim 1, Sophie 2, Tom 3.

1. **Affecter Léo à « Backyard du jour » à la main** : en `Nadia`, « Administration » > « Gestion des courses » > fiche de « Backyard du jour » > section Bénévoles : cocher `Léo`, enregistrer. (`Marc` reste non affecté.)
2. **Jetons** (avant de démarrer) : `docker compose exec base psql -U backyard -d backyard -c "select i.dossard, i.jeton_qr from inscription i join course c on c.id = i.course_id where c.nom = 'Backyard du jour' order by i.dossard"` : trois lignes de 43 caractères ; les noter (dossard 1 = Karim, 2 = Sophie, 3 = Tom). Variante sans base : en `Karim`, ouvrir http://localhost/api/coureur/inscriptions : le JSON contient son `jetonQr`.
3. **Identifiant de la Course** : id lu dans l'URL de la fiche de « Backyard du jour » (`/administration/courses/<id>`).
4. **Console** : se connecter en `Léo` (http://localhost), ouvrir les outils de développement > console, coller (remplacer `<id>`) :
   ```js
   const t = document.cookie.split('; ').find(c => c.startsWith('XSRF-TOKEN='))?.split('=')[1];
   const course = '<id>';
   const scan = (jetonQr, extra = {}) => fetch(`/api/benevole/courses/${course}/passages`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': t }, body: JSON.stringify({ jetonQr, identifiantScan: crypto.randomUUID(), ...extra }) }).then(r => r.json().then(j => [r.status, j]));
   ```
   (`extra` permet d'imposer un `identifiantScan` ou un `ageDuScanMillisecondes`.)
5. **Course non démarrée** : `await scan('abc')` -> `[404, … JETON_INCONNU]` (le jeton est contrôlé avant l'état de la Course) ; `await scan('<jeton de Karim>')` -> `[409, … COURSE_NON_DEMARREE]`.
6. **Démarrer** : en `Nadia` (autre fenêtre), fiche > « Démarrer la course » > « Démarrer maintenant ».
7. **Scan nominal** (les étapes 7 à 9 dans la première minute) : `const u = crypto.randomUUID(); await scan('<jeton de Karim>', { identifiantScan: u })` -> `[201, { dossard: 1, pseudo: "Karim", numeroBoucle: 1, origine: "SCAN", … }]`. Avec des espaces autour : `await scan('  <jeton de Sophie>  ')` -> 201 dossard 2. Ne pas scanner Tom (utile à l'étape 11).
8. **Déjà passé** : `await scan('<jeton de Karim>')` -> `[409, { code: "PASSAGE_DEJA_ENREGISTRE", detail: "Ce coureur a déjà un passage sur la boucle 1.", dossard: 1, pseudo: "Karim", numeroBoucle: 1 }]`.
9. **Rejeu (idempotence)** : `await scan('<jeton de Karim>', { identifiantScan: u })` -> `[200, …]` avec le même corps que le 201 de l'étape 7 (même `scanneLe`), toujours une seule ligne pour Karim en base ; `await scan('<jeton de Sophie>', { identifiantScan: u })` -> `[409, … IDENTIFIANT_SCAN_DEJA_UTILISE]`.
10. **Base** : `docker compose exec base psql -U backyard -d backyard -c "select i.dossard, p.numero_boucle, p.origine, p.scanne_le from passage p join inscription i on i.id = p.inscription_id order by 1, 2"` : deux lignes (dossards 1 et 2, Boucle 1, `SCAN`). Le **pilotage** n'affiche pas les passages (4.7).
11. **Boucle 2** : attendre la minute suivante (visible au pilotage) ; `await scan('<jeton de Karim>')` -> 201 `numeroBoucle: 2`. `await scan('<jeton de Tom>')` (aucun Passage en Boucle 1) -> `[409, … PASSAGE_PRECEDENT_MANQUANT]`.
12. **Erreurs de forme** : `await scan('')` -> `[400, … VALIDATION_ECHOUEE]` ; `await scan('abc')` -> `[404, … JETON_INCONNU]`.
13. **Rôles** : en `Marc` (non affecté), rejouer la console (nouveau `t`) : `[403, … BENEVOLE_NON_AFFECTE]` ; en `Nadia` ou `Alice` : `[403, … ACCES_REFUSE]` ; sans `X-XSRF-TOKEN` : `[403, … CSRF_INVALIDE]` ; fenêtre privée non connectée : 401.
14. **Fin du temps** : cinq minutes après le démarrage : `await scan('<jeton de Karim>')` -> `[409, … TEMPS_DE_COURSE_ECOULE]` (même si Karim a déjà un Passage en Boucle 5).

## 8. Décisions

Aucun point ouvert. Décisions de l'utilisateur et positions retenues :

1. **Découpage** (**décidé par l'utilisateur**) : 4.4a (API, migration, CA1 à CA14) puis 4.4b (écran, accueil bénévole, données de démo, CA15 à CA22). Le contrat d'API ne change pas entre les deux. Taille de 4.4a : ~380 lignes.
2. **Continuité des Boucles** (RG7, **décidé par l'utilisateur**) : conservée.
3. **Anti-doublon** (re-scan accidentel juste après un départ) (**décidé par l'utilisateur**) : rien en 4.4a ; reporté à 4.6 (délai minimal entre deux passages d'un même coureur, valeur à fixer alors).
4. **Roadmap** : amendée par l'orchestrateur (4.4 « Tu testes », 4.6 devient un incrément d'écran avec l'anti-doublon).
5. **Idempotence** par `identifiantScan` et **âge du scan** `ageDuScanMillisecondes` (RG4, RG9) : retenus (position par défaut), pour que 5.1 ne change pas le contrat.
6. **403 uniforme** pour bénévole non affecté, Course inconnue et id non UUID (RG1) : retenu (position par défaut).
7. **Les admins ne scannent pas** (RG1) : retenu (position par défaut), conforme à CLAUDE.md.
8. **Scans hors ligne arrivés tard** (après la fin de la Course ou l'abandon) : refus définitif côté API ; le traitement par la file est décidé en 5.1 (position par défaut).
9. **Pas de limitation des essais de jetons** (jeton de 256 bits) : retenu (position par défaut).
10. **Pas de Boucle courante sur une vue bénévole** et **pas de mémorisation du choix de Course** : retenus (positions par défaut) ; voir 4.4b pour le choix de la Course.
11. **Ordre « temps écoulé » avant « déjà passé »** (RG5, RG10) : au-delà de `M x D` aucune Boucle n'est calculable, le refus `TEMPS_DE_COURSE_ECOULE` précède donc `PASSAGE_DEJA_ENREGISTRE`.
12. **Code `COURSE_TERMINEE`** (RG6) : réutilisation du code existant de l'affectation des bénévoles, avec le `detail` propre au scan « La course est terminée. » (**décidé par l'orchestrateur**) ; exception réutilisée ou dédiée au choix du développeur.
