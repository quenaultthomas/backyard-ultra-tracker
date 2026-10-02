import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** Écran public affiché quand le rôle du Compte ne permet pas d'ouvrir la page demandée. */
@Component({
  selector: 'app-acces-refuse',
  imports: [RouterLink],
  templateUrl: './acces-refuse.html',
  styleUrl: '../../partage/page-carte.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccesRefuse {}
