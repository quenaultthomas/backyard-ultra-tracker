# Prompt correctif — pentest 2026-10-26

Prompt à coller dans Claude Code, à la racine du dépôt, pour corriger les points du rapport
`docs/audits/audit-2026-10-26-pentest.md`. Les deux arbitrages ouverts ont été tranchés
(désactivation plutôt que suppression ; rôle de révocation miroir de la création).

---

Tu travailles sur le dépôt backyard-ultra-tracker (commit db53c58, branche main).
Corrige les points du rapport de pentest docs/audits/audit-2026-10-26-pentest.md.
Lis-le d'abord en entier, ainsi que docs/audits/referentiel.md et CLAUDE.md.

Contraintes de projet (non négociables) :
- Respecte l'architecture DDD : domaine Java pur (zéro Spring/JPA/Jackson), cas d'usage dans
  application, contrôleurs sans logique métier, contexte courses ne référençant un compte que
  par identifiant. Chaque règle métier à UN seul endroit (domaine).
- Nommage métier en français, suffixes techniques en anglais.
- Erreurs typées → ProblemDetail à code stable, jamais d'écho de la saisie ni de trace technique.
- Temps via Clock injecté. Secrets uniquement en variables d'environnement.
- Pour chaque nouveau comportement, écris les tests Spock (utilise le skill spock-test-conventions :
  Mock uniquement, given/when/then, pas de def, vérifications strictes). Couvre les cas de refus.
- Mets à jour docs/audits/referentiel.md (nouveaux IDs, ne jamais réutiliser/renuméroter) et
  CHANGELOG.md. Propose une branche par incrément et une PR par incrément si tu suis le workflow habituel.
- Ne touche pas aux changesets Liquibase déjà appliqués : toute évolution de schéma = nouveau changeset.

Avant de coder : propose-moi un court plan (ordre, découpage en incréments, impacts) et attends ma validation.

Corrections à réaliser, par ordre de priorité :

1) PEN-01 (A_CORRIGER) — Révocation des comptes ADMIN et BENEVOLE.
   Aujourd'hui AdminsController et BenevolesController n'ont que POST/GET : un compte créé ne peut
   être ni supprimé ni désactivé par l'API. Implémente la révocation par DÉSACTIVATION (choix retenu,
   pas de suppression dure) : elle préserve l'historique (passages immuables PER-05, résultats de course)
   et reste réversible.
   - Ajoute un état/drapeau `actif` sur le Compte, testé par Compte.peutSeConnecter (déjà utilisé dans
     Connecter.authentifier). Nouveau changeset Liquibase pour la colonne (valeur par défaut : actif).
   - Prévois la désactivation ET la réactivation.
   - Routes et rôles (MIROIR de la création) :
       * ADMIN : désactivation/réactivation réservées à ADMIN_MASTER (comme la création, /api/administration/admins/**).
       * BENEVOLE : désactivation/réactivation ouvertes à ADMIN et ADMIN_MASTER (comme la création sous /api/administration/**).
   - Règles domaine : interdire de désactiver l'admin master (SEC-08), de désactiver le dernier admin actif,
     et l'auto-désactivation. Pour un bénévole désactivé : le désaffecter des courses EN_PREPARATION,
     conserver ses passages déjà enregistrés. Fermer toutes les sessions du compte désactivé
     (RegistreSessionsEnMemoire). Un compte désactivé qui tente de se connecter obtient la même réponse
     qu'un identifiant invalide (ne pas révéler l'état du compte).
   - Ajoute le point SEC-13 au référentiel : « désactivation/réactivation des comptes ADMIN et BENEVOLE,
     fermeture des sessions associées, compte désactivé non connectable ».
   - Fichiers probables : comptes/exposition/AdminsController.java, comptes/exposition/BenevolesController.java,
     nouveau cas d'usage comptes/application/ (ex. DesactiverCompte / ReactiverCompte),
     règles dans comptes/domaine/ (Compte, Role), comptes/infrastructure/SecuriteConfiguration.java
     pour déclarer les nouvelles routes/rôles, nouveau changeset dans db/changelog/.
   - Critères d'acceptation : un ADMIN simple ne peut pas désactiver un autre ADMIN (403) mais peut
     désactiver un BENEVOLE (2xx) ; l'admin master peut désactiver un ADMIN et un BENEVOLE ; l'admin
     master ne peut pas se désactiver ni désactiver le master ; on ne peut pas désactiver le dernier admin
     actif ; un compte désactivé ne peut plus se connecter (réponse identique à IDENTIFIANTS_INVALIDES) et
     ses sessions sont fermées ; la réactivation rétablit la connexion.

2) PEN-05 (SUGGESTION) — Durée et inactivité de session pour les rôles privilégiés.
   server.servlet.session.timeout vaut 12h pour tous. Ajoute une expiration d'inactivité et/ou une durée
   plus courte pour ADMIN et ADMIN_MASTER, en gardant une durée confortable pour COUREUR/BENEVOLE pendant
   une course. Garde la régénération d'identifiant déjà en place. Rends les valeurs configurables par
   variable d'environnement avec un défaut sûr, et documente-les. Fichier : application.yml (et gestion fine
   via RegistreSessionsEnMemoire si nécessaire).

3) PEN-03 (SUGGESTION) — Exposition de /api/version.
   GET /api/version (alias Caddy vers /actuator/info) est public et expose group/artifact/time/version.
   Choix retenu : réduire la réponse à la seule version applicative (retirer group/artifact/time via
   management.info.*), et garder l'alias public pour la vérification post-déploiement. Fichiers :
   frontend/Caddyfile (handle /api/version), application.yml (management.info.*).

4) PEN-02 (SUGGESTION) — Plafond de tentatives de connexion par IP (password spraying).
   Le blocage est par pseudo (SEC-06). Ajoute, en complément, un compteur d'échecs par IP (fenêtre glissante,
   seuil plus haut ex. 20/min), réponse 429 identique à l'existante (TENTATIVES_EXCESSIVES + Retry-After).
   Réutilise AdresseCliente et la mécanique de RegistreTentativesConnexionEnMemoire. Rends le seuil
   configurable (défaut prudent ; attention aux faux positifs derrière un wifi partagé — aligne-toi sur le
   raisonnement déjà tenu pour HACHAGE_CONCURRENCE_MAX_PAR_IP). Ajoute le point SEC-14 au référentiel.

5) PEN-04 (SUGGESTION) — Création de comptes coureur non limitée par IP.
   POST /api/comptes n'est pas couvert par le limiteur de débit public. Étends FiltreLimiteDebitPublic
   (ou un filtre dédié) à POST /api/comptes avec un seuil par IP adapté (quelques créations/minute),
   configurable. Fichiers : courses/infrastructure/LimiteDebitPublicConfiguration.java ou nouveau filtre
   dans comptes/infrastructure/.

Ne modifie que ce qui est nécessaire à ces corrections. Après implémentation, lance les tests
(./mvnw test ou l'équivalent du projet) et résume ce qui a changé, point par point.
