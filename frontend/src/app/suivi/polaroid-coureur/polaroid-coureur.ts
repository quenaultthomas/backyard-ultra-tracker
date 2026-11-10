import { DOCUMENT } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
} from '@angular/core';

import { SILHOUETTE } from '../../partage/silhouette';
import { CoureurSuivi } from '../suivi-course';

/**
 * Garde défensive : seules les photos servies par l'application sont chargées, toute autre adresse
 * (absolue, `javascript:`…) donne la silhouette sans aucune requête. Copie volontaire de la route
 * construite par le back (exposition du suivi) : le front ne partage aucune constante avec le back.
 */
const PREFIXE_PHOTO = '/api/photos/';

/**
 * Délais des nouvelles tentatives d'une photo en échec, comptés depuis l'échec précédent : 30 s,
 * 2 min puis 5 min. Leur nombre est le plafond par adresse (4 requêtes au total avec la première).
 */
const DELAIS_NOUVELLES_TENTATIVES_MS = [30_000, 120_000, 300_000] as const;

/** `affichee` : photo demandée ou chargée ; `en-echec` : silhouette ; `nouvelle-tentative` : silhouette, photo redemandée. */
type PhasePhoto = 'affichee' | 'en-echec' | 'nouvelle-tentative';

interface EtatPhoto {
  readonly phase: PhasePhoto;
  /** Nouvelles tentatives déjà émises pour l'adresse courante. */
  readonly essais: number;
}

const ETAT_INITIAL: EtatPhoto = { phase: 'affichee', essais: 0 };

/**
 * Polaroïd d'un coureur du suivi public : photo (ou silhouette), pseudo et dossard. Le composant est
 * conservé d'un rafraîchissement à l'autre (suivi par dossard) : le polling ne demande jamais l'image,
 * son `src` ne change que si `photoUrl` change ou lors d'une nouvelle tentative. Une image en échec
 * laisse place à la silhouette, puis est redemandée dans le même nœud `img`, à la même adresse, selon
 * DELAIS_NOUVELLES_TENTATIVES_MS, jamais tant que l'onglet est caché. Incliné sur le tableau, droit
 * dans le bandeau vainqueur.
 */
@Component({
  selector: 'app-polaroid-coureur',
  templateUrl: './polaroid-coureur.html',
  styleUrl: './polaroid-coureur.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PolaroidCoureur {
  readonly coureur = input.required<CoureurSuivi>();
  /** Bandeau vainqueur : plus grand, droit, sans épingle. */
  readonly vainqueur = input(false);
  /** Palier compact du tableau : légende réduite (« n° » au lieu de « Dossard »). */
  readonly compact = input(false);

  private readonly document = inject(DOCUMENT);

  private readonly adresse = computed(() => {
    const url = this.coureur().photoUrl;
    return url !== null && url.startsWith(PREFIXE_PHOTO) ? url : null;
  });

  /** Remis à l'état initial (compteur à zéro) à chaque changement d'adresse. */
  private readonly etat = linkedSignal<string | null, EtatPhoto>({
    source: this.adresse,
    computation: () => ETAT_INITIAL,
  });

  private minuteur: ReturnType<typeof setTimeout> | undefined;
  private attenteVisibilite: (() => void) | undefined;

  /** Photo présentée comme telle (testid, alt) ; silhouette sinon, y compris pendant une nouvelle tentative. */
  protected readonly photoAffichee = computed(
    () => this.adresse() !== null && this.etat().phase === 'affichee',
  );

  protected readonly source = computed(() =>
    this.adresse() === null || this.etat().phase === 'en-echec' ? SILHOUETTE : this.adresse(),
  );

  protected readonly phase = computed(() => (this.adresse() === null ? null : this.etat().phase));

  /** Inclinaison en degrés, de −4 à +4, fonction du seul dossard ; aucune pour un vainqueur. */
  protected readonly inclinaison = computed(() =>
    this.vainqueur() ? null : ((this.coureur().dossard * 7) % 9) - 4,
  );

  constructor() {
    // Changement d'adresse, abandon (polaroïd retiré) ou destruction : la tentative planifiée est annulée.
    effect((onCleanup) => {
      this.adresse();
      onCleanup(() => this.annulerTentative());
    });
  }

  protected photoChargee(): void {
    if (this.etat().phase === 'nouvelle-tentative') {
      this.etat.update((etat) => ({ ...etat, phase: 'affichee' }));
    }
  }

  protected photoEnEchec(): void {
    const { phase, essais } = this.etat();
    const adresse = this.adresse();
    if (adresse === null || phase === 'en-echec') {
      return;
    }
    this.etat.set({ phase: 'en-echec', essais });
    const delai = DELAIS_NOUVELLES_TENTATIVES_MS[essais];
    if (delai !== undefined) {
      this.minuteur = setTimeout(() => this.tenterQuandVisible(adresse), delai);
    }
  }

  /** Échéance atteinte : la tentative part aussitôt, ou au retour de l'onglet à l'état visible. */
  private tenterQuandVisible(adresse: string): void {
    this.minuteur = undefined;
    if (adresse !== this.adresse()) {
      return;
    }
    if (!this.document.hidden) {
      this.tenter();
      return;
    }
    this.attenteVisibilite = () => {
      if (!this.document.hidden) {
        this.annulerTentative();
        this.tenter();
      }
    };
    this.document.addEventListener('visibilitychange', this.attenteVisibilite);
  }

  /** Seule une tentative émise consomme un essai. */
  private tenter(): void {
    this.etat.update(({ essais }) => ({ phase: 'nouvelle-tentative', essais: essais + 1 }));
  }

  private annulerTentative(): void {
    clearTimeout(this.minuteur);
    this.minuteur = undefined;
    if (this.attenteVisibilite) {
      this.document.removeEventListener('visibilitychange', this.attenteVisibilite);
      this.attenteVisibilite = undefined;
    }
  }
}
