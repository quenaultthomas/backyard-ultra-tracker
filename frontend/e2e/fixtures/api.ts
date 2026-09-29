/**
 * Client HTTP minimal vers le backend réel (E1 à E19), utilisé pour créer les données de test (RG58 : « les
 * données sont créées par les endpoints réels ») et pour vérifier « côté serveur » sans passer par la page
 * (RG57.4 : « la vérification côté serveur se fait par l'API, depuis le test et non depuis la page »).
 * N'implémente aucune règle métier : appels HTTP et lecture de réponses seulement.
 */

export const ADMIN_USERNAME = 'admin-test';
export const ADMIN_PASSWORD = 'admin-secret';
export const SCANNER_USERNAME = 'scanner-test';
export const SCANNER_PASSWORD = 'scanner-secret';

export const ADMIN_AUTH = basicAuth(ADMIN_USERNAME, ADMIN_PASSWORD);
export const SCANNER_AUTH = basicAuth(SCANNER_USERNAME, SCANNER_PASSWORD);

export function basicAuth(username: string, password: string): string {
  return `Basic ${Buffer.from(`${username}:${password}`, 'utf-8').toString('base64')}`;
}

/** Identifiant unique par exécution (RG58), pour des noms de course sans collision entre exécutions. */
export function uniqueRun(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`;
}

export interface RaceResponse {
  readonly id: number;
  readonly name: string;
  readonly raceDate: string;
  readonly status: 'SETUP' | 'RUNNING' | 'FINISHED';
  readonly startedAt: string | null;
  readonly loopDistance: number;
  readonly loopDuration: number;
  readonly loopElevation: number;
  readonly registrationOpen: boolean;
}

export interface RegistrationResponse {
  readonly runnerId: number;
  readonly raceId: number;
  readonly bib: number;
  /** Nom affiché (RG18 inc. 5) : le pseudo stocké en minuscules, ou « Coureur n°{bib} » sans compte. */
  readonly name: string;
  readonly pseudo: string | null;
  readonly qrToken: string;
}

/** Inscription faite par la fixture : la réponse d'E3 et le mot de passe du compte créé (jeu de données). */
export interface RegisteredRunner extends RegistrationResponse {
  readonly password: string;
}

export interface AccountSummary {
  readonly accountId: number;
  readonly pseudo: string;
  readonly runnerCount: number;
}

/** Mot de passe des comptes créés par la fixture (12 octets, RG3 inc. 5). */
export const DEFAULT_RUNNER_PASSWORD = 'motdepasse-1';

export interface AdminRunnerResponse {
  readonly id: number;
  readonly raceId: number;
  readonly bib: number;
  readonly name: string;
  readonly accountId: number | null;
  readonly pseudo: string | null;
  readonly qrToken: string;
  readonly status: 'ACTIVE' | 'DNF' | 'WINNER';
  readonly dnfReason: string | null;
  readonly dnfYard: number | null;
}

export interface RunnerBoardEntry {
  readonly runnerId: number;
  readonly bib: number;
  readonly name: string;
  readonly status: 'ACTIVE' | 'DNF' | 'WINNER';
  readonly dnfReason: string | null;
  readonly dnfYard: number | null;
  readonly completedLoops: number;
  readonly distanceMeters: number;
  readonly elevationMeters: number;
  readonly averagePaceSecondsPerKm: number | null;
  readonly corrected: boolean;
}

export interface RaceBoardResponse {
  readonly race: RaceResponse;
  readonly serverTime: string;
  readonly currentYard: number;
  readonly currentYardEndsAt: string | null;
  readonly runners: readonly RunnerBoardEntry[];
}

export interface RunnerDetailResponse extends RunnerBoardEntry {
  readonly raceId: number;
  readonly passages: readonly {
    readonly yardNumber: number;
    readonly source: 'SCAN' | 'MANUAL';
    readonly scannedAt: string | null;
    readonly loopTimeMillis: number | null;
    readonly corrected: boolean;
  }[];
}

export class Api {
  constructor(private readonly baseUrl: string) {}

  private url(path: string): string {
    return `${this.baseUrl}${path}`;
  }

  /** E7 : création d'une course (admin). loopDuration par défaut 3600 s (aucune cloche pendant le test). */
  async createRace(input: {
    readonly name: string;
    readonly raceDate?: string;
    readonly loopDistance: number;
    readonly loopDuration: number;
    readonly loopElevation: number;
  }): Promise<RaceResponse> {
    const response = await fetch(this.url('/api/admin/races'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: ADMIN_AUTH },
      body: JSON.stringify({
        name: input.name,
        raceDate: input.raceDate ?? '2026-10-03',
        loopDistance: input.loopDistance,
        loopDuration: input.loopDuration,
        loopElevation: input.loopElevation,
      }),
    });
    return expectOk(response);
  }

  /**
   * E3 : inscription publique avec création d'un compte pseudo (RG7 inc. 5). Le pseudo est dérivé de `hint`
   * (minuscules, sans accent ni espace) et suffixé d'un identifiant aléatoire, car un pseudo est unique
   * globalement (RG5) et la base du backend est partagée entre les exécutions. Les tests lisent le nom affiché
   * dans la réponse (`name`), jamais le libellé de `hint`.
   */
  async register(raceId: number, hint: string): Promise<RegisteredRunner> {
    const pseudo = pseudoFrom(hint);
    const registration = await this.registerAccount(raceId, pseudo, DEFAULT_RUNNER_PASSWORD);
    if (registration.name !== pseudo || registration.pseudo !== pseudo) {
      throw new Error(`RG18 inc. 5 : le nom affiché devrait être le pseudo ${pseudo}, obtenu ${registration.name}`);
    }
    return { ...registration, password: DEFAULT_RUNNER_PASSWORD };
  }

  /** E3 avec un pseudo et un mot de passe exacts (corps `{"pseudo","password"}`). */
  async registerAccount(raceId: number, pseudo: string, password: string): Promise<RegistrationResponse> {
    const response = await fetch(this.url(`/api/public/races/${raceId}/registrations`), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ pseudo, password }),
    });
    return expectOk(response);
  }

  /** E20 : inscription d'un compte existant à une autre course (HTTP Basic du compte coureur). */
  async registerExistingAccount(raceId: number, pseudo: string, password: string): Promise<RegistrationResponse> {
    const response = await fetch(this.url(`/api/account/races/${raceId}/registrations`), {
      method: 'POST',
      headers: { Authorization: basicAuth(pseudo, password) },
    });
    return expectOk(response);
  }

  /** E21 : statut HTTP de « Mes inscriptions » pour des identifiants donnés (200 valides, 401 sinon). */
  async accountMeStatus(pseudo: string, password: string): Promise<number> {
    const response = await fetch(this.url('/api/account/me'), {
      headers: { Authorization: basicAuth(pseudo, password) },
    });
    return response.status;
  }

  /** E25 : liste des comptes (admin), avec filtre facultatif. */
  async adminAccounts(pseudo?: string): Promise<AccountSummary[]> {
    const query = pseudo === undefined ? '' : `?pseudo=${encodeURIComponent(pseudo)}`;
    const response = await fetch(this.url(`/api/admin/accounts${query}`), {
      headers: { Authorization: ADMIN_AUTH },
    });
    return expectOk(response);
  }

  /** E16 : suppression d'un coureur (admin, course en SETUP), pour préparer des données. */
  async deleteRunner(runnerId: number): Promise<void> {
    const response = await fetch(this.url(`/api/admin/runners/${runnerId}`), {
      method: 'DELETE',
      headers: { Authorization: ADMIN_AUTH },
    });
    if (!response.ok) {
      throw new Error(`${response.status} sur DELETE runner ${runnerId} : ${await response.text()}`);
    }
  }

  /** E12 : démarrage (admin). */
  async startRace(raceId: number): Promise<RaceResponse> {
    const response = await fetch(this.url(`/api/admin/races/${raceId}/start`), {
      method: 'POST',
      headers: { Authorization: ADMIN_AUTH },
    });
    return expectOk(response);
  }

  /** E2 : détail public d'une course. */
  async race(raceId: number): Promise<RaceResponse> {
    const response = await fetch(this.url(`/api/public/races/${raceId}`));
    return expectOk(response);
  }

  /** E13 : liste admin des coureurs d'une course (avec qrToken). */
  async adminRunners(raceId: number): Promise<AdminRunnerResponse[]> {
    const response = await fetch(this.url(`/api/admin/races/${raceId}/runners`), {
      headers: { Authorization: ADMIN_AUTH },
    });
    return expectOk(response);
  }

  /**
   * E6 : scan (utilisé uniquement pour préparer des données de test hors du parcours sous test, ex. CA32,
   * CA37, où c'est le tableau de bord ou l'administration qui est vérifié, pas l'écran de scan lui-même,
   * déjà couvert par CA27 à CA31).
   */
  async scan(qrToken: string, scannedAt?: string): Promise<Response> {
    return fetch(this.url('/api/scan/passages'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: SCANNER_AUTH },
      body: JSON.stringify({ qrToken, scannedAt: scannedAt ?? new Date().toISOString() }),
    });
  }

  /** E4 : tableau de bord public. */
  async board(raceId: number): Promise<RaceBoardResponse> {
    const response = await fetch(this.url(`/api/public/races/${raceId}/board`));
    return expectOk(response);
  }

  /** E5 : détail public d'un coureur. */
  async runnerDetail(runnerId: number): Promise<RunnerDetailResponse> {
    const response = await fetch(this.url(`/api/public/runners/${runnerId}`));
    return expectOk(response);
  }

  /** E18 : réintégration admin (préparation de données hors du parcours admin lui-même, cf. CA35 dédié). */
  async reintegrate(runnerId: number): Promise<unknown> {
    const response = await fetch(this.url(`/api/admin/runners/${runnerId}/reintegration`), {
      method: 'POST',
      headers: { Authorization: ADMIN_AUTH },
    });
    return expectOk(response);
  }

  /** Attend jusqu'à ce que la course atteigne le statut donné (poll direct de l'API, pas de la page). */
  async waitForRaceStatus(raceId: number, status: 'SETUP' | 'RUNNING' | 'FINISHED', timeoutMs = 15_000):
    Promise<RaceBoardResponse> {
    const deadline = Date.now() + timeoutMs;
    let last: RaceBoardResponse | null = null;
    while (Date.now() < deadline) {
      last = await this.board(raceId);
      if (last.race.status === status) {
        return last;
      }
      await sleep(300);
    }
    throw new Error(`Timeout en attendant le statut ${status} de la course ${raceId} (dernier vu : `
      + `${last?.race.status})`);
  }

  /** Attend qu'un coureur atteigne un statut donné, vu par l'API (E4). */
  async waitForRunnerStatus(raceId: number, runnerId: number, status: 'ACTIVE' | 'DNF' | 'WINNER',
                            timeoutMs = 15_000): Promise<RunnerBoardEntry> {
    const deadline = Date.now() + timeoutMs;
    let lastStatus: string | undefined;
    while (Date.now() < deadline) {
      const board = await this.board(raceId);
      const runner = board.runners.find((entry) => entry.runnerId === runnerId);
      lastStatus = runner?.status;
      if (runner?.status === status) {
        return runner;
      }
      await sleep(300);
    }
    throw new Error(`Timeout en attendant le statut ${status} du coureur ${runnerId} (dernier vu : ${lastStatus})`);
  }
}

/** Pseudo valide (RG2 : `[a-z0-9_-]{3,30}` après normalisation) et unique, dérivé d'un libellé libre. */
function pseudoFrom(hint: string): string {
  const slug = hint.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 16) || 'coureur';
  return `${slug}-${Math.random().toString(36).slice(2, 8)}`;
}

async function expectOk<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const body = await response.text();
    throw new Error(`${response.status} ${response.statusText} sur ${response.url} : ${body}`);
  }
  return (await response.json()) as T;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
