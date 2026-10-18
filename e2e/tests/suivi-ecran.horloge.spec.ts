import { type Page } from '@playwright/test';
import { expect, test as testHorloge, avancerHorloge } from './aide-horloge';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { lireStatutCourseEnBase, ouvrirEcranDeScan, scannerParEntree, verifierSucces, type CourseDeScan } from './aide-scan';
import { compterRequetesSuivi, courseDemarreeAvecLogo, lire, lireSecondes, poserMarqueur, urlSuivi, verifierSansRechargement } from './aide-suivi';

/*
 * Incrément 6.1 : suivi public avec horloge applicative pilotée (CA13, CA16, CA22).
 * Le planificateur serveur (tick d'1 s) décide ; l'écran rafraîchit toutes les 3 s.
 */

testHorloge.describe.configure({ mode: 'serial' });

testHorloge.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

/** Scanne tous les coureurs de la Course pour la Boucle donnée, depuis un contexte de bénévole isolé. */
async function scannerTous(page: Page, course: CourseDeScan, boucle: number): Promise<void> {
  const contexte = await page.context().browser()!.newContext();
  try {
    const p = await contexte.newPage();
    await ouvrirEcranDeScan(p, course);
    for (const c of course.coureurs) {
      await scannerParEntree(p, c.jeton);
      await verifierSucces(p, c.dossard, c.pseudo, boucle);
    }
  } finally {
    await contexte.close();
  }
}

const QUATRE_SECONDES = { timeout: 4000 };
const CINQ_SECONDES = { timeout: 5000 };

testHorloge('CA13 - l\'avance de l\'horloge fait passer le suivi à la Boucle 2 puis à la dernière Boucle, sans rechargement', async ({ page, playwright }) => {
  const course = await courseDemarreeAvecLogo(playwright, { nombreMaxBoucles: 5 });
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5');
  await poserMarqueur(page);
  await expect(lire(page, 'suivi-annonce-boucle')).toHaveText('');

  await avancerHorloge(playwright, 'PT59M');
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5', QUATRE_SECONDES);
  await expect.poll(() => lireSecondes(page, 'suivi-compte-a-rebours'), QUATRE_SECONDES).toBeLessThanOrEqual(60);
  await expect(lire(page, 'suivi-compte-a-rebours')).toHaveText(/^\s*00:0[01]:\d{2}\s*$/);
  await expect(lire(page, 'suivi-duree-ecoulee')).toHaveText(/^\s*00:5[89]:\d{2}\s*$/);
  await verifierSansRechargement(page);
});

testHorloge('CA13 - passage en Boucle 2 : annonce, durée proche de 01:00:00 ; puis Boucle 5 : dernière Boucle autorisée', async ({ page, playwright }) => {
  const course = await courseDemarreeAvecLogo(playwright, { nombreMaxBoucles: 5 });
  // Des scans à chaque Boucle sont nécessaires pour que la Course vive (4.10).
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5');
  await poserMarqueur(page);

  const scanner = (boucle: number) => scannerTous(page, course, boucle);
  // Scan de la Boucle 1 dès le départ : un scan à 59 min puis un à 60 min serait refusé comme trop rapproché.
  await scanner(1);

  await avancerHorloge(playwright, 'PT59M');
  await expect.poll(() => lireSecondes(page, 'suivi-compte-a-rebours'), QUATRE_SECONDES).toBeLessThanOrEqual(60);
  await avancerHorloge(playwright, 'PT1M');
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 2 sur 5', QUATRE_SECONDES);
  await expect(lire(page, 'suivi-annonce-boucle')).toContainText('Boucle 2 en cours', QUATRE_SECONDES);
  await expect.poll(() => lireSecondes(page, 'suivi-duree-ecoulee'), QUATRE_SECONDES).toBeGreaterThanOrEqual(3600);
  expect(await lireSecondes(page, 'suivi-duree-ecoulee')).toBeLessThanOrEqual(3600 + 120);
  await verifierSansRechargement(page);

  for (const boucle of [2, 3, 4]) {
    await scanner(boucle);
    await avancerHorloge(playwright, 'PT1H');
    await expect(lire(page, 'suivi-boucle')).toHaveText(`Boucle ${boucle + 1} sur 5`, QUATRE_SECONDES);
  }
  await expect(lire(page, 'suivi-message-derniere-boucle')).toHaveText('Dernière boucle autorisée.');
  await expect(lire(page, 'suivi-libelle-compte-a-rebours')).toHaveText('Fin de la dernière boucle dans');
  await verifierSansRechargement(page);
});

testHorloge('CA16 - fin de course constatée : message de fin, statut Terminée, plus de Boucle, polling arrêté', async ({ page, playwright }) => {
  const course = await courseDemarreeAvecLogo(playwright);
  const requetes = compterRequetesSuivi(page);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5');
  await poserMarqueur(page);

  await avancerHorloge(playwright, 'PT61M');
  await expect(lire(page, 'suivi-message-terminee')).toHaveText('La course est terminée.', CINQ_SECONDES);
  await expect(lire(page, 'suivi-statut')).toHaveText('Terminée');
  await expect(lire(page, 'suivi-boucle')).toHaveCount(0);
  await expect(lire(page, 'suivi-compte-a-rebours')).toHaveCount(0);
  await expect(lire(page, 'suivi-duree-ecoulee')).toHaveCount(0);
  expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
  await verifierSansRechargement(page);

  const avant = requetes.nombre();
  await expect
    .poll(async () => page.evaluate(() => new Promise<boolean>((resolve) => setTimeout(() => resolve(true), 7000))), { timeout: 9000 })
    .toBe(true);
  expect(requetes.nombre()).toBe(avant);
});

testHorloge('CA22 - deux Courses dans deux onglets évoluent indépendamment', async ({ page, playwright }) => {
  const longue = await courseDemarreeAvecLogo(playwright, { dureeBoucleMinutes: 60 });
  const courte = await courseDemarreeAvecLogo(playwright, { dureeBoucleMinutes: 1 });
  const ongletCourte = await page.context().newPage();
  await page.goto(urlSuivi(longue.id));
  await ongletCourte.goto(urlSuivi(courte.id));
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5');
  await expect(lire(ongletCourte, 'suivi-boucle')).toHaveText('Boucle 1 sur 5');
  await expect(lire(page, 'suivi-nom')).toHaveText(longue.nom);
  await expect(lire(ongletCourte, 'suivi-nom')).toHaveText(courte.nom);

  // X est scannée à la Boucle 1 : elle passe en Boucle 2. Y (1 min) n'est pas scannée : sa fin de course est constatée.
  await scannerTous(page, longue, 1);
  await avancerHorloge(playwright, 'PT61M');
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 2 sur 5', { timeout: 6000 });
  await expect(lire(page, 'suivi-statut')).toHaveText('En cours');
  await expect(lire(ongletCourte, 'suivi-message-terminee')).toHaveText('La course est terminée.', { timeout: 6000 });
  await expect(lire(ongletCourte, 'suivi-statut')).toHaveText('Terminée');
  await expect(lire(ongletCourte, 'suivi-boucle')).toHaveCount(0);
  await expect(lire(page, 'suivi-message-terminee')).toHaveCount(0);
  await expect(lire(page, 'suivi-nom')).toHaveText(longue.nom);
  await expect(lire(ongletCourte, 'suivi-nom')).toHaveText(courte.nom);
});
