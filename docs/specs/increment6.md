# Spec Incrément 6 — Séparation admin / public

> Projet : Backyard Ultra Tracker
> Date de rédaction : 2026-09-28
> - Révision 1 : mise en conformité avec la demande initiale de l'utilisateur.
> - Révision 2 (2026-09-28) : intégration des décisions de l'utilisateur sur PO1 à PO6. Le scan n'est plus masqué (PO6) : RG2, CA2 et CA4 sont corrigés en conséquence. Ajouts : RG6, CL7, CL8, CA10, CA11, PO7. La colonne « Si masquage » et la liste des tests à modifier en cas de masquage sont retirées (PO3).
> - Révision 3 (2026-09-28) : intégration des décisions de l'utilisateur sur PO7 (inc. 6) et PO15 (inc. 5), et de l'endpoint E25 créé par l'inc. 5 (PO17 inc. 5).
>   - PO7 : l'hypothèse H4 est confirmée et devient RG7. RG2 et CA2 admettent le bouton « Se connecter » de `/scan`.
>   - PO15 (inc. 5) : les cases croisées de la matrice passent de 403 à 401. RG6 et CA10 sont réécrits : le 403 d'E19 pour un compte coureur n'existe plus.
>   - CA1 couvre E1 à E25 (100 cas).
>
> Statut : **validée pour développement.** Aucun point ouvert ne subsiste.
> **Aucune implémentation ne démarre avant le GO de l'incrément 5** (règle 6 du workflow de validation, `CLAUDE.md`).
> Numérotation propre à l'incrément. Références externes : « RG29 (inc. 3) », « RG36 (inc. 4) », « RG10 (inc. 5) ».

**Besoin exprimé (mot pour mot).** « Séparation admin / public : l'espace admin ne doit pas être visible ni accessible aux visiteurs qui s'inscrivent. Précise si c'est une séparation de routes front, une authentification par rôle côté API, ou les deux, et l'impact sur les endpoints déjà existants (incréments 1 à 3). »

**Réponse (PO1 tranché) : les deux.** Le front masque l'administration ; l'API reste la barrière, avec les rôles existants. **Aucun endpoint ne change.**

---

## 0. Ce qui existe déjà, et ce qui manque

### Déjà en place
- **API, rôle exigé (inc. 3)** : la matrice RG29 (inc. 3) protège `/api/admin/**` par le rôle ADMIN. Réponses : anonyme 401, SCANNER 403, refus par défaut hors préfixes connus, identifiants faux 401 (RG30 inc. 3). Aucun controller ne teste le rôle (RG1 inc. 3). L'espace admin est donc **déjà inaccessible** par l'API à un visiteur.
- **Comptes coureurs (inc. 5)** : les identifiants d'un compte coureur ne sont reconnus que sur `/api/account/**`. Ailleurs, dont `/api/admin/**` et `/api/scan/**`, ils donnent 401 (RG9 et RG10 inc. 5, PO15 inc. 5 tranché : référentiel choisi par le préfixe d'URL). Authentification HTTP Basic, API sans état, identifiants coureur dans un emplacement distinct des identifiants ADMIN/SCANNER (RG9, RG21 inc. 5).
- **Front, garde des routes admin (inc. 4)** :
  - `/admin/**` exige une connexion ADMIN, et aucune requête `/api/admin/**` n'est émise avant (RG36 inc. 4) ;
  - un compte SCANNER voit « Accès réservé à l'administrateur », sans donnée admin (RG10 inc. 4).
- **Données** : aucune réponse publique ne contient de `qrToken`, sauf celles qui le rendent au coureur (RG16 inc. 3, RG11 inc. 5).

### Ce qui manque pour « ni visible ni accessible »
| # | Constat | Traitement |
|---|---|---|
| M1 | L'accueil public affiche un lien « Administration » (RG28 inc. 4). | Retiré (RG2). Le lien « Scan » est conservé (PO6). |
| M2 | `/admin`, ouvert par un visiteur, affiche l'écran de connexion (CA24 inc. 4) : l'espace admin se signale. | « Page introuvable » (RG3). |
| M3 | Le code de l'administration est probablement téléchargé et mis en cache par le service worker chez tout visiteur (RG45 inc. 4), ce qui reste **à vérifier sur le build**. | Module chargé à la demande (RG5). |
| M4 | `/api/admin/**` répond 401 ou 403, et non 404 : l'existence des chemins admin est détectable. | **Conservé** (PO3) : l'API n'est pas masquée. |
| M5 | Coureur connecté (inc. 5) qui ouvre `/admin` : comportement non spécifié. | « Page introuvable » (RG3, RG4). |

---

## 1. Décision : séparation par le front et par l'API (PO1 tranché)

| Couche | Rôle | Changement |
|---|---|---|
| **API** | Barrière effective : contrôle par rôle de RG29 (inc. 3) et RG10 (inc. 5). Codes 401 et 403 conservés, sans masquage. | **Aucun** : aucun endpoint, aucun code de réponse ne change. La matrice est vérifiée endpoint par endpoint (CA1). |
| **Front** | Masquage de l'administration : pas de lien public, « Page introuvable » pour un non-admin, code admin chargé à la demande, connexion depuis `/scan` sans mention de l'administration. | RG2 à RG7. Seuls des tests E2E de l'inc. 4 évoluent (section 4). |

Une séparation par le front n'est jamais une mesure de sécurité à elle seule : elle s'appuie ici sur l'API de l'inc. 3.

**Limites assumées** (PO3, PO5) : même origine, pas de restriction IP ni de VPN, API admin non masquée. Un visiteur technique peut donc constater l'existence de l'administration : codes 401/403 de `/api/admin/**`, route `/admin` servie par Spring Boot (RG53 inc. 4), nom des routes dans le code principal de la PWA. Seul le visiteur ordinaire ne la voit plus.

---

## 2. Périmètre

### Inclus
- Retrait de tout lien public vers l'espace admin et vers `/connexion`.
- Comportement de `/admin/**` pour un non-admin.
- Refus explicite, sur l'écran de connexion staff, des identifiants d'un compte coureur.
- Chargement séparé du code admin.
- Vérification, endpoint par endpoint, de la matrice d'accès E1 à E25.
- Écran de connexion atteint depuis `/scan`, sans mention de l'administration (RG7).
- Mise à jour des tests E2E existants concernés.

### Explicitement exclu
- Masquage de l'API admin (404 au lieu de 401/403) (PO3).
- Origine ou sous-domaine distinct pour l'admin, restriction par adresse IP ou VPN (PO5).
- Masquage de l'écran de scan (PO6).
- Comptes scanneurs déclarés : **incrément 7**.
- Toute modification des règles de course.

---

## 3. Règles de gestion

**RG1 — L'API reste l'autorité** *(PO1, PO3 tranchés)*
- L'accès à chaque endpoint est celui du tableau de la section 4. Il est identique à l'accès actuel : aucune règle de RG29 (inc. 3) ni de RG10 (inc. 5) n'est modifiée, et les codes 401 et 403 sont conservés.
- Aucune règle du front ne remplace un contrôle serveur.

**RG2 — Aucun lien public vers l'espace admin** *(PO4, PO6 tranchés)*
- Les écrans publics ne contiennent **aucun** lien, bouton ou texte menant à `/admin/**` ou à `/connexion`, ni le mot « Administration » : accueil, tableau de bord, détail d'un coureur, inscription et sa confirmation, `/compte` et `/compte/connexion` (inc. 5), écran de scan.
- **Seule exception** *(PO7 tranché)* : le bouton « Se connecter » de `/scan` sans identifiants (RG14 inc. 4) mène à l'écran de connexion, avec retour vers `/scan`. Cet écran suit RG7.
- Le lien « Scan » de l'accueil vers `/scan` (RG28 inc. 4) est **conservé** : l'écran de scan reste visible. Seule l'administration est masquée.
- Cela modifie RG28 (inc. 4) : le lien « Administration » est retiré.
- `/connexion` reste une route publique (RG5 inc. 4), atteinte en tapant son URL ou par le bouton « Se connecter » de `/scan`.

**RG3 — `/admin/**` pour un non-admin** *(PO2 tranché)*
- Anonyme, coureur connecté ou compte SCANNER (de configuration, ou déclaré à l'inc. 7) : `/admin/**` affiche l'écran « Page introuvable », identique à celui d'une route inconnue (RG5 inc. 4), avec un lien vers l'accueil.
  - Aucun formulaire de connexion n'apparaît.
  - Aucune requête `/api/admin/**` n'est émise.
- Cela modifie RG36 et RG10 (inc. 4) : l'écran de connexion, le message « Accès réservé à l'administrateur » et le bouton « Se connecter en administrateur » ne sont plus affichés sur `/admin/**`.
- L'admin se connecte par `/connexion`, dont il connaît l'URL (RG6 inc. 4, route inchangée). Après une connexion ADMIN sur `/connexion` ouverte directement, il est dirigé vers `/admin` ; après une connexion demandée par `/scan`, il revient à `/scan` (RG6 inc. 4).

**RG4 — Identifiants coureur et espace admin**
- Les identifiants coureur (RG21 inc. 5) ne sont jamais pris en compte par la garde des routes admin : seul le rôle `ADMIN` obtenu par E19 dans l'emplacement ADMIN/SCANNER l'est.
- Un coureur connecté est traité comme un anonyme pour `/admin/**` (RG3).

**RG5 — Code de l'administration séparé**
- Le code des écrans `/admin/**` forme un module chargé à la demande.
- Il n'est téléchargé qu'après une connexion ADMIN et une navigation vers `/admin/**`.
- Le service worker ne le met pas en cache à l'installation.
- `/scan` reste pleinement fonctionnel hors ligne (RG45 inc. 4). L'administration n'a pas besoin de l'être (RG43 inc. 4).
- Le fichier reste téléchargeable par qui connaît son URL : il n'est pas secret, et la protection reste l'API (RG1).
- L'ouverture des fichiers statiques (RG53 inc. 4) n'est pas modifiée.

**RG6 — Identifiants coureur saisis sur l'écran de connexion staff** *(PO15 inc. 5 appliqué)*
- Sur `/connexion`, la validation par E19 (RG6 inc. 4) peut concerner un compte coureur, puisqu'un coureur peut suivre le lien « Scan ».
- E19 (`/api/scan/me`) ne connaît que les comptes staff (RG9 inc. 5). Des identifiants coureur y reçoivent donc **401**, comme des identifiants faux. L'écran affiche « Identifiants invalides » (RG6 inc. 4) ; **rien n'est conservé** dans l'emplacement ADMIN/SCANNER, et aucune requête n'est rejouée.
- Les identifiants coureur éventuellement présents dans leur propre emplacement (RG21 inc. 5) ne sont pas touchés.
- **Évolution motivée (PO15 inc. 5)** : la révision 2 prévoyait un 403 et le message « Ce compte ne donne accès ni au scan ni à l'administration. ». Ce message est retiré, car E19 ne peut plus répondre 403 à un compte coureur.

**RG7 — Écran de connexion atteint depuis `/scan`** *(PO7 TRANCHÉ (utilisateur, 2026-09-28) : option a, H4 confirmée)*
- Quand la connexion est demandée depuis `/scan` (bouton « Se connecter », retour vers `/scan`, RG6 inc. 4), l'écran de connexion :
  - ne mentionne **jamais** l'administration : ni « Administration », ni « administrateur », ni aucun lien ou bouton vers `/admin/**` ;
  - accepte un compte ADMIN comme un compte SCANNER. L'ADMIN revient alors sur `/scan` et peut scanner (PO19 inc. 3).
- Après la connexion, `/scan` ne propose toujours aucun lien vers `/admin/**`. L'ADMIN qui veut administrer ouvre `/admin` par son URL (RG3).

---

## 4. Impact endpoint par endpoint

« Accès » : matrice RG29 (inc. 3), E19 (RG52 inc. 4) et RG10 (inc. 5). Numérotation des endpoints de l'inc. 5 : E20 à E25 (section 0 de sa spec).
Légende : Anon = anonyme, RUN = compte pseudo `RUNNER`, SCAN = SCANNER, ADM = ADMIN ; « ok » = autorisé.

Le référentiel d'authentification dépend du préfixe d'URL (**PO15 inc. 5, tranché**) :
- `/api/account/**` ne reconnaît que les comptes coureurs ;
- tous les autres chemins ne reconnaissent que les comptes staff.
Des identifiants inconnus du référentiel du chemin donnent donc 401 :
- RUN sur tout chemin hors `/api/account/**`, y compris `/api/public/**` (RG9, RG10 inc. 5). La PWA n'y envoie jamais d'identifiants coureur ;
- SCAN et ADM sur `/api/account/**`.
Les requêtes **anonymes** sur `/api/public/**` restent autorisées. Ces valeurs sont celles de RG10 (inc. 5) : cet incrément ne les modifie pas.

| # | Endpoint | Accès (Anon / RUN / SCAN / ADM) | Changement |
|---|---|---|---|
| E1 | GET `/api/public/races` | ok / 401 / ok / ok | aucun |
| E2 | GET `/api/public/races/{raceId}` | ok / 401 / ok / ok | aucun |
| E3 | POST `/api/public/races/{raceId}/registrations` | ok / 401 / ok / ok (corps de l'inc. 5) | aucun |
| E4 | GET `/api/public/races/{raceId}/board` | ok / 401 / ok / ok | aucun |
| E5 | GET `/api/public/runners/{runnerId}` | ok / 401 / ok / ok | aucun |
| E6 | POST `/api/scan/passages` | 401 / 401 / ok / ok | aucun (l'inc. 7 ajoute des comptes SCANNER) |
| E7 | POST `/api/admin/races` | 401 / 401 / 403 / ok | aucun |
| E8 | GET `/api/admin/races` | 401 / 401 / 403 / ok | aucun |
| E9 | GET `/api/admin/races/{raceId}` | 401 / 401 / 403 / ok | aucun |
| E10 | PUT `/api/admin/races/{raceId}` | 401 / 401 / 403 / ok | aucun |
| E11 | DELETE `/api/admin/races/{raceId}` | 401 / 401 / 403 / ok | aucun |
| E12 | POST `/api/admin/races/{raceId}/start` | 401 / 401 / 403 / ok | aucun |
| E13 | GET `/api/admin/races/{raceId}/runners` | 401 / 401 / 403 / ok | aucun |
| E14 | GET `/api/admin/runners/{runnerId}` | 401 / 401 / 403 / ok | aucun |
| E15 | PUT `/api/admin/runners/{runnerId}` | 401 / 401 / 403 / ok | aucun |
| E16 | DELETE `/api/admin/runners/{runnerId}` | 401 / 401 / 403 / ok | aucun |
| E17 | POST `/api/admin/runners/{runnerId}/dnf` | 401 / 401 / 403 / ok | aucun |
| E18 | POST `/api/admin/runners/{runnerId}/reintegration` | 401 / 401 / 403 / ok | aucun |
| E19 | GET `/api/scan/me` | 401 / 401 / ok / ok | aucun |
| E20 | POST `/api/account/races/{raceId}/registrations` | 401 / ok / 401 / 401 | aucun |
| E21 | GET `/api/account/me` | 401 / ok / 401 / 401 | aucun |
| E22 | PUT `/api/admin/accounts/{accountId}/password` | 401 / 401 / 403 / ok | aucun |
| E23 | PUT `/api/account/password` | 401 / ok / 401 / 401 | aucun |
| E24 | DELETE `/api/admin/accounts/{accountId}` | 401 / 401 / 403 / ok | aucun |
| E25 | GET `/api/admin/accounts` | 401 / 401 / 403 / ok | aucun |

Des identifiants Basic faux restent en 401 sur tous les chemins (RG30 inc. 3), car le filtre d'authentification passe avant l'autorisation. Toutes les réponses 401 ont le même `detail` et aucune ne porte d'en-tête `WWW-Authenticate`.

### Routes du front
| Route | Actuel (inc. 4 et 5) | Cible |
|---|---|---|
| `/` | liens « Scan » et « Administration » | lien « Scan » conservé ; aucun lien vers `/admin` ni `/connexion` (RG2) |
| `/admin`, `/admin/courses/{id}`, `/admin/courses/{id}/qr`, `/admin/comptes` (inc. 5) | anonyme : écran de connexion ; SCANNER : « Accès réservé » ; ADMIN : écran | non-ADMIN : « Page introuvable » ; ADMIN : écran (RG3) |
| `/connexion` | connexion ADMIN / SCANNER | liée seulement par le bouton « Se connecter » de `/scan` (RG2) ; sans mention de l'administration quand elle est atteinte depuis `/scan` (RG7) ; compte coureur refusé par « Identifiants invalides » (RG6) |
| `/scan` | accessible, liée depuis l'accueil | inchangée : accessible et liée depuis l'accueil ; bouton « Se connecter » sans identifiants (RG2, RG7) |
| `/compte`, `/compte/connexion` (inc. 5) | publiques | inchangées, sans lien vers l'administration (RG2) |
| Autres routes publiques | inchangées | inchangées |

### Tests existants à faire évoluer
Chaque évolution exige un motif écrit et l'accord de l'agent fonctionnel (règle 2 du workflow).
- **CA24 (inc. 4)** :
  - puce 1 : `/admin` sans connexion affiche « Page introuvable » au lieu de l'écran de connexion ; l'absence de requête `/api/admin/**` est conservée ;
  - puce 4 : SCANNER sur `/admin` affiche « Page introuvable » au lieu de « Accès réservé » ;
  - puce 5 : la connexion ADMIN se fait par `/connexion` ouverte directement.
- **CA21 (inc. 4)** : l'écran attendu à l'ouverture directe de `/admin`, `/admin/courses/{id}` et `/admin/courses/{id}/qr` sans connexion devient « Page introuvable ».
- Tout test E2E qui navigue vers `/admin` **en cliquant sur le lien « Administration » de l'accueil** doit ouvrir l'URL directement. Le testeur en fait l'inventaire. Les tests qui suivent le lien « Scan » ne changent pas.
- **Aucun test backend** ne change.

---

## 5. Cas limites

**CL1 — Visiteur qui vient de s'inscrire.** La page de confirmation, l'accueil, `/compte` et `/compte/connexion` ne contiennent aucun lien vers l'espace admin ni vers `/connexion`. Seul l'accueil propose « Scan ».
**CL2 — Visiteur qui tape `/admin/courses/1/qr`.** « Page introuvable », aucune requête `/api/admin/**`, aucune donnée ni QR affiché.
**CL3 — Coureur connecté, qui est aussi bénévole SCANNER.** `/admin` affiche « Page introuvable ». `/scan` fonctionne avec la connexion SCANNER ; les identifiants coureur sont conservés à part (RG21 inc. 5).
**CL4 — Identifiants ADMIN refusés (401) pendant une action admin.** Comportement de RG10 (inc. 4) inchangé : l'écran de connexion s'affiche **dans ce cas**, puisque la personne s'est déjà connectée ADMIN dans ce contexte.
**CL5 — Ancienne version de la PWA en cache**, qui contient encore le lien « Administration ». Après mise à jour (RG46 inc. 4), le lien disparaît. Avant la mise à jour, il mène à l'écran de connexion de l'ancienne version, sans aucun accès, puisque l'API est inchangée.
**CL6 — Sans objet.** Bascule de yard, réintégration, passage manuel ou scan, plusieurs courses, course non démarrée, coureur sans passage : aucune règle de course n'est modifiée (CA9).
**CL7 — Visiteur qui suit le lien « Scan ».** `/scan` demande une connexion par son bouton « Se connecter » (RG14 et RG6 inc. 4). S'il saisit ses identifiants de coureur, E19 répond 401 : « Identifiants invalides », rien n'est conservé, aucun accès au scan (RG6). Ni `/scan` ni l'écran de connexion ne contiennent le mot « Administration » ou un lien vers `/admin` (RG7).
**CL8 — ADMIN connecté depuis `/scan`.** Il revient sur `/scan` et peut scanner (PO19 inc. 3). Aucun lien vers `/admin` ne lui est proposé sur cet écran ; il ouvre `/admin` par son URL, qui s'affiche puisque le rôle ADMIN est connecté (RG7).

---

## 6. Critères d'acceptation

Comptes de test : ADMIN `admin-test` / `admin-secret`, SCANNER `scanner-test` / `scanner-secret`, compte pseudo `Lievre` / `motdepasse-1` (en E2E : `Lievre-{run}`).

**CA1 — Matrice d'accès vérifiée endpoint par endpoint (RG1) [slice]** *(PO15 inc. 5 appliqué : cases croisées en 401)*
- Pour chacun des endpoints E1 à E25, et pour chacun des profils anonyme, `Lievre`, SCANNER et ADMIN, une requête valide donne exactement le code de la colonne « Accès » de la section 4.
- Quand l'accès est autorisé, le statut est celui du service mocké : 200, 201 ou 204.
- Le test est **paramétré** à partir d'une table unique (25 × 4 = 100 cas).
- Toutes les réponses 401 de la table ont le même `detail`, sans en-tête `WWW-Authenticate`.
- Des identifiants `admin-test:mauvais` donnent 401 sur E1 à E25.

**CA2 — Aucun lien public vers l'espace admin (RG2, CL1) [E2E]**
En anonyme, puis connecté `Lievre-{run}`, on ouvre `/`, `/courses/{id}`, `/coureurs/{id}`, `/inscription/{id}`, la confirmation d'inscription, `/compte`, `/compte/connexion` et `/scan` (sans connexion staff). Dans ces pages :
- aucun élément `a[href]` ni bouton ne cible `/admin` ou `/admin/…` ;
- aucun élément ne mène à `/connexion`, **sauf**, sur `/scan`, le seul bouton « Se connecter » (RG2, RG7) ;
- le texte visible ne contient pas « Administration » ;
- sur `/`, un lien « Scan » cible `/scan`, et un clic sur ce lien ouvre l'écran de scan.

**CA3 — `/admin/**` pour un anonyme (RG3, CL2) [E2E]**
Dans un contexte neuf, l'ouverture de `/admin`, `/admin/courses/{id}` et `/admin/courses/{id}/qr` affiche « Page introuvable » et un lien vers l'accueil, sans champ de mot de passe. Le journal réseau ne contient aucune requête `/api/admin/**`.

**CA4 — `/admin/**` pour un coureur et pour un SCANNER (RG3, RG4, CL3) [E2E]**
- Connecté `Lievre-{run}` : `/admin` affiche « Page introuvable », sans requête `/api/admin/**`.
- Connecté SCANNER depuis `/scan` : `/admin` affiche « Page introuvable », sans requête `/api/admin/**`, et sans le texte « Accès réservé ».
- Dans ce même contexte, `/scan` fonctionne : une capture est envoyée et reçoit 200 ; `/compte` affiche toujours les inscriptions de `Lievre-{run}`.

**CA5 — Accès ADMIN (RG3) [E2E]**
- `/connexion` ouverte directement, avec `admin-test` / `admin-secret`, mène à `/admin`, qui liste les courses.
- `/admin/courses/{id}` affiche les coureurs.
- Après rechargement, `/admin` affiche « Page introuvable », car les identifiants ADMIN sont en mémoire seulement (RG7 inc. 4). Une nouvelle connexion par `/connexion` rend l'accès.

**CA6 — Code admin non chargé ni mis en cache par le public (RG5) [E2E]**
- En anonyme, on ouvre `/`, `/courses/{id}`, `/inscription/{id}`, `/scan` et `/admin`. Le journal réseau ne contient aucun fichier du module admin, identifié par son nom de chunk dans le build.
- Après installation du service worker, Cache Storage ne contient pas ce fichier.
- Après une connexion ADMIN et l'ouverture de `/admin`, le fichier est chargé.

**CA7 — Scan hors ligne préservé (RG5) [E2E]**
Après une visite en ligne de `/scan`, réseau coupé : `/scan` s'ouvre et une capture est mise en file. C'est la reprise de CA39 (inc. 4) sous Chromium et WebKit, réserve R4-1 comprise.

**CA8 — Identifiants ADMIN refusés pendant une action (CL4) [E2E]**
Connecté ADMIN sur `/admin/courses/{id}`, la prochaine réponse E13 est interceptée et remplacée par un 401. L'écran de connexion s'affiche avec « Session expirée… » (RG10 inc. 4), et non « Page introuvable ».

**CA9 — Non-régression et tests évolués (RG1, CL5, CL6) [IT + E2E]**
- `mvn -B -f backend/pom.xml clean verify` est vert, et **aucun test backend n'est modifié**.
- La suite E2E est verte.
- Les seuls tests E2E modifiés sont ceux listés en section 4, chacun avec son motif, et les assertions de sécurité ne sont pas affaiblies : l'absence de requête `/api/admin/**` est conservée.
- CL5 : vérification manuelle de la mise à jour (RG46 inc. 4), consignée dans le rapport.

**CA10 — Identifiants coureur sur l'écran de connexion staff (RG6, CL7) [E2E + front-unit]** *(évolution motivée, PO15 inc. 5 : 401 au lieu de 403)*
- [E2E] Connecté `Lievre-{run}`, on suit « Scan » depuis l'accueil, puis « Se connecter », et on saisit `Lievre-{run}` et son mot de passe. Alors :
  - E19 répond 401 (journal réseau) et « Identifiants invalides » s'affiche ;
  - `/scan` redemande une connexion ;
  - `/compte` affiche toujours les inscriptions sans nouvelle connexion.
- [front-unit] Une réponse 401 de E19 pendant la connexion n'écrit rien dans l'emplacement ADMIN/SCANNER, ni en mémoire ni dans le stockage (espionnés). Elle ne modifie pas l'emplacement coureur.

**CA11 — Connexion ADMIN depuis `/scan` (RG3, RG7, CL8) [E2E]**
- On suit « Scan » depuis l'accueil, puis « Se connecter ». L'écran de connexion affiché ne contient ni « Administration » ni « administrateur », et aucun lien vers `/admin`.
- On s'y connecte avec `admin-test` / `admin-secret`. Alors on revient sur `/scan`, la page ne contient ni « Administration » ni lien vers `/admin`, et une capture reçoit 200.
- `/admin` ouverte ensuite par son URL liste les courses.

### Couverture
| RG / CL | CA |
|---|---|
| RG1 | CA1, CA9 |
| RG2 | CA2 |
| RG3 | CA3, CA4, CA5, CA11 |
| RG4 | CA4 |
| RG5 | CA6, CA7 |
| RG6 | CA10 |
| RG7 | CA2, CA11 |
| CL1 | CA2 |
| CL2 | CA3 |
| CL3 | CA4 |
| CL4 | CA8 |
| CL5 | CA9 |
| CL6 | CA9 |
| CL7 | CA10 |
| CL8 | CA11 |

---

## 7. Points ouverts

### Points tranchés

**PO1 — Séparation par routes front, par rôle côté API, ou les deux. TRANCHÉ (utilisateur, 2026-09-28) : les deux (option C), sans masquage.** Le front masque l'administration ; l'API reste la barrière avec les rôles existants ; aucun endpoint ne change. Règles : RG1 à RG5.

**PO2 — Ce que voit un non-admin sur `/admin/**`. TRANCHÉ (utilisateur, 2026-09-28) : « Page introuvable »**, pour l'anonyme, le coureur et le scanneur. Le message « Accès réservé à l'administrateur » n'est pas conservé pour le SCANNER. Règle : RG3.

**PO3 — Masquage de l'API admin. TRANCHÉ (utilisateur, 2026-09-28) : pas de masquage** ; les codes 401 et 403 sont conservés. Règle : RG1.

**PO4 — Accès de l'admin à la connexion. TRANCHÉ (utilisateur, 2026-09-28) : URL `/connexion` connue, sans lien public.** Règles : RG2, RG3.

**PO5 — Séparation plus forte. TRANCHÉ (utilisateur, 2026-09-28) : aucune** (même origine, pas de restriction IP ni de VPN). Voir « Limites assumées », section 1.

**PO6 — Le scan fait-il partie de l'espace masqué ? TRANCHÉ (utilisateur, 2026-09-28) : non.** `/scan` reste visible et le lien « Scan » de l'accueil est conservé ; seule l'administration est masquée. Cette décision remplace l'hypothèse H2 de la révision 1 (« aucun lien public vers `/scan` ») : RG2, CA2 et CA4 sont corrigés.

**PO7 — Écran de connexion atteint depuis le lien « Scan ». TRANCHÉ (utilisateur, 2026-09-28) : option a, accepté (H4 confirmée).**
- L'écran de connexion atteint depuis `/scan` ne mentionne jamais l'administration.
- Un ADMIN peut quand même s'y connecter, et il revient sur `/scan`.
- Le bouton « Se connecter » de `/scan` est la seule exception admise à l'interdiction de lier `/connexion` depuis le public.
Options écartées : b (retirer le lien « Scan ») et c (réserver cette connexion au rôle SCANNER). Règles : RG2, RG7. Critères : CA2, CA11.

**PO15 (inc. 5) — Référentiel d'authentification. TRANCHÉ (utilisateur, 2026-09-28) : référentiel choisi par le préfixe d'URL.** Décision appliquée ici :
- à la matrice de la section 4, dont les cases croisées passent en 401 ;
- à RG6, où le 403 et son message sont retirés ;
- à CA1 et CA10 (évolutions motivées).

Aucun point ouvert ne subsiste pour cet incrément.
