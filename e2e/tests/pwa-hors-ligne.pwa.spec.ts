import { expect, test } from '@playwright/test';
import { connecterAdmin } from './aide-abandon';
import { MOT_DE_PASSE_BENEVOLE_CREE, creerAdminParApi, exigerIdentifiantsAdminMaster, pseudoAdminUnique, MOT_DE_PASSE_ADMIN_CREE } from './aide-admin';
import { MOT_DE_PASSE, creerCompteParApi, pseudoUnique } from './aide-connexion';
import { connecterCoureur } from './aide-coureur';
import { seDeconnecterParLeMenu } from './aide-entete';
import { lireFile, suivreExceptions } from './aide-file-scan';
import { avecApi } from './aide-horloge';
import {
  CLE_BENEVOLE,
  CLE_COURSES,
  MESSAGE_HORS_LIGNE_CONNEXION,
  attendreServiceWorkerPret,
  connexionHorsLigne,
  creerCourseAutreBenevole,
  donneesHorsLigne,
  invaliderLaSessionServeur,
  lireCompte,
  lireLocalStorage,
  lireStockagesNavigateur,
  memoriserLaListe,
  ouvrirScanAvecServiceWorker,
  retourDe,
  urlsDuCache,
} from './aide-pwa';
import { champ, creerBenevole, lirePassagesEnBase, preparerCourseDeScan, scannerParEntree, seConnecterVers, urlScan } from './aide-scan';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const compteur = (page: import('@playwright/test').Page) => page.getByTestId('scan-compteur-attente');
const MESSAGE_ATTENTE = "Scan gardé sur l'appareil. Il sera envoyé dès que possible.";

test.describe('Service worker et Cache Storage (5.2)', () => {
  test('5.2 CA4 - le service worker contrôle la page, la coque est précachée et aucune URL /api n\'est en cache', async ({ page, playwright }) => {
    await page.goto('/');
    await attendreServiceWorkerPret(page);
    const etat = await page.evaluate(async () => {
      const registration = await navigator.serviceWorker.ready;
      return { actif: registration.active?.state, controle: navigator.serviceWorker.controller !== null };
    });
    expect(etat).toEqual({ actif: 'activated', controle: true });

    const noms = await page.evaluate(() => caches.keys());
    expect(noms.some((n) => n.startsWith('ngsw:'))).toBe(true);
    const urls = await urlsDuCache(page);
    expect(urls.some((u) => u.endsWith('/index.html'))).toBe(true);
    expect(urls.filter((u) => /\/chunk-[^/]+\.js$/.test(u)).length, 'chunks paresseux précachés').toBeGreaterThan(0);
    expect(urls.filter((u) => u.includes('/api'))).toEqual([]);

    // Léo se connecte et ouvre l'écran de scan : toujours aucune donnée /api en cache.
    const course = await preparerCourseDeScan(playwright, { nombreCoureurs: 1 });
    await seConnecterVers(page, course.benevole, MOT_DE_PASSE_BENEVOLE_CREE, urlScan(course.id));
    await expect(champ(page)).toBeVisible();
    await memoriserLaListe(page, course.nom);
    await page.goto(urlScan(course.id));
    await expect(champ(page)).toBeVisible();
    expect((await urlsDuCache(page)).filter((u) => u.includes('/api'))).toEqual([]);

    // Un écran paresseux d'administration est déjà dans le cache : ses scripts ne partent pas au réseau.
    const admin = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, admin));
    const cache = new Set(await urlsDuCache(page));
    const scriptsCharges: string[] = [];
    page.on('request', (r) => {
      if (r.resourceType() === 'script') scriptsCharges.push(r.url());
    });
    await seDeconnecterParLeMenu(page);
    await connecterAdmin(page, admin);
    await page.goto('/administration/courses');
    await expect(page).toHaveURL(/\/administration\/courses$/);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(admin);
    expect(scriptsCharges.length).toBeGreaterThan(0);
    for (const script of scriptsCharges) expect(cache.has(script), `script chargé et précaché : ${script}`).toBe(true);
    expect((await urlsDuCache(page)).filter((u) => u.includes('/api'))).toEqual([]);
  });
});

test.describe('Ouverture hors ligne du bénévole (5.2)', () => {
  test('5.2 CA5 - l\'écran de scan se recharge hors ligne, le scan reste en attente et part au retour du réseau', async ({ page, playwright }) => {
    const exceptions = suivreExceptions(page);
    const course = await ouvrirScanAvecServiceWorker(page, playwright);
    await memoriserLaListe(page, course.nom);
    await page.goto(urlScan(course.id));
    await expect(champ(page)).toBeVisible();

    await page.context().setOffline(true);
    await page.reload();
    await expect(page).toHaveURL(new RegExp(`${urlScan(course.id)}$`));
    await expect(page.getByTestId('scan-course-nom')).toHaveText(course.nom);
    await expect(page.getByTestId('scan-hors-ligne')).toBeVisible();
    await expect(donneesHorsLigne(page)).toContainText('Hors ligne : liste des courses enregistrée le');
    await expect(champ(page)).toBeVisible();

    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(page.getByTestId('scan-resultat-attente')).toHaveText(MESSAGE_ATTENTE);
    await expect(compteur(page)).toHaveText('1 en attente');

    await page.reload();
    await expect(page).toHaveURL(new RegExp(`${urlScan(course.id)}$`));
    await expect(compteur(page)).toHaveText('1 en attente');
    await expect(donneesHorsLigne(page)).toBeVisible();
    expect(lirePassagesEnBase(course.id)).toEqual([]);

    await page.context().setOffline(false);
    await expect(page.getByTestId('scan-message-envoyes')).toHaveText('1 scan envoyé');
    await expect(compteur(page)).toHaveCount(0);
    await expect(donneesHorsLigne(page)).toHaveCount(0);
    await expect.poll(() => lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
    expect(exceptions).toEqual([]);
  });

  test('5.2 CA6 - l\'accueil bénévole se recharge hors ligne avec la liste mémorisée ; un écran de Course non mémorisée affiche « non affecté »', async ({ page, playwright }) => {
    const course = await ouvrirScanAvecServiceWorker(page, playwright);
    const autre = await creerCourseAutreBenevole(playwright);
    await memoriserLaListe(page, course.nom);

    await page.context().setOffline(true);
    await page.reload();
    await expect(page).toHaveURL(/\/benevole$/);
    const ligne = page.getByTestId('benevole-ligne-course').filter({ hasText: course.nom });
    await expect(ligne).toHaveCount(1);
    await expect(ligne.getByTestId('benevole-lien-scanner')).toBeVisible();
    await expect(donneesHorsLigne(page)).toContainText('Hors ligne : liste des courses enregistrée le');
    await expect(page.getByTestId('benevole-ligne-course').filter({ hasText: autre.nom })).toHaveCount(0);

    await ligne.getByTestId('benevole-lien-scanner').click();
    await expect(page).toHaveURL(new RegExp(`${urlScan(course.id)}$`));
    await expect(page.getByTestId('scan-course-nom')).toHaveText(course.nom);
    await expect(champ(page)).toBeVisible();

    // Course absente de la liste mémorisée : message existant, aucun formulaire.
    await page.goto(urlScan(autre.id));
    await expect(page.getByTestId('scan-message-etat')).toHaveText("Vous n'êtes pas affecté à cette course.");
    await expect(champ(page)).toHaveCount(0);

    // Retour du réseau sur l'accueil : la liste est relue du serveur.
    await page.goto('/benevole');
    await expect(donneesHorsLigne(page)).toBeVisible();
    await page.context().setOffline(false);
    await expect(donneesHorsLigne(page)).toHaveCount(0);
    await expect(page.getByTestId('benevole-ligne-course').filter({ hasText: course.nom })).toHaveCount(1);
  });
});

test.describe('Écrans non bénévoles hors ligne (5.2)', () => {
  async function verifierRedirectionHorsLigne(page: import('@playwright/test').Page, chemins: string[]): Promise<void> {
    for (const chemin of chemins) {
      await page.goto(chemin);
      await expect(page).toHaveURL(/\/connexion\?/);
      expect(retourDe(page)).toBe(chemin);
      await expect(connexionHorsLigne(page)).toHaveText(MESSAGE_HORS_LIGNE_CONNEXION);
    }
  }

  test('5.2 CA7 - sans session mémorisée, la coque se charge hors ligne puis la connexion est demandée avec le message hors ligne', async ({ page }) => {
    await page.goto('/');
    await attendreServiceWorkerPret(page);
    await page.context().setOffline(true);

    await verifierRedirectionHorsLigne(page, ['/administration/courses', '/coureur/inscriptions', '/benevole']);

    await page.goto('/');
    await expect(page.getByTestId('titre')).toBeVisible();
    await expect(connexionHorsLigne(page)).toHaveCount(0);

    await page.goto('/connexion');
    await expect(connexionHorsLigne(page)).toBeVisible();
    await page.context().setOffline(false);
    await expect(connexionHorsLigne(page)).toHaveCount(0);
  });

  test('5.2 CA7 - un administrateur connecté n\'est pas reconstruit hors ligne', async ({ page, playwright }) => {
    const admin = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, admin, MOT_DE_PASSE_ADMIN_CREE));
    await connecterAdmin(page, admin);
    await page.goto('/administration/courses');
    await attendreServiceWorkerPret(page);
    await page.context().setOffline(true);

    await verifierRedirectionHorsLigne(page, ['/administration/courses', '/benevole']);
    expect(Object.keys(await lireLocalStorage(page))).not.toContain(CLE_BENEVOLE);
    await page.context().setOffline(false);
    await expect(connexionHorsLigne(page)).toHaveCount(0);
  });

  test('5.2 CA7 - un coureur connecté n\'est pas reconstruit hors ligne', async ({ page, request }) => {
    const pseudo = pseudoUnique('coureur');
    await creerCompteParApi(request, pseudo);
    await connecterCoureur(page, pseudo, MOT_DE_PASSE);
    await attendreServiceWorkerPret(page);
    await page.context().setOffline(true);

    await verifierRedirectionHorsLigne(page, ['/coureur/inscriptions', '/benevole']);
    expect(Object.keys(await lireLocalStorage(page))).not.toContain(CLE_BENEVOLE);
    await page.context().setOffline(false);
    await expect(connexionHorsLigne(page)).toHaveCount(0);
  });
});

test.describe('Retour du réseau, session expirée et déconnexion (5.2)', () => {
  test('5.2 CA8 - session expirée pendant le hors ligne : reconnexion demandée, mémoire effacée, file conservée puis envoyée', async ({ page, playwright }) => {
    const course = await ouvrirScanAvecServiceWorker(page, playwright);
    await memoriserLaListe(page, course.nom);
    await page.goto(urlScan(course.id));
    await expect(champ(page)).toBeVisible();

    await page.context().setOffline(true);
    await page.reload();
    await scannerParEntree(page, course.coureurs[0].jeton);
    await expect(compteur(page)).toHaveText('1 en attente');
    await page.reload();
    await expect(compteur(page)).toHaveText('1 en attente');

    await invaliderLaSessionServeur(page.context());
    await page.context().setOffline(false);

    await expect(page).toHaveURL(/\/connexion\?/);
    expect(retourDe(page)).toBe(urlScan(course.id));
    const stockage = await lireLocalStorage(page);
    expect(Object.keys(stockage)).not.toContain(CLE_BENEVOLE);
    expect(Object.keys(stockage)).not.toContain(CLE_COURSES);
    expect(await lireFile(page)).toHaveLength(1);
    expect(lirePassagesEnBase(course.id)).toEqual([]);

    await page.getByTestId('champ-pseudo').fill(course.benevole);
    await page.getByTestId('champ-mot-de-passe').fill(MOT_DE_PASSE_BENEVOLE_CREE);
    await page.getByTestId('bouton-connexion').click();
    await expect(page).toHaveURL(new RegExp(`${urlScan(course.id)}$`));
    await expect.poll(() => lirePassagesEnBase(course.id)).toEqual(['1:1:SCAN']);
    await expect(compteur(page)).toHaveCount(0);
    expect(await lireFile(page)).toHaveLength(0);
  });

  test('5.2 CA8 - session toujours valide au retour du réseau (200) : aucune redirection, le compte reste connecté', async ({ page, playwright }) => {
    const course = await ouvrirScanAvecServiceWorker(page, playwright);
    await memoriserLaListe(page, course.nom);
    await page.goto(urlScan(course.id));
    await expect(champ(page)).toBeVisible();
    const compte = await lireCompte(page);

    await page.context().setOffline(true);
    await page.reload();
    await expect(donneesHorsLigne(page)).toBeVisible();

    let revalidations = 0;
    await page.context().route('**/api/comptes/moi', async (route) => {
      revalidations++;
      await route.fulfill({ status: 200, json: compte });
    });
    await page.context().setOffline(false);

    await expect.poll(() => revalidations).toBeGreaterThan(0);
    await expect(donneesHorsLigne(page)).toHaveCount(0);
    await expect(page).toHaveURL(new RegExp(`${urlScan(course.id)}$`));
    await expect(champ(page)).toBeVisible();
    expect(Object.keys(await lireLocalStorage(page))).toContain(CLE_BENEVOLE);
  });

  test('5.2 CA9 - après déconnexion rien n\'est resté sur l\'appareil ; un autre bénévole ne voit que ses Courses', async ({ page, playwright }) => {
    const courseLeo = await ouvrirScanAvecServiceWorker(page, playwright);
    await memoriserLaListe(page, courseLeo.nom);
    expect(Object.keys(await lireLocalStorage(page))).toContain(CLE_BENEVOLE);

    await seDeconnecterParLeMenu(page);
    await expect(page).toHaveURL(/\/connexion/);
    await page.context().setOffline(true);
    await page.goto('/benevole');
    await expect(page).toHaveURL(/\/connexion\?/);
    expect(retourDe(page)).toBe('/benevole');
    await expect(connexionHorsLigne(page)).toBeVisible();
    const stockage = await lireLocalStorage(page);
    expect(Object.keys(stockage)).not.toContain(CLE_BENEVOLE);
    expect(Object.keys(stockage)).not.toContain(CLE_COURSES);
    await page.context().setOffline(false);

    // Marc se connecte en ligne puis ouvre l'accueil hors ligne : seulement ses Courses.
    const marc = await creerBenevole(playwright);
    const courseMarc = await preparerCourseDeScan(playwright, { nombreCoureurs: 1, benevole: marc });
    await seConnecterVers(page, marc, MOT_DE_PASSE_BENEVOLE_CREE, '/benevole');
    await expect(page).toHaveURL(/\/benevole$/);
    await expect(page.getByTestId('entete-pseudo')).toHaveText(marc);
    await memoriserLaListe(page, courseMarc.nom);
    await page.context().setOffline(true);
    await page.reload();
    await expect(donneesHorsLigne(page)).toBeVisible();
    await expect(page.getByTestId('benevole-ligne-course').filter({ hasText: courseMarc.nom })).toHaveCount(1);
    await expect(page.getByTestId('benevole-ligne-course').filter({ hasText: courseLeo.nom })).toHaveCount(0);
    await page.context().setOffline(false);
  });

  test('5.2 CA13 - ni le jeton QR ni le mot de passe ne sont dans le cache ou le stockage ; le jeton disparaît de la file à l\'envoi', async ({ page, playwright }) => {
    const course = await ouvrirScanAvecServiceWorker(page, playwright);
    await memoriserLaListe(page, course.nom);
    await page.goto(urlScan(course.id));
    await expect(champ(page)).toBeVisible();
    const jeton = course.coureurs[0].jeton;

    await page.context().setOffline(true);
    await page.reload();
    await scannerParEntree(page, jeton);
    await expect(compteur(page)).toHaveText('1 en attente');

    const stockages = await lireStockagesNavigateur(page);
    const clefs = Object.keys(stockages.local).sort();
    expect(clefs).toEqual([CLE_BENEVOLE, CLE_COURSES].sort());
    const compte = JSON.parse(stockages.local[CLE_BENEVOLE]) as Record<string, unknown>;
    expect(Object.keys(compte).sort()).toEqual(['id', 'pseudo', 'role']);
    expect(compte['pseudo']).toBe(course.benevole);
    expect(compte['role']).toBe('BENEVOLE');
    expect(stockages.session).toEqual({});
    const tout = JSON.stringify(stockages);
    expect(tout).not.toContain(jeton);
    expect(tout).not.toContain(MOT_DE_PASSE_BENEVOLE_CREE);
    expect(page.url()).not.toContain(jeton);

    const file = await lireFile(page);
    expect(file.map((e) => e.jetonQr)).toEqual([jeton]);

    await page.context().setOffline(false);
    await expect(compteur(page)).toHaveCount(0);
    await expect.poll(async () => (await lireFile(page)).length).toBe(0);
    const apres = JSON.stringify(await lireStockagesNavigateur(page));
    expect(apres).not.toContain(jeton);
    expect(apres).not.toContain(MOT_DE_PASSE_BENEVOLE_CREE);
  });
});
