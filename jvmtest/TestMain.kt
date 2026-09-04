// JVM test harness for the Kotlin port of NszCore.
// Runs the decoder on nszcli's test fixtures and byte-compares against the
// expected .nsp files. Not part of the APK.
//
// Usage: kotlin TestMain <testdata-dir> <out-dir>
package com.biu.nsz

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun entriesOf(path: File): Triple<List<Pair<String, Long>>, ByteArray, Long> {
    val data = path.readBytes()
    val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    val count = bb.getInt(4)
    val strsize = bb.getInt(8)
    val base = 16 + count * 24 + strsize
    val st = data.copyOfRange(16 + count * 24, base)
    val out = ArrayList<Pair<String, Long>>(count)
    for (i in 0 until count) {
        val eb = 16 + i * 24
        val off = ByteBuffer.wrap(data, eb, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val size = ByteBuffer.wrap(data, eb + 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val nameOff = ByteBuffer.wrap(data, eb + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
        var end = nameOff
        while (end < st.size && st[end].toInt() != 0) end++
        out.add(Pair(String(st, nameOff, end - nameOff, Charsets.UTF_8), base.toLong() + off))
    }
    return Triple(out, data, base.toLong())
}

fun main(args: Array<String>) {
    val testdata = File(args[0])
    val outDir = File(args[1]).apply { mkdirs() }

    val cases = testdata.listFiles { f -> f.name.endsWith(".nsz") }!!.sortedBy { it.name }
    var allOk = true
    for (case in cases) {
        val stem = case.nameWithoutExtension
        val outFile = File(outDir, "$stem.nsp")
        val (entries, _, _) = entriesOf(case)

        FileInputStream(case).use { fis ->
            val reader = ChannelReader(fis.channel)
            outFile.outputStream().use { os ->
                NszDecoder.decompress(reader, os)
            }
        }

        // byte-level compare
        val (gotEntries, gotData, _) = entriesOf(outFile)
        val (wantEntries, wantData, _) = entriesOf(File(testdata, "$stem.nsp"))
        val wantMap = wantEntries.associate { (n, o) ->
            n.substringBeforeLast('.') to wantData.copyOfRange(o.toInt(), o.toInt() + 1).let { _ -> 0 }
        }.toMutableMap()
        var ok = true
        for ((n, o) in gotEntries) {
            val size = wantEntries.firstOrNull { it.first == n }?.let { wn ->
                ByteBuffer.wrap(wantData, 16 + wantEntries.indexOfFirst { it.first == n } * 24 + 8, 8)
                    .order(ByteOrder.LITTLE_ENDIAN).long
            } ?: 0L
            val w = wantData.copyOfRange(o.toInt(), o.toInt() + size.toInt())
            val g = gotData.copyOfRange(o.toInt(), o.toInt() + size.toInt())
            // compare against expected by name lookup in the ground truth
            val wOff = wantEntries.firstOrNull { it.first == n }?.second
            if (wOff == null) { println("  FAIL $stem: extra entry $n"); ok = false; continue }
            val expected = wantData.copyOfRange(wOff.toInt(), wOff.toInt() + size.toInt())
            if (!w.contentEquals(expected) || !g.contentEquals(expected)) {
                println("  FAIL $stem: $n byte diff")
                ok = false
            }
        }
        println(if (ok) "  PASS $stem" else "  FAIL $stem")
        allOk = allOk && ok
    }
    println(if (allOk) "===== ALL PASS =====" else "===== FAILURES =====")
    kotlin.system.exitProcess(if (allOk) 0 else 1)
}
