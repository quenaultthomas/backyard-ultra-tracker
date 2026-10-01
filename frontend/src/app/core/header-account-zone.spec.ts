import { describe, expect, it } from 'vitest';
import { headerAccountZone } from './header-account-zone';

/**
 * Spec inc. 7, RG4 (CA14) : regles d'affichage de la zone « compte » de l'en-tete, fonction pure.
 *
 * Contrat attendu de `core/header-account-zone.ts` (ecrit a partir de la spec, avant implementation) :
 *  `headerAccountZone(path, runnerPseudo)` -> `{ loginLink, createAccountLink, connectedLabel }` ;
 *  `path` est l'URL courante (requete et fragment ignores), `runnerPseudo` le pseudo de la SEULE connexion
 *  coureur (null si absente). La connexion staff n'est volontairement pas un parametre (RG4 : aucun effet).
 */

const ANONYMOUS_ON = (path: string) => headerAccountZone(path, null);

describe('RG4 - anonyme : « Se connecter » et « Creer un compte »', () => {
  it.each([['/'], ['/courses/3'], ['/coureurs/12'], ['/inscription/4'], ['/compte']])('%s : les deux liens', (path) => {
    expect(ANONYMOUS_ON(path)).toEqual({ loginLink: true, createAccountLink: true, connectedLabel: null });
  });

  it('/inscription/{raceId} n\'est pas /inscription : « Creer un compte » reste affiche (CA13)', () => {
    expect(ANONYMOUS_ON('/inscription/4').createAccountLink).toBe(true);
  });
});

describe('RG4 - pas de doublon avec la page affichee', () => {
  it('/scan : pas de « Se connecter » (le bandeau de la page le porte), « Creer un compte » present', () => {
    expect(ANONYMOUS_ON('/scan')).toEqual({ loginLink: false, createAccountLink: true, connectedLabel: null });
  });

  it('/connexion : « Creer un compte » seulement', () => {
    expect(ANONYMOUS_ON('/connexion')).toEqual({ loginLink: false, createAccountLink: true, connectedLabel: null });
  });

  it('/compte/connexion : « Creer un compte » seulement', () => {
    expect(ANONYMOUS_ON('/compte/connexion')).toEqual({
      loginLink: false,
      createAccountLink: true,
      connectedLabel: null,
    });
  });

  it('/inscription : « Se connecter » seulement', () => {
    expect(ANONYMOUS_ON('/inscription')).toEqual({ loginLink: true, createAccountLink: false, connectedLabel: null });
  });

  it.each([
    ['/connexion?retour=/scan', false, true],
    ['/connexion?profil=benevole', false, true],
    ['/compte/connexion?retour=%2Fcompte', false, true],
    ['/inscription?retour=/inscription/4', true, false],
    ['/scan?x=1', false, true],
    ['/scan#haut', false, true],
    ['/inscription/', true, false],
  ])('%s : requete et fragment ignores (Se connecter=%s, Creer un compte=%s)', (path, login, create) => {
    const zone = ANONYMOUS_ON(path);
    expect(zone.loginLink).toBe(login);
    expect(zone.createAccountLink).toBe(create);
  });
});

describe('RG4 - connexion coureur active : « Connecte : {pseudo} », sans lien', () => {
  it.each([['/'], ['/courses/3'], ['/scan'], ['/connexion'], ['/compte/connexion'], ['/inscription'], ['/inscription/4'], ['/compte']])(
    '%s : texte « Connecte : lievre », aucun des deux liens',
    (path) => {
      expect(headerAccountZone(path, 'lievre')).toEqual({
        loginLink: false,
        createAccountLink: false,
        connectedLabel: 'Connecté : lievre',
      });
    },
  );

  it('le pseudo est affiche tel que fourni (valeur stockee), sans transformation', () => {
    expect(headerAccountZone('/', 'nouveau-1').connectedLabel).toBe('Connecté : nouveau-1');
  });
});

describe('RG4 / RG7 inc. 6 - rien du staff dans l\'en-tete', () => {
  it('aucune sortie ne mentionne benevole, administrateur ou administration', () => {
    const outputs = [
      ...['/', '/scan', '/connexion', '/inscription'].map((path) => headerAccountZone(path, null)),
      headerAccountZone('/', 'lievre'),
    ];
    for (const zone of outputs) {
      expect(JSON.stringify(zone)).not.toMatch(/b[ée]n[ée]vole|administrat|scanner|admin/i);
    }
  });
});
