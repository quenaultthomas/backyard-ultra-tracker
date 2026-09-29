import { describe, expect, it } from 'vitest';
import {
  accountPasswordError,
  PASSWORD_MISMATCH_MESSAGE,
  PASSWORD_RULE_MESSAGE,
  passwordChangeErrors,
  PSEUDO_FORMAT_MESSAGE,
  pseudoError,
  registrationFormErrors,
} from './account-validation';

describe('CA27 - validation du formulaire d\'inscription (RG17, miroir de RG2 et RG3)', () => {
  it('refuse ab, Jean Dupont, un mot de passe de 7 caractères et deux mots de passe différents', () => {
    expect(registrationFormErrors('ab', 'motdepasse-1', 'motdepasse-1').get('pseudo')).toBe(PSEUDO_FORMAT_MESSAGE);
    expect(registrationFormErrors('Jean Dupont', 'motdepasse-1', 'motdepasse-1').get('pseudo'))
      .toBe(PSEUDO_FORMAT_MESSAGE);
    expect(registrationFormErrors('Lievre_42', 'court12', 'court12').get('password')).toBe(PASSWORD_RULE_MESSAGE);
    expect(registrationFormErrors('Lievre_42', 'motdepasse-1', 'motdepasse-2').get('confirmation'))
      .toBe(PASSWORD_MISMATCH_MESSAGE);
  });

  it('accepte Lievre_42 / motdepasse-1 confirmé : aucune erreur', () => {
    expect(registrationFormErrors('Lievre_42', 'motdepasse-1', 'motdepasse-1').size).toBe(0);
  });

  it('pseudo : espaces de bord ignorés, majuscules acceptées, 3 à 30 caractères sans accent', () => {
    expect(pseudoError('  Lievre  ')).toBeNull();
    expect(pseudoError('LIEVRE')).toBeNull();
    expect(pseudoError('A'.repeat(30))).toBeNull();
    expect(pseudoError('A'.repeat(31))).toBe(PSEUDO_FORMAT_MESSAGE);
    expect(pseudoError('élan')).toBe(PSEUDO_FORMAT_MESSAGE);
    expect(pseudoError('')).toBe(PSEUDO_FORMAT_MESSAGE);
  });

  it('mot de passe : 8 caractères au moins, 72 octets UTF-8 au plus, espaces conservés', () => {
    expect(accountPasswordError('huitcar8')).toBeNull();
    expect(accountPasswordError('a'.repeat(72))).toBeNull();
    expect(accountPasswordError('a'.repeat(73))).toBe(PASSWORD_RULE_MESSAGE);
    expect(accountPasswordError('é'.repeat(37))).toBe(PASSWORD_RULE_MESSAGE);
    expect(accountPasswordError('é'.repeat(36))).toBeNull();
    expect(accountPasswordError('        ')).toBeNull();
  });
});

describe('CA27 - validation du changement de mot de passe (RG17, RG19)', () => {
  it('refuse 7 caractères et deux saisies différentes ; accepte deux saisies égales valides', () => {
    expect(passwordChangeErrors('court12', 'court12').get('newPassword')).toBe(PASSWORD_RULE_MESSAGE);
    expect(passwordChangeErrors('nouveau-mdp-43', 'nouveau-mdp-44').get('confirmation'))
      .toBe(PASSWORD_MISMATCH_MESSAGE);
    expect(passwordChangeErrors('nouveau-mdp-43', 'nouveau-mdp-43').size).toBe(0);
  });
});
