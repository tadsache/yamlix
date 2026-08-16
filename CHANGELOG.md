# Changelog

All notable changes to yamlix are recorded here. Versions follow
[Semantic Versioning](https://semver.org).

## [Unreleased]

## [0.1.0] — 2026-08-16

First public release. The version number was already in use internally, so this
entry covers the whole plugin rather than only what changed since.

### Added

- Jump to definition for roles, `include_role`, `include_tasks`, `vars_files`,
  `include_vars`, `template`/`copy` sources, `notify` handlers and inventory
  groups. Find Usages goes the other way, including through `include_tasks`.
- Ctrl/⌘-click on a variable lists every definition site, ordered by what
  applies at the caret rather than by name.
- Quick Documentation resolves a variable per inventory and host, collapsing
  the hosts that agree.
- Completion for variables and for `hosts:` patterns, INI inventories included.
- An inspection for roles, includes and file references that resolve to
  nothing.
- An Ansible tool window: the variables the open file uses and defines, their
  effective values, and each definition site under its precedence level, with
  the definitions that lost folded behind one row once there are several.
  Standing in an inventory lists its groups instead, with their hosts and the
  plays that target them. A group nothing targets says so, which is how dead
  configuration becomes visible. Follows the caret; pin freezes it.
- Variable statuses that separate *resolved*, *varies by host*, *ambiguous*,
  *supplied by Ansible*, *undefined* and *never wins*. The last means
  configuration that is written, indexed, and always overridden.
- Ctrl/⌘-click from an inventory group goes to the plays that run on it. That
  direction is inverted from every other jump on purpose: the caret is already
  sitting on the declaration.
- The tool window only appears in projects that actually hold Ansible.

Anything Ansible can only know at run time stays unresolved, with the raw
template shown. Precedence was checked by running ansible-core 2.20.4 against a
fixture project, not read from the documentation.

### Fixed since the internal builds

- A `hosts:` pattern that cannot be evaluated (`web*`, `localhost`) no longer
  makes a role's variables resolve to nothing. A literal group missing from an
  inventory still narrows to nothing, which is a different answer.
- A role used by two plays with different `hosts:` is in scope for both.
- `group_vars`/`host_vars` beside the playbook now outrank the inventory's own,
  as Ansible orders them.
- Playbooks are found anywhere under the `ansible.cfg` directory, not only in
  it and in `playbooks/`.
- A value that is a mapping or a sequence is no longer called a run-time value.
- Symlinked roles and variables are no longer offered twice, in completion or
  in Find Usages.
- A file outside the project's content roots reads as unindexed rather than
  undefined, so a correct project no longer looks like one where nothing is
  defined.
- The tool window waits for indexing instead of answering from a half-built
  index and throwing `IndexNotReadyException` behind it.
- The note on an ambiguous variable describes the ambiguity, instead of being
  taken from whichever row carried a note first.
- Rows are fitted to the width the tool window has, with the full text on
  hover.
- `hostgroup:`-style keys are read from the project's own `hosts:` expressions
  instead of being hard-coded, so the convention works under any name.
- Definitions that cannot apply at the caret are no longer listed.
- Resolution is roughly twenty times faster on a fleet-sized project: a sweep
  that took 1.7s now takes 0.1s.
