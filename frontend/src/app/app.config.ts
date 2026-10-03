import { provideHttpClient, withFetch, withXsrfConfiguration } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';

import { routes } from './app.routes';
import { SessionService } from './comptes/session.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // Les données de route (`data`) alimentent les entrées des composants routés.
    provideRouter(routes, withComponentInputBinding()),
    // Cookie posé par GET /api/csrf, renvoyé en en-tête sur les requêtes modifiantes (relatives).
    provideHttpClient(
      withFetch(),
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
    ),
    // Restauration de l'état connecté à chaque chargement, sans bloquer l'affichage :
    // l'en-tête et les gardes attendent la réponse de GET /api/comptes/moi.
    provideAppInitializer(() => {
      inject(SessionService).restaurer().subscribe();
    }),
  ],
};
