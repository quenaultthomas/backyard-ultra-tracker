import { describe, expect, it } from 'vitest';
import { basicAuthorization, CredentialStore, REMEMBER_DURATION_MS } from './credentials';
import { FakeClock, SpyCredentialPersistence } from './testing/fakes.spec-support';

const EIGHT_AM = Date.UTC(2026, 9, 3, 8, 0, 0);
const SCANNER_AUTH = 'Basic c2Nhbm5lci10ZXN0OnNjYW5uZXItc2VjcmV0';
const ADMIN_AUTH = 'Basic YWRtaW4tdGVzdDphZG1pbi1zZWNyZXQ=';

describe('CA18 - expiration des identifiants SCANNER (RG7)', () => {
  it('« Rester connecté 24 h » à 08:00:00 : lus à +23:59:59, effacés à +24:00:00', async () => {
    const clock = new FakeClock(EIGHT_AM);
    const persistence = new SpyCredentialPersistence();
    await new CredentialStore(persistence, clock.now).signIn('scanner-test', 'SCANNER', SCANNER_AUTH, true);
    expect(persistence.writes).toEqual([
      { username: 'scanner-test', role: 'SCANNER', authorization: SCANNER_AUTH, expiresAt: EIGHT_AM + REMEMBER_DURATION_MS },
    ]);

    clock.current = EIGHT_AM + REMEMBER_DURATION_MS - 1000;
    const beforeExpiry = new CredentialStore(persistence, clock.now);
    expect((await beforeExpiry.restore())?.authorization).toBe(SCANNER_AUTH);
    expect((await beforeExpiry.current())?.authorization).toBe(SCANNER_AUTH);

    clock.current = EIGHT_AM + REMEMBER_DURATION_MS;
    expect(await beforeExpiry.current()).toBeNull();
    expect(persistence.value).toBeNull();
  });

  it('à +24:00:00 au redémarrage : effacés à la première lecture, connexion demandée', async () => {
    const clock = new FakeClock(EIGHT_AM);
    const persistence = new SpyCredentialPersistence();
    await new CredentialStore(persistence, clock.now).signIn('scanner-test', 'SCANNER', SCANNER_AUTH, true);
    clock.current = EIGHT_AM + REMEMBER_DURATION_MS;

    const store = new CredentialStore(persistence, clock.now);

    expect(await store.restore()).toBeNull();
    expect(await store.current()).toBeNull();
    expect(persistence.value).toBeNull();
  });

  it('connexion ADMIN : aucun appel d\'écriture vers le stockage persistant, même avec la case cochée', async () => {
    const persistence = new SpyCredentialPersistence();
    const store = new CredentialStore(persistence, new FakeClock(EIGHT_AM).now);

    const session = await store.signIn('admin-test', 'ADMIN', ADMIN_AUTH, true);

    expect(persistence.writes).toEqual([]);
    expect(session.expiresAt).toBeNull();
    expect((await store.current())?.role).toBe('ADMIN');
    expect(await new CredentialStore(persistence, new FakeClock(EIGHT_AM).now).restore()).toBeNull();
  });

  it('SCANNER sans « Rester connecté » : en mémoire seulement, rien au redémarrage', async () => {
    const persistence = new SpyCredentialPersistence();
    const store = new CredentialStore(persistence, new FakeClock(EIGHT_AM).now);
    await store.signIn('scanner-test', 'SCANNER', SCANNER_AUTH, false);
    expect(persistence.writes).toEqual([]);
    expect((await store.current())?.expiresAt).toBeNull();
    expect(await new CredentialStore(persistence, new FakeClock(EIGHT_AM).now).restore()).toBeNull();
  });

  it('une connexion remplace la précédente : l\'ADMIN efface le SCANNER mémorisé (RG6)', async () => {
    const persistence = new SpyCredentialPersistence();
    const store = new CredentialStore(persistence, new FakeClock(EIGHT_AM).now);
    await store.signIn('scanner-test', 'SCANNER', SCANNER_AUTH, true);
    await store.signIn('admin-test', 'ADMIN', ADMIN_AUTH, false);
    expect(persistence.value).toBeNull();
  });

  it('déconnexion : mémoire et stockage effacés (RG9)', async () => {
    const persistence = new SpyCredentialPersistence();
    const store = new CredentialStore(persistence, new FakeClock(EIGHT_AM).now);
    await store.signIn('scanner-test', 'SCANNER', SCANNER_AUTH, true);
    await store.signOut();
    expect(await store.current()).toBeNull();
    expect(persistence.value).toBeNull();
  });

  it('stockage illisible ou non SCANNER : ignoré et effacé', async () => {
    for (const value of ['texte', { username: 'x' }, { username: 'a', role: 'ADMIN', authorization: ADMIN_AUTH, expiresAt: 1 },
      { username: 'a', role: 'SCANNER', authorization: SCANNER_AUTH, expiresAt: null }]) {
      const persistence = new SpyCredentialPersistence();
      persistence.value = value;
      expect(await new CredentialStore(persistence, new FakeClock(EIGHT_AM).now).restore()).toBeNull();
      expect(persistence.value).toBeNull();
    }
  });

  it('en-tête Basic encodé en UTF-8', () => {
    expect(basicAuthorization('admin-test', 'admin-secret')).toBe(ADMIN_AUTH);
    expect(basicAuthorization('scanner-test', 'scanner-secret')).toBe(SCANNER_AUTH);
    expect(basicAuthorization('é', 'ü')).toBe(`Basic ${btoa('Ã©:Ã¼')}`);
  });
});
