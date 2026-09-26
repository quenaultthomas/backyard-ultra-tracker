/**
 * Chemins de l'API (E1 à E19) et règle d'envoi de l'en-tête Authorization (RG8) : uniquement vers
 * {@code /api/scan/**} et {@code /api/admin/**} de l'origine de l'application, jamais vers {@code /api/public/**}
 * ni vers une autre origine.
 */

const PROTECTED_PATH = /^\/api\/(scan|admin)(\/|$)/;

/** Vrai si l'en-tête Authorization doit accompagner une requête vers ce chemin relatif. */
export function requiresAuthorization(path: string): boolean {
  return path.startsWith('/') && !path.startsWith('//') && PROTECTED_PATH.test(path);
}

export const API_PATHS = {
  races: '/api/public/races',
  race: (raceId: number | string) => `/api/public/races/${encodeURIComponent(String(raceId))}`,
  registrations: (raceId: number | string) =>
    `/api/public/races/${encodeURIComponent(String(raceId))}/registrations`,
  board: (raceId: number | string) => `/api/public/races/${encodeURIComponent(String(raceId))}/board`,
  runner: (runnerId: number | string) => `/api/public/runners/${encodeURIComponent(String(runnerId))}`,
  scan: '/api/scan/passages',
  session: '/api/scan/me',
  adminRaces: '/api/admin/races',
  adminRace: (raceId: number | string) => `/api/admin/races/${encodeURIComponent(String(raceId))}`,
  adminStart: (raceId: number | string) => `/api/admin/races/${encodeURIComponent(String(raceId))}/start`,
  adminRaceRunners: (raceId: number | string) =>
    `/api/admin/races/${encodeURIComponent(String(raceId))}/runners`,
  adminRunner: (runnerId: number | string) => `/api/admin/runners/${encodeURIComponent(String(runnerId))}`,
  adminDnf: (runnerId: number | string) => `/api/admin/runners/${encodeURIComponent(String(runnerId))}/dnf`,
  adminReintegration: (runnerId: number | string) =>
    `/api/admin/runners/${encodeURIComponent(String(runnerId))}/reintegration`,
} as const;
