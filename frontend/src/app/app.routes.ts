import { Routes } from '@angular/router';

import { Accueil } from './accueil/accueil';
import {
  reserveAAdminMaster,
  reserveAuxAdministrateurs,
} from './administration/reserve-aux-administrateurs.guard';
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
  {
    path: 'administration',
    canActivate: [reserveAuxAdministrateurs],
    loadComponent: () =>
      import('./administration/espace-administration/espace-administration').then(
        (m) => m.EspaceAdministration,
      ),
    title: 'Administration - Backyard Ultra Tracker',
  },
  {
    path: 'administration/admins',
    canActivate: [reserveAAdminMaster],
    loadComponent: () =>
      import('./administration/gestion-admins/gestion-admins').then((m) => m.GestionAdmins),
    title: 'Gestion des administrateurs - Backyard Ultra Tracker',
  },
  {
    path: 'acces-refuse',
    loadComponent: () => import('./comptes/acces-refuse/acces-refuse').then((m) => m.AccesRefuse),
    title: 'Accès refusé - Backyard Ultra Tracker',
  },
  { path: '**', redirectTo: '' },
];
