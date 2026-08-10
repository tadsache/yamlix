# Milestone 3 — Quick Documentation. Report.

```
./gradlew test verifyPlugin      BUILD SUCCESSFUL
  NavigationCaseTest             12   (M1)
  CompletionAndInspectionTest     8   (M1)
  FixtureIntegrityTest            1
  VariableResolutionTest         14   (M2)
  VariableNavigationTest          6   (M2)
  IniInventoryTest                4   (M2)
  QuickDocumentationTest         14   <- M3, test set is §3
                                 ---
                                  59   0 failures
IntelliJ Plugin Verifier          IC-252.28539.97 -> Compatible (9 experimental-API usages)
```

Negative controls: flipping the ambiguous verdict to `LITERAL` and the stag
`set_fact` value from 8500 to 8100 both fail the suite.

## Extension point

`com.intellij.platform.backend.documentation.targetProvider` →
`DocumentationTargetProvider`, verified against the 2025.2.6.3 jars with `javap`
rather than assumed.

Offset-based rather than PSI-based, and that is not a workaround — a Jinja
expression has no PSI of its own, so there is no element for
`PsiDocumentationTargetProvider` to hang off. This EP hands over the raw caret
offset, which is exactly the input the in-scalar arithmetic needs.

**The offset math turned out to be a non-problem, for one reason worth
recording.** `PsiReference.rangeInElement` is relative to the *element's own
text*, and `AnsibleVariableReference.identifierRanges` scans that same text. So
plain, single-quoted, double-quoted and folded (`>-`) scalars all work through
one code path with no block-header or indentation compensation. Had the ranges
been computed against `YAMLScalar.textValue` — the logical, unfolded content —
every folded scalar in the fixture would have been off by the width of the block
header plus the indentation of each continuation line. `textValue` is the
tempting API; it is the wrong one here.

The provider also probes `offset - 1`, so Ctrl+Q with the caret immediately
after the last character of an identifier still works.

## What the popup shows

`inventory | effective value | defined in`, exactly as specified.

Rows are `(inventory, set-of-hosts)`. Hosts that resolve identically collapse
into one row; hosts that differ get their own. For `app_workers` the fixture
produces four rows from two inventories:

```
prod (prod-web-1)   16   host_vars[prod-web-1]   inventories/prod/host_vars/prod-web-1.yml · rank 10
prod (prod-web-2)   14   group_vars[webservers]  inventories/prod/group_vars/webservers.yml · rank 7
stag (stag-web-1)    6   host_vars[stag-web-1]   inventories/stag/host_vars/stag-web-1.yml · rank 10
stag (stag-web-2)    4   group_vars[webservers]  inventories/stag/group_vars/webservers.yml · rank 7
```

For `app_log_level` on prod both hosts agree, so it renders as a single `prod`
row rather than two identical ones.

A "Cannot be certain" section lists the caveats — always including that `-e`
extra vars outrank everything and are invisible from the repo.

## Never guessing: five kinds, one of which is a value

`ValueKind` is the whole mechanism. Only `LITERAL` renders a value:

| Kind | Rendered as | Trigger |
|---|---|---|
| `LITERAL` | the value | plain, no Jinja |
| `TEMPLATE` | raw template + `(unresolved)` | value contains `{{ }}` |
| `RUNTIME` | `unresolved — run-time value` | `register:` |
| `AMBIGUOUS` | `unresolved — one of the candidates below` | fact-selected or undecidable guard |
| `UNDEFINED` | `unresolved — not defined here` | nothing in the repo defines it |

There is no code path that produces a value for the last four. `hostvars`,
`lookup(`, `query(`, `vault` and `ansible_facts` are named explicitly in the
note when they appear in a template, so the popup says *why* it cannot know.

### A correction I had to make while writing the tests

My first implementation showed a single value for `AMBIGUOUS` — whichever
candidate sorted last. On prod, `app_port` came out as **8200**, because
`RedHat.yml` sorts after `Darwin.yml`. That is a guess produced by an
alphabetical tiebreak and presented as an answer, which is precisely what §3
forbids. `ReportRow.value` is now `null` for `AMBIGUOUS` and every candidate is
listed underneath with its source file.

### A second, subtler one

Listing "alternatives" needs to know *why* something is ambiguous, so
`ConditionKind` now distinguishes two cases:

- **`FACT_SELECTED`** — one of several `include_vars` candidates. The include
  definitely runs; only *which file* is unknown. So the lower-ranked role-vars
  value (8090) is **not** a possible outcome and listing it would mislead.
- **`GUARD`** — an undecidable `when:`. The site may not apply at all, so the
  unconditional site beneath it *is* a genuine outcome and is listed.

The test caught this: it expected `[8100, 8200]` and got `[8090, 8100, 8200]`.

### And the case that is genuinely decidable

`when: app_env == 'stag'` compares against an inventory variable that resolves
statically and unconditionally, so the popup states it plainly: stag shows
`8500` from `set_fact` as a `LITERAL`, prod does not show it at all. Refusing to
decide this one would be as wrong as guessing the other.

## Restructurings from the M2 report, done

- **PlayFlow is now cached** per playbook, keyed on
  `PsiModificationTracker` + `AnsibleLayoutTracker`. Ctrl+Q resolves once per
  host per inventory — four resolutions for the fixture, dozens for a real
  project — and each one previously re-walked every role and included task file.
- **"Which playbook am I in" is answered**, not ducked.
  `VariableReportBuilder.playbooksFor` returns *every* playbook whose role
  closure (including `meta` dependencies) contains the enclosing role. One
  report is produced per playbook and the renderer labels each section. A file
  in a role used by three playbooks gets three tables rather than one arbitrary
  answer. `AnsibleLayoutService.playbooks` scans only the `ansible.cfg`
  directory and a `playbooks/` subdirectory — a recursive walk would parse every
  role's `tasks/main.yml` on each call.

Two items from that report remain open and are listed below.

## Honest caveats

**The verifier reports 9 experimental-API usages.** `TargetPresentation`,
`TargetPresentationBuilder` and `com.intellij.model.Pointer` are all
`@ApiStatus.Experimental`. There is no non-experimental way to implement a
`DocumentationTarget`; the modern documentation API is experimental in its
entirety. The plugin is Compatible, but these signatures can change between
platform releases and this is the most likely thing to break on an upgrade.

**Playbook discovery is shallow.** Playbooks outside the `ansible.cfg` directory
and `playbooks/` are not found, and a variable in a role reached only from such
a playbook falls back to "all playbooks". Deliberate: correctness here costs a
full-project YAML parse per invocation.

**Host enumeration is capped at 40 per inventory**, and when it truncates it
says so in the caveats rather than silently showing a subset.

**Reports are not cached.** `buildAll` resolves per host on every Ctrl+Q. The
`PlayFlow` cache removes the expensive part, but on a 500-host inventory the
remaining per-host index queries will be felt. This is the first thing to
measure before a tool window multiplies the call count.

## Still open

1. **`staticRoleNames` and `roleClosure` are two hand-written walks of the same
   role graph**, in `VariableResolutionService` and `VariableReportBuilder`
   respectively, and `PlayFlow` computes a third while linearising. These should
   be one `RoleGraph` service — this is now the largest piece of duplication in
   the codebase and the obvious prerequisite for a tool window.
2. **Two orderings of the same data.** `VarResolution.sites` is ascending for
   the doc tables; N13 navigation wants descending and re-queries the index to
   get it. Navigation should consume `VarResolution` instead.
