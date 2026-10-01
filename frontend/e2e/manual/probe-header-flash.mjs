// Sonde (inc. 7) : états successifs de la zone « compte » de l'en-tête pendant le chargement direct d'un écran.
import { chromium, webkit } from '@playwright/test';
const base = process.argv[2] ?? 'http://localhost:8080';
for (const [name, type] of [['chromium', chromium], ['webkit', webkit]]) {
  const browser = await type.launch();
  for (const path of ['/inscription', '/connexion', '/scan']) {
    const seen = new Set();
    for (let i = 0; i < 8; i++) {
      const ctx = await browser.newContext({ serviceWorkers: 'block' });
      const page = await ctx.newPage();
      await page.addInitScript(() => {
        window.__states = [];
        const read = () => {
          const h = document.querySelector('header');
          if (h) { const t = h.innerText.replace(/\s+/g, ' ').trim(); const l = window.__states; if (l.at(-1) !== t) l.push(t); }
          requestAnimationFrame(read);
        };
        requestAnimationFrame(read);
      });
      await page.goto(base + path);
      await page.getByRole('heading', { level: 1 }).waitFor();
      await page.waitForTimeout(300);
      seen.add(JSON.stringify(await page.evaluate(() => window.__states)));
      await ctx.close();
    }
    console.log(name, path, [...seen].join(' || '));
  }
  await browser.close();
}
