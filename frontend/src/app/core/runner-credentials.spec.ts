import { describe, expect, it } from 'vitest';
import { basicAuthorization } from './credentials';
import {
  RUNNER_SESSION_DURATION_MS,
  RunnerCredentialPersistence,
  RunnerCredentialStore,
  RunnerSession,
} from './runner-credentials';
import { FakeClock } from './testing/fakes.spec-support';

const HOUR_MS = 60 * 60 * 1000;
const J_0800 = Date.UTC(2026, 9, 3, 8, 0, 0);
const J_2000 = J_0800 + 12 * HOUR_MS;
const LIEVRE_AUTH = basicAuthorization('Lievre', 'motdepasse-1');
const NEW_AUTH = basicAuthorization('lievre', 'nouveau-mdp-43');

/** Stockage persistant espionné des identifiants coureur. */
class SpyRunnerPersistence implements RunnerCredentialPersistence {
  value: unknown = null;
  readonly writes: RunnerSession[] = [];

  async read(): Promise<unknown> {
    return this.value;
  }

  async write(session: RunnerSession): Promise<void> {
    this.writes.push(session);
    this.value = structuredClone(session);
  }

  async clear(): Promise<void> {
    this.value = null;
  }
}

describe('CA35 - expiration glissante des identifiants coureur (RG21, CL18)', () => {
  it('connexion « Rester connecté » à J 08:00, réponse 200 à J 20:00 : lus à J+1 19:59:59, effacés à J+1 20:00', async () => {
    const clock = new FakeClock(J_0800);
    const persistence = new SpyRunnerPersistence();
    const store = new RunnerCredentialStore(persistence, clock.now);
    await store.signIn('lievre', LIEVRE_AUTH, true);
    clock.current = J_2000;
    await store.recordActivity();

    clock.current = J_2000 + RUNNER_SESSION_DURATION_MS - 1000;
    const reopened = new RunnerCredentialStore(persistence, clock.now);
    expect((await reopened.restore())?.authorization).toBe(LIEVRE_AUTH);
    expect((await reopened.current())?.pseudo).toBe('lievre');

    clock.current = J_2000 + RUNNER_SESSION_DURATION_MS;
    expect(await reopened.current()).toBeNull();
    expect(persistence.value).toBeNull();
    expect(await new RunnerCredentialStore(persistence, clock.now).restore()).toBeNull();
  });

  it('connexion à J 08:00 sans autre réponse 2xx (erreur réseau, 429 à J 20:00) : lus à J+1 07:59:59, effacés à J+1 08:00', async () => {
    const clock = new FakeClock(J_0800);
    const persistence = new SpyRunnerPersistence();
    await new RunnerCredentialStore(persistence, clock.now).signIn('lievre', LIEVRE_AUTH, true);

    clock.current = J_0800 + RUNNER_SESSION_DURATION_MS - 1000;
    const beforeExpiry = new RunnerCredentialStore(persistence, clock.now);
    expect((await beforeExpiry.restore())?.authorization).toBe(LIEVRE_AUTH);

    clock.current = J_0800 + RUNNER_SESSION_DURATION_MS;
    expect(await beforeExpiry.current()).toBeNull();
    expect(await new RunnerCredentialStore(persistence, clock.now).restore()).toBeNull();
  });

  it('connexion sans « Rester connecté » : aucune écriture dans le stockage persistant, même après une activité', async () => {
    const persistence = new SpyRunnerPersistence();
    const store = new RunnerCredentialStore(persistence, new FakeClock(J_0800).now);

    const session = await store.signIn('lievre', LIEVRE_AUTH, false);
    await store.recordActivity();

    expect(persistence.writes).toEqual([]);
    expect(session.expiresAt).toBeNull();
    expect((await store.current())?.authorization).toBe(LIEVRE_AUTH);
    expect(await new RunnerCredentialStore(persistence, new FakeClock(J_0800).now).restore()).toBeNull();
  });

  it('après un 204 de E23 avec nouveau-mdp-43 : la valeur stockée correspond au nouveau mot de passe', async () => {
    const clock = new FakeClock(J_0800);
    const persistence = new SpyRunnerPersistence();
    const store = new RunnerCredentialStore(persistence, clock.now);
    await store.signIn('lievre', LIEVRE_AUTH, true);
    clock.current = J_2000;

    await store.replaceAuthorization(NEW_AUTH);

    expect(persistence.value).toEqual({ pseudo: 'lievre', authorization: NEW_AUTH, expiresAt: J_2000 + RUNNER_SESSION_DURATION_MS });
    expect((await store.current())?.authorization).toBe(NEW_AUTH);
  });

  it('changement de mot de passe sans « Rester connecté » : en mémoire seulement', async () => {
    const persistence = new SpyRunnerPersistence();
    const store = new RunnerCredentialStore(persistence, new FakeClock(J_0800).now);
    await store.signIn('lievre', LIEVRE_AUTH, false);

    await store.replaceAuthorization(NEW_AUTH);

    expect(persistence.writes).toEqual([]);
    expect((await store.current())?.authorization).toBe(NEW_AUTH);
  });

  it('sans connexion : ni activité ni remplacement n\'écrivent ; « Se déconnecter » efface tout', async () => {
    const persistence = new SpyRunnerPersistence();
    const store = new RunnerCredentialStore(persistence, new FakeClock(J_0800).now);

    await store.recordActivity();
    expect(await store.replaceAuthorization(NEW_AUTH)).toBeNull();
    expect(persistence.writes).toEqual([]);

    await store.signIn('lievre', LIEVRE_AUTH, true);
    await store.signOut();
    expect(await store.current()).toBeNull();
    expect(persistence.value).toBeNull();
  });

  it('valeur stockée illisible : ignorée et effacée', async () => {
    const persistence = new SpyRunnerPersistence();
    persistence.value = { pseudo: 42, authorization: LIEVRE_AUTH, expiresAt: J_2000 };

    expect(await new RunnerCredentialStore(persistence, new FakeClock(J_0800).now).restore()).toBeNull();
    expect(persistence.value).toBeNull();
  });
});
