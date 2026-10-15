import { expect, test, type Page } from '@playwright/test';
import { connecterAdminMaster, exigerIdentifiantsAdminMaster } from './aide-admin';
import { lirePassagesEnBase, ouvrirEcranDeScan, preparerCourseDeScan, scannerParEntree, verifierSucces } from './aide-scan';

/*
 * Incrément 4.7 : passages sur l'écran de pilotage (CA7, CA9, CA10, CA11 ; CA8 : pilotage-passages.horloge.spec.ts).
 * CA7 et CA11 : vraies Courses de 60 min (aucune bascule). CA9 et CA10 : réponses de GET .../pilotage fabriquées.
 */
test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const urlPilotage = (id: string) => `/administration/courses/${id}/pilotage`;
const lire = (page: Page, testid: string) => page.getByTestId(testid);
const CINQ_SECONDES = { timeout: 5000 };

async function connecterMaster(page: Page): Promise<void> {
  await connecterAdminMaster(page);
  await expect(page).toHaveURL(/\/administration$/);
}

// ---------- instantanés fabriqués ----------

const T0 = Date.parse('2026-10-09T14:03:27Z');
const heure = (decalageMs: number) => new Date(T0 + decalageMs).toISOString();

interface Ligne {
  dossard: number;
  pseudo: string;
  terminees: number;
  boucle: boolean | null;
}

const ligne = (l: Ligne) => ({
  inscriptionId: `00000000-0000-0000-0000-0000000000a${l.dossard}`,
  dossard: l.dossard,
  pseudo: l.pseudo,
  statut: 'EN_COURSE',
  nombreBouclesTerminees: l.terminees,
  boucleCouranteTerminee: l.boucle,
});

function instantane(lignes: Ligne[], surcharge: Record<string, unknown> = {}): Record<string, unknown> {
  const boucleCourante = (surcharge['boucleCourante'] as number | null | undefined) === null ? null : ((surcharge['boucleCourante'] as number | undefined) ?? 1);
  const ayant = lignes.filter((l) => l.boucle === true).length;
  return {
    courseId: '00000000-0000-0000-0000-000000000001',
    nom: 'Backyard simulé',
    logoUrl: null,
    statut: 'EN_COURS',
    heureServeur: heure(30000),
    demarreeLe: '2026-10-09T14:03:27Z',
    dureeBoucleMinutes: 60,
    nombreMaxBoucles: 5,
    boucleCourante,
    derniereBoucle: false,
    boucleMaxTerminee: false,
    debutBoucleCourante: '2026-10-09T14:03:27Z',
    finBoucleCourante: '2026-10-09T15:03:27Z',
    tempsAvantFinBoucleMillisecondes: 3570000,
    dureeEcouleeMillisecondes: 30000,
    nombreInscrits: lignes.length,
    nombreAyantBoucle: boucleCourante === null ? null : ayant,
    nombreEnBoucle: boucleCourante === null ? null : lignes.length - ayant,
    inscritsEnCourse: lignes.map(ligne),
    nombreAbandons: 0,
    inscritsEnAbandon: [],
    nombreVainqueurs: 0,
    inscritsVainqueurs: [],
    ...surcharge,
  };
}

/** Intercepte GET .../pilotage ; `repondre(n)` fournit le corps du n-ième appel. Renvoie le compteur d'appels. */
async function simuler(page: Page, repondre: (n: number) => Record<string, unknown>): Promise<{ demandes: number }> {
  const etat = { demandes: 0 };
  await page.route('**/api/administration/courses/*/pilotage', async (route) => {
    const n = ++etat.demandes;
    await route.fulfill({ status: 200, contentType: 'application/json', headers: { 'cache-control': 'no-store' }, body: JSON.stringify(repondre(n)) });
  });
  return etat;
}

const ID_SIMULE = '00000000-0000-0000-0000-000000000001';

test.describe('Passages au pilotage', () => {
  test('CA7 - les scans du bénévole font passer les coureurs à « A bouclé » sur le pilotage, sans recharger', async ({ browser, page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
    const [bruno, chloe, tom] = course.coureurs;
    await connecterMaster(page);
    await page.goto(urlPilotage(course.id));
    await page.evaluate(() => ((window as unknown as Record<string, unknown>)['marqueurRechargement'] = 1));

    await expect(lire(page, 'pilotage-ayant-boucle')).toHaveText('A bouclé : 0');
    await expect(lire(page, 'pilotage-en-boucle')).toHaveText('En boucle : 3');
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(3);
    await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText(['En boucle', 'En boucle', 'En boucle']);
    await expect(lire(page, 'pilotage-inscrit-boucles-terminees')).toHaveText(['0', '0', '0']);
    await expect(lire(page, 'pilotage-colonne-boucle')).toHaveText('Boucle 1');

    const contexteBenevole = await browser.newContext();
    try {
      const pageBenevole = await contexteBenevole.newPage();
      await ouvrirEcranDeScan(pageBenevole, course);
      await scannerParEntree(pageBenevole, bruno.jeton);
      await verifierSucces(pageBenevole, bruno.dossard, bruno.pseudo, 1);
      await scannerParEntree(pageBenevole, chloe.jeton);
      await verifierSucces(pageBenevole, chloe.dossard, chloe.pseudo, 1);
    } finally {
      await contexteBenevole.close();
    }

    await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText(['A bouclé', 'A bouclé', 'En boucle'], CINQ_SECONDES);
    await expect(lire(page, 'pilotage-inscrit-boucles-terminees')).toHaveText(['1', '1', '0']);
    await expect(lire(page, 'pilotage-inscrit-pseudo')).toHaveText([bruno.pseudo, chloe.pseudo, tom.pseudo]);
    await expect(lire(page, 'pilotage-ayant-boucle')).toHaveText('A bouclé : 2');
    await expect(lire(page, 'pilotage-en-boucle')).toHaveText('En boucle : 1');
    await expect(lire(page, 'pilotage-colonne-boucle')).toHaveText('Boucle 1');
    await expect(lire(page, 'pilotage-compteur')).toHaveText('3 coureurs en course sur 3 inscrits');
    expect(await page.evaluate(() => (window as unknown as Record<string, unknown>)['marqueurRechargement'])).toBe(1);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
  });

  test('CA9 - Course terminée : ni résumé ni colonne « Boucle N », « Boucles terminées » conservée', async ({ page }) => {
    await connecterMaster(page);
    await simuler(page, () =>
      instantane([{ dossard: 1, pseudo: 'Bruno', terminees: 4, boucle: null }, { dossard: 2, pseudo: 'Chloé', terminees: 3, boucle: null }], {
        statut: 'TERMINEE',
        boucleCourante: null,
        demarreeLe: null,
        debutBoucleCourante: null,
        finBoucleCourante: null,
        tempsAvantFinBoucleMillisecondes: null,
        dureeEcouleeMillisecondes: null,
      }),
    );
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-message-terminee')).toHaveText('La course est terminée.');
    await expect(lire(page, 'pilotage-bouclage')).toHaveCount(0);
    await expect(lire(page, 'pilotage-colonne-boucle')).toHaveCount(0);
    await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveCount(0);
    await expect(lire(page, 'pilotage-colonne-boucles-terminees')).toHaveText('Boucles terminées');
    await expect(lire(page, 'pilotage-inscrit-boucles-terminees')).toHaveText(['4', '3']);
  });

  test('CA9 - Course en cours sans heure de départ : message dédié, pas de résumé ni de colonne « Boucle N »', async ({ page }) => {
    await connecterMaster(page);
    await simuler(page, () =>
      instantane([{ dossard: 1, pseudo: 'Bruno', terminees: 2, boucle: null }], {
        boucleCourante: null,
        demarreeLe: null,
        debutBoucleCourante: null,
        finBoucleCourante: null,
        tempsAvantFinBoucleMillisecondes: null,
        dureeEcouleeMillisecondes: null,
      }),
    );
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-message-sans-depart')).toContainText('Heure de départ inconnue');
    await expect(lire(page, 'pilotage-bouclage')).toHaveCount(0);
    await expect(lire(page, 'pilotage-colonne-boucle')).toHaveCount(0);
    await expect(lire(page, 'pilotage-colonne-boucles-terminees')).toBeVisible();
    await expect(lire(page, 'pilotage-inscrit-boucles-terminees')).toHaveText(['2']);
  });

  test('CA9 - aucun inscrit : « Aucun coureur en course. » et pas de résumé', async ({ page }) => {
    await connecterMaster(page);
    await simuler(page, () => instantane([], { nombreInscrits: 2 }));
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-inscrits-vide')).toHaveText('Aucun coureur en course.');
    await expect(lire(page, 'pilotage-bouclage')).toHaveCount(0);
  });

  test('CA9 - les Boucles terminées 0 et 27 s\'affichent telles quelles', async ({ page }) => {
    await connecterMaster(page);
    await simuler(page, () =>
      instantane([{ dossard: 1, pseudo: 'Bruno', terminees: 0, boucle: false }, { dossard: 2, pseudo: 'Chloé', terminees: 27, boucle: true }]),
    );
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-inscrit-boucles-terminees')).toHaveText(['0', '27']);
    await expect(lire(page, 'pilotage-ayant-boucle')).toHaveText('A bouclé : 1');
    await expect(lire(page, 'pilotage-en-boucle')).toHaveText('En boucle : 1');
  });

  test('CA9 - mise à jour en place au rythme de 3 s, focus conservé, en-tête qui suit la Boucle courante', async ({ page }) => {
    await connecterMaster(page);
    const bruno = (boucle: boolean, terminees: number): Ligne => ({ dossard: 1, pseudo: 'Bruno', terminees, boucle });
    const etat = await simuler(page, (n) => {
      if (n === 1) return instantane([bruno(false, 2)], { boucleCourante: 3 });
      if (n === 2) return instantane([bruno(true, 3)], { boucleCourante: 3 });
      return instantane([bruno(false, 3)], { boucleCourante: 4 });
    });
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-colonne-boucle')).toHaveText('Boucle 3');
    await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText('En boucle');

    const retour = lire(page, 'pilotage-retour');
    await retour.focus();
    const ligneInitiale = await lire(page, 'pilotage-inscrit').elementHandle();
    const demandesAvant = etat.demandes;

    await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText('A bouclé', { timeout: 6000 });
    await expect(lire(page, 'pilotage-inscrit-boucles-terminees')).toHaveText('3');
    expect(await ligneInitiale!.evaluate((e) => e.isConnected)).toBe(true);
    await expect(retour).toBeFocused();
    expect(etat.demandes).toBeLessThanOrEqual(demandesAvant + 2);

    await expect(lire(page, 'pilotage-colonne-boucle')).toHaveText('Boucle 4', { timeout: 6000 });
    await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText('En boucle');
    expect(await ligneInitiale!.evaluate((e) => e.isConnected)).toBe(true);
    await expect(retour).toBeFocused();
  });

  async function verifierResponsive(page: Page, largeur: number, agrandi: boolean): Promise<void> {
    await page.setViewportSize({ width: largeur, height: 700 });
    await connecterMaster(page);
    const pseudoLong = 'Abcdefghij'.repeat(3);
    const lignes: Ligne[] = [
      { dossard: 1, pseudo: pseudoLong, terminees: 12, boucle: true },
      { dossard: 2, pseudo: 'Chloé', terminees: 3, boucle: false },
      { dossard: 3, pseudo: 'Tom', terminees: 0, boucle: false },
    ];
    await simuler(page, () => instantane(lignes));
    await page.goto(urlPilotage(ID_SIMULE));
    if (agrandi) await page.addStyleTag({ content: 'html{font-size:32px}' });
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(3);

    const mesures = await page.evaluate(() => {
      const el = document.documentElement;
      const carte = document.querySelector('main section')!.getBoundingClientRect();
      const t = document.querySelector('[data-testid="pilotage-inscrits"]')!.getBoundingClientRect();
      return { debordeX: el.scrollWidth > el.clientWidth, carteGauche: carte.left, carteDroite: carte.right, tG: t.left, tD: t.right };
    });
    expect(mesures.debordeX, 'défilement horizontal').toBe(false);
    expect(mesures.tG).toBeGreaterThanOrEqual(mesures.carteGauche - 1);
    expect(mesures.tD).toBeLessThanOrEqual(mesures.carteDroite + 1);
    await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText(['A bouclé', 'En boucle', 'En boucle']);
  }

  for (const [largeur, agrandi] of [[375, false], [320, false], [320, true]] as const) {
    test(`CA10 - à ${largeur} px${agrandi ? ' avec un texte à 200 %' : ''} : rien ne déborde, pastilles lisibles par le texte`, async ({ page }) => {
      await verifierResponsive(page, largeur, agrandi);
    });
  }

  test('CA10 - contraste AA des pastilles, aucune couleur en dur, aucun aria-live ajouté, rafraîchissement sans perte de focus', async ({ page }) => {
    await connecterMaster(page);
    const etat = await simuler(page, () =>
      instantane([{ dossard: 1, pseudo: 'Bruno', terminees: 1, boucle: true }, { dossard: 2, pseudo: 'Chloé', terminees: 0, boucle: false }]),
    );
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-inscrit-boucle')).toHaveText(['A bouclé', 'En boucle']);

    const retour = lire(page, 'pilotage-retour');
    await retour.focus();
    const avant = etat.demandes;
    await expect.poll(() => etat.demandes, { timeout: 6000 }).toBeGreaterThan(avant);
    await expect(retour).toBeFocused();

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
      return Array.from(document.querySelectorAll('[data-testid="pilotage-inscrit-boucle"], [data-testid="pilotage-ayant-boucle"], [data-testid="pilotage-en-boucle"], [data-testid="pilotage-inscrit-boucles-terminees"], [data-testid="pilotage-colonne-boucle"]')).map((e) => {
        const l1 = lum(parse(getComputedStyle(e).color));
        const l2 = lum(fond(e));
        return { id: `${e.getAttribute('data-testid')}:${e.textContent?.trim()}`, ratio: (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05) };
      });
    });
    expect(ratios.length).toBeGreaterThanOrEqual(5);
    for (const r of ratios) expect(r.ratio, `contraste de ${r.id}`).toBeGreaterThanOrEqual(4.5);

    // aucune couleur en dur dans les feuilles de style appliquées aux pastilles (les règles passent par des variables)
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
          if (r instanceof CSSStyleRule && /pastille-statut--(a-boucle|en-boucle)/.test(r.selectorText)) trouvees.push(r.cssText);
        }
      }
      return trouvees;
    });
    expect(regles.length).toBeGreaterThan(0);
    for (const t of regles) expect(t, 'couleur en dur').not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/);

    // seul pilotage-annonce-boucle est une région vivante ; rien dans le résumé ni le tableau
    await expect(page.locator('[data-testid="pilotage-bouclage"] [aria-live], [data-testid="pilotage-bouclage"][aria-live], [data-testid="pilotage-inscrits"] [aria-live], [data-testid="pilotage-inscrits"] [role="status"], [data-testid="pilotage-bouclage"][role="status"]')).toHaveCount(0);
    await expect(page.locator('main [aria-live]:not([data-testid="pilotage-annonce-boucle"])')).toHaveCount(0);
  });

  test('CA10 - prefers-reduced-motion : aucune animation sur le résumé et le tableau', async ({ page }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await connecterMaster(page);
    await simuler(page, () => instantane([{ dossard: 1, pseudo: 'Bruno', terminees: 1, boucle: true }]));
    await page.goto(urlPilotage(ID_SIMULE));
    await expect(lire(page, 'pilotage-bouclage')).toBeVisible();
    const fautifs = await page.evaluate(() =>
      Array.from(document.querySelectorAll('[data-testid="pilotage-bouclage"], [data-testid="pilotage-bouclage"] *, [data-testid="pilotage-inscrits"], [data-testid="pilotage-inscrits"] *'))
        .map((e) => {
          const s = getComputedStyle(e);
          const dur = (v: string) => Math.max(0, ...v.split(',').map((x) => parseFloat(x) || 0));
          return (s.animationName !== 'none' && dur(s.animationDuration) > 0) || dur(s.transitionDuration) > 0 ? e.tagName.toLowerCase() : null;
        })
        .filter((x) => x !== null),
    );
    expect(fautifs).toEqual([]);
  });

  test('CA11 - la ligne de pilotage garde dossard, pseudo et pastille « En course » et les data-testid de 4.2', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    await connecterMaster(page);
    await page.goto(urlPilotage(course.id));
    await expect(lire(page, 'pilotage-titre')).toHaveText(course.nom);
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 1 sur 5');
    await expect(lire(page, 'pilotage-compteur')).toHaveText('2 coureurs en course sur 2 inscrits');
    await expect(lire(page, 'pilotage-inscrit-dossard')).toHaveText(['1', '2']);
    await expect(lire(page, 'pilotage-inscrit-pseudo')).toHaveText(course.coureurs.map((c) => c.pseudo));
    await expect(lire(page, 'pilotage-inscrit-statut')).toHaveText(['En course', 'En course']);
    for (const id of ['pilotage-logo', 'pilotage-statut', 'pilotage-compte-a-rebours', 'pilotage-duree-ecoulee', 'pilotage-depart', 'pilotage-annonce-boucle', 'pilotage-retour', 'pilotage-inscrits']) {
      await expect(lire(page, id)).toHaveCount(1);
    }
  });
});
