import { expect, test } from '@playwright/test';
import { MOT_DE_PASSE_BENEVOLE_CREE, exigerIdentifiantsAdminMaster } from './aide-admin';
import { placerStatutCourseEnBase } from './aide-coureur';
import { creerBenevole, creerCourseAffectee, preparerCourseDeScan, seConnecterVers, urlScan } from './aide-scan';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test.describe('Accueil bénévole, action « Scanner »', () => {
  test('CA15 - seule la carte d\'une course en cours porte le lien « Scanner », qui mène à l\'écran de scan', async ({ page, playwright }) => {
    const enCours = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const enPreparation = await creerCourseAffectee(playwright, enCours.benevole);
    const terminee = await creerCourseAffectee(playwright, enCours.benevole);
    placerStatutCourseEnBase(terminee.id, 'TERMINEE');

    await seConnecterVers(page, enCours.benevole, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(page).toHaveURL(/\/benevole$/);
    await expect(page.getByTestId('benevole-ligne-course')).toHaveCount(3);

    const ligne = (nom: string) => page.getByTestId('benevole-ligne-course').filter({ hasText: nom });
    await expect(ligne(enCours.nom).getByTestId('benevole-lien-scanner')).toHaveCount(1);
    await expect(ligne(enCours.nom).getByTestId('benevole-lien-scanner')).toHaveText('Scanner');
    await expect(ligne(enPreparation.nom).getByTestId('benevole-lien-scanner')).toHaveCount(0);
    await expect(ligne(terminee.nom).getByTestId('benevole-lien-scanner')).toHaveCount(0);
    await expect(page.getByTestId('benevole-lien-scanner')).toHaveCount(1);

    const lien = ligne(enCours.nom).getByTestId('benevole-lien-scanner');
    const boite = await lien.boundingBox();
    expect(boite!.height).toBeGreaterThanOrEqual(44);

    // Atteignable au clavier.
    let atteint = false;
    for (let i = 0; i < 40 && !atteint; i++) {
      await page.keyboard.press('Tab');
      atteint = await lien.evaluate((el) => el === document.activeElement);
    }
    expect(atteint, 'le lien « Scanner » doit recevoir le focus au clavier').toBe(true);
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(new RegExp(`${urlScan(enCours.id)}$`));
    await expect(page.getByTestId('scan-course-nom')).toHaveText(enCours.nom);
  });

  test('CA15 - un bénévole sans course voit « Aucune course à scanner pour le moment. »', async ({ page, playwright }) => {
    const benevole = await creerBenevole(playwright);
    await seConnecterVers(page, benevole, MOT_DE_PASSE_BENEVOLE_CREE);
    await expect(page).toHaveURL(/\/benevole$/);
    await expect(page.getByTestId('accueil-benevole-vide')).toHaveText('Aucune course à scanner pour le moment.');
    await expect(page.getByTestId('benevole-lien-scanner')).toHaveCount(0);
  });
});
