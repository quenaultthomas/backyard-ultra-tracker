import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';

import { SessionService } from '../comptes/session.service';
import { entreesAccueil } from './entrees-accueil';

/**
 * Accueil, identique pour tous : titre et accroche sur le décor backyard, puis les actions du
 * visiteur (se connecter, créer un compte) ou de la personne connectée selon son rôle. Aucune
 * liste de courses ici.
 */
@Component({
  selector: 'app-accueil',
  imports: [RouterLink],
  templateUrl: './accueil.html',
  styleUrl: './accueil.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Accueil {
  private readonly session = inject(SessionService);

  /** Vide tant que la session est inconnue : la zone reste réservée pour que la page ne bouge pas. */
  protected readonly entrees = computed(() => {
    const etat = this.session.etat();
    if (etat === 'INCONNU') {
      return [];
    }
    return entreesAccueil(this.session.compte()?.role ?? null);
  });
}
