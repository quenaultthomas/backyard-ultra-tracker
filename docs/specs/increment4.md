# Spec Incrément 4 — Frontend PWA

> Projet : Backyard Ultra Tracker
> Date de rédaction : 2026-09-26
> Statut : **validée pour développement** (2026-09-26). PO1 à PO4 sont tranchés par l'utilisateur (section 0). Points encore ouverts :
> - PO5 (bibliothèques QR) : choix laissé au développeur, selon les critères de la section 0, avec justification écrite ;
> - PO13 (CSP et styles en ligne avec Angular) : l'hypothèse par défaut s'applique ; toute dérogation à `style-src 'self'` exige l'accord de l'utilisateur (section 13) ;
> - PO6 à PO26 (hors PO13) : hypothèses par défaut appliquées dans les RG et CA, remplaçables par une décision de l'utilisateur.
> Prérequis : incréments 1 à 3 en GO sous réserves (`docs/tests/PATRIMOINE.md`). API de référence : `docs/specs/increment3.md` (endpoints E1 à E18, RG5/RG6 erreurs, RG28 à RG34 sécurité).
> Numérotation RG / CA / PO propre à cet incrément. Les références aux autres specs sont notées « RG15 (inc. 3) », « PO8 (inc. 2) », etc.

---

## 0. Décisions de stack, d'hébergement et d'outillage

Ces choix appartiennent à l'utilisateur. PO1 à PO4 ont été tranchés le 2026-09-26. PO5 est laissé au développeur, dans le cadre de critères fixés. Les RG et CA décrivent un comportement observable (dans le navigateur ou par HTTP) ; les décisions ci-dessous fixent en plus l'hébergement (RG53 à RG55) et le build (RG59).

**PO1 — Framework front. TRANCHÉ le 2026-09-26 : Angular.**
- Angular, TypeScript en mode **strict** (`strict: true` dans `tsconfig`, et contrôles stricts des templates `strictTemplates: true`).
- Service worker Angular (`@angular/service-worker`) pour l'installabilité, le cache hors ligne et les mises à jour (RG44 à RG46).
- La définition de « fini » (« compile sans warning », réserve RT3) s'applique au build Angular (RG59).

**PO2 — Hébergement de la PWA. TRANCHÉ le 2026-09-26 : option A.**
- Même origine : la PWA est servie par Spring Boot. Ses fichiers sont produits par le build Angular et copiés dans le jar au build. Un seul livrable.
- Le build Maven dépend donc de Node (RG59).
- **Aucun CORS** : le refus actuel est conservé (RG54).
- Spring Security ouvre une **liste fermée** de chemins statiques en `permitAll()` (GET et HEAD) et renvoie `index.html` pour les routes du front (RG53).
- Les en-têtes de sécurité de la PWA sont portés par Spring Security (RG55). HSTS reste posé par le reverse proxy (RG34 inc. 3).
- Les options B (fichiers servis par le reverse proxy) et C (origine distincte avec CORS explicite) sont écartées, ainsi que les CA qui leur étaient propres.

**PO3 — Outillage E2E et navigateurs. TRANCHÉ le 2026-09-26 : Playwright, Chromium et WebKit, exécution en local.**
- Chaque CA E2E est exécuté sous **Chromium et sous WebKit** (WebKit couvre le moteur de Safari iOS, utilisé par une partie des bénévoles).
- Exécution en local, par une commande documentée, distincte de `mvn verify` (RG58).
- Les CA E2E utilisent les capacités standard de l'outil : mode hors ligne du contexte, interception réseau, flux caméra simulé, horloge simulée du navigateur. Les limites de l'outil sous WebKit sont traitées par RG58.
- Base de données de l'environnement E2E : non tranchée, hypothèse PO24 appliquée.

**PO4 — Emplacement du code, build et définition de « fini » du front. TRANCHÉ le 2026-09-26.**
- Code sous `frontend/`.
- Build du front intégré à `mvn -B -f backend/pom.xml clean verify` (obligatoire avec l'option A).
- Tests unitaires de la **logique pure** du front, avec un seuil de **80 % de couverture de lignes**. Cette logique comprend la file de scans, le backoff, la classification des réponses, le décalage d'horloge, les formats d'affichage, la table de visibilité des actions et l'expiration des identifiants.
- **Aucun seuil** sur les composants d'affichage.
- « Compile sans warning » s'applique au front.
- Règles détaillées : RG59, vérifiées par CA5.

**PO5 — Bibliothèques QR (décodage caméra, génération). NON TRANCHÉ : choix du développeur, à justifier.**
Le développeur choisit les bibliothèques et consigne sa justification (nom, version, licence, critères ci-dessous) dans le rapport de l'incrément. Critères obligatoires :
- licence compatible avec le projet ;
- maintenance active ;
- **embarquées dans le bundle**, jamais chargées depuis un CDN (fonctionnement hors ligne RG45, CSP RG55) ;
- compatibles avec la CSP de RG55 (aucun `eval`, aucune ressource tierce) ;
- **pas seulement l'API `BarcodeDetector`** du navigateur : elle n'est pas disponible partout (notamment Safari iOS). Elle peut être utilisée quand elle existe, avec un repli sur la bibliothèque.
Le choix est contrôlé à la validation par CA22, CA27, CA36 (QR générés puis décodés), CA28 et CA39 (hors ligne).

---

## 1. Périmètre

### Inclus
- PWA installable, en français, avec les écrans suivants :
  - accueil (liste des courses) ;
  - tableau de bord public par course ;
  - détail public d'un coureur ;
  - inscription publique par course ;
  - connexion ;
  - scan (rôles SCANNER et ADMIN) ;
  - administration (courses, coureurs, démarrage, DNF manuel avec confirmation, réintégration avec confirmation, impression des QR codes).
- Filet réseau du scan : file locale persistante, envoi FIFO, nouvel essai avec backoff, idempotence, traitement de chaque réponse de l'API.
- Fonctionnement hors ligne du scan.
- Impacts backend minimaux (section 10) :
  - endpoint E19 `GET /api/scan/me` ;
  - fichiers de la PWA servis par Spring Boot, liste fermée de chemins publics et renvoi de `index.html` (RG53) ;
  - absence de CORS confirmée (RG54) ;
  - en-têtes de sécurité (RG55).
- Build du front (Angular) intégré à `mvn verify`, tests unitaires de la logique pure du front et seuil de couverture (RG59).
- Tests E2E des parcours exigés par la réserve RT2 (section 12.C), sous Chromium et WebKit.

### Explicitement exclu
- Classement (PO7 inc. 3) et tri côté serveur. La PWA affiche les coureurs dans l'ordre de l'API.
- Toute nouvelle règle métier ou tout recalcul d'une valeur dérivée côté front (RG1).
- Comptes nominatifs, changement de mot de passe depuis l'interface, jetons de session (PO26 inc. 3).
- Clôture manuelle d'un yard (RG25 inc. 3), arbitrage d'une course terminée (PO5 inc. 3).
- Création ou modification d'un passage par l'admin hors réintégration.
- Saisie d'un passage par dossard (PO17).
- Notifications push et synchronisation en arrière-plan obligatoire. L'envoi de la file a lieu quand l'application est ouverte (RG22).
- Multilingue.
- Toute API de test ou de manipulation du temps côté serveur (RG56).

### Impact schéma
**Aucune migration.** E19 ne lit que l'utilisateur authentifié et l'horloge.

---

## 2. Principes transverses

**RG1 — Aucune règle métier dans la PWA**
La PWA n'implémente aucune des règles suivantes : calcul du yard courant, de la fin de yard, des tours, de la distance, du D+, de l'allure, du temps de boucle et du badge « corrigé » ; attribution du yard d'un scan ; auto-DNF ; vainqueur ; dossard ; ouverture des inscriptions. Elle affiche les valeurs de l'API (E4, E5, E13, réponses d'action). Seuls sont autorisés côté front :
- le formatage (RG2) ;
- le compte à rebours à partir de `currentYardEndsAt` (RG31) ;
- la validation de **format** des saisies, miroir des contraintes Bean Validation de l'API (non vide, 255 caractères maximum, entier > 0 ou >= 0), le serveur restant l'autorité ;
- la table unique de visibilité des actions admin (RG38) ;
- la classification des réponses HTTP (RG3, RG24).

**RG2 — Formats d'affichage (locale `fr-FR`)**
- Distance : `distanceMeters / 1000`, arrondi HALF_UP à 2 décimales, séparateur décimal virgule, suffixe ` km`. Exemples : 0 → `0,00 km` ; 1000 → `1,00 km` ; 6706 → `6,71 km` ; 13412 → `13,41 km`.
- D+ : `elevationMeters` + ` m D+`. Exemple : 100 → `100 m D+`.
- Allure : `averagePaceSecondsPerKm` = s → `m:ss /km` avec `m = s div 60` et `ss = s mod 60` sur 2 chiffres. Exemples : 425 → `7:05 /km` ; 403 → `6:43 /km` ; 5 → `0:05 /km`. `null` → `—`.
- Temps de boucle : `loopTimeMillis` tronqué à la seconde, `m:ss` si < 3 600 s, sinon `h:mm:ss`. Exemples : 2 700 000 → `45:00` ; 3 725 000 → `1:02:05` ; 999 → `0:00`. `null` → `—`.
- Durée de boucle d'une course : `loopDuration` s → `h:mm:ss`. Exemples : 3600 → `1:00:00` ; 30 → `0:00:30`.
- Instants (`startedAt`, `scannedAt`, `serverTime`) : heure locale de l'appareil, `HH:mm:ss`. `raceDate` : `dd/MM/yyyy`.
- Statut coureur : `ACTIVE` → « En course » ; `WINNER` → « Vainqueur » ; `DNF` → « DNF » + raison + « au yard N » (`dnfYard`). Raisons : `VOLUNTARY` → « abandon volontaire », `TIMEOUT` → « hors délai », `MANUAL` → « décision de l'organisateur », `OTHER` → « autre ».
- Statut course : `SETUP` → « Non démarrée », `RUNNING` → « En cours », `FINISHED` → « Terminée ».
- Source de passage : `SCAN` → « scan », `MANUAL` → « corrigé ».

**RG3 — Interprétation des réponses de l'API**
Toute erreur de l'API est un `ProblemDetail` avec `status`, `code` et `detail` (RG5 inc. 3). La PWA affiche `detail` tel quel (messages exploitables), jamais une trace technique. Pour un 400 `VALIDATION_FAILED`, chaque élément de `errors` est affiché à côté du champ `field` correspondant. Une réponse non JSON (ex. page HTML 502 du reverse proxy) ou l'absence de réponse sont présentées comme « Serveur injoignable ». Classification commune, utilisée par tous les écrans :

| Réponse | Classe |
|---|---|
| 2xx | SUCCÈS |
| 401 | AUTH (identifiants absents ou invalides) |
| 403 | RÔLE (rôle insuffisant) |
| 409 avec `code = DATA_INTEGRITY` | TRANSITOIRE (le serveur demande de réessayer, RG6 inc. 3) |
| 400, 404, 405, 409 avec un autre `code`, 415 et tout autre 4xx | DÉFINITIF |
| 5xx, réponse non JSON, erreur réseau, délai dépassé (10 s pour le scan, 5 s pour le tableau de bord, 15 s pour l'admin) | TRANSITOIRE |

**RG4 — Décalage d'horloge avec le serveur**
À chaque réponse contenant `serverTime` (E4 et E19), la PWA mesure `décalage = serverTime − (t_envoi + t_réception) / 2`, où `t_envoi` et `t_réception` sont les instants de l'appareil à l'envoi de la requête et à la réception de la réponse. La dernière mesure remplace la précédente. Elle est conservée localement, même hors ligne, avec son instant de mesure. Le décalage sert :
- à l'horodatage des scans (RG19) ;
- au compte à rebours (RG31).
Si `|décalage| > 5 s`, l'écran de scan affiche « Horloge de l'appareil décalée de X s (corrigée automatiquement) ». Si aucune mesure n'existe, le décalage vaut 0 et l'écran de scan affiche « Horloge non vérifiée ». (PO8.)

**RG5 — Routes de la PWA**
Les routes sont une liste fermée. Chacune peut être ouverte directement (lien, favori, rechargement) :

| Route | Écran | Accès |
|---|---|---|
| `/` | Accueil, liste des courses | public |
| `/courses/{raceId}` | Tableau de bord | public |
| `/coureurs/{runnerId}` | Détail d'un coureur | public |
| `/inscription/{raceId}` | Inscription (lien public par course, PO16 inc. 3) | public |
| `/connexion` | Connexion | public |
| `/scan` | Scan | SCANNER ou ADMIN pour l'envoi (RG14) |
| `/admin` | Liste des courses (admin) | ADMIN |
| `/admin/courses/{raceId}` | Course et coureurs (admin) | ADMIN |
| `/admin/courses/{raceId}/qr` | Planche d'impression des QR codes | ADMIN |

Une route inconnue de l'application (ex. `/courses/1/inconnu`, `/admin/inconnu`, ou tout chemin atteint par navigation interne) affiche « Page introuvable » avec un lien vers l'accueil. Un chemin hors de la liste fermée de RG53 (ex. `/nimporte-quoi`), demandé directement au serveur, reste refusé par le serveur (RG53). Aucune route du front ne commence par `/api`.

---

## 3. Authentification

**RG6 — Connexion**
L'écran `/connexion` demande un nom d'utilisateur et un mot de passe (champ masqué, bouton « Afficher » facultatif). À la validation, la PWA appelle **E19 `GET /api/scan/me`** (RG52) avec `Authorization: Basic base64(nom:motdepasse)` :
- 200 : connexion réussie. Le rôle (`ADMIN` ou `SCANNER`) et `serverTime` (RG4) sont retenus. L'utilisateur est renvoyé vers l'écran demandé (`/scan` ou `/admin`).
- 401 : « Identifiants invalides », sans préciser si le nom ou le mot de passe est faux. Rien n'est conservé.
- Erreur TRANSITOIRE : « Connexion impossible : serveur injoignable ». Rien n'est conservé. Une connexion exige donc le réseau.
Un seul compte est connecté à la fois sur un appareil. Se connecter avec un autre compte remplace le précédent.

**RG7 — Conservation des identifiants (sécurité, PO6)**
HTTP Basic impose de renvoyer le mot de passe à chaque requête. Conserver les identifiants, c'est donc conserver le mot de passe, en clair ou en base64 (équivalent). Règles :
- **Compte ADMIN** : identifiants en **mémoire uniquement**. Ils ne sont jamais écrits dans `localStorage`, `sessionStorage`, IndexedDB, Cache Storage, un cookie ou l'URL. Ils sont perdus au rechargement ou à la fermeture : l'admin se reconnecte.
- **Compte SCANNER** : en mémoire par défaut. Si l'utilisateur coche « Rester connecté 24 h sur cet appareil » (décochée par défaut), la valeur `Authorization` est enregistrée dans le stockage local de l'origine (IndexedDB) avec une date d'expiration fixée à `instant de connexion + 24 h` (horloge de l'appareil). Au-delà, elle est effacée à la première lecture, et une connexion est demandée. Objectif : ne pas perdre la session quand le système tue l'application en arrière-plan pendant la course.
- Les identifiants n'apparaissent jamais dans une URL, un journal (`console`), un message d'erreur ou une réponse mise en cache par le service worker.
- Les champs de connexion portent `autocomplete="username"` et `autocomplete="current-password"`. L'enregistrement dans le gestionnaire de mots de passe du navigateur reste un choix de l'utilisateur (PO7).

**RG8 — Envoi de l'en-tête Authorization**
L'en-tête `Authorization` est envoyé **uniquement** aux requêtes `/api/scan/**` et `/api/admin/**` de l'origine de l'API. Il n'est **jamais** envoyé à `/api/public/**`, pour qu'un identifiant périmé ne provoque pas de 401 sur le tableau de bord ou l'inscription (PO21 inc. 3). Il n'est jamais envoyé à une autre origine.

**RG9 — Déconnexion**
Un bouton « Se déconnecter » est visible sur l'écran de scan et sur tous les écrans admin. Il efface les identifiants (mémoire et stockage) et renvoie à l'accueil. Il **n'efface pas** la file de scans. Si la file contient des scans en attente, une confirmation est demandée : « N scans en attente ne seront envoyés qu'après une nouvelle connexion. Se déconnecter ? » (boutons « Annuler », focus par défaut, et « Se déconnecter »).

**RG10 — Réponses 401 et 403**
- 401 sur une requête protégée : les identifiants sont effacés (mémoire et stockage) et l'écran de connexion s'affiche avec « Session expirée ou identifiants modifiés : reconnectez-vous ».
  - Pour le scan, la file est **suspendue sans rien perdre** (RG24). Elle reprend automatiquement après une connexion réussie.
  - Pour une action admin, l'action n'est pas exécutée. Elle n'est pas rejouée automatiquement après reconnexion : l'admin la refait.
- 403 : les identifiants sont conservés, le `detail` est affiché (« accès refusé pour le rôle SCANNER… »). Rien n'est réessayé automatiquement.
- Un compte SCANNER qui ouvre `/admin/**` voit « Accès réservé à l'administrateur » et un bouton « Se connecter en administrateur ». Aucune donnée admin n'est affichée.

---

## 4. Inscription publique

**RG11 — Page d'inscription `/inscription/{raceId}`**
Chargement de E2 (`GET /api/public/races/{raceId}`). La page affiche : nom, date, statut, distance de boucle (RG2, ex. `6,71 km`), durée de boucle (`1:00:00`), D+ par boucle.
- `registrationOpen = true` : formulaire avec un seul champ « Nom » (obligatoire, 1 à 255 caractères après suppression des espaces de début et de fin) et un bouton « S'inscrire ».
- `registrationOpen = false` : « Inscriptions fermées », sans formulaire.
- 404 : « Course introuvable ».
- 400 (id non numérique, ex. `/inscription/abc`) : « Course introuvable ».

**RG12 — Envoi et confirmation**
« S'inscrire » envoie une seule fois E3 (`POST /api/public/races/{raceId}/registrations`, `{"name": <nom sans espaces de début et de fin>}`). Le bouton est désactivé pendant la requête : un double clic ne crée qu'une inscription. En cas de 201, la page de confirmation affiche :
- le dossard (`bib`) en grand, et le nom ;
- le **QR code dont le contenu est exactement la valeur `qrToken`**, sans préfixe ni URL ;
- le `qrToken` en texte, sous le QR code, pour la saisie de secours (PO9) ;
- un bouton « Imprimer » et un bouton « Enregistrer l'image » ;
- l'avertissement : « Conservez ce QR code : il ne sera plus affiché. L'organisateur peut le réimprimer. »
Le QR code est généré localement, sans service tiers.

**RG13 — Erreurs d'inscription**
- Champ vide ou blanc : message sous le champ, aucune requête.
- 400 `VALIDATION_FAILED` : message du serveur sous le champ `name`.
- 409 `BUSINESS_CONFLICT` (inscriptions fermées entre l'affichage et l'envoi) : `detail` affiché, formulaire remplacé par « Inscriptions fermées ».
- 409 `DATA_INTEGRITY` (inscription simultanée, dossard en conflit) : **un seul** nouvel essai automatique après 1 s. Si le nouvel essai échoue aussi, `detail` affiché avec un bouton « Réessayer ».
- TRANSITOIRE : « Serveur injoignable, réessayez », sans nouvel essai automatique (une inscription n'est pas idempotente).

---

## 5. Scan et filet réseau

**RG14 — Capture indépendante du réseau et des identifiants**
L'écran `/scan` n'est pas lié à une course : le `qrToken` désigne le coureur, donc sa course. Un même appareil peut scanner des coureurs de plusieurs courses en parallèle.
Une fois l'application chargée au moins une fois (RG45), **la capture d'un scan ne dépend ni du réseau ni de la présence d'identifiants**. Seul l'envoi en dépend. Sans identifiants, l'écran affiche « Non connecté : envoi suspendu » et un bouton « Se connecter », et la capture reste possible.

**RG15 — Caméra**
- La caméra est activée par une action de l'utilisateur (bouton « Activer la caméra »). La caméra arrière (`facingMode: environment`) est préférée.
- Autorisation refusée : « Accès à la caméra refusé. Autorisez-le dans les réglages du navigateur, ou utilisez la saisie manuelle. » La saisie manuelle (RG18) s'affiche au premier plan.
- Aucune caméra ou API indisponible (contexte non sécurisé, navigateur ancien) : « Caméra indisponible sur cet appareil » et saisie manuelle au premier plan.
- Pendant le scan : maintien de l'écran allumé (Wake Lock) si le navigateur le permet. Bouton « Lampe » si la caméra expose une torche. Ces deux capacités sont optionnelles : leur absence n'empêche pas de scanner.
- La caméra est arrêtée en quittant l'écran de scan ou quand la page passe en arrière-plan. Elle reprend au retour si elle était active.

**RG16 — Validation locale du contenu lu**
Le texte lu (caméra ou saisie) est nettoyé de ses espaces de début et de fin, puis mis en minuscules. Il est accepté s'il respecte le format canonique d'UUID `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` (format du `qrToken`, RG16 inc. 3). Sinon, retour « QR non reconnu » (rouge, RG50) et **rien n'est ajouté à la file**.

**RG17 — Anti-rebond**
La caméra lit le même QR plusieurs fois par seconde. Une lecture d'un `qrToken` déjà capturé sur cet appareil moins de **10 s** auparavant (horloge de l'appareil, entre les deux captures) est ignorée : rien n'est ajouté, aucun son, et l'écran indique discrètement « déjà enregistré ». À 10 s pile ou plus, la lecture est une nouvelle capture. Des tokens différents ne sont jamais filtrés entre eux. (PO15.)

**RG18 — Saisie manuelle de secours**
L'écran de scan propose un champ texte « Code du QR » et un bouton « Valider ». La touche Entrée valide aussi, ce qui rend le champ compatible avec les lecteurs de codes-barres USB ou Bluetooth qui émulent un clavier. Les RG16, RG17 et RG19 s'appliquent à l'identique. Le champ est vidé après chaque capture acceptée.

**RG19 — Horodatage à la capture**
À l'instant où le contenu est accepté (RG16 et RG17), la PWA fixe :
- `scannedAt = instant de l'appareil + décalage` (RG4), sérialisé en ISO-8601 UTC à la milliseconde (`yyyy-MM-ddTHH:mm:ss.SSSZ`).
`scannedAt` est **fixé une fois pour toutes**. Il n'est jamais recalculé à l'envoi ni lors d'un nouvel essai, même si le décalage est remesuré entre-temps. C'est l'instant du scan qui détermine le yard (RG13 inc. 2), pas l'instant de réception.

**RG20 — File locale persistante**
Avant toute tentative d'envoi, chaque capture est écrite dans une file persistante (IndexedDB de l'origine) sous la forme d'un élément :
- `localId` (UUID généré sur l'appareil), `qrToken`, `scannedAt` (chaîne RG19), `capturedAt` (instant de l'appareil) ;
- `état` ∈ {`EN_ATTENTE`, `EN_COURS`, `ACCEPTÉ`, `REJETÉ`} ;
- `tentatives`, `prochainEssaiÀ`, `dernièreErreur` (`status`, `code`, `detail`) ;
- `réponse` (champs de `ScanResponse`, si acceptée) ;
- `vu` (booléen, pour les rejets).
Règles :
- Le retour « Enregistré » (RG50) n'est donné **qu'après** l'écriture réussie.
- Si l'écriture échoue (quota, navigation privée) : retour rouge « Échec de l'enregistrement local : notez le dossard », puis une tentative d'envoi directe unique, sans garantie de nouvel essai.
- La file survit au rechargement, à la fermeture de l'onglet ou de l'application, au redémarrage de l'appareil et aux mises à jour de la PWA (RG46).
- Au démarrage, tout élément resté `EN_COURS` (application tuée pendant un envoi) repasse `EN_ATTENTE` et sera renvoyé. L'idempotence (RG23) rend ce renvoi sans danger.
- Le format de stockage est versionné. Une montée de version migre les éléments, et un élément illisible n'est jamais supprimé automatiquement.

**RG21 — Ordre FIFO et émetteur unique**
- Les éléments `EN_ATTENTE` sont envoyés **un par un, dans l'ordre de capture** (`capturedAt`, puis ordre d'insertion). L'élément suivant n'est envoyé qu'une fois le précédent passé à un état final (`ACCEPTÉ` ou `REJETÉ`).
- Un élément en échec TRANSITOIRE **bloque** la file (tête de file) jusqu'à son succès ou son rejet définitif. C'est ce qui garantit l'ordre exigé par la règle du scan tardif (RG14 inc. 2, PO8 inc. 2) : le yard `k-1` d'un coureur est toujours envoyé avant son yard `k`.
- Il y a au plus **une requête de scan en cours par appareil**, même si plusieurs onglets ou fenêtres de la PWA sont ouverts. Un seul contexte est émetteur (verrou partagé entre onglets), les autres affichent l'état de la même file.
- L'ordre FIFO est garanti **par appareil** seulement. Entre deux appareils, aucun ordre n'est garanti (PO14).

**RG22 — Nouvel essai avec backoff**
- Après le `n`-ième échec TRANSITOIRE consécutif de l'élément de tête, le prochain essai a lieu après `délai(n) = min(2^(n−1), 60)` secondes, multiplié par un facteur aléatoire uniforme dans `[1,0 ; 1,2]`. Délais de base : 1, 2, 4, 8, 16, 32, 60, 60… s.
- Aucun nombre maximal d'essais : un scan n'est **jamais** abandonné automatiquement.
- Le compteur repart de 0 quand l'élément de tête change.
- Un essai immédiat, qui annule l'attente en cours, est déclenché par :
  - l'événement `online` du navigateur ;
  - le retour de l'application au premier plan ;
  - une connexion réussie ;
  - le bouton « Réessayer maintenant ».
- Une nouvelle capture déclenche le traitement de la file si aucune attente de backoff n'est en cours.
- L'envoi n'a lieu que lorsque l'application est ouverte. Tant que la file n'est pas vide, l'écran affiche « Gardez l'application ouverte : N scans en attente ». Si l'utilisateur ferme la page avec une file non vide, le navigateur demande confirmation quand il le permet (`beforeunload`).

**RG23 — Idempotence**
Chaque envoi d'un élément, y compris chaque nouvel essai et chaque renvoi manuel (RG25), transmet **exactement le même corps** à E6 : `{"qrToken": <qrToken>, "scannedAt": <scannedAt>}`, avec la chaîne `scannedAt` enregistrée à la capture. Si le serveur a enregistré le passage mais que la réponse s'est perdue, le nouvel essai reçoit 200 avec le passage existant (RG15 inc. 2, PO15 inc. 3) : aucun doublon n'est créé.

**RG24 — Traitement de chaque réponse du scan (E6)**

| Réponse de E6 | État de l'élément | Suite de la file | Affichage |
|---|---|---|---|
| 200 | `ACCEPTÉ`, `réponse` enregistrée | élément suivant | « Dossard {bib} — {runnerName} — yard {yardNumber} » ; si `runnerStatus` n'est pas `ACTIVE`, le statut est affiché aussi |
| 400 (`VALIDATION_FAILED`, `INVALID_INPUT`, `MALFORMED_REQUEST`), ex. scan « dans le futur » ou « antérieur au départ » | `REJETÉ` | élément suivant | `detail` |
| 401 | reste `EN_ATTENTE` (tête), file **suspendue** | bloquée jusqu'à la connexion | RG10 |
| 403 | reste `EN_ATTENTE`, file **suspendue** | bloquée jusqu'à une connexion avec un compte autorisé | `detail` + « Connectez-vous avec le compte scanner » |
| 404 (`qrToken` inconnu : coureur supprimé, QR d'une autre installation) | `REJETÉ` | élément suivant | `detail` |
| 409 `BUSINESS_CONFLICT` : scan tardif (pas de passage sur `k-1`), double scan (autre `scannedAt` sur le même yard), course non démarrée, course terminée, coureur DNF non réactivable, coureur WINNER, réactivation trop tardive (RG32 inc. 2) | `REJETÉ` (**définitif**) | élément suivant | `detail` + « À signaler à l'organisateur » |
| 409 `DATA_INTEGRITY` | reste `EN_ATTENTE` | nouvel essai (RG22) | « Conflit temporaire, nouvel essai » |
| 405, 415, autre 4xx | `REJETÉ` | élément suivant | `detail` + « Erreur technique » |
| 5xx, réponse non JSON, erreur réseau, délai de 10 s dépassé | reste `EN_ATTENTE` | nouvel essai (RG22) | « En attente de réseau » ou « Serveur indisponible » |

Un rejet définitif n'est jamais renvoyé automatiquement : le même corps produirait le même rejet. La PWA ne tente **aucun** arbitrage (ni DNF, ni réintégration). Le rejet est porté à la connaissance du bénévole, qui le signale à l'organisateur. Celui-ci décide, par exemple d'une réintégration (RG42).

**RG25 — Scans rejetés**
- Les éléments `REJETÉ` sont listés dans une zone « Rejetés » de l'écran de scan. Pour chacun : heure de capture (`HH:mm:ss`), dossard et nom s'ils sont connus (réponse d'un scan précédent du même token sur cet appareil), 4 derniers caractères du token, `status`, `code` et `detail`.
- Tant qu'au moins un rejet n'est pas marqué « vu », un compteur rouge « N rejetés » est affiché en permanence.
- « Marquer comme vu » masque le rejet du compteur, sans le supprimer.
- « Renvoyer » remet l'élément `EN_ATTENTE` **en fin de file**, avec le même corps (RG23). Cas d'usage : un scan du yard `k` rejeté parce que le yard `k-1` du coureur était encore dans la file d'un autre appareil (PO14).

**RG26 — Indicateurs de l'écran de scan**
L'écran affiche en permanence :
- l'état réseau : « En ligne », « Hors ligne » ou « Serveur injoignable » (dernier échec TRANSITOIRE de moins de 60 s) ;
- le compte connecté (« scanner » ou « admin ») ou « Non connecté » ;
- le nombre de scans en attente et le nombre de rejets non vus ;
- l'état de l'horloge (RG4) ;
- le dernier résultat, en grand (RG48) ;
- l'historique des 20 dernières captures de l'appareil, avec leur état.

**RG27 — Rétention locale**
- Les éléments `ACCEPTÉ` sont conservés 24 h après leur acceptation, puis supprimés.
- Les éléments `REJETÉ` sont conservés jusqu'à ce qu'ils soient marqués « vu », puis 7 jours.
- Les éléments `EN_ATTENTE` et `EN_COURS` ne sont **jamais** supprimés automatiquement.
- La mémoire de l'anti-rebond (RG17) n'a pas besoin d'être persistée.

---

## 6. Tableau de bord public

**RG28 — Accueil `/`**
Liste des courses (E1) dans l'ordre de l'API. Pour chacune : nom, date, statut, lien « Tableau de bord ». Si `registrationOpen = true`, lien « S'inscrire » vers `/inscription/{id}`. Liste chargée à l'affichage, avec un bouton « Actualiser ». Liens vers « Scan » et « Administration ».

**RG29 — Polling**
Le tableau de bord `/courses/{raceId}` appelle E4 (`GET /api/public/races/{raceId}/board`, sans `Authorization`, RG8) :
- le premier appel a lieu à l'ouverture ;
- chaque appel suivant part **2,5 s après la fin (réponse ou échec) du précédent** quand la course est `RUNNING`, et 10 s après sinon (`SETUP`, `FINISHED`). Pour une course `RUNNING` et une latence inférieure à 0,5 s, l'intervalle entre deux requêtes reste donc compris entre 2,5 et 3 s ;
- jamais deux requêtes E4 simultanées pour un même écran. Délai maximal d'une requête : 5 s ;
- le polling est suspendu quand la page est masquée (onglet en arrière-plan, écran verrouillé). Au retour, un appel immédiat est fait, puis le rythme normal reprend ;
- le polling s'arrête en quittant l'écran.

**RG30 — Données affichées**
- **En-tête** :
  - nom, date, statut de la course (RG2) ;
  - si `currentYard >= 1` : « Yard {currentYard} » et compte à rebours (RG31) ;
  - paramètres de boucle (distance, durée, D+) ;
  - si `SETUP` : « Course non démarrée » ;
  - si `FINISHED` : bandeau « Course terminée », suivi de « Vainqueur : dossard {bib} — {name} — {completedLoops} tours » pour le coureur de statut `WINNER`, ou de « sans vainqueur » s'il n'y en a aucun.
- **Une ligne (ou carte sur mobile) par coureur** :
  - dossard, nom (lien vers `/coureurs/{runnerId}`) ;
  - statut (RG2, avec la raison et le yard du DNF) ;
  - tours (`completedLoops`), distance, D+, allure (RG2) ;
  - badge « corrigé » si `corrected = true`.
- Le coureur `WINNER` est mis en évidence (icône et libellé, pas seulement une couleur). Les coureurs `DNF` sont affichés atténués, avec un contraste qui respecte RG48.
- Aucun `qrToken` n'est affiché (l'API ne le fournit pas, RG16 inc. 3).

**RG31 — Compte à rebours**
`restant = currentYardEndsAt − (instant de l'appareil + décalage)` (RG4, décalage mesuré sur la dernière réponse E4). Il est affiché en `m:ss` (`h:mm:ss` au-delà d'une heure) et rafraîchi chaque seconde localement. Si `restant <= 0`, l'écran affiche `0:00` et « Nouveau yard… » jusqu'à la prochaine réponse E4. **Le numéro de yard n'est jamais incrémenté localement** (RG1).

**RG32 — Ordre d'affichage**
Les coureurs sont affichés dans l'ordre de `runners` de E4 (dossard croissant, RG24 inc. 3). La PWA ne trie pas et ne classe pas (PO7 inc. 3, PO20).

**RG33 — Fraîcheur et erreurs**
- L'écran affiche « Mis à jour à HH:mm:ss » (heure de la dernière réponse réussie).
- Si la dernière réponse réussie date de plus de 10 s : bandeau « Données non actualisées depuis X s ». Les dernières données restent affichées, sans être effacées.
- 404 : « Course introuvable », polling arrêté.
- Erreur TRANSITOIRE : le polling continue au même rythme.
- Les mises à jour de valeurs ne sont pas annoncées par le lecteur d'écran. Seuls le bandeau de fraîcheur et le passage à `FINISHED` le sont.

**RG34 — Détail public d'un coureur `/coureurs/{runnerId}`**
Données de E5 :
- identité, statut et statistiques (comme RG30) ;
- passages par yard croissant : yard, source (« scan » ou « corrigé »), heure de scan (`—` si `null`), temps de boucle (RG2).
Polling selon RG29 (rythme de la course `raceId` du coureur, lu dans E2). 404 : « Coureur introuvable ».

**RG35 — Plusieurs courses en parallèle**
Chaque tableau de bord ne concerne qu'une course : son yard, son compte à rebours et ses coureurs, tous issus de sa réponse E4. Plusieurs tableaux de bord peuvent être ouverts simultanément (onglets ou appareils) : chacun a son propre polling. L'accueil liste toutes les courses, quel que soit leur statut.

---

## 7. Administration

**RG36 — Accès**
Les écrans `/admin/**` exigent une connexion ADMIN (RG6, RG7). Sans connexion, l'écran de connexion s'affiche, et **aucune requête `/api/admin/**` n'est envoyée** avant la connexion. Avec un compte SCANNER : RG10.

**RG37 — Gestion des courses**
- **Liste** (E8) : nom, date, statut, nombre de coureurs (longueur de E13, chargée à l'ouverture de la course), liens.
- **Création** (E7) : formulaire nom, date, distance de boucle (m), durée de boucle (s, avec l'aperçu `h:mm:ss` à côté de la saisie), D+ (m). Validation de format (RG1), puis les erreurs du serveur sont affichées par champ (RG3).
- **Modification** (E10) : même formulaire, prérempli.
- **Suppression** (E11) : confirmation « Supprimer la course {nom} et ses {n} inscrits ? Action irréversible. »
- **Démarrage** (E12) : confirmation « Démarrer {nom} maintenant ? L'heure de départ est celle du serveur. Les inscriptions seront fermées. Action irréversible. » Après succès, l'heure de départ (`startedAt`) est affichée.
Après chaque action réussie, la liste ou la course est rechargée depuis l'API. Chaque erreur est affichée (RG3).

**RG38 — Actions proposées selon le statut (table unique)**
Les boutons d'action sont affichés selon la table ci-dessous. Elle est définie **à un seul endroit** du code front. C'est une aide ergonomique : le serveur reste l'autorité, et un 409 éventuel (données périmées) est affiché puis suivi d'un rechargement.

| Statut course | Course | Coureur `ACTIVE` | Coureur `DNF` | Coureur `WINNER` |
|---|---|---|---|---|
| `SETUP` | modifier (tous champs), supprimer, démarrer, imprimer les QR | modifier (dossard, nom), supprimer, afficher le QR | — (impossible en SETUP) | — |
| `RUNNING` | modifier (nom et date ; champs de boucle en lecture seule), imprimer les QR | modifier (nom), afficher le QR, **déclarer DNF** | modifier (nom), afficher le QR, **réintégrer** | — |
| `FINISHED` | modifier (nom et date), imprimer les QR | modifier (nom), afficher le QR | modifier (nom), afficher le QR | modifier (nom), afficher le QR |

**RG39 — Coureurs d'une course `/admin/courses/{raceId}`**
Liste E13 (ordre de l'API) : dossard, nom, statut (RG2).
- Le `qrToken` est **masqué par défaut**. « Afficher le QR » ouvre une fenêtre avec le QR code (contenu = `qrToken`), le token en texte, et les boutons « Imprimer » et « Fermer ».
- Modification (E15) : formulaire dossard et nom.
- Suppression (E16) : confirmation « Supprimer {bib} — {nom} ? ».
- Lien vers le tableau de bord public de la course.

**RG40 — Planche d'impression des QR `/admin/courses/{raceId}/qr`**
Une vignette par coureur (ordre E13) : dossard en grand, nom, QR code d'au moins 3 cm de côté à l'impression. La mise en page d'impression masque la navigation et ne coupe pas une vignette entre deux pages.

**RG41 — DNF manuel avec confirmation**
- Le bouton « Déclarer DNF » (RG38) ouvre une fenêtre de confirmation (dialogue modal) qui récapitule le dossard, le nom et la course.
- L'admin doit choisir une raison parmi **exactement trois** : « Abandon volontaire » (`VOLUNTARY`), « Décision de l'organisateur » (`MANUAL`), « Autre » (`OTHER`). `TIMEOUT` n'est jamais proposé (réservé à l'auto-DNF). Aucune raison n'est présélectionnée.
- Le bouton « Confirmer le DNF » est désactivé tant qu'aucune raison n'est choisie. « Annuler » a le focus par défaut.
- « Annuler », la touche Échap ou la fermeture de la fenêtre : **aucune requête**.
- « Confirmer » : **une seule** requête E17 `{"reason": <valeur>}`. Le bouton est désactivé pendant la requête.
- Succès : le coureur est affiché `DNF`, avec la raison et « au yard {dnfYard} » de la réponse.
- Erreur : `detail` affiché (ex. 409 si le coureur a déjà été mis DNF par l'auto-DNF), puis la liste est rechargée.
- Le DNF manuel n'existe **que** dans l'administration : jamais sur l'écran de scan (CLAUDE.md).

**RG42 — Réintégration avec confirmation**
- Le bouton « Réintégrer » (RG38) ouvre une confirmation : « Réintégrer {bib} — {nom} (DNF {raison} au yard {dnfYard}) ? Cette action corrige une erreur : les passages manquants seront recréés, marqués "corrigé" et exclus du calcul de l'allure. »
- « Annuler » : aucune requête. « Confirmer » : une seule requête E18.
- Succès : le coureur est affiché `ACTIVE`, puis « Passages recréés : yards {liste des `yardNumber` de `recreatedPassages`} », ou « Aucun passage à recréer » si la liste est vide.
- Erreur : `detail` affiché, puis la liste est rechargée.

**RG43 — Actions admin : en ligne, sans rejeu ni double envoi**
Les actions admin (E7, E10 à E12, E15 à E18) ne sont **jamais** mises en file hors ligne ni réessayées automatiquement, car elles ne sont pas idempotentes. Hors ligne, ou en cas d'erreur TRANSITOIRE, le message est « Action impossible : serveur injoignable. Rien n'a été modifié côté application. Vérifiez l'état après reconnexion. » Tout bouton d'action est désactivé pendant sa requête.

---

## 8. PWA

**RG44 — Installabilité**
- Un manifeste d'application web est servi par l'origine de la PWA. Il contient :
  - `name` « Backyard Ultra Tracker », `short_name` « Backyard » ;
  - `lang` `fr`, `start_url` `/`, `scope` `/`, `display` `standalone` ;
  - `theme_color` et `background_color` ;
  - des icônes PNG 192×192 et 512×512, dont une `maskable`.
- Un service worker de portée `/` est enregistré : celui d'Angular (`/ngsw-worker.js`, PO1).
- L'application est servie en HTTPS en production (RG34 inc. 3). HTTP n'est accepté que sur `localhost` en développement et en E2E.
- La caméra et le service worker exigent un contexte sécurisé.

**RG45 — Hors ligne et cache**
- Le service worker met en cache les fichiers de l'application (HTML, JS, CSS, icônes, manifeste, bibliothèques QR). Après une première visite en ligne, **toutes les routes de RG5 s'affichent hors ligne**.
- `/scan` est **pleinement fonctionnel hors ligne** : capture, file et indicateurs.
- Les autres écrans affichent « Hors ligne : données indisponibles » (ou les dernières données reçues, marquées non actualisées, RG33).
- Les requêtes `/api/**` ne sont **jamais** mises en cache par le service worker (réseau uniquement), et aucune requête portant un en-tête `Authorization` n'est mise en cache.

**RG46 — Mises à jour**
Quand une nouvelle version est disponible, un bandeau « Nouvelle version disponible » propose un bouton « Mettre à jour ». Aucun rechargement n'est forcé. La mise à jour conserve la file de scans et les identifiants SCANNER mémorisés (RG20, RG7).

**RG47 — Stockage persistant**
À la première ouverture de `/scan`, la PWA demande au navigateur un stockage persistant (`navigator.storage.persist()`). Si ce stockage est refusé ou non supporté, l'écran de scan affiche une fois : « Le navigateur peut effacer les scans en attente s'il manque de place. Installez l'application et ne videz pas les données du site pendant la course. »

---

## 9. Accessibilité de base et usage en extérieur

**RG48 — Lisibilité**
- Contraste d'au moins 4,5:1 pour le texte et 3:1 pour les composants d'interface et les icônes porteuses d'information (WCAG 2.1 AA).
- Taille de texte de base d'au moins 16 px CSS. Résultat du dernier scan : au moins 32 px, en gras. Numéro de yard et compte à rebours du tableau de bord : au moins 32 px.
- Utilisable en portrait dès 360 px CSS de large sans défilement horizontal (tableau de bord en cartes sur mobile).
- Le zoom du navigateur n'est pas bloqué.

**RG49 — Cibles tactiles**
Tout élément actionnable de l'écran de scan, des confirmations et des actions admin mesure au moins 48×48 px CSS, avec au moins 8 px d'écart entre deux cibles. Les boutons destructifs (« Confirmer le DNF », « Supprimer ») ne sont jamais adjacents à « Annuler » sans cet écart, et sont visuellement distincts.

**RG50 — Retour du scan multicanal**
Chaque événement est signalé par une **couleur**, une **icône et un texte** (jamais la couleur seule), un **son** et une **vibration** (si l'appareil la supporte) :

| Événement | Couleur de bandeau | Texte | Son | Vibration |
|---|---|---|---|---|
| Capture enregistrée localement | bleu | « Enregistré — envoi… » (ou « Enregistré — en attente de réseau ») | 1 bip aigu court (≈ 880 Hz, 100 ms) | 50 ms |
| Accepté par le serveur (réponse en moins de 5 s après la capture) | vert | « Dossard {bib} — {runnerName} — yard {yardNumber} » | 2 bips aigus | 2 × 50 ms |
| QR non reconnu, ou rejet (réponse en moins de 5 s après la capture) | rouge | « QR non reconnu » ou `detail` | 3 bips graves (≈ 440 Hz) | 200-100-200 ms |
| Relecture ignorée par l'anti-rebond (RG17) | aucune | « déjà enregistré » (discret) | aucun | aucune |

- Un résultat serveur qui arrive plus de 5 s après sa capture (envoi différé) **ne déclenche ni son ni vibration**, pour ne pas le confondre avec le scan en cours. Il met seulement à jour l'historique et les compteurs.
- Un bouton « Son » permet de couper ou de rétablir le son. Ce réglage est mémorisé sur l'appareil. Le son est activé par défaut.

**RG51 — Accessibilité sémantique**
- `lang="fr"` sur le document.
- Chaque champ a une étiquette associée.
- Focus visible, navigation au clavier complète dans l'administration et l'inscription.
- Les fenêtres de confirmation sont des dialogues modaux : le focus y reste tant qu'elles sont ouvertes, la touche Échap équivaut à « Annuler », et le focus revient au bouton d'origine à la fermeture.
- Le résultat du scan est annoncé par une région `aria-live` (`assertive` pour les rejets, `polite` sinon).

---

## 10. Impacts backend et build (à implémenter a minima dans cet incrément)

**RG52 — E19 `GET /api/scan/me` (validation des identifiants et heure serveur)**
- Chemin sous `/api/scan/**` : accès SCANNER et ADMIN **sans modifier la matrice RG29 (inc. 3)**. Anonyme : 401. Identifiants faux : 401, sans `WWW-Authenticate` (RG30 inc. 3).
- Réponse 200 `SessionResponse` : `{"username": <nom du compte authentifié>, "role": "ADMIN" | "SCANNER", "serverTime": <Instant ISO-8601 UTC>}`.
- Justification : c'est le seul moyen de valider des identifiants SCANNER sans effet de bord (il n'existe aucun GET sous `/api/scan/**`). Il fournit en plus l'heure serveur à l'écran de scan, qui n'appelle aucun endpoint public (RG4). Le même endpoint sert à la connexion ADMIN (PO10).
- Architecture (RG1 et RG2 inc. 3) : le controller lit le nom et l'autorité de l'utilisateur authentifié fournis par Spring Security, sans aucune condition. `role` est le nom de l'autorité sans le préfixe `ROLE_`. `serverTime` provient d'un service qui lit la `Clock` : le controller n'injecte pas la `Clock`. Aucune migration.

**RG53 — Fichiers de la PWA servis par Spring Boot (PO27 inc. 3, PO2 option A)**
- Les fichiers produits par le build Angular sont copiés dans le jar (RG59) et servis par Spring Boot, sur la même origine que l'API.
- Accès `permitAll()` en **GET et HEAD** uniquement, sur une **liste fermée** de chemins, définie à un seul endroit de la configuration de sécurité :
  - fichiers : `/`, `/index.html`, `/manifest.webmanifest`, `/favicon.ico`, les fichiers du service worker Angular à la racine (`/ngsw-worker.js`, `/ngsw.json`, `/safety-worker.js`, `/worker-basic.min.js`), `/icons/**`, `/assets/**`, `/media/**`, et les fichiers à empreinte produits à la racine par le build (motifs d'**un seul segment** `/*.js` et `/*.css`) ;
  - routes du front de RG5, qui renvoient le contenu de `index.html` (statut 200) : `/courses/**`, `/coureurs/**`, `/inscription/**`, `/connexion`, `/scan`, `/admin`, `/admin/**`.
- Aucun motif générique de type `/**` n'est ouvert. Tout autre chemin hors `/api/**` reste refusé par `denyAll()` (401 anonyme). Les autres méthodes (POST, PUT, DELETE…) sur ces chemins restent refusées.
- Un chemin sous `/api/**` n'est **jamais** renvoyé vers `index.html` : une ressource d'API inexistante garde son comportement actuel.
- La matrice de `/api/**` est **inchangée** (RG29 inc. 3).
- En-têtes de cache : `Cache-Control: no-cache` pour `index.html` (y compris quand il est renvoyé pour une route du front), les fichiers du service worker et le manifeste ; cache long (`max-age` d'au moins un an, `immutable`) pour les fichiers dont le nom contient une empreinte.
- Types de contenu : `text/html` pour `index.html` et les routes du front, `application/manifest+json` pour le manifeste, `text/javascript` pour les fichiers `.js`, `application/json` pour `/ngsw.json`.

**RG54 — Aucun CORS (PO23 inc. 3, PO2 option A)**
La PWA et l'API sont sur la même origine : aucune configuration CORS n'est ajoutée, le refus actuel est conservé.
- Une requête portant un en-tête `Origin` étranger ne reçoit **aucun** en-tête `Access-Control-Allow-Origin`.
- Une requête préliminaire `OPTIONS` d'une origine étrangère n'est pas acceptée (réponse non 2xx, ou sans en-têtes CORS).

**RG55 — En-têtes de sécurité (portés par Spring Security, PO2 option A)**
Toute réponse HTML de la PWA (y compris `index.html` renvoyé pour une route du front) porte au minimum :
- `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self'; worker-src 'self'; manifest-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'`. Aucun `unsafe-eval`. Aucun `unsafe-inline` pour les scripts. Toute dérogation sur `style-src` relève de PO13.
- `Permissions-Policy: camera=(self), microphone=(), geolocation=()`
- `Referrer-Policy: no-referrer`
- `X-Content-Type-Options: nosniff`
L'en-tête HSTS est posé par le reverse proxy en production (RG34 inc. 3). Justification : les identifiants SCANNER peuvent être stockés dans le navigateur (RG7), et une injection de script les exposerait. Aucun script ni aucune ressource tierce (CDN, polices ou statistiques externes) n'est chargé.

**RG56 — Aucune API de test**
Aucun endpoint de manipulation du temps, de réinitialisation de données ou de déclenchement de la clôture n'est ajouté, dans aucun profil. Les tests E2E utilisent uniquement les endpoints E1 à E19, les comptes du profil `test` et des **propriétés** de configuration (RG57).

**RG59 — Build du front et définition de « fini » (PO1, PO4)**
(Numérotée après RG57 et RG58 de la section 12.E pour ne pas renuméroter les règles existantes.)
- Le code du front est sous `frontend/`.
- `mvn -B -f backend/pom.xml clean verify` :
  - installe les dépendances du front à partir du fichier de verrouillage (`npm ci`), avec une version de Node fixée dans le dépôt ;
  - exécute le build de production Angular ;
  - exécute les tests unitaires de la logique pure du front et contrôle le seuil de couverture ;
  - copie la sortie du build dans les ressources statiques du jar ;
  - **échoue** si l'une de ces étapes échoue, si un test unitaire du front échoue ou si le seuil de couverture n'est pas atteint.
- **Compile sans warning** : TypeScript en mode strict (`strict: true`, `strictTemplates: true`). Le build Angular ne produit aucun avertissement, y compris de budget.
- **Logique pure du front** : file de scans, backoff, classification des réponses, décalage d'horloge, formats d'affichage, table de visibilité des actions, expiration des identifiants, anti-rebond, validation du QR, compte à rebours et rétention. Elle est isolée dans des fichiers sans dépendance aux composants d'affichage, et testable sans backend ni `TestBed` Angular (horloge et stockage injectés ou simulés).
- **Couverture** : au moins **80 % de lignes** sur le périmètre de la logique pure, mesurée à chaque `mvn verify`. Le périmètre mesuré est déclaré dans la configuration (liste de répertoires ou de fichiers), sans exclusion de fichiers de ce périmètre. Aucun seuil sur les composants d'affichage.
- Les tests E2E (Playwright) ne font pas partie de `mvn verify` : ils sont lancés en local par une commande documentée (RG58).

---

## 11. Cas limites

**CL1 — Scan juste avant la bascule, envoyé après.** Scan capturé à `fin(N) − 2 s`, hors ligne, envoyé à `fin(N) + 5 s`. `scannedAt` est l'instant de capture (RG19), donc le passage va au yard N. Si le coureur a déjà été mis DNF par la clôture du yard N, il est réactivé (RG30 inc. 2) : réponse 200, `runnerStatus = ACTIVE`.
**CL2 — Scan envoyé alors que le yard N+1 est aussi clos.** 409 définitif (RG32 inc. 2). L'élément est `REJETÉ` et affiché. Seule une réintégration admin peut corriger.
**CL3 — Coureur réintégré.** Le tableau de bord affiche « En course » et le badge « corrigé ». L'allure exclut les passages MANUAL (valeur de l'API). Un scan ultérieur fonctionne normalement.
**CL4 — Passage manuel vs scan.** Détail coureur : passage MANUAL affiché « corrigé », heure `—`, temps de boucle `—`. Passage SCAN : heure et temps de boucle affichés.
**CL5 — Plusieurs courses actives.** Un même appareil de scan mélange des coureurs de deux courses : chaque passage va à la course du coureur. Chaque tableau de bord n'affiche que sa course (RG35).
**CL6 — Course non démarrée.** Scan : 409 définitif (course SETUP), élément `REJETÉ`. Tableau de bord : « Course non démarrée », pas de yard ni de compte à rebours, coureurs à 0 tour. Inscription ouverte.
**CL7 — Coureur sans aucun passage.** Tableau de bord : 0 tour, `0,00 km`, `0 m D+`, allure `—`.
**CL8 — Même coureur scanné par deux appareils sur le même yard.** Le second envoi a un `scannedAt` différent : 409 double scan, `REJETÉ` sur le second appareil. C'est normal, sans conséquence pour le coureur.
**CL9 — Horloge de l'appareil décalée.** Corrigée par RG4 et RG19. Sans mesure préalable, un appareil en avance produit un 400 « scan dans le futur » (rejet définitif, visible), d'où l'avertissement « Horloge non vérifiée ».
**CL10 — Longue coupure.** 200 scans en attente sont envoyés un par un, dans l'ordre, dès le retour du réseau. Aucun n'est perdu, et l'avancement est visible (compteur « en attente » décroissant).
**CL11 — Application tuée pendant un envoi.** L'élément `EN_COURS` repasse `EN_ATTENTE` au redémarrage et il est renvoyé : 200 idempotent s'il avait été enregistré.
**CL12 — Plusieurs onglets.** Un seul émetteur (RG21), aucun envoi en double simultané.
**CL13 — Déconnexion avec file non vide.** Confirmation (RG9). La file est conservée et reprend à la connexion suivante.
**CL14 — Double clic sur « S'inscrire » ou « Confirmer le DNF ».** Une seule requête (RG12, RG41).
**CL15 — Données admin périmées.** DNF manuel sur un coureur déjà mis DNF par l'auto-DNF : 409 affiché, puis rechargement.
**CL16 — Course terminée pendant que des scans attendent.** 409 définitif (RG31 inc. 2). Les éléments sont `REJETÉ`, le vainqueur est inchangé.
**CL17 — Navigation privée ou stockage refusé.** Échec d'écriture de la file : RG20 (retour rouge et tentative directe unique). Avertissement RG47.
**CL18 — QR étranger** (URL, texte quelconque) : « QR non reconnu », rien n'est ajouté à la file (RG16).
**CL19 — Caméra refusée ou absente.** Saisie manuelle (RG15, RG18).
**CL20 — Course terminée sans vainqueur.** Bandeau « Course terminée — sans vainqueur ».
**CL21 — Identifiants SCANNER expirés (24 h) pendant une coupure.** La capture continue (RG14). L'envoi reprend après reconnexion.
**CL22 — Mot de passe changé côté serveur (redémarrage, PO26 inc. 3).** Premier envoi : 401, file suspendue, écran de connexion. Rien n'est perdu.

---

## 12. Critères d'acceptation

Types de test :
- **[back-slice]** : `@WebMvcTest` avec la sécurité réelle ;
- **[back-IT]** : `*IT`, contexte complet ;
- **[front-unit]** : test unitaire de la logique pure du front (RG59), sans backend ni `TestBed` Angular. Horloge et stockage simulés autorisés ;
- **[build]** : contrôle de la commande `mvn -B -f backend/pom.xml clean verify`, de sa sortie et de ses artefacts, cité dans le rapport avec la commande, la date et les chiffres ;
- **[E2E]** : parcours Playwright dans un vrai navigateur, sous Chromium et WebKit, contre un backend réel (RG57, RG58).

Comptes de test (RG32 inc. 3) : ADMIN `admin-test` / `admin-secret`, SCANNER `scanner-test` / `scanner-secret`.

### A. Backend

**CA1 — E19 [back-slice] (RG52, RG6)**
Donné une `Clock` fixe à `2026-10-03T10:20:00Z` :
- `GET /api/scan/me` avec le compte SCANNER : 200, `{"username":"scanner-test","role":"SCANNER","serverTime":"2026-10-03T10:20:00Z"}` ;
- avec le compte ADMIN : 200, `role = "ADMIN"`, `username = "admin-test"` ;
- sans `Authorization` : 401 `UNAUTHENTICATED`, sans en-tête `WWW-Authenticate` ;
- avec `admin-test:mauvais` : 401.
Aucune réponse ne contient `Set-Cookie`.

**CA2 — E19 sans logique dans le controller [back-slice, architecture] (RG52)**
Le test d'architecture existant (CA45 inc. 3) passe avec le nouveau controller : il n'injecte ni `Clock`, ni repository, ni calculateur. La matrice RG29 (inc. 3) est inchangée : CA46 à CA55 (inc. 3) restent verts sans modification.

**CA3 — Fichiers de la PWA servis par Spring Boot [back-IT] (RG53, RG5)**
Sans authentification :
- `GET /` : 200 `text/html`, `Cache-Control` contenant `no-cache` ;
- `HEAD /` : 200 ;
- `GET /manifest.webmanifest` : 200 `application/manifest+json` ;
- `GET /ngsw-worker.js` : 200 `text/javascript`, `Cache-Control` contenant `no-cache` ;
- `GET /ngsw.json` : 200 `application/json`, `Cache-Control` contenant `no-cache` ;
- un fichier `.js` à empreinte référencé par `index.html` : 200 `text/javascript`, `Cache-Control` contenant `immutable` ;
- `GET /courses/1`, `GET /coureurs/1`, `GET /admin/courses/1/qr`, `GET /inscription/1`, `GET /scan`, `GET /connexion` : 200 `text/html`, avec le contenu de `index.html` et `Cache-Control` contenant `no-cache` ;
- `GET /application.properties`, `GET /nimporte-quoi`, `GET /assets/../application.properties` : pas 200 ;
- `POST /` et `POST /courses/1` : pas 2xx ;
- `GET /api/autre` : 401, sans contenu HTML ;
- `GET /api/admin/races` : 401.
Avec le compte SCANNER : `GET /api/admin/races` renvoie 403, et `GET /api/public/races/999999` renvoie 404 `ProblemDetail` (JSON, pas `index.html`) : matrice et erreurs de l'API inchangées.

**CA4 — Aucun CORS [back-IT] (RG54)**
- `GET /api/public/races` avec `Origin: https://malveillant.example` : la réponse ne contient pas `Access-Control-Allow-Origin`.
- `OPTIONS /api/admin/races` avec `Origin: https://malveillant.example`, `Access-Control-Request-Method: POST` et `Access-Control-Request-Headers: authorization,content-type` : réponse non 2xx, ou sans `Access-Control-Allow-Origin`.
- `GET /` avec `Origin: https://malveillant.example` : pas d'`Access-Control-Allow-Origin`.

**CA5 — Build intégré et définition de « fini » du front [build] (RG59, PO4)**
Donné un dépôt propre (sans `frontend/node_modules` ni sortie de build). Quand on exécute `mvn -B -f backend/pom.xml clean verify` :
- la commande se termine avec le code 0 ;
- sa sortie montre l'exécution de `npm ci`, du build de production Angular et des tests unitaires du front, avec un nombre de tests exécutés strictement positif et 0 échec ;
- le rapport de couverture du front indique au moins 80,0 % de lignes sur le périmètre de la logique pure déclaré (RG59), et ce périmètre contient au moins les fichiers de la file de scans, du backoff, de la classification, du décalage d'horloge, des formats, de la table de visibilité et de l'expiration des identifiants ;
- la sortie du build Angular et du compilateur TypeScript ne contient aucun avertissement (`WARNING` ou `Warning`) ;
- le jar produit contient `index.html`, `manifest.webmanifest`, `ngsw-worker.js` et `ngsw.json` dans ses ressources statiques.
Revue de configuration : `tsconfig` porte `strict: true` et `strictTemplates: true` ; le seuil de 80 % est configuré comme bloquant ; la version de Node est fixée dans le dépôt.
Contrôle négatif : avec un test unitaire front volontairement en échec (modification locale non commitée), la même commande se termine avec un code différent de 0.

**CA6 — En-têtes de sécurité [back-IT] (RG55)**
`GET /` et `GET /courses/1` (réponses HTML de la PWA) portent :
- `Content-Security-Policy`, qui contient `default-src 'self'`, `frame-ancestors 'none'` et `script-src 'self'`, sans `unsafe-eval` ;
- `Permissions-Policy` contenant `camera=(self)` ;
- `Referrer-Policy: no-referrer` ;
- `X-Content-Type-Options: nosniff`.
`GET /api/public/races` porte `X-Content-Type-Options: nosniff`.

**CA7 — Aucune API de test [back-IT] (RG56)**
Au démarrage du contexte complet avec le profil `test`, la liste des correspondances de Spring MVC ne contient que E1 à E19, les ressources statiques et les renvois vers `index.html` de RG53. Aucun chemin ne contient `test`, `clock`, `time`, `reset` ou `close`.

### B. Logique front (tests unitaires)

**CA8 — Formats [front-unit] (RG2)**
- Distance : 0 → `0,00 km` ; 1000 → `1,00 km` ; 6706 → `6,71 km` ; 13412 → `13,41 km` ; 6705 → `6,71 km` (HALF_UP) ; 6704 → `6,70 km`.
- Allure : 425 → `7:05 /km` ; 403 → `6:43 /km` ; 5 → `0:05 /km` ; `null` → `—`.
- Temps de boucle : 2 700 000 → `45:00` ; 3 725 000 → `1:02:05` ; 999 → `0:00` ; `null` → `—`.
- Durée de boucle : 3600 → `1:00:00` ; 30 → `0:00:30`.
- D+ : 100 → `100 m D+`.
- Statut : `DNF` / `TIMEOUT` / 3 → « DNF hors délai au yard 3 » ; `WINNER` → « Vainqueur ».

**CA9 — Classification des réponses [front-unit] (RG3, RG24)**

| Réponse | Classe | État de l'élément |
|---|---|---|
| 200 | SUCCÈS | `ACCEPTÉ` |
| 400 `INVALID_INPUT` | DÉFINITIF | `REJETÉ` |
| 401 | AUTH | `EN_ATTENTE`, file suspendue |
| 403 | RÔLE | `EN_ATTENTE`, file suspendue |
| 404 | DÉFINITIF | `REJETÉ` |
| 409 `BUSINESS_CONFLICT` | DÉFINITIF | `REJETÉ` |
| 409 `DATA_INTEGRITY` | TRANSITOIRE | `EN_ATTENTE` |
| 415 | DÉFINITIF | `REJETÉ` |
| 500 | TRANSITOIRE | `EN_ATTENTE` |
| 502 avec un corps HTML | TRANSITOIRE | `EN_ATTENTE` |
| erreur réseau | TRANSITOIRE | `EN_ATTENTE` |
| délai de 10 s dépassé | TRANSITOIRE | `EN_ATTENTE` |

**CA10 — Backoff [front-unit] (RG22)**
- Pour `n` = 1 à 8 échecs consécutifs, le délai de base vaut 1, 2, 4, 8, 16, 32, 60 et 60 s. Sur 1 000 tirages, chaque délai effectif est compris entre la base et 1,2 fois la base.
- Donné un élément en attente de 32 s. Quand l'événement `online` survient. Alors l'essai a lieu immédiatement.
- Après 20 échecs, l'élément est toujours `EN_ATTENTE` : aucun abandon.

**CA11 — Décalage d'horloge et horodatage [front-unit] (RG4, RG19)**
- Donné `t_envoi = 10:00:00.000`, `t_réception = 10:00:00.400` (appareil) et `serverTime = 10:00:05.200Z`. Alors `décalage = +5,000 s`.
- Capture à `10:01:00.123` appareil : `scannedAt = "…T10:01:05.123Z"`.
- Donné ensuite une nouvelle mesure `décalage = +1,000 s`. Alors le `scannedAt` de l'élément déjà capturé est inchangé.
- Sans aucune mesure : `décalage = 0` et l'état « Horloge non vérifiée » est signalé.
- `|décalage| = 5,001 s` : avertissement affiché. `|décalage| = 5,000 s` : pas d'avertissement.

**CA12 — Validation locale du QR [front-unit] (RG16)**
- `" 3F2C9A4E-8B1D-4C7E-9F00-1A2B3C4D5E6F "` : accepté, normalisé en `3f2c9a4e-8b1d-4c7e-9f00-1a2b3c4d5e6f`.
- `https://exemple.fr/r/3f2c...`, `""` et `3f2c9a4e8b1d4c7e9f001a2b3c4d5e6f` (sans tirets) : refusés, file inchangée.

**CA13 — Anti-rebond [front-unit] (RG17)**
Token T capturé à `10:00:00.000` :
- T à `10:00:09.999` : ignoré (file de taille 1) ;
- T à `10:00:10.000` : capturé (taille 2) ;
- U ≠ T à `10:00:00.500` : capturé.

**CA14 — FIFO et blocage en tête [front-unit] (RG21, RG22, RG24)**
Donné la file [S1, S2, S3], toutes `EN_ATTENTE`, et un serveur simulé :
- S1 reçoit une erreur réseau : S2 n'est pas envoyé ; au plus 1 requête en cours à tout instant.
- S1 reçoit ensuite 200 : S2 est envoyé, puis S3, dans cet ordre.
- Variante : S2 reçoit 409 `BUSINESS_CONFLICT` : S2 `REJETÉ`, et S3 est envoyé ensuite.
- Variante : S1 reçoit 401 : aucune requête pour S2 ni S3 tant qu'aucune connexion n'a eu lieu ; après la connexion, S1 est renvoyé en premier.

**CA15 — Idempotence du corps [front-unit] (RG23)**
Pour un élément envoyé 3 fois (2 erreurs réseau, puis 200), puis renvoyé manuellement après un rejet simulé, les 4 corps émis sont identiques octet pour octet, et leur `scannedAt` est égal à la valeur enregistrée à la capture.

**CA16 — Reprise après interruption [front-unit] (RG20)**
Donné un stockage simulé contenant un élément `EN_COURS` et deux `EN_ATTENTE`. Quand la file est rechargée (démarrage). Alors 3 éléments `EN_ATTENTE`, dans l'ordre d'origine. Donné un stockage au format de version précédente. Quand la file est ouverte. Alors les éléments sont migrés sans perte.

**CA17 — Rétention [front-unit] (RG27)**
Avec une horloge simulée :
- un `ACCEPTÉ` est présent à acceptation + 23 h 59 min et absent à + 24 h 01 min ;
- un `REJETÉ` non vu est toujours présent à + 30 jours ; marqué vu à J, il est présent à J + 6 jours 23 h et absent à J + 7 jours 1 h ;
- un `EN_ATTENTE` est présent à + 30 jours.

**CA18 — Expiration des identifiants SCANNER [front-unit] (RG7)**
Connexion SCANNER avec « Rester connecté 24 h » à `08:00:00`. À `+23:59:59`, les identifiants sont lus. À `+24:00:00`, ils sont effacés et la connexion est demandée. Connexion ADMIN : aucun appel d'écriture vers le stockage persistant (stockage simulé espionné).

**CA19 — Compte à rebours [front-unit] (RG31)**
Donné `currentYardEndsAt = 11:00:00Z`, `décalage = +5 s`, appareil à `10:59:30`. Alors `restant = 0:25`. À `10:59:55` appareil : `0:00` et « Nouveau yard… », et le numéro de yard affiché reste celui de la dernière réponse.

**CA20 — Table de visibilité des actions [front-unit] (RG38)**
Pour les 12 combinaisons (statut course × statut coureur, plus la course seule), l'ensemble des actions proposées est exactement celui de la table RG38. Par exemple : `RUNNING` × `ACTIVE` = {modifier le nom, afficher le QR, déclarer DNF} ; `RUNNING` × `DNF` = {modifier le nom, afficher le QR, réintégrer} ; `SETUP` × course = {modifier, supprimer, démarrer, imprimer}. Revue : la table n'est définie qu'à un seul endroit du code front.

### C. Parcours E2E (réserve RT2)

Conventions communes à tous les CA E2E (voir RG57 et RG58) :
- `T0` = `startedAt` renvoyé par E12.
- Les instants `T0 + x s` sont des cibles, avec une marge d'au moins 5 s par rapport à toute cloche.
- Les attentes sont des assertions avec délai maximal. Le seul délai fixe autorisé est l'attente d'une cloche.
- La vérification « côté serveur » se fait par l'API (E4, E5, E13), depuis le test et non depuis la page.

**CA21 — Routes et liens directs [E2E] (RG5, RG45)**
Pour chaque route de RG5 (avec des ids existants), l'ouverture directe de l'URL dans un contexte neuf (sans service worker), puis un rechargement, affichent l'écran attendu. L'ouverture directe de `/courses/{id}/inconnu` et de `/admin/inconnu` affiche « Page introuvable » avec un lien vers l'accueil.

**CA22 — Inscription [E2E] (RG11, RG12, RG2)**
Donné la course `E2E-INS-{run}` (1000 m, 3600 s, 10 m D+), `SETUP`, sans coureur.
- Quand on ouvre `/inscription/{id}`. Alors la page affiche le nom, `1,00 km`, `1:00:00`, `10 m D+` et le formulaire.
- Quand on s'inscrit « Alice ». Alors la confirmation affiche le dossard `1` et « Alice ».
- Le QR code affiché, décodé par le test, vaut exactement le `qrToken` du coureur 1 (lu par E13). Le token affiché en texte fait 36 caractères.
- Quand on s'inscrit ensuite « Bob », puis « Chloé ». Alors les dossards affichés sont `2` et `3`.

**CA23 — Inscription : erreurs [E2E] (RG11, RG13, CL14)**
- Nom « `   ` » : message sous le champ, aucune requête E3 (journal réseau).
- Double clic rapide sur « S'inscrire » avec « Dan » : exactement une requête E3 ; E13 compte 4 coureurs.
- Après démarrage de la course par l'API, `/inscription/{id}` affiche « Inscriptions fermées », sans formulaire.
- `/inscription/999999` affiche « Course introuvable ».

**CA24 — Connexion et rôles 401/403 [E2E] (RG6, RG10, RG36)**
- `/admin` sans connexion : écran de connexion, et aucune requête `/api/admin/**` émise (journal réseau).
- Connexion `admin-test:mauvais` : « Identifiants invalides ». Rien dans `localStorage`, `sessionStorage`, IndexedDB, Cache Storage ni dans les cookies.
- Connexion `scanner-test:scanner-secret` depuis `/scan` : connecté « scanner ».
- Puis ouverture de `/admin` : « Accès réservé à l'administrateur », aucune donnée de course admin affichée. Toute requête `/api/admin/**` éventuellement émise a reçu 403.
- Connexion `admin-test:admin-secret` : la liste des courses admin s'affiche.
- Interception de la **prochaine** réponse de E6 remplacée par un 401 (outil E2E, côté navigateur) pendant un scan : écran de connexion avec « Session expirée… ». L'élément est toujours « en attente ». Après reconnexion SCANNER, il est `ACCEPTÉ` sans nouvelle capture.

**CA25 — Stockage des identifiants [E2E] (RG7, RG9)**
- Après connexion ADMIN : ni le mot de passe `admin-secret`, ni la chaîne base64 `YWRtaW4tdGVzdDphZG1pbi1zZWNyZXQ=`, ne figurent dans `localStorage`, `sessionStorage`, IndexedDB, Cache Storage ou les cookies. Après rechargement de `/admin`, la connexion est redemandée.
- Connexion SCANNER **sans** « Rester connecté » : même constat pour `c2Nhbm5lci10ZXN0OnNjYW5uZXItc2VjcmV0`, et connexion redemandée après rechargement.
- Connexion SCANNER **avec** « Rester connecté » : après rechargement, toujours connecté.
- « Se déconnecter » : la valeur a disparu de tout stockage.
- Avec 2 scans en attente (hors ligne), « Se déconnecter » demande une confirmation. « Annuler » garde la session. Confirmer déconnecte, et les 2 scans restent « en attente ».

**CA26 — Pas d'`Authorization` vers le public [E2E] (RG8)**
Connecté SCANNER (avec « Rester connecté »), on ouvre `/`, `/courses/{id}` pendant 10 s, `/coureurs/{id}` et `/inscription/{id}`. Aucune requête `/api/public/**` du journal réseau ne porte d'en-tête `Authorization`, et aucune ne reçoit 401.

**CA27 — Scan nominal à la caméra [E2E] (RG15, RG16, RG19, RG24, RG50)**
Donné la course `E2E-SCAN-{run}` (1000 m, 3600 s), `RUNNING`, avec Alice (dossard 1), Bob (2) et Eve (3), et un navigateur dont la caméra simulée diffuse une vidéo du QR d'Alice. Connecté SCANNER, en ligne.
- Quand on active la caméra à `T0 + 10 s`. Alors, en moins de 5 s : bandeau vert « Dossard 1 — Alice — yard 1 », et une seule capture malgré la lecture continue (anti-rebond).
- Côté serveur (E5) : un passage yard 1, `SCAN`, dont `scannedAt` est égal à celui de la requête E6 émise, et compris entre l'instant d'activation et l'instant d'affichage.
Navigateurs (RG58) : le volet caméra est exécuté sous Chromium (flux caméra simulé). Sous WebKit, où l'outil ne fournit pas de flux caméra simulé, le même parcours est exécuté par la saisie manuelle du token d'Alice (RG18), avec les mêmes attendus hors anti-rebond de la caméra.

**CA28 — Caméra refusée et saisie manuelle [E2E] (RG15, RG18, RG16)**
- Permission caméra refusée (contexte de navigateur sans autorisation) : message de refus, et champ « Code du QR » visible.
- Saisie du token de Bob (dossard 2), puis touche Entrée : bandeau vert « Dossard 2 — Bob — yard 1 ».
- Saisie de `bonjour`, puis Entrée : « QR non reconnu », aucune requête E6.

**CA29 — Coupure réseau, reprise FIFO et réactivation [E2E] (RG14, RG19 à RG24, CL1)**
Donné la course `E2E-FIFO-{run}` (1000 m, **30 s**, 10 m), avec Alice (1) et Bob (2), démarrée à `T0`. Connecté SCANNER **avec « Rester connecté 24 h »** (indispensable au rechargement de l'étape 2), en ligne. Au départ, Bob est scanné à `T0 + 5 s` (en ligne, 200).
1. À `T0 + 8 s` : contexte **hors ligne**. Saisie du token d'Alice à `T0 + 10 s`. Alors bandeau bleu « Enregistré — en attente de réseau », compteur « 1 en attente », et E5 (depuis le test) ne montre aucun passage pour Alice.
2. Rechargement de `/scan` hors ligne : la page s'affiche (service worker) et le compteur indique toujours « 1 en attente » (persistance).
3. Attente de `T0 + 33 s` (yard 2 ; clôture du yard 1 faite). Côté serveur : Alice `DNF` `TIMEOUT` `dnfYard 1`, Bob `ACTIVE`.
4. À `T0 + 36 s`, toujours hors ligne : saisie du token d'Alice. Compteur « 2 en attente ». Saisie du token de Bob : « 3 en attente ».
5. À `T0 + 40 s` : contexte **en ligne**. Alors, avant `T0 + 50 s`, le compteur revient à 0, sans aucun rejet. Journal réseau : trois requêtes E6 émises dans l'ordre Alice (scan du yard 1), Alice (yard 2), Bob (yard 2). Chaque requête n'est émise qu'après la réponse de la précédente.
6. Côté serveur (E5) : Alice `ACTIVE`, passages yard 1 et yard 2 `SCAN`, dont les `scannedAt` sont ceux capturés aux étapes 1 et 4 (à la milliseconde, égaux aux corps émis). Bob : passages yard 1 et yard 2. La première réponse d'Alice a `yardNumber = 1` et `runnerStatus = "ACTIVE"` (réactivation).

**CA30 — Backoff sur erreur serveur [E2E] (RG22, RG24)**
Donné la course de CA27, en ligne. Les deux premières requêtes E6 sont interceptées et reçoivent 503. Eve (3) n'a jamais été scannée. Saisie du token d'Eve.
- Trois requêtes E6 au total, de corps identiques.
- Intervalle entre la 1re et la 2e d'au moins 1 s ; intervalle entre la 2e et la 3e d'au moins 2 s.
- Pendant les 503 : élément « en attente » (aucun abandon), indicateur « Serveur injoignable ».
- Élément finalement `ACCEPTÉ` : « Dossard 3 — Eve — yard 1 ». E5 : un seul passage pour Eve.

**CA31 — Rejet définitif et poursuite de la file [E2E] (RG24, RG25, CL2)**
Donné la course `E2E-REJ-{run}` (1000 m, 30 s), avec Chloé (1) jamais scannée, et Dan (2). Démarrée à `T0`. Dan est scanné en ligne à `T0 + 5 s` (yard 1) et à `T0 + 35 s` (yard 2).
- À `T0 + 65 s` (yard 3 ; Chloé DNF au yard 1 depuis `T0 + 30 s`), hors ligne : saisie de Chloé, puis de Dan. Retour en ligne à `T0 + 68 s`.
- Chloé : `REJETÉ`, avec le `detail` du serveur (409 `BUSINESS_CONFLICT`), bandeau et compteur rouges « 1 rejeté ».
- Dan est envoyé **après** Chloé et reçoit 200 « Dossard 2 — Dan — yard 3 » : la file ne s'est pas bloquée sur le rejet.
- « Marquer comme vu » : compteur à 0, rejet toujours listé.
- « Renvoyer » : une nouvelle requête E6 au corps identique, de nouveau `REJETÉ`.

**CA32 — Tableau de bord : bascule de yard, auto-DNF, badge « corrigé », vainqueur [E2E] (RG29 à RG31, RG33, CL3, CL6, CL7)**
Donné la course `E2E-DASH-{run}` (1000 m, **30 s**, 10 m), avec Alice (1), Bob (2) et Chloé (3), `SETUP`. Un onglet ouvre `/courses/{id}` en anonyme. **Aucun rechargement de cet onglet pendant tout le parcours** (une seule navigation au journal).
1. Avant le départ : « Course non démarrée » ; 3 coureurs, 0 tour, `0,00 km`, allure `—`.
2. Démarrage par l'admin (autre onglet). En moins de 13 s (rythme de 10 s en `SETUP`, RG29) : « Yard 1 », et un compte à rebours compris entre `0:30` et `0:15`.
3. Scans d'Alice et Bob à `T0 + 5 s`. Avant `T0 + 16 s` (le premier poll peut encore suivre le rythme de 10 s de `SETUP`) : Alice et Bob à 1 tour, `1,00 km`, `10 m D+`, allure différente de `—` ; Chloé à 0 tour.
4. Après la cloche `T0 + 30 s`, avant `T0 + 36 s` (clôture en 1 s au plus, puis poll en 3 s au plus) : « Yard 2 » ; Chloé « DNF hors délai au yard 1 » ; Alice et Bob « En course ».
5. À `T0 + 38 s`, l'admin réintègre Chloé (CA35). Avant `T0 + 42 s` : Chloé « En course », badge « corrigé », 1 tour, `1,00 km`, allure `—`.
6. Scan d'Alice seule à `T0 + 45 s`. Après la cloche `T0 + 60 s`, avant `T0 + 65 s` : bandeau « Course terminée », « Vainqueur : dossard 1 — Alice — 2 tours » ; Bob « DNF hors délai au yard 2 » ; Chloé « DNF hors délai au yard 2 », badge « corrigé » toujours présent.
7. Journal réseau, sur une fenêtre de 10 s pendant le yard 2 : entre 3 et 5 requêtes E4, jamais deux simultanées, sans `Authorization`.

**CA33 — Fraîcheur du tableau de bord [E2E] (RG33)**
Tableau de bord d'une course `RUNNING` ouvert. Les requêtes E4 sont bloquées (interception, erreur réseau) pendant 15 s. Alors, après plus de 10 s, le bandeau « Données non actualisées depuis X s » apparaît, et les dernières valeurs restent affichées. Quand le blocage est levé, le bandeau disparaît en moins de 4 s.

**CA34 — DNF manuel avec confirmation [E2E] (RG41, RG38, RG43)**
Donné la course `E2E-ADM-{run}` (1000 m, **3600 s**), `RUNNING`, avec Bob (2) `ACTIVE` sans passage. Connecté ADMIN sur `/admin/courses/{id}`.
- « Déclarer DNF » sur Bob : dialogue affichant « 2 — Bob ». Exactement 3 raisons (abandon volontaire, décision de l'organisateur, autre), aucune présélectionnée. « Confirmer le DNF » désactivé.
- « Annuler » : aucune requête E17 (journal réseau) ; E13 : Bob `ACTIVE`.
- Même chose avec la touche Échap.
- Réouverture, choix « Abandon volontaire », double clic sur « Confirmer » : exactement une requête E17 de corps `{"reason":"VOLUNTARY"}`. L'écran affiche « DNF abandon volontaire au yard 1 ». E13 : `DNF`, `VOLUNTARY`, `dnfYard 1`.
- Aucun bouton de DNF n'existe sur `/scan`.

**CA35 — Réintégration avec confirmation [E2E] (RG42)**
Suite de CA34 (Bob `DNF` au yard 1, course au yard 1) :
- « Réintégrer » affiche la confirmation. « Annuler » : aucune requête E18.
- « Confirmer » : une seule requête E18. L'écran affiche Bob « En course » et « Aucun passage à recréer » (intervalle vide, CA46 inc. 2).
Dans CA32 (étape 5 : Chloé `DNF` au yard 1, réintégrée pendant le yard 2), l'écran admin affiche « Passages recréés : yards 1 ».

**CA36 — Administration des courses et des coureurs [E2E] (RG37, RG39, RG40, RG43)**
Connecté ADMIN :
- Création de `E2E-CRUD-{run}` (6706 m, 3600 s, 50 m) : elle apparaît dans la liste, statut « Non démarrée ». L'aperçu de 3600 s affiche `1:00:00`.
- Création avec une distance de 0 : erreur sous le champ distance, aucune course créée.
- Création d'un doublon de nom : `detail` du 409 affiché.
- Deux inscriptions par l'API. Planche QR : 2 vignettes avec les dossards 1 et 2, dont les QR décodés égalent les `qrToken` de E13.
- « Afficher le QR » du dossard 1 : QR et token affichés ; masqués par défaut dans la liste.
- Modification du dossard 2 en 5 : E13 montre le dossard 5.
- Suppression du dossard 5 avec confirmation : E13 compte 1 coureur.
- Démarrage : dialogue de confirmation ; « Annuler » n'émet aucune requête E12 ; « Confirmer » affiche « En cours » et l'heure de départ. Les boutons « Démarrer » et « Supprimer » ne sont plus proposés.
- Contexte hors ligne, puis « Modifier » (nom) : message « Action impossible : serveur injoignable… », aucune mise en file. Après retour en ligne, E9 montre le nom inchangé.

**CA37 — Deux courses en parallèle [E2E] (RG35, CL5)**
Données :
- `E2E-P1-{run}` : 1000 m, 30 s, 10 m, avec Alice P1 (dossard 1), démarrée à `T1` ;
- `E2E-P2-{run}` : 2000 m, 45 s, 20 m, avec Alice P2 (dossard 1), démarrée à `T2 = T1 + 10 s` (±2 s).
Deux onglets de tableau de bord ouverts, un par course. Sur **un seul** écran `/scan`, scan d'Alice P1 à `T1 + 15 s` et d'Alice P2 à `T1 + 17 s`.
- Réponses : « Dossard 1 — Alice P1 — yard 1 » et « Dossard 1 — Alice P2 — yard 1 ».
- À `T1 + 35 s` : P1 affiche « Yard 2 » et P2 « Yard 1 ».
- P1 ne liste qu'Alice P1 (1 tour, `1,00 km`, `10 m D+`) ; P2 ne liste qu'Alice P2 (1 tour, `2,00 km`, `20 m D+`).
- Les deux comptes à rebours sont différents.
- L'accueil liste les deux courses « En cours ».

**CA38 — Fichiers de la PWA et absence de CORS depuis le navigateur [E2E] (RG53, RG54, RG44)**
- Sans connexion, `/`, `/manifest.webmanifest` et `/ngsw-worker.js` se chargent (200) depuis l'origine de Spring Boot. Le service worker est actif et contrôle la page après un rechargement.
- Une page servie par une **autre** origine locale (ex. `http://localhost:5999`, page de test statique) fait `fetch` vers `/api/public/races` de l'API. La requête échoue (erreur CORS côté navigateur), et la réponse observée ne porte pas `Access-Control-Allow-Origin`. Depuis la PWA, la même requête réussit.

**CA39 — Installabilité et hors ligne [E2E] (RG44, RG45, RG47)**
- Le manifeste lu par le test contient `name`, `short_name`, `start_url "/"`, `display "standalone"`, une icône 192×192 et une 512×512 dont une `maskable`.
- Après une visite en ligne, puis passage **hors ligne** : `/`, `/scan`, `/admin` et `/courses/{id}` s'affichent (pas de page d'erreur du navigateur). `/scan` permet une capture (compteur « 1 en attente ») ; `/courses/{id}` affiche « Hors ligne ».
- Le Cache Storage ne contient aucune réponse d'une URL `/api/**`.

**CA40 — Correction d'horloge [E2E] (RG4, RG19, CL9)**
Donné la course de CA27, et l'horloge **du navigateur seul** avancée de 60 s (horloge simulée de l'outil E2E ; l'horloge serveur est réelle).
- Après connexion SCANNER : avertissement « Horloge de l'appareil décalée de 60 s » (±2 s).
- Un scan est accepté (200), sans rejet « dans le futur ». Le `scannedAt` émis est à moins de 3 s de l'heure serveur de l'instant de capture.

**CA41 — Accessibilité et cibles tactiles [E2E] (RG48, RG49, RG51)**
- Un audit automatisé (ex. axe-core, règles WCAG 2.1 A et AA) de `/`, `/courses/{id}` (RUNNING), `/inscription/{id}`, `/scan`, `/connexion`, `/admin/courses/{id}` et du dialogue de DNF ouvert relève **0 violation** de gravité « serious » ou « critical ».
- Sur `/scan`, en fenêtre de 360×740 : tous les boutons mesurent au moins 48×48 px, sans défilement horizontal. Le texte du dernier résultat a une taille calculée d'au moins 32 px.
- Le dialogue de DNF garde le focus (Tab ne sort pas du dialogue), Échap le ferme, et le focus revient au bouton « Déclarer DNF ».

**CA42 — Feedback du scan [E2E] (RG50, RG26)**
Avec les API son et vibration instrumentées par le test :
- capture en ligne acceptée : 1 bip et 1 vibration à la capture, puis 2 bips et une vibration 2 × 50 ms à l'acceptation, bandeau vert avec icône et texte ;
- « QR non reconnu » : 3 bips, vibration `[200, 100, 200]`, bandeau rouge avec texte ;
- son coupé : aucun bip, et le réglage est conservé après rechargement ;
- scan capturé hors ligne puis accepté plus de 5 s après : aucun son ni aucune vibration au moment de l'acceptation.

**CA43 — Détail coureur [E2E] (RG34, CL4)**
Pour Chloé en fin de CA32 : `/coureurs/{id}` liste le yard 1 « corrigé », heure `—`, temps de boucle `—`.
Pour Alice : yard 1 et yard 2 « scan », avec une heure et un temps de boucle au format `m:ss`.

### D. Couverture RG → CA

| RG | CA |
|---|---|
| RG1 | CA19, CA20, CA32 (valeurs affichées égales à E4) |
| RG2 | CA8, CA22, CA32, CA37 |
| RG3 | CA9, CA36 |
| RG4 | CA11, CA40 |
| RG5 | CA3, CA21 |
| RG6 | CA1, CA24 |
| RG7 | CA18, CA25 |
| RG8 | CA26 |
| RG9 | CA25 |
| RG10 | CA14, CA24 |
| RG11 | CA22, CA23 |
| RG12 | CA22, CA23 |
| RG13 | CA23 |
| RG14 | CA29 |
| RG15 | CA27, CA28 |
| RG16 | CA12, CA27, CA28 |
| RG17 | CA13, CA27 |
| RG18 | CA28 |
| RG19 | CA11, CA27, CA29, CA40 |
| RG20 | CA16, CA29 |
| RG21 | CA14, CA29 |
| RG22 | CA10, CA14, CA30 |
| RG23 | CA15, CA30 |
| RG24 | CA9, CA14, CA29, CA30, CA31 |
| RG25 | CA31 |
| RG26 | CA29, CA31, CA42 |
| RG27 | CA17 |
| RG28 | CA37 |
| RG29 | CA32 |
| RG30 | CA32 |
| RG31 | CA19, CA32 |
| RG32 | CA32 (ordre par dossard) |
| RG33 | CA33 |
| RG34 | CA43 |
| RG35 | CA37 |
| RG36 | CA24 |
| RG37 | CA36 |
| RG38 | CA20, CA36 |
| RG39 | CA36 |
| RG40 | CA36 |
| RG41 | CA34 |
| RG42 | CA35 |
| RG43 | CA34, CA36 |
| RG44 | CA38, CA39 |
| RG45 | CA29, CA39 |
| RG46 | CA16 (migration de la file) |
| RG47 | CA39 |
| RG48 | CA41 |
| RG49 | CA41 |
| RG50 | CA42 |
| RG51 | CA41 |
| RG52 | CA1, CA2 |
| RG53 | CA3, CA38 |
| RG54 | CA4, CA38 |
| RG55 | CA6 |
| RG56 | CA7 |
| RG57 | tous les CA E2E (conditions d'exécution) |
| RG58 | tous les CA E2E (exécution sous Chromium et WebKit, rapport) |
| RG59 | CA5 |

### E. Environnement E2E et maîtrise du temps

**RG57 — Maîtrise du temps en E2E, sans API de test**
1. **Horloge serveur réelle.** Aucune horloge serveur n'est simulée. Aucun endpoint de temps n'existe (RG56).
2. **Yards courts.** Les parcours avec cloche utilisent des courses à `loopDuration = 30 s` (45 s pour la seconde course de CA37). C'est une valeur légitime de l'API (`loopDuration > 0`, RG9 inc. 3), sans API de test. Les parcours sans cloche utilisent 3600 s, pour qu'aucune bascule ne survienne pendant le test.
3. **Clôture rapide.** Le planificateur tourne avec `backyard.yard-closing.fixed-delay-ms=1000` (valeur actuelle de `application.properties`). Un profil E2E peut la réduire à 500 ms par **propriété** : l'auto-DNF est alors visible moins de 1 s après la cloche.
4. **Ancrage sur `T0`.** Les instants sont calculés à partir de `startedAt` renvoyé par E12, jamais à partir de l'horloge du test. Chaque action a une marge d'au moins 5 s par rapport à une cloche. Les vérifications sont des assertions avec délai maximal. Le seul délai fixe autorisé est l'attente d'une cloche.
5. **Horloge du navigateur.** L'horloge simulée de l'outil E2E n'est utilisée que pour ce qui ne dépend que du front : backoff, expiration à 24 h, rétention, compte à rebours, décalage d'horloge (CA40). Elle n'est jamais utilisée pour « avancer » la course.
6. **Réseau.** La coupure est simulée par le mode hors ligne du contexte de navigateur, ou par l'interception des requêtes. Les erreurs 401 et 503 sont simulées par interception côté navigateur. Aucun comportement serveur n'est modifié.
7. **Caméra.** Un flux vidéo simulé contenant le QR code (option de lancement du navigateur, disponible sous Chromium) sert à CA27 (RG58 pour WebKit). Les autres CA de scan utilisent la saisie manuelle (RG18), qui suit le même chemin après la lecture.
8. Durée indicative des parcours à cloche : moins de 90 s chacun.

**RG58 — Données et environnement E2E**
- Backend démarré à partir du jar produit par `mvn verify` (PWA servie par Spring Boot, PO2 option A), avec les comptes du profil `test`. Base de données : PO24.
- Outil : Playwright, exécuté **en local** par une commande documentée dans `frontend/` (PO3). Les E2E ne font pas partie de `mvn verify` (RG59).
- **Navigateurs** : chaque CA E2E est exécuté sous **Chromium et sous WebKit**. Un CA n'est réussi que s'il l'est sous les deux.
  - Seule exception admise d'avance : le volet caméra simulée de CA27, exécuté sous Chromium seul (voir CA27).
  - Toute autre impossibilité technique sous WebKit est listée dans le rapport avec son motif ; elle constitue un écart soumis à l'arbitrage de l'agent fonctionnel. Un volet non exécuté n'est jamais compté comme réussi.
- Les données sont créées par les endpoints réels (E7, E3, E12), avec des noms uniques par exécution (`{run}` = horodatage de l'exécution). Une course `RUNNING` ne pouvant pas être supprimée (RG12 inc. 3), aucun nettoyage n'est exigé.
- Le rapport `INC-4-e2e.md` cite la commande, la date, les navigateurs et leurs versions, la version de l'application et le résultat de chaque CA E2E **par navigateur**.

---

## 13. Points ouverts

PO1 à PO4 : tranchés le 2026-09-26 (section 0). PO5 : choix du développeur, à justifier selon les critères de la section 0. Les points suivants ont une **HYPOTHÈSE** par défaut, déjà appliquée dans les RG et CA, qui vaut tant que l'utilisateur ne décide pas autrement. Les points de sécurité viennent en premier.

### Sécurité

**PO6 — Conservation des identifiants dans le navigateur (RG7).**
HYPOTHÈSE :
- ADMIN en mémoire uniquement ;
- SCANNER en mémoire par défaut, ou dans IndexedDB pour 24 h au maximum sur choix explicite (« Rester connecté 24 h »).
Risques résiduels :
- une injection de script (atténuée par la CSP stricte, RG55) ;
- un appareil perdu ou volé déverrouillé : le mot de passe SCANNER, partagé par tous les bénévoles (PO26 inc. 3), doit alors être changé (nouvelle variable, redémarrage ; la file des autres appareils est suspendue puis reprend).
Alternatives :
- (a) tout en mémoire : plus sûr, mais reconnexion à chaque fois que le système tue l'application ;
- (b) `sessionStorage` : survit au rechargement, pas à la fermeture ;
- (c) remplacer HTTP Basic par un jeton court et révocable obtenu au login. C'est la solution la plus sûre, mais elle modifie la sécurité de l'INC-3 et sort du « minimal ».

**PO7 — Gestionnaire de mots de passe du navigateur.**
HYPOTHÈSE : autorisé (`autocomplete` standard). Chacun choisit d'enregistrer ou non. Il est recommandé de ne pas enregistrer le mot de passe ADMIN sur un appareil partagé.
Alternative : `autocomplete="off"`, souvent ignoré par les navigateurs, et qui pousse à des mots de passe plus faibles.

**PO8 — Correction d'horloge des scans (RG4, RG19).**
HYPOTHÈSE : `scannedAt` = instant de capture de l'appareil corrigé du décalage mesuré sur `serverTime`. Sans correction, un appareil en avance de quelques secondes voit ses scans rejetés en 400 « scan dans le futur » (RG12.4 inc. 2, PO6 inc. 2, aucune tolérance).
Effet de bord : la correction ne dépend que du serveur de l'organisateur. Un appareil trafiqué peut toujours envoyer un `scannedAt` arbitraire dans le passé, mais seul un compte SCANNER le peut.
Alternative : instant brut de l'appareil, avec seulement un avertissement.

**PO9 — Affichage du `qrToken` en texte à l'inscription (RG12).**
HYPOTHÈSE : affiché sous le QR code, pour la saisie de secours. Le token seul ne permet pas de scanner : il faut les identifiants SCANNER. Mais une personne qui connaît le token d'un coureur et le mot de passe SCANNER peut enregistrer un passage à sa place.
Alternative : QR code seul.

**PO10 — E19 et accès de l'ADMIN au scan (PO19 inc. 3).**
HYPOTHÈSE : un seul endpoint `GET /api/scan/me`, accessible aux deux rôles. Si PO19 (inc. 3) était retourné (scan réservé au SCANNER), il faudrait un second endpoint `GET /api/admin/me` pour la connexion ADMIN.

**PO11 — Coquille de l'administration servie publiquement (option A retenue, PO2).**
HYPOTHÈSE : `/admin/**` (fichiers HTML et JS) est public, comme toute application monopage. Aucune donnée n'est exposée sans identifiants (l'API protège `/api/admin/**`). Le code JS de l'administration est lisible par tous, et ne contient aucun secret.

**PO12 — Inscriptions abusives (PO18 inc. 3).**
HYPOTHÈSE : aucune protection côté front (ni captcha, ni limitation). La limitation de débit relève du reverse proxy.

**PO13 — En-têtes de sécurité et CSP (RG55).**
HYPOTHÈSE : CSP stricte, sans `unsafe-inline` pour les scripts. Certaines bibliothèques (PO5) ou certains frameworks (PO1) peuvent exiger des styles en ligne : `style-src 'unsafe-inline'` serait alors à accepter explicitement.
Conséquence du choix d'Angular (PO1), à confirmer par le développeur : Angular injecte à l'exécution les styles des composants, et son build de production peut intégrer en ligne les CSS critiques. Ces mécanismes sont incompatibles avec `style-src 'self'` sans nonce, et, pour l'intégration des CSS critiques, avec `script-src 'self'`. Tant que l'utilisateur n'a pas décidé :
- `script-src 'self'` reste non négociable (RG55, CA6) : aucune dérogation sur les scripts ;
- le développeur ne relâche pas `style-src` de lui-même. S'il ne peut pas tenir `style-src 'self'` (par exemple par un nonce), il le signale : la dérogation `style-src 'self' 'unsafe-inline'` est alors soumise à l'accord de l'utilisateur, et consignée comme écart à la validation.

### Filet réseau et scan

**PO14 — Ordre entre plusieurs appareils de scan.**
HYPOTHÈSE : FIFO garanti **par appareil** seulement (RG21).
Cas problématique :
1. l'appareil 1, hors ligne, détient le scan du yard `k-1` d'un coureur ;
2. l'appareil 2 envoie son scan du yard `k` : il est rejeté (409 scan tardif) ;
3. une fois le scan de l'appareil 1 parvenu, le bouton « Renvoyer » (RG25) permet de faire accepter le scan du yard `k`, tant que la fraîcheur (RG32 inc. 2) le permet. Sinon, une réintégration admin est nécessaire.
Recommandation d'organisation : un appareil principal par point de passage.

**PO15 — Durée de l'anti-rebond (RG17).**
HYPOTHÈSE : 10 s. Aucune boucle ne se fait en moins de 10 s, et un second scan légitime du même coureur est de toute façon un double scan sur le même yard, sauf juste après la cloche (PO7 inc. 2).

**PO16 — Retour sonore des envois différés (RG50).**
HYPOTHÈSE : aucun son pour un résultat arrivé plus de 5 s après la capture.
Alternative : un son distinct pour un rejet différé, pour attirer l'attention du bénévole.

**PO17 — Saisie d'un passage par dossard (secours sans QR).**
HYPOTHÈSE : non incluse. Le secours est la saisie du token et les lecteurs clavier (RG18). Un coureur sans son QR est réimprimé par l'admin (RG39, RG40).
Alternative : endpoint `POST /api/scan/passages/by-bib` (`raceId`, `bib`, `scannedAt`), qui demande un nouveau service backend et une règle d'autorisation (un SCANNER pourrait alors scanner n'importe quel dossard).

**PO18 — Nom de la course dans la réponse de scan.**
`ScanResponse` (inc. 3) ne contient ni `raceId` ni nom de course : deux coureurs de même dossard dans deux courses ne se distinguent que par le nom.
HYPOTHÈSE : pas de modification de l'API.
Alternative : ajouter `raceId` et `raceName` à `ScanResponse` (modification backend mineure).

### Tableau de bord et administration

**PO19 — Indicateur « passage effectué sur le yard courant ».**
C'est utile à l'organisateur (« qui est encore en boucle ? »), mais c'est une valeur dérivée absente de E4. La PWA ne peut pas la calculer (RG1).
HYPOTHÈSE : non incluse.
Alternative : champ `passedCurrentYard` ajouté à E4 et calculé côté backend par les calculateurs existants.

**PO20 — Tri et filtre côté client.**
HYPOTHÈSE : aucun, ordre de l'API (RG32).
Alternative : tri local (statut, tours) sans valeur de classement officielle.

**PO21 — Rythmes de polling hors course (RG29).**
HYPOTHÈSE : 2,5 s en `RUNNING`, 10 s en `SETUP` et `FINISHED`.

**PO22 — Navigateurs et appareils cibles.**
HYPOTHÈSE : Chrome Android et Safari iOS, deux dernières versions majeures ; Chrome, Firefox et Safari de bureau pour l'administration. Les E2E couvrent les moteurs Chromium et WebKit (PO3) ; Firefox n'est pas testé en E2E.
Sur iOS, il faut installer la PWA pour éviter l'effacement des données d'un site non visité pendant 7 jours. La synchronisation en arrière-plan n'y existe pas : d'où RG22 (application ouverte).

**PO23 — Unités de saisie admin.**
HYPOTHÈSE : distance en mètres, durée en secondes avec l'aperçu `h:mm:ss`. C'est cohérent avec l'API, et les E2E utilisent 30 s.
Alternative : saisie en minutes et secondes.

**PO24 — Base de données de l'environnement E2E.**
HYPOTHÈSE : PostgreSQL de même version majeure que le VPS, si l'environnement de la réserve RT1 est disponible ; sinon H2 en mode PostgreSQL. La réserve RT1 reste traitée à part.

**PO25 — Confirmations non exigées par CLAUDE.md.**
HYPOTHÈSE : démarrage, suppression de course et suppression de coureur sont aussi confirmés (actions irréversibles). Seul le DNF manuel est exigé par CLAUDE.md.

**PO26 — Rétention locale (RG27).**
HYPOTHÈSE : 24 h pour les éléments acceptés, 7 jours après « vu » pour les rejets, aucune suppression automatique des éléments en attente.

---

## 14. Conditions de validation de l'incrément (rappel)

Le GO de l'INC-4 exige, en plus des CA ci-dessus :
- la levée des réserves ouvertes dans `docs/tests/PATRIMOINE.md` :
  - R1-1 (renommage `ca23_...` avant les tests du nouveau CA23 de l'INC-1) ;
  - R1-2 ;
  - R1-3 (tests des CA23 à CA31 de l'addendum INC-1) ;
  - R2-1 ;
  - R3-1 ;
  - RT1 (PostgreSQL réel et recette HTTPS) ;
  - RT3 (sortie du compilateur, backend **et** front) ;
- des E2E réellement exécutés : un E2E « sans objet » vaut NO-GO (RT2).
