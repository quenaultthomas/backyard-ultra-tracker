# Spec Incrément 7 — Écran de connexion unique et inscription autonome

> Projet : Backyard Ultra Tracker
> Date de rédaction : 2026-10-01
> **Statut : validée par l'utilisateur sur D1, D2-bis et D7 ; les autres décisions (D2, D3, D4, D5, D6, D8, D9, D10, D11) sont retenues par défaut, à confirmer par l'utilisateur** (section 8). Tant que ces points ne sont pas confirmés, une contradiction de l'utilisateur sur l'un d'eux réécrit les règles marquées « [défaut] ».
> **Historique de numérotation** : ce document était le brouillon « incrément 8 » (`increment8-connexion-inscription.md`). Sur décision de l'utilisateur (D7, 2026-10-01), il devient l'**incrément 7**, exécuté **avant** les comptes scanneurs, qui deviennent l'**incrément 8** (`docs/specs/increment8.md`, anciennement `increment7.md`).
> **Dépendance** : l'incrément 6 (GO sous réserves, MR en cours) doit être mergé avant implémentation (règle 6 du workflow). Cette spec amende RG2 de l'inc. 6 (RG4 ci-dessous).
> Numérotation propre à l'incrément. Références externes : « RG2 (inc. 6) », « RG21 (inc. 5) », « RG15 (inc. 8) ».
> **Endpoint créé : E26** (`POST /api/public/accounts`). Les endpoints des comptes scanneurs (inc. 8) deviennent E27 à E31 (ancien E26 à E30, décalés d'un rang).

**Besoin exprimé (propriétaire du produit, 2026-10-01).** « Rajouter à un incrément à venir un écran de connexion préalable pour discriminer les coureurs, des bénévoles et des admins. Prévoir également un écran d'inscription au système. » Constat à l'origine : en validant l'inc. 6, on ne peut pas créer de compte, seulement se connecter ; la création n'existe qu'à l'inscription à une course (RG7 inc. 5).

---

## 0. Cadrage et existant

### Ce qui existe (relu sur le code : `SecurityConfig`, `app.routes.ts`, `app.ts`, `login-page.ts`)
| Sujet | Existant |
|---|---|
| Deux écrans de connexion distincts | `/connexion` (staff : ADMIN et SCANNER, validé par E19 `/api/scan/me`, emplacement d'identifiants « staff ») et `/compte/connexion` (coureur, validé par E21 `/api/account/me`, emplacement « coureur »). |
| Discrimination du profil | **Aucune par le compte** : c'est l'**écran choisi** (donc le préfixe d'URL appelé) qui décide du référentiel (PO15 inc. 5). Pas d'endpoint qui dise « quel type de compte est-ce ». Après connexion staff, E19 renvoie `role` (ADMIN ou SCANNER) et le front dirige vers `/admin` ou `/scan`. |
| Création de compte | Uniquement par E3 `POST /api/public/races/{raceId}/registrations` (RG7 inc. 5). Les comptes sans coureur existent déjà (CL21 inc. 5 : listés par E25). |
| Liens publics | En-tête : « Courses », « Mes inscriptions » (`/compte`), « Scan ». Aucun lien vers `/connexion` hors bouton « Se connecter » de `/scan` (RG2 inc. 6). |
| Comptes scanneurs | **Inc. 8** (validée, non implémentée, exécutée après celle-ci) : créés par l'admin, pas d'auto-inscription. |

### Le besoin, en deux volets
- **(a) Écran de connexion unique d'entrée** qui oriente vers le bon parcours : coureur → `/compte`, bénévole → `/scan`, admin → `/admin`.
- **(b) Écran d'inscription autonome** : créer un compte **coureur** (pseudo + mot de passe) sans choisir de course.

---

## 1. Analyse d'impact et conflits

### 1.1 Conflits avec l'inc. 6 (séparation admin / public)
| # | Règle inc. 6 | Conflit | Traitement |
|---|---|---|---|
| C1 | **RG2** : aucun lien public vers `/connexion` hors bouton de `/scan` ; mot « Administration » interdit. | Un écran d'entrée doit être atteignable depuis le public. | Amendement tracé (RG4, D2 retenue par défaut) : lien `/connexion` autorisé ; liens `/admin/**` et mot « Administration » restent interdits. |
| C2 | **RG7** : l'écran de connexion ne mentionne jamais l'administration. | « Discriminer les admins ». | **D1 = option A (tranchée)** : pas de choix « Administrateur » ; l'admin passe par « Bénévole » (E19 accepte déjà ADMIN et SCANNER, PO7 inc. 6). RG7 conservée. |
| C3 | **RG3 / PO2** : `/admin/**` = « Page introuvable » pour tout non-ADMIN ; **PO4** : URL `/connexion` connue de l'admin. | Aucun avec l'option A. | Inchangé. |
| C4 | **RG6 / CL7** : identifiants coureur sur l'écran staff = 401, rien conservé. | Compatible. | Inchangé (CL1 ci-dessous). |
| C5 | Tests E2E de l'inc. 6 (`ca2`, `ca10`, `ca11`) qui vérifient l'absence de lien vers `/connexion`. | Évolution. | Motif écrit et accord de l'agent fonctionnel (règle 2), voir CA19. |
| C6 | **RG5** (blocs admin non préchargés). | Aucun. | Aucun import de code admin dans les nouveaux écrans. |
| C7 | **CA1 inc. 6** (matrice 25 × 4). | Un 26e endpoint public. | Table additionnelle de l'inc. 7 (CA5) ; le test de l'inc. 6 reste inchangé. |

### 1.2 Conflits avec l'inc. 5
| # | Règle inc. 5 | Conflit |
|---|---|---|
| C8 | **RG9 / PO15** : référentiel par préfixe d'URL, HTTP Basic sans état, aucun endpoint de connexion. | Écarte tout champ unique sans choix de profil (option C du brouillon). Respectée : le choix de l'entrée décide du référentiel. |
| C9 | **CL10 inc. 5** : un nom peut exister dans les deux référentiels, avec le même mot de passe. | Respectée : aucune priorité arbitraire, chaque entrée désigne son référentiel. |
| C10 | **RG21 inc. 5** : deux emplacements d'identifiants distincts. | Respectée : l'écran écrit dans l'emplacement du profil choisi. |
| C11 | **RG7 / PO6 inc. 5** : compte créé avec une inscription à une course ; inscription sans compte exclue. | E26 ajoute une création de compte sans course. L'inscription sans compte reste exclue. L'hypothèse « compte ⇒ au moins une inscription » tombe ; les comptes vides sont déjà gérés (CL21, E25, `runnerCount` = 0). |
| C12 | **RG23 inc. 5** : limitation nginx ; BCrypt coût 12. | E26 hache à chaque appel : limite nginx obligatoire (RG6). |

### 1.3 Interaction avec l'inc. 8 (scanneurs nominatifs, exécutée après)
- Pas d'auto-inscription de bénévoles : l'écran d'inscription ne crée que des comptes coureurs (RG1 inc. 5, rôle RUNNER seulement).
- Homonymie (PO9 inc. 8) : E26 ne consulte pas `ScannerAccount` ; aucun 409 ne révèle un identifiant scanneur. Les tests d'homonymie (CA15 inc. 8) sont portés par l'inc. 8.
- La connexion d'un scanneur déclaré passera par l'entrée « Bénévole » de l'écran de cet incrément, sans changement d'écran (RG1).
- Les numéros d'endpoints de l'inc. 8 sont E27 à E31.

### 1.4 RGPD et minimisation
- Aucune nouvelle donnée : `pseudo` + hash (RG1 inc. 5). Aucun type de compte persisté.
- Avertissement de RG17 (inc. 5) repris : « N'utilisez pas votre nom réel… ».
- Aucun endpoint de résolution de profil (énumération des comptes staff, contraire à la minimisation et à PO3/PO4 inc. 6).
- Pas de purge des comptes inutilisés : aucune date de création n'est stockée [défaut D6].

---

## 2. Option retenue pour la connexion (D1 tranchée : option A)

Deux entrées visibles, « Coureur » et « Bénévole ». L'utilisateur choisit le profil ; l'entrée fixe le référentiel (E21 ou E19). L'admin se connecte par « Bénévole » et est dirigé vers `/admin` d'après le `role` de E19. Aucun changement d'API hors E26. Options écartées par l'utilisateur : B (trois choix dont « Administrateur », incompatible avec PO2, PO4 et RG7 de l'inc. 6), C (un champ unique, profil deviné : ambiguïté d'identifiants, énumération, double charge BCrypt), D (écrans séparés).

---

## 3. Ordre d'exécution (D7 tranchée)

Cet incrément est l'**incrément 7**, avant les comptes scanneurs (inc. 8). Conséquences : `CLAUDE.md` (liste des incréments, ligne ScannerAccount) et les specs 5, 6 et 8 sont mises à jour pour la renumérotation ; endpoints : E26 ici, E27 à E31 pour les scanneurs. L'inc. 6 reste GO sous réserves : il doit être mergé avant l'implémentation de cet incrément.

---

## 4. Périmètre

### Inclus
- Écran de connexion unique (route `/connexion`, inchangée), deux entrées (RG1).
- Écran d'inscription autonome `/inscription` et endpoint E26 [défaut D3].
- Liens publics « Se connecter » et « Créer un compte » dans l'en-tête [défaut D2].
- Orientation après connexion : coureur → `/compte`, bénévole → `/scan`, admin → `/admin`.
- Limitation nginx de E26 [défaut D5].
- Évolution motivée des tests E2E de l'inc. 6 concernés (CA19).

### Exclu
- Choix « Administrateur » visible ; endpoint de résolution de profil ; saisie sans choix de profil.
- Auto-inscription de bénévoles ou d'admins (comptes scanneurs : inc. 8, créés par l'admin) [défaut D11].
- Email, vérification d'adresse, mot de passe oublié automatisé (RG15 inc. 5), modification du pseudo.
- Purge automatique des comptes sans inscription [défaut D6].
- Barrière de connexion obligatoire (D2-bis tranchée : le suivi public et le lien d'inscription public restent ouverts).
- Toute modification des règles de course, du filet réseau, du modèle de données (aucune migration).
- Changement du mécanisme HTTP Basic sans état et du référentiel par préfixe d'URL.

---

## 5. Règles de gestion

**RG1 — Écran de connexion unique, deux entrées visibles** *(D1 tranchée, option A)*
- `/connexion` affiche « Connexion » et **deux entrées** : « Coureur » et « Bénévole ». L'entrée choisie fixe le référentiel : « Coureur » valide par E21 (`/api/account/me`), « Bénévole » par E19 (`/api/scan/me`). Aucune requête avant la soumission.
- L'écran **ne mentionne jamais** « Administration » ni « administrateur » et n'a aucun lien vers `/admin/**` (RG7 inc. 6 conservée).
- Le choix de l'entrée n'est ni stocké ni envoyé au serveur ; il détermine seulement l'endpoint de validation et l'emplacement d'identifiants écrit (RG21 inc. 5, RG7 inc. 4).
- Entrée par défaut : « Coureur », sauf retour demandé vers `/scan` (RG6 inc. 4) ou paramètre `profil=benevole`.

**RG2 — Orientation après connexion**
- « Coureur » réussie (E21 = 200) : retour demandé s'il est interne et sûr (contrôle de `LoginPage.destination`), sinon `/compte`.
- « Bénévole » réussie (E19 = 200) : retour demandé, sinon `/admin` si `role = ADMIN`, `/scan` si `role = SCANNER` (comportement actuel).
- 401 : « Identifiants invalides », rien conservé, aucune requête rejouée (RG6 inc. 6) ; aide neutre « Vérifiez le type de compte choisi », identique que l'identifiant existe ou non dans l'autre référentiel [défaut D1-bis].
- 429 : « Trop de tentatives. Réessayez dans une minute. » (RG23 inc. 5).
- Les deux emplacements d'identifiants restent indépendants (RG21 inc. 5).

**RG3 — Routes de connexion existantes** [défaut D4]
- `/compte/connexion` reste valide : écran unique, entrée « Coureur » préselectionnée (les parcours et tests de l'inc. 5 sont conservés).
- La garde de `/compte` renvoie vers l'écran unique, entrée « Coureur », avec retour vers `/compte`.
- Le bouton « Se connecter » de `/scan` mène à `/connexion`, retour vers `/scan`, entrée « Bénévole » préselectionnée.

**RG4 — Liens publics d'accès** [défaut D2 ; amende RG2 et CA2 de l'inc. 6]
- Sans connexion active, l'en-tête commun affiche « Se connecter » (vers `/connexion`) et « Créer un compte » (vers `/inscription`).
- Avec une connexion coureur ou bénévole active : « Connecté : {pseudo ou identifiant} » et « Se déconnecter ».
- Le mot « Administration » et tout lien vers `/admin/**` restent **interdits** dans l'en-tête et sur toutes les pages publiques.
- Amendement de RG2 (inc. 6) : l'interdiction de lier `/connexion` est levée ; l'interdiction de lier `/admin/**` est conservée.

**RG5 — Inscription autonome : E26** [défaut D3]
`POST /api/public/accounts`, corps `{"pseudo", "password"}`, anonyme. Ordre des contrôles :
1. format du pseudo et du mot de passe : 400 `VALIDATION_FAILED` (RG2 et RG3 inc. 5, normalisation par `Pseudo` seul, RG9 inc. 6) ;
2. pseudo normalisé déjà porté : 409 `BUSINESS_CONFLICT`, `detail` « Pseudo déjà utilisé : {pseudo}. Si c'est votre compte, connectez-vous. » (valeur normalisée) ;
3. création du compte seul : hash BCrypt coût 12 par l'encodeur unique ; aucun coureur, aucune course, aucun `qrToken`.
- Réponse 201 `{"pseudo": <valeur stockée, minuscules>}`. Ni mot de passe, ni hash, ni `accountId` (RG13 inc. 5).
- RG1 à RG5 de l'inc. 5 s'appliquent sans exception.
- Rôle à la connexion : `RUNNER` seulement. E26 ne consulte ni `ScannerAccount` ni les comptes de configuration.
- Journal : aucun pseudo ; une ligne INFO « compte créé {accountId} » est admise (RG16 inc. 5).
- Accès (RG10 inc. 5) : `/api/public/**` : anonyme, SCANNER et ADMIN autorisés ; compte pseudo présentant ses identifiants : 401 (inchangé).

**RG6 — Limitation de débit de E26** [défaut D5]
- nginx, `limit_req`, clé `$binary_remote_addr`, `limit_req_status 429`, versionné sous `deploy/nginx/` ; rien dans l'application.
- **Même zone « inscription » que E3** (10 requêtes par minute et par IP, rafale 5, `nodelay`, RG23 inc. 5).
- Évolution motivée de CA40 (inc. 5) : l'assertion « aucun autre `location` n'a de `limit_req` » admet le `location` exact de `POST /api/public/accounts`. L'inc. 8 ajoutera celui de E19 (RG15 inc. 8).
- PWA : un 429 affiche « Trop de tentatives. Réessayez dans une minute. », sans nouvel essai.

**RG7 — Écran d'inscription `/inscription`**
- Route publique, ajoutée à la liste fermée des routes (RG5 inc. 4) et aux chemins front servis par Spring Boot (RG53 inc. 4). À ne pas confondre avec `/inscription/{raceId}` (inchangée).
- Champs « Pseudo », « Mot de passe », « Confirmer le mot de passe », case « Rester connecté 24 h sur cet appareil » (décochée par défaut, RG21 inc. 5). Validation miroir (RG2, RG3 inc. 5) ; mots de passe différents : aucune requête. Mention de RG17 inc. 5.
- Aide : « Bénévole : votre compte est créé par l'organisateur. » (aucune mention de l'administration) [défaut D10, D11].
- Succès (201) : connexion automatique en coureur par E21 (mêmes identifiants, un seul appel), puis `/compte` (« Aucune inscription pour le moment » et lien « Voir les courses ») ou l'écran demandé (`retour`, par exemple `/inscription/{raceId}` où « M'inscrire à cette course » envoie E20) [défaut D8].
- 409 : le `detail` est affiché avec le lien « J'ai déjà un compte » vers `/connexion` (entrée « Coureur »). 400 : message de champ. 429 : message de RG6.
- Aucune requête rejouée. Aucun identifiant dans une URL, un journal ou un message (RG21 inc. 5).

**RG8 — Inscription à une course inchangée**
- `/inscription/{raceId}` (E3, E20), le lien « J'ai déjà un compte » et les confirmations restent identiques (RG7, RG8, RG17 inc. 5). Le lien « J'ai déjà un compte » mène à l'écran unique, entrée « Coureur ».
- Un compte créé par E26 s'inscrit à une course par E20, jamais par E3 (409, CL13 inc. 5).

**RG9 — Point d'entrée, pas de barrière** *(D2-bis tranchée)*
- Accueil, tableau de bord, détail coureur et inscription à une course restent accessibles **sans connexion**.
- Les routes qui exigent un profil renvoient vers l'écran unique avec `retour` : `/compte` (coureur) ; `/scan` garde son fonctionnement actuel (capture sans connexion, envoi suspendu, RG14 inc. 4).

**RG10 — Non-régression de la sécurité**
- L'API ne change pas hors E26 : matrice E1 à E25 de l'inc. 6 inchangée ; HTTP Basic sans état ; référentiel par préfixe d'URL.
- `/admin/**` pour un non-ADMIN : « Page introuvable » (RG3 inc. 6).
- Aucun bloc admin dans l'écran de connexion ni d'inscription (RG5 inc. 6).

---

## 6. Cas limites

**CL1 — Coureur qui choisit « Bénévole ».** E19 = 401 : « Identifiants invalides », rien conservé ; identifiants coureur éventuels intacts (RG6 inc. 6).
**CL2 — Bénévole ou admin qui choisit « Coureur ».** E21 = 401, même message. Pas d'essai croisé.
**CL3 — Identifiant présent dans les deux référentiels, même mot de passe** (CL10 inc. 5). Chaque entrée désigne le compte de son référentiel.
**CL4 — Admin.** Entrée « Bénévole », arrivée sur `/admin` (ou sur le retour demandé). Ni l'écran ni l'en-tête ne contiennent « Administration ».
**CL5 — Pseudo existant ou à la casse près** (« Lievre » alors que `lievre` existe) : 409 (normalisation avant contrôle). Pseudo égal à un nom ADMIN ou SCANNER de configuration : accepté, sans droit (RG9, CL10 inc. 5).
**CL6 — Créations simultanées du même pseudo.** Une seule réussit (201), l'autre 409 ; aucun compte orphelin supplémentaire.
**CL7 — Compte sans inscription.** `/compte` affiche une liste vide ; E25 le liste avec `runnerCount = 0` ; l'admin peut le réinitialiser ou le supprimer.
**CL8 — Déjà connecté en coureur, ouvre `/inscription`.** « Vous êtes connecté en tant que {pseudo} » et lien vers `/compte`, sans formulaire [défaut D9].
**CL9 — Rafale de créations depuis une même IP** (Wi-Fi du site). Au-delà du seuil, 429 ; la zone est partagée avec E3 (conséquence assumée).
**CL10 — Réseau coupé pendant la création.** Erreur « serveur injoignable », aucun rejeu ; si la requête avait abouti, un nouvel essai donne 409 avec le lien de connexion.
**CL11 — Ancienne PWA en cache.** Les anciens écrans fonctionnent comme avant ; E26 n'est pas appelé (RG46 inc. 4).
**CL12 — Sans objet.** Bascule de yard, passage manuel ou scan, coureur réintégré, plusieurs courses, course non démarrée, coureur sans passage : aucune règle de course modifiée (CA19).

---

## 7. Critères d'acceptation

Comptes de test : ADMIN `admin-test` / `admin-secret`, SCANNER `scanner-test` / `scanner-secret`, compte pseudo `Lievre` / `motdepasse-1`, nouveau compte `nouveau-{run}` / `motdepasse-9`.

**CA1 — E26 crée un compte seul (RG5) [slice + IT]**
Donné aucun compte `nouveau-1`. `POST /api/public/accounts {"pseudo":"  Nouveau-1 ","password":"motdepasse-9"}` (anonyme) : 201, corps `{"pseudo":"nouveau-1"}` sans autre propriété ; en base : 1 ligne `account` (pseudo `nouveau-1`, hash BCrypt coût 12 vérifiant `motdepasse-9`), 0 `runner` ; le corps ne contient ni `password`, ni hash, ni `accountId`.

**CA2 — Validations de E26 (RG5) [slice]**
Pseudo `ab` → 400 sur `pseudo` ; `a b c` → 400 ; mot de passe `court12` (7 caractères) → 400 sur le mot de passe ; mot de passe de 73 octets → 400 ; corps vide → 400 ; le service n'est appelé dans aucun cas.

**CA3 — Pseudo déjà pris (RG5, CL5) [IT]**
Donné `lievre`. `POST` avec `Lievre`, puis `LIEVRE`, puis `lievre` : 409 `BUSINESS_CONFLICT` chaque fois ; le `detail` contient `lievre` ; `count(account WHERE pseudo='lievre')` = 1. Avec le mot de passe `motdepasse-1` de `Lievre` : 409 aussi (E26 ne connecte jamais).

**CA4 — Créations simultanées (CL6) [IT]**
10 appels concurrents avec le même pseudo : un seul 201, neuf 409, un seul compte en base.

**CA5 — Matrice d'accès de E26 (RG5, RG10) [slice]**
Anonyme, SCANNER, ADMIN : 201 ; `Lievre` présentant ses identifiants : 401 « Identifiants invalides » sans `WWW-Authenticate` ; `admin-test:mauvais` : 401. Test de table additionnel (E26 × 4 profils) ; CA1 de l'inc. 6 (25 × 4 = 100 cas) reste vert sans modification.

**CA6 — Compte créé : RUNNER seulement (RG5, CL3) [IT]**
Après CA1, `nouveau-1` / `motdepasse-9` : `GET /api/account/me` → 200, `pseudo = "nouveau-1"`, liste de coureurs vide ; `GET /api/scan/me` et `GET /api/admin/races` → 401.

**CA7 — Inscription d'un compte vide à une course (RG8, CL7) [IT]**
`nouveau-1` : `POST /api/account/races/{raceId}/registrations` sur une course `SETUP` → 201 avec dossard et `qrToken` ; `POST /api/public/races/{raceId}/registrations` avec `nouveau-1` → 409.

**CA8 — Écran unique : deux entrées, aucune mention de l'administration (RG1) [E2E]**
Sur `/connexion`, contexte neuf : « Coureur » et « Bénévole » visibles ; aucun texte « Administration » ni « administrateur » ; aucun `a[href]` vers `/admin` ; aucune requête avant la soumission.

**CA9 — Orientation par profil (RG2, CL4) [E2E]**
(a) « Coureur » + `Lievre-{run}` : E21 = 200, arrivée sur `/compte`. (b) « Bénévole » + `scanner-test` : E19 = 200, arrivée sur `/scan`. (c) « Bénévole » + `admin-test` : E19 = 200, arrivée sur `/admin` qui liste les courses. (d) Avec `retour=/scan`, l'ADMIN revient sur `/scan` (CA11 inc. 6 inchangé).

**CA10 — Mauvaise entrée (CL1, CL2, CL3) [E2E]**
(a) `Lievre-{run}` sur « Bénévole » : E19 = 401, « Identifiants invalides », rien dans l'emplacement bénévole (stockages vidés), `/scan` redemande une connexion. (b) `scanner-test` sur « Coureur » : E21 = 401, même message ; `localStorage`, `sessionStorage`, IndexedDB et Cache Storage sans mot de passe ni Basic. (c) Message identique pour un identifiant inconnu et pour un identifiant existant dans l'autre référentiel.

**CA11 — Indépendance des emplacements (RG2) [E2E]**
Connexion coureur puis bénévole dans le même contexte : `/compte` affiche toujours les inscriptions de `Lievre-{run}`, `/scan` envoie avec le Basic bénévole ; la déconnexion coureur n'efface pas la connexion bénévole.

**CA12 — Écran d'inscription autonome (RG7, CL8) [E2E]**
(a) `/inscription` : champs « Pseudo », « Mot de passe », « Confirmer le mot de passe » ; mots de passe différents : aucune requête. (b) Soumission valide `nouveau-{run}` : une seule requête E26 (201) puis une seule E21 (200) ; arrivée sur `/compte` avec « Aucune inscription pour le moment ». (c) Pseudo pris : 409, `detail` affiché avec « J'ai déjà un compte » vers `/connexion` ; aucun mot de passe dans l'URL ni dans les stockages (« Rester connecté » décochée). (d) Réponse E26 remplacée par un 429 à corps HTML : « Trop de tentatives. Réessayez dans une minute. », une seule requête. (e) Connecté en coureur, `/inscription` n'affiche pas de formulaire de création.

**CA13 — Enchaînement avec une course (RG7, RG8) [E2E]**
Depuis `/inscription/{raceId}` (course `SETUP`), l'anonyme suit « Créer un compte » (retour vers cette page), crée `nouveau-{run}`, revient sur la page de la course connecté, clique « M'inscrire à cette course » : une requête E20 (201), le QR s'affiche et `/compte` le liste.

**CA14 — Liens publics (RG4, RG9) [E2E, évolution de CA2 inc. 6]**
Anonyme, sur `/`, `/courses/{id}`, `/coureurs/{id}`, `/inscription/{id}`, `/compte/connexion`, `/inscription`, `/scan` : l'en-tête contient « Se connecter » (cible `/connexion`) et « Créer un compte » (cible `/inscription`) ; aucun `a[href]` vers `/admin` ; le texte visible ne contient pas « Administration ». Connecté en coureur : ces deux liens disparaissent, « Se déconnecter » apparaît. L'accueil et le tableau de bord s'ouvrent sans connexion.

**CA15 — `/admin` toujours introuvable (RG10) [E2E]**
Anonyme, coureur, SCANNER : `/admin` et `/admin/comptes` affichent « Page introuvable », sans requête `/api/admin/**` (CA3 et CA4 inc. 6 inchangés).

**CA16 — Routes conservées (RG3) [E2E]**
`/compte/connexion` ouvre l'écran unique, entrée « Coureur » sélectionnée ; `/compte` sans connexion renvoie vers l'écran de connexion avec retour vers `/compte` ; après connexion, retour sur `/compte`.

**CA17 — Limitation nginx de E26 (RG6) [config + manuel]**
[config] La configuration nginx versionnée applique `limit_req` (zone d'inscription, `rate=10r/m`, `burst=5 nodelay`, 429) à `POST /api/public/accounts` ; aucun autre `location` n'est limité, hors ceux déjà admis (E3, `/api/account/**`). [manuel] Depuis une même IP après 2 minutes sans requête : 6 requêtes E26 simultanées passent (201 ou 409), la 7e reçoit 429.

**CA18 — Journaux et minimisation (RG5) [unit, capture des journaux]**
Création, conflit (409) et échec de validation de E26 : aucune ligne de journal, à aucun niveau, ne contient `nouveau-1`, `motdepasse-9` ni `$2`.

**CA19 — Non-régression (RG10, CL12) [IT + E2E]**
`mvn -B -f backend/pom.xml clean verify` vert ; suite E2E verte à l'exception de CA39 sous Chromium (état de référence R5-3). Aucun test backend existant modifié. Tests E2E à faire évoluer, chacun avec motif et accord de l'agent fonctionnel (règle 2) : `ca2`, `ca10`, `ca11` de l'inc. 6 (lien « Se connecter » et « Créer un compte » dans l'en-tête, pas de lien `/admin`), tests de l'inc. 5 qui pointent `/compte/connexion` si le rendu change. Les assertions de sécurité (aucune requête `/api/admin/**` pour un non-ADMIN, aucun mot de passe stocké) ne sont pas affaiblies.

**CA20 — Aucun bloc admin sur `/connexion` et `/inscription` (RG10) [E2E, reprise de CA6 inc. 6]**
Aucun bloc `admin-*.js` n'est chargé ni mis en cache par la visite de ces deux écrans.

### Couverture
| RG / CL | CA |
|---|---|
| RG1 | CA8, CA10 |
| RG2 | CA9, CA10, CA11 |
| RG3 | CA16 |
| RG4 | CA14 |
| RG5 | CA1 à CA6, CA18 |
| RG6 | CA12 (d), CA17 |
| RG7 | CA12, CA13 |
| RG8 | CA7, CA13 |
| RG9 | CA14 |
| RG10 | CA5, CA15, CA19, CA20 |
| CL1, CL2, CL3 | CA10 |
| CL4 | CA9 |
| CL5 | CA3 |
| CL6 | CA4 |
| CL7 | CA7 |
| CL8 | CA12 (e) |
| CL9, CL10 | CA12 (d), CA17 (CL10 : essai manuel consigné) |
| CL11, CL12 | CA19 |

---

## 8. Décisions

### Tranchées par l'utilisateur (2026-10-01)
- **D1 — option A** : deux entrées visibles, « Coureur » et « Bénévole » ; l'admin passe par « Bénévole » ; aucun choix « Administrateur ». Règles : RG1, RG2.
- **D2-bis — point d'entrée proposé, pas une barrière** : le suivi public et le lien d'inscription public restent ouverts. Règle : RG9.
- **D7 — avant les comptes scanneurs** : cet incrément est l'inc. 7 ; les scanneurs deviennent l'inc. 8 (E27 à E31). Section 3.

### Retenues par défaut, à confirmer par l'utilisateur
- **D2** : lever l'interdiction de lier `/connexion` (RG2 inc. 6), conserver l'interdiction de lier `/admin` et d'écrire « Administration » (RG4).
- **D1-bis** : message d'échec « Identifiants invalides » plus l'aide neutre « Vérifiez le type de compte choisi » (RG2).
- **D3** : endpoint E26 pour un compte sans course (RG5).
- **D4** : `/compte/connexion` conservée comme alias de l'écran unique, « Coureur » préselectionnée (RG3).
- **D5** : E26 dans la zone nginx de E3, 10 par minute, rafale 5 (RG6).
- **D6** : pas de purge des comptes jamais utilisés (aucune date de création stockée).
- **D8** : connexion automatique en coureur après création (RG7).
- **D9** : utilisateur déjà connecté sur `/inscription` : message, sans formulaire (CL8).
- **D10** : libellé « Bénévole » seul, ni « organisateur » ni « administrateur ».
- **D11** : pas d'auto-création de comptes bénévoles (inc. 8 : créés par l'admin).

Aucun autre point ouvert. Tant que les décisions « par défaut » ne sont pas confirmées, l'incrément peut démarrer sur la base de ces valeurs ; une contradiction ultérieure de l'utilisateur ferait l'objet d'une révision de cette spec.
