import { describe, expect, it } from 'vitest';
import { showAccountLinkAfterConflict } from './outcomes';

describe('RG17 / D1 - lien « J\'ai déjà un compte » après un 409 sur E3', () => {
  it('course relue encore en SETUP (inscriptions ouvertes), aucun coureur connecté : lien proposé', () => {
    expect(showAccountLinkAfterConflict({ registrationOpen: true }, false)).toBe(true);
  });

  it('course relue qui n\'est plus en SETUP (inscriptions fermées) : aucun lien', () => {
    expect(showAccountLinkAfterConflict({ registrationOpen: false }, false)).toBe(false);
  });

  it('course non relue (échec du rechargement) : aucun lien', () => {
    expect(showAccountLinkAfterConflict(null, false)).toBe(false);
  });

  it('coureur déjà connecté : aucun lien, même course ouverte', () => {
    expect(showAccountLinkAfterConflict({ registrationOpen: true }, true)).toBe(false);
  });
});
