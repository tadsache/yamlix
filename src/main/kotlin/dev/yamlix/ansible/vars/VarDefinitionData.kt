package dev.yamlix.ansible.vars

import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.IOUtil
import java.io.DataInput
import java.io.DataOutput

/**
 * One definition site, as stored in the file-based index.
 *
 * ### Why there is no `file` field
 *
 * The brief asks for a `VarDefinition` carrying the file. A `VirtualFile` cannot
 * be serialised into an index value, and it does not need to be: `FileBasedIndex`
 * hands the containing file back at query time. So the *stored* record is
 * file-free and [VarSite] — the runtime type the resolution service returns — is
 * hydrated with the file. Same information, split where the platform splits it.
 *
 * ### Why the scope is path-derived
 *
 * Index values must be a pure function of a file's content and path; they are
 * shared across projects and must not depend on project configuration such as
 * `roles_path`. So the indexer classifies by path *shape*
 * (`…/defaults/main.yml` next to a `tasks/` sibling is role defaults) and the
 * project-specific question — is this role even in the play — is answered later,
 * by the resolution service.
 */
data class VarDefinitionData(
    /** Offset of the defining key within the file. */
    val offset: Int,
    val scope: VarScope,
    /** Group name, host name or role name, depending on [scope]. */
    val qualifier: String,
    /** The literal value as written, or null when it is not a scalar. */
    val valueText: String?,
    /** The `when:` expression guarding this definition, if any. */
    val guard: String?,
) {
    val isConditional: Boolean get() = guard != null
}

/**
 * Length-prefixed record format. Explicit and boring on purpose — a subtle
 * change here without a [AnsibleVarIndex.VERSION] bump corrupts every user's
 * index.
 */
object VarDefinitionExternalizer : DataExternalizer<List<VarDefinitionData>> {

    override fun save(out: DataOutput, value: List<VarDefinitionData>) {
        out.writeInt(value.size)
        for (definition in value) {
            out.writeInt(definition.offset)
            out.writeInt(definition.scope.ordinal)
            IOUtil.writeUTF(out, definition.qualifier)
            out.writeBoolean(definition.valueText != null)
            definition.valueText?.let { IOUtil.writeUTF(out, it) }
            out.writeBoolean(definition.guard != null)
            definition.guard?.let { IOUtil.writeUTF(out, it) }
        }
    }

    override fun read(input: DataInput): List<VarDefinitionData> {
        val size = input.readInt()
        val scopes = VarScope.entries
        val result = ArrayList<VarDefinitionData>(size)
        repeat(size) {
            val offset = input.readInt()
            val scope = scopes[input.readInt()]
            val qualifier = IOUtil.readUTF(input)
            val valueText = if (input.readBoolean()) IOUtil.readUTF(input) else null
            val guard = if (input.readBoolean()) IOUtil.readUTF(input) else null
            result += VarDefinitionData(offset, scope, qualifier, valueText, guard)
        }
        return result
    }
}
