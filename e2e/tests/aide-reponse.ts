import type { Page, Request } from '@playwright/test';

export interface ReponseCapturee {
  status: number;
  corps: string;
}

/**
 * Capture la prochaine réponse correspondant au filtre en lisant son corps AVANT de la transmettre à la page.
 * Lire `response.text()` après coup est instable : la page peut avoir navigué entre-temps et le corps n'est
 * alors plus disponible. À appeler (et attendre) avant l'action qui déclenche la requête, puis `await capture.attendre` après l'action.
 */
export async function capturerReponse(page: Page, correspond: (requete: Request) => boolean): Promise<{ attendre: Promise<ReponseCapturee> }> {
  let resoudre!: (r: ReponseCapturee) => void;
  const capturee = new Promise<ReponseCapturee>((res) => (resoudre = res));
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      if (!correspond(route.request())) {
        await route.fallback();
        return;
      }
      const reponse = await route.fetch();
      resoudre({ status: reponse.status(), corps: await reponse.text() });
      await route.fulfill({ response: reponse });
    },
  );
  // Enveloppée dans un objet : une promesse retournée par une fonction async serait attendue (aplatie) par l'appelant.
  return { attendre: capturee };
}
