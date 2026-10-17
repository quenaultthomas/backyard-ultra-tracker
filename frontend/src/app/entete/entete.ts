import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  linkedSignal,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';

import { SessionService } from '../comptes/session.service';
import { CsrfService } from '../partage/csrf.service';
import {
  MESSAGE_PAGE_EXPIREE,
  MESSAGE_SERVICE_INDISPONIBLE,
  lireProbleme,
} from '../partage/probleme';
import { FileScansService } from '../scan/file-scans/file-scans.service';
import { ACTION_DECONNEXION, entreesMenu } from './entrees-menu';
import { MenuBurger } from './menu-burger/menu-burger';

/** Avertissement avant de se déconnecter avec des scans en attente sur l'appareil. */
function messageScansNonEnvoyes(nombre: number): string {
  return nombre > 1
    ? `${nombre} scans ne sont pas encore envoyés. Si vous vous déconnectez, ils resteront sur cet appareil et ne partiront qu'à votre prochaine connexion avec ce compte.`
    : `1 scan n'est pas encore envoyé. Si vous vous déconnectez, il restera sur cet appareil et ne partira qu'à votre prochaine connexion avec ce compte.`;
}

/**
 * En-tête global : nom de l'application et menu burger selon l'état de connexion. Un bénévole
 * qui a des scans en attente confirme sa déconnexion.
 */
@Component({
  selector: 'app-entete',
  imports: [MenuBurger, RouterLink],
  templateUrl: './entete.html',
  styleUrl: './entete.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Entete {
  protected readonly session = inject(SessionService);
  private readonly csrf = inject(CsrfService);
  private readonly fileScans = inject(FileScansService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly menu = viewChild(MenuBurger);
  private readonly boutonRester = viewChild<ElementRef<HTMLButtonElement>>('rester');

  protected readonly envoiEnCours = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly entreesVisiteur = entreesMenu(null);
  protected readonly entreesCompte = computed(() => {
    const compte = this.session.compte();
    return compte ? entreesMenu(compte.role) : [];
  });
  /** Scans en attente au clic sur « Se déconnecter » ; refermée à tout changement de Compte. */
  private readonly scansNonEnvoyes = linkedSignal<unknown, number | null>({
    source: this.session.compte,
    computation: () => null,
  });
  protected readonly confirmation = computed(() => {
    const nombre = this.scansNonEnvoyes();
    return nombre === null ? null : messageScansNonEnvoyes(nombre);
  });

  protected executer(action: string): void {
    if (action === ACTION_DECONNEXION) {
      this.demanderDeconnexion();
    }
  }

  protected resterConnecte(): void {
    this.scansNonEnvoyes.set(null);
    this.menu()?.rendreLeFocus();
  }

  /** La file n'est pas purgée : elle repartira à la prochaine connexion de ce Compte. */
  protected confirmerDeconnexion(): void {
    this.resterConnecte();
    this.seDeconnecter();
  }

  /** Sans scan en attente, la déconnexion est immédiate ; les scans à vérifier ne la bloquent pas. */
  private demanderDeconnexion(): void {
    const enAttente = this.fileScans.totalEnAttente();
    if (enAttente === 0) {
      this.seDeconnecter();
      return;
    }
    this.scansNonEnvoyes.set(enAttente);
    afterNextRender(() => this.boutonRester()?.nativeElement.focus(), {
      injector: this.injector,
    });
  }

  private seDeconnecter(): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.erreur.set(null);
    this.envoiEnCours.set(true);
    this.session
      .deconnecter()
      .pipe(
        finalize(() => this.envoiEnCours.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => void this.router.navigateByUrl('/connexion'),
        error: (erreur: unknown) => this.afficherErreur(erreur),
      });
  }

  /** L'état local reste connecté : la déconnexion pourra être retentée. */
  private afficherErreur(erreur: unknown): void {
    if (lireProbleme(erreur)?.code === 'CSRF_INVALIDE') {
      this.erreur.set(MESSAGE_PAGE_EXPIREE);
      this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
      return;
    }
    this.erreur.set(MESSAGE_SERVICE_INDISPONIBLE);
  }
}
