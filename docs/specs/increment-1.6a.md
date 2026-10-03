# Increment 1.6a : Créer et lister les comptes bénévoles, accueil bénévole

Les admins (`ADMIN` et `ADMIN_MASTER`) créent et listent les Comptes `BENEVOLE` depuis l'espace d'administration (1.4, 1.5). Un Compte `BENEVOLE` qui se connecte arrive sur un écran d'accueil bénévole vide (le scan arrive aux jalons 4 et 5). Cet incrément est la première moitié de l'ancien incrément 1.6 ; la seconde (« Mon compte », changement de mot de passe) est 1.6b et ne dépend pas de 1.6a, sauf pour la préparation de ses tests (Comptes bénévoles créés par l'API de 1.6a).

Taille estimée : environ 230 lignes de production (domaine et cas d'usage ~70 : `CreerBenevole`, `ListerBenevoles`, `Compte.creerBenevole(...)`, extension du port `DepotComptes` (liste par rôle) ; endpoints et DTO ~40 ; front ~120). Aucun changeset Liquibase, aucune nouvelle variable d'environnement.

## 1. Périmètre

**Inclus**
- Cas d'usage `CreerBenevole` et `ListerBenevoles`, `Compte.creerBenevole(...)` (contexte `comptes`), en réutilisant la logique commune de création de `CreerAdmin` (`CreationCompte`, `Pseudo`, `MotDePasse`, `EncodeurMotDePasse`) : aucune règle de pseudo, de mot de passe ou d'unicité n'est réécrite.
- Endpoints `POST` et `GET /api/administration/benevoles`, couverts par la règle existante `/api/administration/**` (`ADMIN`, `ADMIN_MASTER`, 1.4 RG13) : aucune nouvelle règle de sécurité.
- Front : écran `/administration/benevoles` (liste + formulaire) et lien depuis `/administration` ; écran `/benevole` (accueil bénévole vide) et lien « Espace bénévole » dans l'en-tête ; redirection d'un bénévole après connexion vers `/benevole` (modifie 1.4 RG15 pour le rôle `BENEVOLE`).
- Réutilisation des rôles, de la session, du CSRF, du format `ProblemDetail`, de `CompteReponse` (1.1 à 1.5).

**Exclu**
- Changement de mot de passe, écran « Mon compte » et lien « Mon compte » de l'en-tête : **1.6b**. Jusqu'à 1.6b, un bénévole créé ne peut pas changer son mot de passe.
- Affectation d'un bénévole à une Course : 2.x (les bénévoles créés ne sont affectés à rien). Scan des passages, file hors ligne : 4.x et 5.x. L'écran `/benevole` est vide.
- Modification, désactivation, suppression d'un Compte `BENEVOLE`, réinitialisation par un admin de son mot de passe : aucun incrément ne les prévoit (point ouvert 1).
- Changement forcé du mot de passe à la première connexion : non demandé (point ouvert 2).
- Pagination, recherche, tri paramétrable de la liste des bénévoles : point ouvert 3.
- Contrôle du rôle réel en base à chaque requête (le rôle est celui de la session) : point ouvert 4 (hérité de 1.2, 1.4, 1.5).
- Suppression d'un compte, anonymisation : 3.6 (l'admin master reste non supprimable, note de 1.4 RG11).
- Toute fonctionnalité de Course, Inscription, Passage : 2.x et suivants.

## 2. Règles de gestion

**Création et liste des bénévoles**
- **RG1** : `POST` et `GET /api/administration/benevoles` sont accessibles aux rôles `ADMIN` et `ADMIN_MASTER` (règle `/api/administration/**` de 1.4 RG13, inchangée et non dupliquée). Un `BENEVOLE` ou un `COUREUR` reçoit 403 `ACCES_REFUSE` ; un anonyme reçoit 401 `NON_AUTHENTIFIE` ; le refus précède le 404 pour tout chemin sous `/api/administration/benevoles`. Contrairement à 1.5, l'`ADMIN` non master a accès.
- **RG2** : le Compte créé a toujours le rôle `BENEVOLE`. Aucun champ du corps ne permet de choisir le rôle : un champ `role` envoyé est ignoré (comme 1.1 RG1 et 1.5 RG2).
- **RG3** : le pseudo suit `Pseudo` de 1.1 RG2 (`trim`, 3 à 30 caractères, lettres Unicode, chiffres, `.`, `_`, `-`). Unicité identique à 1.1 RG3 et 1.5 RG3 : insensible à la casse, **tous rôles confondus**, garantie par le cas d'usage et par la contrainte unique sur `pseudo_normalise` (conflit concurrent : 409, jamais 500). Pseudo conservé tel que saisi après `trim`.
- **RG4** : le mot de passe suit `MotDePasse` de 1.1 RG4 : 12 caractères minimum, 128 maximum (points de code), sans `trim`, aucune règle de composition.
- **RG5** : un Compte créé a : `id` (UUID généré côté application), `pseudo`, `pseudoNormalise`, empreinte Argon2id (`EncodeurMotDePasse` de 1.1), `role = BENEVOLE`, `creeLe` = `Clock` injecté tronqué à la microseconde. Réponse 201 : `CompteReponse` (1.1), jamais de mot de passe, de hash ni de `pseudoNormalise`.
- **RG6** : ordre de contrôle de `POST /api/administration/benevoles` : CSRF (403 `CSRF_INVALIDE`), authentification (401), rôle (403 `ACCES_REFUSE`), lecture du corps (415 / 400 `CORPS_ILLISIBLE`), validation (400 `VALIDATION_ECHOUEE`, toutes les violations), unicité (409 `PSEUDO_DEJA_UTILISE`). Le 400 est prioritaire sur le 409. Le 409 `DEJA_CONNECTE` de 1.2 ne s'applique pas (l'endpoint exige une session).
- **RG7** : confidentialité. Le mot de passe en clair n'apparaît ni dans les réponses, ni dans les journaux (tout niveau), exceptions, `ProblemDetail`, `toString()`. Le DTO de requête et la commande du cas d'usage masquent la valeur (`CreerBenevoleRequete[pseudo=..., motDePasse=masqué]`). Les corps de requête ne sont jamais journalisés ; les messages d'erreur ne reprennent jamais la valeur saisie.
- **RG8** : `GET /api/administration/benevoles` renvoie tous les Comptes de rôle `BENEVOLE`, triés par `creeLe` croissant puis `pseudoNormalise` croissant. Un `ADMIN` et l'`ADMIN_MASTER` voient la même liste (pas de notion de « créé par » dans la réponse). Les autres rôles ne sont jamais listés. Liste vide : `[]`. Chaque élément est un `CompteReponse`.
- **RG9** : aucune modification ni suppression de bénévole n'est exposée : `PUT`, `PATCH`, `DELETE` sur `/api/administration/benevoles/{id}` n'existent pas (404 ou 405 pour un admin, jamais 2xx).
- **RG10** : le bénévole créé se connecte par `POST /api/connexion` (1.2, limitation de 1.3, pseudo insensible à la casse) ; `CompteReponse.role` vaut `BENEVOLE`, la session porte `ROLE_BENEVOLE`. Il n'a accès à aucun endpoint `/api/administration/**` (403). Aucune Course n'existant pour lui, son espace est vide.
- **RG11** : journal. À la création, une ligne INFO « Bénévole créé (compte `<id>` par `<id de l'admin>`) » : identifiants uniquement, ni pseudo ni mot de passe. Aucune ligne INFO ou supérieure ne contient de pseudo.

**Front**
- **RG12** : routes, gardes et en-tête. Toutes les gardes attendent la relecture de l'état (`GET /api/comptes/moi`, 1.2 RG20) : F5 ne redirige pas à tort. Une garde est une aide d'affichage, la sécurité est côté serveur.

  | Route | Anonyme | Rôles autorisés | Autres rôles |
  |---|---|---|---|
  | `/administration/benevoles` | `/connexion?retour=%2Fadministration%2Fbenevoles` | `ADMIN`, `ADMIN_MASTER` | `/acces-refuse` |
  | `/benevole` | `/connexion?retour=%2Fbenevole` | `BENEVOLE` | `/acces-refuse` (admins compris, point ouvert 5) |

  En-tête (1.2 RG21, 1.4 RG17) : pour `BENEVOLE`, lien « Espace bénévole » (`lien-espace-benevole`, vers `/benevole`) ; le lien « Administration » reste réservé aux admins. Sur `/administration`, un lien « Gérer les bénévoles » (`lien-gestion-benevoles`) est affiché pour `ADMIN` **et** `ADMIN_MASTER` ; le lien « Gérer les administrateurs » reste réservé à l'admin master (1.5).
- **RG13** : écran `/administration/benevoles`. À l'ouverture, appel de `GET /api/administration/benevoles` : 401 redirige vers `/connexion?retour=%2Fadministration%2Fbenevoles`, 403 vers `/acces-refuse`. Formulaire (pseudo, mot de passe, confirmation) : mêmes contrôles de confort, mêmes règles de vidage des champs, `autocomplete="new-password"`, bouton désactivé pendant l'envoi, `GET /api/csrf` avant le `POST`, messages serveur affichés tels quels, que 1.5 RG15. Après un 201, liste rechargée et message de succès nommant le pseudo. Lignes : pseudo et date de création ; liste vide : message dédié. Le mot de passe n'est jamais stocké côté navigateur.
- **RG14** : écran `/benevole`. Titre et message « Aucune course à scanner pour le moment. », aucune action. Destination après connexion (modifie 1.4 RG15) : `retour` valide s'il existe ; sinon `/administration` pour `ADMIN` et `ADMIN_MASTER` ; sinon **`/benevole` pour `BENEVOLE`** ; sinon `/` pour `COUREUR`. Un bénévole avec `retour=/administration` va sur `/acces-refuse` (comportement de 1.4 RG16 pour un rôle non admin).

**Transverse**
- **RG15** : non-régression. Comportements de 1.1 à 1.5 inchangés : connexion, limitation, `GET /api/comptes/moi`, `POST /api/comptes` (crée un `COUREUR`), `GET /api/administration/acces`, endpoints `/api/administration/admins` (toujours `ADMIN_MASTER` seul), écrans `/administration` et `/administration/admins`. Aucun changeset Liquibase (`databasechangelog` reste `0002-compte`, `0003-admin-master-unique`) ; aucune variable d'environnement ajoutée.
- **RG16** : architecture. Règles (rôle `BENEVOLE` fixé, validation, unicité, tri) dans le domaine et les cas d'usage `CreerBenevole` et `ListerBenevoles` (`comptes.application`) ; port `DepotComptes` étendu (liste par rôle) ; exception métier typée (pseudo déjà utilisé, déjà existante). Contrôleurs sans logique. ArchUnit passe sans exception ; `courses` n'est pas touché.

## 3. Cas limites

- **Rôle insuffisant** : `COUREUR` et `BENEVOLE` : 403 `ACCES_REFUSE` sur `GET` et `POST` ; anonyme : 401 ; sans CSRF : 403 `CSRF_INVALIDE` prioritaire pour le `POST` ; corps invalide envoyé par un non-admin : 403, pas 400. `ADMIN` non master : accepté (RG1).
- **Pseudo aux limites** : 3 et 30 caractères acceptés, 2 et 31 refusés ; `"  Léo  "` devient `Léo` ; `a b`, `a@b` refusés (RG3).
- **Mot de passe aux limites** : 11 refusé, 12 accepté, 128 accepté, 129 refusé ; 12 espaces acceptés (RG4).
- **Pseudo déjà pris** par un coureur, un admin, l'admin master ou un bénévole, `ALICE`, `nadia`, `PATRON`, `léo` : 409 `PSEUDO_DEJA_UTILISE`, aucun Compte modifié ni promu (RG3).
- **Plusieurs violations** : pseudo `ab` et mot de passe de 11 caractères : un 400 à deux entrées. Pseudo existant et mot de passe court : 400, pas 409 (RG6).
- **Doublon concurrent** : deux `POST` simultanés, même pseudo : un 201, un 409 (RG3).
- **Champ `role` injecté** (`ADMIN`, `ADMIN_MASTER`) ou inconnu : ignoré, Compte `BENEVOLE` (RG2).
- **Liste** : aucun bénévole : `[]` ; admins, admin master, coureurs jamais listés ; même `creeLe` : tri par pseudo normalisé (RG8).
- **Session expirée pendant la saisie** : le `POST` répond 401, redirection vers la connexion avec `retour` (RG12, RG13).
- **Jeton CSRF expiré** : 403 `CSRF_INVALIDE`, message « La page a expiré, veuillez réessayer. » et nouveau `GET /api/csrf` (RG13).
- **Réseau ou 5xx** : « Service indisponible, veuillez réessayer plus tard. » ; champs conservés sauf mots de passe ; liste en erreur : message dédié (RG13).
- **Double clic** : bouton désactivé pendant l'envoi ; une seconde requête de création reçoit 409 (RG13).
- **Bénévole, rôle admin visé** : un bénévole qui ouvre `/administration` ou `/administration/benevoles` : `/acces-refuse` ; un admin ou coureur qui ouvre `/benevole` : `/acces-refuse` (RG12).
- **Course, Boucle, Passage, Inscription, Abandon, Réintégration, `CORRECTION`, `SCAN`, plusieurs Courses, Course non démarrée, bénévole non affecté à la Course** : non applicables en 1.6a (aucune Course n'existe). Le bénévole non affecté est traité à l'écran par le message vide de `/benevole` (RG14) ; l'affectation relève de 2.x.
- **Changement de mot de passe, compte disparu, sessions multiples, blocage commun** : relèvent de 1.6b.

## 4. Contrat d'API

Toutes les erreurs sont des `ProblemDetail` (`application/problem+json`) : `{ "type": "about:blank", "title", "status", "detail", "instance", "code" }`, avec les formats de 1.1 (400, 409, CSRF) et 1.2 RG19 (401, 403 `ACCES_REFUSE`, 404).

### POST /api/administration/benevoles (nouveau)
- Rôle requis : `ADMIN` ou `ADMIN_MASTER`. En-tête `X-XSRF-TOKEN` obligatoire. `Content-Type: application/json`.
- **Requête `CreerBenevoleRequete`** :

| Champ | Type | Obligatoire | Règle |
|---|---|---|---|
| `pseudo` | string | oui | RG3 (trim, 3 à 30, lettres/chiffres/`.`/`_`/`-`) |
| `motDePasse` | string | oui | RG4 (12 à 128 caractères) |

- **201 `CompteReponse`** : `{ id: string (UUID), pseudo: string, role: "BENEVOLE", creeLe: string (ISO-8601 UTC) }`. Pas d'en-tête `Location`. Jamais de `motDePasse`, de hash ni de `pseudoNormalise`.
- **400 validation** : `code` `VALIDATION_ECHOUEE`, `title` « Requête invalide », `detail` « Certains champs sont invalides. », `erreurs` : tableau de `{ champ: "pseudo"|"motDePasse", code, message }`, codes et messages exacts de 1.1 (`PSEUDO_REQUIS`, `PSEUDO_LONGUEUR`, `PSEUDO_CARACTERES`, `MOT_DE_PASSE_REQUIS`, `MOT_DE_PASSE_TROP_COURT`, `MOT_DE_PASSE_TROP_LONG`).
- **400 corps illisible** : `CORPS_ILLISIBLE`, « Requête invalide » / « Le corps de la requête est illisible. ».
- **401** `NON_AUTHENTIFIE` ; **403** `ACCES_REFUSE` (`BENEVOLE`, `COUREUR`) ; **403** `CSRF_INVALIDE` (formats de 1.2 et 1.1).
- **409** `PSEUDO_DEJA_UTILISE` : « Conflit » / « Ce pseudo est déjà utilisé. ». **415** : `Content-Type` non JSON. 404 : non applicable.

### GET /api/administration/benevoles (nouveau)
- Rôle requis : `ADMIN` ou `ADMIN_MASTER`. Aucun corps, aucun paramètre, pas de CSRF.
- **200** : tableau de `CompteReponse` (`role` toujours `BENEVOLE`), trié selon RG8, `[]` si aucun.
- **401** `NON_AUTHENTIFIE` ; **403** `ACCES_REFUSE` (`BENEVOLE`, `COUREUR`). 400, 404, 409 : non applicables.

### Autres chemins `/api/administration/benevoles/**`
- Aucun autre endpoint : admin 404 `RESSOURCE_INTROUVABLE` (ou 405 selon la méthode) ; autres identités 401 / 403 (RG1, RG9).

### Endpoints existants
- `POST /api/connexion`, `GET /api/comptes/moi`, `POST /api/comptes`, `GET /api/csrf`, `POST /api/deconnexion`, `GET /api/administration/acces`, `/api/administration/admins` : inchangés (RG15). `CompteReponse.role` peut valoir `BENEVOLE` (déjà prévu en 1.2).

### Schéma et configuration
- Aucun changement (RG15).

## 5. Écrans

### Gestion des bénévoles (`/administration/benevoles`, nouveau)
- **Accès** : `ADMIN` et `ADMIN_MASTER` (RG12).
- **Affiché** : titre `titre-benevoles` « Gestion des bénévoles » ; liste `liste-benevoles` avec, par bénévole, une ligne `ligne-benevole` (pseudo, date de création) ; `benevoles-vide` « Aucun bénévole pour le moment. » si vide ; formulaire (`benevole-champ-pseudo`, `benevole-champ-mot-de-passe`, `benevole-champ-confirmation`, aide « 12 caractères minimum ») ; lien `lien-retour-administration` « Retour à l'administration ».
- **Actions** : créer un bénévole (`benevole-bouton-creer`, « Créer le bénévole ») ; retour.
- **Succès** : `benevole-message-succes` « Le bénévole <pseudo> a été créé. », liste rechargée, formulaire vidé.
- **Erreurs** : client : `benevole-erreur-pseudo` « Le pseudo est obligatoire. », `benevole-erreur-mot-de-passe` « Le mot de passe est obligatoire. » / « Le mot de passe doit faire au moins 12 caractères. », `benevole-erreur-confirmation` « Les mots de passe ne correspondent pas. ». 400 : chaque `erreurs[].message` sous le champ. 409 : « Ce pseudo est déjà utilisé. » sous le pseudo. 403 `CSRF_INVALIDE` : « La page a expiré, veuillez réessayer. » (`benevole-erreur-generale`), nouveau `GET /api/csrf`. 5xx ou réseau : « Service indisponible, veuillez réessayer plus tard. » (`benevole-erreur-generale`). Chargement de la liste en échec : `benevoles-erreur` « Impossible de charger la liste des bénévoles. Réessayez plus tard. », formulaire utilisable. 401 et 403 `ACCES_REFUSE` : redirections (RG13).

### Espace d'administration (`/administration`, évolution)
- Pour `ADMIN` et `ADMIN_MASTER` : lien `lien-gestion-benevoles` « Gérer les bénévoles » vers `/administration/benevoles`. Le lien `lien-gestion-admins` reste réservé à l'admin master. `titre-administration`, `administration-role`, `administration-vide` inchangés.

### Accueil bénévole (`/benevole`, nouveau)
- **Accès** : `BENEVOLE` (RG12).
- **Affiché** : titre `accueil-benevole-titre` « Espace bénévole » ; `accueil-benevole-vide` « Aucune course à scanner pour le moment. ». Aucune action.
- **Erreurs** : aucun appel métier ; l'accès refusé est une redirection (RG12).

### En-tête (évolution)
- `BENEVOLE` : `lien-espace-benevole` « Espace bénévole ». Le reste de l'en-tête (pseudo, « Administration », « Se déconnecter ») est inchangé. Le lien « Mon compte » arrive en 1.6b.

## 6. Critères d'acceptation

Valeurs de référence : admin master `Patron` / `mot-de-passe-patron-1` ; coureur `Alice` / `un-mot-de-passe-12` ; admin `Nadia` / `mot-de-passe-admin-1` ; bénévole `Léo` / `mot-de-passe-benevole-1` (23 caractères) ; autres valeurs : `secret-de-test-123` (18, valide), `court-secre` (11, invalide). `Clock` fixe, dépôt en mémoire et encodeur factice en unitaire. En intégration, `ADMIN_MASTER` et `COUREUR` sont créés par dépôt ou par API existante, `ADMIN` par l'API de 1.5, `BENEVOLE` par l'API de 1.6a.

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné un dépôt vide, un `Clock` fixe et un encodeur factice, quand `CreerBenevole` reçoit `"  Léo  "` / `mot-de-passe-benevole-1`, alors un Compte de rôle `BENEVOLE` est enregistré (pseudo `Léo`, `pseudoNormalise` `léo`, `id` non nul, `creeLe` = instant du `Clock`, empreinte de l'encodeur, jamais le mot de passe) et renvoyé ; `Compte.creerBenevole(...)` donne `BENEVOLE` et `peutSeConnecter()` vrai ; `creerAdmin` et `creerCoureur` restent inchangés (RG2, RG5) | unitaire |
| CA2 | Quand `CreerBenevole` reçoit pseudo `ab`, `a b`, `""`, 31 caractères, ou mot de passe `court-secre`, 129 caractères, absent, alors une exception de validation porte le code de 1.1 correspondant ; `ab` + `court-secre` : 2 violations ; pseudos de 3 et 30 caractères, mots de passe de 12 et 128 caractères : Compte créé ; aucun Compte enregistré en cas de violation (RG3, RG4) | unitaire |
| CA3 | Étant donné en dépôt `Alice` (coureur), `Nadia` (`ADMIN`), `Patron` (`ADMIN_MASTER`) et `Léo` (`BENEVOLE`), quand `CreerBenevole` reçoit `ALICE`, `nadia`, `PATRON`, `léo` (mot de passe valide), alors « pseudo déjà utilisé » est levée quatre fois, aucun rôle modifié, l'encodeur n'est pas appelé pour hacher un Compte refusé (RG3) | unitaire |
| CA4 | Étant donné un dépôt dont `enregistrer` lève la violation d'unicité (concurrence), quand `CreerBenevole` s'exécute, alors « pseudo déjà utilisé » est levée (RG3) | unitaire |
| CA5 | Étant donné `Alice` en dépôt, quand `CreerBenevole` reçoit `alice` avec `court-secre`, alors l'exception de validation est levée, pas « pseudo déjà utilisé » (RG6) | unitaire |
| CA6 | Quand les exceptions de CA2 à CA4 sont levées avec `secret-de-test-123` ou `court-secre`, alors ni leur message, ni leur `toString()`, ni celui de la commande ne contiennent le mot de passe ; la commande affiche `motDePasse=masqué` (RG7) | unitaire |
| CA7 | Étant donné en dépôt `Patron`, `Nadia` (`ADMIN`), `Alice`, `Zoé` (`BENEVOLE`, 10:00), `Léo` (`BENEVOLE`, 10:00), `Marc` (`BENEVOLE`, 09:00), quand `ListerBenevoles` s'exécute, alors le résultat est exactement `Marc`, `Léo`, `Zoé` dans cet ordre ; sur un dépôt sans `BENEVOLE`, la liste est vide (RG8) | unitaire |
| CA8 | Quand `./mvnw test`, alors les règles ArchUnit passent sans exception ajoutée pour 1.6a : domaine sans Spring/JPA/Jackson, cas d'usage dans `application` sans DTO ni Spring, contrôleurs sans accès à l'infrastructure, `courses` indépendant (RG16) | unitaire |
| CA9 | Étant donné `Patron` puis `Nadia` (`ADMIN`) connectés et un jeton CSRF, quand chacun envoie `POST /api/administration/benevoles` `{pseudo:"Léo", motDePasse:"mot-de-passe-benevole-1", role:"ADMIN"}` (le second avec `Marc`), alors 201 `{id (UUID), pseudo, role:"BENEVOLE", creeLe}` sans `motDePasse`, hash ni `pseudoNormalise` ; la table `compte` contient `BENEVOLE` avec empreinte `$argon2id$...` différente du mot de passe ; toujours un seul `ADMIN_MASTER` (RG1, RG2, RG5, RG7) | intégration |
| CA10 | Étant donné `Alice`, un bénévole `Léo` et un anonyme, quand chacun envoie `POST` valide puis `GET /api/administration/benevoles`, alors 401 `NON_AUTHENTIFIE` pour l'anonyme, 403 `ACCES_REFUSE` pour `Alice` et `Léo` (`application/problem+json`, `title`, `status`, `detail`, `code`), aucune ligne créée ; `POST` sans `X-XSRF-TOKEN` ou avec un jeton différent : 403 `CSRF_INVALIDE` pour tous ; un coureur avec corps `{}` : 403 (pas 400) ; `GET /api/administration/benevoles/inexistant` : 401 / 403 / 403, 404 `RESSOURCE_INTROUVABLE` pour `Nadia` (RG1, RG6) | intégration |
| CA11 | Étant donné `Patron`, quand `POST` avec `ab` / `court-secre`, alors 400 `VALIDATION_ECHOUEE`, `erreurs` exactement `PSEUDO_LONGUEUR` (pseudo) et `MOT_DE_PASSE_TROP_COURT` (motDePasse), corps sans `court-secre`, aucune ligne créée ; `{}` : `PSEUDO_REQUIS` et `MOT_DE_PASSE_REQUIS` ; corps non JSON : 400 `CORPS_ILLISIBLE` ; `text/plain` : 415 ; mots de passe de 12 et 128 caractères : 201, de 129 : 400 `MOT_DE_PASSE_TROP_LONG` (RG3, RG4, RG6, RG7) | intégration |
| CA12 | Étant donné `Alice`, `Nadia`, `Patron` et `Léo`, quand `Patron` envoie `POST` avec `alice`, `NADIA`, `PATRON`, `léo`, alors 409 `PSEUDO_DEJA_UTILISE` quatre fois, aucun Compte ajouté ni modifié ; avec `alice` et `court-secre` : 400 ; deux `POST` simultanés `Concurrent` : un 201, un 409, jamais 500, une ligne (RG3, RG6) | intégration |
| CA13 | Étant donné `Patron`, `Nadia`, `Alice` et trois bénévoles créés par l'API (`Marc`, `Léo`, `Zoé`), quand `Patron` puis `Nadia` appellent `GET /api/administration/benevoles`, alors 200 et 3 éléments dans l'ordre de création pour chacun, `{id, pseudo, role:"BENEVOLE", creeLe}` sans hash ni `pseudoNormalise`, ni `Nadia`, `Patron`, `Alice` ; sur une base sans bénévole : `[]` (RG8) | intégration |
| CA14 | Étant donné `Léo` créé par `Nadia` via l'API, quand `POST /api/connexion` avec `LÉO` / `mot-de-passe-benevole-1`, alors 200 `{pseudo:"Léo", role:"BENEVOLE"}` ; avec cette session `GET /api/administration/acces`, `GET` et `POST /api/administration/benevoles`, `GET /api/administration/admins` : 403 `ACCES_REFUSE` ; `GET /api/comptes/moi` : 200 ; la limitation de 1.3 s'applique (5 échecs puis 429) (RG1, RG10) | intégration |
| CA15 | Étant donné `Patron`, quand `PUT`, `PATCH` et `DELETE /api/administration/benevoles/<id de Léo>` sont envoyés, alors 404 ou 405, `Léo` intact ; `POST /api/comptes` avec `role:"BENEVOLE"` crée un `COUREUR` ; `/api/administration/admins` reste 403 pour `Nadia` ; la suite d'intégration de 1.1 à 1.5 passe sans changement d'attente (dont `databasechangelog` = `["0002-compte", "0003-admin-master-unique"]`) (RG9, RG15) | intégration |
| CA16 | Étant donné un journal capturé (tous niveaux), quand `Patron` crée `Léo` avec `secret-de-test-123`, puis qu'une création échoue (`court-secre`, doublon), alors aucune ligne ne contient `secret-de-test-123`, `court-secre` ni `mot-de-passe-benevole-1` ; aucune ligne INFO ou supérieure ne contient `Léo` ; la ligne « Bénévole créé » (id du Compte et de l'admin) existe ; le `toString()` du DTO donne `CreerBenevoleRequete[pseudo=..., motDePasse=masqué]` (RG7, RG11) | intégration |
| CA17 | Étant donné `Patron` (identifiants lus dans l'environnement du test), quand il se connecte, clique sur `lien-gestion-benevoles` depuis `/administration`, alors l'URL devient `/administration/benevoles`, `titre-benevoles` affiche « Gestion des bénévoles » ; après saisie de `benevole-<suffixe>` / `mot-de-passe-benevole-1` (deux fois) et clic sur `benevole-bouton-creer`, `benevole-message-succes` affiche « Le bénévole benevole-<suffixe> a été créé. », une `ligne-benevole` contient ce pseudo, les trois champs sont vides, `GET /api/administration/benevoles` contient ce pseudo avec `role:"BENEVOLE"` sans champ de mot de passe ; `lien-retour-administration` ramène à `/administration` ; la même création réussit avec un `ADMIN` créé par l'API (RG1, RG8, RG13) | E2E |
| CA18 | Étant donné un admin sur `/administration/benevoles`, quand il valide le formulaire vide, saisit un mot de passe de 11 caractères, des confirmations différentes, alors `benevole-erreur-pseudo` « Le pseudo est obligatoire. », `benevole-erreur-mot-de-passe` « Le mot de passe doit faire au moins 12 caractères. » et `benevole-erreur-confirmation` « Les mots de passe ne correspondent pas. » s'affichent sans envoi ; avec le pseudo `ab` valide côté client, `benevole-erreur-pseudo` affiche le message serveur de longueur, le pseudo reste saisi, les mots de passe sont vidés ; avec le pseudo d'un coureur en casse différente, `benevole-erreur-pseudo` « Ce pseudo est déjà utilisé. » et aucune `ligne-benevole` ajoutée (RG3, RG4, RG13) | E2E |
| CA19 | Étant donné un anonyme, un coureur et un bénévole, quand chacun ouvre `/administration/benevoles`, alors l'URL devient `/connexion?retour=%2Fadministration%2Fbenevoles` (puis, après connexion de `Patron`, `/administration/benevoles`, et F5 la conserve), puis `/acces-refuse` avec « Accès refusé » pour le coureur et pour le bénévole ; `lien-gestion-benevoles` est présent sur `/administration` pour `Patron` et pour un `ADMIN`, `lien-gestion-admins` absent pour l'`ADMIN` (RG1, RG12, RG13) | E2E |
| CA20 | Étant donné un admin sur `/administration/benevoles`, quand `GET /api/administration/benevoles` est interceptée en 500, alors `benevoles-erreur` « Impossible de charger la liste des bénévoles. Réessayez plus tard. » sans code HTTP, formulaire utilisable ; quand le `POST` est intercepté en 500 : `benevole-erreur-generale` « Service indisponible, veuillez réessayer plus tard. », pseudo conservé, mots de passe vidés ; liste en 401 : `/connexion?retour=%2Fadministration%2Fbenevoles` ; en 403 `ACCES_REFUSE` : `/acces-refuse` (RG13) | E2E |
| CA21 | Étant donné un bénévole `benevole-<suffixe>` créé par l'API, quand il se connecte (pseudo en majuscules), alors l'URL devient `/benevole`, `accueil-benevole-titre` « Espace bénévole » et `accueil-benevole-vide` « Aucune course à scanner pour le moment. » sont visibles, l'en-tête affiche son pseudo et `lien-espace-benevole`, pas de lien « Administration » ; l'ouverture de `/administration` mène à `/acces-refuse` ; un coureur et un admin qui ouvrent `/benevole` vont sur `/acces-refuse`, un anonyme sur `/connexion?retour=%2Fbenevole` ; F5 sur `/benevole` conserve la page ; connexion avec `retour=/administration` : `/acces-refuse` (RG10, RG12, RG14) | E2E |

Répartition : unitaire 8 (CA1 à CA8), intégration 8 (CA9 à CA16), E2E 5 (CA17 à CA21). Total 21.

Couverture des RG : RG1 CA9/CA10/CA14/CA17/CA19, RG2 CA1/CA9, RG3 CA2/CA3/CA4/CA11/CA12/CA18, RG4 CA2/CA11/CA18, RG5 CA1/CA9, RG6 CA5/CA10/CA11/CA12, RG7 CA6/CA9/CA11/CA16, RG8 CA7/CA13/CA17, RG9 CA15, RG10 CA14/CA21, RG11 CA16, RG12 CA19/CA21, RG13 CA17/CA18/CA19/CA20, RG14 CA21, RG15 CA15, RG16 CA8. Écrans : Gestion des bénévoles (CA17 à CA20), Espace d'administration évolué (CA17, CA19), Accueil bénévole (CA21), En-tête (CA21), tous en E2E.

Notes pour les testeurs : les E2E créent leurs coureurs, admins et bénévoles par l'API avec un suffixe aléatoire (helpers de `e2e/tests/aide-connexion.ts`, à étendre d'un helper « créer un bénévole » appelant `POST /api/administration/benevoles` avec la session d'un admin) ; l'admin master vient de `E2E_ADMIN_MASTER_PSEUDO` et `E2E_ADMIN_MASTER_MOT_DE_PASSE`. Les Comptes créés ne peuvent pas être supprimés (RG9) : pseudos uniques, jamais de liste exacte attendue.

## 7. Tester à la main

Prérequis : stack de 1.5 lancée (`docker compose up -d --build`, `base`, `api`, `web` `healthy`), `.env` avec `ADMIN_MASTER_PSEUDO=Patron` et `ADMIN_MASTER_MOT_DE_PASSE=mot-de-passe-patron-1`, http://localhost. Aucune migration ni variable nouvelle. Les étapes 3 à 9 supposent un Compte admin `Nadia` (créé en 1.5) et un coureur `Alice` (« Créer un compte »).

1. Connexion `Patron` / `mot-de-passe-patron-1` : arrivée sur `/administration`, deux liens « Gérer les administrateurs » et « Gérer les bénévoles ».
2. Clic sur « Gérer les bénévoles » : `/administration/benevoles`, « Gestion des bénévoles », « Aucun bénévole pour le moment. » (base neuve), formulaire.
3. Contrôles : formulaire vide : trois messages d'obligation ; mot de passe `court-secre` : « au moins 12 caractères » ; confirmation différente : message de non-correspondance ; pseudo `ab` avec mot de passe valide : message serveur, pseudo conservé, mots de passe vidés.
4. Création : `Léo` / `mot-de-passe-benevole-1` deux fois : « Le bénévole Léo a été créé. », une ligne `Léo`, formulaire vidé. Créer `Marc` : liste `Léo` puis `Marc`.
5. Doublons : créer `léo`, `PATRON`, `Nadia`, `ALICE` : « Ce pseudo est déjà utilisé. » à chaque fois.
6. Base : `docker compose exec base psql -U backyard -d backyard -c "select pseudo, role, left(empreinte_mot_de_passe, 10) from compte order by cree_le"` : `Léo` et `Marc` en `BENEVOLE`, empreintes `$argon2id$`.
7. Se déconnecter, connexion `Nadia` (admin non master) : `/administration` avec « Gérer les bénévoles », sans « Gérer les administrateurs » ; la page des bénévoles affiche `Léo` et `Marc` ; créer `Zoé` réussit.
8. Se déconnecter, connexion `léo` / `mot-de-passe-benevole-1` (pseudo en minuscules) : arrivée sur `/benevole`, « Espace bénévole », « Aucune course à scanner pour le moment. », en-tête avec « Espace bénévole », pas de lien « Administration ». Saisir http://localhost/administration puis /administration/benevoles : « Accès refusé ». Se déconnecter, connexion `Alice` : arrivée sur `/` ; ouvrir `/benevole` : « Accès refusé ».
9. API (jar CSRF comme 1.2 étape 15). Bénévole : `curl -s -i -b /tmp/jar http://localhost/api/administration/benevoles` : `403` `"code":"ACCES_REFUSE"` ; `POST` avec jeton CSRF et `{"pseudo":"Intrus1","motDePasse":"un-mot-de-passe-12"}` : `403`, aucune ligne `Intrus1` en base ; sans session : `401`. Admin (jar `Nadia`) : `GET` : `200` avec `Léo`, `Marc`, `Zoé`, sans `Nadia`, `Patron`, `Alice`, sans hash ; `POST` avec `{"pseudo":"Eve12","motDePasse":"un-mot-de-passe-12","role":"ADMIN"}` : `201` avec `"role":"BENEVOLE"`.
10. Persistance et journaux : `docker compose restart api`, reconnexion de `Patron` : la liste contient `Léo`, `Marc`, `Zoé`, `Eve12`. `docker compose logs api | grep -c -e "mot-de-passe-benevole-1" -e "court-secre" -e "un-mot-de-passe-12"` renvoie `0` ; `docker compose logs api | grep "Bénévole créé"` montre des identifiants, sans pseudo.

## 8. Points ouverts

Bloquants : aucun.

Non bloquants (position par défaut retenue, à confirmer) :
1. **Aucune action admin sur un bénévole ou un admin existant** : pas de réinitialisation de mot de passe, pas de désactivation ni de suppression. Un bénévole qui oublie son mot de passe (ou un bénévole créé par erreur) ne peut pas être dépanné par l'interface le jour de la course ; contournement SQL uniquement. Même remarque que 1.5 point 2 et que 1.3 point 1 (déblocage). À trancher : ajouter « réinitialiser le mot de passe d'un bénévole » (action admin) à un incrément ultérieur, ou l'accepter.
2. **Mot de passe initial communiqué hors application.** L'admin choisit le mot de passe du bénévole et le lui transmet ; aucun changement forcé à la première connexion (non demandé). Le bénévole pourra le changer à partir de 1.6b. Alternative : mot de passe temporaire à changer à la première connexion. Même point que 1.5 point 5.
3. **Liste des bénévoles sans pagination, recherche ni indication de l'affectation** : le nombre de bénévoles est supposé faible ; les affectations apparaissent en 2.x.
4. **Rôle figé dans la session** (12 h) : même cause que 1.2, 1.4, 1.5 ; sans conséquence tant qu'aucune suppression ou changement de rôle n'existe. À traiter au plus tard avec 3.6.
5. **Écran `/benevole` réservé au rôle `BENEVOLE`** (admins et coureurs vers `/acces-refuse`) ; l'écran n'appelle aucun endpoint, donc la protection est côté front uniquement (aucune donnée n'y est exposée). Alternative : un endpoint technique `GET /api/benevole/acces` pour un contrôle serveur symétrique à `/api/administration/acces` (1.4). Les admins pourraient aussi y accéder pour l'aperçu. La future route de scan (5.x) pourra remplacer cet écran ou en reprendre le chemin.
6. **Noms** : endpoints `/api/administration/benevoles`, écrans `/administration/benevoles` et `/benevole`, préfixes `data-testid` `benevole-` et `accueil-benevole-`. À confirmer.
