# Changelog

All notable changes to yamlix are recorded here. Versions follow
[Semantic Versioning](https://semver.org).

## [Unreleased]

### Added

- **Ansible tool window.** Every variable the current file uses and defines,
  with its effective value and what is wrong with it; expand one to see every
  definition site, led by its Ansible precedence level, with the inventories
  and groups it holds on. A header saying which playbooks reach the file and
  which hosts that means. Follows the caret; pin freezes it.
- Variable statuses that distinguish *resolved*, *varies by host*,
  *ambiguous*, *supplied by Ansible*, *undefined*, and *never wins* — the last
  being configuration that is written, indexed and always overridden.
- `{{ item }}` and `ansible_loop_var` recognised as variables Ansible supplies.
- **Inventories in the tool window.** Standing in an inventory now lists its
  groups: how many hosts each holds, its child groups, the `group_vars` that
  apply, and — the part with no answer anywhere else — every play whose
  `hosts:` selects it. A group nothing targets says so, which is how dead
  configuration becomes visible. INI inventories are recognised too; being
  something other than YAML, they were previously told to open a file inside an
  Ansible project, while inside one.
- **Ctrl-click from an inventory group** goes to the plays that run on it. The
  direction is inverted from every other jump on purpose: the caret is already
  on the declaration, so the platform's answer was "Cannot find declaration to
  go to", and the useful jump is to where the group is consumed. Both this and
  the tool window ask one service, so they cannot come to differ.
- A playbook's own declarations in the tool window: each play with the hosts it
  targets and how many that is, the roles it runs, and its `import_playbook`
  entries, interleaved in file order. A site playbook references no variables
  of its own, so the window previously had nothing to say about the very file
  that decides where everything runs.

### Fixed

- Find Usages no longer lists the same use twice when a role is reachable
  through a symlink. A `playbooks/<area>/roles -> ../../roles` link makes every
  role file reachable at two paths; they are one file on disk, so the second
  was the same line counted again — and a rename would have rewritten the same
  bytes twice. Navigation from the symlinked path still works: what is declined
  is the claim that it is a separate use, not that it points where it points.

### Fixed (tool window)

- Rows are fitted to the width the tool window actually has, with the full text
  on hover. A tree row does not wrap and does not shrink, so docked at its
  default width the end of every row — the inventories, the path, the part that
  says *where* — sat past the edge with nothing to suggest it was there.

- A file outside the project's content roots is reported as unindexed rather
  than undefined. Nothing there is indexed, so every lookup came back empty and
  a completely correct project read as one where no variable is defined
  anywhere — indistinguishable, on screen, from a real finding.
- The note on an ambiguous variable now describes the ambiguity. It was taken
  from whichever of the variable's rows carried a note first, so a variable
  ambiguous under one inventory and undefined under another read "not defined
  anywhere in this project" beside a list of its four candidate values.
- The tool window no longer answers while the index is still building. Every
  row comes from the variable index, so running during start-up indexing did
  not produce a partial view but a confident, wrong one — a role's own
  `defaults/main.yml` reported as "not defined in this project" — and threw
  `IndexNotReadyException` behind it. It now says it is indexing and rebuilds
  when indexing ends.

### Changed

- Definition sites are shown inline under their variable instead of in a second
  pane, and lead with the precedence level — `role defaults`, `group_vars` —
  rather than a verdict. `WINS` was shouted loudest in the case where it said
  least: a single definition has nothing to have won against. The outcome is
  carried by the icon and by greying what lost, and the level says *why* it
  wins. Sites are ordered winner-first and then by descending precedence, so
  the list reads as the ladder it is; index order had put an overridden
  definition above the one that beat it.
- Playbooks are found anywhere under the `ansible.cfg` directory, not only in
  it and in `playbooks/`. A `playbooks/<area>/site-*.yml` layout was previously
  invisible, so role reach and variable resolution silently under-reported.
- "Choose Declaration" rows lead with the value, then where it applies and the
  file. The scope prefix, the group name repeated from the filename, and the
  internal precedence rank are gone.
- Definitions that cannot apply at the caret — a same-named variable in an
  unrelated role — are no longer listed. Flow-sensitive scopes (`set_fact`,
  `include_vars`, `register`) still are, because there the reason is position.
- Quick Documentation collapses playbooks that resolve a variable identically,
  and rows identical in every inventory.
- `hosts:` completion offers every group and host from the parsed inventory,
  including INI inventories and groups that have no `group_vars` file.
- Variable resolution is roughly twenty times faster on a fleet-sized project;
  a sweep that took ~1.7s now takes ~0.1s.

### Fixed

- A `hosts:` pattern that cannot be evaluated (`web*`, `localhost`) no longer
  makes a role's variables resolve to nothing. A literal group absent from an
  inventory still correctly narrows to nothing — the two are different answers.
- A role used by two plays with different `hosts:` is in scope for both.
- `group_vars`/`host_vars` beside the playbook are recognised, and outrank the
  inventory's own as Ansible orders them.
- A variable whose value is a mapping or sequence is no longer reported as a
  run-time value.
- Roles, variables and completions reachable through a symlinked directory are
  no longer offered twice.
- `hostgroup:`-style keys are discovered from the project's own `hosts:`
  expressions instead of being hard-coded, so the convention works under any
  name and stays silent on projects that do not use it.

## [0.1.0]

Initial internal release: jump-to-definition for roles, includes, task files,
templates, handlers, inventory groups and variables; Find Usages; Quick
Documentation with per-inventory variable resolution; completion and an
unresolved-reference inspection.
