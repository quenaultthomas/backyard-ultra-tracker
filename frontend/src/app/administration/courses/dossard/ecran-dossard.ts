import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';

import { RefusAccesService } from '../../../comptes/refus-acces.service';
import { QrCode } from '../../../inscriptions/qr-code/qr-code';
import { lireProbleme } from '../../../partage/probleme';
import { AdministrationApiService } from '../../administration-api.service';
import { DossardReponse } from '../course';
import {
  CODE_COURSE_INTROUVABLE,
  ECRAN_GESTION_COURSES,
  MESSAGE_COURSE_INTROUVABLE,
} from '../erreurs-course';

const CODE_INSCRIPTION_INTROUVABLE = 'INSCRIPTION_INTROUVABLE';

/** Échec de lecture du dossard : message et lien de retour adaptés. */
type ErreurDossard = 'COURSE_INTROUVABLE' | 'INSCRIPTION_INTROUVABLE' | 'INDISPONIBLE';

const MESSAGES_ERREUR: Readonly<Record<ErreurDossard, string>> = {
  COURSE_INTROUVABLE: MESSAGE_COURSE_INTROUVABLE,
  INSCRIPTION_INTROUVABLE:
    "Cette inscription n'existe plus (le coureur s'est peut-être désinscrit).",
  INDISPONIBLE: 'Impossible de charger le dossard. Réessayez plus tard.',
};

/**
 * Dossard imprimable d'une Inscription (admins et admin master) : numéro, pseudo, nom de la
 * Course et QR code. Le jeton QR ne vit que dans le signal `dossard` et le dessin du QR.
 */
@Component({
  selector: 'app-ecran-dossard',
  imports: [RouterLink, QrCode],
  templateUrl: './ecran-dossard.html',
  styleUrls: ['../../../partage/page-carte.css', './ecran-dossard.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EcranDossard implements OnInit {
  private readonly administrationApi = inject(AdministrationApiService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);

  /** Paramètre de route `:id`. */
  readonly id = input.required<string>();
  /** Paramètre de route `:inscriptionId`. */
  readonly inscriptionId = input.required<string>();

  /** `null` tant que la réponse n'est pas arrivée ou en cas d'erreur. */
  protected readonly dossard = signal<DossardReponse | null>(null);
  protected readonly erreur = signal<ErreurDossard | null>(null);
  protected readonly messagesErreur = MESSAGES_ERREUR;

  /** Avertissement non imprimé, d'après les statuts renvoyés par l'API. */
  protected readonly avertissement = computed(() => {
    const dossard = this.dossard();
    if (dossard?.courseStatut === 'TERMINEE') {
      return 'Cette course est terminée : le QR code ne servira plus.';
    }
    switch (dossard?.statut) {
      case 'ABANDON':
        return 'Ce coureur est en abandon : son QR code sera refusé au scan.';
      case 'VAINQUEUR':
        return 'Ce coureur est vainqueur : son QR code sera refusé au scan.';
      default:
        return null;
    }
  });

  /** Retour vers la liste des Courses si la Course n'existe plus, vers sa fiche sinon. */
  protected readonly retour = computed(() =>
    this.erreur() === 'COURSE_INTROUVABLE'
      ? { lien: ECRAN_GESTION_COURSES, libelle: 'Retour aux courses' }
      : { lien: this.ficheCourse(), libelle: 'Retour à la Course' },
  );

  ngOnInit(): void {
    this.charger();
  }

  protected charger(): void {
    this.erreur.set(null);
    this.administrationApi
      .lireDossard(this.id(), this.inscriptionId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (dossard) => this.dossard.set(dossard),
        error: (erreur: unknown) => {
          if (!this.refusAcces.rediriger(erreur, this.ecran())) {
            this.erreur.set(interpreter(erreur));
          }
        },
      });
  }

  protected imprimer(): void {
    this.document.defaultView?.print();
  }

  private ficheCourse(): string {
    return `${ECRAN_GESTION_COURSES}/${this.id()}`;
  }

  /** Adresse de l'écran, pour le retour après connexion (identifiants seulement, jamais le jeton). */
  private ecran(): string {
    return `${this.ficheCourse()}/inscriptions/${this.inscriptionId()}/dossard`;
  }
}

/** 404 identifié par son `code` ; toute autre erreur (5xx, réseau) est réessayable. */
function interpreter(erreur: unknown): ErreurDossard {
  const code = lireProbleme(erreur)?.code;
  if (code === CODE_COURSE_INTROUVABLE || code === CODE_INSCRIPTION_INTROUVABLE) {
    return code;
  }
  return 'INDISPONIBLE';
}
