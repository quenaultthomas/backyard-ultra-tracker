import { provideHttpClient, withFetch, withXsrfConfiguration } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  isDevMode,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';

import { routes } from './app.routes';
import { RevalidationSessionService } from './comptes/revalidation-session.service';
import { SessionService } from './comptes/session.service';
import { FileScansService } from './scan/file-scans/file-scans.service';

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
    // Session mémorisée ouverte hors ligne : relue au retour du réseau.
    provideAppInitializer(() => {
      inject(RevalidationSessionService);
    }),
    // File de scans du bénévole : son cycle d'envoi tourne sur toute page de l'application.
    provideAppInitializer(() => {
      inject(FileScansService);
    }),
    // Coque de l'application en cache pour l'ouverture hors ligne (build de production seulement).
    // La file de scans empêche la stabilité : enregistrement au plus tard 5 s après le démarrage.
    // Un échec d'enregistrement (navigateur bloquant, contexte non sécurisé) n'est pas bloquant.
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode(),
      registrationStrategy: 'registerWhenStable:5000',
    }),
  ],
};
