# Audit 2026-10-16 : backend (SEC, MET, ARC, PER) et déploiement associé

- Commit audité : `e411ccd`
- Périmètre : axes SEC, MET, ARC, PER sur `backend/`, plus `docker-compose*.yml`, `backend/Dockerfile`, `.env.example` (et `frontend/Caddyfile`, lu car il porte le HTTPS et les en-têtes). Le front, les tests E2E et les axes TST/FRT ne sont pas audités.
- Incréments livrés pris en compte : jusqu'à 4.12 (la branche `increment/5.1-file-scan-hors-ligne` n'a aucun commit propre, 5.1 non livré, jalon 6 non livré)
- Lecture de PER : le référentiel désigne par PER la persistance (PER-01 à PER-05). La demande ajoute la performance (N+1, polling, transactions, pool) ; ces remarques sont rattachées à PER-04 ou à des propositions d'ajout.

## Synthèse

| Sévérité | Nombre |
|---|---|
| BLOQUANT | 0 |
| A_CORRIGER | 1 |
| SUGGESTION | 8 |

Le backend est en bon état : contrôle des rôles côté serveur sur chaque route, Argon2id, CSRF, session, limitation de connexion, jeton QR `SecureRandom`, verrou pessimiste sur la course pour sérialiser scan, inscription et abandon, contraintes d'unicité en base, changesets jamais modifiés, aucun secret commité. Aucun blocage de mise en production n'a été trouvé.

Point à traiter avant l'ouverture publique : les endpoints publics `POST /api/connexion` et `POST /api/comptes` déclenchent un Argon2id (19 Mio) sans limite par IP, sans plafond de concurrence et sans limite mémoire sur le conteneur `api` (SEC-06). Un flot de requêtes peut saturer le pool de connexions ou tuer la JVM pendant une course. Les autres remarques sont des durcissements (HSTS, journaux Docker) et de l'optimisation (contrôle d'abandon chaque seconde, N+1 sur deux listes).

## Couverture

| ID | Statut | Périmètre |
|---|---|---|
| SEC-01 | OK | complet |
| SEC-02 | OK | complet |
| SEC-03 | OK | complet |
| SEC-04 | REMARQUE | complet |
| SEC-05 | OK | complet |
| SEC-06 | REMARQUE | complet |
| SEC-07 | OK | complet |
| SEC-08 | OK | complet |
| SEC-09 | OK | complet |
| SEC-10 | OK | complet |
| SEC-11 | OK | complet |
| SEC-12 | OK | complet |
| ARC-01 | OK | complet |
| ARC-02 | OK | complet |
| ARC-03 | REMARQUE | complet |
| ARC-04 | OK | complet |
| ARC-05 | OK | complet |
| ARC-06 | OK | complet |
| ARC-07 | REMARQUE | complet |
| ARC-08 | OK | complet |
| ARC-09 | OK | complet |
| MET-01 | OK | complet |
| MET-02 | OK | complet |
| MET-03 | OK | complet |
| MET-04 | NON_APPLICABLE (5.1 non livré) | complet |
| MET-05 | OK | complet |
| MET-06 | OK | complet |
| MET-07 | OK | complet |
| MET-08 | OK | complet |
| MET-09 | OK | complet |
| MET-10 | OK | complet |
| MET-11 | OK | complet |
| PER-01 | OK | complet |
| PER-02 | OK | complet |
| PER-03 | OK | complet |
| PER-04 | REMARQUE | complet |
| PER-05 | OK | complet |
| DEP-01 | OK | chemins |
| DEP-02 | OK | chemins |
| DEP-03 | OK | chemins |
| DEP-04 | OK | chemins |
| DEP-05 | OK | chemins |
| DEP-06 | OK | chemins |
| DEP-07 | NON_VERIFIE (docs/deploiement.md lu seulement par extraits, aucune restauration rejouée) | chemins |
| MET-04 (information) | voir remarque SUGGESTION « idempotence » ci-dessous | complet |

Notes sur les `OK` (preuves lues) :
- SEC-01 : `SecuriteConfiguration.java:44-70` (règles par préfixe, `anyRequest().permitAll()` ne concerne que les chemins hors `/api/**`, déjà couverts par `/api/**` authenticated) ; tous les contrôleurs ont été listés par `Mapping(` et tombent sous une règle. Les suppressions de course sont réservées à l'admin master (`:51-53`).
- SEC-02 : `SecuriteConfiguration.java:33-38,95-98` Argon2id 19 456 Kio, 2 itérations, parallélisme 1, paramètres documentés en Javadoc.
- SEC-03 : `MotDePasse.java:90-92`, `ConnexionRequete`, `CreerCompteRequete`, `ChangerMotDePasse.Commande`, `SupprimerCompteCoureur.Commande`, `AdminMasterProprietes` masquent la valeur ; `GestionnaireErreursInattendues.java:44-50` ne journalise aucun message d'exception ; aucun journal ne reçoit un mot de passe ou un jeton (grep).
- SEC-05 : CSRF par cookie actif (`SecuriteConfiguration.java:72-76`), jamais désactivé.
- SEC-07 : `JetonQr.java` 32 octets `SecureRandom`, contrainte `uq_inscription_jeton_qr`, jeton absent de tous les `toString()` lus.
- SEC-08 : index unique partiel `0003-admin-master-unique.yaml`, `Compte.anonymiser()` refuse hors COUREUR, `creerAdminMaster` appelé uniquement par `InitialiserAdminMaster`.
- SEC-09 : `.env` ignoré et jamais présent dans l'historique (`git log --all -- .env` vide) ; aucune valeur secrète dans les fichiers suivis (grep `git grep`) ; `.env.example` vide pour les secrets.
- SEC-10 : `Logo.java` (taille 2 Mo, format par signature d'octets), table `logo_course` avec `ck_logo_course_type_mime`, stockage `bytea` sans chemin de fichier, service avec `nosniff` (`LogoPublicController.java:50-56`), SVG exclu.
- SEC-11 : `HorlogeController` et `HorlogePilotableConfiguration` en `@Profile("e2e")`, `HorlogeConfiguration` bascule sur `Clock.systemUTC()` hors e2e, garde-fou de démarrage, `/api/test/**` réservé à l'admin master quel que soit le profil, `BACKYARD_ENVIRONNEMENT=production` fixé par `docker-compose.prod.yml:18`.
- SEC-12 : inscriptions du coureur filtrées par `compteId` de session (`InscriptionJpaAdapter.parIdEtCompte`), routes admin filtrées par couple (course, inscription) (`parIdEtCourse`), scan conditionné à l'affectation relue à chaque scan (`Course.autoriserScanPar`).
- ARC-01/02 : aucun import Spring, JPA, Jackson ou Hibernate dans les packages `domaine` (grep vide) ; aucun import de `infrastructure` depuis `exposition` (grep vide) ; ArchUnit complémentaire non rejoué (hors périmètre).
- ARC-08 : seul `Instant.now(horloge)` / `LocalDate.now(horloge...)` avec `Clock` injectée ; aucun `Instant.now()` nu (grep).
- MET-01/02 : `AbandonAutomatique.decider` (fonction pure, une seule définition), `AbandonnerCoureursHorsDelai` sous verrou pessimiste, planificateur par course dans sa transaction, rattrapage des boucles manquées en séquence.
- MET-03 : refus présents (doublon, non EN_COURSE, course non démarrée/terminée, bénévole non affecté, jeton inconnu) dans `EnregistrerPassage`, `Course`, `Inscription`.
- MET-05 : `ReintegrerCoureur` crée des passages `CORRECTION` (`scanne_le` nul garanti par `ck_passage_scanne_le`).
- MET-07 : verrou de course dans `InscrireCoureur`, plus unicités `uq_inscription_course_dossard` et `uq_inscription_course_compte`.
- PER-01 : chaque fichier de `changesets/` n'a qu'un seul commit (`git log` par fichier).

## Remarques

### [A_CORRIGER] SEC-06 : endpoints publics coûteux (Argon2id) sans limite par IP ni plafond de concurrence
- **Où** : `comptes/infrastructure/SecuriteConfiguration.java:46-47` (`/api/connexion`, `/api/comptes` en `permitAll`), `comptes/application/Connecter.java:60-80`, `comptes/application/CreationCompte.java:42-51`, `docker-compose.yml:22-40`, `backend/Dockerfile:19` (`-XX:MaxRAMPercentage=75`)
- **Règle** : CLAUDE.md « Limitation des tentatives de connexion », disponibilité pendant une course
- **Constat** : la limitation est uniquement par pseudo (`RegistreTentativesConnexionEnMemoire`). Chaque tentative, même sur un pseudo inexistant (empreinte factice voulue), coûte un Argon2id de 19 Mio. Un attaquant qui varie les pseudos n'est jamais bloqué (le registre est borné à 10 000 clés puis évince). La création de compte gratuite appelle aussi Argon2id. Les deux méthodes s'exécutent dans une transaction (`@Transactional`), donc le hachage se fait connexion JDBC prise. Pool Hikari par défaut (10 connexions, attente 5 s), Tomcat à 200 threads, aucun plafond mémoire sur le conteneur. Une rafale concurrente peut (1) épuiser le pool, donc faire échouer les scans des bénévoles, ou (2) consommer plusieurs Gio de tas (200 x 19 Mio) et provoquer un `OutOfMemoryError` puis un redémarrage de l'api en pleine course.
- **Correction suggérée** : limite de débit par IP sur ces deux routes au niveau Caddy (ou module de limitation) et/ou sémaphore de concurrence sur le hachage côté application ; sortir le hachage/vérification de la transaction (ou ne l'ouvrir qu'après) ; fixer `mem_limit` sur `api` et `-Xmx` cohérent dans `docker-compose.prod.yml`.
- **Récurrence** : première fois

### [SUGGESTION] SEC-06 : un tiers peut verrouiller le compte de l'admin master à volonté
- **Où** : `comptes/application/LimitationTentatives.java`, `comptes/infrastructure/RegistreTentativesConnexionEnMemoire.java`, `application.yml:63-64`
- **Règle** : CLAUDE.md « blocage temporaire après échecs répétés » (compromis connu)
- **Constat** : le blocage est par pseudo seul. Les pseudos étant visibles (classement public à venir), n'importe qui peut bloquer 15 min tout pseudo, admin master compris, en 5 requêtes répétables. Le registre est en mémoire (remis à zéro au redémarrage), ce qui est cohérent avec la mono-instance.
- **Correction suggérée** : documenter le compromis dans `docs/deploiement.md` ; option : clé (pseudo, IP) ou délai croissant plutôt que blocage dur ; combiner avec la limite par IP de la remarque précédente.
- **Récurrence** : première fois

### [SUGGESTION] SEC-04 : durcissement HTTPS (HSTS absent, `Secure` non forcé)
- **Où** : `frontend/Caddyfile:16-24`, `application.yml:44-46`
- **Règle** : CLAUDE.md « HTTPS obligatoire via le reverse proxy », cookie `Secure`
- **Constat** : aucun en-tête `Strict-Transport-Security` n'est émis (Caddy ne l'ajoute pas seul). Le drapeau `Secure` des cookies de session et CSRF dépend uniquement de `X-Forwarded-Proto` relayé par Caddy et de `server.forward-headers-strategy: native` ; ce comportement est couvert par des tests d'intégration (`X-Forwarded-Proto` dans `ConnexionIntegrationTest`), mais rien ne le force si l'en-tête est perdu.
- **Correction suggérée** : ajouter `Strict-Transport-Security "max-age=31536000"` en production seulement (la variante `:80` de dev ne doit pas en émettre), et envisager `server.servlet.session.cookie.secure=true` dans le profil de production.
- **Récurrence** : première fois

### [SUGGESTION] PER-04 : contrôle d'abandon chaque seconde, relecture complète et verrou de course à chaque tick
- **Où** : `courses/infrastructure/PlanificateurAbandonAutomatique.java:34-37`, `courses/application/AbandonnerCoureursHorsDelai.java:75-97`
- **Règle** : performance du scan (verrou partagé avec le scan)
- **Constat** : pour chaque course `EN_COURS`, chaque seconde, une transaction prend `PESSIMISTIC_WRITE` sur la ligne course, relit toutes les inscriptions et tous les passages de la course (`parInscriptions`), puis ne décide rien dans l'immense majorité des ticks. Toute course démarrée a `bouclesTerminees > 0` dès la fin de la boucle 1, donc la relecture a lieu à chaque tick pendant toute la course (n inscrits x k boucles lignes). Le scan d'un bénévole attend ce verrou. À 100-200 coureurs, le coût est faible mais permanent et inutile.
- **Correction suggérée** : ne relire que si une frontière de boucle vient d'être franchie (calcul du prochain instant de décision dans le domaine, `Course`), ou tester d'abord par une requête de comptage s'il existe une inscription `EN_COURSE` sans passage sur la dernière boucle terminée avant de prendre le verrou. Verrou avec délai : ajouter `jakarta.persistence.lock.timeout` pour éviter une attente indéfinie.
- **Récurrence** : première fois

### [SUGGESTION] PER-04 : requêtes N+1 sur deux listes
- **Où** : `courses/application/ListerCoursesOuvertes.java:40-44` (un `count` par course), `courses/application/ListerMesInscriptions.java:36-45` et `courses/exposition/MesInscriptionsController.java:57-59` (un `parId` de course et une requête d'empreinte de logo par inscription), `courses/exposition/InscriptionsCoureurController.java:63-65`
- **Règle** : requêtes N+1 (demande d'audit)
- **Constat** : volumétrie faible (quelques courses, peu d'inscriptions par compte), donc impact minime aujourd'hui. Les listes d'inscrits, de pilotage et de pseudos sont, elles, correctement groupées (`parInscriptions`, `pseudosDe`, `empreintesParIdCourse`).
- **Correction suggérée** : un port de dépôt qui renvoie les comptes par course en une requête (`group by course_id`) et un chargement groupé des courses et des empreintes de logo par identifiants.
- **Récurrence** : première fois

### [SUGGESTION] DEP-05 : journaux Docker non bornés, pas de plafond mémoire en production
- **Où** : `docker-compose.prod.yml` (aucune clé `logging` ni `mem_limit`), `docker-compose.yml`
- **Règle** : démarrage sur VPS, robustesse (hors règle écrite de CLAUDE.md)
- **Constat** : le pilote `json-file` par défaut ne tourne pas : les journaux de `api` (un échec du planificateur toutes les secondes produit une trace complète, `PlanificateurAbandonAutomatique.java:41-44`) peuvent remplir le disque du VPS. Aucun service n'a de limite mémoire ; la JVM prend 75 % de la mémoire de l'hôte (`backend/Dockerfile:19`) et laisse PostgreSQL et Caddy sans marge.
- **Correction suggérée** : `logging: {driver: json-file, options: {max-size, max-file}}` sur les trois services et limites mémoire adaptées au VPS dans `docker-compose.prod.yml`.
- **Récurrence** : première fois

### [SUGGESTION] ARC-03 : borne de 24 h de l'âge d'un scan définie dans l'exposition
- **Où** : `courses/exposition/PassageRequete.java:21,87-98`
- **Règle** : CLAUDE.md « une règle métier n'existe qu'à un seul endroit, dans le domaine »
- **Constat** : `AGE_MAX_MILLISECONDES` (24 h) borne la durée de vie d'un scan hors ligne : c'est une règle du filet réseau, pas un simple format. Elle vit dans le DTO, hors du domaine.
- **Correction suggérée** : porter la durée maximale d'un scan différé dans le domaine (par exemple près de `Course.boucleDePassage`), le DTO ne gardant que le contrôle de type.
- **Récurrence** : première fois

### [SUGGESTION] ARC-07 : gestionnaire d'erreurs des courses lié à une liste de contrôleurs
- **Où** : `courses/exposition/GestionnaireErreursCourses.java:36-40`
- **Règle** : codes HTTP cohérents, exceptions métier typées traduites en `ProblemDetail`
- **Constat** : `@RestControllerAdvice(assignableTypes = {...})` énumère les contrôleurs. Un nouveau contrôleur du contexte `courses` (suivi public, jalon 6) oublié dans la liste verrait ses exceptions métier tomber dans le gestionnaire de dernier recours (`GestionnaireErreursInattendues`) et répondre 500 au lieu de 404/409. Rien ne l'impose par test aujourd'hui.
- **Correction suggérée** : cibler par package (`basePackages`) ou ajouter une règle ArchUnit qui exige l'inscription de tout contrôleur de `courses.exposition`.
- **Récurrence** : première fois

### [SUGGESTION] MET-04 : idempotence portée par l'identifiant de scan, pas par (inscription, boucle)
- **Où** : `courses/application/EnregistrerPassage.java:62-72,108-112`, `courses/exposition/GestionnaireErreursCourses.java` (`PASSAGE_DEJA_ENREGISTRE`)
- **Règle** : CLAUDE.md « L'API est idempotente sur (inscription, boucle) » (5.1 non livré : point MET-04 non applicable, remarque d'anticipation)
- **Constat** : un rejeu avec le même `identifiantScan` renvoie 200, ce qui couvre la perte de réponse. Un envoi avec un autre identifiant pour la même inscription et la même boucle (file hors ligne qui régénère l'identifiant, deux bénévoles qui scannent le même coureur) répond 409 `PASSAGE_DEJA_ENREGISTRE`. De plus, un scan différé qui arrive après l'abandon automatique de sa boucle est refusé en `INSCRIPTION_NON_EN_COURSE`. Ce sont des comportements que la file de l'incrément 5.1 devra traiter (conflit = succès côté file pour le premier cas).
- **Correction suggérée** : à arbitrer dans la spec 5.1 : confirmer que le client réutilise l'identifiant de scan à chaque tentative, et traiter 409 `PASSAGE_DEJA_ENREGISTRE` comme livré ; définir le sort d'un scan reçu après la décision d'abandon.
- **Récurrence** : première fois

## Propositions d'ajout au référentiel

- SEC (P1) : limitation du débit ou de la concurrence sur les endpoints publics coûteux (hachage Argon2id) et plafonds de ressources du conteneur `api`.
- SEC (P2) : en-têtes HTTPS de durcissement en production (HSTS) et drapeau `Secure` effectif des cookies derrière le proxy.
- PER (P2) : coût du contrôle périodique côté serveur (fréquence, verrous, volumétrie lue par tick) et durée de détention des verrous de course.
- PER (P2) : requêtes N+1 sur les listes (une ligne dédiée, distincte de PER-04 qui porte sur les index).
- PER (P3) : paramétrage du pool de connexions et des délais de verrou (`lock.timeout`) ; transactions ne contenant pas de calcul coûteux (hachage).
- DEP (P2) : rotation des journaux Docker et limites mémoire en production.
- ARC (P3) : tout contrôleur d'un contexte est couvert par le gestionnaire d'erreurs de ce contexte (règle ArchUnit).
- PER (P1, à partir de 6.1) : coût du polling du suivi public (requêtes par appel, cache court côté serveur ou en-têtes de cache, nombre de clients simultanés).
