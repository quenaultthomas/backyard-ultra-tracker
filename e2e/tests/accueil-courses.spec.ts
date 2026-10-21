import { expect, test, type Page } from '@playwright/test';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  creerAdminParApi,
  dateAffichee,
  dateDansJours,
  exigerIdentifiantsAdminMaster,
  pseudoAdminUnique,
} from './aide-admin';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import { ouvrirMenuCompte, ouvrirMenuVisiteur } from './aide-entete';
import { avecApi } from './aide-horloge';
import { courseDemarreeAvecLogo, courseEnPreparation, lire, poserMarqueur } from './aide-suivi';
import { placerStatutCourseEnBase } from './aide-coureur';
import {
  MESSAGES_VIDES,
  MOTIF_API_COURSES,
  RUBRIQUES,
  TITRES,
  VIDE,
  carte,
  creerCourseDatee,
  creerCourseTerminee,
  nomUnique,
  ordreDansRubrique,
  ouvrirAccueil,
  pageAnonyme,
  repartition,
  rubrique,
} from './aide-accueil';

/*
 * Incrément 6.4 : accueil public (CA11 à CA18). Contexte neuf sans session ; Courses aux noms uniques : la base E2E
 * contient d'autres Courses, les assertions portent sur celles créées par le test.
 */

/*
 * Dates : l'API refuse une date à plus de 5 ans (DATE_TROP_LOINTAINE), donc pas de 2099 ; trois dates futures
 * distinctes (+1100, +1200, +1300 jours) jouent le rôle de 2099-01-01, 2099-02-01 et 2099-03-01.
 */
test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test.describe('Accueil public : rubriques', () => {
  test('CA11 - un visiteur anonyme voit chaque Course dans la bonne rubrique, avec nom, date et nombre', async ({ browser, playwright }) => {
    const aVenir = await courseEnPreparation(playwright);
    const enCours = await courseDemarreeAvecLogo(playwright, { avecLogo: false });
    const terminee = await courseDemarreeAvecLogo(playwright, { avecLogo: false });
    placerStatutCourseEnBase(terminee.id, 'TERMINEE');
    const date = dateAffichee(dateDansJours(0));

    const page = await pageAnonyme(browser);
    try {
      await page.goto('/');
      await expect(page).toHaveURL(/\/$/);
      await expect(page.getByTestId('titre')).toBeVisible();
      await expect(page.getByTestId('accroche')).toBeVisible();
      await expect(page.getByTestId('accueil-rubrique-en-cours')).toBeVisible();

      // ordre des rubriques : En cours, À venir, Terminées
      const cles = await page.locator('[data-testid^="accueil-rubrique-titre-"]').evaluateAll((els) => els.map((e) => e.getAttribute('data-testid')));
      expect(cles).toEqual(RUBRIQUES.map((c) => `accueil-rubrique-titre-${c}`));
      for (const cle of RUBRIQUES) {
        await expect(page.getByTestId(`accueil-rubrique-titre-${cle}`)).toContainText(TITRES[cle]);
      }

      const attendu = { [aVenir.nom]: 'a-venir', [enCours.nom]: 'en-cours', [terminee.nom]: 'terminees' } as const;
      expect(await repartition(page, Object.keys(attendu))).toEqual(attendu);
      for (const nom of Object.keys(attendu)) {
        const c = carte(page, nom);
        await expect(c.getByTestId('accueil-course-nom')).toHaveText(nom);
        await expect(c.getByTestId('accueil-course-date')).toHaveText(date);
        await expect(c.getByTestId('accueil-course-date')).toHaveAttribute('datetime', dateDansJours(0));
      }

      // le titre de rubrique indique le nombre de Courses affichées
      for (const cle of RUBRIQUES) {
        const nombre = await rubrique(page, cle).getByTestId('accueil-course').count();
        await expect(page.getByTestId(`accueil-rubrique-titre-${cle}`)).toHaveText(new RegExp(`\\(${nombre}\\)\\s*$`));
      }
    } finally {
      await page.context().close();
    }
  });

  test('CA12 - « Voir le suivi » mène au suivi de la Course ; le logo réel ou un visuel de substitution est affiché', async ({ browser, playwright }) => {
    const aVenir = await courseEnPreparation(playwright);
    const enCours = await courseDemarreeAvecLogo(playwright, { avecLogo: true });
    const terminee = await courseDemarreeAvecLogo(playwright, { avecLogo: true });
    placerStatutCourseEnBase(terminee.id, 'TERMINEE');

    const page = await pageAnonyme(browser);
    try {
      await ouvrirAccueil(page);

      // logo réel : image chargée dont le src contient /api/courses/<id>/logo
      const logo = carte(page, enCours.nom).locator('[data-testid=accueil-course-logo] img');
      await expect(logo).toBeVisible();
      await expect(logo).toHaveAttribute('src', new RegExp(`/api/courses/${enCours.id}/logo`));
      await expect.poll(() => logo.evaluate((i: HTMLImageElement) => i.complete && i.naturalWidth)).toBeGreaterThan(0);

      // sans logo : visuel de substitution, aucune image
      const sansLogo = carte(page, aVenir.nom).getByTestId('accueil-course-logo');
      await expect(sansLogo).toBeVisible();
      await expect(sansLogo.locator('img')).toHaveCount(0);

      // le nom accessible du lien contient le nom de la Course
      const lien = carte(page, enCours.nom).getByTestId('accueil-course-lien-suivi');
      await expect(lien).toHaveText('Voir le suivi');
      await expect(lien).toHaveAttribute('aria-label', `Voir le suivi de ${enCours.nom}`);
      await expect(carte(page, enCours.nom).getByRole('link', { name: new RegExp(enCours.nom) })).toHaveCount(1);

      const cas = [
        { c: aVenir, message: 'suivi-message-non-demarree' },
        { c: terminee, message: 'suivi-message-terminee' },
      ];
      for (const { c, message } of cas) {
        await ouvrirAccueil(page);
        await carte(page, c.nom).getByTestId('accueil-course-lien-suivi').click();
        await expect(page).toHaveURL(new RegExp(`/courses/${c.id}/suivi$`));
        await expect(page.getByTestId('suivi-nom')).toHaveText(c.nom);
        await expect(lire(page, message)).toBeVisible();
      }

      // Course démarrée : le suivi affiche la boucle en cours
      await ouvrirAccueil(page);
      await carte(page, enCours.nom).getByTestId('accueil-course-lien-suivi').click();
      await expect(page).toHaveURL(new RegExp(`/courses/${enCours.id}/suivi$`));
      await expect(page.getByTestId('suivi-nom')).toHaveText(enCours.nom);
      await expect(page.getByTestId('suivi-boucle')).toBeVisible();
    } finally {
      await page.context().close();
    }
  });

  test('CA13 - « À venir » trie par date croissante, « Terminées » par date décroissante', async ({ browser, playwright }) => {
    const nomMars = nomUnique('mars');
    const nomJanvier = nomUnique('janvier');
    const nomFevrier = nomUnique('fevrier');
    await creerCourseDatee(playwright, nomMars, dateDansJours(1300));
    await creerCourseDatee(playwright, nomJanvier, dateDansJours(1100));
    await creerCourseDatee(playwright, nomFevrier, dateDansJours(1200));
    const nomAncienne = nomUnique('ancienne');
    const nomRecente = nomUnique('recente');
    await creerCourseTerminee(playwright, nomAncienne, dateDansJours(1400));
    await creerCourseTerminee(playwright, nomRecente, dateDansJours(1500));

    const page = await pageAnonyme(browser);
    try {
      await ouvrirAccueil(page);
      expect(await ordreDansRubrique(page, 'a-venir', [nomMars, nomJanvier, nomFevrier])).toEqual([nomJanvier, nomFevrier, nomMars]);
      expect(await ordreDansRubrique(page, 'terminees', [nomAncienne, nomRecente])).toEqual([nomRecente, nomAncienne]);
      await expect(carte(page, nomJanvier, 'a-venir').getByTestId('accueil-course-date')).toHaveText(dateAffichee(dateDansJours(1100)));
    } finally {
      await page.context().close();
    }
  });
});

test.describe('Accueil public : états', () => {
  test('CA15 - rubriques vides et chargement tant que la réponse est retardée', async ({ browser }) => {
    const page = await pageAnonyme(browser);
    try {
      let liberer!: () => void;
      const feu = new Promise<void>((r) => (liberer = r));
      await page.route(MOTIF_API_COURSES, async (route) => {
        await feu;
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(VIDE) });
      });
      await page.goto('/');
      await expect(page.getByTestId('accueil-chargement')).toHaveText('Chargement des courses…');
      await expect(page.getByTestId('titre')).toBeVisible();
      await expect(page.getByTestId('accroche')).toBeVisible();
      await expect(page.getByTestId('accueil-rubrique-en-cours')).toHaveCount(0);

      liberer();
      await expect(page.getByTestId('accueil-chargement')).toHaveCount(0);
      for (const cle of RUBRIQUES) {
        await expect(page.getByTestId(`accueil-vide-${cle}`)).toHaveText(MESSAGES_VIDES[cle]);
        await expect(page.getByTestId(`accueil-rubrique-titre-${cle}`)).toContainText(TITRES[cle]);
        await expect(page.getByTestId(`accueil-rubrique-titre-${cle}`)).toContainText('(0)');
      }
      await expect(page.getByTestId('titre')).toBeVisible();
      await expect(page.getByTestId('accroche')).toBeVisible();
    } finally {
      await page.context().close();
    }
  });

  for (const panne of ['503', 'coupure réseau'] as const) {
    test(`CA16 - ${panne} : message d'erreur, puis « Réessayer » rétablit les rubriques sans rechargement`, async ({ browser, playwright }) => {
      const nom = nomUnique('reessai');
      await creerCourseDatee(playwright, nom, dateDansJours(1250));
      const page = await pageAnonyme(browser);
      try {
        let enPanne = true;
        await page.route(MOTIF_API_COURSES, async (route) => {
          if (!enPanne) return route.fallback();
          if (panne === '503') {
            return route.fulfill({ status: 503, contentType: 'application/problem+json', body: '{"title":"indisponible"}' });
          }
          return route.abort('failed');
        });
        await page.goto('/');
        await expect(page.getByTestId('accueil-erreur')).toHaveText('Les courses ne peuvent pas être chargées pour le moment.');
        await expect(page.getByTestId('accueil-reessayer')).toBeVisible();
        await expect(page.getByTestId('titre')).toBeVisible();
        await expect(page.getByTestId('accroche')).toBeVisible();
        await expect(page.getByTestId('accueil-rubrique-a-venir')).toHaveCount(0);

        await poserMarqueur(page);
        enPanne = false;
        await page.getByTestId('accueil-reessayer').click();
        await expect(page.getByTestId('accueil-erreur')).toHaveCount(0);
        await expect(carte(page, nom, 'a-venir')).toBeVisible();
        await expect(page.getByTestId('titre')).toBeVisible();
        expect(await page.evaluate(() => (window as unknown as Record<string, unknown>)['marqueurRechargement'])).toBe(1);
      } finally {
        await page.context().close();
      }
    });
  }
});

test.describe('Accueil public : sessions', () => {
  test('CA17 - Nadia, Alice et un anonyme voient les mêmes Courses dans les mêmes rubriques, sans action en plus', async ({ browser, playwright }) => {
    const aVenir = await courseEnPreparation(playwright);
    const enCours = await courseDemarreeAvecLogo(playwright, { avecLogo: false });
    const noms = [aVenir.nom, enCours.nom];
    const attendu = { [aVenir.nom]: 'a-venir', [enCours.nom]: 'en-cours' };

    const pseudoAdmin = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, pseudoAdmin));
    const pseudoCoureur = pseudoUnique('alice');
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudoCoureur));

    const sessions: Array<{ nom: string; pseudo: string | null; motDePasse: string }> = [
      { nom: 'admin', pseudo: pseudoAdmin, motDePasse: MOT_DE_PASSE_ADMIN_CREE },
      { nom: 'coureur', pseudo: pseudoCoureur, motDePasse: MOT_DE_PASSE },
      { nom: 'anonyme', pseudo: null, motDePasse: '' },
    ];
    for (const s of sessions) {
      const page = await pageAnonyme(browser);
      try {
        if (s.pseudo) {
          await ouvrirConnexion(page);
          await saisir(page, s.pseudo, s.motDePasse);
          await page.getByTestId('bouton-connexion').click();
          await expect(page.getByTestId('entete-pseudo')).toHaveText(s.pseudo);
        }
        await ouvrirAccueil(page);
        expect(await repartition(page, noms), s.nom).toEqual(attendu);
        for (const nom of noms) {
          const c = carte(page, nom);
          await expect(c.getByRole('link')).toHaveCount(1);
          await expect(c.getByRole('button')).toHaveCount(0);
          await expect(c.locator('a[href*="administration"], a[href*="inscri"], a[href*="pilotage"]')).toHaveCount(0);
          await expect(c).not.toContainText(/s'inscrire|inscription|administration|pilotage/i);
        }
        // en-tête : celui existant pour chaque session
        if (s.pseudo) {
          await expect(page.getByTestId('entete-pseudo')).toHaveText(s.pseudo);
          await ouvrirMenuCompte(page);
        } else {
          await expect(page.getByTestId('entete-pseudo')).toHaveCount(0);
          await ouvrirMenuVisiteur(page);
        }
      } finally {
        await page.context().close();
      }
    }
  });
});

test.describe('Accueil public : adaptation et charte', () => {
  const RACINE = join(__dirname, '..', '..');

  /**
   * 60 caractères sans espace. Un point au milieu : `inscription-course` CA11 interdit toute suite de 43 caractères
   * alphanumériques dans la liste des courses ouvertes, où ce nom apparaît aussi (même précaution que `nomCourseLong`).
   */
  const nomLong = (): string => {
    const unique = nomUnique('').replace(/\s+/g, '');
    const debut = `W${unique}`.padEnd(30, 'z').slice(0, 30);
    return `${debut}.${'y'.repeat(29)}`;
  };

  async function creerTroisCourses(playwright: Parameters<typeof creerCourseDatee>[0]): Promise<string[]> {
    const noms = [nomLong(), nomUnique('b'), nomUnique('c')];
    await creerCourseDatee(playwright, noms[0], dateDansJours(1600));
    await creerCourseDatee(playwright, noms[1], dateDansJours(1601));
    await creerCourseDatee(playwright, noms[2], dateDansJours(1602));
    return noms;
  }

  const sansDefilement = (page: Page) =>
    page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1);

  const configs = [
    { nom: '375 px', largeur: 375, hauteur: 800, police: false },
    { nom: '320 px', largeur: 320, hauteur: 800, police: false },
    { nom: '320 px, texte à 200 %', largeur: 320, hauteur: 800, police: true },
    { nom: '1280 px', largeur: 1280, hauteur: 800, police: false },
  ];
  for (const cfg of configs) {
    test(`CA18 - ${cfg.nom} : aucun défilement horizontal, nom de 60 caractères coupé et lisible`, async ({ browser, playwright }) => {
      const [long] = await creerTroisCourses(playwright);
      const contexte = await browser.newContext({ serviceWorkers: 'block', viewport: { width: cfg.largeur, height: cfg.hauteur } });
      const page = await contexte.newPage();
      try {
        await page.goto('/');
        if (cfg.police) await page.addStyleTag({ content: 'html { font-size: 200% !important; }' });
        await ouvrirAccueil(page);
        const nom = carte(page, long).getByTestId('accueil-course-nom');
        await nom.scrollIntoViewIfNeeded();
        await expect(nom).toBeVisible();
        expect(await sansDefilement(page)).toBe(true);
        const mesure = await nom.evaluate((e) => {
          const r = e.getBoundingClientRect();
          return { depasse: e.scrollWidth > e.clientWidth + 1, droite: r.right, gauche: r.left, vue: document.documentElement.clientWidth };
        });
        expect(mesure.depasse, 'texte du nom qui déborde de son conteneur').toBe(false);
        expect(mesure.droite).toBeLessThanOrEqual(mesure.vue + 1);
        expect(mesure.gauche).toBeGreaterThanOrEqual(0);
        await expect(carte(page, long).getByTestId('accueil-course-lien-suivi')).toBeVisible();
        await expect(page.locator('main.accueil')).toHaveClass(/decor-backyard/);
      } finally {
        await contexte.close();
      }
    });
  }

  test('CA18 - liens atteignables au clavier dans l\'ordre d\'affichage avec un focus visible', async ({ browser, playwright }) => {
    const noms = await creerTroisCourses(playwright);
    const page = await pageAnonyme(browser);
    try {
      await ouvrirAccueil(page);
      const liens = page.getByTestId('accueil-course-lien-suivi');
      const hrefs = await liens.evaluateAll((els) => els.map((e) => e.getAttribute('href')));
      const premier = await carte(page, noms[0]).getByTestId('accueil-course-lien-suivi').getAttribute('href');
      const debut = hrefs.indexOf(premier);
      expect(debut).toBeGreaterThanOrEqual(0);
      await carte(page, noms[0]).getByTestId('accueil-course-lien-suivi').focus();
      for (let i = 1; i <= 2; i++) {
        await page.keyboard.press('Tab');
        const etat = await page.evaluate(() => {
          const a = document.activeElement as HTMLElement;
          const s = getComputedStyle(a);
          return {
            href: a.getAttribute('href'),
            testid: a.getAttribute('data-testid'),
            contour: s.outlineStyle !== 'none' && parseFloat(s.outlineWidth) > 0,
            ombre: s.boxShadow !== 'none',
            visible: a.matches(':focus-visible'),
          };
        });
        expect(etat.testid).toBe('accueil-course-lien-suivi');
        expect(etat.href).toBe(hrefs[debut + i]);
        expect(etat.visible).toBe(true);
        expect(etat.contour || etat.ombre, 'focus visible').toBe(true);
      }
    } finally {
      await page.context().close();
    }
  });

  test('CA18 - contrastes AA du nom, de la date et du lien', async ({ browser, playwright }) => {
    const noms = await creerTroisCourses(playwright);
    const page = await pageAnonyme(browser);
    try {
      await ouvrirAccueil(page);
      const c = carte(page, noms[1]);
      const mesures = await c.evaluate((racine) => {
        const parse = (v: string) => (v.match(/[\d.]+/g) ?? []).map(Number);
        const lum = ([r, g, b]: number[]) => {
          const f = (x: number) => ((x /= 255) <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4));
          return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
        };
        const fond = (e: Element | null): number[] => {
          while (e) {
            const v = parse(getComputedStyle(e).backgroundColor);
            if (v.length >= 3 && (v.length === 3 || v[3] > 0.99)) return v;
            e = e.parentElement;
          }
          return [255, 255, 255];
        };
        return ['accueil-course-nom', 'accueil-course-date', 'accueil-course-lien-suivi'].map((id) => {
          const el = racine.querySelector(`[data-testid=${id}]`)!;
          const a = lum(parse(getComputedStyle(el).color));
          const b = lum(fond(el));
          return { id, ratio: (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05) };
        });
      });
      for (const m of mesures) expect(m.ratio, m.id).toBeGreaterThanOrEqual(4.5);
    } finally {
      await page.context().close();
    }
  });

  test('CA18 - prefers-reduced-motion : aucune animation sur l\'accueil', async ({ browser, playwright }) => {
    await creerTroisCourses(playwright);
    const contexte = await browser.newContext({ serviceWorkers: 'block', reducedMotion: 'reduce' });
    const page = await contexte.newPage();
    try {
      await ouvrirAccueil(page);
      const anim = await page.evaluate(() => {
        const actives = document.getAnimations().length;
        const avecAnimation = [...document.querySelectorAll('main.accueil, main.accueil *')].filter(
          (e) => getComputedStyle(e).animationName !== 'none',
        ).length;
        const transitions = new Set(
          [...document.querySelectorAll('main.accueil, main.accueil *')].flatMap((e) => {
            const s = getComputedStyle(e);
            return parseFloat(s.transitionDuration) > 0 ? s.transitionProperty.split(',').map((p) => p.trim()) : [];
          }),
        );
        return { actives, avecAnimation, transitions: [...transitions] };
      });
      expect(anim.actives).toBe(0);
      expect(anim.avecAnimation).toBe(0);
      for (const p of anim.transitions) expect(p, 'transition hors couleur').toMatch(/color|^all$/);
    } finally {
      await contexte.close();
    }
  });

  test('CA18 - aucune couleur en dur dans les styles ajoutés de l\'accueil', () => {
    const dossier = join(RACINE, 'frontend/src/app/accueil');
    const fichiers: string[] = [];
    const parcourir = (d: string) => {
      for (const f of readdirSync(d)) {
        const chemin = join(d, f);
        if (statSync(chemin).isDirectory()) parcourir(chemin);
        else if (f.endsWith('.css')) fichiers.push(chemin);
      }
    };
    parcourir(dossier);
    expect(fichiers.length).toBeGreaterThan(0);
    for (const f of fichiers) {
      const css = readFileSync(f, 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
      expect(css, f).not.toMatch(/#[0-9a-fA-F]{3,8}\b/);
      expect(css, f).not.toMatch(/\b(rgb|rgba|hsl|hsla)\(/);
    }
  });
});
