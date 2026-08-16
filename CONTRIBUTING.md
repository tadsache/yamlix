# Contributing

Bug reports are the most useful thing you can send. This plugin answers one
question — which value a variable actually has, on which host, and why — and
the only way it gets that wrong is on a project layout nobody thought of.

## Reporting a bug

Open an issue with the layout that breaks it: the directory shape, the relevant
`ansible.cfg`, and what you expected the answer to be. Invented names are fine
and preferred. A layout that reproduces the problem is worth more than a
description of it, because it becomes a fixture case and then a regression test.

If real Ansible and the plugin disagree, say what `ansible-playbook` or
`ansible-inventory` actually printed. Real Ansible wins every such argument —
see below.

## Building

Requires a JDK 17+ to run Gradle; the build provisions a JDK 21 toolchain.

```bash
./gradlew test           # 216 tests
./gradlew verifyPlugin   # IntelliJ Plugin Verifier, against IDEA CE/Ultimate, PyCharm, GoLand
./gradlew runIde         # sandbox IDE with the plugin loaded
./gradlew buildPlugin    # installable zip in build/distributions
```

`runIde` takes a path argument to open a project directly:

```bash
./gradlew runIde --args="$PWD/fleet-fixture"
```

## The fixtures are the specification

`test-fixture/` and `fleet-fixture/` are synthetic Ansible projects that define
what correct means. `src/test/testData/` holds byte-identical copies, and
`FixtureIntegrityTest` / `FleetFixtureIntegrityTest` fail if the two ever drift
— so the spec cannot be quietly edited to match the code.

Two rules follow from that:

1. **Precedence questions are settled by running Ansible, not by reading the
   documentation.** The docs are wrong in at least two places this repository
   records. If you change a precedence rule, run `ansible-playbook` or
   `ansible-inventory` against the fixture and put what it printed in the commit
   message.
2. **A bug fix starts with a fixture case.** Add the layout that reproduces it
   to `fleet-fixture/` (with its entry in `FLEET-FIXTURE-CASES.md`), copy it into
   `src/test/testData/`, then fix the code. Every identifier must be invented —
   no real variable name, hostname, or URL.

## What a good change looks like

- **It never guesses.** Anything Ansible can only know at run time —
  `register`, `hostvars`, `lookup()`, vault, a fact-templated filename — stays
  unresolved with the raw template shown. Listing every candidate and promoting
  none is correct; picking the likely one is not. There is deliberately no code
  path that invents a value, and a change that adds one will not be merged.
- **It stays off other plugins' territory.** The plugin depends only on the
  platform and bundled YAML. It registers no file type, does not claim `*.yml`,
  and never shells out to `ansible`. That is what lets it coexist with the
  Marketplace's other Ansible plugins and run in every JetBrains IDE.
- **It stays fast on a fleet.** Resolution sweeps every host of every inventory
  for every applicable playbook, so the straightforward implementation is
  quadratic enough to freeze the UI. `ScaleBenchmarkTest` generates a
  fleet-sized project — 16 inventories, 300 hosts each, 25 playbooks, 60 roles —
  and asserts a resolve stays well under a second. Keep it there.
- **Comments say why, not what.** The existing ones record where the platform
  fought back and which alternative was rejected. Match that.

## Pull requests

`./gradlew test` and `./gradlew verifyPlugin` both green, and a CHANGELOG entry
under `## [Unreleased]` for anything a user would notice. Commit messages are
written in the imperative and describe the behaviour that changed, not the files
that moved.
