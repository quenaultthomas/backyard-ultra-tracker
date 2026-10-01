import { describe, expect, it } from 'vitest';
import {
  LOGIN_FAILURE_HINT,
  LOGIN_FAILURE_MESSAGE,
  defaultLoginEntry,
  loginDestination,
  loginFieldLabels,
} from './login-entry';

/**
 * Spec inc. 7, RG1 et RG2 (CA8, CA10, CA16, CA22, CA9) : logique pure de l'ecran de connexion unique.
 *
 * Contrat attendu de `core/login-entry.ts` (ecrit a partir de la spec, avant implementation) :
 *  - `LoginEntry` = 'RUNNER' (entree « Coureur », E21) | 'STAFF' (entree « Benevole », E19) ;
 *  - `defaultLoginEntry({ retour?, profil?, runnerRoute? })` : entree preselectionnee ;
 *  - `loginFieldLabels(entry)` : `{ identifier, remember }`, libelles figes ;
 *  - `LOGIN_FAILURE_MESSAGE`, `LOGIN_FAILURE_HINT` : message d'echec unique et aide neutre ;
 *  - `loginDestination(entry, retour, role?)` : ecran d'arrivee apres connexion reussie.
 */

describe('RG1 - entree preselectionnee (CA22 b)', () => {
  it.each([
    ['/scan'],
    ['/scan/'],
    ['/scan/anything'],
    ['/admin'],
    ['/admin/comptes'],
    ['/admin/courses/3/qr'],
  ])('retour=%s : « Benevole » preselectionnee', (retour) => {
    expect(defaultLoginEntry({ retour })).toBe('STAFF');
  });

  it('profil=benevole : « Benevole » preselectionnee, meme sans retour', () => {
    expect(defaultLoginEntry({ profil: 'benevole' })).toBe('STAFF');
    expect(defaultLoginEntry({ profil: 'benevole', retour: '/compte' })).toBe('STAFF');
  });

  it.each([
    [{}],
    [{ retour: '/compte' }],
    [{ retour: '/inscription/4' }],
    [{ retour: '/' }],
    [{ profil: 'x' }],
    [{ profil: 'coureur' }],
    [{ profil: 'BENEVOLE' }],
    [{ retour: '/scanner' }],
    [{ retour: '/administration' }],
    [{ retour: '/administrateur/x' }],
    [{ retour: '/compte/scan' }],
    [{ retour: '' }],
    [{ retour: undefined, profil: undefined }],
  ])('%j : « Coureur » preselectionnee (profil inconnu ignore, prefixe strict)', (params) => {
    expect(defaultLoginEntry(params)).toBe('RUNNER');
  });

  it('un profil inconnu est ignore : le retour sous /scan decide', () => {
    expect(defaultLoginEntry({ profil: 'x', retour: '/scan' })).toBe('STAFF');
    expect(defaultLoginEntry({ profil: 'x', retour: '/compte' })).toBe('RUNNER');
  });

  it('RG3 - sur /compte/connexion (runnerRoute), « Coureur » est preselectionnee quel que soit retour ou profil', () => {
    expect(defaultLoginEntry({ retour: '/scan', runnerRoute: true })).toBe('RUNNER');
    expect(defaultLoginEntry({ retour: '/admin/comptes', runnerRoute: true })).toBe('RUNNER');
    expect(defaultLoginEntry({ profil: 'benevole', runnerRoute: true })).toBe('RUNNER');
    expect(defaultLoginEntry({ runnerRoute: true })).toBe('RUNNER');
  });
});

describe('RG1 - libelles de champs par entree (figes, CA8)', () => {
  it('« Coureur » : champ « Pseudo », case « Rester connecte 24 h sur cet appareil »', () => {
    expect(loginFieldLabels('RUNNER')).toEqual({
      identifier: 'Pseudo',
      remember: 'Rester connecté 24 h sur cet appareil',
    });
  });

  it("« Benevole » : champ « Nom d'utilisateur », case « ... (compte scanner uniquement) »", () => {
    expect(loginFieldLabels('STAFF')).toEqual({
      identifier: "Nom d'utilisateur",
      remember: 'Rester connecté 24 h sur cet appareil (compte scanner uniquement)',
    });
  });

  it("aucun libelle ne mentionne l'administration (RG1, RG7 inc. 6)", () => {
    for (const entry of ['RUNNER', 'STAFF'] as const) {
      const labels = Object.values(loginFieldLabels(entry)).join(' ');
      expect(labels).not.toMatch(/administrat/i);
    }
  });
});

describe('RG2 / D1-bis - message d\'echec unique avec aide neutre (CA10)', () => {
  it('message exact « Identifiants invalides » et aide exacte « Verifiez le type de compte choisi »', () => {
    expect(LOGIN_FAILURE_MESSAGE).toBe('Identifiants invalides');
    expect(LOGIN_FAILURE_HINT).toBe('Vérifiez le type de compte choisi');
  });

  it("l'aide est distincte du message (element separe) et ne revele rien de l'autre referentiel", () => {
    expect(LOGIN_FAILURE_HINT).not.toBe(LOGIN_FAILURE_MESSAGE);
    expect(LOGIN_FAILURE_MESSAGE).not.toContain(LOGIN_FAILURE_HINT);
    for (const text of [LOGIN_FAILURE_MESSAGE, LOGIN_FAILURE_HINT]) {
      expect(text).not.toMatch(/administrat|existe|inconnu|bénévole|coureur/i);
    }
  });
});

describe('RG2 - orientation apres connexion (CA9, CA16)', () => {
  it('« Coureur » : retour interne et sur, sinon /compte', () => {
    expect(loginDestination('RUNNER', '/inscription/4')).toBe('/inscription/4');
    expect(loginDestination('RUNNER', undefined)).toBe('/compte');
    expect(loginDestination('RUNNER', '')).toBe('/compte');
  });

  it.each([['//evil.example'], ['https://evil.example'], ['javascript:alert(1)'], ['compte'], ['\\\\evil']])(
    'retour non sur %s : ignore',
    (retour) => {
      expect(loginDestination('RUNNER', retour)).toBe('/compte');
      expect(loginDestination('STAFF', retour, 'ADMIN')).toBe('/admin');
      expect(loginDestination('STAFF', retour, 'SCANNER')).toBe('/scan');
    },
  );

  it('« Benevole » : SCANNER -> /scan, ADMIN -> /admin sans retour', () => {
    expect(loginDestination('STAFF', undefined, 'SCANNER')).toBe('/scan');
    expect(loginDestination('STAFF', undefined, 'ADMIN')).toBe('/admin');
  });

  it('« Benevole » : le retour demande prevaut sur le role (ADMIN revenant sur /scan, CA9 d)', () => {
    expect(loginDestination('STAFF', '/scan', 'ADMIN')).toBe('/scan');
    expect(loginDestination('STAFF', '/admin/comptes', 'ADMIN')).toBe('/admin/comptes');
  });
});
