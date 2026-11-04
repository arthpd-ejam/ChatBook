package com.chatbook

import com.chatbook.data.extract.PalmDoc
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PalmDocTest {
  @Test
  fun decodesEveryCodeKind() {
    // "ab" literal, then a run of 2 escaped bytes (0x05 0x90), then space+'c' via 0xE3,
    // then a back reference of length 3 at distance 4 copying "b\u0005\u0090".
    val compressed = byteArrayOf(
      'a'.code.toByte(), 'b'.code.toByte(),
      0x02, 0x05, 0x90.toByte(),
      0xE3.toByte(),
      0x80.toByte(), 0x30, // pair value 0x0030 -> distance 6, length 3
    )
    val out = PalmDoc.decompress(compressed)
    val expected = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 0x05, 0x90.toByte(), ' '.code.toByte(), 'c'.code.toByte(),
      'a'.code.toByte(), 'b'.code.toByte(), 0x05)
    assertArrayEquals(expected, out)
  }

  @Test
  fun roundTripsThroughReferenceCompressor() {
    val text = ("The quick brown fox jumps over the lazy dog. " * 40) + "Ünïcödé bytes \u0001\u0007 and nulls\u0000 too. the the the"
    val original = text.toByteArray(Charsets.UTF_8)
    val compressed = TestPalmDocCompressor.compress(original)
    assert(compressed.size < original.size) { "compressor should shrink repetitive text" }
    assertArrayEquals(original, PalmDoc.decompress(compressed))
  }

  @Test
  fun overlappingBackReferenceRepeatsPattern() {
    // "ab" then back reference distance 2 length 6 -> "abababab"
    val compressed = byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 0x80.toByte(), 0x13) // value 0x13: dist 2, len 3+3
    assertEquals("abababab", String(PalmDoc.decompress(compressed), Charsets.ISO_8859_1))
  }
}

private operator fun String.times(n: Int) = repeat(n)

/** Straightforward greedy PalmDOC compressor used only to generate test data. */
object TestPalmDocCompressor {
  fun compress(data: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    val n = data.size
    while (i < n) {
      var bestLen = 0
      var bestDist = 0
      val minStart = maxOf(0, i - 2047)
      var s = i - 1
      while (s >= minStart) {
        var len = 0
        while (len < 10 && i + len < n && data[s + len] == data[i + len]) len++
        if (len > bestLen) { bestLen = len; bestDist = i - s }
        s--
      }
      if (bestLen >= 3) {
        val v = (bestDist shl 3) or (bestLen - 3)
        out.write(0x80 or (v ushr 8))
        out.write(v and 0xFF)
        i += bestLen
        continue
      }
      val b = data[i].toInt() and 0xFF
      if (b == 0x20 && i + 1 < n) {
        val nxt = data[i + 1].toInt() and 0xFF
        if (nxt in 0x40..0x7F) { out.write(nxt xor 0x80); i += 2; continue }
      }
      if (b == 0x00 || b in 0x09..0x7F) { out.write(b); i++; continue }
      var run = 0
      while (run < 8 && i + run < n) {
        val c = data[i + run].toInt() and 0xFF
        if (c in 0x01..0x08 || c >= 0x80) run++ else break
      }
      out.write(run)
      out.write(data, i, run)
      i += run
    }
    return out.toByteArray()
  }
}
