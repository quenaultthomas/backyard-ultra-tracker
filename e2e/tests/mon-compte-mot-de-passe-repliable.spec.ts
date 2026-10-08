import { expect, test, type APIRequestContext, type Page, type Request } from '@playwright/test';
import { ouvrirMenuCompte, seDeconnecterParLeMenu } from './aide-entete';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import {
  MOT_DE_PASSE_ADMIN_CREE,
  MOT_DE_PASSE_BENEVOLE_CREE,
  connecterParApi,
  creerAdminParApi,
  creerBenevoleParApi,
  exigerIdentifiantsAdminMaster,
  pseudoAdminUnique,
  pseudoBenevoleUnique,
} from './aide-admin';
import { deplierChangementMotDePasse } from './aide-mon-compte';
import { ETATS, RGB_PALETTE, RGB_TRANSPARENT, ouvrirScene, type JeuReference, type MesureContraste, type EcartCouleur } from './aide-theme';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const NOUVEAU = 'nouveau-mot-de-passe-1';
const FAUX = 'mauvais-mot-de-passe-1';
const FORET = 'rgb(20, 53, 42)';
const SURFACE = 'rgb(255, 252, 245)';
const SABLE = 'rgb(239, 230, 208)';
const TRAIT = 'rgb(217, 207, 182)';
const FOCUS = 'rgb(168, 67, 0)';
const ID_PANNEAU = 'panneau-changer-mot-de-passe';
const estChangement = (r: Request) => r.method() === 'PUT' && new URL(r.url()).pathname === '/api/comptes/moi/mot-de-passe';

const bouton = (page: Page) => page.getByTestId('mon-compte-bouton-deplier');
const panneau = (page: Page) => page.getByTestId('mon-compte-panneau-mot-de-passe');
const t = (page: Page, id: string) => page.getByTestId(id);

/** Ouvre une session par l'API (cookie partagé avec la page) puis affiche Mon compte. */
async function ouvrirMonCompteConnecte(page: Page, pseudo: string, motDePasse: string): Promise<void> {
  await page.context().clearCookies();
  await connecterParApi(page.request, pseudo, motDePasse);
  await page.goto('/mon-compte');
  await expect(t(page, 'titre-mon-compte')).toBeVisible();
  await expect(bouton(page)).toBeVisible();
}

async function nouveauCoureur(page: Page, request: Parameters<typeof creerCompteParApi>[0]): Promise<string> {
  const pseudo = pseudoUnique('coureur');
  await creerCompteParApi(request, pseudo);
  await ouvrirMonCompteConnecte(page, pseudo, MOT_DE_PASSE);
  return pseudo;
}

async function remplir(page: Page, actuel: string, nouveau: string, confirmation = nouveau): Promise<void> {
  await t(page, 'mon-compte-champ-actuel').fill(actuel);
  await t(page, 'mon-compte-champ-nouveau').fill(nouveau);
  await t(page, 'mon-compte-champ-confirmation').fill(confirmation);
}

async function champsVides(page: Page): Promise<void> {
  for (const id of ['actuel', 'nouveau', 'confirmation']) {
    await expect(t(page, `mon-compte-champ-${id}`)).toHaveValue('');
  }
}

async function aucuneErreur(page: Page): Promise<void> {
  for (const id of ['erreur-actuel', 'erreur-nouveau', 'erreur-confirmation', 'erreur-generale', 'message-succes']) {
    await expect(t(page, `mon-compte-${id}`)).toHaveCount(0);
  }
}

async function estReplie(page: Page): Promise<void> {
  await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
  await expect(panneau(page)).toHaveCSS('display', 'none');
}

async function estDeplie(page: Page): Promise<void> {
  await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');
  await expect(panneau(page)).toBeVisible();
}

async function focusEstSur(page: Page, testid: string): Promise<void> {
  await expect(t(page, testid)).toBeFocused();
}

function ecouterRequetes(page: Page): { api: Request[] } {
  const api: Request[] = [];
  page.on('request', (r) => {
    if (r.url().includes('/api/')) api.push(r);
  });
  return { api };
}

test.describe('Mon compte - changement de mot de passe repliable', () => {
  test('CA1 - la section est repliée à l\'arrivée pour un coureur, un bénévole et un admin', async ({ page, playwright }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    const coureur = pseudoUnique('coureur');
    const benevole = pseudoBenevoleUnique();
    const admin = pseudoAdminUnique();
    const api = async (action: (ctx: APIRequestContext) => Promise<void>) => {
      const ctx = await playwright.request.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost' });
      try {
        await action(ctx);
      } finally {
        await ctx.dispose();
      }
    };
    await api((ctx) => creerCompteParApi(ctx, coureur));
    await api((ctx) => creerBenevoleParApi(ctx, benevole));
    await api((ctx) => creerAdminParApi(ctx, admin));
    const cas = [
      { pseudo: coureur, mdp: MOT_DE_PASSE, role: 'Coureur', suppression: true },
      { pseudo: benevole, mdp: MOT_DE_PASSE_BENEVOLE_CREE, role: 'Bénévole', suppression: false },
      { pseudo: admin, mdp: MOT_DE_PASSE_ADMIN_CREE, role: 'Administrateur', suppression: false },
    ];
    for (const c of cas) {
      await page.context().clearCookies();
      await ouvrirMonCompteConnecte(page, c.pseudo, c.mdp);
      for (const rechargement of [false, true]) {
        if (rechargement) {
          await page.reload();
          await expect(bouton(page)).toBeVisible();
        }
        await expect(bouton(page)).toHaveText('Changer mon mot de passe');
        await expect(bouton(page)).toHaveAttribute('type', 'button');
        await expect(bouton(page)).toHaveAttribute('aria-expanded', 'false');
        await expect(bouton(page)).toHaveAttribute('aria-controls', ID_PANNEAU);
        await expect(page.locator(`[id="${ID_PANNEAU}"]`)).toHaveCount(1);
        await expect(page.locator(`[id="${ID_PANNEAU}"]`)).toHaveCSS('display', 'none');
        for (const id of ['champ-actuel', 'champ-nouveau', 'champ-confirmation', 'aide', 'bouton-changer']) {
          await expect(t(page, `mon-compte-${id}`)).not.toBeVisible();
        }
        await expect(page.getByRole('button', { name: 'Changer le mot de passe' })).toHaveCount(0);
        await aucuneErreur(page);
        await expect(page.locator('h2#titre-changer-mot-de-passe').getByTestId('mon-compte-bouton-deplier')).toHaveCount(1);
        await expect(t(page, 'mon-compte-pseudo')).toHaveText(c.pseudo);
        await expect(t(page, 'mon-compte-role')).toHaveText(c.role);
        await expect(t(page, 'mon-compte-suppression')).toHaveCount(c.suppression ? 1 : 0);
        if (c.suppression) await expect(t(page, 'mon-compte-suppression')).toBeVisible();
        await expect(bouton(page).locator('svg, img, canvas')).toHaveCount(0);
      }
    }
  });

  test('CA2 - le coureur déplie puis replie la section sans appel réseau, le focus reste sur le bouton', async ({ page, request }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await nouveauCoureur(page, request);
    await page.waitForLoadState('networkidle');
    const { api } = ecouterRequetes(page);

    await bouton(page).click();
    await estDeplie(page);
    await expect(panneau(page)).toHaveCSS('display', 'flex');
    await champsVides(page);
    await expect(t(page, 'mon-compte-aide')).toHaveText('12 caractères minimum. Vos autres connexions seront fermées.');
    await expect(t(page, 'mon-compte-bouton-changer')).toBeVisible();
    await expect(t(page, 'mon-compte-bouton-changer')).toHaveText('Changer le mot de passe');
    await aucuneErreur(page);
    await focusEstSur(page, 'mon-compte-bouton-deplier');

    const ordre = await page.evaluate(() => {
      const ids = ['mon-compte-bouton-deplier', 'mon-compte-champ-actuel', 'mon-compte-champ-nouveau', 'mon-compte-champ-confirmation', 'mon-compte-bouton-changer', 'mon-compte-bouton-supprimer'];
      const els = ids.map((id) => document.querySelector(`[data-testid="${id}"]`)!);
      return els.every((el, i) => i === 0 || !!(els[i - 1].compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING));
    });
    expect(ordre).toBe(true);

    await bouton(page).click();
    await estReplie(page);
    await focusEstSur(page, 'mon-compte-bouton-deplier');
    expect(api, 'aucune requête au dépliage / repli').toHaveLength(0);
  });

  test('CA3 - la section se pilote entièrement au clavier', async ({ page, request }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await nouveauCoureur(page, request);
    await page.waitForLoadState('networkidle');
    const { api } = ecouterRequetes(page);

    await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
    let atteint = false;
    for (let i = 0; i < 10 && !atteint; i++) {
      await page.keyboard.press('Tab');
      atteint = await bouton(page).evaluate((el) => el === document.activeElement);
    }
    expect(atteint, 'le bouton de section est atteint au Tab').toBe(true);
    await expect(bouton(page)).toHaveCSS('outline-style', 'solid');
    await expect(bouton(page)).toHaveCSS('outline-width', '3px');
    await expect(bouton(page)).toHaveCSS('outline-color', FOCUS);

    await page.keyboard.press('Tab');
    await focusEstSur(page, 'mon-compte-bouton-supprimer');
    await page.keyboard.press('Shift+Tab');
    await focusEstSur(page, 'mon-compte-bouton-deplier');

    await page.keyboard.press('Enter');
    await estDeplie(page);
    await focusEstSur(page, 'mon-compte-bouton-deplier');
    for (const id of ['champ-actuel', 'champ-nouveau', 'champ-confirmation', 'bouton-changer', 'bouton-supprimer']) {
      await page.keyboard.press('Tab');
      await focusEstSur(page, `mon-compte-${id}`);
    }
    // La spec (CA3) annonce 4 Maj+Tab ; il en faut 5 : supprimer, changer, confirmation, nouveau, actuel, bouton de section.
    for (let i = 0; i < 5; i++) await page.keyboard.press('Shift+Tab');
    await focusEstSur(page, 'mon-compte-bouton-deplier');

    await page.keyboard.press('Space');
    await estReplie(page);
    await focusEstSur(page, 'mon-compte-bouton-deplier');
    expect(await page.evaluate(() => document.activeElement === document.body)).toBe(false);

    await page.keyboard.press('Space');
    await estDeplie(page);
    await page.keyboard.press('Escape');
    await estDeplie(page);
    await focusEstSur(page, 'mon-compte-bouton-deplier');
    expect(api, 'aucune requête pendant la navigation clavier').toHaveLength(0);
  });

  test('CA4 - le repli efface la saisie et les messages, y compris après une erreur serveur', async ({ page, request }) => {
    await nouveauCoureur(page, request);
    let envois = 0;
    page.on('request', (r) => {
      if (estChangement(r)) envois++;
    });

    await deplierChangementMotDePasse(page);
    await remplir(page, MOT_DE_PASSE, 'abc', 'abcd');
    await t(page, 'mon-compte-bouton-changer').click();
    await expect(t(page, 'mon-compte-erreur-nouveau')).toHaveText('Le mot de passe doit faire au moins 12 caractères.');
    await expect(t(page, 'mon-compte-erreur-confirmation')).toHaveText('Les mots de passe ne correspondent pas.');
    await bouton(page).click();
    await estReplie(page);
    await bouton(page).click();
    await estDeplie(page);
    await champsVides(page);
    await aucuneErreur(page);
    expect(envois).toBe(0);

    // erreur serveur affichée puis repli
    await remplir(page, FAUX, NOUVEAU);
    await t(page, 'mon-compte-bouton-changer').click();
    await expect(t(page, 'mon-compte-erreur-actuel')).toHaveText('Le mot de passe actuel est incorrect.');
    await bouton(page).click();
    await estReplie(page);
    await bouton(page).click();
    await estDeplie(page);
    await champsVides(page);
    await aucuneErreur(page);
    expect(envois).toBe(1);

    // double clic rapide : deux bascules, retour au repli
    await bouton(page).click();
    await estReplie(page);
    await bouton(page).dblclick();
    await estReplie(page);
    expect(envois).toBe(1);
  });

  test('CA5 - un changement réussi referme la section, affiche la confirmation et garde le focus', async ({ page, request }) => {
    const pseudo = await nouveauCoureur(page, request);
    let envois = 0;
    page.on('request', (r) => {
      if (estChangement(r)) envois++;
    });
    await deplierChangementMotDePasse(page);
    await remplir(page, MOT_DE_PASSE, NOUVEAU);
    await t(page, 'mon-compte-bouton-changer').click();

    await expect(t(page, 'mon-compte-message-succes')).toBeVisible();
    await expect(t(page, 'mon-compte-message-succes')).toHaveText('Votre mot de passe a été modifié.');
    await expect(t(page, 'mon-compte-message-succes')).toHaveAttribute('role', 'status');
    await estReplie(page);
    await expect(panneau(page).getByTestId('mon-compte-message-succes')).toHaveCount(0);
    await champsVides(page);
    await focusEstSur(page, 'mon-compte-bouton-deplier');
    await expect(t(page, 'entete-pseudo')).toHaveText(pseudo);
    await expect(page).toHaveURL(/\/mon-compte$/);
    expect(envois).toBe(1);

    await bouton(page).click();
    await expect(t(page, 'mon-compte-message-succes')).toHaveCount(0);
    await estDeplie(page);
    await champsVides(page);

    await seDeconnecterParLeMenu(page);
    await expect(t(page, 'menu-visiteur')).toHaveCount(1);
    await ouvrirConnexion(page);
    await saisir(page, pseudo, MOT_DE_PASSE);
    await t(page, 'bouton-connexion').click();
    await expect(t(page, 'erreur-generale')).toHaveText('Pseudo ou mot de passe incorrect.');
    await saisir(page, pseudo, NOUVEAU);
    await t(page, 'bouton-connexion').click();
    await expect(t(page, 'entete-pseudo')).toHaveText(pseudo);
  });

  test('CA6 - chaque erreur laisse la section dépliée avec son message', async ({ page, request }) => {
    await nouveauCoureur(page, request);
    await deplierChangementMotDePasse(page);
    const soumettre = () => t(page, 'mon-compte-bouton-changer').click();

    // (a) soumission à vide
    await soumettre();
    await expect(t(page, 'mon-compte-erreur-actuel')).toHaveText('Le mot de passe actuel est obligatoire.');
    await expect(t(page, 'mon-compte-erreur-nouveau')).toHaveText('Le nouveau mot de passe est obligatoire.');
    await estDeplie(page);
    await expect(t(page, 'mon-compte-message-succes')).toHaveCount(0);

    // (b) nouveau de 11 caractères
    await remplir(page, MOT_DE_PASSE, 'a'.repeat(11));
    await soumettre();
    await expect(t(page, 'mon-compte-erreur-nouveau')).toHaveText('Le mot de passe doit faire au moins 12 caractères.');
    await estDeplie(page);
    await expect(t(page, 'mon-compte-message-succes')).toHaveCount(0);

    // (c) mauvais mot de passe actuel
    await remplir(page, FAUX, NOUVEAU);
    await soumettre();
    await expect(t(page, 'mon-compte-erreur-actuel')).toHaveText('Le mot de passe actuel est incorrect.');
    await estDeplie(page);
    await champsVides(page);
    await expect(t(page, 'mon-compte-message-succes')).toHaveCount(0);

    // (d) nouveau identique à l'actuel
    await remplir(page, MOT_DE_PASSE, MOT_DE_PASSE);
    await soumettre();
    await expect(t(page, 'mon-compte-erreur-nouveau')).toHaveText('Le nouveau mot de passe doit être différent de l\'actuel.');
    await estDeplie(page);
    await champsVides(page);
    await expect(t(page, 'mon-compte-message-succes')).toHaveCount(0);

    // (e) 500 interceptée
    await page.route('**/api/comptes/moi/mot-de-passe', (route) =>
      route.fulfill({ status: 500, contentType: 'application/problem+json', body: JSON.stringify({ status: 500, code: 'ERREUR_INTERNE' }) }),
    );
    await remplir(page, MOT_DE_PASSE, NOUVEAU);
    await soumettre();
    await expect(t(page, 'mon-compte-erreur-generale')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await estDeplie(page);
    await champsVides(page);
    await expect(t(page, 'mon-compte-message-succes')).toHaveCount(0);

    // (f) erreur réseau
    await page.unroute('**/api/comptes/moi/mot-de-passe');
    await page.route('**/api/comptes/moi/mot-de-passe', (route) => route.abort('failed'));
    await remplir(page, MOT_DE_PASSE, NOUVEAU);
    await soumettre();
    await expect(t(page, 'mon-compte-erreur-generale')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
    await estDeplie(page);
    await champsVides(page);
    await expect(t(page, 'mon-compte-message-succes')).toHaveCount(0);
  });

  test('CA6 - le blocage après cinq échecs laisse la section dépliée avec le message de tentatives', async ({ page, request }) => {
    await nouveauCoureur(page, request);
    await deplierChangementMotDePasse(page);
    for (let i = 0; i < 5; i++) {
      await remplir(page, FAUX, NOUVEAU);
      await t(page, 'mon-compte-bouton-changer').click();
      await expect(t(page, 'mon-compte-erreur-actuel')).toHaveText('Le mot de passe actuel est incorrect.');
    }
    await remplir(page, MOT_DE_PASSE, NOUVEAU);
    await t(page, 'mon-compte-bouton-changer').click();
    await expect(t(page, 'mon-compte-erreur-generale')).toHaveText('Trop de tentatives. Réessayez dans 15 minutes.');
    await estDeplie(page);
    await champsVides(page);
    await expect(t(page, 'mon-compte-message-succes')).toHaveCount(0);
  });

  test('CA6 - une réponse 401 redirige vers la connexion puis Mon compte réapparaît replié', async ({ page, request }) => {
    const pseudo = await nouveauCoureur(page, request);
    await deplierChangementMotDePasse(page);
    await page.route('**/api/comptes/moi/mot-de-passe', (route) =>
      route.fulfill({ status: 401, contentType: 'application/problem+json', body: JSON.stringify({ status: 401, code: 'NON_AUTHENTIFIE' }) }),
    );
    await remplir(page, MOT_DE_PASSE, NOUVEAU);
    await t(page, 'mon-compte-bouton-changer').click();
    await expect(page).toHaveURL(/\/connexion\?retour=%2Fmon-compte$/);
    await page.unroute('**/api/comptes/moi/mot-de-passe');
    await saisir(page, pseudo, MOT_DE_PASSE);
    await t(page, 'bouton-connexion').click();
    await expect(page).toHaveURL(/\/mon-compte$/);
    await expect(t(page, 'titre-mon-compte')).toBeVisible();
    await estReplie(page);
  });

  test('CA7 - pendant l\'envoi les boutons sont désactivés puis le focus revient sur le bouton de section', async ({ page, request }) => {
    await nouveauCoureur(page, request);
    await deplierChangementMotDePasse(page);
    let liberer: () => void = () => undefined;
    const attente = new Promise<void>((resolve) => (liberer = resolve));
    await page.route('**/api/comptes/moi/mot-de-passe', async (route) => {
      await attente;
      await route.continue();
    });
    await remplir(page, MOT_DE_PASSE, NOUVEAU);
    await t(page, 'mon-compte-bouton-changer').click();

    await expect(bouton(page)).toBeDisabled();
    await expect(t(page, 'mon-compte-bouton-changer')).toBeDisabled();
    await expect(bouton(page)).toHaveAttribute('aria-expanded', 'true');
    await expect(panneau(page)).toBeVisible();

    liberer();
    await expect(bouton(page)).toBeEnabled();
    await expect(t(page, 'mon-compte-message-succes')).toBeVisible();
    await focusEstSur(page, 'mon-compte-bouton-deplier');
    await estReplie(page);
  });

  test('CA7 - une réponse retardée de 1 s garde le panneau déplié jusqu\'à la réponse', async ({ page, request }) => {
    await nouveauCoureur(page, request);
    await deplierChangementMotDePasse(page);
    await page.route('**/api/comptes/moi/mot-de-passe', async (route) => {
      await new Promise((r) => setTimeout(r, 1000));
      await route.continue();
    });
    await remplir(page, MOT_DE_PASSE, NOUVEAU);
    await t(page, 'mon-compte-bouton-changer').click();
    await expect(bouton(page)).toBeDisabled();
    await expect(panneau(page)).toBeVisible();
    await expect(bouton(page)).toBeEnabled();
    await expect(t(page, 'mon-compte-message-succes')).toBeVisible();
    await expect(bouton(page)).toBeFocused();
  });

  test('CA8 - la suppression du compte reste indépendante du panneau de mot de passe', async ({ page, request, browser }) => {
    const pseudo = await nouveauCoureur(page, request);
    await estReplie(page);
    await t(page, 'mon-compte-bouton-supprimer').click();
    await expect(t(page, 'mon-compte-confirmation-suppression')).toBeVisible();
    await expect(t(page, 'mon-compte-bouton-supprimer')).toHaveAttribute('aria-expanded', 'true');
    await focusEstSur(page, 'mon-compte-champ-suppression-mot-de-passe');
    await estReplie(page);

    await bouton(page).click();
    await estDeplie(page);
    await expect(t(page, 'mon-compte-confirmation-suppression')).toBeVisible();
    await bouton(page).click();
    await estReplie(page);
    await expect(t(page, 'mon-compte-confirmation-suppression')).toBeVisible();

    await t(page, 'mon-compte-bouton-annuler-suppression').click();
    await expect(t(page, 'mon-compte-confirmation-suppression')).toHaveCount(0);
    await focusEstSur(page, 'mon-compte-bouton-supprimer');
    await estReplie(page);

    for (const id of ['suppression', 'suppression-avertissement', 'bouton-supprimer']) {
      await expect(t(page, `mon-compte-${id}`)).toHaveCount(1);
    }
    await t(page, 'mon-compte-bouton-supprimer').click();
    for (const id of ['confirmation-suppression', 'champ-suppression-mot-de-passe', 'bouton-confirmer-suppression', 'bouton-annuler-suppression']) {
      await expect(t(page, `mon-compte-${id}`)).toHaveCount(1);
    }
    await t(page, 'mon-compte-champ-suppression-mot-de-passe').fill(MOT_DE_PASSE);
    await t(page, 'mon-compte-bouton-confirmer-suppression').click();
    await expect(page).toHaveURL(/\/connexion$/);
    await expect(t(page, 'message-compte-supprime')).toHaveText('Votre compte a été supprimé.');
    expect(pseudo).toBeTruthy();

    // un bénévole n'a pas de section de suppression
    const contexte = await browser.newContext();
    try {
      const pageB = await contexte.newPage();
      const benevole = pseudoBenevoleUnique();
      await creerBenevoleParApi(request, benevole);
      await ouvrirMonCompteConnecte(pageB, benevole, MOT_DE_PASSE_BENEVOLE_CREE);
      await expect(bouton(pageB)).toBeVisible();
      await expect(t(pageB, 'mon-compte-suppression')).toHaveCount(0);
    } finally {
      await contexte.close();
    }
  });

  for (const largeur of [1280, 360]) {
    test(`CA9 - le style de la section est conforme à la charte à ${largeur} px`, async ({ page, request }) => {
      await page.setViewportSize({ width: largeur, height: 800 });
      await nouveauCoureur(page, request);
      const titre = await page.locator('h2#titre-changer-mot-de-passe').boundingBox();
      const attendu = largeur === 1280 ? 366 : 278;
      expect(Math.abs(titre!.width - attendu)).toBeLessThanOrEqual(1);

      const mesurerBouton = () =>
        bouton(page).evaluate((el) => {
          const cs = getComputedStyle(el);
          const after = getComputedStyle(el, '::after');
          const r = el.getBoundingClientRect();
          const h2 = el.closest('h2')!.getBoundingClientRect();
          return {
            fond: cs.backgroundColor, couleur: cs.color, bordure: cs.borderTopWidth, bordureCouleur: cs.borderTopColor,
            graisse: cs.fontWeight, taille: cs.fontSize, hauteur: r.height, largeur: r.width, largeurTitre: h2.width,
            afterL: after.width, afterH: after.height, afterContenu: after.content,
          };
        });
      for (const deplie of [false, true]) {
        if (deplie) await deplierChangementMotDePasse(page);
        const m = await mesurerBouton();
        expect(m.fond).toBe(SURFACE);
        expect(m.couleur).toBe(FORET);
        expect(m.bordure).toBe('1px');
        expect(m.bordureCouleur).toBe(FORET);
        expect(m.graisse).toBe('600');
        expect(m.taille).toBe('16px');
        expect(m.hauteur).toBeGreaterThanOrEqual(44);
        expect(Math.abs(m.largeur - m.largeurTitre)).toBeLessThanOrEqual(1);
        expect(Math.abs(m.largeur - attendu)).toBeLessThanOrEqual(1);
        expect(m.afterL).toBe('8px');
        expect(m.afterH).toBe('8px');
        expect(['""', 'none', 'normal']).toContain(m.afterContenu);
      }
      await bouton(page).hover();
      await expect(bouton(page)).toHaveCSS('background-color', SABLE);
      await page.mouse.move(0, 0);

      const espaces = await page.evaluate(() => {
        const haut = (id: string) => document.querySelector(`[data-testid="${id}"]`)!.getBoundingClientRect();
        const identite = document.querySelector('dl')!.getBoundingClientRect();
        const section = document.querySelector('section.section-repliable, section[aria-labelledby="titre-changer-mot-de-passe"]')!.getBoundingClientRect();
        const btn = haut('mon-compte-bouton-deplier');
        const pan = haut('mon-compte-panneau-mot-de-passe');
        return { identiteVersSection: section.top - identite.bottom, boutonVersPanneau: pan.top - btn.bottom };
      });
      expect(Math.abs(espaces.identiteVersSection - 20)).toBeLessThanOrEqual(1);
      expect(Math.abs(espaces.boutonVersPanneau - 16)).toBeLessThanOrEqual(1);

      await expect(t(page, 'mon-compte-suppression')).toHaveCSS('border-top-width', '1px');
      await expect(t(page, 'mon-compte-suppression')).toHaveCSS('border-top-color', TRAIT);
      await expect(t(page, 'mon-compte-suppression')).toHaveCSS('padding-top', '20px');

      if (largeur === 360) {
        const carte = await page.locator('section.carte').boundingBox();
        const cw = await page.evaluate(() => document.documentElement.clientWidth);
        expect(Math.round(carte!.width)).toBe(cw - 32);
        expect(Math.round(carte!.x)).toBe(16);
        expect(Math.round(carte!.width)).toBe(328);
        const petits = await page.evaluate(() => {
          const sel = ['mon-compte-bouton-deplier', 'mon-compte-champ-actuel', 'mon-compte-champ-nouveau', 'mon-compte-champ-confirmation', 'mon-compte-bouton-changer'];
          const cibles = sel.map((id) => document.querySelector(`[data-testid="${id}"]`)!.getBoundingClientRect().height);
          const texte = Array.from(document.querySelectorAll('main *')).filter((e) => Array.from(e.childNodes).some((n) => n.nodeType === 3 && n.textContent!.trim()) && e.getBoundingClientRect().width > 0)
            .map((e) => parseFloat(getComputedStyle(e).fontSize));
          return { cibles, texteMin: Math.min(...texte) };
        });
        for (const h of petits.cibles) expect(h).toBeGreaterThanOrEqual(44);
        expect(petits.texteMin).toBeGreaterThanOrEqual(13);
      }

      // message de succès (réponse réelle sur ce compte)
      await remplirEtValider(page);
      const succes = t(page, 'mon-compte-message-succes');
      await expect(succes).toBeVisible();
      await expect(succes).toHaveCSS('background-color', 'rgb(227, 239, 230)');
      await expect(succes).toHaveCSS('color', FORET);
      await expect(succes).toHaveCSS('padding-top', '12px');
      await expect(succes).toHaveCSS('padding-left', '12px');
      await expect(succes).toHaveCSS('border-left-width', '4px');
    });
  }

  test('CA9 - la scène « mon compte avec changement de mot de passe déplié » respecte palette et contrastes', async ({ page, playwright }) => {
    test.setTimeout(90_000);
    const scene = ETATS.find((e) => e.nom === 'mon compte avec changement de mot de passe deplie');
    expect(scene, 'scène ETATS présente').toBeDefined();
    const pseudo = pseudoUnique('alice');
    const ctx = await playwright.request.newContext({ baseURL: process.env.BASE_URL ?? 'http://localhost' });
    try {
      await creerCompteParApi(ctx, pseudo);
    } finally {
      await ctx.dispose();
    }
    const jeu = { coureur: pseudo } as unknown as JeuReference;
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await ouvrirScene(page, scene!, jeu);
    await page.mouse.move(0, 0);
    await estDeplie(page);
    const ecarts = (await page.evaluate((a) => (window as unknown as { __t: { horsPalette(a: string[]): EcartCouleur[] } }).__t.horsPalette(a), [...RGB_PALETTE, RGB_TRANSPARENT])) as EcartCouleur[];
    expect(ecarts, 'couleurs hors palette').toEqual([]);
    const mesures = (await page.evaluate(() => (window as unknown as { __t: { contrastes(): MesureContraste[] } }).__t.contrastes())) as MesureContraste[];
    expect(mesures.length).toBeGreaterThan(0);
    const insuffisantes = mesures.filter((m) => m.ratio < m.seuil);
    expect(insuffisantes, 'contrastes insuffisants').toEqual([]);
  });

  test('CA10 - aucun débordement à 200 % de texte, de 320 à 1280 px', async ({ page, request }) => {
    test.setTimeout(120_000);
    const pseudo = pseudoUnique('coureur');
    await creerCompteParApi(request, pseudo);
    const tailles = [
      { w: 320, h: 640, carte: 288 },
      { w: 360, h: 640, carte: 328 },
      { w: 1280, h: 800, carte: 416 },
    ];
    for (const { w, h, carte } of tailles) {
      await page.setViewportSize({ width: w, height: h });
      const etats: Array<[string, () => Promise<void>]> = [
        ['replié', async () => {}],
        ['déplié', async () => { await deplierChangementMotDePasse(page); }],
        ['déplié avec erreurs', async () => {
          await deplierChangementMotDePasse(page);
          await t(page, 'mon-compte-bouton-changer').click();
          await expect(t(page, 'mon-compte-erreur-actuel')).toBeVisible();
        }],
        ['replié avec message de succès', async () => {
          await page.route('**/api/comptes/moi/mot-de-passe', (route) => route.fulfill({ status: 204 }));
          await deplierChangementMotDePasse(page);
          await remplir(page, MOT_DE_PASSE, NOUVEAU);
          await t(page, 'mon-compte-bouton-changer').click();
          await expect(t(page, 'mon-compte-message-succes')).toBeVisible();
          await estReplie(page);
        }],
        ['confirmation de suppression ouverte', async () => {
          await t(page, 'mon-compte-bouton-supprimer').click();
          await expect(t(page, 'mon-compte-confirmation-suppression')).toBeVisible();
        }],
      ];
      for (const [nom, preparer] of etats) {
        await page.unroute('**/api/comptes/moi/mot-de-passe');
        await ouvrirMonCompteConnecte(page, pseudo, MOT_DE_PASSE);
        const avant = await page.locator('section.carte').boundingBox();
        expect.soft(Math.round(avant!.width), `${w}px : carte avant agrandissement`).toBe(carte);
        if (w === 320) expect.soft(Math.round(avant!.x)).toBe(16);
        await page.addStyleTag({ content: 'html{font-size:32px}' });
        await expect(t(page, 'titre-mon-compte')).toHaveCSS('font-size', '56px');
        await preparer();
        const mesure = await page.evaluate(() => {
          const carte = document.querySelector('section.carte')!;
          const rc = carte.getBoundingClientRect();
          const debordants: string[] = [];
          for (const el of Array.from(carte.querySelectorAll('*'))) {
            const r = el.getBoundingClientRect();
            if (r.width === 0 && r.height === 0) continue;
            if (r.left < rc.left - 0.5 || r.right > rc.right + 0.5) debordants.push(`${el.tagName}[${el.getAttribute('data-testid') ?? ''}]`);
          }
          const sw = (id: string) => { const e = document.querySelector(`[data-testid="${id}"]`); return e ? e.scrollWidth <= e.clientWidth : true; };
          return {
            page: document.documentElement.scrollWidth <= document.documentElement.clientWidth,
            carteOk: carte.scrollWidth <= carte.clientWidth,
            titre: sw('titre-mon-compte'), bouton: sw('mon-compte-bouton-deplier'), succes: sw('mon-compte-message-succes'),
            debordants, largeur: Math.round(rc.width), gauche: Math.round(rc.left), ow: getComputedStyle(carte).overflowWrap,
            hauteurBouton: document.querySelector('[data-testid="mon-compte-bouton-deplier"]')!.getBoundingClientRect().height,
          };
        });
        const contexte = `${w}px - ${nom}`;
        expect.soft(mesure.page, `${contexte} : défilement horizontal`).toBe(true);
        expect.soft(mesure.carteOk, `${contexte} : carte`).toBe(true);
        expect.soft(mesure.titre, `${contexte} : titre`).toBe(true);
        expect.soft(mesure.bouton, `${contexte} : bouton`).toBe(true);
        expect.soft(mesure.succes, `${contexte} : succès`).toBe(true);
        expect.soft(mesure.debordants, `${contexte} : descendants débordants`).toEqual([]);
        expect.soft(mesure.ow).toBe('anywhere');
        if (w === 320) expect.soft(mesure.hauteurBouton, `${contexte} : libellé sur plusieurs lignes`).toBeGreaterThan(60);
      }
    }
  });

  test('CA11 - mouvement réduit : bascule instantanée, aucune animation', async ({ page, request }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await nouveauCoureur(page, request);
    await expect(bouton(page)).toHaveCSS('transition-duration', '0s');
    await expect(bouton(page)).toHaveCSS('animation-name', 'none');
    await bouton(page).click();
    expect(await panneau(page).evaluate((el) => getComputedStyle(el).display)).toBe('flex');
    expect(await page.evaluate(() => document.getAnimations().length)).toBe(0);
    await estDeplie(page);
    await expect(page.locator('html')).toHaveCSS('color-scheme', 'light');
  });

  test('CA11 - contraste forcé : bouton bordé, chevron visible, bascule fonctionnelle', async ({ page, request }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.emulateMedia({ forcedColors: 'active' });
    await nouveauCoureur(page, request);
    await expect(bouton(page)).toBeVisible();
    const bordures = await bouton(page).evaluate((el) => {
      const cs = getComputedStyle(el);
      const after = getComputedStyle(el, '::after');
      return { largeur: cs.borderTopWidth, couleur: cs.borderTopColor, afterCouleur: after.borderRightColor, afterLargeur: after.borderRightWidth };
    });
    expect(bordures.largeur).toBe('1px');
    expect(bordures.couleur).not.toBe(RGB_TRANSPARENT);
    expect(bordures.afterCouleur).not.toBe(RGB_TRANSPARENT);
    expect(bordures.afterLargeur).toBe('2px');
    await bouton(page).click();
    await estDeplie(page);
    await bouton(page).click();
    await estReplie(page);
  });

  test('CA12 - parcours mobile complet : connexion, menu, dépliage, erreurs, changement, reconnexion', async ({ page, request }) => {
    await page.setViewportSize({ width: 360, height: 640 });
    const pseudo = pseudoUnique('essai-r6');
    await creerCompteParApi(request, pseudo);
    await ouvrirConnexion(page);
    await saisir(page, pseudo, MOT_DE_PASSE);
    await t(page, 'bouton-connexion').click();
    await expect(t(page, 'entete-pseudo')).toHaveText(pseudo);
    await ouvrirMenuCompte(page);
    await t(page, 'menu-lien-mon-compte').click();
    await expect(page).toHaveURL(/\/mon-compte$/);
    await estReplie(page);

    await deplierChangementMotDePasse(page);
    await t(page, 'mon-compte-bouton-changer').click();
    await expect(t(page, 'mon-compte-erreur-actuel')).toHaveText('Le mot de passe actuel est obligatoire.');
    await expect(t(page, 'mon-compte-erreur-nouveau')).toHaveText('Le nouveau mot de passe est obligatoire.');
    await remplir(page, MOT_DE_PASSE, NOUVEAU);
    await t(page, 'mon-compte-bouton-changer').click();
    await expect(t(page, 'mon-compte-message-succes')).toHaveText('Votre mot de passe a été modifié.');
    await estReplie(page);

    await seDeconnecterParLeMenu(page);
    await expect(t(page, 'menu-visiteur')).toHaveCount(1);
    await ouvrirConnexion(page);
    await saisir(page, pseudo, NOUVEAU);
    await t(page, 'bouton-connexion').click();
    await expect(t(page, 'entete-pseudo')).toHaveText(pseudo);
  });
});

async function remplirEtValider(page: Page): Promise<void> {
  await deplierChangementMotDePasse(page);
  await remplir(page, MOT_DE_PASSE, NOUVEAU);
  await t(page, 'mon-compte-bouton-changer').click();
}
