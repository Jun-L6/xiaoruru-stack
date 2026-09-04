-- V2 uses stable IDs so AI taxonomy references remain readable. Advance H2's
-- identity sequence beyond that seeded range before administrators add categories.
ALTER TABLE categories ALTER COLUMN id RESTART WITH 1000;
