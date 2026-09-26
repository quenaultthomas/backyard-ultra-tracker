/**
 * Validation locale du contenu lu (RG16) : espaces de début et de fin retirés, minuscules, format canonique
 * d'UUID (format du qrToken, RG16 inc. 3). Tout autre contenu est « QR non reconnu ».
 */

const QR_TOKEN_FORMAT = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** Token normalisé, ou null si le contenu n'est pas un qrToken. */
export function normalizeQrToken(content: string): string | null {
  const candidate = content.trim().toLowerCase();
  return QR_TOKEN_FORMAT.test(candidate) ? candidate : null;
}

/** 4 derniers caractères du token, pour l'affichage d'un rejet (RG25). */
export function tokenSuffix(qrToken: string): string {
  return qrToken.slice(-4);
}
