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
| `VERSION` | **obligatoire** en production | version des images `api` et `web` tirées de `ghcr.io`, sans le « v » du tag (`0.4.0`, `0.4.0-rc.1`) ; ignorée en dev et en E2E |
| `DOMAINE` | **obligatoire** | nom de domaine servi en HTTPS |
| `CONNEXION_ECHECS_MAX` | facultative (défaut `5`, entier ≥ 1) | échecs de connexion consécutifs sur un même pseudo avant blocage temporaire |
| `CONNEXION_BLOCAGE_SECONDES` | facultative (défaut `900`, entier ≥ 1) | durée du blocage, et délai au-delà duquel des échecs partiels sont oubliés |
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

Une valeur de `HACHAGE_CONCURRENCE_MAX` ou `HACHAGE_CONCURRENCE_MAX_PAR_IP` non entière ou inférieure à 1 empêche de même `api` de démarrer ; `$PROD logs api` cite `backyard.hachage.concurrence-max` ou `backyard.hachage.concurrence-max-par-ip`. Une valeur de `COOKIES_SECURE` autre que `true` ou `false` empêche aussi le démarrage (le journal cite `server.servlet.session.cookie.secure`). Aucune de ces variables n'est obligatoire.

### HTTPS, HSTS et cookies

En production (site servi sur `DOMAINE`), Caddy ajoute `Strict-Transport-Security: max-age=31536000` à toute réponse HTTPS (un an, sans `includeSubDomains` ni `preload`) ; le site HTTP `:80` du dev et des E2E ne l'émet jamais. **Attention** : un navigateur qui a reçu cet en-tête pour un domaine refuse ensuite de le joindre en HTTP pendant un an ; ne servir en HTTPS que des domaines qui le resteront (pour `localhost`, oublier l'entrée dans `chrome://net-internals/#hsts` ou utiliser une fenêtre privée). Les cookies `JSESSIONID` et `XSRF-TOKEN` portent `Secure` si et seulement si `COOKIES_SECURE=true`, valeur imposée par `docker-compose.prod.yml` ; dev et E2E, en HTTP, passent `false` (un cookie `Secure` n'y serait pas renvoyé par tous les navigateurs).

Une valeur de `BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE` non entière ou négative (`abc`, `-1`) empêche aussi `api` de démarrer ; le journal cite `backyard.limite-debit-public.par-minute`.

### Limitation des connexions : compromis connu

Le blocage temporaire après `CONNEXION_ECHECS_MAX` échecs porte sur le **pseudo**, pas sur l'adresse IP : il protège chaque compte contre la recherche de mot de passe, quelle que soit l'origine des tentatives. Contrepartie : n'importe qui connaissant un pseudo (classement et listes publics) peut le bloquer pendant `CONNEXION_BLOCAGE_SECONDES` en enchaînant des mots de passe faux, **y compris celui de l'admin master**, et recommencer à la fin du blocage. Le mot de passe n'est jamais exposé, mais la connexion du compte visé est refusée (429 `TENTATIVES_EXCESSIVES`) pendant ce temps.

- Une session déjà ouverte n'est pas affectée : le jour d'une course, l'admin master se connecte **avant** le départ et garde sa session (12 h d'inactivité au plus ; ne pas se déconnecter).
- Pour lever un blocage en urgence : `$PROD restart api` remet à zéro compteurs et blocages, mais déconnecte tout le monde (sessions en mémoire) ; à éviter pendant une course.
- Si un blocage abusif se répète, réduire temporairement `CONNEXION_BLOCAGE_SECONDES` dans `.env` puis `$PROD up -d api` (même effet de déconnexion).

Il n'y a pas de limite de débit par adresse IP sur la connexion (le Caddy standard n'en fournit pas ; seules les routes publiques de lecture sont limitées, voir « Suivi public sous charge »). Le coût des tentatives est borné autrement : chaque vérification Argon2id se fait hors transaction (aucune connexion de base tenue pendant le calcul), au plus `HACHAGE_CONCURRENCE_MAX_PAR_IP` calculs tournent en même temps pour une même adresse cliente (lue derrière le proxy de confiance `web`), et au plus `HACHAGE_CONCURRENCE_MAX` au total. Une seule adresse ne peut donc plus saturer le plafond global : avec les défauts (1 et 4), il reste au moins 3 places aux autres adresses, et l'admin master qui se connecte d'ailleurs est servi. La décision ne dépend que de l'adresse et des places, jamais du pseudo (aucune énumération possible). Limites restantes : `k` adresses distinctes occupent jusqu'à `k × HACHAGE_CONCURRENCE_MAX_PAR_IP` places (attaque répartie) ; un admin derrière la même adresse que l'attaquant (NAT, wifi partagé) peut recevoir 503 : la conduite reste de se connecter avant le départ, hors de ce réseau si besoin. Plusieurs bénévoles derrière un même wifi qui se reconnectent ensemble passent un par un (quelques dizaines de ms chacun) ; s'ils reçoivent des 503, relever `HACHAGE_CONCURRENCE_MAX_PAR_IP`. Une rafale sur `/api/connexion` ou `/api/comptes` reçoit des 503 `SERVICE_SATURE` (en-tête `Retry-After`) au lieu d'épuiser la mémoire de `api` ou le pool de connexions de la base, que partagent les scans des bénévoles et le planificateur. Limite restante : une rafale massive et soutenue (plusieurs centaines de requêtes simultanées) peut encore occuper les fils d'exécution de Tomcat pendant l'attente d'une seconde ; une limite par IP dans Caddy (module tiers `caddy-ratelimit`, image Caddy à construire) reste la protection à ajouter avant une exposition à forte audience. `$PROD logs api | grep "calculs d'empreinte saturés"` montre ces refus et le plafond atteint (« plafond par adresse » ou « plafond global »).

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
   - `VERSION=` la version à déployer, sans le « v » (ex. `0.4.0`) ;
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
7. Vérifier le site : `curl https://<domaine>/api/sante` répond `{"status":"UP"}`, `curl https://<domaine>/api/version` indique la version déployée (`app.version`, doit valoir celle de `.env`), et `http://<domaine>/` redirige (308) vers HTTPS.
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
3. Comparer `.env.example` avec `.env` et ajouter les nouvelles variables éventuelles (depuis 1.4 : `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE`, obligatoires ; ignorées si l'admin master existe déjà ; depuis la correction de l'audit du 2026-10-16 : `HACHAGE_CONCURRENCE_MAX`, `API_MEMOIRE_MAX`, `BASE_MEMOIRE_MAX` et `WEB_MEMOIRE_MAX`, toutes facultatives ; depuis 7.3 : `HACHAGE_CONCURRENCE_MAX_PAR_IP` et `COOKIES_SECURE`, facultatives, la seconde imposée à `true` en production).
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

Les sessions de connexion sont conservées en mémoire par `api` (expiration après 12 h d'inactivité) : toute mise à jour ou tout redémarrage de `api` déconnecte tous les utilisateurs, qui doivent se reconnecter. Éviter de mettre à jour pendant une course.

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

Sauvegarde de la base au format personnalisé de `pg_dump`, lancée dans le conteneur `base` (l'utilisateur et la base sont lus depuis le conteneur, pas depuis l'hôte) :

```sh
$PROD exec -T base sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > sauvegarde-$(date +%Y%m%d-%H%M).dump
```

Vérifier que le fichier n'est pas vide, puis le copier hors du VPS. La sauvegarde est manuelle (aucune tâche planifiée fournie).

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

`--clean --if-exists` supprime les objets existants avant de les recréer ; `--no-owner` attribue les objets à l'utilisateur courant. Sur un nouveau VPS : faire le premier déploiement (base vide créée avec le mot de passe de `.env`), puis restaurer.

Restauration facultative des certificats Caddy (`web` arrêté ; `tar` exécuté en root conserve les propriétaires d'origine, l'utilisateur non root `web`) :

```sh
$PROD stop web
docker run --rm -v backyard-ultra-tracker_donnees-caddy:/data -v "$PWD":/sauvegarde alpine:3.22 \
  tar xzf /sauvegarde/donnees-caddy.tar.gz -C /data
$PROD start web
```

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
