/**
 * Vérification manuelle outillée de CL5 (inc. 6) : mise à jour d'une ancienne version de la PWA (RG46 inc. 4).
 * Hors suite Playwright (le test exige deux builds successifs servis sur le même port). Usage :
 *
 *   node manual/cl5-sw-update.mjs phase1 <chromium|webkit> <profil> <baseURL>   (serveur = ancienne version, commit de départ)
 *   ... arrêt de l'ancien backend, démarrage du backend de l'incrément 6 sur le MÊME port ...
 *   node manual/cl5-sw-update.mjs phase2 <chromium|webkit> <profil> <baseURL>
 *   node manual/cl5-sw-update.mjs phase3 <chromium|webkit> <profil> <baseURL>   (rouverture : nettoyage des anciens caches)
 *
 * Le profil est un répertoire persistant : le service worker et Cache Storage de la phase 1 sont rejoués en phase 2.
 */
import { chromium, webkit } from '@playwright/test';

const [phase, browserName, profile, baseURL] = process.argv.slice(2);
const browserType = browserName === 'webkit' ? webkit : chromium;
const context = await browserType.launchPersistentContext(profile, { baseURL });
const page = context.pages()[0] ?? (await context.newPage());

async function cachedUrls() {
  return page.evaluate(async () => {
    const urls = [];
    for (const name of await caches.keys()) {
      urls.push(...(await (await caches.open(name)).keys()).map((request) => new URL(request.url).pathname));
    }
    return urls;
  });
}
async function headerLinks() {
  return page.getByLabel('Navigation principale').getByRole('link').allInnerTexts();
}

if (phase === 'phase1') {
  await page.goto('/');
  await page.waitForFunction(() => navigator.serviceWorker.getRegistrations().then((r) => r.length > 0), null, { timeout: 90000, polling: 500 });
  await page.reload();
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null, null, { timeout: 90000, polling: 500 });
  // Attend la fin du préchargement : des fichiers JS apparaissent dans Cache Storage et leur nombre se stabilise.
  let previous = -1;
  for (let i = 0; i < 120; i++) {
    const count = (await cachedUrls()).filter((u) => u.endsWith('.js')).length;
    if (count > 0 && count === previous) break;
    previous = count;
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  console.log('phase1 en-tête :', JSON.stringify(await headerLinks()));
  const urls = await cachedUrls();
  console.log('phase1 fichiers JS en cache :', urls.filter((u) => u.endsWith('.js')).length);
  console.log('phase1 liens /admin à l\'accueil :', await page.locator('a[href="/admin"]').count());
} else if (phase === 'phase3') {
  // Rouverture après la mise à jour : le service worker nettoie les caches des anciennes versions.
  await page.goto('/');
  await page.waitForLoadState('load');
  await page.waitForTimeout(15000); // observation manuelle : laisse le nettoyage s'exécuter
  console.log('phase3 en-tête :', JSON.stringify(await headerLinks()));
  console.log('phase3 caches :', JSON.stringify(await page.evaluate(() => caches.keys())));
  console.log('phase3 fichiers JS en cache :', JSON.stringify((await cachedUrls()).filter((u) => u.endsWith('.js'))));
} else {
  await page.goto('/');
  console.log('phase2 (avant mise à jour) en-tête :', JSON.stringify(await headerLinks()));
  const banner = page.getByText('Nouvelle version disponible');
  try {
    await banner.waitFor({ state: 'visible', timeout: 60000 });
    console.log('phase2 bandeau « Nouvelle version disponible » : visible');
  } catch {
    console.log('phase2 bandeau « Nouvelle version disponible » : ABSENT après 60 s');
  }
  if (await banner.isVisible()) {
    await page.getByRole('button', { name: 'Mettre à jour' }).click();
    await page.waitForLoadState('load');
  }
  await page.waitForTimeout(5000); // observation manuelle : laisse le service worker nettoyer l'ancien cache
  console.log('phase2 (après mise à jour) en-tête :', JSON.stringify(await headerLinks()));
  console.log('phase2 liens /admin :', await page.locator('a[href="/admin"]').count());
  const urls = await cachedUrls();
  console.log('phase2 fichiers JS en cache :', JSON.stringify(urls.filter((u) => u.endsWith('.js'))));
  console.log('phase2 texte « Administration » visible :', /administration/i.test(await page.evaluate(() => document.body.innerText)));
}
await context.close();
