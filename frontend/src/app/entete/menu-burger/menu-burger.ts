import {
  ChangeDetectionStrategy,
  Component,
  DOCUMENT,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  inject,
  input,
  output,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core';
import {
  IsActiveMatchOptions,
  NavigationStart,
  Router,
  RouterLink,
  RouterLinkActive,
} from '@angular/router';
import { filter } from 'rxjs';

import { EntreeAction, EntreeMenu } from '../entrees-menu';

/** Page courante : chemin exact, paramètres de requête ignorés (`/connexion?retour=…`). */
const PAGE_COURANTE: IsActiveMatchOptions = {
  paths: 'exact',
  queryParams: 'ignored',
  fragment: 'ignored',
  matrixParams: 'ignored',
};

/**
 * Menu burger de l'en-tête (pattern « disclosure navigation ») : un bouton qui affiche ou
 * masque le pseudo éventuel et des entrées, sans piège de focus. Visiteur et connecté ne
 * diffèrent que par les données reçues.
 */
@Component({
  selector: 'app-menu-burger',
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './menu-burger.html',
  styleUrl: './menu-burger.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(focusout)': 'fermerSiFocusSort($event)',
    '(keydown.tab)': 'fermerSiSortieParLaFin($event)',
  },
})
export class MenuBurger {
  readonly nomPanneau = input.required<string>();
  readonly testidPanneau = input.required<string>();
  readonly pseudo = input<string | null>(null);
  readonly entrees = input.required<readonly EntreeMenu[]>();
  readonly actionEnCours = input(false);
  /** Émet le `testid` de l'action activée. */
  readonly action = output<string>();

  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly document = inject(DOCUMENT);
  private readonly router = inject(Router);
  private readonly injector = inject(Injector);
  private readonly bouton = viewChild.required<ElementRef<HTMLButtonElement>>('bouton');
  private readonly entreesFocusables = viewChildren<ElementRef<HTMLElement>>('focusable');

  protected readonly ouvert = signal(false);
  protected readonly pageCourante = PAGE_COURANTE;
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
    afterNextRender(() => this.entreesFocusables()[0]?.nativeElement.focus(), {
      injector: this.injector,
    });
  }

  protected fermer(): void {
    this.ouvert.set(false);
    this.retirerEcouteurs?.();
    this.retirerEcouteurs = null;
  }

  /** L'entrée activée va être masquée : le focus revient au bouton avant l'action. */
  protected activer(entree: EntreeAction): void {
    this.fermerEtRendreLeFocus();
    this.action.emit(entree.testid);
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

  /** Tab depuis la dernière entrée quitte le menu même si aucun élément ne reçoit le focus ensuite. */
  protected fermerSiSortieParLaFin(evenement: Event): void {
    const derniere = this.entreesFocusables().at(-1)?.nativeElement;
    if (this.ouvert() && !(evenement as KeyboardEvent).shiftKey && evenement.target === derniere) {
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
