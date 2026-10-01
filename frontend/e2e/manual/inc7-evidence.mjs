/**
 * Captures d'écran et inventaire de preuve de l'incrément 7 (hors suite Playwright, lecture seule) : écran de connexion
 * unique (deux entrées), écran d'inscription, en-tête anonyme et coureur connecté, 401 par entrée, et Cache Storage d'un
 * visiteur des deux nouveaux écrans (service worker actif). Usage :
 *   node manual/inc7-evidence.mjs <baseURL> <répertoireDeSortie>
 */
import { chromium } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';

const [baseURL, outDir] = process.argv.slice(2);
mkdirSync(outDir, { recursive: true });
const browser = await chromium.launch();
const context = await browser.newContext({ baseURL, serviceWorkers: 'block', viewport: { width: 900, height: 640 } });
const page = await context.newPage();
const shot = (name) => page.screenshot({ path: `${outDir}/${name}.png`, fullPage: false });
const suffix = Date.now().toString(36);

await page.goto('/connexion');
await page.getByRole('heading', { level: 1, name: 'Connexion' }).waitFor();
await shot('01-connexion-entree-coureur');
await page.getByRole('radio', { name: 'Bénévole' }).check();
await page.getByLabel("Nom d'utilisateur").waitFor();
await shot('02-connexion-entree-benevole');

await page.getByLabel("Nom d'utilisateur").fill('quelquun');
await page.getByLabel('Mot de passe').fill('motdepasse-1');
await page.getByRole('button', { name: 'Se connecter' }).click();
await page.getByText('Vérifiez le type de compte choisi').waitFor();
await shot('03-connexion-401-aide-type-de-compte');

await page.goto('/inscription');
await page.getByRole('heading', { level: 1, name: 'Créer un compte' }).waitFor();
await shot('04-inscription-autonome');

await page.getByLabel('Pseudo').fill(`preuve-${suffix}`);
await page.getByLabel('Mot de passe', { exact: true }).fill('motdepasse-9');
await page.getByLabel('Confirmer le mot de passe').fill('motdepasse-9');
await page.getByRole('button', { name: 'Créer mon compte' }).click();
await page.getByText('Aucune inscription.').waitFor();
await shot('05-compte-cree-connecte-en-tete');

await page.goto('/scan');
await page.getByRole('heading', { level: 1, name: 'Scan' }).waitFor();
await shot('06-scan-un-seul-se-connecter');

// Cache Storage d'un visiteur des deux nouveaux écrans, service worker actif : inventaire des fichiers JS en cache.
const swContext = await browser.newContext({ baseURL });
const swPage = await swContext.newPage();
await swPage.goto('/connexion');
await swPage.waitForFunction(() => navigator.serviceWorker.getRegistrations().then((r) => r.length > 0), null,
  { timeout: 90000, polling: 500 });
await swPage.reload();
let urls = [];
for (let i = 0; i < 90; i++) {
  urls = await swPage.evaluate(async () => {
    const out = [];
    for (const name of await caches.keys()) {
      out.push(...(await (await caches.open(name)).keys()).map((request) => new URL(request.url).pathname));
    }
    return out;
  });
  if (urls.filter((u) => u.endsWith('.js')).length >= 20) break;
  await new Promise((resolve) => setTimeout(resolve, 1000));
}
await swPage.goto('/inscription');
await swPage.getByRole('heading', { level: 1, name: 'Créer un compte' }).waitFor();
const js = urls.filter((u) => u.endsWith('.js')).sort();
writeFileSync(`${outDir}/07-cache-storage-ecrans-connexion-inscription.txt`,
  `${js.length} fichiers JS en cache\n${js.join('\n')}\nfichiers admin-*.js : ${js.filter((u) => /\/admin-/.test(u)).length}\n`);
await browser.close();
console.log(`${js.length} fichiers JS en cache, captures dans ${outDir}`);
