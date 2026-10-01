# Spec Incrément 7 — Écran de connexion unique et inscription autonome

> Projet : Backyard Ultra Tracker
> Date de rédaction : 2026-10-01
> **Révision 2 (2026-10-01)** : relue contre le code de l'inc. 6 mergé (voir la liste des corrections en fin de section 0).
> **Statut : validée par l'utilisateur (toutes décisions confirmées, 2026-10-01)** : D1, D2-bis et D7 tranchées d'abord, puis D2, D1-bis, D3, D4, D5, D6, D8, D9, D10 et D11 confirmées telles que recommandées (section 9). Prête pour développement.
> **Historique de numérotation** : ce document était le brouillon « incrément 8 » (`increment8-connexion-inscription.md`). Sur décision de l'utilisateur (D7, 2026-10-01), il devient l'**incrément 7**, exécuté **avant** les comptes scanneurs, qui deviennent l'**incrément 8** (`docs/specs/increment8.md`, anciennement `increment7.md`).
> **Dépendance : levée.** L'incrément 6 (GO sous réserves, 2026-10-01) est mergé dans `main` ; la branche `feature/increment-7-connexion-inscription` en part. Cette spec amende RG2 et RG7 de l'inc. 6 (RG1 et RG4 ci-dessous) et RG3 de l'inc. 6 n'est pas modifiée.
> Numérotation propre à l'incrément. Références externes : « RG2 (inc. 6) », « RG21 (inc. 5) », « RG15 (inc. 8) ».
> **Endpoint créé : E26** (`POST /api/public/accounts`). Les endpoints des comptes scanneurs (inc. 8) sont E27 à E31 (vérifié dans `increment8.md` : aucun conflit de numérotation).

**Besoin exprimé (propriétaire du produit, 2026-10-01).** « Rajouter à un incrément à venir un écran de connexion préalable pour discriminer les coureurs, des bénévoles et des admins. Prévoir également un écran d'inscription au système. » Constat à l'origine : en validant l'inc. 6, on ne peut pas créer de compte, seulement se connecter ; la création n'existe qu'à l'inscription à une course (RG7 inc. 5).

---

## 0. Cadrage et existant

### Ce qui existe (relu sur le code livré de l'inc. 6 : `app.ts`, `app.routes.ts`, `login-page.ts`, `runner-login-page.ts`, `scan-page.ts`, `account-page.ts`, `registration-page.ts`, `session.service.ts`, `runner-session.service.ts`, `SecurityConfig`, `PwaPaths`, `ngsw-config.json`, `deploy/nginx/backyard-ultra-tracker.conf`)
| Sujet | Existant |
|---|---|
| Deux écrans de connexion distincts | `/connexion` (`pages/login/login-page.ts`, h1 « Connexion », champ « Nom d'utilisateur », case « Rester connecté 24 h sur cet appareil (compte scanner uniquement) », validé par E19 `/api/scan/me`, emplacement « staff ») et `/compte/connexion` (`pages/account/runner-login-page.ts`, h1 « Connexion coureur », champ « Pseudo », case « Rester connecté 24 h sur cet appareil », validé par E21 `/api/account/me`, emplacement « coureur »). Les deux affichent le `notice` de leur propre état (« Session expirée… »). |
| Discrimination du profil | **Aucune par le compte** : c'est l'**écran choisi** (donc le préfixe d'URL appelé) qui décide du référentiel (PO15 inc. 5). Après connexion staff, `LoginPage.destination` renvoie le `retour` s'il est interne et sûr (commence par `/`, pas par `//`), sinon `/admin` si `role = ADMIN`, `/scan` sinon. Après connexion coureur : `retour` sûr sinon `/compte`. |
| Gardes de routes (`app.routes.ts`) | `/admin` : `canMatch: [matchAdminOnly]` (rôle ADMIN de la session staff, après `sessionService.ready`) ; sinon la route `**` affiche « Page introuvable » sans redirection (RG3 inc. 6). `/compte` : `canActivate: [requireRunnerSignedIn]`, renvoi vers `RUNNER_LOGIN_ROUTE` (`/compte/connexion`) avec `retour`. 401 coureur : `RunnerSessionService.expire` renvoie vers `/compte/connexion` ; 401 staff : `SessionService.expire` renvoie vers `/connexion?retour=…` (CL4 inc. 6). |
| Création de compte | Uniquement par E3 `POST /api/public/races/{raceId}/registrations` (RG7 inc. 5), via `AccountService.create(pseudo, password)` (validation, normalisation par `Pseudo`, 409 `BusinessConflictException` « Pseudo déjà utilisé : {pseudo}. Si c'est votre compte, connectez-vous pour vous inscrire avec. »). Les comptes sans coureur existent déjà (CL21 inc. 5 : listés par E25). |
| Liens publics | En-tête (`app.ts`, `nav` « Navigation principale ») : « Courses », « Mes inscriptions » (`/compte`), « Scan ». Seul lien vers `/connexion` : le bouton « Se connecter » de `/scan` (bandeau « Non connecté : envoi suspendu », `retour=/scan`). Aucun contrôle « Se déconnecter » dans l'en-tête : il est sur `/scan` (`LogoutButton`, avec confirmation si des scans sont en attente), dans la coquille admin et sur `/compte`. L'en-tête ne connaît aucun état de connexion. |
| Lien « J'ai déjà un compte » | `registration-page.ts` : vers `/compte/connexion` avec `retour` (deux endroits). |
| Routes servies par Spring Boot (RG53 inc. 4) | `PwaPaths.FRONT_ROUTES` : `/`, `/courses/**`, `/coureurs/**`, `/inscription/**`, `/connexion`, `/compte`, `/compte/**`, `/scan`, `/admin`, `/admin/**`. `/inscription` seul n'est pas listé explicitement (couvert ou non par `/inscription/**` : à vérifier, CA21). |
| Service worker | `ngsw-config.json` : groupe `app` en `prefetch` sur `/*.js` **sauf** `!/admin-*.js` (groupe `admin` en `lazy`). Tout fichier de route non préfixé `admin-` est préchargé. |
| nginx | `limit_req_zone backyard_registration` (10r/m) et `backyard_account` (30r/m), `limit_req_status 429` ; `limit_req` sur le `location` regex de E3 (burst 5 nodelay) et sur `location /api/account/` (burst 10 nodelay) seulement. Aucun `location` pour `/api/public/accounts`. |
| Comptes scanneurs | **Inc. 8** (validée, non implémentée, exécutée après celle-ci) : créés par l'admin, pas d'auto-inscription. Endpoints E27 à E31. |

### Le besoin, en deux volets
- **(a) Écran de connexion unique d'entrée** qui oriente vers le bon parcours : coureur → `/compte`, bénévole → `/scan`, admin → `/admin`.
- **(b) Écran d'inscription autonome** : créer un compte **coureur** (pseudo + mot de passe) sans choisir de course.

### Corrections de la révision 2 (écarts entre la rédaction initiale et le code livré)
1. « Aucune inscription pour le moment » et lien « Voir les courses » (RG7, CA12) : le code affiche « Aucune inscription. » sans lien ; le texte existant est conservé, le lien n'est pas créé (l'en-tête propose déjà « Courses »).
2. Message 409 : le texte réel du service est « …connectez-vous pour vous inscrire avec. » ; E26 le réutilise tel quel (RG5), sans seconde règle.
3. En-tête : la rédaction initiale ajoutait « Se connecter » sur `/scan`, où le bouton du bandeau existe déjà ; 15 fichiers E2E cliquent `getByRole('link', { name: 'Se connecter' })` sur `/scan` et échoueraient en mode strict avec deux liens. L'en-tête n'ajoute pas de doublon (RG4). Idem pour « Se déconnecter » : pas de second contrôle dans l'en-tête (il perdrait la confirmation « scans en attente » de `LogoutButton`).
4. Entrée par défaut : `retour` sous `/admin` présélectionne « Bénévole » (sinon l'ADMIN dont la session expire, CL4 inc. 6, tombe sur « Coureur ») ; libellés de champs par entrée imposés (RG1), car 10 fichiers E2E remplissent « Pseudo » ou « Nom d'utilisateur ».
5. Tests concernés : la rédaction initiale annonçait « aucun test backend modifié » (CA19) ; c'est faux. `NoTestEndpointsIT` (E1 à E25 exactement) et `DeployConfigIT.ca40` (exactement 2 `limit_req`) évoluent obligatoirement. Les tests front-unit `admin-separation.spec.ts` évoluent aussi. Liste complète en section 8.
6. R6-1 (réserves de l'inc. 6) : voir RG11.

---

## 1. Analyse d'impact et conflits

### 1.1 Conflits avec l'inc. 6 (séparation admin / public)
| # | Règle inc. 6 | Conflit | Traitement |
|---|---|---|---|
| C1 | **RG2** : aucun lien public vers `/connexion` hors bouton de `/scan` ; mot « Administration » interdit. | Un écran d'entrée doit être atteignable depuis le public. | Amendement tracé (RG4, D2 tranchée) : liens `/connexion` et `/inscription` autorisés dans l'en-tête ; liens `/admin/**` et mot « Administration » restent interdits. |
| C2 | **RG7** : l'écran de connexion ne mentionne jamais l'administration. | « Discriminer les admins ». | **D1 = option A (tranchée)** : pas de choix « Administrateur » ; l'admin passe par « Bénévole » (E19 accepte déjà ADMIN et SCANNER, PO7 inc. 6). RG7 conservée. |
| C3 | **RG3 / PO2** : `/admin/**` = « Page introuvable » pour tout non-ADMIN (`canMatch`) ; **PO4** : URL `/connexion` connue de l'admin. | Aucun avec l'option A. | Inchangé. |
| C4 | **RG6 / CL7** : identifiants coureur sur l'écran staff = 401, rien conservé. | Compatible. | Inchangé (CL1 ci-dessous). |
| C5 | Tests E2E de l'inc. 6 (`inc6-ca2`, `inc6-ca10-ca11`) et son utilitaire `auditNoAdminLink` (option `scanLoginLinkAllowed`), qui interdisent tout lien vers `/connexion` hors `/scan`. | Évolution. | Motif écrit et accord de l'agent fonctionnel (règle 2), voir section 8 et CA19. |
| C6 | **RG5** (blocs admin non préchargés, motif `admin-*`). | Aucun, si les nouveaux écrans ne sont pas des fichiers `admin-*` et n'importent aucun code admin. | RG11. |
| C7 | **CA1 inc. 6** (matrice 25 × 4 = 100 cas, `AccessMatrixSliceTest`). | Un 26e endpoint public. | Table additionnelle de l'inc. 7 (CA5) ; le test de l'inc. 6 reste inchangé (il énumère E1 à E25, sans contrôle d'exhaustivité). |
| C13 | Tests front-unit `core/admin-separation.spec.ts` (inc. 6, CA2, CA3, CA11 partie unitaire) : en-tête sans lien `/connexion`, liste des pages publiques, absence de `'/admin'` dans le gabarit de `login-page.ts`, garde sans `['/connexion']`. | Évolution du premier ; vigilance sur les trois autres. | Section 8. |
| C14 | `NoTestEndpointsIT` (inc. 4, CA7 : E1 à E25 exactement, 25 méthodes, 20 motifs) et `DeployConfigIT.ca40` (inc. 5 : exactement 2 `limit_req`). | Un endpoint et un `limit_req` de plus. | Évolution obligatoire, plus stricte, section 8. |

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
- Les numéros d'endpoints de l'inc. 8 sont E27 à E31 ; `NoTestEndpointsIT` y repassera (31 méthodes) à l'inc. 8 ; la limite de E19 (RG15 inc. 8) y étend `DeployConfigIT.ca40`.

### 1.4 RGPD et minimisation
- Aucune nouvelle donnée : `pseudo` + hash (RG1 inc. 5). Aucun type de compte persisté.
- Avertissement de RG17 (inc. 5) repris : « N'utilisez pas votre nom réel… ».
- Aucun endpoint de résolution de profil (énumération des comptes staff, contraire à la minimisation et à PO3/PO4 inc. 6).
- L'en-tête n'affiche jamais d'identifiant ni de rôle staff (RG4) : aucune fuite de l'identifiant ADMIN.
- Pas de purge des comptes inutilisés : aucune date de création n'est stockée [D6].

---

## 2. Option retenue pour la connexion (D1 tranchée : option A)

Deux entrées visibles, « Coureur » et « Bénévole ». L'utilisateur choisit le profil ; l'entrée fixe le référentiel (E21 ou E19). L'admin se connecte par « Bénévole » et est dirigé vers `/admin` d'après le `role` de E19. Aucun changement d'API hors E26. Options écartées par l'utilisateur : B (trois choix dont « Administrateur », incompatible avec PO2, PO4 et RG7 de l'inc. 6), C (un champ unique, profil deviné : ambiguïté d'identifiants, énumération, double charge BCrypt), D (écrans séparés).

---

## 3. Ordre d'exécution (D7 tranchée)

Cet incrément est l'**incrément 7**, avant les comptes scanneurs (inc. 8). Conséquences : `CLAUDE.md` (liste des incréments, ligne ScannerAccount) et les specs 5, 6 et 8 sont mises à jour pour la renumérotation ; endpoints : E26 ici, E27 à E31 pour les scanneurs. L'inc. 6 est mergé (GO sous réserves) : aucune dépendance en attente.

---

## 4. Périmètre

### Inclus
- Écran de connexion unique (route `/connexion`, inchangée), deux entrées (RG1).
- Écran d'inscription autonome `/inscription` et endpoint E26 [D3].
- Liens publics « Se connecter » et « Créer un compte » dans l'en-tête, état « Connecté : {pseudo} » du coureur [D2].
- Orientation après connexion : coureur → `/compte`, bénévole → `/scan`, admin → `/admin`.
- Limitation nginx de E26 [D5].
- Évolution motivée des tests existants listés en section 8 (E2E, front-unit, backend), tous au moins aussi stricts qu'avant.

### Exclu
- Choix « Administrateur » visible ; endpoint de résolution de profil ; saisie sans choix de profil.
- Auto-inscription de bénévoles ou d'admins (comptes scanneurs : inc. 8, créés par l'admin) [D11].
- Email, vérification d'adresse, mot de passe oublié automatisé (RG15 inc. 5), modification du pseudo.
- Purge automatique des comptes sans inscription [D6].
- Barrière de connexion obligatoire (D2-bis tranchée : le suivi public et le lien d'inscription public restent ouverts).
- Contrôle « Se déconnecter » dans l'en-tête (il reste sur `/compte`, `/scan` et la coquille admin).
- Toute modification des règles de course, du filet réseau, du modèle de données (aucune migration), de `ngsw-config.json`.
- Changement du mécanisme HTTP Basic sans état et du référentiel par préfixe d'URL.

---

## 5. Règles de gestion

**RG1 — Écran de connexion unique, deux entrées visibles** *(D1 tranchée, option A)*
- `/connexion` affiche h1 « Connexion » (texte exact) et **deux entrées** : « Coureur » et « Bénévole » (un groupe de choix accessible, une seule entrée active). L'entrée choisie fixe le référentiel : « Coureur » valide par E21 (`/api/account/me`), « Bénévole » par E19 (`/api/scan/me`). Aucune requête avant la soumission.
- **Libellés de champs par entrée (figés, les tests s'y appuient)** : entrée « Coureur » : champ « Pseudo », case « Rester connecté 24 h sur cet appareil » ; entrée « Bénévole » : champ « Nom d'utilisateur », case « Rester connecté 24 h sur cet appareil (compte scanner uniquement) » (l'ADMIN n'est jamais mémorisé, RG7 inc. 4). Dans les deux cas : champ « Mot de passe » unique, bouton « Afficher », bouton de soumission « Se connecter ».
- L'écran **ne mentionne jamais** « Administration » ni « administrateur » et n'a aucun lien vers `/admin/**` (RG7 inc. 6 conservée). Le gabarit de l'écran ne contient pas la chaîne `/admin` (test front-unit de l'inc. 6) ; la présélection par `retour` (ci-dessous) est faite dans le code du composant, hors gabarit.
- Le choix de l'entrée n'est ni stocké ni envoyé au serveur ; il détermine seulement l'endpoint de validation et l'emplacement d'identifiants écrit (RG21 inc. 5, RG7 inc. 4).
- **Entrée présélectionnée** : « Bénévole » si `profil=benevole`, ou si `retour` est `/scan` ou commence par `/scan/`, ou est `/admin` ou commence par `/admin/` ; « Coureur » sinon (y compris sans paramètre). Un `profil` inconnu est ignoré. L'utilisateur peut changer d'entrée.
- Le `notice` d'expiration de session (« Session expirée… ») de chaque référentiel s'affiche sur l'écran ; le message du référentiel actif est affiché, celui de l'autre est conservé pour son entrée (RG21 inc. 5, RG10 inc. 4).

**RG2 — Orientation après connexion**
- « Coureur » réussie (E21 = 200) : retour demandé s'il est interne et sûr (même contrôle qu'aujourd'hui : commence par `/`, pas par `//`), sinon `/compte`.
- « Bénévole » réussie (E19 = 200) : retour demandé, sinon `/admin` si `role = ADMIN`, `/scan` si `role = SCANNER` (comportement actuel de `LoginPage.destination`).
- 401 : « Identifiants invalides », rien conservé, aucune requête rejouée (RG6 inc. 6) ; aide neutre « Vérifiez le type de compte choisi » dans un élément distinct du message, identique que l'identifiant existe ou non dans l'autre référentiel [D1-bis].
- 429 : « Trop de tentatives. Réessayez dans une minute. » (RG23 inc. 5).
- Les deux emplacements d'identifiants restent indépendants (RG21 inc. 5).

**RG3 — Routes de connexion existantes** [D4]
- `/compte/connexion` reste valide : même écran (même composant), entrée « Coureur » présélectionnée **quel que soit** `retour` (les parcours et tests de l'inc. 5 sont conservés). h1 « Connexion » (l'ancien « Connexion coureur » disparaît).
- La garde `requireRunnerSignedIn` de `/compte` et `RunnerSessionService.expire` continuent de renvoyer vers `/compte/connexion` avec `retour` (aucun changement de comportement ; la constante `RUNNER_LOGIN_ROUTE` peut rester).
- Le bouton « Se connecter » de `/scan` mène à `/connexion`, `retour=/scan`, entrée « Bénévole » présélectionnée (RG1) : inchangé.
- Le lien « J'ai déjà un compte » (inscription à une course, et nouvel écran `/inscription`) mène à `/compte/connexion` avec `retour`.
- La garde de `/admin` (`canMatch`) n'est pas modifiée ; elle ne redirige jamais vers `/connexion` (test front-unit de l'inc. 6).

**RG4 — Liens publics d'accès dans l'en-tête** [D2 ; amende RG2 et CA2 de l'inc. 6]
- Le contenu actuel de l'en-tête (« Courses », « Mes inscriptions », « Scan », liens et textes exacts) est conservé.
- Une zone « compte » est ajoutée, selon la seule connexion **coureur** :
  - **pas de connexion coureur** : « Se connecter » (cible `/connexion`) et « Créer un compte » (cible `/inscription`) ;
  - **connexion coureur active** : texte « Connecté : {pseudo} », sans lien ni bouton.
- **Pas de doublon avec la page affichée** : « Se connecter » n'est pas rendu dans l'en-tête sur `/scan` (le bandeau de la page porte déjà ce lien, et un seul lien « Se connecter » doit exister sur `/scan`), sur `/connexion` ni sur `/compte/connexion` ; « Créer un compte » n'est pas rendu sur `/inscription`.
- La connexion staff (bénévole, admin) n'a **aucun effet** sur l'en-tête : ni identifiant, ni rôle, ni mot « bénévole » ou « administrateur » (RG7 inc. 6).
- « Se déconnecter » reste là où il est (`/compte`, `/scan`, coquille admin) ; aucun nouveau contrôle de déconnexion.
- Le mot « Administration » et tout lien vers `/admin/**` restent **interdits** dans l'en-tête et sur toutes les pages publiques.
- Amendement de RG2 (inc. 6) : l'interdiction de lier `/connexion` est levée pour l'en-tête ; l'interdiction de lier `/admin/**` est conservée. `/scan` garde son unique lien « Se connecter ».

**RG5 — Inscription autonome : E26** [D3]
`POST /api/public/accounts`, corps `{"pseudo", "password"}`, anonyme. Réalisé par l'appel de `AccountService.create` existant : aucune règle de format, de normalisation ni de conflit n'est réécrite (RG9 inc. 6, `PseudoNormalizationSourceReviewTest` reste vert). Ordre des contrôles :
1. format du pseudo et du mot de passe : 400 `VALIDATION_FAILED` (RG2 et RG3 inc. 5) ;
2. pseudo normalisé déjà porté : 409 `BUSINESS_CONFLICT`, `detail` = « Pseudo déjà utilisé : {pseudo}. Si c'est votre compte, connectez-vous pour vous inscrire avec. » (texte actuel du service, valeur normalisée ; réutilisé tel quel) ;
3. création du compte seul : hash BCrypt coût 12 par l'encodeur unique ; aucun coureur, aucune course, aucun `qrToken`.
- Réponse 201 `{"pseudo": <valeur stockée, minuscules>}`. Ni mot de passe, ni hash, ni `accountId` (RG13 inc. 5).
- RG1 à RG5 de l'inc. 5 s'appliquent sans exception.
- Rôle à la connexion : `RUNNER` seulement. E26 ne consulte ni `ScannerAccount` ni les comptes de configuration.
- Journal : aucun pseudo ; une ligne INFO « compte créé {accountId} » est admise (RG16 inc. 5).
- Accès (RG10 inc. 5) : `/api/public/**` : anonyme, SCANNER et ADMIN autorisés ; compte pseudo présentant ses identifiants : 401 (inchangé). Le front n'envoie jamais `Authorization` vers `/api/public/**` (RG8 inc. 4), E26 compris.
- `/api/public/accounts` n'est pas dans le préfixe `/api/account/**` : la limite nginx de `/api/account/**` ne s'y applique pas (RG6).

**RG6 — Limitation de débit de E26** [D5]
- nginx, `limit_req`, clé `$binary_remote_addr`, `limit_req_status 429`, versionné sous `deploy/nginx/` ; rien dans l'application.
- **Même zone `backyard_registration` que E3** (10 requêtes par minute et par IP, rafale 5, `nodelay`, RG23 inc. 5), sur un `location` exact `= /api/public/accounts`.
- Évolution motivée de `DeployConfigIT.ca40` (CA40 inc. 5) : le nombre de `limit_req zone=` passe de 2 à 3 et l'assertion vérifie le nouveau `location` (zone, burst, nodelay) ; elle n'est pas affaiblie. L'inc. 8 ajoutera celui de E19 (RG15 inc. 8).
- PWA : un 429 affiche « Trop de tentatives. Réessayez dans une minute. », sans nouvel essai.
- Le 201 de E26 est suivi de E21 (zone `backyard_account`, 30 r/m) : pas de collision avec la zone d'inscription.

**RG7 — Écran d'inscription `/inscription`**
- Route publique, ajoutée à la liste fermée des routes du front (RG5 inc. 4, `app.routes.ts`, titre « Inscription — Backyard Ultra Tracker » à distinguer de `inscription/:raceId`) et, si nécessaire, aux chemins front servis par Spring Boot (`PwaPaths.FRONT_ROUTES`, RG53 inc. 4 ; CA21). À ne pas confondre avec `/inscription/{raceId}` (inchangée). Le fichier de route ne commence pas par `admin-` (RG11).
- h1 « Créer un compte ». Champs « Pseudo », « Mot de passe », « Confirmer le mot de passe » (libellés comme dans `registration-page.ts`), case « Rester connecté 24 h sur cet appareil » (décochée par défaut, RG21 inc. 5). Validation miroir (RG2, RG3 inc. 5) ; mots de passe différents : aucune requête. Mention de RG17 inc. 5.
- Aide : « Bénévole : votre compte est créé par l'organisateur. » (aucune mention de l'administration) [D10, D11].
- Succès (201) : connexion automatique en coureur par E21 (mêmes identifiants, un seul appel, emplacement coureur, RG21 inc. 5), puis `/compte` (texte existant « Aucune inscription. ») ou l'écran demandé (`retour`, par exemple `/inscription/{raceId}` où « M'inscrire à cette course » envoie E20) [D8].
- 409 : le `detail` est affiché avec le lien « J'ai déjà un compte » vers `/compte/connexion` (RG3). 400 : message de champ. 429 : message de RG6.
- Aucune requête rejouée. Aucun identifiant dans une URL, un journal ou un message (RG21 inc. 5).

**RG8 — Inscription à une course inchangée**
- `/inscription/{raceId}` (E3, E20), le lien « J'ai déjà un compte » et les confirmations restent identiques (RG7, RG8, RG17 inc. 5).
- Un compte créé par E26 s'inscrit à une course par E20, jamais par E3 (409, CL13 inc. 5).

**RG9 — Point d'entrée, pas de barrière** *(D2-bis tranchée)*
- Accueil, tableau de bord, détail coureur et inscription à une course restent accessibles **sans connexion**.
- Les routes qui exigent un profil renvoient vers l'écran de connexion avec `retour` : `/compte` (coureur, `/compte/connexion`) ; `/scan` garde son fonctionnement actuel (capture sans connexion, envoi suspendu, RG14 inc. 4).

**RG10 — Non-régression de la sécurité**
- L'API ne change pas hors E26 : matrice E1 à E25 de l'inc. 6 inchangée ; HTTP Basic sans état ; référentiel par préfixe d'URL.
- `/admin/**` pour un non-ADMIN : « Page introuvable » (RG3 inc. 6).
- Aucun bloc admin dans l'écran de connexion ni d'inscription (RG5 inc. 6).

**RG11 — Blocs publics, service worker, réserve R6-1**
- Les écrans `/connexion` (unifié) et `/inscription` sont des fichiers de route publics : leur nom de fichier ne commence pas par `admin-`, ils n'importent ni composant ni texte propre aux écrans admin (« Administration des courses », « Gérer la course et les coureurs », « Imprimer les QR codes »). Ils sont donc préchargés par `ngsw-config.json` (groupe `app`), ce qui est voulu (écran de connexion utilisable hors ligne). `ngsw-config.json` n'est pas modifié.
- R6-1 (fragilité du motif `admin-*`) : cet incrément n'ajoute aucun écran admin ; son rapport de validation confirme qu'aucun bloc partagé préchargé nouveau ne contient de texte admin (CA20). La revérification complète de R6-1 s'applique à l'inc. 8 (nouveaux écrans admin de gestion des scanneurs).

---

## 6. Cas limites

**CL1 — Coureur qui choisit « Bénévole ».** E19 = 401 : « Identifiants invalides », rien conservé ; identifiants coureur éventuels intacts (RG6 inc. 6).
**CL2 — Bénévole ou admin qui choisit « Coureur ».** E21 = 401, même message. Pas d'essai croisé.
**CL3 — Identifiant présent dans les deux référentiels, même mot de passe** (CL10 inc. 5). Chaque entrée désigne le compte de son référentiel.
**CL4 — Admin.** Entrée « Bénévole », arrivée sur `/admin` (ou sur le retour demandé). Ni l'écran ni l'en-tête ne contiennent « Administration ». Session ADMIN expirée (401 pendant une action) : retour sur `/connexion?retour=/admin/…`, entrée « Bénévole » présélectionnée, message « Session expirée… ».
**CL5 — Pseudo existant ou à la casse près** (« Lievre » alors que `lievre` existe) : 409 (normalisation avant contrôle). Pseudo égal à un nom ADMIN ou SCANNER de configuration : accepté, sans droit (RG9, CL10 inc. 5).
**CL6 — Créations simultanées du même pseudo.** Une seule réussit (201), l'autre 409 ; aucun compte orphelin supplémentaire.
**CL7 — Compte sans inscription.** `/compte` affiche « Aucune inscription. » (liste vide) ; E25 le liste avec `runnerCount = 0` ; l'admin peut le réinitialiser ou le supprimer.
**CL8 — Déjà connecté en coureur, ouvre `/inscription`.** « Vous êtes connecté en tant que {pseudo} » et lien vers `/compte`, sans formulaire [D9]. (Connecté en bénévole seulement : le formulaire s'affiche, la connexion staff n'est pas touchée.)
**CL9 — Rafale de créations depuis une même IP** (Wi-Fi du site). Au-delà du seuil, 429 ; la zone est partagée avec E3 (conséquence assumée).
**CL10 — Réseau coupé pendant la création.** Erreur « serveur injoignable », aucun rejeu ; si la requête avait abouti, un nouvel essai donne 409 avec le lien de connexion.
**CL11 — Ancienne PWA en cache.** Les anciens écrans (`/connexion` staff, `/compte/connexion` coureur) fonctionnent comme avant ; E26 n'est pas appelé (RG46 inc. 4). Après mise à jour (bandeau « Nouvelle version disponible »), l'écran unique et l'en-tête s'affichent.
**CL12 — Sans objet.** Bascule de yard, passage manuel ou scan, coureur réintégré, plusieurs courses, course non démarrée, coureur sans passage : aucune règle de course modifiée (CA19).
**CL13 — Connexion coureur et connexion staff simultanées.** L'en-tête ne reflète que la connexion coureur (RG4) ; `/scan` reflète la connexion staff.

---

## 7. Critères d'acceptation

Comptes de test : ADMIN `admin-test` / `admin-secret`, SCANNER `scanner-test` / `scanner-secret`, compte pseudo `Lievre` / `motdepasse-1`, nouveau compte `nouveau-{run}` / `motdepasse-9`.

**CA1 — E26 crée un compte seul (RG5) [slice + IT]**
Donné aucun compte `nouveau-1`. `POST /api/public/accounts {"pseudo":"  Nouveau-1 ","password":"motdepasse-9"}` (anonyme) : 201, corps `{"pseudo":"nouveau-1"}` sans autre propriété ; en base : 1 ligne `account` (pseudo `nouveau-1`, hash BCrypt coût 12 vérifiant `motdepasse-9`), 0 `runner` ; le corps ne contient ni `password`, ni hash, ni `accountId`.

**CA2 — Validations de E26 (RG5) [slice]**
Pseudo `ab` → 400 sur `pseudo` ; `a b c` → 400 ; mot de passe `court12` (7 caractères) → 400 sur le mot de passe ; mot de passe de 73 octets → 400 ; corps vide → 400 ; le service n'est appelé dans aucun cas.

**CA3 — Pseudo déjà pris (RG5, CL5) [IT]**
Donné `lievre`. `POST` avec `Lievre`, puis `LIEVRE`, puis `lievre` : 409 `BUSINESS_CONFLICT` chaque fois ; le `detail` est « Pseudo déjà utilisé : lievre. Si c'est votre compte, connectez-vous pour vous inscrire avec. » ; `count(account WHERE pseudo='lievre')` = 1. Avec le mot de passe `motdepasse-1` de `Lievre` : 409 aussi (E26 ne connecte jamais).

**CA4 — Créations simultanées (CL6) [IT]**
10 appels concurrents avec le même pseudo : un seul 201, neuf 409, un seul compte en base.

**CA5 — Matrice d'accès de E26 (RG5, RG10) [slice]**
Anonyme, SCANNER, ADMIN : 201 ; `Lievre` présentant ses identifiants : 401 « Identifiants invalides » sans `WWW-Authenticate` ; `admin-test:mauvais` : 401. Test de table additionnel (E26 × 4 profils) ; CA1 de l'inc. 6 (25 × 4 = 100 cas) reste vert sans modification.

**CA6 — Compte créé : RUNNER seulement (RG5, CL3) [IT]**
Après CA1, `nouveau-1` / `motdepasse-9` : `GET /api/account/me` → 200, `pseudo = "nouveau-1"`, liste de coureurs vide ; `GET /api/scan/me` et `GET /api/admin/races` → 401.

**CA7 — Inscription d'un compte vide à une course (RG8, CL7) [IT]**
`nouveau-1` : `POST /api/account/races/{raceId}/registrations` sur une course `SETUP` → 201 avec dossard et `qrToken` ; `POST /api/public/races/{raceId}/registrations` avec `nouveau-1` → 409.

**CA8 — Écran unique : deux entrées, libellés, aucune mention de l'administration (RG1) [E2E]**
Sur `/connexion`, contexte neuf : h1 exactement « Connexion » ; « Coureur » (sélectionnée) et « Bénévole » visibles ; entrée « Coureur » : champ « Pseudo » et case « Rester connecté 24 h sur cet appareil » ; après choix de « Bénévole » : champ « Nom d'utilisateur » et case « … (compte scanner uniquement) » ; aucun texte « Administration » ni « administrateur » (`document.body.innerText`, insensible à la casse) ; aucun `a[href]` vers `/admin` ; aucune requête `/api/` avant la soumission.

**CA9 — Orientation par profil (RG2, CL4) [E2E]**
(a) « Coureur » + `Lievre-{run}` : E21 = 200, arrivée sur `/compte`. (b) « Bénévole » + `scanner-test` : E19 = 200, arrivée sur `/scan`. (c) « Bénévole » + `admin-test` : E19 = 200, arrivée sur `/admin` qui liste les courses. (d) Avec `retour=/scan`, l'ADMIN revient sur `/scan` (CA11 inc. 6 inchangé).

**CA10 — Mauvaise entrée (CL1, CL2, CL3) [E2E]**
(a) `Lievre-{run}` sur « Bénévole » : E19 = 401, « Identifiants invalides », rien dans l'emplacement bénévole (stockages vidés), `/scan` redemande une connexion. (b) `scanner-test` sur « Coureur » : E21 = 401, même message ; `localStorage`, `sessionStorage`, IndexedDB et Cache Storage sans mot de passe ni Basic. (c) Message « Identifiants invalides » identique pour un identifiant inconnu et pour un identifiant existant dans l'autre référentiel, et l'aide « Vérifiez le type de compte choisi » présente dans les deux cas (D1-bis).

**CA11 — Indépendance des emplacements (RG2) [E2E]**
Connexion coureur puis bénévole dans le même contexte : `/compte` affiche toujours les inscriptions de `Lievre-{run}`, `/scan` envoie avec le Basic bénévole ; la déconnexion coureur (sur `/compte`) n'efface pas la connexion bénévole.

**CA12 — Écran d'inscription autonome (RG7, CL8) [E2E]**
(a) `/inscription` : h1 « Créer un compte », champs « Pseudo », « Mot de passe », « Confirmer le mot de passe » ; mots de passe différents : aucune requête. (b) Soumission valide `nouveau-{run}` : une seule requête E26 (201, sans en-tête `Authorization`) puis une seule E21 **de connexion automatique** (200) émise depuis l'écran de création (précision COH7-3 : la page `/compte`, une fois affichée, émet sa propre E21 pour charger sa liste ; elle n'est pas une connexion et n'est pas comptée) ; arrivée sur `/compte` avec « Aucune inscription. ». (c) Pseudo pris : 409, `detail` affiché avec « J'ai déjà un compte » vers `/compte/connexion` ; aucun mot de passe dans l'URL ni dans les stockages (« Rester connecté » décochée). (d) Réponse E26 remplacée par un 429 à corps HTML : « Trop de tentatives. Réessayez dans une minute. », une seule requête. (e) Connecté en coureur, `/inscription` affiche « Vous êtes connecté en tant que {pseudo} » sans champ de création.

**CA13 — Enchaînement avec une course (RG7, RG8) [E2E]**
Depuis `/inscription/{raceId}` (course `SETUP`), l'anonyme suit « Créer un compte » (de l'en-tête, avec retour vers cette page), crée `nouveau-{run}`, revient sur la page de la course connecté, clique « M'inscrire à cette course » : une requête E20 (201), le QR s'affiche et `/compte` le liste.

**CA14 — Liens publics de l'en-tête (RG4, RG9) [E2E, évolution de CA2 inc. 6]**
Anonyme, sur `/`, `/courses/{id}`, `/coureurs/{id}`, `/inscription/{id}`, `/compte/connexion`, `/inscription`, `/scan`, `/connexion` : aucun `a[href]` vers `/admin` ; le texte visible ne contient pas « Administration » ; les liens « Courses », « Mes inscriptions », « Scan » de l'en-tête sont conservés (le nombre de liens « Scan » reste 2 sur `/`). Liens de la zone « compte » : sur `/`, `/courses/{id}`, `/coureurs/{id}`, `/inscription/{id}` : « Se connecter » (cible `/connexion`) et « Créer un compte » (cible `/inscription`) ; sur `/scan` : un **seul** lien « Se connecter » (celui du bandeau, `retour=/scan`) et « Créer un compte » ; sur `/connexion` et `/compte/connexion` : « Créer un compte » seulement ; sur `/inscription` : « Se connecter » seulement. Connecté en coureur : ces deux liens disparaissent partout, « Connecté : {pseudo} » apparaît dans l'en-tête, aucun nouveau bouton « Se déconnecter » (un seul sur `/compte`). Connecté en bénévole ou en admin seulement : l'en-tête est identique à celui d'un anonyme (aucun identifiant ni rôle). L'accueil et le tableau de bord s'ouvrent sans connexion.

**CA15 — `/admin` toujours introuvable (RG10) [E2E]**
Anonyme, coureur, SCANNER : `/admin` et `/admin/comptes` affichent « Page introuvable », sans requête `/api/admin/**` (CA3 et CA4 inc. 6 inchangés).

**CA16 — Routes conservées (RG3) [E2E]**
`/compte/connexion` ouvre l'écran unique (h1 « Connexion »), entrée « Coureur » sélectionnée, y compris avec `retour=/scan` ; `/compte` sans connexion renvoie vers `/compte/connexion` avec retour vers `/compte` ; après connexion, retour sur `/compte`.

**CA17 — Limitation nginx de E26 (RG6) [config + manuel]**
[config] La configuration nginx versionnée applique `limit_req` (zone `backyard_registration`, `rate=10r/m`, `burst=5 nodelay`, 429) à `location = /api/public/accounts` ; exactement 3 `limit_req zone=` dans le fichier (E3, E26, `/api/account/`) ; aucun autre `location` n'est limité. [manuel] Depuis une même IP après 2 minutes sans requête : 6 requêtes E26 simultanées passent (201 ou 409), la 7e reçoit 429.

**CA18 — Journaux et minimisation (RG5) [unit, capture des journaux]**
Création, conflit (409) et échec de validation de E26 : aucune ligne de journal, à aucun niveau, ne contient `nouveau-1`, `motdepasse-9` ni `$2`.

**CA19 — Non-régression (RG10, CL12) [IT + E2E + front-unit]**
`mvn -B -f backend/pom.xml clean verify` vert ; suite E2E verte à l'exception de CA39 sous Chromium (état de référence R5-3/R4-1) ; tests front-unit verts. Les **seuls** tests existants modifiés sont ceux de la section 8, chacun avec motif, catégorie et accord de l'agent fonctionnel (règle 2) ; chacun reste au moins aussi strict. Les assertions de sécurité (aucune requête `/api/admin/**` pour un non-ADMIN, aucun mot de passe stocké, aucun lien `/admin`, aucun texte « Administration ») ne sont pas affaiblies.

**CA20 — Aucun bloc admin sur `/connexion` et `/inscription` (RG10, RG11) [E2E, reprise de CA6 inc. 6]**
Aucun bloc `admin-*.js` n'est chargé ni mis en cache par la visite de ces deux écrans (service worker actif), et aucun fichier `.js` du build contenant « Administration des courses », « Gérer la course et les coureurs » ou « Imprimer les QR codes » n'est chargé par ces visites (CA6 inc. 6 reste vert, ses blocs partagés `race-form` et `account-admin-actions` restant la limite assumée).

**CA21 — `/inscription` servi par Spring Boot (RG7) [IT]**
`GET /inscription` et `HEAD /inscription`, anonymes : 200 avec le contenu de `index.html` (comme `/connexion`) ; `GET /nimporte-quoi` reste 401. Si `/inscription/**` ne couvre pas `/inscription`, `FRONT_ROUTES` reçoit `/inscription` explicitement. Extension additive de `PwaStaticResourcesIT`.

**CA22 — Accessibilité et présélection (RG1, RG3) [E2E]**
(a) Aucune violation `serious` ou `critical` (même outil que CA41 inc. 4) sur `/connexion` (entrées « Coureur » puis « Bénévole ») et sur `/inscription`. (b) `/connexion?retour=/scan`, `/connexion?retour=/admin/comptes` et `/connexion?profil=benevole` présélectionnent « Bénévole » ; `/connexion` seul, `/connexion?retour=/compte`, `/connexion?profil=x` et `/compte/connexion?retour=/scan` présélectionnent « Coureur ».

**CA23 — Messages d'expiration par entrée (RG1, CL4) [E2E, reprise de ca24]**
Après un 401 de E6 pendant un envoi de scan (réponse remplacée), l'écran affiche h1 « Connexion », « Session expirée » et l'entrée « Bénévole » sélectionnée ; après un 401 sur `/api/account/**`, `/compte/connexion` affiche « Session expirée ou identifiants modifiés : reconnectez-vous » avec « Coureur » sélectionnée.

**CA24 — Aucune frame d'en-tête erronée au chargement direct (RG4) [front-unit + E2E] (ajouté à la validation, COH7-1)**
(a) [front-unit] Donné le routeur avant la première navigation (URL initiale du navigateur `/scan`), l'en-tête rendu ne contient ni « Se connecter » ni « Créer un compte » erroné : soit aucune zone « compte » tant que la première `NavigationEnd` n'a pas eu lieu, soit la zone calculée sur l'URL réelle ; le test échoue sur l'implémentation initialisée à `/`. (b) [E2E] Sur `/scan`, `/connexion`, `/compte/connexion` et `/inscription` en chargement direct (`goto`, sans attente préalable), l'observation de la **première** frame comportant la zone « compte » (MutationObserver ou équivalent de `manual/probe-header-flash.mjs`) montre : sur `/scan`, zéro lien « Se connecter » dans l'en-tête ; sur `/connexion` et `/compte/connexion`, zéro « Se connecter » ; sur `/inscription`, zéro « Créer un compte » ; Chromium et WebKit. L'attente `expect.poll` de `auditNoAdminLink` ne peut servir qu'aux audits d'état stable, jamais à CA24.
*(Note du 2026-10-02, décision de l'utilisateur D8 de l'inc. 9)* : le correctif de RES7-1 et ce critère sont **repris par l'incrément 9** (CA8, sous-critère (c) : les assertions (a) et (b) ci-dessus y sont écrites et vertes sous Chromium et WebKit). L'échéance de RES7-1 devient « avant le GO de l'inc. 9 » ; le contenu du critère n'est pas assoupli. Voir `increment9.md`, section 7.

### Couverture
| RG / CL | CA |
|---|---|
| RG1 | CA8, CA10, CA22, CA23 |
| RG2 | CA9, CA10, CA11 |
| RG3 | CA16, CA22 |
| RG4 | CA14, CA24 |
| RG5 | CA1 à CA6, CA12 (b), CA18 |
| RG6 | CA12 (d), CA17 |
| RG7 | CA12, CA13, CA21 |
| RG8 | CA7, CA13 |
| RG9 | CA14 |
| RG10 | CA5, CA15, CA19, CA20 |
| RG11 | CA20 |
| CL1, CL2, CL3 | CA10 |
| CL4 | CA9, CA23 |
| CL5 | CA3 |
| CL6 | CA4 |
| CL7 | CA7 |
| CL8 | CA12 (e) |
| CL9, CL10 | CA12 (d), CA17 (CL10 : essai manuel consigné) |
| CL11, CL12 | CA19 |
| CL13 | CA14 |

---

## 8. Tests existants à faire évoluer

Chaque évolution exige un motif écrit et l'accord de l'agent fonctionnel (règle 2). Catégories (convention de `INC-6-e2e.md`) : **A** = mécanique de parcours, assertions conservées à l'identique ; **B** = attendu changé par une règle de cet incrément, remplacement aussi strict ; **C** = assertion retirée, affaiblie ou test désactivé (**aucune C n'est admise** dans cet incrément). La catégorie est « probable » : le testeur la confirme à l'exécution, et toute C est remontée à l'agent fonctionnel avant d'être appliquée.

### E2E (Playwright, `frontend/e2e/tests/` et `fixtures/`)
| Test / fichier | Évolution | Cat. |
|---|---|---|
| `inc6-ca2-no-admin-link.spec.ts` (CA2 inc. 6) + `fixtures/admin-separation.ts` (`auditNoAdminLink`, option `scanLoginLinkAllowed`) | L'audit n'interdit plus tout lien `/connexion` : il admet les liens de la zone « compte » de l'en-tête (RG4, CA14) et le lien du bandeau de `/scan`, avec les règles de présence/absence de CA14 ; le contrôle « aucun `a[href]` `/admin` », « aucun texte ni lien ni bouton Administration » est conservé à l'identique ; ajout de `/inscription` à la liste des écrans audités ; le test de la phase « coureur » vérifie « Connecté : {pseudo} » et l'absence des deux liens. Les liens « Courses », « Mes inscriptions », « Scan » (2 liens `Scan` sur `/`) sont inchangés. | B |
| `inc6-ca10-ca11-staff-login.spec.ts`, CA10 | Parcours « Scan » puis « Se connecter » (un seul lien sur `/scan`, grâce à RG4), entrée « Bénévole » présélectionnée par `retour=/scan` : aucune ligne à changer attendue. Ajouter dans le même test la vérification de l'aide « Vérifiez le type de compte choisi » (additif, D1-bis). | A |
| `inc6-ca10-ca11-staff-login.spec.ts`, CA11 | `auditNoAdminLink` sur `/connexion?retour=/scan` : suit l'évolution de l'utilitaire (aucun lien `/connexion` dans l'en-tête sur cet écran, donc l'attendu actuel `[]` reste vrai) ; reste « aucun `/admin`, aucun « administrateur » ». | A |
| `ca21-routes.spec.ts` (CA21 inc. 4) | Les routes `/connexion` (h1 « Connexion ») et les tests `/admin` « Page introuvable » restent identiques. Ajouts : `/inscription` (h1 « Créer un compte », rechargement) et `/compte/connexion` (h1 « Connexion ») dans la liste des routes statiques. | A (additif) |
| `ca24-login-roles.spec.ts` (CA24 inc. 4) | `goto('/connexion')` suivi de « Nom d'utilisateur » (tests « identifiants admin invalides » et « connexion admin ») : choisir « Bénévole » avant de remplir (ou `?profil=benevole`). Tests passant par `/scan` puis « Se connecter » : inchangés. Test du 401 de E6 : `h1 « Connexion »` et « Session expirée » inchangés (CA23). Assertions de stockage et `adminRequests` égal à `[]` conservées. | A |
| `ca25-credentials-storage.spec.ts` (CA25 inc. 4) | Premier test (`goto('/connexion')` puis admin) : choisir « Bénévole ». Les quatre autres passent par `/scan` : inchangés. Assertions de stockage conservées. | A |
| `ca36-admin-crud.spec.ts` (helper local `loginAdmin`, appel sans `retour`) | Sans `retour` : `?profil=benevole` ou choix de « Bénévole ». Avec `retour` sous `/admin` (présélection) : inchangé. | A |
| `fixtures/ui.ts` `loginAdmin(page, retour='/admin')`, `ca34-ca35-dnf-reintegration.spec.ts`, `ca41-accessibility.spec.ts` (ligne `retour=/admin/courses/…`), `inc5-*`, `inc6-ca5-ca8`, `inc6-ca6` | `retour` sous `/admin` : « Bénévole » présélectionnée, aucun changement. | aucune |
| `inc6-ca5-ca8-admin-access.spec.ts` (`loginAdminFromConnexion`, `goto('/connexion')` sans `retour`) | Choisir « Bénévole » avant de remplir. Le `h1 « Connexion »` (ligne 85) est inchangé. | A |
| `ca41-accessibility.spec.ts` (CA41 inc. 4), `goto('/connexion')` | L'assertion « aucune violation sérieuse » est conservée à l'identique sur l'écran unique (entrée « Coureur ») ; la vérification de l'entrée « Bénévole » et de `/inscription` est ajoutée (CA22). | A (additif) |
| `inc5-accessibility.spec.ts` (ligne 32) | `/compte/connexion` : h1 attendu « Connexion coureur » devient « Connexion » (RG3). Le reste (aucune violation sérieuse) est conservé. | B |
| `inc5-ca41-too-many-attempts.spec.ts` | `/compte/connexion`, champ « Pseudo », bouton « Se connecter » : aucun changement attendu ; à confirmer à l'exécution (message 429 exact conservé). | A probable |
| `inc6-ca3-ca4-admin-not-found.spec.ts` (CA3, CA4 inc. 6) | Parcours « /scan » puis « Se connecter » : inchangé. Les assertions « sans redirection vers `/connexion` » et « aucun formulaire » restent vraies (l'en-tête n'est pas un formulaire de connexion). | aucune |
| `ca26-no-auth-to-public.spec.ts`, `ca27` à `ca31`, `ca37`, `ca40`, `ca42`, `ca44` (cliquent « Se connecter » sur `/scan`) | Aucun changement attendu : un seul lien « Se connecter » existe sur `/scan` (RG4). Si un doublon apparaissait, c'est un défaut de réalisation de RG4, pas une raison de modifier ces tests. | aucune |
| `ca39-installability-offline.spec.ts` | Aucun changement attendu (pas de lien de connexion). Contrôler que l'en-tête n'ajoute pas de requête réseau. | aucune |
| `inc6-ca6-admin-chunks-not-cached.spec.ts` | Inchangé ; prolongé par CA20 pour les deux nouveaux écrans. | aucune |

### Front-unit (`frontend/src/app/core/`)
| Test | Évolution | Cat. |
|---|---|---|
| `admin-separation.spec.ts`, « l'en-tête de navigation commun n'a ni lien admin, ni « Administration », ni lien /connexion… » (RG2) | L'assertion « aucun lien `/connexion` dans l'en-tête » devient « liens `/connexion` et `/inscription` admis, et seulement ceux-ci ; toujours aucun lien `/admin`, aucune mention « Administration » ; « Courses », « Mes inscriptions », « Scan » conservés ». | B |
| `admin-separation.spec.ts`, « aucun écran public… » (liste `arrayContaining` des pages) | Si `account/runner-login-page.ts` est supprimé au profit de l'écran unique, retirer ce nom de la liste et ajouter le fichier de la page d'inscription ; l'assertion « aucun lien admin, aucune mention » s'applique aussi au nouveau fichier. | B |
| `admin-separation.spec.ts`, « seul /scan lie /connexion » (pages) | Reste vrai si l'en-tête (`app.ts`) porte les nouveaux liens : aucun changement. Si un autre écran de page lie `/connexion` (littéral), le test échoue : il faut alors utiliser `/compte/connexion` (RG3) ou faire évoluer le test (B). | aucune ou B |
| `admin-separation.spec.ts`, RG3 (« garde… ne redirige plus vers `['/connexion']` ») et RG7 (gabarit sans `/admin`) | Inchangés ; la garde de `/admin` n'est pas touchée, le gabarit de l'écran unique ne contient pas `/admin` (RG1). | aucune |

### Backend
| Test | Évolution | Cat. |
|---|---|---|
| `it/NoTestEndpointsIT` (CA7 inc. 4) | Les correspondances sous `/api/**` deviennent exactement E1 à E26 : 26 méthodes, 21 motifs uniques (ajout de `/api/public/accounts`). Le test garde son contrôle d'égalité exacte (aucun assouplissement) ; les mots interdits (`test`, `clock`, `time`, `reset`, `close`) restent absents. | B |
| `it/DeployConfigIT.ca40_nginxRateLimiting` (CA40 inc. 5) | Exactement 3 `limit_req zone=` ; ajout de la vérification du `location = /api/public/accounts` (zone `backyard_registration`, `burst=5 nodelay`). Les vérifications existantes sont conservées. | B |
| `it/PwaStaticResourcesIT` (valeurs `/courses/1`, … `/connexion`) | Ajout de `/inscription` (CA21), additif. | A (additif) |
| `api/AccessMatrixSliceTest` (CA1 inc. 6, E1 à E25, 100 cas) | **Aucun changement** : la liste est fixe ; E26 a sa table (CA5). | aucune |
| `AccountFlowIT.ca19`, `PseudoNormalizationSourceReviewTest`, `ApiExceptionHandlerLoggingTest`, `InternalErrorLogsIT`, `V1ToV2LegacyDataHttpIT` | Aucun changement attendu ; ils doivent rester verts avec E26 (CA19). | aucune |

### Tests nouveaux (additifs)
CA1 à CA7, CA18, CA21 (backend, front-unit pour la validation miroir et le routage de l'écran unique) ; CA8 à CA16, CA20, CA22, CA23 (E2E) ; CA17 (config) ; la présélection (`RG1`) et la règle d'affichage de l'en-tête (`RG4`) sont des fonctions pures testées en front-unit.

---

## 9. Décisions

### Tranchées par l'utilisateur (2026-10-01)
- **D1 — option A** : deux entrées visibles, « Coureur » et « Bénévole » ; l'admin passe par « Bénévole » ; aucun choix « Administrateur ». Règles : RG1, RG2.
- **D2-bis — point d'entrée proposé, pas une barrière** : le suivi public et le lien d'inscription public restent ouverts. Règle : RG9.
- **D7 — avant les comptes scanneurs** : cet incrément est l'inc. 7 ; les scanneurs deviennent l'inc. 8 (E27 à E31). Section 3.

### Tranchées par l'utilisateur (confirmées le 2026-10-01, telles que recommandées)
- **D2** : lever l'interdiction de lier `/connexion` (RG2 inc. 6) dans l'en-tête, avec « Se connecter », « Créer un compte » et « Connecté : {pseudo} » (coureur seulement, sans doublon avec la page, sans contrôle de déconnexion), conserver l'interdiction de lier `/admin` et d'écrire « Administration » (RG4).
- **D1-bis** : message d'échec « Identifiants invalides » plus l'aide neutre « Vérifiez le type de compte choisi » (RG2).
- **D3** : endpoint E26 pour un compte sans course (RG5).
- **D4** : `/compte/connexion` conservée comme alias de l'écran unique, « Coureur » présélectionnée, h1 « Connexion » ; présélection de « Bénévole » par `retour` sous `/scan` ou `/admin` ou par `profil=benevole` (RG1, RG3).
- **D5** : E26 dans la zone nginx de E3, 10 par minute, rafale 5 (RG6).
- **D6** : pas de purge des comptes jamais utilisés (aucune date de création stockée).
- **D8** : connexion automatique en coureur après création (RG7).
- **D9** : utilisateur déjà connecté sur `/inscription` : message, sans formulaire (CL8).
- **D10** : libellé « Bénévole » seul, ni « organisateur » ni « administrateur ». *Précision de l'agent fonctionnel (validation, 2026-10-01, COH7-2)* : D10 vise le **libellé de l'entrée de connexion** (« Bénévole »). Le **texte d'aide** de RG7 « Bénévole : votre compte est créé par l'organisateur. » est conforme : « organisateur » est le terme déjà employé par le produit (RG17 inc. 5, « À signaler à l'organisateur ») et n'est ni « administrateur » ni « Administration », seuls mots interdits sur les écrans publics (RG1, RG4, RG7 inc. 6). Le texte est donc conservé et figé ; le test littéral (`inc7-login-routes-sources.spec.ts`) et la chaîne de CA20 restent valides.
- **D11** : pas d'auto-création de comptes bénévoles (inc. 8 : créés par l'admin).

Aucun point ouvert. Toute évolution ultérieure d'une de ces décisions fera l'objet d'une révision de cette spec.
