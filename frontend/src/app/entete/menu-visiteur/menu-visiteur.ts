import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { NavigationStart, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { filter } from 'rxjs';

/**
 * Menu burger du visiteur non connecté (pattern « disclosure navigation ») :
 * un bouton qui affiche ou masque deux liens, sans piège de focus.
 */
@Component({
  selector: 'app-menu-visiteur',
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './menu-visiteur.html',
  styleUrl: './menu-visiteur.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '(focusout)': 'fermerSiFocusSort($event)' },
})
export class MenuVisiteur {
  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly document = inject(DOCUMENT);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly bouton = viewChild.required<ElementRef<HTMLButtonElement>>('bouton');
  private readonly premiereEntree =
    viewChild.required<ElementRef<HTMLAnchorElement>>('premiereEntree');

  protected readonly ouvert = signal(false);
  private retirerEcouteurs: (() => void) | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.fermer());
  }

  protected basculer(): void {
    if (this.ouvert()) {
      this.fermerEtRendreLeFocus();
      return;
    }
    this.ouvert.set(true);
    this.ecouterLesFermetures();
    afterNextRender(() => this.premiereEntree().nativeElement.focus(), {
      injector: this.injector,
    });
  }

  protected fermer(): void {
    this.ouvert.set(false);
    this.retirerEcouteurs?.();
    this.retirerEcouteurs = null;
  }

  /** Un focus qui devient null (fenêtre inactive, autre onglet) ne ferme pas le menu. */
  protected fermerSiFocusSort(evenement: FocusEvent): void {
    if (
      this.ouvert() &&
      evenement.relatedTarget !== null &&
      !this.contient(evenement.relatedTarget)
    ) {
      this.fermer();
    }
  }

  /** Échap, appui extérieur et changement de route ne sont écoutés que menu ouvert. */
  private ecouterLesFermetures(): void {
    const surAppui = (evenement: PointerEvent) => {
      if (!this.contient(evenement.target)) {
        this.fermer();
      }
    };
    const surTouche = (evenement: KeyboardEvent) => {
      if (evenement.key === 'Escape') {
        evenement.preventDefault();
        this.fermerEtRendreLeFocus();
      }
    };
    this.document.addEventListener('pointerdown', surAppui, true);
    this.document.addEventListener('keydown', surTouche);
    const navigation = this.router.events
      .pipe(filter((evenement) => evenement instanceof NavigationStart))
      .subscribe(() => this.fermer());
    this.retirerEcouteurs = () => {
      this.document.removeEventListener('pointerdown', surAppui, true);
      this.document.removeEventListener('keydown', surTouche);
      navigation.unsubscribe();
    };
  }

  private fermerEtRendreLeFocus(): void {
    this.fermer();
    this.bouton().nativeElement.focus();
  }

  private contient(cible: EventTarget | null): boolean {
    return cible instanceof Node && this.hote.nativeElement.contains(cible);
  }
}
