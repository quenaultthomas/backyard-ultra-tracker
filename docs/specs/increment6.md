# Spec Incrément 6 — Séparation admin / public

> Projet : Backyard Ultra Tracker
> Date de rédaction : 2026-09-28
> - Révision 1 : mise en conformité avec la demande initiale de l'utilisateur.
> - Révision 2 (2026-09-28) : intégration des décisions de l'utilisateur sur PO1 à PO6. Le scan n'est plus masqué (PO6) : RG2, CA2 et CA4 sont corrigés en conséquence. Ajouts : RG6, CL7, CL8, CA10, CA11, PO7. La colonne « Si masquage » et la liste des tests à modifier en cas de masquage sont retirées (PO3).
> - Révision 3 (2026-09-28) : intégration des décisions de l'utilisateur sur PO7 (inc. 6) et PO15 (inc. 5), et de l'endpoint E25 créé par l'inc. 5 (PO17 inc. 5).
>   - PO7 : l'hypothèse H4 est confirmée et devient RG7. RG2 et CA2 admettent le bouton « Se connecter » de `/scan`.
>   - PO15 (inc. 5) : les cases croisées de la matrice passent de 403 à 401. RG6 et CA10 sont réécrits : le 403 d'E19 pour un compte coureur n'existe plus.
>   - CA1 couvre E1 à E25 (100 cas).
> - **Révision 4 (2026-10-01)** : vérification de chaque constat et de chaque règle face au code de l'inc. 5 mergé, et intégration des réserves R5-4, R5-5 et R5-8 de l'inc. 5.
>   - **Constats corrigés** (section 0) : le lien « Administration » n'est pas seulement sur l'accueil, il est aussi dans l'**en-tête de navigation présent sur tous les écrans** (`app.ts`) ; M3 est **confirmé** (`ngsw-config.json` précharge `/*.js`, donc tous les blocs chargés à la demande, admin compris) ; la garde actuelle ne contrôle qu'une session staff, le rôle étant tranché par la coquille admin.
>   - **RG2, RG5, RG7** précisées (en-tête, configuration du service worker, écran de connexion sans mention de l'administration quel que soit le retour demandé). **CL9** ajouté.
>   - **Tests E2E à faire évoluer** : ajout de `ca39-installability-offline.spec.ts` (lien « Administration » cliqué en WebKit). CA6 et CA7 précisés (configuration du service worker, identification du bloc admin, état de référence de CA39 sous Chromium). CA9 corrigé : des tests backend sont **ajoutés**, aucun test existant n'est modifié.
>   - **Nouveau périmètre** : RG8 (R5-4), RG9 (R5-5), RG10 (R5-8) ; CA12, CA13, CA14, avec l'agent responsable de chacune (section 6).
>   - Matrice E1 à E25 : **inchangée**, relue contre `SecurityConfig` et les contrôleurs.
>
> - **Révision 4b (2026-10-01)** : deux écarts tranchés par l'agent fonctionnel après l'écriture des tests. (1) CA1 et la note de la section 4 s'alignent sur le code : deux `detail` de 401 selon la cause, aucun endpoint ni test existant modifié. (2) CA10 : volet front-unit retiré, couvert par l'E2E seul (aucun changement de production).
>
> - **Révision 5 (2026-10-01, verdict de l'inc. 6)** : arbitrages de l'agent fonctionnel après la revue de cohérence. (1) RG5 : définition des « blocs admin » précisée, `race-form` et `account-admin-actions` assumés comme limite (COH6-1). (2) Section 4 : `ca25` ajouté, adaptations de `ca21`, `ca24`, `ca39` acceptées avec deux renforcements exigés (COH6-2, COH6-3). (3) CA11 et CL8 : « par son URL » = navigation du routeur, non rechargement (COH6-4).

Statut : **validée pour développement.** L'incrément 5 est mergé (GO sous réserves du 2026-09-30) : la restriction « aucune implémentation avant le GO de l'inc. 5 » est levée. Aucun point ouvert ne subsiste.
> Numérotation propre à l'incrément. Références externes : « RG29 (inc. 3) », « RG36 (inc. 4) », « RG10 (inc. 5) ».

**Besoin exprimé (mot pour mot).** « Séparation admin / public : l'espace admin ne doit pas être visible ni accessible aux visiteurs qui s'inscrivent. Précise si c'est une séparation de routes front, une authentification par rôle côté API, ou les deux, et l'impact sur les endpoints déjà existants (incréments 1 à 3). »

**Réponse (PO1 tranché) : les deux.** Le front masque l'administration ; l'API reste la barrière, avec les rôles existants. **Aucun endpoint ne change.**

**Réserves de l'inc. 5 portées par cet incrément.** Le verdict de l'inc. 5 a fixé trois réserves « avant le verdict de l'inc. 6 » : R5-4, R5-5, R5-8. Elles sont des critères d'acceptation de cet incrément (RG8 à RG10, CA12 à CA14) et suivent la règle 1 du workflow comme tous les autres.

---

## 0. Ce qui existe déjà, et ce qui manque

*(Révision 4 : relu contre le code de l'inc. 5 mergé : `SecurityConfig`, contrôleurs, `app.ts`, `app.routes.ts`, `admin-shell.ts`, `login-page.ts`, `scan-page.ts`, `home-page.ts`, `ngsw-config.json`, tests E2E.)*

### Déjà en place
- **API, rôle exigé (inc. 3)** : la matrice RG29 (inc. 3) protège `/api/admin/**` par le rôle ADMIN (`SecurityConfig` : `/api/scan/**` pour SCANNER ou ADMIN, `/api/admin/**` pour ADMIN, `anyRequest().denyAll()`). Réponses : anonyme 401, SCANNER 403, refus par défaut hors préfixes connus, identifiants faux 401 (RG30 inc. 3). Aucun controller ne teste le rôle (RG1 inc. 3). L'espace admin est donc **déjà inaccessible** par l'API à un visiteur.
- **Comptes coureurs (inc. 5)** : les identifiants d'un compte coureur ne sont reconnus que sur `/api/account/**` (chaîne de sécurité à part, `securityMatcher(ACCOUNT_PATHS)`). Ailleurs, dont `/api/admin/**` et `/api/scan/**`, ils donnent 401 (RG9 et RG10 inc. 5, PO15 inc. 5 tranché : référentiel choisi par le préfixe d'URL). Authentification HTTP Basic, API sans état, identifiants coureur dans un emplacement distinct des identifiants ADMIN/SCANNER (RG9, RG21 inc. 5).
- **Front, garde des routes admin (inc. 4)** :
  - la route `admin` est protégée par `requireSignedIn`, qui ne regarde que la **présence d'une session staff** (ADMIN ou SCANNER) ; sans session, renvoi vers `/connexion?retour=…` ; aucune requête `/api/admin/**` n'est émise avant (RG36 inc. 4) ;
  - la coquille `AdminShell` tranche ensuite sur le rôle : un compte SCANNER voit « Accès réservé à l'administrateur », un bouton « Se connecter en administrateur », et aucune donnée admin (RG10 inc. 4) ;
  - une URL inconnue sous `/admin` tombe sur la route `**` : « Page introuvable » pour tous (CA21 inc. 4).
  - Les identifiants coureur (`RunnerAuthState`) ne sont pas lus par cette garde.
- **Blocs chargés à la demande** : toutes les routes utilisent déjà `loadComponent` (le code admin est donc déjà dans des fichiers séparés), mais voir M3.
- **Données** : aucune réponse publique ne contient de `qrToken`, sauf celles qui le rendent au coureur (RG16 inc. 3, RG11 inc. 5).

### Ce qui manque pour « ni visible ni accessible »
| # | Constat (révision 4 : vérifié sur le code) | Traitement |
|---|---|---|
| M1 | Le lien « Administration » existe à **deux** endroits : le bouton de l'accueil (`home-page.ts`, RG28 inc. 4) **et** l'en-tête de navigation (`app.ts`, `<nav aria-label="Navigation principale">`), présent sur **tous** les écrans (accueil, tableau de bord, inscription, `/compte`, `/scan`, `/connexion`…). | Les deux sont retirés (RG2). Les liens « Courses », « Mes inscriptions » et « Scan » sont conservés (PO6). |
| M2 | `/admin`, ouvert par un visiteur, affiche l'écran de connexion (CA24 inc. 4) : l'espace admin se signale. La coquille admin propose aussi « Se connecter en administrateur » à un SCANNER. | « Page introuvable » (RG3), sans formulaire ni bouton vers la connexion. |
| M3 | **Confirmé** : `ngsw-config.json` déclare le groupe `app` en `installMode: prefetch` et `updateMode: prefetch` sur `/*.js`. Tous les fichiers JavaScript du build, donc les blocs de `loadComponent` admin, sont téléchargés et mis en cache à l'installation du service worker chez tout visiteur (RG45 inc. 4). | Les blocs admin sortent du préchargement (RG5). |
| M4 | `/api/admin/**` répond 401 ou 403, et non 404 : l'existence des chemins admin est détectable. | **Conservé** (PO3) : l'API n'est pas masquée. |
| M5 | Coureur connecté (inc. 5) qui ouvre `/admin` : comportement non spécifié (la garde ignore sa session, il tombe donc sur l'écran de connexion). | « Page introuvable » (RG3, RG4). |

---

## 1. Décision : séparation par le front et par l'API (PO1 tranché)

| Couche | Rôle | Changement |
|---|---|---|
| **API** | Barrière effective : contrôle par rôle de RG29 (inc. 3) et RG10 (inc. 5). Codes 401 et 403 conservés, sans masquage. | **Aucun** : aucun endpoint, aucun code de réponse ne change. La matrice est vérifiée endpoint par endpoint (CA1). |
| **Front** | Masquage de l'administration : pas de lien public, « Page introuvable » pour un non-admin, code admin chargé à la demande et non préchargé, connexion depuis `/scan` sans mention de l'administration. | RG2 à RG7. Seuls des tests E2E de l'inc. 4 évoluent (section 4). |
| **Backend, hors séparation** | Réserves de l'inc. 5 : preuve HTTP de la migration, gel de la normalisation du pseudo, journaux sans pseudo. | RG8 à RG10 : tests ajoutés, et un correctif de journalisation (RG10). Aucun endpoint ne change. |

Une séparation par le front n'est jamais une mesure de sécurité à elle seule : elle s'appuie ici sur l'API de l'inc. 3.

**Limites assumées** (PO3, PO5) : même origine, pas de restriction IP ni de VPN, API admin non masquée. Un visiteur technique peut donc constater l'existence de l'administration : codes 401/403 de `/api/admin/**`, route `/admin` servie par Spring Boot (RG53 inc. 4 ; `PwaPaths` ouvre `/admin` et `/admin/**` en lecture), nom des routes dans le code principal de la PWA. Seul le visiteur ordinaire ne la voit plus.

---

## 2. Périmètre

### Inclus
- Retrait de tout lien public vers l'espace admin et vers `/connexion` (hors bouton de `/scan`), **en-tête de navigation compris**.
- Comportement de `/admin/**` pour un non-admin.
- Refus explicite, sur l'écran de connexion staff, des identifiants d'un compte coureur.
- Blocs admin exclus du préchargement du service worker.
- Vérification, endpoint par endpoint, de la matrice d'accès E1 à E25.
- Écran de connexion sans mention de l'administration, atteint depuis `/scan` (RG7).
- Mise à jour des tests E2E existants concernés.
- **Réserves de l'inc. 5 (révision 4)** :
  - R5-4 : volet HTTP de CA31 (inc. 5) sur une base migrée depuis V1 (RG8, CA12) ;
  - R5-5 : test de lecture de sources figeant la normalisation du pseudo (RG9, CA13) ;
  - R5-8 : journal de `ApiExceptionHandler` sans pseudo (RG10, CA14).

### Explicitement exclu
- Masquage de l'API admin (404 au lieu de 401/403) (PO3).
- Origine ou sous-domaine distinct pour l'admin, restriction par adresse IP ou VPN (PO5).
- Masquage de l'écran de scan (PO6).
- Comptes scanneurs déclarés : **incrément 7**.
- Toute modification des règles de course.
- Autres réserves de l'inc. 5 : R5-1 (journaux et 429 nginx sur le VPS), R5-2 (PostgreSQL réel), R5-3 (CA39 sous Chromium) et R5-6 (doublon du lien « J'ai déjà un compte ») : leurs échéances sont « avant déploiement » ou sans échéance ; elles ne sont pas des critères de cet incrément.
- Modification du **corps** des réponses d'erreur 500 (le `detail` de `handleInconsistency` reste celui de l'inc. 5) : RG10 ne porte que sur les journaux.

---

## 3. Règles de gestion

**RG1 — L'API reste l'autorité** *(PO1, PO3 tranchés)*
- L'accès à chaque endpoint est celui du tableau de la section 4. Il est identique à l'accès actuel : aucune règle de RG29 (inc. 3) ni de RG10 (inc. 5) n'est modifiée, et les codes 401 et 403 sont conservés.
- Aucune règle du front ne remplace un contrôle serveur.

**RG2 — Aucun lien public vers l'espace admin** *(PO4, PO6 tranchés ; révision 4 : en-tête de navigation)*
- Les écrans publics ne contiennent **aucun** lien, bouton ou texte menant à `/admin/**` ou à `/connexion`, ni le mot « Administration » : accueil, tableau de bord, détail d'un coureur, inscription et sa confirmation, `/compte` et `/compte/connexion` (inc. 5), écran de scan, et `/connexion` elle-même.
- Cela vaut pour l'**en-tête de navigation commun** (composant racine) comme pour le contenu de chaque page : le lien « Administration » de l'en-tête et le bouton « Administration » de l'accueil sont tous deux retirés.
- **Seule exception** *(PO7 tranché)* : le lien « Se connecter » de `/scan` sans identifiants (RG14 inc. 4) mène à l'écran de connexion, avec retour vers `/scan`. Cet écran suit RG7.
- Le lien « Scan » (accueil et en-tête) vers `/scan` (RG28 inc. 4) est **conservé** : l'écran de scan reste visible. Les liens « Courses » et « Mes inscriptions » de l'en-tête sont conservés. Seule l'administration est masquée.
- Cela modifie RG28 (inc. 4) : le lien « Administration » est retiré partout.
- `/connexion` reste une route publique (RG5 inc. 4), atteinte en tapant son URL ou par le lien « Se connecter » de `/scan`.
- Les écrans admin eux-mêmes (visibles seulement par l'ADMIN, RG3) ne sont pas concernés : « Courses (admin) », « Comptes », « Imprimer les QR codes », etc. restent.

**RG3 — `/admin/**` pour un non-admin** *(PO2 tranché)*
- Anonyme, coureur connecté ou compte SCANNER (de configuration, ou déclaré à l'inc. 7) : `/admin/**` affiche l'écran « Page introuvable », identique à celui d'une route inconnue (RG5 inc. 4), avec un lien vers l'accueil.
  - Aucun formulaire de connexion n'apparaît.
  - Aucune requête `/api/admin/**` n'est émise.
  - L'adresse reste celle demandée : aucune redirection vers `/connexion`.
- Une URL inconnue sous `/admin` (par exemple `/admin/inconnu`) affiche déjà « Page introuvable » pour tous (CA21 inc. 4) : inchangé.
- Cela modifie RG36 et RG10 (inc. 4) : l'écran de connexion, le message « Accès réservé à l'administrateur » et le bouton « Se connecter en administrateur » ne sont plus affichés sur `/admin/**`. La garde et la coquille admin ne peuvent plus laisser un non-ADMIN voir autre chose que « Page introuvable ».
- L'admin se connecte par `/connexion`, dont il connaît l'URL (RG6 inc. 4, route inchangée). Après une connexion ADMIN sur `/connexion` ouverte directement, il est dirigé vers `/admin` ; après une connexion demandée par `/scan`, il revient à `/scan` (RG6 inc. 4).

**RG4 — Identifiants coureur et espace admin**
- Les identifiants coureur (RG21 inc. 5) ne sont jamais pris en compte par la garde des routes admin : seul le rôle `ADMIN` obtenu par E19 dans l'emplacement ADMIN/SCANNER l'est.
- Un coureur connecté est traité comme un anonyme pour `/admin/**` (RG3).

**RG5 — Code de l'administration séparé et non préchargé** *(révision 4 : M3 confirmé)*
- Le code des écrans `/admin/**` forme des blocs chargés à la demande (déjà le cas par `loadComponent`) **et exclus du préchargement du service worker** : ni `installMode` ni `updateMode` `prefetch` ne les couvrent. La configuration `ngsw-config.json` actuelle (`/*.js` en `prefetch`) ne satisfait pas cette règle.
- Sont « blocs admin » les fichiers du build qui contiennent le code des écrans admin, repérés par le texte propre à ces écrans (« Administration des courses », « Gérer la course et les coureurs », « Imprimer les QR codes »). Le code partagé avec les écrans publics ou de scan (boîte de confirmation, bouton « Se déconnecter », etc.) reste dans les blocs publics ; il n'est pas admin.
- **Limite assumée (révision 5, COH6-1, décision de l'agent fonctionnel)** : sont exclus du préchargement les **fichiers de route** `admin-*.js` (coquille, liste des courses, course, comptes, planche de QR codes), motif `!/admin-*.js` de `ngsw-config.json`. Deux composants **uniquement importés par des écrans admin**, `race-form` (formulaire de course, « Durée de boucle ») et `account-admin-actions` (« Réinitialiser le mot de passe »), sont placés par le compilateur dans des blocs partagés `chunk-<hash>.js` (importés par plusieurs écrans admin) qui **restent préchargés**. Cela est accepté : ils ne contiennent ni donnée ni route (libellés de formulaire et appel d'API via les constantes `API_PATHS` déjà présentes dans le code principal, limite déjà assumée en section 1) ; la protection reste l'API (RG1) ; l'impossibilité de les exclure par motif de nom sans changer la production justifie de ne pas élargir RG5. CA6 vérifie le périmètre ainsi défini. **Fragilité consignée** : l'exclusion repose sur le préfixe de nom `admin-` ; tout nouvel écran admin (notamment ceux de l'incrément 7, comptes scanneurs) doit être un fichier de route `admin-*` et son rapport de validation doit lister les blocs partagés préchargés qu'il introduit.
- Un bloc admin n'est téléchargé qu'après une connexion ADMIN et une navigation vers `/admin/**`.
- `/scan` reste pleinement fonctionnel hors ligne (RG45 inc. 4). L'administration n'a pas besoin de l'être (RG43 inc. 4) : un ADMIN hors ligne qui n'a pas encore chargé les blocs admin n'ouvre pas l'administration, conséquence assumée.
- Le fichier reste téléchargeable par qui connaît son URL : il n'est pas secret, et la protection reste l'API (RG1).
- L'ouverture des fichiers statiques (RG53 inc. 4) n'est pas modifiée.

**RG6 — Identifiants coureur saisis sur l'écran de connexion staff** *(PO15 inc. 5 appliqué)*
- Sur `/connexion`, la validation par E19 (RG6 inc. 4) peut concerner un compte coureur, puisqu'un coureur peut suivre le lien « Scan ».
- E19 (`/api/scan/me`) ne connaît que les comptes staff (RG9 inc. 5). Des identifiants coureur y reçoivent donc **401**, comme des identifiants faux. L'écran affiche « Identifiants invalides » (RG6 inc. 4) ; **rien n'est conservé** dans l'emplacement ADMIN/SCANNER, et aucune requête n'est rejouée.
- Les identifiants coureur éventuellement présents dans leur propre emplacement (RG21 inc. 5) ne sont pas touchés.
- **Évolution motivée (PO15 inc. 5)** : la révision 2 prévoyait un 403 et le message « Ce compte ne donne accès ni au scan ni à l'administration. ». Ce message est retiré, car E19 ne peut plus répondre 403 à un compte coureur.

**RG7 — Écran de connexion sans mention de l'administration** *(PO7 TRANCHÉ (utilisateur, 2026-09-28) : option a, H4 confirmée ; révision 4 : valable quel que soit le retour demandé)*
- L'écran de connexion ne mentionne **jamais** l'administration : ni « Administration », ni « administrateur », ni aucun lien ou bouton vers `/admin/**`. Le code actuel (`login-page.ts`) ne contient aucune mention ; seul l'en-tête commun (RG2) en apportait une.
- Quand la connexion est demandée depuis `/scan` (lien « Se connecter », retour vers `/scan`, RG6 inc. 4), l'écran accepte un compte ADMIN comme un compte SCANNER. L'ADMIN revient alors sur `/scan` et peut scanner (PO19 inc. 3).
- Après la connexion, `/scan` ne propose toujours aucun lien vers `/admin/**`. L'ADMIN qui veut administrer ouvre `/admin` par son URL (RG3).

**RG8 — Volet HTTP de la migration V2 sur une base migrée depuis V1** *(réserve R5-4, CA31 inc. 5)*
- La preuve que les coureurs créés avant l'inc. 5 s'affichent « Coureur n°{bib} » par HTTP ne doit pas reposer sur une base créée directement en V2 avec des lignes insérées après coup (limite consignée en inc. 5).
- Elle porte sur une base **migrée de V1 vers V2 par Flyway alors qu'elle contient déjà des données**, sur laquelle le contexte applicatif complet est ensuite démarré. Les lignes ne sont jamais réinsérées après la migration.
- Les valeurs attendues sont celles de CA31 (inc. 5), reprises dans CA12.

**RG9 — Normalisation du pseudo en un seul endroit, figée par un test** *(réserve R5-5, CA2 inc. 5, RG2 inc. 5)*
- Dans le code de production (`backend/src/main`), la mise en minuscules d'un pseudo n'apparaît qu'à un seul endroit : `fr.backyard.domain.Pseudo` (`toLowerCase`, une occurrence). Aucune requête sur le pseudo n'utilise `lower`, `upper`, `IgnoreCase` ni `ILIKE`.
- Un test automatisé, exécuté à chaque `mvn verify`, lit les sources et échoue si la règle est violée (CA13). La revue manuelle de l'inc. 5 ne suffit plus.
- Toute future normalisation de casse ailleurs (par exemple un pseudonyme de scanneur, inc. 7) exige de faire évoluer cette règle et ce test, avec motif écrit et accord de l'agent fonctionnel (règle 2 du workflow) ; elle ne passe pas inaperçue.

**RG10 — Aucun pseudo dans les journaux d'erreur interne** *(réserve R5-8, RG16 inc. 5)*
- Les gestionnaires d'`ApiExceptionHandler` qui journalisent l'exception complète, `handleInconsistency` (`IllegalStateException`, `IllegalArgumentException`) et `handleUnexpected` (toute autre exception, même risque, même règle), n'écrivent **ni le message de l'exception, ni celui de ses causes, ni la trace** : un message peut contenir un pseudo, et RG16 (inc. 5) interdit tout pseudo, à tout niveau de journal.
- La ligne ERROR reste exploitable : niveau ERROR, méthode, chemin de la requête et **nom de la classe de l'exception** (comme le fait déjà `handleDataIntegrity`, qui n'écrit que `getSimpleName()` de la cause).
- Le statut et le code de la réponse (`INTERNAL_INCONSISTENCY` 500 et `INTERNAL_ERROR` 500) ne changent pas. Le corps de la réponse n'est pas modifié (exclusion, section 2).

---

## 4. Impact endpoint par endpoint

« Accès » : matrice RG29 (inc. 3), E19 (RG52 inc. 4) et RG10 (inc. 5). Numérotation des endpoints de l'inc. 5 : E20 à E25 (section 0 de sa spec).
Légende : Anon = anonyme, RUN = compte pseudo `RUNNER`, SCAN = SCANNER, ADM = ADMIN ; « ok » = autorisé.
*(Révision 4 : relu contre les 25 mappings d'API des contrôleurs et `SecurityConfig` ; aucun endpoint ajouté depuis la révision 3, aucune valeur modifiée.)*

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

Des identifiants Basic faux restent en 401 sur tous les chemins (RG30 inc. 3), car le filtre d'authentification passe avant l'autorisation. Aucune réponse 401 ne porte d'en-tête `WWW-Authenticate`. *(Révision 4b : le `detail` n'est pas unique, il dépend de la cause, comme dans le code de l'inc. 5 : « Authentification requise » quand aucun en-tête `Authorization` n'est présenté, « Identifiants invalides » quand des identifiants sont présentés mais non reconnus par le référentiel du chemin, y compris un compte coureur hors `/api/account/**`. Source : `ProblemDetailAuthenticationEntryPoint`. Aucune réponse ne change.)*

### Routes du front
| Route | Actuel (inc. 4 et 5) | Cible |
|---|---|---|
| En-tête de navigation (toutes les pages) | liens « Courses », « Mes inscriptions », « Scan », « Administration » | « Administration » retiré ; les trois autres conservés (RG2) |
| `/` | liens « Scan » et « Administration » (bouton de la page) | lien « Scan » conservé ; aucun lien vers `/admin` ni `/connexion` (RG2) |
| `/admin`, `/admin/courses/{id}`, `/admin/courses/{id}/qr`, `/admin/comptes` (inc. 5) | anonyme : écran de connexion ; coureur : écran de connexion ; SCANNER : « Accès réservé » ; ADMIN : écran | non-ADMIN : « Page introuvable » ; ADMIN : écran (RG3) |
| `/admin/inconnu` | « Page introuvable » pour tous | inchangé |
| `/connexion` | connexion ADMIN / SCANNER | liée seulement par le lien « Se connecter » de `/scan` (RG2) ; sans mention de l'administration (RG7) ; compte coureur refusé par « Identifiants invalides » (RG6) |
| `/scan` | accessible, liée depuis l'accueil et l'en-tête | inchangée : accessible et liée depuis l'accueil et l'en-tête ; lien « Se connecter » sans identifiants (RG2, RG7) |
| `/compte`, `/compte/connexion` (inc. 5) | publiques | inchangées, sans lien vers l'administration (RG2) |
| Autres routes publiques | inchangées | inchangées |

### Tests existants à faire évoluer
Chaque évolution exige un motif écrit et l'accord de l'agent fonctionnel (règle 2 du workflow).
- **CA24 (inc. 4)**, `e2e/tests/ca24-login-roles.spec.ts` :
  - test « `/admin` sans connexion » : `/admin` sans connexion affiche « Page introuvable » au lieu de l'écran de connexion ; l'absence de requête `/api/admin/**` est conservée ;
  - test « connexion scanner (mémorisée) puis accès admin refusé » : SCANNER sur `/admin` affiche « Page introuvable » au lieu de « Accès réservé » ; la partie « aucune donnée admin » (texte « Gérer les courses » absent, statuts `/api/admin/**` éventuels à 403) est conservée ;
  - la connexion ADMIN se fait par `/connexion` ouverte directement (test « connexion admin », déjà conforme).
- **CA21 (inc. 4)**, `e2e/tests/ca21-routes.spec.ts` : l'écran attendu à l'ouverture directe de `/admin` et `/admin/courses/{id}/qr` sans connexion (tests « … affiche l'écran de connexion ») devient « Page introuvable ». Le test `/admin/inconnu` est inchangé.
- **CA39 (inc. 4)**, `e2e/tests/ca39-installability-offline.spec.ts` *(ajout de la révision 4)* : le parcours WebKit clique le lien « Administration » de l'en-tête (lignes 100 et 122) ; il ouvre `/admin` par navigation directe ou supprime ces étapes, sans affaiblir les assertions hors ligne de `/scan`. Le parcours Chromium (`page.goto('/admin')` servi par le service worker, un titre `h1` ou `h2` visible) reste valable : « Page introuvable » est un `h1`. L'inventaire de l'inc. 6 : ce sont les **seuls** tests de la suite qui cliquent le lien « Administration » (relevé sur les sources de l'inc. 5 mergé) ; le testeur le confirme.
- **CA25 (inc. 4)**, `e2e/tests/ca25-credentials-storage.spec.ts` *(ajout de la révision 5, COH6-2)* : test « connexion ADMIN : ni le mot de passe ni le Basic ne sont stockés ; reconnexion redemandée » : après rechargement sur `/admin`, « Page introuvable » à la place de l'écran de connexion (RG3, CA5), avec une assertion ajoutée (titre « Administration des courses » absent). Les assertions de stockage (mot de passe et Basic absents) sont inchangées. Accord de l'agent fonctionnel : **oui**, l'attendu découle directement de RG3 et l'assertion est plus stricte. Inventaire : l'oubli de la révision 4 venait d'un test qui rechargeait `/admin` sans cliquer le lien ; la liste de cette section est désormais **ca21, ca24, ca25, ca39**.
- **Précisions et renforcements exigés (révision 5, COH6-3)** :
  - `ca24`, test « connexion scanner (mémorisée) puis `/admin` » : la boucle sur les statuts 403 est désormais vide (aucune requête admin n'est émise). Accord pour la conserver, à condition d'ajouter l'assertion `adminRequests` égale à `[]` dans ce même test, afin que l'absence de requête ne repose pas sur CA4 seul.
  - `ca39`, branche WebKit : accord sur l'ordre des visites modifié (motif LIM-E2E-2 : un `goto` remplace le document) et sur la navigation du routeur hors ligne (`pushState` + `popstate`). L'assertion `h1, h2` visible étant satisfaite par le titre « Scan » déjà affiché, elle est **refusée comme preuve** : après la navigation du routeur hors ligne, le test doit asserter le titre de niveau 1 « Page introuvable ». La branche Chromium est inchangée.
  - `ca21` (B1, B2) et les titres renommés de `ca24` : accord sans réserve.
- Tout autre test E2E qui navigue vers `/admin` en cliquant sur le lien « Administration » doit ouvrir l'URL directement. Les tests qui suivent le lien « Scan » ne changent pas. Les tests qui ouvrent `/connexion?retour=…` (helper `loginAdmin`) ne changent pas.
- **Aucun test backend existant n'est modifié, supprimé ni assoupli.** Des tests sont **ajoutés** (CA1, CA12, CA13, CA14).

---

## 5. Cas limites

**CL1 — Visiteur qui vient de s'inscrire.** La page de confirmation, l'accueil, `/compte` et `/compte/connexion` ne contiennent aucun lien vers l'espace admin ni vers `/connexion`, **en-tête compris**. Seuls l'accueil et l'en-tête proposent « Scan ».
**CL2 — Visiteur qui tape `/admin/courses/1/qr`.** « Page introuvable », aucune requête `/api/admin/**`, aucune donnée ni QR affiché.
**CL3 — Coureur connecté, qui est aussi bénévole SCANNER.** `/admin` affiche « Page introuvable ». `/scan` fonctionne avec la connexion SCANNER ; les identifiants coureur sont conservés à part (RG21 inc. 5).
**CL4 — Identifiants ADMIN refusés (401) pendant une action admin.** Comportement de RG10 (inc. 4) inchangé : l'écran de connexion s'affiche **dans ce cas** (`SessionService.expire`, renvoi vers `/connexion?retour=…`), puisque la personne s'est déjà connectée ADMIN dans ce contexte. Si elle se reconnecte avec un compte SCANNER, le retour vers `/admin/…` affiche « Page introuvable » (RG3).
**CL5 — Ancienne version de la PWA en cache**, qui contient encore le lien « Administration » et le préchargement des blocs admin. Après mise à jour (RG46 inc. 4), le lien disparaît. Avant la mise à jour, il mène à l'écran de connexion de l'ancienne version, sans aucun accès, puisque l'API est inchangée. Les blocs admin déjà mis en cache sont retirés avec l'ancienne version du service worker (mise à jour du cache applicatif).
**CL6 — Sans objet.** Bascule de yard, réintégration, passage manuel ou scan, plusieurs courses, course non démarrée, coureur sans passage : aucune règle de course n'est modifiée (CA9).
**CL7 — Visiteur qui suit le lien « Scan ».** `/scan` demande une connexion par son lien « Se connecter » (RG14 et RG6 inc. 4). S'il saisit ses identifiants de coureur, E19 répond 401 : « Identifiants invalides », rien n'est conservé, aucun accès au scan (RG6). Ni `/scan` ni l'écran de connexion ne contiennent le mot « Administration » ou un lien vers `/admin` (RG7).
**CL8 — ADMIN connecté depuis `/scan`.** Il revient sur `/scan` et peut scanner (PO19 inc. 3). Aucun lien vers `/admin` ne lui est proposé sur cet écran ni dans l'en-tête ; il ouvre `/admin` en saisissant son adresse **dans l'application sans rechargement** (navigation du routeur, révision 5), qui s'affiche puisque le rôle ADMIN est connecté (RG7). Après un rechargement, il doit se reconnecter par `/connexion` (CL9).
**CL9 — ADMIN dans l'administration (révision 4).** L'en-tête commun n'a plus de lien « Administration » : depuis la page d'accueil, l'ADMIN revient à l'administration par son URL ou par l'historique du navigateur. Dans l'administration, la barre « Courses (admin) » (coquille admin) reste son moyen de navigation. Ses identifiants sont en mémoire seulement : un rechargement exige une nouvelle connexion par `/connexion` (RG7 inc. 4).
**CL10 — Base existante avant l'inc. 5 (révision 4).** Coureurs créés avec un nom réel, base migrée en V2 (RG8) : ils s'affichent « Coureur n°{bib} » dans toutes les réponses, et aucun nom réel ne ressort (CA12).
**CL11 — Exception dont le message contient un pseudo (révision 4).** Un `IllegalStateException` levé avec un message qui cite un pseudo est journalisé sans ce message ni sa trace (RG10, CA14).

---

## 6. Critères d'acceptation

Comptes de test : ADMIN `admin-test` / `admin-secret`, SCANNER `scanner-test` / `scanner-secret`, compte pseudo `Lievre` / `motdepasse-1` (en E2E : `Lievre-{run}`).

Agents responsables : implémentation front et correctif de journalisation : `developpeur` ; tests unitaires et slice : `testeur` ; tests d'intégration : `test-integration-backend` ; E2E : `test-e2e-frontend` ; contrôle : `revue-coherence-patrimoine` ; verdict : `fonctionnel`.

**CA1 — Matrice d'accès vérifiée endpoint par endpoint (RG1) [slice]** *(PO15 inc. 5 appliqué : cases croisées en 401)* — agent : `testeur`
- Pour chacun des endpoints E1 à E25, et pour chacun des profils anonyme, `Lievre`, SCANNER et ADMIN, une requête valide donne exactement le code de la colonne « Accès » de la section 4.
- Quand l'accès est autorisé, le statut est celui du service mocké : 200, 201 ou 204.
- Le test est **paramétré** à partir d'une table unique (25 × 4 = 100 cas).
- Toutes les réponses 401 de la table sont sans en-tête `WWW-Authenticate`. Leur `detail` est « Authentification requise » pour le profil anonyme, et « Identifiants invalides » pour `Lievre`, SCANNER et ADMIN lorsqu'ils présentent des identifiants non reconnus par le référentiel du chemin (révision 4b : aligné sur le code, valeurs figées aussi par `AccountContractIT#ca13_accessMatrix` et `SecuritySliceTest`, qui restent inchangés).
- Des identifiants `admin-test:mauvais` donnent 401 sur E1 à E25.
- Ce test s'ajoute à `AccountContractIT#ca13_accessMatrix` (inc. 5), qui reste inchangé.

**CA2 — Aucun lien public vers l'espace admin (RG2, CL1) [E2E]** — agent : `test-e2e-frontend`
En anonyme, puis connecté `Lievre-{run}`, on ouvre `/`, `/courses/{id}`, `/coureurs/{id}`, `/inscription/{id}`, la confirmation d'inscription, `/compte`, `/compte/connexion`, `/scan` (sans connexion staff) et `/connexion`. Dans ces pages, **en-tête de navigation compris** :
- aucun élément `a[href]` ni bouton ne cible `/admin` ou `/admin/…` ;
- aucun élément ne mène à `/connexion`, **sauf**, sur `/scan`, le seul lien « Se connecter » (RG2, RG7) ;
- le texte visible ne contient pas « Administration » ;
- sur `/`, un lien « Scan » cible `/scan` (celui de l'en-tête et celui de l'accueil, selon la page), et un clic sur ce lien ouvre l'écran de scan ; les liens « Courses » et « Mes inscriptions » de l'en-tête sont toujours présents.

**CA3 — `/admin/**` pour un anonyme (RG3, CL2) [E2E]** — agent : `test-e2e-frontend`
Dans un contexte neuf, l'ouverture de `/admin`, `/admin/courses/{id}`, `/admin/courses/{id}/qr`, `/admin/comptes` et `/admin/inconnu` affiche « Page introuvable » et un lien vers l'accueil, sans champ de mot de passe, et l'adresse n'a pas changé (pas de redirection vers `/connexion`). Le journal réseau ne contient aucune requête `/api/admin/**`.

**CA4 — `/admin/**` pour un coureur et pour un SCANNER (RG3, RG4, CL3) [E2E]** — agent : `test-e2e-frontend`
- Connecté `Lievre-{run}` : `/admin` affiche « Page introuvable », sans requête `/api/admin/**`.
- Connecté SCANNER depuis `/scan` : `/admin` affiche « Page introuvable », sans requête `/api/admin/**`, et sans les textes « Accès réservé » ni « Se connecter en administrateur ».
- Dans ce même contexte, `/scan` fonctionne : une capture est envoyée et reçoit 200 ; `/compte` affiche toujours les inscriptions de `Lievre-{run}`.

**CA5 — Accès ADMIN (RG3) [E2E]** — agent : `test-e2e-frontend`
- `/connexion` ouverte directement, avec `admin-test` / `admin-secret`, mène à `/admin`, qui liste les courses.
- `/admin/courses/{id}` affiche les coureurs.
- Après rechargement, `/admin` affiche « Page introuvable », car les identifiants ADMIN sont en mémoire seulement (RG7 inc. 4). Une nouvelle connexion par `/connexion` rend l'accès.

**CA6 — Code admin non préchargé ni mis en cache par le public (RG5) [E2E]** — agents : `developpeur` (configuration), `test-e2e-frontend` (test)
- Le test s'exécute sur le **build de production avec service worker actif**, comme CA39 (inc. 4) ; les autres tests E2E, qui bloquent le service worker (LIM-E2E-1), ne conviennent pas.
- Les blocs admin sont identifiés **dans le répertoire du build**, par les textes de RG5 ; la liste des fichiers ainsi trouvés n'est pas vide, sinon le test échoue (le test ne peut pas passer à vide).
- En anonyme, on ouvre `/`, `/courses/{id}`, `/inscription/{id}`, `/scan` et `/admin`. Le journal réseau ne contient aucun bloc admin.
- Après installation du service worker et activation, Cache Storage ne contient aucun bloc admin. Sans la correction de `ngsw-config.json`, il les contient tous : le test est discriminant.
- Après une connexion ADMIN et l'ouverture de `/admin`, au moins un bloc admin est chargé.
- Le test s'exécute sous Chromium et WebKit ; il inspecte Cache Storage et le réseau, non l'émulation hors ligne concernée par R5-3.

**CA7 — Scan hors ligne préservé (RG5) [E2E]** — agent : `test-e2e-frontend`
Après une visite en ligne de `/scan`, réseau coupé : `/scan` s'ouvre et une capture est mise en file. C'est la reprise de CA39 (inc. 4), évoluée comme en section 4.
- **WebKit** : vert.
- **Chromium** : CA39 est en échec connu depuis l'inc. 4 (LIM-E2E-1, réserves R4-1 et R5-3, avant déploiement). Le critère de l'inc. 6 est l'**absence de régression** : le testeur joue d'abord le test sur le commit de départ de l'inc. 6, consigne l'échec (étape et message), puis constate **la même étape et le même message** après les changements. Tout autre échec, ou un échec plus tôt dans le parcours, est un écart.
- La partie « `/scan` hors ligne » ne dépend pas des blocs admin : elle reste vérifiée avant l'étape `/admin`.

**CA8 — Identifiants ADMIN refusés pendant une action (CL4) [E2E]** — agent : `test-e2e-frontend`
Connecté ADMIN sur `/admin/courses/{id}`, la prochaine réponse E13 est interceptée et remplacée par un 401. L'écran de connexion s'affiche avec « Session expirée… » (RG10 inc. 4), et non « Page introuvable ».

**CA9 — Non-régression et tests évolués (RG1, CL5, CL6) [IT + E2E]** — agents : `testeur`, `test-e2e-frontend`
- `mvn -B -f backend/pom.xml clean verify` est vert. **Aucun test backend existant n'est modifié, supprimé, désactivé ni assoupli** (vérifié par `git diff` contre le commit de départ) ; seuls des tests sont ajoutés (CA1, CA12, CA13, CA14).
- La suite E2E est verte, à l'exception de CA39 sous Chromium (CA7, état de référence).
- Les seuls tests E2E modifiés sont ceux listés en section 4 (ca21, ca24, ca25, ca39), chacun avec son motif, et les assertions de sécurité ne sont pas affaiblies : l'absence de requête `/api/admin/**` est conservée.
- CL5 : vérification manuelle de la mise à jour (RG46 inc. 4), consignée dans le rapport.

**CA10 — Identifiants coureur sur l'écran de connexion staff (RG6, CL7) [E2E]** *(évolution motivée, PO15 inc. 5 : 401 au lieu de 403 ; révision 4b : volet front-unit retiré)* — agent : `test-e2e-frontend`
- [E2E] Connecté `Lievre-{run}`, on suit « Scan » depuis l'accueil, puis « Se connecter », et on saisit `Lievre-{run}` et son mot de passe. Alors :
  - E19 répond 401 (journal réseau) et « Identifiants invalides » s'affiche ;
  - `/scan` redemande une connexion ;
  - `/compte` affiche toujours les inscriptions sans nouvelle connexion.
- [E2E, même test] Après ce refus, le contenu de `localStorage`, `sessionStorage`, IndexedDB et Cache Storage (utilitaire de vidage de CA24 inc. 4) ne contient ni le mot de passe de `Lievre-{run}` ni son encodage Basic dans l'emplacement ADMIN/SCANNER ; les identifiants coureur restent dans leur emplacement propre, inchangés (RG21 inc. 5). Que rien ne soit conservé en mémoire est prouvé par le comportement : `/scan` redemande une connexion.
- **Décision (révision 4b)** : le volet front-unit est abandonné. La logique est dans `SessionService.login` (couche `infra`, hors du périmètre Vitest, qui ne couvre que `core/**` sans TestBed) ; extraire une décision dans `core` uniquement pour la tester imposerait un changement de production sans gain de comportement, la règle (seule une réponse SUCCESS écrit les identifiants) étant déjà vérifiée de bout en bout. La classification d'un 401 en `AUTH` reste couverte par `http-classification.spec.ts`.

**CA11 — Connexion ADMIN depuis `/scan` (RG3, RG7, CL8) [E2E]** — agent : `test-e2e-frontend`
- On suit « Scan » depuis l'accueil, puis « Se connecter ». L'écran de connexion affiché ne contient ni « Administration » ni « administrateur », et aucun lien vers `/admin`, en-tête compris.
- On s'y connecte avec `admin-test` / `admin-secret`. Alors on revient sur `/scan`, la page ne contient ni « Administration » ni lien vers `/admin`, et une capture reçoit 200.
- `/admin` ouverte ensuite liste les courses. *(Révision 5, COH6-4 : « par son URL » s'entend comme une navigation du routeur vers l'adresse `/admin` (historique, `pushState` + `popstate`), sans rechargement, car un rechargement complet perd les identifiants ADMIN conservés en mémoire seulement (CA5, CL9). Un `goto` complet de `/admin` après la connexion donnerait « Page introuvable » : ce n'est pas le comportement de ce critère. Même lecture pour CL8.)*

**CA12 — Volet HTTP de la migration sur base migrée depuis V1 (RG8, CL10) [IT]** *(réserve R5-4, CA31 inc. 5)* — agent : `test-integration-backend`
Donné une base H2 en mode PostgreSQL, dédiée, migrée par Flyway à la version 1 seulement (`target("1")`), avec :
- R1 `RUNNING`, démarrée le `2026-10-03T08:00:00Z`, et le coureur « Alice » (dossard 1, `qr_token = tok-alice`, `ACTIVE`, sans compte) avec 2 passages : yard 1 `SCAN` à `2026-10-03T08:50:00Z`, yard 2 `MANUAL` sans date ;
- R0 `FINISHED` et le coureur « Bob » (dossard 3, `DNF`, `TIMEOUT`, `dnf_yard = 4`).

Quand Flyway applique V2 sur cette base **déjà peuplée**, puis que le contexte Spring complet (`@SpringBootTest`) est démarré **sur cette même base**, sans aucune insertion SQL après la migration. Alors :
- `flyway_schema_history` contient les versions 1 et 2, toutes deux en succès, et V2 a été appliquée **avant** le démarrage du contexte (le test le vérifie avant de le démarrer) ;
- E4 de R1 donne `name = "Coureur n°1"` pour le dossard 1 ; E4 de R0 donne `name = "Coureur n°3"`, `status = DNF` ;
- E5 du coureur de dossard 1 donne `name = "Coureur n°1"` ;
- E13 de R1 (ADMIN) donne `name = "Coureur n°1"` et `pseudo = null` ;
- un DNF manuel de ce coureur (E17, ADMIN, raison `VOLUNTARY`) répond 200, `status = DNF`, `name = "Coureur n°1"` ;
- aucun des corps de réponse ci-dessus ne contient `Alice` ni `Bob` ;
- en base, `SELECT count(*) FROM runner WHERE name IS NOT NULL` vaut 0 ; le `qr_token`, le dossard et les 2 passages d'Alice sont inchangés.
Ce test s'ajoute à `V2MigrationIT` (qui prouve la purge en base) et à `AccountContractIT#ca31_…` (qui joue le volet HTTP sur base déjà en V2) ; ces deux tests restent inchangés. En cas de retrait de la ligne SQL nommée, le test ne passe pas à vide : l'assertion « aucun `Alice` » n'a de sens que parce que le nom était présent avant V2.

**CA13 — Normalisation du pseudo en un seul endroit, figée par un test de lecture de sources (RG9) [unit]** *(réserve R5-5, CA2 inc. 5)* — agent : `testeur`
Un test unitaire (surefire, `*Test`, sans Spring ni base) lit tous les fichiers `.java` de `backend/src/main/java` et les fichiers `.sql` et de configuration de `backend/src/main/resources`, **texte brut, commentaires compris**. Alors :
- `toLowerCase` apparaît **exactement 1 fois**, dans `fr/backyard/domain/Pseudo.java` ;
- aucune occurrence (insensible à la casse) de `lower(`, `upper(`, `IgnoreCase` ni `ILIKE` ;
- la fonction de lecture est elle-même testée sur des sources **synthétiques** : (a) deux occurrences de `toLowerCase` dans deux fichiers donnent un échec ; (b) une occurrence dans un fichier autre que `Pseudo.java` donne un échec ; (c) un `findByPseudoIgnoreCase` donne un échec ; (d) un `LOWER(pseudo)` dans une requête donne un échec ; (e) les sources de référence (une seule occurrence dans `Pseudo.java`) passent. Le test est ainsi démontré discriminant sans toucher au code de production.
- Le test échoue si le répertoire source n'est pas trouvé ou si aucun fichier n'est lu (pas de passage à vide).

**CA14 — Aucun pseudo dans les journaux d'erreur interne (RG10, CL11) [unit]** *(réserve R5-8, RG16 inc. 5)* — agents : `testeur` (test), `developpeur` (correctif)
Le gestionnaire `ApiExceptionHandler` est appelé directement (sans Spring ni base), avec une requête simulée `GET /api/public/runners/1` et la capture des journaux (appender en mémoire). Pour chacun des cas :
- (a) `handleInconsistency(new IllegalStateException("Pseudo lievre incohérent"))` ;
- (b) `handleInconsistency(new IllegalArgumentException("lievre", new RuntimeException("cause LIEVRE")))` ;
- (c) `handleUnexpected(new RuntimeException("Erreur sur lievre"))`.

Alors :
- aucune ligne capturée (message formaté, arguments, message du `throwable`, message de sa cause, trace rendue) ne contient `lievre`, dans aucune casse ;
- exactement une ligne de niveau ERROR est écrite, elle contient `GET`, `/api/public/runners/1` et le nom de la classe de l'exception (`IllegalStateException`, `IllegalArgumentException`, `RuntimeException`) ;
- le statut de la réponse est 500, avec le code `INTERNAL_INCONSISTENCY` pour (a) et (b) et `INTERNAL_ERROR` pour (c).
Avant le correctif, (a), (b) et (c) échouent sur la première assertion : le testeur le consigne en rejouant le test sur le commit de départ (comme pour le CA20 de l'inc. 5).

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
| RG8 | CA12 |
| RG9 | CA13 |
| RG10 | CA14 |
| CL1 | CA2 |
| CL2 | CA3 |
| CL3 | CA4 |
| CL4 | CA8 |
| CL5 | CA9 |
| CL6 | CA9 |
| CL7 | CA10 |
| CL8 | CA11 |
| CL9 | CA5 |
| CL10 | CA12 |
| CL11 | CA14 |

### Levée des réserves de l'inc. 5
Quand CA12, CA13 et CA14 sont verts et consignés dans les rapports de l'inc. 6, `PATRIMOINE.md` passe R5-4, R5-5 et R5-8 à « levée ». Si l'une manque au moment du verdict de l'inc. 6, c'est un écart (règle 1 du workflow) : elle ne peut pas être reportée sans motif écrit et accord de l'agent fonctionnel.

---

## 7. Points ouverts

### Points tranchés

**PO1 — Séparation par routes front, par rôle côté API, ou les deux. TRANCHÉ (utilisateur, 2026-09-28) : les deux (option C), sans masquage.** Le front masque l'administration ; l'API reste la barrière avec les rôles existants ; aucun endpoint ne change. Règles : RG1 à RG5.

**PO2 — Ce que voit un non-admin sur `/admin/**`. TRANCHÉ (utilisateur, 2026-09-28) : « Page introuvable »**, pour l'anonyme, le coureur et le scanneur. Le message « Accès réservé à l'administrateur » n'est pas conservé pour le SCANNER. Règle : RG3.

**PO3 — Masquage de l'API admin. TRANCHÉ (utilisateur, 2026-09-28) : pas de masquage** ; les codes 401 et 403 sont conservés. Règle : RG1.

**PO4 — Accès de l'admin à la connexion. TRANCHÉ (utilisateur, 2026-09-28) : URL `/connexion` connue, sans lien public.** Règles : RG2, RG3.

**PO5 — Séparation plus forte. TRANCHÉ (utilisateur, 2026-09-28) : aucune** (même origine, pas de restriction IP ni de VPN). Voir « Limites assumées », section 1.

**PO6 — Le scan fait-il partie de l'espace masqué ? TRANCHÉ (utilisateur, 2026-09-28) : non.** `/scan` reste visible et le lien « Scan » de l'accueil est conservé (révision 4 : et celui de l'en-tête) ; seule l'administration est masquée. Cette décision remplace l'hypothèse H2 de la révision 1 (« aucun lien public vers `/scan` ») : RG2, CA2 et CA4 sont corrigés.

**PO7 — Écran de connexion atteint depuis le lien « Scan ». TRANCHÉ (utilisateur, 2026-09-28) : option a, accepté (H4 confirmée).**
- L'écran de connexion atteint depuis `/scan` ne mentionne jamais l'administration.
- Un ADMIN peut quand même s'y connecter, et il revient sur `/scan`.
- Le lien « Se connecter » de `/scan` est la seule exception admise à l'interdiction de lier `/connexion` depuis le public.
Options écartées : b (retirer le lien « Scan ») et c (réserver cette connexion au rôle SCANNER). Règles : RG2, RG7. Critères : CA2, CA11.

**PO15 (inc. 5) — Référentiel d'authentification. TRANCHÉ (utilisateur, 2026-09-28) : référentiel choisi par le préfixe d'URL.** Décision appliquée ici :
- à la matrice de la section 4, dont les cases croisées passent en 401 ;
- à RG6, où le 403 et son message sont retirés ;
- à CA1 et CA10 (évolutions motivées).

**Réserves R5-4, R5-5, R5-8 (verdict de l'inc. 5, 2026-09-30)** : décidées par l'agent fonctionnel dans ce verdict, sans nouvelle décision utilisateur. Intégrées en RG8 à RG10 et CA12 à CA14 ; agents responsables en section 6.

Aucun point ouvert ne subsiste pour cet incrément.
