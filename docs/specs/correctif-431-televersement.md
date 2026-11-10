# Correctif : 431 (ou 400, 502, ECONNRESET) après un 413 de téléversement

Origine : point ouvert hérité de `docs/specs/increment-10.1.md` (10.1a) « 431 après un 413 d'envoi de photo ». Décision de l'utilisateur : on corrige. Branche `correctif/points-ouverts-jalon-10`.

## 0. Diagnostic (validé, reproduit à volonté : 15 échecs sur 1000 sur la stack actuelle)

Un `PUT` multipart trop gros (photo de profil `/api/comptes/moi/photo`, logo de Course `/api/administration/courses/{id}/logo`) est coupé par Spring à 3 Mo (`max-request-size`) : Tomcat répond 413 avant d'avoir lu tout le corps. Caddy relaie ce 413 alors que son proxy lit encore le corps envoyé par le client. Le serveur HTTP de Go garde alors la connexion client en keep-alive avec des octets non lus, et la requête suivante (par exemple `GET /api/comptes/moi`) est analysée sur ce reste : **431** (ou **400** si les octets sont de vraies données d'image), ou plus rarement `ECONNRESET` / 502.

Ce n'est pas un artefact de test : un navigateur peut subir la même chose après un 413 de logo ou de photo (la connexion est réutilisée par la requête suivante).

Variantes essayées et insuffisantes : `Connection: close` sur le 413 (perd des 413), `request_buffers` seul, absorption Tomcat seule.

Correctif retenu (« G »), mesuré : 0 anomalie sur 1000 `PUT` + `GET` en parallèle, et sur 900 envois sur socket brute à 3,5 / 5 / 8 Mo ; 413 JSON `PHOTO_TROP_VOLUMINEUSE` conservé ; `PUT` valide : 200. L'effet vient de la **combinaison** Caddy (`request_body max_size 8MiB` + `request_buffers 8MiB`) et Tomcat (`max-swallow-size: 8MB`) ; le réglage `swallowAbortedUploads` du contexte Tomcat n'a pas d'effet mesurable à lui seul (voir RG4).

Taille de la production : très petite (environ 3 lignes dans le Caddyfile, 1 ligne dans `application.yml`, une classe de configuration d'une quinzaine de lignes, une section de documentation). Aucune modification de l'API, des DTO, de la base, du code Angular, de `.env.example` ni des fichiers `docker-compose*.yml`. Aucun nouvel écran.

## 1. Périmètre

**Inclus**
- **[front]** `frontend/Caddyfile`, bloc `handle /api/*` : `request_body { max_size 8MiB }` (au lieu de 4MiB) et `reverse_proxy api:8080 { request_buffers 8MiB }`. Les blocs `handle /api/sante` et `handle /api/version` sont inchangés.
- **[back]** `backend/src/main/resources/application.yml` : `server.tomcat.max-swallow-size: 8MB`.
- **[back]** Une classe `TeleversementConfiguration` dans `fr.backyard.tracker.partage.infrastructure` (sur le modèle de `AdresseClienteConfiguration`), qui déclare un `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` faisant `fabrique.addContextCustomizers(contexte -> contexte.setSwallowAbortedUploads(true))`. Le réglage porte sur le **contexte Tomcat** (`org.apache.catalina.Context` / `StandardContext`), pas sur le connecteur. Tomcat 11 a déjà ce défaut à `true` : le bean rend le réglage explicite. Il ne touche ni aux valves ni à l'ordre de `valveAdressePair`.
- **[back]** `docs/deploiement.md` : section dédiée (RG8 à RG10).
- Amendement de `increment-2.3.md`, `increment-10.1.md` et du test existant de 8.3 (section 2 bis).

**Exclu**
- Toute modification des limites fonctionnelles : 2 097 152 octets par fichier, `multipart.max-file-size: 2MB`, `max-request-size: 3MB`, codes `LOGO_TROP_VOLUMINEUX` / `PHOTO_TROP_VOLUMINEUSE` (inchangés).
- Limitation de débit ou de connexions simultanées dans Caddy (voir point ouvert 2).
- Code Angular : le front traite déjà tout 413 comme « trop volumineux » (2.3 RG22 ; 10.1b).

## 2. Règles de gestion

- **RG1** [front] : dans `handle /api/*` du Caddyfile, `request_body { max_size 8MiB }` (8 388 608 octets). Au-delà, Caddy répond lui-même 413 (corps non `ProblemDetail`). `handle /api/sante` et `handle /api/version` restent sans `request_body`.
- **RG2** [front] : dans le même bloc, `reverse_proxy api:8080 { request_buffers 8MiB }` : Caddy lit le corps du client en entier (jusqu'à 8 MiB) avant de le transmettre à `api`, ce qui empêche qu'un 413 de l'api arrive pendant que le proxy lit encore le client.
- **RG3** [back] : `server.tomcat.max-swallow-size: 8MB` (8 388 608 octets, `MB` de Spring = 1024 × 1024) : Tomcat lit et jette jusqu'à 8 Mo d'un envoi refusé avant de fermer la connexion.
- **RG4** [back] : le **contexte Tomcat** a `swallowAbortedUploads = true`, posé explicitement par `TeleversementConfiguration` : Tomcat n'interrompt pas la lecture du corps après une réponse d'erreur. Tomcat 11 a déjà ce défaut ; le bean le rend explicite et le protège d'un changement de défaut, sans effet mesurable à lui seul. L'effet observé vient de la combinaison RG1 + RG2 + RG3.
- **RG5** [back + front] : cohérence. `max-swallow-size` ≥ limite Caddy − `max-request-size`, c'est-à-dire 8 MiB − 3 MiB = 5 MiB ≤ 8 MiB. Toute évolution d'une des trois valeurs revérifie cette inégalité (CA3).
- **RG6** : comportement observable, à deux niveaux.
  - **Tomcat direct (sans Caddy)** : pour un corps multipart de 3,5 à 8 Mo sur une des deux routes, le 413 JSON `ProblemDetail` (`PHOTO_TROP_VOLUMINEUSE` ou `LOGO_TROP_VOLUMINEUX` selon le chemin, 10.1 RG12) arrive **en entier**, sans reset de connexion ni écriture interrompue pendant l'envoi du corps. Tomcat répond avec `Connection: close` et coupe la connexion : ce comportement est normal et n'est pas une anomalie. La requête suivante, sur une **nouvelle connexion** (puisque le serveur a annoncé `Connection: close`), répond normalement (200 pour `GET /api/comptes/moi`).
  - **Via `web` (Caddy)** : un corps de plus de 3 Mo et d'au plus 8 MiB reçoit le même 413 JSON de Spring, puis la requête suivante dans le même contexte client (connexion client↔Caddy réutilisée si le client le fait) est traitée normalement (200), **jamais 431, 400 parasite, 502 ni coupure de connexion**.
  - Un corps de plus de 8 MiB : Caddy répond 413 sans corps JSON. Le front gère « tout 413 ».
  - L'ancienne photo, l'ancien logo sont conservés dans tous les cas.
- **RG7** : les limites fonctionnelles et les codes d'erreur de 2.3 et 10.1 (hors valeur de la limite Caddy) sont inchangés : 2 097 152 octets accepté, 2 097 153 refusé.
- **RG8** : risque mémoire de `web`, **accepté comme risque connu**. `request_buffers` place en mémoire jusqu'à 8 Mio **par requête `/api/*` en cours** (avant toute authentification : un client anonyme y est soumis aussi). Avec `WEB_MEMOIRE_MAX=256m` (production), une vingtaine d'envois de 8 Mio simultanés, ou des envois lents qui retiennent leur tampon, peuvent faire tuer `web` par le noyau (OOM) ; Docker le relance (`restart: unless-stopped`), l'indisponibilité dure quelques secondes et `api`, la base et les sessions ne sont pas touchées. Seules les requêtes volumineuses pèsent : une requête ordinaire ne tamponne que la taille de son corps (quelques octets).
- **RG9** : bande passante. Tomcat lit et jette jusqu'à 8 Mo d'un envoi refusé, et Caddy en lit autant : un envoi refusé consomme jusqu'à 8 Mo entrants au lieu de 2 à 3. Accepté.
- **RG10** : `docs/deploiement.md` gagne une section « Envois volumineux refusés (413) » qui décrit : la cause du 431 et le correctif (trois éléments), la cohérence de RG5 (et l'obligation de la revérifier si une limite change), le risque mémoire de RG8 avec la mesure d'exploitation (surveiller `docker inspect --format '{{.State.OOMKilled}}' <conteneur web>` et `$PROD ps` pour un redémarrage de `web` ; en cas d'OOM, relever `WEB_MEMOIRE_MAX` à `384m` puis `$PROD up -d` ; contrôle du volume entrant au niveau du VPS ou du pare-feu si un abus est constaté), la bande passante de RG9 et la mention « risque accepté ». La section « Ressources : mémoire et journaux » renvoie vers elle.

## 2 bis. Amendements aux specs et tests existants

Les documents des incréments livrés ne sont pas réécrits (même principe que 2.3 point 15) ; le `fonctionnel` y ajoute seulement une note « amendé par `correctif-431-televersement.md` » en tête de chaque passage listé, et les valeurs ci-dessous font foi.

`docs/specs/increment-2.3.md` :
| Passage | Ancien | Nouveau |
|---|---|---|
| Périmètre technique du Caddyfile (« ajout de `request_body { max_size 4MiB }` (RG9) ») | 4MiB | `request_body { max_size 8MiB }` et `reverse_proxy` avec `request_buffers 8MiB` |
| RG9, niveau (3) : « Caddy : `request_body { max_size 4MiB }` ... Tomcat, qui n'absorbe qu'environ 2 Mo ... » | 4 MiB ; justification « Tomcat n'absorbe qu'environ 2 Mo » | 8 MiB ; justification : Caddy tamponne (`request_buffers 8MiB`) et Tomcat absorbe jusqu'à 8 Mo (`max-swallow-size`), ce qui évite le 431 de la requête suivante |
| Cas limites, « Limites de taille » : « 5 000 000 octets : 413 de Caddy (corps non JSON) » | 413 de Caddy, non JSON | 5 000 000 octets : **413 JSON `LOGO_TROP_VOLUMINEUX` de Spring** ; au-delà de 8 MiB (8 388 608 octets) : 413 de Caddy non JSON |
| Contrat, code 413 : « Au-delà de 4 MiB : 413 de Caddy » | 4 MiB | Au-delà de 8 MiB |
| Point ouvert 8 (« 413 de Caddy hors `ProblemDetail` ... 4 MiB ... inférieure à ce que Tomcat absorbe ») | 4 MiB | 8 MiB ; valeur validée par ce correctif, l'argument « inférieure à ce que Tomcat absorbe » est remplacé par RG5 |

`docs/specs/increment-10.1.md` :
| Passage | Ancien | Nouveau |
|---|---|---|
| Contexte / reprise de 2.3 (« `request_body { max_size 4MiB }` sur `/api/*` ») | 4 MiB | 8 MiB + `request_buffers 8MiB` |
| Périmètre technique : « `frontend/Caddyfile` : **inchangé** dans les deux incréments (... limite 4 MiB déjà en place ...) » | inchangé, 4 MiB | modifié par ce correctif : 8 MiB et `request_buffers 8MiB` ; CSP inchangée |
| **RG12** : « Caddy (4 MiB sur `/api/*`) ... avant la limite de 4 MiB de Caddy ... la limite Caddy reste un filet de sécurité » | 4 MiB | Caddy 8 MiB ; la coupure de Spring (3 Mo) précède toujours la limite Caddy ; au-delà de 8 MiB, 413 de Caddy sans corps JSON ; nouveau complément : la requête suivante n'est plus perturbée (RG6 de ce correctif) |
| Cas limites, « Limites de taille » et contrat, code 413 (« avant la limite Caddy de 4 MiB ») | 4 MiB | 8 MiB |
| **CA12** (intégration, renvoi : « 5 000 000 octets via Caddy ... vérifié en E2E (CA33) ») | renvoi à CA33 | renvoi à CA33 **et** à CA6 de ce correctif (non-régression en boucle) ; énoncé du test inchangé |
| **CA33** (E2E, « un `PUT` direct de 5 000 000 octets via Caddy ... coupure à 3 Mo avant la limite Caddy de 4 MiB ») | 4 MiB | 8 MiB ; un corps de **5 000 000 octets donne toujours le 413 JSON `PHOTO_TROP_VOLUMINEUSE` de Spring**, suivi d'un `GET /api/comptes/moi` en 200 ; au-delà de 8 MiB, Caddy répond lui-même 413 sans corps JSON |

Tests existants amendés :
- **8.3 `ca7_caddyfile_inchange_par_rapport_a_main`** : reformulé. Les alias `handle /api/sante` et `handle /api/version` restent inchangés par rapport à `main` ; seul le bloc `handle /api/*` principal change (`max_size 8MiB`, `request_buffers 8MiB`). Le test n'est ni supprimé ni affaibli : il continue de vérifier que le reste du Caddyfile (en-têtes, CSP, alias, application Angular) est identique.
- **E2E `photo-deploiement.spec.ts`** (10.1 CA33) : énoncé amendé (8 MiB, `GET` suivant en 200) ; voir CA9.

## 3. Cas limites

- 3 145 728 octets exactement (= `max-request-size`) puis 3 145 729 octets : le premier est traité par le contrôleur (413 car le fichier dépasse 2 Mo de toute façon si sa partie fait plus de 2 097 152 octets), le second est coupé par Spring : même 413 JSON.
- 8 388 608 octets exactement : accepté par Caddy, 413 JSON de Spring. 8 388 609 octets : 413 de Caddy, sans JSON.
- Tomcat direct : après le 413, la connexion est fermée par le serveur (`Connection: close`) ; un client qui réutilise cette connexion ouvre simplement une nouvelle connexion (ce n'est pas une erreur du correctif).
- Plusieurs `PUT` refusés en parallèle depuis le même navigateur (connexions réutilisées côté Caddy) : aucun ne perturbe la requête suivante de sa connexion.
- Anonyme : un `PUT` de 5 000 000 octets sans session reçoit la réponse habituelle de la sécurité (401 ou 403 `CSRF_INVALIDE`) ou le 413 de Spring selon l'ordre de traitement existant ; dans tous les cas, jamais 431 sur la requête suivante. L'ordre actuel est conservé.
- Routes autres que photo et logo : pas de limite ajoutée, un corps de plus de 8 MiB sur n'importe quelle route `/api/*` reçoit le 413 de Caddy.
- `/api/sante` et `/api/version` : inchangés, aucun tampon.
- Redémarrage de `web` par le noyau (RG8) : les requêtes en cours échouent côté client, aucune donnée n'est perdue ; un `PUT` interrompu n'a pas modifié la photo ni le logo.

## 4. Contrat d'API

Aucun endpoint ni DTO ajouté ou modifié. Les contrats de `PUT /api/comptes/moi/photo` (10.1) et `PUT /api/administration/courses/{id}/logo` (2.3) sont inchangés, sauf la valeur de la limite Caddy (section 2 bis).

Réponses observables d'un envoi multipart via `web` (valeurs de limite : fichier 2 097 152 octets, requête 3 Mo, Caddy 8 MiB) :

| Corps | Réponse |
|---|---|
| fichier valide jusqu'à 2 097 152 octets | 200 (photo) / 200 (logo), inchangé |
| fichier de 2 097 153 octets à 8 388 608 octets de corps | **413** `ProblemDetail` JSON : `PHOTO_TROP_VOLUMINEUSE` (« La photo ne doit pas dépasser 2 Mo. ») ou `LOGO_TROP_VOLUMINEUX` (« Le logo ne doit pas dépasser 2 Mo. ») ; `title` « Fichier trop volumineux » |
| plus de 8 388 608 octets | **413** de Caddy, corps non JSON |
| requête suivante dans le même contexte client (via `web`) | réponse normale de la route (jamais 431, 400 parasite, 502) |

## 5. Écrans

Aucun écran créé ou modifié. Les écrans concernés par le comportement (formulaire de logo de « Gestion des courses », 2.3 ; « Mon compte » / photo de profil, 10.1b) gardent leurs informations, actions et messages d'erreur (« Fichier trop volumineux » pour tout 413). Leur couverture E2E existante doit rester verte (CA9).

## 6. Critères d'acceptation

| CA | Cible | Énoncé | Niveau |
|---|---|---|---|
| CA1 | back | Étant donné `TeleversementConfiguration`, une fabrique `TomcatServletWebServerFactory` et un contexte Tomcat neuf (`StandardContext`, `swallowAbortedUploads` forcé à `false` avant le test, sans Spring), quand le customizer est appliqué à la fabrique puis ses `ContextCustomizer` au contexte, alors `getSwallowAbortedUploads()` vaut `true` ; appliqué deux fois, le résultat est identique (RG4) | unitaire |
| CA2 | back | Étant donné le contexte complet démarré sur un serveur réel (`RANDOM_PORT`), alors `server.tomcat.max-swallow-size` vaut 8 388 608 octets (`8MB`), le protocole du connecteur a `getMaxSwallowSize() == 8388608` et le **contexte Tomcat** de l'application a `getSwallowAbortedUploads() == true` ; les valeurs multipart restent `2MB` (fichier) et `3MB` (requête) (RG3, RG4, RG7) | intégration |
| CA3 | back | Étant donné `max-swallow-size` (8 388 608), `max-request-size` (3 145 728) et la limite `max_size` lue dans `frontend/Caddyfile` (8 388 608), alors `max-swallow-size >= max_size - max-request-size` (8 388 608 >= 5 242 880) ; le test échoue avec un message citant les trois valeurs si l'inégalité est rompue (RG5) | intégration |
| CA4 | front | Étant donné le fichier `frontend/Caddyfile`, alors dans le bloc `handle /api/*` figurent `max_size 8MiB` et `request_buffers 8MiB` dans le `reverse_proxy api:8080`, aucune occurrence de `4MiB` ne subsiste, et les blocs `handle /api/sante` et `handle /api/version` ne contiennent ni `request_body` ni `request_buffers` et sont identiques à ceux de `main` (test 8.3 `ca7_caddyfile_inchange_par_rapport_a_main` reformulé en ce sens) (test de fichier lisant le fichier à la racine du dépôt) (RG1, RG2) | intégration |
| CA5 | back | Étant donné un serveur réel (Tomcat direct, sans Caddy), un coureur connecté et une socket brute, quand on envoie un `PUT` multipart de 3 500 000, 5 000 000 puis 8 000 000 octets sur `/api/comptes/moi/photo`, alors, sur 20 itérations par taille : l'envoi du corps se termine sans reset de connexion ni erreur d'écriture, la réponse est lue en entier et c'est un 413 JSON `PHOTO_TROP_VOLUMINEUSE` (en-tête `Connection: close` accepté, pas une anomalie) ; un `GET /api/comptes/moi` émis ensuite sur une **nouvelle connexion** (le serveur ayant annoncé `Connection: close`) répond 200 ; la photo est inchangée. Même chose pour `/api/administration/courses/{id}/logo` avec `Patron` à 5 000 000 octets (413 `LOGO_TROP_VOLUMINEUX`). Le comportement « même connexion client↔Caddy, jamais 431 » n'est pas testé ici : il relève de CA6 à CA8 (RG3, RG4, RG6, RG7) | intégration |
| CA6 | front | Étant donné la stack `docker compose` du profil `e2e` (via `web`) et un coureur connecté, quand 5 boucles s'exécutent en parallèle de 100 itérations chacune de « `PUT` de 5 000 000 octets sur `/api/comptes/moi/photo` puis `GET /api/comptes/moi` dans le même contexte de requête », alors les 500 `PUT` reçoivent 413 avec `code` `PHOTO_TROP_VOLUMINEUSE` (corps `ProblemDetail`) et les 500 `GET` 200 ; aucune erreur 431, 400, 502 ni `ECONNRESET` ; la photo du coureur est inchangée (même URL, même ETag) (RG1, RG2, RG6) | E2E |
| CA7 | front | Étant donné `Patron` connecté et une Course, quand 5 boucles en parallèle de 100 itérations alternent des envois de 2 097 153 et 5 000 000 octets sur `/api/administration/courses/{id}/logo` suivis de `GET /api/administration/courses/{id}`, alors tous les envois reçoivent 413 `LOGO_TROP_VOLUMINEUX` et tous les `GET` 200, sans 431/400/502/`ECONNRESET` ; le logo de la Course est inchangé (RG6, RG7) | E2E |
| CA8 | front | Étant donné un coureur connecté, quand il envoie via `web` un corps de 9 000 000 octets (> 8 MiB) sur `/api/comptes/moi/photo`, alors la réponse est un 413 (corps JSON non exigé) et le `GET /api/comptes/moi` suivant dans le même contexte répond 200, sur 20 itérations ; avec un corps de 8 388 608 octets : 413 JSON `PHOTO_TROP_VOLUMINEUSE` (RG1, RG6) | E2E |
| CA9 | front | Les E2E existants `e2e/tests/logo-course.spec.ts` (écran « Gestion des courses », 2.3) et `e2e/tests/photo-deploiement.spec.ts` (10.1 CA33, énoncé amendé : 8 MiB, `GET` suivant en 200) restent verts sans test supprimé ni affaibli ; les E2E d'écran de photo de profil (10.1b) aussi (RG6, RG7) | E2E |
| CA10 | back | Étant donné `docs/deploiement.md`, alors il contient la section « Envois volumineux refusés (413) » citant `WEB_MEMOIRE_MAX`, `request_buffers`, `max-swallow-size`, `OOMKilled`, la valeur `384m` et la mention « risque accepté » ; la section « Ressources : mémoire et journaux » contient un renvoi vers elle (test de fichier) (RG8, RG9, RG10) | intégration |

Répartition : unitaire 1 (CA1), intégration 5 (CA2, CA3, CA4, CA5, CA10), E2E 4 (CA6 à CA9). Total 10 CA, 10 RG.

Couverture des RG : RG1 CA4/CA6/CA8, RG2 CA4/CA6, RG3 CA2/CA5, RG4 CA1/CA2/CA5, RG5 CA3, RG6 CA5/CA6/CA7/CA8/CA9, RG7 CA2/CA5/CA7/CA9, RG8 CA10, RG9 CA10, RG10 CA10.

Couverture des écrans : aucun écran modifié ; les écrans concernés par le comportement (logo de Course, photo de profil) restent couverts par CA9 (E2E).

Tests : chaque test référence le CA couvert. Les tests CA6 à CA8 utilisent `APIRequestContext` de Playwright (pas d'écran) sur le port 80 de `web`.

## 7. Tester à la main

Prérequis : `docker compose up -d --build` (dev), tous les services sains ; `.env` avec `ADMIN_MASTER_PSEUDO` et `ADMIN_MASTER_MOT_DE_PASSE` ; `scripts/donnees-demo.sh` lancé (coureur `Alice`, mot de passe `un-mot-de-passe-12`). Les commandes se lancent depuis un terminal de l'hôte ; `head` et `curl` suffisent.

1. Préparer une session de `Alice` : `curl -s -c /tmp/alice -o /dev/null http://localhost/api/csrf`, puis `curl -s -b /tmp/alice -c /tmp/alice -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $(awk '$6=="XSRF-TOKEN"{print $7}' /tmp/alice)" -d '{"pseudo":"Alice","motDePasse":"un-mot-de-passe-12"}' http://localhost/api/connexion` : attendu 200. Relire le jeton CSRF (`/api/csrf` à nouveau, comme le fait `scripts/donnees-demo.sh`).
2. Fabriquer un fichier de 5 000 000 octets : `head -c 5000000 /dev/urandom > /tmp/gros.bin`.
3. Envoyer la photo et enchaîner la lecture dans la même commande `curl` (connexion réutilisée vers `web`) : `curl -s -b /tmp/alice -H "X-XSRF-TOKEN: <jeton>" -F fichier=@/tmp/gros.bin -X PUT http://localhost/api/comptes/moi/photo --next -s -b /tmp/alice http://localhost/api/comptes/moi`. Attendu : le premier corps est un `ProblemDetail` JSON avec `code` `PHOTO_TROP_VOLUMINEUSE`, le second est le compte d'Alice (200). Aucun message 431, 400 ni « connection reset ».
4. Répéter 50 fois (boucle shell) : aucune anomalie ; avant le correctif, environ 1 itération sur 70 échouait.
5. Envoyer 9 000 000 octets (`head -c 9000000 /dev/urandom`) : attendu 413 (sans corps JSON), puis `GET /api/comptes/moi` en 200.
6. Avec `Patron` (admin master), même essai de 5 000 000 octets sur `/api/administration/courses/<id>/logo` : 413 `LOGO_TROP_VOLUMINEUX`, puis lecture de la Course en 200.
7. Dans l'interface : Mon compte de `Alice`, choisir une petite photo valide : enregistrée (non-régression) ; « Gestion des courses » avec `Patron`, logo valide de moins de 2 Mo : enregistré.
8. Lire la section « Envois volumineux refusés (413) » de `docs/deploiement.md`.

## 8. Points ouverts

1. **Risque mémoire accepté** (RG8) : à confirmer par l'utilisateur. Un client anonyme peut, par envois simultanés ou lents de 8 Mio, faire tuer `web` (OOM à 256 Mo) ; `web` redémarre seul. Mesure : documentation. Alternative non retenue : `WEB_MEMOIRE_MAX=384m` par défaut en production (la RAM disponible sur le VPS de 2 Go est déjà allouée à 1,75 Go, `.env.example` resterait à modifier).
2. Aucune limitation de débit ou de connexions simultanées dans Caddy (non native) : traitement éventuel dans un incrément de durcissement (audit).
3. Le comportement de Caddy au-delà de 8 MiB n'a pas été mesuré sur 1000 itérations dans le diagnostic ; CA8 le vérifie sur 20 itérations. En cas d'anomalie, le développeur le signale (le `fonctionnel` met la spec à jour) plutôt que d'affaiblir CA8.
4. CA3 et CA4 lisent `frontend/Caddyfile` depuis un test du module `backend` : le chemin dépend du répertoire de lancement de Maven. Si la CI exécute les tests du back hors du dépôt complet, déplacer ces deux tests dans `e2e/` (même contenu, niveau inchangé) : à trancher par `developpeur-back` à l'implémentation. Le test 8.3 `ca7_caddyfile_inchange_par_rapport_a_main` est déjà dans ce cas : il reste là où il est et est reformulé.
5. Amendements par renvoi, sans réécrire les specs 2.3 et 10.1. À confirmer.
6. Le 413 de Caddy (> 8 MiB) reste sans corps JSON : acceptable car le front traite tout 413 de la même façon ; un `handle_errors` renvoyant un `ProblemDetail` n'est pas inclus.
7. La mesure d'exploitation (alerte sur redémarrage de `web`) reste manuelle ; pas de supervision dans le projet.
8. `swallowAbortedUploads` n'a pas d'effet mesurable seul (défaut déjà `true` en Tomcat 11) : CA1 et CA2 vérifient le réglage explicite, pas un effet fonctionnel isolé ; l'effet est couvert par CA5 et CA6.
