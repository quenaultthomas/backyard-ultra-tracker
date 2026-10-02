import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
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
import { Router } from '@angular/router';

import { RoleAdministrateur, estAdministrateur } from '../../comptes/roles';
import { SessionService } from '../../comptes/session.service';
import { AdministrationApiService } from '../administration-api.service';

const LIBELLES_ROLE: Record<RoleAdministrateur, string> = {
  ADMIN_MASTER: 'Administrateur master',
  ADMIN: 'Administrateur',
};

const MESSAGE_VERIFICATION_IMPOSSIBLE =
  "Impossible de vérifier vos droits d'accès. Réessayez plus tard.";

/** Espace d'administration, vide pour l'instant ; ses droits sont vérifiés par le serveur à l'ouverture. */
@Component({
  selector: 'app-espace-administration',
  templateUrl: './espace-administration.html',
  styleUrl: '../../partage/page-carte.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EspaceAdministration implements OnInit {
  private readonly administrationApi = inject(AdministrationApiService);
  private readonly session = inject(SessionService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly libelleRole = computed(() => {
    const role = this.session.compte()?.role;
    return role !== undefined && estAdministrateur(role) ? LIBELLES_ROLE[role] : null;
  });
  protected readonly erreur = signal<string | null>(null);

  ngOnInit(): void {
    this.administrationApi
      .verifierAcces()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ error: (erreur: unknown) => this.traiterRefus(erreur) });
  }

  private traiterRefus(erreur: unknown): void {
    const statut = erreur instanceof HttpErrorResponse ? erreur.status : null;
    if (statut === HttpStatusCode.Unauthorized) {
      this.session.oublierSessionExpiree();
      void this.router.navigate(['/connexion'], { queryParams: { retour: '/administration' } });
    } else if (statut === HttpStatusCode.Forbidden) {
      void this.router.navigateByUrl('/acces-refuse');
    } else {
      this.erreur.set(MESSAGE_VERIFICATION_IMPOSSIBLE);
    }
  }
}
