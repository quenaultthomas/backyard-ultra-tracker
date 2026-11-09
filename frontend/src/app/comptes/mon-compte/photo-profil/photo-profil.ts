import { HttpErrorResponse, HttpStatusCode } from '@angular/common/http';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnInit,
  afterNextRender,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, finalize, switchMap } from 'rxjs';

import { CsrfService } from '../../../partage/csrf.service';
import { lireImage } from '../../../partage/lecture-image';
import { MESSAGE_SERVICE_INDISPONIBLE } from '../../../partage/probleme';
import { SILHOUETTE } from '../../../partage/silhouette';
import { Role } from '../../compte';
import { ComptesApiService } from '../../comptes-api.service';
import { ErreursSpecifiques, interpreterErreurFormulaire } from '../../erreurs-compte';
import { RefusAccesService } from '../../refus-acces.service';
import { estCoureur } from '../../roles';
import { SessionService } from '../../session.service';
import { ECRAN_MON_COMPTE } from '../ecran-mon-compte';
import { EchecPreparationPhoto, MotifEchecPreparation, preparerPhoto } from './preparation-photo';

const MESSAGE_TROP_VOLUMINEUSE = 'La photo ne doit pas dépasser 2 Mo.';

const MESSAGES_PREPARATION: Readonly<Record<MotifEchecPreparation, string>> = {
  ILLISIBLE: "Ce fichier n'est pas une image lisible.",
  TROP_VOLUMINEUX: 'Ce fichier est trop volumineux (20 Mo maximum).',
  IMPOSSIBLE: "La photo n'a pas pu être préparée. Essayez avec une autre image.",
};

const ERREURS_PHOTO: ErreursSpecifiques = {
  PHOTO_TROP_VOLUMINEUSE: { generale: MESSAGE_TROP_VOLUMINEUSE },
  PHOTO_FORMAT_INVALIDE: { generale: 'La photo doit être une image PNG, JPEG ou WebP.' },
  PHOTO_REQUISE: { generale: 'Le fichier de la photo est obligatoire.' },
};

/** Photo et rôle du Compte, tels que renvoyés par `GET /api/comptes/moi`. */
interface ComptePhoto {
  readonly role: Role;
  readonly photoUrl: string | null;
}

/** Photo préparée, prête à l'envoi : le JPEG et son aperçu en URL `data:`. */
interface PhotoPreparee {
  readonly fichier: Blob;
  readonly apercu: string;
}

/**
 * Bloc « Ma photo de profil » de Mon compte : photo ou silhouette, ajout et remplacement (coureur)
 * avec préparation de l'image dans le navigateur, retrait. Le rôle et la photo viennent de l'API ;
 * l'affichage selon le rôle n'est qu'une aide, l'API contrôle chaque envoi.
 */
@Component({
  selector: 'app-photo-profil',
  templateUrl: './photo-profil.html',
  styleUrls: ['../../formulaire-compte.css', './photo-profil.css'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PhotoProfil implements OnInit {
  private readonly comptesApi = inject(ComptesApiService);
  private readonly csrf = inject(CsrfService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly session = inject(SessionService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly boutonChoisir = viewChild<ElementRef<HTMLButtonElement>>('boutonChoisir');
  /** Seule la dernière préparation lancée compte (nouveau choix ou annulation entre-temps). */
  private numeroPreparation = 0;

  protected readonly silhouette = SILHOUETTE;
  protected readonly compte = signal<ComptePhoto | null>(null);
  protected readonly peutModifier = computed(() => {
    const compte = this.compte();
    return compte !== null && estCoureur(compte.role);
  });
  /** Bloc absent pour un Compte non coureur sans photo ; erreur de lecture montrée au coureur. */
  protected readonly visible = computed(() => {
    const compte = this.compte();
    return compte === null
      ? this.erreur() !== null && this.session.estCoureur()
      : estCoureur(compte.role) || compte.photoUrl !== null;
  });
  protected readonly preparationEnCours = signal(false);
  protected readonly preparee = signal<PhotoPreparee | null>(null);
  protected readonly envoiEnCours = signal(false);
  protected readonly message = signal<string | null>(null);
  protected readonly erreur = signal<string | null>(null);

  ngOnInit(): void {
    this.comptesApi
      .lireCompteCourant()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ role, photoUrl }) => this.compte.set({ role, photoUrl }),
        error: (erreur: unknown) => {
          if (!this.refusAcces.rediriger(erreur, ECRAN_MON_COMPTE)) {
            this.erreur.set(MESSAGE_SERVICE_INDISPONIBLE);
          }
        },
      });
  }

  /** Prépare le fichier choisi, sans aucune requête ; le champ est vidé pour pouvoir le rechoisir. */
  protected choisir(champ: HTMLInputElement): void {
    const fichier = champ.files?.item(0) ?? null;
    champ.value = '';
    if (fichier === null) {
      return;
    }
    const numero = ++this.numeroPreparation;
    this.effacerMessages();
    this.preparee.set(null);
    this.preparationEnCours.set(true);
    void this.preparer(fichier).then((resultat) => this.terminerPreparation(numero, resultat));
  }

  /** Ferme l'aperçu sans requête ; une préparation en cours est ignorée. */
  protected annuler(): void {
    this.numeroPreparation++;
    this.preparationEnCours.set(false);
    this.preparee.set(null);
    this.rendreFocus();
  }

  protected envoyer(): void {
    const preparee = this.preparee();
    if (preparee === null) {
      return;
    }
    this.executer(this.comptesApi.televerserPhoto(preparee.fichier), ({ photoUrl }) => {
      this.preparee.set(null);
      this.appliquer(photoUrl, 'Votre photo a été enregistrée.');
    });
  }

  /** Retrait sans confirmation : l'action est réversible et protège la vie privée. */
  protected retirer(): void {
    this.executer(this.comptesApi.retirerPhoto(), () =>
      this.appliquer(null, 'Votre photo a été retirée.'),
    );
  }

  /** Photo préparée et son aperçu, ou motif de l'échec : aucune exception ne remonte. */
  private async preparer(fichier: File): Promise<PhotoPreparee | MotifEchecPreparation> {
    try {
      const jpeg = await preparerPhoto(fichier);
      const apercu = await lireImage(jpeg);
      return apercu === null ? 'IMPOSSIBLE' : { fichier: jpeg, apercu };
    } catch (echec: unknown) {
      return echec instanceof EchecPreparationPhoto ? echec.motif : 'IMPOSSIBLE';
    }
  }

  private terminerPreparation(
    numero: number,
    resultat: PhotoPreparee | MotifEchecPreparation,
  ): void {
    if (numero !== this.numeroPreparation) {
      return;
    }
    this.preparationEnCours.set(false);
    if (typeof resultat === 'string') {
      this.erreur.set(MESSAGES_PREPARATION[resultat]);
    } else {
      this.preparee.set(resultat);
    }
  }

  /** Nouveau jeton CSRF puis requête ; un seul envoi à la fois (double clic sans effet). */
  private executer<T>(requete: Observable<T>, succes: (reponse: T) => void): void {
    if (this.envoiEnCours()) {
      return;
    }
    this.envoiEnCours.set(true);
    this.effacerMessages();
    this.csrf
      .renouvelerJeton()
      .pipe(
        switchMap(() => requete),
        finalize(() => this.envoiEnCours.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({ next: succes, error: (erreur: unknown) => this.traiterErreur(erreur) });
  }

  /** Nouvelle photo affichée ; le focus revient au bouton d'ajout, une fois réactivé. */
  private appliquer(photoUrl: string | null, message: string): void {
    this.compte.update((compte) => (compte === null ? null : { ...compte, photoUrl }));
    this.message.set(message);
    this.rendreFocus();
  }

  /** Focus sur le bouton d'ajout après le rendu (une fois réactivé, s'il existe encore). */
  private rendreFocus(): void {
    afterNextRender(() => this.boutonChoisir()?.nativeElement.focus(), {
      injector: this.injector,
    });
  }

  /** 401 : connexion ; 403 (hors CSRF) : accès refusé ; sinon message, photo préparée conservée. */
  private traiterErreur(erreur: unknown): void {
    if (this.refusAcces.rediriger(erreur, ECRAN_MON_COMPTE)) {
      return;
    }
    const erreurs = interpreterErreurFormulaire(erreur, ERREURS_PHOTO, []);
    // Tout 413 vaut « trop volumineuse », y compris celui de Caddy, sans `ProblemDetail`.
    const tropVolumineuse =
      erreur instanceof HttpErrorResponse && erreur.status === HttpStatusCode.PayloadTooLarge;
    this.erreur.set(
      tropVolumineuse
        ? MESSAGE_TROP_VOLUMINEUSE
        : (erreurs.generale ?? MESSAGE_SERVICE_INDISPONIBLE),
    );
    if (erreurs.jetonExpire) {
      this.csrf.renouvelerJeton().pipe(takeUntilDestroyed(this.destroyRef)).subscribe();
    }
  }

  private effacerMessages(): void {
    this.message.set(null);
    this.erreur.set(null);
  }
}
