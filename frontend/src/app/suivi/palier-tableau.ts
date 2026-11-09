/** Taille des polaroïds du tableau du suivi, selon l'effectif reçu de « Coureurs en course ». */
export type PalierTableau = 'grand' | 'moyen' | 'petit' | 'compact';

/** Palier d'affichage : jusqu'à 12 grand, 13 à 30 moyen, 31 à 60 petit, au-delà compact. */
export function palierTableau(effectif: number): PalierTableau {
  if (effectif <= 12) {
    return 'grand';
  }
  if (effectif <= 30) {
    return 'moyen';
  }
  return effectif <= 60 ? 'petit' : 'compact';
}

/** Paliers pour lesquels le tableau occupe toute la largeur de l'écran. */
export function estPalierLarge(palier: PalierTableau): boolean {
  return palier === 'petit' || palier === 'compact';
}
