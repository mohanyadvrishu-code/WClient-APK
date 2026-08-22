package com.retrivedmods.wclient.game.registry

import android.content.Context
import org.cloudburstmc.nbt.NBTInputStream
import org.cloudburstmc.nbt.NbtList
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.nbt.NbtType
import org.cloudburstmc.protocol.common.DefinitionRegistry
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

class BlockMapping(
    private val runtimeToGameMap: Map<Int, BlockDefinition>
) : DefinitionRegistry<org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition> {

    private val gameToRuntimeMap = mutableMapOf<BlockDefinition, Int>()

    init {
        runtimeToGameMap.forEach { (k, v) -> gameToRuntimeMap[v] = k }
    }

    override fun getDefinition(runtimeId: Int): BlockDefinition {
        return runtimeToGameMap[runtimeId] ?: UnknownBlockDefinition(runtimeId)
    }

    override fun isRegistered(definition: org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition): Boolean {
        return definition is UnknownBlockDefinition || getDefinition(definition.runtimeId) == definition
    }

    companion object {
        fun read(context: Context, version: Short): BlockMapping {
            // Older builds used a gzipped list named runtime_block_states_*.dat.
            // The current game data uses an uncompressed NBT compound named
            // runtime_blocks_states_*.nbt and stores the list under "blocks".
            val currentPath = "mcpedata/blocks/runtime_blocks_states_$version.nbt"
            val legacyPath = "mcpedata/blocks/runtime_block_states_$version.dat"
            val hasCurrentFormat = currentPath.substringAfterLast('/') in assetPaths(context)
            val stream: InputStream = try {
                context.assets.open(currentPath)
            } catch (_: Exception) {
                context.assets.open(legacyPath)
            }

            stream.use { input ->
                val nbtInput = if (hasCurrentFormat) {
                    NBTInputStream(DataInputStream(input))
                } else {
                    NBTInputStream(DataInputStream(GZIPInputStream(input)))
                }

                val root = nbtInput.readTag()
                val tag: Iterable<NbtMap> = when (root) {
                    is NbtList<*> -> root.filterIsInstance<NbtMap>()
                    is NbtMap -> root.getList("blocks", NbtType.COMPOUND)
                    else -> error("Unsupported block mapping NBT root: ${root?.javaClass?.simpleName}")
                }
                val runtimeToBlock = mutableMapOf<Int, BlockDefinition>()

                tag.forEach { subtag ->
                    val runtime = subtag.getInt("runtimeId")
                    val name = subtag.getString("name")
                    runtimeToBlock[runtime] = BlockDefinition(runtime, name)
                }

                return BlockMapping(runtimeToBlock)
            }
        }

        private fun assetPaths(context: Context): Set<String> =
            context.assets.list("mcpedata/blocks")?.toSet().orEmpty()
    }

}