# Increment 1.4 : Admin master et espace d'administration

Au premier démarrage, `api` crée l'unique Compte `ADMIN_MASTER` à partir de deux variables d'environnement. Un endpoint réservé aux rôles `ADMIN_MASTER` et `ADMIN` (401 anonyme, 403 autre rôle) et un écran d'administration vide (`/administration`) rendent le contrôle des rôles vérifiable de bout en bout. Après connexion, un admin arrive dans l'espace d'administration, les autres rôles comme avant. Taille estimée : environ 300 lignes de production (domaine et cas d'usage ~90, initialisation au démarrage et propriétés ~50, changeset et adaptateur ~30, sécurité et endpoint ~25, front ~100, compose et `.env.example` ~10), sous la cible de 400 : aucun découpage nécessaire.

## 1. Périmètre

**Inclus**
- Variables d'environnement `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE`, lues par `api`, transmises par `docker-compose.yml` (facultatives) et `docker-compose.prod.yml` (obligatoires), documentées dans `.env.example` et `docs/deploiement.md`.
- Cas d'usage `InitialiserAdminMaster` (contexte `comptes`), exécuté au démarrage de `api` : création idempotente de l'unique Compte `ADMIN_MASTER`. Nouveau changeset Liquibase `0003-admin-master-unique` : index unique partiel garantissant un seul `ADMIN_MASTER`.
- Endpoint `GET /api/administration/acces`, réservé à `ADMIN_MASTER` et `ADMIN`. Règle d'accès `/api/administration/**`.
- Front : écran `/administration` (vide), écran `/acces-refuse`, gardes de route, lien « Administration » dans l'en-tête pour les admins, redirection après connexion selon le rôle.
- Mise à jour de l'attente de deux tests d'intégration existants, devenue fausse avec le changeset `0003` (RG21, CA22).

**Exclu**
- Création des Comptes `ADMIN` par l'admin master : 1.5. Création des Comptes `BENEVOLE` par un admin, liste des comptes, changement de mot de passe, suppression d'un compte (dont le refus de supprimer l'admin master) : 1.6 et 3.6.
- Toute fonctionnalité dans l'espace d'administration (Courses, pilotage, comptes) : 2.x et suivants. L'écran est volontairement vide.
- Changement du pseudo ou du mot de passe de l'admin master par les variables après sa création : non (RG3). Par l'interface : 1.6.
- Contrôle du rôle réel en base à chaque requête (le rôle est celui de la session, comme en 1.2) : point ouvert 6.
- Aucun nouvel endpoint public ; `POST /api/comptes`, `POST /api/connexion`, `POST /api/deconnexion`, `GET /api/comptes/moi`, `GET /api/csrf` inchangés (RG21).
- `docker-compose.e2e.yml` et horloge pilotable : 4.3 ; les E2E de 1.4 tournent sur la stack `docker compose up` avec les variables de `.env` (point ouvert 2).

## 2. Règles de gestion

**Création de l'admin master**
- **RG1** : variables `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE`, lues dans `application.yml` (`backyard.admin-master.pseudo: ${ADMIN_MASTER_PSEUDO:}`, `backyard.admin-master.mot-de-passe: ${ADMIN_MASTER_MOT_DE_PASSE:}`), défaut chaîne vide. Les propriétés sont liées à de simples chaînes (aucune annotation de validation Spring : la validation est dans le domaine, RG6, pour qu'aucune valeur ne soit recopiée dans un message de liaison). Le pseudo est « renseigné » s'il n'est pas vide après `trim` ; le mot de passe l'est s'il n'est pas vide (une chaîne d'espaces est une valeur, comme en 1.1).
- **RG2** : `InitialiserAdminMaster` s'exécute à chaque démarrage de `api`, une fois Liquibase appliqué et avant que `api` soit déclarée prête (`ApplicationRunner`). Un échec (RG5 à RG7) arrête le démarrage : `api` ne devient jamais `healthy`.
- **RG3** : idempotence. Si un Compte `ADMIN_MASTER` existe déjà, quel que soit son pseudo, rien n'est créé ni modifié : ni son pseudo, ni son empreinte, ni son rôle. Les variables sont alors ignorées, même absentes, différentes ou invalides. Une ligne INFO « Admin master déjà présent » est journalisée.
- **RG4** : si aucun `ADMIN_MASTER` n'existe et que les deux variables sont non renseignées : `api` démarre, aucun Compte n'est créé, une ligne WARN « Aucun admin master n'existe et ADMIN_MASTER_PSEUDO / ADMIN_MASTER_MOT_DE_PASSE ne sont pas renseignées » est journalisée. En production, `docker-compose.prod.yml` rend les deux variables obligatoires (RG19).
- **RG5** : si aucun `ADMIN_MASTER` n'existe et qu'une seule des deux variables est renseignée : échec du démarrage, message « ADMIN_MASTER_PSEUDO et ADMIN_MASTER_MOT_DE_PASSE doivent être renseignées ensemble (manquante : `<nom de la variable>`). ». Aucun Compte créé.
- **RG6** : validation (aucun `ADMIN_MASTER` existant, les deux variables renseignées). Le pseudo suit les règles de `Pseudo` de 1.1 (3 à 30 caractères après `trim`, lettres, chiffres, `.`, `_`, `-`) ; le mot de passe suit celles de `MotDePasse` de 1.1 (**12 caractères minimum**, 128 maximum, comptés en points de code, aucune règle de composition). Les règles ne sont pas réécrites : `Pseudo.verifier` et `MotDePasse.verifier` sont réutilisés. En cas de violation, le démarrage échoue avec un message qui cite le nom de la variable et la règle violée (« ADMIN_MASTER_MOT_DE_PASSE : le mot de passe doit faire au moins 12 caractères. »), toutes les violations cumulées, **jamais la valeur**. Aucun Compte créé.
- **RG7** : si le pseudo normalisé (`trim` puis minuscules) est déjà pris par un Compte non `ADMIN_MASTER` (coureur, ou tout autre rôle), le démarrage échoue avec « ADMIN_MASTER_PSEUDO : ce pseudo est déjà utilisé par un autre compte. ». Ce Compte n'est ni promu, ni modifié ; aucun Compte n'est créé. Reprise : choisir un autre pseudo.
- **RG8** : création. Compte de rôle `ADMIN_MASTER`, pseudo tel que saisi (après `trim`), empreinte Argon2id par le même `EncodeurMotDePasse` qu'en 1.1, `creeLe` = `Clock` tronqué à la microseconde (même règle que `CreerCompteCoureur`). Une ligne INFO « Admin master créé (compte <id>) » est journalisée, sans pseudo ni mot de passe. Le Compte se connecte ensuite comme tout Compte (RG12).
- **RG9** : unicité. Au plus un Compte `ADMIN_MASTER`. Garantie par le domaine (contrôle d'existence, RG3) et par un index unique partiel en base (`create unique index uk_compte_admin_master on compte (role) where role = 'ADMIN_MASTER'`, nouveau changeset `0003-admin-master-unique`, le changeset `0002-compte` n'est pas modifié). Si l'enregistrement échoue sur cet index parce qu'un autre démarrage a créé l'admin master entre-temps, le cas d'usage se comporte comme RG3 (aucune erreur).
- **RG10** : confidentialité. Le mot de passe en clair n'apparaît dans aucun journal (quel que soit le niveau), aucune exception, aucun message de démarrage ni `toString()` (propriétés et objets qui le transportent masquent la valeur : `MotDePasse[masqué]`). Le pseudo de l'admin master n'est pas journalisé non plus à partir du niveau INFO (cohérent avec 1.2 RG8 et 1.3 RG14). Seuls les noms de variables et l'identifiant du Compte sont journalisés.
- **RG11** : l'admin master n'est jamais créable depuis l'interface : `POST /api/comptes` crée toujours un `COUREUR` (champ `role` ignoré, 1.1) et aucun endpoint de 1.4 ne crée, modifie ou supprime un Compte. « Non supprimable » : aucune suppression n'existe en 1.4 ; 1.6 et 3.6 devront le refuser (noté pour ces incréments).
- **RG12** : l'admin master se connecte par `POST /api/connexion` (1.2), soumis à la limitation de 1.3. `CompteReponse.role` vaut `ADMIN_MASTER` ; la session porte l'autorité `ROLE_ADMIN_MASTER` (mécanisme de 1.2 inchangé).

**Contrôle des rôles**
- **RG13** : `/api/administration/**` (toutes méthodes) est réservé aux rôles `ADMIN_MASTER` et `ADMIN`, contrôlé côté serveur. Sans session : 401 `NON_AUTHENTIFIE`. Session de rôle `COUREUR` ou `BENEVOLE` : 403 `ACCES_REFUSE` (format de 1.2 RG19), y compris sur un chemin inexistant (le refus précède le 404). Admin sur un chemin inexistant : 404 `RESSOURCE_INTROUVABLE`. La règle est placée avant la règle générale `/api/**` authentifié.
- **RG14** : `GET /api/administration/acces` : 204 sans corps pour un admin. Sert de contrôle serveur de l'espace d'administration (RG16). Aucune donnée n'est renvoyée.

**Front**
- **RG15** : destination après connexion réussie (aussi après un 409 `DEJA_CONNECTE` suivi de la relecture du Compte) : le paramètre `retour` s'il est un chemin interne valide (1.2 RG22, inchangé) ; sinon `/administration` si le rôle est `ADMIN_MASTER` ou `ADMIN` ; sinon `/` (comportement de 1.2 pour `BENEVOLE` et `COUREUR`). Un `retour` externe ou invalide est ignoré, puis on applique les deux règles suivantes.
- **RG16** : routes et gardes. `/administration` : anonyme redirigé vers `/connexion?retour=%2Fadministration` ; `COUREUR` ou `BENEVOLE` redirigé vers `/acces-refuse` ; admin accède à l'écran. La garde attend la relecture de l'état (`GET /api/comptes/moi`, 1.2 RG20) avant de décider, donc un rechargement (F5) sur `/administration` ne redirige pas un admin. À l'ouverture, l'écran appelle `GET /api/administration/acces` : 401 redirige vers `/connexion?retour=%2Fadministration`, 403 vers `/acces-refuse`. `/acces-refuse` est public (aucune garde). Les gardes ne sont qu'une aide d'affichage : la sécurité est celle de RG13. Un utilisateur connecté qui ouvre `/connexion` ou `/creer-compte` est toujours renvoyé vers `/` (1.2 inchangé).
- **RG17** : en-tête : pour un Compte `ADMIN_MASTER` ou `ADMIN`, un lien « Administration » (vers `/administration`) apparaît à côté du pseudo ; absent pour les autres rôles et les anonymes.
- **RG18** : l'écran `/administration` affiche un titre, le rôle de l'utilisateur et un message indiquant qu'aucune fonctionnalité n'est disponible. Aucune action.

**Transverse**
- **RG19** : déploiement. `docker-compose.yml` transmet à `api` `ADMIN_MASTER_PSEUDO: ${ADMIN_MASTER_PSEUDO:-}` et `ADMIN_MASTER_MOT_DE_PASSE: ${ADMIN_MASTER_MOT_DE_PASSE:-}` (aucun défaut secret dans le dépôt). `docker-compose.prod.yml` surcharge avec `${ADMIN_MASTER_PSEUDO:?...}` et `${ADMIN_MASTER_MOT_DE_PASSE:?...}` : Compose refuse de démarrer si l'une est vide ou absente, avec un message citant la variable. `.env.example` : les deux variables avec commentaire (rôle, ignorées une fois l'admin master créé, 12 caractères minimum, génération conseillée `openssl rand -base64 18`, mot de passe laissé vide, aucune valeur secrète) et suppression de la ligne « à venir en 1.4 ». `docs/deploiement.md` : tableau de configuration, étape du premier déploiement, mise à jour, reprise en cas de mot de passe perdu (RG3, point ouvert 3), remplacement de « à venir en 1.4 ».
- **RG20** : architecture. Règles (rôle `ADMIN_MASTER`, validation, unicité, idempotence) dans le domaine et `InitialiserAdminMaster` (`comptes.application`) : `Compte.creerAdminMaster(...)` ; port `DepotComptes` étendu (existence d'un `ADMIN_MASTER`) ; exception métier typée pour la configuration invalide. L'`ApplicationRunner`, les propriétés, le changeset et la règle de sécurité sont dans `comptes.infrastructure`. Le contrôleur de l'endpoint (`comptes.exposition`) ne contient aucune logique. ArchUnit passe sans exception ; `courses` n'est pas touché.
- **RG21** : non-régression. Les comportements de 1.1 à 1.3 sont inchangés, hors la redirection de RG15 (qui n'affecte que les admins). Les tests de 1.1 à 1.3 passent sans changement d'attente, **à deux exceptions** dues au nouveau changeset `0003-admin-master-unique` : `SqueletteIntegrationTest.ca2_liquibase_applique_avec_uniquement_la_table_compte` (CA2 de 0.x évolué par 1.1 CA21) et `CompteIntegrationTest.ca21_schema_de_la_table_compte` (CA21 de 1.1) attendent désormais, dans `databasechangelog`, exactement `0002-compte` puis `0003-admin-master-unique` (au lieu de `0002-compte` seul). Aucun autre changement dans ces deux tests : la table `compte` et ses colonnes, la table métier unique `compte` et le changeset `0002-compte` inchangé restent vérifiés comme avant.

## 3. Cas limites

- **Premier démarrage, variables valides** : un seul Compte `ADMIN_MASTER`, connexion possible (RG8).
- **Redémarrages répétés, mêmes variables** : toujours un seul `ADMIN_MASTER`, empreinte inchangée (RG3). **Variables changées** (autre pseudo, autre mot de passe) : ignorées, l'ancien pseudo et l'ancien mot de passe restent valables (RG3).
- **Variables absentes** : sur une base sans admin master, démarrage OK avec WARN, aucun admin (RG4) ; sur une base qui en a un, démarrage normal (RG3). En production Compose refuse d'abord (RG19).
- **Une seule variable** : échec si aucun admin master n'existe (RG5) ; sans effet s'il existe (RG3). Un pseudo fait d'espaces seuls vaut « non renseigné ».
- **Mot de passe de 11 caractères** : échec ; **12 caractères** : accepté ; **128** : accepté ; **129** : échec (RG6). Mot de passe de 12 espaces : accepté (valeur, comme en 1.1).
- **Pseudo invalide** (`ab`, `a b`, 31 caractères) : échec, message cite `ADMIN_MASTER_PSEUDO` (RG6). **Pseudo en majuscules** (`ADMIN`) : accepté ; la connexion avec `admin` fonctionne (normalisation de 1.1).
- **Pseudo déjà pris** par un coureur (`Alice` ou `ALICE`) : échec, le coureur n'est ni promu ni modifié (RG7). Un coureur peut avoir pris le pseudo avant le premier déploiement d'une instance déjà en ligne : changer de pseudo (point ouvert 7).
- **Deux démarrages concurrents** (deux `api` sur la même base) : un seul crée ; l'autre voit le conflit de l'index et continue (RG9).
- **Mot de passe dans les journaux** : jamais, ni au succès ni à l'échec, ni dans l'analyse d'échec de Spring Boot (RG1, RG10).
- **Valeur de variable contenant `$`** : Compose interpole `$` dans `.env` ; utiliser une valeur sans `$` ou la doubler (`$$`) ; documenté dans `docs/deploiement.md`.
- **Admin master bloqué par 1.3** (5 échecs sur son pseudo) : bloqué 15 min comme tout pseudo ; le redémarrage de `api` débloque (point ouvert 11).
- **Rôle et endpoint** : anonyme 401, `COUREUR` 403, `BENEVOLE` 403, `ADMIN` 204, `ADMIN_MASTER` 204 (RG13, RG14). Chemin inexistant sous `/api/administration/` : 401 / 403 / 404 selon l'identité (RG13). Le jeton CSRF n'est pas exigé par un GET.
- **Redirection** : admin sans `retour` : `/administration` ; admin avec `retour=//exemple.org` : `/administration` ; coureur sans `retour` : `/` ; coureur avec `retour=/administration` : `/acces-refuse` (RG15, RG16) ; bénévole : comme le coureur (non testable en E2E avant 1.6).
- **F5 sur `/administration`** : pas de redirection parasite pour un admin (RG16). **Session expirée ou `api` redémarrée** : l'appel de l'écran donne 401, redirection vers la connexion avec `retour` (RG16).
- **Réponse 5xx ou réseau de `GET /api/administration/acces`** : message d'erreur sur l'écran, pas de redirection (RG16, section 5).
- **Course, Boucle, Passage, Inscription, Abandon, Réintégration, bénévole non affecté à la Course, `CORRECTION`, plusieurs Courses, Course non démarrée** : non applicables en 1.4. Le rôle insuffisant est traité ci-dessus (RG13).

## 4. Contrat d'API

Toutes les erreurs sont des `ProblemDetail` (`application/problem+json`) : `{ "type": "about:blank", "title", "status", "detail", "instance", "code" }`, avec les formats de 1.2 RG19.

### GET /api/administration/acces (nouveau)
- Rôle requis : `ADMIN_MASTER` ou `ADMIN`. Requête : aucun corps, aucun paramètre, pas de jeton CSRF.
- **204** : sans corps (RG14).
- **401** `NON_AUTHENTIFIE` : « Authentification requise » / « Vous devez être connecté. » (sans session).
- **403** `ACCES_REFUSE` : « Accès refusé » / « Vous n'avez pas les droits nécessaires. » (rôle `COUREUR` ou `BENEVOLE`).
- 400, 404, 409 : non applicables. Les autres méthodes sur ce chemin donnent le même 401 / 403 aux non-admins.

### /api/administration/** (autres chemins)
- Aucun autre endpoint en 1.4. Pour un admin : 404 `RESSOURCE_INTROUVABLE` ; pour un non-admin : 401 ou 403 comme ci-dessus (RG13).

### Endpoints existants : modifications
- `POST /api/connexion` et `GET /api/comptes/moi` : forme `CompteReponse { id: uuid, pseudo: string, role: "ADMIN_MASTER" | "ADMIN" | "BENEVOLE" | "COUREUR", creeLe: instant ISO-8601 }` inchangée ; `role` peut maintenant valoir `ADMIN_MASTER`. Le front s'appuie sur ce champ pour RG15 à RG17.
- Aucun autre changement de contrat.

### Configuration (variables d'environnement de `api`)

| Variable | Propriété | Dev (`docker-compose.yml`) | Production | Rôle |
|---|---|---|---|---|
| `ADMIN_MASTER_PSEUDO` | `backyard.admin-master.pseudo` | facultative (vide) | **obligatoire** | pseudo de l'admin master, créé s'il n'existe pas |
| `ADMIN_MASTER_MOT_DE_PASSE` | `backyard.admin-master.mot-de-passe` | facultative (vide) | **obligatoire** | mot de passe initial (12 à 128 caractères) |

### Schéma
- Changeset `0003-admin-master-unique` (fichier `0003-admin-master-unique.yaml`) : index unique partiel sur `compte (role) where role = 'ADMIN_MASTER'`. Aucune autre modification de schéma. Conséquence sur les tests existants : voir RG21 et CA22.

## 5. Écrans

### Espace d'administration (`/administration`, nouveau)
- **Accès** : `ADMIN_MASTER` et `ADMIN` uniquement (RG16).
- **Affiché** : titre `titre-administration` « Administration » ; `administration-role` : « Administrateur master » (`ADMIN_MASTER`) ou « Administrateur » (`ADMIN`) ; `administration-vide` : « Aucune fonctionnalité d'administration pour le moment. »
- **Actions** : aucune sur l'écran. L'en-tête (pseudo, « Se déconnecter », lien « Administration » `lien-administration`) reste disponible.
- **Messages d'erreur** : `administration-erreur` « Impossible de vérifier vos droits d'accès. Réessayez plus tard. » si `GET /api/administration/acces` échoue en 5xx ou réseau ; le titre reste affiché. 401 et 403 redirigent (RG16), sans message d'erreur technique.

### Accès refusé (`/acces-refuse`, nouveau)
- **Affiché** : `titre-acces-refuse` « Accès refusé » ; `message-acces-refuse` « Vous n'avez pas les droits nécessaires pour accéder à cette page. » ; lien `lien-accueil-acces-refuse` « Retour à l'accueil » vers `/`.
- **Actions** : retour à l'accueil. Écran public (un anonyme qui l'ouvre voit le même contenu).
- **Messages d'erreur** : aucun.

### Se connecter (`/connexion`) : évolution
- Affichage et messages inchangés (1.2, 1.3). Seule la destination après succès change (RG15).

### En-tête global : évolution
- Lien `lien-administration` « Administration » pour les admins (RG17). Autres éléments inchangés.

## 6. Critères d'acceptation

Valeurs de référence : admin master `Patron` / `mot-de-passe-patron-1` (21 caractères) ; coureur `Alice` / `un-mot-de-passe-12` ; mots de passe de test `secret-de-test-123` (18 caractères, valide) et `court-secre` (11 caractères, invalide). `Clock` fixe. Dépôt en mémoire et encodeur factice pour les tests unitaires. En intégration, les Comptes `ADMIN` et `BENEVOLE` sont insérés directement par le dépôt (pas d'endpoint de création avant 1.5 et 1.6).

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné un dépôt vide et un `Clock` fixe, quand `InitialiserAdminMaster` reçoit `Patron` / `mot-de-passe-patron-1`, alors un Compte de rôle `ADMIN_MASTER` est enregistré (pseudo `Patron`, `creeLe` = instant du `Clock`, empreinte fournie par l'encodeur, jamais le mot de passe), le résultat est `CREE`, `peutSeConnecter()` vaut vrai (RG8) | unitaire |
| CA2 | Étant donné un `ADMIN_MASTER` `Chef` existant, quand le cas d'usage reçoit `Patron` / valide, puis `a` / `court`, puis des valeurs absentes, alors le résultat est `DEJA_PRESENT` dans les trois cas, le dépôt est inchangé et l'encodeur n'est jamais appelé (RG3) | unitaire |
| CA3 | Quand le cas d'usage est exécuté deux fois de suite avec `Patron` / valide sur un dépôt vide, alors le dépôt contient un seul `ADMIN_MASTER`, 1er résultat `CREE`, 2e `DEJA_PRESENT`, empreinte inchangée (RG3, RG9) | unitaire |
| CA4 | Étant donné un dépôt sans admin master, quand les variables sont (`null`, `null`), (`""`, `""`), (`"   "`, `null`), alors le résultat est `NON_CONFIGURE` et aucun Compte n'est enregistré (RG4) | unitaire |
| CA5 | Quand seule une variable est renseignée (`Patron` / `null`, puis `null` / valide, puis `Patron` / `""`), alors une exception métier typée est levée, son message nomme la variable manquante, aucun Compte n'est enregistré (RG5) | unitaire |
| CA6 | Quand le pseudo vaut `ab`, `a b` ou 31 caractères, ou le mot de passe 11 ou 129 caractères, alors l'exception nomme `ADMIN_MASTER_PSEUDO` ou `ADMIN_MASTER_MOT_DE_PASSE` et la règle violée ; pseudo et mot de passe invalides ensemble : 2 violations dans l'exception ; mot de passe de 12 et de 128 caractères : `CREE` (RG6) | unitaire |
| CA7 | Étant donné un coureur `Alice` en dépôt, quand le cas d'usage reçoit `ALICE` / valide, alors une exception nomme `ADMIN_MASTER_PSEUDO` (« déjà utilisé »), `Alice` reste `COUREUR`, aucun Compte n'est ajouté (RG7) | unitaire |
| CA8 | Étant donné un dépôt dont `enregistrer` lève la violation d'unicité de l'admin master (concurrence), quand le cas d'usage s'exécute, alors le résultat est `DEJA_PRESENT` sans exception (RG9) | unitaire |
| CA9 | Quand les exceptions de CA5 à CA7 sont levées avec le mot de passe `secret-de-test-123` (ou `court-secre` pour CA6), alors ni leur message ni leur `toString()` ni celui du résultat ou des propriétés ne contiennent le mot de passe (RG10) | unitaire |
| CA10 | Quand `Compte.creerAdminMaster(...)` est appelé, alors le rôle est `ADMIN_MASTER` ; `Compte.creerCoureur(...)` reste `COUREUR` (RG8, RG11) | unitaire |
| CA11 | Quand `./mvnw test`, alors les règles ArchUnit passent avec les classes ajoutées : domaine sans Spring/JPA/Jackson, `InitialiserAdminMaster` dans `application`, `ApplicationRunner` et propriétés dans `infrastructure`, contrôleur sans accès à l'infrastructure, `courses` indépendant (RG20) | unitaire |
| CA12 | Étant donné la base migrée par Liquibase (Testcontainers), quand deux Comptes `ADMIN_MASTER` de pseudos différents sont insérés, alors le second échoue sur `uk_compte_admin_master` ; un `ADMIN` et plusieurs `COUREUR` s'insèrent sans erreur (RG9) | intégration |
| CA13 | Étant donné une base vide et `pseudo=Patron`, `mot-de-passe=mot-de-passe-patron-1` au démarrage du contexte, alors la table `compte` contient un `ADMIN_MASTER` `Patron` dont l'empreinte commence par `$argon2id$` et diffère du mot de passe ; `POST /api/connexion` avec `patron` (casse différente) renvoie 200 `{pseudo:"Patron", role:"ADMIN_MASTER"}` et `GET /api/comptes/moi` renvoie le même rôle (RG1, RG2, RG8, RG12) | intégration |
| CA14 | Étant donné l'admin master créé (CA13), quand l'initialisation est rejouée avec les mêmes variables, puis avec `Autre` / `autre-mot-de-passe-9`, alors la table contient toujours un seul `ADMIN_MASTER` `Patron`, l'empreinte est inchangée, la connexion avec `mot-de-passe-patron-1` répond 200 et celle avec `autre-mot-de-passe-9` répond 401 (RG3) | intégration |
| CA15 | Étant donné un contexte sans les propriétés (défaut vide) sur base vide, alors le contexte démarre, aucun `ADMIN_MASTER` n'existe et une ligne WARN de RG4 est journalisée ; sur une base avec admin master, une ligne INFO « Admin master déjà présent » et aucun WARN (RG1, RG3, RG4) | intégration |
| CA16 | Étant donné une base sans admin master, quand le contexte démarre avec : seulement le pseudo ; seulement le mot de passe ; pseudo `ab` ; mot de passe `court-secre` (11 caractères) ; pseudo `Alice` d'un coureur existant, alors le démarrage échoue à chaque fois, le message cite la variable en cause, ne contient pas le mot de passe, et la base est inchangée (aucun admin master, `Alice` toujours `COUREUR`) (RG5, RG6, RG7) | intégration |
| CA17 | Étant donné un journal capturé, quand le contexte démarre avec succès (`Patron` / `secret-de-test-123`) puis échoue (`Patron` / `court-secre`), alors aucune ligne de niveau quelconque ne contient `secret-de-test-123` ni `court-secre`, aucune ligne INFO ou supérieure ne contient `Patron`, et la ligne INFO de création contient l'identifiant du Compte (RG8, RG10) | intégration |
| CA18 | Étant donné un admin master existant, quand `POST /api/comptes` reçoit `{pseudo:"Bob12", motDePasse:"un-mot-de-passe-12", role:"ADMIN_MASTER"}` avec jeton CSRF, alors 201 avec `role:"COUREUR"` et la table contient toujours un seul `ADMIN_MASTER` (RG11) | intégration |
| CA19 | Étant donné quatre Comptes (`Patron` admin master, un `ADMIN`, un `BENEVOLE`, `Alice` coureur) et un anonyme, quand `GET /api/administration/acces`, alors anonyme : 401 `NON_AUTHENTIFIE` ; `Alice` : 403 `ACCES_REFUSE` ; bénévole : 403 `ACCES_REFUSE` ; `ADMIN` : 204 sans corps ; `Patron` : 204 ; les erreurs sont en `application/problem+json` avec `title`, `status`, `detail`, `code` de la section 4 (RG13, RG14) | intégration |
| CA20 | Quand `GET /api/administration/inexistant` puis `POST /api/administration/acces` (avec jeton CSRF) sont appelés, alors anonyme : 401 ; `Alice` : 403 `ACCES_REFUSE` pour les deux ; `Patron` : 404 `RESSOURCE_INTROUVABLE` pour le chemin inexistant (RG13) | intégration |
| CA21 | Étant donné `Patron`, quand 5 connexions échouent puis une 6e avec le bon mot de passe, alors la 6e répond 429 `TENTATIVES_EXCESSIVES` (la limitation de 1.3 s'applique à l'admin master) ; après déconnexion d'une session ouverte, `GET /api/administration/acces` répond 401 (RG12, RG13) | intégration |
| CA22 | Étant donné la suite d'intégration de 1.1 à 1.3, quand elle est exécutée, alors elle passe sans changement d'attente, à deux exceptions : `SqueletteIntegrationTest.ca2_liquibase_applique_avec_uniquement_la_table_compte` et `CompteIntegrationTest.ca21_schema_de_la_table_compte` lisent `databasechangelog` (ordre d'exécution) et attendent exactement `["0002-compte", "0003-admin-master-unique"]` au lieu de `["0002-compte"]` ; leurs autres vérifications (table métier unique `compte`, colonnes de `compte`) sont inchangées et passent. Par ailleurs `GET /api/comptes/moi` et `POST /api/connexion` d'un coureur renvoient `role:"COUREUR"` (RG21) | intégration |
| CA23 | Quand un test lit `.env.example`, `docker-compose.yml`, `docker-compose.prod.yml` et `application.yml`, alors `.env.example` contient `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE` sans valeur de mot de passe ; `docker-compose.yml` les transmet à `api` avec défaut vide ; `docker-compose.prod.yml` les déclare avec `:?` ; `application.yml` les lit avec défaut vide (RG1, RG19) | intégration |
| CA24 | Étant donné l'admin master du `.env` (variables `E2E_ADMIN_MASTER_PSEUDO` et `E2E_ADMIN_MASTER_MOT_DE_PASSE` lues par Playwright, mêmes valeurs que la stack), quand on se connecte sur `/connexion`, alors l'URL devient `/administration`, `titre-administration` affiche « Administration », `administration-role` « Administrateur master », `administration-vide` est visible, `entete-pseudo` affiche le pseudo, `lien-administration` est visible et `GET /api/administration/acces` répond 204 (RG12, RG14, RG15, RG17, RG18) | E2E |
| CA25 | Étant donné un coureur `coureur-<suffixe>` créé par l'API, quand on se connecte, alors l'URL devient `/` et `lien-administration` est absent ; quand on ouvre `/administration` dans l'URL, alors l'URL devient `/acces-refuse`, `titre-acces-refuse` affiche « Accès refusé », `message-acces-refuse` affiche « Vous n'avez pas les droits nécessaires pour accéder à cette page. » ; `GET /api/administration/acces` avec la session répond 403 ; `lien-accueil-acces-refuse` ramène à `/` (RG13, RG15, RG16, RG17) | E2E |
| CA26 | Étant donné un anonyme, quand il ouvre `/administration`, alors l'URL devient `/connexion?retour=%2Fadministration` et `titre-connexion` est visible ; après connexion de l'admin master, l'URL devient `/administration` ; en refaisant avec un coureur, l'URL finale est `/acces-refuse` ; avec `/connexion?retour=//exemple.org` et l'admin master, l'URL finale est `/administration` (RG15, RG16) | E2E |
| CA27 | Étant donné l'admin master sur `/administration`, quand on recharge la page (F5), alors l'URL reste `/administration` et le contenu s'affiche ; quand on va sur `/` puis clique sur `lien-administration`, alors on revient sur `/administration` (RG16, RG17) | E2E |
| CA28 | Étant donné l'admin master connecté et la réponse de `GET /api/administration/acces` interceptée, quand elle est 401, alors l'URL devient `/connexion?retour=%2Fadministration` ; quand elle est 403, alors `/acces-refuse` ; quand elle est 500, alors `administration-erreur` affiche « Impossible de vérifier vos droits d'accès. Réessayez plus tard. », l'URL reste `/administration` et aucun code HTTP n'est affiché (RG16, RG18) | E2E |
| CA29 | Étant donné l'admin master sur `/administration`, quand on clique sur `bouton-deconnexion`, alors l'URL devient `/connexion` avec « Vous êtes déconnecté. » ; en ouvrant ensuite `/administration`, l'URL devient `/connexion?retour=%2Fadministration` (RG16) | E2E |

Répartition : unitaire 11 (CA1 à CA11), intégration 12 (CA12 à CA23), E2E 6 (CA24 à CA29). Total 29.

Couverture des RG : RG1 CA13/CA15/CA23, RG2 CA13, RG3 CA2/CA3/CA14/CA15, RG4 CA4/CA15, RG5 CA5/CA16, RG6 CA6/CA16, RG7 CA7/CA16, RG8 CA1/CA10/CA13/CA17, RG9 CA3/CA8/CA12, RG10 CA9/CA17, RG11 CA10/CA18, RG12 CA13/CA21/CA24, RG13 CA19/CA20/CA21/CA25, RG14 CA19/CA24, RG15 CA24/CA25/CA26, RG16 CA25 à CA29, RG17 CA24/CA25/CA27, RG18 CA24/CA28, RG19 CA23, RG20 CA11, RG21 CA22. Écrans : Espace d'administration (CA24, CA26 à CA29), Accès refusé (CA25), Se connecter (CA26), En-tête (CA24, CA25, CA27), tous en E2E.

Notes pour les testeurs : les E2E créent leurs coureurs par l'API avec un suffixe aléatoire (helpers de `e2e/tests/aide-connexion.ts`) et lisent les identifiants de l'admin master dans l'environnement du test, jamais en dur dans le dépôt. Les rôles `ADMIN` et `BENEVOLE` ne sont pas testables en E2E avant 1.5 et 1.6 (CA19 les couvre en intégration). Les deux tests modifiés par CA22 gardent leur nom et leur référence à leur CA d'origine ; seule leur attente sur `databasechangelog` change (le test du squelette n'a pas de `order by` : l'ajouter, ou utiliser `containsExactlyInAnyOrder`).

## 7. Tester à la main

Prérequis : Docker, `docker compose`, racine du dépôt, http://localhost. Les commandes `psql` utilisent les valeurs par défaut `backyard` de la base.

1. `cp .env.example .env`, puis dans `.env` : `ADMIN_MASTER_PSEUDO=Patron` et `ADMIN_MASTER_MOT_DE_PASSE=mot-de-passe-patron-1`. `docker compose up -d --build` puis `docker compose ps` : `base`, `api`, `web` en `healthy` (la migration `0003` s'applique seule sur un volume existant).
2. `docker compose logs api | grep -i "admin master"` : une ligne « Admin master créé (compte <uuid>) », sans pseudo ni mot de passe. `docker compose exec base psql -U backyard -d backyard -c "select pseudo, role from compte"` : une ligne `Patron | ADMIN_MASTER`. `docker compose exec api printenv ADMIN_MASTER_PSEUDO` affiche `Patron`.
3. Connexion : sur http://localhost/connexion, `Patron` / `mot-de-passe-patron-1` : arrivée sur `/administration`, titre « Administration », « Administrateur master », « Aucune fonctionnalité d'administration pour le moment. », en-tête avec `Patron`, « Administration » et « Se déconnecter ». F5 : on reste sur la page. Cliquer sur le titre de l'en-tête puis « Administration » : retour sur la page. Se déconnecter : `/connexion`, « Vous êtes déconnecté. ». Ouvrir `/administration` : redirection vers `/connexion?retour=%2Fadministration` ; se reconnecter avec `patron` en minuscules : arrivée sur `/administration`.
4. Redémarrages : `docker compose restart api`, attendre `healthy`. `docker compose logs api | grep "Admin master déjà présent"` : une ligne ; la requête `psql` de l'étape 2 renvoie toujours une seule ligne. Dans `.env`, mettre `ADMIN_MASTER_MOT_DE_PASSE=autre-mot-de-passe-9` et `docker compose up -d` : `Patron` se connecte toujours avec `mot-de-passe-patron-1` et pas avec `autre-mot-de-passe-9` (les variables sont ignorées une fois l'admin master créé). Remettre la valeur d'origine.
5. Coureur : créer `Alice` / `un-mot-de-passe-12` via « Créer un compte », se connecter : arrivée sur `/`, pas de lien « Administration » dans l'en-tête. Saisir l'URL http://localhost/administration : redirection vers « Accès refusé » avec le message « Vous n'avez pas les droits nécessaires pour accéder à cette page. » et le lien de retour à l'accueil. Se déconnecter, ouvrir `/administration` puis se connecter avec `Alice` : « Accès refusé ».
6. API (voir 1.2 étape 15 pour le cookie CSRF) : `curl -s -i http://localhost/api/administration/acces` sans cookie : `401` et `"code":"NON_AUTHENTIFIE"`. Avec un jar : `curl -s -c /tmp/jar -o /dev/null http://localhost/api/csrf`, `TOKEN=$(awk '/XSRF-TOKEN/ {print $7}' /tmp/jar)`, puis `curl -s -i -b /tmp/jar -c /tmp/jar -X POST http://localhost/api/connexion -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $TOKEN" -d '{"pseudo":"Alice","motDePasse":"un-mot-de-passe-12"}'` puis `curl -s -i -b /tmp/jar http://localhost/api/administration/acces` : `403` et `"code":"ACCES_REFUSE"`. Même séquence avec `Patron` dans un autre jar : `204` sans corps.
7. Création interdite : avec un nouveau jar sans session (jeton CSRF obtenu comme à l'étape 6), `POST /api/comptes` avec `{"pseudo":"Bob12","motDePasse":"un-mot-de-passe-12","role":"ADMIN_MASTER"}` : `201` avec `"role":"COUREUR"`. La requête `psql` montre toujours un seul `ADMIN_MASTER`.
8. Variables invalides. Pour chaque cas : supprimer l'admin master (`docker compose exec base psql -U backyard -d backyard -c "delete from compte where role = 'ADMIN_MASTER'"`), modifier `.env`, `docker compose up -d`, consulter `docker compose ps` et `docker compose logs api | tail -n 30` ; à chaque échec `api` n'est jamais `healthy` et aucun compte n'est créé.
   - `ADMIN_MASTER_PSEUDO=Alice` : message citant `ADMIN_MASTER_PSEUDO` (« déjà utilisé »), `Alice` reste coureur.
   - `ADMIN_MASTER_PSEUDO=Patron`, `ADMIN_MASTER_MOT_DE_PASSE=court-secre` (11 caractères) : message citant `ADMIN_MASTER_MOT_DE_PASSE` et « au moins 12 caractères », sans afficher la valeur.
   - Seul `ADMIN_MASTER_PSEUDO` renseigné (mot de passe vide) : message « doivent être renseignées ensemble ».
   - Les deux vides : `api` démarre `healthy`, WARN « Aucun admin master n'existe ... », aucune ligne `ADMIN_MASTER` ; la connexion de `Patron` échoue.
   - Remettre `Patron` / `mot-de-passe-patron-1` : l'admin master est recréé (reprise après un mot de passe perdu).
9. Journaux : `docker compose logs api | grep -c -e "mot-de-passe-patron-1" -e "court-secre" -e "autre-mot-de-passe-9"` renvoie `0`.
10. Production : `docker compose -f docker-compose.yml -f docker-compose.prod.yml config --quiet` avec `ADMIN_MASTER_MOT_DE_PASSE` vide dans `.env` (et `DOMAINE`, `BASE_MOT_DE_PASSE` renseignés) échoue avec un message citant `ADMIN_MASTER_MOT_DE_PASSE` ; avec les deux renseignées la commande réussit. Relire `.env.example` et la section « Configuration » de `docs/deploiement.md` : les deux variables y sont décrites.

## 8. Points ouverts

Bloquants : aucun.

Non bloquants (position par défaut retenue, à confirmer) :
1. **Variables absentes** : le besoin dit « créé au déploiement ». Défaut retenu : tolérance en dev (démarrage avec WARN, pas d'admin), obligation en production par Compose (RG4, RG19). Alternative : échec systématique du démarrage sans admin master, qui obligerait chaque développeur à renseigner `.env` même pour tester 1.1 à 1.3.
2. **Stack E2E** : `docker-compose.e2e.yml` n'existe pas encore (4.3) ; les E2E de 1.4 supposent une stack lancée avec `ADMIN_MASTER_*` dans `.env` et les mêmes valeurs dans `E2E_ADMIN_MASTER_*` pour Playwright. À confirmer, ou à prévoir une valeur de test commune dans `.env.example`.
3. **Variables ignorées après création** : changer le mot de passe dans `.env` n'a aucun effet (RG3). En cas de mot de passe perdu avant 1.6 : suppression SQL de la ligne `ADMIN_MASTER` puis redémarrage (étape 8). À documenter dans `docs/deploiement.md` ; alternative : réinitialisation forcée par variable dédiée, non prévue.
4. **Secret en variable d'environnement** : le mot de passe est visible par `docker inspect` et `printenv` sur le VPS. Accepté (budget, une seule personne a accès). Alternative : fichier secret Docker (`_FICHIER`), plus lourd. De plus la variable reste obligatoire en production à chaque démarrage même une fois l'admin master créé ; alternative : ne l'exiger qu'au premier déploiement.
5. **Pseudo et mot de passe de l'admin master non modifiables par variable** (RG3) ; le changement par l'interface relève de 1.6.
6. **Rôle figé dans la session** : un Compte supprimé ou rétrogradé garde son accès administration jusqu'à la fin de sa session (12 h) ; cause identique au point 6 de 1.2. À généraliser au plus tard en 3.6 ; non traité ici faute d'opération qui modifie un rôle ou supprime un Compte avant 1.5/1.6.
7. **Pseudo réservé** : sur une instance déjà en ligne, un coureur peut prendre le pseudo prévu pour l'admin master avant son premier démarrage, ce qui fait échouer `api` (RG7). Position retenue : échec explicite plutôt que promotion silencieuse ; choisir un autre pseudo. Alternative : réserver des pseudos (`admin`...), non demandé.
8. **Rôles `ADMIN` et `BENEVOLE` non testables en E2E** avant 1.5 et 1.6 ; couverts en intégration par insertion directe.
9. **Priorité de `retour`** : pour un admin, un `retour` valide l'emporte sur `/administration` (RG15). Alternative : toujours `/administration` pour un admin. Défaut : `retour` d'abord, car il évite de perdre la page demandée.
10. **Noms** : chemins `/administration`, `/acces-refuse` et endpoint `GET /api/administration/acces` (endpoint technique, sans donnée, destiné à vérifier le rôle côté serveur ; il pourra disparaître dès qu'un vrai endpoint admin existera, 1.5). Lien « Administration » dans l'en-tête ajouté, non demandé explicitement par la roadmap. À confirmer.
11. **Blocage de l'admin master (1.3)** : n'importe qui peut verrouiller le pseudo de l'admin master pendant 15 minutes (point 1 de 1.3, plus sensible ici). Le redémarrage de `api` débloque. Pas de traitement spécifique en 1.4.
12. **Taille de l'incrément** : environ 300 lignes de production, sous la cible de 400 : pas de découpage.
13. **Nom du fichier de spec** : la consigne mentionnait `incrementN.md` ; la convention de `CLAUDE.md` (`increment-X.Y.md`) a été suivie.
