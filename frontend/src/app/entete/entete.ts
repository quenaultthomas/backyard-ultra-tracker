import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
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
import { MenuVisiteur } from './menu-visiteur/menu-visiteur';

/** En-tête global : nom de l'application et état de connexion. */
@Component({
  selector: 'app-entete',
  imports: [MenuVisiteur, RouterLink],
  templateUrl: './entete.html',
  styleUrl: './entete.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Entete {
  protected readonly session = inject(SessionService);
  private readonly csrf = inject(CsrfService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly envoiEnCours = signal(false);
  protected readonly erreur = signal<string | null>(null);

  protected seDeconnecter(): void {
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
