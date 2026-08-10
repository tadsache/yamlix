# Milestone 2 — variable index. Report.

```
./gradlew test verifyPlugin      BUILD SUCCESSFUL
  NavigationCaseTest             12    (M1, still green)
  CompletionAndInspectionTest     8    (M1, still green)
  FixtureIntegrityTest            1
  VariableResolutionTest         14    <- §2a / §2b / §2c / §2d verbatim
  VariableNavigationTest          5    <- N13
  IniInventoryTest                4    <- [group:vars], [group:children], inline host vars
                                 ---
                                  44   0 failures
IntelliJ Plugin Verifier          IC-252.28539.97 -> Compatible
```

Negative controls: flipping the expected R5 value (8090 → 8070) and the group
priority winner (debug → info) both fail the suite, so the tables are genuinely
pinned.

## What was built

| Piece | Class | Kind |
|---|---|---|
| Variable index | `AnsibleVarIndex` | `FileBasedIndexExtension<String, List<VarDefinitionData>>`, `VERSION = 5`, custom `VarDefinitionExternalizer` |
| Path classifier | `VarFileRole` | Pure path-shape function, no project state |
| Inventory topology | `InventoryGraph`, `InventoryGraphService` | Cached project service, **not** an index |
| Execution order (R7) | `PlayFlow` | Expands roles, meta deps, `include_tasks`, `include_role` |
| Precedence (R3–R7) | `VariableResolutionService` | Ordered sites + winner + caveats |
| N13 navigation | `AnsibleVariableReference` | Poly-variant, winner first |

Indexed: role defaults, role vars, `group_vars` (file *and* directory form),
`host_vars`, playbook `vars:`, `vars_files` targets, role params from both
`roles:` and `meta` dependencies, `set_fact` (with its `when:`), `register`,
task `vars:`, blocks/rescue/always, and INI `[group:vars]` / `[all:vars]` /
inline host variables.

## Two shape changes from the brief

**1. The index value carries no file.** A `VirtualFile` is not serialisable, and
`FileBasedIndex` hands the file back at query time anyway. So the stored record
(`VarDefinitionData`) is offset + scope + qualifier + value + guard, and the
runtime type the resolver returns (`VarSite`) is hydrated with the file. Same
information, split where the platform splits it.

**2. Precedence is derived, not stored.** `VarDefinitionData` stores the *scope*;
rank comes from `VarScope.rank`. Storing a number would bake Ansible's table
into every user's on-disk index, so correcting a rank would need a version bump
rather than a code change.

Also added beyond the brief, because the tables cannot be asserted without them:
`valueText` (the effective value) and `guard` (the `when:` expression).

## The interesting problems

**Group ordering is a sub-rank, not a rank.** Every `group_vars/<g>.yml` sits at
rank 7; which one wins is decided by `(depth, priority, name)`. So `VarSite`
carries a `subRank` holding the group's index in that ordering, and the final
sort is `(rank, subRank, fileName)`. The filename tiebreak exists purely so a
same-rank tie is stable across index iteration order — without it the test for
`Darwin.yml` vs `RedHat.yml` was order-dependent.

**Depth is a maximum, not a shortest path.** `InventoryGraphBuilder.computeDepths`
iterates `child.depth = max(child.depth, parent.depth + 1)` to a fixpoint, with a
bounded loop so a cycle in `children:` cannot hang the IDE. Using shortest-path
would silently reorder groups in any diamond-shaped inventory.

**R5 fell out of the design rather than needing a special case.** Because role
defaults/vars are admitted whenever their role is in the play's static role set —
including roles pulled in by `meta` dependencies — `pre_tasks` correctly sees
`app_port=8090`, and so does the unrelated `common` role. There is a dedicated
test for it (`testRoleVarsAreVisibleInsideADifferentRole`) since it is the rule
most likely to regress.

**R7 needed a real flow model.** `PlayFlow` linearises `pre_tasks` → per role
(meta deps depth-first, then tasks) → `tasks` → `post_tasks`, expanding literal
`include_tasks` and `include_role` in place. `include_vars` targets are recorded
against the step that loads them, so a site in `Darwin.yml` is out of scope
before that step and in scope after. Templated includes are recorded in
`PlayFlow.unexpandable` and surface as caveats rather than being dropped.

## Where §2b and §3 pull in opposite directions

§2b's table says `app_port` is **8100** inside role `app` — because the verified
run happened on a Darwin host. §3 says a fact-templated `include_vars` must never
be narrowed to one file. Both are right, and a resolver that only did one would
be wrong.

The resolution is `ResolutionContext.knownFacts`:

- **Empty (the default)** — `Darwin.yml` and `RedHat.yml` are both returned at
  rank 19, both flagged `conditional`, `isAmbiguous` is true, and the
  unconditional winner falls back to role vars (8090).
  `testWithoutFactsTheIncludeVarsTargetIsAmbiguous` pins this.
- **`ansible_os_family=Darwin`** — the template renders to one filename, the
  sibling is dropped, and the answer is exactly 8100.
  `testAppPortWinnerChangesWithPosition*` pins this.

The resolver never invents a fact. It only uses one it was handed.

The `when:` guard is different, and genuinely decidable: `app_env == 'stag'`
compares against an inventory variable that resolves statically and
unconditionally, so the guard evaluates to true on stag (set_fact applies, 8500)
and false on prod (site dropped, 8100). Guard evaluation is restricted to
`variable == 'literal'` / `!=` where the variable resolves unconditionally to a
non-Jinja value; anything else — facts, `hostvars`, lookups, nested ambiguity —
returns UNKNOWN and the site stays conditional. That is the §3 rule, enforced by
construction rather than by convention.

Every resolution also carries the caveat that `-e` extra vars outrank everything
and are invisible from the repo.

## One bug the fixture caught

`vars/common.yml` was being indexed as **role vars** rather than a `vars_files`
candidate. The classifier treated any directory containing `vars/` as a role, and
the fixture's project root contains a top-level `vars/` — so the root itself
looked like a role and 8070 vanished from the precedence list. The role markers
are now `tasks`, `defaults`, `meta` only. This is exactly the kind of thing a
synthetic fixture would not have caught; the real layout did.

## N13 navigation

Variable references are contributed only on scalars that are *not* already a
role or file reference, so reference ranges never overlap — clicking
`{{ ansible_os_family }}.yml` on an `include_vars` line still navigates to the
file candidates, not the fact.

Identifier extraction works on the element's own text, which means folded (`>-`)
scalars need no separate offset arithmetic: `rangeInElement` is relative to the
element text either way. Attribute access (`app_config.changed`), filter names
(`| int`) and string literals (`'app_port'`) are excluded, with a test.

Navigation is **host-agnostic on purpose**: all definition sites project-wide,
ordered winner-first by rank. The IDE has no current host, and pruning by an
invented one would hide real definitions. Per-inventory effective values are
Milestone 3's job.

Variable references are exempt from the unresolved-reference inspection —
`inventory_hostname`, gathered facts and `-e` variables have no definition site
in the repo, so flagging them would make the inspection useless noise.

## What to restructure before Milestone 3

**1. `VariableResolutionService.resolve` rebuilds the `PlayFlow` on every call.**
Fine for a test; Quick Documentation on a variable in a large play will rebuild
it per keystroke. It needs the same `CachedValuesManager` treatment the
references already have, keyed on the playbook plus `AnsibleLayoutTracker`.

**2. There is no "which playbook am I in" answer for a role file.** Resolution
currently takes the playbook as a parameter. A role file can belong to several
playbooks, so M3 has to either pick one, ask, or show an N-way table. This is a
real product decision, not a coding one — worth settling before writing the
documentation renderer.

**3. `staticRoleNames` walks `meta/main.yml` by hand** and duplicates what
`PlayFlow` already computes while linearising. They should share one role-graph
walk.

**4. The `sites` list is returned ascending** to match the doc tables, while N13
navigation wants descending. Two orderings of the same data, currently produced
by two different code paths. M3 wants a single ordered structure with an explicit
`winner` pointer, which `VarResolution` already has — the navigation path should
use it instead of re-querying the index.
