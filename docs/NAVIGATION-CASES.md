# NAVIGATION-CASES.md

Test-bed analysis for an IntelliJ plugin providing *jump to definition* and
variable resolution in Ansible projects.

Everything in section 2 and 3 was verified by running Ansible, not by
reasoning about the docs. Two of my predictions were **wrong**; both are
called out explicitly rather than quietly corrected.

```
ansible [core 2.20.4]   python 3.14.4   macOS (ansible_os_family = Darwin)
All commands were run from the fixture root: test-fixture/
```

The fixture now lives at `src/test/testData/fixture/`; the transcripts below
were recorded when it sat at the repository root, and are quoted verbatim.

---

## 0. What the fixture contains

```
ansible.cfg                        roles_path = ./roles:./external-roles
                                   inventory  = ./inventories/stag
                                   collections_path = ./collections
site-playbook.yml
vars/common.yml                    vars_files target
inventories/stag/  hosts.yml, group_vars/{all,platform,webservers,canary}.yml,
                   host_vars/stag-web-1.yml
inventories/prod/  hosts.yml, group_vars/{all,webservers}.yml,
                   host_vars/prod-web-1.yml
roles/app/         defaults, vars/{main,Darwin,RedHat}.yml, tasks/{main,configure}.yml,
                   handlers, templates, meta
roles/common/      tasks                      (meta dependency of app)
roles/monitoring/  defaults, tasks            (include_role target)
external-roles/legacy_backup/tasks            (outside ./roles)
collections/ansible_collections/acme/web/roles/proxy/tasks
```

Group graph:

| env  | graph | notes |
|------|-------|-------|
| stag | `all(0) → platform(1) → { webservers(2), canary(2, priority 10) }` | `stag-web-1` ∈ both depth-2 groups; `stag-web-2` ∈ `webservers` only |
| prod | `all(0) → platform(1) → webservers(2)` | `platform` has **no** `group_vars` file; `prod-web-2` has **no** `host_vars` file |

---

## 1. Navigation cases

`L` = line number in the current file. "Fallback order" is the sequence a
resolver must walk; the first hit wins.

| # | Source file : line | Ctrl+Click token | Expected target | Lookup rule and fallback order |
|---|--------------------|------------------|-----------------|--------------------------------|
| N1 | `site-playbook.yml:28` | `app` | `roles/app/` (entry point `tasks/main.yml`) | Short role name from a playbook. Search order, verified from Ansible's own error message: **1.** `<playbook_dir>/roles/<name>` **2.** each `roles_path` entry, left to right (`./roles`, `./external-roles`) **3.** `~/.ansible/roles` **4.** `/usr/share/ansible/roles` **5.** `/etc/ansible/roles` **6.** `<playbook_dir>/<name>`. Hits at step 1. |
| N2 | `site-playbook.yml:31` | `acme.web.proxy` | `collections/ansible_collections/acme/web/roles/proxy/` | A name with ≥2 dots is an FQCN and **bypasses `roles_path` entirely**. Resolve `<ns>.<coll>.<role>` → `<collections_path>/ansible_collections/<ns>/<coll>/roles/<role>`. Fallback across `collections_path` entries, then `~/.ansible/collections`, then `/usr/share/ansible/collections`. Never falls back to `roles/proxy`. |
| N3 | `site-playbook.yml:34` | `legacy_backup` | `external-roles/legacy_backup/` | Same rule as N1, but misses step 1 and hits the **second** `roles_path` entry. This is the case that breaks if the plugin only scans `<playbook_dir>/roles`. |
| N4 | `site-playbook.yml:8` | `vars/common.yml` | `vars/common.yml` | `vars_files` paths resolve relative to the **playbook directory**, then to a `vars/` subdirectory of it. (A role-level `vars_files` would instead resolve relative to the role dir.) |
| N5 | `roles/app/meta/main.yml:11` | `common` | `roles/common/` | Role dependency. Same search order as N1, but step 1 is `<role_basedir>/roles` where `role_basedir` is the *depending* role's parent — for `roles/app` that is `roles/`, so `roles/common` hits. Note the dependency executes **before** `roles/app/tasks/main.yml`. |
| N6 | `roles/app/tasks/main.yml:39` | `configure.yml` | `roles/app/tasks/configure.yml` | `include_tasks` with a relative path. Order: **1.** `<role>/tasks/<path>` **2.** `<role>/<path>` **3.** `<playbook_dir>/<path>`. Ansible confirmed the absolute path in its output: `included: .../roles/app/tasks/configure.yml`. |
| N7 | `roles/app/tasks/main.yml:44` | `monitoring` | `roles/monitoring/` | `include_role: name:` — same resolution as N1/N5. Difference is temporal, not spatial: dynamic, so it does not appear in `--list-tasks`. |
| N8 | `roles/app/tasks/configure.yml:10` | `legacy_backup` | `external-roles/legacy_backup/` | `import_role` from **inside a role**, targeting a role outside `./roles`. Step 1 (`roles/app/../roles/legacy_backup`) misses; the `roles_path` entry `./external-roles` hits. |
| N9 | `roles/app/tasks/main.yml:18` | `app.conf.j2` | `roles/app/templates/app.conf.j2` | `template: src:` — order: **1.** `<role>/templates/<src>` **2.** `<role>/files/<src>` **3.** `<playbook_dir>/templates/<src>` **4.** `<playbook_dir>/<src>`. |
| N10 | `roles/app/tasks/main.yml:22` | `Restart app` | `roles/app/handlers/main.yml:2` | `notify:` matches a handler by its **`name:` string** (or by `listen:`). Search order: handlers of the current role, then handlers of other roles in the play, then play-level `handlers:`. String match, not a path. |
| N11 | `roles/app/tasks/main.yml:5` | `"{{ ansible_os_family }}.yml"` | `roles/app/vars/Darwin.yml` **or** `roles/app/vars/RedHat.yml` | `include_vars` relative path: **1.** `<role>/vars/<file>` **2.** `<role>/files/<file>` **3.** `<playbook_dir>/vars/<file>`. The filename is fact-templated, so a static resolver must offer **both candidates**, never one. See §3. |
| N12 | `site-playbook.yml:3` | `webservers` | `inventories/<env>/hosts.yml` (the `webservers:` key) and `inventories/<env>/group_vars/webservers.yml` | `hosts:` pattern → group definition. Which inventory root applies depends on `-i`, defaulting to `ansible.cfg`'s `inventory = ./inventories/stag`. Multi-target: the group is defined in the inventory source, its vars in `group_vars/`. |
| N13 | any `{{ app_port }}` usage | `app_port` | 11 competing files — see §2 | Not a single-target jump. Correct UX is a "choose definition" popup ordered by precedence, winner first. |

### Verified: `--list-tasks` for both environments

```
$ ansible-playbook site-playbook.yml -i inventories/stag --list-tasks

playbook: site-playbook.yml

  play #1 (webservers): Deploy the demo application stack	TAGS: []
    tasks:
      Report variables before any role has run	TAGS: []
      common : Report what the dependency role sees	TAGS: []
      app : Load OS-family specific vars	TAGS: []
      app : Report resolved variables inside role app	TAGS: []
      app : Render the application config	TAGS: []
      app : Report the registered variable	TAGS: []
      app : Pin the debug port on staging only	TAGS: []
      app : Include the configure sub-tasks	TAGS: []
      Run the monitoring role dynamically	TAGS: []
      acme.web.proxy : Report from the collection role	TAGS: []
      legacy_backup : Report from the out-of-tree role	TAGS: []
      Report variables after all roles have run	TAGS: []
```

`-i inventories/prod` produces byte-identical output — the task graph does not
depend on the inventory here.

Note what is **absent**: the tasks inside `configure.yml` (N6) and inside
`monitoring` (N7). `--list-tasks` expands `import_*` statically but stops at
`include_*`. A plugin's structure view can and should go further than
`--list-tasks` does, because the paths in N6/N7 are literals.

### Verified: `roles_path` is CWD-relative, and so is `ansible.cfg` discovery

Running the same playbook from the parent directory:

```
$ cd /Users/tade/ai/yamlix && ansible-playbook test-fixture/site-playbook.yml \
    -i test-fixture/inventories/stag --list-tasks

[ERROR]: the role 'legacy_backup' was not found in
/Users/tade/ai/yamlix/test-fixture/roles:/Users/tade/.ansible/roles:/usr/share/ansible/roles:/etc/ansible/roles:/Users/tade/ai/yamlix/test-fixture
Origin: /Users/tade/ai/yamlix/test-fixture/site-playbook.yml:34:7
```

`./external-roles` is missing from the search path because `ansible.cfg` was
never loaded — Ansible looks for it in `$CWD`, not next to the playbook. For
the plugin this means: **the effective `roles_path` is a function of the
assumed working directory**, which is a project-level setting the user must be
able to override. The error message also hands you the canonical search order
for free.

---

## 2. Variable resolution

### 2a. Definition sites of `app_port`

| Prec. | Kind | File | stag value | prod value |
|-------|------|------|-----------|-----------|
| 2 | role defaults | `roles/app/defaults/main.yml:3` | 8000 | 8000 |
| 4 | inventory `group_vars/all` | `inventories/<env>/group_vars/all.yml` | 8010 | 9010 |
| 7 | `group_vars/platform` (depth 1) | `inventories/stag/group_vars/platform.yml` | 8020 | — (no file) |
| 7 | `group_vars/webservers` (depth 2, prio 1) | `inventories/<env>/group_vars/webservers.yml` | 8030 | 9030 |
| 7 | `group_vars/canary` (depth 2, prio 10) | `inventories/stag/group_vars/canary.yml` | 8040 | — |
| 10 | `host_vars/<host>` | `inventories/<env>/host_vars/*.yml` | 8050 (web-1 only) | 9050 (web-1 only) |
| 13 | play vars | `site-playbook.yml:13` | 8060 | 8060 |
| 15 | play `vars_files` | `vars/common.yml:4` | 8070 | 8070 |
| 16 | role vars | `roles/app/vars/main.yml:6` | 8090 | 8090 |
| 19 | `include_vars` | `roles/app/vars/Darwin.yml:5` | 8100 | 8100 |
| 20 | `set_fact` (guarded) | `roles/app/tasks/main.yml:34` | 8500 | *skipped* |

`app_port` has **no single winner per host** — it has a winner per *point in
the play*. That is the single most important thing this fixture demonstrates.

### 2b. Effective value of `app_port`, verified

Raw output, `--limit` to one host per env, deprecation warnings suppressed:

```
########## stag / stag-web-1 ##########
PRE-ROLE   host=stag-web-1 app_port=8090 app_workers=6 app_log_level=debug app_url=http://stag-web-1:8090/yamlix-demo
ROLE-COMMON reason=dependency-of-app app_port=8090
ROLE-APP   host=stag-web-1 app_port=8100 app_workers=6 app_url=http://stag-web-1:8100/yamlix-demo app_pkg_manager=brew
REGISTERED changed=True dest=/tmp/yamlix-stag.conf
TASK [app : Pin the debug port on staging only]   -> ok (ran)
CONFIGURE  app_port=8500 app_banner=yamlix-demo on stag-web-1
ROLE-LEGACY-BACKUP (from external-roles/) host=stag-web-1 app_port=8500
ROLE-MONITORING source=app app_port=8500 scrape=http://stag-web-1:8500/metrics
ROLE-ACME-WEB-PROXY host=stag-web-1 app_port=8500 backend=http://stag-web-1:8500/yamlix-demo
HANDLER    restarting yamlix-app on port 8500
POST-ROLE  host=stag-web-1 app_port=8500 app_url=http://stag-web-1:8500/yamlix-demo

########## prod / prod-web-1 ##########
PRE-ROLE   host=prod-web-1 app_port=8090 app_workers=16 app_log_level=warning app_url=http://prod-web-1:8090/yamlix-demo
ROLE-COMMON reason=dependency-of-app app_port=8090
ROLE-APP   host=prod-web-1 app_port=8100 app_workers=16 app_url=http://prod-web-1:8100/yamlix-demo app_pkg_manager=brew
REGISTERED changed=True dest=/tmp/yamlix-prod.conf
TASK [app : Pin the debug port on staging only]   -> skipping: [prod-web-1]
CONFIGURE  app_port=8100 app_banner=yamlix-demo on prod-web-1
ROLE-LEGACY-BACKUP (from external-roles/) host=prod-web-1 app_port=8100
ROLE-MONITORING source=app app_port=8100 scrape=http://prod-web-1:8100/metrics
ROLE-ACME-WEB-PROXY host=prod-web-1 app_port=8100 backend=http://prod-web-1:8100/yamlix-demo
HANDLER    restarting yamlix-app on port 8100
POST-ROLE  host=prod-web-1 app_port=8100 app_url=http://prod-web-1:8100/yamlix-demo
```

`stag-web-2` and `prod-web-2` behave identically for `app_port`:

```
PRE-ROLE host=stag-web-2 app_port=8090 ... POST-ROLE ... app_port=8500
PRE-ROLE host=prod-web-2 app_port=8090 ... POST-ROLE ... app_port=8100
```

Summarised:

| Scope | stag-web-1 | stag-web-2 | prod-web-1 | prod-web-2 | Winning site |
|-------|-----------|-----------|-----------|-----------|--------------|
| `pre_tasks` | 8090 | 8090 | 8090 | 8090 | role vars of `app` |
| role `common` (dep of `app`) | 8090 | 8090 | 8090 | 8090 | role vars of `app` |
| role `app`, after `include_vars` | 8100 | 8100 | 8100 | 8100 | `vars/Darwin.yml` |
| template render | 8100 | 8100 | 8100 | 8100 | `vars/Darwin.yml` (`set_fact` has not run yet) |
| after `set_fact` → end of play | **8500** | **8500** | 8100 | 8100 | `set_fact` / `vars/Darwin.yml` |

**Everything from precedence 2 through 15 — all inventory, all `host_vars`,
all group priority, play vars, `vars_files` — is dead weight for `app_port`.**
Nine definition sites never win anywhere. A plugin that shows "go to
definition → `host_vars/stag-web-1.yml`" for `app_port` is confidently wrong.

#### ⚠️ Prediction vs. reality #1 — role vars are not role-scoped

I predicted `pre_tasks` would see **8070** (`vars_files`, precedence 15),
because role `app` had not run yet. Ansible produced **8090** — the role vars
of `app`.

Reason: for roles listed statically under `roles:`, `defaults/main.yml` and
`vars/main.yml` are merged into the play's variable context at play-compile
time, before the first `pre_task` executes. They are *play*-scoped, not
role-scoped. The `ROLE-COMMON app_port=8090` line proves the same point from
the other side: a completely different role sees `app`'s role vars.

Consequence for the plugin: `roles/<r>/vars/main.yml` cannot be filtered out
when resolving a variable in a file that is not part of role `<r>`, as long as
`<r>` appears in the play's `roles:` list. Only `include_role` (dynamic) keeps
its vars scoped.

### 2c. `app_workers` — inventory-only, so the group graph decides

| Prec. | Site | stag | prod |
|-------|------|------|------|
| 2 | `roles/app/defaults/main.yml:4` | 1 | 1 |
| 4 | `group_vars/all.yml` | 2 | 12 |
| 7 | `group_vars/platform.yml` (depth 1) | 3 | — |
| 7 | `group_vars/webservers.yml` (depth 2) | 4 | 14 |
| 7 | `group_vars/canary.yml` (depth 2, prio 10) | 5 | — |
| 10 | `host_vars/<host>.yml` | 6 (web-1) | 16 (web-1) |

Verified with `ansible-inventory`:

```
$ ansible-inventory -i inventories/stag --list --yaml
all:
  children:
    platform:
      children:
        canary:
          hosts:
            stag-web-1: {}
        webservers:
          hosts:
            stag-web-1:
              ansible_connection: local
              ansible_group_priority: 10
              ansible_host: 127.0.0.1
              app_env: stag
              app_log_level: debug
              app_port: 8050
              app_url: http://{{ inventory_hostname }}:{{ app_port }}/{{ app_name }}
              app_workers: 6
              platform_owner: sre
            stag-web-2:
              ansible_connection: local
              ansible_host: 127.0.0.1
              app_env: stag
              app_log_level: info
              app_port: 8030
              app_url: http://{{ inventory_hostname }}:{{ app_port }}/{{ app_name }}
              app_workers: 4
              platform_owner: sre

$ ansible-inventory -i inventories/prod --list --yaml
all:
  children:
    platform:
      children:
        webservers:
          hosts:
            prod-web-1:
              ansible_connection: local
              ansible_host: 127.0.0.1
              app_env: prod
              app_log_level: warning
              app_port: 9050
              app_url: http://{{ inventory_hostname }}:{{ app_port }}/{{ app_name }}
              app_workers: 16
            prod-web-2:
              ansible_connection: local
              ansible_host: 127.0.0.1
              app_env: prod
              app_log_level: warning
              app_port: 9030
              app_url: http://{{ inventory_hostname }}:{{ app_port }}/{{ app_name }}
              app_workers: 14
```

| host | winner | value | why |
|------|--------|-------|-----|
| stag-web-1 | `host_vars/stag-web-1.yml` | 6 | host_vars beats every group regardless of depth or priority |
| stag-web-2 | `group_vars/webservers.yml` | 4 | not a member of `canary`; `webservers`(2) beats `platform`(1) |
| prod-web-1 | `host_vars/prod-web-1.yml` | 16 | — |
| prod-web-2 | `group_vars/webservers.yml` | 14 | — |

Note `app_url` in the dump: it is stored **unexpanded**. The inventory layer
does not resolve Jinja; the templating engine does, lazily, at use time.

### 2d. `app_log_level` — the group tie-break, isolated

Defined *only* in `group_vars`, so nothing higher masks the graph:

| host | platform(1) | webservers(2, prio 1) | canary(2, prio 10) | winner |
|------|-------------|----------------------|--------------------|--------|
| stag-web-1 | warning | info | **debug** | `canary` — priority breaks a same-depth tie |
| stag-web-2 | warning | **info** | n/a | `webservers` — depth beats depth |
| prod-web-* | n/a (all: error) | **warning** | n/a | `webservers` beats `all` |

#### ⚠️ Prediction vs. reality #2 — `ansible_group_priority` in `group_vars/` is a no-op

I originally set `ansible_group_priority: 10` in
`inventories/stag/group_vars/canary.yml` and predicted `stag-web-1` would get
`app_log_level: debug`. Actual result:

```
$ ansible-inventory -i inventories/stag --list --yaml | grep -E 'stag-web|app_log_level'
            stag-web-1: {}
            stag-web-1:
              app_log_level: info      # <-- webservers won, priority ignored
            stag-web-2:
              app_log_level: info
```

Moving the same line into the inventory source (`hosts.yml`, under
`canary: vars:`) fixed it:

```
            stag-web-1:
              ansible_group_priority: 10
              app_log_level: debug     # <-- canary won
            stag-web-2:
              app_log_level: info
```

Reason: group priority lives on the `Group` object, which is built while
parsing the inventory source. `group_vars/*.yml` files are merged *later*, by
the `host_group_vars` vars plugin, **using** the priority already on the
object. Setting it in a file that is itself being priority-sorted is
circular, so it is silently ignored.

The fixture deliberately keeps the dead `ansible_group_priority: 10` line in
`group_vars/canary.yml` (with a comment) as a trap case — a plugin should
either ignore it or flag it as ineffective, and must not use it for sorting.

### 2e. `app_url` — a Jinja-valued variable

Defined once, in `group_vars/all.yml`:

```yaml
app_url: "http://{{ inventory_hostname }}:{{ app_port }}/{{ app_name }}"
```

Verified renderings for `stag-web-1`, one definition site, four different
values:

| Where rendered | Value |
|----------------|-------|
| `pre_tasks` | `http://stag-web-1:8090/yamlix-demo` |
| role `app` after `include_vars` | `http://stag-web-1:8100/yamlix-demo` |
| `monitoring` (via `monitoring_scrape_url`, itself Jinja) | `http://stag-web-1:8500/metrics` |
| `post_tasks` | `http://stag-web-1:8500/yamlix-demo` |

Lazy evaluation means the *reference* `app_port` inside `app_url` must be
re-resolved at every use site, not at the definition site. `app_name` comes
from play vars, which are not visible in the inventory dump at all — so the
`group_vars/all.yml` file alone cannot be type-checked.

### 2f. Registered variable

```
REGISTERED changed=True dest=/tmp/yamlix-stag.conf
```

`app_config` (`roles/app/tasks/main.yml:21`) is defined by `register:` and read
at line 27. The definition jump is trivial — same file, the `register:` key.
Its *members* (`.changed`, `.dest`, `.failed`, `.skipped`, …) are the module's
return schema, which is a documentation lookup, not a file lookup.

Note also `dest=/tmp/yamlix-stag.conf`: `app_config_path` is
`"/tmp/yamlix-{{ app_env }}.conf"` from role defaults, resolved with `app_env`
from `group_vars/all.yml` — a defaults-level value depending on an
inventory-level value.

---

## 3. What cannot be resolved statically

| Case | Where | Why it is undecidable without running Ansible |
|------|-------|-----------------------------------------------|
| `include_vars: "{{ ansible_os_family }}.yml"` | `roles/app/tasks/main.yml:5` | The filename is a **fact**, produced by `gather_facts` against a live host. Best a plugin can do: glob `roles/app/vars/*.yml` minus `main.yml` and offer `Darwin.yml` + `RedHat.yml` as multiple targets. Note it changed `app_port` from 8090 to 8100 — so *every* downstream resolution of `app_port` is fact-dependent too. |
| `set_fact` guarded by `when: app_env == 'stag'` | `roles/app/tasks/main.yml:30-35` | The guard is decidable *here* (`app_env` is a static inventory var), but in general a `when:` may reference facts, registered results or `lookup()`. A resolver must treat any `set_fact` as **conditionally winning** and present both branches. Verified: ran on stag, `skipping:` on prod. |
| Which host / which inventory | everywhere | `app_port` is 8050 on `stag-web-1` and 9030 on `prod-web-2` at the inventory layer. The plugin has no "current host". It needs an explicit inventory + host selector in the UI, or it must show an N-way answer. |
| `--limit`, `--extra-vars`, `-e @file` | invocation | Extra vars are precedence 23 and beat everything. They live in the user's shell history, not in the repo. Unknowable, full stop. |
| Registered var contents | `app_config.changed` | Only the *name* is static. Whether `.changed` is `True` depends on filesystem state at run time. |
| Dynamic vs. static include ordering | `include_tasks`, `include_role` | With a templated `name:`/path (not used here, but common: `include_role: name: "{{ role_name }}"`) the target is unresolvable. Even with a literal name, `include_*` inside a `loop:` runs N times with different vars. |
| Group priority effects | `group_vars/canary.yml` | Requires knowing the priority came from the inventory source, i.e. the plugin must parse `hosts.yml` group `vars:` blocks and *not* trust `group_vars/`. Statically resolvable, but only if the parser models the two-phase load. |
| Effective `roles_path` | `ansible.cfg` | Relative entries resolve against the process CWD, and `ansible.cfg` itself is discovered from CWD (`ANSIBLE_CONFIG` → `./ansible.cfg` → `~/.ansible.cfg` → `/etc/ansible/ansible.cfg`). Demonstrated above: running from the parent directory dropped `./external-roles` and broke N3. Environment variables (`ANSIBLE_ROLES_PATH`) override it entirely. |
| Vars plugins / dynamic inventory | not exercised here | A real project may pull vars from a script, a `_meta` block, or a custom vars plugin. No file to jump to. |

**Rule of thumb the plugin should follow:** never present a single target when
the resolution is a set. Present the set, ordered by precedence, with the
statically-provable winner marked and the conditional entries flagged.

---

## 4. Resolution rules to implement (pseudocode)

```text
# --- R1. Role name -> directory ------------------------------------------
resolveRole(name, fromCtx):
    if name.count('.') >= 2:                       # FQCN, N2
        ns, coll, role = name.split('.', 2)
        for base in collectionsPath():             # cfg, then ~/.ansible, then /usr/share
            try base/ansible_collections/ns/coll/roles/role
        return notFound                            # NEVER fall back to roles_path
    for dir in [ fromCtx.roleBasedir ?: playbookDir + "/roles",
                 *rolesPath(),                     # cfg order, left to right
                 fromCtx.roleBasedir,
                 playbookDir ]:
        try dir/name
    return notFound

rolesPath():   # each relative entry resolves against assumedCwd, NOT playbookDir
    env ANSIBLE_ROLES_PATH ?: cfg.roles_path ?: [~/.ansible/roles,
                                                 /usr/share/ansible/roles,
                                                 /etc/ansible/roles]

# --- R2. Relative file references ----------------------------------------
resolveTaskFile(p, role):   first_of [ role/tasks/p, role/p, playbookDir/p ]
resolveTemplate(p, role):   first_of [ role/templates/p, role/files/p,
                                       playbookDir/templates/p, playbookDir/p ]
resolveVarsFile(p, role):   first_of [ role/vars/p, role/files/p, playbookDir/vars/p ]
resolvePlayVarsFile(p):     first_of [ playbookDir/p, playbookDir/vars/p ]

if p contains "{{":                    # N11 — fact-templated
    return glob(searchDirs, replaceJinjaWithStar(p)) as MULTIPLE targets

# --- R3. Group ordering (the two-phase load) ------------------------------
groupPriority(g):
    return g.varsDeclaredInInventorySource["ansible_group_priority"] ?: 1
    # deliberately ignores group_vars/<g>.yml — verified no-op

orderedGroups(host):
    return sort(host.allGroupsTransitive(), key = (depth ASC, priority ASC, name ASC))
    # later entries override earlier ones

# --- R4. Variable precedence ---------------------------------------------
PRECEDENCE = [
   2:  role_defaults,                          # roles/*/defaults/main.yml
   4:  inventory_group_vars_all,
   7:  inventory_group_vars_group,             # merged in orderedGroups() order
  10:  inventory_host_vars,
  13:  play_vars,
  15:  play_vars_files,
  16:  role_vars,                              # roles/*/vars/main.yml
  17:  block_vars,
  18:  task_vars,
  19:  include_vars,                           # conditional: templated filename
  20:  set_fact_or_registered,                 # conditional: when:
  21:  role_params,                            # include_role/import_role vars:
  23:  extra_vars,                             # UNKNOWABLE
]

candidates(varName, atPosition, host, inventory):
    sites = []
    for role in play.staticRoles:               # NOT scoped to atPosition's role — R5
        sites += role.defaults[varName] @ 2
        sites += role.vars[varName]     @ 16
    sites += inventoryCandidates(varName, host, inventory)      # 4, 7, 10 via R3
    sites += play.vars[varName] @ 13
    sites += play.varsFiles[varName] @ 15
    for stmt in tasksExecutedBefore(atPosition):                # flow order, not file order
        if stmt is include_vars: sites += stmt.targets[varName] @ 19  (conditional)
        if stmt is set_fact:     sites += stmt[varName]         @ 20  (conditional if when:)
        if stmt is register:     sites += stmt.register         @ 20
    return sort(sites, by = precedence DESC, then flowOrder DESC)

winner(sites):
    top = sites.first()
    if top.conditional: return AMBIGUOUS(top, sites.firstUnconditional())
    return top

# --- R5. Role-vars scope (verified counter-intuitive) --------------------
inScope(roleVarSite, atPosition):
    if roleVarSite.role in play.rolesListedStatically:  return TRUE   # whole play
    if roleVarSite.role reached via include_role:       return atPosition inside that role
    # -> pre_tasks saw app_port=8090 from roles/app/vars/main.yml

# --- R6. Jinja values are lazy -------------------------------------------
renderAt(varSite, usePosition):
    for ref in jinjaRefs(varSite.value):
        resolve ref with candidates(ref, usePosition, ...)   # NOT varSite's position
    # app_url is one definition, four values

# --- R7. Flow order, not file order --------------------------------------
executionOrder(play) = pre_tasks
                     + for r in roles: [ meta_dependencies(r) DFS, r.tasks ]
                     + tasks + post_tasks + handlers(notified, in handler-file order)
```

The three rules most likely to be got wrong, in order: **R5** (role vars are
play-scoped), **R3** (priority only counts from the inventory source), and
**R4**'s conditional handling (a `set_fact` behind a `when:` is a *maybe*
winner, and rendering it as the definitive answer is worse than rendering
nothing).
