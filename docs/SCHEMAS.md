# Schemas for the inventory and the config files

`schemas/` holds [JSON Schema](https://json-schema.org/) (draft 2020-12) for the files Orphera reads. They describe the
files as YAML or JSON documents, so a source that is not a file (a database, a connector: see
[`-I` and `-c`](../README.md#where-the-inventory-and-configs-are)) has a contract to meet: whatever it produces must
validate against the same schema.

| Schema | Describes | Read by |
|---|---|---|
| `schemas/inventory.schema.json` | `inventory.yaml`: nodes, groups, their vars, `never-reboot` | `InventoryYaml.scala` |
| `schemas/vm-config.schema.json` | a per-VM config, `config/<vm>.yaml` | `ConfigYaml.scala`: the KVM playbooks, `gen_packages`, `gen_dist_upgrade`, the bootstrap step |
| `schemas/dns-config.schema.json` | `config/dns.yaml`, the parameters of `gen_set_dns.scala` | `gen_set_dns.scala` |

All config files have a top-level `params:` map, but what is in it depends on who reads it, so a config is checked
against the schema of its reader, not one schema for "config". `disable_unattended_upgrades.scala` reads a `nodes:` list
from a config too, and has no schema yet.

## What the schemas say, and where they are stricter than Orphera

The schemas describe what Orphera is meant to be given, and they are stricter than the Scala readers in four ways. The
readers ignore a key they do not know; the schemas do not, so a typo (`memroy:`, `neverreboot:`) is caught.

- **Unknown keys are errors.** Every object has `additionalProperties: false`. A key starting with `x-` is allowed in a
  config's `params`, `nic0` and `nics` entries, for notes or for a playbook of your own. The inventory's `vars` are
  free-form, and the names Orphera's playbooks read (`cluster_ip`, `ceph_role`, `osd_disks`, `zap_disks`) are
  described in the schema.
- **Strings stay strings.** `vars` values are strings, and `disk`, `memory` and `cpu` in a VM config are strings of
  digits (`memory: '16384'`), because that is what the reader accepts: it rejects a bare number. If you would rather
  write `memory: 16384`, the reader needs to change first (the schema follows the reader, not the other way round).
- **Names are plain.** Node, group and VM names match `^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$`. They end up in shell
  commands, file names and log names (`utils/*-vm.sh` rely on it), so nothing else is allowed in them.
- **Addresses and ranges are checked**: IPv4/IPv6 forms, ports 1 to 65535, MTU 576 to 9216, `cidr` shape, and a NIC with
  an `ipaddress` has a `netmask` or a `cidr`.

`schema_version: 1` is optional at the top of each file. Nothing reads it today; it is there so a database or connector
can say which shape it is giving.

## What a schema cannot say

These are checked by `utils/validate-schemas.py`, after the schema passes:

| Rule | Level |
|---|---|
| node names are unique; group names are unique | error |
| every group member is a node | error |
| no two nodes have the same `cluster_ip` | error |
| two nodes with the same `host` under different names | warning |
| a VM config's `hypervisor` is a node, and is not the VM itself | error |
| a VM config's `hostname` is in the inventory | warning (a VM is added after it is provisioned) |
| a VM config's file name is its `hostname` (`config/tst7.yaml`) | warning |
| two configs do not have the same `hostname` or `nic0.ipaddress` | error |
| a NIC's `ipaddress` is inside its `cidr`, `netmask` agrees with `cidr`, `gateway` is inside the subnet | error |
| a DNS config's `nodes` are in the inventory | error |

## Using them

```
utils/validate-schemas.py                          # inventory.yaml and config/*.yaml
utils/validate-schemas.py -I path/inv.yaml -c path/config
utils/validate-schemas.py config/tst7.yaml         # one config (and the inventory)
make check-schemas                                 # the same
```

It needs `python3-jsonschema` and `python3-yaml` (`apt install python3-jsonschema python3-yaml`), reads files only, and
exits 1 on any error. It is not part of `make test`, which needs no Python. `hostname` formats are checked only if the
`fqdn` module is installed.

For an editor, a first line makes the YAML language server validate as you type:

```yaml
# yaml-language-server: $schema=../schemas/vm-config.schema.json
```

`inventory.yaml` and `config/dns.yaml` have it; the per-VM configs do not.

## Not done

- Orphera itself does not validate: `orphera` loads the files as before. A `validate` verb in the orchestrator, using
  the same schemas, would make the check part of every run; it needs a JSON Schema library as a new dependency.
- A numeric `memory` or `disk` is refused until the reader accepts it (above).
- A `host` that is all digits and dots (`10.1.1.300`) is a valid host name to the schema, so a mistyped IPv4 address is
  not caught there.
