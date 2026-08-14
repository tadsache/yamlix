package dev.yamlix.ansible

import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.yamlix.ansible.vars.VariableReportBuilder
import org.jetbrains.yaml.psi.YAMLScalar
import com.intellij.psi.util.PsiTreeUtil

/**
 * A synthetic project shaped like a real fleet: many inventories, hundreds of
 * hosts, many playbooks and roles.
 *
 * The printed timings are for reading; the assertion is a guard against an
 * *algorithmic* regression, not a wall-clock target. Resolving once per host
 * per playbook per inventory — which is what this codebase did before
 * `resolveAcross` — takes ~1.7s on this project, so the ceiling below trips on
 * that while leaving more than an order of magnitude of headroom for a slow
 * or loaded CI machine.
 */
class ScaleBenchmarkTest : BasePlatformTestCase() {

    /** Generous enough not to flake; tight enough to catch a return to the per-host sweep. */
    private val ceilingMillis = 1_000L

    private val inventories = 16
    private val hostsPerInventory = 300
    private val playbooks = 25
    private val roles = 60

    private fun generate() {
        myFixture.addFileToProject("ansible.cfg", "[defaults]\nroles_path = ./roles\n")
        myFixture.addFileToProject(
            "group_vars/all.yml",
            "---\nartifact_repo: \"https://repo.example.test/generic\"\nshared_tunable: 10\n",
        )

        for (i in 0 until inventories) {
            val env = "env-%02d".format(i)
            val sb = StringBuilder()
            for (g in 0 until 10) {
                sb.append("[group_$g]\n")
                for (h in 0 until hostsPerInventory / 10) {
                    sb.append("%s-host-%03d\n".format(env, g * (hostsPerInventory / 10) + h))
                }
                sb.append("\n")
            }
            myFixture.addFileToProject("inventories/$env/hosts", sb.toString())
            myFixture.addFileToProject(
                "inventories/$env/group_vars/group_3.yml",
                "---\nshared_tunable: $i\n",
            )
        }

        for (r in 0 until roles) {
            myFixture.addFileToProject(
                "roles/role_$r/defaults/main.yml",
                "---\nshared_tunable: $r\nrole_${r}_only: yes\n",
            )
            myFixture.addFileToProject(
                "roles/role_$r/tasks/main.yml",
                "---\n- name: use it\n  ansible.builtin.debug:\n" +
                    "    msg: \"{{ shared_tunable }} {{ artifact_repo }}\"\n",
            )
        }

        for (p in 0 until playbooks) {
            val roleList = (0 until roles).filter { it % playbooks == p % playbooks }
                .joinToString("\n") { "    - role_$it" }
            myFixture.addFileToProject(
                "site-%02d.yml".format(p),
                "---\n- hosts: group_${p % 10}\n  roles:\n$roleList\n",
            )
        }
    }

    private fun scalarIn(path: String): YAMLScalar {
        val target = myFixture.findFileInTempDir(path)!!
        val psi = PsiManager.getInstance(project).findFile(target)!!
        return PsiTreeUtil.findChildrenOfType(psi, YAMLScalar::class.java)
            .first { it.text.contains("{{") || it.text.contains("repo.example") }
    }

    private fun time(label: String, runs: Int, body: () -> Unit): Long {
        body() // warm caches (index, inventory graphs) — measure the sweep, not indexing
        val started = System.nanoTime()
        repeat(runs) { body() }
        val millis = (System.nanoTime() - started) / runs / 1_000_000
        println("BENCH $label: ${millis}ms")
        return millis
    }

    fun testSweepTiming() {
        generate()
        val builder = VariableReportBuilder.getInstance(project)

        println(
            "BENCH inventories=$inventories hosts/inv=$hostsPerInventory " +
                "playbooks=$playbooks roles=$roles",
        )

        // Narrow path: inside a role, so playbooksFor() filters to the few
        // playbooks that actually pull that role in.
        val inRole = scalarIn("roles/role_3/tasks/main.yml")
        time("siteScopes from role  (Ctrl-click)", 3) { builder.siteScopes("shared_tunable", inRole) }
        time("buildAll   from role  (Ctrl-Q)    ", 3) { builder.buildAll("shared_tunable", inRole) }

        // Wide path: inside group_vars, which every playbook in the project
        // applies to — playbooksFor() cannot narrow anything. This is the
        // worst case, and the one that used to freeze the popup.
        val inGroupVars = scalarIn("group_vars/all.yml")
        val scopes = time("siteScopes from group_vars", 1) {
            builder.siteScopes("artifact_repo", inGroupVars)
        }
        val docs = time("buildAll   from group_vars", 1) {
            builder.buildAll("artifact_repo", inGroupVars)
        }

        assertTrue(
            "Ctrl-click on a group_vars variable took ${scopes}ms across " +
                "$inventories inventories x $hostsPerInventory hosts x $playbooks " +
                "playbooks; the per-host sweep this replaced took ~1700ms",
            scopes < ceilingMillis,
        )
        assertTrue(
            "Quick Documentation on a group_vars variable took ${docs}ms",
            docs < ceilingMillis,
        )
    }
}
