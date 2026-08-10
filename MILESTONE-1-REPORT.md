# Milestone 1 — navigation. Report.

```
./gradlew test verifyPlugin      BUILD SUCCESSFUL
  NavigationCaseTest             12 tests, 0 failures    (rows N1–N12)
  CompletionAndInspectionTest     8 tests, 0 failures
  FixtureIntegrityTest            1 test,  0 failures
IntelliJ Plugin Verifier          IC-252.28539.97 -> Compatible
```

1610 lines of Kotlin. No `FileType` registered, `*.yml` not claimed, and no
process spawned anywhere in `src/main` (grep for `ProcessBuilder`,
`GeneralCommandLine`, `Runtime.getRuntime`, `ansible-playbook` returns only a
prose mention in a KDoc).

`FixtureIntegrityTest` asserts `src/test/testData/fixture` is byte-identical to
`test-fixture/`, so the specification cannot be quietly edited to suit the
implementation.

## Extension points used

| EP | Class | Why |
|----|-------|-----|
| `com.intellij.psi.referenceContributor` (`language="yaml"`) | `AnsibleReferenceContributor` | The only entry point. One pattern, `AnsiblePatterns.anyAnsibleReference()`, dispatching to six reference classes. |
| `com.intellij.completion.contributor` (`language="yaml"`) | `AnsibleCompletionContributor` | **Not in the brief.** See friction #2 — YAML does not run the platform's legacy reference-completion fallback, so `getVariants()` alone reaches nobody. The contributor is a thin delegate to `getVariants()`, so completion and resolution still share one model. |
| `com.intellij.localInspection` (`language="yaml"`) | `UnresolvedAnsibleReferenceInspection` | Flags references whose `multiResolve` is empty. |
| `applicationListeners` → `BulkFileListener` | `AnsibleVfsListener` | Bumps `AnsibleLayoutTracker`. |
| `@Service(Service.Level.PROJECT)` | `AnsibleLayoutService` | `ansible.cfg` discovery, `roles_path`, `collections_path`, inventory roots. |

Base classes: `PsiPolyVariantReferenceBase<YAMLScalar>` for all six references,
`CachedValuesManager.getCachedValue(element, key)` keyed on
`PsiModificationTracker.MODIFICATION_COUNT` + `AnsibleLayoutTracker`.

## Where the platform fought us

**1. Completion runs against a non-physical copy of the file.** The one real
bug. `PsiElement.containingFile.virtualFile` inside a completion session returns
a light in-memory file with no parent directory, so walking up to find
`ansible.cfg` found nothing, `isAnsibleContext()` returned false, no references
were contributed, and completion silently returned an empty list — with no error
anywhere. Fix: every lookup goes through `PlayStructure.sourceFile()`, which
prefers `containingFile.originalFile.virtualFile`. Any project-layout-aware
plugin has this bug until it hits it; the failure mode is silence, not an
exception.

**2. YAML has no legacy reference-completion fallback.** Implementing
`getVariants()` on a `PsiReference` gets you completion for free in several
languages. Not in YAML. Cost: one extra extension point.

**3. There is no modification tracker for "a directory appeared".**
`PsiModificationTracker.MODIFICATION_COUNT` does not fire for VFS structure
changes, and the role search path is entirely a question of which directories
exist. Hence `AnsibleLayoutTracker` (a `SimpleModificationTracker`) plus a
filtered `BulkFileListener`. Filtering matters — an unfiltered listener
invalidates the whole layout on every build-output write.

**4. `PsiElementPattern` depth is unusable against YAML sequences.** The
distance from a scalar to its owning key is 2 for `key: value`, 3 for a sequence
item, and 4 for `- role: name` inside a sequence. `withSuperParent(n, …)` chains
would encode those numbers. Instead one `PatternCondition` delegates to
`AnsiblePatterns.classify()`, a single structural classifier that references,
completion and the inspection all share. Still position-based, not filename-based.

**5. Signature and typing papercuts.** `PsiReferenceBase` already declares
`getValue()`, so a Kotlin `val value` on a subclass is an accidental override —
the error names a JVM signature clash, not the property. `Key<CachedValue<T>>`
is required where the natural guess is `Key<CachedValueProvider.Result<T>>`.
`ProblemsHolder.registerProblem` takes the `TextRange` *after* the
`ProblemHighlightType`, not before.

**6. Kotlin, not the platform:** block comments nest, so `` `inventories/*` ``
inside a KDoc opens a comment that the closing `*/` then only half-closes. The
compiler reports "missing `}`" 70 lines away.

**7. The plugin verifier rejects an em dash in `<name>`.** Only
`letters, digits, spaces, .,+_-/:()#'&[]|` are allowed.

## Deviations from the specification, stated not hidden

**The CWD rule cannot be implemented, by construction.** §3 of
NAVIGATION-CASES.md establishes that `ansible.cfg` discovery and relative
`roles_path` entries resolve against the process working directory — running the
fixture from its parent drops `./external-roles` and breaks N3. An IDE has no
CWD. `AnsibleLayoutService` resolves relative entries against the directory
holding the `ansible.cfg` found by walking up from the referencing file. That is
what a user running `ansible-playbook` from their project root gets, and it is
the only choice that makes N3 resolve, but it is not literally Ansible's rule.
A project whose cfg is intended to be used from elsewhere resolves differently
here. This is a candidate for a project-level setting.

**N11 candidate set is scoped to the first matching directory.** Ansible takes
the first search directory containing a match, so `{{ ansible_os_family }}.yml`
globs `roles/app/vars/` and stops. Unioning across all search directories would
also have offered the playbook's own `vars/common.yml`, which matches `*.yml`.
`main.yml` is additionally excluded from templated matches — it is loaded
automatically by the role loader and is never what a templated `include_vars`
is reaching for. That exclusion is a heuristic, not a rule from Ansible's source.

**N12 returns four targets, not one.** `hosts: webservers` resolves to the group
key and `group_vars` file in *both* inventories, `ansible.cfg`'s default
(`inventories/stag`) first. Which inventory applies depends on `-i`, which §3
lists as statically unknowable, so an N-way answer is the honest one.

**Role references resolve to `tasks/main.yml`**, falling back to
`meta/main.yml`, then the `PsiDirectory`. The table's target column names the
role directory; navigating a user to a directory node is worse UX than the entry
point they actually want.

**N13 is not implemented**, as agreed when the plan was approved. The row itself
says it is not a single-target jump; it needs the §2 precedence machinery and
lands with Milestone 2.

## What to restructure before adding a tool window

**1. Resolution logic must move out of the reference classes.** It already
lives in `AnsibleTargets` (an object), but its entry points all take
`(from: VirtualFile, project: Project)` — they answer "what does *this
reference* see". A tool window asks the inverse: "what roles exist", "what plays
exist", "which playbooks reference this role". That wants a project-level
`AnsibleModelService` owning a materialised model, with the references becoming
thin queries against it. Doing this before the tool window avoids the tool
window instantiating references just to ask questions.

**2. `AnsibleTargets.handlers()` is accidentally quadratic.** Every `notify:`
resolution calls `roleVariants()`, which enumerates every role directory on
every search path, then parses every handler file it finds. Fine for one
reference in an editor; not fine for a tool window rendering a whole play. This
needs to become a real index — and it is the natural first client of the
Milestone 2 `FileBasedIndexExtension`, which suggests building the index before
the tool window rather than after.

**3. The layout cache has no change notification.** `AnsibleLayoutService` holds
a `ConcurrentHashMap` inside a `CachedValue`; consumers discover staleness by
re-reading. A tool window needs to be *told*. Before that, `AnsibleLayoutTracker`
should be joined by a project `MessageBus` topic that the VFS listener publishes
to.

**4. Inventory topology does not exist yet.** `inventoryRoots()` returns
directories; nothing parses the group graph. That is Milestone 2's
`InventoryGraphService`, and a tool window showing hosts and groups depends on
it entirely. Sequencing M2 before the tool window is not optional.
