const baseUrl = process.env.BASE_URL ?? 'http://localhost';
const MESSAGE_STACK_E2E =
  'Stack e2e requise : lancer `docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build`';


function cookiesDe(reponse: Response): string[] {
  return reponse.headers.getSetCookie().map((c) => c.split(';')[0]);
}

function fusionner(actuels: Map<string, string>, reponse: Response): void {
  for (const c of cookiesDe(reponse)) {
    const i = c.indexOf('=');
    actuels.set(c.slice(0, i), c.slice(i + 1));
  }
}

/**
 * Toute la suite tourne sur la stack e2e (RG11) : une session ADMIN_MASTER doit pouvoir lire
 * GET /api/test/horloge (404 = mauvaise stack). L'horloge est remise à zéro avant tous les tests.
 */
export default async function globalSetup(): Promise<void> {
  const message = `Stack injoignable sur ${baseUrl} : lancer \`docker compose -f docker-compose.yml -f docker-compose.e2e.yml up -d --build\``;
  let reponse: Response;
  try {
    reponse = await fetch(`${baseUrl}/api/sante`, { signal: AbortSignal.timeout(5000) });
  } catch (erreur) {
    throw new Error(`${message} (${erreur instanceof Error ? erreur.message : String(erreur)})`);
  }
  if (reponse.status !== 200) {
    throw new Error(`${message} (GET /api/sante a répondu ${reponse.status})`);
  }

  const pseudo = process.env.E2E_ADMIN_MASTER_PSEUDO;
  const motDePasse = process.env.E2E_ADMIN_MASTER_MOT_DE_PASSE;
  if (!pseudo || !motDePasse) {
    throw new Error('E2E_ADMIN_MASTER_PSEUDO et E2E_ADMIN_MASTER_MOT_DE_PASSE doivent être renseignées (mêmes valeurs que la stack).');
  }

  const cookies = new Map<string, string>();
  const entete = () => [...cookies].map(([k, v]) => `${k}=${v}`).join('; ');
  const csrf = await fetch(`${baseUrl}/api/csrf`);
  fusionner(cookies, csrf);
  const connexion = await fetch(`${baseUrl}/api/connexion`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Cookie: entete(), 'X-XSRF-TOKEN': cookies.get('XSRF-TOKEN') ?? '' },
    body: JSON.stringify({ pseudo, motDePasse }),
  });
  if (connexion.status !== 200) {
    throw new Error(`Connexion de l'admin master impossible (${connexion.status}) : vérifier E2E_ADMIN_MASTER_*.`);
  }
  fusionner(cookies, connexion);
  fusionner(cookies, await fetch(`${baseUrl}/api/csrf`, { headers: { Cookie: entete() } }));

  const lecture = await fetch(`${baseUrl}/api/test/horloge`, { headers: { Cookie: entete() } });
  if (lecture.status !== 200) {
    throw new Error(MESSAGE_STACK_E2E);
  }
  const remise = await fetch(`${baseUrl}/api/test/horloge`, {
    method: 'DELETE',
    headers: { Cookie: entete(), 'X-XSRF-TOKEN': cookies.get('XSRF-TOKEN') ?? '' },
  });
  if (remise.status !== 200) {
    throw new Error(`Remise à zéro de l'horloge impossible (${remise.status}).`);
  }
}
