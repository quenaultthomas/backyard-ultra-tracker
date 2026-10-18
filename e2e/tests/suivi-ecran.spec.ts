import { expect, test, type Page } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { MOT_DE_PASSE_ADMIN_CREE, courseDeReference, creerAdminParApi, creerCourseParApi, dateDansJours, exigerIdentifiantsAdminMaster, pseudoAdminUnique } from './aide-admin';
import { placerStatutCourseEnBase, sinscrireParApi } from './aide-coureur';
import { creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import { avecApi } from './aide-horloge';
import {
  MOTIF_API_SUIVI,
  compterRequetesSuivi,
  courseDemarreeAvecLogo,
  courseEnPreparation,
  courseNommee,
  demarrer,
  lire,
  lireSecondes,
  poserMarqueur,
  reponseSuivi,
  urlSuivi,
  verifierSansRechargement,
} from './aide-suivi';

/*
 * Incrément 6.1 : écran public de suivi d'une Course (CA11 à CA21 hors horloge, CA20 fiche d'administration).
 * Chaque test ouvre la page dans le contexte neuf fourni par Playwright (aucune session), sauf CA20.
 * CA13, CA16 et CA22 (horloge pilotable) : suivi-ecran.horloge.spec.ts.
 */

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const UUID_INCONNU = '00000000-0000-0000-0000-000000000000';

test('CA11 - un visiteur sans session voit le suivi d\'une Course en cours : nom, logo, statut, Boucle, compte à rebours, durée', async ({ page, playwright }) => {
  const course = await courseDemarreeAvecLogo(playwright);
  await page.goto(urlSuivi(course.id));

  await expect(lire(page, 'suivi-nom')).toHaveText(course.nom);
  await expect(page).toHaveURL(new RegExp(`${urlSuivi(course.id)}$`));
  await expect(page).not.toHaveURL(/connexion/);
  await expect(lire(page, 'suivi-logo').locator('img')).toHaveAttribute('src', new RegExp(`/api/courses/${course.id}/logo`));
  await expect(lire(page, 'suivi-statut')).toHaveText('En cours');
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5');
  await expect(lire(page, 'suivi-libelle-compte-a-rebours')).toHaveText('Prochain départ dans');
  await expect(lire(page, 'suivi-compte-a-rebours')).toHaveText(/^\s*(01:00:00|00:59:\d{2})\s*$/);
  expect(await lireSecondes(page, 'suivi-compte-a-rebours')).toBeLessThanOrEqual(3600);
  await expect(lire(page, 'suivi-duree-ecoulee')).toHaveText(/^\s*00:00:0\d\s*$/);
  await expect(page).toHaveTitle('Suivi de la course - Backyard Ultra Tracker');
});

test('CA12 - le compte à rebours et la durée avancent seuls, le polling part dans les 3,5 s, sans rechargement ni perte de focus', async ({ page, playwright }) => {
  const course = await courseDemarreeAvecLogo(playwright);
  const requetes = compterRequetesSuivi(page);
  const ouverture = Date.now();
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-boucle')).toBeVisible();
  await poserMarqueur(page);
  await lire(page, 'entete-titre').focus();
  await expect(lire(page, 'entete-titre')).toBeFocused();

  const reste1 = await lireSecondes(page, 'suivi-compte-a-rebours');
  const ecoule1 = await lireSecondes(page, 'suivi-duree-ecoulee');
  await expect.poll(async () => reste1 - (await lireSecondes(page, 'suivi-compte-a-rebours')), { timeout: 5000 }).toBeGreaterThanOrEqual(2);
  const reste2 = await lireSecondes(page, 'suivi-compte-a-rebours');
  const ecoule2 = await lireSecondes(page, 'suivi-duree-ecoulee');
  expect(reste1 - reste2).toBeGreaterThanOrEqual(1);
  expect(reste1 - reste2).toBeLessThanOrEqual(4);
  expect(ecoule2 - ecoule1).toBeGreaterThanOrEqual(1);
  expect(Math.abs(ecoule2 - ecoule1 - (reste1 - reste2))).toBeLessThanOrEqual(1);

  await expect.poll(() => requetes.instants.filter((t) => t - ouverture <= 3500).length, { timeout: 5000 }).toBeGreaterThanOrEqual(1);
  await expect.poll(() => requetes.nombre(), { timeout: 6000 }).toBeGreaterThanOrEqual(2);
  await verifierSansRechargement(page);
  await expect(lire(page, 'entete-titre')).toBeFocused();
});

test('CA14 - une horloge du navigateur décalée de +3 h ou de -3 h ne change pas l\'affichage', async ({ browser, playwright }) => {
  const course = await courseDemarreeAvecLogo(playwright);
  for (const decalageHeures of [3, -3]) {
    const contexte = await browser.newContext();
    try {
      const page = await contexte.newPage();
      await page.addInitScript((decalage) => {
        const RealDate = Date;
        const ms = decalage * 3600 * 1000;
        class DateDecalee extends RealDate {
          constructor(...args: unknown[]) {
            if (args.length === 0) super(RealDate.now() + ms);
            else super(...(args as [number]));
          }
          static override now(): number {
            return RealDate.now() + ms;
          }
        }
        (window as unknown as { Date: unknown }).Date = DateDecalee;
      }, decalageHeures);
      await page.goto(urlSuivi(course.id));
      await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5');
      await expect(lire(page, 'suivi-compte-a-rebours')).toHaveText(/^\s*(01:00:00|00:59:\d{2})\s*$/);
      await expect(lire(page, 'suivi-duree-ecoulee')).toHaveText(/^\s*00:00:\d{2}\s*$/);
      expect(await lireSecondes(page, 'suivi-duree-ecoulee')).toBeLessThanOrEqual(30);
      expect(await page.evaluate(() => new Date().getTime() - Date.now())).toBeLessThanOrEqual(5);
      expect(Math.abs((await page.evaluate(() => Date.now())) - Date.now())).toBeGreaterThan(2 * 3600 * 1000);
    } finally {
      await contexte.close();
    }
  }
});

test('CA15 - Course en préparation : message d\'attente, puis bascule seule au démarrage, sans rechargement', async ({ page, playwright }) => {
  const course = await courseEnPreparation(playwright);
  await page.goto(urlSuivi(course.id));
  await poserMarqueur(page);

  await expect(lire(page, 'suivi-message-non-demarree')).toHaveText("La course n'a pas encore démarré.");
  await expect(lire(page, 'suivi-nom')).toHaveText(course.nom);
  await expect(lire(page, 'suivi-logo')).toBeVisible();
  await expect(lire(page, 'suivi-statut')).toHaveText('En préparation');
  await expect(lire(page, 'suivi-boucle')).toHaveCount(0);
  await expect(lire(page, 'suivi-compte-a-rebours')).toHaveCount(0);
  await expect(lire(page, 'suivi-duree-ecoulee')).toHaveCount(0);

  await demarrer(playwright, course.id);
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5', { timeout: 5000 });
  await expect(lire(page, 'suivi-compte-a-rebours')).toBeVisible();
  await expect(lire(page, 'suivi-message-non-demarree')).toHaveCount(0);
  await expect(lire(page, 'suivi-statut')).toHaveText('En cours');
  await verifierSansRechargement(page);
});

test('CA17 - Course inconnue ou identifiant invalide : message d\'introuvable, sans redirection, polling arrêté', async ({ page }) => {
  for (const id of [UUID_INCONNU, 'abc']) {
    const requetes = compterRequetesSuivi(page);
    await page.goto(urlSuivi(id));
    await expect(lire(page, 'suivi-introuvable')).toHaveText("Cette course n'existe pas.");
    await expect(page).toHaveURL(new RegExp(`${urlSuivi(id)}$`));
    await expect(lire(page, 'suivi-nom')).toHaveCount(0);
    await expect(lire(page, 'suivi-boucle')).toHaveCount(0);
    await expect(lire(page, 'suivi-compte-a-rebours')).toHaveCount(0);
    const avant = requetes.nombre();
    expect(avant).toBeGreaterThanOrEqual(1);
    // 7 s sans nouvelle requête : attente passive vérifiée par une assertion répétée côté navigateur
    await expect
      .poll(async () => page.evaluate(() => new Promise<boolean>((resolve) => setTimeout(() => resolve(true), 7000))), { timeout: 9000 })
      .toBe(true);
    expect(requetes.nombre()).toBe(avant);
    page.removeAllListeners('request');
  }
});

test('CA18 - réseau coupé : avertissement, dernière Boucle conservée, compte à rebours local, retour seul ; premier chargement en 503 puis nominal', async ({ page, playwright }) => {
  const course = await courseDemarreeAvecLogo(playwright);
  await page.goto(urlSuivi(course.id));
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5');
  await poserMarqueur(page);
  const avant = await lireSecondes(page, 'suivi-compte-a-rebours');

  await page.route(MOTIF_API_SUIVI, (route) => route.abort());
  await expect(lire(page, 'suivi-avertissement-actualisation')).toHaveText('Actualisation impossible, nouvelle tentative…', { timeout: 8000 });
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5');
  await expect.poll(async () => avant - (await lireSecondes(page, 'suivi-compte-a-rebours')), { timeout: 8000 }).toBeGreaterThanOrEqual(2);
  await expect(lire(page, 'suivi-avertissement-actualisation')).toBeVisible();

  await page.unroute(MOTIF_API_SUIVI);
  await expect(lire(page, 'suivi-avertissement-actualisation')).toHaveCount(0, { timeout: 4500 });
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 1 sur 5');
  await verifierSansRechargement(page);

  // premier chargement en 503
  const page2 = await page.context().newPage();
  await page2.route(MOTIF_API_SUIVI, (route) =>
    route.fulfill({ status: 503, contentType: 'application/problem+json', body: JSON.stringify({ status: 503 }) }),
  );
  await page2.goto(urlSuivi(course.id));
  await expect(lire(page2, 'suivi-erreur')).toHaveText('Service indisponible, veuillez réessayer plus tard.');
  await expect(lire(page2, 'suivi-nom')).toHaveCount(0);
  await page2.unroute(MOTIF_API_SUIVI);
  await expect(lire(page2, 'suivi-boucle')).toHaveText('Boucle 1 sur 5', { timeout: 5000 });
  await expect(lire(page2, 'suivi-erreur')).toHaveCount(0);
});

test('CA19 - dernière Boucle écoulée : message, compte à rebours figé à 00:00:00, durée qui avance, pas de redemande immédiate', async ({ page }) => {
  const requetes = compterRequetesSuivi(page);
  await page.route(MOTIF_API_SUIVI, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: reponseSuivi({ boucleCourante: 5, derniereBoucle: true, boucleMaxTerminee: true, tempsAvantFinBoucleMillisecondes: 0, dureeEcouleeMillisecondes: 18_060_000 }),
    }),
  );
  await page.goto(urlSuivi('00000000-0000-0000-0000-000000000001'));

  await expect(lire(page, 'suivi-message-boucle-max-terminee')).toHaveText(
    "Le temps de la dernière boucle est écoulé. La fin de course sera constatée automatiquement.",
  );
  await expect(lire(page, 'suivi-compte-a-rebours')).toHaveText('00:00:00');
  await expect(lire(page, 'suivi-duree-ecoulee')).toHaveText(/^\s*05:01:0\d\s*$/);
  await expect(lire(page, 'suivi-message-derniere-boucle')).toHaveCount(0);
  await expect(lire(page, 'suivi-boucle')).toHaveText('Boucle 5 sur 5');
  const depart = requetes.nombre();

  // 5 s d'observation dans le navigateur : le compte à rebours ne quitte jamais 00:00:00, la durée avance
  const observation = await page.evaluate(
    () =>
      new Promise<{ comptes: string[]; durees: string[] }>((resolve) => {
        const comptes: string[] = [];
        const durees: string[] = [];
        const lireTexte = (id: string) => document.querySelector(`[data-testid="${id}"]`)?.textContent?.trim() ?? '';
        const horloge = setInterval(() => {
          comptes.push(lireTexte('suivi-compte-a-rebours'));
          durees.push(lireTexte('suivi-duree-ecoulee'));
        }, 250);
        setTimeout(() => {
          clearInterval(horloge);
          resolve({ comptes, durees });
        }, 5000);
      }),
  );
  expect(new Set(observation.comptes)).toEqual(new Set(['00:00:00']));
  const distinctes = new Set(observation.durees);
  expect(distinctes.size).toBeGreaterThanOrEqual(2);
  for (const d of distinctes) expect(d).toMatch(/^05:01:0\d$/);
  // une seule requête par tick de 3 s (pas de redemande immédiate à 0) : au plus 2 en 5 s
  expect(requetes.nombre() - depart).toBeLessThanOrEqual(2);
});

test('CA20 - la fiche d\'une Course (préparation, en cours, terminée) propose le lien « Ouvrir le suivi public »', async ({ page, playwright }) => {
  const pseudo = pseudoAdminUnique();
  await avecApi(playwright, (ctx) => creerAdminParApi(ctx, pseudo));
  await ouvrirConnexion(page);
  await saisir(page, pseudo, MOT_DE_PASSE_ADMIN_CREE);
  await page.getByTestId('bouton-connexion').click();
  await expect(page).toHaveURL(/\/administration$/);

  const enPreparation = await courseEnPreparation(playwright);
  const enCours = await courseDemarreeAvecLogo(playwright, { avecLogo: false });
  const terminee = await courseEnPreparation(playwright);
  placerStatutCourseEnBase(terminee.id, 'TERMINEE');

  for (const [course, statut] of [[enPreparation, 'En préparation'], [enCours, 'En cours'], [terminee, 'Terminée']] as const) {
    await page.goto(`/administration/courses/${course.id}`);
    await expect(lire(page, 'fiche-statut')).toHaveText(statut);
    const lien = lire(page, 'fiche-lien-suivi');
    await expect(lien).toHaveText('Ouvrir le suivi public');
    await expect(lien).toHaveAttribute('href', urlSuivi(course.id));
    if (statut === 'En cours') {
      await expect(lire(page, 'fiche-lien-pilotage')).toBeVisible();
    } else if (statut === 'En préparation') {
      await expect(lire(page, 'fiche-lien-pilotage')).toHaveCount(0);
    }
  }
  await page.goto(`/administration/courses/${enCours.id}`);
  await lire(page, 'fiche-lien-suivi').click();
  await expect(page).toHaveURL(new RegExp(`${urlSuivi(enCours.id)}$`));
  await expect(lire(page, 'suivi-nom')).toHaveText(enCours.nom);
});

/* ---------- CA21 : responsive, typographie, accessibilité, charte ---------- */

const NOM_LONG = `Backyard${Date.now().toString(36)}`.replace(/qr/gi, 'q9').padEnd(60, 'x');

async function defilementHorizontal(page: Page): Promise<number> {
  return page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
}

function rgb(couleur: string): [number, number, number, number] {
  const m = /rgba?\(([^)]+)\)/.exec(couleur);
  const v = (m ? m[1] : '0,0,0,0').split(/[ ,/]+/).filter(Boolean).map(Number);
  return [v[0] ?? 0, v[1] ?? 0, v[2] ?? 0, v[3] ?? 1];
}

function luminanceRelative([r, g, b]: number[]): number {
  const c = [r, g, b].map((x) => {
    const s = x / 255;
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}

test('CA21 - suivi responsive et accessible : pas de défilement horizontal, nom long coupé, chiffres tabulaires stables, annonces, contrastes, mouvement réduit, aucune couleur en dur', async ({ page, playwright }) => {
  const donnees = courseDeReference({ nom: NOM_LONG, date: dateDansJours(0) });
  // Course au nom long : un coureur inscrit est requis pour la démarrer
  const course = await avecApi(playwright, (ctx) => creerCourseParApi(ctx, donnees));
  const id = course['id'] as string;
  const pseudo = pseudoUnique('coureur');
  await avecApi(playwright, (ctx) => creerCompteParApi(ctx, pseudo));
  await avecApi(playwright, (ctx) => sinscrireParApi(ctx, pseudo, id));
  await demarrer(playwright, id);

  const profils = [
    { nom: '1280 px', largeur: 1280, hauteur: 800, police: null },
    { nom: '375 px', largeur: 375, hauteur: 800, police: null },
    { nom: '320 px', largeur: 320, hauteur: 800, police: null },
    { nom: '320 px texte 200 %', largeur: 320, hauteur: 800, police: '32px' },
  ];
  for (const p of profils) {
    await page.setViewportSize({ width: p.largeur, height: p.hauteur });
    await page.goto(urlSuivi(id));
    await expect(lire(page, 'suivi-nom')).toHaveText(NOM_LONG);
    if (p.police) await page.addStyleTag({ content: `html{font-size:${p.police}}` });
    await expect(lire(page, 'suivi-boucle')).toBeVisible();
    expect(await defilementHorizontal(page), `défilement horizontal à ${p.nom}`).toBeLessThanOrEqual(0);
    const debord = await page.evaluate(() => {
      const nom = document.querySelector('[data-testid="suivi-nom"]') as HTMLElement;
      const carte = document.querySelector('main section') as HTMLElement;
      return nom.getBoundingClientRect().right - carte.getBoundingClientRect().right;
    });
    expect(debord, `nom long débordant à ${p.nom}`).toBeLessThanOrEqual(1);
  }

  // Mesures à 375 px
  await page.setViewportSize({ width: 375, height: 800 });
  await page.goto(urlSuivi(id));
  await expect(lire(page, 'suivi-boucle')).toBeVisible();
  const mesures = await page.evaluate(() => {
    const sonde = document.createElement('span');
    sonde.style.fontSize = 'var(--taille-titre)';
    document.body.appendChild(sonde);
    const tailleTitre = getComputedStyle(sonde).fontSize;
    sonde.remove();
    const ids = ['suivi-boucle', 'suivi-compte-a-rebours', 'suivi-duree-ecoulee'];
    return {
      tailleTitre,
      elements: ids.map((i) => {
        const e = document.querySelector(`[data-testid="${i}"]`) as HTMLElement;
        const s = getComputedStyle(e);
        return {
          id: i,
          taille: s.fontSize,
          chiffres: s.fontVariantNumeric,
          ariaLive: e.closest('[aria-live]') !== null,
          role: e.closest('[role="status"],[role="alert"],[role="log"]') !== null,
        };
      }),
      annonceRole: document.querySelector('[data-testid="suivi-annonce-boucle"]')?.getAttribute('role'),
    };
  });
  expect(mesures.tailleTitre).not.toBe('');
  for (const e of mesures.elements) {
    expect(e.taille, `${e.id} en --taille-titre`).toBe(mesures.tailleTitre);
    expect(e.chiffres, `${e.id} chiffres tabulaires`).toContain('tabular-nums');
    expect(e.ariaLive, `${e.id} sans aria-live`).toBe(false);
    expect(e.role, `${e.id} hors région live`).toBe(false);
  }
  expect(mesures.annonceRole).toBe('status');

  // Aucun saut de mise en page sur ~10 s (compte à rebours qui change chaque seconde, au moins 3 rafraîchissements)
  const largeurs = await page.evaluate(
    () =>
      new Promise<Record<string, number[]>>((resolve) => {
        const ids = ['suivi-boucle', 'suivi-compte-a-rebours', 'suivi-duree-ecoulee'];
        const hauteurs: number[] = [];
        const relevé: Record<string, number[]> = { hauteur: hauteurs };
        for (const i of ids) relevé[i] = [];
        const h = setInterval(() => {
          for (const i of ids) relevé[i].push((document.querySelector(`[data-testid="${i}"]`) as HTMLElement).getBoundingClientRect().width);
          hauteurs.push(document.documentElement.scrollHeight);
        }, 200);
        setTimeout(() => {
          clearInterval(h);
          resolve(relevé);
        }, 10000);
      }),
  );
  for (const [cle, valeurs] of Object.entries(largeurs)) {
    expect(new Set(valeurs.map((v) => Math.round(v))).size, `${cle} change de taille pendant les rafraîchissements`).toBe(1);
  }

  // Message avec libellé + contraste AA du texte principal
  const contrastes = await page.evaluate(() => {
    const fondEffectif = (el: Element): string => {
      let e: Element | null = el;
      while (e) {
        const c = getComputedStyle(e).backgroundColor;
        if (c && c !== 'rgba(0, 0, 0, 0)' && c !== 'transparent') return c;
        e = e.parentElement;
      }
      return 'rgb(255, 255, 255)';
    };
    return ['suivi-nom', 'suivi-boucle', 'suivi-compte-a-rebours', 'suivi-libelle-compte-a-rebours', 'suivi-statut'].map((i) => {
      const e = document.querySelector(`[data-testid="${i}"]`) as HTMLElement;
      return { id: i, couleur: getComputedStyle(e).color, fond: fondEffectif(e), taille: parseFloat(getComputedStyle(e).fontSize) };
    });
  });
  for (const c of contrastes) {
    const l1 = luminanceRelative(rgb(c.couleur));
    const l2 = luminanceRelative(rgb(c.fond));
    const ratio = (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    expect(ratio, `contraste ${c.id} (${c.couleur} sur ${c.fond})`).toBeGreaterThanOrEqual(4.5);
  }

  // prefers-reduced-motion : aucune animation
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const animations = await page.evaluate(() =>
    [...document.querySelectorAll('main, main *')]
      .map((e) => getComputedStyle(e))
      .filter((s) => s.animationName !== 'none' && parseFloat(s.animationDuration) > 0.001).length,
  );
  expect(animations).toBe(0);

  // Messages : libellé en plus de la couleur (course non démarrée et message d'état)
  await page.route(MOTIF_API_SUIVI, (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: reponseSuivi({ boucleCourante: 5, derniereBoucle: true, boucleMaxTerminee: true, tempsAvantFinBoucleMillisecondes: 0, dureeEcouleeMillisecondes: 18_060_000 }),
    }),
  );
  await page.goto(urlSuivi(id));
  await expect(lire(page, 'suivi-message-boucle-max-terminee')).toContainText('Le temps de la dernière boucle est écoulé');
  await page.unroute(MOTIF_API_SUIVI);

  // Aucune couleur en dur dans les styles ajoutés (sources du front)
  for (const fichier of ['suivi/suivi-course/suivi-course.css', 'partage/chronometrage/chronometrage.css']) {
    const css = readFileSync(join(__dirname, '..', '..', 'frontend', 'src', 'app', fichier), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    expect(css, `couleur en dur dans ${fichier}`).not.toMatch(/#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/);
    expect(css, `taille en dur (px) hors bordure dans ${fichier}`).not.toMatch(/font-size:\s*\d+(\.\d+)?px/);
  }
});
