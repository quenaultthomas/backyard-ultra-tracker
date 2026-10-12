import { expect, test as base, type APIRequestContext, type PlaywrightWorkerArgs } from '@playwright/test';
import { MOT_DE_PASSE_ADMIN_MASTER, PSEUDO_ADMIN_MASTER } from './aide-admin';

/** Réponse de GET/POST/DELETE /api/test/horloge (incrément 4.3, profil e2e). */
export interface EtatHorloge {
  maintenant: string;
  decalage: string;
  decalageMillisecondes: number;
}

const BASE_URL = process.env.BASE_URL ?? 'http://localhost';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

/** Ouvre une session `ADMIN_MASTER` dans un contexte isolé et renvoie le jeton CSRF. */
async function sessionMaster(ctx: APIRequestContext): Promise<string> {
  let csrf = await ctx.get('/api/csrf');
  expect(csrf.status()).toBe(204);
  let jeton = (await ctx.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  const connexion = await ctx.post('/api/connexion', {
    headers: { 'X-XSRF-TOKEN': jeton! },
    data: { pseudo: PSEUDO_ADMIN_MASTER, motDePasse: MOT_DE_PASSE_ADMIN_MASTER },
  });
  expect(connexion.status()).toBe(200);
  csrf = await ctx.get('/api/csrf');
  expect(csrf.status()).toBe(204);
  jeton = (await ctx.storageState()).cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  expect(jeton).toBeTruthy();
  return jeton!;
}

async function avecSessionMaster<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext, csrf: string) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx, await sessionMaster(ctx));
  } finally {
    await ctx.dispose();
  }
}

/** Lit l'état de l'horloge applicative (GET /api/test/horloge). */
export function lireHorloge(playwright: PlaywrightLib): Promise<EtatHorloge> {
  return avecSessionMaster(playwright, async (ctx) => {
    const reponse = await ctx.get('/api/test/horloge');
    expect(reponse.status()).toBe(200);
    return (await reponse.json()) as EtatHorloge;
  });
}

/** Avance l'horloge applicative d'une durée ISO-8601 (ex. `PT60M`). Renvoie l'état après avance. */
export function avancerHorloge(playwright: PlaywrightLib, duree: string): Promise<EtatHorloge> {
  return avecSessionMaster(playwright, async (ctx, csrf) => {
    const reponse = await ctx.post('/api/test/horloge/avance', { headers: { 'X-XSRF-TOKEN': csrf }, data: { duree } });
    expect(reponse.status()).toBe(200);
    return (await reponse.json()) as EtatHorloge;
  });
}

/** Remet le décalage de l'horloge applicative à zéro. Renvoie l'état après réinitialisation. */
export function reinitialiserHorloge(playwright: PlaywrightLib): Promise<EtatHorloge> {
  return avecSessionMaster(playwright, async (ctx, csrf) => {
    const reponse = await ctx.delete('/api/test/horloge', { headers: { 'X-XSRF-TOKEN': csrf } });
    expect(reponse.status()).toBe(200);
    return (await reponse.json()) as EtatHorloge;
  });
}

/** Exécute une action avec un contexte d'API isolé (une session par appel). */
export async function avecApi<T>(playwright: PlaywrightLib, action: (ctx: APIRequestContext) => Promise<T>): Promise<T> {
  const ctx = await playwright.request.newContext({ baseURL: BASE_URL });
  try {
    return await action(ctx);
  } finally {
    await ctx.dispose();
  }
}

/**
 * `test` du projet `horloge` : fixture automatique qui remet l'horloge à zéro avant chaque test (et vérifie
 * que le décalage est `PT0S`) et après chaque test, même en échec (RG11).
 */
export const test = base.extend<{ horlogeRemiseAZero: void }>({
  horlogeRemiseAZero: [
    async ({ playwright }, use) => {
      const initial = await reinitialiserHorloge(playwright);
      const verifie = await lireHorloge(playwright);
      if (initial.decalage !== 'PT0S' || verifie.decalage !== 'PT0S') {
        throw new Error('horloge non réinitialisée : tests concurrents ?');
      }
      try {
        await use();
      } finally {
        await reinitialiserHorloge(playwright);
      }
    },
    { auto: true },
  ],
});

export { expect };
