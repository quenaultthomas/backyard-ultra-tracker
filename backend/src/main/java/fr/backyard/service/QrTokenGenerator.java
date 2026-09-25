package fr.backyard.service;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Génère le qr_token opaque d'un coureur (RG16 inc. 3) : UUID version 4 tiré d'un SecureRandom
 * (122 bits d'entropie), forme canonique minuscule de 36 caractères, non dérivé des données du coureur.
 */
@Component
public class QrTokenGenerator {

    public String generate() {
        return UUID.randomUUID().toString();
    }
}
