import { expect } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { creerAdminParApi, exigerIdentifiantsAdminMaster, MOT_DE_PASSE_ADMIN_CREE, pseudoAdminUnique } from './aide-admin';
import { avancerHorloge, avecApi, lireHorloge, test } from './aide-horloge';
import { reintegrerParApi } from './aide-reintegration';
import {
  MESSAGE_ATTENTE,
  attendreFileVide,
  laisserPasserLeReseau,
  lireDecalageScanApresDepart,
  suivrePassages,
} from './aide-file-scan';
import { champ, lireInscriptionEnBase, lirePassagesEnBase, ouvrirEcranDeScan, preparerCourseDeScan, scannerParEntree, verifierSucces } from './aide-scan';

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

test.describe('File de scan et Boucles (horloge applicative pilotée)', () => {
  test('CA9 - un scan fait dans les temps mais reçu après l\'abandon automatique est refusé ; la réintégration admin rétablit le coureur', async ({ page, playwright }) => {
    // Trois coureurs : avec un seul passant sur la Boucle 1 il serait VAINQUEUR et la Course se terminerait (4.9), et
    // sans passant tous seraient vainqueurs partagés. Deux coureurs bouclent en ligne, le troisième est scanné hors ligne.
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
    expect(lirePassagesEnBase(course.id)).toEqual(['2:1:SCAN', '3:1:SCAN']);

    await avancerHorloge(playwright, 'PT90S');
    await expect.poll(() => lireInscriptionEnBase(course.id, hors.dossard), { timeout: 8000 }).toEqual({
      statut: 'ABANDON',
      motifAbandon: 'HORS_DELAI',
      boucleAbandon: '1',
    });

    await page.context().setOffline(false);
    await expect(page.getByTestId('scan-refus-motif')).toHaveText('Coureur en abandon');
    await expect(page.getByTestId('scan-resultat-erreur')).toHaveText('Ce coureur n\'est plus en course.');
    await attendreFileVide(page);
    expect(lirePassagesEnBase(course.id)).toEqual(['2:1:SCAN', '3:1:SCAN']);
    expect(lireInscriptionEnBase(course.id, hors.dossard).statut).toBe('ABANDON');

    const admin = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, admin));
    const reponse = await avecApi(playwright, (ctx) =>
      reintegrerParApi(ctx, course.id, hors.inscriptionId, { pseudo: admin, motDePasse: MOT_DE_PASSE_ADMIN_CREE }),
    );
    expect(reponse.statut).toBe(200);
    expect(lireInscriptionEnBase(course.id, hors.dossard)).toEqual({ statut: 'EN_COURSE', motifAbandon: '', boucleAbandon: '' });
  });

  test('CA9 - un scan fait hors ligne puis reçu 15 s plus tard dans la même Boucle est accepté et replacé à son instant par l\'âge', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1, nombreMaxBoucles: 5, dureeBoucleMinutes: 1 });
    const [un] = course.coureurs;
    // Boucles de 1 min : le départ n'est pas reculé ; seule l'horloge de la page avance de 15 s avec celle du serveur.
    await page.clock.install();
    await ouvrirEcranDeScan(page, course);
    const requetes = suivrePassages(page);
    await page.context().setOffline(true);
    await expect(page.getByTestId('scan-hors-ligne')).toBeVisible();
    await scannerParEntree(page, un.jeton);
    await expect(page.getByTestId('scan-compteur-attente')).toHaveText('1 en attente');
    const serveurAuScan = new Date((await lireHorloge(playwright)).maintenant).getTime();
    const departAuScan = lireDepartEnSecondes(serveurAuScan, course.id);

    await avancerHorloge(playwright, 'PT15S');
    await page.clock.fastForward('00:15');
    await page.context().setOffline(false);
    await verifierSucces(page, un.dossard, un.pseudo, 1);
    expect(requetes).toHaveLength(1);
    expect(requetes[0].ageDuScanMillisecondes).toBeGreaterThanOrEqual(15_000);
    expect(requetes[0].ageDuScanMillisecondes).toBeLessThan(25_000);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
    // `scanneLe` (replacé par l'âge) correspond à l'instant du scan, pas à celui de la réception, à 1 s près.
    const decalage = lireDecalageScanApresDepart(course.id, un.dossard, 1);
    expect(Math.abs(decalage - departAuScan)).toBeLessThanOrEqual(1.5);
    await laisserPasserLeReseau(page);
    await expect(champ(page)).toBeVisible();
  });
});

/** Écart entre l'instant serveur donné (ms) et le départ de la Course. */
function lireDepartEnSecondes(instantServeurMs: number, courseId: string): number {
  return (instantServeurMs - lireDepartCourseMs(courseId)) / 1000;
}

function lireDepartCourseMs(courseId: string): number {
  if (!/^[0-9a-f-]{36}$/.test(courseId)) throw new Error('identifiant de course invalide');
  const sortie = execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'base', 'psql', '-U', process.env.E2E_BASE_UTILISATEUR ?? 'backyard', '-d', process.env.E2E_BASE_NOM ?? 'backyard', '-t', '-A', '-c',
      `select extract(epoch from demarree_le) * 1000 from course where id = '${courseId}'`],
    { cwd: join(__dirname, '..', '..'), stdio: 'pipe', encoding: 'utf8' },
  ).trim();
  return Number(sortie);
}
