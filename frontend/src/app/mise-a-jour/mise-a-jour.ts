import { ChangeDetectionStrategy, Component, DOCUMENT, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { SwUpdate } from '@angular/service-worker';
import { filter, fromEvent, merge } from 'rxjs';

/** Nouvelle version prête, ou cache du service worker inutilisable. */
type EtatMiseAJour = 'NOUVELLE_VERSION' | 'RECHARGEMENT';

const INTERVALLE_MIN_VERIFICATION_MS = 15 * 60 * 1000;

/**
 * Bandeau « nouvelle version disponible » : la version est téléchargée en arrière-plan mais ne
 * s'applique jamais toute seule (caméra, saisie ou envoi en cours ne sont pas interrompus). Les
 * scans en attente (IndexedDB) et la session survivent au rechargement.
 */
@Component({
  selector: 'app-mise-a-jour',
  templateUrl: './mise-a-jour.html',
  styleUrl: './mise-a-jour.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MiseAJour {
  private readonly swUpdate = inject(SwUpdate);
  private readonly document = inject(DOCUMENT);
  private derniereVerification = 0;

  protected readonly etat = signal<EtatMiseAJour | null>(null);

  constructor() {
    if (!this.swUpdate.isEnabled) {
      return;
    }
    this.swUpdate.versionUpdates
      .pipe(
        filter((evenement) => evenement.type === 'VERSION_READY'),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.etat.set('NOUVELLE_VERSION'));
    this.swUpdate.unrecoverable
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.etat.set('RECHARGEMENT'));
    const fenetre = this.document.defaultView;
    const retours = [fromEvent(this.document, 'visibilitychange')];
    if (fenetre) {
      retours.push(fromEvent(fenetre, 'online'));
    }
    merge(...retours)
      .pipe(
        filter(() => this.document.visibilityState === 'visible'),
        takeUntilDestroyed(),
      )
      .subscribe(() => this.verifier());
    this.verifier();
  }

  /** Active la nouvelle version puis recharge la page (rechargement seul si le cache est perdu). */
  protected appliquer(): void {
    const activation =
      this.etat() === 'NOUVELLE_VERSION' ? this.swUpdate.activateUpdate() : Promise.resolve();
    void activation.finally(() => this.document.location.reload());
  }

  /** Masqué jusqu'au prochain chargement : la version s'appliquera au prochain lancement. */
  protected plusTard(): void {
    this.etat.set(null);
  }

  /** Au plus une vérification toutes les 15 min ; un échec (hors ligne) autorise un nouvel essai. */
  private verifier(): void {
    const maintenant = Date.now();
    if (maintenant - this.derniereVerification < INTERVALLE_MIN_VERIFICATION_MS) {
      return;
    }
    this.derniereVerification = maintenant;
    this.swUpdate.checkForUpdate().catch(() => (this.derniereVerification = 0));
  }
}
