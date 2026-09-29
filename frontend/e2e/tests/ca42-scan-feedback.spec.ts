import { expect, test } from '@playwright/test';
import { Api, uniqueRun } from '../fixtures/api';

declare global {
  interface Window {
    __beeps: number[];
    __vibrations: readonly number[][];
  }
}

/**
 * CA42 — Feedback du scan (RG50, RG26). Le son et la vibration sont instrumentés par un script d'initialisation
 * qui remplace `AudioContext` et `navigator.vibrate` par des enregistreurs, seul moyen fiable d'observer ces
 * API sans matériel réel (RG57.6, l'app n'expose aucun crochet de test dédié, RG56).
 */
test.describe('@INC-4 @INC4-CA42 Feedback du scan', () => {
  test.use({ serviceWorkers: 'block' });

  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      window.__beeps = [];
      window.__vibrations = [];
      Object.defineProperty(navigator, 'vibrate', {
        configurable: true,
        value: (pattern: number[]) => {
          window.__vibrations = [...window.__vibrations, pattern];
          return true;
        },
      });
      class FakeAudioParam {
        value = 0;
      }
      class FakeOscillator {
        frequency = new FakeAudioParam();
        connect(): FakeOscillator {
          return this;
        }
        start(): void {
          window.__beeps = [...window.__beeps, this.frequency.value];
        }
        stop(): void {
          /* no-op */
        }
      }
      class FakeGain {
        gain = new FakeAudioParam();
        connect(): FakeGain {
          return this;
        }
      }
      class FakeAudioContext {
        currentTime = 0;
        destination = {};
        createOscillator(): FakeOscillator {
          return new FakeOscillator();
        }
        createGain(): FakeGain {
          return new FakeGain();
        }
        resume(): Promise<void> {
          return Promise.resolve();
        }
      }
      (window as unknown as { AudioContext: unknown }).AudioContext = FakeAudioContext;
    });
  });

  async function resetRecorders(page: import('@playwright/test').Page): Promise<void> {
    await page.evaluate(() => {
      window.__beeps = [];
      window.__vibrations = [];
    });
  }

  test('capture puis acceptation en ligne : 1 puis 2 bips, vibrations, bandeaux', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-FEEDBACK-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    const runner = await api.register(race.id, 'Runner Feedback');
    await api.startRace(race.id);

    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

    await resetRecorders(page);
    await page.getByLabel('Code du QR').fill(runner.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByText(`Dossard ${runner.bib} — ${runner.name}`, { exact: false }))
      .toBeVisible({ timeout: 10_000 });

    const beeps = await page.evaluate(() => window.__beeps);
    const vibrations = await page.evaluate(() => window.__vibrations);
    // Comptes exacts (RG50) : 1 bip à la capture (880 Hz) puis 2 à l'acceptation (1320 Hz) — exactement 3, pas
    // « au moins » — et exactement 2 vibrations (50 ms à la capture, 2×50 ms à l'acceptation).
    expect(beeps).toEqual([880, 1320, 1320]);
    expect(vibrations).toEqual([[50], [50, 50, 50]]);
    await expect(page.locator('.scan-result')).toHaveClass(/tone-success/);

    // QR non reconnu : exactement 3 bips graves (440 Hz), une seule vibration [200,100,200], bandeau rouge.
    await resetRecorders(page);
    await page.getByLabel('Code du QR').fill('bonjour');
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByText('QR non reconnu')).toBeVisible();
    const vibrationsAfterInvalid = await page.evaluate(() => window.__vibrations);
    const beepsAfterInvalid = await page.evaluate(() => window.__beeps);
    expect(beepsAfterInvalid).toEqual([440, 440, 440]);
    expect(vibrationsAfterInvalid).toEqual([[200, 100, 200]]);
    await expect(page.locator('.scan-result')).toHaveClass(/tone-error/);
  });

  /**
   * 4e point de CA42 : un scan capturé hors ligne, accepté plus de 5 s après sa capture, ne déclenche ni son
   * ni vibration au moment de l'acceptation (RG50 : « un résultat serveur qui arrive plus de 5 s après sa
   * capture ne déclenche ni son ni vibration »). Le délai de 6 s ci-dessous applique littéralement le seuil
   * que l'énoncé du CA fixe explicitement (« plus de 5 s ») : fenêtre d'observation à durée fixée par le CA,
   * autorisée par l'amendement du 2026-09-27 de RG57.4 (comme CA26).
   */
  test('scan capturé hors ligne, accepté plus de 5 s après : aucun son ni vibration à l\'acceptation', async ({
    page, context,
  }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-FEEDBACK3-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    const runner = await api.register(race.id, 'Runner Deferred');
    await api.startRace(race.id);

    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

    await resetRecorders(page);
    await context.setOffline(true);
    await page.getByLabel('Code du QR').fill(runner.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByText('Enregistré — en attente de réseau', { exact: false })).toBeVisible();

    // Retour immédiat de la capture : exactement 1 bip et 1 vibration (celui de « Enregistré », RG50).
    const beepsAtCapture = await page.evaluate(() => window.__beeps);
    const vibrationsAtCapture = await page.evaluate(() => window.__vibrations);
    expect(beepsAtCapture).toEqual([880]);
    expect(vibrationsAtCapture).toEqual([[50]]);

    // Attente fixe de 6 s (> 5 s, seuil fixé par l'énoncé du CA lui-même) avant de rétablir le réseau : sans
    // cette attente, il n'existe aucun autre moyen d'observer que le délai entre capture et acceptation
    // dépasse bien 5 s (RG57.4 amendée).
    await page.waitForTimeout(6_000);
    await context.setOffline(false);

    await expect(page.getByText('0 en attente')).toBeVisible({ timeout: 15_000 });

    // Aucun son ni vibration supplémentaire au moment de l'acceptation, plus de 5 s après la capture.
    const beepsAfterAcceptance = await page.evaluate(() => window.__beeps);
    const vibrationsAfterAcceptance = await page.evaluate(() => window.__vibrations);
    expect(beepsAfterAcceptance).toEqual([880]);
    expect(vibrationsAfterAcceptance).toEqual([[50]]);
  });

  test('son coupé : aucun bip, réglage conservé après rechargement', async ({ page }, testInfo) => {
    const api = new Api(testInfo.project.use.baseURL as string);
    const run = uniqueRun();
    const race = await api.createRace({
      name: `E2E-FEEDBACK2-${run}`, loopDistance: 1000, loopDuration: 3600, loopElevation: 10,
    });
    const runner = await api.register(race.id, 'Runner Silence');
    await api.startRace(race.id);

    await page.goto('/scan');
    await page.getByRole('link', { name: 'Se connecter' }).click();
    await page.getByLabel("Nom d'utilisateur").fill('scanner-test');
    await page.getByLabel('Mot de passe').fill('scanner-secret');
    await page.getByRole('button', { name: 'Se connecter' }).click();
    await expect(page.getByRole('heading', { level: 1 })).toHaveText('Scan');

    await page.getByRole('button', { name: /^Son/ }).click();
    await expect(page.getByRole('button', { name: /^Son/ })).toContainText('coupé');

    await resetRecorders(page);
    await page.getByLabel('Code du QR').fill(runner.qrToken);
    await page.getByRole('button', { name: 'Valider' }).click();
    await expect(page.getByText(`Dossard ${runner.bib} — ${runner.name}`, { exact: false }))
      .toBeVisible({ timeout: 10_000 });
    const beeps = await page.evaluate(() => window.__beeps);
    expect(beeps).toEqual([]);

    await page.reload();
    await expect(page.getByRole('button', { name: /^Son/ })).toContainText('coupé');
  });
});
