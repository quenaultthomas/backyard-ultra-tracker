import { type Page } from '@playwright/test';
import { MOT_DE_PASSE, creerCompteParApi, ouvrirConnexion, pseudoUnique, saisir } from './aide-connexion';
import { avancerHorloge, expect, test } from './aide-horloge';

/* Incrément 4.3 CA15 : fin du blocage de connexion (1.3), différée jusqu'à l'horloge pilotable. */
test.describe.configure({ mode: 'serial' });

const MOT_DE_PASSE_FAUX = 'mauvais-mot-de-passe-1';
const MESSAGE_ECHEC = 'Pseudo ou mot de passe incorrect.';
const MESSAGE_BLOCAGE = 'Trop de tentatives de connexion. Réessayez dans 15 minutes.';

async function tenter(page: Page, pseudo: string, motDePasse: string): Promise<void> {
  await saisir(page, pseudo, motDePasse);
  await page.getByTestId('bouton-connexion').click();
}

test('CA15 - le blocage de connexion prend fin quand l\'horloge avance de 16 minutes', async ({ page, request, playwright }) => {
  const pseudo = pseudoUnique();
  await creerCompteParApi(request, pseudo);
  await ouvrirConnexion(page);

  for (let i = 0; i < 5; i++) {
    await tenter(page, pseudo, MOT_DE_PASSE_FAUX);
    await expect(page.getByTestId('erreur-generale')).toHaveText(MESSAGE_ECHEC);
    await expect(page.getByTestId('champ-mot-de-passe')).toHaveValue('');
  }
  await tenter(page, pseudo, MOT_DE_PASSE);
  await expect(page.getByTestId('erreur-generale')).toHaveText(MESSAGE_BLOCAGE);
  await expect(page).toHaveURL(/\/connexion$/);

  await avancerHorloge(playwright, 'PT16M');

  await tenter(page, pseudo, MOT_DE_PASSE);
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByTestId('entete-pseudo')).toHaveText(pseudo);
});
