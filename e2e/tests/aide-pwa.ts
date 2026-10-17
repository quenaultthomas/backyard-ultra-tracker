import { expect, type BrowserContext, type Locator, type Page, type PlaywrightWorkerArgs } from '@playwright/test';
import { MOT_DE_PASSE_BENEVOLE_CREE } from './aide-admin';
import { avecApi } from './aide-horloge';
import { lireFile } from './aide-file-scan';
import { creerBenevole, ouvrirEcranDeScan, preparerCourseDeScan, seConnecterVers, type CourseDeScan } from './aide-scan';

/**
 * Aides de l'incrément 5.2 (application installable) : service worker, Cache Storage, localStorage, mise à jour,
 * mesures d'accessibilité. Les tests qui les utilisent tournent dans le projet `pwa` (service worker autorisé).
 */

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

export const CLE_BENEVOLE = 'backyard.dernier-benevole';
export const CLE_COURSES = 'backyard.courses-benevole';
export const MESSAGE_HORS_LIGNE_CONNEXION = 'Vous êtes hors ligne. Connectez-vous dès que le réseau est revenu.';
export const MESSAGE_NOUVELLE_VERSION = "Une nouvelle version de l'application est disponible.";

export const donneesHorsLigne = (page: Page) => page.getByTestId('donnees-hors-ligne');
export const connexionHorsLigne = (page: Page) => page.getByTestId('connexion-hors-ligne');
export const bandeau = (page: Page) => page.getByTestId('mise-a-jour-bandeau');

/** URLs de toutes les entrées des caches `ngsw:` (Cache Storage du service worker Angular). */
export async function urlsDuCache(page: Page): Promise<string[]> {
  return page.evaluate(async () => {
    const urls: string[] = [];
    for (const nom of await caches.keys()) {
      if (!nom.startsWith('ngsw:')) continue;
      const cache = await caches.open(nom);
      for (const requete of await cache.keys()) urls.push(requete.url);
    }
    return urls;
  });
}

export async function nomsDesCaches(page: Page): Promise<string[]> {
  return page.evaluate(() => caches.keys());
}

/**
 * Attend que le service worker soit actif et qu'il contrôle la page (rechargement), puis que la coque soit
 * entièrement précachée (index, scripts, chunks paresseux), condition de l'ouverture hors ligne.
 */
export async function attendreServiceWorkerPret(page: Page): Promise<void> {
  await page.evaluate(() => navigator.serviceWorker.ready.then(() => undefined));
  await expect.poll(() => urlsDuCache(page), { timeout: 30_000 }).toEqual(
    expect.arrayContaining([expect.stringMatching(/\/index\.html$/), expect.stringMatching(/\/main-[^/]+\.js$/), expect.stringMatching(/\/chunk-[^/]+\.js$/)]),
  );
  await page.reload();
  await expect.poll(() => page.evaluate(() => navigator.serviceWorker.controller !== null)).toBe(true);
}

/** Compte bénévole affecté à une Course de 60 min démarrée ; connecté sur l'écran de scan, service worker prêt. */
export async function ouvrirScanAvecServiceWorker(
  page: Page,
  playwright: PlaywrightLib,
  options: { nombreCoureurs?: number } = {},
): Promise<CourseDeScan> {
  const course = await preparerCourseDeScan(playwright, { nombreCoureurs: options.nombreCoureurs ?? 1 });
  await ouvrirEcranDeScan(page, course);
  await attendreServiceWorkerPret(page);
  await expect(page.getByTestId('scan-champ-jeton')).toBeVisible();
  return course;
}

/** Visite l'accueil bénévole en ligne : la liste des Courses est mémorisée sur l'appareil. */
export async function memoriserLaListe(page: Page, nomCourse: string): Promise<void> {
  await page.goto('/benevole');
  await expect(page.getByTestId('benevole-course-nom').filter({ hasText: nomCourse })).toBeVisible();
  await expect.poll(async () => Object.keys(await lireLocalStorage(page))).toContain(CLE_COURSES);
}

export async function lireLocalStorage(page: Page): Promise<Record<string, string>> {
  return page.evaluate(() => ({ ...localStorage }));
}

export async function lireStockagesNavigateur(page: Page): Promise<{ local: Record<string, string>; session: Record<string, string>; cache: string }> {
  return page.evaluate(async () => {
    const cache: string[] = [];
    for (const nom of await caches.keys()) {
      const ouvert = await caches.open(nom);
      for (const requete of await ouvert.keys()) {
        cache.push(requete.url);
        const reponse = await ouvert.match(requete);
        const type = reponse?.headers.get('content-type') ?? '';
        if (reponse && /json|text|javascript/.test(type) && !/\/main-|\/chunk-|polyfills/.test(requete.url)) cache.push(await reponse.clone().text());
      }
    }
    return { local: { ...localStorage }, session: { ...sessionStorage }, cache: cache.join('\n') };
  });
}

/** Nom de la Course affiché hors ligne, dans l'accueil bénévole ou le titre de l'écran de scan. */
export async function reconnecterLeBenevole(page: Page, pseudo: string, retour: string): Promise<void> {
  await seConnecterVers(page, pseudo, MOT_DE_PASSE_BENEVOLE_CREE, retour);
}

export function retourDe(page: Page): string | null {
  return new URL(page.url()).searchParams.get('retour');
}

/** Cookie de session serveur (HttpOnly) supprimé : le serveur ne reconnaît plus le navigateur. */
export async function invaliderLaSessionServeur(contexte: BrowserContext): Promise<void> {
  const cookies = await contexte.cookies();
  const session = cookies.filter((c) => c.httpOnly);
  expect(session.length, 'cookie de session HttpOnly présent').toBeGreaterThan(0);
  for (const cookie of session) await contexte.clearCookies({ name: cookie.name, domain: cookie.domain });
}

/**
 * Simule un nouveau déploiement : toute requête de `ngsw.json` reçoit le manifeste réel avec un `timestamp`
 * modifié (donc un autre hachage, donc une « nouvelle version » pour le service worker).
 */
export async function simulerNouvelleVersion(contexte: BrowserContext): Promise<void> {
  await contexte.route('**/ngsw.json*', async (route) => {
    const reponse = await route.fetch();
    const manifeste = (await reponse.json()) as { timestamp: number };
    manifeste.timestamp += 1;
    await route.fulfill({ response: reponse, json: manifeste });
  });
}

/**
 * Prépare le scénario de mise à jour : Léo sur l'écran de scan, service worker prêt, nouvelle version simulée,
 * rechargement (la vérification de démarrage la détecte) et bandeau affiché. Pose un marqueur dans `window`.
 */
export async function afficherLeBandeauDeMiseAJour(page: Page, playwright: PlaywrightLib): Promise<CourseDeScan> {
  const course = await ouvrirScanAvecServiceWorker(page, playwright);
  await simulerNouvelleVersion(page.context());
  await page.reload();
  await page.evaluate(() => ((window as unknown as { __marqueur: string }).__marqueur = 'present'));
  await expect(bandeau(page)).toBeVisible({ timeout: 30_000 });
  return course;
}

export const marqueur = (page: Page) => page.evaluate(() => (window as unknown as { __marqueur?: string }).__marqueur ?? null);

export async function nombreEnFile(page: Page): Promise<number> {
  return (await lireFile(page)).length;
}

/** `creerBenevole` + course affectée sans coureur (bénévole d'un autre jeu de Courses). */
export async function creerCourseAutreBenevole(playwright: PlaywrightLib): Promise<CourseDeScan> {
  const pseudo = await creerBenevole(playwright);
  return preparerCourseDeScan(playwright, { nombreCoureurs: 1, benevole: pseudo });
}

export async function lireCompte(page: Page): Promise<unknown> {
  return page.evaluate(async () => (await fetch('/api/comptes/moi')).json());
}

/** Lit un PNG par l'API de requête (IHDR et pixels des coins). */
export interface InfoPng {
  largeur: number;
  hauteur: number;
  typeCouleur: number;
  coinsOpaques: boolean;
}

export function analyserPng(octets: Buffer, decoder: (b: Buffer) => { data: Buffer; width: number; height: number }): InfoPng {
  expect(octets.subarray(1, 4).toString('latin1')).toBe('PNG');
  const largeur = octets.readUInt32BE(16);
  const hauteur = octets.readUInt32BE(20);
  const typeCouleur = octets[25];
  const image = decoder(octets);
  const alphaA = (x: number, y: number) => image.data[(y * image.width + x) * 4 + 3];
  const coinsOpaques = [alphaA(0, 0), alphaA(image.width - 1, 0), alphaA(0, image.height - 1), alphaA(image.width - 1, image.height - 1)].every((a) => a === 255);
  return { largeur, hauteur, typeCouleur, coinsOpaques };
}

/* ---------------------------------------------------------------- accessibilité (CA12) */

export async function pasDeDefilementHorizontal(page: Page): Promise<void> {
  const debordement = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(debordement, 'défilement horizontal').toBeLessThanOrEqual(0);
}

export async function atteindreAuClavier(page: Page, cible: Locator): Promise<void> {
  for (let i = 0; i < 40; i++) {
    if (await cible.evaluate((el) => el === document.activeElement)) return;
    await page.keyboard.press('Tab');
  }
  expect(await cible.evaluate((el) => el === document.activeElement), 'élément atteint par Tab').toBe(true);
}

export async function focusVisible(cible: Locator): Promise<void> {
  const s = await cible.evaluate((el) => {
    const c = getComputedStyle(el);
    return { style: c.outlineStyle, largeur: c.outlineWidth, ombre: c.boxShadow };
  });
  expect((s.style !== 'none' && parseFloat(s.largeur) > 0) || s.ombre !== 'none', 'contour de focus visible').toBe(true);
}

/** Pictogramme (pseudo-élément `::before` avec contenu et masque) : l'information ne passe pas par la seule couleur. */
export async function aUnPictogramme(cible: Locator): Promise<boolean> {
  return cible.evaluate((el) => {
    const s = getComputedStyle(el, '::before');
    const masque = s.getPropertyValue('mask-image') || s.getPropertyValue('-webkit-mask-image');
    return s.content !== 'none' && s.content !== 'normal' && ((masque !== '' && masque !== 'none') || parseFloat(s.width) > 0);
  });
}

export function contraste(a: string, b: string): number {
  const luminance = (css: string) => {
    const [r, g, bl] = (css.match(/[\d.]+/g) ?? [])
      .slice(0, 3)
      .map(Number)
      .map((v) => {
        const c = v / 255;
        return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
      });
    return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
  };
  const [clair, sombre] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (clair + 0.05) / (sombre + 0.05);
}

/** Couleur d'arrière-plan effective : premier ancêtre dont le fond n'est pas transparent. */
export async function couleurs(cible: Locator): Promise<{ texte: string; fond: string }> {
  return cible.evaluate((el) => {
    let courant: Element | null = el;
    let fond = 'rgba(0, 0, 0, 0)';
    while (courant) {
      fond = getComputedStyle(courant).backgroundColor;
      if (fond !== 'rgba(0, 0, 0, 0)' && fond !== 'transparent') break;
      courant = courant.parentElement;
    }
    return { texte: getComputedStyle(el).color, fond };
  });
}

/** Aucune animation ni transition sur l'élément et ses descendants. */
export async function sansAnimation(cible: Locator): Promise<string[]> {
  return cible.evaluate((racine) =>
    [racine, ...racine.querySelectorAll('*')].flatMap((el) => {
      const s = getComputedStyle(el);
      const durees = [...s.animationDuration.split(','), ...s.transitionDuration.split(',')];
      return s.animationName !== 'none' || durees.some((d) => parseFloat(d) > 0.001) || el.getAnimations().length > 0 ? [el.tagName + '.' + el.className] : [];
    }),
  );
}

/**
 * Valeurs de couleur écrites en dur dans les règles CSS qui s'appliquent à l'élément et à ses descendants
 * (hors `var(...)`) : doit rester vide, les couleurs viennent des jetons de la charte.
 */
export async function couleursEnDur(cible: Locator): Promise<string[]> {
  return cible.evaluate((racine) => {
    const elements = [racine, ...racine.querySelectorAll('*')];
    const trouvees = new Set<string>();
    const examiner = (regles: CSSRuleList) => {
      for (const regle of Array.from(regles)) {
        if (regle instanceof CSSStyleRule) {
          let concerne = false;
          try {
            concerne = elements.some((el) => el.matches(regle.selectorText));
          } catch {
            concerne = false;
          }
          if (!concerne) continue;
          for (let i = 0; i < regle.style.length; i++) {
            const propriete = regle.style[i];
            if (propriete.startsWith('--')) continue;
            const valeur = regle.style.getPropertyValue(propriete).replace(/var\([^)]*\)/g, '');
            const couleur = valeur.match(/#[0-9a-f]{3,8}\b|\b(?:rgba?|hsla?|oklch|oklab|color)\(/i);
            if (couleur) trouvees.add(`${regle.selectorText} { ${propriete}: ${regle.style.getPropertyValue(propriete)} }`);
          }
        } else if ('cssRules' in regle) {
          examiner((regle as CSSGroupingRule).cssRules);
        }
      }
    };
    for (const feuille of Array.from(document.styleSheets)) {
      try {
        examiner(feuille.cssRules);
      } catch {
        // Feuille d'une autre origine : non lisible.
      }
    }
    return [...trouvees];
  });
}

export { avecApi };

/** Vérifie que la valeur lue ne change pas pendant `millisecondes` (absence d'événement), sans pause fixe de test. */
export async function resteInchange<T>(page: Page, lire: () => Promise<T>, attendue: T, millisecondes = 5000): Promise<void> {
  const fin = Date.now() + millisecondes;
  while (Date.now() < fin) {
    expect(await lire()).toEqual(attendue);
    await page.evaluate(() => new Promise<void>((resolve) => setTimeout(resolve, 250)));
  }
}
