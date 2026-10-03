import { Routes } from '@angular/router';

import { Accueil } from './accueil/accueil';
import {
  reserveAAdminMaster,
  reserveAuxAdministrateurs,
} from './administration/reserve-aux-administrateurs.guard';
import { ADMINS, BENEVOLES } from './administration/gestion-comptes/comptes-geres';
import { reserveAuxAnonymes } from './comptes/reserve-aux-anonymes.guard';
import { reserveAuxComptesConnectes } from './comptes/reserve-aux-roles.guard';
import { reserveAuxCoureurs } from './inscriptions/reserve-aux-coureurs.guard';
import { reserveAuxBenevoles } from './scan/reserve-aux-benevoles.guard';

const chargerGestionComptes = () =>
  import('./administration/gestion-comptes/gestion-comptes').then((m) => m.GestionComptes);

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
    path: 'mon-compte',
    canActivate: [reserveAuxComptesConnectes],
    loadComponent: () => import('./comptes/mon-compte/mon-compte').then((m) => m.MonCompte),
    title: 'Mon compte - Backyard Ultra Tracker',
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
    loadComponent: chargerGestionComptes,
    data: { comptesGeres: ADMINS },
    title: 'Gestion des administrateurs - Backyard Ultra Tracker',
  },
  {
    path: 'administration/benevoles',
    canActivate: [reserveAuxAdministrateurs],
    loadComponent: chargerGestionComptes,
    data: { comptesGeres: BENEVOLES },
    title: 'Gestion des bénévoles - Backyard Ultra Tracker',
  },
  {
    path: 'administration/courses',
    canActivate: [reserveAuxAdministrateurs],
    loadComponent: () =>
      import('./administration/courses/gestion-courses/gestion-courses').then(
        (m) => m.GestionCourses,
      ),
    title: 'Gestion des courses - Backyard Ultra Tracker',
  },
  {
    path: 'administration/courses/:id',
    canActivate: [reserveAuxAdministrateurs],
    loadComponent: () =>
      import('./administration/courses/fiche/fiche-course').then((m) => m.FicheCourse),
    title: 'Fiche de la course - Backyard Ultra Tracker',
  },
  {
    path: 'benevole',
    canActivate: [reserveAuxBenevoles],
    loadComponent: () =>
      import('./scan/accueil-benevole/accueil-benevole').then((m) => m.AccueilBenevole),
    title: 'Espace bénévole - Backyard Ultra Tracker',
  },
  {
    path: 'coureur',
    canActivate: [reserveAuxCoureurs],
    loadComponent: () =>
      import('./inscriptions/accueil-coureur/accueil-coureur').then((m) => m.AccueilCoureur),
    title: 'Courses ouvertes - Backyard Ultra Tracker',
  },
  {
    path: 'acces-refuse',
    loadComponent: () => import('./comptes/acces-refuse/acces-refuse').then((m) => m.AccesRefuse),
    title: 'Accès refusé - Backyard Ultra Tracker',
  },
  { path: '**', redirectTo: '' },
];
