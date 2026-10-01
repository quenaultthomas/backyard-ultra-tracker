const baseUrl = process.env.BASE_URL ?? 'http://localhost';

export default async function globalSetup(): Promise<void> {
  const message = `Stack injoignable sur ${baseUrl} : lancer \`docker compose up -d --build\``;
  let reponse: Response;
  try {
    reponse = await fetch(`${baseUrl}/api/sante`, { signal: AbortSignal.timeout(5000) });
  } catch (erreur) {
    throw new Error(`${message} (${erreur instanceof Error ? erreur.message : String(erreur)})`);
  }
  if (reponse.status !== 200) {
    throw new Error(`${message} (GET /api/sante a répondu ${reponse.status})`);
  }
}
