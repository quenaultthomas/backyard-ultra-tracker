-- V1__init.sql : schéma initial Backyard Ultra Tracker
-- Cascade : RESTRICT (pas de suppression en cascade)

CREATE TABLE race (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name           VARCHAR(255)    NOT NULL,
    race_date      DATE            NOT NULL,
    status         VARCHAR(20)     NOT NULL DEFAULT 'SETUP',
    started_at     TIMESTAMP WITH TIME ZONE,
    loop_distance  INT             NOT NULL CHECK (loop_distance > 0),
    loop_duration  INT             NOT NULL CHECK (loop_duration > 0),
    loop_elevation INT             NOT NULL CHECK (loop_elevation >= 0),
    CONSTRAINT uq_race_name UNIQUE (name)
);

CREATE TABLE runner (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    race_id    BIGINT       NOT NULL,
    bib        INT          NOT NULL CHECK (bib > 0),
    name       VARCHAR(255) NOT NULL,
    qr_token   VARCHAR(36)  NOT NULL,
    status     VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE',
    dnf_reason VARCHAR(10),
    dnf_yard   INT,
    CONSTRAINT fk_runner_race   FOREIGN KEY (race_id) REFERENCES race (id) ON DELETE RESTRICT,
    CONSTRAINT uq_runner_race_bib  UNIQUE (race_id, bib),
    CONSTRAINT uq_runner_qr_token  UNIQUE (qr_token)
);

CREATE TABLE passage (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    runner_id   BIGINT      NOT NULL,
    scanned_at  TIMESTAMP WITH TIME ZONE,
    yard_number INT         NOT NULL CHECK (yard_number >= 1),
    source      VARCHAR(10) NOT NULL,
    CONSTRAINT fk_passage_runner FOREIGN KEY (runner_id) REFERENCES runner (id) ON DELETE RESTRICT,
    CONSTRAINT uq_passage_runner_yard UNIQUE (runner_id, yard_number)
);
