/**
 * Identifiants du compte coureur (RG21 inc. 5), distincts de ceux des comptes ADMIN/SCANNER (credentials.ts).
 * HTTP Basic impose de renvoyer le mot de passe : la valeur `Authorization` est un secret.
 * - Par défaut, en mémoire uniquement : perdus au rechargement.
 * - « Rester connecté 24 h sur cet appareil » : enregistrés dans le stockage persistant avec une expiration
 *   glissante, égale à l'instant de la dernière activité + 24 h (horloge de l'appareil). Une activité est une
 *   requête faite avec ces identifiants qui reçoit une réponse 2xx ; la connexion en est une.
 * - Au-delà de l'expiration, les identifiants sont effacés à la première lecture.
 */

export const RUNNER_SESSION_DURATION_MS = 24 * 60 * 60 * 1000;

export interface RunnerSession {
  /** Pseudo renvoyé par l'API (stocké en minuscules, RG2). */
  readonly pseudo: string;
  /** Valeur complète de l'en-tête : « Basic … ». */
  readonly authorization: string;
  /** Fin de validité des identifiants mémorisés ; null s'ils ne sont qu'en mémoire. */
  readonly expiresAt: number | null;
}

/** Emplacement persistant des identifiants coureur (IndexedDB en production, simulé en test). */
export interface RunnerCredentialPersistence {
  read(): Promise<unknown>;
  write(session: RunnerSession): Promise<void>;
  clear(): Promise<void>;
}

export class RunnerCredentialStore {
  private session: RunnerSession | null = null;

  constructor(private readonly persistence: RunnerCredentialPersistence, private readonly now: () => number) {}

  /** Connexion réussie (réponse 2xx de E21) : c'est une activité. */
  async signIn(pseudo: string, authorization: string, rememberFor24h: boolean): Promise<RunnerSession> {
    this.session = { pseudo, authorization, expiresAt: rememberFor24h ? this.nextExpiry() : null };
    await this.persist();
    return this.session;
  }

  /** Reprise au démarrage des identifiants mémorisés, s'ils ne sont pas expirés. */
  async restore(): Promise<RunnerSession | null> {
    const stored = readRunnerSession(await this.persistence.read());
    if (stored === null || stored.expiresAt === null || this.isExpired(stored)) {
      await this.persistence.clear();
      return null;
    }
    this.session = stored;
    return stored;
  }

  /** Session courante ; des identifiants mémorisés expirés sont effacés à cette lecture. */
  async current(): Promise<RunnerSession | null> {
    if (this.session !== null && this.isExpired(this.session)) {
      await this.signOut();
    }
    return this.session;
  }

  /** Réponse 2xx reçue avec ces identifiants : l'expiration glisse à maintenant + 24 h s'ils sont mémorisés. */
  async recordActivity(): Promise<void> {
    if (this.session === null || this.session.expiresAt === null || this.isExpired(this.session)) {
      return;
    }
    this.session = { ...this.session, expiresAt: this.nextExpiry() };
    await this.persist();
  }

  /** Changement de mot de passe réussi (204 de E23) : nouveaux identifiants, même option de conservation. */
  async replaceAuthorization(authorization: string): Promise<RunnerSession | null> {
    if (this.session === null) {
      return null;
    }
    const remembered = this.session.expiresAt !== null;
    this.session = { ...this.session, authorization, expiresAt: remembered ? this.nextExpiry() : null };
    await this.persist();
    return this.session;
  }

  /** « Se déconnecter » ou 401 : efface les identifiants coureur, en mémoire et dans le stockage. */
  async signOut(): Promise<void> {
    this.session = null;
    await this.persistence.clear();
  }

  private async persist(): Promise<void> {
    if (this.session !== null && this.session.expiresAt !== null) {
      await this.persistence.write(this.session);
    } else {
      await this.persistence.clear();
    }
  }

  private nextExpiry(): number {
    return this.now() + RUNNER_SESSION_DURATION_MS;
  }

  private isExpired(session: RunnerSession): boolean {
    return session.expiresAt !== null && this.now() >= session.expiresAt;
  }
}

function readRunnerSession(value: unknown): RunnerSession | null {
  if (typeof value !== 'object' || value === null) {
    return null;
  }
  const record = value as Record<string, unknown>;
  const pseudo = record['pseudo'];
  const authorization = record['authorization'];
  const expiresAt = record['expiresAt'];
  if (typeof pseudo !== 'string' || typeof authorization !== 'string'
    || (typeof expiresAt !== 'number' && expiresAt !== null)) {
    return null;
  }
  return { pseudo, authorization, expiresAt };
}
