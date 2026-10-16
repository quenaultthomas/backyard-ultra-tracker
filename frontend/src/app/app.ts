import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { Entete } from './entete/entete';
import { VERSION } from './version';

@Component({
  selector: 'app-root',
  imports: [Entete, RouterOutlet],
  template: `
    <app-entete />
    <router-outlet />
    <footer class="version">v{{ version }}</footer>
  `,
  styles: `
    /* Hors flux : n'ajoute aucune hauteur à la page ni aucun élément cliquable. */
    .version {
      position: fixed;
      right: 0.5rem;
      bottom: 0.25rem;
      font-size: 0.8125rem;
      color: var(--couleur-encre-douce);
      pointer-events: none;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly version = VERSION;
}
