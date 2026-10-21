# The inventory and configs in MariaDB

`schemas/mariadb/` has the inventory and the per-VM configs as MariaDB tables, with a loader from the files
(`yaml-to-sql.py`), a reader that writes them back (`sql-to-yaml.py`), and a `mariadb://` source for `orphera` itself
(see [`-I` and `-c`](../README.md#where-the-inventory-and-configs-are) and [Reading it with orphera](#reading-it-with-orphera)).
**The files in git are the source of truth**; the database is a copy loaded from them, and `orphera` only reads it.

## Reading it with orphera

```
orphera -I mariadb://orphera_ro@dbhost.example.com/orphera/inventory \
        -c mariadb://orphera_ro@dbhost.example.com/orphera/config \
        version
```

The first form names the inventory, the second the directory of configs; `--config tst3` is then
`mariadb://.../config/tst3.yaml`, and the last part of the path is the config's name (a VM's hostname, or a
`config_document` such as `dns`). The port defaults to 3306. The same two variables work: `ORPHERA_INVENTORY`,
`ORPHERA_CONFIG_DIR`.

- **Read-only.** `orphera` runs `SELECT`s, nothing else. The account should be able to do no more (role `orphera_reader`).
- **Always verified.** The connection checks the server's certificate and its host name against the Orphera CA,
  `certs/ca.crt` or the file named by `ORPHERA_DB_CA`. The host in the URI must be a name on the certificate
  (`dbhost.ljalbinson.com`). There is no option to skip this.
- **Password.** The secret `orphera/inventory-db-reader`, which must exist: it is not made on first use, so a missing secret
  is an error and not a new, wrong password. The URI cannot carry a password; one that does is refused.
- **Same data.** The rows become the documents the files hold (`DbDocuments.scala`, the twin of `sql-to-yaml.py`), so
  everything downstream is unchanged. Within one run the tables are fetched once.
- **Failure mode.** If the database cannot be read, the inventory falls back to empty with a warning, as for a missing file.
  Check `orphera -I ... version` lists the fleet before relying on a `never-reboot` that comes from the database.
- The shell scripts still read files only (`ORPHERA_CONFIG_DIR` and `ORPHERA_INVENTORY` as paths); export with
  `sql-to-yaml.py --out DIR` for them.

To check the Scala reader against the files, compare what each source gives. The inventory:
`diff <(orphera -I inventory.yaml version | sort) <(orphera -I mariadb://... version | sort)` (the database is sorted by name,
the file is not). The configs: `orphera show-config` prints each config as read, so
`names=$(ls config | sed 's/\.yaml$//')` then `diff <(orphera -c config show-config $names) <(orphera -c mariadb://.../config show-config $names)`
(`dns` is not a VM and prints the same error from both). `utils/sql-to-yaml.py --compare` checks the database against the files
without `orphera`.

## Reading it back

`utils/sql-to-yaml.py` is the reverse of `yaml-to-sql.py`. It uses only `SELECT`, through the `mariadb` command-line client
(the arguments you would give it to connect, in one string; the password is read from the secret
`orphera/inventory-db-reader`, or from `MYSQL_PWD`, and is not put on the command line):

```
utils/sql-to-yaml.py --mariadb="-h DBHOST -u orphera_ro --ssl-ca certs/ca.crt orphera" --compare
utils/sql-to-yaml.py --mariadb="..." --out /tmp/from-db
```

`--compare` checks that the database holds what `inventory.yaml` and `config/*.yaml` hold, as data and not as text, and
prints one line per difference (exit 1), for example `config vm tst0.disks: file [256, 256] != database [256, 128]`.
`--out DIR` writes `DIR/inventory.yaml` and `DIR/config/*.yaml` (the directory must be empty or new). A rebuild can then be
started against the copy with `ORPHERA_INVENTORY=DIR/inventory.yaml ORPHERA_CONFIG_DIR=DIR/config`. The `orphera` command
and the shell scripts both follow these two variables; the options `-I` and `-c` are only for `orphera`.

What the database cannot hold, and so the export differs in: the order of nodes, groups and members (the export is sorted by
name), and `pdisks` (`yaml-to-sql.py` now refuses a non-empty one instead of dropping it; all the current configs have it empty).

`make check-roundtrip` runs `utils/test-sql-roundtrip.py`: it creates the tables in SQLite from the column lists in
`001-tables.sql`, loads the real files with `yaml-to-sql.py`, reads them back with `sql-to-yaml.py`, and checks the two agree,
that the exported files pass `validate-schemas.py`, that a made-up config with the cases the real files lack (extra NICs, scalar disks,
extension keys, a custom port, group vars) survives the trip, and that a changed or missing row is reported. It needs no
database server, so it does not test the server's own rules, the `mariadb` client path, or TLS.

## Status (2026-10-21)

Checked against the real server, from scala0, over the verified TLS connection:

- `utils/sql-to-yaml.py --compare`: the database equals `inventory.yaml` and `config/*.yaml`.
- `orphera -I mariadb://... version` lists the same 25 nodes as the file (the database is sorted by name; compare with `sort`).
- `orphera -c mariadb://... show-config` prints the same 22 configs as `orphera -c config show-config`.
- The export (`sql-to-yaml.py --out DIR`) passes `validate-schemas.py`, works as `-I`/`-c`, and `rebuild_openstack_from_scratch.sh --dry-run`
  prints the same plan from it.

Not done: a real rebuild from the database. It tears down and rebuilds the OpenStack VMs, so it is a planned run, not a test.
What it would still depend on:

| Gap | Detail |
|---|---|
| Scripts read files | The shell scripts read a VM's hypervisor and address from `config/<vm>.yaml`. They take the directory from `ORPHERA_CONFIG_DIR` and the inventory from `ORPHERA_INVENTORY`, as `orphera` does (`utils/orphera-sources.sh`), but only files, so a database has to be exported first (`sql-to-yaml.py --out DIR`). They do not read `-I`/`-c`. |
| Copy, not source | The files in git are the source of truth (decided 2026-10-21). The database must be reloaded with `yaml-to-sql.py --replace` after any change to a file, and `sql-to-yaml.py --compare` shows when it is behind. |
| Failure mode | If the inventory cannot be read, `orphera` falls back to an empty inventory with a warning; with a database, an outage would look the same. `never-reboot` is also then absent from `orphera`, though a host's own agent marker still holds. |
| One account per client host | The login accounts are per host (`orphera_ro@scala0.ljalbinson.com` for scala0, `orphera_ro@192.168.1.91` for DBeaver); the role `orphera_reader` cannot log in. |

The tables hold the same data as the [JSON Schemas](SCHEMAS.md). Where a schema could only say "checked by
`validate-schemas.py`", the database enforces the rule itself:

| Rule | In the database |
|---|---|
| node names and group names are unique | primary keys |
| a group member is a node | foreign key `group_member.node_name` |
| a VM's `hypervisor` is a node, and cannot be deleted while it has VMs | foreign key `vm_config.hypervisor`, `ON DELETE RESTRICT` |
| a VM is not its own hypervisor | not in the database (MariaDB refuses a `CHECK` on a cascading foreign key column); `validate-schemas.py` and `yaml-to-sql.py` refuse it |
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

The account has to exist for the host the client connects from, as the server sees it (`scala0.ljalbinson.com`, say, for a client on scala0), so create one per client host. Roles cannot log in. The same for `orphera_admin` with the account that loads and edits the data. The server needs TLS configured
(`ssl_cert`, `ssl_key`, `ssl_ca`) for `REQUIRE SSL` to work, and the orchestrator is the only client that needs
`orphera_reader`.

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
