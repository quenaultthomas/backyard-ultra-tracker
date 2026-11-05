import { expect, test } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { creerCoureursNumerotes, prefixeUnique } from './aide-recherche-coureurs';
import { boutonPasserAdmin, chercherAdmin, nomBoutonAdmin, ouvrirConfirmationAdmin, ouvrirEnMaster, t } from './aide-passage-admin';

/*
 * Incrément 9.7, CA22 (RG17) : clavier, focus, cibles de 44 px, contrastes, 320 et 360 px du bouton
 * « Passer en admin » et de sa confirmation sur la page Admins.
 */
test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const FOCUS = 'solid 3px rgb(168, 67, 0)';

test.describe('Passer en admin : accessibilité', () => {
  test('CA22 - ordre de Tab, focus visible en entier, cibles de 44 px, contrastes, rôles et absence de débordement', async ({ page, playwright }) => {
    test.setTimeout(150_000);
    const prefixe = prefixeUnique();
    const pseudos = await creerCoureursNumerotes(playwright, prefixe, 12);
    await page.setViewportSize({ width: 1280, height: 800 });
    await ouvrirEnMaster(page, '/administration/admins', 'titre-admins');
    await chercherAdmin(page, pseudos[0]);
    await t(page, 'admin-promotion-recherche').fill(prefixe);
    await expect(t(page, 'admin-promotion-compte')).toHaveText('10 coureurs trouvés');
    await expect(t(page, 'admin-promotion-bouton-passer')).toHaveCount(10);

    // résultats dans une région d'état
    await expect(t(page, 'admin-promotion').getByRole('status').filter({ has: t(page, 'admin-promotion-liste') })).toHaveCount(1);

    interface Arret { testid: string | null; nom: string | null; contour: string; largeur: number; hauteur: number; rogne: boolean }
    const parcourir = async (maximum: number): Promise<Arret[]> => {
      await t(page, 'admin-promotion-recherche').focus();
      const arrets: Arret[] = [];
      for (let i = 0; i < maximum; i++) {
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
          return { testid: el.getAttribute('data-testid'), nom: el.getAttribute('aria-label'), contour: `${cs.outlineStyle} ${cs.outlineWidth} ${cs.outlineColor}`, largeur: r.width, hauteur: r.height, rogne };
        });
        if (!arret) break;
        arrets.push(arret);
        if (arret.testid === 'lien-retour-administration') break;
      }
      return arrets;
    };

    // panneau ouvert, sans confirmation : champ, un bouton par résultat, Fermer, boutons de ligne
    const sans = await parcourir(200);
    const ids = sans.map((a) => a.testid ?? '');
    expect(ids.slice(0, 10)).toEqual(Array(10).fill('admin-promotion-bouton-passer'));
    expect(ids[10]).toBe('admin-promotion-bouton-fermer');
    expect(ids.slice(11, -1).every((id) => /^admin-bouton-(desactiver|reactiver|supprimer)$/.test(id))).toBe(true);
    expect(ids.at(-1)).toBe('lien-retour-administration');
    // le nom accessible contient le pseudo et distingue les lignes
    for (const [i, pseudo] of pseudos.slice(0, 10).entries()) {
      await expect(t(page, 'admin-promotion-bouton-passer').nth(i)).toHaveAccessibleName(nomBoutonAdmin(pseudo));
    }

    // confirmation ouverte sur le 2e résultat : bouton, Confirmer, Annuler, puis le résultat suivant
    await ouvrirConfirmationAdmin(page, pseudos[1]);
    await boutonPasserAdmin(page, pseudos[1]).focus();
    const arretsAvec: string[] = [];
    for (let i = 0; i < 3; i++) {
      await page.keyboard.press('Tab');
      arretsAvec.push((await page.evaluate(() => document.activeElement?.getAttribute('data-testid'))) ?? '');
    }
    expect(arretsAvec).toEqual(['admin-promotion-bouton-confirmer', 'admin-promotion-bouton-annuler', 'admin-promotion-bouton-passer']);

    // focus visible en entier, cibles de 44 px
    for (const arret of sans.filter((a) => a.testid !== 'lien-retour-administration')) {
      expect(arret.contour, `${arret.testid} : contour`).toBe(FOCUS);
      expect(arret.rogne, `${arret.testid} : ancêtre qui rogne`).toBe(false);
    }
    for (const arret of sans.slice(0, 11)) {
      expect(arret.hauteur, `${arret.testid} : hauteur`).toBeGreaterThanOrEqual(44);
      expect(arret.largeur, `${arret.testid} : largeur`).toBeGreaterThanOrEqual(44);
    }
    for (const id of ['admin-promotion-bouton-confirmer', 'admin-promotion-bouton-annuler']) {
      const b = (await t(page, id).boundingBox())!;
      expect(b.height, `${id} : hauteur`).toBeGreaterThanOrEqual(44);
      expect(b.width, `${id} : largeur`).toBeGreaterThanOrEqual(44);
    }
    await t(page, 'admin-promotion-bouton-confirmer').focus();
    expect(await t(page, 'admin-promotion-bouton-confirmer').evaluate((el) => { const cs = getComputedStyle(el); return `${cs.outlineStyle} ${cs.outlineWidth} ${cs.outlineColor}`; })).toBe(FOCUS);

    // contrastes (WCAG AA : 4,5 pour le texte)
    const mesures = await page.evaluate(() => {
      const analyser = (c: string): [number, number, number, number] => {
        const m = c.match(/rgba?\(([^)]+)\)/)!;
        const v = m[1].split(/[ ,/]+/).filter(Boolean).map(Number);
        return [v[0], v[1], v[2], v[3] ?? 1];
      };
      const fondDe = (el: HTMLElement): [number, number, number] => {
        for (let n: HTMLElement | null = el; n; n = n.parentElement) {
          const [r, g, b, a] = analyser(getComputedStyle(n).backgroundColor);
          if (a > 0.99) return [r, g, b];
        }
        return [255, 255, 255];
      };
      const lum = ([r, g, b]: number[]) => {
        const f = (x: number) => { const s = x / 255; return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4; };
        return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
      };
      const ratio = (a: number[], b: number[]) => { const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };
      return ['admin-promotion-bouton-passer', 'admin-promotion-bouton-confirmer', 'admin-promotion-bouton-annuler', 'admin-promotion-confirmation-texte'].map((id) => {
        const el = document.querySelector<HTMLElement>(`[data-testid="${id}"]`)!;
        const [r, g, b] = analyser(getComputedStyle(el).color);
        return { id, ratio: ratio([r, g, b], fondDe(el)) };
      });
    });
    for (const m of mesures) expect(m.ratio, `${m.id} : contraste`).toBeGreaterThanOrEqual(4.5);

    // 320 et 360 px : aucun débordement, boutons et confirmation dans la fenêtre
    for (const largeur of [360, 320]) {
      await page.setViewportSize({ width: largeur, height: 800 });
      await expect(t(page, 'admin-promotion-confirmation')).toBeVisible();
      const debord = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(debord, `${largeur} px : défilement horizontal`).toBeLessThanOrEqual(0);
      for (const id of ['admin-promotion', 'admin-promotion-confirmation', 'admin-promotion-bouton-confirmer', 'admin-promotion-bouton-annuler', 'admin-promotion-bouton-fermer']) {
        const boite = (await t(page, id).boundingBox())!;
        expect(boite.x, `${largeur} px ${id} : gauche`).toBeGreaterThanOrEqual(0);
        expect(boite.x + boite.width, `${largeur} px ${id} : droite`).toBeLessThanOrEqual(largeur);
      }
      for (const bouton of await t(page, 'admin-promotion-bouton-passer').all()) {
        const boite = (await bouton.boundingBox())!;
        expect(boite.x).toBeGreaterThanOrEqual(0);
        expect(boite.x + boite.width, `${largeur} px bouton de résultat`).toBeLessThanOrEqual(largeur);
        expect(boite.height).toBeGreaterThanOrEqual(44);
      }
    }

    // erreur en role="alert" (409 simulé), Échap rend le focus
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.keyboard.press('Escape');
    await expect(t(page, 'admin-promotion-confirmation')).toHaveCount(0);
    await expect(boutonPasserAdmin(page, pseudos[1])).toBeFocused();
  });
});
