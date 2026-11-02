import { expect, type Page } from '@playwright/test';

/** Formulaires de création à la demande (9.2) : préfixe des `data-testid`, bouton d'ouverture et premier champ. */
export const FORMULAIRES = {
  course: { bouton: 'course-bouton-nouvelle', formulaire: 'course-formulaire', premierChamp: 'course-champ-nom', libelle: 'Nouvelle course', id: 'formulaire-course' },
  admin: { bouton: 'admin-bouton-nouveau', formulaire: 'admin-formulaire', premierChamp: 'admin-champ-pseudo', libelle: 'Nouvel admin', id: 'formulaire-admin' },
  benevole: { bouton: 'benevole-bouton-nouveau', formulaire: 'benevole-formulaire', premierChamp: 'benevole-champ-pseudo', libelle: 'Nouveau bénévole', id: 'formulaire-benevole' },
} as const;

export type NomFormulaire = keyof typeof FORMULAIRES;

/**
 * Ouvre le formulaire par son bouton d'ouverture (9.2 RG3) : attend le formulaire visible,
 * `aria-expanded="true"` et le focus sur le premier champ. Sans effet sur un formulaire déjà ouvert.
 */
export async function ouvrirFormulaire(page: Page, nom: NomFormulaire): Promise<void> {
  const f = FORMULAIRES[nom];
  await page.getByTestId(f.bouton).click();
  await expect(page.getByTestId(f.formulaire)).toBeVisible();
  await expect(page.getByTestId(f.bouton)).toHaveAttribute('aria-expanded', 'true');
  await expect(page.getByTestId(f.premierChamp)).toBeFocused();
}

/** Le formulaire est fermé : masqué, `aria-expanded="false"` sur le bouton d'ouverture. */
export async function verifierFerme(page: Page, nom: NomFormulaire): Promise<void> {
  const f = FORMULAIRES[nom];
  await expect(page.getByTestId(f.formulaire)).not.toBeVisible();
  await expect(page.getByTestId(f.bouton)).toHaveAttribute('aria-expanded', 'false');
}

/**
 * Ouvre le panneau « Rechercher un coureur » (9.5) par son bouton : attend le panneau visible,
 * `aria-expanded="true"` et le focus sur le champ de recherche. `prefixe` : `benevole` ou `admin`.
 */
export async function ouvrirPanneauRecherche(page: Page, prefixe: 'benevole' | 'admin'): Promise<void> {
  await page.getByTestId(`${prefixe}-bouton-promouvoir`).click();
  await expect(page.getByTestId(`${prefixe}-promotion`)).toBeVisible();
  await expect(page.getByTestId(`${prefixe}-bouton-promouvoir`)).toHaveAttribute('aria-expanded', 'true');
  await expect(page.getByTestId(`${prefixe}-promotion-recherche`)).toBeFocused();
}
