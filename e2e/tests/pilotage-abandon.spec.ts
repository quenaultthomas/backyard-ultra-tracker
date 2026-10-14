import { expect, test, type Page } from '@playwright/test';
import { connecterAdminMaster, exigerIdentifiantsAdminMaster } from './aide-admin';

/*
 * Incrément 4.8 : section « Coureurs en abandon » du pilotage (CA16 et CA17), réponses de GET .../pilotage
 * fabriquées (page.route). CA12 à CA15 : pilotage-abandon.horloge.spec.ts.
 */
test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const ID_SIMULE = '00000000-0000-0000-0000-000000000001';
const urlPilotage = (id: string) => `/administration/courses/${id}/pilotage`;
const lire = (page: Page, testid: string) => page.getByTestId(testid);
const T0 = Date.parse('2026-10-09T14:03:27Z');
const heure = (decalageMs: number) => new Date(T0 + decalageMs).toISOString();

async function connecterMaster(page: Page): Promise<void> {
  await connecterAdminMaster(page);
  await expect(page).toHaveURL(/\/administration$/);
}

interface Abandon {
  dossard: number;
  pseudo: string;
  terminees?: number;
  boucle: number | null;
  motif: 'HORS_DELAI' | 'VOLONTAIRE' | 'MANUEL' | 'AUTRE' | null;
}

const enCourse = (dossard: number, pseudo: string) => ({
  inscriptionId: `00000000-0000-0000-0000-0000000000a${dossard}`,
  dossard,
  pseudo,
  statut: 'EN_COURSE',
  nombreBouclesTerminees: 1,
  boucleCouranteTerminee: false,
});

const abandon = (a: Abandon) => ({
  inscriptionId: `00000000-0000-0000-0000-0000000000b${a.dossard}`,
  dossard: a.dossard,
  pseudo: a.pseudo,
  statut: 'ABANDON',
  nombreBouclesTerminees: a.terminees ?? 0,
  boucleAbandon: a.boucle,
  motifAbandon: a.motif,
});

function instantane(enCours: Array<ReturnType<typeof enCourse>>, abandons: Abandon[], surcharge: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    courseId: ID_SIMULE,
    nom: 'Backyard simulé',
    logoUrl: null,
    statut: 'EN_COURS',
    heureServeur: heure(30000),
    demarreeLe: '2026-10-09T14:03:27Z',
    dureeBoucleMinutes: 60,
    nombreMaxBoucles: 5,
    boucleCourante: 2,
    derniereBoucle: false,
    boucleMaxTerminee: false,
    debutBoucleCourante: '2026-10-09T14:03:27Z',
    finBoucleCourante: '2026-10-09T15:03:27Z',
    tempsAvantFinBoucleMillisecondes: 3570000,
    dureeEcouleeMillisecondes: 30000,
    nombreInscrits: enCours.length + abandons.length,
    nombreAyantBoucle: 0,
    nombreEnBoucle: enCours.length,
    inscritsEnCourse: enCours,
    nombreAbandons: abandons.length,
    inscritsEnAbandon: abandons.map(abandon),
    ...surcharge,
  };
}

async function simuler(page: Page, repondre: (n: number) => Record<string, unknown> | 'echec'): Promise<{ demandes: number }> {
  const etat = { demandes: 0 };
  await page.route('**/api/administration/courses/*/pilotage', async (route) => {
    const n = ++etat.demandes;
    const corps = repondre(n);
    if (corps === 'echec') {
      await route.abort('connectionrefused');
      return;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'cache-control': 'no-store' }, body: JSON.stringify(corps) });
  });
  return etat;
}

test.describe('Coureurs en abandon au pilotage', () => {
  test('CA16 - aucun abandon : « Aucun abandon. », compteur à zéro, pas de tableau', async ({ page }) => {
    await connecterMaster(page);
    await simuler(page, () => instantane([enCourse(1, 'Bruno')], []));
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-aucun-abandon')).toHaveText('Aucun abandon.');
    await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('0 coureur en abandon');
    await expect(lire(page, 'pilotage-abandons')).toHaveCount(0);
  });

  test('CA16 - trois abandons (Hors délai, Manuel, Autre) dans l\'ordre de dossard, puis un abandon Volontaire', async ({ page }) => {
    await connecterMaster(page);
    await simuler(page, (n) =>
      n === 1
        ? instantane([enCourse(1, 'Bruno')], [
            { dossard: 2, pseudo: 'Chloé', boucle: 1, motif: 'HORS_DELAI', terminees: 0 },
            { dossard: 3, pseudo: 'Tom', boucle: 2, motif: 'MANUEL', terminees: 1 },
            { dossard: 5, pseudo: 'Léa', boucle: 3, motif: 'AUTRE', terminees: 2 },
          ])
        : instantane([enCourse(1, 'Bruno')], [{ dossard: 4, pseudo: 'Karim', boucle: 1, motif: 'VOLONTAIRE', terminees: 0 }]),
    );
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('3 coureurs en abandon');
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(3);
    await expect(lire(page, 'pilotage-abandon-dossard')).toHaveText(['2', '3', '5']);
    await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText(['Chloé', 'Tom', 'Léa']);
    await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText(['Boucle 1', 'Boucle 2', 'Boucle 3']);
    await expect(lire(page, 'pilotage-abandon-motif')).toHaveText(['Hors délai', 'Manuel', 'Autre']);
    await expect(lire(page, 'pilotage-abandon-boucles-terminees')).toHaveText(['0', '1', '2']);
    await expect(lire(page, 'pilotage-aucun-abandon')).toHaveCount(0);

    await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('1 coureur en abandon', { timeout: 6000 });
    await expect(lire(page, 'pilotage-abandon-motif')).toHaveText('Volontaire');
    await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText('Karim');
  });

  test('CA16 - abandon legacy sans Boucle ni motif : « — » dans les deux cellules', async ({ page }) => {
    await connecterMaster(page);
    await simuler(page, () => instantane([enCourse(1, 'Bruno')], [{ dossard: 2, pseudo: 'Chloé', boucle: null, motif: null }]));
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText('—');
    await expect(lire(page, 'pilotage-abandon-motif')).toHaveText('—');
    await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('1 coureur en abandon');
  });

  test('CA16 - Course terminée avec abandons : section affichée avec « La course est terminée. »', async ({ page }) => {
    await connecterMaster(page);
    await simuler(page, () =>
      instantane([], [{ dossard: 2, pseudo: 'Chloé', boucle: 1, motif: 'HORS_DELAI', terminees: 0 }], {
        statut: 'TERMINEE',
        boucleCourante: null,
        demarreeLe: null,
        debutBoucleCourante: null,
        finBoucleCourante: null,
        tempsAvantFinBoucleMillisecondes: null,
        dureeEcouleeMillisecondes: null,
        nombreAyantBoucle: null,
        nombreEnBoucle: null,
      }),
    );
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-message-terminee')).toHaveText('La course est terminée.');
    await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('1 coureur en abandon');
    await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText('Chloé');
    await expect(lire(page, 'pilotage-abandon-motif')).toHaveText('Hors délai');
  });

  test('CA16 - une ligne passe de « en course » à « en abandon » en place, sans rechargement, focus conservé, au rythme de 3 s', async ({ page }) => {
    await connecterMaster(page);
    const bruno = enCourse(1, 'Bruno');
    const tom = enCourse(3, 'Tom');
    const etat = await simuler(page, (n) => {
      if (n === 1) return instantane([bruno, tom], []);
      if (n === 2) return instantane([bruno], [{ dossard: 3, pseudo: 'Tom', boucle: 1, motif: 'HORS_DELAI' }]);
      return instantane([bruno], [{ dossard: 3, pseudo: 'Tom', boucle: 1, motif: 'HORS_DELAI' }]);
    });
    await page.goto(urlPilotage(ID_SIMULE));
    await page.evaluate(() => ((window as unknown as Record<string, unknown>)['marqueurRechargement'] = 1));
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(2);
    await expect(lire(page, 'pilotage-aucun-abandon')).toBeVisible();
    const retour = lire(page, 'pilotage-retour');
    await retour.focus();
    const debut = Date.now();

    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1, { timeout: 6000 });
    await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText('Tom');
    await expect(lire(page, 'pilotage-inscrit-pseudo')).toHaveText('Bruno');
    await expect(lire(page, 'pilotage-compteur')).toHaveText('1 coureur en course sur 2 inscrits');
    await expect(retour).toBeFocused();
    expect(await page.evaluate(() => (window as unknown as Record<string, unknown>)['marqueurRechargement'])).toBe(1);

    // aucune requête hors rythme de 3 s : au plus une toutes les ~3 s depuis l'ouverture
    const ecoule = (Date.now() - debut) / 1000;
    expect(etat.demandes).toBeLessThanOrEqual(Math.ceil((ecoule + 1) / 3) + 2);
  });

  test('CA16 - réseau coupé : les dernières valeurs d\'abandon sont conservées avec l\'avertissement d\'actualisation', async ({ page }) => {
    await connecterMaster(page);
    await simuler(page, (n) =>
      n === 1 ? instantane([enCourse(1, 'Bruno')], [{ dossard: 3, pseudo: 'Tom', boucle: 1, motif: 'HORS_DELAI' }]) : 'echec',
    );
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText('Tom');
    await expect(lire(page, 'pilotage-avertissement-actualisation')).toBeVisible({ timeout: 8000 });
    await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText('Tom');
    await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText('Boucle 1');
    await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('1 coureur en abandon');
  });

  // ---------- CA17 ----------

  const PSEUDO_LONG = 'Abcdefghij'.repeat(3);

  async function ouvrirAvecAbandons(page: Page, largeur: number, agrandi: boolean): Promise<void> {
    await page.setViewportSize({ width: largeur, height: 700 });
    await connecterMaster(page);
    await simuler(page, () =>
      instantane([enCourse(1, 'Bruno')], [
        { dossard: 2, pseudo: PSEUDO_LONG, boucle: 1, motif: 'HORS_DELAI', terminees: 0 },
        { dossard: 3, pseudo: 'Tom', boucle: 12, motif: 'VOLONTAIRE', terminees: 11 },
      ]),
    );
    await page.goto(urlPilotage(ID_SIMULE));
    if (agrandi) await page.addStyleTag({ content: 'html{font-size:32px}' });
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(2);
  }

  async function verifierMiseEnPage(page: Page, largeur: number, agrandi: boolean): Promise<void> {
    await ouvrirAvecAbandons(page, largeur, agrandi);
    const mesures = await page.evaluate(() => {
      const el = document.documentElement;
      const carte = document.querySelector('main section')!.getBoundingClientRect();
      const rect = (id: string) => Array.from(document.querySelectorAll(`[data-testid="${id}"]`)).map((e) => e.getBoundingClientRect());
      return {
        debordeX: el.scrollWidth > el.clientWidth,
        gauche: carte.left,
        droite: carte.right,
        elements: ['pilotage-abandons', 'pilotage-abandon-pseudo', 'pilotage-abandon-boucle', 'pilotage-abandon-motif', 'pilotage-abandon-boucles-terminees'].flatMap((id) =>
          rect(id).map((r) => ({ id, left: r.left, right: r.right })),
        ),
      };
    });
    expect(mesures.debordeX, 'défilement horizontal').toBe(false);
    for (const e of mesures.elements) {
      expect(e.left, `${e.id} déborde à gauche`).toBeGreaterThanOrEqual(mesures.gauche - 1);
      expect(e.right, `${e.id} déborde à droite`).toBeLessThanOrEqual(mesures.droite + 1);
    }
    await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText(['Boucle 1', 'Boucle 12']);
    await expect(lire(page, 'pilotage-abandon-motif')).toHaveText(['Hors délai', 'Volontaire']);
    await expect(lire(page, 'pilotage-abandon-boucle').first()).toBeVisible();
    await expect(lire(page, 'pilotage-abandon-motif').first()).toBeVisible();
    await expect(lire(page, 'pilotage-abandon-pseudo').first()).toHaveText(PSEUDO_LONG);
  }

  for (const [largeur, agrandi] of [[375, false], [320, false], [320, true]] as const) {
    test(`CA17 - à ${largeur} px${agrandi ? ' avec un texte à 200 %' : ''} : aucun défilement horizontal, tableau des abandons dans la carte, Boucle et motif en texte`, async ({ page }) => {
      await verifierMiseEnPage(page, largeur, agrandi);
    });
  }

  test('CA17 - contraste AA, aucune couleur en dur, aucun aria-live ni role="status" ajouté', async ({ page }) => {
    await ouvrirAvecAbandons(page, 375, false);
    const ratios = await page.evaluate(() => {
      const parse = (c: string): number[] => {
        const m = c.match(/rgba?\(([^)]+)\)/);
        if (!m) throw new Error(`couleur inattendue : ${c}`);
        const p = m[1].split(/[ ,/]+/).filter(Boolean).map(Number);
        return [p[0], p[1], p[2], p.length > 3 ? p[3] : 1];
      };
      const lum = ([r, g, b]: number[]) => {
        const f = (v: number) => {
          const s = v / 255;
          return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        };
        return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
      };
      const fond = (e: Element): number[] => {
        for (let n: Element | null = e; n; n = n.parentElement) {
          const c = parse(getComputedStyle(n).backgroundColor);
          if (c[3] === 1) return c;
        }
        return [255, 255, 255, 1];
      };
      const ids = ['pilotage-compteur-abandons', 'pilotage-abandon-dossard', 'pilotage-abandon-pseudo', 'pilotage-abandon-boucle', 'pilotage-abandon-motif', 'pilotage-abandon-boucles-terminees'];
      return ids.flatMap((id) =>
        Array.from(document.querySelectorAll(`[data-testid="${id}"]`)).map((e) => {
          const l1 = lum(parse(getComputedStyle(e).color));
          const l2 = lum(fond(e));
          return { id: `${id}:${e.textContent?.trim()}`, ratio: (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05) };
        }),
      );
    });
    expect(ratios.length).toBeGreaterThanOrEqual(8);
    for (const r of ratios) expect(r.ratio, `contraste de ${r.id}`).toBeGreaterThanOrEqual(4.5);

    const regles = await page.evaluate(() => {
      const trouvees: string[] = [];
      for (const feuille of Array.from(document.styleSheets)) {
        let regs: CSSRuleList;
        try {
          regs = feuille.cssRules;
        } catch {
          continue;
        }
        for (const r of Array.from(regs)) {
          if (r instanceof CSSStyleRule && /pastille-statut--abandon/.test(r.selectorText)) trouvees.push(r.cssText);
        }
      }
      return trouvees;
    });
    expect(regles.length).toBeGreaterThan(0);
    for (const t of regles) expect(t, 'couleur en dur').not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/);

    // seul pilotage-annonce-boucle reste une région vivante ; rien dans la section des abandons
    const section = page.getByRole('region', { name: 'Coureurs en abandon' });
    await expect(section.locator('[aria-live], [role="status"], [role="alert"]')).toHaveCount(0);
    await expect(page.locator('main [aria-live]')).toHaveCount(0);
    await expect(page.locator('main [role="status"]')).toHaveCount(1);
    await expect(page.locator('main [role="status"]')).toHaveAttribute('data-testid', 'pilotage-annonce-boucle');
  });

  test('CA17 - prefers-reduced-motion : aucune animation ni transition sur la section des abandons', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await ouvrirAvecAbandons(page, 375, false);
    const fautifs = await page.evaluate(() =>
      Array.from(document.querySelectorAll('[data-testid="pilotage-abandons"], [data-testid="pilotage-abandons"] *, [data-testid="pilotage-compteur-abandons"]'))
        .map((e) => {
          const s = getComputedStyle(e);
          const dur = (v: string) => Math.max(0, ...v.split(',').map((x) => parseFloat(x) || 0));
          return (s.animationName !== 'none' && dur(s.animationDuration) > 0) || dur(s.transitionDuration) > 0 ? e.tagName.toLowerCase() : null;
        })
        .filter((x) => x !== null),
    );
    expect(fautifs).toEqual([]);
  });
});
