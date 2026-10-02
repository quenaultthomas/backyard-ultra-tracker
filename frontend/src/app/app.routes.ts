import { Routes } from '@angular/router';

import { Accueil } from './accueil/accueil';

export const routes: Routes = [
  { path: '', component: Accueil, title: 'Backyard Ultra Tracker' },
  {
    path: 'creer-compte',
    loadComponent: () => import('./comptes/creer-compte/creer-compte').then((m) => m.CreerCompte),
    title: 'Créer un compte - Backyard Ultra Tracker',
  },
  { path: '**', redirectTo: '' },
];
