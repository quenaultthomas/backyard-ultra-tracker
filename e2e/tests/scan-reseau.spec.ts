import { expect, test, type Route } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { champ, lirePassagesEnBase, ouvrirEcranDeScan, preparerCourseDeScan, scannerParEntree, urlScan, verifierSucces } from './aide-scan';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const MOTIF_PASSAGES = '**/api/benevole/courses/*/passages';
const MESSAGE_INJOIGNABLE = 'Le serveur est injoignable. Le passage n\'est peut-être pas enregistré : validez de nouveau avec le même jeton.';

const probleme = (status: number, code: string, detail = 'Détail simulé.') => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ title: 'Simulé', status, code, detail }),
});

const identifiantDe = (route: Route): string => (route.request().postDataJSON() as { identifiantScan: string }).identifiantScan;

function reponseRejeu(dossard: number, pseudo: string) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      passageId: '11111111-1111-4111-8111-111111111111',
      inscriptionId: '22222222-2222-4222-8222-222222222222',
      dossard,
      pseudo,
      numeroBoucle: 1,
      scanneLe: new Date().toISOString(),
      origine: 'SCAN',
    }),
  };
}

test.describe('Écran de scan, pannes réseau et idempotence', () => {
  test('CA20 - après une réponse 500, la nouvelle validation réutilise le même identifiantScan et la réponse 200 s\'affiche en succès', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    const identifiants: string[] = [];
    await page.route(MOTIF_PASSAGES, async (route) => {
      identifiants.push(identifiantDe(route));
      if (identifiants.length === 1) return route.fulfill(probleme(500, 'ERREUR_INTERNE'));
      return route.fulfill(reponseRejeu(un.dossard, un.pseudo));
    });

    await scannerParEntree(page, un.jeton);
    await expect(page.getByTestId('scan-resultat-erreur')).toHaveText(MESSAGE_INJOIGNABLE);
    await expect(champ(page)).toHaveValue(un.jeton);
    await expect(page.getByTestId('scan-bouton-enregistrer')).toBeEnabled();
    await expect(page.getByTestId('scan-bouton-enregistrer')).toHaveText('Enregistrer le passage');

    await page.getByTestId('scan-bouton-enregistrer').click();
    await verifierSucces(page, un.dossard, un.pseudo, 1);
    expect(identifiants).toHaveLength(2);
    expect(identifiants[1]).toBe(identifiants[0]);
    expect(identifiants[0]).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });

  test('CA20 - une requête interrompue affiche le message d\'injoignabilité, puis le rejeu réel n\'écrit qu\'un passage', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    const identifiants: string[] = [];
    await page.route(MOTIF_PASSAGES, async (route) => {
      identifiants.push(identifiantDe(route));
      if (identifiants.length === 1) return route.abort('failed');
      return route.continue();
    });

    await scannerParEntree(page, un.jeton);
    await expect(page.getByTestId('scan-resultat-erreur')).toHaveText(MESSAGE_INJOIGNABLE);
    await expect(champ(page)).toHaveValue(un.jeton);
    await expect(page.getByTestId('scan-bouton-enregistrer')).toBeEnabled();

    await champ(page).press('Enter');
    await verifierSucces(page, un.dossard, un.pseudo, 1);
    expect(identifiants[1]).toBe(identifiants[0]);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
  });

  test('CA20 - après une réponse 409, le scan suivant du même jeton utilise un nouvel identifiantScan', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    const identifiants: string[] = [];
    await page.route(MOTIF_PASSAGES, async (route) => {
      identifiants.push(identifiantDe(route));
      return route.fulfill(probleme(409, 'PASSAGE_DEJA_ENREGISTRE', 'Ce coureur a déjà un passage sur la boucle 1.'));
    });

    await scannerParEntree(page, un.jeton);
    await expect(page.getByTestId('scan-resultat-erreur')).toHaveText('Ce coureur a déjà un passage sur la boucle 1.');
    await champ(page).press('Enter');
    await expect.poll(() => identifiants.length).toBe(2);
    await expect(page.getByTestId('scan-resultat-erreur')).toBeVisible();
    expect(identifiants[1]).not.toBe(identifiants[0]);
  });

  test('CA20 - pendant l\'envoi, le bouton est désactivé et indique « Envoi… »', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    let liberer!: () => void;
    const attente = new Promise<void>((resolve) => (liberer = resolve));
    await page.route(MOTIF_PASSAGES, async (route) => {
      await attente;
      await route.continue();
    });

    await scannerParEntree(page, un.jeton);
    const bouton = page.getByTestId('scan-bouton-enregistrer');
    await expect(bouton).toBeDisabled();
    await expect(bouton).toHaveText('Envoi…');
    liberer();
    await verifierSucces(page, un.dossard, un.pseudo, 1);
    await expect(bouton).toBeEnabled();
    await expect(bouton).toHaveText('Enregistrer le passage');
  });

  test('CA20 - une réponse 401 renvoie à la connexion avec le retour', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await page.route(MOTIF_PASSAGES, (route) => route.fulfill(probleme(401, 'NON_AUTHENTIFIE')));
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(page).toHaveURL(`/connexion?retour=${encodeURIComponent(urlScan(course.id))}`);
  });

  test('CA20 - une réponse 403 ACCES_REFUSE renvoie à /acces-refuse', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await page.route(MOTIF_PASSAGES, (route) => route.fulfill(probleme(403, 'ACCES_REFUSE')));
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(page).toHaveURL(/\/acces-refuse$/);
  });

  test('CA20 - une réponse 403 BENEVOLE_NON_AFFECTE reste sur l\'écran avec le détail', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await page.route(MOTIF_PASSAGES, (route) => route.fulfill(probleme(403, 'BENEVOLE_NON_AFFECTE', 'Vous n\'êtes pas affecté à cette course.')));
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(page.getByTestId('scan-resultat-erreur')).toHaveText('Vous n\'êtes pas affecté à cette course.');
    await expect(page).toHaveURL(new RegExp(`${urlScan(course.id)}$`));
  });
});
