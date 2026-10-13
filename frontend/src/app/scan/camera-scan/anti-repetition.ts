/** Absence consécutive d'un QR devant la caméra au-delà de laquelle il est de nouveau pris en compte. */
const DELAI_REARMEMENT_MILLISECONDES = 1_500;

/**
 * Anti-répétition de lecture : une caméra relit le même QR plusieurs fois par seconde.
 * Un contenu retenu est ignoré tant qu'il est revu moins de 1,5 s après sa dernière lecture.
 * Mémoire vive uniquement.
 */
export class AntiRepetition {
  private readonly derniersVus = new Map<string, number>();

  /** Prend en compte une analyse ; vrai si le contenu lu est nouveau (absent depuis 1,5 s au moins). */
  estNouveau(contenu: string | null, instant: number): contenu is string {
    for (const [contenuVu, vuLe] of this.derniersVus) {
      if (instant - vuLe >= DELAI_REARMEMENT_MILLISECONDES) {
        this.derniersVus.delete(contenuVu);
      }
    }
    if (contenu === null) {
      return false;
    }
    if (this.derniersVus.has(contenu)) {
      this.derniersVus.set(contenu, instant);
      return false;
    }
    return true;
  }

  retenir(contenu: string, instant: number): void {
    this.derniersVus.set(contenu, instant);
  }

  oublier(): void {
    this.derniersVus.clear();
  }
}
