package dev.yamlix.ansible

/**
 * One test per navigation row of NAVIGATION-CASES.md §1.
 *
 * File, line and token come straight from that table; the expected target is the
 * table's "Expected target" column. When the table documents several candidates,
 * the assertion pins the full ordered list.
 */
class NavigationCaseTest : AnsibleFixtureTestCase() {

    /** N1 — `roles:` short name resolves via `<playbook_dir>/roles`. */
    fun testN1_rolesShortName() =
        assertResolvesTo(
            "roles/app/tasks/main.yml",
            "site-playbook.yml", 28, "app",
        )

    /** N2 — FQCN bypasses roles_path and resolves inside the collection. */
    fun testN2_fullyQualifiedCollectionName() =
        assertResolvesTo(
            "collections/ansible_collections/acme/web/roles/proxy/tasks/main.yml",
            "site-playbook.yml", 31, "acme.web.proxy",
        )

    /** N3 — a role reachable only via the second `roles_path` entry. */
    fun testN3_roleOutsideRolesDir() =
        assertResolvesTo(
            "external-roles/legacy_backup/tasks/main.yml",
            "site-playbook.yml", 34, "legacy_backup",
        )

    /** N4 — `vars_files:` resolves relative to the playbook directory. */
    fun testN4_varsFiles() =
        assertResolvesTo(
            "vars/common.yml",
            "site-playbook.yml", 8, "vars/common.yml",
        )

    /** N5 — role dependency declared in `meta/main.yml`. */
    fun testN5_metaDependency() =
        assertResolvesTo(
            "roles/common/tasks/main.yml",
            "roles/app/meta/main.yml", 11, "common",
        )

    /** N6 — `include_tasks` with a path relative to the role's `tasks/`. */
    fun testN6_includeTasksRelative() =
        assertResolvesTo(
            "roles/app/tasks/configure.yml",
            "roles/app/tasks/main.yml", 39, "configure.yml",
        )

    /** N7 — `include_role` inside a role's tasks. */
    fun testN7_includeRole() =
        assertResolvesTo(
            "roles/monitoring/tasks/main.yml",
            "roles/app/tasks/main.yml", 44, "monitoring",
        )

    /** N8 — `import_role` from inside a role, targeting an out-of-tree role. */
    fun testN8_importRoleFromInsideRole() =
        assertResolvesTo(
            "external-roles/legacy_backup/tasks/main.yml",
            "roles/app/tasks/configure.yml", 10, "legacy_backup",
        )

    /** N9 — `template: src:` resolves against the role's `templates/`. */
    fun testN9_templateSrc() =
        assertResolvesTo(
            "roles/app/templates/app.conf.j2",
            "roles/app/tasks/main.yml", 18, "app.conf.j2",
        )

    /** N10 — `notify:` matches a handler by its `name:` string. */
    fun testN10_notifyHandler() =
        assertResolvesTo(
            "roles/app/handlers/main.yml",
            "roles/app/tasks/main.yml", 22, "Restart app",
        )

    /**
     * N11 — a fact-templated filename has a *set* of answers. Both OS-family
     * files must be offered; picking one would mean guessing a fact.
     */
    fun testN11_factTemplatedIncludeVars() {
        assertResolvesTo(
            listOf("roles/app/vars/Darwin.yml", "roles/app/vars/RedHat.yml"),
            "roles/app/tasks/main.yml", 5, "{{ ansible_os_family }}.yml",
        )
        val reference = referenceAt("roles/app/tasks/main.yml", 5, "{{ ansible_os_family }}.yml")
        assertTrue("N11 must be reported as fact-dependent", reference.isFactDependent)
    }

    /**
     * N12 — a `hosts:` pattern names a group defined in the inventory source and
     * given vars by `group_vars/`. Which inventory applies depends on `-i`, so
     * every inventory root contributes, with the `ansible.cfg` default first.
     */
    fun testN12_hostsGroup() =
        assertResolvesTo(
            listOf(
                "inventories/stag/hosts.yml",
                "inventories/stag/group_vars/webservers.yml",
                "inventories/prod/hosts.yml",
                "inventories/prod/group_vars/webservers.yml",
            ),
            "site-playbook.yml", 3, "webservers",
        )
}
