-- V2__account.sql : comptes coureurs « pseudo » (spec incrément 5, RG1, RG4, RG5, RG6)
-- Cascade : RESTRICT (pas de suppression en cascade)

-- RG1 : exactement trois colonnes. RG5 : pseudo stocké normalisé (minuscules) par l'application,
-- l'unicité exacte en base suffit donc à la rendre insensible à la casse saisie.
CREATE TABLE account (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pseudo        VARCHAR(30) NOT NULL,
    password_hash VARCHAR(60) NOT NULL,
    CONSTRAINT uq_account_pseudo UNIQUE (pseudo)
);

-- RG4 : lien nullable, au plus un coureur par compte et par course.
ALTER TABLE runner ADD COLUMN account_id BIGINT;
ALTER TABLE runner ADD CONSTRAINT fk_runner_account
    FOREIGN KEY (account_id) REFERENCES account (id) ON DELETE RESTRICT;
ALTER TABLE runner ADD CONSTRAINT uq_runner_race_account UNIQUE (race_id, account_id);

-- RG6 : le nom propre du coureur n'est plus alimenté ; les noms réels existants sont purgés,
-- quel que soit le statut de la course. La colonne est conservée, vide.
ALTER TABLE runner ALTER COLUMN name DROP NOT NULL;
UPDATE runner SET name = NULL;
