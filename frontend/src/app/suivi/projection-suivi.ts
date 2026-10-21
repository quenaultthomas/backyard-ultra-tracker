import { DOCUMENT, DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';

/** Classe posée sur `body` en projection : masque l'en-tête de session et le bandeau de mise à jour. */
const CLASSE_PROJECTION = 'suivi-projection';

/**
 * Mode projection du suivi public : plein écran de la racine de la page (API Fullscreen, dans le
 * geste de l'utilisateur) ou, si le navigateur ne le permet pas, repli en affichage agrandi.
 * L'état plein écran suit uniquement `fullscreenchange` (jamais déduit d'un clic). Fourni par
 * l'écran : sa destruction quitte le plein écran et retire le masquage.
 */
@Injectable()
export class ProjectionSuivi {
  private readonly document = inject(DOCUMENT);
  private racine: HTMLElement | null = null;
  private readonly pleinEcran = signal(false);

  /** Repli sans plein écran (API absente ou refusée) : message d'indisponibilité affiché. */
  readonly repli = signal(false);
  readonly actif = computed(() => this.pleinEcran() || this.repli());

  private readonly surChangement = (): void =>
    this.pleinEcran.set(this.racine !== null && this.document.fullscreenElement === this.racine);
  private readonly surTouche = (evenement: KeyboardEvent): void => {
    if (evenement.key === 'Escape') {
      this.quitterRepli();
    }
  };

  constructor() {
    this.document.addEventListener('fullscreenchange', this.surChangement);
    effect(() => this.document.body.classList.toggle(CLASSE_PROJECTION, this.actif()));
    inject(DestroyRef).onDestroy(() => this.detruire());
  }

  /** Entre en projection sur `racine`, ou en sort si elle est active. */
  basculer(racine: HTMLElement): void {
    if (this.actif()) {
      this.quitter();
      return;
    }
    this.racine = racine;
    if (!this.document.fullscreenEnabled || typeof racine.requestFullscreen !== 'function') {
      this.activerRepli();
      return;
    }
    try {
      racine.requestFullscreen().catch(() => this.activerRepli());
    } catch {
      this.activerRepli();
    }
  }

  private quitter(): void {
    this.quitterRepli();
    if (this.pleinEcran()) {
      this.sortirDuPleinEcran();
    }
  }

  private activerRepli(): void {
    if (!this.repli()) {
      this.repli.set(true);
      this.document.addEventListener('keydown', this.surTouche);
    }
  }

  private quitterRepli(): void {
    this.repli.set(false);
    this.document.removeEventListener('keydown', this.surTouche);
  }

  /** Le refus éventuel du navigateur est sans conséquence : l'état suit `fullscreenchange`. */
  private sortirDuPleinEcran(): void {
    this.document.exitFullscreen().catch(() => undefined);
  }

  private detruire(): void {
    this.document.removeEventListener('fullscreenchange', this.surChangement);
    this.quitterRepli();
    if (this.racine !== null && this.document.fullscreenElement === this.racine) {
      this.sortirDuPleinEcran();
    }
    this.document.body.classList.remove(CLASSE_PROJECTION);
  }
}
