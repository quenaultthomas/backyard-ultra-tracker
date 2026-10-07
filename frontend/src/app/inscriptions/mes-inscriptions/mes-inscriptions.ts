import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

import { formaterDateCourse } from '../../administration/courses/champs-course';
import {
  LIBELLES_STATUT_COURSE,
  PASTILLES_STATUT_COURSE,
} from '../../administration/courses/course';
import { RefusAccesService } from '../../comptes/refus-acces.service';
import { CoureurApiService } from '../coureur-api.service';
import {
  Desinscription,
  ECRAN_MES_INSCRIPTIONS,
  ResultatDesinscription,
} from '../desinscription/desinscription';
import {
  LIBELLES_STATUT_INSCRIPTION,
  MonInscriptionReponse,
  PASTILLES_STATUT_INSCRIPTION,
} from '../mon-inscription';
import { QrCode } from '../qr-code/qr-code';

/**
 * Inscriptions du coureur connecté : Course, dossard, statut, QR code et désinscription.
 * Le jeton QR ne sert qu'à dessiner le QR : jamais affiché en texte, ni placé dans un attribut.
 */
@Component({
  selector: 'app-mes-inscriptions',
  imports: [RouterLink, QrCode, Desinscription],
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
  protected readonly pastillesStatutCourse = PASTILLES_STATUT_COURSE;
  protected readonly pastillesStatutInscription = PASTILLES_STATUT_INSCRIPTION;
  /** `null` tant que la liste n'est pas chargée. */
  protected readonly inscriptions = signal<MonInscriptionReponse[] | null>(null);
  protected readonly erreurChargement = signal(false);
  /** Inscription dont la confirmation de désinscription est ouverte : une seule à la fois. */
  protected readonly desinscriptionOuverte = signal<string | null>(null);
  /** Dernier retour de désinscription : le plus récent remplace le précédent. */
  protected readonly retourDesinscription = signal<ResultatDesinscription | null>(null);

  ngOnInit(): void {
    this.charger();
  }

  /** Le dossard n'est que dans le texte alternatif, jamais dans le QR code. */
  protected libelleQr(inscription: MonInscriptionReponse): string {
    return `QR code de l'inscription, dossard ${inscription.dossard}`;
  }

  protected afficherResultat(resultat: ResultatDesinscription): void {
    this.retourDesinscription.set(resultat);
    if (resultat.relire) {
      this.desinscriptionOuverte.set(null);
      this.charger();
    }
  }

  private charger(): void {
    this.coureurApi
      .listerMesInscriptions()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (inscriptions) => {
          this.erreurChargement.set(false);
          this.inscriptions.set(inscriptions);
        },
        error: (erreur: unknown) => {
          if (!this.refusAcces.rediriger(erreur, ECRAN_MES_INSCRIPTIONS)) {
            this.erreurChargement.set(true);
          }
        },
      });
  }
}
