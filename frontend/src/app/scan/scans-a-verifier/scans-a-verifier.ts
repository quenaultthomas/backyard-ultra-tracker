import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  viewChildren,
} from '@angular/core';

import { FileScansService } from '../file-scans/file-scans.service';
import { ScanAVerifier } from '../file-scans/scan-en-file';
import { proposeLaReintegration } from '../refus-scan';

/**
 * « Scans à vérifier » d'une Course : refus reçus en différé et scans trop anciens, gardés sur
 * l'appareil jusqu'à « Compris ». Le serveur a décidé ; la liste ne fait que les montrer.
 */
@Component({
  selector: 'app-scans-a-verifier',
  templateUrl: './scans-a-verifier.html',
  styleUrl: './scans-a-verifier.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScansAVerifier {
  private readonly file = inject(FileScansService);
  private readonly injector = inject(Injector);
  private readonly boutonsCompris = viewChildren<ElementRef<HTMLButtonElement>>('compris');

  readonly courseId = input.required<string>();

  protected readonly lignes = computed(() => this.file.aVerifier(this.courseId()));
  protected readonly proposeLaReintegration = proposeLaReintegration;

  /** Le focus passe au « Compris » suivant (ou précédent) : il ne disparaît pas avec la ligne. */
  protected comprendre(ligne: ScanAVerifier, index: number): void {
    this.file.marquerCompris(ligne.identifiantScan);
    afterNextRender(
      () => {
        const boutons = this.boutonsCompris();
        boutons[Math.min(index, boutons.length - 1)]?.nativeElement.focus();
      },
      { injector: this.injector },
    );
  }
}
