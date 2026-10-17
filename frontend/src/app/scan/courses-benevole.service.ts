import { DOCUMENT, Injectable, inject } from '@angular/core';
import {
  EMPTY,
  Observable,
  catchError,
  expand,
  fromEvent,
  map,
  of,
  switchMap,
  take,
  tap,
  throwError,
} from 'rxjs';

import { SessionService } from '../comptes/session.service';
import { CLE_COURSES_BENEVOLE, MemoireLocaleService } from '../partage/memoire-locale.service';
import { estSansReponseDuServeur } from '../partage/reseau.service';
import { BenevoleApiService } from './benevole-api.service';
import { CourseBenevoleReponse } from './course-benevole';

/** Liste des Courses du bénévole ; `enregistreLe` (ISO) quand elle vient de l'appareil, hors ligne. */
export interface CoursesDuBenevole {
  courses: CourseBenevoleReponse[];
  enregistreLe: string | null;
}

interface CoursesMemorisees {
  compteId: string;
  enregistreLe: string;
  courses: CourseBenevoleReponse[];
}

/**
 * Courses du bénévole connecté, lues sur le serveur et mémorisées sur l'appareil. Sans réponse du
 * serveur, la dernière liste du même Compte est rendue ; toute réponse du serveur (401, 403, 500…)
 * reste une erreur, traitée par l'écran comme avant.
 */
@Injectable({ providedIn: 'root' })
export class CoursesBenevoleService {
  private readonly benevoleApi = inject(BenevoleApiService);
  private readonly session = inject(SessionService);
  private readonly memoire = inject(MemoireLocaleService);
  private readonly fenetre = inject(DOCUMENT).defaultView;

  /** Liste du serveur, ou mémorisée hors ligne puis relue à chaque retour du réseau. */
  suivre(): Observable<CoursesDuBenevole> {
    return this.lire().pipe(
      expand((liste) =>
        liste.enregistreLe === null || !this.fenetre
          ? EMPTY
          : fromEvent(this.fenetre, 'online').pipe(
              take(1),
              switchMap(() => this.lire()),
            ),
      ),
    );
  }

  private lire(): Observable<CoursesDuBenevole> {
    const compteId = this.session.compte()?.id ?? null;
    return this.benevoleApi.listerMesCourses().pipe(
      tap((courses) => this.memoriser(compteId, courses)),
      map((courses) => ({ courses, enregistreLe: null })),
      catchError((erreur: unknown) => {
        const memorisees = estSansReponseDuServeur(erreur) ? this.lireMemorisees(compteId) : null;
        return memorisees
          ? of({ courses: memorisees.courses, enregistreLe: memorisees.enregistreLe })
          : throwError(() => erreur);
      }),
    );
  }

  private memoriser(compteId: string | null, courses: CourseBenevoleReponse[]): void {
    if (compteId !== null) {
      const memorisees: CoursesMemorisees = {
        compteId,
        enregistreLe: new Date().toISOString(),
        // Champs du contrat seulement : rien d'autre que ce que l'écran affiche n'est gardé.
        courses: courses.map(({ id, nom, date, statut, logoUrl }) => ({
          id,
          nom,
          date,
          statut,
          logoUrl,
        })),
      };
      this.memoire.ecrire(CLE_COURSES_BENEVOLE, memorisees);
    }
  }

  private lireMemorisees(compteId: string | null): CoursesMemorisees | null {
    const valeur = this.memoire.lire(CLE_COURSES_BENEVOLE) as Partial<CoursesMemorisees> | null;
    return compteId !== null &&
      valeur?.compteId === compteId &&
      typeof valeur.enregistreLe === 'string' &&
      Array.isArray(valeur.courses)
      ? (valeur as CoursesMemorisees)
      : null;
  }
}
