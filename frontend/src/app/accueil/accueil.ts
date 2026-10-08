import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Accueil public : titre et accroche sur le décor backyard, identique pour tous. */
@Component({
  selector: 'app-accueil',
  templateUrl: './accueil.html',
  styleUrl: './accueil.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Accueil {}
