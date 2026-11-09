import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

import { SILHOUETTE } from '../../partage/silhouette';
import { CoureurSuivi } from '../suivi-course';

/** Seules les photos servies par l'application sont chargées ; toute autre adresse est ignorée. */
const PREFIXE_PHOTO = '/api/photos/';

/**
 * Polaroïd d'un coureur du suivi public : photo (ou silhouette), pseudo et dossard. Le composant est
 * conservé d'un rafraîchissement à l'autre (suivi par dossard) : l'image n'est demandée qu'une fois,
 * son `src` ne change que si `photoUrl` change. Une image en échec laisse place à la silhouette, sans
 * nouvelle tentative pour la même adresse. Incliné sur le tableau, droit dans le bandeau vainqueur.
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

  protected readonly silhouette = SILHOUETTE;
  private readonly photoEnErreur = signal<string | null>(null);

  protected readonly photo = computed(() => {
    const url = this.coureur().photoUrl;
    return url !== null && url.startsWith(PREFIXE_PHOTO) && url !== this.photoEnErreur()
      ? url
      : null;
  });

  /** Inclinaison en degrés, de −4 à +4, fonction du seul dossard ; aucune pour un vainqueur. */
  protected readonly inclinaison = computed(() =>
    this.vainqueur() ? null : ((this.coureur().dossard * 7) % 9) - 4,
  );

  protected signalerPhotoEnErreur(url: string): void {
    this.photoEnErreur.set(url);
  }
}
