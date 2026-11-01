import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, PRIMARY_OUTLET, Router, RouterLink } from '@angular/router';
import { filter, map } from 'rxjs';

import { SessionService } from '../../comptes/session.service';
import { EntreeAdministration, entreesAdministration } from './entrees-administration';

/** `page` : URL de l'entrée ; `true` : sous-page de sa section ; `null` : autre section. */
type Courante = 'page' | 'true' | null;

interface EntreeAffichee extends EntreeAdministration {
  readonly courante: Courante;
}

function segments(chemin: string): string[] {
  return chemin.split('/').filter((segment) => segment !== '');
}

/** Correspondance par préfixe de segment entre le chemin affiché et la route d'une entrée. */
function correspondance(cheminAffiche: readonly string[], route: string): Courante {
  const attendu = segments(route);
  if (attendu.some((segment, i) => cheminAffiche[i] !== segment)) {
    return null;
  }
  return cheminAffiche.length === attendu.length ? 'page' : 'true';
}

/**
 * Menu de navigation des écrans d'administration : entrées par rôle de la session (sans requête),
 * entrée de la section affichée mise en évidence. Les gardes et l'API restent la référence.
 */
@Component({
  selector: 'app-menu-administration',
  imports: [RouterLink],
  templateUrl: './menu-administration.html',
  styleUrl: './menu-administration.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MenuAdministration {
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((evenement) => evenement instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );

  private readonly cheminAffiche = computed(() => {
    const groupe = this.router.parseUrl(this.url()).root.children[PRIMARY_OUTLET];
    return groupe?.segments.map((segment) => segment.path) ?? [];
  });

  protected readonly entrees = computed<readonly EntreeAffichee[]>(() =>
    entreesAdministration(this.session.compte()?.role ?? null).map((entree) => ({
      ...entree,
      courante: correspondance(this.cheminAffiche(), entree.route),
    })),
  );
}
