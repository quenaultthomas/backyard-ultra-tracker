/**
 * Tire un UUID v4 aléatoire. `crypto.randomUUID` n'existe qu'en contexte sécurisé (HTTPS ou
 * localhost) : ailleurs, l'UUID est construit à partir de `crypto.getRandomValues`.
 */
export function tirerIdentifiantScan(): string {
  if (typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const octets = crypto.getRandomValues(new Uint8Array(16));
  octets[6] = (octets[6] & 0x0f) | 0x40;
  octets[8] = (octets[8] & 0x3f) | 0x80;
  const hexa = Array.from(octets, (octet) => octet.toString(16).padStart(2, '0')).join('');
  return `${hexa.slice(0, 8)}-${hexa.slice(8, 12)}-${hexa.slice(12, 16)}-${hexa.slice(16, 20)}-${hexa.slice(20)}`;
}
