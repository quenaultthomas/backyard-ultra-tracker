import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { FileScansService } from '../file-scans/file-scans.service';

/**
 * État de la file de scans sur l'écran d'une Course : hors ligne, stockage indisponible, session
 * expirée, compteur « N en attente » avec « Renvoyer maintenant », et fin d'envoi.
 */
@Component({
  selector: 'app-etat-file',
  templateUrl: './etat-file.html',
  styleUrl: './etat-file.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EtatFile {
  protected readonly file = inject(FileScansService);

  readonly courseId = input.required<string>();

  protected readonly enAttente = computed(() => this.file.enAttente(this.courseId()));
  protected readonly messageSessionExpiree = computed(() => {
    const nombre = this.file.sessionExpiree();
    if (nombre === null) {
      return null;
    }
    const scans = nombre > 1 ? `les ${nombre} scans en attente` : 'le scan en attente';
    return `Votre session a expiré. Reconnectez-vous pour envoyer ${scans}.`;
  });
  protected readonly messageEnvoyes = computed(() => {
    const nombre = this.file.scansEnvoyes();
    if (!nombre) {
      return null;
    }
    return nombre > 1 ? `${nombre} scans envoyés` : '1 scan envoyé';
  });
}
