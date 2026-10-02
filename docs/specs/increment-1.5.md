# Increment 1.5 : Créer des comptes admin

L'admin master crée et liste les Comptes `ADMIN` depuis l'espace d'administration livré en 1.4. Deux endpoints (`POST` et `GET /api/administration/admins`) sont réservés au seul `ADMIN_MASTER` : un `ADMIN` non master reçoit 403 et ne voit pas la fonction dans l'écran. Un écran `/administration/admins` (liste + formulaire de création) et un lien sur `/administration` rendent le tout vérifiable de bout en bout. Taille estimée : environ 280 lignes de production (domaine et cas d'usage ~70, endpoints et DTO ~60, règle de sécurité ~10, front ~140), sous la cible de 400 : aucun découpage nécessaire. Aucun changeset Liquibase, aucune nouvelle variable d'environnement.

## 1. Périmètre

**Inclus**
- Cas d'usage `CreerAdmin` et `ListerAdmins` (contexte `comptes`), `Compte.creerAdmin(...)` (domaine).
- Endpoints `POST /api/administration/admins` (création) et `GET /api/administration/admins` (liste), réservés à `ADMIN_MASTER`. Règle d'accès `/api/administration/admins/**` plus stricte que `/api/administration/**` (1.4).
- Front : écran `/administration/admins` (liste et formulaire de création), lien « Gérer les administrateurs » sur `/administration` visible uniquement de l'admin master, garde de route.
- Réutilisation des rôles, de la session, du format d'erreur `ProblemDetail`, du CSRF, de `Pseudo`, `MotDePasse` et `EncodeurMotDePasse` (1.1 à 1.4).

**Exclu**
- Création des Comptes `BENEVOLE` par un admin, liste des bénévoles, changement de mot de passe (y compris celui d'un admin créé), écran « Mon compte » : 1.6.
- Modification, désactivation ou suppression d'un Compte `ADMIN` (aucun incrément ne la prévoit dans la roadmap : point ouvert 2). Suppression d'un compte coureur : 3.6.
- Création d'un second `ADMIN_MASTER` : impossible (RG10, 1.4 RG9).
- Contrôle du rôle réel en base à chaque requête (le rôle est celui de la session) : point ouvert 4.
- Pagination, recherche ou tri paramétrable de la liste : point ouvert 3.
- Toute fonctionnalité de Course, Inscription, Passage : 2.x et suivants.

## 2. Règles de gestion

**Création**
- **RG1** : `POST /api/administration/admins` n'est accessible qu'au rôle `ADMIN_MASTER`. Un `ADMIN` reçoit 403 `ACCES_REFUSE`, comme un `BENEVOLE` ou un `COUREUR` ; un anonyme reçoit 401 `NON_AUTHENTIFIE`.
- **RG2** : le Compte créé a toujours le rôle `ADMIN`. Aucun champ du corps ne permet de choisir le rôle : un champ `role` éventuellement envoyé est ignoré (même principe que 1.1 RG1).
- **RG3** : le pseudo suit `Pseudo` de 1.1 RG2 (`trim`, 3 à 30 caractères, lettres Unicode, chiffres, `.`, `_`, `-`, ordre requis puis longueur puis caractères). L'unicité est la même que celle de 1.1 RG3 : insensible à la casse, **tous rôles confondus** (un pseudo de coureur, d'admin ou d'admin master est « déjà utilisé »), garantie par le cas d'usage et par la contrainte unique sur `pseudo_normalise` (conflit concurrent : 409, jamais 500). Le pseudo est conservé tel que saisi (casse comprise, après `trim`).
- **RG4** : le mot de passe suit `MotDePasse` de 1.1 RG4 : **12 caractères minimum**, 128 maximum, comptés en points de code, sans `trim`, aucune règle de composition. Les règles ne sont pas réécrites : `Pseudo` et `MotDePasse` du domaine sont réutilisés.
- **RG5** : un Compte créé a : `id` (UUID généré côté application), `pseudo`, `pseudoNormalise`, empreinte Argon2id par le même `EncodeurMotDePasse` qu'en 1.1, `role = ADMIN`, `creeLe` = `Clock` injecté tronqué à la microseconde. La réponse 201 est un `CompteReponse` (1.1) : jamais de mot de passe, de hash ni de `pseudoNormalise`.
- **RG6** : ordre de contrôle de `POST /api/administration/admins` : CSRF (403 `CSRF_INVALIDE`), authentification (401), rôle (403 `ACCES_REFUSE`), lecture du corps (415 / 400 `CORPS_ILLISIBLE`), validation (400 `VALIDATION_ECHOUEE`, toutes les violations, une par champ au plus), unicité (409 `PSEUDO_DEJA_UTILISE`). Le 400 est prioritaire sur le 409. Le 409 `DEJA_CONNECTE` de 1.2 RG18 ne s'applique pas à cet endpoint (il exige une session).
- **RG7** : confidentialité. Le mot de passe en clair n'apparaît ni dans les réponses, ni dans les journaux (tout niveau), exceptions, `ProblemDetail`, `toString()`. Le DTO de requête et la commande du cas d'usage masquent la valeur (`CreerAdminRequete[pseudo=..., motDePasse=masqué]`). Les corps de requête ne sont jamais journalisés ; les messages d'erreur ne reprennent jamais la valeur saisie.

**Liste**
- **RG8** : `GET /api/administration/admins` (rôle `ADMIN_MASTER` uniquement) renvoie la liste des Comptes de rôle `ADMIN`, triée par `creeLe` croissant puis par `pseudoNormalise` croissant (ordre déterministe). Liste vide : tableau `[]`. Pas de pagination (point ouvert 3). Chaque élément est un `CompteReponse` (`id`, `pseudo`, `role`, `creeLe`).
- **RG9** : contrôle des rôles. Les règles `/api/administration/admins` et `/api/administration/admins/**` (toutes méthodes) sont réservées à `ADMIN_MASTER` et placées **avant** la règle `/api/administration/**` (admins et admin master, 1.4 RG13). Un `ADMIN` reçoit 403 sur tout chemin sous `/api/administration/admins`, même inexistant (le refus précède le 404). `GET /api/administration/acces` reste accessible à `ADMIN` et `ADMIN_MASTER` (1.4 RG14).
- **RG10** : l'`ADMIN_MASTER` n'apparaît pas dans la liste (seuls les `ADMIN`, RG8) et ne peut être créé par ces endpoints (RG2).
- **RG11** : aucune modification ni suppression d'un Compte `ADMIN` n'est exposée : `PUT`, `PATCH`, `DELETE` sur `/api/administration/admins/{id}` n'existent pas (404 ou 405 pour l'admin master, jamais 2xx ; 403 pour un non-master selon RG9).
- **RG12** : l'admin créé se connecte par `POST /api/connexion` (1.2, soumis à la limitation de 1.3, pseudo insensible à la casse). `CompteReponse.role` vaut `ADMIN`, la session porte `ROLE_ADMIN`. Après connexion il arrive sur `/administration` (1.4 RG15), voit « Administrateur » mais ni le lien ni l'écran de gestion des admins.
- **RG13** : journal. À la création, une ligne INFO « Admin créé (compte `<id>` par `<id de l'admin master>`) » : identifiants uniquement, ni pseudo ni mot de passe (cohérent avec 1.4 RG10).

**Front**
- **RG14** : routes et gardes. `/administration/admins` : anonyme redirigé vers `/connexion?retour=%2Fadministration%2Fadmins` ; `COUREUR`, `BENEVOLE` et `ADMIN` redirigés vers `/acces-refuse` ; `ADMIN_MASTER` accède à l'écran. La garde attend la relecture de l'état (`GET /api/comptes/moi`, 1.2 RG20) : F5 ne redirige pas l'admin master. À l'ouverture, l'écran appelle `GET /api/administration/admins` : 401 redirige vers `/connexion?retour=%2Fadministration%2Fadmins`, 403 vers `/acces-refuse`. Sur `/administration`, un lien « Gérer les administrateurs » (`lien-gestion-admins`) n'est affiché que pour `ADMIN_MASTER` (absent pour `ADMIN`). Les gardes sont une aide d'affichage : la sécurité est celle de RG1 et RG9.
- **RG15** : formulaire de création (champs pseudo, mot de passe, confirmation). Contrôles côté client de confort uniquement (champs non vides, confirmation identique, 12 caractères minimum), jamais en remplacement du serveur : les `erreurs[].message` de l'API sont affichés tels quels sous le champ concerné (comme 1.1 RG16). Champs mot de passe de type `password`, `autocomplete="new-password"`. Après un succès ou une erreur, les champs mot de passe sont vidés ; après une erreur de pseudo, le pseudo est conservé ; après un succès, le pseudo est aussi vidé. Bouton désactivé pendant l'envoi. Le mot de passe n'est jamais stocké côté navigateur ni affiché dans la liste ou un message. Avant tout `POST`, le front appelle `GET /api/csrf` (1.2 RG15).
- **RG16** : liste. Après un succès (201), la liste est rechargée (`GET`) et affiche le nouvel admin ; un message de succès nomme le pseudo créé. Chaque ligne affiche le pseudo et la date de création ; liste vide : message dédié.

**Transverse**
- **RG17** : non-régression. Comportements de 1.1 à 1.4 inchangés : `GET /api/administration/acces` (204 pour `ADMIN` et `ADMIN_MASTER`), 404 d'un admin sur un chemin `/api/administration/` inexistant hors `/admins`, `POST /api/comptes` crée toujours un `COUREUR`, écran `/administration` (le message `administration-vide` reste affiché pour les deux rôles, un lien s'y ajoute pour l'admin master). Aucun changeset Liquibase (le `role` `ADMIN` est déjà autorisé par `0002-compte`) : `databasechangelog` reste `0002-compte`, `0003-admin-master-unique`. Aucune variable d'environnement ajoutée.
- **RG18** : architecture. Règles (rôle `ADMIN` fixé, validation, unicité, ordre de la liste) dans le domaine et les cas d'usage `CreerAdmin` et `ListerAdmins` (`comptes.application`) ; port `DepotComptes` étendu (liste par rôle) ; exception métier typée réutilisée pour le pseudo déjà utilisé. Le contrôleur (`comptes.exposition`) ne contient aucune logique ; la règle de sécurité est dans `comptes.infrastructure`. ArchUnit passe sans exception ; `courses` n'est pas touché.

## 3. Cas limites

- **Rôle insuffisant** : `ADMIN`, `BENEVOLE`, `COUREUR` : 403 `ACCES_REFUSE` sur `GET` et `POST` ; anonyme : 401 (RG1, RG9). Sans jeton CSRF : 403 `CSRF_INVALIDE` prioritaire pour le `POST` (RG6). Corps invalide envoyé par un `ADMIN` : 403, pas 400.
- **Pseudo aux limites** : 3 et 30 caractères acceptés, 2 et 31 refusés ; `"  Nadia  "` devient `Nadia` ; `a b`, `a@b` refusés (RG3).
- **Mot de passe aux limites** : 11 refusé, 12 accepté, 128 accepté, 129 refusé ; 12 espaces acceptés (RG4).
- **Pseudo déjà pris** : par un coureur, un admin ou l'admin master, `Alice`, `alice`, `PATRON` : 409 `PSEUDO_DEJA_UTILISE`, aucun Compte créé, le Compte existant n'est ni promu ni modifié (RG3).
- **Plusieurs violations** : pseudo `ab` et mot de passe de 11 caractères : un 400 à deux entrées. Pseudo existant et mot de passe trop court : 400, pas 409 (RG6).
- **Doublon concurrent** : deux `POST` simultanés avec le même pseudo : un 201, un 409 (RG3).
- **Champ `role` injecté** (`ADMIN_MASTER`, `COUREUR`) ou champ inconnu : ignoré, Compte `ADMIN` (RG2).
- **Corps vide, non JSON, mal typé** : 400 `CORPS_ILLISIBLE` sans écho ; `Content-Type` non JSON : 415 (RG6).
- **Liste** : aucun admin créé : `[]` et message d'écran dédié ; admin master, bénévoles et coureurs jamais listés ; deux admins créés dans la même microseconde : tri par pseudo normalisé (RG8, RG10).
- **Admin créé qui se connecte** : 200, rôle `ADMIN`, pas d'accès aux endpoints `/admins` (403) ; pseudo en casse différente accepté (RG12).
- **Session de l'admin master expirée** pendant la saisie : le `POST` répond 401, l'écran redirige vers `/connexion?retour=%2Fadministration%2Fadmins` (RG14). Jeton CSRF expiré : 403 `CSRF_INVALIDE`, message « La page a expiré, veuillez réessayer. » et nouveau `GET /api/csrf`.
- **Réseau ou 5xx** : message « Service indisponible, veuillez réessayer plus tard. » à la création ; message d'erreur de chargement pour la liste ; champs conservés sauf mots de passe (RG15).
- **Double clic** sur « Créer l'administrateur » : bouton désactivé pendant l'envoi ; si deux requêtes partent, la seconde reçoit 409 (RG15).
- **Rôle figé dans la session** : un `ADMIN` créé garde son rôle pour sa session (point ouvert 4).
- **Course, Boucle, Passage, Inscription, Abandon, Réintégration, `CORRECTION`, plusieurs Courses, Course non démarrée, bénévole non affecté** : non applicables en 1.5. Le rôle insuffisant est traité ci-dessus.

## 4. Contrat d'API

Toutes les erreurs sont des `ProblemDetail` (`application/problem+json`) : `{ "type": "about:blank", "title", "status", "detail", "instance", "code" }`, avec les formats de 1.1 (400, 409, CSRF) et 1.2 RG19 (401, 403 `ACCES_REFUSE`, 404).

### POST /api/administration/admins (nouveau)
- Rôle requis : `ADMIN_MASTER`. En-tête `X-XSRF-TOKEN` obligatoire. `Content-Type: application/json`.
- **Requête `CreerAdminRequete`** :

| Champ | Type | Obligatoire | Règle |
|---|---|---|---|
| `pseudo` | string | oui | RG3 (trim, 3 à 30, lettres/chiffres/`.`/`_`/`-`) |
| `motDePasse` | string | oui | RG4 (12 à 128 caractères) |

- **201 `CompteReponse`** : `{ id: string (UUID), pseudo: string, role: "ADMIN", creeLe: string (ISO-8601 UTC) }`. Pas d'en-tête `Location`. Jamais de `motDePasse`, de hash ni de `pseudoNormalise`.
- **400 validation** : `code` = `VALIDATION_ECHOUEE`, `title` = « Requête invalide », `detail` = « Certains champs sont invalides. », `erreurs` : tableau de `{ champ: "pseudo"|"motDePasse", code, message }` avec les codes et messages exacts de 1.1 (`PSEUDO_REQUIS`, `PSEUDO_LONGUEUR`, `PSEUDO_CARACTERES`, `MOT_DE_PASSE_REQUIS`, `MOT_DE_PASSE_TROP_COURT`, `MOT_DE_PASSE_TROP_LONG`).
- **400 corps illisible** : `code` = `CORPS_ILLISIBLE`, `title` = « Requête invalide », `detail` = « Le corps de la requête est illisible. ».
- **401** `NON_AUTHENTIFIE` : « Authentification requise » / « Vous devez être connecté. ».
- **403** `ACCES_REFUSE` : « Accès refusé » / « Vous n'avez pas les droits nécessaires. » (rôles `ADMIN`, `BENEVOLE`, `COUREUR`). **403** `CSRF_INVALIDE` : « Accès refusé » / « Jeton CSRF absent ou invalide. ».
- **409** `PSEUDO_DEJA_UTILISE` : « Conflit » / « Ce pseudo est déjà utilisé. ».
- **415** : `Content-Type` non JSON (`ProblemDetail` standard de Spring, `code` non garanti). 404 : non applicable.

### GET /api/administration/admins (nouveau)
- Rôle requis : `ADMIN_MASTER`. Aucun corps, aucun paramètre, pas de jeton CSRF.
- **200** : tableau de `CompteReponse` (`role` toujours `ADMIN`), trié selon RG8, `[]` si aucun.
- **401** `NON_AUTHENTIFIE` ; **403** `ACCES_REFUSE` (rôles `ADMIN`, `BENEVOLE`, `COUREUR`), mêmes corps que ci-dessus. 400, 404, 409 : non applicables.

### Autres chemins `/api/administration/admins/**`
- Aucun autre endpoint. Admin master : 404 `RESSOURCE_INTROUVABLE` (ou 405 selon la méthode). Autres identités : 401 / 403 (RG9, RG11).

### Endpoints existants
- Inchangés : `GET /api/administration/acces` (204 pour `ADMIN` et `ADMIN_MASTER`), `POST /api/connexion`, `GET /api/comptes/moi`, `POST /api/comptes`, `GET /api/csrf`, `POST /api/deconnexion` (RG17). `CompteReponse.role` peut valoir `ADMIN` en connexion.

### Schéma et configuration
- Aucun changement (RG17).

## 5. Écrans

### Gestion des administrateurs (`/administration/admins`, nouveau)
- **Accès** : `ADMIN_MASTER` uniquement (RG14).
- **Affiché** : titre `titre-admins` « Gestion des administrateurs » ; liste `liste-admins` avec, par admin, une ligne `ligne-admin` (pseudo, date de création) ; message `admins-vide` « Aucun administrateur pour le moment. » si la liste est vide ; formulaire (champs `admin-champ-pseudo`, `admin-champ-mot-de-passe`, `admin-champ-confirmation`, aide « 12 caractères minimum ») ; lien `lien-retour-administration` « Retour à l'administration ».
- **Actions** : créer un administrateur (`admin-bouton-creer`, libellé « Créer l'administrateur ») ; retour vers `/administration`.
- **Succès** : `admin-message-succes` « L'administrateur <pseudo> a été créé. », liste rechargée, formulaire vidé.
- **Messages d'erreur** :
  - Client : `admin-erreur-pseudo` « Le pseudo est obligatoire. » ; `admin-erreur-mot-de-passe` « Le mot de passe est obligatoire. » / « Le mot de passe doit faire au moins 12 caractères. » ; `admin-erreur-confirmation` « Les mots de passe ne correspondent pas. ».
  - 400 `VALIDATION_ECHOUEE` : chaque `erreurs[].message` sous le champ concerné. 409 : « Ce pseudo est déjà utilisé. » sous le pseudo.
  - 403 `CSRF_INVALIDE` : « La page a expiré, veuillez réessayer. » (`admin-erreur-generale`), nouveau `GET /api/csrf`. 5xx ou réseau : « Service indisponible, veuillez réessayer plus tard. » (`admin-erreur-generale`). 401 et 403 `ACCES_REFUSE` redirigent (RG14).
  - Échec du chargement de la liste (5xx, réseau) : `admins-erreur` « Impossible de charger la liste des administrateurs. Réessayez plus tard. » ; le formulaire reste utilisable.

### Espace d'administration (`/administration`) : évolution
- Pour `ADMIN_MASTER` : lien `lien-gestion-admins` « Gérer les administrateurs » vers `/administration/admins`. Pour `ADMIN` : lien absent. `titre-administration`, `administration-role`, `administration-vide` inchangés (RG17).

### Autres écrans
- `/connexion`, `/acces-refuse`, en-tête : inchangés. Un admin créé arrive sur `/administration` après connexion (1.4 RG15).

## 6. Critères d'acceptation

Valeurs de référence : admin master `Patron` / `mot-de-passe-patron-1` ; coureur `Alice` / `un-mot-de-passe-12` ; admin créé `Nadia` / `mot-de-passe-admin-1` (20 caractères) ; mots de passe de test `secret-de-test-123` (18, valide) et `court-secre` (11, invalide). `Clock` fixe. Dépôt en mémoire et encodeur factice en unitaire. En intégration, `ADMIN_MASTER`, `BENEVOLE` et `COUREUR` sont insérés directement par le dépôt (pas d'endpoint de création de bénévole avant 1.6) ; les `ADMIN` des tests de rôle sont créés par l'API (admin master) ou par le dépôt.

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné un dépôt vide, un `Clock` fixe et un encodeur factice, quand `CreerAdmin` reçoit `"  Nadia  "` / `mot-de-passe-admin-1`, alors un Compte de rôle `ADMIN` est enregistré (pseudo `Nadia`, `pseudoNormalise` `nadia`, `id` non nul, `creeLe` = instant du `Clock`, empreinte de l'encodeur, jamais le mot de passe) et renvoyé ; `Compte.creerAdmin(...)` donne le rôle `ADMIN` et `peutSeConnecter()` vrai ; `Compte.creerCoureur(...)` reste `COUREUR` (RG2, RG5) | unitaire |
| CA2 | Quand `CreerAdmin` reçoit pseudo `ab`, `a b`, `""`, 31 caractères, ou mot de passe `court-secre`, 129 caractères, absent, alors une exception de validation porte le code de 1.1 correspondant ; pseudo `ab` et mot de passe `court-secre` ensemble : 2 violations ; pseudo de 3 et de 30 caractères, mot de passe de 12 et de 128 caractères : Compte créé ; aucun Compte enregistré en cas de violation (RG3, RG4) | unitaire |
| CA3 | Étant donné en dépôt un coureur `Alice`, un `ADMIN` `Nadia` et l'`ADMIN_MASTER` `Patron`, quand `CreerAdmin` reçoit `ALICE`, `nadia`, `PATRON` (mot de passe valide), alors l'exception « pseudo déjà utilisé » est levée trois fois, le dépôt est inchangé (aucun rôle modifié), l'encodeur n'est pas appelé pour le hachage d'un Compte refusé (RG3) | unitaire |
| CA4 | Étant donné un dépôt dont `enregistrer` lève la violation d'unicité du pseudo (concurrence), quand `CreerAdmin` s'exécute, alors l'exception « pseudo déjà utilisé » est levée (RG3) | unitaire |
| CA5 | Étant donné `Alice` en dépôt, quand `CreerAdmin` reçoit `alice` avec `court-secre`, alors l'exception de validation (mot de passe trop court) est levée, pas celle du pseudo déjà utilisé (RG6) | unitaire |
| CA6 | Quand les exceptions de CA2 à CA4 sont levées avec le mot de passe `secret-de-test-123` ou `court-secre`, alors ni leur message, ni leur `toString()`, ni celui de la commande du cas d'usage ne contiennent le mot de passe ; la commande affiche `motDePasse=masqué` (RG7) | unitaire |
| CA7 | Étant donné en dépôt `Patron` (admin master), `Zoe` (`ADMIN`, créé à 10:00), `Nadia` (`ADMIN`, créé à 10:00), `Marc` (`ADMIN`, créé à 09:00), un `BENEVOLE` et `Alice` (coureur), quand `ListerAdmins` s'exécute, alors le résultat est exactement `Marc`, `Nadia`, `Zoe` dans cet ordre ; sur un dépôt sans `ADMIN`, la liste est vide (RG8, RG10) | unitaire |
| CA8 | Étant donné un cas d'usage `CreerAdmin` sans appelant de rôle (le contrôle de rôle est en exposition), quand on inspecte ses signatures, alors aucune dépendance vers Spring ni vers un DTO ; les règles ArchUnit passent avec les classes ajoutées : domaine sans Spring/JPA/Jackson, cas d'usage dans `application`, contrôleur sans accès à l'infrastructure, règle de sécurité dans `infrastructure`, `courses` indépendant (RG18) | unitaire |
| CA9 | Quand `./mvnw test`, alors les tests ArchUnit existants passent sans aucune exception ajoutée pour 1.5 (RG18) | unitaire |
| CA10 | Étant donné `Patron` connecté et un jeton CSRF, quand `POST /api/administration/admins` reçoit `{pseudo:"Nadia", motDePasse:"mot-de-passe-admin-1", role:"ADMIN_MASTER"}`, alors 201, corps `{id (UUID), pseudo:"Nadia", role:"ADMIN", creeLe}` sans `motDePasse`, hash ni `pseudoNormalise` ; la table `compte` contient `Nadia` en rôle `ADMIN` avec une empreinte `$argon2id$...` différente du mot de passe, et toujours un seul `ADMIN_MASTER` (RG2, RG5, RG7, RG10) | intégration |
| CA11 | Étant donné quatre sessions (`ADMIN` `Nadia`, `BENEVOLE`, `Alice`, anonyme) et un jeton CSRF valide, quand chacun envoie `POST /api/administration/admins` valide, alors anonyme 401 `NON_AUTHENTIFIE`, les trois autres 403 `ACCES_REFUSE` (`application/problem+json`, `title`, `status`, `detail`, `code` de la section 4), et aucune ligne n'est créée (RG1, RG9) | intégration |
| CA12 | Même population, quand `GET /api/administration/admins`, alors anonyme 401, `Nadia` (ADMIN) 403, bénévole 403, `Alice` 403, `Patron` 200 ; `GET /api/administration/admins/inexistant` : 401 / 403 / 403 / 403 pour les mêmes, 404 `RESSOURCE_INTROUVABLE` pour `Patron` ; `GET /api/administration/acces` reste 204 pour `Nadia` et `Patron` (RG1, RG8, RG9, RG17) | intégration |
| CA13 | Quand `POST /api/administration/admins` est envoyé sans en-tête `X-XSRF-TOKEN` puis avec un en-tête différent du cookie, alors 403 `CSRF_INVALIDE` pour `Patron`, `Nadia` et l'anonyme ; avec jeton valide, un `ADMIN` envoyant un corps `{}` reçoit 403 `ACCES_REFUSE` (pas 400) et l'anonyme avec corps `{}` reçoit 401 (RG6, RG9) | intégration |
| CA14 | Étant donné `Patron` et un jeton CSRF, quand `POST` avec `pseudo=ab` et `motDePasse=court-secre`, alors 400 `VALIDATION_ECHOUEE`, `erreurs` contient exactement `PSEUDO_LONGUEUR` (champ pseudo) et `MOT_DE_PASSE_TROP_COURT` (champ motDePasse), le corps ne contient pas `court-secre`, aucune ligne créée ; avec corps `{}` : `PSEUDO_REQUIS` et `MOT_DE_PASSE_REQUIS` ; avec un corps non JSON : 400 `CORPS_ILLISIBLE` ; avec `Content-Type: text/plain` : 415 ; mot de passe de 12 et de 128 caractères : 201, de 129 : 400 `MOT_DE_PASSE_TROP_LONG` (RG3, RG4, RG6, RG7) | intégration |
| CA15 | Étant donné `Alice` (coureur), `Patron` et un `ADMIN` `Nadia`, quand `Patron` envoie `POST` avec pseudo `alice`, puis `PATRON`, puis `NADIA` (mot de passe valide), alors 409 `PSEUDO_DEJA_UTILISE` (« Ce pseudo est déjà utilisé. ») trois fois, aucun Compte ajouté, `Alice` reste `COUREUR` ; avec `alice` et `court-secre` : 400 et non 409 (RG3, RG6) | intégration |
| CA16 | Étant donné `Patron` et un jeton CSRF, quand deux `POST` simultanés ont le même pseudo `Concurrent`, alors exactement un 201 et un 409 `PSEUDO_DEJA_UTILISE`, jamais de 500, une seule ligne en base (RG3) | intégration |
| CA17 | Étant donné `Patron`, un bénévole, `Alice` et trois `ADMIN` créés par l'API (`Marc`, `Nadia`, `Zoe`), quand `Patron` appelle `GET /api/administration/admins`, alors 200 et un tableau de 3 éléments dans l'ordre de création (`Marc`, `Nadia`, `Zoe`), chacun `{id, pseudo, role:"ADMIN", creeLe}` sans hash ni `pseudoNormalise` ; sur une base sans `ADMIN`, 200 `[]` (RG8, RG10) | intégration |
| CA18 | Étant donné `Nadia` créée par `Patron` via l'API, quand `POST /api/connexion` avec `nadia` / `mot-de-passe-admin-1`, alors 200 `{pseudo:"Nadia", role:"ADMIN"}` ; avec cette session `GET /api/administration/acces` répond 204, `GET` et `POST /api/administration/admins` répondent 403 `ACCES_REFUSE` ; la limitation de 1.3 s'applique (5 échecs puis 429 `TENTATIVES_EXCESSIVES`) (RG1, RG12) | intégration |
| CA19 | Étant donné un journal capturé, quand `Patron` crée `Nadia` avec `secret-de-test-123` puis qu'une création échoue (`court-secre`, doublon), alors aucune ligne de niveau quelconque ne contient `secret-de-test-123` ni `court-secre`, aucune ligne INFO ou supérieure ne contient `Nadia`, et la ligne INFO de création contient l'identifiant du Compte créé et celui de l'admin master (RG7, RG13) | intégration |
| CA20 | Étant donné la suite d'intégration de 1.1 à 1.4, quand elle s'exécute, alors elle passe sans changement d'attente (dont `databasechangelog` = `["0002-compte", "0003-admin-master-unique"]`) ; `POST /api/comptes` avec `role:"ADMIN"` crée un `COUREUR` ; `PUT`, `PATCH` et `DELETE /api/administration/admins/<id>` par `Patron` répondent 404 ou 405 et le Compte `Nadia` reste intact (RG11, RG17) | intégration |
| CA21 | Étant donné `Patron` (identifiants lus dans l'environnement du test), quand il se connecte, clique sur `lien-gestion-admins` depuis `/administration` (où `administration-vide` reste visible), alors l'URL devient `/administration/admins`, `titre-admins` affiche « Gestion des administrateurs », aucun `ligne-admin` `admin-<suffixe>` n'est présent ; après saisie de `admin-<suffixe>` / `mot-de-passe-admin-1` (deux fois) et clic sur `admin-bouton-creer`, `admin-message-succes` affiche « L'administrateur admin-<suffixe> a été créé. », une `ligne-admin` contient `admin-<suffixe>`, les trois champs sont vides, et `GET /api/administration/admins` contient ce pseudo avec `role:"ADMIN"` sans champ de mot de passe ; `lien-retour-administration` ramène à `/administration` (RG1, RG8, RG14, RG15, RG16, RG17) | E2E |
| CA22 | Étant donné l'admin `admin-<suffixe>` créé par l'API par `Patron`, quand on se connecte avec lui (pseudo saisi en majuscules), alors l'URL devient `/administration`, `administration-role` affiche « Administrateur », `lien-gestion-admins` est absent ; en ouvrant `/administration/admins` l'URL devient `/acces-refuse` avec « Accès refusé » ; `GET /api/administration/admins` avec sa session répond 403 et `POST` répond 403 (RG1, RG9, RG12, RG14) | E2E |
| CA23 | Étant donné `Patron` sur `/administration/admins`, quand il valide le formulaire vide, saisit un mot de passe de 11 caractères, des confirmations différentes, alors `admin-erreur-pseudo` « Le pseudo est obligatoire. », `admin-erreur-mot-de-passe` « Le mot de passe doit faire au moins 12 caractères. » et `admin-erreur-confirmation` « Les mots de passe ne correspondent pas. » s'affichent sans envoi ; avec un pseudo `ab` valide côté client, `admin-erreur-pseudo` affiche le message serveur de longueur, le pseudo reste saisi, les mots de passe sont vidés ; avec le pseudo d'un coureur existant (`coureur-<suffixe>` en casse différente), `admin-erreur-pseudo` affiche « Ce pseudo est déjà utilisé. » et aucune `ligne-admin` n'est ajoutée (RG3, RG4, RG15) | E2E |
| CA24 | Étant donné un anonyme, quand il ouvre `/administration/admins`, alors l'URL devient `/connexion?retour=%2Fadministration%2Fadmins` ; après connexion de `Patron`, l'URL devient `/administration/admins` et un F5 la conserve ; en refaisant avec un coureur `coureur-<suffixe>`, l'URL finale est `/acces-refuse` ; `lien-gestion-admins` est absent de `/administration` pour un admin non master et présent pour `Patron` (RG14) | E2E |
| CA25 | Étant donné `Patron` sur `/administration/admins`, quand la réponse de `GET /api/administration/admins` est interceptée en 500, alors `admins-erreur` affiche « Impossible de charger la liste des administrateurs. Réessayez plus tard. » sans code HTTP, et le formulaire reste utilisable ; quand la réponse du `POST` est interceptée en 500, alors `admin-erreur-generale` affiche « Service indisponible, veuillez réessayer plus tard. », pseudo conservé, mots de passe vidés ; en 401 sur la liste, l'URL devient `/connexion?retour=%2Fadministration%2Fadmins` ; en 403 `ACCES_REFUSE`, `/acces-refuse` (RG14, RG15, RG16) | E2E |

Répartition : unitaire 9 (CA1 à CA9), intégration 11 (CA10 à CA20), E2E 5 (CA21 à CA25). Total 25.

Couverture des RG : RG1 CA11/CA12/CA18/CA21/CA22, RG2 CA1/CA10, RG3 CA2/CA3/CA4/CA14/CA15/CA16/CA23, RG4 CA2/CA14/CA23, RG5 CA1/CA10, RG6 CA5/CA13/CA14/CA15, RG7 CA6/CA10/CA14/CA19, RG8 CA7/CA12/CA17/CA21, RG9 CA11/CA12/CA13/CA22, RG10 CA7/CA10/CA17, RG11 CA20, RG12 CA18/CA22, RG13 CA19, RG14 CA21 à CA25, RG15 CA21/CA23/CA25, RG16 CA21/CA25, RG17 CA12/CA20/CA21, RG18 CA8/CA9. Écrans : Gestion des administrateurs (CA21 à CA25), Espace d'administration évolué (CA21, CA24), tous en E2E.

Notes pour les testeurs : les E2E créent leurs coureurs et leurs admins par l'API avec un suffixe aléatoire (helpers de `e2e/tests/aide-connexion.ts`, à étendre d'un helper « créer un admin » appelant `POST /api/administration/admins` avec la session de l'admin master) et lisent les identifiants de l'admin master dans `E2E_ADMIN_MASTER_PSEUDO` et `E2E_ADMIN_MASTER_MOT_DE_PASSE` (comme en 1.4). Les admins créés par les E2E ne peuvent pas être supprimés (RG11) : les tests utilisent des pseudos uniques et n'attendent jamais une liste exacte. Les rôles `BENEVOLE` restent testés en intégration par insertion directe jusqu'à 1.6.

## 7. Tester à la main

Prérequis : stack de 1.4 lancée (`docker compose up -d --build`, `base`, `api`, `web` `healthy`), `.env` avec `ADMIN_MASTER_PSEUDO=Patron` et `ADMIN_MASTER_MOT_DE_PASSE=mot-de-passe-patron-1`, http://localhost. Aucune migration ni variable nouvelle.

1. Connexion `Patron` / `mot-de-passe-patron-1` : arrivée sur `/administration`, « Administrateur master », « Aucune fonctionnalité d'administration pour le moment. » et un lien « Gérer les administrateurs ».
2. Clic sur le lien : `/administration/admins`, titre « Gestion des administrateurs », « Aucun administrateur pour le moment. » (sur une base neuve) et le formulaire.
3. Contrôles du formulaire : valider vide : trois messages d'obligation ; mot de passe `court-secre` : « au moins 12 caractères » ; confirmation différente : « Les mots de passe ne correspondent pas. » ; pseudo `ab` avec mot de passe valide : message serveur sur la longueur, pseudo conservé, mots de passe vidés.
4. Création : pseudo `Nadia`, mot de passe `mot-de-passe-admin-1` deux fois, « Créer l'administrateur » : message « L'administrateur Nadia a été créé. », une ligne `Nadia` avec sa date, formulaire vidé. Créer ensuite `Marc` : la liste affiche `Nadia` puis `Marc` (ordre de création).
5. Doublons : créer `nadia` (casse différente) : « Ce pseudo est déjà utilisé. » ; créer `PATRON` : même message ; après avoir créé un coureur `Alice` par « Créer un compte », créer l'admin `ALICE` : même message.
6. Base : `docker compose exec base psql -U backyard -d backyard -c "select pseudo, role, left(empreinte_mot_de_passe, 10) from compte order by cree_le"` : `Patron` `ADMIN_MASTER`, `Nadia` et `Marc` `ADMIN`, empreintes commençant par `$argon2id$`.
7. Se déconnecter, se connecter avec `nadia` / `mot-de-passe-admin-1` : arrivée sur `/administration`, « Administrateur », message « Aucune fonctionnalité... », **pas** de lien « Gérer les administrateurs ». Saisir http://localhost/administration/admins : redirection vers « Accès refusé ».
8. API pour l'admin non master (cookie CSRF comme 1.2 étape 15) : après connexion de `Nadia` dans un jar, `curl -s -i -b /tmp/jar http://localhost/api/administration/admins` : `403` `"code":"ACCES_REFUSE"` ; `POST` du même chemin avec jeton CSRF et `{"pseudo":"Intrus1","motDePasse":"un-mot-de-passe-12"}` : `403`, aucune ligne `Intrus1` en base. Sans session : `401` `NON_AUTHENTIFIE`.
9. API pour l'admin master (jar `Patron`) : `GET /api/administration/admins` : `200` avec `Nadia` et `Marc`, sans `Patron` ni `Alice`, sans hash. `POST` avec `{"pseudo":"Eve12","motDePasse":"un-mot-de-passe-12","role":"ADMIN_MASTER"}` : `201` avec `"role":"ADMIN"` ; `psql` montre toujours un seul `ADMIN_MASTER`.
10. Coureur : connecté en `Alice`, ouvrir `/administration/admins` : « Accès refusé ». Anonyme : redirection vers la connexion avec `retour`, puis après connexion de `Patron` arrivée sur l'écran de gestion.
11. Persistance et journaux : `docker compose restart api`, reconnexion de `Patron` : la liste contient toujours `Nadia`, `Marc`, `Eve12`. `docker compose logs api | grep -c -e "mot-de-passe-admin-1" -e "court-secre" -e "un-mot-de-passe-12"` renvoie `0` ; `docker compose logs api | grep "Admin créé"` montre des identifiants, sans pseudo.

## 8. Points ouverts

Bloquants : aucun.

Non bloquants (position par défaut retenue, à confirmer) :
1. **Liste : admin master exclu.** « Liste les admins » interprété comme les Comptes `ADMIN` seuls (RG8, RG10). Alternative : inclure l'admin master avec son rôle, pour une vue complète des comptes à privilèges.
2. **Pas de modification ni de suppression d'un admin.** Le besoin et la roadmap ne prévoient ni désactivation, ni suppression, ni réinitialisation du mot de passe d'un admin (1.6 couvre seulement le changement de son propre mot de passe). Un admin créé par erreur reste donc en base ; contournement SQL. À trancher : ajouter l'action à un incrément ultérieur (par exemple 1.6 ou 3.6), ou l'accepter. Rappel hérité de 1.4 : l'admin master reste non supprimable.
3. **Liste sans pagination ni recherche** : le nombre d'admins est supposé très faible (une poignée).
4. **Rôle figé dans la session** (12 h) : un admin créé garde ses droits jusqu'à la fin de sa session, même après une éventuelle suppression future ; même cause que 1.2 point 6 et 1.4 point 6, à traiter au plus tard avec la première opération de suppression d'un Compte privilégié.
5. **Mot de passe initial choisi par l'admin master** : il le communique lui-même à l'admin créé. Le changement par l'admin est prévu en 1.6 ; aucun changement forcé à la première connexion (non demandé). Alternative : mot de passe temporaire à changer à la première connexion.
6. **Champ de confirmation du mot de passe** : présent dans l'écran seulement, comme en 1.1 (RG15). À confirmer pour un écran d'administration.
7. **Pseudo partagé avec les coureurs** : un pseudo déjà pris par un coureur ne peut pas devenir un admin (409). Alternative : aucune retenue, cohérent avec « pseudo unique » de CLAUDE.md.
8. **Endpoint technique `GET /api/administration/acces`** conservé (1.4 point 10) : utilisé par l'écran `/administration`. Il pourra être retiré quand un autre appel admin sert de contrôle serveur.
9. **Noms** : endpoints `/api/administration/admins` et chemin d'écran `/administration/admins`, identifiants `data-testid` préfixés `admin-` pour éviter toute collision avec les écrans 1.1 et 1.2. À confirmer.
10. **Taille de l'incrément** : environ 280 lignes de production, sous la cible de 400 : pas de découpage.
11. **Nom du fichier** : la consigne parlait de `incrementN.md` ; la convention de `CLAUDE.md` (`increment-X.Y.md`) est suivie.
