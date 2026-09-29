package fr.backyard.repository;

import fr.backyard.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Comptes coureurs (inc. 5). Toutes les recherches portent sur un pseudo déjà normalisé par
 * {@link fr.backyard.domain.Pseudo#normalize(String)} et le comparent à l'identique (RG2) : aucune requête ne
 * met un pseudo en minuscules ni n'ignore la casse.
 */
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByPseudo(String pseudo);

    boolean existsByPseudo(String pseudo);

    /**
     * Comptes dont le pseudo contient littéralement le fragment (RG24) : Spring Data échappe {@code %}, {@code _}
     * et le caractère d'échappement dans le paramètre d'un {@code Containing}, qui n'ont donc aucun rôle de joker.
     * Tri par pseudo puis par id croissants.
     */
    List<Account> findByPseudoContainingOrderByPseudoAscIdAsc(String fragment);

    /** Tous les comptes, triés par pseudo puis par id croissants (RG24). */
    List<Account> findAllByOrderByPseudoAscIdAsc();
}
