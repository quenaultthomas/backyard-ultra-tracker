import { inject } from '@angular/core';
import { CanActivateFn, Router, Routes } from '@angular/router';
import { SessionService } from './infra/session.service';
import { AuthState } from './infra/auth-state';

/**
 * Sans connexion, l'administration affiche l'écran de connexion et n'émet aucune requête /api/admin/** (RG36).
 * Un compte SCANNER accède à la coquille, qui affiche « Accès réservé à l'administrateur » (RG10).
 */
const requireSignedIn: CanActivateFn = async (_route, state) => {
  const sessionService = inject(SessionService);
  const authState = inject(AuthState);
  const router = inject(Router);
  await sessionService.ready;
  if (authState.session() !== null) {
    return true;
  }
  return router.createUrlTree(['/connexion'], { queryParams: { retour: state.url } });
};

/** Routes de la PWA (RG5), liste fermée. Aucune ne commence par /api. */
export const routes: Routes = [
  {
    path: '',
    title: 'Courses — Backyard Ultra Tracker',
    loadComponent: () => import('./pages/home/home-page').then((m) => m.HomePage),
  },
  {
    path: 'courses/:raceId',
    title: 'Tableau de bord — Backyard Ultra Tracker',
    loadComponent: () => import('./pages/board/board-page').then((m) => m.BoardPage),
  },
  {
    path: 'coureurs/:runnerId',
    title: 'Coureur — Backyard Ultra Tracker',
    loadComponent: () => import('./pages/runner/runner-page').then((m) => m.RunnerPage),
  },
  {
    path: 'inscription/:raceId',
    title: 'Inscription — Backyard Ultra Tracker',
    loadComponent: () => import('./pages/registration/registration-page').then((m) => m.RegistrationPage),
  },
  {
    path: 'connexion',
    title: 'Connexion — Backyard Ultra Tracker',
    loadComponent: () => import('./pages/login/login-page').then((m) => m.LoginPage),
  },
  {
    path: 'scan',
    title: 'Scan — Backyard Ultra Tracker',
    loadComponent: () => import('./pages/scan/scan-page').then((m) => m.ScanPage),
  },
  {
    path: 'admin',
    canActivate: [requireSignedIn],
    loadComponent: () => import('./pages/admin/admin-shell').then((m) => m.AdminShell),
    children: [
      {
        path: '',
        title: 'Administration — Backyard Ultra Tracker',
        loadComponent: () => import('./pages/admin/admin-races-page').then((m) => m.AdminRacesPage),
      },
      {
        path: 'courses/:raceId',
        title: 'Course (admin) — Backyard Ultra Tracker',
        loadComponent: () => import('./pages/admin/admin-race-page').then((m) => m.AdminRacePage),
      },
      {
        path: 'courses/:raceId/qr',
        title: 'QR codes — Backyard Ultra Tracker',
        loadComponent: () => import('./pages/admin/admin-qr-sheet-page').then((m) => m.AdminQrSheetPage),
      },
      {
        path: '**',
        title: 'Page introuvable — Backyard Ultra Tracker',
        loadComponent: () => import('./pages/not-found/not-found-page').then((m) => m.NotFoundPage),
      },
    ],
  },
  {
    path: '**',
    title: 'Page introuvable — Backyard Ultra Tracker',
    loadComponent: () => import('./pages/not-found/not-found-page').then((m) => m.NotFoundPage),
  },
];
