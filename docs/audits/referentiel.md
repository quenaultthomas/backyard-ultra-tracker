# Référentiel des points d'audit

Liste de référence des points auditables. Chaque point a un identifiant stable : les rapports d'audit y renvoient, et l'agent `auditeur-lacunes` s'en sert pour savoir ce qui n'a pas encore été regardé.

- Un point n'est **applicable** que si l'incrément qui le concerne est livré (voir `docs/roadmap.md`). Les points non applicables ne comptent pas comme des lacunes.
- Ajouter un point = ajouter une ligne avec un nouvel identifiant. Ne jamais réutiliser ni renuméroter un identifiant existant.
- Priorité : `P1` risque élevé (sécurité, intégrité des résultats), `P2` important, `P3` hygiène.

## SEC : Sécurité

| ID | Point à vérifier | Priorité | Livré à partir de |
|---|---|---|---|
| SEC-01 | Contrôle des rôles côté serveur sur chaque endpoint (jamais uniquement côté front) | P1 | 1.2 |
| SEC-02 | Mots de passe hachés en Argon2id, paramètres documentés | P1 | 1.1 |
| SEC-03 | Aucun mot de passe en clair dans les logs, exceptions, `ProblemDetail`, traces ; `toString()` masqué (`MotDePasse`, DTO) | P1 | 1.1 |
| SEC-04 | Session serveur, cookie `HttpOnly`, `Secure`, `SameSite=Lax` | P1 | 1.2 |
| SEC-05 | Protection CSRF active sur les requêtes modifiantes | P1 | 1.2 |
| SEC-06 | Limitation des tentatives de connexion, message identique que le pseudo existe ou non | P1 | 1.3 |
| SEC-07 | Jeton QR généré par `SecureRandom`, distinct du dossard, non devinable | P1 | 3.3 |
| SEC-08 | Admin master unique, non supprimable, jamais créable depuis l'interface | P1 | 1.4 |
| SEC-09 | Secrets uniquement en variables d'environnement, rien de commité (historique git compris) | P1 | 0.4 |
| SEC-10 | Envoi du logo : type réel vérifié, taille limitée, nom de fichier non pris tel quel, pas de traversée de chemin | P1 | 2.3 |
| SEC-11 | `/api/test/horloge` et l'horloge pilotable absents hors profil `e2e` | P1 | 4.3 |
| SEC-12 | Un bénévole ou un coureur ne peut pas lire ni modifier les données d'un autre (accès par identifiant, IDOR) | P1 | 2.4 |

## ARC : Architecture et DDD

| ID | Point à vérifier | Priorité | Livré à partir de |
|---|---|---|---|
| ARC-01 | Domaine en Java pur (aucune dépendance Spring, JPA, Jackson) | P2 | 0.3 |
| ARC-02 | L'exposition ne touche pas l'infrastructure ; aucune logique métier dans les contrôleurs | P2 | 0.3 |
| ARC-03 | Chaque règle métier n'existe qu'à un seul endroit, dans le domaine (pas de duplication domaine / cas d'usage / front) | P1 | 1.1 |
| ARC-04 | Le contexte `courses` ne référence un compte que par son identifiant | P2 | 2.4 |
| ARC-05 | Entités JPA distinctes des objets du domaine, mapping explicite | P2 | 1.1 |
| ARC-06 | Un cas d'usage par action métier, transactions gérées dans la couche application | P2 | 1.1 |
| ARC-07 | Exceptions métier typées, aucune exception avalée, codes HTTP cohérents (400/401/403/404/409), `ProblemDetail` | P2 | 1.1 |
| ARC-08 | Temps injecté via `Clock`, aucun `Instant.now()` ni `LocalDateTime.now()` direct | P2 | 2.1 |
| ARC-09 | Conventions de nommage : métier en français, suffixes techniques en anglais, tests nommés par comportement | P3 | 1.1 |

## MET : Règles métier

| ID | Point à vérifier | Priorité | Livré à partir de |
|---|---|---|---|
| MET-01 | Abandon automatique : un seul contrôle sur la boucle écoulée, indépendant par course, déclenché côté serveur | P1 | 4.8 |
| MET-02 | Fin de course : vainqueur unique, vainqueurs partagés, dernière boucle autorisée | P1 | 4.9 |
| MET-03 | Scan refusé : doublon sur la boucle, inscription non `EN_COURSE`, course non démarrée, bénévole non affecté, jeton inconnu | P1 | 4.6 |
| MET-04 | Idempotence de l'enregistrement d'un passage sur (inscription, boucle), y compris en cas de requêtes simultanées | P1 | 5.1 |
| MET-05 | Réintégration : passages `CORRECTION` pour les boucles manquées, exclus de l'allure, badge | P1 | 4.12 |
| MET-06 | Paramètres de boucle et nombre max de boucles figés une fois la course `EN_COURS` | P2 | 4.1 |
| MET-07 | Inscription : course complète, double inscription, course non `EN_PREPARATION`, dossard unique par course (y compris en concurrence) | P1 | 3.2 |
| MET-08 | Désinscription possible uniquement en `EN_PREPARATION`, libération de la place | P2 | 3.4 |
| MET-09 | Suppression de compte coureur : annulation des inscriptions en préparation, anonymisation, conservation des résultats, pseudo réutilisable | P1 | 3.6 |
| MET-10 | Suppression de course : admin master uniquement, `EN_PREPARATION` uniquement, logo et affectations supprimés | P2 | 2.5 |
| MET-11 | Valeurs dérivées (boucle courante, distance, dénivelé, allure, classement) jamais stockées | P2 | 4.2 |

## PER : Persistance

| ID | Point à vérifier | Priorité | Livré à partir de |
|---|---|---|---|
| PER-01 | Aucun changeset Liquibase appliqué n'a été modifié (historique git) | P1 | 1.1 |
| PER-02 | `spring.jpa.hibernate.ddl-auto=validate` | P2 | 0.1 |
| PER-03 | Contraintes d'unicité et clés étrangères en base (pseudo, dossard par course, inscription par course et compte, passage par inscription et boucle) | P1 | 3.1 |
| PER-04 | Index adaptés aux requêtes de suivi et de scan | P3 | 4.4 |
| PER-05 | Passages immuables : aucune mise à jour ni suppression applicative | P1 | 4.4 |

## DEP : Déploiement

| ID | Point à vérifier | Priorité | Livré à partir de |
|---|---|---|---|
| DEP-01 | Versions d'images fixées, jamais `latest` | P2 | 0.1 |
| DEP-02 | Exécution en utilisateur non root dans les images | P2 | 0.1 |
| DEP-03 | `.env.example` complet et sans valeur secrète | P2 | 0.4 |
| DEP-04 | Healthchecks sur les trois services, `depends_on` avec `service_healthy` | P2 | 0.2 |
| DEP-05 | Production : seul `web` expose des ports, `restart: unless-stopped`, HTTPS | P1 | 0.4 |
| DEP-06 | `.dockerignore` : pas de `target/`, `node_modules/`, secrets dans les images | P3 | 0.1 |
| DEP-07 | Sauvegarde et restauration documentées et cohérentes avec les volumes réels | P2 | 0.4 |

## TST : Tests et qualité

| ID | Point à vérifier | Priorité | Livré à partir de |
|---|---|---|---|
| TST-01 | Chaque critère d'acceptation des specs est référencé par au moins un test | P2 | 1.1 |
| TST-02 | Les tests vérifient un comportement (assertions réelles), pas seulement de la couverture | P2 | 1.1 |
| TST-03 | Le seuil de couverture de 85 % n'est pas contourné (exclusions JaCoCo, tests vides) | P2 | 0.3 |
| TST-04 | Chaque écran livré est couvert par au moins un test E2E | P2 | 1.1 |
| TST-05 | Chaque règle métier visible à l'écran a son scénario E2E | P2 | 4.8 |
| TST-06 | Aucun test supprimé ou affaibli pour obtenir un verdict vert (historique git) | P2 | 1.1 |

## FRT : Front

| ID | Point à vérifier | Priorité | Livré à partir de |
|---|---|---|---|
| FRT-01 | Gardes de routes selon le rôle, sans qu'elles soient la seule protection | P2 | 1.4 |
| FRT-02 | Gestion des erreurs de l'API (401, 403, 409, 5xx) cohérente et exploitable | P2 | 1.2 |
| FRT-03 | Conformité aux contrats d'API des specs | P2 | 1.1 |
| FRT-04 | Pas de données non échappées injectées dans le DOM (XSS) | P1 | 1.1 |
| FRT-05 | File de scan hors ligne : pas de perte ni de doublon, reprise après coupure | P1 | 5.1 |
| FRT-06 | Service worker : pas de mise en cache de données sensibles ni de réponses d'API périmées | P2 | 5.2 |
