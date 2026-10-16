import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';

import { interpreterErreurFormulaire } from '../../../../comptes/erreurs-compte';
import { AdministrationApiService } from '../../../administration-api.service';
import {
  AbandonManuel,
  InscritEnCourse,
  LIBELLES_MOTIF_ABANDON,
  MOTIFS_ABANDON_MANUEL,
  MotifAbandonManuel,
} from '../../pilotage-course';
import { ConfirmationPilotage } from '../confirmation-pilotage';
import { RefusActionPilotage } from '../refus-action-pilotage';

const MESSAGE_PLUS_EN_COURSE = "Ce coureur n'est plus en course.";

/** Refus du serveur qui ferment le panneau. */
const REFUS_ABANDON: Readonly<Record<string, RefusActionPilotage>> = {
  INSCRIPTION_NON_EN_COURSE: { message: MESSAGE_PLUS_EN_COURSE, rafraichir: true },
  COURSE_TERMINEE: {
    message: "La course est terminée : aucun abandon n'est possible.",
    rafraichir: true,
  },
  COURSE_NON_DEMARREE: { message: "La course n'est pas démarrée.", rafraichir: true },
  INSCRIPTION_INTROUVABLE: { message: 'Ce coureur est introuvable.', rafraichir: false },
};

/**
 * Abandon manuel depuis le pilotage : confirmation en ligne avec motif obligatoire (socle
 * {@link ConfirmationPilotage}). Le serveur décide de tout (éligibilité, Boucle d'abandon, fin de
 * Course) ; l'avertissement « dernier coureur » n'est qu'une indication tirée de la liste affichée.
 */
@Component({
  selector: 'app-abandon-coureur',
  imports: [ReactiveFormsModule],
  templateUrl: './abandon-coureur.html',
  styleUrls: [
    '../../../../partage/confirmation-en-ligne.css',
    '../confirmation-pilotage.css',
    './abandon-coureur.css',
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { tabindex: '-1' },
})
export class AbandonCoureur extends ConfirmationPilotage<InscritEnCourse> {
  private readonly administrationApi = inject(AdministrationApiService);

  protected readonly refus = REFUS_ABANDON;
  protected readonly motifs = MOTIFS_ABANDON_MANUEL;
  protected readonly libellesMotif = LIBELLES_MOTIF_ABANDON;
  protected readonly formulaire = new FormGroup({
    motif: new FormControl<MotifAbandonManuel | null>(null, Validators.required),
  });
  protected readonly soumis = signal(false);
  protected readonly erreurMotifServeur = signal<string | null>(null);

  private readonly inscritsEnCourse = computed(
    () => this.rafraichissement.instantane()?.inscritsEnCourse ?? [],
  );
  protected readonly cible = computed(() =>
    this.inscritsEnCourse().find(({ inscriptionId }) => inscriptionId === this.cibleId()),
  );
  protected readonly dernier = computed(() => this.inscritsEnCourse().length === 1);

  constructor() {
    super();
    // Coureur visé absent d'un nouvel instantané (abandon automatique, fin de Course).
    this.fermerSiCibleAbsente(MESSAGE_PLUS_EN_COURSE);
  }

  /** Motif requis, puis `POST` ; un seul envoi à la fois. */
  protected confirmer(): void {
    const cible = this.cible();
    const motif = this.formulaire.controls.motif.value;
    if (this.envoiEnCours() || cible === undefined) {
      return;
    }
    this.soumis.set(true);
    this.erreurMotifServeur.set(null);
    this.erreur.set(null);
    if (motif === null) {
      return;
    }
    this.envoyer(
      () => this.administrationApi.abandonnerCoureur(this.courseId(), cible.inscriptionId, motif),
      (abandon) => this.reussir(messageAbandon(abandon, cible)),
    );
  }

  protected erreurMotif(): string | null {
    const manquant = this.soumis() && this.formulaire.controls.motif.value === null;
    return manquant ? "Choisissez un motif d'abandon." : this.erreurMotifServeur();
  }

  protected override reinitialiser(): void {
    this.formulaire.reset();
    this.soumis.set(false);
    this.erreurMotifServeur.set(null);
  }

  /** Erreur de validation du motif sous le champ, sinon message général. */
  protected override afficherErreur(erreur: unknown): void {
    const { motif, generale, jetonExpire } = interpreterErreurFormulaire(erreur, {}, ['motif']);
    this.erreurMotifServeur.set(motif ?? null);
    this.erreur.set(generale ?? null);
    this.renouvelerJetonSiExpire(jetonExpire);
  }
}

function messageAbandon(abandon: AbandonManuel, cible: InscritEnCourse): string {
  const fin =
    abandon.statutCourse === 'TERMINEE'
      ? ' La course est terminée : plus aucun coureur en course.'
      : '';
  return `Abandon enregistré : coureur n° ${abandon.dossard} ${cible.pseudo}.${fin}`;
}
