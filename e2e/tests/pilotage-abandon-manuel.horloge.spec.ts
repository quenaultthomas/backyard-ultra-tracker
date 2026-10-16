import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { abandonnerParEcran, connecterPatron, lire, ouvrirPilotage } from './aide-abandon';
import { avancerHorloge, expect, test } from './aide-horloge';
import {
  lireInscriptionEnBase,
  lireStatutCourseEnBase,
  ouvrirEcranDeScan,
  preparerCourseDeScan,
  scannerParEntree,
  verifierSucces,
  type Coureur,
} from './aide-scan';

/*
 * Incrément 4.11 CA16 : un coureur abandonné manuellement sort des contrôles automatiques (RG3, RG6). Horloge pilotée ;
 * le planificateur serveur (tick d'1 s) décide, le pilotage rafraîchit toutes les 3 s : 8 s de marge.
 */
test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const HUIT_SECONDES = { timeout: 8000 };

test('CA16 - Bruno abandonné « Manuel » après avoir bouclé la Boucle 1 : Boucle d\'abandon 2, jamais examiné ensuite ; Chloé vainqueur unique, Tom « Hors délai »', async ({ browser, page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 60 });
  const [bruno, chloe, tom] = course.coureurs;
  const contexteBenevole = await browser.newContext();
  try {
    const scan = await contexteBenevole.newPage();
    const scanner = async (c: Coureur, boucle: number) => {
      await scannerParEntree(scan, c.jeton);
      await verifierSucces(scan, c.dossard, c.pseudo, boucle);
    };
    await ouvrirEcranDeScan(scan, course);

    await connecterPatron(page);
    await ouvrirPilotage(page, course.id, 3);

    await avancerHorloge(playwright, 'PT10M');
    await scanner(bruno, 1);
    await scanner(chloe, 1);
    await scanner(tom, 1);

    await avancerHorloge(playwright, 'PT50M');
    await expect(lire(page, 'pilotage-boucle-courante')).toContainText('Boucle 2', HUIT_SECONDES);
    await expect(lire(page, 'pilotage-inscrit')).toHaveCount(3);

    await abandonnerParEcran(page, bruno.dossard, bruno.pseudo, 'MANUEL');
    await expect(lire(page, 'pilotage-abandon')).toHaveCount(1);
    await expect(lire(page, 'pilotage-abandon-boucle')).toHaveText('Boucle 2');
    await expect(lire(page, 'pilotage-abandon-motif')).toHaveText('Manuel');
    await expect(lire(page, 'pilotage-abandon-boucles-terminees')).toHaveText('1');
    expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual({ statut: 'ABANDON', motifAbandon: 'MANUEL', boucleAbandon: '2' });

    await avancerHorloge(playwright, 'PT10M');
    await scanner(chloe, 2);

    await avancerHorloge(playwright, 'PT50M');

    await expect(lire(page, 'pilotage-statut')).toHaveText('Terminée', HUIT_SECONDES);
    await expect(lire(page, 'pilotage-vainqueurs-titre')).toHaveText('Vainqueur');
    await expect(lire(page, 'pilotage-vainqueur')).toHaveCount(1);
    await expect(lire(page, 'pilotage-vainqueur-pseudo')).toHaveText(chloe.pseudo);
    await expect(lire(page, 'pilotage-aucun-vainqueur')).toHaveCount(0);

    await expect(lire(page, 'pilotage-abandon')).toHaveCount(2);
    const ligneBruno = lire(page, 'pilotage-abandon').filter({ hasText: bruno.pseudo });
    const ligneTom = lire(page, 'pilotage-abandon').filter({ hasText: tom.pseudo });
    await expect(ligneBruno.getByTestId('pilotage-abandon-boucle')).toHaveText('Boucle 2');
    await expect(ligneBruno.getByTestId('pilotage-abandon-motif')).toHaveText('Manuel');
    await expect(ligneTom.getByTestId('pilotage-abandon-boucle')).toHaveText('Boucle 2');
    await expect(ligneTom.getByTestId('pilotage-abandon-motif')).toHaveText('Hors délai');

    expect(lireInscriptionEnBase(course.id, bruno.dossard)).toEqual({ statut: 'ABANDON', motifAbandon: 'MANUEL', boucleAbandon: '2' });
    expect(lireInscriptionEnBase(course.id, tom.dossard)).toEqual({ statut: 'ABANDON', motifAbandon: 'HORS_DELAI', boucleAbandon: '2' });
    expect(lireInscriptionEnBase(course.id, chloe.dossard).statut).toBe('VAINQUEUR');
    expect(lireStatutCourseEnBase(course.id)).toBe('TERMINEE');
  } finally {
    await contexteBenevole.close();
  }
});
