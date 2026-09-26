import { Component, computed, input } from '@angular/core';
import { qrCodeDataUrl } from '../infra/qr-code';

/** QR code dont le contenu est exactement le `qrToken` (RG12, RG39, RG40), généré localement. */
@Component({
  selector: 'app-qr-image',
  template: `<img class="qr-image" [src]="dataUrl()" [alt]="label()" width="264" height="264" />`,
})
export class QrImage {
  readonly value = input.required<string>();
  readonly label = input('QR code du coureur');
  protected readonly dataUrl = computed(() => qrCodeDataUrl(this.value()));
}
