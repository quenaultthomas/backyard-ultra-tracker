import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

import { LIBELLES_ROLE, estAdministrateur } from '../../comptes/roles';
import { SessionService } from '../../comptes/session.service';
import { AdministrationApiService } from '../administration-api.service';
import { RefusAccesService } from '../../comptes/refus-acces.service';

const MESSAGE_VERIFICATION_IMPOSSIBLE =
  "Impossible de vérifier vos droits d'accès. Réessayez plus tard.";

/** Espace d'administration ; ses droits sont vérifiés par le serveur à l'ouverture. */
@Component({
  selector: 'app-espace-administration',
  imports: [RouterLink],
  templateUrl: './espace-administration.html',
  styleUrl: '../../partage/page-carte.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EspaceAdministration implements OnInit {
  private readonly administrationApi = inject(AdministrationApiService);
  private readonly session = inject(SessionService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly libelleRole = computed(() => {
    const role = this.session.compte()?.role;
    return role !== undefined && estAdministrateur(role) ? LIBELLES_ROLE[role] : null;
  });
  /** Aide d'affichage : l'API réserve la gestion des admins à l'admin master. */
  protected readonly peutGererAdmins = this.session.estAdminMaster;
  protected readonly erreur = signal<string | null>(null);

  ngOnInit(): void {
    this.administrationApi
      .verifierAcces()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ error: (erreur: unknown) => this.traiterRefus(erreur) });
  }

  private traiterRefus(erreur: unknown): void {
    if (!this.refusAcces.rediriger(erreur, '/administration')) {
      this.erreur.set(MESSAGE_VERIFICATION_IMPOSSIBLE);
    }
  }
}
