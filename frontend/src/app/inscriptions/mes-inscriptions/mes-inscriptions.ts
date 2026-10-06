import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

import { formaterDateCourse } from '../../administration/courses/champs-course';
import { LIBELLES_STATUT_COURSE } from '../../administration/courses/course';
import { RefusAccesService } from '../../comptes/refus-acces.service';
import { CoureurApiService } from '../coureur-api.service';
import { LIBELLES_STATUT_INSCRIPTION, MonInscriptionReponse } from '../mon-inscription';
import { QrCode } from '../qr-code/qr-code';

const ECRAN_MES_INSCRIPTIONS = '/coureur/inscriptions';

/**
 * Inscriptions du coureur connecté : Course, dossard, statut et QR code.
 * Le jeton QR ne sert qu'à dessiner le QR : jamais affiché en texte, ni placé dans un attribut.
 */
@Component({
  selector: 'app-mes-inscriptions',
  imports: [RouterLink, QrCode],
  templateUrl: './mes-inscriptions.html',
  styleUrls: ['../../partage/page-carte.css', './mes-inscriptions.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MesInscriptions implements OnInit {
  private readonly coureurApi = inject(CoureurApiService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly formaterDate = formaterDateCourse;
  protected readonly libellesStatutCourse = LIBELLES_STATUT_COURSE;
  protected readonly libellesStatutInscription = LIBELLES_STATUT_INSCRIPTION;
  /** `null` tant que la liste n'est pas chargée. */
  protected readonly inscriptions = signal<MonInscriptionReponse[] | null>(null);
  protected readonly erreurChargement = signal(false);

  ngOnInit(): void {
    this.coureurApi
      .listerMesInscriptions()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (inscriptions) => this.inscriptions.set(inscriptions),
        error: (erreur: unknown) => {
          if (!this.refusAcces.rediriger(erreur, ECRAN_MES_INSCRIPTIONS)) {
            this.erreurChargement.set(true);
          }
        },
      });
  }

  /** Le dossard n'est que dans le texte alternatif, jamais dans le QR code. */
  protected libelleQr(inscription: MonInscriptionReponse): string {
    return `QR code de l'inscription, dossard ${inscription.dossard}`;
  }
}
