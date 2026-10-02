# Increment 1.1 : Créer un compte coureur

Premier incrément métier : un visiteur crée un Compte `COUREUR` (pseudo + mot de passe). Il introduit le contexte `comptes`, la table des comptes, le hachage Argon2id, Spring Security (CSRF actif, refus par défaut) et le premier écran Angular routé. La connexion arrive en 1.2.

## 1. Périmètre

**Inclus**
- Contexte `comptes` (packages `fr.backyard.tracker.comptes.{domaine,application,infrastructure,exposition}`) : Compte, value objects `Pseudo` et `MotDePasse`, cas d'usage `CreerCompteCoureur`, port de dépôt et port d'encodage du mot de passe.
- Table `compte` (changeset Liquibase `0002-compte.yaml`, ajouté au master).
- Endpoint public `POST /api/comptes` et endpoint `GET /api/csrf`.
- Spring Security minimal : CSRF actif (cookie lisible par Angular), tout `/api/**` refusé par défaut sauf les endpoints publics listés en RG10, aucune session créée, aucun formulaire de connexion.
- Écran Angular « Créer un compte » (`/creer-compte`), introduction du routeur Angular, lien depuis l'accueil.
- Hachage Argon2id (paramètres documentés en RG5).

**Exclu**
- Connexion, déconnexion, session, cookie de session, pseudo dans l'en-tête, lien « Se connecter » : 1.2. Après la création, le coureur n'est donc pas connecté.
- Blocage après échecs de connexion : 1.3.
- Admin master, comptes `ADMIN` / `BENEVOLE`, contrôle des rôles sur les endpoints : 1.4 à 1.6.
- Changement de mot de passe : 1.6. Suppression/anonymisation d'un compte : 3.6 (le modèle de 1.1 le permet : `empreinte_mot_de_passe` nullable, unicité portée par une colonne normalisée).
- Limitation du nombre de créations de comptes par IP : non prévue (point ouvert 4).
- Inscription aux courses, contexte `courses` : jalons 2 et 3. `courses` n'est pas modifié.
- Changement de Caddy, `docker-compose*.yml`, `.env.example` : aucun (aucune nouvelle variable d'environnement).

## 2. Règles de gestion

**Création de compte**
- **RG1** : `POST /api/comptes` est public (sans authentification) et crée toujours un Compte de rôle `COUREUR`. Aucun champ du corps ne permet de choisir le rôle : un champ `role` éventuellement envoyé est ignoré.
- **RG2** : règle du pseudo (dans le domaine, value object `Pseudo`). Le pseudo saisi est d'abord débarrassé de ses espaces de début et de fin (`trim`), puis doit : être non vide ; faire de 3 à 30 caractères ; ne contenir que des lettres (Unicode), des chiffres, `.`, `_` et `-` (pas d'espace interne). Ordre de contrôle, une seule violation retenue par champ : requis, puis longueur, puis caractères. Le pseudo est conservé et affiché tel que saisi (casse comprise, après `trim`).
- **RG3** : unicité du pseudo insensible à la casse. `Alice` et `alice` sont le même pseudo. La clé d'unicité est `pseudo_normalise` = pseudo en minuscules (`toLowerCase(Locale.ROOT)`), protégée par une contrainte unique en base. Un conflit détecté par le cas d'usage ou par la contrainte (requêtes concurrentes) donne le même 409, jamais un 500.
- **RG4** : règle du mot de passe (dans le domaine, value object `MotDePasse`). Au moins 12 caractères et au plus 128, comptés en points de code Unicode, sans `trim` (les espaces comptent et sont conservés). Aucune autre règle de composition. Le maximum de 128 borne le coût du hachage.
- **RG5** : le mot de passe est haché en Argon2id (implémentation Spring Security `Argon2PasswordEncoder`, avec BouncyCastle) avant toute persistance. Paramètres (recommandation OWASP) : mémoire 19456 Kio (19 Mio), 2 itérations, parallélisme 1, sel aléatoire de 16 octets, hachage de 32 octets. Ils sont documentés dans le code (constantes nommées) et dans `docs/deploiement.md` uniquement si le développeur les expose en configuration (non requis). L'empreinte est stockée au format encodé standard (`$argon2id$v=19$m=19456,t=2,p=1$...`) ; deux comptes de même mot de passe ont des empreintes différentes. Le hachage est un port du domaine (`EncodeurMotDePasse`) implémenté dans l'infrastructure.
- **RG6** : un mot de passe en clair n'apparaît jamais : ni en base, ni dans les logs, ni dans une exception, ni dans un `ProblemDetail`, ni dans une réponse, ni dans un `toString()`. `MotDePasse` et le DTO de requête masquent leur valeur dans `toString()` (`MotDePasse[masqué]`, `CreerCompteRequete[pseudo=..., motDePasse=masqué]`). Les corps de requête ne sont jamais journalisés. Les messages d'erreur ne reprennent jamais la valeur saisie.
- **RG7** : un Compte créé a : `id` (UUID généré côté application), `pseudo`, `pseudoNormalise`, `empreinteMotDePasse`, `role = COUREUR`, `creeLe` (instant fourni par le `Clock` injecté, UTC). Le hash n'est jamais renvoyé par l'API.
- **RG8** : validation et erreurs. Le contrôleur ne valide que le format (corps JSON lisible). Les règles RG2 et RG4 sont appliquées par le domaine. Quand plusieurs champs sont invalides, toutes les violations (une par champ au plus) sont renvoyées dans une même réponse 400. Pas de vérification d'unicité tant que le format est invalide (400 prioritaire sur 409).
- **RG9** : le champ de confirmation du mot de passe n'existe que dans l'écran. L'API ne le reçoit pas ; l'écran bloque l'envoi si les deux saisies diffèrent (RG16).

**Sécurité transverse**
- **RG10** : Spring Security est introduit. Politique : tout `/api/**` exige une authentification, sauf `GET /api/sante` (proxy Caddy de `/actuator/health`), `GET /actuator/health`, `GET /api/csrf` et `POST /api/comptes`. Un appel non autorisé à un autre chemin `/api/**` (même inexistant) répond 401 `ProblemDetail`. Seul `health` reste exposé par actuator. Ce changement modifie 0.2 CA6 : `/api/inexistant` répond désormais 401 (et non 404) ; `/api/sante` et `:8080/actuator/health` restent 200.
- **RG11** : aucune session serveur n'est créée (`SessionCreationPolicy.STATELESS` en 1.1, remplacé en 1.2), aucun cookie `JSESSIONID`, aucune connexion automatique après création. Seul cookie posé : `XSRF-TOKEN`.
- **RG12** : la protection CSRF reste active sur `POST /api/comptes` (aucune exemption). Mode « cookie » pour SPA : `GET /api/csrf` répond 204 et pose le cookie `XSRF-TOKEN` (non `HttpOnly` pour être lisible par Angular, `SameSite=Lax`, `Path=/`, `Secure` quand la requête est en HTTPS, ce qui suppose que l'API tienne compte de `X-Forwarded-Proto` du proxy Caddy). Toute requête `POST/PUT/PATCH/DELETE` doit renvoyer sa valeur dans l'en-tête `X-XSRF-TOKEN`. Angular le fait automatiquement pour les URL relatives une fois le cookie présent (mécanisme `HttpClient` par défaut). Sans en-tête ou avec une valeur fausse : 403 `ProblemDetail` `CSRF_INVALIDE`. Ce mécanisme est réutilisé tel quel en 1.2 (connexion) : le jeton est lié à la session à ce moment-là, la forme du contrat ne change pas.
- **RG13** : les réponses d'erreur sont au format `ProblemDetail` (`application/problem+json`) avec, en plus des champs standard (`type` = `about:blank`, `title`, `status`, `detail`), une propriété `code` stable lisible par le front, et pour les 400 de validation une propriété `erreurs`.

**Persistance et architecture**
- **RG14** : table `compte` créée par un nouveau changeset (le changeset `0001` n'est pas modifié) : `id` uuid clé primaire, `pseudo` varchar(30) non nul, `pseudo_normalise` varchar(30) non nul avec contrainte unique, `empreinte_mot_de_passe` varchar(255) **nullable** (effacée à l'anonymisation en 3.6), `role` varchar(20) non nul contraint à `ADMIN_MASTER|ADMIN|BENEVOLE|COUREUR`, `cree_le` timestamptz non nul. `ddl-auto=validate` doit passer.
- **RG15** : Clean Architecture. Le domaine `comptes` est du Java pur (aucun Spring, JPA, Jackson, BouncyCastle). L'entité JPA est distincte du Compte du domaine. La configuration Spring Security vit dans `comptes.infrastructure`. Les règles ArchUnit de 0.3 passent sans exception ; le contexte `courses` n'est pas touché.

**Écran**
- **RG16** : l'écran « Créer un compte » contrôle côté client uniquement le confort (champs non vides, confirmation identique, longueur minimale du mot de passe), sans jamais remplacer le contrôle serveur : les messages de l'API (`erreurs[].message`) sont affichés tels quels sous le champ concerné. Les champs mot de passe sont de type `password` avec `autocomplete="new-password"`. Après un succès ou une erreur, les champs mot de passe sont vidés ; après une erreur de pseudo, le pseudo saisi est conservé. Le mot de passe n'est jamais stocké côté navigateur (ni `localStorage`, ni URL, ni état partagé après l'envoi).

## 3. Cas limites

- **Pseudo aux limites** : 3 caractères accepté, 2 refusé ; 30 accepté, 31 refusé. `"  Alice  "` devient `Alice`. `"   "` : pseudo requis.
- **Casse** : créer `Alice` puis `alice` ou `ALICE` : 409. Le pseudo affiché reste celui du premier compte.
- **Caractères** : `éloïse_89` accepté ; `a b`, `a@b`, `a/b`, `<script>` refusés (PSEUDO_CARACTERES).
- **Mot de passe aux limites** : 11 caractères refusé, 12 accepté, 128 accepté, 129 refusé. 12 espaces acceptés (aucune règle de composition, point ouvert 3). Un mot de passe de 12 `é` (24 octets) est accepté : le comptage est en caractères.
- **Plusieurs violations** : pseudo `ab` et mot de passe de 5 caractères : un 400 avec deux entrées dans `erreurs`.
- **Champs absents ou `null`** : 400 avec `PSEUDO_REQUIS` et/ou `MOT_DE_PASSE_REQUIS`.
- **Corps non JSON, vide ou mal typé** (ex. `pseudo` numérique) : 400 `CORPS_ILLISIBLE`, sans écho du contenu reçu. Mauvais `Content-Type` : 415.
- **Rôle injecté** : `{"role":"ADMIN"}` dans le corps : ignoré, compte `COUREUR`. Champ inconnu quelconque : ignoré.
- **Doublon concurrent** : deux requêtes simultanées avec le même pseudo : une 201, une 409.
- **Pseudo libéré** (futur 3.6) : la contrainte porte sur `pseudo_normalise`, que l'anonymisation remplacera ; non testé en 1.1.
- **CSRF** : POST sans cookie ni en-tête, ou en-tête différent du cookie : 403. Premier chargement de l'écran : le front appelle `GET /api/csrf` avant tout POST. Cookie expiré/effacé pendant la saisie : 403 CSRF_INVALIDE, l'écran redemande un jeton (`GET /api/csrf`) et affiche « La page a expiré, veuillez réessayer. » ; un nouvel envoi réussit.
- **Utilisateur déjà « connecté »** : sans objet en 1.1 (pas de connexion).
- **Réseau/API indisponible** : l'écran affiche « Service indisponible, veuillez réessayer plus tard. » (réponse 5xx, 502/504 de Caddy ou erreur réseau), les champs sont conservés sauf les mots de passe.
- **Double clic sur « Créer le compte »** : le bouton est désactivé pendant l'envoi ; en cas de double requête, la seconde reçoit 409 (le front n'en tient pas compte tant que la première a réussi).
- **Redémarrage de la stack** : les comptes persistent dans le volume `donnees-postgres`.
- Rôle insuffisant, bénévole non affecté, course, boucle, passage, inscription : non applicables en 1.1.

## 4. Contrat d'API

Toutes les réponses d'erreur sont des `ProblemDetail` (`application/problem+json`) : `{ "type": "about:blank", "title": string, "status": int, "detail": string, "code": string }`, plus `erreurs` pour les 400 de validation.

### GET /api/csrf
- Rôle requis : aucun (public).
- Requête : aucun corps. Réponse **204** sans corps, avec `Set-Cookie: XSRF-TOKEN=<jeton>; Path=/; SameSite=Lax` (non `HttpOnly`, `Secure` si HTTPS). Idempotent : un nouvel appel peut renouveler le jeton.
- Autres codes : aucun attendu (400/401/403/404/409 non applicables).

### POST /api/comptes
- Rôle requis : aucun (public). En-tête `X-XSRF-TOKEN` obligatoire (RG12). `Content-Type: application/json`.
- **Requête `CreerCompteRequete`** :

| Champ | Type | Obligatoire | Règle |
|---|---|---|---|
| `pseudo` | string | oui | RG2 (trim, 3 à 30, lettres/chiffres/`.`/`_`/`-`) |
| `motDePasse` | string | oui | RG4 (12 à 128 caractères) |

- **Réponse 201 `CompteReponse`** (aucun en-tête `Location` : pas de lecture d'un compte en 1.1) :

| Champ | Type | Valeur |
|---|---|---|
| `id` | string (UUID) | identifiant du compte |
| `pseudo` | string | pseudo après `trim`, casse conservée |
| `role` | string | toujours `COUREUR` |
| `creeLe` | string (ISO-8601 UTC) | instant de création |

Jamais de `motDePasse`, de hash ni de `pseudoNormalise` dans la réponse.

- **400 validation** : `code` = `VALIDATION_ECHOUEE`, `title` = « Requête invalide », `detail` = « Certains champs sont invalides. », et `erreurs` : tableau d'objets `{ "champ": "pseudo"|"motDePasse", "code": string, "message": string }`, une entrée par champ invalide au plus :

| `champ` | `code` | `message` |
|---|---|---|
| pseudo | `PSEUDO_REQUIS` | Le pseudo est obligatoire. |
| pseudo | `PSEUDO_LONGUEUR` | Le pseudo doit faire entre 3 et 30 caractères. |
| pseudo | `PSEUDO_CARACTERES` | Le pseudo ne peut contenir que des lettres, des chiffres, « . », « _ » et « - ». |
| motDePasse | `MOT_DE_PASSE_REQUIS` | Le mot de passe est obligatoire. |
| motDePasse | `MOT_DE_PASSE_TROP_COURT` | Le mot de passe doit faire au moins 12 caractères. |
| motDePasse | `MOT_DE_PASSE_TROP_LONG` | Le mot de passe ne doit pas dépasser 128 caractères. |

- **400 corps illisible** : `code` = `CORPS_ILLISIBLE`, `title` = « Requête invalide », `detail` = « Le corps de la requête est illisible. » (pas de `erreurs`).
- **401** : non applicable à cet endpoint (public).
- **403 CSRF** : `code` = `CSRF_INVALIDE`, `title` = « Accès refusé », `detail` = « Jeton CSRF absent ou invalide. ».
- **409** : `code` = `PSEUDO_DEJA_UTILISE`, `title` = « Conflit », `detail` = « Ce pseudo est déjà utilisé. » (aucun autre détail sur le compte existant).
- **415** : `Content-Type` non JSON (`ProblemDetail` standard de Spring, `code` non garanti).
- 404 : non applicable.

### Endpoints existants
- `GET /api/sante` et `GET /actuator/health` : inchangés (200 `{"status":"UP"}`, publics).
- Tout autre `/api/**` : **401** `ProblemDetail` `code` = `NON_AUTHENTIFIE`, `title` = « Authentification requise », `detail` = « Vous devez être connecté. » (RG10). Pas de redirection, pas d'en-tête `WWW-Authenticate: Basic`.

## 5. Écrans

### Accueil (`/`) : évolution
- Ajout d'un lien « Créer un compte » (`data-testid="lien-creer-compte"`) vers `/creer-compte`. Le titre et l'indicateur d'état de l'API (0.2, `data-testid="titre"` et `data-testid="etat-api"`) sont inchangés. Le lien « Se connecter » arrive en 1.2.

### Créer un compte (`/creer-compte`)
- **Informations affichées** : titre « Créer un compte » (`data-testid="titre-creer-compte"`) ; champs « Pseudo » (`champ-pseudo`), « Mot de passe » (`champ-mot-de-passe`), « Confirmer le mot de passe » (`champ-confirmation`), avec l'aide « Pseudo : 3 à 30 caractères, lettres, chiffres, . _ - » et « Mot de passe : 12 caractères minimum » ; lien « Retour à l'accueil » (`lien-accueil`).
- **Actions** : bouton « Créer le compte » (`bouton-creer-compte`), désactivé pendant l'envoi. Au chargement, appel de `GET /api/csrf`. À l'envoi : `POST /api/comptes` avec `pseudo` et `motDePasse` uniquement.
- **Succès (201)** : le formulaire est remplacé par le message « Compte créé pour <pseudo>. Vous pourrez vous connecter dès que la connexion sera disponible. » (`data-testid="message-succes"`) et un lien « Retour à l'accueil ». Pas de connexion automatique.
- **Messages d'erreur** (`data-testid="erreur-pseudo"`, `erreur-mot-de-passe`, `erreur-confirmation`, `erreur-generale`) :
  - Contrôles client : « Le pseudo est obligatoire. », « Le mot de passe est obligatoire. », « Le mot de passe doit faire au moins 12 caractères. », « Les deux mots de passe ne correspondent pas. » (l'envoi n'a pas lieu).
  - 400 `VALIDATION_ECHOUEE` : chaque `erreurs[].message` sous le champ `champ`.
  - 409 `PSEUDO_DEJA_UTILISE` : « Ce pseudo est déjà utilisé. » sous le pseudo.
  - 403 `CSRF_INVALIDE` : « La page a expiré, veuillez réessayer. » (`erreur-generale`), nouveau `GET /api/csrf`.
  - 400 `CORPS_ILLISIBLE`, 5xx, 502/504, erreur réseau : « Service indisponible, veuillez réessayer plus tard. » (`erreur-generale`). Aucun code HTTP ni détail technique affiché.

## 6. Critères d'acceptation

Les valeurs chiffrées sont celles à utiliser dans les tests. Les tests E2E utilisent des pseudos uniques par exécution (suffixe aléatoire), la base persistant entre exécutions.

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné le pseudo `"  Alice_01 "`, quand on construit un `Pseudo`, alors la valeur est `Alice_01`. Les pseudos de 3 (`Bob`) et 30 caractères sont acceptés ; `éloïse.89-x` aussi (RG2) | unitaire |
| CA2 | Étant donné les pseudos `""`, `"   "`, `"ab"`, 31 caractères, `"a b"`, `"a@b"`, `"<b>"`, quand on construit un `Pseudo`, alors violation respectivement `PSEUDO_REQUIS`, `PSEUDO_REQUIS`, `PSEUDO_LONGUEUR`, `PSEUDO_LONGUEUR`, `PSEUDO_CARACTERES` x3 (RG2) | unitaire |
| CA3 | Étant donné des mots de passe de 11, 12, 128 et 129 caractères, ainsi que 12 `é`, quand on construit un `MotDePasse`, alors 11 : `MOT_DE_PASSE_TROP_COURT`, 12 : accepté, 128 : accepté, 129 : `MOT_DE_PASSE_TROP_LONG`, 12 `é` : accepté ; `null` : `MOT_DE_PASSE_REQUIS` (RG4) | unitaire |
| CA4 | Étant donné un `MotDePasse` « secret-de-test-123 », quand on appelle `toString()`, alors le texte est `MotDePasse[masqué]` et ne contient pas « secret-de-test-123 » ; idem pour `CreerCompteRequete.toString()` et pour les messages des exceptions de validation (RG6) | unitaire |
| CA5 | Étant donné un dépôt en mémoire vide, un encodeur factice et un `Clock` fixe à 2026-10-02T10:00:00Z, quand `CreerCompteCoureur` reçoit `Alice` / `un-mot-de-passe-12`, alors un Compte est enregistré avec rôle `COUREUR`, `pseudoNormalise` = `alice`, `creeLe` = 2026-10-02T10:00:00Z, empreinte fournie par l'encodeur et différente du mot de passe (RG1, RG5, RG7) | unitaire |
| CA6 | Étant donné un compte `Alice` existant, quand `CreerCompteCoureur` reçoit `alice` puis `ALICE`, alors `PseudoDejaUtiliseException` à chaque fois et rien n'est enregistré (RG3) | unitaire |
| CA7 | Étant donné pseudo `ab` et mot de passe de 5 caractères, quand `CreerCompteCoureur` s'exécute, alors une exception de validation porte deux violations (pseudo, motDePasse), l'encodeur n'est pas appelé et le dépôt n'est pas interrogé (RG8) | unitaire |
| CA8 | Étant donné les règles ArchUnit de 0.3, quand `./mvnw test`, alors elles passent avec les classes du contexte `comptes` (domaine sans Spring/JPA/Jackson/BouncyCastle, exposition sans infrastructure, `courses` indépendant) (RG15) | unitaire |
| CA9 | Étant donné la base migrée et un jeton CSRF valide, quand `POST /api/comptes` avec `{"pseudo":"  Alice ","motDePasse":"un-mot-de-passe-12"}`, alors 201, `Content-Type` JSON, corps `{id (UUID), pseudo:"Alice", role:"COUREUR", creeLe}` sans aucun champ `motDePasse`, hash ni `pseudoNormalise` (RG1, RG7) | intégration |
| CA10 | Étant donné CA9, quand on lit la ligne en base, alors `pseudo`=`Alice`, `pseudo_normalise`=`alice`, `role`=`COUREUR`, `empreinte_mot_de_passe` commence par `$argon2id$` avec `m=19456,t=2,p=1`, ne contient pas `un-mot-de-passe-12`, et l'encodeur confirme que l'empreinte correspond au mot de passe (RG5, RG6, RG14) | intégration |
| CA11 | Étant donné deux comptes créés avec le même mot de passe, alors leurs empreintes sont différentes (sel aléatoire) (RG5) | intégration |
| CA12 | Étant donné le compte `Alice`, quand `POST` avec `alice` puis `ALICE`, alors 409, `code`=`PSEUDO_DEJA_UTILISE`, `detail`=« Ce pseudo est déjà utilisé. », et la table contient toujours 1 ligne (RG3) | intégration |
| CA13 | Étant donné un jeton CSRF valide, quand `POST` avec `pseudo`=`ab` et `motDePasse`=`court-12345` (11 caractères), alors 400, `code`=`VALIDATION_ECHOUEE`, `erreurs` contient exactement `PSEUDO_LONGUEUR` (champ pseudo) et `MOT_DE_PASSE_TROP_COURT` (champ motDePasse), et le corps ne contient pas `court-12345` ; aucune ligne créée (RG2, RG4, RG6, RG8, RG13) | intégration |
| CA14 | Étant donné un jeton valide, quand `POST` avec corps `{}`, `{"pseudo":null,"motDePasse":null}`, puis `{"pseudo":123,"motDePasse":"un-mot-de-passe-12"}` et `pas-du-json`, alors les deux premiers : 400 `PSEUDO_REQUIS` + `MOT_DE_PASSE_REQUIS` ; les deux autres : 400 `CORPS_ILLISIBLE` sans écho du contenu ; mauvais `Content-Type` : 415 (RG8, RG13) | intégration |
| CA15 | Étant donné un jeton valide, quand `POST` avec `{"pseudo":"Zoe","motDePasse":"un-mot-de-passe-12","role":"ADMIN"}`, alors 201 avec `role`=`COUREUR` et la ligne en base est `COUREUR` (RG1) | intégration |
| CA16 | Étant donné aucun en-tête `X-XSRF-TOKEN`, puis un en-tête différent du cookie, quand `POST /api/comptes` valide, alors 403 `CSRF_INVALIDE` dans les deux cas et aucune ligne créée ; avec le jeton obtenu via `GET /api/csrf`, 201 (RG12) | intégration |
| CA17 | Quand `GET /api/csrf`, alors 204 sans corps, `Set-Cookie` `XSRF-TOKEN` avec `SameSite=Lax`, sans `HttpOnly` ; aucun cookie `JSESSIONID` ni sur ce GET ni sur le POST de CA9 (RG11, RG12) | intégration |
| CA18 | Quand `GET /api/inexistant` puis `GET /api/comptes` puis `POST /api/inexistant` avec jeton valide, sans authentification, alors 401 `NON_AUTHENTIFIE` en `application/problem+json`, sans `WWW-Authenticate` ; `GET /api/sante` et `GET /actuator/health` restent 200 `{"status":"UP"}` ; `GET /actuator/env` n'est pas 200 (RG10) | intégration |
| CA19 | Étant donné un journal capturé (logs de l'application), quand on enchaîne CA9, CA12, CA13 et CA14 avec le mot de passe `un-mot-de-passe-12`, alors aucune ligne de log ne contient `un-mot-de-passe-12` ni `court-12345` (RG6) | intégration |
| CA20 | Quand 5 requêtes `POST` simultanées avec le pseudo `Course` sont envoyées, alors exactement une répond 201, les 4 autres 409, aucune 500, et la table contient 1 ligne `Course` (RG3) | intégration |
| CA21 | Quand l'application démarre sur une base vide avec `ddl-auto=validate`, alors le changeset `0002` s'applique, `0001` est inchangé, la table `compte` a les colonnes et contraintes de RG14 (unicité de `pseudo_normalise`, `empreinte_mot_de_passe` nullable, rôle contraint) (RG14) | intégration |
| CA22 | Étant donné l'accueil, quand on clique sur « Créer un compte » (`lien-creer-compte`), alors l'URL est `/creer-compte`, le titre `titre-creer-compte` « Créer un compte » est visible ; l'accueil (titre, « API : disponible ») est inchangé (0.3 CA14/CA15 non régressés) (écrans Accueil et Créer un compte) | E2E |
| CA23 | Étant donné `/creer-compte`, quand on saisit un pseudo unique `coureur-<suffixe>`, le mot de passe `un-mot-de-passe-12` deux fois et qu'on clique sur le bouton, alors `message-succes` affiche « Compte créé pour coureur-<suffixe>. », le formulaire disparaît, aucun cookie `JSESSIONID` n'est présent, et le mot de passe n'apparaît ni dans l'URL ni dans le stockage local (RG1, RG9, RG11, RG16) | E2E |
| CA24 | Étant donné un compte `coureur-<suffixe>` déjà créé, quand on recrée un compte avec `COUREUR-<suffixe>` (autre casse), alors `erreur-pseudo` affiche « Ce pseudo est déjà utilisé. », le pseudo saisi est conservé et les champs mot de passe sont vidés (RG3, RG16) | E2E |
| CA25 | Étant donné `/creer-compte`, quand on saisit un mot de passe de 11 caractères puis qu'on valide, alors `erreur-mot-de-passe` affiche « Le mot de passe doit faire au moins 12 caractères. » (aucune requête `POST /api/comptes` n'est émise) ; avec 12 caractères mais confirmation différente, `erreur-confirmation` affiche « Les deux mots de passe ne correspondent pas. » (RG4, RG9, RG16) | E2E |
| CA26 | Étant donné `/creer-compte`, quand on saisit un pseudo `a b` (valide pour le client) avec un mot de passe valide, alors la réponse 400 du serveur fait afficher sous le pseudo « Le pseudo ne peut contenir que des lettres, des chiffres, « . », « _ » et « - ». » ; le champ pseudo vide affiche « Le pseudo est obligatoire. » (RG2, RG8, RG16) | E2E |
| CA27 | Étant donné `/creer-compte` ouverte, quand on supprime le cookie `XSRF-TOKEN` du navigateur puis qu'on soumet un formulaire valide, alors `erreur-generale` affiche « La page a expiré, veuillez réessayer. » ; un second envoi réussit (201, `message-succes`) (RG12) | E2E |
| CA28 | Étant donné `/creer-compte` ouverte et la route `POST /api/comptes` interceptée en 503, quand on soumet un formulaire valide, alors `erreur-generale` affiche « Service indisponible, veuillez réessayer plus tard. » sans code HTTP, et le bouton est de nouveau actif (RG16) | E2E |

Répartition : unitaire 8 (CA1 à CA8), intégration 13 (CA9 à CA21), E2E 7 (CA22 à CA28). Total 28.

Couverture des RG : RG1 CA5/CA9/CA15/CA23, RG2 CA1/CA2/CA13/CA26, RG3 CA6/CA12/CA20/CA24, RG4 CA3/CA13/CA25, RG5 CA5/CA10/CA11, RG6 CA4/CA10/CA13/CA19, RG7 CA5/CA9, RG8 CA7/CA13/CA14/CA26, RG9 CA23/CA25, RG10 CA18, RG11 CA17/CA23, RG12 CA16/CA17/CA27, RG13 CA13/CA14, RG14 CA10/CA21, RG15 CA8, RG16 CA23 à CA28. Écrans : Accueil (lien) et Créer un compte couverts en E2E par CA22 à CA28.

## 7. Tester à la main

Prérequis : Docker et `docker compose`, depuis la racine du dépôt. Aucun compte préexistant n'est nécessaire (la connexion arrive en 1.2).

1. `docker compose up -d --build` puis `docker compose ps` : `base`, `api`, `web` en `healthy`.
2. Ouvrir http://localhost : titre, « API : disponible » et un lien « Créer un compte ».
3. Cliquer sur « Créer un compte » : l'URL devient http://localhost/creer-compte, le formulaire s'affiche (pseudo, mot de passe, confirmation).
4. Saisir pseudo `Alice`, mot de passe `un-mot-de-passe-12` deux fois, valider : message « Compte créé pour Alice. … », formulaire remplacé.
5. Retourner sur `/creer-compte`, saisir `alice` (minuscules) avec un mot de passe valide : « Ce pseudo est déjà utilisé. » sous le pseudo, le pseudo reste saisi, les mots de passe sont vidés.
6. Saisir `Bob` et un mot de passe de 11 caractères (`court-12345`) : « Le mot de passe doit faire au moins 12 caractères. » Corriger avec 12 caractères mais une confirmation différente : « Les deux mots de passe ne correspondent pas. »
7. Saisir `a b` comme pseudo avec un mot de passe valide : message de caractères autorisés renvoyé par le serveur. Pseudo vide : « Le pseudo est obligatoire. »
8. Vérifier la base (aucun mot de passe en clair) :
   `docker compose exec base psql -U backyard -d backyard -c "select pseudo, pseudo_normalise, role, left(empreinte_mot_de_passe, 40) as empreinte from compte"` (les valeurs par défaut du compose ; sinon `BASE_UTILISATEUR` / `BASE_NOM` du `.env`). Attendu : une ligne `Alice | alice | COUREUR | $argon2id$v=19$m=19456,t=2,p=1$...`.
9. Vérifier les logs : `docker compose logs api | grep -c "un-mot-de-passe-12"` renvoie `0`, de même pour `court-12345`.
10. CSRF et API en ligne de commande :
    - `curl -i -X POST http://localhost/api/comptes -H "Content-Type: application/json" -d '{"pseudo":"Eve","motDePasse":"un-mot-de-passe-12"}'` : `403`, `code` `CSRF_INVALIDE`.
    - `curl -i -c /tmp/jar http://localhost/api/csrf` : `204`, cookie `XSRF-TOKEN`. Puis relancer le POST avec `-b /tmp/jar -H "X-XSRF-TOKEN: <valeur du cookie>"` : `201`, corps sans mot de passe ni hash, `role` `COUREUR`.
    - Même POST avec `-d '{"pseudo":"Zed","motDePasse":"un-mot-de-passe-12","role":"ADMIN"}'` : `201` avec `role` `COUREUR`.
    - Même POST avec `"pseudo":"alice"` : `409`.
11. `curl -i http://localhost/api/inexistant` : `401` (et non plus 404, voir RG10). `curl -i http://localhost/api/sante` : `200`.
12. `docker compose restart api` puis refaire l'étape 8 : les comptes sont toujours là.

## 8. Points ouverts

Bloquants : aucun.

Non bloquants (position par défaut retenue, à confirmer) :
1. **Unicité insensible à la casse et pseudo Unicode** : par défaut `Alice` = `alice`, lettres Unicode autorisées (accents), pas d'espace. Alternative : unicité sensible à la casse (risque d'usurpation visuelle) ou ASCII seul.
2. **Longueur du pseudo (3 à 30)** et **maximum du mot de passe (128)** : valeurs proposées, non issues de CLAUDE.md (qui impose seulement 12 minimum). À valider.
3. **Aucune règle de composition du mot de passe** (majuscule, chiffre) ni liste de mots de passe courants : conforme à CLAUDE.md (12 caractères minimum seulement) et aux recommandations NIST. Une liste de mots de passe compromis pourrait être ajoutée plus tard.
4. **Pas de limitation du débit de création de comptes** (spam de comptes, coût de calcul Argon2 sur une requête publique) : la limitation de CLAUDE.md porte sur la connexion (1.3). Une limite par IP sur `POST /api/comptes` pourrait être ajoutée ; hors périmètre par défaut.
5. **Énumération de pseudos par la création** : le 409 révèle l'existence d'un pseudo. Inévitable pour une création de compte avec pseudo unique, accepté. Le message générique exigé par CLAUDE.md concerne la connexion.
6. **Pseudos réservés** : « Coureur anonyme » (3.6) contient un espace, donc impossible à créer avec RG2 ; les pseudos d'anonymisation devront aussi respecter la contrainte d'unicité (ex. `anonyme-<id>`). Faut-il interdire dès maintenant des pseudos comme `admin` ? Par défaut non.
7. **Confirmation du mot de passe contrôlée côté front seulement** (RG9) : l'API n'a pas le champ. Alternative : l'envoyer et le contrôler côté serveur.
8. **Spring Security dès 1.1** : nécessaire car le CSRF doit être actif sur le premier POST ; effet de bord assumé : `/api/inexistant` passe de 404 à 401 (modifie 0.2 CA6, à mettre à jour dans le test correspondant s'il est automatisé). La config `STATELESS` sera remplacée en 1.2 par la session serveur.
9. **Attribut `Secure` du cookie `XSRF-TOKEN`** : en HTTPS (production) il doit être posé, ce qui impose que l'API honore `X-Forwarded-Proto` de Caddy (`server.forward-headers-strategy`). En local HTTP (`http://localhost`) il est absent. À vérifier en 0.4 (https://localhost) lors du test à la main de production.
10. **Taille de l'incrément** : estimé à environ 450 lignes de production (domaine et cas d'usage ~110, persistance + Liquibase ~90, sécurité + gestion des erreurs ~110, contrôleur + DTO ~50, front ~110), à la limite des 400. Si la relecture dépasse 20 minutes, découpage proposé : **1.1a** socle sécurité (Spring Security, CSRF, `GET /api/csrf`, format `ProblemDetail`, 401 par défaut) sans écran ; **1.1b** création du compte (domaine, table, endpoint, écran). Non retenu par défaut car 1.1a serait invisible pour l'utilisateur (contraire aux règles de la roadmap).
11. **Versions** : BouncyCastle (requis par `Argon2PasswordEncoder`) et Angular Router : choix du développeur, versions fixées.
