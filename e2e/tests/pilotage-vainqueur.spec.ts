import { expect, test, type Page } from '@playwright/test';
import { connecterAdminMaster, exigerIdentifiantsAdminMaster } from './aide-admin';

/*
 * Incrément 4.9 : section « Vainqueur(s) » du pilotage (CA15 et CA16), réponses de GET .../pilotage fabriquées (page.route).
 * CA11 à CA14 : pilotage-vainqueur.horloge.spec.ts.
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

interface Vainqueur {
  dossard: number;
  pseudo: string;
  terminees?: number;
}

const enCourse = (dossard: number, pseudo: string) => ({
  inscriptionId: `00000000-0000-0000-0000-0000000000a${dossard}`,
  dossard,
  pseudo,
  statut: 'EN_COURSE',
  nombreBouclesTerminees: 1,
  boucleCouranteTerminee: false,
});

const vainqueur = (v: Vainqueur) => ({
  inscriptionId: `00000000-0000-0000-0000-0000000000c${v.dossard}`,
  dossard: v.dossard,
  pseudo: v.pseudo,
  statut: 'VAINQUEUR',
  nombreBouclesTerminees: v.terminees ?? 1,
});

const abandon = (dossard: number, pseudo: string) => ({
  inscriptionId: `00000000-0000-0000-0000-0000000000b${dossard}`,
  dossard,
  pseudo,
  statut: 'ABANDON',
  nombreBouclesTerminees: 0,
  boucleAbandon: 1,
  motifAbandon: 'HORS_DELAI',
});

const TERMINEE = {
  statut: 'TERMINEE',
  boucleCourante: null,
  demarreeLe: null,
  debutBoucleCourante: null,
  finBoucleCourante: null,
  tempsAvantFinBoucleMillisecondes: null,
  dureeEcouleeMillisecondes: null,
  nombreAyantBoucle: null,
  nombreEnBoucle: null,
};

function instantane(
  enCours: Array<ReturnType<typeof enCourse>>,
  vainqueurs: Vainqueur[],
  abandons: Array<ReturnType<typeof abandon>> = [],
  surcharge: Record<string, unknown> = {},
): Record<string, unknown> {
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
    nombreInscrits: enCours.length + vainqueurs.length + abandons.length,
    nombreAyantBoucle: 0,
    nombreEnBoucle: enCours.length,
    inscritsEnCourse: enCours,
    nombreAbandons: abandons.length,
    inscritsEnAbandon: abandons,
    nombreVainqueurs: vainqueurs.length,
    inscritsVainqueurs: vainqueurs.map(vainqueur),
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

test.describe('Vainqueur(s) au pilotage', () => {
  test('CA15 - Course terminée avec un vainqueur : section « Vainqueur » (singulier), ligne complète, message de fin, « Aucun coureur en course. »', async ({ page }) => {
    await connecterMaster(page);
    await simuler(page, () => instantane([], [{ dossard: 1, pseudo: 'Bruno', terminees: 3 }], [abandon(2, 'Chloé'), abandon(3, 'Tom')], TERMINEE));
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-vainqueurs')).toBeVisible();
    await expect(lire(page, 'pilotage-vainqueurs-titre')).toHaveText('Vainqueur');
    await expect(lire(page, 'pilotage-vainqueur')).toHaveCount(1);
    await expect(lire(page, 'pilotage-vainqueur-dossard')).toHaveText('1');
    await expect(lire(page, 'pilotage-vainqueur-pseudo')).toHaveText('Bruno');
    await expect(lire(page, 'pilotage-vainqueur-statut')).toHaveText('Vainqueur');
    await expect(lire(page, 'pilotage-vainqueur-boucles-terminees')).toHaveText('3');
    await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée');
    await expect(lire(page, 'pilotage-message-terminee')).toHaveText('La course est terminée.');
    await expect(lire(page, 'pilotage-inscrits-vide')).toHaveText('Aucun coureur en course.');
    await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('2 coureurs en abandon');
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(2);
    // la section Vainqueur est placée avant le tableau « Coureurs en course »
    const ordre = await page.evaluate(() => {
      const v = document.querySelector('[data-testid="pilotage-vainqueurs"]')!;
      const c = document.querySelector('[data-testid="pilotage-inscrits-vide"]')!;
      return v.compareDocumentPosition(c) & Node.DOCUMENT_POSITION_FOLLOWING ? 'avant' : 'apres';
    });
    expect(ordre).toBe('avant');
  });

  test('CA15 - deux vainqueurs : titre « Vainqueurs » (pluriel), lignes dans l\'ordre de dossard', async ({ page }) => {
    await connecterMaster(page);
    await simuler(page, () =>
      instantane([], [{ dossard: 2, pseudo: 'Chloé', terminees: 5 }, { dossard: 4, pseudo: 'Karim', terminees: 5 }], [abandon(1, 'Bruno')], TERMINEE),
    );
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-vainqueurs-titre')).toHaveText('Vainqueurs');
    await expect(lire(page, 'pilotage-vainqueur')).toHaveCount(2);
    await expect(lire(page, 'pilotage-vainqueur-dossard')).toHaveText(['2', '4']);
    await expect(lire(page, 'pilotage-vainqueur-pseudo')).toHaveText(['Chloé', 'Karim']);
    await expect(lire(page, 'pilotage-vainqueur-boucles-terminees')).toHaveText(['5', '5']);
  });

  test('CA15 - nombreVainqueurs à 0 : aucune section « Vainqueur »', async ({ page }) => {
    await connecterMaster(page);
    await simuler(page, () => instantane([enCourse(1, 'Bruno')], []));
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(1);
    await expect(lire(page, 'pilotage-vainqueurs')).toHaveCount(0);
    await expect(lire(page, 'pilotage-vainqueurs-titre')).toHaveCount(0);
  });

  test('CA15 - le front n\'infère jamais un vainqueur : un seul coureur restant en course, liste vide, aucune section', async ({ page }) => {
    await connecterMaster(page);
    await simuler(page, () => instantane([enCourse(1, 'Bruno')], [], [abandon(2, 'Chloé'), abandon(3, 'Tom')]));
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-inscrit-pseudo')).toHaveText('Bruno');
    await expect(lire(page, 'pilotage-compteur-abandons')).toHaveText('2 coureurs en abandon');
    await expect(lire(page, 'pilotage-vainqueurs')).toHaveCount(0);
    await expect(lire(page, 'pilotage-statut')).toHaveText('En cours');
  });

  test('CA15 - bascule en cours -> terminée en place, sans rechargement, focus conservé, au rythme de 3 s', async ({ page }) => {
    await connecterMaster(page);
    const bruno = enCourse(1, 'Bruno');
    const etat = await simuler(page, (n) =>
      n === 1
        ? instantane([bruno], [])
        : instantane([], [{ dossard: 1, pseudo: 'Bruno', terminees: 1 }], [abandon(2, 'Chloé')], TERMINEE),
    );
    await page.goto(urlPilotage(ID_SIMULE));
    await page.evaluate(() => ((window as unknown as Record<string, unknown>)['marqueurRechargement'] = 1));
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(1);
    await expect(lire(page, 'pilotage-vainqueurs')).toHaveCount(0);
    await expect(lire(page, 'pilotage-statut')).toHaveText('En cours');
    const retour = lire(page, 'pilotage-retour');
    await retour.focus();
    const debut = Date.now();

    await expect(lire(page, 'pilotage-vainqueurs')).toBeVisible({ timeout: 6000 });
    await expect(lire(page, 'pilotage-vainqueur-pseudo')).toHaveText('Bruno');
    await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée');
    await expect(lire(page, 'pilotage-message-terminee')).toBeVisible();
    await expect(lire(page, 'pilotage-inscrits-vide')).toBeVisible();
    await expect(lire(page, 'pilotage-abandon-pseudo')).toHaveText('Chloé');
    await expect(retour).toBeFocused();
    expect(await page.evaluate(() => (window as unknown as Record<string, unknown>)['marqueurRechargement'])).toBe(1);

    const ecoule = (Date.now() - debut) / 1000;
    expect(etat.demandes).toBeLessThanOrEqual(Math.ceil((ecoule + 1) / 3) + 2);
  });

  test('CA15 - réseau coupé : le vainqueur reste affiché avec l\'avertissement d\'actualisation', async ({ page }) => {
    await connecterMaster(page);
    await simuler(page, (n) => (n === 1 ? instantane([], [{ dossard: 1, pseudo: 'Bruno' }], [], TERMINEE) : 'echec'));
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-vainqueur-pseudo')).toHaveText('Bruno');
    await expect(lire(page, 'pilotage-avertissement-actualisation')).toBeVisible({ timeout: 8000 });
    await expect(lire(page, 'pilotage-vainqueur-pseudo')).toHaveText('Bruno');
    await expect(lire(page, 'pilotage-vainqueurs')).toBeVisible();
  });

  // ---------- CA16 ----------

  const PSEUDO_LONG = 'Abcdefghij'.repeat(3);

  async function ouvrirAvecVainqueur(page: Page, largeur: number, agrandi: boolean): Promise<void> {
    await page.setViewportSize({ width: largeur, height: 700 });
    await connecterMaster(page);
    await simuler(page, () => instantane([], [{ dossard: 1, pseudo: PSEUDO_LONG, terminees: 12 }], [abandon(2, 'Chloé')], TERMINEE));
    await page.goto(urlPilotage(ID_SIMULE));
    if (agrandi) await page.addStyleTag({ content: 'html{font-size:32px}' });
    await expect(lire(page, 'pilotage-vainqueur')).toHaveCount(1);
  }

  for (const [largeur, agrandi] of [[375, false], [320, false], [320, true]] as const) {
    test(`CA16 - à ${largeur} px${agrandi ? ' avec un texte à 200 %' : ''} : aucun défilement horizontal, section Vainqueur dans la carte, statut en texte`, async ({ page }) => {
      await ouvrirAvecVainqueur(page, largeur, agrandi);
      const mesures = await page.evaluate(() => {
        const el = document.documentElement;
        const carte = document.querySelector('main section')!.getBoundingClientRect();
        const rect = (id: string) => Array.from(document.querySelectorAll(`[data-testid="${id}"]`)).map((e) => e.getBoundingClientRect());
        return {
          debordeX: el.scrollWidth > el.clientWidth,
          gauche: carte.left,
          droite: carte.right,
          elements: ['pilotage-vainqueurs', 'pilotage-vainqueur-pseudo', 'pilotage-vainqueur-statut', 'pilotage-vainqueur-boucles-terminees', 'pilotage-vainqueur-dossard'].flatMap((id) =>
            rect(id).map((r) => ({ id, left: r.left, right: r.right })),
          ),
        };
      });
      expect(mesures.debordeX, 'défilement horizontal').toBe(false);
      for (const e of mesures.elements) {
        expect(e.left, `${e.id} déborde à gauche`).toBeGreaterThanOrEqual(mesures.gauche - 1);
        expect(e.right, `${e.id} déborde à droite`).toBeLessThanOrEqual(mesures.droite + 1);
      }
      await expect(lire(page, 'pilotage-vainqueur-pseudo')).toHaveText(PSEUDO_LONG);
      await expect(lire(page, 'pilotage-vainqueur-statut')).toBeVisible();
      await expect(lire(page, 'pilotage-vainqueur-statut')).toHaveText('Vainqueur');
      await expect(lire(page, 'pilotage-vainqueur-boucles-terminees')).toBeVisible();
      // texte à 200 % à 320 px : mode empilé (cellules l'une sous l'autre, libellé de colonne devant chaque valeur)
      if (largeur === 320 && agrandi) {
        const hauts = await page.evaluate(() => ['pilotage-vainqueur-dossard', 'pilotage-vainqueur-pseudo', 'pilotage-vainqueur-boucles-terminees'].map((id) => document.querySelector(`[data-testid="${id}"]`)!.getBoundingClientRect().top));
        expect(hauts[0]).toBeLessThan(hauts[1]);
        expect(hauts[1]).toBeLessThan(hauts[2]);
      }
    });
  }

  test('CA16 - contraste AA, aucune couleur en dur, aucun aria-live ni role="status" ajouté', async ({ page }) => {
    await ouvrirAvecVainqueur(page, 375, false);
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
      const ids = ['pilotage-vainqueurs-titre', 'pilotage-vainqueur-dossard', 'pilotage-vainqueur-pseudo', 'pilotage-vainqueur-statut', 'pilotage-vainqueur-boucles-terminees'];
      return ids.flatMap((id) =>
        Array.from(document.querySelectorAll(`[data-testid="${id}"]`)).map((e) => {
          const l1 = lum(parse(getComputedStyle(e).color));
          const l2 = lum(fond(e));
          return { id: `${id}:${e.textContent?.trim()}`, ratio: (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05) };
        }),
      );
    });
    expect(ratios.length).toBe(5);
    for (const r of ratios) expect(r.ratio, `contraste de ${r.id}`).toBeGreaterThanOrEqual(4.5);

    // aucune couleur en dur dans les règles de la pastille Vainqueur ni de la section (feuilles de style de l'application)
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
          const visees = (x: CSSRule): void => {
            if (x instanceof CSSStyleRule && /pastille-statut--vainqueur|\.vainqueurs\b/.test(x.selectorText)) trouvees.push(x.cssText);
            if ('cssRules' in x) for (const y of Array.from((x as CSSGroupingRule).cssRules)) visees(y);
          };
          visees(r);
        }
      }
      return trouvees;
    });
    expect(regles.length).toBeGreaterThan(0);
    for (const t of regles) expect(t, 'couleur en dur').not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/);

    const section = lire(page, 'pilotage-vainqueurs');
    await expect(section.locator('[aria-live], [role="status"], [role="alert"]')).toHaveCount(0);
    await expect(page.locator('main [aria-live]')).toHaveCount(0);
    await expect(page.locator('main [role="status"]')).toHaveCount(1);
    await expect(page.locator('main [role="status"]')).toHaveAttribute('data-testid', 'pilotage-annonce-boucle');
  });

  test('CA16 - prefers-reduced-motion : aucune animation ni transition sur la section Vainqueur', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await ouvrirAvecVainqueur(page, 375, false);
    const fautifs = await page.evaluate(() =>
      Array.from(document.querySelectorAll('[data-testid="pilotage-vainqueurs"], [data-testid="pilotage-vainqueurs"] *'))
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
