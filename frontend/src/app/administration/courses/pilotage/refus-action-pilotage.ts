import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';

import { RefusAccesService } from '../../../comptes/refus-acces.service';
import { lireProbleme } from '../../../partage/probleme';
import {
  CODE_COURSE_INTROUVABLE,
  ECRAN_GESTION_COURSES,
  ETAT_MESSAGE_ERREUR,
  MESSAGE_COURSE_INTROUVABLE,
} from '../erreurs-course';

/** Refus d'une action du pilotage qui ferme le panneau ; `rafraichir` : instantané redemandé aussitôt. */
export interface RefusActionPilotage {
  message: string;
  rafraichir: boolean;
}

/** Refus d'une action du pilotage qui font quitter l'écran (session, rôle, Course introuvable). */
@Injectable({ providedIn: 'root' })
export class RedirectionActionPilotage {
  private readonly refusAcces = inject(RefusAccesService);
  private readonly router = inject(Router);

  /** 401, 403 (hors CSRF) ou 404 `COURSE_INTROUVABLE` : redirection, et `true`. */
  rediriger(erreur: unknown, courseId: string): boolean {
    if (this.refusAcces.rediriger(erreur, `${ECRAN_GESTION_COURSES}/${courseId}/pilotage`)) {
      return true;
    }
    if (lireProbleme(erreur)?.code !== CODE_COURSE_INTROUVABLE) {
      return false;
    }
    void this.router.navigateByUrl(ECRAN_GESTION_COURSES, {
      state: { [ETAT_MESSAGE_ERREUR]: MESSAGE_COURSE_INTROUVABLE },
    });
    return true;
  }
}
