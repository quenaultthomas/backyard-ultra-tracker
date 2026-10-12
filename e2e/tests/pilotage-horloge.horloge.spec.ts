import { type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { creerCompteParApi, pseudoUnique } from './aide-connexion';
import {
  connecterAdminMaster,
  courseDeReference,
  creerCourseParApi,
  dateDansJours,
  demarrerCourseParApi,
  exigerIdentifiantsAdminMaster,
  nomCourseUnique,
} from './aide-admin';
import { sinscrireParApi } from './aide-coureur';
import { avancerHorloge, avecApi, expect, lireHorloge, test } from './aide-horloge';

/*
 * Incrément 4.3 : CA12 à CA14, l'horloge applicative est avancée par l'API ; les écrans de pilotage
 * suivent par polling, sans rechargement. Courses datées du jour à Paris et démarrées AVANT toute avance.
 */
type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const lire = (page: Page, testid: string) => page.getByTestId(testid);
const SIX_SECONDES = { timeout: 6000 };

function secondes(texte: string): number {
  const m = /^(\d{2,}):(\d{2}):(\d{2})$/.exec(texte.trim());
  expect(m, `format HH:MM:SS attendu, obtenu « ${texte} »`).not.toBeNull();
  return Number(m![1]) * 3600 + Number(m![2]) * 60 + Number(m![3]);
}

async function dansIntervalle(page: Page, testid: string, min: string, max: string): Promise<void> {
  await expect
    .poll(async () => secondes(await lire(page, testid).innerText()), SIX_SECONDES)
    .toBeGreaterThanOrEqual(secondes(min));
  expect(secondes(await lire(page, testid).innerText())).toBeLessThanOrEqual(secondes(max));
}

/** Course datée du jour à Paris, avec 2 inscrits, démarrée par l'API. */
async function courseDemarree(playwright: PlaywrightLib, dureeBoucleMinutes: number, nombreMaxBoucles: number): Promise<{ id: string; nom: string }> {
  const nom = nomCourseUnique();
  const course = await avecApi(playwright, (ctx) =>
    creerCourseParApi(ctx, courseDeReference({ nom, date: dateDansJours(0), dureeBoucleMinutes, nombreMaxBoucles })),
  );
  const id = course['id'] as string;
  for (const prefixe of ['bruno', 'chloe']) {
    const pseudo = pseudoUnique(prefixe);
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
    await avecApi(playwright, (ctx) => sinscrireParApi(ctx, pseudo, id));
  }
  await avecApi(playwright, (ctx) => demarrerCourseParApi(ctx, id));
  return { id, nom };
}

const urlPilotage = (id: string) => `/administration/courses/${id}/pilotage`;

test.describe('Pilotage avec horloge pilotable (4.3)', () => {
  test('CA12 - avancer l\'horloge de 60 min fait passer le pilotage à la Boucle 2 puis 3, sans rechargement', async ({ page, playwright }) => {
    const course = await courseDemarree(playwright, 60, 24);
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await page.goto(urlPilotage(course.id));
    await page.evaluate(() => ((window as unknown as Record<string, unknown>)['marqueurRechargement'] = 1));

    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 1 sur 24');
    await dansIntervalle(page, 'pilotage-compte-a-rebours', '00:59:00', '01:00:00');

    await avancerHorloge(playwright, 'PT60M');
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 2 sur 24', SIX_SECONDES);
    await expect(lire(page, 'pilotage-annonce-boucle')).toContainText('Boucle 2 en cours', SIX_SECONDES);
    await dansIntervalle(page, 'pilotage-compte-a-rebours', '00:58:00', '01:00:00');
    await dansIntervalle(page, 'pilotage-duree-ecoulee', '01:00:00', '01:02:00');

    await avancerHorloge(playwright, 'PT60M');
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 3 sur 24', SIX_SECONDES);
    expect((await lireHorloge(playwright)).decalage).toBe('PT2H');
    expect(await page.evaluate(() => (window as unknown as Record<string, unknown>)['marqueurRechargement'])).toBe(1);
  });

  test('CA12 - l\'horloge est remise à PT0S au test suivant', async ({ playwright }) => {
    const etat = await lireHorloge(playwright);
    expect(etat.decalage).toBe('PT0S');
    expect(etat.decalageMillisecondes).toBe(0);
  });

  test('CA13 - sur la dernière boucle autorisée puis après son temps, le pilotage l\'annonce', async ({ page, playwright }) => {
    const course = await courseDemarree(playwright, 60, 2);
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    await page.goto(urlPilotage(course.id));
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 1 sur 2');

    await avancerHorloge(playwright, 'PT60M');
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 2 sur 2', SIX_SECONDES);
    await expect(lire(page, 'pilotage-libelle-compte-a-rebours')).toHaveText('Fin de la dernière boucle dans', SIX_SECONDES);
    await expect(lire(page, 'pilotage-message-derniere-boucle')).toHaveText('Dernière boucle autorisée.', SIX_SECONDES);

    await avancerHorloge(playwright, 'PT60M');
    await expect(lire(page, 'pilotage-message-boucle-max-terminee')).toHaveText(
      'Le temps de la dernière boucle est écoulé. La fin de course sera constatée automatiquement.',
      SIX_SECONDES,
    );
    await expect(lire(page, 'pilotage-compte-a-rebours')).toHaveText('00:00:00');
    expect(secondes(await lire(page, 'pilotage-duree-ecoulee').innerText())).toBeGreaterThanOrEqual(2 * 3600);
    await expect(lire(page, 'pilotage-statut')).toHaveText('En cours');
  });

  test('CA14 - deux courses de 60 et 90 min dans deux onglets : le même décalage, des boucles différentes', async ({ context, page, playwright }) => {
    const a = await courseDemarree(playwright, 60, 24);
    const b = await courseDemarree(playwright, 90, 24);
    await connecterAdminMaster(page);
    await expect(page).toHaveURL(/\/administration$/);
    const ongletB = await context.newPage();
    await page.goto(urlPilotage(a.id));
    await ongletB.goto(urlPilotage(b.id));
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 1 sur 24');
    await expect(lire(ongletB, 'pilotage-boucle-courante')).toHaveText('Boucle 1 sur 24');

    await avancerHorloge(playwright, 'PT60M');
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 2 sur 24', SIX_SECONDES);
    await expect(lire(ongletB, 'pilotage-boucle-courante')).toHaveText('Boucle 1 sur 24');

    await avancerHorloge(playwright, 'PT30M');
    await expect(lire(ongletB, 'pilotage-boucle-courante')).toHaveText('Boucle 2 sur 24', SIX_SECONDES);
    await expect(lire(page, 'pilotage-boucle-courante')).toHaveText('Boucle 2 sur 24');
  });
});
