import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

/**
 * Spec inc. 6, RG2 a RG7 (CA2 a CA6, partie « unitaire » : sources et configuration, sans navigateur).
 * Les parcours dans le navigateur (CA2 a CA11) relevent des tests E2E. Ces tests lisent les sources du front :
 * ils figent l'absence de liens et de textes admin dans les ecrans publics, et l'exclusion des blocs admin du
 * prechargement du service worker. Ils sont demontres discriminants par des sources synthetiques, et ne peuvent pas
 * passer a vide (fichiers non trouves = echec).
 */

const APP = new URL('../', import.meta.url);
const FRONTEND = new URL('../../../', import.meta.url);

// ----- lecture de sources -----

function read(url: URL): string {
  expect(existsSync(url), `fichier introuvable : ${url.pathname}`).toBe(true);
  const text = readFileSync(url, 'utf8');
  expect(text.length, `fichier vide : ${url.pathname}`).toBeGreaterThan(0);
  return text;
}

/** Gabarit inline du composant (texte entre les accents graves de `template:`), ou echec s'il n'y en a pas. */
function templateOf(source: string): string {
  const match = /template:\s*`([\s\S]*?)`/.exec(source);
  expect(match, 'gabarit inline `template: ...` introuvable').not.toBeNull();
  return match![1]!;
}

/** Fichiers .ts des sous-dossiers de src/app/pages, hors .spec et hors pages/admin. */
function publicPageFiles(): { readonly name: string; readonly url: URL }[] {
  const root = new URL('pages/', APP);
  const files: { name: string; url: URL }[] = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    if (!entry.isDirectory() || entry.name === 'admin') {
      continue;
    }
    const folder = new URL(`${entry.name}/`, root);
    for (const file of readdirSync(folder, { withFileTypes: true })) {
      if (file.name.endsWith('.ts') && !file.name.endsWith('.spec.ts')) {
        files.push({ name: `${entry.name}/${file.name}`, url: new URL(file.name, folder) });
      }
    }
  }
  return files;
}

// ----- detecteurs (testes sur des sources synthetiques plus bas) -----

/** Liens menant a /admin : routerLink, href ou [routerLink] vers un chemin /admin. */
function adminLinks(template: string): string[] {
  return template.match(/(?:routerLink|href)=["'][^"']*\/admin[^"']*["']|\[routerLink\]="\[?'\/admin[^"]*"/g) ?? [];
}

/** Liens vers /connexion (et non /compte/connexion, qui est l'ecran coureur). */
function loginLinks(template: string): string[] {
  return template.match(/(?:routerLink|href)=["']\/connexion[^"']*["']/g) ?? [];
}

/** Cibles internes des liens d'un gabarit : routerLink="/x", href="/x" et [routerLink]="'/x'" (inc. 7, RG4). */
function linkTargets(template: string): string[] {
  const targets: string[] = [];
  for (const match of template.matchAll(/(?:routerLink|href)=["'](\/[^"']*)["']|\[routerLink\]="\[?'(\/[^']*)'/g)) {
    targets.push(match[1] ?? match[2]!);
  }
  return targets;
}

function mentionsAdministration(template: string): boolean {
  return /administration|administrateur/i.test(template);
}

describe('detecteurs (discriminants sur sources synthetiques)', () => {
  it('adminLinks voit routerLink, href et la forme tableau, pas un lien public', () => {
    expect(adminLinks('<a routerLink="/admin">x</a>')).toHaveLength(1);
    expect(adminLinks('<a href="/admin/comptes">x</a>')).toHaveLength(1);
    expect(adminLinks(`<a [routerLink]="['/admin/courses', id]">x</a>`)).toHaveLength(1);
    expect(adminLinks('<a routerLink="/scan">Scan</a><a routerLink="/">Courses</a>')).toHaveLength(0);
  });

  it('loginLinks voit /connexion mais pas /compte/connexion', () => {
    expect(loginLinks(`<a routerLink="/connexion" [queryParams]="{ retour: '/scan' }">x</a>`)).toHaveLength(1);
    expect(loginLinks('<a routerLink="/compte/connexion">x</a>')).toHaveLength(0);
  });

  it('mentionsAdministration voit « Administration » et « administrateur » quelle que soit la casse', () => {
    expect(mentionsAdministration('<a>Administration</a>')).toBe(true);
    expect(mentionsAdministration(`<h1>Accès réservé à l'administrateur</h1>`)).toBe(true);
    expect(mentionsAdministration('<a>Courses</a>')).toBe(false);
  });

  it('linkTargets liste les cibles internes sous les trois formes de lien', () => {
    expect(linkTargets(`<a routerLink="/">C</a><a href="/scan">S</a><a [routerLink]="'/inscription'">I</a>`)).toEqual([
      '/',
      '/scan',
      '/inscription',
    ]);
    expect(linkTargets('<a href="https://x.example/admin">x</a>')).toEqual([]);
  });

  it('templateOf extrait le gabarit inline et echoue sans gabarit', () => {
    expect(templateOf('@Component({ template: `<h1>Scan</h1>` })')).toBe('<h1>Scan</h1>');
    expect(() => templateOf('class A {}')).toThrow();
  });
});

// ----- RG2 : aucun lien public vers l'espace admin -----

describe("RG2 - aucun lien public vers l'espace admin (CA2, CL1)", () => {
  // Inc. 7, RG4 (categorie B, motif : la regle « aucun lien /connexion dans l'en-tete » de l'inc. 6 est amendee par
  // l'inc. 7 : /connexion et /inscription sont admis, et seulement eux). Aucune assertion retiree : aucun lien admin,
  // aucune mention « Administration », liens Courses / Mes inscriptions / Scan conserves ; ajouts : liste fermee des
  // cibles, presence des deux liens d'acces, aucune mention du staff.
  it("l'en-tete de navigation commun n'a ni lien admin, ni « Administration » ; seuls /connexion et /inscription s'ajoutent ; Courses, Mes inscriptions et Scan sont conserves", () => {
    const template = templateOf(read(new URL('app.ts', APP)));

    expect(adminLinks(template)).toEqual([]);
    expect(mentionsAdministration(template)).toBe(false);
    expect(template).toContain('routerLink="/">Courses</a>');
    expect(template).toContain('routerLink="/compte">Mes inscriptions</a>');
    expect(template).toContain('routerLink="/scan">Scan</a>');
    const targets = new Set(linkTargets(template));
    expect([...targets].filter((t) => !['/', '/compte', '/scan', '/connexion', '/inscription'].includes(t))).toEqual([]);
    expect(targets).toContain('/connexion');
    expect(targets).toContain('/inscription');
    expect(template).toContain('Se connecter');
    expect(template).toContain('Créer un compte');
  });

  it("l'en-tete ne mentionne jamais le staff : ni « benevole », ni « administrateur », ni lien /admin (RG4, RG7 inc. 6)", () => {
    const template = templateOf(read(new URL('app.ts', APP)));

    expect(template).not.toMatch(/b[ée]n[ée]vole|administrat|scanner/i);
    expect(template).not.toContain('/admin');
  });

  it("la page d'accueil n'a ni bouton admin, ni « Administration », ni lien /connexion ; le lien Scan est conserve", () => {
    const template = templateOf(read(new URL('pages/home/home-page.ts', APP)));

    expect(adminLinks(template)).toEqual([]);
    expect(mentionsAdministration(template)).toBe(false);
    expect(loginLinks(template)).toEqual([]);
    expect(template).toMatch(/routerLink="\/scan"/);
  });

  // Inc. 7 (categorie B, motif : l'ecran unique /connexion remplace l'ecran coureur ; runner-login-page.ts peut etre
  // supprime, RG3). Le nom de l'ecran d'inscription n'etant pas fige par la spec, il est identifie par son h1
  // « Creer un compte ». Aucune assertion affaiblie : le controle « aucun lien /admin, aucune mention » s'applique a
  // tous les fichiers presents, nouveaux compris.
  it('aucun ecran public (hors pages/admin) ne contient de lien admin ni de mention « Administration » dans son gabarit', () => {
    const pages = publicPageFiles();
    expect(pages.map((page) => page.name)).toEqual(
      expect.arrayContaining([
        'home/home-page.ts', 'board/board-page.ts', 'runner/runner-page.ts', 'registration/registration-page.ts',
        'account/account-page.ts', 'login/login-page.ts', 'scan/scan-page.ts', 'not-found/not-found-page.ts',
      ]),
    );
    const createAccountScreens = pages.filter((page) => /<h1>\s*Créer un compte\s*<\/h1>/.test(read(page.url)));
    expect(createAccountScreens, "ecran d'inscription autonome (h1 « Creer un compte ») introuvable").toHaveLength(1);
    for (const page of pages) {
      const template = templateOf(read(page.url));
      expect(adminLinks(template), `${page.name} : lien vers /admin`).toEqual([]);
      expect(mentionsAdministration(template), `${page.name} : mention de l'administration`).toBe(false);
    }
  });

  it('seul /scan lie /connexion (lien « Se connecter », retour vers /scan) ; aucun autre ecran public', () => {
    const withLoginLink = publicPageFiles()
      .filter((page) => loginLinks(templateOf(read(page.url))).length > 0)
      .map((page) => page.name);

    expect(withLoginLink).toEqual(['scan/scan-page.ts']);
    const scanTemplate = templateOf(read(new URL('pages/scan/scan-page.ts', APP)));
    expect(loginLinks(scanTemplate)).toHaveLength(1);
    expect(scanTemplate).toMatch(/routerLink="\/connexion"\s+\[queryParams\]="\{ retour: '\/scan' \}">Se connecter</);
  });
});

// ----- RG3 : /admin/** pour un non-admin = Page introuvable -----

describe('RG3 - /admin/** pour un non-admin (CA3, CA4)', () => {
  it("la coquille admin n'affiche plus « Accès réservé », ni « Se connecter en administrateur », ni lien /connexion", () => {
    const template = templateOf(read(new URL('pages/admin/admin-shell.ts', APP)));

    expect(template).not.toContain('Accès réservé');
    expect(template).not.toContain('Se connecter en administrateur');
    expect(loginLinks(template)).toEqual([]);
  });

  it("la garde des routes admin ne redirige plus vers /connexion (l'adresse reste celle demandee)", () => {
    const routes = read(new URL('app.routes.ts', APP));

    expect(routes).not.toMatch(/\['\/connexion'\]/);
  });

  it('la route /connexion reste publique, les routes admin et la route Page introuvable sont toujours declarees', () => {
    const routes = read(new URL('app.routes.ts', APP));

    expect(routes).toContain("path: 'connexion'");
    expect(routes).toContain("path: 'admin'");
    expect(routes).toContain("path: '**'");
    expect(routes).toContain('Page introuvable');
  });
});

// ----- RG7 : ecran de connexion sans mention de l'administration -----

describe("RG7 - ecran de connexion sans mention de l'administration (CA11)", () => {
  it('le gabarit de /connexion ne contient ni « Administration », ni « administrateur », ni lien /admin', () => {
    const template = templateOf(read(new URL('pages/login/login-page.ts', APP)));

    expect(mentionsAdministration(template)).toBe(false);
    expect(adminLinks(template)).toEqual([]);
    expect(template).not.toContain('/admin');
  });
});

// ----- RG5 : blocs admin hors du prechargement du service worker -----

interface AssetGroup {
  readonly name: string;
  readonly installMode: string;
  readonly updateMode: string;
  readonly urls: readonly string[];
}

const ADMIN_TEXTS = ['Administration des courses', 'Gérer la course et les coureurs', 'Imprimer les QR codes'];
const SCAN_TEXT = 'Le navigateur peut effacer les scans en attente';

function builtScripts(): { readonly names: string[]; readonly contentOf: (name: string) => string } {
  const browser = new URL('dist/backyard-pwa/browser/', FRONTEND);
  expect(existsSync(browser), 'build de production absent : lancer `npm run build` avant ce test').toBe(true);
  const names = readdirSync(browser, { withFileTypes: true })
    .filter((entry) => !entry.isDirectory() && entry.name.endsWith('.js'))
    .map((entry) => entry.name);
  return { names, contentOf: (name) => read(new URL(name, browser)) };
}

/** Urls couvertes par un groupe dont installMode ou updateMode vaut `prefetch`. */
function prefetchedUrls(): Set<string> {
  const manifest = JSON.parse(read(new URL('dist/backyard-pwa/browser/ngsw.json', FRONTEND))) as {
    assetGroups: AssetGroup[];
  };
  expect(manifest.assetGroups.length).toBeGreaterThan(0);
  const urls = new Set<string>();
  for (const group of manifest.assetGroups) {
    if (group.installMode === 'prefetch' || group.updateMode === 'prefetch') {
      group.urls.forEach((url) => urls.add(url));
    }
  }
  return urls;
}

/** Fichiers JS du build contenant un des textes ; liste vide = echec (pas de passage a vide). */
function scriptsContaining(texts: readonly string[]): string[] {
  const { names, contentOf } = builtScripts();
  const found = names.filter((name) => texts.some((text) => contentOf(name).includes(text)));
  expect(found, `aucun fichier du build ne contient ${texts.join(' / ')}`).not.toHaveLength(0);
  return found;
}

describe('RG5 - blocs admin hors du prechargement du service worker (CA6, build de production requis)', () => {
  it("aucun bloc admin (repere par ses textes propres) n'est couvert par un groupe en prefetch (installMode ou updateMode) de ngsw.json", () => {
    const adminBlocks = scriptsContaining(ADMIN_TEXTS);
    const prefetched = prefetchedUrls();

    for (const block of adminBlocks) {
      expect(prefetched.has(`/${block}`), `${block} (code admin) est precharge par le service worker`).toBe(false);
    }
  });

  it("le bloc de l'ecran de scan reste precharge : /scan reste utilisable hors ligne (RG45 inc. 4)", () => {
    const scanBlocks = scriptsContaining([SCAN_TEXT]);
    const prefetched = prefetchedUrls();

    expect(scanBlocks.some((block) => prefetched.has(`/${block}`)), "le code de /scan n'est plus precharge").toBe(true);
  });

  it("le bloc de scan n'est pas un bloc admin (le repere admin ne confond pas public et admin)", () => {
    const adminBlocks = new Set(scriptsContaining(ADMIN_TEXTS));
    const scanBlocks = scriptsContaining([SCAN_TEXT]);

    expect(scanBlocks.filter((block) => adminBlocks.has(block))).toEqual([]);
  });
});
