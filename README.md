# yamlix

An IntelliJ IDEA plugin that gives Ansible projects **jump-to-definition** and
**variable resolution** — the two things that make a large playbook readable.

It reuses the platform's YAML PSI. It registers no file type, does not claim
`*.yml`, and never shells out to `ansible`.

```
IntelliJ IDEA 2025.2+ (sinceBuild 252, no upper bound)   Kotlin · Gradle 9 · IJPGP 2.x
126 tests · IntelliJ Plugin Verifier: Compatible
```

---

## What it does

### Navigation

Ctrl/⌘+Click resolves the things Ansible resolves at runtime:

| Where | What resolves |
|---|---|
| `roles:` in a playbook | short names, fully-qualified collection names, and roles reachable only via `roles_path` |
| `include_role` / `import_role` | role names, including from inside another role |
| `include_tasks` / `import_tasks` | relative paths, against the role's `tasks/` then the playbook dir |
| `meta/main.yml` `dependencies:` | role dependencies |
| `vars_files:` / `include_vars:` | including fact-templated filenames — **all** candidates, never one guess |
| `template:` / `copy:` `src:` | `templates/`, then `files/`, then the playbook dir |
| `notify:` | the handler with that `name:` or `listen:` |
| `hosts:` | the group in the inventory source (INI or YAML) and its `group_vars` file |
| `hostgroup:`-style keys | any key this project's own `hosts:` expressions interpolate |
| `{{ variable }}` | every definition site, ordered by what applies at the caret |

**Find Usages** works in reverse: from an `app_port:` key in `host_vars`,
`group_vars`, a role's `defaults` or a `set_fact`, to every `{{ app_port }}`
that uses it — including uses inside files reached through `include_tasks`.

Completion and an unresolved-reference inspection read the same model, so they
cannot disagree with navigation.

### Variable resolution

A variable in Ansible does not have *a* definition — it has as many as the
precedence table allows, and which one wins depends on the host, the inventory,
and where in the play you are. The plugin models that properly.

**Ctrl+Click** on `{{ app_port }}` lists every definition site, ordered by what
applies at the caret:

```
8500     stag  ·  roles/app/tasks/main.yml
8100     may win on prod  ·  roles/app/vars/Darwin.yml
8200     may win on prod  ·  roles/app/vars/RedHat.yml
8090     overridden  ·  roles/app/vars/main.yml
8070     overridden  ·  vars/common.yml
6        overridden  ·  inventories/stag/host_vars/stag-web-1.yml
```

The value leads because it is the field that differs between rows; where it
applies and which file it lives in qualify it. Nothing restates what the path
already says — no scope prefix, no group name repeated from the filename, no
precedence number the list is already sorted by.

Move the caret four lines earlier — before the `set_fact` runs — and the same
variable produces a different list, with the `set_fact` marked *not in scope
here*. A same-named variable belonging to an unrelated role is not listed at
all: Ansible's namespace is global, so the index offers it, but it can never
be the declaration of the symbol under the caret.

**Quick Documentation** (F1 on macOS, Ctrl+Q elsewhere) renders the effective
value per inventory and host:

| inventory | effective value | defined in |
|---|---|---|
| prod (prod-web-1) | 16 | `host_vars[prod-web-1]` · rank 10 |
| prod (prod-web-2) | 14 | `group_vars[webservers]` · rank 7 |
| stag (stag-web-1) | 6 | `host_vars[stag-web-1]` · rank 10 |
| stag (stag-web-2) | 4 | `group_vars[webservers]` · rank 7 |

Hosts that agree collapse into one row. Hosts that differ get their own.

### It never guesses

Everything Ansible can only know at run time is rendered as **unresolved**, with
the raw template shown:

- a Jinja-valued variable → shown unexpanded, because Ansible expands lazily
- `register:` → *run-time value*
- `hostvars`, `lookup()`, `query()`, vault → named explicitly in the note
- a fact-templated `include_vars` → **every** candidate listed, none promoted
- a gathered fact or magic variable → described, with its *origin* offered

There is deliberately no code path that produces a value for any of these. When
a `when:` guard *is* statically decidable — `app_env == 'stag'` compares against
an inventory variable — it says so plainly rather than hedging.

---

## How it was built

The specification is a real Ansible repo, not prose.

```
test-fixture/                  a small but deliberately nasty Ansible project
test-fixture/NAVIGATION-CASES.md   13 navigation cases, verified resolution
                                   tables, and the rules as pseudocode
```

Every expected value in that document was produced by running
`ansible-playbook` and `ansible-inventory` against the fixture with
**ansible-core 2.20.4** — not by reasoning about the docs. The document records
two places where real Ansible contradicted the obvious prediction:

1. **Role `vars/main.yml` is play-scoped, not role-scoped.** For roles listed
   statically under `roles:`, their vars are merged before the first `pre_task`
   runs. A completely different role sees them.
2. **`ansible_group_priority` in `group_vars/` is a silent no-op.** It is only
   honoured from the inventory source, because `group_vars` files are merged
   *using* the priority that already exists on the group.

`src/test/testData/fixture` is a byte-identical copy, and a test asserts it stays
that way — so the specification cannot be quietly edited to match the code.

The three build reports (`MILESTONE-*-REPORT.md`) record what was built, where
the platform fought back, and every deviation from the spec with its reason.

A second, independent fixture — `fleet-fixture/`, documented in
`fleet-fixture/FLEET-FIXTURE-CASES.md` — exists purely to keep real-world bug
reports from regressing: a project-root `group_vars/all.yml` sibling to
`inventories/`, plain-INI inventories with no file extension, a role's
`hosts:` pattern matching a sliver of a much bigger inventory, playbooks that
open with an `import_playbook` step before their real play,
`include_vars: "{{ item }}"` + `with_first_found:`, a role reachable through a
symlinked directory, host patterns the plugin cannot model (`web*`,
`localhost`), one role shared by two plays with different `hosts:`, and
playbook-adjacent `group_vars/`. Every identifier in it is invented — no real
variable name, hostname, or URL. Same integrity guarantee as the first
fixture (`FleetFixtureIntegrityTest`), same "byte-identical copy" rule.

`ScaleBenchmarkTest` generates a fleet-sized project — 16 inventories, 300
hosts each, 25 playbooks, 60 roles — and asserts that resolving a variable
across it stays well under a second. Variable resolution sweeps every host of
every inventory for every applicable playbook, so it is the one part of the
plugin where a straightforward implementation is quadratic enough to freeze
the UI on a real project.

---

## Build and run

Requires a JDK 17+ to run Gradle; the build provisions a JDK 21 toolchain.

```bash
./gradlew test           # 126 tests
./gradlew verifyPlugin   # IntelliJ Plugin Verifier
./gradlew runIde         # sandbox IDE with the plugin loaded
./gradlew buildPlugin    # build/distributions/yamlix-<version>.zip
```

To try it: `runIde`, then open `test-fixture/` in the sandbox IDE. Wait for
indexing to finish — variable resolution is index-backed and reports
*"Indexing — variable resolution is not available yet"* until it completes.

To install into your own IDE: **Settings → Plugins → ⚙ → Install Plugin from
Disk**, and pick the zip from `buildPlugin`.

---

## Known limitations

**`ansible.cfg` discovery cannot match Ansible exactly.** Ansible finds it from
the process working directory, and resolves relative `roles_path` entries
against that. An IDE has no working directory, so the plugin resolves against
the directory containing the `ansible.cfg` found by walking up from the file.
That is what running `ansible-playbook` from your project root gives you, but it
is not literally Ansible's rule.

**Playbook discovery is shallow** — the `ansible.cfg` directory and a
`playbooks/` subdirectory. A recursive scan would parse every role's
`tasks/main.yml` on each call.

**Host enumeration is capped at 40 per inventory**, and says so in the popup's
caveats when it truncates rather than silently showing a subset.

**Variable reports are not cached.** The play flow is, but a very large
inventory will be felt on Quick Documentation.

**The documentation API is experimental.** `TargetPresentation` and
`com.intellij.model.Pointer` are `@ApiStatus.Experimental`; there is no
non-experimental way to implement a `DocumentationTarget`. This is the most
likely thing to break on a platform upgrade.

**Extra vars (`-e`) always win and are invisible from a repository.** Every
resolution says so.

**The Quick Documentation popup renders through Swing's `HTMLEditorKit`** — an
HTML 3.2 engine. No flexbox, no grid, no `white-space`. The layout is built from
short labels and the platform's own markup constants because anything wider gets
its columns squeezed until words break mid-character.

---

## Architecture

```
layout/     ansible.cfg discovery, roles_path, collections_path, inventory roots
psi/        structural classification — is this a playbook, a role, a task list
refs/       PsiPolyVariantReference per case, plus completion and picker rendering
vars/       the variable index, the precedence engine, the play flow model
inventory/  host→group graph, with Ansible's depth and priority rules
doc/        Quick Documentation
```

Two pieces are worth knowing about:

- **`AnsibleVarIndex`** is a `FileBasedIndexExtension` that records *sites*
  only, classified by path shape. It has no notion of which inventory is
  selected or which roles are in a play — index values must not depend on
  project configuration.
- **`PlayFlow`** linearises a play (`pre_tasks` → per role: meta dependencies
  then tasks → `tasks` → `post_tasks`), expanding literal includes. That is what
  makes "before the `set_fact`" and "after it" different answers.

`VariableResolutionService` encodes the rules as pseudocode'd in
`NAVIGATION-CASES.md` §4.

---

## Status

A working plugin with a thorough test suite, not a released product. Not on the
JetBrains Marketplace. No tool window yet — the role graph is currently walked by
three separate traversals that should be one service first.

---

## License

[Apache License 2.0](LICENSE).

The `test-fixture/` directory is a synthetic Ansible project written as the
specification for this plugin. It describes no real infrastructure and contains
no credentials: every task is `debug`, `file`, `copy` or `template`, and every
host is `127.0.0.1` over a local connection.
