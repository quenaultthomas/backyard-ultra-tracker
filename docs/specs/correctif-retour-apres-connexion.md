# Correctif : destination après connexion avec `?retour=`

Origine : point ouvert hérité de `docs/specs/increment-9.7.md` §8 point 1 (constat du 9.6b). Décision de l'utilisateur : on le traite. Branche `correctif/points-ouverts-jalon-10`.

Problème : après connexion avec `/connexion?retour=<chemin>`, le front suit `retour` même si le rôle connecté n'a pas le droit d'ouvrir cette route, et la garde de rôles envoie alors sur `/acces-refuse`. Cas typiques : coureur promu en `BENEVOLE` ou `ADMIN` dont la session est invalidée et qui se reconnecte depuis `retour=/coureur/inscriptions` ; reconnexion avec un autre compte ; `ADMIN` avec `retour=/administration/admins`.

Diagnostic (front seul) : `frontend/src/app/comptes/retour.ts` (`destinationApresConnexion`), `frontend/src/app/comptes/connexion/connexion.ts` (`rejoindreDestination`, cas `dejaConnecte`), `frontend/src/app/comptes/reserve-aux-roles.guard.ts` (renvoie `/acces-refuse`).

Ce correctif **amende** le comportement décrit par 9.6b CA29 et 9.7 CA21 (simplification des scénarios, sans affaiblissement : voir « Tests »). Tous les CA sont **[front]**, niveau `E2E` (pas de tests unitaires front). Aucune modification du back, de l'API, de la base ni de `.env.example`. Aucun nouvel écran.

## 1. Périmètre

**Inclus** : choix de la destination après connexion (connexion nominale et cas « déjà connecté »), et comportement de la garde de rôles pour une navigation issue d'une connexion.

**Exclu** : tout changement d'accès direct à une route interdite (reste `/acces-refuse`, 9.6 CA28, 9.7 CA20) ; menu d'administration ; contrôle des rôles côté serveur ; invalidation des sessions.

## 2. Règles de gestion

Accueil du rôle : `/administration` pour `ADMIN_MASTER` et `ADMIN`, `/benevole` pour `BENEVOLE`, `/` pour `COUREUR`.

- **RG1** : un `retour` interne (RG4) **autorisé** pour le rôle connecté est suivi tel quel (inchangé).
- **RG2** : un `retour` interne **interdit** au rôle connecté conduit à l'accueil du rôle, sans passer par `/acces-refuse`. Exemples : `COUREUR` promu `BENEVOLE` avec `retour=/coureur/inscriptions` arrive sur `/benevole` ; promu `ADMIN` sur `/administration` ; `ADMIN` avec `retour=/administration/admins` sur `/administration`.
- **RG3** : un accès direct (hors navigation issue d'une connexion) à une route interdite à un Compte connecté mène toujours à `/acces-refuse`. Un anonyme est toujours envoyé vers `/connexion?retour=<url>`.
- **RG4** : un `retour` est interne s'il commence par un seul `/` suivi d'un caractère autre que `/` ou `\`. Tout autre `retour` (`//exemple.org`, `/\exemple.org`, `https://exemple.org`, vide, absent) conduit à l'accueil du rôle.
- **RG5** : `retour=/connexion` et `retour=/creer-compte` (avec ou sans paramètres) sont traités comme absents : accueil du rôle.
- **RG6** : pas de boucle de redirection possible : l'accueil de chaque rôle est autorisé à ce rôle, et la substitution (RG2) n'a lieu qu'une fois.
- **RG7** : `retour=/mon-compte` (route ouverte à tout Compte connecté) est suivi.
- **RG8** : RG1 à RG7 s'appliquent aussi au cas « déjà connecté » (réponse `dejaConnecte` du serveur lors de la connexion).

Mécanisme suggéré (le développeur front peut en choisir un équivalent) : `rejoindreDestination` navigue avec un indicateur d'état « après connexion » (`state`), lu par la garde de rôles qui, si le rôle n'est pas autorisé et que l'indicateur est présent, renvoie vers l'accueil du rôle au lieu de `/acces-refuse`. L'indicateur n'est jamais pris en compte hors navigation issue de la connexion (un rechargement ou un lien direct n'en porte pas).

## 3. Cas limites

- Navigation « Précédent » du navigateur vers une route interdite après connexion : pas d'indicateur, donc `/acces-refuse` (RG3).
- Reconnexion avec un autre compte (`Alice` coureuse puis `Léo` bénévole) sur `retour=/coureur/inscriptions` : `/benevole` (RG2).
- `ADMIN_MASTER` avec `retour=/administration/admins` : autorisé, suivi (RG1).
- `retour` encodé en double ou contenant une requête (`/mon-compte?x=1`) : interne, traité selon RG1/RG2 sur le chemin.
- Anonyme sur une route d'administration : `/connexion?retour=%2Fadministration%2Fadmins` (inchangé).

## 4. Contrat d'API

Aucun changement.

## 5. Écrans

Écran « Connexion » seul concerné : aucun nouveau champ, message ni action. Le message de `/acces-refuse` reste inchangé et n'est plus affiché après une connexion avec `retour` interdit.

## 6. Critères d'acceptation (tous [front], E2E)

| CA | Énoncé | Niveau |
|---|---|---|
| CA1 | Étant donné `E5` coureur connecté (fenêtre A, « Mes inscriptions ») puis promu `BENEVOLE` par `Nadia` : au clic suivant, retour à `/connexion?retour=%2Fcoureur%2Finscriptions` ; reconnexion de `E5` : arrivée sur `/benevole`, jamais `/acces-refuse` (RG2) | E2E |
| CA2 | Même scénario avec `E6` promu `ADMIN` par `Patron` : arrivée sur `/administration` (RG2) | E2E |
| CA3 | Étant donné `Nadia` (`ADMIN`) ouvrant `/connexion?retour=%2Fadministration%2Fadmins` : après connexion, arrivée sur `/administration`, menu sans entrée Admins (RG2) ; avec `Patron` (`ADMIN_MASTER`) même URL : arrivée sur `/administration/admins` (RG1) | E2E |
| CA4 | Quand `Alice` (coureuse) se connecte depuis `/connexion?retour=%2F%2Fexemple.org`, `?retour=%2F%5Cexemple.org`, `?retour=https%3A%2F%2Fexemple.org` et `?retour=` : arrivée à chaque fois sur `/` (accueil coureur) et l'URL reste sur l'origine de l'application ; avec `Léo` (bénévole) : `/benevole` (RG4) | E2E |
| CA5 | Étant donné `Alice` connectée, un accès direct (saisie de l'URL) à `/administration` mène à `/acces-refuse` ; `Léo` sur `/administration/admins` : `/acces-refuse` ; `Nadia` sur `/administration/admins` : `/acces-refuse` (RG3) | E2E |
| CA6 | Quand `Alice` se connecte depuis `/connexion?retour=%2Fmon-compte` : arrivée sur `/mon-compte` (RG7, RG1) ; avec `Léo` aussi | E2E |
| CA7 | Étant donné un anonyme ouvrant `/administration/admins`, alors redirection vers `/connexion?retour=%2Fadministration%2Fadmins` ; après connexion de `Patron`, arrivée sur `/administration/admins` (RG1, RG3) | E2E |
| CA8 | Quand `Nadia` se connecte depuis `/connexion?retour=%2Fconnexion` puis `?retour=%2Fcreer-compte` : arrivée sur `/administration` ; `Alice` : `/` (RG5) | E2E |
| CA9 | Étant donné un utilisateur déjà connecté dans un autre onglet (réponse `dejaConnecte`), quand il valide `/connexion?retour=%2Fcoureur%2Finscriptions` en étant `Léo` : arrivée sur `/benevole` (RG8, RG6) | E2E |
| CA10 | Après CA1, un clic sur « Précédent » puis rechargement de `/coureur/inscriptions` mène à `/acces-refuse` (indicateur absent, RG3) ; aucune boucle de redirection (la page se stabilise) (RG6) | E2E |

Couverture : RG1 CA3/CA6/CA7, RG2 CA1/CA2/CA3, RG3 CA5/CA7/CA10, RG4 CA4, RG5 CA8, RG6 CA9/CA10, RG7 CA6, RG8 CA9. Écran « Connexion » : CA1 à CA10.

## 7. Tests et simplification des scénarios existants

- Scénarios E2E à écrire : (a) CA1, (b) CA2, (c) CA3, (d) CA4, (e) CA5 + CA6 (non-régression), plus CA7 à CA10.
- (f) **Utile** : dans 9.6b CA29 et 9.7 CA21, la reconnexion peut passer par `/connexion?retour=%2Fcoureur%2Finscriptions` (parcours réel d'un utilisateur dont la session est invalidée) au lieu de `/connexion` sans `retour`, avec le même résultat attendu (espace bénévole / administration). Les autres vérifications de ces CA (affectation, suppression, anonymisation, absence d'Admins) restent inchangées : aucun affaiblissement. CA1 et CA2 ci-dessus peuvent être fusionnés dans ces scénarios si le testeur E2E le préfère, à condition de conserver l'assertion explicite « jamais `/acces-refuse` ».

## 8. Tester à la main (`docker compose up -d --build`, http://localhost)

Comptes : `Patron` (admin master, variables `.env`), puis `Nadia` (admin), `Léo` (bénévole), `Alice` (coureuse) créés via `scripts/donnees-demo.sh` ou l'interface.

1. Créer le coureur `E5` (« Créer un compte »), ouvrir « Mes inscriptions ». Dans un autre onglet, `Nadia` > Bénévoles > recherche `E5` > « Passer en bénévole ». Dans le premier onglet, cliquer : redirection vers `/connexion?retour=%2Fcoureur%2Finscriptions`. Se reconnecter : attendu `/benevole`, pas de page « Accès refusé ».
2. Idem avec `E6` promu admin par `Patron` : attendu `/administration`.
3. Se déconnecter, ouvrir `http://localhost/connexion?retour=%2Fadministration%2Fadmins`, se connecter en `Nadia` : attendu `/administration` (pas d'Admins dans le menu). Avec `Patron` : `/administration/admins`.
4. Ouvrir `http://localhost/connexion?retour=%2F%2Fexemple.org`, se connecter en `Alice` : attendu accueil `/`, jamais `exemple.org`.
5. `Alice` connectée, saisir `http://localhost/administration` : attendu « Accès refusé ». Avec `retour=%2Fmon-compte` : attendu `/mon-compte`.
6. Ouvrir `http://localhost/connexion?retour=%2Fconnexion` et se connecter en `Nadia` : attendu `/administration`.

## 9. Points ouverts

1. Mécanisme exact (état de navigation vs autre) laissé au développeur front ; seul le comportement observable fait foi.
2. RG5 (`/connexion`, `/creer-compte`) : retenu car trivial ; à retirer si le développeur y voit un surcoût.
3. Faut-il à terme aussi traiter le cas d'un `retour` vers une route inexistante (404 front) ? Non spécifié ici, comportement actuel conservé.
