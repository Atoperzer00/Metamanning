package com.metamanning.questlite

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class Yuv420Test {
    @Test fun planarBuffersPackVThenU() {
        val packed = Yuv420.nv21(2, 2, 0, 0, arrayOf(ByteBuffer.wrap(byteArrayOf(1,2,3,4)), ByteBuffer.wrap(byteArrayOf(5)), ByteBuffer.wrap(byteArrayOf(6))), intArrayOf(2,1,1), intArrayOf(1,1,1))
        assertArrayEquals(byteArrayOf(1,2,3,4,6,5), packed)
    }
    @Test fun paddedRowsAndInterleavedChromaHonorPixelStride() {
        val y = ByteBuffer.wrap(byteArrayOf(1,2,0,0,3,4,0,0))
        val u = ByteBuffer.wrap(byteArrayOf(5,99))
        val v = ByteBuffer.wrap(byteArrayOf(6,99))
        assertArrayEquals(byteArrayOf(1,2,3,4,6,5), Yuv420.nv21(2,2,0,0,arrayOf(y,u,v),intArrayOf(4,2,2),intArrayOf(1,2,2)))
    }
    @Test fun nonzeroBufferPositionAndCropAreRespected() {
        val y = ByteBuffer.wrap(byteArrayOf(99,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16)); y.position(1)
        val u = ByteBuffer.wrap(byteArrayOf(21,22,23,24)); val v = ByteBuffer.wrap(byteArrayOf(31,32,33,34))
        assertArrayEquals(byteArrayOf(11,12,15,16,34,24), Yuv420.nv21(2,2,2,2,arrayOf(y,u,v),intArrayOf(4,2,2),intArrayOf(1,1,1)))
    }
    @Test fun invalidAndTruncatedFramesAreRejected() {
        val planes = arrayOf(ByteBuffer.allocate(1), ByteBuffer.allocate(1), ByteBuffer.allocate(1))
        assertTrue(runCatching { Yuv420.nv21(3,2,0,0,planes,intArrayOf(2,1,1),intArrayOf(1,1,1)) }.isFailure)
        assertTrue(runCatching { Yuv420.nv21(4,4,0,0,planes,intArrayOf(4,2,2),intArrayOf(1,1,1)) }.isFailure)
    }
}
