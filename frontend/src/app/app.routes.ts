import { Routes } from '@angular/router';

import { Accueil } from './accueil/accueil';
import { reserveAuxAnonymes } from './comptes/reserve-aux-anonymes.guard';

export const routes: Routes = [
  { path: '', component: Accueil, title: 'Backyard Ultra Tracker' },
  {
    path: 'connexion',
    canActivate: [reserveAuxAnonymes],
    loadComponent: () => import('./comptes/connexion/connexion').then((m) => m.Connexion),
    title: 'Se connecter - Backyard Ultra Tracker',
  },
  {
    path: 'creer-compte',
    canActivate: [reserveAuxAnonymes],
    loadComponent: () => import('./comptes/creer-compte/creer-compte').then((m) => m.CreerCompte),
    title: 'Créer un compte - Backyard Ultra Tracker',
  },
  { path: '**', redirectTo: '' },
];
