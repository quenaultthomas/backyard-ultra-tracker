/**
 * Captures d'écran de preuve de l'incrément 6 (hors suite Playwright, lecture seule) : en-tête sans « Administration »,
 * `/admin` en « Page introuvable » pour un anonyme, écran de connexion depuis `/scan`, écran admin après connexion
 * ADMIN. Usage : node manual/inc6-evidence.mjs <baseURL> <répertoireDeSortie>
 */
import { chromium } from '@playwright/test';
import { mkdirSync } from 'node:fs';

const [baseURL, outDir] = process.argv.slice(2);
mkdirSync(outDir, { recursive: true });
const browser = await chromium.launch();
const context = await browser.newContext({ baseURL, serviceWorkers: 'block', viewport: { width: 900, height: 600 } });
const page = await context.newPage();
const shot = (name) => page.screenshot({ path: `${outDir}/${name}.png`, fullPage: false });

await page.goto('/');
await page.getByRole('heading', { level: 1, name: 'Courses' }).waitFor();
await shot('01-accueil-sans-lien-administration');

await page.goto('/admin');
await page.getByRole('heading', { level: 1, name: 'Page introuvable' }).waitFor();
await shot('02-admin-anonyme-page-introuvable');

await page.goto('/scan');
await page.getByRole('link', { name: 'Se connecter' }).click();
await page.getByRole('heading', { level: 1, name: 'Connexion' }).waitFor();
await shot('03-connexion-depuis-scan-sans-mention-admin');

await page.getByLabel("Nom d'utilisateur").fill('admin-test');
await page.getByLabel('Mot de passe').fill('admin-secret');
await page.getByRole('button', { name: 'Se connecter' }).click();
await page.getByRole('heading', { level: 1, name: 'Scan' }).waitFor();
await shot('04-scan-apres-connexion-admin-sans-lien-admin');

await page.evaluate(() => {
  window.history.pushState(null, '', '/admin');
  window.dispatchEvent(new PopStateEvent('popstate', { state: null }));
});
await page.getByRole('heading', { level: 1, name: 'Administration des courses' }).waitFor();
await shot('05-admin-par-url-apres-connexion-admin');

// Cache Storage d'un visiteur anonyme avec service worker actif (inventaire des fichiers JS mis en cache).
const swContext = await browser.newContext({ baseURL });
const swPage = await swContext.newPage();
await swPage.goto('/');
await swPage.waitForFunction(() => navigator.serviceWorker.getRegistrations().then((r) => r.length > 0), null, { timeout: 90000, polling: 500 });
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
const { writeFileSync } = await import('node:fs');
writeFileSync(`${outDir}/06-cache-storage-anonyme.txt`, `${urls.sort().join('\n')}\n`);
await browser.close();
