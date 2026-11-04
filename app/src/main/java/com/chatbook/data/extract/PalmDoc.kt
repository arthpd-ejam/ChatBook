package com.chatbook.data.extract

/**
 * PalmDOC (LZ77) decompression, compression type 2 in MOBI/PRC files.
 *
 * Byte codes:
 *  0x00         literal 0x00
 *  0x01..0x08   copy the next N bytes literally
 *  0x09..0x7F   literal byte
 *  0x80..0xBF   two-byte back reference: 11-bit distance, 3-bit (length - 3)
 *  0xC0..0xFF   a space followed by (byte XOR 0x80)
 */
object PalmDoc {
  fun decompress(input: ByteArray): ByteArray {
    var out = ByteArray(maxOf(16, input.size * 3))
    var size = 0

    fun append(b: Int) {
      if (size == out.size) out = out.copyOf(out.size * 2)
      out[size++] = b.toByte()
    }

    var i = 0
    val n = input.size
    while (i < n) {
      val b = input[i].toInt() and 0xFF
      i++
      when {
        b == 0x00 -> append(0)
        b <= 0x08 -> {
          var count = b
          while (count > 0 && i < n) {
            append(input[i].toInt() and 0xFF)
            i++
            count--
          }
        }
        b <= 0x7F -> append(b)
        b <= 0xBF -> {
          if (i >= n) break
          val b2 = input[i].toInt() and 0xFF
          i++
          val pair = ((b and 0x3F) shl 8) or b2
          val distance = pair ushr 3
          val length = (pair and 0x07) + 3
          val start = size - distance
          if (distance == 0 || start < 0) {
            throw UnsupportedBookException("Corrupt PalmDOC data (bad back reference)")
          }
          for (k in 0 until length) append(out[start + k].toInt() and 0xFF)
        }
        else -> {
          append(' '.code)
          append(b xor 0x80)
        }
      }
    }
    return out.copyOf(size)
  }
}
