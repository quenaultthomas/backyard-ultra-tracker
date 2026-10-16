import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { libelleBouclesCorrigees } from '../../pilotage-course';

/**
 * Badge « corrigé » d'un coureur portant au moins un Passage `CORRECTION` (`nombreBouclesCorrigees`
 * renvoyé par le serveur, jamais déduit) : texte visible « corrigé », équivalent accessible
 * « dont N boucle(s) corrigée(s) ». Rien sous 1.
 */
@Component({
  selector: 'app-badge-corrige',
  template: `
    @if (nombre() >= 1) {
      <span class="badge-corrige" aria-hidden="true" [attr.data-testid]="testId()">corrigé</span>
      <span class="masque">, dont {{ libelle() }}</span>
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BadgeCorrige {
  /** `nombreBouclesCorrigees` de la ligne. */
  readonly nombre = input.required<number>();
  /** `data-testid` du badge visible (un par section du pilotage). */
  readonly testId = input.required<string>();

  protected readonly libelle = computed(() => libelleBouclesCorrigees(this.nombre()));
}
