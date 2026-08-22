package com.retrivedmods.wclient.game.module.visual

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.cloudburstmc.protocol.common.util.VarInts
import com.retrivedmods.wclient.game.registry.BlockMapping

/**
 * Rewrites Bedrock network-runtime subchunk palettes without changing the
 * server-side world. Supports modern subchunk versions 8/9 and the older
 * palettized version 1. Unknown/legacy layouts are left untouched.
 */
internal object XrayChunkRewriter {
    private const val BLOCKS = 4096

    fun rewriteSubChunk(data: ByteBuf, mapping: BlockMapping, keep: (String) -> Boolean): ByteBuf? {
        val input = data.duplicate()
        val output = Unpooled.buffer(input.readableBytes())
        return try {
            if (!rewriteSubChunkBody(input, output, mapping, keep)) {
                output.release()
                null
            } else {
                output.writeBytes(input, input.readerIndex(), input.readableBytes())
                output
            }
        } catch (_: Throwable) {
            output.release()
            null
        }
    }

    fun rewriteLevelChunk(data: ByteBuf, subChunkCount: Int, mapping: BlockMapping, keep: (String) -> Boolean): ByteBuf? {
        if (subChunkCount <= 0) return null
        val input = data.duplicate()
        val output = Unpooled.buffer(input.readableBytes())
        return try {
            repeat(subChunkCount) {
                if (!rewriteSubChunkBody(input, output, mapping, keep)) {
                    output.release()
                    return null
                }
            }
            // Heightmap/biomes/border-blocks/block-entity tail is opaque here;
            // copy it byte-for-byte so we don't damage unrelated chunk data.
            output.writeBytes(input, input.readerIndex(), input.readableBytes())
            output
        } catch (_: Throwable) {
            output.release()
            null
        }
    }

    private fun rewriteSubChunkBody(input: ByteBuf, output: ByteBuf, mapping: BlockMapping, keep: (String) -> Boolean): Boolean {
        if (!input.isReadable) return false
        val version = input.readUnsignedByte()
        if (version != 1 && version != 8 && version != 9) return false

        output.writeByte(version)
        val storageCount = if (version >= 8) input.readUnsignedByte() else 1
        if (storageCount !in 1..8) return false
        output.writeByte(storageCount)
        if (version >= 9) {
            if (!input.isReadable) return false
            output.writeByte(input.readByte().toInt())
        }

        repeat(storageCount) {
            if (!rewriteStorage(input, output, mapping, keep)) return false
        }
        return true
    }

    private fun rewriteStorage(input: ByteBuf, output: ByteBuf, mapping: BlockMapping, keep: (String) -> Boolean): Boolean {
        if (!input.isReadable) return false
        val header = input.readUnsignedByte()
        val runtime = (header and 1) != 0
        val bits = header ushr 1
        if (!runtime || bits !in setOf(0, 1, 2, 3, 4, 5, 6, 8, 16)) return false
        output.writeByte(header)

        val blocksPerWord = if (bits == 0) 0 else 32 / bits
        val wordCount = if (bits == 0) 0 else (BLOCKS + blocksPerWord - 1) / blocksPerWord
        val wordsStart = input.readerIndex()
        val wordBytes = wordCount * 4
        if (input.readableBytes() < wordBytes) return false

        val words = IntArray(wordCount) { input.readIntLE() }
        val paletteSize = VarInts.readUnsignedInt(input)
        if (paletteSize !in 1..4096) return false
        if (input.readableBytes() < paletteSize) return false

        val palette = IntArray(paletteSize) { VarInts.readUnsignedInt(input) }
        val airRuntime = 0
        val newPalette = ArrayList<Int>()
        val paletteMap = IntArray(paletteSize)
        val newIndexByRuntime = HashMap<Int, Int>()

        fun addRuntime(runtimeId: Int): Int {
            return newIndexByRuntime.getOrPut(runtimeId) {
                val idx = newPalette.size
                newPalette.add(runtimeId)
                idx
            }
        }

        addRuntime(airRuntime)
        for (i in palette.indices) {
            val runtimeId = palette[i]
            val definition = mapping.getDefinition(runtimeId)
            val identifier = definition.toString().lowercase()
            val target = if (identifier == "minecraft:air" || keep(identifier)) runtimeId else airRuntime
            paletteMap[i] = addRuntime(target)
        }

        // A zero-bit palette has no packed words; only a single palette entry.
        if (bits == 0) {
            VarInts.writeUnsignedInt(output, 1)
            VarInts.writeUnsignedInt(output, newPalette[paletteMap[0]])
            return true
        }

        // Remap palette indices while preserving the original packing width.
        for (word in words) {
            var rewritten = 0
            for (slot in 0 until blocksPerWord) {
                val shift = slot * bits
                if (shift >= 32) break
                val oldIndex = (word ushr shift) and ((1 shl bits) - 1)
                val mapped = if (oldIndex < paletteMap.size) paletteMap[oldIndex] else 0
                rewritten = rewritten or (mapped shl shift)
            }
            output.writeIntLE(rewritten)
        }

        VarInts.writeUnsignedInt(output, newPalette.size)
        newPalette.forEach { VarInts.writeUnsignedInt(output, it) }
        return true
    }
}
