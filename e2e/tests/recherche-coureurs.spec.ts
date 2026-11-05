import { expect, test, type Page } from '@playwright/test';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_ADMIN_MASTER,
  MOT_DE_PASSE_BENEVOLE_CREE,
  PSEUDO_ADMIN_MASTER,
  connecterParApi,
  creerAdminParApi,
  creerBenevoleParApi,
  exigerIdentifiantsAdminMaster,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';
import { MOT_DE_PASSE, creerCompteParApi, pseudoUnique } from './aide-connexion';
import { ouvrirFormulaire, ouvrirPanneauRecherche } from './aide-formulaire';
import {
  CHEMIN_RECHERCHE,
  PROBLEME,
  aucuneRecherche,
  avecApi,
  corpsRecherche,
  creerCoureursNumerotes,
  deuxChiffres,
  espionnerRecherches,
  prefixeUnique,
  preparerJeuRecherche,
  resteAbsent,
  retarderRecherche,
} from './aide-recherche-coureurs';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const FOCUS = 'rgb(168, 67, 0)';
const MESSAGE_ERREUR = 'Recherche impossible, veuillez réessayer plus tard.';
const MESSAGE_PLUS = 'Plus de 10 coureurs correspondent : affinez votre recherche.';

const t = (page: Page, id: string) => page.getByTestId(id);

async function ouvrirEnMaster(page: Page, chemin: string, titre: string): Promise<void> {
  await page.context().clearCookies();
  await connecterParApi(page.request, PSEUDO_ADMIN_MASTER, MOT_DE_PASSE_ADMIN_MASTER);
  await page.goto(chemin);
  await expect(t(page, titre)).toBeVisible();
  await expect(page.getByText('Chargement…')).toHaveCount(0);
}

async function ouvrirEnAdmin(page: Page, pseudo: string, chemin: string): Promise<void> {
  await page.context().clearCookies();
  await connecterParApi(page.request, pseudo, MOT_DE_PASSE_ADMIN_CREE);
  await page.goto(chemin);
}

async function resultats(page: Page, p: string): Promise<string[]> {
  // Pages Bénévoles (9.6b) et Admins (9.7) : chaque résultat est une carte « pseudo + bouton Passer en bénévole / en admin » ; on compare le pseudo seul.
  return (await t(page, `${p}-promotion-resultat`).allTextContents()).map((x) => x.replace(/\s*Passer en (bénévole|admin)\s*$/, '').trim());
}

const attendus = (prefixe: string, de: number, a: number): string[] =>
  Array.from({ length: a - de + 1 }, (_, i) => `${prefixe}${deuxChiffres(de + i)}`);

/** Parcours commun aux deux pages : états repos, résultats, plus de 10, affinage, vide, exclusions. */
async function parcourirRecherche(page: Page, p: 'benevole' | 'admin', prefixe: string, exclus: string[]): Promise<void> {
  const champ = t(page, `${p}-promotion-recherche`);
  await champ.fill(prefixe);
  await expect(t(page, `${p}-promotion-compte`)).toHaveText('10 coureurs trouvés');
  expect(await resultats(page, p)).toEqual(attendus(prefixe, 1, 10));
  await expect(t(page, `${p}-promotion-plus`)).toHaveText(MESSAGE_PLUS);
  // pages Bénévoles (9.6b) et Admins (9.7) : un bouton par résultat (« Passer en bénévole » / « Passer en admin »), aucun lien
  await expect(t(page, `${p}-promotion-liste`).getByRole('button')).toHaveCount(10);
  await expect(t(page, `${p}-promotion-liste`).getByRole('button', { name: p === 'admin' ? /^Passer en admin le coureur / : /^Passer en bénévole le coureur / })).toHaveCount(10);
  await expect(t(page, `${p}-promotion-liste`).getByRole('link')).toHaveCount(0);

  await champ.fill(`${prefixe}1`);
  await expect(t(page, `${p}-promotion-compte`)).toHaveText('3 coureurs trouvés');
  expect(await resultats(page, p)).toEqual(attendus(prefixe, 10, 12));
  await expect(t(page, `${p}-promotion-plus`)).toHaveCount(0);

  await champ.fill(`${prefixe}12`.toUpperCase());
  await expect(t(page, `${p}-promotion-compte`)).toHaveText('1 coureur trouvé');
  expect(await resultats(page, p)).toEqual([`${prefixe}12`]);

  for (const pseudo of exclus) {
    await champ.fill(pseudo);
    await expect(t(page, `${p}-promotion-vide`)).toHaveText('Aucun coureur ne correspond à cette recherche.');
    await expect(t(page, `${p}-promotion-resultat`)).toHaveCount(0);
  }
  await champ.fill(`${prefixe}zzz`);
  await expect(t(page, `${p}-promotion-vide`)).toBeVisible();
}

test.describe('Rechercher un coureur par pseudo', () => {
  test('CA12 - le panneau de la page Bénévoles s\'ouvre à la demande, recherche après 300 ms, affiche les états et se referme', async ({ page, browser, playwright }) => {
    test.setTimeout(150_000);
    const prefixe = prefixeUnique();
    const jeu = await preparerJeuRecherche(playwright, prefixe);
    const nadia = pseudoAdminUnique();
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
    const requetes = espionnerRecherches(page);
    const ecritures: string[] = [];
    page.on('request', (r) => {
      if (new URL(r.url()).pathname.startsWith('/api/') && r.method() !== 'GET') ecritures.push(`${r.method()} ${new URL(r.url()).pathname}`);
    });

    await ouvrirEnMaster(page, '/administration/benevoles', 'titre-benevoles');
    const lignesAvant = await t(page, 'ligne-benevole').allTextContents();

    // panneau fermé, bouton juste après « Nouveau bénévole » dans l'ordre de Tab, aucune requête
    await expect(t(page, 'benevole-promotion')).toBeHidden();
    await expect(t(page, 'benevole-bouton-promouvoir')).toHaveAttribute('aria-expanded', 'false');
    await expect(t(page, 'benevole-bouton-promouvoir')).toHaveText('Promouvoir un coureur');
    await t(page, 'benevole-bouton-nouveau').focus();
    await page.keyboard.press('Tab');
    await expect(t(page, 'benevole-bouton-promouvoir')).toBeFocused();
    expect(requetes).toEqual([]);

    // Entrée ouvre le panneau, focus dans le champ, aide visible, toujours aucune requête
    await page.keyboard.press('Enter');
    await expect(t(page, 'benevole-promotion')).toBeVisible();
    await expect(t(page, 'benevole-bouton-promouvoir')).toHaveAttribute('aria-expanded', 'true');
    await expect(t(page, 'benevole-promotion-recherche')).toBeFocused();
    await expect(t(page, 'benevole-promotion-aide')).toHaveText('3 caractères minimum');
    await expect(t(page, 'benevole-promotion-aide')).toBeVisible();
    expect(requetes).toEqual([]);

    // 2 caractères : aucune requête après 600 ms
    await page.keyboard.type(prefixe.slice(0, 2));
    await aucuneRecherche(page, 600);
    expect(requetes).toEqual([]);
    await expect(t(page, 'benevole-promotion-resultat')).toHaveCount(0);

    // le préfixe complet lettre par lettre sans pause : une seule requête, avec le préfixe complet
    await page.keyboard.type(prefixe.slice(2));
    await expect(t(page, 'benevole-promotion-compte')).toHaveText('10 coureurs trouvés');
    await aucuneRecherche(page, 600);
    expect(requetes).toEqual([{ methode: 'GET', q: prefixe }]);
    expect(await resultats(page, 'benevole')).toEqual(attendus(prefixe, 1, 10));
    await expect(t(page, 'benevole-promotion-plus')).toHaveText(MESSAGE_PLUS);
    await expect(t(page, 'benevole-promotion-liste').getByRole('button')).toHaveCount(10); // un bouton « Passer en bénévole » par résultat (9.6b)
    await expect(t(page, 'benevole-promotion-liste').getByRole('link')).toHaveCount(0);
    // un coureur d'un autre rôle ou anonymisé de même préfixe n'apparaît jamais
    for (const exclu of [jeu.admin, jeu.benevole, jeu.anonymise]) {
      expect(await resultats(page, 'benevole')).not.toContain(exclu);
    }

    // une frappe qui ne change pas le fragment rogné ne relance rien (espace en fin de saisie)
    await page.keyboard.type(' ');
    await aucuneRecherche(page, 600);
    expect(requetes).toHaveLength(1);
    await page.keyboard.press('Backspace');

    // affinage, casse, vide
    await t(page, 'benevole-promotion-recherche').fill(`${prefixe}1`);
    await expect(t(page, 'benevole-promotion-compte')).toHaveText('3 coureurs trouvés');
    expect(await resultats(page, 'benevole')).toEqual(attendus(prefixe, 10, 12));
    await expect(t(page, 'benevole-promotion-plus')).toHaveCount(0);
    await t(page, 'benevole-promotion-recherche').fill(`${prefixe}12`.toUpperCase());
    await expect(t(page, 'benevole-promotion-compte')).toHaveText('1 coureur trouvé');
    await t(page, 'benevole-promotion-recherche').fill(`${prefixe}zzz`);
    await expect(t(page, 'benevole-promotion-vide')).toHaveText('Aucun coureur ne correspond à cette recherche.');
    await expect(t(page, 'benevole-promotion-vide')).not.toContainText(prefixe);

    // Entrée lance la recherche sans attendre, sans rechargement de la page
    const avant = requetes.length;
    await t(page, 'benevole-promotion-recherche').fill(prefixe);
    await t(page, 'benevole-promotion-recherche').press('Enter');
    await expect(t(page, 'benevole-promotion-compte')).toHaveText('10 coureurs trouvés');
    expect(requetes.length).toBeGreaterThan(avant);
    await expect(page).toHaveURL(/\/administration\/benevoles$/);

    // pseudo interprété en texte : réponse interceptée avec du HTML
    await page.route((url) => url.pathname === CHEMIN_RECHERCHE, (route) => route.fulfill({ status: 200, contentType: 'application/json', body: corpsRecherche(['<b>x</b>']) }));
    await t(page, 'benevole-promotion-recherche').fill('htm');
    await expect(t(page, 'benevole-promotion-resultat')).toHaveText(['<b>x</b> Passer en bénévole']);
    await expect(t(page, 'benevole-promotion-liste').locator('b')).toHaveCount(0);
    await page.unroute((url) => url.pathname === CHEMIN_RECHERCHE).catch(() => undefined);

    // Fermer : panneau masqué, champ vidé, focus rendu, réouverture vide et sans requête
    await t(page, 'benevole-promotion-bouton-fermer').click();
    await expect(t(page, 'benevole-promotion')).toBeHidden();
    await expect(t(page, 'benevole-bouton-promouvoir')).toBeFocused();
    await expect(t(page, 'benevole-bouton-promouvoir')).toHaveAttribute('aria-expanded', 'false');
    const avantReouverture = requetes.length;
    await ouvrirPanneauRecherche(page, 'benevole');
    await expect(t(page, 'benevole-promotion-recherche')).toHaveValue('');
    await expect(t(page, 'benevole-promotion-resultat')).toHaveCount(0);
    await expect(t(page, 'benevole-promotion-compte')).toHaveCount(0);
    await expect(t(page, 'benevole-promotion-vide')).toHaveCount(0);
    await aucuneRecherche(page, 500);
    expect(requetes).toHaveLength(avantReouverture);

    // indépendance avec le formulaire de création : les deux restent visibles
    await ouvrirFormulaire(page, 'benevole');
    await expect(t(page, 'benevole-promotion')).toBeVisible();
    await expect(t(page, 'benevole-formulaire')).toBeVisible();

    // aucune liste modifiée, aucune écriture
    expect(await t(page, 'ligne-benevole').allTextContents()).toEqual(lignesAvant);
    expect(ecritures).toEqual([]);

    // Nadia (ADMIN) voit le même bouton et les mêmes résultats sur la page Bénévoles
    const contexte = await browser.newContext();
    try {
      const pageNadia = await contexte.newPage();
      await connecterParApi(pageNadia.request, nadia, MOT_DE_PASSE_ADMIN_CREE);
      await pageNadia.goto('/administration/benevoles');
      await expect(t(pageNadia, 'titre-benevoles')).toBeVisible();
      await expect(t(pageNadia, 'benevole-promotion')).toBeHidden();
      await ouvrirPanneauRecherche(pageNadia, 'benevole');
      await t(pageNadia, 'benevole-promotion-recherche').fill(prefixe);
      await expect(t(pageNadia, 'benevole-promotion-compte')).toHaveText('10 coureurs trouvés');
      expect(await resultats(pageNadia, 'benevole')).toEqual(attendus(prefixe, 1, 10));
      await expect(t(pageNadia, 'benevole-promotion-plus')).toBeVisible();
    } finally {
      await contexte.close();
    }
  });

  test('CA13 - l\'admin master recherche depuis la page Admins ; l\'admin y est renvoyé ; l\'API refuse bénévoles et coureurs', async ({ page, browser, playwright }) => {
    test.setTimeout(150_000);
    const prefixe = prefixeUnique();
    const jeu = await preparerJeuRecherche(playwright, prefixe);
    const nadia = pseudoAdminUnique();
    const leo = pseudoBenevoleUnique();
    const alice = pseudoUnique('alice');
    await avecApi(playwright, (ctx) => creerAdminParApi(ctx, nadia));
    await avecApi(playwright, (ctx) => creerBenevoleParApi(ctx, leo));
    await avecApi(playwright, (ctx) => creerCompteParApi(ctx, alice));
    const requetes = espionnerRecherches(page);

    await ouvrirEnMaster(page, '/administration/admins', 'titre-admins');
    const lignesAvant = await t(page, 'ligne-admin').allTextContents();
    await expect(t(page, 'admin-promotion')).toBeHidden();
    await t(page, 'admin-bouton-nouveau').focus();
    await page.keyboard.press('Tab');
    await expect(t(page, 'admin-bouton-promouvoir')).toBeFocused();
    expect(requetes).toEqual([]);

    await ouvrirPanneauRecherche(page, 'admin');
    await expect(t(page, 'admin-promotion-aide')).toHaveText('3 caractères minimum');
    expect(requetes).toEqual([]);
    // coureurs seulement : jamais d'ADMIN, de BENEVOLE ni de Compte anonymisé
    await parcourirRecherche(page, 'admin', prefixe, [jeu.admin, jeu.benevole, jeu.anonymise, 'anonyme']);

    // le panneau et le formulaire « Nouvel admin » s'ouvrent indépendamment
    await ouvrirFormulaire(page, 'admin');
    await expect(t(page, 'admin-promotion')).toBeVisible();
    await expect(t(page, 'admin-formulaire')).toBeVisible();
    expect(await t(page, 'ligne-admin').allTextContents()).toEqual(lignesAvant);

    // Fermer : champ vidé, focus rendu
    await t(page, 'admin-promotion-bouton-fermer').click();
    await expect(t(page, 'admin-promotion')).toBeHidden();
    await expect(t(page, 'admin-bouton-promouvoir')).toBeFocused();
    await expect(t(page, 'admin-formulaire')).toBeVisible();

    // Nadia (ADMIN) est renvoyée vers /acces-refuse, sans bouton ; son fetch direct reçoit 200
    const contexteNadia = await browser.newContext();
    try {
      const pageNadia = await contexteNadia.newPage();
      await connecterParApi(pageNadia.request, nadia, MOT_DE_PASSE_ADMIN_CREE);
      await pageNadia.goto('/administration/admins');
      await expect(pageNadia).toHaveURL(/\/acces-refuse$/);
      await expect(t(pageNadia, 'admin-bouton-promouvoir')).toHaveCount(0);
      const statut = await pageNadia.evaluate((url) => fetch(url).then((r) => r.status), `${CHEMIN_RECHERCHE}?q=${prefixe}`);
      expect(statut).toBe(200);
    } finally {
      await contexteNadia.close();
    }

    // Léo (BENEVOLE) et Alice (COUREUR) : 403 sur l'API, même pour un fragment invalide
    for (const [pseudo, mdp] of [[leo, MOT_DE_PASSE_BENEVOLE_CREE], [alice, MOT_DE_PASSE]] as const) {
      const contexte = await browser.newContext();
      try {
        const autre = await contexte.newPage();
        await connecterParApi(autre.request, pseudo, mdp);
        await autre.goto('/');
        for (const q of [prefixe, 'ab']) {
          const statut = await autre.evaluate((url) => fetch(url).then((r) => r.status), `${CHEMIN_RECHERCHE}?q=${q}`);
          expect(statut, `${pseudo} q=${q}`).toBe(403);
        }
      } finally {
        await contexte.close();
      }
    }
  });

  test('CA14 - erreurs, redirections, chargement et réponses tardives de la recherche', async ({ page, playwright }) => {
    test.setTimeout(150_000);
    const prefixe = prefixeUnique();
    await creerCoureursNumerotes(playwright, prefixe, 2); // prefixe01, prefixe02 (prefixe + '0' les trouve)
    const requetes = espionnerRecherches(page);
    await ouvrirEnMaster(page, '/administration/benevoles', 'titre-benevoles');
    await ouvrirPanneauRecherche(page, 'benevole');
    const champ = t(page, 'benevole-promotion-recherche');
    const motif = (url: URL) => url.pathname === CHEMIN_RECHERCHE;

    // 500 -> erreur, relancée par une nouvelle saisie
    await page.route(motif, (route) => route.fulfill(PROBLEME(500, 'ERREUR_INTERNE')));
    await champ.fill(prefixe);
    await expect(t(page, 'benevole-promotion-erreur')).toHaveText(MESSAGE_ERREUR);
    await expect(t(page, 'benevole-promotion-erreur')).toHaveAttribute('role', 'alert');
    await page.unroute(motif);
    await champ.fill(`${prefixe}0`);
    await expect(t(page, 'benevole-promotion-compte')).toHaveText('2 coureurs trouvés');
    await expect(t(page, 'benevole-promotion-erreur')).toHaveCount(0);

    // réseau coupé -> même message ; Entrée relance la même saisie
    await page.route(motif, (route) => route.abort('failed'));
    await champ.fill(prefixe);
    await expect(t(page, 'benevole-promotion-erreur')).toHaveText(MESSAGE_ERREUR);
    await page.unroute(motif);
    await champ.press('Enter');
    await expect(t(page, 'benevole-promotion-compte')).toHaveText('2 coureurs trouvés');

    // 400 inattendu -> même message
    await page.route(motif, (route) => route.fulfill(PROBLEME(400, 'VALIDATION_ECHOUEE')));
    await champ.fill(`${prefixe}01`);
    await expect(t(page, 'benevole-promotion-erreur')).toHaveText(MESSAGE_ERREUR);
    await page.unroute(motif);

    // réponse lente : chargement visible, résultats précédents effacés ; réponse tardive ignorée
    await champ.fill(`${prefixe}02`);
    await expect(t(page, 'benevole-promotion-compte')).toHaveText('1 coureur trouvé');
    const lente = await retarderRecherche(page, prefixe);
    const avant = requetes.length;
    await champ.fill(prefixe);
    await lente.vue;
    expect(requetes.length).toBeGreaterThan(avant);
    await expect(t(page, 'benevole-promotion-chargement')).toHaveText('Recherche en cours…');
    await expect(t(page, 'benevole-promotion-resultat')).toHaveCount(0);
    // la saisie évolue (P1) : réponse immédiate
    await champ.fill(`${prefixe}0`);
    await expect(t(page, 'benevole-promotion-compte')).toHaveText('2 coureurs trouvés');
    lente.liberer();
    await resteAbsent(t(page, 'benevole-promotion-resultat').filter({ hasText: 'tardif' }));
    expect(await resultats(page, 'benevole')).toEqual([`${prefixe}01`, `${prefixe}02`]);
    await page.unroute(motif);

    // repasser sous 3 caractères pendant une requête en vol : rien n'est affiché
    const lente2 = await retarderRecherche(page, prefixe);
    await champ.fill(prefixe);
    await lente2.vue;
    await expect(t(page, 'benevole-promotion-chargement')).toBeVisible();
    await champ.fill(prefixe.slice(0, 2));
    await expect(t(page, 'benevole-promotion-chargement')).toHaveCount(0);
    lente2.liberer();
    await resteAbsent(page.getByText('tardif'));
    await expect(t(page, 'benevole-promotion-resultat')).toHaveCount(0);
    await expect(t(page, 'benevole-promotion-erreur')).toHaveCount(0);
    await page.unroute(motif);

    // fermer le panneau pendant une requête en vol : rien, même à la réouverture
    const lente3 = await retarderRecherche(page, prefixe);
    await champ.fill(prefixe);
    await lente3.vue;
    await t(page, 'benevole-promotion-bouton-fermer').click();
    await expect(t(page, 'benevole-promotion')).toBeHidden();
    lente3.liberer();
    await ouvrirPanneauRecherche(page, 'benevole');
    await resteAbsent(page.getByText('tardif'));
    await expect(t(page, 'benevole-promotion-resultat')).toHaveCount(0);
    await expect(t(page, 'benevole-promotion-chargement')).toHaveCount(0);
    await expect(t(page, 'benevole-promotion-recherche')).toHaveValue('');
    await page.unroute(motif);
  });

  test('CA14 - une réponse 401 renvoie à la connexion, une réponse 403 à l\'accès refusé', async ({ page }) => {
    const motif = (url: URL) => url.pathname === CHEMIN_RECHERCHE;
    await ouvrirEnMaster(page, '/administration/benevoles', 'titre-benevoles');
    await ouvrirPanneauRecherche(page, 'benevole');
    await page.route(motif, (route) => route.fulfill(PROBLEME(403, 'ACCES_REFUSE')));
    await t(page, 'benevole-promotion-recherche').fill('abc');
    await expect(page).toHaveURL(/\/acces-refuse$/);

    await ouvrirEnMaster(page, '/administration/benevoles', 'titre-benevoles');
    await ouvrirPanneauRecherche(page, 'benevole');
    await page.unroute(motif);
    await page.route(motif, (route) => route.fulfill(PROBLEME(401, 'NON_AUTHENTIFIE')));
    await t(page, 'benevole-promotion-recherche').fill('abc');
    await expect(page).toHaveURL(/\/connexion\?retour=%2Fadministration%2Fbenevoles$/);
  });

  test('CA15 - clavier, cibles de 44 px, étiquette, région d\'état et absence de débordement du panneau de recherche', async ({ page, playwright }) => {
    test.setTimeout(150_000);
    const prefixe = prefixeUnique();
    await creerCoureursNumerotes(playwright, prefixe, 12);

    interface Arret { testid: string | null; outline: string; largeur: number; hauteur: number; rogne: boolean }
    const parcourir = async (): Promise<Arret[]> => {
      await t(page, 'entete-titre').focus();
      const arrets: Arret[] = [];
      for (let i = 0; i < 3000; i++) {
        await page.keyboard.press('Tab');
        const arret = await page.evaluate(() => {
          const el = document.activeElement as HTMLElement | null;
          if (!el || el === document.body) return null;
          const cs = getComputedStyle(el);
          const r = el.getBoundingClientRect();
          let rogne = false;
          for (let n = el.parentElement; n; n = n.parentElement) {
            const s = getComputedStyle(n);
            if (s.overflowX !== 'visible' || s.overflowY !== 'visible') rogne = true;
            if (n.tagName === 'MAIN') break;
          }
          return { testid: el.getAttribute('data-testid'), dansEntete: !!el.closest('.entete'), outline: `${cs.outlineStyle} ${cs.outlineWidth} ${cs.outlineColor}`, largeur: r.width, hauteur: r.height, rogne };
        });
        if (!arret) break;
        if (!arret.dansEntete) arrets.push(arret);
        if (arret.testid === 'lien-retour-administration') break;
      }
      return arrets;
    };

    for (const [p, chemin, titre] of [['benevole', '/administration/benevoles', 'titre-benevoles'], ['admin', '/administration/admins', 'titre-admins']] as const) {
      await page.setViewportSize({ width: 1280, height: 800 });
      await ouvrirEnMaster(page, chemin, titre);
      const ligne = new RegExp(`^${p}-bouton-(desactiver|reactiver|supprimer)$`);

      // panneau fermé : menu, Nouveau, Promouvoir, boutons de ligne, retour
      const fermes = await parcourir();
      const idsFermes = fermes.map((a) => a.testid ?? '');
      expect(idsFermes.slice(0, 3)).toEqual(['menu-administration-courses', 'menu-administration-admins', 'menu-administration-benevoles']);
      expect(idsFermes.slice(3, 5)).toEqual([`${p}-bouton-nouveau`, `${p}-bouton-promouvoir`]);
      expect(idsFermes.slice(5, -1).length).toBeGreaterThan(0);
      expect(idsFermes.slice(5, -1).every((id) => ligne.test(id)), `${p} : boutons de ligne`).toBe(true);
      expect(idsFermes.at(-1)).toBe('lien-retour-administration');

      // panneau ouvert : Promouvoir, champ, Fermer, boutons de ligne
      await ouvrirPanneauRecherche(page, p);
      const ouverts = await parcourir();
      const idsOuverts = ouverts.map((a) => a.testid ?? '');
      expect(idsOuverts.slice(3, 7)).toEqual([`${p}-bouton-nouveau`, `${p}-bouton-promouvoir`, `${p}-promotion-recherche`, `${p}-promotion-bouton-fermer`]);
      expect(idsOuverts.slice(7, -1).every((id) => ligne.test(id)), `${p} : boutons de ligne`).toBe(true);
      expect(idsOuverts.at(-1)).toBe('lien-retour-administration');
      for (const arret of [...fermes, ...ouverts]) {
        expect(arret.outline, `${arret.testid} : contour`).toBe(`solid 3px ${FOCUS}`);
        expect(arret.rogne, `${arret.testid} : ancêtre qui rogne`).toBe(false);
      }
      for (const id of [`${p}-bouton-promouvoir`, `${p}-promotion-recherche`, `${p}-promotion-bouton-fermer`]) {
        const arret = ouverts.find((a) => a.testid === id)!;
        expect(arret.hauteur, `${id} : hauteur`).toBeGreaterThanOrEqual(44);
        expect(arret.largeur, `${id} : largeur`).toBeGreaterThanOrEqual(44);
      }

      // étiquette, aide associée, région d'état, Échap sans effet
      const champ = t(page, `${p}-promotion-recherche`);
      await expect(page.getByLabel('Pseudo du coureur')).toHaveAttribute('data-testid', `${p}-promotion-recherche`);
      await expect(champ).toHaveAttribute('maxlength', '30');
      await expect(champ).toHaveAttribute('autocomplete', 'off');
      const idAide = await t(page, `${p}-promotion-aide`).getAttribute('id');
      await expect(champ).toHaveAttribute('aria-describedby', idAide!);
      await expect(page.getByRole('region', { name: 'Rechercher un coureur' })).toBeVisible();
      await champ.fill(prefixe);
      await expect(t(page, `${p}-promotion-compte`)).toHaveText('10 coureurs trouvés');
      await expect(t(page, `${p}-promotion`).getByRole('status').filter({ has: t(page, `${p}-promotion-compte`) })).toHaveCount(1);
      await expect(t(page, `${p}-promotion`).getByRole('status').filter({ has: t(page, `${p}-promotion-liste`) })).toHaveCount(1);
      await champ.press('Escape');
      await expect(t(page, `${p}-promotion`)).toBeVisible();
      await expect(champ).toHaveValue(prefixe);
      await expect(t(page, `${p}-promotion-compte`)).toBeVisible();

      // 320 et 360 px : aucun débordement horizontal, panneau dans la fenêtre
      for (const largeur of [320, 360]) {
        await page.setViewportSize({ width: largeur, height: 800 });
        await expect(t(page, `${p}-promotion-plus`)).toBeVisible();
        const debord = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
        expect(debord, `${p} ${largeur} px : défilement horizontal`).toBeLessThanOrEqual(0);
        for (const id of [`${p}-promotion`, `${p}-promotion-recherche`, `${p}-promotion-bouton-fermer`, `${p}-promotion-plus`, `${p}-bouton-promouvoir`]) {
          const boite = (await t(page, id).boundingBox())!;
          expect(boite.x, `${p} ${largeur} px ${id} : gauche`).toBeGreaterThanOrEqual(0);
          expect(boite.x + boite.width, `${p} ${largeur} px ${id} : droite`).toBeLessThanOrEqual(largeur);
        }
      }
    }

    // titres de la page Bénévoles
    await page.setViewportSize({ width: 1280, height: 800 });
    await ouvrirEnMaster(page, '/administration/benevoles', 'titre-benevoles');
    const titres = await page.locator('main').evaluate((main) => Array.from(main.querySelectorAll('h1, h2')).map((h) => `${h.tagName.toLowerCase()}:${(h.textContent ?? '').trim()}`));
    expect(titres).toEqual(['h1:Gestion des bénévoles', 'h2:Bénévoles', 'h2:Rechercher un coureur', 'h2:Créer un bénévole']);
  });
});
