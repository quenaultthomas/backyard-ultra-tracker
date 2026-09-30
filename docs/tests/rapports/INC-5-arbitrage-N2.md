# INC-5 — Arbitrage N2 : adaptations E2E et écarts de la revue de cohérence

> Décideur : agent fonctionnel. Date : 2026-09-30.
> Entrées : `docs/specs/increment5.md` (révision 5, avec les précisions du 2026-09-30 listées en §7), `INC-5-arbitrage-N1.md`, `INC-5-integration.md`, `INC-5-e2e.md`, `INC-5-coherence.md`, `PATRIMOINE.md`, diff lu dans `ca36-admin-crud.spec.ts` et `ApiExceptionHandler.java:145-152`.
> **Ce document ne rend pas le verdict GO / NO-GO.** Il tranche les écarts et fixe la liste fermée de ce qui reste à faire (§6). Le verdict viendra après la synthèse et ces corrections.

## 0. Synthèse

| Écart | Décision |
|---|---|
| COH5-2 (E2E A1-A3, B1-B16) | **Accord** sur toutes les lignes. B16 : accord (catégorie B, renommage du titre autorisé). Filtre `qr-card` de A2 : accord, reclassé « A, changement de sélecteur de cible » |
| COH5-3 (pseudo dans le WARN des 409) | **Retirer le `detail` du journal** (correction de production + test). RG16 et CA20 précisés (pas amendés à la baisse) |
| COH5-4 (CA1, CA8) | **Bloquant avant verdict** |
| COH5-5 (CA17, CA34, CA31) | CA17 et CA34 : **bloquant avant verdict** (coût faible, même agent que COH5-4). CA31 : **réserve acceptée** |
| COH5-7 (revue CA2 non automatisée) | **Réserve acceptée**, avec échéance |
| COH5-8 (CA10) | **Correction de spec faite** (rédaction) |
| COH5-1 (matrice front-unit) | **À corriger avant verdict** |
| COH5-6 (doublon du lien) | **Non bloquant**, réserve acceptée |

## 1. COH5-2 — adaptations E2E (règle 2 du workflow, note N1)

Critère appliqué : nombre de `test(` inchangé, aucune assertion retirée ni affaiblie, aucun timeout modifié, aucun test désactivé (constaté par la revue de cohérence §3.1 et par `git diff`). Toute assertion sur un nom porte sur la valeur réellement créée et renvoyée par l'API, en égalité exacte.

### Catégorie A

| # | Décision | Motif |
|---|---|---|
| A1 `fixtures/api.ts` | **Accord global** | Fixture de jeu de données : E3 avec `{"pseudo","password"}` valides (seul corps valide, RG7). Le contrôle ajouté (`name` et `pseudo` renvoyés égaux au pseudo envoyé, RG18) est un ajout, pas un affaiblissement. Le suffixe aléatoire est justifié par l'unicité globale (RG5) sur base partagée. Les nouvelles méthodes (`registerAccount`, `registerExistingAccount`, `accountMeStatus`, `adminAccounts`, `deleteRunner`) sont des ajouts |
| A2 `ca36-admin-crud`, sélecteurs `exact: true` sur « Supprimer » (3 endroits, dont le `toHaveCount(0)` de la barre d'outils) | **Accord** | Même bouton visé ; le mode strict échouait à cause de « Supprimer le compte » (RG17). La valeur attendue `toHaveCount(0)` n'est pas assouplie : `exact: true` la rend au contraire précise |
| A2 `ca36-admin-crud`, filtre `li.qr-card` : `hasText: String(runner.bib)` → `hasText: runner.name` | **Accord expresse, reclassé « A, sélecteur de cible »** | Ce n'est pas une adaptation de valeur attendue. Le filtre sert à retrouver la vignette du coureur ; un dossard `1` ou `2` en sous-chaîne devient ambigu avec un pseudo à suffixe aléatoire (le filtre par nom est donc plus discriminant, pas moins). Les assertions restent : `toHaveCount(2)` sur `li.qr-card` (ligne 101) et « QR décodé = `qrToken` de ce coureur » (ligne 104-108). Condition remplie : le nom est unique (suffixe aléatoire) et vient de l'API |
| A3 `fixtures/ui.ts`, `fixtures/storage.ts` | **Sans objet** | Fichiers nouveaux, non des adaptations. Pas d'accord nécessaire ; ils sont couverts par la revue générale de qualité |

### Catégorie B (attendu modifié par la spec, remplacement aussi strict)

| # | Décision | Motif |
|---|---|---|
| B1 à B14 | **Accord ligne à ligne** | Nom fixe → `runner.name` renvoyé par E3 (égal au pseudo créé). Égalité exacte conservée (RG18, RG2). Chaque test garde le même nombre d'assertions et les mêmes autres valeurs |
| B15 `ca22-registration` | **Accord** | Formulaire réécrit (`Nom` → `Pseudo` + mot de passe + confirmation, RG17). `getByText('Alice', {exact:false})` devient `getByText('alice-{run}', {exact:true})` : plus strict. Dossards 1, 2, 3, présence du QR, décodage = `qrToken` inchangés. Pseudos saisis avec majuscules et attendus en minuscules : conforme à RG2 |
| B16 `ca23-registration-errors` | **Accord expresse, catégorie B** | Le champ « Nom » n'existe plus, donc « nom blanc » n'est plus exprimable ; « pseudo blanc » est le remplaçant direct (RG2, RG17) : mêmes 4 cas, mêmes saisies blanches, sélecteur d'erreur renommé (`#registration-pseudo-error`), mots de passe valides ajoutés pour que seul le pseudo soit fautif (sinon l'assertion « erreur sur le pseudo seul » serait faussée). Les cas double clic (1 requête E3), inscriptions fermées et course inconnue sont inchangés. Le **renommage du titre** est autorisé, par analogie avec R1 (arbitrage N1), et il ne s'agit pas d'une catégorie C (aucun test supprimé, désactivé ni affaibli). Tag `CA23`/référence d'origine conservés |

Aucune adaptation refusée. Aucune catégorie C. La note « les noms de fixtures ne peuvent plus figurer dans l'interface » est justifiée (RG2).

## 2. COH5-3 — pseudo dans le WARN d'un 409 de E3 : décision

**Décision : retirer le `detail` de la ligne de journal.** Amender RG16 / CA20 à la baisse est refusé.

Motifs :
- RG16 et `CLAUDE.md` (minimisation, journaux qui contiennent déjà IP et horodatages) : un pseudo dans un journal serveur, associé à une IP et à une heure, est une donnée personnelle supplémentaire, sans utilité d'exploitation (le `code` d'erreur et le chemin suffisent au diagnostic) ;
- le comportement est corrigeable en un seul endroit (`ApiExceptionHandler.respond`, ligne 148) et sans duplication ;
- la spec a été précisée (RG16 et CA20, 2026-09-30) : **aucune ligne de journal ne contient un pseudo**, y compris le WARN des 4xx, qui n'écrit plus le `detail` mais méthode, chemin, statut et `code`.

Exigences :
1. developpeur : la ligne WARN de `respond` n'écrit plus `problem.getDetail()`. Le `code` d'erreur est écrit à la place. Aucun autre changement du corps de la réponse HTTP.
2. test-integration-backend : dans `AccountFlowIT#ca20_logsWithoutSecretsNorPseudo` (ou un test voisin), capturer les journaux pendant un E3 en 409 (pseudo déjà pris, saisie `Lievre` avec compte `lievre`) : aucune ligne ne contient `lievre` (insensible à la casse), alors que le corps de la réponse le contient. Le test doit échouer avant la correction.
3. La revue de cohérence et le testeur vérifient qu'aucun test existant ne dépendait du `detail` dans le WARN (recherche `Requête refusée`).

## 3. COH5-4, COH5-5, COH5-7, COH5-8

| ID | Décision | Détail et action |
|---|---|---|
| COH5-4 (a) CA1 : contrainte `(race_id, account_id)` et clé étrangère `RESTRICT` non testées | **Bloquant avant verdict** | Clause explicite de CA1 sans test. test-integration-backend ajoute, dans `AccountFlowIT#ca1_schema` ou un test voisin : tentative d'insertion SQL native de deux coureurs de la même course liés au même compte, rejetée (`DataIntegrityViolationException`) ; et suppression SQL native d'un compte porteur d'un coureur, rejetée (RESTRICT). Ou lecture du catalogue H2 équivalente |
| COH5-4 (b) CA8 : variante « même course » non jouée | **Bloquant avant verdict** | test-integration-backend ajoute les variantes : `Tortue` / `Tortue` en parallèle sur R1 et R1 ; et `Tortue` / `Tortue` en parallèle sur R1 et R2 (le CA cite les deux) ; chacune : une 201, une 409 (`BUSINESS_CONFLICT` ou `DATA_INTEGRITY`), 1 compte `tortue`, 1 coureur lié |
| COH5-5 CA17 : E23 puis E20 non enchaînés après E22 | **Bloquant avant verdict** (coût faible) | À ajouter dans le même passage que COH5-4 : après E22 et connexion avec `nouveau-mdp-42`, E23 accepté (204) puis E20 accepté (201) sans changement préalable |
| COH5-5 CA34 : E5 d'un coureur détaché non asserté | **Bloquant avant verdict** (coût faible) | Ajouter l'assertion `name = "Coureur n°1"` sur E5 du coureur détaché de R1 (CA34, première puce) |
| COH5-5 CA31 : volet HTTP joué sur une base déjà en V2 | **Réserve acceptée** | Le fond (purge de `runner.name`, données et passages préservés) est prouvé par `V2MigrationIT`, et l'affichage « Coureur n°{bib} » ne dépend pas de `runner.name` (RG18). Condition : la synthèse confirme que `V2MigrationIT` vérifie `count(name IS NOT NULL) = 0`, `account_id = null`, dossard, statut, `qrToken`, 2 passages, et Bob (`dnf_reason`, `dnf_yard`). Si ce n'est pas le cas, la réserve tombe et l'écart redevient bloquant. Échéance : le complément HTTP sur base migrée depuis V1 est fait au plus tard avant le verdict de l'inc. 6 |
| COH5-7 Revue de CA2 non automatisée | **Réserve acceptée** | La revue manuelle de la cohérence (une seule occurrence de `toLowerCase`, aucune requête `lower`/`upper`/`IgnoreCase`/`ILIKE`) satisfait CA2 tel qu'écrit (« Revue »). Action : le testeur ajoute un test de lecture de sources (sans nouvelle dépendance, ou ArchUnit s'il existe déjà) qui fige la règle. Échéance : avant le verdict de l'inc. 6 |
| COH5-8 CA10 : `accountId` absent de la réponse E20 | **Correction de spec faite** | CA10 réécrit dans `docs/specs/increment5.md` : la réponse ne porte pas `accountId` (RG8, RG13) ; le lien se vérifie en base ou par E13. Le test `AccountContractIT#ca10_...` est donc conforme à la spec corrigée. E-INC5-2 est clos |

Corrections de rédaction faites en même temps (2026-09-30) : erratum RG18 (E6 renvoie `runnerName` = nom affiché, arbitrage N1 point 2) ; CA37 (« en clair », OBS-E2E-2 : la valeur `Authorization` en base64 dans IndexedDB est prévue par RG21 et acceptée) ; RG16 et CA20 (COH5-3).

## 4. COH5-1 et COH5-6

- **COH5-1 : à corriger avant verdict.** La règle 1 du workflow (toute exigence a au moins un test référencé) s'applique à la matrice elle-même : CA27, CA35, CA36 (et CA41 côté unité, D1, RG21, RG17) n'ont aucune ligne. Ce sont des tests réels et verts (vitest 235/235), donc le risque est documentaire, mais je ne peux pas vérifier un CA sans ligne de matrice. Action : testeur, section « Incrément 5 — front-unit » de `PATRIMOINE.md`, ligne `RunnerAccountLinkTest`, et mise à jour de la ligne `INC4-CA20` (adaptation N1 de `action-visibility.spec.ts`, arbitrage N1 §6 point 4). Les lignes « Historique des validations » et « Écarts ouverts » seront mises à jour par moi au verdict.
- **COH5-6 : non bloquant.** Le doublon du lien « J'ai déjà un compte » après un 409 n'est pas une violation axe (0 serious/critical) et CA25 est vérifié. Réserve acceptée, sans test complémentaire. Action optionnelle (developpeur, inc. 6 ou plus tard) : ne rendre le lien qu'une fois. Si cette correction est faite, le test `.first()` doit être resserré en `toHaveCount(1)` dans la même modification.

## 5. Points repris de l'arbitrage N1 non confirmés

- **D2** (E25 hors de tout rafraîchissement périodique) : le rapport du développeur n'est pas fourni (constat cohérence §5). Il est exigé avant le verdict (voir §6).
- **Compte rendu de vérification du testeur** (N1 étape 3 : diff des tests existants comparé à la liste) : absent des rapports. La revue de cohérence l'a refait, ce qui suffit pour le fond ; le testeur doit néanmoins le consigner dans la synthèse.

## 6. Liste fermée des actions avant le verdict final

| # | Action | Agent responsable | Bloquant |
|---|---|---|---|
| 1 | Retirer le `detail` de la ligne WARN de `ApiExceptionHandler.respond` (écrire méthode, chemin, statut, `code`) | developpeur | Oui |
| 2 | Test de journal : E3 en 409 sans occurrence de `lievre` dans les journaux capturés (échoue avant la correction) ; extension de `ca20_logsWithoutSecretsNorPseudo` | test-integration-backend | Oui |
| 3 | CA1 : contrainte `(race_id, account_id)` et FK `RESTRICT` | test-integration-backend | Oui |
| 4 | CA8 : variantes `Tortue`/`Tortue` sur R1+R1 et sur R1+R2 | test-integration-backend | Oui |
| 5 | CA17 : E23 puis E20 après E22 | test-integration-backend | Oui |
| 6 | CA34 : E5 du coureur détaché = « Coureur n°1 » | test-integration-backend | Oui |
| 7 | Confirmer par écrit le comportement de D2 (E25 hors du rafraîchissement périodique) ; si non conforme, corriger | developpeur | Oui |
| 8 | Section « Incrément 5 — front-unit » de `PATRIMOINE.md` (CA27, CA35, CA36, CA41 unité, D1, RG17, RG21), ligne `RunnerAccountLinkTest`, mise à jour de `INC4-CA20` ; mise à jour des lignes E2E et des tests ajoutés (2 à 6) | testeur | Oui |
| 9 | Relancer `mvn -B -f backend/pom.xml clean verify` après les points 1 à 6 (chiffres réels, date), relancer les E2E de `@INC-5` si le développeur a touché le front (point 7), et vérifier qu'aucun test existant ne dépendait du `detail` dans le WARN | testeur | Oui |
| 10 | Rapport de synthèse `INC-5-synthese.md` : recopier le résultat de l'étape 9, la vérification N1 étape 3, la confirmation `V2MigrationIT` (COH5-5 CA31), la liste des réserves connues (CA39 et CA40 manuel VPS, RT1 PostgreSQL réel, LIM-E2E-1 CA39 Chromium, COH5-5 CA31, COH5-6, COH5-7) ; section 8 laissée vide pour l'agent fonctionnel | testeur | Oui |
| 11 | Mise à jour de la revue de cohérence (COH5-1, 3, 4, 5 levés ou maintenus), sur demande de l'orchestrateur si le testeur ne l'a pas couverte | revue-coherence-patrimoine | Non, mais souhaitable pour le verdict |

Réserves d'ores et déjà acceptées pour le verdict (chacune avec action et échéance à reporter dans le verdict) :
- CA39 et CA40, parties `[manuel]` sur le VPS : action de l'exploitant, à l'installation des fichiers `deploy/` ; échéance avant la mise en service de l'inc. 5 ;
- RT1 (PostgreSQL réel : `V2__account.sql`, `LIKE ... ESCAPE`, index unique concurrent, `uq_runner_race_account`) : échéance avant la mise en production ;
- CA31 volet HTTP sur base migrée depuis V1 (COH5-5) : avant le verdict de l'inc. 6 ;
- COH5-7 (test de lecture de sources pour RG2) : avant le verdict de l'inc. 6 ;
- COH5-6 (doublon du lien, non bloquant) : au gré du développeur, sans échéance ;
- LIM-E2E-1, CA39 Chromium (préexistant depuis l'inc. 4) : conservé au patrimoine, sans régression nouvelle.

## 7. Modifications de spec faites lors de cet arbitrage (rédaction, aucune RG ni CA ajouté)

Fichier `docs/specs/increment5.md` :
- CA10 : la réponse E20 ne porte pas `accountId` ; le lien est vérifié en base ou par E13 ;
- RG18 : erratum E6 (`runnerName`) ;
- RG16 et CA20 : aucune ligne de journal ne contient un pseudo, y compris le WARN des 4xx (sans `detail`) ;
- CA37 : « en clair ».
