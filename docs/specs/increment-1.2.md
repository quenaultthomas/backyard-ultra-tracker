# Increment 1.2 : Se connecter et se déconnecter

Un Compte existant (créé en 1.1) se connecte par pseudo + mot de passe, obtient une session serveur, voit son pseudo dans l'en-tête de l'application et peut se déconnecter. Cet incrément remplace la politique `STATELESS` de 1.1 par la session serveur et met à jour les règles d'accès `/api/**`. La limitation des tentatives est hors périmètre (1.3).

## 1. Périmètre

**Inclus**
- Cas d'usage `Connecter` (contexte `comptes`), vérification Argon2id via le port `EncodeurMotDePasse` existant (1.1), ajout éventuel d'une méthode de vérification si absente.
- Endpoints `POST /api/connexion`, `POST /api/deconnexion`, `GET /api/comptes/moi`.
- Session serveur (cookie `JSESSIONID`, `HttpOnly`, `SameSite=Lax`, `Secure` en HTTPS), rotation de l'identifiant de session à la connexion, expiration.
- Message d'erreur de connexion générique, sans énumération de pseudos (contenu et temps de réponse). Compte sans empreinte (anonymisé, 3.6) : connexion impossible.
- Règles d'accès `/api/**` à jour (remplace RG10 de 1.1), refus `POST /api/connexion` et `POST /api/comptes` pour un utilisateur déjà connecté.
- Front : écran « Se connecter » (`/connexion`), en-tête global (pseudo, « Se connecter », « Se déconnecter »), restauration de l'état connecté au rechargement, redirection après connexion, adaptation de « Créer un compte » (1.1).

**Exclu**
- Blocage temporaire après échecs répétés : 1.3. Aucun compteur, aucun délai, aucun code 429 en 1.2 : un nombre illimité d'essais est possible jusqu'à 1.3 (point ouvert 4).
- Création de l'admin master et des comptes `ADMIN` / `BENEVOLE`, contrôle des rôles par endpoint : 1.4 à 1.6. En 1.2 un seul niveau d'accès existe : « authentifié, quel que soit le rôle ». Le code 403 `ACCES_REFUSE` est défini (RG19) mais aucun endpoint ne le produit encore.
- Changement de mot de passe : 1.6. Anonymisation / suppression de compte : 3.6 (1.2 garantit seulement qu'un compte sans empreinte ne se connecte pas et que `GET /api/comptes/moi` détecte un compte disparu, RG16).
- Pages réservées aux utilisateurs connectés, intercepteur HTTP « session expirée » (redirection sur 401 des appels métier) : premiers incréments qui appellent un endpoint authentifié (2.x). En 1.2 le seul appel authentifié est `GET /api/comptes/moi`.
- Sessions partagées / persistantes entre redémarrages de `api` : non (point ouvert 3). « Se souvenir de moi » : non.
- Aucune nouvelle variable d'environnement, aucun changement de `docker-compose*.yml` ni de Caddy (le cookie de session traverse le proxy `/api` tel quel).
- Pas de nouveau changeset Liquibase (aucun changement de schéma).

## 2. Règles de gestion

**Connexion**
- **RG1** : `POST /api/connexion` est public (avec jeton CSRF, RG15). Corps `{pseudo, motDePasse}`. En cas de succès : 200, une session serveur est créée, la réponse est le Compte courant (`CompteReponse`, même forme que 1.1, sans hash ni `pseudoNormalise`).
- **RG2** : identification. Le pseudo reçu est débarrassé de ses espaces de début et de fin puis comparé en minuscules (`toLowerCase(Locale.ROOT)`) à `pseudo_normalise` (même normalisation que RG3 de 1.1). `ALICE`, `alice` et ` Alice ` désignent le compte `Alice`. Le mot de passe est comparé tel quel (sans `trim`) à l'empreinte Argon2id.
- **RG3** : un Compte dont `empreinte_mot_de_passe` est nul (anonymisé, 3.6) ne peut jamais se connecter, quel que soit le mot de passe envoyé. Règle portée par le domaine (`Compte.peutSeConnecter()`).
- **RG4** : message générique. Tous les échecs d'authentification (pseudo inconnu, mot de passe faux, compte sans empreinte, valeurs hors bornes RG6) renvoient exactement la même réponse : 401, `code` `IDENTIFIANTS_INVALIDES`, `title` « Authentification échouée », `detail` « Pseudo ou mot de passe incorrect. », mêmes en-têtes, aucun cookie de session. Aucun élément (champ, message, en-tête, log applicatif de niveau INFO ou supérieur) ne permet de distinguer ces cas.
- **RG5** : temps de réponse. Pour un pseudo inconnu ou un compte sans empreinte, le cas d'usage exécute quand même une vérification Argon2id complète contre une empreinte factice (calculée au démarrage avec les mêmes paramètres que RG5 de 1.1, à partir d'une valeur aléatoire jamais conservée), puis rejette. Le coût de calcul est donc du même ordre que pour un mot de passe faux. Seules les valeurs hors bornes (RG6) sont rejetées sans calcul.
- **RG6** : à la connexion, les règles de format de 1.1 (RG2 pseudo, RG4 mot de passe) ne sont pas appliquées : `a b` ou un mot de passe de 5 caractères donnent un 401 générique et non un 400. Seules bornes : pseudo de plus de 30 caractères (après `trim`) ou mot de passe de plus de 128 caractères (en points de code) : 401 générique immédiat, sans calcul (borne le coût de hachage d'une requête publique).
- **RG7** : champs requis. `pseudo` absent, `null` ou vide après `trim` : `PSEUDO_REQUIS`. `motDePasse` absent, `null` ou chaîne vide : `MOT_DE_PASSE_REQUIS` (une chaîne d'espaces est une valeur, traitée comme un mot de passe faux). Les deux erreurs peuvent être renvoyées ensemble (400 `VALIDATION_ECHOUEE`, même forme que 1.1).
- **RG8** : le mot de passe n'apparaît jamais (logs, exceptions, `ProblemDetail`, `toString()`), règle RG6 de 1.1 étendue : `ConnexionRequete.toString()` = `ConnexionRequete[pseudo=..., motDePasse=masqué]`. Un échec de connexion est journalisé sans pseudo ni mot de passe (message fixe du type « Échec de connexion »). Une connexion réussie peut journaliser l'identifiant (UUID) du compte, pas le pseudo.
- **RG9** : aucune limitation du nombre d'essais en 1.2 (1.3).

**Session**
- **RG10** : une session serveur n'est créée que par une connexion réussie. Les requêtes anonymes (GET /api/csrf, POST /api/comptes, connexion échouée, appels refusés en 401) ne créent aucune session et ne posent aucun cookie `JSESSIONID` (maintient 1.1 CA17). La session contient l'identité authentifiée : identifiant du Compte et rôle. Le rôle donne l'autorité Spring `ROLE_<role>` (non exploitée avant 1.4).
- **RG11** : à la connexion réussie, l'identifiant de session est renouvelé (protection contre la fixation de session, `changeSessionId`) : un `JSESSIONID` fourni avant la connexion, même forgé, n'est jamais celui de la session authentifiée et ne donne aucun accès.
- **RG12** : cookie de session : nom `JSESSIONID`, `Path=/`, `HttpOnly`, `SameSite=Lax`, `Secure` dès que la requête est en HTTPS (via `X-Forwarded-Proto` de Caddy, même mécanisme que `XSRF-TOKEN`, RG12 de 1.1). Absent en local `http://localhost`, toujours présent en production (HTTPS seul). Cookie de session, sans `Max-Age` ni `Expires`.
- **RG13** : expiration : 12 heures d'inactivité côté serveur (`server.servlet.session.timeout=12h`, dans `application.yml`, pas de variable d'environnement). Les sessions sont en mémoire : un redémarrage de `api` déconnecte tout le monde (point ouvert 3). Plusieurs sessions simultanées d'un même compte sont autorisées.
- **RG14** : `POST /api/deconnexion` (public, avec jeton CSRF) invalide la session serveur, répond 204 et efface le cookie (`Set-Cookie: JSESSIONID=; Max-Age=0; Path=/`). Idempotent : sans session, 204 aussi. Un `JSESSIONID` invalidé n'ouvre plus aucun accès.
- **RG15** : CSRF : `POST /api/connexion` et `POST /api/deconnexion` exigent `X-XSRF-TOKEN` égal au cookie `XSRF-TOKEN` (mécanisme 1.1 inchangé, 403 `CSRF_INVALIDE`). Le contrat ne promet pas que le jeton soit renouvelé par ces réponses : le front rappelle `GET /api/csrf` après une connexion réussie et après une déconnexion avant tout nouveau POST.
- **RG16** : `GET /api/comptes/moi` renvoie le Compte courant relu en base (pas une copie de la session). Si le Compte n'existe plus ou n'a plus d'empreinte (anonymisé), la session est invalidée et la réponse est 401 `NON_AUTHENTIFIE`. C'est l'appel de restauration d'état du front (RG20).

**Règles d'accès (remplace RG10 de 1.1)**
- **RG17** : politique `/api/**` et actuator :

| Requête | Accès |
|---|---|
| `GET /api/sante`, `GET /actuator/health` | public |
| `GET /api/csrf` | public |
| `POST /api/comptes` | public, mais 409 `DEJA_CONNECTE` si déjà connecté (RG18) |
| `POST /api/connexion` | public, mais 409 `DEJA_CONNECTE` si déjà connecté (RG18) |
| `POST /api/deconnexion` | public (idempotent) |
| `GET /api/comptes/moi` | authentifié (tout rôle) |
| tout autre `/api/**`, existant ou non | authentifié, sinon 401 `NON_AUTHENTIFIE` ; authentifié sur un chemin inexistant : 404 `RESSOURCE_INTROUVABLE` |
| autres chemins actuator | non exposés (inchangé) |

  Ce changement modifie 1.1 CA18 : `GET /api/inexistant` reste 401 sans session mais devient 404 avec session. Aucune redirection, pas de `WWW-Authenticate`.
- **RG18** : impact sur 1.1. Un utilisateur déjà connecté qui appelle `POST /api/connexion` ou `POST /api/comptes` reçoit 409 `DEJA_CONNECTE`, `title` « Conflit », `detail` « Vous êtes déjà connecté. », sans effet (aucun compte créé, session inchangée). Ordre de contrôle : CSRF (403), puis déjà connecté (409), puis lecture/validation du corps (415/400), puis authentification ou création. Les autres comportements de `POST /api/comptes` (1.1 RG1 à RG9) sont inchangés.
- **RG19** : formats d'erreur de sécurité : 401 `NON_AUTHENTIFIE` (« Authentification requise » / « Vous devez être connecté. », inchangé de 1.1) ; 403 `ACCES_REFUSE` pour un utilisateur authentifié sans le rôle requis (« Accès refusé » / « Vous n'avez pas les droits nécessaires. », format défini ici, utilisé à partir de 1.4) ; 403 `CSRF_INVALIDE` (inchangé) ; 404 `RESSOURCE_INTROUVABLE` (« Introuvable » / « La ressource demandée est introuvable. »). Tous en `application/problem+json`.

**Front**
- **RG20** : restauration de l'état. Au démarrage de l'application (chargement ou rechargement de n'importe quelle page), le front appelle `GET /api/comptes/moi` : 200 = connecté (pseudo mémorisé en mémoire), 401 = anonyme. Tant que la réponse n'est pas arrivée, l'en-tête n'affiche ni « Se connecter » ni le pseudo (pas de clignotement). L'état connecté n'est jamais stocké dans `localStorage`, `sessionStorage` ni un cookie lisible : la seule source de vérité est la session serveur. Erreur réseau / 5xx : état anonyme et l'application reste utilisable.
- **RG21** : en-tête global (toutes les pages). Anonyme : lien « Se connecter » (`/connexion`). Connecté : pseudo (tel qu'en base) et bouton « Se déconnecter ».
- **RG22** : redirection après connexion : vers le chemin du paramètre de requête `retour` s'il est valide, sinon vers `/`. `retour` est valide uniquement s'il commence par un seul `/` suivi d'un caractère autre que `/` ou `\` (chemin interne), sinon ignoré (anti redirection ouverte). Exemple : `/connexion?retour=/creer-compte` mène à `/creer-compte`, `?retour=//exemple.org` et `?retour=https://exemple.org` mènent à `/`. En 1.2 aucune page n'ajoute elle-même `retour` (pas de page protégée) ; le mécanisme est en place pour 2.x.
- **RG23** : `/connexion` et `/creer-compte` sont réservés aux anonymes : un utilisateur connecté qui les ouvre est redirigé vers `/` (cohérent avec RG18).
- **RG24** : écran de connexion. Contrôles côté client (confort) : pseudo et mot de passe non vides. Le champ mot de passe est de type `password`, `autocomplete="current-password"`. Après un échec (quel qu'il soit) ou un succès, le champ mot de passe est vidé ; après un échec, le pseudo saisi est conservé. Le mot de passe n'est jamais stocké côté navigateur ni placé dans l'URL. Un 401 affiche toujours le même message, sans distinction.
- **RG25** : déconnexion front : clic sur « Se déconnecter » : `POST /api/deconnexion` puis `GET /api/csrf`, état local remis à anonyme, navigation vers `/connexion` avec le message « Vous êtes déconnecté. ». Si l'appel échoue (réseau/5xx), l'état local reste connecté et le message « Service indisponible, veuillez réessayer plus tard. » est affiché dans l'en-tête.
- **RG26** : adaptations de 1.1 : message de succès de « Créer un compte » : « Compte créé pour <pseudo>. Vous pouvez maintenant vous connecter. » avec un lien « Se connecter » (`/connexion`) ; toujours aucune connexion automatique. L'accueil masque le lien « Créer un compte » quand l'utilisateur est connecté.

**Architecture**
- **RG27** : Clean Architecture. Le cas d'usage `Connecter` est dans `comptes.application` ; la règle « compte sans empreinte = connexion impossible » est dans `Compte` (domaine) ; le domaine reste du Java pur. Contrôleurs sans logique métier. La configuration Spring Security (session, CSRF, matrice RG17, gestionnaires 401/403/404/409) vit dans `comptes.infrastructure`. Les règles ArchUnit passent sans exception. `courses` n'est pas touché.

## 3. Cas limites

- **Casse et espaces** : `ALICE`, `alice`, `  Alice ` se connectent au compte `Alice` ; l'en-tête affiche `Alice` (casse de la création).
- **Mot de passe** : la casse compte ; des espaces de début/fin comptent (`un-mot-de-passe-12 ` est faux si le mot de passe est `un-mot-de-passe-12`).
- **Valeurs hors bornes** : pseudo de 31 caractères, mot de passe de 129 caractères : 401 générique (jamais 400). Mot de passe de 128 caractères exact : traité normalement.
- **Pseudo au format invalide** (`a b`, `<script>`) : 401 générique, pas de 400 ni de message de format.
- **Corps** : `{}` ou champs `null` : 400 `PSEUDO_REQUIS` + `MOT_DE_PASSE_REQUIS` ; corps non JSON ou mal typé : 400 `CORPS_ILLISIBLE` ; mauvais `Content-Type` : 415. Un champ inconnu (`role`) est ignoré.
- **Compte anonymisé / sans empreinte** (simulable en base par `update compte set empreinte_mot_de_passe = null`) : 401 générique pour tout mot de passe, avec le même temps de calcul qu'un mot de passe faux.
- **Compte supprimé ou anonymisé pendant une session ouverte** : le prochain `GET /api/comptes/moi` répond 401 et invalide la session ; le rechargement de la page affiche l'état anonyme.
- **Fixation de session** : requête avec un cookie `JSESSIONID=forge` avant connexion : après connexion réussie, le serveur renvoie un nouvel identifiant ; `JSESSIONID=forge` n'ouvre aucun accès.
- **Déjà connecté** : `POST /api/connexion` (mêmes ou autres identifiants) et `POST /api/comptes` : 409 `DEJA_CONNECTE`, y compris avec un corps invalide (le 409 précède la validation). Sans jeton CSRF : 403 prioritaire.
- **Deux onglets** : se déconnecter dans l'onglet A ; dans l'onglet B (état local encore connecté), la prochaine action serveur donnera 401 ; l'état n'est resynchronisé qu'au rechargement (pas d'intercepteur en 1.2). Cas accepté.
- **Rechargement** : après connexion, F5 sur `/`, `/creer-compte` ou `/connexion` : l'état connecté est restauré via `GET /api/comptes/moi` (RG20) ; sur les deux dernières, redirection vers `/` (RG23).
- **Session expirée / cookie supprimé** : au rechargement, `GET /api/comptes/moi` renvoie 401, l'en-tête passe à « Se connecter ».
- **Redémarrage de `api`** : toutes les sessions sont perdues (RG13) ; les comptes persistent.
- **CSRF** : cookie `XSRF-TOKEN` absent ou erroné lors de la connexion/déconnexion : 403 `CSRF_INVALIDE`, l'écran affiche « La page a expiré, veuillez réessayer. » et redemande un jeton.
- **Double clic** sur « Se connecter » : bouton désactivé pendant l'envoi ; une seconde requête (éventuelle) recevrait 409 `DEJA_CONNECTE`, que le front traite en rafraîchissant l'état (`GET /api/comptes/moi`) puis en redirigeant (RG22).
- **Réseau / API indisponible** : « Service indisponible, veuillez réessayer plus tard. » (5xx, 502/504, erreur réseau) ; pseudo conservé, mot de passe vidé.
- **Plusieurs sessions** d'un même compte : autorisées, indépendantes ; la déconnexion n'invalide que la session courante.
- **Course, Boucle, Passage, Inscription, Réintégration, bénévole non affecté, rôle insuffisant (hors format RG19)** : non applicables en 1.2.

## 4. Contrat d'API

Toutes les erreurs sont des `ProblemDetail` (`application/problem+json`) : `{ "type": "about:blank", "title", "status", "detail", "code" }`, plus `erreurs` pour les 400 de validation (forme de 1.1).

### POST /api/connexion
- Rôle requis : aucun (public). En-têtes : `Content-Type: application/json`, `X-XSRF-TOKEN`.
- **Requête `ConnexionRequete`** :

| Champ | Type | Obligatoire | Règle |
|---|---|---|---|
| `pseudo` | string | oui | non vide après `trim` ; pas d'autre contrôle de format (RG6, RG7) |
| `motDePasse` | string | oui | non vide ; pas d'autre contrôle de format ; jamais renvoyé ni journalisé |

- **200** : `CompteReponse` `{ id: string (UUID), pseudo: string, role: "ADMIN_MASTER"|"ADMIN"|"BENEVOLE"|"COUREUR", creeLe: string (ISO-8601 UTC) }`, plus `Set-Cookie: JSESSIONID=<nouvel identifiant>; Path=/; HttpOnly; SameSite=Lax[; Secure]`.
- **400** `VALIDATION_ECHOUEE` (« Requête invalide » / « Certains champs sont invalides. ») avec `erreurs` : `{champ:"pseudo", code:"PSEUDO_REQUIS", message:"Le pseudo est obligatoire."}` et/ou `{champ:"motDePasse", code:"MOT_DE_PASSE_REQUIS", message:"Le mot de passe est obligatoire."}`.
- **400** `CORPS_ILLISIBLE` (« Requête invalide » / « Le corps de la requête est illisible. »).
- **401** `IDENTIFIANTS_INVALIDES` : « Authentification échouée » / « Pseudo ou mot de passe incorrect. » (RG4). Sans `WWW-Authenticate`, sans `Set-Cookie`.
- **403** `CSRF_INVALIDE` : « Accès refusé » / « Jeton CSRF absent ou invalide. ».
- **409** `DEJA_CONNECTE` : « Conflit » / « Vous êtes déjà connecté. ».
- **415** : `Content-Type` non JSON. 404 : non applicable.

### POST /api/deconnexion
- Rôle requis : aucun (public, idempotent). En-tête `X-XSRF-TOKEN`. Pas de corps.
- **204** sans corps, `Set-Cookie: JSESSIONID=; Max-Age=0; Path=/` (HttpOnly, SameSite=Lax).
- **403** `CSRF_INVALIDE`. 400/401/404/409 : non applicables.

### GET /api/comptes/moi
- Rôle requis : authentifié (tout rôle).
- **200** `CompteReponse` (même forme que ci-dessus), relu en base (RG16). Aucun `Set-Cookie`.
- **401** `NON_AUTHENTIFIE` : pas de session valide, session expirée, ou Compte disparu/sans empreinte (session alors invalidée). 400/403/404/409 : non applicables.

### Endpoints modifiés ou inchangés
- `POST /api/comptes` (1.1) : ajout du **409 `DEJA_CONNECTE`** si l'utilisateur est connecté (RG18) ; le reste est inchangé (201 `CompteReponse`, 400, 403, 409 `PSEUDO_DEJA_UTILISE`, 415).
- `GET /api/csrf` : inchangé (204, cookie `XSRF-TOKEN`, public).
- `GET /api/sante`, `GET /actuator/health` : inchangés.
- Tout autre `/api/**` : 401 `NON_AUTHENTIFIE` sans session ; avec session, 404 `RESSOURCE_INTROUVABLE` si le chemin n'existe pas, 403 `ACCES_REFUSE` si le rôle est insuffisant (à partir de 1.4).

## 5. Écrans

### En-tête global (toutes les pages)
- **Affiché** : nom de l'application (`data-testid="entete-titre"`) ; zone d'état vide tant que `GET /api/comptes/moi` n'a pas répondu (RG20). Anonyme : lien « Se connecter » (`lien-se-connecter`). Connecté : pseudo (`entete-pseudo`) et bouton « Se déconnecter » (`bouton-deconnexion`).
- **Actions** : « Se connecter » vers `/connexion` ; « Se déconnecter » (RG25).
- **Erreurs** : échec de déconnexion : « Service indisponible, veuillez réessayer plus tard. » (`entete-erreur`).

### Se connecter (`/connexion`)
- **Affiché** : titre « Se connecter » (`titre-connexion`) ; champs « Pseudo » (`champ-pseudo`) et « Mot de passe » (`champ-mot-de-passe`) ; lien « Créer un compte » (`lien-creer-compte-connexion`, vers `/creer-compte`) ; lien « Retour à l'accueil » (`lien-accueil`) ; message « Vous êtes déconnecté. » (`message-deconnexion`) quand on arrive après une déconnexion.
- **Actions** : bouton « Se connecter » (`bouton-connexion`), désactivé pendant l'envoi. Au chargement : `GET /api/csrf`. À l'envoi : `POST /api/connexion` avec `pseudo` et `motDePasse` uniquement, puis `GET /api/csrf`, puis mise à jour de l'état et redirection (RG22). Appuyer sur Entrée valide le formulaire.
- **Messages d'erreur** (`erreur-pseudo`, `erreur-mot-de-passe`, `erreur-generale`) :
  - Client : « Le pseudo est obligatoire. », « Le mot de passe est obligatoire. » (pas d'envoi).
  - 401 `IDENTIFIANTS_INVALIDES` : « Pseudo ou mot de passe incorrect. » (`erreur-generale`), identique dans tous les cas.
  - 400 `VALIDATION_ECHOUEE` : `erreurs[].message` sous le champ concerné.
  - 403 `CSRF_INVALIDE` : « La page a expiré, veuillez réessayer. » (`erreur-generale`), nouveau `GET /api/csrf`.
  - 409 `DEJA_CONNECTE` : pas de message, rafraîchissement de l'état puis redirection (RG22).
  - 400 `CORPS_ILLISIBLE`, 5xx, 502/504, réseau : « Service indisponible, veuillez réessayer plus tard. ». Jamais de code HTTP ni de détail technique.

### Accueil (`/`) et Créer un compte (`/creer-compte`) : évolutions
- Accueil : titre et indicateur d'état de l'API inchangés (`titre`, `etat-api`) ; `lien-creer-compte` visible seulement en anonyme.
- Créer un compte : message de succès et lien `lien-se-connecter-succes` (RG26) ; le reste inchangé. Un utilisateur connecté est redirigé vers `/` (RG23).

## 6. Critères d'acceptation

Valeurs chiffrées à utiliser dans les tests. Les tests E2E créent leurs comptes via l'API avec un suffixe aléatoire (la base persiste entre exécutions). Mot de passe de référence : `un-mot-de-passe-12`.

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné un dépôt en mémoire avec le compte `Alice` (empreinte factice valide pour `un-mot-de-passe-12`), quand `Connecter` reçoit `"  ALICE "` / `un-mot-de-passe-12`, alors le Compte `Alice` est retourné (RG1, RG2) | unitaire |
| CA2 | Étant donné le compte `Alice`, quand `Connecter` reçoit `Alice` / `un-mot-de-passe-13` (faux) puis `Alice` / `un-mot-de-passe-12 ` (espace final), alors `IdentifiantsInvalidesException` à chaque fois, avec un message identique à celui de CA3 (RG2, RG4) | unitaire |
| CA3 | Étant donné un dépôt sans `Inconnu`, quand `Connecter` reçoit `Inconnu` / `un-mot-de-passe-12`, alors `IdentifiantsInvalidesException` (même message que CA2) et l'encodeur factice a reçu exactement 1 appel de vérification (contre l'empreinte factice) (RG4, RG5) | unitaire |
| CA4 | Étant donné un compte `Anonyme1` dont l'empreinte est nulle, quand `Connecter` reçoit `Anonyme1` / n'importe quel mot de passe, alors `IdentifiantsInvalidesException` (même message) et 1 appel de vérification contre l'empreinte factice ; `Compte.peutSeConnecter()` vaut `false` sans empreinte et `true` avec (RG3, RG5) | unitaire |
| CA5 | Étant donné un pseudo de 31 caractères puis un mot de passe de 129 caractères, quand `Connecter` s'exécute, alors `IdentifiantsInvalidesException` et 0 appel à l'encodeur ; étant donné `a b` / `court` (5 caractères), alors `IdentifiantsInvalidesException` après 1 appel de vérification, jamais une violation de format ; un mot de passe de 128 caractères est vérifié normalement (RG6) | unitaire |
| CA6 | Étant donné pseudo `null` ou `"   "` et mot de passe `null` ou `""`, quand `Connecter` s'exécute, alors violations `PSEUDO_REQUIS` et `MOT_DE_PASSE_REQUIS` ensemble ; un mot de passe `"            "` (espaces) n'est pas une violation ; le dépôt n'est pas interrogé (RG7) | unitaire |
| CA7 | Étant donné `ConnexionRequete("Alice", "secret-de-test-123")`, quand `toString()` et les messages des exceptions de `Connecter`, alors `ConnexionRequete[pseudo=Alice, motDePasse=masqué]` et aucune occurrence de `secret-de-test-123` (RG8) | unitaire |
| CA8 | Quand `./mvnw test`, alors les règles ArchUnit (domaine sans Spring/JPA/Jackson, exposition sans infrastructure, `courses` indépendant) passent avec les classes ajoutées ; `Connecter` est dans `comptes.application` (RG27) | unitaire |
| CA9 | Étant donné un `Clock` fixe et le compte `Alice`, quand `Connecter` réussit, alors le Compte retourné expose id, pseudo `Alice`, rôle `COUREUR` et `creeLe` d'origine, et ne modifie pas le dépôt (aucun enregistrement) (RG1) | unitaire |
| CA10 | Étant donné le compte `Alice` en base et un jeton CSRF valide, quand `POST /api/connexion` `{"pseudo":"Alice","motDePasse":"un-mot-de-passe-12"}`, alors 200, corps `{id (UUID), pseudo:"Alice", role:"COUREUR", creeLe}` sans hash ni `pseudoNormalise`, et un `Set-Cookie: JSESSIONID` (RG1) | intégration |
| CA11 | Étant donné `Alice`, quand `POST /api/connexion` avec pseudo `ALICE`, puis `"  alice "`, alors 200 avec `pseudo`=`Alice` à chaque fois ; avec mot de passe `UN-MOT-DE-PASSE-12` : 401 (RG2) | intégration |
| CA12 | Étant donné `Alice` et un compte `Anonyme1` à empreinte nulle, quand `POST /api/connexion` avec (`Alice`, `mauvais-mot-de-passe-1`), (`Inconnu`, `un-mot-de-passe-12`), (`Anonyme1`, `un-mot-de-passe-12`), alors 3 réponses 401 au corps strictement identique (`code` `IDENTIFIANTS_INVALIDES`, `detail` « Pseudo ou mot de passe incorrect. »), mêmes en-têtes (hors date), sans `Set-Cookie` de session, sans pseudo ni mot de passe dans le corps (RG3, RG4) | intégration |
| CA13 | Étant donné un encodeur espionné, quand `POST /api/connexion` avec (`Alice`, mot de passe faux), (`Inconnu`, mot de passe), (`Anonyme1`, mot de passe), alors chacun provoque exactement 1 vérification Argon2id (appel de vérification observé), l'empreinte factice utilisant `m=19456,t=2,p=1` (RG5) | intégration |
| CA14 | Quand `POST /api/connexion` avec pseudo de 31 caractères / mot de passe valide, `Alice` / mot de passe de 129 caractères, `a b` / `court-12345`, alors 401 `IDENTIFIANTS_INVALIDES` identique à CA12 dans les trois cas (jamais 400) ; pseudo de 30 caractères inexistant : 401 après vérification (RG6) | intégration |
| CA15 | Quand `POST /api/connexion` avec `{}`, `{"pseudo":null,"motDePasse":null}`, `{"pseudo":"  ","motDePasse":"x"}`, alors 400 `VALIDATION_ECHOUEE` avec respectivement `PSEUDO_REQUIS`+`MOT_DE_PASSE_REQUIS`, idem, `PSEUDO_REQUIS` seul ; `{"pseudo":123,"motDePasse":"x"}` et `pas-du-json` : 400 `CORPS_ILLISIBLE` sans écho ; `Content-Type: text/plain` : 415 (RG7) | intégration |
| CA16 | Étant donné un journal capturé, quand on enchaîne une connexion réussie, un échec avec `mauvais-mot-de-passe-1` et un échec avec le pseudo `Inconnu`, alors aucune ligne de log ne contient `un-mot-de-passe-12`, `mauvais-mot-de-passe-1` ni `Inconnu` (RG8) | intégration |
| CA17 | Quand la connexion de CA10 est faite en HTTP, alors le `Set-Cookie` contient `HttpOnly`, `SameSite=Lax`, `Path=/`, ni `Max-Age` ni `Expires`, et pas `Secure` ; avec l'en-tête `X-Forwarded-Proto: https`, le cookie contient `Secure` (RG12) | intégration |
| CA18 | Étant donné un cookie `JSESSIONID=forge` envoyé avec la requête de connexion, quand la connexion réussit, alors le `JSESSIONID` renvoyé est différent de `forge` ; `GET /api/comptes/moi` avec `JSESSIONID=forge` répond 401 et avec le nouveau cookie 200 ; si une session authentifiée existait déjà, l'ancien identifiant ne donne plus accès (RG11) | intégration |
| CA19 | Quand `GET /api/csrf`, `POST /api/comptes` (201), une connexion en échec (401) et `GET /api/comptes/moi` sans session (401), alors aucune de ces réponses ne contient `JSESSIONID` (RG10) | intégration |
| CA20 | Étant donné une connexion réussie, quand on lit la session serveur, alors `maxInactiveInterval` vaut 43200 s (12 h) et la session contient l'identifiant du compte et le rôle `COUREUR` ; une session simulée expirée (invalidée) donne 401 sur `GET /api/comptes/moi` (RG10, RG13) | intégration |
| CA21 | Étant donné une session ouverte, quand `POST /api/deconnexion` avec jeton CSRF, alors 204 sans corps et `Set-Cookie: JSESSIONID=` avec `Max-Age=0` ; ensuite `GET /api/comptes/moi` avec l'ancien cookie répond 401 ; `POST /api/deconnexion` sans session répond aussi 204 ; deux sessions du même compte : en fermer une laisse l'autre valide (RG14) | intégration |
| CA22 | Quand `POST /api/connexion` (identifiants valides) puis `POST /api/deconnexion` sans en-tête `X-XSRF-TOKEN`, puis avec un en-tête différent du cookie, alors 403 `CSRF_INVALIDE` dans les quatre cas, aucune session créée ni invalidée ; avec le jeton de `GET /api/csrf`, 200 puis 204 (RG15) | intégration |
| CA23 | Étant donné une session de `Alice`, quand `GET /api/comptes/moi`, alors 200 `{id, pseudo:"Alice", role:"COUREUR", creeLe}` ; sans session, 401 `NON_AUTHENTIFIE` ; si le pseudo du compte est modifié en base, la réponse reflète la valeur en base (RG16) | intégration |
| CA24 | Étant donné une session de `Alice`, quand la ligne `compte` est supprimée (puis, dans un second essai, `empreinte_mot_de_passe` mise à NULL), alors `GET /api/comptes/moi` répond 401 `NON_AUTHENTIFIE` et un second appel avec le même cookie répond 401 (session invalidée) (RG16) | intégration |
| CA25 | Étant donné les règles de RG17, quand on appelle sans session : `GET /api/sante`, `/actuator/health`, `/api/csrf` (200/200/204) ; `GET /api/comptes/moi`, `GET /api/comptes`, `GET /api/inexistant`, `POST /api/inexistant` avec jeton (401 `NON_AUTHENTIFIE`, sans `WWW-Authenticate`) ; avec session : `GET /api/inexistant` et `POST /api/inexistant` avec jeton répondent 404 `RESSOURCE_INTROUVABLE` ; `GET /actuator/env` n'est pas 200 dans les deux cas ; toutes les erreurs en `application/problem+json` (RG17, RG19) | intégration |
| CA26 | Étant donné une session de `Alice`, quand `POST /api/connexion` (mêmes identifiants, puis ceux de `Bob`) puis `POST /api/comptes` valide (`Nouveau` / `un-mot-de-passe-12`) puis `POST /api/comptes` avec corps `{}`, alors 409 `DEJA_CONNECTE` (« Vous êtes déjà connecté. ») dans les quatre cas, aucune ligne `Nouveau` créée, la session reste celle d'Alice ; sans jeton CSRF : 403 prioritaire (RG18) | intégration |
| CA27 | Étant donné `Alice`, quand 10 connexions échouent d'affilée puis une connexion correcte, alors la dernière répond 200 (aucune limitation en 1.2). Ce CA est remplacé par ceux de 1.3 à la livraison de 1.3 (RG9) | intégration |
| CA28 | Étant donné un compte `coureur-<suffixe>` existant, quand on ouvre `/connexion`, saisit le pseudo et `un-mot-de-passe-12` et clique sur `bouton-connexion`, alors l'URL devient `/`, `entete-pseudo` affiche `coureur-<suffixe>`, `bouton-deconnexion` est visible, `lien-se-connecter` absent ; après rechargement (F5) l'état est identique ; le même résultat s'obtient en saisissant le pseudo en majuscules (RG1, RG2, RG20, RG21) | E2E |
| CA29 | Étant donné `/connexion`, quand on saisit le pseudo existant avec `mauvais-mot-de-passe-1`, puis un pseudo inexistant `fantome-<suffixe>`, alors `erreur-generale` affiche dans les deux cas exactement « Pseudo ou mot de passe incorrect. », le pseudo saisi est conservé, le mot de passe est vidé, `entete-pseudo` est absent et l'URL reste `/connexion` (RG4, RG24) | E2E |
| CA30 | Étant donné un utilisateur connecté, quand il clique sur `bouton-deconnexion`, alors l'URL devient `/connexion`, `message-deconnexion` affiche « Vous êtes déconnecté. », l'en-tête affiche `lien-se-connecter`, un rechargement reste en état anonyme, et une nouvelle connexion réussit sans erreur CSRF (RG14, RG15, RG25) | E2E |
| CA31 | Étant donné un compte existant, quand on ouvre `/connexion?retour=/creer-compte` et qu'on se connecte, alors l'URL finale est `/` : `/creer-compte` est réservé aux anonymes (RG23) ; avec `/connexion?retour=//exemple.org` et `?retour=https://exemple.org`, l'URL finale est `/` sur l'origine de l'application (RG22). Le chemin valide est vérifié en lecture directe du cas ci-dessus dès qu'une page connectée existe (2.x) | E2E |
| CA32 | Étant donné un utilisateur connecté, quand il ouvre `/connexion` puis `/creer-compte` par l'URL, alors il est redirigé vers `/` les deux fois, et `lien-creer-compte` est absent de l'accueil (RG18, RG23, RG26) | E2E |
| CA33 | Étant donné un visiteur anonyme, quand il ouvre `/`, alors `lien-se-connecter` est dans l'en-tête et `lien-creer-compte` sur l'accueil ; un clic sur `lien-se-connecter` ouvre `/connexion` (`titre-connexion`) ; un clic sur `lien-creer-compte-connexion` ouvre `/creer-compte` ; après création d'un compte, `message-succes` affiche « Compte créé pour <pseudo>. Vous pouvez maintenant vous connecter. », `entete-pseudo` est absent, et `lien-se-connecter-succes` mène à `/connexion` (RG21, RG26) | E2E |
| CA34 | Étant donné `/connexion`, quand on valide sans pseudo, puis sans mot de passe, alors `erreur-pseudo` « Le pseudo est obligatoire. » puis `erreur-mot-de-passe` « Le mot de passe est obligatoire. » sans requête `POST /api/connexion` ; quand on supprime le cookie `XSRF-TOKEN` puis qu'on valide des identifiants corrects, alors `erreur-generale` « La page a expiré, veuillez réessayer. » et un second envoi réussit ; avec `POST /api/connexion` interceptée en 503, `erreur-generale` « Service indisponible, veuillez réessayer plus tard. » sans code HTTP et bouton de nouveau actif (RG15, RG24) | E2E |
| CA35 | Étant donné une connexion réussie, quand on inspecte le navigateur, alors le cookie `JSESSIONID` est `HttpOnly` et `SameSite=Lax`, `document.cookie` ne le contient pas, `localStorage` et `sessionStorage` ne contiennent ni le mot de passe ni d'indicateur de connexion, et l'URL ne contient pas le mot de passe (RG12, RG20, RG24) | E2E |
| CA36 | Étant donné un utilisateur connecté, quand on supprime le cookie `JSESSIONID` du navigateur puis qu'on recharge la page, alors l'en-tête affiche `lien-se-connecter` et plus `entete-pseudo` (session absente, RG16/RG20) ; avec `GET /api/comptes/moi` interceptée en 503, l'application reste utilisable en état anonyme (RG20) | E2E |

Répartition : unitaire 9 (CA1 à CA9), intégration 18 (CA10 à CA27), E2E 9 (CA28 à CA36). Total 36.

Couverture des RG : RG1 CA1/CA9/CA10/CA28, RG2 CA1/CA2/CA11/CA28, RG3 CA4/CA12, RG4 CA2/CA3/CA12/CA29, RG5 CA3/CA4/CA13, RG6 CA5/CA14, RG7 CA6/CA15, RG8 CA7/CA16, RG9 CA27, RG10 CA19/CA20, RG11 CA18, RG12 CA17/CA35, RG13 CA20, RG14 CA21/CA30, RG15 CA22/CA30/CA34, RG16 CA23/CA24/CA36, RG17 CA25, RG18 CA26/CA32, RG19 CA25, RG20 CA28/CA35/CA36, RG21 CA28/CA33, RG22 CA31, RG23 CA31/CA32, RG24 CA29/CA34/CA35, RG25 CA30, RG26 CA32/CA33, RG27 CA8. Écrans : En-tête (CA28, CA30, CA33, CA36), Se connecter (CA28 à CA35), Accueil et Créer un compte modifiés (CA32, CA33), tous en E2E.

Impacts sur les tests existants à mettre à jour (même PR) : 1.1 CA18 (cas avec session : 404), 1.1 CA23 (message de succès : le préfixe « Compte créé pour <pseudo>. » reste valable), et 1.1 CA22 (accueil inchangé). Les tests de 1.1 s'exécutent désormais en anonyme, sans session.

## 7. Tester à la main

Prérequis : Docker et `docker compose`, depuis la racine du dépôt, http://localhost. Les valeurs de base par défaut sont celles de 1.1 (`BASE_UTILISATEUR` / `BASE_NOM` du `.env` sinon).

1. `docker compose up -d --build` puis `docker compose ps` : `base`, `api`, `web` en `healthy`.
2. Ouvrir http://localhost : l'en-tête affiche « Se connecter ». Cliquer dessus : écran « Se connecter » avec un lien « Créer un compte ».
3. Créer le compte `Alice` / `un-mot-de-passe-12` (lien « Créer un compte »). Le message de succès propose un lien « Se connecter » ; l'en-tête reste « Se connecter » (pas de connexion automatique). Créer aussi `Bob` / `un-mot-de-passe-12`.
4. Mauvais mot de passe : sur `/connexion`, `Alice` / `mauvais-mot-de-passe-1` : « Pseudo ou mot de passe incorrect. », le pseudo reste saisi, le mot de passe est vidé.
5. Pseudo inexistant : `Fantome` / `un-mot-de-passe-12` : exactement le même message.
6. Champs vides : « Le pseudo est obligatoire. » puis « Le mot de passe est obligatoire. », sans appel réseau (onglet Réseau des outils de développement).
7. Connexion : `alice` (minuscules) / `un-mot-de-passe-12` : redirection vers `/`, l'en-tête affiche `Alice` et « Se déconnecter », le lien « Créer un compte » a disparu de l'accueil.
8. Cookie : outils de développement, Application, Cookies : `JSESSIONID` avec `HttpOnly` coché, `SameSite=Lax`, `Secure` non coché en HTTP local. Dans la console, `document.cookie` ne montre pas `JSESSIONID`. `localStorage` est vide de toute donnée de connexion.
9. Rechargement (F5) sur `/` : toujours connecté en tant qu'`Alice`. Ouvrir http://localhost/connexion et http://localhost/creer-compte : redirection vers `/`.
10. Redirection : se déconnecter, ouvrir http://localhost/connexion?retour=//exemple.org, se connecter : on arrive sur `/` de l'application, pas sur un autre site.
11. Déconnexion : cliquer sur « Se déconnecter » : retour sur `/connexion` avec « Vous êtes déconnecté. », l'en-tête affiche « Se connecter ». F5 : toujours déconnecté. Reconnexion immédiate possible sans erreur.
12. Compte sans empreinte (simule l'anonymisation de 3.6) : `docker compose exec base psql -U backyard -d backyard -c "update compte set empreinte_mot_de_passe = null where pseudo = 'Bob'"`, puis connexion `Bob` / `un-mot-de-passe-12` : même message générique que l'étape 4.
13. Session qui disparaît : connecté en `Alice`, supprimer le cookie `JSESSIONID` dans les outils de développement, F5 : l'en-tête repasse à « Se connecter ».
14. Redémarrage : connecté, `docker compose restart api`, attendre `healthy`, F5 : déconnecté (sessions en mémoire, RG13) ; `Alice` peut se reconnecter (le compte persiste).
15. API en ligne de commande :
    - `curl -i -c /tmp/jar http://localhost/api/csrf` (204, cookie `XSRF-TOKEN`). Connexion : `curl -i -b /tmp/jar -c /tmp/jar -X POST http://localhost/api/connexion -H "Content-Type: application/json" -H "X-XSRF-TOKEN: <valeur du cookie>" -d '{"pseudo":"Alice","motDePasse":"un-mot-de-passe-12"}'` : `200`, corps sans hash, `Set-Cookie: JSESSIONID=...; HttpOnly; SameSite=Lax`.
    - Même requête sans `X-XSRF-TOKEN` : `403` `CSRF_INVALIDE`.
    - `curl -i -b /tmp/jar http://localhost/api/comptes/moi` : `200`. Sans `-b` : `401` `NON_AUTHENTIFIE`.
    - Avec le cookie, `curl -i -b /tmp/jar http://localhost/api/inexistant` : `404` ; sans cookie : `401`.
    - Reposer la connexion avec le même cookie : `409` `DEJA_CONNECTE`.
    - `POST /api/deconnexion` avec le jeton : `204` et `JSESSIONID` effacé ; `GET /api/comptes/moi` avec l'ancien cookie : `401`.
    - Fixation : avec un cookie jar contenant `JSESSIONID=forge`, une connexion réussie renvoie un `JSESSIONID` différent de `forge`.
    - Temps de réponse (ordre de grandeur) : `curl -s -o /dev/null -w '%{time_total}\n'` pour (`Alice`, mauvais mot de passe) et (`Fantome`, n'importe quoi) : valeurs du même ordre (quelques dizaines à centaines de ms), le pseudo inconnu n'est pas nettement plus rapide.
16. Logs : `docker compose logs api | grep -c -e "un-mot-de-passe-12" -e "mauvais-mot-de-passe-1" -e "Fantome"` renvoie `0`.

## 8. Points ouverts

Bloquants : aucun.

Non bloquants (position par défaut retenue, à confirmer) :
1. **Chemins** : `POST /api/connexion`, `POST /api/deconnexion`, `GET /api/comptes/moi` (termes en français). Alternative plus REST : `/api/session` (POST/DELETE) et `/api/comptes/courant`. Par défaut les chemins ci-dessus.
2. **Durée de session** : 12 h d'inactivité (une course backyard peut durer de nombreuses heures et le bénévole ne doit pas être déconnecté en plein scan). Ni durée absolue maximale ni « se souvenir de moi ». À valider ; à revoir au jalon bénévole (4.x).
3. **Sessions en mémoire** : un redémarrage de `api` (mise à jour, `docker compose restart`) déconnecte tout le monde, y compris les bénévoles en course. Alternative : sessions persistées en base (Spring Session JDBC, nouveau changeset, hors du budget de 1.2). Défaut : en mémoire ; à reconsidérer avant le jalon 4 (scan).
4. **Pas de limitation des essais en 1.2** : l'API de connexion, publique, coûte un calcul Argon2id par requête et permet des essais illimités jusqu'à 1.3. Acceptable pour un incrément local non déployé publiquement ; ne pas mettre 1.2 seul en production.
5. **Utilisateur déjà connecté** : choix de 409 `DEJA_CONNECTE` sur `POST /api/connexion` et `POST /api/comptes` plutôt que « remplacer la session ». Il change le comportement de 1.1 (qui n'avait pas de notion de connecté). Alternative : un admin connecté pourrait vouloir créer un compte coureur pour quelqu'un ; ce besoin passera par des endpoints d'administration (1.5, 1.6), pas par `POST /api/comptes`.
6. **Contrôle du compte à chaque requête** : en 1.2 seul `GET /api/comptes/moi` relit le Compte (RG16). Un compte anonymisé ou supprimé garde sa session valide pour les autres endpoints jusqu'à ce que ceux-ci le vérifient ; à généraliser (filtre ou vérification dans la session) à l'incrément 3.6 au plus tard, ou dès 1.4 avec les rôles.
7. **Désynchronisation entre onglets** : pas de resynchronisation automatique de l'état (pas d'intercepteur ni d'écoute de stockage). Reportée à 2.x avec le premier appel authentifié.
8. **Redirection `retour`** : en 1.2 aucune page protégée ne l'utilise, donc la redirection vers un chemin valide n'est vérifiable que par l'absence de redirection externe (CA31). Le chemin valide sera testable à partir de 2.x.
9. **Message de déconnexion** « Vous êtes déconnecté. » transmis par l'état de navigation (pas par l'URL) : il disparaît au rechargement. À valider.
10. **`Secure` du cookie de session** : dépend de `X-Forwarded-Proto` envoyé par Caddy et de `server.forward-headers-strategy` (point 9 de 1.1) ; à vérifier au test manuel de production (https://localhost, 0.4). Si le développeur constate que cela n'est pas en place, l'ajouter dans 1.2.
11. **Taille de l'incrément** : estimée à environ 430 lignes de production (domaine et cas d'usage ~50, contrôleurs et DTO ~70, configuration Security session/CSRF/matrice/gestionnaires ~110, front : écran de connexion ~90, service d'état + en-tête + garde ~100, adaptations de 1.1 ~20), légèrement au-dessus de la cible de 400. Découpage proposé si la relecture dépasse 20 minutes : **1.2a** connexion côté API et écran (`POST /api/connexion`, `GET /api/comptes/moi`, session, en-tête avec pseudo, restauration au rechargement) ; **1.2b** déconnexion (`POST /api/deconnexion`, bouton, message) et redirections `retour` / routes réservées aux anonymes. Non retenu par défaut : 1.2a laisserait l'utilisateur sans moyen de se déconnecter, contraire au test manuel de la roadmap.
12. **Version/dépendances** : aucune nouvelle dépendance attendue (Argon2 et BouncyCastle déjà présents depuis 1.1).
