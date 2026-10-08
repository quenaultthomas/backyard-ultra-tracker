import { expect, test, type Locator, type Page } from '@playwright/test';
import { exigerIdentifiantsAdminMaster } from './aide-admin';
import { placerStatutCourseEnBase, placerStatutInscriptionEnBase } from './aide-coureur';
import { ouvrirMenuCompte } from './aide-entete';
import { ECRANS, ETATS, ouvrirScene, ouvrirSession, preparerJeuReference, type JeuReference } from './aide-theme';

test.beforeAll(() => {
  exigerIdentifiantsAdminMaster();
});

const ecran = (nom: string) => ECRANS.find((e) => e.nom === nom)!;
const etat = (nom: string) => ETATS.find((e) => e.nom === nom)!;

const FORET = 'rgb(20, 53, 42)';
const FORET_SURVOL = 'rgb(31, 90, 67)';
const SURFACE = 'rgb(255, 252, 245)';
const CREME = 'rgb(247, 241, 227)';
const DANGER = 'rgb(155, 28, 28)';
const TRANSPARENT = 'rgba(0, 0, 0, 0)';

async function hauteur(l: Locator): Promise<number> {
  return (await l.boundingBox())!.height;
}

test('CA9 - le bouton principal, son survol, le bouton désactivé et la réduction de mouvement suivent la charte', async ({ page, playwright }) => {
  const jeu = await preparerJeuReference(playwright);
  await ouvrirScene(page, ecran('connexion'), jeu);
  const bouton = page.getByTestId('bouton-connexion');
  await expect(bouton).toHaveClass(/\bbouton\b/);
  await expect(bouton).toHaveCSS('background-color', FORET);
  await expect(bouton).toHaveCSS('color', SURFACE);
  await expect(bouton).toHaveCSS('border-radius', '6px');
  expect(await hauteur(bouton)).toBeGreaterThanOrEqual(44);

  await bouton.hover();
  await expect(bouton).toHaveCSS('background-color', FORET_SURVOL);
  await page.mouse.move(0, 0);

  // Réduction de mouvement : plus aucune transition.
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await expect(bouton).toHaveCSS('transition-duration', '0s');
  await page.emulateMedia({ reducedMotion: 'no-preference' });
});

test('CA9 - un bouton désactivé pendant l\'envoi prend les couleurs de désactivation et le curseur wait', async ({ page, playwright }) => {
  const jeu = await preparerJeuReference(playwright);
  const nettoyage = await ouvrirScene(page, etat('connexion avec bouton desactive pendant l envoi'), jeu);
  const bouton = page.getByTestId('bouton-connexion');
  await page.mouse.move(0, 0);
  await expect(bouton).toBeDisabled();
  await expect(bouton).toHaveCSS('background-color', 'rgb(221, 214, 195)');
  await expect(bouton).toHaveCSS('color', 'rgb(95, 106, 98)');
  await expect(bouton).toHaveCSS('cursor', 'wait');
  await nettoyage?.();
});

test('CA9 - les boutons de la gestion des courses (secondaire, danger contour, danger, sur fond sombre) suivent la charte', async ({ page, playwright }) => {
  const jeu = await preparerJeuReference(playwright);
  await ouvrirScene(page, ecran('gestion des courses'), jeu);
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const ligne = page.getByTestId('ligne-course').filter({ has: page.getByTestId('course-nom').getByText(jeu.courseA.nom, { exact: true }) });

  const secondaire = ligne.getByTestId('course-bouton-modifier');
  await expect(secondaire).toHaveClass(/ligne__action/);
  await expect(secondaire).toHaveCSS('background-color', SURFACE);
  await expect(secondaire).toHaveCSS('color', FORET);
  await expect(secondaire).toHaveCSS('border-top-color', FORET);
  expect(await hauteur(secondaire)).toBeGreaterThanOrEqual(44);

  const supprimer = ligne.getByTestId('course-bouton-supprimer');
  await expect(supprimer).toHaveClass(/ligne__action--danger/);
  await page.mouse.move(0, 0);
  await expect(supprimer).toHaveCSS('border-top-color', DANGER);
  await expect(supprimer).toHaveCSS('color', DANGER);

  await supprimer.click();
  const confirmer = ligne.getByTestId('course-bouton-confirmer-suppression');
  await expect(confirmer).toBeVisible();
  await expect(confirmer).toHaveClass(/ligne__action--danger-plein/);
  await page.mouse.move(0, 0);
  await expect(confirmer).toHaveCSS('background-color', DANGER);
  await expect(confirmer).toHaveCSS('color', SURFACE);

  await ouvrirMenuCompte(page);
  const deconnexion = page.getByTestId('bouton-deconnexion');
  const lienCompte = page.getByTestId('menu-lien-mon-compte');
  await expect(deconnexion).toHaveCSS('background-color', TRANSPARENT);
  await expect(deconnexion).toHaveCSS('color', CREME);
  expect(await hauteur(deconnexion)).toBeGreaterThanOrEqual(44);
  await expect(deconnexion).toHaveCSS('color', await lienCompte.evaluate((e) => getComputedStyle(e).color));
  await expect(deconnexion).toHaveCSS('font-weight', await lienCompte.evaluate((e) => getComputedStyle(e).fontWeight));
  expect(await hauteur(deconnexion)).toBe(await hauteur(lienCompte));
});

test('CA10 - le message d\'erreur de connexion, les champs et la carte suivent la charte', async ({ page, playwright }) => {
  const jeu = await preparerJeuReference(playwright);
  await page.setViewportSize({ width: 1280, height: 800 });
  await ouvrirScene(page, etat('connexion avec erreur affichee'), jeu);
  const alerte = page.getByTestId('erreur-generale');
  await expect(alerte).toHaveAttribute('role', 'alert');
  await expect(alerte).toHaveText('Pseudo ou mot de passe incorrect.');
  await expect(alerte).toHaveCSS('background-color', 'rgb(251, 233, 228)');
  await expect(alerte).toHaveCSS('color', DANGER);
  await expect(alerte).toHaveCSS('border-left-width', '4px');

  for (const id of ['champ-pseudo', 'champ-mot-de-passe']) {
    const champ = page.getByTestId(id);
    await expect(champ).toHaveCSS('background-color', SURFACE);
    await expect(champ).toHaveCSS('border-top-width', '1px');
    await expect(champ).toHaveCSS('border-top-color', 'rgb(111, 122, 110)');
    expect(await hauteur(champ)).toBeGreaterThanOrEqual(44);
  }

  const carte = page.locator('section.carte').first();
  await expect(carte).toHaveCSS('background-color', SURFACE);
  const rayon = await carte.evaluate((e) => parseFloat(getComputedStyle(e).borderTopLeftRadius));
  expect(rayon).toBeGreaterThanOrEqual(12);
  expect(await carte.evaluate((e) => getComputedStyle(e).boxShadow)).not.toBe('none');
  expect((await carte.boundingBox())!.width).toBe(416);
});

test('CA10 - un champ aria-invalid a une bordure de 2px de la couleur danger', async ({ page, playwright }) => {
  const jeu = await preparerJeuReference(playwright);
  await ouvrirScene(page, etat('creation de compte avec champs invalides'), jeu);
  const champ = page.getByTestId('champ-pseudo');
  await expect(champ).toHaveAttribute('aria-invalid', 'true');
  await expect(champ).toHaveCSS('border-top-width', '2px');
  await expect(champ).toHaveCSS('border-top-color', DANGER);
  await expect(page.getByTestId('erreur-pseudo')).toBeVisible();
});

interface Attendu { classe: string; fond: string; texte: string; libelle: string }
const PASTILLES: Record<string, Attendu> = {
  EN_PREPARATION: { classe: 'pastille-statut--en-preparation', fond: 'rgb(239, 230, 208)', texte: 'rgb(74, 90, 80)', libelle: 'En préparation' },
  EN_COURS: { classe: 'pastille-statut--en-cours', fond: 'rgb(251, 233, 207)', texte: 'rgb(168, 67, 0)', libelle: 'En cours' },
  TERMINEE: { classe: 'pastille-statut--terminee', fond: FORET, texte: CREME, libelle: 'Terminée' },
  EN_COURSE: { classe: 'pastille-statut--en-course', fond: 'rgb(227, 239, 230)', texte: FORET, libelle: 'En course' },
  ABANDON: { classe: 'pastille-statut--abandon', fond: 'rgb(251, 233, 228)', texte: DANGER, libelle: 'Abandon' },
  VAINQUEUR: { classe: 'pastille-statut--vainqueur', fond: 'rgb(242, 140, 40)', texte: 'rgb(27, 42, 34)', libelle: 'Vainqueur' },
};

async function verifierPastille(pastille: Locator, attendu: Attendu): Promise<void> {
  await expect(pastille).toHaveClass(/\bpastille-statut\b/);
  await expect(pastille).toHaveClass(new RegExp(`\\b${attendu.classe}\\b`));
  await expect(pastille).toHaveText(attendu.libelle);
  await expect(pastille).toHaveCSS('background-color', attendu.fond);
  await expect(pastille).toHaveCSS('color', attendu.texte);
  const mesures = await pastille.evaluate((e) => ({
    rayon: parseFloat(getComputedStyle(e).borderTopLeftRadius),
    puce: getComputedStyle(e, '::before').content,
  }));
  expect(mesures.rayon).toBeGreaterThanOrEqual(12);
  expect(mesures.puce).not.toBe('none');
}

const ligneInscription = (page: Page, nom: string) =>
  page.getByTestId('inscriptions-ligne').filter({ has: page.getByTestId('inscriptions-course-nom').getByText(nom, { exact: true }) });
const ligneBenevole = (page: Page, nom: string) =>
  page.getByTestId('benevole-ligne-course').filter({ has: page.getByTestId('benevole-course-nom').getByText(nom, { exact: true }) });

async function rechargerInscriptions(page: Page): Promise<void> {
  await page.goto('/coureur/inscriptions');
  await expect(page.getByTestId('inscriptions-liste')).toBeVisible();
}

test('CA11 - les pastilles de statut de Course et d\'Inscription suivent la charte à chaque statut', async ({ page, playwright }) => {
  test.setTimeout(120_000);
  const jeu: JeuReference = await preparerJeuReference(playwright);
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await ouvrirSession(page, 'coureur', jeu);
  await rechargerInscriptions(page);

  // Course A EN_PREPARATION, Inscription EN_COURSE ; Course B EN_COURS.
  const a = ligneInscription(page, jeu.courseA.nom);
  const b = ligneInscription(page, jeu.courseB.nom);
  await verifierPastille(a.getByTestId('inscriptions-course-statut'), PASTILLES['EN_PREPARATION']);
  await verifierPastille(a.getByTestId('inscriptions-statut'), PASTILLES['EN_COURSE']);
  await verifierPastille(b.getByTestId('inscriptions-course-statut'), PASTILLES['EN_COURS']);

  placerStatutCourseEnBase(jeu.courseB.id, 'TERMINEE');
  await rechargerInscriptions(page);
  await verifierPastille(ligneInscription(page, jeu.courseB.nom).getByTestId('inscriptions-course-statut'), PASTILLES['TERMINEE']);

  placerStatutInscriptionEnBase(jeu.inscriptionB.id, 'ABANDON');
  await rechargerInscriptions(page);
  await verifierPastille(ligneInscription(page, jeu.courseB.nom).getByTestId('inscriptions-statut'), PASTILLES['ABANDON']);

  placerStatutInscriptionEnBase(jeu.inscriptionB.id, 'VAINQUEUR');
  await rechargerInscriptions(page);
  await verifierPastille(ligneInscription(page, jeu.courseB.nom).getByTestId('inscriptions-statut'), PASTILLES['VAINQUEUR']);
});

test('CA11 - le bénévole voit la pastille de statut de chaque Course', async ({ page, playwright }) => {
  const jeu = await preparerJeuReference(playwright);
  await page.emulateMedia({ reducedMotion: 'reduce' });
  await ouvrirScene(page, ecran('espace benevole'), jeu);
  await verifierPastille(ligneBenevole(page, jeu.courseA.nom).getByTestId('benevole-course-statut'), PASTILLES['EN_PREPARATION']);
  await verifierPastille(ligneBenevole(page, jeu.courseB.nom).getByTestId('benevole-course-statut'), PASTILLES['EN_COURS']);
});

test('CA11 - la fiche d\'une Course ne contient ni svg, ni img, ni canvas, ni b dans les inscrits et les statuts restent en texte', async ({ page, playwright }) => {
  const jeu = await preparerJeuReference(playwright);
  await ouvrirScene(page, ecran('fiche de course'), jeu);
  const inscrits = page.getByTestId('fiche-inscrits');
  await expect(inscrits.locator('svg, img, canvas, b')).toHaveCount(0);
  await expect(page.getByTestId('fiche-inscrit-statut').first()).toHaveText('En course');
  await expect(page.getByTestId('fiche-statut')).toHaveText('En préparation');
  expect(await page.getByTestId('fiche-inscrit-statut').first().evaluate((e) => e.classList.contains('pastille-statut'))).toBe(false);
});
