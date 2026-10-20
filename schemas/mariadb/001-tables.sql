-- SPDX-License-Identifier: Apache-2.0
--
-- The inventory and the per-VM configs as MariaDB tables: the same data as
-- schemas/inventory.schema.json, vm-config.schema.json and dns-config.schema.json,
-- with the rules a JSON Schema cannot state (names are unique, group members and
-- hypervisors exist, addresses are not shared) made foreign keys and unique keys.
-- See docs/MARIADB.md.
--
-- Needs MariaDB 10.10 or later (INET4 columns; Ubuntu 24.04 has 10.11).
-- Run as a user that may create databases:  mariadb < 001-tables.sql
--
-- Names are compared case-sensitively (utf8mb4_bin), as Orphera compares them.
-- Numbers that the YAML files hold as quoted strings (disk, memory, cpu) are
-- integers here; a reader writes them back as strings.

CREATE DATABASE IF NOT EXISTS orphera
  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
USE orphera;

CREATE TABLE IF NOT EXISTS orphera_schema (
  version INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT IGNORE INTO orphera_schema (version) VALUES (1);

-- ---------------------------------------------------------------- inventory

CREATE TABLE node (
  name          VARCHAR(63)  NOT NULL,
  host          VARCHAR(255) NOT NULL,
  port          SMALLINT UNSIGNED NOT NULL DEFAULT 50051,
  never_reboot  BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (name),
  CONSTRAINT node_name_ok CHECK (name REGEXP '^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$'),
  CONSTRAINT node_port_ok CHECK (port BETWEEN 1 AND 65535)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE node_var (
  node_name  VARCHAR(63)   NOT NULL,
  name       VARCHAR(63)   NOT NULL,
  value      VARCHAR(1024) NOT NULL,
  -- No two nodes share a cluster_ip: NULL for every other var, so the unique
  -- key only ever compares cluster_ip values.
  cluster_ip_unique VARCHAR(45)
    GENERATED ALWAYS AS (CASE WHEN name = 'cluster_ip' THEN LEFT(value, 45) END) STORED,
  PRIMARY KEY (node_name, name),
  UNIQUE KEY node_var_cluster_ip (cluster_ip_unique),
  CONSTRAINT node_var_node FOREIGN KEY (node_name) REFERENCES node (name)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT node_var_name_ok CHECK (name REGEXP '^[A-Za-z_][A-Za-z0-9_-]*$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE node_group (
  name          VARCHAR(63) NOT NULL,
  never_reboot  BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (name),
  CONSTRAINT node_group_name_ok CHECK (name REGEXP '^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE group_member (
  group_name VARCHAR(63) NOT NULL,
  node_name  VARCHAR(63) NOT NULL,
  PRIMARY KEY (group_name, node_name),
  CONSTRAINT group_member_group FOREIGN KEY (group_name) REFERENCES node_group (name)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT group_member_node FOREIGN KEY (node_name) REFERENCES node (name)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE group_var (
  group_name VARCHAR(63)   NOT NULL,
  name       VARCHAR(63)   NOT NULL,
  value      VARCHAR(1024) NOT NULL,
  PRIMARY KEY (group_name, name),
  CONSTRAINT group_var_group FOREIGN KEY (group_name) REFERENCES node_group (name)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT group_var_name_ok CHECK (name REGEXP '^[A-Za-z_][A-Za-z0-9_-]*$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- ------------------------------------------------------------------ configs

-- One row per per-VM config. hostname is not a foreign key to node: a VM is
-- added to the inventory after it is provisioned. hypervisor is.
CREATE TABLE vm_config (
  hostname        VARCHAR(63)  NOT NULL,
  hypervisor      VARCHAR(63)  NOT NULL,
  domainname      VARCHAR(253) NOT NULL,
  os              VARCHAR(255) NOT NULL,
  disk_gb         INT UNSIGNED NOT NULL,
  memory_mib      INT UNSIGNED NOT NULL,
  cpu             INT UNSIGNED NOT NULL,
  host_type       VARCHAR(63)  NOT NULL,
  dns1            VARCHAR(45)  NOT NULL,
  dns2            VARCHAR(45)  NOT NULL,
  nic0_nic        VARCHAR(63)  NOT NULL,
  nic0_ipaddress  INET4 NOT NULL,
  nic0_netmask    INET4 NOT NULL,
  nic0_cidr       VARCHAR(18) NULL,
  nic0_gateway    INET4 NOT NULL,
  nic0_bridge     VARCHAR(63)  NOT NULL,
  nic0_mtu        SMALLINT UNSIGNED NOT NULL DEFAULT 1500,
  nic0_route_table INT UNSIGNED NOT NULL DEFAULT 0,
  nic0_priority   INT UNSIGNED NOT NULL DEFAULT 0,
  -- Keys beginning "x-" in params, as a JSON object; NULL if there are none.
  extensions      LONGTEXT NULL,
  updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (hostname),
  UNIQUE KEY vm_config_nic0_ipaddress (nic0_ipaddress),
  KEY vm_config_hypervisor_idx (hypervisor),
  CONSTRAINT vm_config_hypervisor FOREIGN KEY (hypervisor) REFERENCES node (name)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT vm_config_hostname_ok CHECK (hostname REGEXP '^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$'),
  CONSTRAINT vm_config_not_own_hypervisor CHECK (hypervisor <> hostname),
  CONSTRAINT vm_config_sizes_ok CHECK (disk_gb >= 1 AND memory_mib >= 1 AND cpu >= 1),
  CONSTRAINT vm_config_mtu_ok CHECK (nic0_mtu BETWEEN 576 AND 9216),
  CONSTRAINT vm_config_priority_ok CHECK (nic0_priority <= 32767),
  CONSTRAINT vm_config_extensions_ok CHECK (extensions IS NULL OR JSON_VALID(extensions))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Extra data disks, in the order they are attached (`disks:` in the YAML).
CREATE TABLE vm_disk (
  hostname  VARCHAR(63) NOT NULL,
  ordinal   SMALLINT UNSIGNED NOT NULL,
  size_gb   INT UNSIGNED NOT NULL,
  PRIMARY KEY (hostname, ordinal),
  CONSTRAINT vm_disk_vm FOREIGN KEY (hostname) REFERENCES vm_config (hostname)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT vm_disk_size_ok CHECK (size_gb >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Further NICs (`nics:`): ordinal 0 is the VM's second NIC.
CREATE TABLE vm_nic (
  hostname     VARCHAR(63) NOT NULL,
  ordinal      SMALLINT UNSIGNED NOT NULL,
  nic          VARCHAR(63) NULL,
  bridge       VARCHAR(63) NOT NULL,
  mtu          SMALLINT UNSIGNED NOT NULL DEFAULT 1500,
  ipaddress    INET4 NULL,
  netmask      INET4 NULL,
  cidr         VARCHAR(18) NULL,
  gateway      INET4 NULL,
  route_table  INT UNSIGNED NOT NULL DEFAULT 0,
  priority     INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (hostname, ordinal),
  CONSTRAINT vm_nic_vm FOREIGN KEY (hostname) REFERENCES vm_config (hostname)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT vm_nic_mtu_ok CHECK (mtu BETWEEN 576 AND 9216),
  CONSTRAINT vm_nic_priority_ok CHECK (priority <= 32767),
  -- An address needs its subnet, as netmask or cidr.
  CONSTRAINT vm_nic_address_has_subnet
    CHECK (ipaddress IS NULL OR netmask IS NOT NULL OR cidr IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- pre_basic_packages and basic_packages, in order.
CREATE TABLE vm_package (
  hostname  VARCHAR(63)  NOT NULL,
  phase     VARCHAR(16)  NOT NULL,
  ordinal   SMALLINT UNSIGNED NOT NULL,
  package_name VARCHAR(255) NOT NULL,
  PRIMARY KEY (hostname, phase, ordinal),
  UNIQUE KEY vm_package_once (hostname, phase, package_name),
  CONSTRAINT vm_package_vm FOREIGN KEY (hostname) REFERENCES vm_config (hostname)
    ON UPDATE CASCADE ON DELETE CASCADE,
  CONSTRAINT vm_package_phase_ok CHECK (phase IN ('pre_basic', 'basic')),
  CONSTRAINT vm_package_not_empty CHECK (package_name <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- A config of any other kind (the DNS config of gen_set_dns, and whatever a
-- playbook of your own reads): its `params` as a JSON document. `kind` says
-- which schema it follows (dns -> dns-config.schema.json).
CREATE TABLE config_document (
  kind        VARCHAR(32) NOT NULL,
  name        VARCHAR(63) NOT NULL,
  document    LONGTEXT NOT NULL,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (kind, name),
  CONSTRAINT config_document_json CHECK (JSON_VALID(document)),
  CONSTRAINT config_document_kind_ok CHECK (kind REGEXP '^[a-z][a-z0-9-]*$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
