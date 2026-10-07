package com.megamaced.nccollectives.data.api

import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/**
 * D1b: the comparison that decides whether a refused upload had in fact
 * landed. A false "same" records another client's file as this upload; a
 * false "different" is the duplicate the check exists to prevent.
 */
class SameBytesTest {
    @Test
    fun equalContentIsTheSame() {
        assertTrue(sameBytes(source("mine"), source("mine")))
    }

    @Test
    fun twoEmptyInputsAreTheSame() {
        assertTrue(sameBytes(source(""), source("")))
    }

    @Test
    fun aPrefixIsNotTheSame() {
        assertFalse(sameBytes(source("min"), source("mine")))
        assertFalse(sameBytes(source("mine"), source("min")))
    }

    @Test
    fun sameLengthDifferentContentIsNotTheSame() {
        assertFalse(sameBytes(source("mine"), source("mime")))
    }

    @Test
    fun aDifferenceInTheLastByteOfALongInputIsFound() {
        val large = ByteArray(200_000) { (it % 251).toByte() }
        val changed = large.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }

        assertTrue(sameBytes(chunky(large), Buffer().write(large)))
        assertFalse(sameBytes(chunky(large), Buffer().write(changed)))
    }

    private fun source(text: String): BufferedSource = Buffer().writeUtf8(text)

    /**
     * Hands out a few bytes per read, as a network stream does, so the two
     * sides' buffers never line up.
     */
    private fun chunky(bytes: ByteArray): BufferedSource {
        var position = 0
        val stream = object : InputStream() {
            override fun read(): Int = if (position < bytes.size) bytes[position++].toInt() and 0xFF else -1

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int {
                if (position >= bytes.size) return -1
                val n = minOf(len, 777, bytes.size - position)
                bytes.copyInto(b, off, position, position + n)
                position += n
                return n
            }
        }
        return stream.source().buffer()
    }
}
