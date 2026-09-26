import { Role } from './api.types';

/**
 * Conservation des identifiants (RG7, RG9, RG10). HTTP Basic impose de renvoyer le mot de passe : la valeur
 * `Authorization` est donc un secret.
 * - ADMIN : en mémoire uniquement, jamais d'écriture dans le stockage persistant.
 * - SCANNER : en mémoire, ou, sur choix explicite, dans le stockage persistant pour 24 h (horloge de l'appareil).
 *   Au-delà, la valeur est effacée à la première lecture.
 * Un seul compte à la fois : une connexion remplace la précédente.
 */

export const REMEMBER_DURATION_MS = 24 * 60 * 60 * 1000;

export interface Session {
  readonly username: string;
  readonly role: Role;
  /** Valeur complète de l'en-tête : « Basic … ». */
  readonly authorization: string;
  /** Fin de validité des identifiants mémorisés ; null s'ils ne sont qu'en mémoire. */
  readonly expiresAt: number | null;
}

/** Stockage persistant de l'origine (IndexedDB en production, simulé en test). */
export interface CredentialPersistence {
  read(): Promise<unknown>;
  write(session: Session): Promise<void>;
  clear(): Promise<void>;
}

export class CredentialStore {
  private session: Session | null = null;

  constructor(private readonly persistence: CredentialPersistence, private readonly now: () => number) {}

  /** Enregistre une connexion réussie (RG6, RG7). Seul un compte SCANNER peut être mémorisé. */
  async signIn(username: string, role: Role, authorization: string, rememberFor24h: boolean): Promise<Session> {
    const remember = role === 'SCANNER' && rememberFor24h;
    const session: Session = {
      username,
      role,
      authorization,
      expiresAt: remember ? this.now() + REMEMBER_DURATION_MS : null,
    };
    this.session = session;
    if (remember) {
      await this.persistence.write(session);
    } else {
      await this.persistence.clear();
    }
    return session;
  }

  /** Reprise au démarrage des identifiants SCANNER mémorisés, s'ils ne sont pas expirés. */
  async restore(): Promise<Session | null> {
    const stored = readSession(await this.persistence.read());
    if (stored === null || stored.role !== 'SCANNER' || stored.expiresAt === null || this.isExpired(stored)) {
      await this.persistence.clear();
      return null;
    }
    this.session = stored;
    return stored;
  }

  /** Session courante ; des identifiants mémorisés expirés sont effacés à cette lecture. */
  async current(): Promise<Session | null> {
    if (this.session !== null && this.isExpired(this.session)) {
      await this.signOut();
    }
    return this.session;
  }

  /** Efface les identifiants en mémoire et dans le stockage (RG9, RG10). */
  async signOut(): Promise<void> {
    this.session = null;
    await this.persistence.clear();
  }

  private isExpired(session: Session): boolean {
    return session.expiresAt !== null && this.now() >= session.expiresAt;
  }
}

function readSession(value: unknown): Session | null {
  if (typeof value !== 'object' || value === null) {
    return null;
  }
  const record = value as Record<string, unknown>;
  const username = record['username'];
  const role = record['role'];
  const authorization = record['authorization'];
  const expiresAt = record['expiresAt'];
  if (typeof username !== 'string' || typeof authorization !== 'string'
    || (role !== 'SCANNER' && role !== 'ADMIN') || (typeof expiresAt !== 'number' && expiresAt !== null)) {
    return null;
  }
  return { username, role, authorization, expiresAt };
}

/** En-tête HTTP Basic « Basic base64(nom:motdepasse) », encodé en UTF-8. */
export function basicAuthorization(username: string, password: string): string {
  const bytes = new TextEncoder().encode(`${username}:${password}`);
  let binary = '';
  bytes.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });
  return `Basic ${btoa(binary)}`;
}
