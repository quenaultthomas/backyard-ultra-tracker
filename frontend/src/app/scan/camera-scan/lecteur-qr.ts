/** Au plus 8 analyses par seconde. */
const INTERVALLE_ANALYSE_MILLISECONDES = 125;
/** Image réduite avant analyse. */
const LARGEUR_ANALYSE_MAX = 640;

type DecodeurQr = typeof import('jsqr').default;

/**
 * Lecture continue des QR codes de l'aperçu, localement : chaque image est réduite dans un
 * canevas hors du DOM puis décodée ; aucune image ne quitte l'appareil.
 * La bibliothèque de lecture est chargée à la première activation.
 */
export class LecteurQr {
  private readonly canevas = document.createElement('canvas');
  private readonly contexte = this.canevas.getContext('2d', { willReadFrequently: true });
  private minuterie: ReturnType<typeof setTimeout> | undefined;
  private generation = 0;

  /** Appelle `surLecture` à chaque analyse avec le contenu lu, ou `null` sans QR lisible. */
  async demarrer(
    video: () => HTMLVideoElement | undefined,
    surLecture: (contenu: string | null, instant: number) => void,
  ): Promise<void> {
    const generation = ++this.generation;
    const { default: decoder } = await import('jsqr');
    const analyser = (): void => {
      if (generation !== this.generation) {
        return;
      }
      surLecture(this.lire(decoder, video()), performance.now());
      this.minuterie = setTimeout(analyser, INTERVALLE_ANALYSE_MILLISECONDES);
    };
    analyser();
  }

  arreter(): void {
    this.generation++;
    clearTimeout(this.minuterie);
  }

  private lire(decoder: DecodeurQr, video: HTMLVideoElement | undefined): string | null {
    if (!video || !this.contexte || video.readyState < video.HAVE_CURRENT_DATA) {
      return null;
    }
    const echelle = Math.min(1, LARGEUR_ANALYSE_MAX / video.videoWidth);
    const largeur = Math.round(video.videoWidth * echelle);
    const hauteur = Math.round(video.videoHeight * echelle);
    if (largeur === 0 || hauteur === 0) {
      return null;
    }
    this.canevas.width = largeur;
    this.canevas.height = hauteur;
    this.contexte.drawImage(video, 0, 0, largeur, hauteur);
    const image = this.contexte.getImageData(0, 0, largeur, hauteur);
    return decoder(image.data, largeur, hauteur, { inversionAttempts: 'dontInvert' })?.data || null;
  }
}
