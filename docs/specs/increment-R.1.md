# Increment R.1 : Charte graphique et fondations du thème

Premier incrément du jalon R (refonte graphique). Il pose les **fondations visuelles** : la charte `docs/design.md`, les variables CSS globales, les styles de base partagés (boutons, champs, cartes, messages, pastilles de statut), les polices auto-hébergées, le motif de fond, et applique la nouvelle palette et la nouvelle typographie à l'en-tête et au corps de **tous les écrans existants**. **Aucun back, aucun contrat d'API, aucune règle métier, aucun texte, aucune structure** (arbre DOM, routes, `data-testid`) ne change. **Aucune migration, aucune variable d'environnement, aucune dépendance npm.**

## Ce qui est déjà livré (0.x à 3.6), donc non respécifié

Vérifié dans le code :
- `frontend/src/styles.css` : 22 lignes (`color-scheme: light`, pile `system-ui`, texte `#111827`, fond `#f9fafb`, `app-root` en colonne `min-height: 100dvh`). Aucun fichier de variables ni de polices.
- 20 fichiers CSS portent 88 couleurs en dur (bleu `#1d4ed8`, gris `#6b7280`, rouge `#b91c1c`, ambre de focus `#f59e0b`, en-tête `#111827`…). Styles partagés actuels : `partage/page-carte.css` (`.page`, `.carte`, `.erreur`, `.succes`, `.lien`), `comptes/formulaire-compte.css` (`.champ`, `input`, `.bouton`, `.message-succes`, `.message-info`), `administration/courses/action-ligne.css` (`.ligne__action` et variantes `--danger`, `--danger-plein`), `partage/confirmation-en-ligne.css`.
- En-tête `entete/` : `.entete`, `.entete__titre`, `.entete__lien`, `.entete__bouton`, `.entete__erreur`, `data-testid` `entete-titre`, `lien-se-connecter`, `entete-pseudo`, `lien-administration`, `lien-espace-benevole`, `lien-espace-coureur`, `lien-mes-inscriptions`, `lien-mon-compte`, `bouton-deconnexion`, `entete-erreur`. **Inchangés** (R.2 et R.3 les remplacent par un menu burger).
- `index.html` : `lang="fr"`, viewport, favicon ; aucune police ni `theme-color`.
- `Caddyfile` : CSP `default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:` (donc `font-src` retombe sur `'self'` : **aucune modification de la CSP**) ; cache `immutable` d'un an pour `\-[A-Z0-9]{8}\.(js|css)$`, `no-cache` pour le reste (donc pour `index.html`).
- `angular.json` : `styles: ["src/styles.css"]`, budgets `anyComponentStyle` 4 kB (avertissement) / 8 kB (erreur) ; `optimization.fonts` n'inline que les polices Google (non utilisé ici).
- Composants : QR code `inscriptions-qr` (SVG, `fill="#fff"` / `#000` en attribut) ; statuts affichés sous `data-testid` `inscriptions-statut`, `inscriptions-course-statut`, `benevole-course-statut` (`<span>`), `fiche-inscrit-statut` (`<td>`), `fiche-statut`, `course-statut` (`<dd>`).
- E2E : 23 fichiers dans `e2e/tests/` (dont les aides `aide-connexion.ts`, `aide-admin.ts`, `aide-coureur.ts`), sélecteurs quasi uniquement par `data-testid`. Contraintes de DOM à ne pas casser : `fiche-inscrits` ne contient ni `canvas`, ni `img`, ni `svg`, ni `<b>` (`inscrits-course-admin.spec.ts`) ; la page d'inscription ne contient ni `canvas` ni `svg[data-testid*="qr"]` ni `img[alt*="QR"]` avant l'inscription (`inscription-course.spec.ts`). **Les puces, filets et motifs de R.1 sont donc uniquement en CSS (`::before`, `background`), jamais des éléments `svg`/`img`/`canvas` du DOM.**

Hypothèse : jalons 0 à 3 mergés. Aucun sélecteur des tests existants n'a besoin de changer (RG22).

Taille estimée (production hors tests, hors `docs/`) : CSS neuf ~290 lignes (`variables.css` ~75, `polices.css` ~15, `base.css` ~55, `composants.css` ~145), migration des 20 CSS existants ~90 lignes modifiées (valeurs en dur -> `var()`, doublons supprimés), 3 templates (classe de pastille) ~6 lignes, `styles.css` ~6, `index.html` ~2, `Caddyfile` ~2, SVG du motif ~30. Total ~430 : légèrement au-dessus de la cible, découpage possible (point ouvert 11).

## 1. Périmètre

**Inclus**
- **Direction artistique tranchée** (RG2) : thème clair sur fond crème, Inter auto-hébergée, motif de courbes de niveau avec boucle fermée.
- `docs/design.md` : la charte complète et illustrée (RG20).
- Variables CSS globales (RG5), typographie et polices auto-hébergées (RG6, RG7), espacements, rayons, ombres (RG8), motif de fond (RG9).
- Styles de base partagés (RG10 à RG15) : base, boutons principal / secondaire / danger, champs, cartes, messages, pastilles de statut.
- Application à l'en-tête et au corps de page de tous les écrans existants (RG16, RG17), responsive téléphone d'abord (RG18), navigation clavier (RG19).
- Aperçu de chaque composant : `docs/design-apercu.html` (RG20).
- Non-régression : E2E existants inchangés (RG21).

**Exclu**
- Menu burger visiteur (R.2) et connecté (R.3) ; composant d'en-tête partagé (R.2) : l'en-tête garde sa structure et ses liens alignés.
- Page d'accueil (sapins, hero) : R.4. Liste des courses en cartes : R.5 et suivants (le roadmap R.x le précise). Application des pastilles aux cellules `<td>` / `<dd>` de statut : incréments R suivants (point ouvert 4).
- Mode sombre et bascule jour/nuit : hors périmètre (décision utilisateur ; point ouvert 12).
- Logo de l'application, favicon, icônes PWA : inchangés. Précache des polices par le service worker : jalon 5 (point ouvert 10).
- Écrans des jalons 4 à 6 : ils utiliseront la charte (décision du roadmap « Thème graphique »), sans travail ici.
- Tout changement de texte, de route, de `data-testid`, d'ordre ou de nombre d'éléments, de comportement, d'API, de back, de `docker-compose*.yml`, `.env.example`, `docs/deploiement.md`, `scripts/donnees-demo.sh`.
- Tests unitaires front (interdits par CLAUDE.md) ; dépendances npm, CDN, bibliothèque de composants, Tailwind, Sass : interdits.

**Fichiers modifiés ou créés (indicatif)** : nouveaux `frontend/src/styles/{variables,polices,base,composants}.css`, `frontend/src/styles/polices/inter-4.1-latin-variable.woff2`, `frontend/src/styles/motif/courbes-niveau.svg`, `frontend/public/licences/Inter-OFL.txt`, `docs/design.md`, `docs/design-apercu.html`, `docs/images/design-apercu.png` ; modifiés `frontend/src/styles.css`, `frontend/src/index.html` (`theme-color`), `frontend/Caddyfile` (regex de cache), les 20 CSS existants, 3 templates (classe de pastille) et leur `.ts` (table statut -> classe) ; tests E2E nouveaux (`e2e/tests/theme-*.spec.ts`).

## 2. Règles de gestion

**Cadre**
- **RG1** : refonte purement visuelle. Interdits : modifier un texte, une balise, l'ordre ou le nombre des éléments, une route, un `data-testid`, un attribut ARIA existant, un appel d'API, du code `backend/`. Autorisé : changer ou ajouter des **noms de classes CSS**, ajouter des feuilles de style, des règles `:root`, du CSS générant du contenu décoratif (`::before`, `::after` sans texte). Le seul changement de template est la classe des pastilles (RG15) ; il n'ajoute aucun élément.
- **RG2** : direction artistique (décisions de l'utilisateur, à consigner dans `docs/design.md`) : (1) thème **clair** sur fond **crème**, `color-scheme: light`, pas de mode sombre ; (2) police **sans-serif sobre auto-hébergée, Inter** (SIL OFL), chiffres **tabulaires** pour dossards et chronomètres, **pas de police display** ; (3) motif de fond = **courbes de niveau discrètes en SVG avec une boucle fermée** évoquant le yard ; les sapins sont réservés à l'accueil (R.4) ; (4) univers : une boucle de 6,7 km refaite toutes les heures, en forêt : **vert forêt profond, orange balise/frontale, crème** ; (5) pas de dépendance lourde, aucune requête vers un CDN ; (6) responsive téléphone d'abord, contrastes AA, navigation clavier.

**Palette et variables**
- **RG3** : palette. Les couleurs sont exactement celles du tableau ci-dessous (valeurs hexadécimales, nom de variable). Le thème n'emploie **aucune autre couleur** (ni noir pur ni blanc pur) hors QR code (RG17).

| Variable | Hex | Usage |
|---|---|---|
| `--couleur-foret` | `#14352A` | en-tête, bouton principal, titres, pastille `TERMINEE` |
| `--couleur-foret-survol` | `#1F5A43` | survol du bouton principal, liens |
| `--couleur-creme` | `#F7F1E3` | fond de page, texte sur forêt |
| `--couleur-surface` | `#FFFCF5` | cartes, champs, bouton secondaire |
| `--couleur-sable` | `#EFE6D0` | message d'information, survol secondaire, pastille `EN_PREPARATION` |
| `--couleur-trait` | `#D9CFB6` | filets, bordures de cartes, traits du motif (jamais de texte) |
| `--couleur-encre` | `#1B2A22` | texte courant |
| `--couleur-encre-douce` | `#4A5A50` | texte secondaire, aides |
| `--couleur-orange` | `#F28C28` | orange balise : accents et texte **sur fond sombre** uniquement, pastille `VAINQUEUR` |
| `--couleur-orange-fonce` | `#A84300` | texte et focus orange **sur fond clair**, pastille `EN_COURS` |
| `--couleur-orange-pale` | `#FBE9CF` | message d'avertissement, fond pastille `EN_COURS` |
| `--couleur-danger` | `#9B1C1C` | bouton danger, erreurs |
| `--couleur-danger-survol` | `#7F1D1D` | survol du bouton danger |
| `--couleur-danger-pale` | `#FBE9E4` | fond des erreurs, pastille `ABANDON` |
| `--couleur-succes-pale` | `#E3EFE6` | fond des succès, pastille `EN_COURSE` |
| `--couleur-bordure-champ` | `#6F7A6E` | bordure des champs et du bouton secondaire inactif |
| `--couleur-desactive-fond` | `#DDD6C3` | contrôle désactivé |
| `--couleur-desactive-texte` | `#5F6A62` | texte désactivé (exempt de contraste AA, WCAG 1.4.3) |

  Alias sémantiques (valeurs `var()` des précédentes) : `--couleur-fond-page` = crème, `--couleur-texte` = encre, `--couleur-lien` = forêt-survol, `--couleur-focus` = orange-fonce, `--couleur-focus-sur-fonce` = orange.
- **RG4** : contrastes AA, formule WCAG 2.x : `L = 0,2126 R + 0,7152 G + 0,0722 B` (canaux linéarisés), ratio = `(Lclaire + 0,05) / (Lsombre + 0,05)`. Seuils : texte normal >= 4,5 ; texte large (>= 24 px, ou >= 18,66 px en gras) et éléments non textuels (bordures de champ, focus, puces) >= 3. Tout couple texte / fond du thème figure dans `docs/design.md` avec son ratio. Couples de référence (ratios calculés ; à recalculer lors de la rédaction de `design.md`, point ouvert 6) :

| Couple (texte / fond) | Ratio | Seuil |
|---|---|---|
| encre / crème | 13,31 | 4,5 |
| encre / surface | 14,63 | 4,5 |
| encre-douce / crème | 6,50 | 4,5 |
| encre-douce / surface | 7,14 | 4,5 |
| encre-douce / sable | 5,89 | 4,5 |
| encre-douce / trait (pire cas du motif) | 4,72 | 4,5 |
| encre / sable | 12,06 | 4,5 |
| crème / forêt (en-tête, pastille `TERMINEE`) | 11,86 | 4,5 |
| surface / forêt (bouton principal) | 13,04 | 4,5 |
| surface / forêt-survol (bouton survolé) | 7,88 | 4,5 |
| forêt-survol / crème (liens) | 7,17 | 4,5 |
| forêt-survol / surface (liens en carte) | 7,88 | 4,5 |
| orange / forêt (liens de l'en-tête) | 5,44 | 4,5 |
| encre / orange (pastille `VAINQUEUR`) | 6,11 | 4,5 |
| orange-fonce / crème | 5,38 | 4,5 |
| orange-fonce / surface | 5,91 | 4,5 |
| orange-fonce / orange-pale (avertissement, pastille `EN_COURS`) | 5,09 | 4,5 |
| surface / danger (bouton danger) | 7,96 | 4,5 |
| surface / danger-survol | 9,78 | 4,5 |
| danger / crème | 7,24 | 4,5 |
| danger / danger-pale (erreurs, pastille `ABANDON`) | 6,94 | 4,5 |
| forêt / succes-pale (succès, pastille `EN_COURSE`) | 11,30 | 4,5 |
| bordure-champ / surface (non textuel) | 4,37 | 3 |
| bordure-champ / crème (non textuel) | 3,98 | 3 |
| orange / forêt (focus dans l'en-tête, non textuel) | 5,44 | 3 |
| orange-fonce / crème ou surface (focus ailleurs, non textuel) | 5,38 / 5,91 | 3 |

  `--couleur-orange` n'est **jamais** employé en texte sur crème ou surface (ratio 2,18). La couleur ne porte jamais seule une information : chaque pastille et chaque message contient son libellé en texte.
- **RG5** : variables globales. Définies sur `:root` dans `frontend/src/styles/variables.css`, noms **en français, préfixés `--`**, exactement ceux de la RG3 plus ceux des RG6 et RG8. **Aucune valeur de couleur, d'espacement, de rayon, d'ombre ni de police en dur** hors de ce fichier (et hors `docs/`) : les 20 CSS existants et les nouveaux n'emploient que `var(--…)`. Aucune variable inutilisée dans la charte ; aucun nom en anglais.

**Typographie, polices**
- **RG6** : typographie. `--police-texte` = `'Inter', system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif` ; appliquée à `body` et héritée par `button`, `input`, `select`, `textarea` (`font: inherit`). Corps 1rem / interligne 1,5 (`--interligne-texte`), titres interligne 1,2 (`--interligne-titre`), graisses `--poids-normal` 400, `--poids-moyen` 500, `--poids-gras` 700 (les 600 existants restent rendus par la police variable). Échelle : `--taille-petite` 0,8125rem (13 px, minimum de l'interface), `--taille-secondaire` 0,875rem, `--taille-base` 1rem, `--taille-moyenne` 1,125rem, `--taille-grande` 1,25rem, `--taille-titre` 1,75rem. Les tailles déjà en `rem` dans les écrans sont conservées (pas de saut de mise en page). **Chiffres tabulaires** : `font-variant-numeric: tabular-nums` sur `body` (donc sur dossards, durées, distances, et le futur chronomètre) ; un test de largeur le vérifie (CA3). Pas de police display ; un seul style (droit).
- **RG7** : polices auto-hébergées. (1) Fichier unique `frontend/src/styles/polices/inter-4.1-latin-variable.woff2` : Inter 4.1, sous-ensemble **latin** (couvre les accents français, `œ`, `’`, `«»`, `…`, `€`), axe de graisse variable 400 à 700, format **woff2 seul**, **<= 100 Ko** ; récupéré une fois puis versionné dans le dépôt (aucune dépendance npm). (2) `polices.css` : un `@font-face` `font-family: 'Inter'`, `font-weight: 400 700`, `font-style: normal`, **`font-display: swap`**, `unicode-range` latin, `src: url('./polices/inter-4.1-latin-variable.woff2') format('woff2')` (URL relative : le build Angular l'empaquette et la renomme avec un empreinte, ex. `/media/inter-4.1-latin-variable-AB12CD34.woff2` ; si le build ne l'empreinte pas, c'est un écart à signaler). (3) **Licence** : SIL Open Font License 1.1 ; texte complet dans `frontend/public/licences/Inter-OFL.txt`, servi à `/licences/Inter-OFL.txt` ; provenance (URL de publication, version, somme SHA-256 du fichier) consignée dans `docs/design.md`. (4) **Aucune requête externe** : ni `@import` d'URL, ni `<link>` vers Google Fonts ou tout CDN, ni `preconnect`. (5) **Cache Caddy** : l'expression `\-[A-Z0-9]{8}\.(js|css)$` du `Caddyfile` devient `\-[A-Z0-9]{8}\.(js|css|woff2|svg)$` (polices et motif empreints : `Cache-Control: public, max-age=31536000, immutable`) ; `index.html` reste `no-cache` ; le type de contenu d'une police est `font/woff2` (à ajouter par `header` si Caddy ne le déduit pas). La CSP n'est pas modifiée.

**Espacements, rayons, ombres, motif**
- **RG8** : `--espace-1` 0,25rem, `--espace-2` 0,5rem, `--espace-3` 0,75rem, `--espace-4` 1rem, `--espace-5` 1,25rem, `--espace-6` 1,5rem, `--espace-8` 2rem, `--espace-12` 3rem ; `--rayon-s` 0,375rem, `--rayon-m` 0,75rem, `--rayon-pastille` 999px ; `--ombre-carte` = `0 1px 2px rgb(20 53 42 / 0.10), 0 4px 12px rgb(20 53 42 / 0.08)` (jamais de noir pur) ; `--cible-min` 2,75rem (44 px, cible tactile) ; `--largeur-carte` 26rem ; `--duree-transition` 150ms.
- **RG9** : motif de fond. Fichier `frontend/src/styles/motif/courbes-niveau.svg`, tuile **sans couture** `viewBox="0 0 480 480"` répétée (`background-repeat: repeat`) sur `body`, au-dessus de `--couleur-creme` ; poids **<= 4 Ko** ; contenu : 5 à 7 `<path>` de **courbes de niveau** (traits `fill="none"`, `stroke-width` 1 à 1,5, les courbes sortant d'un bord rentrent à la même ordonnée / abscisse du bord opposé) et **une boucle fermée** (un `<path>` terminé par `Z`, traits plus épais 2 et pointillés) évoquant le yard, entièrement contenue dans la tuile ; **une seule couleur de trait, `#D9CFB6`** (donc le pire contraste d'un texte posé directement sur le motif est celui de `--couleur-encre-douce` / `--couleur-trait`, 4,72) ; ni `<script>`, ni `<text>`, ni `<image>`, ni `href` externe, ni police, ni sapin (réservés à R.4). Appliqué par `background-image: url('./motif/courbes-niveau.svg')` : le build l'empaquette (fichier empreinte ou `data:` URI, tous deux couverts par la CSP existante `img-src 'self' data:`). Le motif est décoratif (aucun contenu, aucun rôle ARIA) ; les cartes (`--couleur-surface`, opaques) le masquent, il n'est donc visible que sur le fond de page.

**Styles de base partagés** (fichiers `base.css` et `composants.css`, importés par `styles.css`)
- **RG10** : base. `body` : marge 0, police et interligne de RG6, couleur `--couleur-texte`, fond `--couleur-fond-page` + motif ; `h1` à `h3` : `--couleur-foret`, graisse 700, interligne 1,2 ; liens : `--couleur-lien`, soulignés (`text-underline-offset: 0.2em`) ; `:focus-visible` global : `outline: 3px solid var(--couleur-focus); outline-offset: 2px`, remplacé par `--couleur-focus-sur-fonce` à l'intérieur de `.entete` ; `::selection` fond sable ; `@media (prefers-reduced-motion: reduce)` : `transition-duration: 0s`, `animation: none`. Les transitions (survol des boutons) durent `--duree-transition` au plus. `app-root` conserve `display: flex; flex-direction: column; min-height: 100dvh`. Les règles `:focus-visible` dupliquées dans les composants (couleur `#f59e0b`) sont supprimées.
- **RG11** : boutons. Classes globales `.bouton` (principal), `.bouton--secondaire`, `.bouton--danger`, `.bouton--danger-contour`, `.bouton--sur-fonce` (secondaire dans l'en-tête). Tous : `min-height: var(--cible-min)`, `padding: var(--espace-3) var(--espace-5)`, `font: inherit; font-weight: 600`, `border-radius: var(--rayon-s)`, bordure 1px, `cursor: pointer`. **Principal** : fond forêt, texte surface, survol forêt-survol. **Secondaire** : fond surface, bordure et texte forêt, survol sable. **Danger** (action destructive confirmée) : fond danger, texte surface, survol danger-survol. **Danger contour** (déclenche la confirmation) : fond surface, bordure et texte danger, survol danger-pale. **Sur fond sombre** : fond transparent, bordure et texte crème, survol fond forêt-survol. **Désactivé** (`:disabled`) : fond `--couleur-desactive-fond`, texte `--couleur-desactive-texte`, bordure idem, curseur `wait` (comme aujourd'hui). Les noms existants sont **conservés comme alias** dans le même CSS global : `.ligne__action` = secondaire, `.ligne__action--danger` = danger contour, `.ligne__action--danger-plein` = danger, `.entete__bouton` = sur fond sombre ; `action-ligne.css` est supprimé, `formulaire-compte.css` ne redéfinit plus `.bouton`. Aucune règle dupliquée (point ouvert 5).
- **RG12** : champs. `input`, `select`, `textarea` : fond surface, texte encre, bordure 1px `--couleur-bordure-champ`, `border-radius: var(--rayon-s)`, `min-height: var(--cible-min)`, `padding: var(--espace-2) var(--espace-3)`, `font: inherit`, `width: 100%` dans `.champ` ; `[aria-invalid='true']` : bordure 2px `--couleur-danger` ; `:disabled` : couleurs de désactivation ; `label` : graisse 600, couleur encre ; aide (`.aide`) : `--couleur-encre-douce`, 0,875rem ; texte d'erreur de champ : `--couleur-danger`, graisse 500. Hauteur >= 44 px.
- **RG13** : cartes. `.carte` : fond surface, bordure 1px `--couleur-trait`, `border-radius: var(--rayon-m)`, `box-shadow: var(--ombre-carte)`, `padding: var(--espace-6)`, `max-width: var(--largeur-carte)` (les variantes locales, par ex. 40rem, sont conservées) ; `.page` inchangée.
- **RG14** : messages. `.message--erreur` (fond danger-pale, texte danger), `.message--succes` (fond succes-pale, texte forêt), `.message--info` (fond sable, texte encre), `.message--avertissement` (fond orange-pale, texte orange-fonce) : `padding: var(--espace-3)`, `border-radius: var(--rayon-s)`, bordure gauche 4px de la couleur du texte. Les classes existantes `.erreur--generale`, `.succes`, `.message-succes`, `.message-info`, `.entete__erreur`, `.qr .erreur` et leurs équivalents restent comme alias de ces messages. `role="alert"` et textes existants inchangés.
- **RG15** : pastilles de statut. Classe de base `.pastille-statut` : `display: inline-flex; align-items: center; gap: var(--espace-2)`, `padding: 0.125rem 0.625rem`, `border-radius: var(--rayon-pastille)`, `font-size: var(--taille-petite)`, graisse 700, bordure 1px, **puce** `::before` (cercle 0,5rem de la couleur du texte, purement CSS). Six modificateurs, libellés **déjà existants** (aucun texte ajouté) :

| Statut | Modificateur | Libellé | Fond | Texte | Bordure |
|---|---|---|---|---|---|
| Course `EN_PREPARATION` | `--en-preparation` | En préparation | sable | encre-douce | trait |
| Course `EN_COURS` | `--en-cours` | En cours | orange-pale | orange-fonce | orange-fonce |
| Course `TERMINEE` | `--terminee` | Terminée | forêt | crème | forêt |
| Inscription `EN_COURSE` | `--en-course` | En course | succes-pale | forêt | forêt |
| Inscription `ABANDON` | `--abandon` | Abandon | danger-pale | danger | danger |
| Inscription `VAINQUEUR` | `--vainqueur` | Vainqueur | orange | encre | orange-fonce |

  **Application** : aux trois `<span>` existants qui affichent un statut seul : `inscriptions-course-statut` (statut de la Course), `inscriptions-statut` (statut de l'Inscription) et `benevole-course-statut` ; le `.ts` associé fournit la table statut -> modificateur (mapping d'affichage, aucune règle métier). Les statuts en `<td>` / `<dd>` ne reçoivent pas la pastille (point ouvert 4). Les libellés des 6 statuts sont ceux de `libellesStatut*` ; les pastilles sont toutes montrées dans l'aperçu (RG20).

**Application aux écrans**
- **RG16** : en-tête. `.entete` : fond forêt, texte crème, filet inférieur 3px orange, `padding` en `--espace-*`, même disposition flex (titre à gauche, bloc compte à droite, retour à la ligne sur écran étroit) ; `.entete__titre` crème, graisse 700, sans soulignement ; `.entete__lien` : couleur orange (liens sur fond sombre), souligné au survol et au focus ; `.entete__pseudo` crème ; `.entete__bouton` : bouton sur fond sombre (RG11) ; `.entete__erreur` : message d'erreur (RG14). La hauteur minimale du bouton passe à 44 px (l'en-tête grandit de quelques pixels, accepté). Structure et `data-testid` de l'en-tête strictement inchangés.
- **RG17** : corps de page. Les 13 écrans (`/`, `/connexion`, `/creer-compte`, `/mon-compte`, `/acces-refuse`, `/administration`, `/administration/admins`, `/administration/benevoles`, `/administration/courses`, `/administration/courses/:id`, `/benevole`, `/coureur`, `/coureur/inscriptions`) emploient la nouvelle palette, la nouvelle police et le motif ; **les couleurs calculées (texte, fond, bordure, contour) de chaque élément appartiennent à la palette RG3** (plus `transparent`), aucune des anciennes valeurs (`#111827`, `#f9fafb`, `#1d4ed8`, `#6b7280`, `#b91c1c`, `#f59e0b`, `#bfdbfe`, `#fff` en dehors du QR code…) ne subsiste. **Exception unique : le QR code `inscriptions-qr`** reste **noir sur blanc** (`#000` sur `#fff`, zone calme blanche, lisibilité caméra) ; son cadre peut recevoir une bordure de la palette. Les logos de Course (images) ne sont pas recolorés. Mises en page (grilles, tailles `rem`, tailles de logo 48 px, QR 240 px) inchangées.

**Responsive, clavier, accessibilité**
- **RG18** : téléphone d'abord. À 360 px de large (et 320 px) : **aucun défilement horizontal** (`scrollWidth <= clientWidth`) sur les 13 écrans ; boutons et champs >= 44 px de haut ; texte >= 13 px ; cartes en pleine largeur moins `--espace-4` de marge. Au-delà (>= 640 px) la carte garde `--largeur-carte` (416 px pour la connexion). Pas de point de rupture nouveau : les écrans se réorganisent comme aujourd'hui.
- **RG19** : navigation clavier. Chaque élément interactif (lien, bouton, champ) est atteignable au Tab dans l'ordre du DOM existant et montre un contour `3px solid` visible (RG10) ; aucun `outline: none` sans équivalent ; `Entrée` / `Espace` activent les boutons comme avant. La charte n'ajoute aucun piège de focus ni `tabindex`.

**Documentation et non-régression**
- **RG20** : `docs/design.md` (français, termes du langage ubiquitaire pour les statuts) contient, dans cet ordre : 1. principes et décisions (RG2) ; 2. palette : le tableau de RG3 avec nom, hex, usage ; 3. contrastes : le tableau de RG4 (ratio recalculé, seuil, conforme oui/non) et la formule ; 4. typographie : famille, graisses, échelle, chiffres tabulaires, licence et provenance (RG6, RG7) ; 5. espacements, rayons, ombres, cibles (RG8) ; 6. motif de fond (RG9) ; 7. composants : règles de RG10 à RG15 avec les noms de classes ; 8. pastilles de statut : le tableau de RG15 ; 9. **aperçu illustré** : lien vers `docs/design-apercu.html` et la capture `docs/images/design-apercu.png` insérée en Markdown ; 10. règles d'usage pour les incréments suivants (variables uniquement, pas de couleur en dur, orange jamais en texte sur clair, pas de `svg`/`img` décoratif dans le DOM). **Aperçu** : fichier statique `docs/design-apercu.html`, sans script, qui lie la feuille réelle `../frontend/src/styles.css` (donc la même source que l'application : pas de copie du CSS) et montre, chacun repéré par `data-composant` : palette (18 pastilles de couleur avec nom et hex), typographie (graisses 400/700, chiffres tabulaires `0123456789`), 5 boutons (`bouton`, `bouton-secondaire`, `bouton-danger`, `bouton-danger-contour`, `bouton-sur-fonce`) normaux, survolables et `disabled`, champ normal / invalide / désactivé, carte, 4 messages, 6 pastilles de statut, en-tête, motif de fond. Justification du moyen : une route front livrerait en production une page de démonstration (structure et surface d'attaque en plus, contraire à RG1), alors que le fichier statique est vérifiable (ouvert en `file://` par Playwright), consomme le CSS réel, n'exige ni build ni serveur et se relit dans le dépôt. La capture est produite par le test CA12 lancé avec `MAJ_CAPTURE=1` (hors lancement normal), versionnée, et n'est comparée à aucune référence.
- **RG21** : non-régression. Les 23 fichiers E2E existants passent **sans qu'aucune de leurs attentes ne change** ; tout sélecteur à adapter doit être signalé à l'orchestrateur (aucun n'est attendu). `backend/` n'est pas touché ; ses suites unitaire et d'intégration passent sans modification. Le build Angular n'émet aucun avertissement (budgets `anyComponentStyle` 4 kB respectés par fichier), `package.json` et `package-lock.json` inchangés.
- **RG22** : contraintes de DOM (voir « Déjà livré ») : aucun `svg`, `img`, `canvas`, `<b>` ajouté dans `fiche-inscrits` ni sur l'écran d'inscription ; puces et motifs en CSS seulement.

## 3. Cas limites

- **Police non chargée** (réseau lent, fichier absent) : `font-display: swap` affiche d'abord la pile de secours (`system-ui`…), puis Inter ; aucun texte invisible ; mise en page non cassée (tailles en `rem`).
- **Navigateur sans prise en charge de `font-variant-numeric`** : chiffres proportionnels de secours, sans erreur.
- **Pseudo, nom de Course ou texte avec caractères hors latin** : rendus par la police de secours, jamais d'erreur ni de carré vide gênant (limite assumée, point ouvert 1).
- **Texte posé directement sur le motif** (titre de page hors carte, par ex. écran `/`) : couleur encre ou encre-douce, contraste >= 4,5 même sur un trait du motif (RG9).
- **Écran 320 px** : le bloc compte de l'en-tête passe à la ligne ; aucun défilement horizontal ; noms de Course longs : `overflow-wrap: anywhere` conservé.
- **Désactivation pendant l'envoi** (`:disabled`) : couleurs de désactivation, curseur `wait` ; exempt de ratio AA.
- **Champ invalide** : bordure danger 2px + message textuel (jamais la couleur seule) ; focus visible conservé.
- **`prefers-reduced-motion: reduce`** : plus aucune transition ni animation.
- **Impression, contraste élevé forcé (`forced-colors`)** : non traités (point ouvert 13) ; le navigateur reprend ses couleurs système sans casser la lisibilité.
- **Statut inconnu dans une pastille** : n'arrive pas (les 6 statuts sont des énumérations de CLAUDE.md) ; si le mapping n'a pas de clé, le `<span>` s'affiche sans pastille (texte lisible).
- **QR code** : jamais recoloré, jamais moins de 240 px, reste noir sur blanc ; son cadre est hors palette de fond (RG17).
- **Logo de Course** transparent ou sombre : posé sur surface claire (carte) ; non recoloré.
- **Mise en cache** : après mise à jour, `index.html` (`no-cache`) référence de nouveaux fichiers empreintes ; l'ancien `immutable` ne sert jamais un contenu périmé ; un visiteur déjà connecté voit le nouveau thème au rechargement sans reconnexion.
- **Rôles, courses parallèles, Passages, Boucle, vainqueurs partagés, `CORRECTION` vs `SCAN`, course non démarrée, inscription sans Passage, bénévole non affecté** : **non applicables** (aucune règle métier ni donnée ne change) ; les écrans réservés à un rôle gardent leurs gardes (garde de route, 401/403 du back) à l'identique.

## 4. Contrat d'API

**Aucun.** Aucun endpoint créé, modifié ni supprimé ; aucun DTO, code de retour ni `ProblemDetail` ne change. Les appels réseau des écrans sont identiques avant et après.

Seul contrat nouveau, **statique** (servi par `web`, pas par `api`) :

| Ressource | Méthode | Réponse |
|---|---|---|
| `/media/inter-4.1-latin-variable-<EMPREINTE>.woff2` (nom exact fixé par le build, repéré dans le CSS servi) | GET | 200, `Content-Type: font/woff2`, `Cache-Control: public, max-age=31536000, immutable`, <= 100 Ko |
| fichier du motif (URL repérée dans le CSS servi, ou `data:` URI) | GET | 200, `Content-Type: image/svg+xml`, `Cache-Control: public, max-age=31536000, immutable`, <= 4 Ko |
| `/licences/Inter-OFL.txt` | GET | 200, `text/plain`, texte de la SIL OFL 1.1 |
| `/index.html` et `/` | GET | 200, `Cache-Control: no-cache`, CSP **inchangée** |

Rôle requis : aucun (statique public). Les 401/403/404/409 existants de l'API ne sont pas touchés. Une police ou un motif inexistant : repli sur `index.html` (comportement actuel de Caddy), jamais d'erreur applicative.

## 5. Écrans

Pour chacun des 13 écrans : **informations, actions et messages d'erreur strictement inchangés** (RG1) ; seul le rendu change (palette RG3, police RG6, motif RG9, composants RG11 à RG15).

| Écran | Route | Rendu attendu |
|---|---|---|
| Accueil | `/` | fond crème + motif, titre forêt, indicateur d'état API (puces vert / rouge de la palette), lien |
| Connexion | `/connexion` | carte surface, champs RG12, bouton principal, messages de déconnexion / suppression (RG14) |
| Création de compte | `/creer-compte` | idem |
| Mon compte | `/mon-compte` | carte, formulaire de mot de passe, section « Supprimer mon compte » avec boutons danger contour puis danger (coureur) |
| Accès refusé | `/acces-refuse` | carte, message |
| Administration | `/administration` | carte, liens aux couleurs de lien |
| Gestion des admins / des bénévoles | `/administration/admins`, `/administration/benevoles` | listes, formulaire, bouton principal |
| Gestion des courses | `/administration/courses` | formulaire, boutons principal et secondaire, liste, boutons `.ligne__action`, confirmation de suppression |
| Fiche d'une Course | `/administration/courses/:id` | détails, inscrits (tableau sans pastille), bouton principal |
| Espace bénévole | `/benevole` | liste des Courses avec pastille de statut de la Course |
| Courses ouvertes | `/coureur` | liste, bouton `.ligne__action`, « Inscrit » |
| Mes inscriptions | `/coureur/inscriptions` | liste, pastilles de statut de Course et d'Inscription, dossard en chiffres tabulaires, QR noir sur blanc, désinscription |
| En-tête (tous écrans) | - | fond forêt, filet orange, liens orange, bouton sur fond sombre |

Aperçu (hors application) : `docs/design-apercu.html`, voir RG20.

## 6. Critères d'acceptation

Valeurs de référence : comptes de la démo (`Patron` / `mot-de-passe-patron-1` admin master, `Nadia` / `mot-de-passe-admin-1` admin, `Léo` / `mot-de-passe-benevole-1` bénévole, `Alice` / `un-mot-de-passe-12` coureuse) ; une Course A `EN_PREPARATION` et une Course B passée `EN_COURS` par SQL (`placerStatutCourseEnBase`) avec `Alice` inscrite à A (dossard 1) et B (dossard 1), `Léo` affecté à A et B ; `Alice` en `ABANDON` sur B par `placerStatutInscriptionEnBase` pour la pastille `ABANDON`. Les « 13 écrans » sont ceux de RG17. Il n'existe pas de test unitaire front (CLAUDE.md) : tous les CA visibles sont E2E (Playwright, Chromium).

| CA | Étant donné / quand / alors | Niveau |
|---|---|---|
| CA1 | Quand `/` est chargé, alors `getComputedStyle(document.documentElement).getPropertyValue(nom)` vaut pour chaque variable des RG3, RG6 et RG8 la valeur du tableau (ex. `--couleur-foret` = `#14352A`, `--couleur-creme` = `#F7F1E3`, `--cible-min` = `2.75rem`, `--rayon-m` = `0.75rem`), `color-scheme` = `light`, et aucun nom de variable en anglais n'est défini sur `:root` par la feuille du site (RG3, RG5, RG6, RG8) | E2E |
| CA2 | Étant donné `/connexion` chargé, quand les polices sont prêtes (`document.fonts.ready`), alors `document.fonts.check('400 16px Inter')` et `('700 16px Inter')` sont vrais, la police `Inter` apparaît `loaded` ; la police est servie par la requête observée vers l'origine du site, `200`, `Content-Type: font/woff2`, `Cache-Control: public, max-age=31536000, immutable`, taille <= 100 Ko ; `getComputedStyle(body).fontFamily` commence par `Inter` ; la règle `@font-face` a `font-display: swap` ; `GET /licences/Inter-OFL.txt` : 200 et contient « SIL OPEN FONT LICENSE » ; `GET /` : `Cache-Control: no-cache` (RG6, RG7) | E2E |
| CA3 | Étant donné `/` chargé, quand un élément de test mesure les largeurs rendues de `1111111111` et `0000000000` avec `font-family: Inter`, alors elles sont égales à 0,5 px près (chiffres tabulaires), `getComputedStyle(body).fontVariantNumeric` contient `tabular-nums` ; sur `/coureur/inscriptions` le dossard `1` est affiché dans la police Inter (RG6) | E2E |
| CA4 | Étant donné chacun des 13 écrans (connecté avec le rôle requis, jeu de référence), quand l'écran est chargé et que tous les événements `request` sont collectés, alors **100 % des requêtes ont pour origine celle du site** (ou `data:` / `blob:`) : aucune vers Google Fonts, un CDN ou tout autre hôte ; les en-têtes de `GET /` montrent la CSP identique à la valeur d'avant R.1 (`default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'`) ; aucun `<link>` de `index.html` ne cible une autre origine ; les fichiers CSS et JS empreintes gardent `immutable` (RG7) | E2E |
| CA5 | Étant donné chacun des 13 écrans dans l'état de référence (y compris confirmation de suppression ouverte, message d'erreur de connexion affiché, bouton désactivé en cours d'envoi), quand le test parcourt tous les éléments visibles et lit `color`, `background-color`, `border-*-color`, `outline-color`, alors chaque valeur appartient à l'ensemble {les 18 couleurs de la RG3, `rgba(0, 0, 0, 0)`} ; **exception** : `inscriptions-qr` et ses enfants ; aucune des anciennes valeurs (`rgb(17, 24, 39)`, `rgb(249, 250, 251)`, `rgb(29, 78, 216)`, `rgb(107, 114, 128)`, `rgb(185, 28, 28)`, `rgb(245, 158, 11)`) n'est présente ; le QR : `fill` du fond `#fff`, tracé `#000` inchangés (RG3, RG17) | E2E |
| CA6 | Étant donné chacun des 13 écrans, quand le test calcule pour chaque élément contenant du texte visible (hors `disabled`) le ratio WCAG entre sa couleur de texte et le fond effectif (premier ancêtre au fond non transparent, sinon `--couleur-creme` ; sur le motif, le pire cas `--couleur-trait`), alors le ratio est >= 4,5 (>= 3 pour un texte >= 24 px, ou >= 18,66 px en gras) ; les bordures de champ et contours de focus vérifient >= 3 contre leur fond ; aucune information n'est portée par la couleur seule (chaque pastille et chaque message a un texte) (RG4) | E2E |
| CA7 | Étant donné `/`, `/connexion` et `/coureur/inscriptions`, quand la page est chargée, alors `.entete` a fond `rgb(20, 53, 42)`, texte `rgb(247, 241, 227)`, `border-bottom` 3px `rgb(242, 140, 40)` ; `.entete__lien` (ex. `lien-administration`) est `rgb(242, 140, 40)` ; `body` a fond `rgb(247, 241, 227)`, couleur `rgb(27, 42, 34)`, `background-image` contenant `courbes-niveau` ou un `data:image/svg+xml` ; les `data-testid` et textes de l'en-tête sont identiques à ceux d'avant (liste exacte du « Déjà livré ») et leur ordre dans le DOM inchangé ; le titre `entete-titre` n'est pas souligné (RG10, RG16) | E2E |
| CA8 | Étant donné le fichier du motif récupéré depuis l'URL lue dans `background-image` (ou décodé du `data:`), quand il est analysé, alors : 200 (ou data), `Content-Type: image/svg+xml`, poids <= 4096 octets, `viewBox="0 0 480 480"`, 5 à 7 `<path>` de courbes de niveau en `fill="none"` + exactement un `<path>` fermé (`d` se terminant par `Z`) avec `stroke-dasharray`, une seule couleur de trait `#D9CFB6`, aucun `<script>`, `<text>`, `<image>`, `href` ou `xlink:href` externe, aucun sapin ; `background-repeat` = `repeat` (RG9) | E2E |
| CA9 | Étant donné `/connexion` puis `/administration/courses` (Patron), quand le test lit les styles des boutons, alors : bouton principal (`connexion` : `.bouton`) fond `rgb(20, 53, 42)`, texte `rgb(255, 252, 245)`, hauteur >= 44 px, rayon 6 px ; au survol (`hover`) fond `rgb(31, 90, 67)` ; bouton `.ligne__action` fond `rgb(255, 252, 245)`, bordure et texte `rgb(20, 53, 42)` ; bouton de suppression de Course (`.ligne__action--danger`) bordure et texte `rgb(155, 28, 28)` ; bouton de confirmation (`.ligne__action--danger-plein`) fond `rgb(155, 28, 28)`, texte `rgb(255, 252, 245)` ; bouton `bouton-deconnexion` (connecté) fond transparent, bordure et texte `rgb(247, 241, 227)`, hauteur >= 44 px ; un bouton `disabled` pendant l'envoi : fond `rgb(221, 214, 195)`, texte `rgb(95, 106, 98)`, `cursor: wait` ; `reduced-motion` : `transition-duration` = `0s` (RG10, RG11) | E2E |
| CA10 | Étant donné `/connexion`, quand un identifiant incorrect est soumis, alors le message d'erreur (`role="alert"`) a fond `rgb(251, 233, 228)`, texte `rgb(155, 28, 28)`, bordure gauche 4px, texte inchangé (« Pseudo ou mot de passe incorrect. ») ; les champs ont fond `rgb(255, 252, 245)`, bordure 1px `rgb(111, 122, 110)`, hauteur >= 44 px ; un champ `aria-invalid="true"` (création de compte avec pseudo vide) a une bordure 2px `rgb(155, 28, 28)` ; la carte a fond `rgb(255, 252, 245)`, rayon >= 12 px, `box-shadow` différent de `none`, largeur max 416 px à 1280 px de large (RG12 à RG14) | E2E |
| CA11 | Étant donné `Alice` sur `/coureur/inscriptions` (A `EN_PREPARATION`, Inscription `EN_COURSE`), quand la page est chargée, alors `inscriptions-course-statut` porte la classe `pastille-statut--en-preparation` (fond `rgb(239, 230, 208)`, texte `rgb(74, 90, 80)`, texte « En préparation ») et `inscriptions-statut` la classe `--en-course` (fond `rgb(227, 239, 230)`, texte `rgb(20, 53, 42)`, « En course ») ; après passage de B à `EN_COURS` : `--en-cours` (fond `rgb(251, 233, 207)`, texte `rgb(168, 67, 0)`, « En cours ») ; B `TERMINEE` : `--terminee` (fond `rgb(20, 53, 42)`, texte `rgb(247, 241, 227)`, « Terminée ») ; Inscription `ABANDON` : `--abandon` (fond `rgb(251, 233, 228)`, texte `rgb(155, 28, 28)`, « Abandon ») ; Inscription `VAINQUEUR` : `--vainqueur` (fond `rgb(242, 140, 40)`, texte `rgb(27, 42, 34)`, « Vainqueur ») ; `Léo` sur `/benevole` : `benevole-course-statut` porte la pastille correspondante ; `border-radius` >= 12 px, puce `::before` présente ; `fiche-inscrits` ne contient toujours ni `svg`, ni `img`, ni `canvas`, ni `<b>` ; les textes sont ceux d'avant (RG15, RG22) | E2E |
| CA12 | Étant donné `docs/design-apercu.html` ouvert par Playwright en `file://` (chemin relatif au dépôt), quand la page est chargée, alors : les éléments `data-composant` suivants sont tous présents : `palette` (18 pastilles de couleur, chacune avec son nom et son hex), `typographie`, `bouton`, `bouton-secondaire`, `bouton-danger`, `bouton-danger-contour`, `bouton-sur-fonce`, `bouton-desactive`, `champ`, `champ-invalide`, `champ-desactive`, `carte`, `message-erreur`, `message-succes`, `message-info`, `message-avertissement`, les 6 `pastille-statut-*`, `entete`, `motif` ; `--couleur-foret` lue sur la page vaut `#14352A` (le CSS réel est chargé : lien vers `frontend/src/styles.css`) ; la police `Inter` est `loaded` ; aucune requête externe (hors `file://`) ; la page ne contient aucun `<script>` ; lancé avec `MAJ_CAPTURE=1`, le test écrit `docs/images/design-apercu.png` (non vide, largeur >= 1000 px) ; `docs/design.md` référence cette image et ce fichier (RG20) | E2E |
| CA13 | Étant donné `docs/design.md`, quand le test le lit, alors il contient les 10 sections de RG20 dans l'ordre, chaque variable de RG3, RG6 et RG8 avec sa valeur exacte, le tableau des 6 pastilles, la mention SIL OFL 1.1 avec version d'Inter et somme SHA-256 du fichier woff2 (qui est celle du fichier du dépôt, recalculée), les 4 décisions de DA (clair crème, Inter sans police display, courbes de niveau avec boucle fermée sans sapins, univers forêt vert / orange / crème) ; pour chaque ligne du tableau de contrastes, le ratio recalculé depuis les hex est égal au ratio affiché à 0,05 près et >= seuil ; les valeurs hex de `design.md` sont égales à celles de `variables.css` (RG3, RG4, RG7, RG20) | E2E |
| CA14 | Étant donné `Tab` pressé depuis le haut de page sur `/connexion` (anonyme) puis `/administration/courses` (Patron) et `/coureur/inscriptions` (Alice), quand le focus atteint successivement chaque lien, champ et bouton, alors l'ordre est celui du DOM existant, le contour est `3px solid` ; sa couleur est `rgb(242, 140, 40)` dans `.entete` et `rgb(168, 67, 0)` ailleurs ; `Entrée` sur le bouton principal de `/connexion` soumet le formulaire comme avant ; aucun élément interactif n'a `outline-style: none` au focus (RG10, RG19) | E2E |
| CA15 | Étant donné un viewport 360 x 640 (puis 320 x 640), quand chacun des 13 écrans est affiché (jeu de référence, `Alice` avec un nom de Course de 60 caractères), alors `document.documentElement.scrollWidth <= clientWidth` ; tout bouton et champ visible a une hauteur >= 44 px ; aucun texte visible < 13 px ; les cartes occupent la largeur moins 2 x 16 px ; à 1280 x 800 la carte de connexion mesure 416 px (RG18) | E2E |
| CA16 | Étant donné l'application lancée par `docker compose up -d --build` (profil `e2e`), quand les 23 fichiers E2E existants sont exécutés **sans aucune modification**, alors tous passent ; `git diff` des fichiers `e2e/tests/*.spec.ts` et `aide-*.ts` d'avant R.1 est vide ; `donnees-demo.spec.ts` passe ; les `data-testid` de toutes les pages sont toujours présents (RG1, RG21, RG22) | E2E |
| CA17 | Étant donné le dépôt après R.1, quand les suites back existantes (unitaires + intégration + ArchUnit) sont exécutées, alors elles passent **sans modification** ; `backend/`, `docker-compose*.yml`, `.env.example`, `docs/deploiement.md`, `scripts/donnees-demo.sh`, `package.json` et `package-lock.json` n'ont aucune différence ; le build Angular de production s'achève sans avertissement ni erreur de budget ; `docker compose up -d --build` : `base`, `api`, `web` sains (RG1, RG21) | intégration |

Répartition : unitaire 0, intégration 1 (CA17), E2E 16 (CA1 à CA16). Total 17.

Couverture des RG : RG1 CA16/CA17, RG2 CA13/CA7, RG3 CA1/CA5/CA12/CA13, RG4 CA6/CA13, RG5 CA1, RG6 CA1/CA2/CA3, RG7 CA2/CA4/CA13, RG8 CA1/CA10, RG9 CA7/CA8, RG10 CA7/CA9/CA14, RG11 CA9, RG12 CA10, RG13 CA10, RG14 CA10, RG15 CA11/CA12, RG16 CA7/CA9, RG17 CA5/CA7, RG18 CA15, RG19 CA14, RG20 CA12/CA13, RG21 CA16/CA17, RG22 CA11/CA16. Écrans : les 13 écrans en CA4, CA5, CA6, CA15 (E2E), détail par écran en CA7 à CA11 et CA14 (accueil, connexion, création de compte, mon compte, accès refusé, administration, comptes gérés, courses, fiche, bénévole, courses ouvertes, mes inscriptions) ; en-tête CA7 ; `docs/design-apercu.html` CA12.

Notes pour les testeurs : un fichier `e2e/tests/theme-*.spec.ts` par thème (variables / polices / palette et contraste / composants / aperçu / responsive) ; fonction utilitaire de ratio WCAG partagée (`e2e/tests/aide-contraste.ts`) ; parcours des 13 écrans par une table (route, rôle, préparation) réutilisant `aide-connexion.ts`, `aide-admin.ts`, `aide-coureur.ts` sans les modifier ; pour CA5 et CA6, états ouverts (confirmation de suppression, erreur de connexion) obtenus par les mêmes actions que les specs existantes ; l'URL de la police et celle du motif se lisent dans la réponse du CSS (`/styles-*.css`) ; CA12 et CA13 lisent `docs/` par `fs` et `file://` (chemins résolus depuis `__dirname`). Aucune règle temporelle : `/api/test/horloge` inutile.

## 7. Tester à la main

Prérequis : `docker compose up -d --build` (`base`, `api`, `web` sains), http://localhost, `.env` avec `ADMIN_MASTER_PSEUDO=Patron`, `ADMIN_MASTER_MOT_DE_PASSE=mot-de-passe-patron-1`. Lancer `./scripts/donnees-demo.sh` : `Nadia` (`mot-de-passe-admin-1`), `Léo` et `Marc` (`mot-de-passe-benevole-1`), `Alice`, `Karim`, `Sophie`, `Tom` (`un-mot-de-passe-12`) ; courses `Backyard de démo`, `Backyard express`, `Backyard mini`. Ouvrir les outils de développement (onglet Réseau) avant de charger la page.

1. Charte : ouvrir `docs/design.md` : palette, ratios, typographie, pastilles ; ouvrir `docs/design-apercu.html` dans le navigateur (double clic) : chaque composant s'affiche (5 boutons, champs, carte, 4 messages, 6 pastilles, motif). Les ratios affichés sont tous >= 4,5 (>= 3 pour le non-textuel).
2. Accueil : http://localhost : fond crème avec de fines courbes de niveau et une boucle fermée en pointillés, en-tête vert forêt avec filet orange, titre en Inter. Aucun sapin.
3. Réseau : recharger (Ctrl+Maj+R) : toutes les requêtes viennent de `localhost` ; une police `.woff2` (`font/woff2`) et un fichier `.svg` (ou `data:`) y figurent, aucune vers `fonts.googleapis.com` ou un CDN. Recharger normalement : la police vient du cache.
4. Connexion : `/connexion` : carte claire, champs à bordure grise-verte, bouton vert forêt (plus bleu). Saisir un mauvais mot de passe : message rouge brique sur fond rosé, même texte qu'avant. Tab : le contour de focus est orange (clair sur l'en-tête, foncé sur le reste).
5. Coureur : en `Alice`, `Backyard express` -> « S'inscrire » (bouton secondaire) puis `Mes inscriptions` : pastille « En préparation » (beige) pour la course, pastille verte « En course » pour l'inscription, dossard et QR code (noir sur blanc, 240 px). Passer la course en cours : `docker compose exec base psql -U backyard -d backyard -c "update course set statut = 'EN_COURS' where nom = 'Backyard express'"` puis F5 : pastille orange « En cours ». Idem `TERMINEE` (pastille vert forêt) ; `update inscription set statut = 'ABANDON'` / `'VAINQUEUR'` pour les pastilles rouge pâle et orange.
6. Administration : en `Patron`, parcourir `/administration`, admins, bénévoles, courses, fiche d'une Course : mêmes informations et textes qu'avant, nouvelle palette, boutons de suppression en contour rouge brique, confirmation en bouton rouge plein.
7. Bénévole : en `Léo`, `/benevole` : pastille de statut de la Course.
8. Téléphone : outils de développement, mode appareil 360 x 640 puis 320 x 640 : aucun défilement horizontal sur les écrans visités, boutons et champs confortables au doigt (>= 44 px).
9. Clavier : sans souris, Tab / Maj+Tab dans `/connexion` puis `/administration/courses` : chaque élément montre un contour orange épais ; Entrée active le bouton.
10. Régression : dérouler quelques parcours des incréments précédents (création de compte, changement de mot de passe, suppression de Course, désinscription) : comportement et textes identiques à avant.
11. Remise à zéro : `docker compose down -v` puis `docker compose up -d --build` si besoin.

## 8. Points ouverts

Bloquants : aucun. Positions par défaut appliquées dans la spec.

1. **Sous-ensemble latin seul.** Les accents français, `œ`, `’`, `«»`, `…` sont couverts ; d'autres alphabets (pseudos en cyrillique, etc.) retombent sur la police de secours. Retenu : latin seul (< 100 Ko). Alternative : ajouter `latin-ext` (+ ~25 Ko). À confirmer.
2. **Chiffres tabulaires partout.** Retenu : `tabular-nums` global sur `body` (aucun changement de template, dossards et futurs chronomètres couverts). Alternative : classe ciblée sur dossards et durées. À confirmer.
3. **Aperçu en fichier statique `docs/design-apercu.html`** plutôt qu'une route front (pas de page de démonstration en production), avec une capture PNG versionnée dans `docs/images/`. Alternative : seulement la capture, ou une route protégée `ADMIN_MASTER`. À confirmer.
4. **Pastilles limitées aux trois `<span>`.** Les statuts en `<td>` (inscrits) et `<dd>` (liste, fiche) restent en texte brut car envelopper la valeur dans un élément serait un changement de structure et de sélecteur ; leur passage en pastilles est repoussé aux incréments R de ces écrans (liste en cartes, fiche). À confirmer.
5. **Alias des classes historiques** (`.ligne__action*`, `.entete__bouton`, `.erreur--generale`, `.succes`, `.message-succes`, `.message-info`) pour ne toucher aucun template. Ils seront retirés quand les templates adopteront les classes `bouton*` / `message--*` (R.2 et suivants). Alternative : les renommer dès R.1 (≈ 25 templates touchés). À confirmer.
6. **Ratios calculés à la main.** Les valeurs du tableau RG4 sont indicatives ; la rédaction de `docs/design.md` les recalcule (formule WCAG 2.x) et CA13 les contrôle à 0,05 près. En cas d'écart qui ferait passer un couple sous le seuil, ajuster la teinte et en informer le fonctionnel.
7. **Orange et texte.** `--couleur-orange` (2,18 sur crème) est réservé aux fonds sombres et aux aplats ; le texte orange sur clair utilise `--couleur-orange-fonce`. Si l'utilisateur veut un orange plus vif en texte sur clair, il faudra assombrir la teinte.
8. **Empreinte des polices par le build Angular.** Le fichier `woff2` et le SVG sont référencés par URL relative dans le CSS pour être empreintes et mis en cache `immutable`. Si le builder les inline ou ne les empreinte pas, le développeur le signale ; repli : les placer dans `frontend/public/` avec un nom versionné et une règle Caddy sur leur chemin. Pas de `<link rel="preload">` (nom empreinte inconnu à l'écriture de `index.html`) : un bref affichage en police de secours (`swap`) est accepté.
9. **Hauteur de l'en-tête.** Cible tactile de 44 px sur `entete__bouton` : l'en-tête grandit de quelques pixels, seul changement de gabarit visible. À confirmer (alternative : 36 px dans l'en-tête, hors règle de 44 px).
10. **Service worker.** Le précache des polices et du motif pour l'usage hors ligne relève du jalon 5 (PWA) ; sans lui, une police absente du cache hors ligne se replie sur la police de secours.
11. **Taille et découpage.** ~430 lignes de production, un peu au-dessus de la cible (400). Découpage proposé si la relecture est trop longue : R.1a (variables, polices, base, en-tête, motif, `design.md`) puis R.1b (boutons, champs, cartes, messages, pastilles, migration des 20 CSS, aperçu). À confirmer.
12. **Roadmap : « de jour comme de nuit ».** L'univers du jalon R évoque le jour et la nuit, alors que le mode sombre est hors périmètre (décision utilisateur). Un thème « nuit » (projection du suivi public, jalon 6) est à décider plus tard. Par ailleurs la « Décision en attente » « Direction artistique » du roadmap est tranchée par cette spec : à retirer du roadmap par l'orchestrateur (le fonctionnel ne modifie pas `docs/roadmap.md`).
13. **Impression et `forced-colors`.** Non traités en R.1 ; le comportement par défaut du navigateur s'applique. À confirmer qu'aucune feuille d'impression n'est attendue.
14. **Sélecteurs E2E.** Aucun sélecteur existant n'a besoin de changer (tous par `data-testid`). Contraintes à respecter par le développeur front : aucune balise `svg` / `img` / `canvas` / `b` ajoutée dans `fiche-inscrits` ni sur l'inscription (RG22). Tout écart découvert à l'implémentation est signalé à l'orchestrateur, pas corrigé dans les tests.
15. **Thème et `theme-color`.** `<meta name="theme-color" content="#14352A">` est ajouté dans `index.html` (barre du navigateur mobile aux couleurs de l'en-tête) ; il n'a pas de contenu textuel. À confirmer.
