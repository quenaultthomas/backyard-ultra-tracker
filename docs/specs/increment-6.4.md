# Increment 6.4 : Accueil public

**Dépendance : 6.1 à 6.3 (écran public `/courses/:id/suivi`), 4.1 (démarrage), 4.10 (fin de course), 2.x (logo) livrées et validées.** 6.4 clôt le jalon 6 : la page d'accueil publique `/` liste les Courses par statut (en cours, à venir, terminées) avec un lien vers leur suivi. C'est le seul moyen, pour un visiteur, de trouver l'adresse d'un suivi sans passer par l'administration.

Numérotation : RG et CA repartent à 1. « 6.1 RGn », « 4.x RGn » désignent les specs correspondantes.

## État actuel constaté dans le dépôt

- Aucun endpoint public ne liste les Courses. `GET /api/administration/courses` (admin) et `GET /api/coureur/courses` (rôle `COUREUR`, Courses `EN_PREPARATION` seulement, avec Inscription personnelle) ne conviennent pas : rôle exigé, données sensibles (bénévoles, Inscriptions).
- `SecuriteConfiguration` : seules lectures publiques sous `/api/courses` : `GET /api/courses/*/logo` et `GET /api/courses/*/suivi`. Le chemin exact `GET /api/courses` tombe dans `/api/**` `authenticated()` (401 pour un anonyme).
- `ListerCourses.executerAvecLogos()` : toutes les Courses triées par `Course.ORDRE_DE_LISTE` (date décroissante, nom insensible à la casse, identifiant) avec l'empreinte du logo, **en une lecture groupée** (aucun octet de logo lu). `CourseReponse.depuis(CourseListee)` fournit `logoUrl` versionné (`/api/courses/{id}/logo?v=…`, null sans logo). `Course.estOuverte()` = `EN_PREPARATION`.
- Frontend : `frontend/src/app/accueil/` (`Accueil`, `OnPush`, `main.accueil.decor-backyard`, `titre` « Backyard Ultra Tracker » et `accroche`), route `''`. L'accueil n'appelle aucune API. Le test E2E de thème (`aide-theme.ts`) attend `accroche` visible sur `/` pour un anonyme.
- `scripts/donnees-demo.sh` crée 4 Courses (toutes `EN_PREPARATION`), 21 éléments ; `e2e/tests/donnees-demo.spec.ts` le vérifie.

## 1. Périmètre

**Inclus**
- Endpoint public `GET /api/courses` : Courses regroupées en trois rubriques (`enCours`, `aVenir`, `terminees`), triées, avec nom, date, logo (RG1 à RG7).
- Accueil `/` : trois rubriques sous le titre et l'accroche existants, chaque Course avec son logo, son nom, sa date et un lien « Voir le suivi » vers `/courses/:id/suivi` (RG8 à RG13).
- Données de démo : une Course de plus pour pouvoir montrer les trois rubriques (RG14).

**Exclu**
- Inscription depuis l'accueil, bouton « S'inscrire », nombre d'inscrits, places restantes, bénévoles : l'inscription reste dans l'espace coureur (3.x). Aucun lien d'administration ou de pilotage.
- Recherche, filtre côté écran, pagination, tri choisi par le visiteur (point ouvert 1 et 2).
- Rafraîchissement automatique de l'accueil (point ouvert 3).
- Statistiques, vainqueurs, durée dans la liste : elles restent dans le suivi (6.2, 6.3).
- Toute modification du domaine des règles de statut, de l'abandon, de la fin de course ; aucune migration, aucune variable d'environnement, aucun changement de `docker-compose*.yml`, `.env.example`, Caddy.

**Taille estimée (production hors tests) : ~230 lignes** : domaine (ordre « à venir ») ~8, cas d'usage ~35, DTO + contrôleur ~55, sécurité ~1, front (composant, service, rubrique, styles) ~120, script de démo ~10. Dans la cible, pas de découpage.

## 2. Règles de gestion

**API**
- **RG1** : `GET /api/courses` est **public** : aucune authentification, aucune session créée, aucun CSRF (GET). Un Compte connecté, quel que soit son rôle, reçoit la même réponse. Règle ajoutée dans `SecuriteConfiguration` à côté de celles du logo et du suivi. Les autres méthodes sur `/api/courses` ne répondent jamais 2xx.
- **RG2** : données exposées, **liste fermée** par Course : `id`, `nom`, `date`, `logoUrl`. Jamais de statut de ligne redondant (le statut est la rubrique), de paramètres de Boucle, de limites, de `demarreeLe`, de bénévole, d'Inscription, de pseudo, de dossard, de jeton QR, de nombre d'inscrits, d'identifiant de Compte.
- **RG3** : regroupement par statut de la Course **tel qu'enregistré** (aucun calcul dérivé, aucune lecture de l'horloge) : `enCours` = `EN_COURS`, `aVenir` = `EN_PREPARATION`, `terminees` = `TERMINEE`. Chaque Course figure dans exactement une rubrique. Une Course `EN_COURS` sans heure de départ (4.1 RG13) est dans `enCours`. Une Course dont la fin vient d'arriver passe en `terminees` dès que le planificateur a enregistré `TERMINEE` (4.10), pas avant.
- **RG4** : tri de chaque rubrique, déterministe :
  - `enCours` : `Course.ORDRE_DE_LISTE` (date décroissante, puis nom sans casse, puis identifiant) ;
  - `terminees` : `Course.ORDRE_DE_LISTE` (la plus récente d'abord) ;
  - `aVenir` : date **croissante** (la prochaine d'abord), puis nom sans casse, puis identifiant. Nouvel ordre `Course.ORDRE_A_VENIR` dans le domaine, **seule définition** ; aucun tri dans le contrôleur ni le front.
- **RG5** : une rubrique sans Course est un **tableau vide** (jamais `null`, jamais absente). Aucune Course du tout : trois tableaux vides, 200.
- **RG6** : lecture seule, une lecture de Courses et une lecture groupée des empreintes de logo ; **ni Inscriptions, ni Passages, ni Comptes lus**. Réponse en `Cache-Control: no-store`. Aucun journal INFO ou supérieur en nominal. `toString()` de la réponse n'affiche que les effectifs des rubriques. Aucune limite de taille ni pagination.
- **RG7** : `date` au format `AAAA-MM-JJ` (date de la Course, fuseau de la Course, comme `CourseOuverteReponse`). `logoUrl` : adresse publique versionnée du logo, identique à celle du suivi ; `null` sans logo.

**Écran**
- **RG8** : sous `titre` et `accroche` (inchangés, mêmes `data-testid`), trois rubriques dans cet ordre : « En cours », « À venir », « Terminées » (la rubrique utile en direct d'abord). Chaque rubrique : `h2` `accueil-rubrique-titre-<cle>` avec le nombre de Courses, puis la liste. Clés : `en-cours`, `a-venir`, `terminees`. Conteneurs `accueil-rubrique-en-cours`, `accueil-rubrique-a-venir`, `accueil-rubrique-terminees`. L'ordre des Courses est celui de la réponse (RG4), jamais retrié.
- **RG9** : carte de Course (`accueil-course`, réutilise le style des cartes de Course ouverte de la charte, `docs/design.md`) : `accueil-course-logo` (visuel 16/9 : image de `logoUrl`, ou visuel de substitution existant si `logoUrl` est null ou si l'image ne charge pas), `accueil-course-nom` (mots coupés), `accueil-course-date` (format `JJ/MM/AAAA` déjà utilisé par les listes de Courses), `accueil-course-lien-suivi` « Voir le suivi » (`a.bouton.bouton--secondaire`, `href` `/courses/<id>/suivi`, même onglet). Le nom accessible du lien inclut le nom de la Course (« Voir le suivi de <nom> »). Aucune autre action.
- **RG10** : états : chargement `accueil-chargement` « Chargement des courses… » (rubriques masquées) ; rubrique vide `accueil-vide-<cle>` : « Aucune course en cours. », « Aucune course à venir. », « Aucune course terminée. » (le titre de la rubrique reste affiché, avec le nombre 0) ; erreur réseau ou 5xx `accueil-erreur` « Les courses ne peuvent pas être chargées pour le moment. » avec bouton `accueil-reessayer` « Réessayer » (relance l'appel, rétablit « Chargement… »). Titre et accroche restent visibles dans tous les états.
- **RG11** : chargement une fois à l'ouverture de `/` (à chaque navigation vers `/`, donc retour depuis un suivi à jour). Pas de polling (point ouvert 3).
- **RG12** : charte : `docs/design.md` (cartes, `.message--info`, `.message--erreur` avec libellé, jamais la couleur seule), décor backyard conservé ; aucune couleur ni taille en dur ; aucun défilement horizontal à 320 px (texte à 200 % compris) ; pas d'animation hors changement de couleur ; `docs/design-apercu.html` complété si un composant est ajouté.
- **RG13** : l'accueil est identique pour un anonyme et un Compte connecté (aucun lien de rôle ajouté) ; l'en-tête de session reste celui existant. Les attentes existantes de `/` (titre, accroche, décor, thème) sont inchangées.

**Démo**
- **RG14** : `scripts/donnees-demo.sh` ajoute la Course « Backyard du soir » (date du jour, 400 m, **5 min**, 5 m, 10 participants, 5 Boucles max, Léo non affecté) et l'Inscription de Karim. Raison : « Backyard du jour » (1 min) se termine d'elle-même une minute après son démarrage ; « Backyard du soir » reste `EN_COURS` au moins 5 minutes, ce qui permet de voir en même temps `terminees` (« du jour ») et `enCours` (« du soir »). Total du script : 5 Courses, 9 Inscriptions, soit 23 éléments. Idempotent comme le reste ; `e2e/tests/donnees-demo.spec.ts` et l'en-tête du script sont mis à jour.

## 3. Cas limites

- **Rubriques vides** : base sans Course, ou aucune Course d'un statut : tableau vide (RG5), message de rubrique (RG10).
- **Course non démarrée** : listée en « À venir », le lien mène au suivi qui affiche « La course n'a pas encore démarré. » (6.1 RG3).
- **Course démarrée, pas encore terminée au sens du temps** (Boucle max écoulée, fin non constatée) : reste dans « En cours » jusqu'à l'enregistrement de `TERMINEE` (RG3).
- **Course terminée** (vainqueur unique, vainqueurs partagés, dernière Boucle autorisée) : même rubrique, aucune différence d'affichage ; le suivi montre le détail.
- **Plusieurs Courses en parallèle** : chacune dans sa rubrique selon son propre statut (démarrage et fin indépendants).
- **Course supprimée** (seulement `EN_PREPARATION`, 2.5) : disparaît de « À venir » à la lecture suivante ; un lien déjà affiché mène à `suivi-introuvable` (6.1 RG8).
- **Même date, même nom** : départagées par identifiant (RG4). **Nom très long ou sans espace** : coupé, lisible.
- **Course sans logo / logo en erreur** : visuel de substitution, sans message.
- **Beaucoup de Courses terminées** : toutes listées, sans limite (point ouvert 2).
- **Désinscription, inscription, Passage `CORRECTION` ou `SCAN`, Abandon, Réintégration, coureur réintégré, bénévole non affecté, rôle insuffisant** : aucune règle propre, l'endpoint ne lit aucun de ces éléments et n'a aucun contrôle de rôle ; un Compte connecté voit la même réponse (RG1, RG13).
- **Instants de bascule de Boucle** : sans effet sur la liste (aucune lecture de l'horloge, RG3).
- **Anonymisation d'un Compte coureur** : sans effet (aucune donnée de Compte exposée).

## 4. Contrat d'API

### `GET /api/courses`

- **Accès** : public (aucune authentification, aucun CSRF). Aucun corps, aucun paramètre de requête lu.
- **Réponse 200** `CoursesPubliquesReponse`, `Cache-Control: no-store`, `application/json` :

| Champ | Type | Contenu |
|---|---|---|
| `enCours` | tableau de `CoursePubliqueReponse` | Courses `EN_COURS`, tri RG4 ; `[]` si aucune |
| `aVenir` | tableau de `CoursePubliqueReponse` | Courses `EN_PREPARATION`, tri RG4 ; `[]` si aucune |
| `terminees` | tableau de `CoursePubliqueReponse` | Courses `TERMINEE`, tri RG4 ; `[]` si aucune |

`CoursePubliqueReponse` :

| Champ | Type | Contenu |
|---|---|---|
| `id` | UUID (texte) | identifiant de la Course (sert au lien `/courses/{id}/suivi`) |
| `nom` | texte | nom de la Course |
| `date` | texte `AAAA-MM-JJ` | date de la Course |
| `logoUrl` | texte ou `null` | `/api/courses/{id}/logo?v=…`, `null` sans logo |

Exemple : `{"enCours":[{"id":"…","nom":"Backyard du soir","date":"2026-10-21","logoUrl":null}],"aVenir":[],"terminees":[{"id":"…","nom":"Backyard du jour","date":"2026-10-21","logoUrl":"/api/courses/…/logo?v=ab12"}]}`.

- **Erreurs** (`ProblemDetail`, `application/problem+json`, champ `code`) :

| Code HTTP | Cas | `code` |
|---|---|---|
| 405 / 403 | autre méthode sur `/api/courses` (jamais 2xx ; un `POST` sans CSRF donne 403 `CSRF_INVALIDE`) | existants |

Aucun 400 (aucun paramètre), 401, 403 de rôle, 404, 409 : la liste vide est un 200.

### Impacts back
- **Domaine** : `Course.ORDRE_A_VENIR` (comparateur). Aucune autre règle.
- **Application** : cas d'usage `ListerCoursesPubliques` (lecture seule, `@Transactional(readOnly = true)`), réutilise `ListerCourses.executerAvecLogos()` ou les mêmes ports ; regroupe et trie par statut via le domaine.
- **Exposition** : `CoursesPubliquesController` (`GET /api/courses`, HEAD refusé comme `SuiviPublicController`), DTO `CoursesPubliquesReponse` et `CoursePubliqueReponse` ; `logoUrl` construit comme `CourseReponse`.
- **Sécurité** : `GET /api/courses` ajouté aux `permitAll`.
- Aucune migration Liquibase, aucune variable d'environnement.

### Front (indicatif)
`Accueil` charge via un service `CoursesPubliquesApiService.lister()` ; composant de rubrique réutilisé trois fois ; liens `routerLink` vers `/courses/:id/suivi`. Noms libres, comportement fixé par RG8 à RG13.

## 5. Écrans

### Accueil (`/`, modifié, public)
- **Affiché** : `titre`, `accroche` (inchangés) ; rubriques « En cours », « À venir », « Terminées » avec leur nombre ; par Course : logo ou substitution, nom, date, lien « Voir le suivi » ; états chargement, rubrique vide, erreur (RG8 à RG10).
- **Actions** : « Voir le suivi » (vers le suivi de la Course) ; « Réessayer » en cas d'erreur. Aucune autre.
- **Messages** : « Chargement des courses… » ; « Aucune course en cours. » ; « Aucune course à venir. » ; « Aucune course terminée. » ; « Les courses ne peuvent pas être chargées pour le moment. ».

## 6. Critères d'acceptation

Valeurs de référence : Courses A « Backyard Alpha » (`EN_PREPARATION`, 2026-11-20), B « backyard beta » (`EN_PREPARATION`, 2026-11-05), C « Backyard Gamma » (`EN_PREPARATION`, 2026-11-05), D « Backyard Delta » (`EN_COURS`, 2026-10-20), E « Backyard Epsilon » (`EN_COURS`, 2026-10-21), F « Backyard Zeta » (`TERMINEE`, 2026-09-01), G « Backyard Eta » (`TERMINEE`, 2026-10-10). Comptes de 3.1 (`Nadia` `ADMIN`, `Léo` `BENEVOLE`, `Alice` `COUREUR`). E2E : projet `chromium`, sauf CA14 (`horloge`). Les tests E2E de l'accueil s'ouvrent dans un **contexte neuf sans session**, avec des Courses aux noms uniques « Course E2E … » (la base E2E contient d'autres Courses : les assertions portent sur les Courses créées par le test).

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Étant donné A à G, quand `ListerCoursesPubliques` s'exécute, alors `enCours` = [E, D], `terminees` = [G, F], `aVenir` = [B, C, A] ; chaque Course figure dans une seule rubrique (RG3) | unitaire |
| CA2 | Étant donné B et C (même date 2026-11-05) puis une troisième Course de même date et même nom que C, alors `aVenir` ordonne date croissante, nom sans casse (« backyard beta » avant « Backyard Gamma »), puis identifiant ; deux appels donnent le même ordre ; `enCours` et `terminees` suivent `ORDRE_DE_LISTE` (date décroissante) (RG4) | unitaire |
| CA3 | Étant donné aucune Course, ou seulement des Courses `TERMINEE`, alors les rubriques sans Course sont des listes vides (jamais absentes) et aucune exception n'est levée (RG5) | unitaire |
| CA4 | Étant donné des doublures des ports, quand le cas d'usage s'exécute, alors ni Inscriptions, ni Passages, ni annuaire de pseudos ne sont lus et l'empreinte de logo est lue en un seul appel groupé ; une Course `EN_COURS` sans heure de départ est dans `enCours` ; aucun `Clock` n'est utilisé (RG3, RG6) | unitaire |
| CA5 | Étant donné A à G en base et **aucune session**, quand `GET /api/courses`, alors 200, `Cache-Control: no-store`, aucun `Set-Cookie`, `enCours` = [E, D], `aVenir` = [B, C, A], `terminees` = [G, F] ; chaque élément n'a que les 4 champs `id`, `nom`, `date` (`AAAA-MM-JJ`), `logoUrl` ; et, à égalité de date et de nom, les Courses sont départagées par identifiant, avec un ordre identique d'un appel à l'autre (RG1, RG2, RG3, RG4, RG7) | intégration |
| CA6 | Étant donné une Course avec logo et une sans, alors `logoUrl` commence par `/api/courses/<id>/logo?v=` pour la première et vaut `null` pour la seconde ; le corps ne contient ni `benevole`, `inscri`, `pseudo`, `dossard`, `jeton`, `demarree`, `distance`, `nombreMax` (RG2, RG7) | intégration |
| CA7 | Étant donné une base sans Course, quand `GET /api/courses`, alors 200 et `{"enCours":[],"aVenir":[],"terminees":[]}` (RG5) | intégration |
| CA8 | Étant donné A à G, quand la même requête est faite avec une session `ADMIN`, `BENEVOLE` affecté, `BENEVOLE` non affecté et `COUREUR`, alors 200 et le même corps pour tous ; sans jeton CSRF elle n'est jamais refusée ; `POST`, `PUT`, `DELETE` sur `/api/courses` ne répondent jamais 2xx (un `POST` sans CSRF : 403 `CSRF_INVALIDE`) (RG1) | intégration |
| CA9 | Étant donné une Course `EN_PREPARATION` supprimée par l'admin master, puis une Course démarrée (statut `EN_COURS` après `POST .../demarrage`), alors la première a disparu de `aVenir` ; la seconde est passée de `aVenir` à `enCours` ; la lecture ne modifie rien en base (aucun statut, Inscription, Passage modifié) (RG3, RG6) | intégration |
| CA10 | Étant donné 5 Courses et 40 Inscriptions en base, quand `GET /api/courses`, alors le nombre d'instructions SQL ne dépend pas du nombre d'Inscriptions (aucune lecture d'Inscription) et ne croît pas avec le nombre de Courses (une lecture de Courses, une des empreintes) (RG6) | intégration |
| CA11 | Étant donné un contexte neuf sans session et trois Courses créées par le test (une `EN_PREPARATION`, une démarrée, une terminée), quand on ouvre `/`, alors `titre` et `accroche` sont visibles ; les rubriques apparaissent dans l'ordre « En cours », « À venir », « Terminées » ; chaque Course est dans la bonne rubrique avec `accueil-course-nom` et `accueil-course-date` au format `JJ/MM/AAAA` ; le titre de rubrique indique le nombre de Courses ; aucune redirection vers `/connexion` (RG1, RG3, RG8, RG9) | E2E |
| CA12 | Étant donné le CA11 et une Course avec logo, quand on clique `accueil-course-lien-suivi` de la Course démarrée, alors l'URL est `/courses/<id>/suivi` et `suivi-nom` affiche son nom ; pour la Course à venir `suivi-message-non-demarree` ; pour la terminée `suivi-message-terminee` ; `accueil-course-logo` affiche une image dont `src` contient `/api/courses/<id>/logo`, et un visuel de substitution pour une Course sans logo ; le nom accessible du lien contient le nom de la Course (RG9) | E2E |
| CA13 | Étant donné trois Courses `EN_PREPARATION` créées, dans cet ordre de création, avec des dates relatives à aujourd'hui de +1300, +1100 et +1200 jours (dans la limite de 5 ans acceptée par l'API, sinon 400 `DATE_TROP_LOINTAINE`), alors dans `accueil-rubrique-a-venir` leur ordre est +1100, +1200, +1300 jours ; deux Courses terminées ou en cours de dates différentes apparaissent la plus récente d'abord (RG4, RG8) | E2E |
| CA14 | Étant donné une Course à venir et l'accueil ouvert (projet `horloge`), quand l'admin la démarre par l'API puis que l'horloge avance de `PT61M` et que la fin de course est constatée (4.10), alors après rechargement de `/` la Course est d'abord dans « En cours », puis dans « Terminées » et n'est plus dans les deux autres rubriques ; sans rechargement, l'écran ne change pas (RG3, RG11) | E2E |
| CA15 | Étant donné `GET /api/courses` interceptée pour renvoyer `{"enCours":[],"aVenir":[],"terminees":[]}`, alors `accueil-vide-en-cours` « Aucune course en cours. », `accueil-vide-a-venir` « Aucune course à venir. », `accueil-vide-terminees` « Aucune course terminée. » sont visibles, les titres de rubrique restent affichés avec 0, `titre` et `accroche` aussi ; `accueil-chargement` « Chargement des courses… » est visible tant que la réponse est retardée (RG5, RG10) | E2E |
| CA16 | Étant donné `GET /api/courses` interceptée en 503, alors `accueil-erreur` « Les courses ne peuvent pas être chargées pour le moment. » et `accueil-reessayer` s'affichent, `titre` et `accroche` restent visibles ; après retour à 200 et clic sur « Réessayer », les rubriques s'affichent sans rechargement de la page ; coupure réseau (`abort`) : même comportement (RG10) | E2E |
| CA17 | Étant donné Nadia, puis Alice, puis un anonyme, quand chacun ouvre `/`, alors les mêmes Courses dans les mêmes rubriques, sans lien d'administration ni bouton d'inscription dans les cartes ; l'en-tête de chaque session reste celui existant (RG13) | E2E |
| CA18 | Étant donné l'accueil à 375 px, 320 px (texte normal puis 200 %) et 1280 px, avec une Course au nom de 60 caractères sans espace, alors aucun défilement horizontal, le nom est coupé et lisible, les liens sont atteignables au clavier dans l'ordre d'affichage avec un focus visible, contrastes AA, `prefers-reduced-motion` sans animation, aucune couleur en dur dans les styles ajoutés, décor backyard conservé (RG9, RG12) | E2E |
| CA19 | Étant donné `donnees-demo.sh` lancé sur une base neuve, alors 23 éléments sont créés (5 Courses dont « Backyard du soir » de 5 min avec l'Inscription de Karim) ; relancé, rien n'est créé en double ; l'accueil liste les 5 Courses dans « À venir » (RG14) | E2E |

Répartition : unitaire 4 (CA1 à CA4), intégration 6 (CA5 à CA10), E2E 9 (CA11 à CA19). Total 19.

Couverture des RG : RG1 CA5/CA8/CA11, RG2 CA5/CA6, RG3 CA1/CA4/CA5/CA9/CA11/CA14, RG4 CA1/CA2/CA5/CA13, RG5 CA3/CA7/CA15, RG6 CA4/CA9/CA10, RG7 CA5/CA6, RG8 CA11/CA13, RG9 CA11/CA12/CA18, RG10 CA15/CA16, RG11 CA14, RG12 CA18, RG13 CA17, RG14 CA19. Écran « Accueil » : CA11 à CA18 (E2E).

### Stratégie de test
- Unitaire : `ListerCoursesPubliques` avec doublures des ports, sans Spring ; le tri est vérifié sur le comparateur du domaine.
- Intégration : `@SpringBootTest` + Testcontainers ; requêtes **sans** cookie ni CSRF ; comptage d'instructions comme 6.2 CA12.
- E2E : helpers existants (Course créée, démarrée par l'API, `avancerHorloge`) ; interceptions `page.route` pour les états vide, lent et erreur. Les assertions portent sur les Courses créées par le test (noms uniques), jamais sur le contenu total de la base. CA19 s'appuie sur `donnees-demo.spec.ts` mis à jour.

## 7. Tester à la main

Prérequis : `docker compose up -d --build` (services sains), base neuve (`docker compose down -v` si besoin), puis `./scripts/donnees-demo.sh`. Comptes : `Nadia` (`mot-de-passe-admin-1`), `Léo` (`mot-de-passe-benevole-1`), `Karim` (`un-mot-de-passe-12`). Le démarrage n'est possible que le jour de la Course : « Backyard du jour » et « Backyard du soir » ont la date du jour.

1. **Accueil sans connexion** : ouvrir http://localhost dans une fenêtre privée. Sous le titre et l'accroche : « En cours » (0, « Aucune course en cours. »), « À venir » (5 Courses : « Backyard du jour », « Backyard du soir » en premier avec la date du jour, puis « Backyard express » J+7, « Backyard mini » J+14, « Backyard de démo » J+30), « Terminées » (0, « Aucune course terminée. »).
2. **Tri « À venir »** : la date la plus proche d'abord ; les deux Courses du jour sont ordonnées par nom.
3. **Lien** : cliquer « Voir le suivi » sur « Backyard express » : le suivi s'ouvre avec « La course n'a pas encore démarré. ». Revenir à l'accueil (menu ou retour).
4. **Passage en cours** : en `Nadia`, démarrer « Backyard du jour » (fiche > Démarrer) puis « Backyard du soir ». Recharger l'accueil de la fenêtre privée : les deux sont dans « En cours » (compteur 2) et ne sont plus dans « À venir » (3 restantes). Cliquer « Voir le suivi » : Boucle et compte à rebours s'affichent.
5. **Passage en terminées** : attendre environ 1 à 2 minutes (« du jour » dure 1 min par Boucle, personne ne scanne : fin de course, vainqueurs partagés). Recharger l'accueil : « Backyard du jour » est dans « Terminées », « Backyard du soir » (5 min par Boucle) reste « En cours ». Le lien de « Backyard du jour » mène à « La course est terminée. ».
6. **Pas de rafraîchissement automatique** : sans recharger, l'écran ne change pas seul (comportement attendu, point ouvert 3).
7. **Même vue connecté** : se connecter en `Karim` puis en `Nadia` : mêmes rubriques, aucun lien d'administration dans les cartes.
8. **Logo** : en `Nadia`, ajouter un logo à « Backyard mini » (fiche) ; recharger l'accueil : le logo remplace le visuel de substitution.
9. **Erreur** : F12 > Réseau > Hors connexion, recharger l'accueil depuis le lien « Backyard Ultra Tracker » de l'en-tête (navigation interne) : « Les courses ne peuvent pas être chargées pour le moment. » ; rétablir le réseau, « Réessayer » : les rubriques reviennent.
10. **Téléphone** : ouvrir l'accueil à 375 px : cartes lisibles, pas de défilement horizontal.
11. **Script relancé** : relancer `./scripts/donnees-demo.sh` avant le démarrage des Courses : « déjà présent » partout, rien en double (après le démarrage, il échoue sur les Inscriptions, comportement connu).

## 8. Points ouverts

**Décisions validées par l'utilisateur** : ordre des rubriques En cours / À venir / Terminées, toutes les Courses listées sans limite, rubrique « À venir » publique, et « Backyard du soir » ajoutée à la démo ; les points ouverts 1 à 7 ci-dessous sont tranchés dans le sens de leur position par défaut.

À titre d'historique, les points et leur position par défaut :

1. **Filtre par statut dans l'API** : le besoin parle de « groupées/filtrables ». Position par défaut : réponse déjà groupée en trois rubriques, aucun paramètre `statut` (YAGNI, l'écran affiche tout). Alternative : paramètre optionnel `statut=EN_COURS` (400 `PARAMETRE_INVALIDE` sinon) si un autre écran en a besoin.
2. **Volume des « Terminées »** : toutes les Courses terminées sont listées, sans pagination ni limite. Position par défaut : aucune limite (peu d'éditions). Alternative : afficher les N plus récentes (par exemple 12) avec un lien « Voir toutes ».
3. **Rafraîchissement de l'accueil** : chargement à l'ouverture seulement. Alternative : polling de 10 s ou rechargement au retour sur l'onglet, utile si l'accueil est projeté avant le départ. Position par défaut : aucun (le suivi, lui, se rafraîchit).
4. **Ordre des rubriques** : la roadmap cite « à venir, en cours et terminées » ; la spec affiche « En cours » d'abord (le direct est le cas d'usage principal). Position par défaut : En cours, À venir, Terminées.
5. **Exposition des Courses `EN_PREPARATION`** : leur nom, leur date et leur logo deviennent publics (déjà vrai pour les coureurs connectés, et la page de suivi 6.1 point 1). Position par défaut : publiques. Alternative : masquer « À venir » aux anonymes.
6. **Dates passées dans « À venir »** : une Course `EN_PREPARATION` dont la date est passée (jamais démarrée) reste dans « À venir », sans marque particulière. Position par défaut : rien de plus. Alternative : badge « Date dépassée ».
7. **Script de démo** : ajout de « Backyard du soir » (RG14), qui change le total de 21 à 23 éléments et oblige à mettre à jour `donnees-demo.spec.ts`. Alternative : ne rien ajouter et créer la deuxième Course du jour à la main.
