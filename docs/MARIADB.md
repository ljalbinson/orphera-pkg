# The inventory and configs in MariaDB

`schemas/mariadb/` has the inventory and the per-VM configs as MariaDB tables, for a future `-I mariadb://...` /
`-c mariadb://...` source (see [`-I` and `-c`](../README.md#where-the-inventory-and-configs-are)). **Orphera cannot read
from MariaDB yet.** This is the database design and a way to load the existing files into it; a reader in the
orchestrator is a separate piece of work (a JDBC driver, and a `Sources.register("mariadb", ...)` reader).

The tables hold the same data as the [JSON Schemas](SCHEMAS.md). Where a schema could only say "checked by
`validate-schemas.py`", the database enforces the rule itself:

| Rule | In the database |
|---|---|
| node names and group names are unique | primary keys |
| a group member is a node | foreign key `group_member.node_name` |
| a VM's `hypervisor` is a node, and cannot be deleted while it has VMs | foreign key `vm_config.hypervisor`, `ON DELETE RESTRICT` |
| a VM is not its own hypervisor | `CHECK` |
| no two nodes share a `cluster_ip` | unique key on a generated column of `node_var` |
| no two VMs share a `nic0` address, and a hostname has one config | unique key, primary key |
| a NIC address has a netmask or a cidr | `CHECK` on `vm_nic` |
| names are plain characters; ports and MTUs are in range; sizes are at least 1 | `CHECK`s |
| IPv4 addresses are valid | `INET4` columns (the server refuses `10.1.1.300`) |

`vm_config.hostname` is deliberately not a foreign key to `node`: a VM is added to the inventory after it is provisioned.

## Tables

| Table | Holds | YAML it replaces |
|---|---|---|
| `node`, `node_var` | hosts, port, `never_reboot`, and their vars | `nodes:` |
| `node_group`, `group_member`, `group_var` | groups, members, vars | `groups:` |
| `vm_config` | one row per per-VM config, with `nic0_*` columns | `params:` of `config/<vm>.yaml` |
| `vm_disk`, `vm_nic`, `vm_package` | `disks:`, `nics:`, `pre_basic_packages:` and `basic_packages:`, in order | the lists in `params:` |
| `config_document` | any other kind of config as a JSON document (`kind = 'dns'` is `config/dns.yaml`) | `params:` of those files |
| `orphera_schema` | the version of this layout (1) | `schema_version` |

Differences from the YAML, which a reader undoes: `disk`, `memory` and `cpu` are integers here and strings in the file;
`table` is `route_table` (a reserved word); a list has an `ordinal` column that keeps its order; a `pdisks:` entry
(not implemented) is not stored; `x-` keys of a VM config are kept as JSON in `vm_config.extensions`.

## Setting it up

Needs MariaDB 10.10 or later (`INET4`); Ubuntu 24.04 has 10.11. A separate server, not the Galera cluster, so the
inventory does not depend on the cluster it describes.

```
mariadb < schemas/mariadb/001-tables.sql      # the orphera database and its tables
mariadb < schemas/mariadb/002-roles.sql       # roles orphera_reader and orphera_admin, no accounts
```

The accounts are made by hand, so that no password is in this repository. Generate one in the secret store and put it
in the statement through the shell, not on a command line, where `ps` would show it:

```
orphera secrets generate orphera/inventory-db-reader
PW=$(orphera secrets get orphera/inventory-db-reader)
mariadb <<SQL
CREATE USER 'orphera_ro'@'10.10.5.%' IDENTIFIED BY '$PW' REQUIRE SSL;
GRANT orphera_reader TO 'orphera_ro'@'10.10.5.%';
SET DEFAULT ROLE orphera_reader FOR 'orphera_ro'@'10.10.5.%';
SQL
```

The same for `orphera_admin` with the account that loads and edits the data. The server needs TLS configured
(`ssl_cert`, `ssl_key`, `ssl_ca`, which `orphera issue-certs` can supply keys for), and the orchestrator is the only
client that needs `orphera_reader`.

## Loading the existing files

```
utils/yaml-to-sql.py | mariadb orphera              # first load: fails if anything is there already
utils/yaml-to-sql.py --replace | mariadb orphera    # make the database what the files are
utils/yaml-to-sql.py -I path/inv.yaml -c path/config-dir > load.sql
```

`yaml-to-sql.py` checks the files first (the same checks as `utils/validate-schemas.py`) and writes nothing if there is
an error. It writes SQL and does not connect to anything. The output is one transaction, so a failure leaves the
database as it was. Needs `python3-jsonschema` and `python3-yaml`.

## What was tested, and what was not

There was no MariaDB where this was written. What was tested is a translation of the same tables to SQLite (the
dialect differences removed: `INET4`, `UNSIGNED`, `ENGINE=`, and so on): the real inventory and all 21 per-VM configs
and the DNS config were loaded; the data was read back into documents that matched the originals exactly and validated
against the JSON Schemas; and the keys and checks refused what they should (duplicate names and `cluster_ip`, a missing
hypervisor or group member, a bad port, an unsubnetted NIC address, a duplicate package, invalid JSON, deleting a
hypervisor that has VMs). Not tested: MariaDB's own parsing of `001-tables.sql` (`INET4`, the generated column,
`CHECK ... REGEXP`), roles, and TLS. Run the two `.sql` files on a scratch server first and send me any error.
