import { expect, test, type Page } from '@playwright/test';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir, seConnecter } from './aide-connexion';
import { seDeconnecterParLeMenu } from './aide-entete';
import {
  AIDES_PAGE,
  RGB_PALETTE,
  RGB_TRANSPARENT,
  ECRANS,
  ETATS,
  ouvrirScene,
  type EcartCouleur,
  type JeuReference,
  type MesureContraste,
  type MesuresResponsive,
} from './aide-theme';

/* Incrément R.5 : écrans de connexion et de création de compte sur le décor backyard. */

const TITRE_CONNEXION = 'Se connecter - Backyard Ultra Tracker';
const TITRE_CREATION = 'Créer un compte - Backyard Ultra Tracker';
const CREME = 'rgb(247, 241, 227)';
const SURFACE = 'rgb(255, 252, 245)';
const TRAIT = 'rgb(217, 207, 182)';
const FORET = 'rgb(20, 53, 42)';
const AGRANDIR = 'html{font-size:32px}';

type Ecran = 'connexion' | 'creer-compte';
const ECRANS_COMPTE: { ecran: Ecran; chemin: string; titre: string; idTitre: string }[] = [
  { ecran: 'connexion', chemin: '/connexion', titre: TITRE_CONNEXION, idTitre: 'titre-connexion' },
  { ecran: 'creer-compte', chemin: '/creer-compte', titre: TITRE_CREATION, idTitre: 'titre-creer-compte' },
];

/** Scènes d'`aide-theme.ts` (écrans anonymes : le jeu de référence n'est pas utilisé). */
const JEU_VIDE = {} as JeuReference;
function scene(nom: string) {
  const trouvee = [...ECRANS, ...ETATS].find((s) => s.nom === nom);
  if (!trouvee) throw new Error(`Scène inconnue : ${nom}`);
  return trouvee;
}

/** Pseudo de 30 caractères (lettres, chiffres, tirets), sans espace, unique. */
function pseudoDe30(): string {
  const unique = `u${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
  return `${unique}-`.padEnd(30, 'x').slice(0, 30);
}

async function ouvrir(page: Page, e: (typeof ECRANS_COMPTE)[number]): Promise<void> {
  if (e.ecran === 'connexion') {
    await ouvrirConnexion(page);
  } else {
    await page.goto(e.chemin);
    await expect(page.getByTestId(e.idTitre)).toBeVisible();
  }
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
}

/** Soumet des valeurs invalides : trois erreurs de champ (pseudo, mot de passe, confirmation). */
async function soumettreCreationVide(page: Page): Promise<void> {
  await page.getByTestId('champ-pseudo').fill('');
  await page.getByTestId('champ-mot-de-passe').fill('court');
  await page.getByTestId('champ-confirmation').fill('autre');
  await page.getByTestId('bouton-creer-compte').click();
  await expect(page.getByTestId('erreur-pseudo')).toBeVisible();
  await expect(page.getByTestId('erreur-mot-de-passe')).toBeVisible();
  await expect(page.getByTestId('erreur-confirmation')).toBeVisible();
}

async function soumettreConnexionVide(page: Page): Promise<void> {
  await page.getByTestId('bouton-connexion').click();
  await expect(page.getByTestId('erreur-pseudo')).toBeVisible();
  await expect(page.getByTestId('erreur-mot-de-passe')).toBeVisible();
}

async function afficherErreurGeneraleConnexion(page: Page): Promise<void> {
  await saisir(page, pseudoUnique('inconnu'), 'mauvais-mot-de-passe-1');
  await page.getByTestId('bouton-connexion').click();
  await expect(page.getByTestId('erreur-generale')).toHaveText('Pseudo ou mot de passe incorrect.');
}

async function backgroundImageDeAccueil(page: Page): Promise<string> {
  await page.goto('/');
  await expect(page.getByTestId('accroche')).toBeVisible();
  return page.locator('main').evaluate((m) => getComputedStyle(m).backgroundImage);
}

/** Mesures du décor du `main` : classes, fond, couches, enfants, éléments interdits. */
async function mesurerDecor(page: Page) {
  return page.locator('main').evaluate((m) => {
    const cs = getComputedStyle(m);
    const carte = m.querySelector(':scope > section.carte');
    return {
      classes: Array.from(m.classList),
      backgroundColor: cs.backgroundColor,
      backgroundImage: cs.backgroundImage,
      nombreUrl: (cs.backgroundImage.match(/url\(/g) ?? []).length,
      nombreDataSvg: (cs.backgroundImage.match(/url\("data:image\/svg\+xml/g) ?? []).length,
      repeat: cs.backgroundRepeat,
      positionY: cs.backgroundPositionY,
      taille: cs.backgroundSize,
      enfants: m.children.length,
      carteEstEnfant: carte !== null && m.children[0] === carte,
      interdits: m.querySelectorAll('svg, img, canvas, picture, video').length,
    };
  });
}

async function verifierDecor(page: Page, imageAccueil: string): Promise<void> {
  const d = await mesurerDecor(page);
  expect(d.classes).toEqual(expect.arrayContaining(['page', 'decor-backyard']));
  expect(d.backgroundColor).toBe(CREME);
  expect(d.backgroundImage).toBe(imageAccueil);
  expect(d.nombreUrl).toBe(2);
  expect(d.nombreDataSvg).toBe(2);
  expect(d.repeat).toBe('repeat-x, repeat');
  expect(d.positionY.split(',')[0].trim()).toBe('100%');
  expect(d.taille.split(',')[0].trim()).toBe('auto 96px');
  expect(d.enfants).toBe(1);
  expect(d.carteEstEnfant).toBe(true);
  expect(d.interdits).toBe(0);
}

interface Geometrie {
  racineScrollWidth: number;
  racineClientWidth: number;
  racineScrollHeight: number;
  racineClientHeight: number;
  innerHeight: number;
  innerWidth: number;
  main: { haut: number; bas: number; centreX: number; paddingBas: number; paddingHaut: number };
  carte: {
    haut: number;
    bas: number;
    gauche: number;
    droite: number;
    largeur: number;
    centreX: number;
    scrollWidth: number;
    clientWidth: number;
    overflowWrap: string;
  };
  /** Descendants visibles de la carte qui dépassent de ses bords gauche ou droit. */
  debordements: string[];
  /** Nœuds texte non vides du `main` situés hors de la carte. */
  texteHorsCarte: string[];
}

async function mesurer(page: Page): Promise<Geometrie> {
  return page.evaluate(() => {
    const racine = document.documentElement;
    const main = document.querySelector('main') as HTMLElement;
    const carte = main.querySelector('section.carte') as HTMLElement;
    const rm = main.getBoundingClientRect();
    const rc = carte.getBoundingClientRect();
    const debordements: string[] = [];
    for (const el of Array.from(carte.querySelectorAll('*'))) {
      if (!el.checkVisibility()) continue;
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      if (r.left < rc.left - 0.5 || r.right > rc.right + 0.5) {
        const id = el.getAttribute('data-testid') ?? el.tagName.toLowerCase();
        debordements.push(`${id} [${r.left.toFixed(1)} ; ${r.right.toFixed(1)}] hors carte [${rc.left.toFixed(1)} ; ${rc.right.toFixed(1)}]`);
      }
    }
    const texteHorsCarte: string[] = [];
    const marcheur = document.createTreeWalker(main, NodeFilter.SHOW_TEXT);
    for (let n = marcheur.nextNode(); n; n = marcheur.nextNode()) {
      if ((n.textContent ?? '').trim().length > 0 && !carte.contains(n)) texteHorsCarte.push((n.textContent ?? '').trim());
    }
    return {
      racineScrollWidth: racine.scrollWidth,
      racineClientWidth: racine.clientWidth,
      racineScrollHeight: racine.scrollHeight,
      racineClientHeight: racine.clientHeight,
      innerHeight: window.innerHeight,
      innerWidth: window.innerWidth,
      main: { haut: rm.top, bas: rm.bottom, centreX: rm.left + rm.width / 2, paddingBas: parseFloat(getComputedStyle(main).paddingBottom), paddingHaut: parseFloat(getComputedStyle(main).paddingTop) },
      carte: {
        haut: rc.top,
        bas: rc.bottom,
        gauche: rc.left,
        droite: rc.right,
        largeur: rc.width,
        centreX: rc.left + rc.width / 2,
        scrollWidth: carte.scrollWidth,
        clientWidth: carte.clientWidth,
        overflowWrap: getComputedStyle(carte).overflowWrap,
      },
      debordements,
      texteHorsCarte,
    };
  });
}

/** Contrôles communs : pas de défilement horizontal, contenu dans la carte, carte au-dessus de la bande de sapins. */
async function verifierContenuDansLaCarte(page: Page, bande: number): Promise<Geometrie> {
  const g = await mesurer(page);
  expect(g.racineScrollWidth, 'défilement horizontal de la page').toBeLessThanOrEqual(g.racineClientWidth);
  expect(g.carte.scrollWidth, 'défilement horizontal de la carte').toBeLessThanOrEqual(g.carte.clientWidth);
  expect(g.debordements, 'descendants hors de la carte').toEqual([]);
  expect(g.carte.bas, 'bas de la carte au-dessus de la bande de sapins').toBeLessThanOrEqual(g.main.bas - bande + 0.5);
  return g;
}

async function tailleDePolice(page: Page, testid: string): Promise<number> {
  return page.getByTestId(testid).evaluate((e) => parseFloat(getComputedStyle(e).fontSize));
}

async function titreSansDebordement(page: Page, idTitre: string): Promise<void> {
  const t = await page.getByTestId(idTitre).evaluate((e) => ({ sw: e.scrollWidth, cw: e.clientWidth, droite: e.getBoundingClientRect().right }));
  const carte = await page.locator('main section.carte').evaluate((e) => e.getBoundingClientRect().right);
  expect(t.sw, `scrollWidth du titre ${idTitre}`).toBeLessThanOrEqual(t.cw);
  expect(t.droite).toBeLessThanOrEqual(carte + 0.5);
}

/* ------------------------------------------------------------------------------------------ */

test.describe('Connexion et création de compte sur le décor backyard (R.5)', () => {
  test('CA1 - les deux écrans portent le décor de l\'accueil dans tous leurs états', async ({ page }) => {
    test.setTimeout(120_000);
    await page.setViewportSize({ width: 1280, height: 800 });
    const imageAccueil = await backgroundImageDeAccueil(page);
    expect(imageAccueil).not.toBe('none');

    const requetes: string[] = [];
    const origine = new URL(page.url()).origin;
    page.on('request', (r) => {
      const url = r.url();
      if (/\.(svg|png|jpe?g|webp)(\?|$)/i.test(new URL(url).pathname) || (url.startsWith('http') && new URL(url).origin !== origine)) {
        requetes.push(url);
      }
    });

    // Connexion nue.
    await page.goto('/connexion');
    await expect(page.getByTestId('titre-connexion')).toBeVisible();
    await expect(page).toHaveTitle(TITRE_CONNEXION);
    await verifierDecor(page, imageAccueil);

    // Connexion avec erreur générale.
    await afficherErreurGeneraleConnexion(page);
    await verifierDecor(page, imageAccueil);

    // Connexion après déconnexion.
    const pseudo = pseudoUnique('r5');
    await creerCompteParApi(page.request, pseudo);
    await seConnecter(page, pseudo);
    await seDeconnecterParLeMenu(page);
    await expect(page.getByTestId('message-deconnexion')).toBeVisible();
    await expect(page).toHaveTitle(TITRE_CONNEXION);
    await verifierDecor(page, imageAccueil);

    // Création de compte : nue, avec erreurs, puis succès.
    await page.goto('/creer-compte');
    await expect(page.getByTestId('titre-creer-compte')).toBeVisible();
    await expect(page).toHaveTitle(TITRE_CREATION);
    await verifierDecor(page, imageAccueil);
    await soumettreCreationVide(page);
    await verifierDecor(page, imageAccueil);

    await page.getByTestId('champ-pseudo').fill(pseudoUnique('r5c'));
    await page.getByTestId('champ-mot-de-passe').fill(MOT_DE_PASSE);
    await page.getByTestId('champ-confirmation').fill(MOT_DE_PASSE);
    await page.getByTestId('bouton-creer-compte').click();
    await expect(page.getByTestId('message-succes')).toBeVisible();
    await expect(page).toHaveTitle(TITRE_CREATION);
    await verifierDecor(page, imageAccueil);

    expect(requetes, 'requêtes image ou hors origine').toEqual([]);
  });

  for (const e of ECRANS_COMPTE) {
    test(`CA2 - la carte de ${e.ecran} est opaque, centrée sur le décor et au-dessus des sapins`, async ({ page }) => {
      await page.setViewportSize({ width: 1280, height: 800 });
      await ouvrir(page, e);

      const style = await page.locator('main section.carte').evaluate((c) => {
        const cs = getComputedStyle(c);
        return { fond: cs.backgroundColor, bordure: cs.borderTopWidth, couleur: cs.borderTopColor, ombre: cs.boxShadow };
      });
      expect(style.fond).toBe(SURFACE);
      expect(style.bordure).toBe('1px');
      expect(style.couleur).toBe(TRAIT);
      expect(style.ombre).not.toBe('none');

      const g = await mesurer(page);
      expect(g.carte.largeur).toBeCloseTo(416, 0);
      expect(Math.abs(g.carte.centreX - g.main.centreX)).toBeLessThanOrEqual(1);
      expect(g.main.paddingBas).toBeGreaterThanOrEqual(112);
      expect(g.carte.bas).toBeLessThanOrEqual(g.main.bas - 96);
      expect(g.main.bas).toBeGreaterThanOrEqual(g.innerHeight - 1);
      if (e.ecran === 'connexion') {
        expect(g.racineScrollHeight).toBeLessThanOrEqual(g.racineClientHeight);
        // Écarts mesurés dans la zone de contenu du main (hors marge haute de 1rem et hors bande réservée de 112 px).
        const ecartHaut = g.carte.haut - (g.main.haut + g.main.paddingHaut);
        const ecartBas = g.main.bas - 112 - g.carte.bas;
        expect(Math.abs(ecartHaut - ecartBas), `centrage vertical (haut ${ecartHaut}, bas ${ecartBas})`).toBeLessThanOrEqual(2);
      }

      if (e.ecran === 'creer-compte') {
        await soumettreCreationVide(page);
        const apres = await mesurer(page);
        expect(apres.carte.largeur).toBeCloseTo(416, 0);
        expect(Math.abs(apres.carte.centreX - apres.main.centreX)).toBeLessThanOrEqual(1);
        expect(apres.main.paddingBas).toBeGreaterThanOrEqual(112);
        expect(apres.carte.bas).toBeLessThanOrEqual(apres.main.bas - 96);
        expect(apres.main.bas).toBeGreaterThanOrEqual(apres.innerHeight - 1);
      }
    });
  }

  test('CA3 - le texte n\'est que dans la carte, avec les contrastes et la palette de la charte', async ({ page }) => {
    test.setTimeout(120_000);
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.setViewportSize({ width: 1280, height: 800 });
    const autorisees = [...RGB_PALETTE, RGB_TRANSPARENT];
    const noms = ['connexion', 'creation de compte', 'connexion avec erreur affichee', 'creation de compte avec champs invalides'];
    for (const nom of noms) {
      await test.step(nom, async () => {
        const nettoyage = await ouvrirScene(page, scene(nom), JEU_VIDE);
        const g = await mesurer(page);
        expect(g.texteHorsCarte, `texte hors carte (${nom})`).toEqual([]);

        const mesures = (await page.evaluate(() => (window as unknown as { __t: { contrastes(): MesureContraste[] } }).__t.contrastes())) as MesureContraste[];
        expect(mesures.length).toBeGreaterThan(0);
        const sousSeuil = mesures.filter((m) => m.ratio + 1e-9 < m.seuil).map((m) => `${m.element} ${m.couleur}/${m.fond} = ${m.ratio.toFixed(2)} < ${m.seuil}`);
        expect(sousSeuil, `contrastes sous le seuil (${nom})`).toEqual([]);
        expect(mesures.some((m) => m.fond.toLowerCase() === '#fffcf5')).toBe(true);

        // Aucun texte de la carte n'a un fond effectif issu du décor (trait ou crème) : le premier fond opaque est celui de la carte ou d'un message.
        const fondsDecor = await page.evaluate(() => {
          const carte = document.querySelector('main section.carte') as HTMLElement;
          const fautifs: string[] = [];
          for (const el of Array.from(carte.querySelectorAll('*'))) {
            if (!el.checkVisibility()) continue;
            if (!Array.from(el.childNodes).some((n) => n.nodeType === 3 && (n.textContent ?? '').trim())) continue;
            let n: Element | null = el;
            let fond = 'rgba(0, 0, 0, 0)';
            while (n && n !== carte.parentElement) {
              const c = getComputedStyle(n).backgroundColor;
              if (c !== 'rgba(0, 0, 0, 0)') { fond = c; break; }
              n = n.parentElement;
            }
            if (n === carte.parentElement || fond === 'rgb(247, 241, 227)' || fond === 'rgb(217, 207, 182)') {
              fautifs.push(`${el.getAttribute('data-testid') ?? el.tagName}: ${fond}`);
            }
          }
          return fautifs;
        });
        expect(fondsDecor, `texte posé sur le décor (${nom})`).toEqual([]);

        const ecarts = (await page.evaluate((a) => (window as unknown as { __t: { horsPalette(a: string[]): EcartCouleur[] } }).__t.horsPalette(a), autorisees)) as EcartCouleur[];
        expect(ecarts, `couleurs hors palette (${nom})`).toEqual([]);

        const idTitre = nom.startsWith('connexion') ? 'titre-connexion' : 'titre-creer-compte';
        expect(await page.getByTestId(idTitre).evaluate((e) => getComputedStyle(e).color)).toBe(FORET);
        await nettoyage?.();
      });
    }
  });

  const VIEWPORTS = [
    { w: 320, h: 640 },
    { w: 360, h: 640 },
    { w: 1280, h: 800 },
    { w: 1920, h: 1080 },
  ];

  for (const vp of VIEWPORTS) {
    test(`CA4 - pas de débordement, carte au-dessus des sapins à ${vp.w} x ${vp.h}`, async ({ page }) => {
      test.setTimeout(90_000);
      await page.setViewportSize({ width: vp.w, height: vp.h });
      for (const e of ECRANS_COMPTE) {
        for (const avecErreurs of e.ecran === 'creer-compte' ? [false, true] : [false]) {
          await test.step(`${e.ecran}${avecErreurs ? ' avec erreurs' : ''}`, async () => {
            await ouvrir(page, e);
            if (avecErreurs) await soumettreCreationVide(page);
            await page.evaluate(AIDES_PAGE);

            const g = await verifierContenuDansLaCarte(page, 96);
            expect(g.main.bas).toBeGreaterThanOrEqual(g.innerHeight - 1);
            if (vp.w <= 360) {
              expect(g.carte.largeur).toBeCloseTo(g.racineClientWidth - 32, 0);
              expect(g.carte.gauche).toBeCloseTo(16, 0);
            }
            if (vp.w >= 1920) expect(g.racineScrollHeight, 'défilement vertical').toBeLessThanOrEqual(g.racineClientHeight);

            const r = (await page.evaluate(() => (window as unknown as { __t: { responsive(): MesuresResponsive } }).__t.responsive())) as MesuresResponsive;
            expect(r.cibles, 'cibles tactiles < 44 px').toEqual([]);
            expect(r.petits, 'textes < 13 px').toEqual([]);

            // Défilée jusqu'en bas : la bande de sapins est visible sous la carte.
            await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
            const bas = await page.evaluate(() => {
              const main = document.querySelector('main') as HTMLElement;
              const carte = main.querySelector('section.carte') as HTMLElement;
              return { mainBas: main.getBoundingClientRect().bottom, carteBas: carte.getBoundingClientRect().bottom, fenetre: window.innerHeight };
            });
            expect(Math.abs(bas.mainBas - bas.fenetre)).toBeLessThanOrEqual(1);
            expect(bas.carteBas).toBeLessThanOrEqual(bas.fenetre - 96 + 0.5);
          });
        }
      }
    });
  }

  test('CA4 - en paysage 640 x 360 la carte reste au moins 96 px au-dessus du bas du main', async ({ page }) => {
    await page.setViewportSize({ width: 640, height: 360 });
    for (const e of ECRANS_COMPTE) {
      await ouvrir(page, e);
      if (e.ecran === 'creer-compte') await soumettreCreationVide(page);
      await verifierContenuDansLaCarte(page, 96);
    }
  });

  test('CA4 - l\'ordre de tabulation est celui d\'avant R.5', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    const ordres: { ecran: (typeof ECRANS_COMPTE)[number]; suite: string[] }[] = [
      { ecran: ECRANS_COMPTE[0], suite: ['champ-mot-de-passe', 'bouton-connexion', 'lien-creer-compte-connexion', 'lien-accueil'] },
      { ecran: ECRANS_COMPTE[1], suite: ['champ-mot-de-passe', 'champ-confirmation', 'bouton-creer-compte', 'lien-accueil'] },
    ];
    for (const { ecran, suite } of ordres) {
      await ouvrir(page, ecran);
      await page.getByTestId('champ-pseudo').focus();
      for (const attendu of suite) {
        await page.keyboard.press('Tab');
        await expect(page.locator(':focus')).toHaveAttribute('data-testid', attendu);
      }
    }
  });

  test('CA5 - la connexion à 320 px avec le texte à 200 % reste dans la carte', async ({ page }) => {
    test.setTimeout(120_000);
    // Message de fin de session : préparer la déconnexion avant l'agrandissement.
    const pseudo = pseudoUnique('r5d');
    await creerCompteParApi(page.request, pseudo);
    await seConnecter(page, pseudo);
    await page.setViewportSize({ width: 320, height: 640 });
    await seDeconnecterParLeMenu(page);
    await expect(page.getByTestId('message-deconnexion')).toBeVisible();
    await page.addStyleTag({ content: AGRANDIR });

    const verifier = async () => {
      const g = await verifierContenuDansLaCarte(page, 192);
      expect(g.racineScrollWidth).toBeLessThanOrEqual(320);
      expect(g.carte.overflowWrap).toBe('anywhere');
      expect(await tailleDePolice(page, 'titre-connexion')).toBe(56);
      await titreSansDebordement(page, 'titre-connexion');
    };

    await test.step('avec le message de déconnexion', verifier);

    await test.step('avec les erreurs de champ', async () => {
      await soumettreConnexionVide(page);
      await verifier();
    });

    await test.step('avec l\'erreur générale', async () => {
      await afficherErreurGeneraleConnexion(page);
      await verifier();
    });

    await test.step('page fraîche', async () => {
      await page.goto('/connexion');
      await expect(page.getByTestId('titre-connexion')).toBeVisible();
      await page.addStyleTag({ content: AGRANDIR });
      await verifier();
    });
  });

  for (const largeur of [320, 360]) {
    test(`CA6 - la création de compte à ${largeur} px avec le texte à 200 % reste dans la carte`, async ({ page }) => {
      test.setTimeout(120_000);
      await page.setViewportSize({ width: largeur, height: 640 });
      await page.goto('/creer-compte');
      await expect(page.getByTestId('titre-creer-compte')).toBeVisible();
      await page.addStyleTag({ content: AGRANDIR });
      await page.evaluate(() => document.fonts.ready.then(() => undefined));

      const verifier = async () => {
        const g = await verifierContenuDansLaCarte(page, 192);
        expect(g.racineScrollWidth).toBeLessThanOrEqual(g.racineClientWidth);
        expect(await tailleDePolice(page, 'titre-creer-compte')).toBe(56);
        await titreSansDebordement(page, 'titre-creer-compte');
      };

      await test.step('saisie', verifier);

      await test.step('trois erreurs de champ', async () => {
        await soumettreCreationVide(page);
        await verifier();
      });

      const pseudo = pseudoDe30();
      expect(pseudo).toHaveLength(30);
      const remplir = async () => {
        await page.getByTestId('champ-pseudo').fill(pseudo);
        await page.getByTestId('champ-mot-de-passe').fill(MOT_DE_PASSE);
        await page.getByTestId('champ-confirmation').fill(MOT_DE_PASSE);
      };

      await test.step('service indisponible', async () => {
        await page.route('**/api/comptes', async (route) => {
          if (route.request().method() === 'POST') {
            await route.fulfill({ status: 503, contentType: 'text/plain', body: 'Service Unavailable' });
          } else {
            await route.continue();
          }
        });
        await remplir();
        await page.getByTestId('bouton-creer-compte').click();
        await expect(page.getByTestId('erreur-generale')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
        await verifier();
        await page.unrouteAll({ behavior: 'ignoreErrors' });
      });

      await test.step('succès avec un pseudo de 30 caractères', async () => {
        await remplir();
        await page.getByTestId('bouton-creer-compte').click();
        const succes = page.getByTestId('message-succes');
        await expect(succes).toContainText(`Compte créé pour ${pseudo}.`);
        await expect(page.getByTestId('lien-se-connecter-succes')).toBeVisible();
        await verifier();
        const m = await succes.evaluate((e) => ({ sw: e.scrollWidth, cw: e.clientWidth }));
        expect(m.sw).toBeLessThanOrEqual(m.cw);
      });
    });
  }

  test('CA6 - la création de compte à 1280 px avec le texte à 200 % reste dans la carte', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto('/creer-compte');
    await expect(page.getByTestId('titre-creer-compte')).toBeVisible();
    await page.addStyleTag({ content: AGRANDIR });
    await soumettreCreationVide(page);
    const g = await verifierContenuDansLaCarte(page, 192);
    // La largeur maximale de la carte est de 26rem : elle suit le texte (832 px à 200 %).
    expect(g.carte.largeur).toBeCloseTo(26 * 32, 0);
    expect(g.racineScrollWidth).toBeLessThanOrEqual(g.racineClientWidth);
  });

  test('CA7 - mouvement réduit, contraste forcé et schéma de couleurs', async ({ page }) => {
    test.setTimeout(90_000);
    await page.setViewportSize({ width: 1280, height: 800 });

    await test.step('mouvement réduit', async () => {
      await page.emulateMedia({ reducedMotion: 'reduce' });
      await ouvrirConnexion(page);
      const mesures = await page.evaluate(() => {
        const lire = (e: Element) => {
          const cs = getComputedStyle(e);
          return { transition: cs.transitionDuration, animation: cs.animationName };
        };
        return {
          main: lire(document.querySelector('main')!),
          carte: lire(document.querySelector('main section.carte')!),
          bouton: lire(document.querySelector('[data-testid="bouton-connexion"]')!),
          animations: document.getAnimations().length,
        };
      });
      for (const m of [mesures.main, mesures.carte, mesures.bouton]) {
        expect(m.transition).toBe('0s');
        expect(m.animation).toBe('none');
      }
      expect(mesures.animations).toBe(0);
    });

    await test.step('sans émulation', async () => {
      await page.emulateMedia({ reducedMotion: 'no-preference', forcedColors: 'none' });
      for (const e of ECRANS_COMPTE) {
        await ouvrir(page, e);
        expect((await mesurerDecor(page)).backgroundImage).not.toBe('none');
      }
      expect(await page.evaluate(() => getComputedStyle(document.documentElement).colorScheme)).toBe('light');
    });

    await test.step('contraste forcé', async () => {
      await page.emulateMedia({ forcedColors: 'active' });
      for (const e of ECRANS_COMPTE) {
        await ouvrir(page, e);
        expect((await mesurerDecor(page)).backgroundImage).toBe('none');
        const bouton = e.ecran === 'connexion' ? 'bouton-connexion' : 'bouton-creer-compte';
        for (const cible of [page.locator('main section.carte'), page.getByTestId(e.idTitre), page.getByTestId('champ-pseudo'), page.getByTestId(bouton)]) {
          await expect(cible).toBeVisible();
          const r = await cible.evaluate((el) => {
            const b = el.getBoundingClientRect();
            return { gauche: b.left, droite: b.right, largeur: window.innerWidth };
          });
          expect(r.gauche).toBeGreaterThanOrEqual(0);
          expect(r.droite).toBeLessThanOrEqual(r.largeur);
        }
      }
    });
  });

  test('CA8 - parcours complet à 360 px : création, connexion, déconnexion et erreurs', async ({ page }) => {
    test.setTimeout(120_000);
    await page.setViewportSize({ width: 360, height: 640 });
    const imageAccueil = await backgroundImageDeAccueil(page);
    const pseudo = pseudoUnique('r5p');

    const controler = async () => {
      await verifierDecor(page, imageAccueil);
      const g = await mesurer(page);
      expect(g.debordements).toEqual([]);
      expect(g.texteHorsCarte).toEqual([]);
      expect(g.racineScrollWidth).toBeLessThanOrEqual(g.racineClientWidth);
    };

    await test.step('1. création de compte', async () => {
      await page.goto('/creer-compte');
      await expect(page.getByTestId('titre-creer-compte')).toBeVisible();
      await controler();
      await page.getByTestId('champ-pseudo').fill(pseudo);
      await page.getByTestId('champ-mot-de-passe').fill(MOT_DE_PASSE);
      await page.getByTestId('champ-confirmation').fill(MOT_DE_PASSE);
      await page.getByTestId('bouton-creer-compte').click();
      await expect(page.getByTestId('message-succes')).toHaveText(`Compte créé pour ${pseudo}. Vous pouvez maintenant vous connecter.`);
      await controler();
    });

    await test.step('2. connexion', async () => {
      await page.getByTestId('lien-se-connecter-succes').click();
      await expect(page).toHaveURL(/\/connexion$/);
      await expect(page.getByTestId('titre-connexion')).toBeVisible();
      await controler();
      await saisir(page, pseudo, MOT_DE_PASSE);
      await page.getByTestId('bouton-connexion').click();
      await expect(page).toHaveURL(/\/$/);
      await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
    });

    await test.step('3. déconnexion', async () => {
      await seDeconnecterParLeMenu(page);
      await expect(page).toHaveURL(/\/connexion$/);
      await expect(page.getByTestId('message-deconnexion')).toHaveText('Vous êtes déconnecté.');
      await controler();
    });

    await test.step('4a. mauvais mot de passe puis pseudo inconnu', async () => {
      await saisir(page, pseudo, 'mauvais-mot-de-passe-1');
      await page.getByTestId('bouton-connexion').click();
      await expect(page.getByTestId('erreur-generale')).toHaveText('Pseudo ou mot de passe incorrect.');
      await controler();
      await saisir(page, pseudoUnique('inconnu'), MOT_DE_PASSE);
      await page.getByTestId('bouton-connexion').click();
      await expect(page.getByTestId('erreur-generale')).toHaveText('Pseudo ou mot de passe incorrect.');
      await controler();
    });

    await test.step('4b. champs vides à la connexion', async () => {
      await page.goto('/connexion');
      await expect(page.getByTestId('titre-connexion')).toBeVisible();
      await page.getByTestId('bouton-connexion').click();
      await expect(page.getByTestId('erreur-pseudo')).toHaveText('Le pseudo est obligatoire.');
      await expect(page.getByTestId('erreur-mot-de-passe')).toHaveText('Le mot de passe est obligatoire.');
      await controler();
    });

    await test.step('4c. pseudo déjà utilisé (autre casse)', async () => {
      await page.goto('/creer-compte');
      await expect(page.getByTestId('titre-creer-compte')).toBeVisible();
      await page.getByTestId('champ-pseudo').fill(pseudo.toUpperCase());
      await page.getByTestId('champ-mot-de-passe').fill(MOT_DE_PASSE);
      await page.getByTestId('champ-confirmation').fill(MOT_DE_PASSE);
      await page.getByTestId('bouton-creer-compte').click();
      await expect(page.getByTestId('erreur-pseudo')).toHaveText('Ce pseudo est déjà utilisé.');
      await controler();
    });

    await test.step('4d. mot de passe de 11 caractères', async () => {
      await page.goto('/creer-compte');
      await expect(page.getByTestId('titre-creer-compte')).toBeVisible();
      await page.getByTestId('champ-pseudo').fill(pseudoUnique('r5c'));
      await page.getByTestId('champ-mot-de-passe').fill('abcdefghijk');
      await page.getByTestId('champ-confirmation').fill('abcdefghijk');
      await page.getByTestId('bouton-creer-compte').click();
      await expect(page.getByTestId('erreur-mot-de-passe')).toHaveText('Le mot de passe doit faire au moins 12 caractères.');
      await controler();
    });

    await test.step('4e. confirmation différente', async () => {
      await page.goto('/creer-compte');
      await expect(page.getByTestId('titre-creer-compte')).toBeVisible();
      await page.getByTestId('champ-pseudo').fill(pseudoUnique('r5c'));
      await page.getByTestId('champ-mot-de-passe').fill(MOT_DE_PASSE);
      await page.getByTestId('champ-confirmation').fill(`${MOT_DE_PASSE}-autre`);
      await page.getByTestId('bouton-creer-compte').click();
      await expect(page.getByTestId('erreur-confirmation')).toHaveText('Les deux mots de passe ne correspondent pas.');
      await controler();
    });

    await test.step('4f. champs vides à la création', async () => {
      await page.goto('/creer-compte');
      await expect(page.getByTestId('titre-creer-compte')).toBeVisible();
      await page.getByTestId('bouton-creer-compte').click();
      await expect(page.getByTestId('erreur-pseudo')).toHaveText('Le pseudo est obligatoire.');
      await controler();
    });
  });
});
