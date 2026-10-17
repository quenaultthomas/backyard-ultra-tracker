import { expect } from '@playwright/test';
import { creerAdminParApi, exigerIdentifiantsAdminMaster, pseudoAdminUnique } from './aide-admin';
import { connecterAdmin, lire, urlPilotage } from './aide-abandon';
import { avancerHorloge, avecApi, test } from './aide-horloge';
import { boutonReintegrer } from './aide-reintegration';
import { MESSAGE_ATTENTE, attendreFileVide } from './aide-file-scan';
import { lireInscriptionEnBase, lirePassagesDetailEnBase, lirePassagesEnBase, ouvrirEcranDeScan, preparerCourseDeScan, scannerParEntree, verifierSucces } from './aide-scan';

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test.describe('Scans à vérifier et Boucles (horloge applicative pilotée)', () => {
  test('CA3 - un scan fait dans les temps mais reçu après l\'abandon automatique : ligne « Coureur en abandon » avec l\'aide ; la réintégration par Nadia depuis le pilotage laisse la ligne jusqu\'à « Compris »', async ({ browser, page, playwright }) => {
    // Trois coureurs (et non un seul comme dans la spec) : avec un seul coureur sans passage sur la Boucle 1 et aucun autre
    // passant, la règle des vainqueurs partagés (4.10) le rendrait VAINQUEUR au lieu de ABANDON.
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 3, nombreMaxBoucles: 5, dureeBoucleMinutes: 1 });
    const [hors, enLigne, autreEnLigne] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    await scannerParEntree(page, enLigne.jeton);
    await verifierSucces(page, enLigne.dossard, enLigne.pseudo, 1);
    await scannerParEntree(page, autreEnLigne.jeton);
    await verifierSucces(page, autreEnLigne.dossard, autreEnLigne.pseudo, 1);

    await page.context().setOffline(true);
    await expect(page.getByTestId('scan-hors-ligne')).toBeVisible();
    await scannerParEntree(page, hors.jeton);
    await expect(page.getByTestId('scan-resultat-attente')).toHaveText(MESSAGE_ATTENTE);
    await expect(page.getByTestId('scan-compteur-attente')).toHaveText('1 en attente');

    await avancerHorloge(playwright, 'PT90S');
    await expect.poll(() => lireInscriptionEnBase(course.id, hors.dossard), { timeout: 8000 }).toEqual({
      statut: 'ABANDON',
      motifAbandon: 'HORS_DELAI',
      boucleAbandon: '1',
    });

    await page.context().setOffline(false);
    await expect(page.getByTestId('scan-a-verifier-motif')).toHaveText('Coureur en abandon');
    await expect(page.getByTestId('scan-a-verifier-dossard')).toHaveText(`Dossard ${hors.dossard}`);
    await expect(page.getByTestId('scan-a-verifier-aide')).toHaveText(
      'Si ce scan a été fait dans les temps, demandez à un administrateur de réintégrer le coureur.',
    );
    await expect(page.getByTestId('scan-refus')).toHaveCount(0);
    await attendreFileVide(page);
    expect(lirePassagesEnBase(course.id)).toEqual(['2:1:SCAN', '3:1:SCAN']);

    const nadia = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
    const contexteAdmin = await browser.newContext();
    try {
      const pilotage = await contexteAdmin.newPage();
      await connecterAdmin(pilotage, nadia);
      await pilotage.goto(urlPilotage(course.id));
      await expect(lire(pilotage, 'pilotage-inscrit').first()).toBeVisible();
      await boutonReintegrer(pilotage, hors.dossard, hors.pseudo).click();
      const reponse = pilotage.waitForResponse((r) => r.request().method() === 'POST' && /\/reintegration$/.test(new URL(r.url()).pathname));
      await lire(pilotage, 'pilotage-reintegrer-confirmer').click();
      expect((await reponse).status()).toBe(200);
    } finally {
      await contexteAdmin.close();
    }
    expect(lireInscriptionEnBase(course.id, hors.dossard).statut).toBe('EN_COURSE');
    expect(lirePassagesDetailEnBase(course.id).filter((l) => l.startsWith(`${hors.dossard}:`))).toEqual([`${hors.dossard}:1:CORRECTION:sansDate`]);

    // La ligne reste dans la liste, rechargement compris, jusqu'à « Compris ».
    await page.reload();
    await expect(page.getByTestId('scan-a-verifier-motif')).toHaveText('Coureur en abandon');
    await page.getByTestId('scan-a-verifier-compris').click();
    await expect(page.getByTestId('scan-a-verifier')).toHaveCount(0);
  });
});
