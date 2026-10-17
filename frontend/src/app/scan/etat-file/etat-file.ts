import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';

import { FileScansService } from '../file-scans/file-scans.service';

function scans(nombre: number): string {
  return nombre > 1 ? `${nombre} scans` : `${nombre} scan`;
}

/**
 * État de la file de scans sur l'écran d'une Course : hors ligne, stockage indisponible, session
 * expirée, compteur « N en attente » avec « Renvoyer maintenant », et fin d'envoi (dont scans à
 * vérifier).
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
    const bilan = this.file.scansEnvoyes();
    if (!bilan) {
      return null;
    }
    const { envoyes, aVerifier } = bilan;
    if (envoyes === 0) {
      return `${scans(aVerifier)} à vérifier`;
    }
    const message = `${scans(envoyes)} ${envoyes > 1 ? 'envoyés' : 'envoyé'}`;
    return aVerifier > 0 ? `${message}, dont ${aVerifier} à vérifier` : message;
  });
}
