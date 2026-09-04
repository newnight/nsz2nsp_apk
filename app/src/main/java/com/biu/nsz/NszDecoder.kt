package com.biu.nsz

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Kotlin port of nszcli/Sources/NszCore (Ncz.swift + Decompress.swift).
// NSZ -> NSP / NCZ -> NCA decompression, decompression-only (no packing).

class NszException(message: String) : Exception(message)

data class ZeroRun(val start: Long, val end: Long) {
    val length: Long get() = end - start
}

sealed class DecompressEvent {
    data class ScanWarning(val name: String, val runs: List<ZeroRun>) : DecompressEvent()
    data class EntryStart(val name: String) : DecompressEvent()
    data class Progress(val currentName: String, val written: Long, val total: Long) : DecompressEvent()
    data class EntryDone(val name: String, val hash: String, val verified: Boolean) : DecompressEvent()
    data class EntryCopied(val name: String) : DecompressEvent()
    data class Done(val summary: String) : DecompressEvent()
}

data class Pfs0Entry(val name: String, val offset: Long, val size: Long) {
    val isNcz: Boolean get() = name.endsWith(".ncz")
}

object Ncz {
    const val INCOMPRESSIBLE_HEADER_SIZE = 0x4000
    val SECTION_MAGIC = "NCZSECTN".toByteArray(Charsets.US_ASCII)
    val BLOCK_MAGIC = "NCZBLOCK".toByteArray(Charsets.US_ASCII)
}

// ---------------------------------------------------------------------------
// Binary reading helpers over a FileChannel (works for both plain files and
// SAF ParcelFileDescriptors)
// ---------------------------------------------------------------------------

class ChannelReader(val channel: FileChannel) {
    fun seek(pos: Long) {
        channel.position(pos)
    }

    fun position(): Long = channel.position()

    fun readExactly(count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        val bb = ByteBuffer.allocate(count)
        while (bb.hasRemaining()) {
            val n = channel.read(bb)
            if (n < 0) throw NszException("unexpected EOF (wanted $count bytes)")
        }
        return bb.array()
    }
}

private fun leU32(b: ByteArray, off: Int): Long =
    ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

private fun leU64(b: ByteArray, off: Int): Long =
    ByteBuffer.wrap(b, off, 8).order(ByteOrder.LITTLE_ENDIAN).long

// ---------------------------------------------------------------------------
// SHA-256 (incremental)
// ---------------------------------------------------------------------------

class Sha256Context {
    private val md: MessageDigest = MessageDigest.getInstance("SHA-256")
    fun update(data: ByteArray) = md.update(data)
    fun finalHex(): String = md.digest().joinToString("") { "%02x".format(it) }
}

// ---------------------------------------------------------------------------
// AES-CTR re-encryption (replicates nsz's nut.AESCTR semantics)
//
// nsz (pycryptodome): Counter.new(64, prefix=nonce[0:8],
//                                    initial_value=(absoluteOffset >> 4))
// => counter block = nonce[0..<8] || bigEndian(absoluteOffset >> 4)
// The counter is addressed by the ABSOLUTE offset inside the NCA.
// ---------------------------------------------------------------------------

fun ctrEncrypt(key: ByteArray, counter: ByteArray, absoluteOffset: Long, data: ByteArray): ByteArray {
    require(key.size == 16) { "AES-128 key expected, got ${key.size} bytes" }
    val iv = ByteArray(16)
    System.arraycopy(counter, 0, iv, 0, 8)
    val v = absoluteOffset ushr 4
    for (i in 0 until 8) {
        iv[8 + i] = (v ushr ((7 - i) * 8)).toByte()
    }
    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(data)
}

// ---------------------------------------------------------------------------
// NCZ header structures
// ---------------------------------------------------------------------------

data class NczSection(
    val offset: Long,
    val size: Long,
    val cryptoType: Long,
    val cryptoKey: ByteArray = ByteArray(0),
    val cryptoCounter: ByteArray = ByteArray(0)
) {
    val needsCrypto: Boolean get() = cryptoType == 3L || cryptoType == 4L

    companion object {
        const val BYTE_SIZE = 64
        fun from(data: ByteArray, index: Int): NczSection {
            val base = index * BYTE_SIZE
            return NczSection(
                offset = leU64(data, base),
                size = leU64(data, base + 8),
                cryptoType = leU64(data, base + 16),
                cryptoKey = data.copyOfRange(base + 32, base + 48),
                cryptoCounter = data.copyOfRange(base + 48, base + 64)
            )
        }
    }
}

data class NczBlockHeader(
    val blockSizeExponent: Int,
    val decompressedSize: Long,
    val compressedBlockSizeList: List<Int>
) {
    val blockSize: Int get() = 1 shl blockSizeExponent

    companion object {
        fun from(data: ByteArray): NczBlockHeader {
            val exponent = data[11].toInt() and 0xFF
            require(exponent in 14..32) {
                "block size exponent must be 14..32, got $exponent"
            }
            val numberOfBlocks = leU32(data, 12).toInt()
            require(numberOfBlocks >= 0) { "negative block count" }
            val decompressedSize = leU64(data, 16)
            val sizes = ArrayList<Int>(numberOfBlocks)
            for (i in 0 until numberOfBlocks) {
                sizes.add(leU32(data, 24 + i * 4).toInt())
            }
            return NczBlockHeader(exponent, decompressedSize, sizes)
        }
    }
}

// ---------------------------------------------------------------------------
// InputStream over a [start, end) region of a file channel (sequential)
// ---------------------------------------------------------------------------

private class RegionInputStream(
    private val reader: ChannelReader,
    start: Long,
    private val end: Long
) : InputStream() {
    private var pos = start

    override fun read(): Int {
        if (pos >= end) return -1
        val b = ByteArray(1)
        return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= end) return -1
        val want = minOf(len.toLong(), end - pos).toInt()
        reader.seek(pos)
        var done = 0
        while (done < want) {
            val n = reader.channel.read(ByteBuffer.wrap(b, off + done, want - done))
            if (n < 0) {
                if (done == 0) return -1
                break
            }
            done += n
            pos += n
        }
        return done
    }
}

// ---------------------------------------------------------------------------
// Block decompressor (replicates nsz BlockDecompressorReader, sequential mode)
// ---------------------------------------------------------------------------

private class BlockDecompressor(
    private val reader: ChannelReader,
    header: NczBlockHeader,
    streamStart: Long
) {
    private val blockSize = header.blockSize
    private val decompressedSize = header.decompressedSize
    private val compressedSizes = header.compressedBlockSizeList
    private val blockOffsets: List<Long>
    private var position: Long = 0
    private var currentBlockID = -1
    private var currentBlock = ByteArray(0)

    init {
        val offsets = ArrayList<Long>(compressedSizes.size)
        var off = streamStart
        for (s in compressedSizes) {
            offsets.add(off)
            off += s.toLong() and 0xFFFFFFFFL
        }
        blockOffsets = offsets
    }

    private fun loadBlock(id: Int): ByteArray {
        var dSize = blockSize
        if (id == compressedSizes.size - 1) {
            val remainder = decompressedSize % blockSize
            if (remainder > 0) dSize = remainder.toInt()
        }
        val cbs = compressedSizes[id]
        reader.seek(blockOffsets[id])
        val raw = reader.readExactly(cbs)

        return if (cbs < dSize) {
            // independent zstd frame (one-shot decode)
            val declared = runCatching { Zstd.decompressedSize(raw) }.getOrNull() ?: -1L
            val cap = if (declared in 1..dSize.toLong()) declared.toInt() else dSize
            val dst = ByteArray(cap)
            val n = Zstd.decompress(dst, raw)
            if (n != dSize.toLong()) {
                throw NszException("block $id: decoded $n bytes, expected $dSize")
            }
            if (cap == dSize) dst else dst.copyOf(dSize)
        } else {
            // stored in plain text
            raw.copyOf(dSize)
        }
    }

    /** Sequential read of exactly [n] decompressed bytes (or fewer at EOF). */
    fun read(n: Int): ByteArray {
        val out = ByteArrayOutputStream(n)
        while (out.size() < n) {
            val blockID = (position / blockSize).toInt()
            if (blockID >= compressedSizes.size) break
            if (blockID != currentBlockID) {
                currentBlock = loadBlock(blockID)
                currentBlockID = blockID
            }
            val off = (position % blockSize).toInt()
            if (off >= currentBlock.size) break
            val take = minOf(n - out.size(), currentBlock.size - off)
            out.write(currentBlock, off, take)
            position += take
        }
        return out.toByteArray()
    }
}

// ---------------------------------------------------------------------------
// Corruption pre-scan (zero-run detection inside compressed data)
// ---------------------------------------------------------------------------

/** Scans [start, end) for runs of zero bytes >= [threshold] (streaming, 4 MB chunks). */
fun scanZeroRuns(reader: ChannelReader, start: Long, end: Long, threshold: Long = 4096): List<ZeroRun> {
    val runs = ArrayList<ZeroRun>()
    var zeroStart = -1L
    var pos = start
    val chunkSize = 4 shl 20
    val buf = ByteArray(chunkSize)
    while (pos < end) {
        val want = minOf(chunkSize.toLong(), end - pos).toInt()
        reader.seek(pos)
        val chunk = reader.readExactly(want)
        var i = 0
        while (i < want) {
            if (chunk[i] == 0.toByte()) {
                if (zeroStart < 0) zeroStart = pos + i
                while (i < want && chunk[i] == 0.toByte()) i++
            } else {
                val here = pos + i
                while (i < want && chunk[i] != 0.toByte()) i++
                if (zeroStart >= 0 && here - zeroStart >= threshold) {
                    runs.add(ZeroRun(zeroStart, here))
                }
                zeroStart = -1
            }
        }
        pos += want
    }
    if (zeroStart >= 0 && end - zeroStart >= threshold) {
        runs.add(ZeroRun(zeroStart, end))
    }
    return runs
}

/** Byte ranges of an NCZ entry that hold zstd-compressed data (scan target). */
fun nczCompressedRanges(reader: ChannelReader, entryOffset: Long, entrySize: Long): List<LongRange> {
    val entryEnd = entryOffset + entrySize
    reader.seek(entryOffset + Ncz.INCOMPRESSIBLE_HEADER_SIZE)
    val magic = reader.readExactly(8)
    if (!magic.contentEquals(Ncz.SECTION_MAGIC)) throw NszException("Bad magic, expected NCZSECTN - is this really a .ncz file?")
    val count = leU64(reader.readExactly(8), 0).toInt()
    reader.readExactly(count * NczSection.BYTE_SIZE)
    val headerEnd = reader.position()

    val peek = reader.readExactly(8)
    return if (peek.contentEquals(Ncz.BLOCK_MAGIC)) {
        // block mode: scan only zstd-compressed blocks
        reader.seek(headerEnd)
        val head24 = reader.readExactly(24)
        val nBlocks = leU32(head24, 12).toInt()
        var headerData = head24
        if (nBlocks > 0) headerData += reader.readExactly(nBlocks * 4)
        val blockHeader = NczBlockHeader.from(headerData)

        val ranges = ArrayList<LongRange>()
        val bs = blockHeader.blockSize.toLong()
        var off = headerEnd + headerData.size
        for ((id, cbsInt) in blockHeader.compressedBlockSizeList.withIndex()) {
            val cbs = cbsInt.toLong() and 0xFFFFFFFFL
            var dSize = bs
            if (id == blockHeader.compressedBlockSizeList.size - 1) {
                val rem = blockHeader.decompressedSize % bs
                if (rem > 0) dSize = rem
            }
            if (cbs > 0 && cbs < dSize) {
                ranges.add(off until (off + cbs))
            }
            off += cbs
        }
        ranges
    } else {
        // solid mode: everything after the section table is one zstd frame
        listOf(headerEnd until entryEnd)
    }
}

fun scanNczForCorruption(reader: ChannelReader, entryOffset: Long, entrySize: Long): List<ZeroRun> {
    val runs = ArrayList<ZeroRun>()
    for (r in nczCompressedRanges(reader, entryOffset, entrySize)) {
        runs += scanZeroRuns(reader, r.first, r.last + 1)
    }
    return runs
}

// ---------------------------------------------------------------------------
// PFS0 (NSP container)
// ---------------------------------------------------------------------------

fun parsePfs0(reader: ChannelReader): Pair<List<Pfs0Entry>, Long> {
    val header = reader.readExactly(16)
    if (!header.copyOfRange(0, 4).contentEquals("PFS0".toByteArray(Charsets.US_ASCII))) {
        throw NszException("Bad magic, expected PFS0 - is this really a .nsz file?")
    }
    val fileCount = leU32(header, 4).toInt()
    val stringTableSize = leU32(header, 8).toInt()

    val entryTableSize = fileCount * 24
    val rest = reader.readExactly(entryTableSize + stringTableSize)
    val entryTable = rest.copyOfRange(0, entryTableSize)
    val stringTable = rest.copyOfRange(entryTableSize, rest.size)

    // PFS0 spec: entry offsets are relative to the end of the entry+string tables
    val dataStart = 16L + entryTableSize + stringTableSize

    val entries = ArrayList<Pfs0Entry>(fileCount)
    for (i in 0 until fileCount) {
        val base = i * 24
        val offset = leU64(entryTable, base)
        val size = leU64(entryTable, base + 8)
        val nameOffset = leU32(entryTable, base + 16).toInt()
        var nameEnd = nameOffset
        while (nameEnd < stringTable.size && stringTable[nameEnd].toInt() != 0) nameEnd++
        val name = String(stringTable, nameOffset, nameEnd - nameOffset, Charsets.UTF_8)
        entries.add(Pfs0Entry(name, offset + dataStart, size))
    }
    val firstFileOffset = entries.minOfOrNull { it.offset } ?: dataStart
    return Pair(entries, firstFileOffset)
}

// ---------------------------------------------------------------------------
// NCZ decompression core
// ---------------------------------------------------------------------------

/** Parses only the header to compute the decompressed NCA size. */
fun nczDecompressedSize(reader: ChannelReader, entryOffset: Long): Long {
    reader.seek(entryOffset + Ncz.INCOMPRESSIBLE_HEADER_SIZE)
    val magic = reader.readExactly(8)
    if (!magic.contentEquals(Ncz.SECTION_MAGIC)) throw NszException("Bad magic, expected NCZSECTN")
    val count = leU64(reader.readExactly(8), 0).toInt()
    val sectionBytes = reader.readExactly(count * NczSection.BYTE_SIZE)
    val sections = (0 until count).map { NczSection.from(sectionBytes, it) }.toMutableList()
    // official: FakeSection when the first section starts beyond 0x4000
    sections.firstOrNull()?.let { first ->
        if (first.offset > Ncz.INCOMPRESSIBLE_HEADER_SIZE) {
            sections.add(
                0,
                NczSection(
                    Ncz.INCOMPRESSIBLE_HEADER_SIZE.toLong(),
                    first.offset - Ncz.INCOMPRESSIBLE_HEADER_SIZE,
                    1L
                )
            )
        }
    }
    var ncaSize = Ncz.INCOMPRESSIBLE_HEADER_SIZE.toLong()
    for (s in sections) ncaSize += s.size
    return ncaSize
}

/** Decompresses one NCZ entry into [write], returns the SHA-256 hex of the produced NCA. */
fun decompressNcz(
    reader: ChannelReader,
    entryOffset: Long,
    entrySize: Long,
    write: (ByteArray) -> Unit
): String {
    val hash = Sha256Context()

    reader.seek(entryOffset)
    val header = reader.readExactly(Ncz.INCOMPRESSIBLE_HEADER_SIZE)
    write(header)
    hash.update(header)

    val magic = reader.readExactly(8)
    if (!magic.contentEquals(Ncz.SECTION_MAGIC)) throw NszException("Bad magic, expected NCZSECTN")
    val count = leU64(reader.readExactly(8), 0).toInt()
    val sectionBytes = reader.readExactly(count * NczSection.BYTE_SIZE)
    val sections = (0 until count).map { NczSection.from(sectionBytes, it) }.toMutableList()

    sections.firstOrNull()?.let { first ->
        if (first.offset > Ncz.INCOMPRESSIBLE_HEADER_SIZE) {
            sections.add(
                0,
                NczSection(
                    Ncz.INCOMPRESSIBLE_HEADER_SIZE.toLong(),
                    first.offset - Ncz.INCOMPRESSIBLE_HEADER_SIZE,
                    1L
                )
            )
        }
    }

    // block compression or solid zstd?
    val headerEnd = reader.position()
    val blockPeek = reader.readExactly(8)
    reader.seek(headerEnd)
    val useBlock = blockPeek.contentEquals(Ncz.BLOCK_MAGIC)

    var blockReader: BlockDecompressor? = null
    var zstdStream: InputStream? = null
    if (useBlock) {
        reader.seek(headerEnd + 12)
        val numberOfBlocks = leU32(reader.readExactly(4), 0).toInt()
        reader.seek(headerEnd)
        val blockData = reader.readExactly(24 + numberOfBlocks * 4)
        val blockHeader = NczBlockHeader.from(blockData)
        blockReader = BlockDecompressor(reader, blockHeader, headerEnd + blockData.size)
    } else {
        zstdStream = ZstdInputStreamNoFinalizer(RegionInputStream(reader, headerEnd, entryOffset + entrySize))
    }

    var firstSection = true
    try {
        for (s in sections) {
            var i = s.offset
            val end = s.offset + s.size
            val useCrypto = s.needsCrypto

            if (firstSection) {
                firstSection = false
                // official quirk: when the first real section starts *inside* the
                // header region, the compressed stream begins at 0x4000, so skip
                // the already-written part of the section.
                val uncompressed = Ncz.INCOMPRESSIBLE_HEADER_SIZE - sections[0].offset
                if (uncompressed > 0) i += uncompressed
            }

            while (i < end) {
                val chunkSz = minOf(0x10000L, end - i).toInt()
                val chunk: ByteArray = if (blockReader != null) {
                    blockReader.read(chunkSz)
                } else {
                    readUpTo(zstdStream!!, chunkSz)
                }
                if (chunk.isEmpty()) break
                var data = chunk
                if (useCrypto) {
                    data = ctrEncrypt(s.cryptoKey, s.cryptoCounter, i, data)
                }
                write(data)
                hash.update(data)
                i += chunk.size
            }
        }
    } finally {
        zstdStream?.close()
    }
    return hash.finalHex()
}

private fun readUpTo(stream: InputStream, max: Int): ByteArray {
    val out = ByteArray(max)
    var off = 0
    while (off < max) {
        val n = stream.read(out, off, max - off)
        if (n < 0) break
        off += n
    }
    return if (off == max) out else out.copyOf(off)
}

// ---------------------------------------------------------------------------
// NSZ -> NSP decompression (top level)
// ---------------------------------------------------------------------------

object NszDecoder {

    /** Expected output file name for a .nsz/.ncz display name, or null if unsupported. */
    fun defaultOutputName(inputName: String): String? {
        val lower = inputName.lowercase()
        return when {
            lower.endsWith(".nsz") -> inputName.dropLast(4) + ".nsp"
            lower.endsWith(".ncz") -> inputName.dropLast(4) + ".nca"
            else -> null
        }
    }

    fun decompress(
        reader: ChannelReader,
        output: OutputStream,
        skipScan: Boolean = false,
        onEvent: (DecompressEvent) -> Unit = {}
    ) {
        val (entries, _) = parsePfs0(reader)

        // ---- pass 1: register output entries (ncz renamed + resized) ----
        data class OutEntry(val name: String, val size: Long)
        val outEntries = ArrayList<OutEntry>(entries.size)

        for (e in entries) {
            val lower = e.name.lowercase()
            if (lower.endsWith(".xcz") || lower.endsWith(".xci")) {
                throw NszException("XCZ/XCI (HFS0 container) is not supported yet - only NSP/NSZ")
            }
            if (e.isNcz) {
                val ncaSize = nczDecompressedSize(reader, e.offset)
                outEntries.add(OutEntry(e.name.dropLast(4) + ".nca", ncaSize))
            } else {
                outEntries.add(OutEntry(e.name, e.size))
            }
        }

        // ---- corruption pre-scan ----
        var scanWarnings = 0
        if (!skipScan) {
            for (e in entries) {
                if (!e.isNcz) continue
                val runs = scanNczForCorruption(reader, e.offset, e.size)
                if (runs.isEmpty()) continue
                scanWarnings += runs.size
                onEvent(DecompressEvent.ScanWarning(e.name, runs))
            }
        }

        // ---- rebuild PFS0 header ----
        val stringTable = ByteArrayOutputStream()
        val nameOffsets = ArrayList<Int>(outEntries.size)
        for (e in outEntries) {
            nameOffsets.add(stringTable.size())
            stringTable.write(e.name.toByteArray(Charsets.UTF_8))
            stringTable.write(0)
        }

        val headerSize = 16L + 24L * outEntries.size + stringTable.size()
        val dataStart = headerSize

        fun le32(v: Int): ByteArray {
            val b = ByteArray(4)
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(v)
            return b
        }

        fun le64(v: Long): ByteArray {
            val b = ByteArray(8)
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putLong(v)
            return b
        }

        val hdr = ByteArrayOutputStream()
        hdr.write("PFS0".toByteArray(Charsets.US_ASCII))
        hdr.write(le32(outEntries.size))
        hdr.write(le32(stringTable.size()))
        hdr.write(le32(0)) // reserved

        var runningOffset = dataStart
        val outOffsets = ArrayList<Long>(outEntries.size)
        for ((idx, e) in outEntries.withIndex()) {
            outOffsets.add(runningOffset)
            hdr.write(le64(runningOffset - dataStart)) // relative to data start (PFS0 spec)
            hdr.write(le64(e.size))
            hdr.write(le32(nameOffsets[idx]))
            hdr.write(le32(0))
            runningOffset += e.size
        }
        stringTable.writeTo(hdr)

        output.write(hdr.toByteArray())

        // ---- pass 2: write content ----
        val totalBytes = runningOffset - dataStart
        var writtenBytes: Long = 0
        var verified = 0
        var mismatched = 0

        fun verifyName(name: String, hash: String): Boolean {
            val stem = name.substringBeforeLast('.').lowercase()
            if (stem.length == 32) return stem == hash.take(32)
            if (stem.length == 64) return stem == hash
            return true // not hash-named: skip verification
        }

        for ((i, e) in entries.withIndex()) {
            val outName = outEntries[i].name
            onEvent(DecompressEvent.EntryStart(outName))
            if (e.isNcz) {
                val acc = ByteArrayOutputStream()
                val hash = decompressNcz(reader, e.offset, e.size) { chunk ->
                    acc.write(chunk)
                    // stream out in 4 MB pieces to bound memory
                    if (acc.size() >= 4 shl 20) {
                        val bytes = acc.toByteArray()
                        output.write(bytes)
                        writtenBytes += bytes.size
                        onEvent(DecompressEvent.Progress(outName, writtenBytes, totalBytes))
                        acc.reset()
                    }
                }
                if (acc.size() > 0) {
                    val bytes = acc.toByteArray()
                    output.write(bytes)
                    writtenBytes += bytes.size
                    onEvent(DecompressEvent.Progress(outName, writtenBytes, totalBytes))
                }
                val ok = verifyName(outName, hash)
                if (ok) verified++ else mismatched++
                onEvent(DecompressEvent.EntryDone(outName, hash, ok))
            } else {
                // plain entry: chunked copy + optional hash check
                val hash = Sha256Context()
                reader.seek(e.offset)
                var remaining = e.size
                while (remaining > 0) {
                    val want = minOf((4 shl 20).toLong(), remaining).toInt()
                    val chunk = reader.readExactly(want)
                    hash.update(chunk)
                    output.write(chunk)
                    writtenBytes += chunk.size
                    onEvent(DecompressEvent.Progress(outName, writtenBytes, totalBytes))
                    remaining -= chunk.size
                }
                val hex = hash.finalHex()
                // Only .nca entries are hash-named by content; .cert/.tik carry
                // the title id in their hex name, so a hash check is meaningless.
                val ext = e.name.substringAfterLast('.', "").lowercase()
                val stem = e.name.substringBeforeLast('.').lowercase()
                val isHashNamed = ext == "nca" &&
                        (stem.length == 32 || stem.length == 64) &&
                        stem.all { it.isDigit() || it in 'a'..'f' }
                if (isHashNamed) {
                    val ok = verifyName(e.name, hex)
                    if (ok) verified++ else mismatched++
                    onEvent(DecompressEvent.EntryDone(outName, hex, ok))
                } else {
                    onEvent(DecompressEvent.EntryCopied(outName))
                }
            }
        }

        var doneMsg = "Done — ${outEntries.size} files, $verified verified, $mismatched mismatched"
        if (scanWarnings > 0) doneMsg += ", ⚠️ $scanWarnings corruption warning(s) — re-download recommended"
        onEvent(DecompressEvent.Done(doneMsg))
    }
}
