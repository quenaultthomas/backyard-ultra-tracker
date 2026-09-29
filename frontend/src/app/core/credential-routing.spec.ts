import { describe, expect, it } from 'vitest';
import { API_PATHS, requiresAuthorization, requiresRunnerAuthorization } from './api-paths';
import { authorizationFor, credentialEvent, CredentialSources } from './credential-routing';
import { basicAuthorization, CredentialStore } from './credentials';
import { RunnerCredentialPersistence, RunnerCredentialStore, RunnerSession } from './runner-credentials';
import { FakeClock, SpyCredentialPersistence } from './testing/fakes.spec-support';

const NOW = Date.UTC(2026, 9, 3, 8, 0, 0);
const LIEVRE_AUTH = basicAuthorization('Lievre', 'motdepasse-1');
const SCANNER_AUTH = basicAuthorization('scanner-test', 'scanner-secret');

class MemoryRunnerPersistence implements RunnerCredentialPersistence {
  value: unknown = null;

  async read(): Promise<unknown> {
    return this.value;
  }

  async write(session: RunnerSession): Promise<void> {
    this.value = structuredClone(session);
  }

  async clear(): Promise<void> {
    this.value = null;
  }
}

async function connectedOnSameDevice(): Promise<{
  readonly staff: CredentialStore;
  readonly runner: RunnerCredentialStore;
  readonly sources: CredentialSources;
}> {
  const clock = new FakeClock(NOW);
  const staff = new CredentialStore(new SpyCredentialPersistence(), clock.now);
  const runner = new RunnerCredentialStore(new MemoryRunnerPersistence(), clock.now);
  await staff.signIn('scanner-test', 'SCANNER', SCANNER_AUTH, true);
  await runner.signIn('lievre', LIEVRE_AUTH, true);
  const sources: CredentialSources = {
    staffAuthorization: async () => (await staff.current())?.authorization ?? null,
    runnerAuthorization: async () => (await runner.current())?.authorization ?? null,
  };
  return { staff, runner, sources };
}

describe('CA36 - envoi et séparation des identifiants (RG21, CL19)', () => {
  it('E21 porte l\'en-tête de Lievre, E6 celui de scanner-test ; E3 et la liste publique aucun', async () => {
    const { sources } = await connectedOnSameDevice();

    expect(await authorizationFor(API_PATHS.accountMe, sources)).toBe(LIEVRE_AUTH);
    expect(await authorizationFor(API_PATHS.accountRegistrations(2), sources)).toBe(LIEVRE_AUTH);
    expect(await authorizationFor(API_PATHS.accountPassword, sources)).toBe(LIEVRE_AUTH);
    expect(await authorizationFor(API_PATHS.scan, sources)).toBe(SCANNER_AUTH);
    expect(await authorizationFor(API_PATHS.registrations(1), sources)).toBeNull();
    expect(await authorizationFor(API_PATHS.races, sources)).toBeNull();
    expect(await authorizationFor('//autre.example/api/account/me', sources)).toBeNull();
  });

  it('un 401 sur E21 efface les identifiants de Lievre et conserve ceux de scanner-test', async () => {
    const { staff, runner, sources } = await connectedOnSameDevice();

    expect(credentialEvent(API_PATHS.accountMe, 'AUTH')).toBe('RUNNER_UNAUTHORIZED');
    await runner.signOut();

    expect(await authorizationFor(API_PATHS.accountMe, sources)).toBeNull();
    expect((await staff.current())?.authorization).toBe(SCANNER_AUTH);
  });

  it('« Se déconnecter » du compte coureur conserve les identifiants de scanner-test', async () => {
    const { staff, runner } = await connectedOnSameDevice();

    await runner.signOut();

    expect((await staff.current())?.authorization).toBe(SCANNER_AUTH);
  });

  it('événements : 401 par emplacement, activité coureur sur 2xx seulement, rien sur les chemins publics', () => {
    expect(credentialEvent(API_PATHS.scan, 'AUTH')).toBe('STAFF_UNAUTHORIZED');
    expect(credentialEvent(API_PATHS.adminRaces, 'AUTH')).toBe('STAFF_UNAUTHORIZED');
    expect(credentialEvent(API_PATHS.accountMe, 'SUCCESS')).toBe('RUNNER_ACTIVITY');
    expect(credentialEvent(API_PATHS.accountMe, 'TRANSIENT')).toBeNull();
    expect(credentialEvent(API_PATHS.accountMe, 'DEFINITIVE')).toBeNull();
    expect(credentialEvent(API_PATHS.adminRaces, 'SUCCESS')).toBeNull();
    expect(credentialEvent(API_PATHS.registrations(1), 'AUTH')).toBeNull();
  });

  it('chemins : les identifiants ADMIN/SCANNER ne vont jamais vers /api/account/**, ni les coureur ailleurs', () => {
    expect(requiresAuthorization(API_PATHS.accountMe)).toBe(false);
    expect(requiresRunnerAuthorization(API_PATHS.accountMe)).toBe(true);
    expect(requiresRunnerAuthorization(API_PATHS.adminAccounts())).toBe(false);
    expect(requiresAuthorization(API_PATHS.adminAccounts('LIE'))).toBe(true);
    expect(requiresRunnerAuthorization('/api/accounts')).toBe(false);
    expect(requiresRunnerAuthorization(API_PATHS.registrations(1))).toBe(false);
    expect(API_PATHS.adminAccounts(' a_b%')).toBe('/api/admin/accounts?pseudo=%20a_b%25');
    expect(API_PATHS.adminAccountPassword(5)).toBe('/api/admin/accounts/5/password');
    expect(API_PATHS.adminAccount(5)).toBe('/api/admin/accounts/5');
  });
});
