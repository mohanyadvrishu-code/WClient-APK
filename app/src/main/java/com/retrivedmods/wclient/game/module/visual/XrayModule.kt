package com.retrivedmods.wclient.game.module.visual

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket
import org.cloudburstmc.protocol.bedrock.data.SubChunkRequestResult

/**
 * Client-side X-Ray. It rewrites clientbound block updates and modern
 * network-runtime subchunk palettes, leaving the server world untouched.
 * Unknown/legacy payload layouts are deliberately passed through unchanged
 * instead of risking a malformed packet.
 */
class XrayModule : Module("xray", ModuleCategory.Visual) {

    private enum class Mode { Essential, Full }

    private val mode by enumValue("mode", Mode.Essential, Mode::class.java)
    private val oresOnly by boolValue("ores_only", true)
    private val keepWaterAndLava by boolValue("keep_fluids", true)
    private val keepContainers by boolValue("keep_containers", true)

    private val oreTokens = setOf(
        "coal_ore", "iron_ore", "copper_ore", "gold_ore", "redstone_ore",
        "lapis_ore", "diamond_ore", "emerald_ore",
        "deepslate_coal_ore", "deepslate_iron_ore", "deepslate_copper_ore",
        "deepslate_gold_ore", "deepslate_redstone_ore", "deepslate_lapis_ore",
        "deepslate_diamond_ore", "deepslate_emerald_ore",
        "nether_gold_ore", "nether_quartz_ore", "ancient_debris"
    )

    private val fluidTokens = setOf("water", "lava")
    private val containerTokens = setOf(
        "chest", "trapped_chest", "ender_chest", "barrel", "shulker_box",
        "hopper", "dispenser", "dropper", "furnace", "blast_furnace", "smoker"
    )

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated || !::session.isInitialized) return

        when (val packet = interceptablePacket.packet) {
            is UpdateBlockPacket -> {
                val definition = packet.definition ?: return
                val identifier = definition.toString().lowercase()
                if (identifier == "minecraft:air" || identifier == "minecraft:unknown" || shouldKeep(identifier)) return
                packet.definition = session.blockMapping.getDefinition(0)
            }
            is SubChunkPacket -> rewriteSubChunkPacket(packet)
            is LevelChunkPacket -> rewriteLevelChunkPacket(packet)
        }
    }

    private fun rewriteSubChunkPacket(packet: SubChunkPacket) {
        for (subChunk in packet.subChunks) {
            if (subChunk.result != SubChunkRequestResult.SUCCESS) continue
            val data = subChunk.data ?: continue
            val rewritten = XrayChunkRewriter.rewriteSubChunk(data, session.blockMapping, ::shouldKeep) ?: continue
            data.release()
            subChunk.data = rewritten
        }
    }

    private fun rewriteLevelChunkPacket(packet: LevelChunkPacket) {
        if (packet.isRequestSubChunks || packet.subChunksLength <= 0) return
        val data = packet.data
        val rewritten = XrayChunkRewriter.rewriteLevelChunk(data, packet.subChunksLength, session.blockMapping, ::shouldKeep) ?: return
        data.release()
        packet.data = rewritten
    }

    private fun shouldKeep(identifier: String): Boolean {
        val id = identifier.removePrefix("minecraft:")

        if (oresOnly && oreTokens.any { id.contains(it) }) return true
        if (keepWaterAndLava && fluidTokens.any { id == it || id.startsWith("${it}_") }) return true
        if (keepContainers && containerTokens.any { id.contains(it) }) return true

        // Essential mode keeps a few structural blocks visible so caves remain
        // navigable. Full mode intentionally hides every non-kept block.
        if (mode == Mode.Essential) {
            val structural = setOf(
                "stone", "deepslate", "granite", "diorite", "andesite",
                "tuff", "calcite", "dripstone_block", "netherrack",
                "end_stone", "blackstone", "basalt", "dirt", "grass_block",
                "sand", "gravel", "clay"
            )
            return !structural.contains(id)
        }

        return false
    }

    override fun onDisabled() {
        super.onDisabled()
        // Existing chunks are owned by the vanilla client renderer. We do not
        // fabricate a world refresh because doing so without rewriting the
        // version-specific chunk payload can desync the client's world state.
    }
}
