# Déploiement

Toute l'application tourne dans Docker : aucune installation de Java, Node ou PostgreSQL n'est nécessaire sur la machine hôte.

| Service | Rôle | Exposition en production |
|---|---|---|
| `base` | PostgreSQL, volume nommé `donnees-postgres` | aucune |
| `api` | Spring Boot, migrations Liquibase appliquées au démarrage | aucune (réseau interne uniquement) |
| `web` | Caddy : front Angular, relais `/api` vers `api`, HTTPS automatique | 80/tcp, 443/tcp, 443/udp |

- Dev : `docker compose up -d --build` (HTTP sur http://localhost, API aussi publiée sur le port 8080).
- Tests E2E : `docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build` (voir « Tests E2E et profil `e2e` »).
- Production : `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d`. Le VPS ne compile jamais : `api` et `web` sont des images versionnées (`VERSION` dans `.env`) construites et testées par la CI, tirées de `ghcr.io` (voir « Publier une version » et « Accès du VPS aux images »).

`docker-compose.prod.yml` ne contient que des surcharges : seul `web` publie des ports, `restart: unless-stopped` sur les trois services, HTTPS par Caddy, volumes des certificats `donnees-caddy` et `config-caddy`, variables `BASE_MOT_DE_PASSE`, `DOMAINE`, `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE` obligatoires, variable fixe `BACKYARD_ENVIRONNEMENT=production` (garde-fou, jamais lue dans `.env`), plafonds mémoire `mem_limit` des trois services (voir « Ressources : mémoire et journaux »).

Dans la suite, sur le VPS :

```sh
PROD="docker compose -f docker-compose.yml -f docker-compose.prod.yml"
```

## Prérequis

- Un VPS Linux avec **Docker Engine** et **Docker Compose >= 2.24** (`docker compose version`) : la surcharge de production utilise la balise `!reset`, inconnue des versions antérieures.
- **2 Go de RAM** recommandés pour faire tourner la stack (plafonds mémoire de production : 1,75 Go au total, voir « Ressources : mémoire et journaux »). Aucune construction d'image sur le VPS.
- Un **enregistrement DNS** (A, et AAAA si IPv6) du nom de domaine pointant vers l'adresse IP du VPS.
- Pare-feu : ports **80/tcp** et **443/tcp** ouverts depuis Internet (obtention et renouvellement du certificat Let's Encrypt, puis trafic HTTPS), **443/udp** ouvert pour HTTP/3 (facultatif : pour s'en passer, retirer la ligne `443:443/udp` de `docker-compose.prod.yml`).
- Ports 80 et 443 libres sur l'hôte (aucun autre serveur web).
- `git` et `openssl` pour récupérer le code et générer le mot de passe.

## Configuration

Toutes les variables sont décrites dans `.env.example`. Le fichier `.env` n'est jamais commité.

| Variable | Production | Rôle |
|---|---|---|
| `BASE_NOM` | facultative (défaut `backyard`) | nom de la base |
| `BASE_UTILISATEUR` | facultative (défaut `backyard`) | utilisateur de la base |
| `BASE_MOT_DE_PASSE` | **obligatoire**, non vide | mot de passe de la base |
| `VERSION` | **obligatoire** en production | version des images `api` et `web` tirées de `ghcr.io`, sans le « v » du tag (`0.5.0`, `0.5.0-rc.1`) ; ignorée en dev et en E2E |
| `DOMAINE` | **obligatoire** | nom de domaine servi en HTTPS |
| `CONNEXION_ECHECS_MAX` | facultative (défaut `5`, entier ≥ 1) | échecs de connexion consécutifs sur un même pseudo avant blocage temporaire |
| `CONNEXION_BLOCAGE_SECONDES` | facultative (défaut `900`, entier ≥ 1) | durée du blocage, et délai au-delà duquel des échecs partiels sont oubliés |
| `CONNEXION_ECHECS_PAR_IP_MAX` | facultative (défaut `20`, entier de 1 à 1000) | échecs de connexion admis par adresse cliente sur la fenêtre glissante ; au-delà, 429 `TENTATIVES_EXCESSIVES` (voir « Limitation des connexions ») ; ne pas descendre sous 10 en production |
| `CONNEXION_ECHECS_PAR_IP_FENETRE_SECONDES` | facultative (défaut `60`, entier de 1 à 3600) | durée de la fenêtre glissante du plafond par adresse, en secondes : un échec cesse de compter à son expiration |
| `SESSION_INACTIVITE_ADMIN_SECONDES` | facultative (défaut `7200`, entier de 1 à 604800) | délai d'inactivité de session des comptes `ADMIN_MASTER` et `ADMIN`, en secondes ; ne doit pas dépasser `SESSION_INACTIVITE_COUREUR_BENEVOLE_SECONDES` (voir « Durée des sessions ») |
| `SESSION_INACTIVITE_COUREUR_BENEVOLE_SECONDES` | facultative (défaut `43200`, entier de 1 à 604800) | délai d'inactivité de session des comptes `COUREUR` et `BENEVOLE`, en secondes |
| `ADMIN_MASTER_PSEUDO` | **obligatoire** (facultative en dev) | pseudo de l'admin master, créé au démarrage s'il n'existe pas (3 à 30 caractères) |
| `ADMIN_MASTER_MOT_DE_PASSE` | **obligatoire** (facultative en dev) | mot de passe initial de l'admin master (12 à 128 caractères) |
| `HACHAGE_CONCURRENCE_MAX` | facultative (défaut `4`, entier ≥ 1) | calculs Argon2id simultanés (connexion, création de compte, changement de mot de passe), environ 19 Mio chacun ; au-delà, attente d'une seconde au plus puis réponse 503 `SERVICE_SATURE` |
| `HACHAGE_CONCURRENCE_MAX_PAR_IP` | facultative (défaut `1`, entier ≥ 1) | calculs Argon2id simultanés par adresse cliente, appliqué avant `HACHAGE_CONCURRENCE_MAX` : une adresse n'occupe jamais plus de places (voir « Limitation des connexions ») |
| `COOKIES_SECURE` | imposée à `true` par `docker-compose.prod.yml` (`.env` ignoré) ; `false` par défaut dans `docker-compose.yml` | drapeau `Secure` des cookies `JSESSIONID` et `XSRF-TOKEN`, indépendant de `X-Forwarded-Proto` ; `true` ou `false` |
| `BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE` | facultative (défaut `1200`, entier ≥ 0, `0` = aucune limite) | requêtes `GET /api/courses` et `/api/courses/**` (accueil, suivi, logo) admises par IP et par minute ; au-delà, 429 `TROP_DE_REQUETES` (voir « Suivi public sous charge ») |
| `API_MEMOIRE_MAX` | facultative (défaut `1g`) | plafond mémoire du conteneur `api` (production seulement) |
| `BASE_MEMOIRE_MAX` | facultative (défaut `512m`) | plafond mémoire du conteneur `base` (production seulement) |
| `WEB_MEMOIRE_MAX` | facultative (défaut `256m`) | plafond mémoire du conteneur `web` (production seulement) |

Si `BASE_MOT_DE_PASSE`, `DOMAINE`, `ADMIN_MASTER_PSEUDO` ou `ADMIN_MASTER_MOT_DE_PASSE` est absente ou vide, Compose échoue avant tout démarrage avec un message citant la variable. Compose ne peut pas refuser une valeur faible : utiliser impérativement un mot de passe généré (`openssl rand -hex 24`), jamais `backyard`.

Une valeur de `CONNEXION_ECHECS_MAX` ou `CONNEXION_BLOCAGE_SECONDES` non entière ou inférieure à 1 empêche `api` de démarrer (jamais `healthy`) ; `$PROD logs api` cite la propriété (`backyard.connexion.echecs-max` ou `backyard.connexion.blocage-secondes`) et la borne. Pour un test à la main, `CONNEXION_BLOCAGE_SECONDES=30` évite d'attendre 15 minutes ; ne pas garder une valeur aussi basse en production.

Une valeur de `CONNEXION_ECHECS_PAR_IP_MAX` (entier de 1 à 1000) ou `CONNEXION_ECHECS_PAR_IP_FENETRE_SECONDES` (entier de 1 à 3600) non entière, vide ou hors bornes empêche `api` de démarrer (jamais `healthy`) ; `$PROD logs api` cite la propriété (`backyard.connexion.echecs-par-ip-max` ou `backyard.connexion.echecs-par-ip-fenetre-secondes`) et la borne. Au démarrage valide, `$PROD logs api | grep "Plafond d'échecs"` affiche « Plafond d'échecs de connexion par adresse : 20 échecs par 60 s ».

Une valeur de `SESSION_INACTIVITE_ADMIN_SECONDES` ou `SESSION_INACTIVITE_COUREUR_BENEVOLE_SECONDES` non entière, vide, hors de 1 à 604800, ou un délai administrateurs supérieur à celui des coureurs et bénévoles empêche `api` de démarrer (jamais `healthy`) ; `$PROD logs api` cite la propriété (`backyard.session.inactivite-admin-secondes` ou `backyard.session.inactivite-coureur-benevole-secondes`) et la borne. Au démarrage valide, `$PROD logs api | grep "Politique de session"` affiche les deux délais retenus.

Une valeur de `HACHAGE_CONCURRENCE_MAX` ou `HACHAGE_CONCURRENCE_MAX_PAR_IP` non entière ou inférieure à 1 empêche de même `api` de démarrer ; `$PROD logs api` cite `backyard.hachage.concurrence-max` ou `backyard.hachage.concurrence-max-par-ip`. Une valeur de `COOKIES_SECURE` autre que `true` ou `false` empêche aussi le démarrage (le journal cite `server.servlet.session.cookie.secure`). Aucune de ces variables n'est obligatoire.

### HTTPS, HSTS et cookies

En production (site servi sur `DOMAINE`), Caddy ajoute `Strict-Transport-Security: max-age=31536000` à toute réponse HTTPS (un an, sans `includeSubDomains` ni `preload`) ; le site HTTP `:80` du dev et des E2E ne l'émet jamais. **Attention** : un navigateur qui a reçu cet en-tête pour un domaine refuse ensuite de le joindre en HTTP pendant un an ; ne servir en HTTPS que des domaines qui le resteront (pour `localhost`, oublier l'entrée dans `chrome://net-internals/#hsts` ou utiliser une fenêtre privée). Les cookies `JSESSIONID` et `XSRF-TOKEN` portent `Secure` si et seulement si `COOKIES_SECURE=true`, valeur imposée par `docker-compose.prod.yml` ; dev et E2E, en HTTP, passent `false` (un cookie `Secure` n'y serait pas renvoyé par tous les navigateurs).

Une valeur de `BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE` non entière ou négative (`abc`, `-1`) empêche aussi `api` de démarrer ; le journal cite `backyard.limite-debit-public.par-minute`.

### Durée des sessions

Une session expire après une période d'**inactivité** qui dépend du rôle du compte, fixée à la connexion : `SESSION_INACTIVITE_ADMIN_SECONDES` (défaut 2 h) pour `ADMIN_MASTER` et `ADMIN`, `SESSION_INACTIVITE_COUREUR_BENEVOLE_SECONDES` (défaut 12 h) pour `COUREUR` et `BENEVOLE`. Le délai est glissant : chaque requête authentifiée le repousse ; il n'y a pas de durée absolue. Une session expirée reçoit 401 et l'écran de connexion affiche « Votre session a expiré ou a été fermée. Veuillez vous reconnecter. ».

- Les sessions déjà ouvertes gardent le délai reçu à leur ouverture : changer une valeur impose `$PROD up -d api`, ce qui déconnecte tout le monde (à faire hors course).
- Administrateurs : ne pas descendre sous 600 s en production. Bénévoles : garder au moins la durée d'une course.
- **Jour de course** : l'écran de pilotage d'une course relance des requêtes régulières et garde la session de l'admin tant qu'il reste ouvert. Un onglet admin laissé sur un autre écran (liste des courses, gestion des comptes) sans activité pendant le délai demande une reconnexion à l'action suivante.

### Limitation des connexions : compromis connu

Le blocage temporaire après `CONNEXION_ECHECS_MAX` échecs porte sur le **pseudo**, pas sur l'adresse IP : il protège chaque compte contre la recherche de mot de passe, quelle que soit l'origine des tentatives. Contrepartie : n'importe qui connaissant un pseudo (classement et listes publics) peut le bloquer pendant `CONNEXION_BLOCAGE_SECONDES` en enchaînant des mots de passe faux, **y compris celui de l'admin master**, et recommencer à la fin du blocage. Le mot de passe n'est jamais exposé, mais la connexion du compte visé est refusée (429 `TENTATIVES_EXCESSIVES`) pendant ce temps.

- Une session déjà ouverte n'est pas affectée : le jour d'une course, l'admin master se connecte **avant** le départ et garde sa session tant qu'il fait une action au moins toutes les 2 h par défaut (`SESSION_INACTIVITE_ADMIN_SECONDES`, pas de limite absolue ; l'écran de pilotage ouvert suffit) ; ne pas se déconnecter.
- Pour lever un blocage en urgence : `$PROD restart api` remet à zéro compteurs et blocages, mais déconnecte tout le monde (sessions en mémoire) ; à éviter pendant une course.
- Si un blocage abusif se répète, réduire temporairement `CONNEXION_BLOCAGE_SECONDES` dans `.env` puis `$PROD up -d api` (même effet de déconnexion).

**Plafond d'échecs par adresse** : en plus du blocage par pseudo, `api` compte les échecs de connexion (pseudo inconnu, mot de passe faux, compte désactivé ou anonymisé) par adresse cliente, lue derrière le proxy de confiance `web`, sur une fenêtre glissante : au-delà de `CONNEXION_ECHECS_PAR_IP_MAX` échecs (défaut 20) dans les `CONNEXION_ECHECS_PAR_IP_FENETRE_SECONDES` dernières secondes (défaut 60), toute tentative depuis cette adresse est refusée avant toute lecture de la base et tout calcul d'empreinte, avec **la même réponse** que le blocage par pseudo (429 `TENTATIVES_EXCESSIVES`, `Retry-After` d'au plus la fenêtre) : rien n'indique la cause. Cela arrête la « pulvérisation » (un mot de passe essayé sur de nombreux pseudos), que le blocage par pseudo ne voit pas. Une tentative refusée ne prolonge rien ; l'adresse redevient admise dès que son plus ancien échec retenu expire. Une connexion réussie n'ajoute aucun échec mais n'efface pas non plus le compteur (sinon un attaquant muni d'un compte coureur le remettrait à zéro). Les échecs du changement de mot de passe et de la suppression de compte ne comptent pas. Le compteur est en mémoire : `$PROD restart api` le remet à zéro (et déconnecte tout le monde). Journal : une ligne `Connexion : plafond d'échecs par adresse atteint` (WARN) quand une adresse atteint le plafond, sans pseudo ni adresse ; l'adresse figure dans les journaux d'accès de Caddy.

- **Wifi partagé (bénévoles, spectateurs)** : toutes les connexions sortent par la même adresse, mais seuls les échecs comptent ; 20 par minute laissent la place à plusieurs fautes de frappe simultanées. Si des connexions légitimes reçoivent « Trop de tentatives », relever `CONNEXION_ECHECS_PAR_IP_MAX` dans `.env` (jusqu'à 1000) puis `$PROD up -d api` (hors course : déconnecte tout le monde).
- **Jour de course** : un 429 dû au plafond d'adresse dure au plus la fenêtre (une minute par défaut) et se résout seul, sans intervention. Un attaquant situé sur le même réseau que les bénévoles peut retarder les **nouvelles** connexions depuis ce réseau, jamais une session déjà ouverte : se connecter avant le départ reste la conduite à tenir. `$PROD restart api` remet les compteurs à zéro mais déconnecte tout le monde.
- **Limite restante** : un attaquant disposant de nombreuses adresses (attaque répartie) contourne tout compteur par adresse ; restent le blocage par pseudo, Argon2id et le plafond de hachage ci-dessous. Une limite de débit dans Caddy (module tiers `caddy-ratelimit`) reste l'évolution possible.

Le coût des tentatives admises est en outre borné : chaque vérification Argon2id se fait hors transaction (aucune connexion de base tenue pendant le calcul), au plus `HACHAGE_CONCURRENCE_MAX_PAR_IP` calculs tournent en même temps pour une même adresse cliente (lue derrière le proxy de confiance `web`), et au plus `HACHAGE_CONCURRENCE_MAX` au total. Une seule adresse ne peut donc plus saturer le plafond global : avec les défauts (1 et 4), il reste au moins 3 places aux autres adresses, et l'admin master qui se connecte d'ailleurs est servi. La décision ne dépend que de l'adresse et des places, jamais du pseudo (aucune énumération possible). Limites restantes : `k` adresses distinctes occupent jusqu'à `k × HACHAGE_CONCURRENCE_MAX_PAR_IP` places (attaque répartie) ; un admin derrière la même adresse que l'attaquant (NAT, wifi partagé) peut recevoir 503 : la conduite reste de se connecter avant le départ, hors de ce réseau si besoin. Plusieurs bénévoles derrière un même wifi qui se reconnectent ensemble passent un par un (quelques dizaines de ms chacun) ; s'ils reçoivent des 503, relever `HACHAGE_CONCURRENCE_MAX_PAR_IP`. Une rafale sur `/api/connexion` ou `/api/comptes` reçoit des 503 `SERVICE_SATURE` (en-tête `Retry-After`) au lieu d'épuiser la mémoire de `api` ou le pool de connexions de la base, que partagent les scans des bénévoles et le planificateur. Limite restante : une rafale massive et soutenue (plusieurs centaines de requêtes simultanées) peut encore occuper les fils d'exécution de Tomcat pendant l'attente d'une seconde ; une limite par IP dans Caddy (module tiers `caddy-ratelimit`, image Caddy à construire) reste la protection à ajouter avant une exposition à forte audience. `$PROD logs api | grep "calculs d'empreinte saturés"` montre ces refus et le plafond atteint (« plafond par adresse » ou « plafond global »).

### Ressources : mémoire et journaux

**Journaux** : les trois services utilisent le pilote `json-file` avec rotation (5 fichiers de 10 Mo au plus par conteneur, défini dans `docker-compose.yml`), soit 150 Mo au plus pour la stack. `$PROD logs` ne montre que ce qui est conservé : pour garder une trace plus longue, copier les journaux régulièrement (`$PROD logs --no-color --since 24h > journaux-$(date +%F).txt`). Une panne durable du contrôle d'abandon automatique (base injoignable, course en échec) n'est journalisée, trace comprise, qu'aux échecs consécutifs 1, 2, 4, 8… puis tous les 1024, et son rétablissement une fois.

**Mémoire (production)** : `mem_limit` plafonne chaque conteneur (`API_MEMOIRE_MAX`, `BASE_MEMOIRE_MAX`, `WEB_MEMOIRE_MAX`, défauts `1g`, `512m`, `256m`, soit 1,75 Go). La JVM de `api` prend 60 % de son plafond pour le tas (`-XX:MaxRAMPercentage=60`, `backend/Dockerfile`), le reste couvre la mémoire hors tas ; elle s'arrête sur `OutOfMemoryError` (`-XX:+ExitOnOutOfMemoryError`) et Docker la relance (`restart: unless-stopped`). Sur un VPS de 2 Go, garder les défauts ; au-delà, augmenter d'abord `API_MEMOIRE_MAX` (et `HACHAGE_CONCURRENCE_MAX` si les connexions simultanées sont nombreuses : compter 19 Mio par calcul dans le tas). Un conteneur qui dépasse son plafond est tué par le noyau (`$PROD ps` le montre redémarré, `docker inspect --format '{{.State.OOMKilled}}' <conteneur>` vaut `true`). Une modification de ces variables s'applique par `$PROD up -d`. En dev, aucun plafond n'est posé.

### Admin master

L'admin master est l'unique compte de rôle `ADMIN_MASTER`. Il n'est jamais créable depuis l'interface : `api` le crée à son démarrage, après les migrations, à partir de `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE`.

- **Création** : uniquement s'il n'existe pas encore. `$PROD logs api` contient alors « Admin master créé (compte <identifiant>) ». Il se connecte ensuite comme tout compte (pseudo insensible à la casse).
- **Variables ignorées ensuite** : dès qu'un admin master existe (« Admin master déjà présent » dans les logs), les deux variables ne sont plus lues ; les modifier ne change ni son pseudo ni son mot de passe. Elles restent néanmoins exigées par `docker-compose.prod.yml` à chaque démarrage.
- **Échec du démarrage** (`api` jamais `healthy`, cause dans `$PROD logs api`, sans aucune valeur) : une seule des deux variables renseignée ; pseudo invalide (3 à 30 caractères, lettres, chiffres, `.`, `_`, `-`) ; mot de passe de moins de 12 ou de plus de 128 caractères ; pseudo déjà utilisé par un autre compte (ce compte n'est jamais promu : choisir un autre pseudo).
- **Dev** : les deux variables vides, `api` démarre sans admin master avec l'avertissement « Aucun admin master n'existe et ADMIN_MASTER_PSEUDO / ADMIN_MASTER_MOT_DE_PASSE ne sont pas renseignées ».
- **Changement du mot de passe** : par l'écran « Mon compte » (http(s)://<domaine>/mon-compte), mot de passe actuel exigé. À faire après le premier déploiement : la valeur de `ADMIN_MASTER_MOT_DE_PASSE` n'est plus relue ensuite, c'est le nouveau mot de passe qui reste valable après redémarrage. Les autres sessions de l'admin master sont fermées.
- **Mot de passe** : générer avec `openssl rand -base64 18`. Compose interprète `$` dans `.env` : éviter ce caractère ou le doubler (`$$`). Le mot de passe n'apparaît jamais dans les logs, mais reste visible par `docker inspect` et `printenv` sur le VPS : protéger l'accès au VPS et au fichier `.env` (`chmod 600`).

**Mot de passe de l'admin master perdu** (l'écran « Mon compte » exige le mot de passe actuel) : supprimer le compte puis redémarrer `api` avec de nouvelles valeurs dans `.env` ; il est recréé.

```sh
# Remplacer backyard par BASE_UTILISATEUR et BASE_NOM s'ils ont été changés.
$PROD exec base psql -U backyard -d backyard -c "delete from compte where role = 'ADMIN_MASTER'"
$PROD up -d
$PROD logs api | grep "Admin master"
```

## Premier déploiement

1. Récupérer le code :
   ```sh
   git clone <url-du-depot> backyard-ultra-tracker
   cd backyard-ultra-tracker
   ```
2. Créer le fichier de variables et le protéger :
   ```sh
   cp .env.example .env
   chmod 600 .env
   ```
3. Renseigner dans `.env` :
   - `VERSION=` la version à déployer, sans le « v » (ex. `0.5.0`, ou `0.5.0-rc.1` pour une pré-version) ;
   - `DOMAINE=` le nom de domaine (ex. `backyard.example.org`) ;
   - `BASE_MOT_DE_PASSE=` la sortie de `openssl rand -hex 24` ;
   - `ADMIN_MASTER_PSEUDO=` le pseudo de l'admin master (ex. `Patron`) ;
   - `ADMIN_MASTER_MOT_DE_PASSE=` la sortie de `openssl rand -base64 18` (à conserver dans un gestionnaire de mots de passe).
4. Vérifier la configuration (échoue si une variable obligatoire manque) :
   ```sh
   $PROD config --quiet
   ```
5. Tirer les images (voir « Accès du VPS aux images ») et lancer :
   ```sh
   $PROD pull
   $PROD up -d
   ```
6. Attendre environ 90 s puis vérifier que les trois services sont `healthy` :
   ```sh
   $PROD ps
   ```
7. Vérifier le site : `curl https://<domaine>/api/sante` répond `{"status":"UP"}`, `curl https://<domaine>/api/version` indique la version déployée (`app.version`, doit valoir celle de `.env`) : la réponse est `{"app":{"version":"<VERSION de .env>"}}` et ne contient que cette version (aucune métadonnée de build), et `http://<domaine>/` redirige (308) vers HTTPS.
8. Vérifier l'admin master : `$PROD logs api | grep "Admin master"` affiche « Admin master créé (compte <identifiant>) », puis se connecter sur `https://<domaine>/connexion` avec ses identifiants : arrivée sur l'espace d'administration.

Le certificat Let's Encrypt est obtenu au premier démarrage puis renouvelé automatiquement par Caddy. En cas d'échec (DNS non propagé, port 80 ou 443 fermé), la cause figure dans `$PROD logs web` ; le site reste inaccessible en HTTPS mais `web` reste `healthy` (sa santé ne dépend pas du certificat). Les certificats sont conservés dans le volume `donnees-caddy` : ne pas le supprimer sans raison, Let's Encrypt limite le nombre d'émissions.

### Piège du volume de base existant

`POSTGRES_PASSWORD` n'est appliqué par PostgreSQL **qu'à la création** du volume `donnees-postgres`. Changer `BASE_MOT_DE_PASSE` ensuite, ou passer d'une stack de dev (mot de passe `backyard`) à la production sur le même volume, fait échouer `api` (authentification refusée, `api` jamais `healthy`).

- En local, repartir d'un volume vide : `docker compose down -v` (**supprime les données**).
- Sur le VPS, ne jamais supprimer le volume : restaurer une sauvegarde dans une base créée avec le bon mot de passe (voir Restauration), ou changer le mot de passe dans PostgreSQL (`ALTER USER ... PASSWORD ...`) avant de modifier `.env`.

## Publier une version

Les images de production sont construites par la CI, jamais sur le VPS. Une version se publie par un tag `vX.Y.Z` (ou `vX.Y.Z-rc.N` pour une pré-version de test) :

1. Sur une branche `release/X.Y.Z`, mettre à jour les versions : `version` de `backend/pom.xml` (sans `-SNAPSHOT`), de `frontend/package.json` et de `e2e/package.json` (et leurs `package-lock.json`), puis ajouter la section `## [X.Y.Z]` de `CHANGELOG.md`.
2. Ouvrir la pull request, attendre la CI verte, la merger sur `main`. Pour tester avant, poser un tag `vX.Y.Z-rc.1` sur la tête de la branche de release : les versions des sources sont celles de `X.Y.Z`, le suffixe `-rc.N` est ignoré par la vérification.
3. Poser le tag sur `main` et le pousser :
   ```sh
   git switch main && git pull
   git tag vX.Y.Z && git push origin vX.Y.Z
   ```
4. Suivre le workflow `Release` (`gh run watch`, onglet *Actions*). Il rejoue la CI complète, vérifie que le tag correspond aux versions des sources et à `CHANGELOG.md`, construit les images `linux/amd64`, les pousse, puis crée la GitHub Release (notes reprises de `CHANGELOG.md`, pré-version pour un tag `-rc`). Si un job échoue, aucune image n'est publiée.
5. Retrouver les images sur GHCR (page *Packages* du dépôt) : `ghcr.io/quenaultthomas/backyard-ultra-tracker-api` et `ghcr.io/quenaultthomas/backyard-ultra-tracker-web`, taguées `X.Y.Z` (ou `X.Y.Z-rc.N`) et par SHA court du commit. Il n'y a jamais de tag `latest`.

Un tag publié n'est jamais déplacé : pour corriger, publier la version suivante.

## Accès du VPS aux images

Le dépôt est **public**. Les packages créés par le workflow sont en revanche **privés** par défaut. Après la première publication, pour chacun des deux packages (`backyard-ultra-tracker-api` et `backyard-ultra-tracker-web`) : page du package sur GitHub, *Package settings*, *Danger Zone*, *Change visibility*, **Public**. Le VPS peut alors tirer les images sans authentification :

```sh
docker pull ghcr.io/quenaultthomas/backyard-ultra-tracker-api:<version>
docker pull ghcr.io/quenaultthomas/backyard-ultra-tracker-web:<version>
```

Si l'on préfère garder les packages privés : créer un jeton GitHub (*Settings*, *Developer settings*, *Personal access tokens*) limité à la portée `read:packages` (jeton classic ; un jeton fine-grained ne gère pas les packages), puis sur le VPS :

```sh
echo "<jeton>" | docker login ghcr.io -u <utilisateur-github> --password-stdin
```

Le jeton est stocké dans `~/.docker/config.json` : ne jamais le commiter et lui donner une expiration.

## Mise à jour

1. Faire une sauvegarde (voir ci-dessous).
2. Récupérer les fichiers de déploiement de la nouvelle version (compose, `.env.example`, ce document) :
   ```sh
   git pull
   ```
3. Comparer `.env.example` avec `.env` et ajouter les nouvelles variables éventuelles (depuis 1.4 : `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE`, obligatoires ; ignorées si l'admin master existe déjà ; depuis la correction de l'audit du 2026-10-16 : `HACHAGE_CONCURRENCE_MAX`, `API_MEMOIRE_MAX`, `BASE_MEMOIRE_MAX` et `WEB_MEMOIRE_MAX`, toutes facultatives ; depuis 7.3 : `HACHAGE_CONCURRENCE_MAX_PAR_IP` et `COOKIES_SECURE`, facultatives, la seconde imposée à `true` en production ; la liste complète des variables nouvelles d'une version figure dans sa section de `CHANGELOG.md`).
4. Passer `VERSION` à la nouvelle version dans `.env`, tirer les images et relancer (les migrations Liquibase s'appliquent au démarrage de `api`) :
   ```sh
   $PROD pull
   $PROD up -d
   $PROD ps
   curl https://<domaine>/api/version
   ```
   Pour revenir en arrière, remettre l'ancienne valeur de `VERSION` et relancer `$PROD up -d` (les migrations Liquibase déjà appliquées ne sont pas annulées : restaurer la sauvegarde si le schéma a changé).
5. Facultatif, libérer l'espace des anciennes images : `docker image prune -f`.

Ne jamais utiliser `down -v` en production : cette option supprime les volumes, donc la base et les certificats.

Les sessions de connexion sont conservées en mémoire par `api` (expiration après une inactivité qui dépend du rôle : 2 h par défaut pour les administrateurs, 12 h pour les coureurs et bénévoles) : toute mise à jour ou tout redémarrage de `api` déconnecte tous les utilisateurs, qui doivent se reconnecter. Éviter de mettre à jour pendant une course.

Les compteurs d'échecs de connexion et les blocages temporaires sont aussi en mémoire : un redémarrage de `api` les remet à zéro (c'est aussi le moyen de débloquer un pseudo avant la fin du délai). L'application est prévue pour une seule instance de `api`.

### Après une mise à jour : application installée

L'application est installable et garde sa coque en cache (service worker, incrément 5.2). Après un déploiement, chaque appareil télécharge la nouvelle version en arrière-plan : elle s'applique au prochain lancement complet de l'application, ou tout de suite par le bandeau « Une nouvelle version de l'application est disponible. » > « Mettre à jour ». Elle ne s'applique jamais toute seule, et les scans en attente sont conservés. Aucune purge de cache n'est à demander aux bénévoles. Le service worker exige HTTPS (sauf `localhost`) : en production, l'application doit être servie par le domaine (`DOMAINE`), jamais par l'adresse IP en HTTP.

## Fin de course et scans tardifs

**Délai de grâce.** La Boucle N n'est tranchée (abandon automatique hors délai, fin de course) qu'au départ de la Boucle N+1 plus un délai de grâce de **3 minutes**, plafonné au quart de la durée de Boucle (15 s pour une Boucle de 1 min). C'est une constante de l'application, non configurable. Pendant ce délai, un scan fait pendant la Boucle N (l'application tient compte de l'âge du scan en file d'attente) est encore accepté sur la Boucle N, même s'il arrive après le départ de N+1. Suivi public et pilotage affichent alors « Contrôle de la boucle N en cours : les scans de cette boucle sont encore acceptés. » Conséquence : le vainqueur est annoncé jusqu'à 3 minutes (le délai de grâce) après la fin de la Boucle décisive.

**Consignes terrain.**
- Garder au moins un poste de scan en ligne près de la ligne d'arrivée.
- En fin de course, avant chaque départ, vérifier que le compteur « en attente » de chaque poste de scan est à zéro.
- Ne pas mettre à jour ni redémarrer `api` pendant une course. Si c'est inévitable, reconnecter les bénévoles (les sessions sont perdues) avant la fin du délai de grâce pour que leurs scans en attente partent à temps.

**Limite.** Au-delà du délai de grâce, la Boucle est tranchée : un scan tardif est refusé. Si la course est encore en cours, un administrateur peut réintégrer le coureur. Sur une course `TERMINEE`, aucune correction n'est possible dans l'application : noter le résultat corrigé à la main.

## Suivi public sous charge

Le suivi public est projeté et ouvert par les spectateurs : chaque écran interroge `GET /api/courses/{id}/suivi` toutes les 3 s environ (~20 requêtes par minute), avec un décalage aléatoire pour ne pas partir tous au même instant, et espace ses nouvelles tentatives en cas d'erreur (3, 6, 12, 24 puis 30 s).

- **Micro-cache** : l'api relit la Course, ses Inscriptions et ses Passages au plus une fois par seconde et par Course ; compte à rebours, Boucle courante et heure du serveur sont recalculés à chaque requête. Un statut de coureur (scan, abandon, vainqueur) peut apparaître sur le suivi jusqu'à ~2 s après la décision. Le pilotage n'est pas mis en cache.
- **Planificateur** : le contrôle d'abandon automatique ne verrouille une Course et ne relit ses Passages qu'à l'échéance d'une Boucle (départ + délai de grâce), plus chaque seconde.
- **Limite de débit** : `BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE` (défaut `1200`) requêtes par IP et par minute sur `GET /api/courses` et `/api/courses/**` ; au-delà, 429 `TROP_DE_REQUETES` avec `Retry-After` jusqu'à la minute suivante, refusé avant tout accès à la base. La connexion, les routes coureur, bénévole et administration ne sont pas concernées. L'IP retenue est celle transmise par Caddy (`X-Forwarded-For`), uniquement si la requête vient du service `web` (`BACKYARD_PROXY_DE_CONFIANCE=web`, fixé dans `docker-compose.yml`) : un en-tête envoyé directement à `api` est ignoré. Une ligne `Débit public dépassé` par IP et par minute apparaît dans `$PROD logs api`.
- **Limite connue** : des spectateurs derrière une même IP (wifi d'un stade, NAT d'opérateur) partagent le compteur ; 1200 admet ~60 écrans. Au-delà, remonter la variable dans `.env` puis `$PROD up -d api` (aucune reconstruction, mais les sessions sont perdues : hors course). `0` désactive la limite.
- **Évolution possible** : limiter dans Caddy (module tiers `caddy-ratelimit`, image Caddy à construire) protégerait aussi la bande passante ; non retenu pour garder l'image officielle.

**Mesure** (local uniquement, comme les données de démonstration) :

```bash
# Crée (si absente) la Course « Backyard de charge » et 30 coureurs charge01…charge30, affiche son identifiant.
PEUPLER=30 ./scripts/charge-suivi.sh
# Démarrer la Course dans l'interface, puis simuler 50 écrans pendant 60 s.
COURSE=<identifiant> ECRANS=50 DUREE=60 ./scripts/charge-suivi.sh
```

Le script affiche les requêtes envoyées, les `200`, `429`, `5xx`/erreurs réseau, la latence médiane et le p95 ; il sort en code 1 si le p95 dépasse 500 ms ou s'il y a au moins un `5xx`. Il lit `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE` dans `.env` comme `scripts/donnees-demo.sh` (pour `PEUPLER`), vise `BASE_URL` (défaut `http://localhost`) et refuse une cible autre que `localhost`/`127.0.0.1` sans `DEMO_FORCER=oui` (code 2).

## Sauvegarde

Sauvegarde de la base au format personnalisé de `pg_dump`, lancée dans le conteneur `base` (l'utilisateur et la base sont lus depuis le conteneur, pas depuis l'hôte) par `scripts/sauvegarder.sh`, à lancer depuis la racine du dépôt :

```sh
./scripts/sauvegarder.sh                          # production (-f docker-compose.yml -f docker-compose.prod.yml)
./scripts/sauvegarder.sh --verifier-restauration  # idem, puis restauration d'essai et comparaison des comptages
COMPOSE_FICHIERS="-f docker-compose.yml" ./scripts/sauvegarder.sh   # stack de dev
```

Le script écrit dans un fichier temporaire, le contrôle (`pg_restore --list`), le renomme `sauvegarde-AAAAMMJJ-HHMM.dump` (suffixe des secondes si le nom existe déjà), puis ne garde que les plus récents fichiers `sauvegarde-*.dump` du dossier (les autres fichiers ne sont jamais touchés). La dernière ligne affichée donne le chemin et la taille. En cas d'échec (`base` arrêtée, disque plein, dump illisible), le message part sur la sortie d'erreur, le code de sortie est non nul et aucune sauvegarde existante n'est supprimée. Il ne lit pas `.env` et n'affiche aucun mot de passe.

| Variable | Défaut | Rôle |
|---|---|---|
| `SAUVEGARDE_DOSSIER` | `./sauvegardes` | dossier de destination, créé s'il manque (droits `700`) |
| `SAUVEGARDE_CONSERVER` | `14` | nombre de fichiers `sauvegarde-*.dump` conservés ; entier ≥ 1, sinon code 2 avant toute écriture |
| `COMPOSE_FICHIERS` | `-f docker-compose.yml -f docker-compose.prod.yml` | options passées à `docker compose` (ajouter `-p <projet> --env-file <fichier>` pour une stack isolée) |

`--verifier-restauration` restaure le dump dans une base temporaire `restauration_verif` du conteneur `base` (supprimée puis recréée si elle existe déjà, jamais la base de production), compare le nombre de lignes des tables `course`, `inscription` et `passage` avec la base source, supprime la base temporaire dans tous les cas et sort en code 1 si un comptage diffère (une écriture pendant la sauvegarde peut l'expliquer : relancer).

**Jour de course.**
- Une sauvegarde manuelle juste avant le départ de la première Boucle, une autre juste après l'annonce du Vainqueur.
- Les copier aussitôt hors du VPS : une sauvegarde laissée sur le même disque ne protège pas d'une panne du disque ou de la perte du VPS. Depuis le poste de l'administrateur :
  ```sh
  rsync -av <utilisateur>@<vps>:backyard-ultra-tracker/sauvegardes/ ./sauvegardes-backyard/
  # ou un seul fichier :
  scp <utilisateur>@<vps>:backyard-ultra-tracker/sauvegardes/sauvegarde-20261024-0800.dump .
  ```

**Planification** (`crontab -e` de l'utilisateur qui lance Docker sur le VPS ; `cron` s'exécute sur l'hôte, pas dans un conteneur) :

```cron
# Pendant une course : toutes les heures, journal dans sauvegardes/sauvegarde.log.
0 * * * * cd /home/<utilisateur>/backyard-ultra-tracker && ./scripts/sauvegarder.sh >> sauvegardes/sauvegarde.log 2>&1
# Hors course : une fois par jour à 3 h, avec restauration d'essai.
0 3 * * * cd /home/<utilisateur>/backyard-ultra-tracker && ./scripts/sauvegarder.sh --verifier-restauration >> sauvegardes/sauvegarde.log 2>&1
```

Avec 14 fichiers conservés, la ligne horaire garde 14 heures de course ; relever `SAUVEGARDE_CONSERVER` (par exemple `SAUVEGARDE_CONSERVER=48 ./scripts/sauvegarder.sh`) pour une course plus longue. `pg_dump` lit un instantané cohérent sans verrouiller les tables : il ne bloque ni les scans ni le suivi public. Le journal `sauvegarde.log` n'est pas concerné par la rotation.

Facultatif, sauvegarde des certificats Caddy (évite une nouvelle émission après une réinstallation) :

```sh
docker run --rm -v backyard-ultra-tracker_donnees-caddy:/data -v "$PWD":/sauvegarde alpine:3.22 \
  tar czf /sauvegarde/donnees-caddy.tar.gz -C /data .
```

Le nom du volume est préfixé par le nom du projet Compose (`backyard-ultra-tracker_` sur le VPS, `backyard-prod_` pour le test local décrit plus bas) : vérifier avec `docker volume ls`.

Les logos de course sont stockés dans la base : ils sont inclus dans le `pg_dump` ci-dessus et restaurés avec lui. Il n'existe aucun volume séparé pour les logos.

## Restauration

La restauration se fait avec `api` et `web` arrêtés, `base` en marche :

```sh
$PROD stop api web
$PROD exec -T base sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner' < sauvegarde.dump
$PROD start api web
$PROD ps
```

`--clean --if-exists` supprime les objets existants avant de les recréer ; `--no-owner` attribue les objets à l'utilisateur courant (le dump ne contient pas les rôles : le mot de passe de base du VPS cible s'applique). Sur un nouveau VPS : faire le premier déploiement (base vide créée avec le mot de passe de `.env`), puis restaurer.

Contrôle après restauration :
1. `$PROD ps` : `base`, `api` et `web` `healthy` (environ 90 s) ;
2. `curl https://<domaine>/api/version` : la version attendue ;
3. connexion avec l'admin master sur `https://<domaine>/connexion` ;
4. la liste des Courses de l'administration (et l'accueil public) montre les Courses de la sauvegarde, et le suivi public d'une Course affiche ses coureurs.

Restauration facultative des certificats Caddy (`web` arrêté ; `tar` exécuté en root conserve les propriétaires d'origine, l'utilisateur non root `web`) :

```sh
$PROD stop web
docker run --rm -v backyard-ultra-tracker_donnees-caddy:/data -v "$PWD":/sauvegarde alpine:3.22 \
  tar xzf /sauvegarde/donnees-caddy.tar.gz -C /data
$PROD start web
```

## Contrôle des vulnérabilités

À refaire avant chaque version (et à chaque répétition `-rc.N`), résultats datés ci-dessous. Règles de décision :
- dépendance d'exécution (Maven, `dependencies` npm, image) `HIGH` ou `CRITICAL` : corrigée avant la version par montée de version (parent Spring Boot si possible, sinon propriété de version dans `backend/pom.xml`, retirée dès que Spring Boot la rattrape ; pour une image, nouvelle version de l'image de base puis nouvelle `-rc.N`) ; si aucune version corrigée n'existe, acceptée par écrit ici avec son exposition réelle ;
- outillage de build (`devDependencies`, étape de build des Dockerfiles) : corrigé dès qu'une version existe, sans montée de version majeure d'Angular (jamais `npm audit fix --force`) ; un `overrides` est documenté dans `frontend/package.json` et retiré quand le paquet amont embarque la correction. Il n'est pas embarqué dans les images d'exécution.

**npm** (dans `frontend/` et `e2e/`) :
```sh
npm audit --omit=dev && npm audit
```

**Maven** (sans clé NVD, `dependency-check` n'est pas intégré au build) : alertes Dependabot de l'onglet *Security* du dépôt, puis
```sh
cd backend && ./mvnw -B org.codehaus.mojo:versions-maven-plugin:2.21.0:display-dependency-updates
```
Avec une clé NVD : `./mvnw org.owasp:dependency-check-maven:<version>:check -DnvdApiKey=<clé>` (version épinglée).

**Images** (outil épinglé, jamais `latest`) :
```sh
for image in ghcr.io/quenaultthomas/backyard-ultra-tracker-api:<version> \
             ghcr.io/quenaultthomas/backyard-ultra-tracker-web:<version> postgres:17.11-alpine; do
  docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.76.1 \
    image --severity HIGH,CRITICAL "$image"
done
```
Avant le tag, les images construites localement (`docker compose build`, images `backyard-ultra-tracker-api:latest` et `backyard-ultra-tracker-web:latest`) donnent le même résultat que les images publiées pour le même commit.

**Résultats du 2026-10-22 (préparation de la 0.5.0, images locales)** :

| Contrôle | Résultat |
|---|---|
| `npm audit` `frontend/` | avant : 2 alertes (`vite` 8.3.0 `high`, via `@angular/build` 22.2.3 qui l'épingle ; 6 alertes Dependabot `vite`, corrigées en 8.3.3). Après `overrides` `vite` 8.3.3 : `found 0 vulnerabilities` (avec et sans `--omit=dev`) ; `@angular/build` 22.2.3 est la dernière version stable, aucune ne corrige encore `vite` |
| `npm audit` `e2e/` | `found 0 vulnerabilities` (avec et sans `--omit=dev`) |
| Maven (Dependabot + `display-dependency-updates`) | aucune alerte Dependabot Maven ; mises à jour disponibles non liées à une CVE (`postgresql` 42.7.14, Spring Boot 4.2.0-M2 jalon) non appliquées |
| Image `api` | avant : `tomcat-embed-core` 11.0.24 (CVE-2026-65182, CVE-2026-65905, CVE-2026-68525, `CRITICAL`), `jackson-core`/`jackson-databind` 3.1.5 (CVE-2026-89407, -89425, -91776, -91777, -68497, `HIGH`). Corrigé : `tomcat.version` 11.0.25 et `jackson-bom.version` 3.1.7 dans `backend/pom.xml`. Après : 0 vulnérabilité `HIGH`/`CRITICAL` (Ubuntu 24.04 et `application.jar`) |
| Image `web` (`caddy:2.11.7-alpine`) | 5 `HIGH` dans le binaire `caddy` (Go `stdlib` 1.26.8 et `golang.org/x/net` 0.59.0 : CVE-2026-78669 et CVE-2026-78667 déni de service HTTP/2 et en-têtes `Range`, CVE-2026-97031 déni de service TLS ECH, CVE-2026-111943 validation de chaîne x509). **Acceptées** : aucune image Caddy plus récente que 2.11.7 publiée. Exposition réelle : `web` est exposé à Internet, risque de déni de service (pas de fuite de données identifiée) ; CVE-2026-111943 concerne la validation de certificats clients/amont, non utilisée (Caddy ne relaie qu'en HTTP vers `api` dans le réseau Compose). À reprendre (nouvelle `-rc.N`) dès la sortie d'une image Caddy corrigée |
| Image `postgres:17.11-alpine` | 1 `CRITICAL` et 25 `HIGH`, toutes dans `gosu` (compilé avec Go 1.24.6). **Acceptées** : aucune image `postgres` 17 plus récente publiée. Exposition réelle : `gosu` ne sert qu'au point d'entrée pour abandonner les droits root, sans réseau ; `base` n'expose aucun port (réseau Compose interne). PostgreSQL lui-même : 0 vulnérabilité `HIGH`/`CRITICAL` |

Les résultats de la répétition `v0.5.0-rc.1` sur les images publiées sont ajoutés à ce tableau par l'utilisateur (étape 7 de « Répétition générale d'une version »).

## Répétition générale d'une version

Avant chaque version `X.Y.Z`, une pré-version `vX.Y.Z-rc.1` est déployée et éprouvée sur le VPS, dans cet ordre. Exemple pour la 0.5.0 :

1. **Tag de pré-version.** Pull request de la branche de release ouverte et CI verte. Sur la tête de la branche :
   ```sh
   git tag v0.5.0-rc.1 && git push origin v0.5.0-rc.1
   gh run watch
   ```
   Le workflow `Release` doit réussir et créer une Release GitHub marquée *Pre-release*. Les sources restent en `0.5.0` : la vérification ignore le suffixe `-rc.N`.
2. **Packages publics** si c'est la première publication (voir « Accès du VPS aux images »).
3. **Déploiement sur le VPS.** Faire une sauvegarde, puis `VERSION=0.5.0-rc.1` dans `.env` et :
   ```sh
   $PROD pull && $PROD up -d && $PROD ps
   curl https://<domaine>/api/version
   ```
   Le workflow construit les images avec l'argument `VERSION` égal au tag sans le « v » : `app.version` vaut `0.5.0-rc.1` (seul champ de la réponse). Le pied de page de l'application affiche aussi `0.5.0-rc.1`.
4. **Sécurité visible.**
   - `curl -sI https://<domaine>/ | grep -i strict-transport-security` : en-tête présent ;
   - cookies `JSESSIONID` et `XSRF-TOKEN` marqués `Secure` (outils du navigateur, après connexion) ;
   - connecté en admin master, `https://<domaine>/api/test/horloge` répond 404 (l'horloge pilotable n'existe qu'en E2E ; 401 sans session).
5. **Sauvegarde et restauration.** `./scripts/sauvegarder.sh --verifier-restauration` (trois comptages identiques, code 0), copie hors du VPS, poser le `crontab` d'exemple et vérifier qu'un `.dump` apparaît ; puis restauration réelle de ce dump (section Restauration) et son contrôle.
6. **Mini-course de bout en bout.** Course de 2 minutes par Boucle, 2 ou 3 coureurs inscrits, un bénévole affecté ; application installée (PWA) sur le téléphone du bénévole ; un scan fait hors ligne (mode avion) puis transmis ; un coureur sans passage passe en abandon automatique ; un Vainqueur est désigné ; suivi public ouvert sur un second appareil pendant toute la course.
7. **Scan des images** publiées `0.5.0-rc.1` (section « Contrôle des vulnérabilités ») et ajout des résultats datés.
8. **Décision.** Tout est vert : passage à la `0.5.0`. Sinon : correction sur la branche, puis `v0.5.0-rc.2` et reprise à l'étape 1.
9. **Version finale.** Merger la pull request, puis :
   ```sh
   git switch main && git pull
   git tag v0.5.0 && git push origin v0.5.0
   ```
   Déployer par la procédure « Mise à jour » (`VERSION=0.5.0`). Le retour arrière se fait vers `VERSION=0.5.0-rc.1` (mêmes migrations) ou, avec restauration de la sauvegarde faite avant la mise à jour, vers la version précédente.

Un tag publié n'est jamais déplacé ni supprimé : une correction donne toujours un nouveau tag.

## Tester la production en local

Pour ne toucher ni aux données ni au `.env` de la stack de dev, la production se teste dans un projet Compose isolé avec son propre fichier de variables `.env.prod` (ignoré par git) :

```sh
docker compose down            # libère les ports 80 et 8080, sans -v : données de dev conservées
cp .env.example .env.prod
# dans .env.prod : DOMAINE=localhost, BASE_MOT_DE_PASSE=<sortie de openssl rand -hex 24>,
#                  ADMIN_MASTER_PSEUDO=Patron et ADMIN_MASTER_MOT_DE_PASSE=<sortie de openssl rand -base64 18>
P="docker compose -p backyard-prod --env-file .env.prod -f docker-compose.yml -f docker-compose.prod.yml"
# La production tire ses images de ghcr.io : en local, construire des images de test portant les mêmes noms.
# Dans .env.prod : VERSION=local
docker build --build-arg VERSION=local -t ghcr.io/quenaultthomas/backyard-ultra-tracker-api:local backend
docker build --build-arg VERSION=local -t ghcr.io/quenaultthomas/backyard-ultra-tracker-web:local frontend
$P up -d
$P ps                           # 3 services healthy, ports publiés uniquement sur web
curl -k https://localhost/api/sante   # {"status":"UP"}
curl -sI http://localhost/            # 308, Location: https://localhost/
curl --max-time 5 http://localhost:8080/actuator/health   # échec attendu (connexion refusée)
```

Redémarrage automatique : `docker compose kill` et `docker compose stop` sont des arrêts manuels, que `restart: unless-stopped` ne relance volontairement pas. Pour simuler un plantage : `$P exec api kill 1` (arrêt du processus Java), puis `$P ps` : `api` redémarre seul et redevient `healthy` (`docker inspect -f '{{.RestartCount}}' backyard-prod-api-1` vaut 1 juste après ; le compteur repart à 0 si le conteneur est recréé par `down` puis `up`).

**Navigateur : utiliser une fenêtre de navigation privée.** En production, `http://localhost` répond par une redirection 308 permanente vers `https://localhost`, que le navigateur garde en cache. De retour sur la stack de dev (HTTP seul, rien sur le port 443), http://localhost devient alors inaccessible dans ce navigateur. Si c'est déjà arrivé : vider le cache du navigateur pour localhost (F12, clic droit sur le bouton recharger, « Vider le cache et effectuer une actualisation forcée »).

Avec `DOMAINE=localhost`, Caddy émet un certificat de son autorité interne : le navigateur affiche un avertissement (attendu). Pour l'éviter avec curl : `$P cp web:/data/caddy/pki/authorities/local/root.crt ./caddy-root.crt` puis `curl --cacert ./caddy-root.crt https://localhost/api/sante` (ne pas commiter ce fichier).

Pour finir : `$P down -v` (supprime uniquement les volumes du projet `backyard-prod`), puis supprimer `.env.prod` et les éventuels fichiers de sauvegarde. Les étapes détaillées sont dans la section « Tester à la main » de `docs/specs/increment-0.4.md`.

## Données de démonstration

**À ne pas utiliser en production.** `scripts/donnees-demo.sh` crée, par l'API de la stack lancée, les données utiles au test manuel, avec des mots de passe connus :

| Élément | Valeurs |
|---|---|
| Admin | `Nadia` / `mot-de-passe-admin-1` |
| Bénévoles | `Léo` et `Marc` / `mot-de-passe-benevole-1` |
| Coureurs | `Alice`, `Karim`, `Sophie` et `Tom` / `un-mot-de-passe-12` |
| Courses (`EN_PREPARATION`) | « Backyard de démo » (J+30, 6706 m, 60 min, 120 m, 50 participants, 24 boucles), « Backyard express » (J+7, 400 m, 1 min, 5 m, 10, 5), « Backyard mini » (J+14, 1000 m, 2 min, 10 m, 2, 2), « Backyard du jour » (jour même, 400 m, 1 min, 5 m, 10, 5), « Backyard du soir » (jour même, 400 m, 5 min, 5 m, 10, 5) |
| Affectations | `Léo` à « Backyard de démo » et « Backyard du jour » |
| Inscriptions | `Karim` : démo, express, du jour, du soir ; `Sophie` : démo, express, du jour ; `Tom` : démo, du jour |

Soit 23 éléments (7 Comptes, 5 Courses, 2 affectations, 9 Inscriptions). Les deux Courses du jour sont démarrables le jour de leur création : « du jour » se termine en une à deux minutes, « du soir » reste en cours au moins 5 minutes, ce qui montre ensemble les rubriques « En cours » et « Terminées » de l'accueil.

```sh
docker compose up -d --build     # stack de dev, .env avec ADMIN_MASTER_PSEUDO et ADMIN_MASTER_MOT_DE_PASSE
./scripts/donnees-demo.sh        # à la racine du dépôt ; une ligne « créé » ou « déjà présent » par élément
```

- Prérequis sur l'hôte : `bash` et `curl`. Les identifiants de l'admin master sont lus dans l'environnement, à défaut dans le `.env` du répertoire courant (tels quels, sans interprétation du `$` ni des guillemets) ; ils ne sont jamais affichés.
- Relançable : un Compte ou une Course (même nom) déjà présent n'est ni recréé ni modifié. Un pseudo pris par un Compte d'un autre rôle n'est pas détecté.
- `BASE_URL` (défaut `http://localhost`) : adresse visée. Garde-fou : tout hôte autre que `localhost` ou `127.0.0.1` est refusé (code 2, aucune requête) sauf `DEMO_FORCER=oui`, à ne jamais utiliser contre la production : les Comptes `Nadia`, `Léo`, `Marc` et `Alice` ne doivent jamais exister en production.
- Codes de sortie : 0 succès, 1 variable manquante ou réponse inattendue de l'API (statut HTTP affiché, `000` si l'API est injoignable), 2 cible refusée.
- Pour repartir d'une base neuve : `docker compose down -v` puis `docker compose up -d --build`.

## Tests E2E et profil `e2e`

**Jamais en production ni sur le VPS.** Toute la suite Playwright tourne sur la stack `e2e` : la stack normale plus le profil Spring `e2e` sur `api`, activé par `docker-compose.e2e.yml` (seule source de `SPRING_PROFILES_ACTIVE`, jamais lue dans `.env`). Ce profil remplace l'horloge de l'application par une horloge pilotable (heure système plus un décalage) et expose `/api/test/horloge` (lecture, avance, réinitialisation), réservé à l'admin master, avec session et CSRF. Le décalage est en mémoire : un redémarrage de `api` le remet à zéro.

```sh
E2E="docker compose -f docker-compose.yml -f docker-compose.e2e.yml"
$E2E up -d --build
$E2E ps                                  # 3 services healthy
docker compose logs api | grep "Profil e2e"   # une ligne WARN « Profil e2e actif : horloge pilotable… »
# ... tests Playwright (voir e2e/README.md) ...
$E2E down                                # puis docker compose up -d --build pour revenir à la stack de dev
```

Le profil `e2e` est conservé tant que les conteneurs ne sont pas recréés : relancer la stack de dev avec `docker compose up -d --build` (sans le fichier e2e) recrée `api` sans le profil.

**Garde-fou.** `docker-compose.prod.yml` fixe `BACKYARD_ENVIRONNEMENT=production` sur `api`. Si le profil `e2e` y est combiné (par exemple `-f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.e2e.yml`), `api` refuse de démarrer, ne devient jamais `healthy`, et ses logs contiennent « Le profil e2e est interdit en production (horloge pilotable). ». Ne pas définir `BACKYARD_ENVIRONNEMENT` à la main. Un VPS lancé sans `docker-compose.prod.yml` n'est pas protégé par ce garde-fou : toujours utiliser la commande `$PROD` ci-dessus.

**Vérifier qu'un déploiement n'a pas le profil** :

```sh
$PROD logs api | grep -i "profil e2e"     # aucune ligne attendue
$PROD exec api printenv SPRING_PROFILES_ACTIVE   # aucune sortie attendue (code de sortie 1)
```

Connecté en admin master, `https://<domaine>/api/test/horloge` doit répondre 404 (401 sans session, 403 pour un autre rôle).

## Intégration continue

Le workflow `.github/workflows/release.yml` réutilise ce workflow (`workflow_call`) avant de publier les images (voir « Publier une version »). Le workflow `.github/workflows/ci.yml` vérifie la définition de « fini » sur chaque pull request et à chaque push sur `main`. Il ne requiert aucun secret : les identifiants de l'admin master du job `e2e` sont des valeurs de test écrites dans le workflow. Les exécutions obsolètes d'une même PR sont annulées (`concurrency`).

| Job | Ce qu'il fait |
|---|---|
| `back` | `./mvnw verify` dans `backend/` (JDK 25, cache Maven) : tests unitaires, tests d'intégration Testcontainers, règles ArchUnit, seuil JaCoCo de 85 % (lignes et branches). |
| `front` | `npm ci` puis build Angular de production dans `frontend/` (Node 24, cache npm). |
| `e2e` | Démarre la stack `docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build --wait` (tous les services sains), puis lance `npm test` dans `e2e/` (projets `chromium`, `camera`, puis `horloge`, voir `e2e/README.md`). La stack est arrêtée par `docker compose down -v`, succès ou échec. |

Le dépôt étant public, aucun job d'analyse de secrets n'est ajouté. S'il devenait privé, ajouter un job gitleaks.

**Lire un échec.** Onglet *Actions* de la PR, ouvrir le job rouge puis l'étape en échec.
- `back` : les tests en échec sont listés en fin de log (`Tests run: … Failures: …`). Un seuil JaCoCo non atteint s'affiche par « Rule violated ». Ne jamais abaisser le seuil.
- `front` : l'erreur de compilation Angular est dans le log de l'étape de build.
- `e2e` : le log de l'étape Playwright liste le scénario en échec. Les artefacts ci-dessous donnent le détail.

**Artefacts** (en bas de la page de l'exécution, conservés 14 jours, publiés uniquement si `e2e` échoue) :
- `playwright-report` : rapport HTML et traces (`npx playwright show-report <dossier>` ou `npx playwright show-trace <trace.zip>`).
- `docker-compose-logs` : sortie de `docker compose logs` (base, api, web).

Pour rejouer en local : `cd backend && ./mvnw verify`, `cd frontend && npm ci && npm run build -- --configuration production`, et la procédure de `e2e/README.md`.

**Mises à jour de dépendances.** `.github/dependabot.yml` ouvre chaque semaine une PR groupée par écosystème : Maven (`backend/`), npm (`frontend/` et `e2e/`), Docker (`backend/` et `frontend/`) et GitHub Actions. Les actions tierces sont épinglées par SHA de commit (version en commentaire) : Dependabot met les deux à jour.

### Réglages manuels GitHub

À faire une fois, dans *Settings* du dépôt (les rulesets ne sont pas versionnés dans le dépôt) :

- [ ] *Rules > Rulesets > New branch ruleset* ciblant `main`, statut *Active* :
  - [ ] *Require a pull request before merging*
  - [ ] *Require status checks to pass* avec les checks `back`, `front` et `e2e`
  - [ ] *Require linear history*
  - [ ] *Block force pushes*
- [ ] *General > Pull Requests* : ne laisser que *Allow squash merging* (décocher merge commit et rebase merge).
- [ ] *General > Pull Requests* : cocher *Automatically delete head branches*.

Les rulesets et la protection de branche sur un **dépôt privé** exigent un compte GitHub Pro (ou une organisation payante). Sur un dépôt public, ils sont gratuits.

## Notes développeur

- `./mvnw verify` (dans `backend/`) nécessite un **démon Docker accessible** : les tests d'intégration lancent PostgreSQL par Testcontainers. Sous WSL, activer l'intégration WSL de Docker Desktop (Settings > Resources > WSL integration) ou lancer le démon Docker dans la distribution.
- Tests E2E (`e2e/`) : si Chromium de Playwright refuse de démarrer faute de bibliothèques système (WSL, Linux) :
  ```sh
  sudo npx playwright install-deps chromium
  ```
  Sous nvm, `sudo` ne voit pas `npx` : `sudo env "PATH=$PATH" npx playwright install-deps chromium`.
- Les tests E2E tournent sur la stack `e2e` (`docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build`, `BASE_URL=http://localhost`, voir « Tests E2E et profil `e2e` »). Depuis 1.4, ils supposent un admin master : renseigner `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE` dans `.env` avant le démarrage, et les mêmes valeurs dans `E2E_ADMIN_MASTER_PSEUDO` et `E2E_ADMIN_MASTER_MOT_DE_PASSE` pour Playwright.
