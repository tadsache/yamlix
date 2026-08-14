# FLEET-FIXTURE-CASES.md

A second, independent Ansible test project — obfuscated, but structurally
built to reproduce the real-world project layout and bug reports that drove a
string of fixes to this plugin. Nothing here is a real variable name,
hostname, group name, or URL; every identifier is invented for this fixture.

Where `test-fixture/` (see `NAVIGATION-CASES.md`) is a small, general-purpose
specification of Ansible's precedence rules, `fleet-fixture/` exists
specifically to keep the following real-world-shaped bugs from regressing:

## What the fixture contains

```
ansible.cfg                        roles_path = ./roles, no `inventory=`
group_vars/all.yml                 project-root group_vars — sibling of
                                    inventories/, not nested inside any one
                                    of them
inventories/env-a/hosts            INI inventory, no file extension
inventories/env-b/hosts            INI inventory + a host_vars override
inventories/env-b/host_vars/b-host-03.yml
inventories/env-c/hosts            a "big" inventory: ~24 hosts in several
                                    groups, "containers" and "special_group"
                                    each a tiny subset of it
inventories/env-c/group_vars/special_group.yml   overrides group_vars/all.yml
                                    for that one group only
inventories/env-d/hosts            INI inventory
roles/container_monitoring_agent/  defaults, vars/main.yml, vars/env-*.yml,
                                    tasks/main.yml (the with_first_found
                                    pattern below)
roles/legacy_monitoring_agent/     a second role defining the *same*
                                    variable name, used by a different play
shared/noop.yml                    an `import_playbook` target
site-container-mon.yml             `import_playbook` + `hosts: containers`
site-legacy-mon.yml                `hosts: legacy_hosts`
playbooks/fleet/site-fleet-extra.yml
playbooks/fleet/roles -> ../../roles   a symlink duplicating the whole role
                                    tree under a second logical path
```

## Cases

### F1 — root-level `group_vars/all.yml`, not nested inside an inventory

`group_vars/` sits next to `inventories/`, `roles/`, etc. — a normal Ansible
layout — rather than inside any specific `inventories/<env>/`. It must be
recognized as applying to every inventory the project has.

`artifact_repo.url` from `roles/container_monitoring_agent/tasks/main.yml`:
resolves from `group_vars/all.yml` (`https://repo.example.test/generic-release`)
for every host except the `special_group` hosts in `env-c`.

### F2 — a narrow group override beats the root default, only where it applies

`inventories/env-c/group_vars/special_group.yml` overrides `artifact_repo` for
`special_group` hosts only (`c-host-01`, `c-host-02`). Everywhere else in
`env-c`, and in every other environment, `group_vars/all.yml` still wins.
Candidate labels must name the *group* (`special_group`), not enumerate hosts.

### F3 — many inventories, one uniform answer

Four inventories all resolve `artifact_repo.url` to the exact same
`group_vars/all.yml` site (except `special_group`, per F2). The report must
collapse this to a single "all inventories" entry/section rather than
repeating the same value once per environment.

### F4 — INI inventories with no file extension

`inventories/*/hosts` are plain INI, not `.yml`/`.ini`. `hosts: containers` in
a playbook must resolve — jumping straight to the `[containers]` section
header, not to line 1 of the file — in every one of the four inventories.

### F5 — a play's `hosts:` pattern is a tiny group inside a big inventory

`env-c` has ~24 hosts; `containers` matches exactly one of them
(`c-host-07`). `container_monitoring_agent`'s role defaults/vars must be
reported as applying to that one host, never to the whole `env-c` inventory.

### F6 — `import_playbook` step before the real play

Every `site-*.yml` here opens with `- import_playbook: shared/noop.yml`
before its actual `hosts:`/`roles:` play. Resolving anything from inside
`container_monitoring_agent`'s task file must use the *real* play's
`roles:` list for scoping, not silently see zero roles because the first
entry in the file has none.

### F7 — `hostgroup:` under an `import_playbook`'s `vars:`

`site-container-mon.yml`'s `import_playbook` step carries
`vars: { hostgroup: containers }`. `containers` there must resolve as a group
reference too, not just a `hosts:` value.

Nothing in Ansible's syntax marks it as one — it is a plain string assigned to
a plain variable. What marks it is `shared/noop.yml`'s
`hosts: "{{ hostgroup | default('all') }}"`: whatever a `hosts:` expression
interpolates is a group-valued key *in this project*. So the key is discovered
from the playbooks rather than hard-coded or configured, and a project using
`target_group` gets the same treatment for free.

The negative case is the point of the design. `hostgroup:` is also an ordinary
field of `theforeman.foreman`'s modules; a project that never templates
`hosts:` yields no keys at all and is left completely alone. References
inferred this way also never report as unresolved, so a value that names no
group is never flagged as an error. See `GroupKeyConventionTest` and
`GroupKeyConventionAbsentTest`.

### F8 — same variable name, two unrelated roles, never merged

Both `container_monitoring_agent` and `legacy_monitoring_agent` define
`agent_image`, with different values, in plays that never share a playbook.
Resolving `agent_image` from within one role's task file must never offer
the other role's value as a candidate.

### F9 — `include_vars: "{{ item }}"` + `with_first_found:`

`container_monitoring_agent/tasks/main.yml` loads
`vars/env-{{ fleet_env }}.yml` through the loop-variable idiom rather than a
literal path. `retention_days`, defined only in those four `env-*.yml` files,
must resolve to each one depending on which inventory's `fleet_env` is
assumed — never "not defined anywhere".

### F10 — a role reachable through a symlink

`playbooks/fleet/roles -> ../../roles` gives `container_monitoring_agent` a
second logical path to the exact same physical directory.
`site-fleet-extra.yml` uses the role through that symlink;
`site-container-mon.yml` uses it through the canonical `roles/` path. Neither
role name completion, nor "Find Declaration" on `agent_image`, nor any other
role-based lookup may report the role, or any variable defined inside it,
twice.

### F11 — host_vars beats everything

`inventories/env-b/host_vars/b-host-03.yml` overrides `artifact_repo` for
that single host — the narrowest possible scope, and it must still win over
`group_vars/all.yml` for `b-host-03` specifically while every other host in
`env-b` keeps the root default.

### F12 — a `hosts:` pattern the plugin cannot model must not narrow anything

`site-probe-glob.yml` uses `hosts: "web_ap*"` and `site-probe-local.yml` uses
`hosts: localhost`. Neither is a literal group or host name in these
inventories, so the set of hosts they match is unknown.

F5 established that a pattern which *is* understood restricts a role's
variables to the hosts it targets. The other half matters just as much:
"unknown" must mean unrestricted, never the empty set. An empty set removes
the role's defaults, `vars:` and `vars_files:` from every host, and a variable
that resolves nowhere is indistinguishable — in the popup and in "Choose
Declaration" — from one that was never defined.

`probe_setting`, defined only in `roles/pattern_probe_agent/defaults/main.yml`,
must still resolve under both playbooks. `agent_image` under
`site-container-mon.yml` must still *not* reach `a-host-02`, so the F5
restriction is not simply switched off.

### F13 — one role, two plays, disjoint host patterns

`site-probe-multiplay.yml` runs `pattern_probe_agent` twice: once on
`hosts: containers`, once on `hosts: web_app`. The role's defaults are in
scope for the union of both plays' hosts.

Scoping from a single play — whichever encloses the caret, or the first one in
the file — makes the role's variables vanish for every host the *other* play
targets. Multi-play playbooks are the norm, so this is not an edge case.

### F14 — playbook-adjacent `group_vars` outranks the inventory's own

Ansible loads `group_vars/` from two places: beside the inventory source and
beside the playbook, and orders them `inventory group_vars/all` <
`playbook group_vars/all` < `inventory group_vars/*` < `playbook group_vars/*`.

`playbooks/group_vars/all.yml` and `inventories/env-a/group_vars/all.yml` both
define `probe_adjacent`. For `playbooks/site-probe-adjacent.yml` — the one
playbook that actually sits beside the former — the playbook-adjacent value
wins. For a root-level playbook it is not in scope at all, and the inventory's
own value wins instead.

Both files are `all.yml` at the same precedence rank, so without modelling
adjacency the winner falls out of an alphabetical tie between two identically
named files: a wrong answer rather than merely an unhelpful one.

### F15 — a literal group absent from an inventory means "no hosts", not "unknown"

`site-legacy-mon.yml` targets `hosts: legacy_hosts`, a group that exists only
in `env-b`. F12 says a pattern the plugin cannot evaluate must not narrow
anything — but a literal name that this inventory simply does not have *has*
been evaluated. The play does not run there, and saying so is the answer, not
a guess.

Conflating the two leaked `legacy_monitoring_agent`'s defaults onto every host
of `env-a`, `env-c` and `env-d`: the role was reported as winning nearly
fleet-wide when Ansible runs it on exactly one host.

`localhost` is the deliberate exception — Ansible supplies it implicitly, so a
`hosts: localhost` play really does run even though the name is in no
inventory, and it returns "cannot be evaluated" instead.

### F16 — an unrelated role's same-named variable is not a candidate at all

Ansible's variable namespace is global and the index is keyed by name, so
`agent_image` in `container_monitoring_agent` and in `legacy_monitoring_agent`
collide. From inside either role's task file, only that role's own definition
is offered.

The other one used to be listed last, marked out of scope. That is noise: the
two roles never share a play, so it can never be the declaration of the symbol
under the caret, and every reader had to rule it out by hand.

Flow-sensitive scopes (`include_vars`, `set_fact`, `register`) are exempt and
stay in the list when out of scope, because there the reason is *position* —
"it is set in this very play, just after where you are" — which is frequently
the answer to why a value is not what was expected.
