import { expect, test, type Locator, type Page, type Route } from '@playwright/test';
import { randomBytes } from 'node:crypto';
import { pseudoUnique } from './aide-connexion';
import { MOT_DE_PASSE_BENEVOLE_CREE, affecterBenevolesParApi, exigerIdentifiantsAdminMaster } from './aide-admin';
import { placerStatutCourseEnBase, placerStatutInscriptionEnBase } from './aide-coureur';
import { installerCameraSimulee, presenterQr } from './aide-camera';
import { avecApi } from './aide-horloge';
import {
  champ,
  creerBenevole,
  lirePassagesEnBase,
  ouvrirEcranDeScan,
  preparerCourseDeScan,
  scannerParEntree,
  urlScan,
  verifierSucces,
} from './aide-scan';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const MOTIF_PASSAGES = '**/api/benevole/courses/*/passages';
const MESSAGE_INJOIGNABLE = 'Le serveur est injoignable. Le passage n\'est peut-être pas enregistré : validez de nouveau avec le même jeton.';

const refus = (page: Page) => page.getByTestId('scan-refus');
const motif = (page: Page) => page.getByTestId('scan-refus-motif');
const erreur = (page: Page) => page.getByTestId('scan-resultat-erreur');

async function verifierRefus(
  page: Page,
  attendu: { motif: string; detail: string; dossard?: number; pseudo?: string; boucle?: number },
): Promise<void> {
  await expect(refus(page)).toBeVisible();
  await expect(motif(page)).toHaveText(attendu.motif);
  await expect(erreur(page)).toHaveText(attendu.detail);
  await expect(erreur(page)).toHaveAttribute('role', 'alert');
  if (attendu.dossard !== undefined) await expect(page.getByTestId('scan-refus-dossard')).toHaveText(`Dossard ${attendu.dossard}`);
  else await expect(page.getByTestId('scan-refus-dossard')).toHaveCount(0);
  if (attendu.pseudo !== undefined) await expect(page.getByTestId('scan-refus-pseudo')).toHaveText(attendu.pseudo);
  else await expect(page.getByTestId('scan-refus-pseudo')).toHaveCount(0);
  if (attendu.boucle !== undefined) await expect(page.getByTestId('scan-refus-boucle')).toHaveText(`Boucle ${attendu.boucle}`);
  else await expect(page.getByTestId('scan-refus-boucle')).toHaveCount(0);
}

/** Pictogramme CSS (masque) porté par l'alerte du refus, fond rouge de la charte. */
async function verifierRougeEtPictogramme(page: Page): Promise<void> {
  const picto = await erreur(page).evaluate((el) => {
    const s = getComputedStyle(el, '::before');
    return { content: s.content, masque: s.maskImage !== 'none' || s.getPropertyValue('-webkit-mask-image') !== 'none', largeur: parseFloat(s.width) };
  });
  expect(picto.content).not.toBe('none');
  expect(picto.masque, 'pictogramme').toBe(true);
  expect(picto.largeur).toBeGreaterThan(0);
  const memeFondQueLaCharte = await refus(page).evaluate((el) => {
    const sonde = document.createElement('p');
    sonde.style.backgroundColor = 'var(--couleur-danger-pale)';
    document.body.appendChild(sonde);
    const attendu = getComputedStyle(sonde).backgroundColor;
    sonde.remove();
    return attendu !== 'rgba(0, 0, 0, 0)' && getComputedStyle(el).backgroundColor === attendu;
  });
  expect(memeFondQueLaCharte, 'fond rouge de la charte (variable)').toBe(true);
  const rouge = await refus(page).evaluate((el) => {
    const [r, g, b] = getComputedStyle(el).color.match(/[\d.]+/g)!.map(Number);
    return r > g && r > b;
  });
  expect(rouge, 'texte rouge (danger)').toBe(true);
}

function contraste(a: string, b: string): number {
  const luminance = (css: string) => {
    const [r, g, bl] = (css.match(/[\d.]+/g) ?? []).slice(0, 3).map(Number).map((v) => {
      const c = v / 255;
      return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    });
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
  };
  const [clair, sombre] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (clair + 0.05) / (sombre + 0.05);
}

async function pasDeDefilementHorizontal(page: Page): Promise<void> {
  const debordement = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(debordement, 'défilement horizontal').toBeLessThanOrEqual(0);
}

async function selectionComplete(champDeSaisie: Locator): Promise<boolean> {
  return champDeSaisie.evaluate((el: HTMLInputElement) => el.selectionStart === 0 && el.selectionEnd === el.value.length && el.value.length > 0);
}

const probleme = (status: number, code: string, detail = 'Détail simulé.') => ({
  status,
  contentType: 'application/problem+json',
  body: JSON.stringify({ title: 'Simulé', status, code, detail }),
});

const identifiantDe = (route: Route): string => (route.request().postDataJSON() as { identifiantScan: string }).identifiantScan;

test.describe('Écran de scan, refus plein cadre', () => {
  test('CA8 - un second scan du même coureur sur la même boucle affiche le refus « Déjà passé », puis le vert le remplace', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const [bruno, chloe] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    await scannerParEntree(page, bruno.jeton);
    await verifierSucces(page, bruno.dossard, bruno.pseudo, 1);
    await expect(refus(page)).toHaveCount(0);

    await scannerParEntree(page, bruno.jeton);
    await verifierRefus(page, {
      motif: 'Déjà passé',
      detail: 'Ce coureur a déjà un passage sur la boucle 1.',
      dossard: bruno.dossard,
      pseudo: bruno.pseudo,
      boucle: 1,
    });
    await verifierRougeEtPictogramme(page);
    await expect(page.getByTestId('scan-resultat-succes')).toHaveCount(0);
    await expect(champ(page)).toHaveValue(bruno.jeton);
    await expect(champ(page)).toBeFocused();
    expect(await selectionComplete(champ(page))).toBe(true);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);

    await scannerParEntree(page, chloe.jeton);
    await verifierSucces(page, chloe.dossard, chloe.pseudo, 1);
    await expect(refus(page)).toHaveCount(0);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
  });

  test('CA11 - chaque motif de refus a son libellé, son détail, son pictogramme et son fond rouge', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const [abandon, vainqueur] = course.coureurs;
    await ouvrirEcranDeScan(page, course);

    placerStatutInscriptionEnBase(abandon.inscriptionId, 'ABANDON');
    await scannerParEntree(page, abandon.jeton);
    await verifierRefus(page, { motif: 'Coureur en abandon', detail: 'Ce coureur n\'est plus en course.', dossard: abandon.dossard, pseudo: abandon.pseudo });
    await verifierRougeEtPictogramme(page);

    placerStatutInscriptionEnBase(vainqueur.inscriptionId, 'VAINQUEUR');
    await scannerParEntree(page, vainqueur.jeton);
    await verifierRefus(page, { motif: 'Coureur déjà vainqueur', detail: 'Ce coureur n\'est plus en course.', dossard: vainqueur.dossard, pseudo: vainqueur.pseudo });
    await verifierRougeEtPictogramme(page);

    await scannerParEntree(page, 'abc');
    await verifierRefus(page, { motif: 'Jeton inconnu', detail: 'Jeton inconnu pour cette course.' });
    await verifierRougeEtPictogramme(page);

    placerStatutCourseEnBase(course.id, 'TERMINEE');
    await scannerParEntree(page, abandon.jeton);
    await verifierRefus(page, { motif: 'Course terminée', detail: 'La course est terminée.' });
    await verifierRougeEtPictogramme(page);
    await expect(motif(page)).not.toHaveText('Course non affectée');

    placerStatutCourseEnBase(course.id, 'EN_PREPARATION');
    await scannerParEntree(page, abandon.jeton);
    await verifierRefus(page, { motif: 'Course non démarrée', detail: 'La course n\'est pas démarrée.' });
    await verifierRougeEtPictogramme(page);
    await expect(motif(page)).not.toHaveText('Course non affectée');

    placerStatutCourseEnBase(course.id, 'EN_COURS');
    const autreBenevole = await creerBenevole(playwright);
    await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, course.id, [autreBenevole]));
    await scannerParEntree(page, abandon.jeton);
    await verifierRefus(page, { motif: 'Course non affectée', detail: 'Vous n\'êtes pas affecté à cette course.' });
    await verifierRougeEtPictogramme(page);
    await expect(page).toHaveURL(new RegExp(`${urlScan(course.id)}$`));
  });

  test('CA12 - jeton inexistant, jeton d\'une autre course et jeton à la casse altérée donnent le même refus générique', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const autre = await preparerCourseDeScan(playwright, { nombreCoureurs: 1, benevole: course.benevole });
    await avecApi(playwright, (ctx) => affecterBenevolesParApi(ctx, autre.id, [course.benevole]));
    const reel = course.coureurs[0];
    expect(reel.jeton).not.toBe(reel.jeton.toLowerCase());
    await ouvrirEcranDeScan(page, course);

    const inexistant = randomBytes(32).toString('base64url');
    const saisis = [inexistant, autre.coureurs[0].jeton, reel.jeton.toLowerCase()];
    const textes: string[] = [];
    for (const jeton of saisis) {
      await scannerParEntree(page, jeton);
      await verifierRefus(page, { motif: 'Jeton inconnu', detail: 'Jeton inconnu pour cette course.' });
      textes.push(((await refus(page).innerText()) ?? '').replace(/\s+/g, ' ').trim());
      // Le jeton saisi n'apparaît pas dans le DOM hors de la valeur du champ de saisie.
      const dansLeDom = await page.evaluate((j) => document.body.innerHTML.includes(j), jeton);
      expect(dansLeDom, 'jeton absent du HTML').toBe(false);
      const texteHorsChamp = await page.evaluate((j) => (document.body.innerText ?? '').includes(j), jeton);
      expect(texteHorsChamp, 'jeton absent du texte').toBe(false);
    }
    expect(textes[1]).toBe(textes[0]);
    expect(textes[2]).toBe(textes[0]);
    expect(lirePassagesEnBase(course.id)).toEqual([]);
    expect(lirePassagesEnBase(autre.id)).toEqual([]);
  });

  test('CA13 - un refus 409 n\'est jamais réessayé et la validation suivante utilise un nouvel identifiantScan', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    const identifiants: string[] = [];
    let reponses = 0;
    await page.route(MOTIF_PASSAGES, async (route) => {
      identifiants.push(identifiantDe(route));
      reponses++;
      if (reponses === 1) return route.fulfill({
        ...probleme(409, 'PASSAGE_TROP_RAPPROCHE', 'Ce coureur vient de passer : attendez 105 secondes avant un nouveau passage.'),
        body: JSON.stringify({
          title: 'Conflit', status: 409, code: 'PASSAGE_TROP_RAPPROCHE', detail: 'Ce coureur vient de passer : attendez 105 secondes avant un nouveau passage.',
          dossard: 1, pseudo: un.pseudo, numeroBoucle: 2, delaiMinimalSecondes: 120, secondesRestantes: 105,
        }),
      });
      return route.fulfill(probleme(409, 'PASSAGE_DEJA_ENREGISTRE', 'Ce coureur a déjà un passage sur la boucle 1.'));
    });

    await scannerParEntree(page, un.jeton);
    await verifierRefus(page, {
      motif: 'Scan trop rapproché',
      detail: 'Ce coureur vient de passer : attendez 105 secondes avant un nouveau passage.',
      dossard: 1,
      pseudo: un.pseudo,
      boucle: 2,
    });
    // Aucun réessai automatique : observation sur 3 s (condition de l'absence de requête, pas une attente fixe sur un état).
    await expect.poll(() => identifiants.length, { timeout: 3000, intervals: [3000] }).toBe(1);
    expect(identifiants).toHaveLength(1);

    await champ(page).press('Enter');
    await expect.poll(() => identifiants.length).toBe(2);
    await verifierRefus(page, { motif: 'Déjà passé', detail: 'Ce coureur a déjà un passage sur la boucle 1.' });
    expect(identifiants[1]).not.toBe(identifiants[0]);
    await expect.poll(() => identifiants.length, { timeout: 3000, intervals: [3000] }).toBe(2);
  });

  test('CA13 - une réponse 500 ou une requête interrompue est une panne, pas un refus', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    let appels = 0;
    await page.route(MOTIF_PASSAGES, async (route) => {
      appels++;
      if (appels === 1) return route.fulfill(probleme(500, 'ERREUR_INTERNE'));
      return route.abort('failed');
    });
    await scannerParEntree(page, un.jeton);
    await expect(erreur(page)).toHaveText(MESSAGE_INJOIGNABLE);
    await expect(refus(page)).toHaveCount(0);
    await expect(motif(page)).toHaveCount(0);

    await champ(page).press('Enter');
    await expect.poll(() => appels).toBe(2);
    await expect(erreur(page)).toHaveText(MESSAGE_INJOIGNABLE);
    await expect(refus(page)).toHaveCount(0);
  });

  test('CA13 - réponse perdue puis revalidation : même identifiantScan, le rejeu s\'affiche en succès vert, jamais en refus', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    const [un] = course.coureurs;
    await ouvrirEcranDeScan(page, course);
    const identifiants: string[] = [];
    await page.route(MOTIF_PASSAGES, async (route) => {
      identifiants.push(identifiantDe(route));
      if (identifiants.length === 1) {
        await route.fetch();
        return route.abort('failed');
      }
      return route.continue();
    });
    await scannerParEntree(page, un.jeton);
    await expect(erreur(page)).toHaveText(MESSAGE_INJOIGNABLE);
    await expect(refus(page)).toHaveCount(0);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);

    await champ(page).press('Enter');
    await verifierSucces(page, un.dossard, un.pseudo, 1);
    await expect(refus(page)).toHaveCount(0);
    expect(identifiants).toHaveLength(2);
    expect(identifiants[1]).toBe(identifiants[0]);
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
  });

  test('CA13 - 403 ACCES_REFUSE renvoie à /acces-refuse, sans bloc de refus', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await page.route(MOTIF_PASSAGES, (route) => route.fulfill(probleme(403, 'ACCES_REFUSE')));
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(page).toHaveURL(/\/acces-refuse$/);
    await expect(refus(page)).toHaveCount(0);
  });

  test('CA13 - 401 renvoie à la connexion avec le retour, sans bloc de refus', async ({ page, playwright }) => {
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await ouvrirEcranDeScan(page, course);
    await page.route(MOTIF_PASSAGES, (route) => route.fulfill(probleme(401, 'NON_AUTHENTIFIE')));
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(page).toHaveURL(`/connexion?retour=${encodeURIComponent(urlScan(course.id))}`);
    await expect(refus(page)).toHaveCount(0);
  });
});

test.describe('Écran de scan, refus à la caméra', () => {
  test('CA14 - le refus obtenu à la caméra reste affiché, une seule requête, focus non déplacé, le vert le remplace', async ({ page, playwright }) => {
    await installerCameraSimulee(page);
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 2 });
    const [bruno, chloe] = course.coureurs;
    const envois: string[] = [];
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().includes('/passages')) envois.push(r.postData() ?? '');
    });
    await ouvrirEcranDeScan(page, course);
    await scannerParEntree(page, bruno.jeton);
    await verifierSucces(page, bruno.dossard, bruno.pseudo, 1);
    envois.length = 0;

    await page.getByTestId('scan-camera-activer').click();
    await expect(page.getByTestId('scan-camera-etat')).toHaveText('Caméra active');
    await champ(page).evaluate((el) => el.blur());
    await presenterQr(page, bruno.jeton);
    await verifierRefus(page, { motif: 'Déjà passé', detail: 'Ce coureur a déjà un passage sur la boucle 1.', dossard: bruno.dossard, pseudo: bruno.pseudo, boucle: 1 });
    await expect(champ(page)).not.toBeFocused();

    // QR maintenu 5 s : toujours une seule requête, refus inchangé (observation de l'absence d'envoi).
    await expect.poll(() => envois.length, { timeout: 5000, intervals: [5000] }).toBe(1);
    await verifierRefus(page, { motif: 'Déjà passé', detail: 'Ce coureur a déjà un passage sur la boucle 1.', dossard: bruno.dossard, pseudo: bruno.pseudo, boucle: 1 });
    await expect(champ(page)).not.toBeFocused();

    await presenterQr(page, chloe.jeton);
    await verifierSucces(page, chloe.dossard, chloe.pseudo, 1);
    await expect(refus(page)).toHaveCount(0);

    await presenterQr(page, randomBytes(32).toString('base64url'));
    await verifierRefus(page, { motif: 'Jeton inconnu', detail: 'Jeton inconnu pour cette course.' });
    expect(lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN', '2:1:SCAN']);
  });
});

for (const { largeur, zoom, libelle } of [
  { largeur: 375, zoom: false, libelle: '375 px' },
  { largeur: 375, zoom: true, libelle: '375 px, texte à 200 %' },
  { largeur: 320, zoom: false, libelle: '320 px' },
  { largeur: 320, zoom: true, libelle: '320 px, texte à 200 %' },
]) {
  test(`CA15 - le refus à ${libelle} : pas de défilement horizontal, dossard en grand, pseudo de 30 caractères contenu`, async ({ page, playwright }) => {
    const pseudoLong = pseudoUnique('r').padEnd(30, 'm').slice(0, 30);
    expect(pseudoLong).toHaveLength(30);
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1, pseudos: [pseudoLong] });
    const [un] = course.coureurs;
    await page.setViewportSize({ width: largeur, height: 740 });
    await ouvrirEcranDeScan(page, course);
    if (zoom) await page.addStyleTag({ content: 'html { font-size: 200% !important; }' });
    await scannerParEntree(page, un.jeton);
    await verifierSucces(page, un.dossard, pseudoLong, 1);
    await scannerParEntree(page, un.jeton);
    await verifierRefus(page, { motif: 'Déjà passé', detail: 'Ce coureur a déjà un passage sur la boucle 1.', dossard: un.dossard, pseudo: pseudoLong, boucle: 1 });

    await pasDeDefilementHorizontal(page);
    const carte = (await page.locator('section.carte').boundingBox())!;
    for (const id of ['scan-refus', 'scan-refus-pseudo', 'scan-refus-dossard', 'scan-refus-motif', 'scan-resultat-erreur']) {
      const boite = (await page.getByTestId(id).boundingBox())!;
      expect(boite.x + boite.width, id).toBeLessThanOrEqual(carte.x + carte.width + 1);
    }
    const tailles = async (id: string) => parseFloat(await page.getByTestId(id).evaluate((el) => getComputedStyle(el).fontSize));
    expect(await tailles('scan-refus-dossard')).toBeGreaterThan(await tailles('scan-refus-pseudo'));
    expect(await page.getByTestId('scan-refus-pseudo').evaluate((el) => getComputedStyle(el).textOverflow)).not.toBe('ellipsis');

    const couleurs = await refus(page).evaluate((el) => {
      const s = getComputedStyle(el);
      return { color: s.color, fond: s.backgroundColor };
    });
    expect(contraste(couleurs.color, couleurs.fond), 'contraste AA').toBeGreaterThanOrEqual(4.5);
    // Identifiable sans la couleur : libellé de motif et pictogramme.
    expect((await motif(page).innerText()).trim().length).toBeGreaterThan(0);
    await verifierRougeEtPictogramme(page);
    await expect(erreur(page)).toHaveAttribute('role', 'alert');
    // Le refus ne disparaît pas tout seul.
    await expect(refus(page)).toBeVisible();
  });
}

test('CA15 - prefers-reduced-motion : aucune animation sur le refus ; le refus ne disparaît pas tout seul', async ({ page, playwright }) => {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await ouvrirEcranDeScan(page, course);
  await scannerParEntree(page, course.coureurs[0].jeton);
  await expect(page.getByTestId('scan-resultat-succes')).toBeVisible();
  await scannerParEntree(page, course.coureurs[0].jeton);
  await expect(refus(page)).toBeVisible();
  const animations = await page.evaluate(() =>
    [...document.querySelectorAll('main, main *')].flatMap((el) => {
      const s = getComputedStyle(el);
      const duree = [...s.animationDuration.split(','), ...s.transitionDuration.split(',')];
      return s.animationName !== 'none' || duree.some((d) => parseFloat(d) > 0.001) ? [el.tagName + '.' + el.className] : [];
    }),
  );
  expect(animations).toEqual([]);
  // Observation du maintien du refus : il est toujours là après 4 s, sans action.
  await expect.poll(() => refus(page).isVisible(), { timeout: 5000, intervals: [4000] }).toBe(true);
});
