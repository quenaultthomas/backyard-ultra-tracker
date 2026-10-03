/**
 * Lit `fichier` en URL `data:` (aperçu autorisé par la CSP `img-src 'self' data:`) et vérifie que
 * le navigateur sait la décoder. Renvoie `null` pour un fichier illisible (texte, image
 * corrompue). Ni la taille, ni le format ne sont contrôlés : c'est le rôle de l'API.
 */
export function lireImage(fichier: Blob): Promise<string | null> {
  return new Promise<string | null>((resoudre) => {
    const lecteur = new FileReader();
    lecteur.onerror = () => resoudre(null);
    lecteur.onload = () => {
      const url = lecteur.result;
      if (typeof url !== 'string') {
        resoudre(null);
        return;
      }
      const image = new Image();
      image.src = url;
      image.decode().then(
        () => resoudre(url),
        () => resoudre(null),
      );
    };
    lecteur.readAsDataURL(fichier);
  });
}
