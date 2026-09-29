# Spec Incrément 5 — Comptes « pseudo »

> Projet : Backyard Ultra Tracker
> Date de rédaction : 2026-09-28
> Statut : **validée pour développement, amendée le 2026-09-28 (révision 5).** Aucun point ouvert ne subsiste.
> **Aucune implémentation ne démarre avant le GO de l'incrément 4** (règle 6 du workflow de validation, `CLAUDE.md`).
>
> **Révision 1 (2026-09-28)** : PO1 tranché, **option B : pseudo unique globalement**. Cette décision remplace la demande initiale d'un pseudo « unique par course ».
>
> **Révision 2 (2026-09-28)** : l'utilisateur a tranché PO2 à PO12. Les hypothèses H1 à H8 deviennent des règles. Principales conséquences :
> - HTTP Basic pour les comptes pseudo, API sans état (RG9) ;
> - « Rester connecté » de 24 h glissantes, géré côté client (RG21) ;
> - `Runner.name` remplacé par le pseudo (RG6, RG18) ;
> - journaux conservés 7 jours (RG22) et limitation de débit au reverse proxy nginx (RG23) ;
> - changement de mot de passe par le coureur (RG19) et suppression de compte par l'admin (RG20).
>
> Nouveaux éléments : RG18 à RG23, CL15 à CL21, CA30 à CA41, PO13 à PO17. Aucun élément existant n'est renuméroté ; les CA sont désormais présentés dans l'ordre numérique.
>
> **Révision 3 (2026-09-28)** : l'utilisateur a tranché PO13 à PO17 et validé l'interprétation de RG21. Principales conséquences :
> - la migration V2 purge les noms réels ; tout coureur sans compte s'affiche « Coureur n°{bib} » (RG6, RG18, PO13, PO14) ;
> - le référentiel d'authentification est choisi selon le préfixe d'URL ; les accès croisés passent de 403 à 401 (RG9, RG10, PO15). **Évolution motivée** de CA13, CA18, CA22, CA32 et CA33 ;
> - seuils nginx distincts pour l'inscription publique et pour `/api/account/**` (RG23, PO16) ;
> - écran admin « Comptes » et nouvel endpoint E25 de liste et de recherche (RG17, RG24, PO17). L'inc. 7 décale ses endpoints de E25–E29 à E26–E30.
>
> Nouveaux éléments : RG24, CL22, CA42 à CA44, PO18. Les hypothèses H9 (seuils de l'inscription) et H10 (essai successif) sont retirées ; H11 est créée pour PO18. Aucun élément existant n'est renuméroté.
>
> **Révision 4 (2026-09-28)** : l'utilisateur a tranché PO18 (rafale de 10). H11 est confirmée et devient une règle (RG23, CA40). Les choix de rédaction de la révision 3 sont confirmés et marqués comme tels, à l'endroit où ils sont rédigés : RG6, RG9, RG23, RG24, CA21, PO13, PO15, PO16 et PO17. Aucun nouvel élément.
>
> **Révision 5 — amendement du 2026-09-28**, après le point d'arrêt prévu par RG5 et PO11. Le développeur a constaté que H2 (2.4.240 et 2.2.224, mode PostgreSQL) refuse l'index unique sur `lower(pseudo)` et une colonne générée `STORED`.
> - **Repli décidé par l'utilisateur (2026-09-28)** : le pseudo est **stocké en minuscules**. L'application le normalise avant tout enregistrement et toute recherche, par une fonction unique. La contrainte en base devient un `UNIQUE` classique sur `pseudo`. La casse saisie est perdue : « Lievre » s'affiche « lievre » (RG2, RG5, RG6, RG9, RG24, PO11 ; CA1, CA2, CA6, CA7, CA8, CA10, CA12, CA14, CA15, CA20, CA25, CA26, CA28, CA30, CA37, CA38, CA42, CA43, CA44).
> - **401 anonyme clarifié** : l'alignement du `detail` ne vise que les identifiants **présentés**. Une requête anonyme garde « Authentification requise », sans changement de l'inc. 3 (RG9, RG10, CA13).
> - **Coût BCrypt précisé** : le coût 12 s'applique aux hash **produits** par l'application. Les hash staff de configuration restent vérifiés quel que soit leur coût (RG3, CA4).
> - **Démarche d'adaptation des tests existants** : elle est fixée, avec des critères d'accord (CA21, note N1).
>
> Aucune RG, CL, CA ni PO n'est ajouté ni renuméroté. Seule la note N1, placée après CA21, est ajoutée. L'ancienne rédaction de RG5 (index sur `lower(pseudo)`) est remplacée.
>
> Numérotation RG / CL / CA / PO propre à cet incrément. Les références aux autres specs s'écrivent « RG28 (inc. 3) », « RG7 (inc. 4) », etc.
> Aucune hypothèse ne subsiste : toutes ont été confirmées ou retirées.

**Note RGPD (cadre déjà tranché).** Un pseudo seul ne fait pas sortir l'application du RGPD : l'adresse IP et les horodatages de connexion et de scan restent des données personnelles potentielles. L'objectif de cet incrément est la **minimisation** des données collectées, pas une exemption légale.

---

## 0. Existant et changements

| Sujet | Existant (inc. 1 à 4) | Ce que l'incrément 5 change |
|---|---|---|
| Modèle | `Race`, `Runner` (dont `name` NOT NULL, RG6 inc. 1), `Passage`. Seule migration : `V1__init.sql`. | Nouvelle entité `Account` et lien `Runner.account_id`, par une migration `V2`. `Runner.name` n'est plus alimenté et les noms existants sont purgés : le nom affiché est le pseudo, ou « Coureur n°{bib} » sans compte (RG1, RG4, RG6, RG18). |
| Inscription | E3 `POST /api/public/races/{raceId}/registrations`, corps `{"name"}`, anonyme ; `qrToken` rendu une seule fois (RG15, RG16 inc. 3 ; RG11 à RG13 inc. 4). | E3 crée un compte pseudo et son coureur (RG7). Nouvel endpoint E20 pour inscrire un compte existant à une autre course (RG8). |
| Comptes | Exactement deux comptes en mémoire, ADMIN et SCANNER, définis par `BACKYARD_SECURITY_*`, HTTP Basic, API sans état (RG28 à RG34 inc. 3). | Comptes pseudo en base, rôle `RUNNER` uniquement, **également en HTTP Basic** (RG9). ADMIN et SCANNER inchangés côté serveur. |
| Accès | Matrice RG29 (inc. 3) : `/api/public/**`, `/api/scan/**`, `/api/admin/**`, refus par défaut. | Nouveau préfixe `/api/account/**`, authentifié par les seuls comptes pseudo ; les autres chemins sont authentifiés par les seuls comptes staff (RG9, RG10). Pour ADMIN et SCANNER, les codes des chemins existants sont inchangés. |
| Front | Écrans `/inscription/{raceId}` et `/connexion` ; conservation des identifiants ADMIN/SCANNER selon RG7 (inc. 4). | Inscription pseudo + mot de passe, connexion coureur `/compte/connexion`, « Mes inscriptions » `/compte`, changement de mot de passe, actions admin sur les comptes, dont l'écran `/admin/comptes` (RG17, RG21). |
| Exploitation | HTTPS et HSTS au reverse proxy (RG34 inc. 3). Aucune règle de conservation des journaux, aucune limitation de débit. | Conservation des journaux limitée à 7 jours (RG22) ; limitation de débit nginx versionnée, avec des seuils distincts (RG23). |
| Scan, calculs dérivés, auto-DNF, réintégration | RG inc. 2 et 3. | **Aucun changement.** Le `qrToken` reste la seule clé du scan. |

### Endpoints créés par cet incrément
Numérotation à la suite de l'inc. 4 (E19), reprise par la spec de l'inc. 6. L'inc. 7 poursuit à partir de E26.

| # | Endpoint | Rôle | Règle |
|---|---|---|---|
| E20 | `POST /api/account/races/{raceId}/registrations` | RUNNER | RG8 |
| E21 | `GET /api/account/me` | RUNNER | RG11 |
| E22 | `PUT /api/admin/accounts/{accountId}/password` | ADMIN | RG14 |
| E23 | `PUT /api/account/password` | RUNNER | RG19 |
| E24 | `DELETE /api/admin/accounts/{accountId}` | ADMIN | RG20 |
| E25 | `GET /api/admin/accounts?pseudo={recherche}` | ADMIN | RG24 |

---

## 1. Périmètre

### Inclus
- Entité `Account` (pseudo + hash BCrypt, rien d'autre), lien avec `Runner` et migration `V2`.
- Remplacement du nom du coureur par le pseudo de son compte (RG18).
- Inscription publique avec création de compte obligatoire ; inscription d'un compte existant à une autre course.
- Pseudo unique globalement, stocké en minuscules : l'unicité ne tient donc pas compte de la casse saisie (révision 5).
- Authentification d'un compte pseudo par pseudo + mot de passe, en HTTP Basic.
- Consultation par le coureur de ses propres inscriptions et de son QR code.
- Changement de mot de passe par le coureur ; liste, recherche par pseudo, réinitialisation et suppression d'un compte par l'admin.
- Purge des noms réels des coureurs existants par la migration V2.
- Conservation des identifiants coureur dans le navigateur, avec expiration de 24 h glissantes.
- Règles de minimisation (réponses API, journaux), durée de conservation des journaux, limitation de débit au reverse proxy (configurations versionnées).
- Écrans PWA correspondants et tests E2E associés.

### Explicitement exclu
- Email, téléphone, nom réel, date de naissance ou toute autre donnée sur le compte.
- Procédure automatisée de mot de passe oublié (aucun canal de contact).
- Changement de mot de passe imposé après une réinitialisation par l'admin (PO7).
- Modification du pseudo.
- Conservation de la casse saisie du pseudo : il est stocké et affiché en minuscules (RG2, révision 5).
- Inscription publique sans compte (PO6).
- Toute modification des comptes ADMIN et SCANNER côté serveur, et de leur mécanisme (HTTP Basic).
- Verrouillage de compte ou limitation de débit dans l'application (PO9).
- Troncature ou pseudonymisation des adresses IP dans les journaux (PO5).
- Pagination de la liste des comptes (RG24).
- Suppression de la colonne `runner.name` : elle est vidée par V2, puis n'est plus écrite (RG6).
- Journaux système du VPS et de l'hébergeur, hors application et reverse proxy (RG22).
- Séparation admin / public : **incrément 6**. Rôle scanneur : **incrément 7**.
- Toute modification des règles de course (calculs dérivés, auto-DNF, DNF manuel, réintégration, scan).

### Dépendances avec les incréments 6 et 7
- **Même mécanisme partout** : HTTP Basic, API sans état, pour les comptes pseudo (cet incrément), ADMIN et SCANNER (inc. 3) et les scanneurs déclarés (inc. 7).
- **Même durée** : « Rester connecté » de 24 h glissantes pour les comptes pseudo (RG21) et pour les comptes de rôle SCANNER (inc. 7).
- **Même conservation des journaux** : 7 jours (RG22), reprise par l'inc. 7.
- **Incrément 6** : les routes `/compte/**` (RG21) sont des routes publiques ; elles ne contiennent aucun lien vers l'espace admin.
- **Incrément 7** : les comptes scanneurs sont une entité distincte d'`Account`, qui ne porte aucun rôle (RG1). Ils rejoignent le référentiel **staff** de RG10, avec les comptes de configuration. Un pseudo peut donc être homonyme d'un identifiant scanneur : le préfixe d'URL lève l'ambiguïté (PO15). Les endpoints de l'inc. 7 sont numérotés à partir de E26.
- **CLAUDE.md** : mis à jour par l'orchestrateur après validation des trois specs (PO10).

---

## 2. Règles de gestion

### A. Modèle et persistance

**RG1 — Entité Account minimale**
`Account` a exactement trois attributs persistés : `id` (BIGINT auto-généré), `pseudo`, `password_hash`. Aucun autre attribut : ni email, ni téléphone, ni nom réel, ni date de création ou de dernière connexion, ni adresse IP, ni rôle, ni indicateur de changement de mot de passe.

**RG2 — Format et normalisation du pseudo** *(PO11 tranché ; amendée en révision 5 : repli « stocké en minuscules », décision de l'utilisateur du 2026-09-28)*
- **Format de la saisie** : espaces de début et de fin supprimés, puis 3 à 30 caractères parmi `A-Z`, `a-z`, `0-9`, `_`, `-` (motif `^[A-Za-z0-9_-]{3,30}$`). Les espaces internes sont donc interdits. Les majuscules restent **acceptées à la saisie**.
- **Normalisation** : `normaliser(saisie) = saisie sans espaces de début et de fin, puis mise en minuscules` (`toLowerCase(Locale.ROOT)`). Pour un pseudo valide, le résultat respecte `^[a-z0-9_-]{3,30}$`.
- **Le pseudo est stocké normalisé.** La casse saisie est perdue : `Lievre`, `LIEVRE` et `  lievre ` donnent le pseudo `lievre`. Partout où le pseudo apparaît (réponses API, nom affiché RG18, écrans, confirmations), c'est la valeur stockée, en minuscules. *(Conséquence acceptée par l'utilisateur, 2026-09-28.)*
- **La normalisation est appliquée avant tout enregistrement et avant toute recherche d'un pseudo** :
  - création de compte (E3, RG7), avant le contrôle d'unicité (RG5) ;
  - authentification d'un compte pseudo (RG9) : le pseudo présenté en HTTP Basic est normalisé, puis recherché à l'identique ;
  - recherche de l'admin (E25, RG24) : la valeur du filtre est normalisée, puis comparée aux pseudos stockés.
- Les comparaisons se font ensuite **à l'identique** sur des valeurs normalisées. « Lievre » et « lievre » sont donc le même pseudo.
- **Un seul endroit** : la normalisation est une fonction pure du domaine, définie **une seule fois** et appelée par tous les usages ci-dessus. Aucun autre code (controller, repository, requête SQL, front) ne met un pseudo en minuscules ni ne compare des pseudos sans tenir compte de la casse. C'est l'application de la règle « aucune duplication de règle métier » (`CLAUDE.md`).
- Le front n'applique pas la normalisation : il envoie la saisie telle quelle, et affiche le pseudo renvoyé par l'API.
- La normalisation ne s'applique qu'aux comptes pseudo. Les noms des comptes staff (inc. 3, inc. 7) suivent leurs propres règles, inchangées.
- Le pseudo n'est pas modifiable.
- Échec de format : 400 `VALIDATION_FAILED` sur le champ `pseudo` (RG8 inc. 3). Le format est contrôlé sur la saisie privée de ses espaces de bord, avant la mise en minuscules.

**RG3 — Mot de passe** *(PO11 tranché)*
- Longueur : 8 caractères minimum et 72 octets UTF-8 maximum (limite de BCrypt, au-delà de laquelle la fin du mot de passe serait ignorée). Aucun nettoyage d'espaces.
- Stockage : uniquement le hash, produit par le `PasswordEncoder` BCrypt existant (RG33 inc. 3), coût 12.
- **Portée du coût 12** *(précisée en révision 5)* :
  - le coût 12 s'applique à **tout hash produit par l'application**, c'est-à-dire aux mots de passe de compte pseudo : création (E3), réinitialisation (E22), changement (E23) ;
  - il ne s'applique **pas** aux hash staff de configuration (`BACKYARD_SECURITY_*_PASSWORD_HASH`, RG32 inc. 3). L'application ne les produit pas : elle les reçoit. Ils restent vérifiés quel que soit leur coût, puisque BCrypt lit le coût dans le hash. Le contrôle de format au démarrage (RG32 inc. 3) est inchangé ; aucun coût minimal n'est imposé. Le coût 12 reste une **recommandation** de production pour ces hash (RG32 inc. 3), et les hash de test en coût 4 (`$2a$04$`) restent valables ;
  - mise en œuvre : le bean `PasswordEncoder` existant passe du coût par défaut (10) au coût 12. Il reste **unique** et sert à la fois à produire les hash de compte pseudo et à vérifier tous les hash, staff comme pseudo. Le coût est défini à un seul endroit ; aucun second encodeur n'est créé.
- Le mot de passe et son hash ne figurent dans **aucune** réponse de l'API et dans aucun journal.
- Échec de format : 400 `VALIDATION_FAILED` sur le champ du mot de passe.
- Cette règle s'applique à toute saisie de mot de passe de compte pseudo : création (RG7), changement par le coureur (RG19), réinitialisation par l'admin (RG14).

**RG4 — Lien Account / Runner**
- `Runner.account_id` : clé étrangère vers `account`, **nullable** (coureurs antérieurs à l'incrément et coureurs détachés), `ON DELETE RESTRICT`.
- Un compte a **au plus un coureur par course** (contrainte d'unicité en base sur `(race_id, account_id)`), et peut avoir des coureurs dans plusieurs courses.
- Le lien n'est jamais modifié après la création du coureur, **sauf** son passage à `null` lors de la suppression du compte (RG20). Un coureur n'est jamais rattaché à un autre compte.

**RG5 — Unicité globale du pseudo** *(PO1 et PO11 tranchés ; amendée en révision 5 : repli décidé par l'utilisateur le 2026-09-28)*
- Deux comptes ne peuvent pas avoir le même pseudo **normalisé** (RG2), toutes courses confondues. L'unicité porte sur `Account`, et non sur les coureurs d'une course.
- L'unicité par course en découle, combinée à RG4. Aucune copie du pseudo n'est faite côté `runner`.
- La règle tient aussi sous créations simultanées. Elle est garantie en base par une **contrainte `UNIQUE` classique sur `account.pseudo`**, qui fonctionne sur PostgreSQL comme sur H2 en mode PostgreSQL. Comme tout pseudo est stocké normalisé (RG2), cette contrainte exacte suffit à rendre l'unicité insensible à la casse saisie.
- **Repli retenu** : l'index unique sur `lower(pseudo)` et la colonne générée `STORED` sont abandonnés, car H2 (2.4.240 et 2.2.224, mode PostgreSQL) les refuse (constat du développeur, 2026-09-28). Aucune autre variante (index fonctionnel propre à PostgreSQL, contrainte `CHECK`, colonne supplémentaire) n'est demandée. Le compte garde exactement 3 colonnes (RG1).
- **Limite assumée** : la base garantit l'unicité exacte ; l'insensibilité à la casse repose sur la normalisation applicative (RG2), faite à un seul endroit. Seule l'application écrit dans `account` : aucune écriture SQL manuelle n'est prévue.
- Violation : 409 `BUSINESS_CONFLICT`, `detail` « Pseudo déjà utilisé : {pseudo}. Si c'est votre compte, connectez-vous pour vous inscrire avec. », où `{pseudo}` est le pseudo **normalisé**. Ou 409 `DATA_INTEGRITY` si c'est la contrainte en base qui rejette (RG6 inc. 3).
- Conséquence assumée : ce refus révèle qu'un pseudo existe. La réponse n'indique ni la course ni le compte.
- Un pseudo libéré par la suppression de son compte (RG20) peut être repris par un nouveau compte.

**RG6 — Migration V2** *(PO4 tranché ; PO13 TRANCHÉ (utilisateur, 2026-09-28) : purge)*
Une migration `V2__account.sql` :
- crée la table `account` avec une contrainte `UNIQUE` sur `pseudo` (RG5, révision 5) ;
- ajoute `runner.account_id` (nullable, clé étrangère `ON DELETE RESTRICT`) et la contrainte d'unicité `(race_id, account_id)` ;
- rend `runner.name` **nullable**, puisque les coureurs créés à partir de cet incrément n'ont plus de nom propre (RG18) ;
- **purge les noms réels** : `runner.name` est mis à `null` pour **tous** les coureurs existants, quel que soit le statut de leur course (`SETUP`, `RUNNING` ou `FINISHED`).

`V1__init.sql` n'est pas modifiée. Les coureurs existants gardent `account_id = null` ; leurs passages, dossards, statuts, `dnf_reason`, `dnf_yard` et `qrToken` ne sont pas touchés.
- La colonne `runner.name` est conservée, vide, et n'est plus jamais écrite (RG18). Sa suppression n'est pas demandée par cet incrément. *(Choix confirmé (utilisateur, 2026-09-28).)*
- **Conséquence assumée (PO13)** : la purge est irréversible. Les tableaux de bord des courses passées affichent « Coureur n°{bib} » à la place des noms.

**RG18 — Nom affiché d'un coureur** *(PO4 tranché : `Runner.name` remplacé par le pseudo ; PO13 et PO14 TRANCHÉS (utilisateur, 2026-09-28) : libellé neutre)*
- Le nom affiché d'un coureur lié à un compte est le **pseudo de son compte**, tel que stocké, donc en minuscules (RG2), lu au moment de la réponse. Il n'est jamais recopié dans `runner.name`, qui reste `null` pour tout coureur.
- Les réponses qui exposaient `name` le conservent, avec pour valeur le nom affiché : E4 et E5 (public), E13 à E18 (`AdminRunnerResponse`), E3 et E20.
- Nom affiché d'un coureur **sans compte** : **« Coureur n°{bib} »**, où `{bib}` est le dossard actuel en décimal, sans zéro de tête (exemple : `Coureur n°1`, `Coureur n°12`). La règle est la même pour :
  - un coureur antérieur à l'incrément, dont le nom a été purgé par V2 (PO13) ;
  - un coureur détaché par la suppression de son compte (RG20, PO14) : aucune copie du pseudo n'est conservée.
- Si le dossard d'un coureur sans compte change (E15, course `SETUP`), le libellé suit le nouveau dossard.
- Aucun pseudo ne peut être égal à ce libellé, puisque RG2 interdit l'espace et le caractère `°` : un coureur sans compte ne peut pas être confondu avec un compte.
- E15 (RG18 inc. 3) ne modifie plus le nom : la requête ne porte plus que `bib`, et une propriété `name` éventuellement présente est ignorée (propriétés inconnues ignorées, RG18 inc. 3). Le formulaire admin de modification d'un coureur ne propose plus le nom.
- Le calcul du nom affiché est défini **à un seul endroit** du code serveur.

### B. Inscription

**RG7 — Inscription publique avec création de compte (E3 modifié)** *(PO6 tranché : compte obligatoire)*
`POST /api/public/races/{raceId}/registrations`, corps `{"pseudo", "password"}`, anonyme. Ordre des contrôles :
1. format (RG2, RG3) : 400, service non appelé ;
2. course introuvable : 404 ;
3. inscriptions fermées (RG14 inc. 3) : 409 ;
4. pseudo normalisé (RG2) déjà porté par un compte, quelle que soit la course (RG5) : 409. Aucun compte n'est lié ni créé, **même si le mot de passe fourni est celui du compte existant**. E3 ne crée que des comptes nouveaux ; un compte existant s'inscrit par RG8 (PO12) ;
5. création du compte avec le pseudo normalisé (RG2, RG3), puis du coureur : dossard et `qrToken` selon RG15 et RG16 (inc. 3), `account_id` = le nouveau compte, `name` = `null` (RG18).

Compte et coureur sont créés dans la même transaction : un échec n'en laisse aucun des deux. Réponse 201, identique à RG15 (inc. 3), plus le champ `pseudo` (valeur stockée, normalisée). L'ancien corps `{"name"}` seul est refusé en 400. Il n'existe plus d'inscription publique sans compte.

**RG8 — Inscription d'un compte existant à une autre course (E20)** *(PO12 tranché : connexion, puis inscription)*
Une personne qui a déjà un compte s'inscrit à une autre course **avec ce compte**, sans en créer un second, en deux temps : elle se connecte (RG21), puis demande l'inscription.
- Endpoint : `POST /api/account/races/{raceId}/registrations`, sans corps, par un compte pseudo authentifié (RG9).
- Ordre des contrôles :
  1. 404 si la course est introuvable ;
  2. 409 si les inscriptions sont fermées ;
  3. 409 « Déjà inscrit à cette course » si ce compte a déjà un coureur dans cette course (RG4).
- Sinon, création du coureur comme à l'étape 5 de RG7, lié au compte authentifié.
- Aucun contrôle de pseudo n'est nécessaire : le pseudo est celui du compte, unique globalement (RG5).
- Réponse 201, même corps que RG7.

### C. Authentification et accès

**RG9 — Authentification d'un compte pseudo** *(PO2 tranché : HTTP Basic)*
- Mécanisme : **HTTP Basic**, comme les comptes ADMIN et SCANNER (RG28 inc. 3). L'API reste **sans état** (RG31 inc. 3) : ni session, ni cookie, ni jeton ; la protection CSRF reste désactivée pour la même raison. Aucun endpoint de connexion dédié : la PWA valide les identifiants par E21 (RG21).
- Identifiants présentés : **pseudo + mot de passe**, sans course. Le pseudo présenté est normalisé (RG2), puis recherché à l'identique : il désigne au plus un compte. `LIEVRE` et `Lievre` désignent le compte `lievre`. Le mot de passe, lui, n'est ni normalisé ni nettoyé ; il est vérifié contre le hash BCrypt de ce compte.
- Chaque requête est vérifiée contre le hash en base au moment où elle est traitée : un changement (RG19), une réinitialisation (RG14) ou une suppression (RG20) prend effet dès la requête suivante.
- Échec (compte inconnu ou mot de passe faux) : 401 `UNAUTHENTICATED`, avec le `detail` que l'inc. 3 produit déjà pour des identifiants faux, « Identifiants invalides ». Il est identique dans les deux cas (RG6 inc. 3) et sans `WWW-Authenticate` (RG30 inc. 3). Cet incrément ne crée aucun nouveau texte de 401.
- Un compte pseudo authentifié a **uniquement** le rôle `RUNNER` (autorité `ROLE_RUNNER`). Il n'obtient jamais `ADMIN` ni `SCANNER`, même si son pseudo est égal au nom d'un compte ADMIN ou SCANNER.
- **Référentiel choisi selon le préfixe d'URL** *(PO15 TRANCHÉ (utilisateur, 2026-09-28))* :
  - sur `/api/account/**`, les identifiants sont vérifiés **uniquement** contre les comptes pseudo ;
  - sur tout autre chemin (`/api/scan/**`, `/api/admin/**`, mais aussi `/api/public/**` et les URL inconnues), ils sont vérifiés **uniquement** contre les comptes **staff** : comptes de configuration ADMIN et SCANNER (inc. 3), puis scanneurs déclarés (inc. 7).
  - Un nom présent dans les deux référentiels ne crée donc aucune ambiguïté : le chemin décide. Un échec d'authentification coûte une seule vérification BCrypt.
  - Des identifiants valides dans un référentiel, présentés sur un chemin de l'autre, sont **inconnus** de ce dernier : 401 `UNAUTHENTICATED`, avec le même `detail` que des identifiants faux, « Identifiants invalides » (voir RG10). Une requête **anonyme** n'est pas concernée : elle garde le `detail` de l'inc. 3, « Authentification requise » (RG10, révision 5).
  - *(Choix confirmé (utilisateur, 2026-09-28).)* Le comportement de `/api/public/**` et des URL inconnues découle de RG30 (inc. 3, PO21), inchangée : des identifiants présentés mais invalides pour le référentiel du chemin donnent 401, y compris sur `/api/public/**`. La PWA n'envoie jamais d'identifiants coureur sur ces chemins (RG21).
- Les comptes ADMIN et SCANNER restent régis par RG28 à RG33 (inc. 3).

**RG10 — Matrice d'accès étendue** *(PO15 TRANCHÉ (utilisateur, 2026-09-28) : référentiel choisi par le préfixe)*
Ajout d'une ligne à RG29 (inc. 3) et d'une colonne RUNNER. Les colonnes Anonyme, SCANNER et ADMIN des chemins existants sont inchangées.

| Chemin | Référentiel (RG9) | Anonyme | RUNNER | SCANNER | ADMIN |
|---|---|---|---|---|---|
| `/api/account/**` (E20, E21, E23) | comptes pseudo | 401 | autorisé | 401 | 401 |
| `/api/public/**` | staff | autorisé | 401 | autorisé | autorisé |
| `/api/scan/**` | staff | 401 | 401 | autorisé | autorisé |
| `/api/admin/**` (dont E22, E24, E25) | staff | 401 | 401 | 403 | autorisé |
| toute autre URL | staff | 401 | 401 | 403 | 403 |

- **`detail` des cases 401** *(clarifié en révision 5, 2026-09-28)* :
  - colonnes RUNNER, SCANNER et ADMIN (des identifiants sont **présentés**) : même `detail` que des identifiants faux, « Identifiants invalides » (RG9) ;
  - colonne **Anonyme** (aucun identifiant, ou en-tête `Authorization` d'un autre schéma que `Basic`, RG30 inc. 3) : « Authentification requise », **inchangé** par rapport à l'inc. 3 (RG6, RG30 inc. 3) ;
  - toutes les cases 401 sont sans `WWW-Authenticate` (RG30 inc. 3).
- **Motif de cette lecture** : l'alignement du `detail` sert à ne pas révéler que des identifiants présentés sont valides dans l'autre référentiel (PO15). Une requête anonyme ne présente rien : il n'y a aucune information à masquer. La distinction entre « Authentification requise » et « Identifiants invalides » existe déjà depuis l'inc. 3 et ne révèle aucun compte. Enfin, la PWA ne s'appuie pas sur le `detail` d'un 401 (RG21, RG6 inc. 4). Aligner l'anonyme modifierait l'inc. 3 et ses tests sans aucun besoin : ce n'est pas demandé.
- **Évolution motivée (PO15)** : sous la rédaction précédente (hypothèse H10, retirée), les cases croisées valaient 403 (RUNNER sur `/api/scan/**` et `/api/admin/**` ; SCANNER et ADMIN sur `/api/account/**`). Elles valent désormais 401, parce que ces identifiants sont inconnus du référentiel du chemin. CA13, CA18, CA22, CA32, CA33 et CA42 portent ces valeurs.
- RUNNER sur `/api/public/**` et sur les URL inconnues donne aussi 401, pour la même raison (RG9). C'est sans effet sur l'usage : la PWA n'envoie jamais d'identifiants coureur hors de `/api/account/**` (RG21).
- La mise en œuvre attendue repose sur deux chaînes de sécurité distinguées par le chemin, chacune avec son propre référentiel. Les règles d'autorisation restent dans la configuration de sécurité, et non dans les controllers (RG1 inc. 3).

Un compte pseudo n'accède qu'à **ses propres** données : aucun endpoint `/api/account/**` ne prend l'identifiant d'un compte ou d'un coureur en paramètre pour lire ou modifier ses données.

**RG11 — Mes inscriptions (E21)** *(PO6 tranché : le compte sert à réafficher ses inscriptions et son QR)*
`GET /api/account/me` : `pseudo` et la liste des coureurs du compte, triée par `raceDate` croissante puis `raceId`, chacun avec `raceId`, `raceName`, `raceDate`, `raceStatus`, `runnerId`, `bib`, `status`, `qrToken`. Permet de réafficher son QR code, ce que l'incrément 4 ne permettait pas (RG12 inc. 4). C'est la seule réponse, avec celles de E3 (RG7) et de E20 (RG8), qui expose un `qrToken` au coureur ; RG16 (inc. 3) est étendue en ce sens. Cet endpoint sert aussi à valider les identifiants à la connexion (RG21).

**RG19 — Changement de mot de passe par le coureur (E23)** *(PO7 tranché)*
`PUT /api/account/password`, corps `{"newPassword"}`, par un compte pseudo authentifié. Le mot de passe actuel est celui présenté dans l'en-tête `Authorization` : il n'est pas redemandé dans le corps.
- 400 si `newPassword` ne respecte pas RG3 ; sinon le hash du compte authentifié est remplacé, réponse 204 sans corps.
- Dès la réponse, l'ancien mot de passe est refusé (401) sur tous les appareils (RG9).
- Journalisé au niveau INFO : « mot de passe modifié par le titulaire du compte {accountId} », sans pseudo, sans mot de passe, sans hash.

### D. Administration

**RG12 — Compte visible par l'admin**
Les réponses admin sur les coureurs (`AdminRunnerResponse`, E13 à E18) gagnent `accountId` (Long|null) et `pseudo` (String|null). Ils valent `null` pour un coureur sans compte. `name` porte le nom affiché (RG18).

**RG13 — Aucune corrélation technique publique entre courses** *(PO4 tranché)*
Aucune réponse `/api/public/**` n'expose `accountId`, ni aucun identifiant technique commun aux coureurs d'un même compte dans des courses différentes.
Conséquence **assumée** de PO1 et PO4 : le nom affiché publiquement étant le pseudo, unique globalement, le même nom dans deux courses désigne le même compte. L'historique public des courses d'un compte est donc reconstituable par son pseudo.

**RG14 — Réinitialisation du mot de passe par l'admin (E22)** *(PO7 tranché)*
`PUT /api/admin/accounts/{accountId}/password`, corps `{"newPassword"}`, rôle ADMIN. L'admin **saisit** le mot de passe provisoire et le transmet de vive voix à la personne.
- 400 si `newPassword` ne respecte pas RG3 ; 404 si le compte n'existe pas ;
- sinon le hash est remplacé. Réponse 204 sans corps ;
- dès la réponse, l'ancien mot de passe est refusé (401) sur tous les appareils, y compris ceux qui l'avaient mémorisé (RG9, RG21) ;
- **aucune obligation de changement** ensuite : le mot de passe provisoire reste valable tant que le coureur ne le change pas (RG19) ;
- journalisé au niveau INFO : « mot de passe réinitialisé pour le compte {accountId} », sans mot de passe, sans hash.
La vérification de l'identité de la personne qui demande la réinitialisation est organisationnelle (par exemple, en présentiel avec son dossard) et n'est pas outillée.

**RG15 — Aucune procédure automatisée**
Aucun endpoint public de mot de passe oublié, de question secrète ou d'envoi de lien. Les seules voies de modification d'un mot de passe sont RG14 (admin) et RG19 (titulaire authentifié).

**RG20 — Suppression d'un compte par l'admin (E24)** *(PO8 tranché)*
`DELETE /api/admin/accounts/{accountId}`, rôle ADMIN.
- 404 si le compte n'existe pas.
- Sinon, dans une seule transaction :
  1. tous les coureurs du compte sont **détachés** : `account_id = null` ;
  2. le compte est supprimé.
- Les coureurs détachés gardent leur dossard, leur statut, `dnf_reason`, `dnf_yard`, leur `qrToken` et **tous leurs passages**. La suppression est possible quel que soit le statut des courses concernées ; un coureur `ACTIVE` d'une course `RUNNING` continue de courir et d'être scanné.
- Réponse 204 sans corps. Dès la réponse, les identifiants du compte sont refusés (401).
- Le pseudo est libéré (RG5). Un nouveau compte qui le reprend n'a aucun lien avec les coureurs détachés.
- Nom affiché des coureurs détachés : « Coureur n°{bib} » (RG18, PO14 tranché). `runner.name` reste `null` : le pseudo n'est copié nulle part.
- **Conséquence assumée (PO14)** : le classement historique des courses concernées devient anonyme pour ces coureurs.
- Journalisé au niveau INFO : « compte {accountId} supprimé, {n} coureurs détachés », sans pseudo.
- La clé étrangère reste `ON DELETE RESTRICT` : une suppression qui n'aurait pas détaché les coureurs échoue.

**RG24 — Liste et recherche des comptes par l'admin (E25)** *(PO17 TRANCHÉ (utilisateur, 2026-09-28) : écran admin « Comptes »)*
`GET /api/admin/accounts`, paramètre de requête facultatif `pseudo`, rôle ADMIN.
- **Filtre** :
  - la valeur de `pseudo` est normalisée par la fonction de RG2 (espaces de bord retirés, puis minuscules) ;
  - sans paramètre, ou si la valeur normalisée est vide, tous les comptes sont renvoyés ;
  - sinon, seuls les comptes dont le pseudo stocké **contient** la valeur normalisée sont renvoyés. La comparaison est exacte sur des valeurs en minuscules : la recherche est donc insensible à la casse saisie, sans aucune fonction `lower`/`upper` ni `ILIKE` côté base (RG2, un seul endroit) ;
  - la correspondance est **littérale** : `_`, `%` et `\` n'ont aucun rôle de joker ;
  - aucune validation de format : une valeur qui ne peut correspondre à aucun pseudo donne une liste vide.
- **Tri** : par pseudo stocké (en minuscules, RG2), puis par `accountId` croissant. Deux pseudos ne pouvant être égaux (RG5), le second critère ne départage en pratique jamais ; il reste la règle écrite.
- **Élément** : `accountId`, `pseudo`, `runnerCount`, qui est le nombre de coureurs liés au compte, toutes courses et tous statuts confondus (0 possible). Aucun mot de passe ni hash (RG3).
- **Réponse** : 200, liste éventuellement vide, sans pagination.
- *(Choix confirmé (utilisateur, 2026-09-28) : recherche « contient », insensible à la casse, littérale, sans pagination. Révision 5 : l'insensibilité à la casse est obtenue par la normalisation de RG2.)*
- **Accès** : RG10 (anonyme 401, RUNNER 401, SCANNER 403, ADMIN autorisé).
- Cet endpoint permet d'atteindre un compte qui n'a plus aucune inscription (CL21), pour le réinitialiser (E22) ou le supprimer (E24).

### E. Minimisation et exploitation

**RG16 — Minimisation dans les données et les journaux** *(H7 validée, PO11)*
- Aucune adresse IP ni aucun horodatage de connexion n'est stocké en base.
- Aucun journal applicatif ne contient : mot de passe, hash, en-tête `Authorization`. Un échec d'authentification est journalisé au niveau WARN **sans le pseudo présenté**.
- Conservation des journaux : RG22.

**RG22 — Conservation des journaux : 7 jours** *(PO5 tranché)*
- **Périmètre** : les journaux qui peuvent contenir des adresses IP et des horodatages de connexion ou de scan :
  - journaux de l'application Spring Boot ;
  - journaux d'accès et d'erreur du reverse proxy nginx.
- Les journaux système du VPS et ceux de l'hébergeur ne relèvent pas de l'outil ; ils sont hors périmètre.
- **Durée** : à tout instant, les fichiers conservés couvrent **au plus 7 jours calendaires** : le jour courant et les 6 jours précédents. Au-delà, ils sont supprimés, sans archivage.
- Les adresses IP figurent en clair dans les journaux nginx : aucune troncature n'est demandée, la durée de conservation est la mesure de minimisation.
- **Rotation, qui la fait** :
  - **application** : la rotation est faite par l'application elle-même (Logback via Spring Boot), configurée dans `application-prod.properties` : fichier de journal, rotation quotidienne, 6 archives au plus. Si l'application est lancée par systemd, la sortie console capturée par journald est soumise à la même limite, par un fichier de configuration journald versionné ;
  - **nginx** : la rotation est faite par logrotate, avec un fichier de configuration versionné (rotation quotidienne, 6 archives au plus, sans archive au-delà) ;
  - les fichiers de configuration sont versionnés dans le dépôt, sous `deploy/`, par le développeur ; leur installation sur le VPS est faite par l'exploitant (l'utilisateur).

**RG23 — Limitation des tentatives au reverse proxy** *(PO9 tranché ; PO16 TRANCHÉ (utilisateur, 2026-09-28) : seuils distincts ; PO18 TRANCHÉ (utilisateur, 2026-09-28) : rafale de 10 pour `/api/account/**`)*
- La limitation est faite par **nginx** (`limit_req`), clé = adresse IP du client (`$binary_remote_addr`), avec `limit_req_status 429`. **Rien n'est codé dans l'application**, qui ne compte aucune tentative.
- Deux zones **distinctes** : l'épuisement de l'une n'affecte jamais l'autre.

| Zone | Chemin limité | Débit par IP | Rafale | Mode |
|---|---|---|---|---|
| inscription | `POST /api/public/races/{raceId}/registrations` (E3) | 10 requêtes par minute (`rate=10r/m`) | 5 (`burst=5`) | `nodelay` |
| compte | `/api/account/**` (E20, E21, E23) | 30 requêtes par minute (`rate=30r/m`) | 10 (`burst=10`, PO18) | `nodelay` |

- Le débit de 30 par minute pour `/api/account/**` tient compte de plusieurs coureurs derrière la même IP (Wi-Fi du site, réseau mobile partagé).
- Le mode `nodelay` s'applique aux deux zones : une requête en excès est refusée au lieu d'être retardée *(choix confirmé (utilisateur, 2026-09-28))*.
- **Effet attendu** (sémantique de `limit_req` avec `nodelay`), depuis un état sans requête récente de la même IP :
  - inscription : 6 requêtes simultanées passent (1 + rafale), la 7e reçoit 429 ; ensuite, une requête de plus toutes les 6 s ;
  - compte : 11 requêtes simultanées passent (1 + rafale), la 12e reçoit 429 ; ensuite, une requête de plus toutes les 2 s.
- Aucun autre chemin n'est limité par cet incrément. L'inc. 7 ajoute E19 (sa RG15).
- La configuration nginx du site est versionnée sous `deploy/nginx/`.
- Côté PWA, une réponse 429 sur ces chemins, quel que soit son corps (le corps nginx n'est pas un `ProblemDetail`), affiche « Trop de tentatives. Réessayez dans une minute. », sans nouvel essai automatique. Cela complète la classification de RG3 (inc. 4) pour le statut 429.

### F. Front (PWA)

**RG17 — Écrans**
- *(Révision 5)* Dans tous les écrans, `{pseudo}` est la valeur renvoyée par l'API, donc en minuscules (RG2), même si l'utilisateur l'a saisi avec des majuscules : connexion, « Vous êtes connecté en tant que {pseudo} », « Mes inscriptions », colonne « Pseudo », écran « Comptes », confirmation de suppression. Les champs de saisie du pseudo acceptent les majuscules et envoient la saisie telle quelle.
- `/inscription/{raceId}` : champs « Pseudo », « Mot de passe », « Confirmer le mot de passe », avec la mention « N'utilisez pas votre nom réel ni un pseudo qui permet de vous identifier. Sans email, un mot de passe oublié ne peut être réinitialisé que par l'organisateur. » Validation de format miroir de RG2 et RG3 (RG1 inc. 4) ; les deux mots de passe doivent être égaux, sinon aucune requête. Confirmation inchangée (RG12 inc. 4), sauf l'avertissement, qui devient : « Vous pourrez réafficher ce QR code depuis Mes inscriptions. »
- La même page propose un lien « J'ai déjà un compte » :
  - il mène à `/compte/connexion`, puis revient à la page d'inscription de la course ;
  - une fois connecté, le formulaire est remplacé par « Vous êtes connecté en tant que {pseudo} » et un bouton « M'inscrire à cette course », qui envoie une seule requête E20 ;
  - en cas de 409 « Pseudo déjà utilisé » sur E3, le `detail` est affiché avec ce même lien.
- `/compte` (« Mes inscriptions », RG11) : QR code de chaque inscription, généré localement ; formulaire « Changer mon mot de passe » (nouveau mot de passe et confirmation, validation miroir de RG3, une seule requête E23) ; bouton « Se déconnecter » (RG21).
- Admin, liste des coureurs : colonne « Pseudo » ; le nom affiché remplace le nom saisi ; le formulaire de modification ne propose plus que le dossard (RG18). Pour un coureur lié à un compte :
  - « Réinitialiser le mot de passe » : confirmation, saisie du mot de passe provisoire, une seule requête E22 ;
  - « Supprimer le compte » : confirmation « Supprimer le compte {pseudo} ? Ses {n} inscriptions et leurs passages sont conservés, sans compte. Action irréversible. », une seule requête E24.
  - Aucune de ces actions n'est rejouée automatiquement (RG43 inc. 4).
- Admin, écran **« Comptes »** `/admin/comptes` *(PO17 tranché)* :
  - accessible par un lien « Comptes » de l'accueil admin `/admin` ;
  - à l'ouverture, une requête E25 sans filtre ; un champ « Rechercher un pseudo » et un bouton « Rechercher » (ou la touche Entrée) envoient **une seule** requête E25 avec `pseudo` ;
  - chaque ligne affiche le pseudo et le nombre d'inscriptions (`runnerCount`) ; une liste vide affiche « Aucun compte » ;
  - actions par ligne : « Réinitialiser le mot de passe » et « Supprimer le compte », avec les mêmes confirmations et la même requête unique (E22, E24) que depuis la liste des coureurs ; après une suppression réussie, la liste est rechargée par E25 avec le même filtre ;
  - l'écran fait partie des écrans admin : même garde que `/admin/**` (RG36 inc. 4, puis RG3 inc. 6), et route ajoutée à la liste fermée des routes (RG5 inc. 4).

**RG21 — Connexion coureur et conservation de ses identifiants** *(PO2 et PO3 tranchés : HTTP Basic, 24 h glissantes ; interprétation ci-dessous validée telle quelle par l'utilisateur le 2026-09-28 : « Rester connecté » décoché par défaut, activité = réponse 2xx)*
- **Connexion** : l'écran `/compte/connexion` demande le pseudo et le mot de passe, puis appelle E21 avec `Authorization: Basic base64(pseudo:motdepasse)` :
  - 200 : connexion réussie, retour à la page d'origine (inscription d'une course) ou à `/compte` ;
  - 401 : « Identifiants invalides », rien n'est conservé ;
  - 429 : message de RG23 ;
  - erreur transitoire : « Connexion impossible : serveur injoignable », rien n'est conservé.
- **Conservation** : mêmes principes que RG7 (inc. 4) pour le compte SCANNER.
  - Par défaut, les identifiants sont en **mémoire uniquement** et sont perdus au rechargement ou à la fermeture.
  - Si l'utilisateur coche « Rester connecté 24 h sur cet appareil » (décochée par défaut), la valeur `Authorization` est enregistrée dans IndexedDB avec une date d'expiration.
  - **Expiration glissante** : la date d'expiration vaut `instant de la dernière activité + 24 h` (horloge de l'appareil). Une **activité** est une requête envoyée avec ces identifiants qui reçoit une réponse **2xx**. La connexion elle-même est une activité. Une erreur réseau, une réponse 429 ou 5xx, ou l'ouverture de l'application sans requête ne prolongent pas la durée.
  - Au-delà de la date d'expiration, les identifiants sont effacés à la première lecture et la connexion est demandée.
  - Après un changement de mot de passe réussi (RG19, 204), les identifiants conservés sont remplacés par les nouveaux, avec la même option de conservation.
- **Séparation des identifiants** : les identifiants coureur et les identifiants ADMIN/SCANNER de `/connexion` (RG6 inc. 4) sont deux emplacements distincts. Se connecter en coureur ne remplace pas une connexion SCANNER ou ADMIN, et inversement ; « un seul compte connecté à la fois » (RG6 inc. 4) vaut pour chaque emplacement.
- **Envoi** (extension de RG8 inc. 4) : les identifiants coureur sont envoyés **uniquement** aux requêtes `/api/account/**` de l'origine de l'API ; jamais à `/api/public/**` (E3 reste anonyme, même connecté), `/api/scan/**` ou `/api/admin/**`. Les identifiants ADMIN/SCANNER ne sont jamais envoyés à `/api/account/**`.
- **401 sur `/api/account/**`** : les identifiants coureur sont effacés (mémoire et stockage), l'écran `/compte/connexion` s'affiche avec « Session expirée ou identifiants modifiés : reconnectez-vous ». Les identifiants ADMIN/SCANNER ne sont pas touchés.
- **Déconnexion** : « Se déconnecter » sur `/compte` efface les identifiants coureur seulement.
- **Interdits** de RG7 (inc. 4) : jamais dans une URL, un journal (`console`), un message d'erreur ou une réponse mise en cache ; champs `autocomplete="username"` et `autocomplete="current-password"` (`new-password` pour les nouveaux mots de passe).
- **Routes** : `/compte` et `/compte/connexion` sont ajoutées à la liste fermée des routes (RG5 inc. 4) et des chemins front servis par Spring Boot (RG53 inc. 4 : `/compte`, `/compte/**`).

---

## 3. Cas limites

**CL1 — Même compte, deux courses.** Un compte inscrit en R1 s'inscrit en R2 par E20 : deux coureurs, un par course, même pseudo. Cas nominal.
**CL2 — Pseudo déjà pris dans une autre course.** « Lievre » existe (inscrit en R1). Une création de compte « Lievre » par E3, sur R2 ou sur R1, est refusée en 409 (RG5). La personne choisit un autre pseudo, ou se connecte si c'est son compte.
**CL3 — Même pseudo à la casse près.** « Lievre » a été saisi, puis stocké `lievre`. Une création de « lievre » ou de « LIEVRE » par E3, sur n'importe quelle course, est refusée (409, RG2, RG5). La saisie est normalisée avant le contrôle. *(Révision 5)* La casse saisie est perdue : la personne qui a saisi « Lievre » voit `lievre` comme pseudo et comme nom affiché, dans tous les écrans. Elle peut se connecter en saisissant `Lievre`, `lievre` ou `LIEVRE`.
**CL4 — Créations simultanées du même pseudo.** Même course ou courses différentes : une réussit (201), l'autre reçoit 409. La requête refusée ne crée ni compte orphelin ni coureur.
**CL5 — Coureur sans compte, antérieur à l'incrément.** `account_id = null`. Scan, tableau de bord, DNF manuel, réintégration et auto-DNF inchangés. `pseudo` et `accountId` à `null` en admin ; `runner.name` purgé par V2 ; nom affiché « Coureur n°{bib} » partout (RG6, RG18). Pas de réinitialisation ni de suppression de compte possible. Aucun rattachement a posteriori.
**CL6 — Coureur supprimé (course SETUP, RG19 inc. 3).** Le compte subsiste, « Mes inscriptions » ne liste plus ce coureur, et le compte peut s'inscrire ailleurs ou de nouveau à la même course.
**CL7 — Course non démarrée, en cours ou terminée.** Inscription possible uniquement en `SETUP` (RG14 inc. 3), avec ou sans compte existant.
**CL8 — Plusieurs courses actives en parallèle.** Un compte avec des coureurs dans deux courses `RUNNING` voit chaque coureur avec le statut de sa course. Les courses restent indépendantes (auto-DNF, yard courant).
**CL9 — Réinitialisation pendant une connexion active.** La requête suivante du coureur, avec l'ancien mot de passe, reçoit 401 ; ses identifiants mémorisés sont effacés et la connexion est demandée (RG14, RG21).
**CL10 — Pseudo égal au nom d'un compte ADMIN ou SCANNER.** Accepté comme pseudo, sans aucun droit supplémentaire (RG9). Aucune ambiguïté, puisque le préfixe d'URL choisit le référentiel (PO15). Les identifiants du compte pseudo donnent 401 sur `/api/admin/**` et `/api/scan/**`, et ceux du compte technique 401 sur `/api/account/**`. C'est vrai même si les deux comptes ont le même mot de passe : chaque chemin ne reconnaît que le compte de son référentiel.
**CL11 — Mot de passe de plus de 72 octets.** Refusé en 400, pour éviter une troncature silencieuse par BCrypt.
**CL12 — Sans objet pour cet incrément.** Instants de bascule de yard, passage manuel ou scan, coureur réintégré, coureur sans passage : aucune règle de course n'est modifiée. La non-régression est vérifiée par la suite complète (CA21).
**CL13 — Titulaire d'un compte qui repasse par la création de compte.** A « Lievre » tente E3 sur R2 avec son propre pseudo et son bon mot de passe. La réponse est 409 (RG7, étape 4) : aucun second compte n'est créé et le compte existant n'est pas lié. Le message l'oriente vers la connexion, puis vers E20.
**CL14 — Une même personne avec deux comptes de pseudos différents.** Rien ne permet de le détecter (RG1). Les deux comptes peuvent être inscrits à la même course : deux coureurs distincts, deux dossards. Ce cas relève de l'organisateur (suppression en `SETUP`, RG19 inc. 3).
**CL15 — Suppression d'un compte dont un coureur est en course.** Le coureur, `ACTIVE` dans une course `RUNNING`, est détaché et continue : son `qrToken` reste valable, le scan (200), l'auto-DNF et le classement sont inchangés. Seuls son nom affiché, qui devient « Coureur n°{bib} » (RG18), et le lien au compte changent.
**CL16 — Pseudo libéré puis repris.** Après suppression du compte « Lievre », une création de compte « Lievre » par E3 réussit (201). Le nouveau compte n'a aucun coureur de l'ancien : « Mes inscriptions » ne liste que ses propres inscriptions.
**CL17 — Changement de mot de passe avec deux appareils connectés.** L'appareil qui change le mot de passe remplace ses identifiants et reste connecté. L'autre reçoit 401 à sa requête suivante et demande une connexion.
**CL18 — Expiration glissante.** Un coureur connecté avec « Rester connecté » qui ouvre « Mes inscriptions » au moins une fois par 24 h (réponse 2xx) reste connecté. Sans aucune réponse 2xx pendant 24 h, y compris si l'application a été ouverte hors ligne, les identifiants expirent.
**CL19 — Coureur et bénévole sur le même appareil.** Connecté en coureur et en SCANNER : chaque requête porte les identifiants de son emplacement ; un 401 sur l'un n'efface pas l'autre ; se déconnecter du compte coureur ne déconnecte pas le SCANNER.
**CL20 — Limitation de débit atteinte.** nginx répond 429 sans transmettre la requête : aucun compte ni coureur n'est créé. La PWA affiche le message de RG23 et ne réessaie pas d'elle-même.
**CL21 — Compte sans aucune inscription** (après CL6). Il se connecte et voit une liste vide ; il peut s'inscrire à une course `SETUP`. Il n'apparaît dans aucune liste de coureurs, mais il figure dans l'écran « Comptes » avec 0 inscription (`runnerCount = 0`) : l'admin peut le trouver par son pseudo, le réinitialiser ou le supprimer (RG24, PO17).
**CL22 — Recherche contenant un caractère joker SQL.** Les pseudos peuvent contenir `_` (RG2). Une recherche `a_b` ne renvoie que les pseudos qui contiennent littéralement `a_b`, et non `axb`. Une recherche `%` ne renvoie aucun compte, puisque aucun pseudo ne contient `%` (RG24).

---

## 4. Critères d'acceptation

Sauf mention contraire : course **R1** (`id 1`, « Backyard Test », `2026-10-03`, 6706 m, 3600 s, 50 m, `SETUP`, aucun coureur) et course **R2** (`id 2`, « Backyard Automne », `2026-11-07`, mêmes paramètres, `SETUP`, aucun coureur). Mot de passe par défaut : `motdepasse-1` (12 octets). *(Révision 5)* Dans le texte, « compte « Lievre » » désigne le compte créé par la saisie `Lievre` : son pseudo stocké, renvoyé et affiché est `lievre` (RG2). Il en va de même pour « Tortue » (`tortue`), « Oublie » (`oublie`) et, en E2E, `Lievre-{run}` (`lievre-{run}`). Toute valeur **attendue** d'un champ `pseudo` ou `name`, ou d'un texte affiché, est en minuscules. Les valeurs **saisies** (corps de E3, identifiants HTTP Basic, champs de formulaire) sont écrites telles qu'envoyées. Étiquettes : **[unit]** service ou domaine sans Spring ni base ; **[slice]** `@WebMvcTest` avec sécurité réelle ; **[IT]** `@SpringBootTest` sur H2 ; **[E2E]** Playwright, Chromium et WebKit ; **[front-unit]** logique pure du front ; **[config]** lecture automatisée d'un fichier de configuration versionné ; **[manuel]** vérification sur le VPS, consignée dans le rapport.

**CA1 — Schéma (RG1, RG4, RG5, RG6, RG16) [IT]**
Après migration : la table `account` a exactement les colonnes `id`, `pseudo`, `password_hash` ; `runner.account_id` existe et est nullable ; `runner.name` est nullable ; la contrainte d'unicité `(race_id, account_id)` existe ; une contrainte d'unicité porte sur `account.pseudo` seul ; l'insertion SQL native d'un second compte `lievre` quand `lievre` existe est rejetée (`DataIntegrityViolationException`) *(révision 5 : contrainte `UNIQUE` classique, RG5)* ; la somme de contrôle Flyway de V1 est inchangée ; aucune colonne de `account` ni de `runner` ne contient `ip`, `mail`, `phone`, `login` ou `last` dans son nom.

**CA2 — Format et normalisation du pseudo (RG2) [unit + slice]** *(révision 5)*
- Acceptés, avec le pseudo enregistré entre parenthèses : `Lievre_42` (`lievre_42`), `abc` (`abc`), un pseudo de 30 caractères `A` (30 `a`), `  Lievre  ` (`lievre`), `LIEVRE` (`lievre`).
- Refusés en 400 `VALIDATION_FAILED` avec une erreur sur `pseudo` : `ab`, 31 caractères, `Jean Dupont`, `élan`, chaîne vide. Service non appelé en cas de refus.
- [unit] La fonction de normalisation, appelée directement : `normaliser("  Lievre_42 ") = "lievre_42"`, `normaliser("LIEVRE") = "lievre"`, `normaliser("lievre") = "lievre"`. Elle est pure : ni Spring, ni base.
- Revue : la mise en minuscules d'un pseudo n'apparaît qu'à un seul endroit du code de production (RG2) ; aucune requête de repository sur le pseudo n'utilise `lower`, `upper`, `IgnoreCase` ni `ILIKE`.

**CA3 — Format du mot de passe (RG3, CL11) [unit + slice]**
Refusés en 400 : `court12` (7 caractères), 73 caractères ASCII, 37 fois `é` (74 octets). Acceptés : `huitcar8` (8), 72 caractères ASCII. Mêmes résultats pour `password` (E3), `newPassword` (E22) et `newPassword` (E23).

**CA4 — Hash BCrypt (RG3) [unit + IT]** *(complété en révision 5 : portée du coût 12)*
- Après inscription de « Lievre » avec `motdepasse-1` : `password_hash` commence par `$2a$12$` (ou `$2b$12$`, `$2y$12$`), fait 60 caractères, diffère de `motdepasse-1`, et `BCryptPasswordEncoder.matches("motdepasse-1", hash)` est vrai.
- Même préfixe de coût 12 pour le hash produit par E22 (`nouveau-mdp-42`) et par E23 (`nouveau-mdp-43`).
- [IT] Avec le bean `PasswordEncoder` de l'application, dans le profil `test` : `matches("admin-secret", <hash de test ADMIN en $2a$04$ de application-test.properties>)` est vrai. Et `GET /api/admin/races` avec `admin-test` / `admin-secret` donne 200 : un hash staff de coût 4 reste vérifiable.
- Le contexte contient un seul bean `PasswordEncoder`.

**CA5 — Aucun secret en réponse (RG3) [slice + IT]**
Les corps JSON de E3, E20, E21, E13, E14 et E25 ne contiennent aucune propriété `password`, `passwordHash`, `newPassword`, ni la sous-chaîne `$2a$`, `$2b$` ou `$2y$`. E22, E23 et E24 répondent 204 sans corps.

**CA6 — Inscription avec création de compte (RG7, RG18) [unit + slice]**
Donné R1. Quand E3 avec `{"pseudo":"Lievre","password":"motdepasse-1"}`. Alors 201 ; un compte créé, de pseudo `lievre` en base ; un coureur de R1, `bib = 1`, `status = ACTIVE`, `qrToken` au format UUID, lié à ce compte, avec `runner.name = null` en base ; réponse avec `runnerId`, `raceId = 1`, `bib = 1`, `pseudo = "lievre"`, `name = "lievre"`, `qrToken` *(révision 5 : casse saisie perdue, RG2)*.

**CA7 — Pseudo déjà pris, toutes courses (RG2, RG5, CL2, CL3) [unit + IT]** *(révision 5)*
Donné « Lievre » inscrit en R1 : compte unique en base, de pseudo `lievre`.
- Quand E3 sur R1 avec `lievre`. Alors 409 `BUSINESS_CONFLICT`, `detail` contenant `lievre`.
- Quand E3 sur R2 avec `Lievre`, puis avec `LIEVRE`, puis avec `  LiEvRe `. Alors 409 `BUSINESS_CONFLICT` à chaque fois, `detail` contenant `lievre` (valeur normalisée) et ne contenant ni `LIEVRE` ni `LiEvRe`.
- Dans les quatre cas, la base contient toujours exactement 1 compte, de pseudo `lievre`, et R2 aucun coureur.
- [IT] Le refus est obtenu par le contrôle applicatif (`BUSINESS_CONFLICT`), et non par la contrainte en base.

**CA8 — Créations simultanées (RG5, CL4) [IT]**
Donné R1 et R2. Quand deux requêtes E3 `{"pseudo":"Tortue",...}` partent en parallèle, l'une sur R1 et l'autre sur R2. Alors une 201 et une 409 (`BUSINESS_CONFLICT` ou `DATA_INTEGRITY`). La base contient exactement 1 compte `tortue` et exactement 1 coureur lié à ce compte. Même résultat si les deux requêtes visent R1. *(Révision 5)* Même résultat (une 201, une 409, 1 compte `tortue`, 1 coureur) si l'une porte `Tortue` et l'autre `TORTUE`, puisque les deux saisies sont normalisées avant l'enregistrement.

**CA9 — Inscription d'un compte existant (RG4, RG8, CL1, CL7) [unit + slice]**
Donné le compte A « Lievre », inscrit en R1 (dossard 1), authentifié. Quand il s'inscrit à R2 par E20. Alors 201, coureur de R2 `bib = 1` lié à A ; A a 2 coureurs. Quand il s'inscrit de nouveau à R2 : 409 « Déjà inscrit à cette course », aucun coureur créé. Donné R1 passée en `RUNNING` et un compte B authentifié non inscrit en R1 : son inscription à R1 donne 409 (inscriptions fermées).

**CA10 — Titulaire d'un compte : pas de second compte, liaison par E20 (RG5, RG7, RG8, CL13) [unit + slice]**
Donné A « Lievre » / `motdepasse-1`, inscrit en R1 (dossard 1).
- Quand E3 sur R2 avec `{"pseudo":"Lievre","password":"motdepasse-1"}`. Alors 409 `BUSINESS_CONFLICT`, `detail` contenant « connectez-vous ». Nombre de comptes inchangé (1), R2 sans coureur.
- Puis A s'authentifie avec `Lievre` / `motdepasse-1` et appelle `POST /api/account/races/2/registrations`. Alors 201, coureur de R2 `bib = 1`, `accountId` égal à celui de A. La base contient toujours 1 seul compte, de pseudo `lievre`.

**CA11 — Ancien corps refusé (RG7) [slice]**
Quand E3 avec `{"name":"Alice"}`. Alors 400 `VALIDATION_FAILED`, `errors` contient `pseudo` et `password` ; service non appelé.

**CA12 — Authentification HTTP Basic (RG2, RG9) [unit + slice]** *(révision 5)*
Donné le compte « Lievre » / `motdepasse-1` (pseudo stocké `lievre`), inscrit en R1 et R2. Identifiants présentés en HTTP Basic : pseudo + mot de passe, sans course.
- `Lievre` / `motdepasse-1` : succès, autorité unique `ROLE_RUNNER`, compte `lievre`.
- `lievre` / `motdepasse-1` et `LIEVRE` / `motdepasse-1` : succès, même compte. E21 renvoie `pseudo = "lievre"` dans les trois cas.
- `Lievre` / `motdepasse-2` : échec.
- `Lievre` / `MOTDEPASSE-1` : échec (le mot de passe n'est pas normalisé).
- `Inconnu` / `motdepasse-1` : échec.
- Les échecs donnent au niveau HTTP un 401 `UNAUTHENTICATED`, au même `detail`, « Identifiants invalides », sans `WWW-Authenticate`.
- Aucune réponse, réussie ou non, ne contient d'en-tête `Set-Cookie`.

**CA13 — Matrice d'accès (RG9, RG10) [slice]** *(évolution motivée, PO15 tranché : les accès croisés passent de 403 à 401)*
- E21 `GET /api/account/me` : anonyme 401, RUNNER 200, SCANNER (`scanner-test`) 401, ADMIN (`admin-test`) 401.
- Avec RUNNER `Lievre` : `GET /api/scan/me` 401, `GET /api/admin/races` 401, `GET /api/public/races` 401. La même requête `GET /api/public/races` sans en-tête `Authorization` donne 200.
- Avec SCANNER : `GET /api/scan/me` 200, `GET /api/admin/races` 403, `GET /api/public/races` 200. Avec ADMIN : `GET /api/admin/races` 200.
- *(Clarifié en révision 5)* Les réponses 401 obtenues **avec des identifiants** (RUNNER, SCANNER, ADMIN) ont le même `detail` que `Inconnu` / `motdepasse-1` (CA12), « Identifiants invalides ». La réponse 401 **anonyme** de E21 a le `detail` « Authentification requise », comme tout 401 anonyme de l'inc. 3. Aucune réponse 401 ne porte d'en-tête `WWW-Authenticate`.
- Les tests de l'inc. 3 qui vérifient le `detail` des 401 anonymes restent inchangés.

**CA14 — Mes inscriptions (RG11) [unit + slice]**
Donné A inscrit en R2 (bib 1) puis en R1 (bib 3), et B « Tortue » inscrit en R1 (bib 4). Quand A appelle E21. Alors `pseudo = "lievre"` et 2 éléments dans l'ordre R1 puis R2 (`raceDate` croissante), avec les `qrToken` de ses deux coureurs. Aucun élément ni `qrToken` de B.

**CA15 — Compte visible en admin (RG12, CL5) [slice]**
E13 sur R1, avec A (compte id 5) et un coureur antérieur sans compte : A a `accountId = 5`, `pseudo = "lievre"`, `name = "lievre"` ; l'autre a `accountId = null`, `pseudo = null`.

**CA16 — Aucune corrélation technique publique (RG13) [IT]**
Donné A inscrit en R1 et R2. Les réponses E4 de R1 et R2 et E5 de ses deux coureurs ne contiennent aucune propriété `accountId`, `account` ni `pseudo` ; le pseudo n'apparaît que comme valeur de `name` ; les `runnerId` diffèrent et aucun `qrToken` n'est présent (RG16 inc. 3).

**CA17 — Réinitialisation par l'admin (RG14, CL9) [unit + IT]**
Donné le compte 5 « Lievre » / `motdepasse-1`, qui vient d'obtenir 200 sur E21. Quand l'ADMIN envoie `PUT /api/admin/accounts/5/password` avec `{"newPassword":"nouveau-mdp-42"}`. Alors 204 sans corps ; le hash a changé ; la requête suivante sur E21 avec `Lievre` / `motdepasse-1` reçoit 401 ; avec `nouveau-mdp-42`, 200, puis E23 et E20 sont acceptés sans changement préalable du mot de passe ; le journal contient « mot de passe réinitialisé pour le compte 5 » et ni `nouveau-mdp-42` ni le hash.

**CA18 — Réinitialisation : erreurs (RG14) [slice]**
ADMIN : compte 99 inexistant → 404 `RESOURCE_NOT_FOUND` ; `{"newPassword":"court12"}` → 400 `VALIDATION_FAILED`. Anonyme → 401. SCANNER → 403. RUNNER → 401 (*évolution motivée, PO15* : 403 dans la rédaction précédente).

**CA19 — Aucune procédure automatisée (RG15) [IT]**
Au démarrage du contexte complet, les seules correspondances Spring MVC dont le chemin contient `password` sont `PUT /api/admin/accounts/{accountId}/password` et `PUT /api/account/password`. Aucune correspondance ne contient `reset`, `forgot` ou `recover`.

**CA20 — Journaux sans secret ni pseudo (RG3, RG16, RG19, RG20) [unit ou IT, capture des journaux]**
Pendant une inscription réussie de `Lievre`, une authentification échouée avec `Lievre` et `motdepasse-2`, un changement de mot de passe vers `nouveau-mdp-43`, une réinitialisation vers `nouveau-mdp-42` et une suppression de compte : les journaux capturés ne contiennent ni `motdepasse-1`, ni `motdepasse-2`, ni `nouveau-mdp-42`, ni `nouveau-mdp-43`, ni `$2a$`/`$2b$`/`$2y$`, ni `Authorization`. La ligne WARN de l'échec et les lignes INFO du changement, de la réinitialisation et de la suppression ne contiennent `lievre` dans aucune casse (recherche insensible à la casse, révision 5).

**CA21 — Non-régression et coureurs sans compte (RG6, CL5, CL12) [IT]**
- `mvn -B -f backend/pom.xml clean verify` est vert, tests des incréments 1 à 4 compris, sans test désactivé.
- Les tests existants dont l'attendu change du fait de cet incrément sont modifiés avec un motif écrit et l'accord de l'agent fonctionnel (règle 2 du workflow), sans affaiblir leurs autres assertions. Cela concerne :
  - l'ancien corps de E3 (RG7) ;
  - `runner.name` devenu nullable (RG6) ;
  - le nom affiché d'un coureur sans compte, désormais « Coureur n°{bib} » (RG18, PO13).
  Le testeur en fait l'inventaire. *(Choix confirmé (utilisateur, 2026-09-28).)*
- *(Révision 5)* Les adaptations de **compilation** (signatures sans nom, `runner.name` plus écrit) suivent la démarche et les critères d'accord de la **note N1** ci-dessous. La liste des adaptations appliquées, avec la catégorie de chacune (A, B ou C) et l'accord obtenu, figure dans le rapport de l'incrément.
- Les tests de l'inc. 3 sur le `detail` des 401 anonymes (« Authentification requise ») ne sont pas modifiés (RG10, révision 5).
- En plus : un coureur sans compte de R1 démarrée est scanné (200), déclaré DNF, réintégré, et apparaît dans E4 avec son dossard, son statut et ses tours comme avant l'incrément, sous le nom « Coureur n°{bib} » (voir CA31).

**Note N1 — Démarche d'adaptation des tests existants** *(révision 5, 2026-09-28 ; démarche proposée par le développeur, validée et précisée par l'agent fonctionnel)*

Constat : le modèle de cet incrément casse la **compilation** de la plupart des tests existants. L'inscription et la modification n'ont plus de nom, et `runner.name` n'est plus écrit. Sont concernés notamment `new Runner(race, bib, "Alice", token)` dans `testsupport/TestData.java`, `runnerService.register(1L, "Alice")`, `runnerService.update(12L, 7, "Alice B.")` et `runner.setName(...)`.

*Étapes*
1. **Développeur.** Il implémente la production sans modifier les tests du dépôt. Il mesure la non-régression sur une **copie des tests adaptés, hors du dépôt** (répertoire temporaire, jamais commité). Il fournit :
   - la **liste exacte** des adaptations : fichier, méthode de test, extrait avant et après, catégorie (A, B ou C ci-dessous) et motif ;
   - la commande exécutée sur la copie, sa date et ses chiffres réels (tests exécutés, échecs, ignorés).

   Ce résultat est indicatif : il ne vaut ni verdict technique ni GO.
2. **Agent fonctionnel.** Il contrôle la catégorie de chaque adaptation et donne son accord écrit :
   - accord global pour la catégorie A ;
   - accord ligne à ligne pour la catégorie B ;
   - arbitrage motivé pour la catégorie C.
   
   Une adaptation mal classée est reclassée ; seul l'agent fonctionnel décide de la catégorie finale.
3. **Testeur.** Il applique dans le dépôt les seules adaptations acceptées. Il vérifie que le diff des tests existants correspond exactement à la liste acceptée, puis lance `mvn -B -f backend/pom.xml clean verify` (et la suite front concernée) et rend son verdict technique.

   Tout écart entre le diff et la liste revient à l'agent fonctionnel avant le verdict. Adapter une signature n'est pas assouplir un test : aucune assertion ne doit être retirée ni affaiblie.

*Critères d'accord*

**Catégorie A — admise sans nouvel arbitrage** (accord global donné par la présente note, sous réserve qu'elle figure dans la liste). C'est une adaptation de **compilation ou de jeu de données**, qui ne touche aucune assertion :
- constructeurs et fabriques de `TestData` sans nom (`new Runner(race, bib, token)` ou avec compte), quand aucune assertion du test ne porte sur le nom ;
- appel de `register` avec la nouvelle signature (pseudo + mot de passe, ou compte), quand le test ne vérifie pas le nom : dossard, `qrToken`, course fermée, course introuvable, etc. ;
- `update(12L, 7, "Alice B.")` remplacé par la nouvelle signature sans nom, quand le test vérifie le dossard ou une erreur ;
- suppression d'un `runner.setName(...)` de préparation, dont la valeur n'est lue par aucune assertion ;
- corps de requête E3 `{"name":...}` remplacé par `{"pseudo":...,"password":...}` valides, dans un test dont l'objet n'est pas le corps de E3 ;
- ajout d'imports, de mocks, de bouchons ou de dépendances de construction (dépôt de comptes, `PasswordEncoder`) exigés par les nouveaux constructeurs.

Conditions **cumulatives** : même nombre d'assertions, mêmes valeurs attendues, mêmes codes et `detail` attendus ; nom de méthode, `@DisplayName` et référence de CA inchangés ; aucun test supprimé, désactivé, ignoré ou exclu de la suite.

**Catégorie B — attendu modifié par la spec, accord ligne à ligne.** L'attendu change parce qu'une règle de cet incrément le change, et seulement dans ces cas :
- un nom attendu (`"Alice"`) devient le pseudo normalisé (RG18) ou « Coureur n°{bib} » (RG18, PO13) ;
- `runner.name` NOT NULL (RG6 inc. 1) devient nullable (RG6) ;
- l'ancien corps de E3 `{"name"}` passe de 201 à 400 (RG7) ;
- E15 ne modifie plus le nom (RG18).

Condition : l'assertion de remplacement est **aussi stricte** que l'originale. Une égalité exacte reste une égalité exacte, sur la nouvelle valeur prévue par la spec ; elle ne devient pas `isNotNull`, `contains` ou « non vide ». Motif écrit citant la RG.

**Catégorie C — arbitrage exigé, bloquant tant qu'il n'est pas rendu.** Relève de cette catégorie toute adaptation qui :
- retire une assertion, en affaiblit une (égalité remplacée par une inclusion, valeur exacte par une présence, nombre exact par un minimum), ou supprime, désactive, ignore ou exclut un test ;
- change un code HTTP, un `code` ou un `detail` attendu, hors de la catégorie B et des évolutions motivées déjà écrites (PO15 : CA13, CA18, CA22, CA32, CA33, CA42) ;
- touche aux tests de sécurité de l'inc. 3, notamment le `detail` des 401 anonymes, ou aux règles de course (calculs dérivés, auto-DNF, réintégration, scan) ;
- modifie les seuils de couverture, les exclusions JaCoCo, surefire ou failsafe, ou la configuration de test ;
- ne peut être classée ni en A ni en B.

**CA22 — Pseudo égal à un compte technique (RG9, RG10, CL10) [slice]** *(évolution motivée, PO15 tranché : 401 au lieu de 403)*
- E3 avec `{"pseudo":"admin-test","password":"motdepasse-1"}` → 201. Avec `admin-test` / `motdepasse-1` : `GET /api/admin/races` → 401, `GET /api/scan/me` → 401, E21 → 200 (`pseudo = "admin-test"`).
- Le compte ADMIN `admin-test` / `admin-secret` garde son accès : `GET /api/admin/races` → 200 ; E21 → 401.
- E3 avec `{"pseudo":"scanner-test","password":"scanner-secret"}` → 201 (même mot de passe que le compte SCANNER de configuration). Avec `scanner-test` / `scanner-secret` : E21 → 200 et autorité `ROLE_RUNNER` ; `GET /api/scan/me` → 200 avec `role = "SCANNER"`. Aucun des deux comptes ne masque l'autre.

**CA23 — Compte conservé après suppression du coureur (RG4, CL6, CL21) [unit]**
Donné A inscrit seulement en R1 (`SETUP`). Quand l'admin supprime ce coureur (E16). Alors le compte A existe toujours, E21 renvoie une liste vide, et A peut s'inscrire à R2 (201).

**CA24 — Courses en parallèle (RG11, CL8) [unit]**
Donné A avec un coureur `ACTIVE` en R1 (`RUNNING`) et un coureur `DNF` (`TIMEOUT`, yard 2) en R2 (`RUNNING`). Quand E21. Alors R1 → `ACTIVE`, R2 → `DNF`, chacun avec le statut de sa course.

**CA25 — Parcours d'inscription et de réaffichage du QR (RG17, RG7, RG11) [E2E]**
Sur `/inscription/{R}` (R en `SETUP`) : mots de passe différents → message, aucune requête E3 (journal réseau) ; pseudo saisi `Lievre-{run}` et mots de passe identiques → confirmation avec dossard et QR, qui affiche le pseudo `lievre-{run}` (révision 5) ; la mention de minimisation est visible sur le formulaire. Connexion coureur par `/compte/connexion` puis `/compte` : le QR affiché, décodé, est égal au `qrToken` de la confirmation. Une seconde création de compte `Lievre-{run}` sur une autre course `SETUP` affiche le `detail` 409 et le lien « J'ai déjà un compte ».

**CA26 — Parcours de réinitialisation par l'admin (RG14, RG17) [E2E]**
Connecté ADMIN sur `/admin/courses/{R}` : la colonne « Pseudo » affiche `lievre-{run}` (compte saisi `Lievre-{run}`, révision 5) ; « Réinitialiser le mot de passe » → « Annuler » : aucune requête ; « Confirmer » avec `nouveau-mdp-42` : une seule requête E22, 204. La connexion coureur avec l'ancien mot de passe affiche « Identifiants invalides » ; avec le nouveau, `/compte` s'affiche directement, sans demande de changement.

**CA27 — Validation des formulaires coureur (RG17) [front-unit]**
La fonction de validation du formulaire d'inscription refuse `ab`, `Jean Dupont`, un mot de passe de 7 caractères et deux mots de passe différents, et accepte `Lievre_42` / `motdepasse-1` confirmé. La validation du formulaire de changement de mot de passe refuse 7 caractères et deux saisies différentes.

**CA28 — Parcours d'inscription avec un compte existant (RG8, RG17, CL1) [E2E]**
Donné `Lievre-{run}` inscrit en R et une course R' en `SETUP`. Sur `/inscription/{R'}`, on suit « J'ai déjà un compte » et on se connecte en saisissant `Lievre-{run}`. On revient alors sur `/inscription/{R'}`, qui affiche « Vous êtes connecté en tant que lievre-{run} » (révision 5 : pseudo en minuscules). On clique sur « M'inscrire à cette course » : une seule requête E20 (journal réseau), aucune requête E3, 201. `/compte` liste alors R et R'.

**CA29 — Deux comptes de pseudos différents dans la même course (RG5, CL14) [unit]**
Donné R1. E3 avec `Lievre` puis avec `Lievre2` (mêmes mots de passe) : deux 201, deux comptes distincts (`lievre` et `lievre2`) et deux coureurs de R1 (dossards 1 et 2). Aucun contrôle d'identité n'est fait au-delà du pseudo.

**CA30 — Nom affiché dérivé du pseudo (RG18) [unit + IT]**
Donné A « Lievre » inscrit en R1 (dossard 1). Alors E4 de R1 et E5 du coureur donnent `name = "lievre"` ; E13 donne `name = "lievre"` et `pseudo = "lievre"` ; en base, `runner.name` est `null`. Quand l'ADMIN envoie E15 avec `{"bib":2,"name":"Autre"}` (R1 en `SETUP`) : 200, `bib = 2`, `name = "lievre"`, `runner.name` toujours `null`. Aucune réponse ne contient `Lievre` avec une majuscule (révision 5).

**CA31 — Migration V2 sur des données existantes (RG6, RG18, CL5) [IT]**
Donné une base migrée en V1 seulement, avec :
- R1 `RUNNING` et le coureur « Alice » (dossard 1, sans compte), qui a 2 passages ;
- une course R0 `FINISHED` et le coureur « Bob » (dossard 3, `DNF`, `TIMEOUT`, yard 4).
Quand V2 est appliquée. Alors :
- `SELECT count(*) FROM runner WHERE name IS NOT NULL` vaut 0 ;
- le coureur de dossard 1 existe toujours, avec `account_id = null` ; son dossard, son statut, son `qrToken` et ses 2 passages sont inchangés ;
- Bob garde son statut, `dnf_reason` et `dnf_yard` ;
- E4 de R1, E5 du coureur de dossard 1 et E13 de R1 donnent `name = "Coureur n°1"` (et `pseudo = null` en E13) ; E4 de R0 donne `name = "Coureur n°3"` ;
- aucune réponse ne contient `Alice` ni `Bob`.

**CA32 — Changement de mot de passe par le coureur (RG19, CL17) [unit + slice + IT]**
Donné « Lievre » / `motdepasse-1`.
- E23 avec `{"newPassword":"nouveau-mdp-43"}` → 204 sans corps. E21 avec `motdepasse-1` → 401 ; avec `nouveau-mdp-43` → 200.
- E23 avec `{"newPassword":"court12"}` → 400 `VALIDATION_FAILED`, hash inchangé.
- E23 : anonyme 401, SCANNER 401, ADMIN 401 (*évolution motivée, PO15* : 403 pour SCANNER et ADMIN dans la rédaction précédente).
- Le journal contient « mot de passe modifié par le titulaire du compte {id} ».

**CA33 — Suppression d'un compte (RG20, RG4, CL15, CL16) [unit + IT]**
Donné le compte 5 « Lievre » avec un coureur en R1 (`RUNNING`, `ACTIVE`, dossard 1, 2 passages) et un coureur en R2 (`SETUP`, dossard 1).
- Quand l'ADMIN envoie `DELETE /api/admin/accounts/5`. Alors 204 sans corps ; le compte 5 n'existe plus ; les deux coureurs existent, avec `account_id = null` et les mêmes dossard, statut, `qrToken` ; le coureur de R1 a toujours 2 passages.
- Le scan de son `qrToken` au yard courant → 200.
- E21 avec `Lievre` / `motdepasse-1` → 401.
- E3 sur R2 avec `{"pseudo":"Lievre","password":"motdepasse-9"}` → 201 : nouveau compte d'id différent de 5, dossard 2, et E21 de ce compte ne liste qu'un coureur.
- Une seconde suppression du compte 5 → 404. E24 : anonyme 401, SCANNER 403, RUNNER 401 (*évolution motivée, PO15* : 403 pour RUNNER dans la rédaction précédente).
- Le journal contient « compte 5 supprimé, 2 coureurs détachés ».

**CA34 — Nom affiché d'un coureur détaché (RG18, RG20) [IT]**
Après CA33 :
- pour le coureur détaché de R1 (dossard 1), E4 et E5 donnent `name = "Coureur n°1"`, et E13 donne `name = "Coureur n°1"`, `pseudo = null`, `accountId = null` ;
- en base, `runner.name` est `null` pour les deux coureurs détachés ;
- ce `name` ne change pas après la création du nouveau compte « Lievre » (dernière étape de CA33) ;
- quand l'ADMIN envoie E15 avec `{"bib":5}` au coureur détaché de R2 (`SETUP`), la réponse et E13 de R2 donnent `name = "Coureur n°5"`.

**CA35 — Expiration glissante des identifiants coureur (RG21, CL18) [front-unit]**
Avec une horloge simulée et un stockage espionné :
- connexion avec « Rester connecté » à J `08:00:00`, puis une réponse 200 de E21 à J `20:00:00` : les identifiants sont lus à J+1 `19:59:59` et effacés à J+1 `20:00:00` ;
- connexion à J `08:00:00` sans autre réponse 2xx, avec une erreur réseau et une réponse 429 à J `20:00:00` : identifiants lus à J+1 `07:59:59`, effacés à J+1 `08:00:00` ;
- connexion sans « Rester connecté » : aucune écriture dans le stockage persistant ;
- après un 204 de E23 avec `nouveau-mdp-43`, la valeur stockée correspond à `nouveau-mdp-43`.

**CA36 — Envoi et séparation des identifiants (RG21, CL19) [front-unit]**
Coureur `Lievre` et SCANNER `scanner-test` connectés sur le même appareil :
- une requête E21 porte l'en-tête de `Lievre`, une requête E6 celui de `scanner-test` ;
- E3 et `GET /api/public/races` ne portent aucun en-tête `Authorization` ;
- un 401 sur E21 efface les identifiants de `Lievre` et conserve ceux de `scanner-test` ;
- « Se déconnecter » sur `/compte` conserve ceux de `scanner-test`.

**CA37 — Parcours de changement de mot de passe (RG17, RG19, RG21) [E2E]**
Connecté `Lievre-{run}` sur `/compte` avec « Rester connecté » : saisie de `nouveau-mdp-43` deux fois, une seule requête E23, 204. Après rechargement, `/compte` s'affiche sans nouvelle connexion, avec le pseudo `lievre-{run}` (révision 5). Après « Se déconnecter », la connexion avec l'ancien mot de passe affiche « Identifiants invalides » ; avec `nouveau-mdp-43`, elle réussit. Ni l'un ni l'autre mot de passe n'apparaît dans `localStorage`, `sessionStorage`, les cookies ou l'URL.

**CA38 — Parcours de suppression d'un compte par l'admin (RG17, RG20) [E2E]**
Connecté ADMIN sur `/admin/courses/{R}` avec `Lievre-{run}` inscrit : la confirmation affiche « Supprimer le compte lievre-{run} ? … » (révision 5) ; « Supprimer le compte » → « Annuler » : aucune requête ; « Confirmer » : une seule requête E24, 204. Après rechargement de la liste, le coureur est toujours présent avec son dossard, la colonne « Pseudo » est vide et le nom affiché est « Coureur n°{bib} », avec le dossard du coureur. Une connexion coureur avec `Lievre-{run}` affiche « Identifiants invalides ».

**CA39 — Conservation des journaux (RG22) [config + manuel]**
- [config] `application-prod.properties` définit un fichier de journal, une rotation quotidienne et un historique de 6 archives au plus (`logging.logback.rollingpolicy.max-history=6` ou équivalent).
- [config] Le fichier logrotate versionné pour les journaux d'accès et d'erreur nginx contient `daily` et `rotate 6`. Si un fichier journald est versionné, il fixe `MaxRetentionSec=7day`.
- [manuel] Sur le VPS, après installation, aucun fichier de journal de l'application ou de nginx ne contient d'entrée datée de plus de 7 jours calendaires.

**CA40 — Limitation de débit (RG23, CL20) [config + manuel]** *(PO16 et PO18 tranchés)*
- [config] La configuration nginx versionnée contient :
  - deux `limit_req_zone` distinctes, indexées par `$binary_remote_addr`, l'une en `rate=10r/m` et l'autre en `rate=30r/m` ;
  - sur le `location` de l'inscription publique (E3), `limit_req` sur la zone à `10r/m` avec `burst=5 nodelay` ;
  - sur `location /api/account/`, `limit_req` sur la zone à `30r/m` avec `burst=10 nodelay` ;
  - `limit_req_status 429`.
  Aucun autre `location` n'a de `limit_req` à cet incrément. L'inc. 7 ajoute E19 : évolution prévue de cette assertion (RG15 inc. 7).
- [manuel] Sur le VPS, depuis une même IP et après 2 minutes sans requête :
  - 7 requêtes E3 avec un corps `{}`, en moins d'une seconde : les 6 premières reçoivent 400 (de l'application), la 7e reçoit 429 ;
  - immédiatement après, 12 requêtes E21, en moins d'une seconde : les 11 premières reçoivent 200 ou 401, la 12e reçoit 429. Les zones sont donc distinctes : l'épuisement de l'inscription n'a pas bloqué E21 ;
  - 20 requêtes `GET /api/public/races` en moins d'une seconde : aucune ne reçoit 429.
- Aucun code applicatif ne compte les tentatives (revue).

**CA41 — Affichage d'un 429 (RG23, CL20) [E2E]**
Sur `/inscription/{R}`, la réponse de E3 est interceptée et remplacée par un 429 à corps HTML. Alors « Trop de tentatives. Réessayez dans une minute. » s'affiche, et le journal réseau ne contient qu'une seule requête E3. Même résultat sur `/compte/connexion` avec E21.

**CA42 — Liste et recherche des comptes (RG2, RG24, RG10, CL21) [unit + slice]** *(révision 5 : pseudos stockés en minuscules)*
Donné les comptes 5 `lievre` (2 coureurs), 6 `oublie` (0 coureur) et 7 `tortue` (1 coureur), créés par les saisies `Lievre`, `Oublie` et `Tortue`. En ADMIN :
- E25 sans paramètre → 200, 3 éléments dans l'ordre `lievre`, `oublie`, `tortue`, avec `runnerCount` 2, 0 et 1 ; les `pseudo` renvoyés sont en minuscules ;
- `?pseudo=LIE` → `lievre` puis `oublie` (valeur normalisée en `lie`) ;
- `?pseudo=%20tor%20` → `tortue` seul ;
- `?pseudo=` → les 3 comptes ;
- `?pseudo=zzz` → 200, liste vide.
Aucun élément ne contient de propriété autre que `accountId`, `pseudo` et `runnerCount`. E25 : anonyme 401, SCANNER 403, RUNNER 401.

**CA43 — Recherche littérale (RG2, RG24, CL22) [IT]**
Donné les comptes `a_b` et `axb`, sur H2 en mode PostgreSQL :
- `?pseudo=a_b` → « a_b » seul ;
- `?pseudo=%25` (soit `%`) → liste vide ;
- `?pseudo=A_B` → « a_b » seul (valeur normalisée en `a_b`, révision 5).
- Donné en plus le compte créé par la saisie `A_B2` : il est stocké `a_b2`, et `?pseudo=a_b` renvoie alors `a_b` puis `a_b2`.

**CA44 — Parcours de l'écran « Comptes » (RG17, RG24, RG14, RG20, CL21) [E2E]**
Donné `Lievre-{run}` inscrit en R, et `Oublie-{run}` dont le seul coureur a été supprimé par E16 (R en `SETUP`). Connecté ADMIN, on suit le lien « Comptes » de `/admin` :
- recherche `-{run}` : une seule requête E25 (journal réseau), deux lignes, affichées `lievre-{run}` (1 inscription) et `oublie-{run}` (0) (révision 5) ;
- sur `oublie-{run}`, « Réinitialiser le mot de passe », puis « Annuler » : aucune requête ; « Confirmer » avec `nouveau-mdp-42` : une seule requête E22, 204 ;
- sur `oublie-{run}`, « Supprimer le compte », puis « Confirmer » : une seule requête E24, 204. La liste rechargée ne montre plus que `lievre-{run}`. Une recherche `Oublie-{run}` (saisie avec majuscule) affiche « Aucun compte ».

### Couverture RG / CL → CA

| RG / CL | CA |
|---|---|
| RG1 | CA1 |
| RG2 (normalisation, révision 5) | CA2, CA6, CA7, CA8, CA12, CA27, CA42, CA43 |
| RG3 (dont portée du coût 12, révision 5) | CA3, CA4, CA5, CA20 |
| RG4 | CA1, CA9, CA23, CA33 |
| RG5 (contrainte `UNIQUE` sur `pseudo`, révision 5) | CA1, CA7, CA8, CA10, CA29, CA33 |
| RG6 | CA1, CA21, CA31 |
| RG7 | CA6, CA10, CA11, CA25 |
| RG8 | CA9, CA10, CA28 |
| RG9 | CA10, CA12, CA13, CA22 |
| RG10 (dont `detail` des 401, révision 5) | CA13, CA18, CA22, CA32, CA33, CA42 |
| RG11 | CA14, CA24, CA25 |
| RG12 | CA15 |
| RG13 | CA16 |
| RG14 | CA17, CA18, CA26, CA44 |
| RG15 | CA19 |
| RG16 | CA1, CA20 |
| RG17 | CA25, CA26, CA27, CA28, CA37, CA38, CA44 |
| RG18 | CA6, CA21, CA30, CA31, CA34, CA38 |
| RG19 | CA20, CA32, CA37 |
| RG20 | CA20, CA33, CA34, CA38, CA44 |
| RG21 | CA35, CA36, CA37 |
| RG22 | CA39 |
| RG23 | CA40, CA41 |
| RG24 | CA5, CA42, CA43, CA44 |
| CL1 | CA9, CA28 |
| CL2 | CA7 |
| CL3 | CA7, CA8, CA12 |
| CL4 | CA8 |
| CL5 | CA15, CA21, CA31 |
| CL6 | CA23 |
| CL7 | CA9 |
| CL8 | CA24 |
| CL9 | CA17 |
| CL10 | CA22 |
| CL11 | CA3 |
| CL12 | CA21 |
| CL13 | CA10 |
| CL14 | CA29 |
| CL15 | CA33, CA34 |
| CL16 | CA33 |
| CL17 | CA32 |
| CL18 | CA35 |
| CL19 | CA36 |
| CL20 | CA40, CA41 |
| CL21 | CA23, CA42, CA44 |
| CL22 | CA43 |

---

## 5. Points ouverts

### Points tranchés

**PO1 — Identité du compte à la connexion. TRANCHÉ (utilisateur, 2026-09-28) : option B, pseudo unique globalement.**
La décision remplace la demande initiale d'un pseudo « unique par course ». Connexion par pseudo + mot de passe, sans course (RG9). Options écartées : A (connexion par course + pseudo), C (désambiguïsation par le mot de passe), D (identifiant de connexion distinct du pseudo).

**PO2 — Mécanisme d'authentification. TRANCHÉ (utilisateur, 2026-09-28) : HTTP Basic**, comme les comptes ADMIN et SCANNER. L'API reste sans état ; le stockage des identifiants dans le navigateur suit RG7 (inc. 4). Règles : RG9, RG21. Options écartées : session serveur, JWT.

**PO3 — Durée de vie. TRANCHÉ (utilisateur, 2026-09-28) : 24 h glissantes, gérées côté client.** Avec HTTP Basic, le serveur n'a pas de session : les identifiants mémorisés expirent après 24 h sans activité, sur le modèle du « Rester connecté » de l'inc. 4. Règle : RG21 ; activité = réponse 2xx. Interprétation de RG21 validée telle quelle par l'utilisateur le 2026-09-28 : « Rester connecté » décoché par défaut, activité = réponse 2xx. Remarque : RG7 (inc. 4) fixe pour le SCANNER une expiration **absolue** (connexion + 24 h) ; l'alignement du SCANNER sur l'expiration glissante est traité par l'inc. 7.

**PO4 — Devenir de `Runner.name`. TRANCHÉ (utilisateur, 2026-09-28) : option A, remplacé par le pseudo.** Règles : RG6, RG18, RG13 (corrélation assumée). Sort des noms réels existants : PO13 ; nom des coureurs détachés : PO14 (tous deux tranchés).

**PO5 — Conservation des journaux. TRANCHÉ (utilisateur, 2026-09-28) : 7 jours.** Périmètre (application, nginx) et rotation : RG22.

**PO6 — Inscription et usage du compte. TRANCHÉ (utilisateur, 2026-09-28) : compte obligatoire pour l'inscription publique (H3) ; le compte sert à réafficher ses inscriptions et son QR (H5).** Règles : RG7, RG11.

**PO7 — Changement de mot de passe. TRANCHÉ (utilisateur, 2026-09-28) : le coureur change son mot de passe lui-même ; l'admin réinitialise en saisissant un mot de passe provisoire, sans obligation de changement ensuite.** Règles : RG14, RG19.

**PO8 — Suppression d'un compte. TRANCHÉ (utilisateur, 2026-09-28) : l'admin peut supprimer un compte ; les coureurs sont détachés (`account_id = null`) et gardent leurs passages.** Règle : RG20.

**PO9 — Limitation des tentatives. TRANCHÉ (utilisateur, 2026-09-28) : reverse proxy nginx (`limit_req`) sur `/api/account/**` et sur l'inscription publique, configuration versionnée, rien dans l'application.** Règle : RG23. Seuils : PO16 ; rafale du compte : PO18 (tous deux tranchés).

**PO10 — Mise à jour de `CLAUDE.md`. TRANCHÉ (utilisateur, 2026-09-28) : faite par l'orchestrateur après validation des trois specs.** L'agent fonctionnel ne la modifie pas. Points à reporter : entité `Account` ; `Runner.account_id` ; `Runner.name` remplacé par le pseudo ; incréments 5 à 7 ; authentification HTTP Basic des comptes pseudo ; 24 h glissantes ; journaux 7 jours.

**PO11 — Formats. TRANCHÉ (utilisateur, 2026-09-28) : H1, H2 et H7 validées telles quelles. Unicité : repli « pseudo stocké en minuscules » décidé par l'utilisateur le 2026-09-28 (révision 5).**
- Décision initiale : index unique sur `lower(pseudo)`, avec un point d'arrêt si H2 le refusait.
- Point d'arrêt atteint : H2 (2.4.240 et 2.2.224, mode PostgreSQL) refuse `CREATE UNIQUE INDEX ... ON account (lower(pseudo))` ainsi qu'une colonne générée `STORED` (constat du développeur).
- Repli retenu par l'utilisateur :
  - le pseudo est normalisé (espaces de bord retirés, minuscules) par une fonction unique, avant tout enregistrement et toute recherche (RG2) ;
  - contrainte `UNIQUE` classique sur `account.pseudo` (RG5, RG6) ;
  - le compte garde 3 colonnes (RG1).
- Conséquence acceptée : la casse saisie est perdue ; « Lievre » s'affiche « lievre » partout.
- Critères impactés : CA1, CA2, CA6, CA7, CA8, CA10, CA12, CA14, CA15, CA20, CA25, CA26, CA28, CA30, CA37, CA38, CA42, CA43, CA44.

**PO12 — Inscription d'un titulaire de compte à une autre course. TRANCHÉ (utilisateur, 2026-09-28) : option a, connexion puis « M'inscrire à cette course ».** Règles : RG7 (étape 4), RG8, RG17.

**PO13 — Noms réels des coureurs créés avant l'incrément. TRANCHÉ (utilisateur, 2026-09-28) : option a, purge par la migration V2.** Tous les `runner.name` existants sont mis à `null`, quel que soit le statut de la course ; ces coureurs s'affichent « Coureur n°{bib} ». La purge est irréversible (conséquence assumée). La colonne est vidée et conservée, pas supprimée : choix confirmé (utilisateur, 2026-09-28). Règles : RG6, RG18. Critères : CA21, CA31.

**PO14 — Nom d'un coureur détaché de son compte supprimé. TRANCHÉ (utilisateur, 2026-09-28) : option a, libellé neutre « Coureur n°{bib} ».** Aucune copie du pseudo n'est conservée. Tous les coureurs sans compte suivent donc une seule règle d'affichage. Règles : RG18, RG20. Critères : CA34, CA38.

**PO15 — Choix du référentiel quand un même nom existe dans deux référentiels. TRANCHÉ (utilisateur, 2026-09-28) : option a, référentiel choisi par le préfixe d'URL.**
- `/api/account/**` interroge uniquement les comptes coureurs.
- `/api/scan/**` et `/api/admin/**` interrogent uniquement les comptes staff (configuration, et scanneurs déclarés de l'inc. 7).
- Choix confirmé (utilisateur, 2026-09-28) : `/api/public/**` et les URL inconnues restent sur le référentiel staff, pour ne pas modifier RG29 et RG30 (inc. 3). Des identifiants coureur y donnent donc 401 ; la PWA ne les y envoie jamais.
- Les accès croisés passent de 403 à 401 : **évolution motivée** de RG10, CA13, CA18, CA22, CA32 et CA33.
- L'hypothèse H10 (essai successif) est retirée.
Règles : RG9, RG10. Les incréments 6 (matrice de la section 4) et 7 (RG1, RG4) appliquent la même décision.

**PO16 — Seuils de limitation de débit. TRANCHÉ (utilisateur, 2026-09-28) : option c, seuils distincts.**
- Inscription publique : 10 requêtes par minute et par IP, rafale de 5.
- `/api/account/**` : 30 requêtes par minute et par IP, pour les coureurs derrière une même IP.
- L'hypothèse H9 est retirée. Le mode `nodelay` de H9 est conservé pour les deux zones, et ce choix est confirmé (utilisateur, 2026-09-28) : c'est lui qui rend le seuil exact et testable, puisque la requête en excès est refusée au lieu d'être retardée.
Règle : RG23. Critère : CA40. Rafale de `/api/account/**` : PO18.

**PO17 — Accès de l'admin à un compte sans inscription. TRANCHÉ (utilisateur, 2026-09-28) : option b, écran admin « Comptes ».**
- L'écran permet de lister les comptes, de chercher par pseudo, de réinitialiser un mot de passe et de supprimer un compte.
- Nouvel endpoint **E25** `GET /api/admin/accounts?pseudo=`, à la suite de E24, sans trou dans la numérotation. Les endpoints de l'inc. 7 sont décalés d'un rang (E26 à E30).
- Choix confirmé (utilisateur, 2026-09-28) : recherche « contient », insensible à la casse et littérale ; aucune pagination.
Règles : RG17, RG24. Critères : CA42, CA43, CA44.

**PO18 — Rafale nginx pour `/api/account/**`. TRANCHÉ (utilisateur, 2026-09-28) : option a, rafale de 10.**
- L'hypothèse H11 est confirmée et devient une règle : 30 requêtes par minute et par IP, rafale de 10, `nodelay`.
- 11 requêtes simultanées sont admises, puis une toutes les 2 s.
- Options écartées : b (rafale de 29) et c (rafale de 5).
Règle : RG23. Critère : CA40. L'inc. 7 reprend ces seuils pour E19 (PO11 inc. 7).

Aucun point ouvert ne subsiste pour cet incrément.
