import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** Liste des Courses lue sur l'appareil faute de réseau : sa date, et le rappel qu'elle a pu changer. */
@Component({
  selector: 'app-donnees-hors-ligne',
  imports: [DatePipe],
  template: `
    <p
      class="message--info message-pictogramme pictogramme-hors-ligne"
      role="status"
      data-testid="donnees-hors-ligne"
    >
      Hors ligne : liste des courses enregistrée le
      {{ enregistreLe() | date: "dd/MM 'à' HH:mm" }}. Le statut de la course a pu changer.
    </p>
  `,
  styles: `
    p {
      font-size: var(--taille-moyenne);
      overflow-wrap: anywhere;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DonneesHorsLigne {
  /** Instant ISO de l'enregistrement de la liste. */
  readonly enregistreLe = input.required<string>();
}
