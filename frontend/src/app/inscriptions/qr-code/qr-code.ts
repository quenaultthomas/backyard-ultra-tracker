import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { encode } from 'uqr';

/** Zone de silence de 4 modules, correction d'erreur M (lisible malgré un écran rayé ou un reflet). */
const OPTIONS_QR = { ecc: 'M', border: 4 } as const;

/** Dessin du QR code : côté en modules (zone de silence comprise) et tracé SVG des modules noirs. */
interface DessinQr {
  readonly cote: number;
  readonly trace: string;
}

/**
 * QR code dessiné localement en SVG, noir sur blanc, encodant exactement `contenu`.
 * Aucun appel réseau ; si la génération échoue, un message remplace le dessin.
 */
@Component({
  selector: 'app-qr-code',
  templateUrl: './qr-code.html',
  styleUrl: './qr-code.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class QrCode {
  readonly contenu = input.required<string>();
  /** Texte alternatif du QR code. */
  readonly libelle = input.required<string>();

  protected readonly dessin = computed(() => dessiner(this.contenu()));
}

function dessiner(contenu: string): DessinQr | null {
  if (contenu.length === 0) {
    return null;
  }
  try {
    const { size, data } = encode(contenu, OPTIONS_QR);
    let trace = '';
    data.forEach((ligne, y) =>
      ligne.forEach((noir, x) => {
        if (noir) {
          trace += `M${x} ${y}h1v1h-1z`;
        }
      }),
    );
    return { cote: size, trace };
  } catch {
    return null;
  }
}
