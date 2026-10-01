import { inject } from '@angular/core';
import { CanActivateFn, CanMatchFn, Router, Routes } from '@angular/router';
import { SessionService } from './infra/session.service';
import { AuthState } from './infra/auth-state';
import { RunnerAuthState } from './infra/runner-auth-state';
import { RUNNER_LOGIN_ROUTE, RunnerSessionService } from './infra/runner-session.service';

/**
 * L'administration n'existe que pour le rôle ADMIN de la session staff (RG3, RG4 inc. 6). Pour tout autre visiteur
 * (anonyme, SCANNER, coureur, dont les identifiants ne sont pas lus ici), la route `admin` ne correspond pas : le
 * routeur passe à la route `**` (« Page introuvable »), l'adresse reste celle demandée, aucun bloc admin n'est
 * chargé et aucune requête /api/admin/** n'est émise.
 */
const matchAdminOnly: CanMatchFn = async () => {
  const sessionService = inject(SessionService);
  const authState = inject(AuthState);
  await sessionService.ready;
  return authState.role() === 'ADMIN';
};

/** « Mes inscriptions » exige une connexion coureur (RG21 inc. 5) ; sinon, écran de connexion coureur. */
const requireRunnerSignedIn: CanActivateFn = async (_route, state) => {
  const runnerSession = inject(RunnerSessionService);
  const runnerAuth = inject(RunnerAuthState);
  const router = inject(Router);
  await runnerSession.ready;
  if (runnerAuth.session() !== null) {
    return true;
  }
  return router.createUrlTree([RUNNER_LOGIN_ROUTE], { queryParams: { retour: state.url } });
};

/**
 * Routes de la PWA (RG5), liste fermée. Aucune ne commence par /api. Une URL inconnue sous /admin ne correspond à
 * aucun enfant de `admin`, et un non-ADMIN ne correspond pas à `admin` : dans les deux cas, le routeur passe à la
 * route `**` finale et affiche « Page introuvable » (CA21 inc. 4, RG3 inc. 6).
 */
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
    path: 'compte',
    title: 'Mes inscriptions — Backyard Ultra Tracker',
    canActivate: [requireRunnerSignedIn],
    loadComponent: () => import('./pages/account/account-page').then((m) => m.AccountPage),
  },
  {
    path: 'compte/connexion',
    title: 'Connexion coureur — Backyard Ultra Tracker',
    loadComponent: () => import('./pages/account/runner-login-page').then((m) => m.RunnerLoginPage),
  },
  {
    path: 'scan',
    title: 'Scan — Backyard Ultra Tracker',
    loadComponent: () => import('./pages/scan/scan-page').then((m) => m.ScanPage),
  },
  {
    path: 'admin',
    canMatch: [matchAdminOnly],
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
        path: 'comptes',
        title: 'Comptes (admin) — Backyard Ultra Tracker',
        loadComponent: () => import('./pages/admin/admin-accounts-page').then((m) => m.AdminAccountsPage),
      },
      {
        path: 'courses/:raceId/qr',
        title: 'QR codes — Backyard Ultra Tracker',
        loadComponent: () => import('./pages/admin/admin-qr-sheet-page').then((m) => m.AdminQrSheetPage),
      },
    ],
  },
  {
    path: '**',
    title: 'Page introuvable — Backyard Ultra Tracker',
    loadComponent: () => import('./pages/not-found/not-found-page').then((m) => m.NotFoundPage),
  },
];
