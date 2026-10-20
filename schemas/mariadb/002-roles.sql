-- SPDX-License-Identifier: Apache-2.0
--
-- Two roles on the orphera database, and no passwords: this file is in a public
-- repository. Create the accounts by hand, with a password from the secret store
-- and TLS required, as docs/MARIADB.md shows. Run as a user that may create roles:
--   mariadb < 002-roles.sql

CREATE ROLE IF NOT EXISTS orphera_reader;
GRANT SELECT ON orphera.* TO orphera_reader;

CREATE ROLE IF NOT EXISTS orphera_admin;
GRANT SELECT, INSERT, UPDATE, DELETE ON orphera.* TO orphera_admin;
