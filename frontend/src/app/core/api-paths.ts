/**
 * Chemins de l'API (E1 à E19) et règle d'envoi de l'en-tête Authorization (RG8) : uniquement vers
 * {@code /api/scan/**} et {@code /api/admin/**} de l'origine de l'application, jamais vers {@code /api/public/**}
 * ni vers une autre origine.
 */

const PROTECTED_PATH = /^\/api\/(scan|admin)(\/|$)/;
const RUNNER_ACCOUNT_PATH = /^\/api\/account(\/|$)/;

/** Vrai si l'en-tête Authorization des comptes ADMIN/SCANNER doit accompagner une requête vers ce chemin relatif. */
export function requiresAuthorization(path: string): boolean {
  return isSameOriginPath(path) && PROTECTED_PATH.test(path);
}

/**
 * RG21 inc. 5 : vrai si les identifiants du compte coureur doivent accompagner une requête vers ce chemin relatif,
 * c'est-à-dire uniquement vers `/api/account/**` de l'origine de l'application.
 */
export function requiresRunnerAuthorization(path: string): boolean {
  return isSameOriginPath(path) && RUNNER_ACCOUNT_PATH.test(path);
}

function isSameOriginPath(path: string): boolean {
  return path.startsWith('/') && !path.startsWith('//');
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
  accountRegistrations: (raceId: number | string) =>
    `/api/account/races/${encodeURIComponent(String(raceId))}/registrations`,
  accountMe: '/api/account/me',
  accountPassword: '/api/account/password',
  adminAccounts: (pseudo?: string) =>
    pseudo === undefined ? '/api/admin/accounts' : `/api/admin/accounts?pseudo=${encodeURIComponent(pseudo)}`,
  adminAccount: (accountId: number | string) => `/api/admin/accounts/${encodeURIComponent(String(accountId))}`,
  adminAccountPassword: (accountId: number | string) =>
    `/api/admin/accounts/${encodeURIComponent(String(accountId))}/password`,
} as const;
