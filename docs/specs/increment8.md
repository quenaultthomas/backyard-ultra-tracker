# Spec Incrément 8 — Rôle scanneur

> Projet : Backyard Ultra Tracker
> Date de rédaction : 2026-09-28
> - Révision 1 : mise en conformité avec la demande initiale de l'utilisateur.
> - Révision 2 (2026-09-28) : intégration des décisions de l'utilisateur sur PO1 à PO8. L'option B (accès par poste) est écartée : la comparaison A/B et les marques [H1] sont retirées. Ajouts : RG13, RG14, CL8 à CL10, CA13 à CA15, PO9, PO10. Correction d'une contradiction avec RG7 (inc. 4) sur l'expiration de 24 h (RG10).
>
> - Révision 3 (2026-09-28) : intégration des décisions de l'utilisateur sur PO9, PO10 et PO15 (inc. 5).
>   - PO9 : entité distincte, référentiels indépendants ; l'hypothèse H11 de cet incrément est retirée. RG2, RG4 et CA15 sont réécrits.
>   - PO10 : limitation nginx sur E19 seulement.
>   - PO15 (inc. 5) : accès croisés en 401 ; RG1, CA1 et CA2 évoluent.
>   - Ajouts : RG15, CL11 à CL13, CA16 à CA18, PO11.
>
> - Révision 4 (2026-09-28) : l'utilisateur a tranché PO11 (mêmes seuils que `/api/account/**`). H12 est confirmée et devient une règle (RG15, CA16). Le contrôle de collision au démarrage est confirmé (RG2, PO9). Aucun nouvel élément.
>
> - **Révision 5 (2026-10-01, renumérotation décidée par l'utilisateur)** : cette spec était l'« incrément 7 ». Elle devient l'**incrément 8**, exécutée **après** la nouvelle spec de l'incrément 7 « Connexion et inscription » (`docs/specs/increment7.md`). Changements, et uniquement ceux-là :
>   - numéro d'incrément : 7 devient 8, partout (titre, en-tête, références) ;
>   - **endpoints** : E26 à E30 deviennent **E27 à E31** (E26 est créé par l'inc. 7 : `POST /api/public/accounts`). Correspondance : E26→E27 création, E27→E28 liste, E28→E29 mot de passe, E29→E30 désactivation, E30→E31 réactivation ;
>   - dépendances : GO de l'inc. 7 (et non plus de l'inc. 6) avant implémentation ; l'écran `/connexion` est désormais l'écran unique de l'inc. 7 (voir RG4) ; la configuration nginx contient en plus la limite de E26 (RG15 et CA16) ;
>   - aucune règle de gestion, aucun cas limite ni critère d'acceptation n'est modifié sur le fond (numéros RG, CL, CA inchangés).
>
> Statut : **validée pour développement** (contenu inchangé depuis la révision 4) ; son exécution est **conditionnée au GO de l'incrément 7**.
> **Aucune implémentation ne démarre avant le GO de l'incrément 7** (règle 6 du workflow de validation, `CLAUDE.md`).
> Numérotation propre à l'incrément. Références externes : « RG21 (inc. 4) », « RG29 (inc. 3) », « RG22 (inc. 5) », « RG2 (inc. 6) », « RG1 (inc. 7) ».

**Besoin exprimé (mot pour mot).** « Rôle "scanneur" : l'admin doit pouvoir déclarer des comptes ou des postes "scanneur", limités au pointage des passages, sans les autres droits admin. Précise si un scanneur a un compte nominatif ou un simple accès par poste/appareil, et le lien avec le filet réseau déjà prévu (scan local puis envoi API). »

**Réponse (PO1 tranché) : compte nominatif pseudonyme par bénévole.** Un identifiant sans nom réel et un mot de passe, créés par l'admin, désactivables, en HTTP Basic comme les autres comptes (inc. 3 et 5). La file de scans reste celle de l'appareil (PO5).

---

## 0. Existant et changements

| Sujet | Existant | Ce que l'incrément 8 change |
|---|---|---|
| Accès au scan | **Un seul** compte SCANNER, partagé par tous les bénévoles et toutes les courses, défini par `BACKYARD_SECURITY_SCANNER_*` (RG28, RG32 inc. 3). Un mot de passe divulgué impose de changer la variable et de redémarrer. | L'admin **crée, désactive et réactive** des comptes scanneurs nominatifs depuis l'interface, sans redémarrage (RG2, RG3). Le compte partagé est **conservé en secours** (RG5). |
| Droits du rôle SCANNER | `/api/scan/**` : E6 (scan) et E19 (`/api/scan/me`). 403 sur `/api/admin/**` (RG29 inc. 3) ; 401 sur `/api/account/**` (RG10 inc. 5, PO15 inc. 5). | **Inchangés** : les comptes déclarés ont exactement ces droits (RG1), sur toutes les courses (RG13). |
| Authentification | HTTP Basic pour ADMIN, SCANNER (inc. 3) et comptes pseudo (inc. 5). Référentiel choisi selon le préfixe d'URL : comptes pseudo sur `/api/account/**`, comptes staff ailleurs (PO15 inc. 5). | HTTP Basic aussi pour les scanneurs déclarés, qui rejoignent le référentiel **staff** (RG4). |
| Écran de connexion | Depuis l'inc. 7 : `/connexion` est l'écran unique à deux entrées « Coureur » et « Bénévole ». L'entrée « Bénévole » valide par E19 (RG1 inc. 7). | **Aucun changement d'écran** : un scanneur déclaré se connecte par l'entrée « Bénévole », comme le compte de configuration. |
| Limitation de débit | nginx sur E3, E26 (inc. 7) et `/api/account/**` (RG23 inc. 5, RG6 inc. 7) ; rien sur `/api/scan/**` (PO25 inc. 3). | `limit_req` sur E19 seulement ; E6 reste non limité (RG15). |
| Conservation des identifiants | SCANNER : « Rester connecté 24 h », expiration **absolue** (RG7 inc. 4). Coureurs : 24 h **glissantes** (RG21 inc. 5). | 24 h **glissantes** pour toute connexion de rôle SCANNER (RG10). |
| Filet réseau | File locale par appareil, capture indépendante du réseau et des identifiants, FIFO, émetteur effectif, backoff, idempotence, suspension sur 401 ou 403 (RG14 à RG27 inc. 4, RG21 amendée). | Règles inchangées. Leur comportement face à la désactivation, à l'expiration et au changement d'utilisateur est précisé (section 3). |
| Passages | `Passage` sans auteur (inc. 1). | **Inchangé** : aucun auteur enregistré (RG14). |

### Endpoints créés par cet incrément
Numérotation à la suite de l'inc. 7 (E26, création d'un compte coureur sans course). *(Révision 5 : décalage d'un rang par rapport à la révision 4, E26 à E30 devenant E27 à E31.)*

| # | Endpoint | Rôle | Règle |
|---|---|---|---|
| E27 | `POST /api/admin/scanners` | ADMIN | RG3 |
| E28 | `GET /api/admin/scanners` | ADMIN | RG3 |
| E29 | `PUT /api/admin/scanners/{id}/password` | ADMIN | RG3 |
| E30 | `POST /api/admin/scanners/{id}/deactivate` | ADMIN | RG3 |
| E31 | `POST /api/admin/scanners/{id}/activate` | ADMIN | RG3 |

---

## 1. Périmètre

### Inclus
- Entité des comptes scanneurs, migration versionnée.
- Création, liste, réinitialisation du mot de passe, désactivation et réactivation par l'admin (API et écran).
- Authentification HTTP Basic des scanneurs déclarés, par l'écran `/connexion` (entrée « Bénévole ») et E19 existants.
- Alignement de la conservation des identifiants SCANNER sur 24 h glissantes.
- Précision du comportement du filet réseau face à la désactivation, à l'expiration et au changement d'utilisateur.
- Limitation de débit nginx sur E19 (`GET /api/scan/me`), configuration versionnée.

### Explicitement exclu
- Limitation de débit de E6 (`POST /api/scan/passages`) et de tout autre chemin `/api/scan/**` (PO10).
- Unicité d'un identifiant scanneur vis-à-vis des pseudos coureurs (PO9).
- Accès par poste ou appareil appairé (PO1, option B écartée).
- Affectation d'un scanneur à certaines courses (PO2).
- Enregistrement de l'auteur d'un passage (PO3) ; le schéma de `passage` ne change pas.
- Suppression d'un compte scanneur (PO7).
- Mot de passe généré par le serveur (PO8).
- Refus des scans capturés après une désactivation (PO5).
- Auto-inscription d'un bénévole (l'écran d'inscription de l'inc. 7 ne crée que des comptes coureurs).
- Toute modification des règles de course et des règles du filet réseau de l'inc. 4.

---

## 2. Règles de gestion

### A. Déclaration et droits

**RG1 — Droits limités au pointage**
Un compte scanneur déclaré a uniquement le rôle `SCANNER` (autorité `ROLE_SCANNER`), avec les droits de RG29 (inc. 3) et RG10 (inc. 5) :
- `/api/scan/**` (E6, E19) : autorisé ;
- `/api/admin/**` : 403 ;
- `/api/account/**` (inc. 5) : 401, car ce chemin ne reconnaît que les comptes pseudo (PO15 inc. 5 tranché, RG4) ;
- `/api/public/**` : autorisé.
Il n'a ni DNF manuel, ni réintégration, ni gestion de course, de coureur, de compte ou de scanneur, ni accès aux `qrToken` via l'admin. Côté front, `/admin/**` affiche « Page introuvable » (RG3 inc. 6).

**RG2 — Entité ScannerAccount** *(PO1 tranché ; PO9 TRANCHÉ (utilisateur, 2026-09-28) : entité distincte, référentiels indépendants)*
- Entité `ScannerAccount`, distincte d'`Account` (inc. 5). `Account` reste sans rôle (RG1 inc. 5) et n'est pas modifié.
- Attributs : `id`, `username`, `password_hash` (BCrypt, coût 12), `active`. Rien d'autre : ni nom réel, ni contact, ni date de dernière connexion, ni adresse IP.
- `username` : format du pseudo de RG2 (inc. 5), non modifiable, unique parmi les comptes scanneurs sans tenir compte de la casse (index unique sur `lower(username)`, même mécanisme et même réserve H2 que RG5 inc. 5).
- `username` est distinct, sans tenir compte de la casse, du nom du compte ADMIN et du compte SCANNER de configuration : 409 à la création (E27).
- **Aucune unicité vis-à-vis des pseudos coureurs** : `benevole-07` peut exister à la fois comme scanneur et comme compte pseudo. Le préfixe d'URL lève l'ambiguïté (RG4, PO15 inc. 5). La création d'un scanneur ne consulte pas `Account`, et E3 et E26 (inc. 5, inc. 7) ne consultent pas `ScannerAccount` : aucun 409 ne révèle l'existence d'un identifiant de l'autre référentiel.
- **Collision introduite par la configuration** : si, au démarrage, `BACKYARD_SECURITY_ADMIN_USERNAME` ou `BACKYARD_SECURITY_SCANNER_USERNAME` égale, sans tenir compte de la casse, le `username` d'un compte scanneur en base (actif ou non), l'application **refuse de démarrer**. Le message nomme la variable d'environnement concernée, comme pour RG32 (inc. 3), dont c'est l'extension. Aucun nom du référentiel staff ne peut donc désigner deux comptes. *(Choix confirmé (utilisateur, 2026-09-28).)*
- Mot de passe : RG3 (inc. 5).
- Le formulaire rappelle : « Identifiant sans nom réel (ex. benevole-07) ».
- Migration versionnée (`V3`), sans modifier les migrations existantes. *(Si l'inc. 7 ajoute une migration, le numéro suit.)*

**RG3 — Gestion par l'admin (rôle ADMIN)** *(PO7, PO8 tranchés)*
- **E27** `POST /api/admin/scanners` `{username, password}` : le mot de passe initial est **saisi par l'admin**. 201, compte actif. Erreurs 400 (format, RG2 et RG3 inc. 5) et 409 (nom déjà pris par un scanneur ou par un compte de configuration, RG2).
- **E28** `GET /api/admin/scanners` : liste triée par `username` (`id`, `username`, `active`), sans mot de passe ni hash. Le compte SCANNER de configuration n'y figure pas.
- **E29** `PUT /api/admin/scanners/{id}/password` `{newPassword}` : 204 ; 400 ; 404. Mot de passe saisi par l'admin, sans obligation de changement ensuite (comme RG14 inc. 5).
- **E30** `POST /api/admin/scanners/{id}/deactivate` et **E31** `.../activate` : 200 avec le compte ; 404. Idempotents.
- **Aucune suppression** : il n'existe aucun endpoint de suppression d'un compte scanneur. Un compte désactivé reste en base et dans la liste.

**RG4 — Authentification et révocation** *(PO1 tranché : HTTP Basic)*
- Un compte scanneur actif s'authentifie en **HTTP Basic**, API sans état (RG28, RG31 inc. 3), par l'écran `/connexion` (entrée « Bénévole », RG1 inc. 7) et E19, inchangés (RG6 inc. 4).
- Un compte désactivé, ou un mot de passe faux, donne 401 `UNAUTHENTICATED`, avec un `detail` identique à celui d'un nom inconnu, sans `WWW-Authenticate`.
- Chaque requête est vérifiée contre l'état du compte en base au moment où elle est traitée : la désactivation prend effet **dès la requête suivante**, sur tous les appareils, sans redémarrage. Une requête déjà authentifiée et en cours de traitement se termine normalement.
- **Référentiel** *(PO9 et PO15 inc. 5 tranchés)* : les scanneurs déclarés font partie du référentiel **staff**, avec les comptes de configuration ADMIN et SCANNER. Ce référentiel sert sur tous les chemins sauf `/api/account/**` (RG9 inc. 5).
  - Un scanneur n'est jamais cherché sur `/api/account/**`, et un compte pseudo jamais ailleurs.
  - Un identifiant scanneur homonyme d'un pseudo coureur désigne donc le scanneur sur `/api/scan/**` et le compte pseudo sur `/api/account/**`, **même si les mots de passe sont identiques**.
  - Dans le référentiel staff, un nom désigne au plus un compte (RG2) : l'ordre de consultation n'a aucun effet.

**RG5 — Compte SCANNER de configuration conservé en secours** *(PO4 tranché)*
- Le compte défini par `BACKYARD_SECURITY_SCANNER_*` reste inchangé (RG28, RG32 inc. 3) : même rôle, mêmes droits, même mode de changement (variable d'environnement et redémarrage).
- Il n'est ni listé, ni désactivable, ni réinitialisable par E27 à E31.
- Le compte ADMIN peut toujours scanner (PO19 inc. 3).

**RG6 — Journalisation**
- Création, désactivation, réactivation et réinitialisation sont journalisées en INFO avec l'`id` et l'identifiant, jamais le mot de passe ni le hash.
- Les règles de minimisation de RG16 (inc. 5) s'appliquent : un échec d'authentification est journalisé en WARN sans le nom présenté.
- Aucune ligne de journal n'associe un scan à un compte (RG14).
- Conservation : **7 jours**, avec le périmètre et la rotation de RG22 (inc. 5). Aucune configuration supplémentaire.

**RG7 — Écran admin « Scanneurs »**
- `/admin/scanneurs` permet de créer un compte (identifiant et mot de passe saisis), de réinitialiser son mot de passe et de le désactiver ou le réactiver. Aucun bouton de suppression.
- La réinitialisation, la désactivation et la réactivation demandent une confirmation.
- Chaque action envoie une seule requête, sans rejeu (RG43 inc. 4).
- L'écran fait partie du module admin chargé à la demande (RG5 inc. 6) : fichier de route `admin-*`, et son rapport de validation liste les blocs partagés préchargés qu'il introduit (fragilité consignée, RG5 inc. 6).

**RG13 — Toutes les courses** *(PO2 tranché)*
Un compte scanneur n'est affecté à aucune course : il scanne toutes les courses, comme le compte de configuration. Aucune table de liaison, aucun contrôle de course dans `PassageRecordingService`.

**RG14 — Aucun auteur de passage** *(PO3 tranché)*
L'auteur d'un passage n'est enregistré nulle part : ni colonne sur `passage`, ni journal. Le corps de E6 reste `{"qrToken", "scannedAt"}`. Un passage créé par un scanneur déclaré est indiscernable d'un passage créé par le compte de configuration ou par l'ADMIN.

**RG15 — Limitation des tentatives de connexion sur E19** *(PO10 TRANCHÉ (utilisateur, 2026-09-28) : option b ; PO11 TRANCHÉ (utilisateur, 2026-09-28) : mêmes seuils que `/api/account/**`)*
- nginx applique `limit_req` à **E19 `GET /api/scan/me` seulement**, c'est-à-dire l'appel qui valide les identifiants à la connexion (RG6 inc. 4). Mêmes principes que RG23 (inc. 5) :
  - clé = `$binary_remote_addr` ;
  - `limit_req_status 429` ;
  - zone propre à E19, distincte des zones de l'inscription et de `/api/account/**` ;
  - configuration versionnée sous `deploy/nginx/` ;
  - rien dans l'application.
- Seuils (PO11) : ceux de `/api/account/**` (RG23 inc. 5), soit 30 requêtes par minute et par IP (`rate=30r/m`), rafale de 10 (`burst=10`), `nodelay`. Dans une zone propre à E19, 11 requêtes simultanées depuis une même IP sont admises, puis une toutes les 2 s.
- **E6 `POST /api/scan/passages` n'est jamais limité**, ni aucun autre chemin `/api/scan/**`. La vidange d'une file après une coupure n'est donc jamais freinée, et la file ne reçoit jamais de 429 de nginx. Le classement des réponses (RG24 inc. 4) est inchangé.
- Côté PWA, une réponse 429 de E19 pendant une connexion « Bénévole » sur `/connexion` affiche « Trop de tentatives. Réessayez dans une minute. » (message de RG23 inc. 5). Rien n'est conservé dans l'emplacement ADMIN/SCANNER, et la requête n'est pas rejouée. Comme pour toute réponse non 2xx, ce 429 ne prolonge pas l'expiration (RG10).
- **Évolution motivée de CA40 (inc. 5)** : son assertion [config] « aucun autre `location` n'a de `limit_req` » admet désormais aussi le `location` exact de `/api/scan/me` (en plus de celui de `POST /api/public/accounts` admis par l'inc. 7).

---

## 3. Lien avec le filet réseau (inc. 4, RG14 à RG27)

**RG8 — Principes inchangés**
Cet incrément ne modifie aucune des règles suivantes :
- capture indépendante du réseau et des identifiants (RG14) ;
- horodatage fixé à la capture (RG19) ;
- file persistante **par appareil** (RG20) ;
- FIFO et émetteur effectif, y compris l'amendement BUG-3 et l'arbitrage OBS-T1 (RG21, RG22) ;
- idempotence (RG23) ;
- traitement des réponses (RG24) ;
- rejets (RG25).

**RG9 — Compte désactivé avec des scans en attente** *(PO5 tranché)*
- Au premier envoi après la désactivation, l'appareil reçoit 401. La tête de file reste `EN_ATTENTE`, la file est **suspendue sans rien perdre**, les identifiants de l'appareil sont effacés et l'écran de connexion s'affiche (RG10, RG24 inc. 4).
- Les éléments `EN_ATTENTE` ne sont jamais supprimés automatiquement (RG27 inc. 4).
- La capture reste possible (RG14 inc. 4).
- La file reprend dès qu'un compte **actif** est connecté sur l'appareil. Les scans sont envoyés avec leur `scannedAt` d'origine, et le serveur les traite normalement.
- **Conséquence assumée (PO5)** : les scans capturés **après** la désactivation, par exemple sur un appareil perdu, sont acceptés s'ils sont envoyés plus tard par un compte actif. Aucune règle ne les distingue.

**RG10 — Conservation des identifiants SCANNER : 24 h glissantes** *(PO6 tranché)*
- « Rester connecté 24 h sur cet appareil » (décochée par défaut) suit la règle d'expiration **glissante** de RG21 (inc. 5) : la date d'expiration vaut `instant de la dernière activité + 24 h`, une activité étant une requête envoyée avec ces identifiants qui reçoit une réponse **2xx** (connexion par E19, envoi E6 accepté).
- **Correction d'une contradiction** : RG7 (inc. 4) fixe l'expiration du SCANNER à `instant de connexion + 24 h` (absolue). La PWA ne distingue pas un scanneur déclaré du compte de configuration (E19 renvoie `role = "SCANNER"` pour les deux) : la règle glissante s'applique donc à **toute connexion de rôle SCANNER**, compte de configuration compris. RG7 (inc. 4) est amendée en ce sens. CA18 (inc. 4), qui vérifie l'expiration sans activité intermédiaire, reste valable sans modification.
- Le compte ADMIN reste en mémoire uniquement (RG7 inc. 4, inchangée).
- Les identifiants peuvent expirer hors ligne, puisque la capture n'est pas une activité. La capture continue (RG14 inc. 4). Au retour du réseau, en l'absence d'identifiants, l'écran affiche « Non connecté : envoi suspendu ». Rien n'est perdu, et l'envoi reprend après une connexion (RG22 inc. 4, essai immédiat).
- Le yard est déterminé par `scannedAt` (RG19 inc. 4). Un envoi retardé reste soumis aux règles existantes du scan tardif et de la réactivation (RG14 et RG30 à RG32 inc. 2). Une suspension longue peut donc aboutir à des rejets 409 définitifs (RG24 inc. 4), à signaler à l'organisateur. Ce comportement existe déjà ; cet incrément ne le change pas.

**RG11 — Changement d'utilisateur sur un appareil dont la file n'est pas vide** *(PO5 tranché : la file appartient à l'appareil)*
- La déconnexion demande confirmation quand la file n'est pas vide, sans vider la file (RG9 inc. 4).
- La file appartient à l'**appareil**, pas au compte. Les scans en attente partent sous le compte connecté **au moment de l'envoi**, dans l'ordre FIFO.
- Les rejets déjà affichés (RG25 inc. 4) restent visibles.
- Sans auteur enregistré (RG14), ce transfert est sans effet sur les données.

**RG12 — Idempotence entre comptes**
- Un même élément de file, renvoyé par un autre compte après un changement d'utilisateur ou une désactivation, transmet exactement le même corps (RG23 inc. 4).
- Si le passage existe déjà, le serveur répond 200 avec ce passage (RG15 inc. 2), quel que soit le compte qui renvoie : aucun doublon n'est créé.

---

## 4. Cas limites

**CL1 — Désactivation pendant une coupure réseau.** L'appareil continue de capturer. Au retour du réseau : 401, file suspendue, puis reprise après la connexion d'un compte actif (RG9).
**CL2 — Désactivation pendant un envoi (élément `EN_COURS`).** Si la requête était déjà authentifiée, elle aboutit (200) ; sinon, 401. Dans les deux cas, pas de doublon au renvoi (RG12).
**CL3 — Un même compte connecté sur deux appareils.** Deux files indépendantes, sans ordre garanti entre elles (RG21 inc. 4, PO14 inc. 4). La désactivation coupe les deux.
**CL4 — Téléphone partagé, relève sans déconnexion.** Les scans du second bénévole partent sous le compte du premier. C'est sans effet, puisque aucun auteur n'est enregistré (RG14).
**CL5 — Plusieurs courses en parallèle.** Un compte scanneur scanne toutes les courses (RG13).
**CL6 — Course non démarrée, bascule de yard, passage manuel, réintégration, coureur sans passage.** Règles inchangées. Un scan envoyé par un compte déclaré est traité exactement comme un scan du compte SCANNER de configuration (CA8).
**CL7 — ADMIN qui scanne.** Il le peut toujours (PO19 inc. 3). Ce n'est pas un compte scanneur déclaré, et il n'est pas concerné par la désactivation.
**CL8 — Compte désactivé puis réactivé.** Les appareils dont les identifiants ont été effacés par le 401 (RG9) demandent une nouvelle connexion ; un appareil qui n'a fait aucune requête entre-temps garde ses identifiants et les utilise normalement après la réactivation.
**CL9 — Course sur plusieurs jours.** Un bénévole connecté avec « Rester connecté » dont au moins un envoi est accepté toutes les 24 h reste connecté. Après 24 h sans réponse 2xx, y compris hors ligne, il doit se reconnecter ; les scans capturés entre-temps restent en file (RG10).
**CL10 — Compte de configuration.** Il reste utilisable en secours quand tous les comptes déclarés sont désactivés ; il n'apparaît pas dans `/admin/scanneurs`, et son « Rester connecté » suit aussi la règle glissante (RG10).
**CL11 — Nom de configuration modifié pour égaler un scanneur déclaré.** L'exploitant change `BACKYARD_SECURITY_SCANNER_USERNAME` en `BENEVOLE-07` alors que le scanneur `benevole-07` existe, même désactivé. Au redémarrage, l'application refuse de démarrer, et le message nomme la variable (RG2).
**CL12 — Vidange d'une file après une coupure.** Un appareil renvoie des dizaines de scans en rafale au retour du réseau : aucun ne reçoit 429, puisque E6 n'est pas limité (RG15). Seules les connexions répétées par E19 depuis une même IP peuvent être freinées, par exemple plusieurs bénévoles qui se connectent en même temps sur le Wi-Fi du site.
**CL13 — Identifiant scanneur homonyme d'un pseudo coureur.** Un bénévole scanneur `benevole-07` crée aussi un compte coureur `benevole-07` (par l'inscription de l'inc. 7 ou celle d'une course). Les deux coexistent, et chaque chemin désigne le compte de son référentiel (RG2, RG4) ; sur `/connexion`, c'est l'entrée choisie (« Bénévole » ou « Coureur ») qui sélectionne le référentiel. La désactivation du scanneur ne touche pas le compte coureur, et réciproquement.

---

## 5. Critères d'acceptation

Comptes de test : ADMIN `admin-test` / `admin-secret`, SCANNER de configuration `scanner-test` / `scanner-secret`. Scanneurs déclarés : `benevole-07` / `motdepasse-7` et `benevole-02` / `motdepasse-2`. Étiquettes : voir la spec de l'inc. 5. Dans les parcours E2E, la connexion d'un bénévole se fait par l'entrée « Bénévole » de `/connexion` (inc. 7).

**CA1 — Droits limités (RG1) [slice]**
`benevole-07` actif :
- `GET /api/scan/me` → 200, `role = "SCANNER"`, `username = "benevole-07"` ;
- `POST /api/scan/passages` avec un `qrToken` d'une course `RUNNING` → 200 ;
- `GET /api/admin/races`, `POST /api/admin/runners/1/dnf`, `GET /api/admin/scanners` → 403 ;
- `GET /api/account/me` → 401, avec le même `detail` qu'un nom inconnu (*évolution motivée, PO15 inc. 5* : 403 dans la révision 2) ;
- `GET /api/public/races` → 200 ; `POST /api/public/accounts` (E26, inc. 7) → 201 avec un corps valide.

**CA2 — Déclaration par l'admin (RG2, RG3) [unit + slice]**
En ADMIN :
- création de `benevole-07` / `motdepasse-7` → 201, `active = true`, hash en base commençant par `$2` et vérifiant `motdepasse-7` ;
- `BENEVOLE-07` → 409 ; `admin-test` → 409 ; `SCANNER-TEST` → 409 ; `ab` → 400 ; mot de passe `court12` → 400 ;
- la liste renvoie `benevole-02` puis `benevole-07`, sans propriété `password` ni `passwordHash`, et sans `scanner-test`.
Les endpoints E27 à E31 renvoient 401 en anonyme, 403 pour `benevole-07` et pour `scanner-test`, et 401 pour un compte pseudo (*évolution motivée, PO15 inc. 5* : 403 dans la révision 2).

**CA3 — Révocation immédiate (RG4, CL3, CL8) [IT]**
- `benevole-07` obtient 200 sur `/api/scan/me`.
- L'ADMIN le désactive (200). La requête suivante de `benevole-07` reçoit 401, avec un `detail` égal à celui d'un nom inconnu.
- Une seconde désactivation renvoie 200, sans changement. Après réactivation : 200 avec `motdepasse-7`.
- Réinitialisation vers `nouveau-mdp-77` : `motdepasse-7` → 401, `nouveau-mdp-77` → 200.
- Aucun redémarrage n'a lieu pendant le test.

**CA4 — Scans en attente au moment de la désactivation (RG9, CL1) [E2E]**
1. `benevole-07` est connecté sur `/scan`, réseau coupé. On capture trois coureurs A, B et C d'une course `RUNNING` : 3 éléments en attente.
2. L'ADMIN désactive `benevole-07` par l'API, puis le réseau revient.
3. Premier envoi : 401. L'écran de connexion s'affiche, les 3 éléments restent « en attente », et aucun n'est `REJETÉ`.
4. On capture D pendant la suspension : 4 éléments en attente.
5. Connexion de `benevole-02` : les 4 éléments sont envoyés dans l'ordre A, B, C, D et reçoivent 200. E5 montre, pour chaque coureur, le passage avec le `scannedAt` de sa capture, y compris D, capturé après la désactivation.

**CA5 — Expiration hors ligne (RG10, CL9) [E2E]**
1. `benevole-07` est connecté avec « Rester connecté 24 h ». On coupe le réseau et on avance l'horloge du navigateur de 24 h et 1 min (horloge simulée).
2. On capture un coureur : l'élément est mis en file, et l'écran indique « Non connecté : envoi suspendu ».
3. Le réseau revient : aucune requête E6 n'est émise tant qu'on ne s'est pas connecté. Rien n'est perdu.
4. Après connexion de `benevole-07`, l'élément est envoyé et reçoit 200.

**CA6 — Changement d'utilisateur avec une file non vide (RG11, CL4) [E2E]**
- `benevole-07` a 2 éléments en attente, réseau coupé. « Se déconnecter » affiche « 2 scans en attente ne seront envoyés qu'après une nouvelle connexion. Se déconnecter ? ».
- Après confirmation, la file compte toujours 2 éléments.
- Connexion de `benevole-02`, puis retour du réseau : les 2 éléments sont envoyés sous `benevole-02` (l'en-tête `Authorization` des requêtes E6 est celui de `benevole-02`) et reçoivent 200.

**CA7 — Idempotence entre comptes (RG12, CL2) [IT]**
- Le même corps `{"qrToken": T, "scannedAt": "2026-10-03T08:45:00.000Z"}` est envoyé à E6 par `benevole-07`, puis par `benevole-02`.
- Les deux réponses sont 200 et contiennent le même `passageId`.
- La course compte exactement un passage pour ce coureur et ce yard.

**CA8 — Scan d'un compte déclaré identique au compte de configuration (RG1, RG13, RG14, CL5, CL6) [IT]**
- Deux courses `RUNNING` R1 et R2, un coureur dans chacune. Le coureur de R1 est scanné par `benevole-07`, celui de R2 par `benevole-07` aussi : deux 200.
- Dans des conditions identiques, un scan par `scanner-test` produit un passage aux mêmes champs (yard, `SCAN`, `scanned_at`).
- La table `passage` a exactement les colonnes de V1 : aucune colonne d'auteur. Aucune table de liaison entre scanneurs et courses n'existe.

**CA9 — Journaux (RG6, RG14) [unit ou IT, capture des journaux]**
- La création, la désactivation, la réactivation et la réinitialisation de `benevole-07` produisent chacune une ligne INFO avec son `id` et `benevole-07`, sans `motdepasse-7`, `nouveau-mdp-77` ni `$2`.
- Une authentification échouée de `benevole-07` produit une ligne WARN sans `benevole-07`.
- Aucun scan ne produit de ligne contenant `benevole-07`.

**CA10 — Écran admin « Scanneurs » (RG7) [E2E]**
- Création de `benevole-{run}` : une seule requête, 201, et le compte apparaît dans la liste.
- « Désactiver » puis « Annuler » : aucune requête.
- « Désactiver » puis « Confirmer » : une seule requête, et le compte affiche « inactif ».
- L'écran ne propose aucune action de suppression.

**CA11 — Compte de configuration conservé (RG5, CL7, CL10) [slice]**
`scanner-test` / `scanner-secret` → `GET /api/scan/me` 200, y compris quand tous les comptes déclarés sont désactivés. `admin-test` → `POST /api/scan/passages` 200. E29 à E31 n'acceptent aucun identifiant désignant le compte de configuration (il n'a pas d'`id` en base) : un `id` inexistant → 404.

**CA12 — Non-régression [IT + E2E]**
`mvn -B -f backend/pom.xml clean verify` est vert, incréments 1 à 7 compris, et la suite E2E aussi. Les CA du filet réseau de l'inc. 4 (file, FIFO, émetteur effectif, idempotence) et CA18 (inc. 4) passent sans modification. Le seul test existant modifié est l'assertion [config] de CA40 (inc. 5) (déjà évoluée par l'inc. 7), avec le motif de RG15.

**CA13 — Expiration glissante des identifiants SCANNER (RG10, CL9, CL10) [front-unit]**
Avec une horloge simulée et un stockage espionné :
- connexion SCANNER avec « Rester connecté » à J `08:00:00`, puis une réponse 200 de E6 à J `20:00:00` : identifiants lus à J+1 `19:59:59`, effacés à J+1 `20:00:00` ;
- sans réponse 2xx après la connexion (erreur réseau à J `20:00:00`) : identifiants lus à J+1 `07:59:59`, effacés à J+1 `08:00:00` ;
- le résultat est le même pour `benevole-07` et pour `scanner-test` ;
- connexion ADMIN : aucune écriture dans le stockage persistant.

**CA14 — Aucune suppression (RG3) [IT]**
Au démarrage du contexte complet, aucune correspondance Spring MVC `DELETE` n'existe sous `/api/admin/scanners`. Après désactivation de `benevole-07`, la ligne existe toujours en base avec `active = false`, et E28 la liste.

**CA15 — Identifiant scanneur et pseudo coureur identiques (RG2, RG4, CL13) [IT]** *(PO9 et PO15 inc. 5 tranchés)*
Donné le scanneur `benevole-07` / `motdepasse-7`.
- E3 (ou E26 de l'inc. 7) crée le compte pseudo `benevole-07` / `motdepasse-1` → 201.
- `benevole-07` / `motdepasse-7` : E19 → 200, `role = "SCANNER"` ; E21 → 401.
- `benevole-07` / `motdepasse-1` : E21 → 200, `pseudo = "benevole-07"` ; E19 → 401.
- Donné ensuite le scanneur `benevole-02` / `motdepasse-2`, E3 crée le pseudo `BENEVOLE-02` avec le **même** mot de passe `motdepasse-2` → 201. E19 donne 200 `SCANNER` et E21 donne 200 `RUNNER`, avec les mêmes identifiants.
- L'ADMIN désactive le scanneur `benevole-07` : E21 avec `benevole-07` / `motdepasse-1` donne toujours 200.
- Réciproquement, E27 `{"username":"lievre","password":"motdepasse-8"}` → 201 alors que le compte pseudo « Lievre » existe.

**CA16 — Limitation de débit sur E19 seulement (RG15, CL12) [config + manuel]** *(PO10 et PO11 tranchés)*
- [config] La configuration nginx versionnée contient :
  - une `limit_req_zone` propre à E19, indexée par `$binary_remote_addr`, en `rate=30r/m` ;
  - sur un `location` exact de `/api/scan/me`, `limit_req` sur cette zone avec `burst=10 nodelay` ;
  - `limit_req_status 429`.
  Aucun `location` couvrant `/api/scan/passages` n'a de `limit_req`. Les zones de RG23 (inc. 5) et celle de E26 (inc. 7) sont inchangées.
- [manuel] Sur le VPS, depuis une même IP, après 2 minutes sans requête :
  - 12 requêtes E19 avec `benevole-07`, en moins d'une seconde : les 11 premières reçoivent 200, la 12e reçoit 429 ;
  - immédiatement après, 50 requêtes E6 valides (même corps, idempotentes, RG12) en moins de 5 secondes : aucune ne reçoit 429.
- Aucun code applicatif ne compte les tentatives (revue).

**CA17 — Affichage d'un 429 à la connexion (RG15) [E2E + front-unit]**
- [E2E] Sur `/connexion`, entrée « Bénévole », la réponse de E19 est interceptée et remplacée par un 429 à corps HTML. Alors « Trop de tentatives. Réessayez dans une minute. » s'affiche, et le journal réseau ne contient qu'une seule requête E19.
- [front-unit] Ce 429 n'écrit rien dans l'emplacement ADMIN/SCANNER (mémoire et stockage espionnés).

**CA18 — Collision entre la configuration et un scanneur déclaré (RG2, CL11) [IT]**
Donné en base le scanneur `benevole-07`, désactivé. Quand le contexte démarre avec `BACKYARD_SECURITY_SCANNER_USERNAME=BENEVOLE-07`, le démarrage échoue, et le message d'erreur contient `BACKYARD_SECURITY_SCANNER_USERNAME`. Même résultat avec `BACKYARD_SECURITY_ADMIN_USERNAME=benevole-07`. Avec `BACKYARD_SECURITY_SCANNER_USERNAME=scanner-test`, le contexte démarre.

### Couverture
| RG / CL | CA |
|---|---|
| RG1 | CA1, CA8 |
| RG2 | CA2, CA15, CA18 |
| RG3 | CA2, CA3, CA14 |
| RG4 | CA3, CA15 |
| RG5 | CA11 |
| RG6 | CA9 |
| RG7 | CA10 |
| RG8 | CA12 |
| RG9 | CA4 |
| RG10 | CA5, CA13 |
| RG11 | CA6 |
| RG12 | CA7 |
| RG13 | CA8 |
| RG14 | CA8, CA9 |
| RG15 | CA16, CA17 |
| CL1 | CA4 |
| CL2 | CA7 |
| CL3 | CA3 (désactivation globale, vérifiée côté API) |
| CL4 | CA6 |
| CL5 | CA8 |
| CL6 | CA8 |
| CL7 | CA11 |
| CL8 | CA3 |
| CL9 | CA5, CA13 |
| CL10 | CA11, CA13 |
| CL11 | CA18 |
| CL12 | CA16 |
| CL13 | CA15 |

---

## 6. Points ouverts

### Points tranchés

**PO1 — Compte nominatif ou accès par poste. TRANCHÉ (utilisateur, 2026-09-28) : option A, compte nominatif pseudonyme** par bénévole (identifiant sans nom réel et mot de passe), créé et désactivable par l'admin, en HTTP Basic comme l'inc. 5. Options écartées : B (poste appairé par QR), C (les deux).

**PO2 — Limitation à certaines courses. TRANCHÉ (utilisateur, 2026-09-28) : non.** Règle : RG13.

**PO3 — Auteur d'un passage. TRANCHÉ (utilisateur, 2026-09-28) : aucun enregistrement** ; le schéma de `passage` ne change pas. Règle : RG14.

**PO4 — Compte SCANNER partagé de configuration. TRANCHÉ (utilisateur, 2026-09-28) : conservé en secours.** Règle : RG5.

**PO5 — Propriétaire de la file de scans. TRANCHÉ (utilisateur, 2026-09-28) : l'appareil.** Les scans en attente partent sous le compte connecté au moment de l'envoi ; les scans capturés après une désactivation sont acceptés (conséquence assumée). Règles : RG9, RG11.

**PO6 — Durée de validité. TRANCHÉ (utilisateur, 2026-09-28) : « Rester connecté » de 24 h glissantes, comme pour les coureurs.** Règle : RG10, qui étend la règle glissante au compte SCANNER de configuration, la PWA ne pouvant pas les distinguer.

**PO7 — Suppression ou désactivation. TRANCHÉ (utilisateur, 2026-09-28) : désactivation seulement.** Règle : RG3.

**PO8 — Mot de passe initial. TRANCHÉ (utilisateur, 2026-09-28) : saisi par l'admin.** Règle : RG3.

**PO9 — Rapport entre comptes scanneurs et comptes pseudo. TRANCHÉ (utilisateur, 2026-09-28) : entité distincte `ScannerAccount`, référentiels indépendants (options 1a et 2i).**
- `Account` reste sans rôle.
- Un pseudo coureur et un identifiant scanneur peuvent être homonymes ; PO15 (inc. 5) lève l'ambiguïté par le préfixe d'URL.
- L'identifiant scanneur est unique parmi les scanneurs et face aux comptes de configuration.
- L'hypothèse H11 de cet incrément est retirée. Choix confirmé (utilisateur, 2026-09-28) : l'unicité face à la configuration est aussi contrôlée au démarrage, par extension de RG32 (inc. 3) (CL11, CA18).
Règles : RG2, RG4. Critères : CA2, CA15, CA18.

**PO10 — Limitation des tentatives de connexion des scanneurs. TRANCHÉ (utilisateur, 2026-09-28) : option b, `limit_req` nginx sur E19 seulement.** E6 n'est pas limité. Règle : RG15. Critères : CA16, CA17. Seuils : PO11.

**PO15 (inc. 5) — Référentiel d'authentification. TRANCHÉ (utilisateur, 2026-09-28) : référentiel choisi par le préfixe d'URL.** Décision appliquée ici :
- à RG1 et RG4 ;
- à CA1 et CA2, où l'accès à `/api/account/**` et l'accès d'un compte pseudo aux endpoints admin passent en 401 (évolutions motivées) ;
- à CA15.

**PO11 — Seuils nginx pour E19 `GET /api/scan/me`. TRANCHÉ (utilisateur, 2026-09-28) : option a, mêmes seuils que `/api/account/**`.**
- 30 requêtes par minute et par IP, rafale de 10, `nodelay`, dans une zone propre à E19.
- L'hypothèse H12 est confirmée et devient une règle.
- Options écartées : b (seuils de l'inscription publique) et c (autres valeurs).
Règle : RG15. Critère : CA16.

**Renumérotation (2026-10-01, utilisateur).** Cette spec passe de l'inc. 7 à l'inc. 8 pour laisser la place à « Connexion et inscription » (nouvelle inc. 7). Endpoints décalés (E27 à E31). Voir la révision 5.

Aucun point ouvert ne subsiste pour cet incrément.
