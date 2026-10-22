# Increment 7.2 : Suivi public sous charge (PER-04)

**Dépendance : 6.1 à 6.4 (suivi public, accueil public), 4.8 à 4.10 (abandon automatique), 7.1 (délai de grâce) livrées.** Branche `increment/7.2-suivi-sous-charge`, issue de `increment/7.1-scan-tardif`. Source : `docs/roadmap.md` (7.2) et `docs/audits/audit-2026-10-21-pre-release.md` (PER-04 : remarque A_CORRIGER et suggestions « planificateur » et « N+1 »).

Numérotation : RG et CA repartent à 1.

## État actuel constaté

- `GET /api/courses/{id}/suivi` relit à chaque appel la Course, l'empreinte du logo, les Inscriptions, les pseudos et tous les Passages ; aucun cache, aucune limite de débit (`SuiviPublicController`, `ConsulterSuivi`). Tous les écrans de suivi relancent leur requête au même instant au zéro du compte à rebours et toutes les 3 s (`RafraichissementChronometrage`), et retentent toutes les 3 s sur erreur.
- `PlanificateurAbandonAutomatique` appelle chaque seconde `coursesAControler()` (`DepotCourses.idsEnCours`, qui lit **toutes** les Courses) puis `AbandonnerCoureursHorsDelai.executer` pour chaque Course en cours : verrou pessimiste sur la Course, relecture des Inscriptions et de **tous** les Passages, chaque seconde, même quand aucune Boucle n'est à décider.
- N+1 : `ListerCoursesOuvertes` compte les Inscrits une requête par Course ; `ListerMesInscriptions` lit la Course une requête par Inscription ; `InscriptionsCoureurController` et `MesInscriptionsController` lisent l'empreinte du logo une requête par Course.
- L'image Caddy est l'image officielle (`caddy:2.11.7-alpine`) : elle ne contient pas le module `caddy-ratelimit`.

## 1. Périmètre

**Inclus**
- Micro-cache de 1 s des **lectures** du suivi par Course, côté application ; tout ce qui dépend de l'heure est recalculé à chaque requête (RG1 à RG3).
- Limite de débit par IP, **applicative**, sur les routes publiques `GET /api/courses` et `GET /api/courses/**` (RG4, RG5).
- Front du suivi : décalage aléatoire de la relance au zéro du compte à rebours et des relances périodiques, espacement progressif sur erreur 5xx, 429 et erreur réseau (RG6, RG7).
- Contrôle d'échéance allégé, porté par un nouveau cas d'usage `ControlerAbandonsAEcheance` : le verrou et les relectures n'ont lieu qu'à l'échéance d'une Boucle (RG8, RG9).
- Fin des N+1 des listes de Courses du coureur (RG10).
- Script de charge `scripts/charge-suivi.sh` et section de `docs/deploiement.md` (RG11).

**Exclu** (non retenu pour garder l'incrément petit, ou inutile à un test grandeur nature)
- Limite de débit dans Caddy : exige une image Caddy construite avec un module (`caddy-ratelimit`), exclue par choix (image officielle, versions fixées). Notée dans `docs/deploiement.md` comme évolution possible. Pas d'incrément prévu.
- Cache HTTP partagé côté Caddy ou CDN du suivi : le suivi reste `Cache-Control: no-store`.
- Mise en cache de `GET /api/courses` (accueil) et des listes du coureur : seule la fin des N+1 est traitée.
- `lock.timeout` sur le verrou de Course (`CourseJpaAdapter`, `PESSIMISTIC_WRITE`) : il touche aussi le chemin du scan et exige un nouveau mappage d'erreur (503) ; à reprendre si la mesure de RG11 montre une attente de verrou (point ouvert 5).
- Priorité de pool pour les scans, dimensionnement du pool de connexions : mesuré par le script (RG11), non modifié.
- Réponses incrémentales (ETag, 304), WebSocket / SSE : non.
- Limite de débit des routes non publiques (connexion : traitée en 7.3).

**Taille estimée (hors tests) : ~350 lignes** (cache ~70, limite de débit ~70, front ~60, planificateur ~40, N+1 ~60, doc ~20 ; le script de charge, ~80 lignes, n'est pas du code de production). Pas de découpage ; en cas de dépassement constaté, scinder en 7.2a (cache, planificateur, N+1) et 7.2b (limite de débit, front, script).

## 2. Règles de gestion

### Micro-cache du suivi

- **RG1 : lectures en cache, calculs frais.** `ConsulterSuivi` distingue (a) les **lectures** : Course, empreinte du logo, Inscriptions, pseudos, Passages ; (b) les **calculs dérivés** : chronométrage, Boucle courante, Boucles terminées, Boucles tranchées, `boucleEnAttenteDeDecision` (7.1), statistiques (distance, dénivelé, allure, durée), `heureServeur`. Seules les lectures (a) sont mises en cache, par identifiant de Course. Les calculs (b) sont refaits à **chaque** requête avec l'instant `maintenant` de l'horloge (`Clock`, lue une fois). Conséquence : compte à rebours, Boucle courante et `heureServeur` ne sont jamais périmés ; seules la liste des coureurs, les Passages et le statut de la Course peuvent l'être, de moins de 1 s.
- **RG2 : durée de vie et péremption.** Une entrée est chargée à l'instant `chargeeLe` (horloge) et réutilisée tant que `0 <= maintenant − chargeeLe < 1 s` (constante `ConsulterSuivi.DUREE_DU_CACHE`, **1 s**, non configurable). Au-delà, ou si `maintenant < chargeeLe` (horloge pilotable reculée) : relecture. Une donnée servie a donc moins de 1 s plus la durée de sa lecture : jamais un suivi périmé de 2 s. Une erreur de lecture n'est jamais mise en cache et n'est pas masquée par une entrée périmée.
- **RG3 : un chargement à la fois, bornes.** Au plus une lecture simultanée par Course : les requêtes concurrentes sur une entrée expirée attendent le chargement en cours et partagent son résultat. Course inexistante : 404 `COURSE_INTROUVABLE` comme avant, aucune entrée créée pour un identifiant inconnu ou invalide (mémoire bornée par le nombre de Courses). Une Course non exposée au public (`EN_PREPARATION`) ne charge ni Inscriptions ni Passages (inchangé). Le pilotage (`/api/administration/.../pilotage`) et tous les endpoints d'écriture ne passent **jamais** par ce cache.

### Limite de débit

- **RG4 : limite par IP.** Les requêtes `GET` vers `/api/courses` et `/api/courses/**` sont limitées par IP cliente à `L` requêtes par fenêtre fixe d'une minute (alignée sur la minute de l'horloge), `L` = variable d'environnement `BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE`, **défaut 1200**, entier >= 0 ; `0` désactive la limite. Valeur invalide : démarrage en échec avec message explicite. Justification du défaut : un écran de suivi consomme ~20 requêtes/minute ; 1200 admet ~60 écrans derrière une même IP (wifi d'un stade) tout en bloquant une boucle de requêtes. Les autres routes (`/api/sante`, `/api/version`, `/api/coureur/**`, `/api/benevole/**`, `/api/administration/**`, connexion) ne sont **pas** concernées ; les méthodes autres que `GET` sur `/api/courses/**` ne sont pas comptées.
- **RG5 : refus.** La `L+1`-ième requête de la fenêtre est refusée **avant** tout accès base : `429`, en-tête `Retry-After` (secondes jusqu'à la fin de la fenêtre, entier >= 1), corps `ProblemDetail` (`code` `TROP_DE_REQUETES`, propriété `reessayerDansSecondes` égale à `Retry-After`), `Cache-Control: no-store`. Compteurs en mémoire, remis à zéro à chaque fenêtre (mémoire bornée par les IP d'une minute). L'IP est `request.getRemoteAddr()` après résolution des en-têtes `X-Forwarded-*` posés par Caddy (déjà configuré : `forward-headers-strategy: native`, inchangé) ; Caddy remplace l'en-tête reçu du client, il n'est pas falsifiable depuis l'extérieur. Au plus une ligne WARN (« Débit public dépassé ») par IP et par fenêtre, sans cookie ni donnée de compte. Limite appliquée par un filtre de l'infrastructure, avant Spring Security ; aucune logique métier.

### Front

- **RG6 : décalage aléatoire.** (a) Au zéro du compte à rebours, la demande de bascule part après un délai aléatoire uniforme dans `[0 ; 1000 ms]` (au lieu d'immédiatement) : la bascule affichée n'est retardée que de 1 s au plus. (b) Chaque relance périodique est planifiée à `3000 ms + aléa uniforme [0 ; 500 ms]`. Constantes dans `RafraichissementChronometrage` (socle partagé avec le pilotage, qui bénéficie du même décalage). La logique de bascule existante (une seule demande de bascule par Boucle, rattrapage `DELAI_NOUVELLE_DEMANDE_MS`) est inchangée.
- **RG7 : espacement progressif sur erreur.** Après `k` échecs consécutifs (5xx, 429, erreur réseau), la prochaine tentative part après `min(30 s, 3 s × 2^(k−1))` (3 s, 6 s, 12 s, 24 s, 30 s…) plus un aléa de 0 à 10 %. Sur 429, si `Retry-After` est présent, le délai est le plus grand de ce délai et de `Retry-After`. Un succès remet `k` à 0 et rétablit le rythme normal (RG6b). Les statuts définitifs existants (404 du suivi) arrêtent toujours l'actualisation. Le signal `echec` et ses messages sont inchangés ; l'écran garde le dernier instantané affiché, compte à rebours avancé localement (inchangé).

### Planificateur et contrôle d'échéance

- **RG8 : lecture légère à chaque tick, portée par un cas d'usage.** L'échéance est portée par un **nouveau cas d'usage de la couche application**, `ControlerAbandonsAEcheance`, avec `void executer()` (dépendances : `DepotCourses`, `Clock`, `AbandonnerCoureursHorsDelai`). Le `PlanificateurAbandonAutomatique` (infrastructure) ne fait **plus que l'appeler chaque seconde**, sans dépendance vers le domaine (règle ArchUnit `AbandonAutomatiqueArchitectureTest.ca11` respectée). `executer()` lit **sans verrou** les Courses `EN_COURS` (une requête filtrée par statut, `DepotCourses.enCours()` : il ne lit plus `toutes()`) et calcule pour chacune `Course.bouclesTranchees(maintenant)` (7.1, domaine). Il garde en mémoire (état du cas d'usage, singleton), par Course, la dernière valeur **décidée avec succès** `dernierePointee` (absente au départ, y compris après redémarrage de `api`).
- **RG9 : verrou et relectures à l'échéance seulement.** `AbandonnerCoureursHorsDelai.executer(UUID courseId)` **renvoie un `Resultat`** (et non `void`). `ControlerAbandonsAEcheance.executer()` ne l'appelle que si `bouclesTranchees(maintenant) > dernierePointee` (`dernierePointee` absente = 0). `AbandonnerCoureursHorsDelai` garde son comportement actuel (verrou, relecture des Inscriptions et des Passages, idempotent, décision du domaine inchangée, délai de grâce de 7.1 compris) ; après succès, `dernierePointee` prend la valeur de `bouclesTranchees` lue avant l'appel. Un échec n'avance pas `dernierePointee` (nouvel essai à l'appel suivant, y compris la reprise après échec ; journal borné DEP-05 inchangé) et n'empêche pas le traitement des autres Courses. Une Course qui n'est plus `EN_COURS` est oubliée (état purgé). Conséquences : aucun verrou ni relecture de Passages entre deux échéances ; après un redémarrage de `api`, les Boucles dont la grâce s'est écoulée sont décidées au premier appel. La règle de décision reste **uniquement** dans le domaine ; `ControlerAbandonsAEcheance` décide seulement *quand* appeler, le planificateur ne fait que déclencher le cas d'usage.

### N+1

- **RG10 : listes en un nombre constant de requêtes.** Pour `GET /api/coureur/courses` et `GET /api/coureur/inscriptions`, le nombre de requêtes SQL ne dépend plus du nombre de Courses ou d'Inscriptions : (a) `DepotInscriptions.nombresInscritsParCourse(Collection<UUID>)` renvoie une `Map` (0 pour une Course sans Inscrit) en une requête groupée, utilisée par `ListerCoursesOuvertes` ; (b) `DepotCourses.parIds(Collection<UUID>)` lit les Courses des Inscriptions en une requête, utilisée par `ListerMesInscriptions` (Inscription dont la Course a disparu : ignorée comme aujourd'hui) ; (c) les empreintes de logo sont lues en une fois pour la liste (`DepotLogos.empreintesParIdCourse`, existant, via une nouvelle méthode de `ListerCourses` qui complète une liste de Courses) au lieu de `avecEmpreinteLogo` par Course. Résultat (ordre, contenu, codes) identique. Détail d'une Course et écritures inchangés.

### Mesure

- **RG11 : script de charge et procédure.** `scripts/charge-suivi.sh` (bash, curl ; local uniquement comme `donnees-demo.sh` : hôtes `localhost`/`127.0.0.1` sauf `DEMO_FORCER=oui`, sinon code 2) simule `ECRANS` (défaut 50) écrans de suivi sur une Course pendant `DUREE` secondes (défaut 60), chacun à 3 s d'intervalle avec décalage de départ aléatoire, et affiche : requêtes envoyées, nombre de `200`, de `429`, de `5xx`/erreurs réseau, latence médiane et p95 (ms) ; code de sortie 1 si p95 > 500 ms ou au moins un 5xx. `COURSE` (identifiant) est requis, sauf avec `PEUPLER=30` : le script crée alors par l'API (session admin master, comme `donnees-demo.sh`) la Course « Backyard de charge » (400 m, 1 min, 5 m, 50 participants, 5 Boucles max, date du jour) si absente et y inscrit `charge01`…`charge30` (comptes coureurs, mot de passe `un-mot-de-passe-12`), idempotent, puis affiche son identifiant. Sans `COURSE` ni `PEUPLER` : code 1. `docs/deploiement.md` gagne une section « Suivi public sous charge » : variable de limite, défaut et raison, usage du script, limite (IP partagée), évolution possible (module Caddy).

## 3. Cas limites

- **Instants de bascule** : une requête à `départ(N+1)` voit la Boucle N+1 courante même si les lectures datent de 900 ms (RG1) ; à `départ(N+1) + G` (7.1), `bouclesTranchees` et `boucleEnAttenteDeDecision` sont recalculés à cet instant, mais les statuts des coureurs reflètent la décision du planificateur avec un retard cumulé **inférieur à 2 s** (au plus 1 s de tick + 1 s de cache). Le message « Contrôle de la boucle N en cours » peut donc disparaître jusqu'à ~2 s avant que le dernier abandon ou le vainqueur n'apparaisse : accepté (point ouvert 4).
- **Dernière Boucle autorisée, course complète** : inchangés ; le planificateur déclenche `ControlerAbandonsAEcheance`, qui appelle `AbandonnerCoureursHorsDelai.executer` à `demarreeLe + N × durée + G` (RG9), la Course passe `TERMINEE`, puis disparaît de la lecture légère au tick suivant.
- **Aucun passage sur une Boucle (vainqueurs partagés)** : décidé à l'échéance par `AbandonnerCoureursHorsDelai.executer` ; `dernierePointee` évite de relire chaque seconde.
- **Coureur réintégré** : la réintégration (admin, verrou propre) crée des Passages `CORRECTION` ; le suivi les voit en moins de 1 s ; le contrôle d'échéance n'a rien à refaire pour une Boucle déjà pointée.
- **Passage `CORRECTION` vs `SCAN`** : inchangés (le cache stocke les Passages bruts ; l'allure ne retient que les `SCAN`, calculée à chaque requête).
- **Plusieurs Courses en parallèle** : une entrée de cache et un `dernierePointee` par Course ; une Course en échec n'empêche pas les autres. Compteur de limite de débit par IP, pas par Course.
- **Course non démarrée, `TERMINEE`, inscription sans aucun passage** : `EN_PREPARATION` ne charge pas d'Inscriptions ; `TERMINEE` : valeurs finales, l'actualisation s'arrête (inchangé) ; Inscription sans Passage : aucun Passage lu pour elle.
- **Démarrage de la Course par l'admin** : le suivi peut encore montrer « non démarrée » jusqu'à 1 s après le démarrage (statut en cache) ; jamais une Boucle ou un compte à rebours faux. Accepté.
- **Horloge pilotable (profil `e2e`)** : avancer l'horloge de >= 1 s invalide l'entrée (RG2) ; la reculer aussi. Un saut de moins d'une seconde peut resservir des lectures antérieures : sans effet sur les calculs (RG1).
- **Rafale d'un client** : au-delà de `L` par minute, 429 pour cette IP jusqu'à la minute suivante (pas de blocage durable). Les écrans sous la limite ne sont pas affectés.
- **429 côté front** : RG7, sans arrêt de l'actualisation. **Bénévole non affecté, rôle insuffisant** : inchangés (hors de cette limite).
- **Redémarrage de `api`** : caches et compteurs vides, `dernierePointee` vide : une Boucle déjà décidée est ré-appelée une fois (idempotent, sans effet).

## 4. Contrat d'API

Aucun nouvel endpoint. Aucun champ de requête ou de réponse ajouté ou modifié. Seul changement observable : un nouveau refus possible sur les routes publiques.

### `GET /api/courses` (public) et `GET /api/courses/**` dont `GET /api/courses/{id}/suivi` (public)

Réponses 200 / 404 : **inchangées** (`SuiviCourseReponse`, liste des Courses publiques, logo). Nouveau :

- **429** `ProblemDetail` quand l'IP dépasse la limite (RG4, RG5) :
  - en-têtes `Retry-After: <secondes>` et `Cache-Control: no-store` ;
  - `status` 429, `title` « Trop de requêtes », `detail` « Trop de requêtes. Réessayez dans quelques secondes. », `code` `TROP_DE_REQUETES` (string), `reessayerDansSecondes` (entier >= 1) ;
  - aucune donnée de Course dans le corps.

### `GET /api/coureur/courses` et `GET /api/coureur/inscriptions` (`COUREUR`)

Réponses, codes (200/401/403) et DTO **inchangés** ; seul le nombre de requêtes SQL change (RG10).

### Signatures internes (couche application)

- `ControlerAbandonsAEcheance` (nouveau) : `void executer()`.
- `AbandonnerCoureursHorsDelai` : `Resultat executer(UUID courseId)` (renvoie un `Resultat`, pas `void`).
- `DepotCourses.enCours()` : liste des Courses `EN_COURS`, sans verrou.

### Configuration

Nouvelle variable d'environnement `BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE` (entier >= 0, défaut `1200`, `0` = pas de limite), ajoutée à `.env.example` (commentée avec la raison du défaut), au service `api` de `docker-compose.yml` (`${BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE:-1200}`) et à `docs/deploiement.md`. `docker-compose.e2e.yml` la fixe à `6000` pour que les tests E2E parallèles ne s'auto-limitent pas. Aucune migration Liquibase.

## 5. Écrans

- **Suivi public** (`/courses/:id/suivi`) : aucun nouvel élément. Rafraîchissement avec décalage (RG6). En cas d'erreur (503, 429, réseau), l'affichage existant : `suivi-avertissement-actualisation` quand des données sont déjà affichées (elles restent), `suivi-erreur` (message « Service indisponible… » inchangé) avant la première réponse ; les tentatives s'espacent (RG7) ; le message disparaît au premier succès. Pas de message spécifique « limite de débit » : un 429 est traité comme une indisponibilité temporaire.
- **Pilotage** (`/administration/courses/:id/pilotage`) : aucun changement visible ; bénéficie du décalage de relance et de l'espacement sur erreur (socle partagé). Ne passe pas par le micro-cache.
- **Accueil public, Courses ouvertes, Mes inscriptions** : aucun changement visible (mêmes données, moins de requêtes).

## 6. Critères d'acceptation

Sauf mention, horloge fixe, cache de 1 s (RG2), limite par défaut.

- **CA1** (RG1, RG2, unitaire) : étant donné `ConsulterSuivi` avec des ports qui comptent leurs lectures et une Course `EN_COURS` de 5 coureurs, quand 100 consultations ont lieu à `maintenant` puis à `maintenant + 999 ms`, alors 1 seul chargement (une lecture de Course, d'Inscriptions, de Passages) ; à `maintenant + 1000 ms`, un second chargement.
- **CA2** (RG1, unitaire) : étant donné un chargement à 10:00:00.000 d'une Course de Boucle 60 min démarrée à 10:00:00 (`Clock` avancé ensuite), quand on consulte à 10:00:00.900, alors `heureServeur` = 10:00:00.900, `tempsAvantFinBoucleMillisecondes` = 3 599 100 et durée écoulée = 900 ms (calculés à l'instant courant, sans rechargement) ; à 10:59:59.999 puis 11:00:00.000, Boucle courante 1 puis 2 ; `boucleEnAttenteDeDecision` = 1 à 11:00:00.500 et `null` à 11:03:00 (G = 3 min).
- **CA3** (RG2, unitaire) : étant donné une entrée chargée à 10:00:05, quand l'horloge recule à 10:00:04, alors elle est relue ; une erreur de lecture au rechargement est propagée (pas de donnée périmée servie) et la consultation suivante réessaie.
- **CA4** (RG3, unitaire) : étant donné un identifiant de Course inconnu consulté 3 fois, alors 3 fois `CourseIntrouvableException`, aucune entrée créée ; une Course `EN_PREPARATION` : aucune lecture d'Inscriptions ni de Passages.
- **CA5** (RG3, intégration) : étant donné 20 requêtes `GET /suivi` concurrentes sur une entrée expirée, alors 20 réponses 200 et un seul chargement (compteur `Statistics` de Hibernate ou port espion).
- **CA6** (RG1, RG2, intégration) : étant donné une Course démarrée, 3 coureurs, quand `GET /suivi` (anonyme) est appelé, puis un coureur scanné, puis `GET /suivi` à nouveau dans la même seconde, alors la seconde réponse peut contenir l'ancien état des coureurs mais un `heureServeur` strictement postérieur ; après avancement de l'horloge de 1 s, la réponse contient le Passage (distance cumulée mise à jour). Le pilotage, appelé juste après le scan, le contient immédiatement.
- **CA7** (RG1, 7.1, intégration) : étant donné la Course du CA12 de 7.1 (A scanné sur 1, B non, Boucle 60 min, départ 10:00), quand l'horloge est à 11:00:30, alors `GET /suivi` donne `boucleEnAttenteDeDecision` = 1 et la Boucle courante 2, même si une réponse a été servie à 10:59:59.7 ; à 11:03:01 puis 1 s de plus (planificateur et cache), B est en abandon `HORS_DELAI` boucle 1, A vainqueur, Course `TERMINEE`.
- **CA8** (RG4, unitaire) : étant donné `L` = 3, quand une IP envoie 4 requêtes dans la même minute, alors les 3 premières passent, la 4e est refusée avec `Retry-After` = secondes restantes dans la minute (>= 1) ; à la minute suivante le compteur repart de zéro ; une autre IP n'est pas affectée ; `L` = 0 : jamais de refus.
- **CA9** (RG4, unitaire) : étant donné `L` = 3, quand les requêtes sont `GET /api/sante`, `GET /api/version`, `POST /api/courses/x`, `GET /api/coureur/courses`, `GET /api/benevole/...`, alors aucune n'est comptée ; `GET /api/courses`, `GET /api/courses/{id}/suivi` et `GET /api/courses/{id}/logo` le sont, avec le même compteur.
- **CA10** (RG4, intégration) : étant donné `BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE` = 5, quand 6 `GET /api/courses/{id}/suivi` sont envoyés dans la même minute (même IP ; un `X-Forwarded-For` falsifié hors proxy de confiance est ignoré), alors les 5 premiers donnent 200 et le 6e 429 ; à la minute suivante (horloge pilotée), 200. Valeur `abc` ou `-1` : le contexte ne démarre pas, message explicite.
- **CA11** (RG5, intégration) : étant donné le 429 de CA10, alors `Retry-After` entier >= 1, corps `ProblemDetail` avec `status` 429, `code` `TROP_DE_REQUETES`, `reessayerDansSecondes` égal à `Retry-After`, `Cache-Control: no-store`, aucune donnée de Course ; pendant ce 429, `GET /api/sante` et la connexion répondent normalement ; aucune requête SQL n'est émise pour la requête refusée.
- **CA12** (RG6, RG7, E2E) : étant donné un suivi ouvert où `GET /api/courses/{id}/suivi` est intercepté pour répondre 503 aux 3 premiers appels puis normalement, quand la page est ouverte, alors les 2e et 3e appels partent respectivement >= 2,9 s et >= 5,9 s après l'appel précédent (3 s puis 6 s, tolérance 0,1 s), `suivi-erreur` est affiché pendant l'échec (aucune donnée encore reçue) ; après le 4e appel (200), il disparaît et l'intervalle revient entre 2,9 s et 3,7 s. Même scénario sur un suivi déjà affiché (premier succès avant les 503) : `suivi-avertissement-actualisation` apparaît, les données restent.
- **CA13** (RG7, E2E) : étant donné une réponse interceptée 429 avec `Retry-After: 8`, quand le suivi la reçoit, alors la tentative suivante part >= 7,9 s plus tard (et non 3 s), puis le rythme normal revient après un 200.
- **CA14** (RG6, E2E) : étant donné la Course de démo « Backyard du jour » (Boucle 1 min) démarrée et un suivi ouvert, quand l'horloge pilotable atteint le zéro du compte à rebours, alors la requête de bascule est émise dans les 1,5 s qui suivent et le suivi affiche « Boucle 2 en cours » ; le caractère aléatoire (`[0 ; 1000 ms]`, `[0 ; 500 ms]`) est vérifié par revue de code, non par test.
- **CA15** (RG8, unitaire) : étant donné un `ControlerAbandonsAEcheance` (`DepotCourses` en mémoire, `Clock` pilotable, `AbandonnerCoureursHorsDelai` espion) et une Course `EN_COURS` de Boucle 60 min démarrée à 10:00, quand `executer()` est appelé à 10:30:00, 10:59:59 et 11:02:59, alors `AbandonnerCoureursHorsDelai.executer` n'est jamais appelé ; à 11:03:00 (7.1), il est appelé une fois ; aux appels suivants (11:03:01, 11:30:00), jamais plus. Le `PlanificateurAbandonAutomatique` est testé comme simple délégation : à chaque tick, il appelle une fois `ControlerAbandonsAEcheance.executer()` (cas d'usage espion), sans autre dépendance.
- **CA16** (RG9, unitaire) : étant donné `AbandonnerCoureursHorsDelai.executer` qui échoue lors de l'appel de `ControlerAbandonsAEcheance.executer()` de 11:03:00, quand l'appel de 11:03:01 a lieu, alors il est rappelé (reprise après échec) ; après succès, plus aucun appel pour la Boucle 1 ; à 12:03:00, un appel pour la Boucle 2. Redémarrage simulé (nouvelle instance de `ControlerAbandonsAEcheance`) à 11:30:00 sur la même Course : un appel (Boucle 1 jamais pointée en mémoire). Une Course qui n'est plus `EN_COURS` est oubliée (son état est purgé) ; l'échec sur une Course n'empêche pas le traitement d'une autre Course `EN_COURS` au même appel.
- **CA17** (RG8, intégration) : étant donné 3 Courses `EN_PREPARATION`, 2 `TERMINEE` et 2 `EN_COURS`, quand `ControlerAbandonsAEcheance.executer()` lit les Courses à contrôler, alors seules les 2 `EN_COURS` sont renvoyées par `DepotCourses.enCours()` (requête filtrée sur le statut, vérifiée sur les requêtes émises) ; une Course passée `TERMINEE` n'est plus lue au tick suivant.
- **CA18** (RG9, intégration) : étant donné une Course démarrée avec 30 Inscriptions, quand l'horloge reste dans la Boucle 1 pendant 10 ticks du planificateur (planificateur activé), alors aucune requête de lecture des Passages ni de verrou (`FOR UPDATE`) n'est émise ; à l'échéance de 7.1 (scénario du CA12 de 7.1), le résultat est identique à celui de 7.1 (A vainqueur, B `ABANDON` `HORS_DELAI` boucle 1, Course `TERMINEE`).
- **CA19** (RG10, intégration) : étant donné un coureur avec 1 puis 10 Courses ouvertes dont 5 avec Inscrits et logo, quand `GET /api/coureur/courses` est appelé, alors le nombre de requêtes SQL (`Statistics.getPrepareStatementCount`) est identique dans les deux cas (<= 4) et le corps est identique à celui d'avant (ordre, `complete`, inscription du coureur).
- **CA20** (RG10, intégration) : étant donné un coureur avec 1 puis 8 Inscriptions, quand `GET /api/coureur/inscriptions` est appelé, alors le nombre de requêtes SQL est identique (<= 4) ; une Inscription dont la Course n'existe plus est ignorée comme avant ; ordre, dossards et jetons QR inchangés.
- **CA21** (RG10, unitaire) : `nombresInscritsParCourse` renvoie 0 pour une Course sans Inscrit et le compte exact pour les autres ; `ListerCoursesOuvertes` calcule `complete` pour une Course de 10 places avec 10 Inscrits (true), 9 (false), 0 (false).
- **CA22** (RG1, RG4, RG10, E2E, tous écrans) : étant donné la stack de démo, quand on parcourt l'accueil public, le suivi public, « Courses ouvertes » et « Mes inscriptions » en coureur, et le pilotage en admin, alors chaque écran affiche les mêmes informations qu'avant l'incrément (parcours nominaux existants inchangés et verts) ; aucun 429 (limite E2E à 6000).
- **CA23** (RG11, revue manuelle) : `scripts/charge-suivi.sh` sans `COURSE` ni `PEUPLER` sort en code 1 ; cible non locale sans `DEMO_FORCER=oui` : code 2 ; avec `PEUPLER=30`, lancé deux fois, 30 coureurs `charge01`…`charge30` inscrits sans doublon. Vérifié aux étapes 2 à 5 de « Tester à la main ».
- **CA24** (RG4, revue manuelle) : `.env.example`, `docker-compose.yml` et `docs/deploiement.md` contiennent `BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE` (étape 8).

Couverture des RG : RG1 CA1, 2, 6, 7, 22 ; RG2 CA1-3, 6 ; RG3 CA4-5 ; RG4 CA8-10, 22, 24 ; RG5 CA11 ; RG6 CA12, 14 ; RG7 CA12-13 ; RG8 CA15, 17 ; RG9 CA15-16, 18 ; RG10 CA19-22 ; RG11 CA23. Écrans : suivi CA12-14 et 22 ; pilotage CA22 ; accueil, courses ouvertes, mes inscriptions CA22.

Niveaux : unitaire 9 (CA1-4, 8, 9, 15, 16, 21), intégration 9 (CA5-7, 10, 11, 17-20), E2E 4 (CA12-14, 22), revue manuelle 2 (CA23, CA24). Total 24.

## 7. Tester à la main

Prérequis : `docker compose up -d --build` (profil de démo à horloge pilotable comme en 7.1), http://localhost, `./scripts/donnees-demo.sh`. Comptes : admin master (`ADMIN_MASTER_PSEUDO`), Nadia (admin), Léo (bénévole), Karim/Sophie/Tom.

1. **Cohérence du suivi** : ouvrir le suivi de « Backyard du jour » et, dans un autre onglet, son pilotage (admin). Démarrer la Course. Attendu : compte à rebours identique dans les deux onglets à ~1 s près, aucune régression visible.
2. **Peupler** : `PEUPLER=30 ./scripts/charge-suivi.sh` crée « Backyard de charge » et 30 coureurs, affiche l'identifiant de la Course ; l'admin démarre cette Course dans l'interface. Attendu : 30 coureurs en course au suivi.
3. **Charge** : `COURSE=<id> ECRANS=50 DUREE=60 ./scripts/charge-suivi.sh`. Attendu : 0 réponse 5xx, 0 réponse 429 (50 écrans × 20/min = 1000 < 1200), p95 < 500 ms, code de sortie 0. Pendant ce temps, le suivi ouvert à l'étape 1 reste fluide.
4. **Départ de Boucle sous charge** : relancer le script (`DUREE=180`) et, côté bénévole (Léo, affecté à la Course), scanner des coureurs autour d'un départ de Boucle. Attendu : scans acceptés en moins de 1 s, aucun refus anormal ; au départ, les écrans basculent en moins de ~2 s.
5. **Limite de débit** : relancer l'`api` avec `BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE=30` (`.env`, puis `docker compose up -d api`), puis `ECRANS=10 DUREE=60 ./scripts/charge-suivi.sh`. Attendu : des 429 comptés par le script ; en même temps, la connexion admin et `curl http://localhost/api/sante` répondent normalement ; `curl -i http://localhost/api/courses/<id>/suivi` donne 429 avec `Retry-After` et `code` `TROP_DE_REQUETES` ; un suivi ouvert dans le navigateur affiche l'avertissement d'actualisation, puis se rétablit à la minute suivante. Remettre ensuite la valeur par défaut.
6. **Espacement sur erreur** : arrêter `api` (`docker compose stop api`) avec un suivi ouvert. Attendu : avertissement d'actualisation, données conservées ; les requêtes visibles dans les outils du navigateur s'espacent (3, 6, 12, 24, 30 s) ; après `docker compose start api`, l'avertissement disparaît au prochain essai.
7. **Planificateur** : avec la Course de l'étape 4 en cours, `docker compose logs api` ne montre aucune ligne par seconde ; la Boucle terminée est décidée à `départ + délai de grâce` (7.1) : un coureur sans Passage passe en abandon, après le message « Contrôle de la boucle N en cours ».
8. **Revue** : `.env.example`, `docker-compose.yml` et `docs/deploiement.md` (section « Suivi public sous charge ») mentionnent la variable.

## 8. Points ouverts (tranchés par l'analyste, utilisateur indisponible)

1. **Cache applicatif plutôt que Caddy** : la réponse n'est pas partagée par Caddy car le compte à rebours et `heureServeur` doivent rester frais (RG1) et l'image officielle n'a pas de module de cache. Retenu : cache des lectures, calculs frais.
2. **Limite applicative plutôt que Caddy** : pas d'image Caddy personnalisée. Compromis : elle s'applique après Caddy et Tomcat et protège la base (refus avant tout accès SQL), pas la bande passante. Évolution possible documentée.
3. **Valeurs** : cache 1 s ; limite 1200 requêtes/minute/IP (~60 écrans par IP) ; décalage 0-1 s au zéro, 0-0,5 s par relance ; espacement 3 s ×2 jusqu'à 30 s. À revoir après l'essai de charge ; la limite se règle sans reconstruire. Risque : plus de 60 spectateurs derrière une même IP (NAT d'opérateur) seraient limités ; remonter la variable.
4. **Retard de statut accepté** : jusqu'à 1 s de cache + 1 s de tick du planificateur (< 2 s) entre une décision et son affichage, sans fausser la Boucle ni le compte à rebours ; le message de 7.1 peut disparaître un peu avant le dernier abandon.
5. **Pas de `lock.timeout` ni de priorité de pool** : si l'étape 4 montre des scans ralentis, créer un incrément 7.2b plutôt qu'élargir celui-ci.
6. **Pilotage non mis en cache** : l'admin voit l'état à la seconde ; son trafic est faible.
7. **Fenêtre fixe** (pas de seau à jetons) : une rafale jusqu'à 2L à la jonction de deux minutes est acceptée.
8. **Décalage de bascule jusqu'à 1 s** sur l'affichage du suivi projeté : accepté en contrepartie de l'étalement des requêtes.
