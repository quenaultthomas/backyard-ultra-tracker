import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { credentialScope } from './credential-routing';
import { requiresAuthorization, requiresRunnerAuthorization } from './api-paths';

/**
 * Spec inc. 7 [front-unit, lecture de sources] : routes et gabarits de l'ecran de connexion unique et de l'ecran
 * d'inscription autonome (RG1, RG3, RG5, RG7, RG11). Les parcours dans le navigateur (CA8 a CA16, CA20, CA22,
 * CA23) relevent des tests E2E. Aucun test ne passe a vide : fichier introuvable ou bloc de route absent = echec.
 */

const APP = new URL('../', import.meta.url);

function read(url: URL): string {
  expect(existsSync(url), `fichier introuvable : ${url.pathname}`).toBe(true);
  const text = readFileSync(url, 'utf8');
  expect(text.length, `fichier vide : ${url.pathname}`).toBeGreaterThan(0);
  return text;
}

/** Bloc `{ path: '<path>', ... }` de la liste de routes : de `path: '<path>'` a l'accolade fermante du meme niveau. */
function routeBlock(routes: string, path: string): string {
  const marker = `path: '${path}'`;
  const at = routes.indexOf(marker);
  expect(at, `route « ${path} » absente de app.routes.ts`).toBeGreaterThanOrEqual(0);
  const end = routes.indexOf('\n  },', at);
  expect(end).toBeGreaterThan(at);
  return routes.slice(at, end);
}

function templateOf(source: string): string {
  const match = /template:\s*`([\s\S]*?)`/.exec(source);
  expect(match, 'gabarit inline introuvable').not.toBeNull();
  return match![1]!;
}

function loadedComponentFile(block: string): string {
  const match = /import\('\.\/pages\/([^']+)'\)/.exec(block);
  expect(match, 'loadComponent introuvable').not.toBeNull();
  return `pages/${match![1]!}.ts`;
}

describe('RG3 - /connexion et /compte/connexion : meme ecran, h1 « Connexion » (CA16)', () => {
  const routes = read(new URL('app.routes.ts', APP));

  it('/compte/connexion et /connexion chargent le meme composant', () => {
    expect(loadedComponentFile(routeBlock(routes, 'compte/connexion'))).toBe(
      loadedComponentFile(routeBlock(routes, 'connexion')),
    );
  });

  it("l'ancien intitule « Connexion coureur » a disparu (titre de route et gabarits publics)", () => {
    expect(routes).not.toContain('Connexion coureur');
    for (const folder of ['login', 'account', 'registration', 'scan']) {
      for (const { name: file } of readdirSync(new URL(`pages/${folder}/`, APP), { withFileTypes: true })) {
        if (file.endsWith('.ts') && !file.endsWith('.spec.ts')) {
          expect(read(new URL(`pages/${folder}/${file}`, APP)), `${folder}/${file}`).not.toContain('Connexion coureur');
        }
      }
    }
  });

  it('le gabarit de l\'ecran unique : h1 « Connexion » exact, entrees « Coureur » et « Benevole », libelles des deux profils', () => {
    const template = templateOf(read(new URL(loadedComponentFile(routeBlock(routes, 'connexion')), APP)));

    expect(template).toMatch(/<h1>\s*Connexion\s*<\/h1>/);
    expect(template).toContain('Coureur');
    expect(template).toContain('Bénévole');
    expect(template).toContain('Mot de passe');
    expect(template).toContain('Afficher');
    expect(template).toContain('Se connecter');
  });

  it('la garde /compte renvoie toujours vers /compte/connexion avec retour (comportement conserve, RG3)', () => {
    expect(routes).toContain('RUNNER_LOGIN_ROUTE');
    expect(routes).toContain('retour: state.url');
  });
});

describe('RG7 / RG11 - route /inscription (CA12, CA20, CA21)', () => {
  const routes = read(new URL('app.routes.ts', APP));

  it("la route /inscription est declaree, avec son titre propre ; /inscription/:raceId est conservee", () => {
    const block = routeBlock(routes, 'inscription');
    expect(block).toContain("title: 'Inscription — Backyard Ultra Tracker'");
    expect(routes).toContain("path: 'inscription/:raceId'");
  });

  it("les ecrans /connexion et /inscription ne sont pas des fichiers admin-* et n'importent rien de l'admin (RG11)", () => {
    for (const path of ['connexion', 'inscription']) {
      const file = loadedComponentFile(routeBlock(routes, path));
      const name = file.split('/').pop()!;
      expect(file, `${path} : fichier de route`).not.toContain('/admin/');
      expect(name.startsWith('admin-'), `${path} : ${name}`).toBe(false);
      const source = read(new URL(file, APP));
      expect(source, `${path} : import admin`).not.toMatch(/from\s+'[^']*\/admin\/[^']*'/);
      for (const text of ['Administration des courses', 'Gérer la course et les coureurs', 'Imprimer les QR codes']) {
        expect(source, `${path} : texte admin « ${text} »`).not.toContain(text);
      }
    }
  });

  it("l'ecran d'inscription : champs « Pseudo », « Mot de passe », « Confirmer le mot de passe », avertissement RG17, aide benevole sans « administrat », lien « J'ai deja un compte » vers /compte/connexion", () => {
    const source = read(new URL(loadedComponentFile(routeBlock(routes, 'inscription')), APP));
    const template = templateOf(source);

    expect(template).toMatch(/<h1>\s*Créer un compte\s*<\/h1>/);
    expect(template).toContain('Pseudo');
    expect(template).toContain('Mot de passe');
    expect(template).toContain('Confirmer le mot de passe');
    expect(template).toContain('Rester connecté 24 h sur cet appareil');
    expect(template).toContain("N'utilisez pas votre nom réel");
    expect(template).toContain('Bénévole : votre compte est créé par l\'organisateur.');
    expect(template).not.toMatch(/administrat/i);
    expect(template).toContain("J'ai déjà un compte");
    expect(template).toContain('/compte/connexion');
    expect(template).not.toMatch(/(?:routerLink|href)=["']\/connexion/);
  });
});

describe('RG5 - E26 n\'envoie jamais Authorization (CA12 b, RG8 inc. 4)', () => {
  it.each([['/api/public/accounts']])('%s : aucun emplacement d\'identifiants, aucun en-tete', (path) => {
    expect(requiresAuthorization(path)).toBe(false);
    expect(requiresRunnerAuthorization(path)).toBe(false);
    expect(credentialScope(path)).toBeNull();
  });
});
