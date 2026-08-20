# yamlix

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/33589-yamlix-for-ansible?label=Marketplace)](https://plugins.jetbrains.com/plugin/33589-yamlix-for-ansible)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/33589-yamlix-for-ansible)](https://plugins.jetbrains.com/plugin/33589-yamlix-for-ansible)
[![Build](https://github.com/tadsache/yamlix/actions/workflows/build.yml/badge.svg)](https://github.com/tadsache/yamlix/actions/workflows/build.yml)

**Which value does this variable actually have — on which host, and why?**

Install from the JetBrains Marketplace:
**[Yamlix for Ansible](https://plugins.jetbrains.com/plugin/33589-yamlix-for-ansible)**
— or search *Yamlix* under **Settings → Plugins → Marketplace**.

A JetBrains IDE plugin for Ansible projects — it depends only on the platform and
bundled YAML, so it runs in IDEA, PyCharm, GoLand and the rest. A variable in Ansible does not have
*a* definition: it has as many as the precedence table allows, and which one wins
depends on the host, the inventory, and where in the play you are. Yamlix models
that properly, and refuses to guess when the answer is only knowable at run time.

The precedence rules were verified by running `ansible-playbook` and
`ansible-inventory` against a fixture project with **ansible-core 2.20.4** — not
reasoned from the documentation, which is wrong in at least two places this
repository records.

It reuses the platform's YAML PSI. It registers no file type, does not claim
`*.yml`, and never shells out to `ansible`.

### Why another Ansible plugin?

The Marketplace has several, and they are better than this one at breadth —
module completion, schemas, linting, vault. None of them answer the question
above. If you want syntax help writing a task, use one of those. If you want to
know why `artifact_repo` is the canary URL on exactly one host in one
environment, that is what this is for. They coexist.

```
Any JetBrains IDE 2025.2+ (sinceBuild 252, no upper bound)   Kotlin · Gradle 9 · IJPGP 2.x
214 tests · Plugin Verifier: Compatible on IDEA CE/Ultimate, PyCharm, GoLand
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
src/test/testData/fixture/     a small but deliberately nasty Ansible project
docs/NAVIGATION-CASES.md       13 navigation cases, verified resolution
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

Because every expected value came out of real Ansible rather than out of the
implementation, the document is a specification the code answers to, not a
transcript of what the code happens to do.

A second, independent fixture — `src/test/testData/fleet-fixture/`, documented
in [docs/FLEET-FIXTURE-CASES.md](docs/FLEET-FIXTURE-CASES.md) — exists purely to keep real-world bug
reports from regressing: a project-root `group_vars/all.yml` sibling to
`inventories/`, plain-INI inventories with no file extension, a role's
`hosts:` pattern matching a sliver of a much bigger inventory, playbooks that
open with an `import_playbook` step before their real play,
`include_vars: "{{ item }}"` + `with_first_found:`, a role reachable through a
symlinked directory, host patterns the plugin cannot model (`web*`,
`localhost`), one role shared by two plays with different `hosts:`, and
playbook-adjacent `group_vars/`. Every identifier in it is invented — no real
variable name, hostname, or URL.

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
./gradlew test           # 214 tests
./gradlew verifyPlugin   # IntelliJ Plugin Verifier
./gradlew runIde         # sandbox IDE with the plugin loaded
./gradlew buildPlugin    # build/distributions/yamlix-<version>.zip
```

To try it: `runIde`, then open `src/test/testData/fixture/` in the sandbox IDE. Wait for
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

**The tool window's existence is decided once, at project open.** It appears
only in projects that hold an `ansible.cfg` or an Ansible layout within two
directories of a content root — so it does not sit permanently empty on the
stripe of every unrelated project. A repository that gains its first
`ansible.cfg` mid-session gets the window on the next start; the platform
offers no way to re-ask the question.

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
[docs/NAVIGATION-CASES.md](docs/NAVIGATION-CASES.md) §4.

---

## Status

Published on the JetBrains Marketplace as
**[Yamlix for Ansible](https://plugins.jetbrains.com/plugin/33589-yamlix-for-ansible)**.
Install it from inside the IDE — **Settings → Plugins → Marketplace**, search
for *Yamlix* — or take the signed zip from the
[releases page](https://github.com/tadsache/yamlix/releases) and use
**Settings → Plugins → ⚙ → Install Plugin from Disk**.

Releasing is automated: a `v*` tag runs the tests and the Plugin Verifier,
signs the plugin, publishes it to the Marketplace and drafts a GitHub release
with the same signed zip.

Known rough edge beyond the limitations above: the role graph is walked by
three separate traversals that should be one service.

---

## Contributing

Bug reports are the most useful thing you can send — this plugin only gets the
answer wrong on a project layout nobody thought of. [CONTRIBUTING.md](CONTRIBUTING.md)
covers the build, the rule that the fixtures are the specification, and why a
fix starts with a fixture case. Vulnerabilities go to the address in
[SECURITY.md](SECURITY.md) rather than to a public issue.

---

## License

[Apache License 2.0](LICENSE).

The fixtures under `src/test/testData/` are synthetic Ansible projects written
as the specification for this plugin. They describe no real infrastructure and
contain no credentials: every task is `debug`, `file`, `copy` or `template`, and every
host is `127.0.0.1` over a local connection.
