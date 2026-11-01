import { ElementRef, Injector, Signal, afterNextRender, inject, signal } from '@angular/core';

type Cible = Signal<ElementRef<HTMLElement>>;

/**
 * État « ouvert / fermé » d'un formulaire de création à la demande (fermé à l'arrivée, sans
 * requête ni mémorisation). Le focus est donné après le rendu : au premier champ à l'ouverture,
 * au bouton d'ouverture (réactivé) à la fermeture. À créer dans un contexte d'injection.
 */
export class FormulaireALaDemande {
  private readonly etat = signal(false);
  readonly ouvert = this.etat.asReadonly();

  constructor(
    private readonly boutonOuvrir: Cible,
    private readonly premierChamp: Cible,
    private readonly injector: Injector = inject(Injector),
  ) {}

  /** Ouvre le formulaire et focalise le premier champ ; `false` s'il était déjà ouvert. */
  ouvrir(): boolean {
    const etaitFerme = !this.etat();
    this.etat.set(true);
    this.focaliser(this.premierChamp);
    return etaitFerme;
  }

  /** Ferme le formulaire et rend le focus au bouton d'ouverture. */
  fermer(): void {
    this.etat.set(false);
    this.focaliser(this.boutonOuvrir);
  }

  private focaliser(cible: Cible): void {
    afterNextRender(() => cible().nativeElement.focus(), { injector: this.injector });
  }
}
