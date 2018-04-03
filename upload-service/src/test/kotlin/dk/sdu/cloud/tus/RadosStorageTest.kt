package dk.sdu.cloud.tus

import dk.sdu.cloud.tus.services.IReadChannel
import dk.sdu.cloud.tus.services.ObjectStore
import dk.sdu.cloud.tus.services.UploadService
import dk.sdu.cloud.tus.services.UploadService.Companion.BLOCK_SIZE
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import org.hamcrest.CoreMatchers.hasItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThat
import org.junit.Ignore
import org.junit.Test
import org.slf4j.LoggerFactory

// TODO Tests are currently copy pasted with minor (non-parameter) changes between them
class RadosStorageTest {
    class ByteArrayReadChannel(private val byteArray: ByteArray) : IReadChannel {
        private var pointer = 0
        override suspend fun read(dst: ByteArray, offset: Int): Int {
            val remaining = byteArray.size - pointer
            if (remaining <= 0) return -1

            val copySize = Math.min(remaining, dst.size - offset)
            System.arraycopy(byteArray, pointer, dst, offset, copySize)
            pointer += copySize
            return copySize
        }

        override fun close() {
            // Do nothing
        }
    }

    class DelayedByteArrayReadChannel(byteArray: ByteArray, private val delayPer1MOfDataInMs: Long) : IReadChannel {
        private val delegate = ByteArrayReadChannel(byteArray)

        override suspend fun read(dst: ByteArray, offset: Int): Int {
            val result = delegate.read(dst, offset)
            val sleep = (result / (1024 * 1024).toDouble() * delayPer1MOfDataInMs).toLong()
            log.debug("Read $result bytes. Sleeping for $sleep ms")
            delay(sleep)
            return result
        }

        override fun close() {
            delegate.close()
        }

        companion object {
            private val log = LoggerFactory.getLogger(DelayedByteArrayReadChannel::class.java)
        }
    }

    class CappedAndDelayedByteArrayReadChannel(
        private val byteArray: ByteArray,
        val chunkSize: Int,
        val delayPerChunk: Long
    ) : IReadChannel {
        private var pointer = 0

        override suspend fun read(dst: ByteArray, offset: Int): Int {
            val remaining = byteArray.size - pointer
            if (remaining <= 0) return -1

            val copySize = Math.min(Math.min(remaining, chunkSize), dst.size - offset)
            System.arraycopy(byteArray, pointer, dst, offset, copySize)
            pointer += copySize
            delay(delayPerChunk)
            return copySize
        }

        override fun close() {
            // Do nothing
        }
    }

    @Test
    fun testUploadWithSmallFile() {
        val byteArray = ByteArray(4096) { it.toByte() }
        val readChannel = ByteArrayReadChannel(byteArray)

        val oids = arrayListOf<String>()
        val buffers = arrayListOf<ByteArray>()
        val verified = arrayListOf<Long>()
        val store: ObjectStore = mockk(relaxed = true)

        coEvery { store.write(capture(oids), capture(buffers), any()) } returns Unit

        val upload = UploadService("small-oid", 0, byteArray.size.toLong(), readChannel, store)
        upload.onProgress = { verified += it }
        runBlocking { upload.upload() }

        assertEquals(1, buffers.size)

        val buffer = buffers.first()
        val ourData = buffer.slice(0 until byteArray.size).toList()
        val padding = buffer.slice(byteArray.size until buffer.size).toList()
        val expectedPadding = List(padding.size) { 0.toByte() }

        assertEquals(byteArray.toList(), ourData)
        assertEquals(expectedPadding, padding)
        assertEquals(listOf("small-oid"), oids)
        assertEquals(listOf(0.toLong()), verified)
    }

    @Test
    fun testUploadWithMediumFileOnBlockBoundary() {
        val numBlocks = 32
        val byteArray = ByteArray(BLOCK_SIZE * numBlocks) { it.toByte() }
        val checksum = byteArray.sum()
        val readChannel = ByteArrayReadChannel(byteArray)
        val objectId = "medium-oid"

        val oids = arrayListOf<String>()
        val buffers = arrayListOf<ByteArray>()
        val verified = arrayListOf<Long>()
        val store: ObjectStore = mockk(relaxed = true)

        coEvery { store.write(capture(oids), capture(buffers), any()) } returns Unit

        val upload = UploadService(objectId, 0, byteArray.size.toLong(), readChannel, store)
        upload.onProgress = { verified += it }
        runBlocking { upload.upload() }

        assertEquals(numBlocks, buffers.size)

        val actualSum = buffers.map { it.sum() }.sum()
        assertEquals(checksum, actualSum)

        val expectedOids = (0 until numBlocks).map { if (it == 0) objectId else "$objectId-$it" }.sorted()
        val actualOids = oids.sorted()
        assertEquals(expectedOids, actualOids)

        assertThat(verified, hasItem(numBlocks - 1.toLong()))
    }

    @Test
    fun testUploadWithMediumFileNotOnBlockBoundary() {
        val numBlocks = 16
        val byteArray = ByteArray(BLOCK_SIZE * numBlocks + BLOCK_SIZE / 2) { it.toByte() }
        val checksum = byteArray.sum()
        val readChannel = ByteArrayReadChannel(byteArray)
        val objectId = "medium-oid"

        val oids = arrayListOf<String>()
        val buffers = arrayListOf<ByteArray>()
        val verified = arrayListOf<Long>()
        val store: ObjectStore = mockk(relaxed = true)

        coEvery { store.write(capture(oids), capture(buffers), any()) } returns Unit

        val upload = UploadService(objectId, 0, byteArray.size.toLong(), readChannel, store)
        upload.onProgress = { verified += it }
        runBlocking { upload.upload() }

        assertEquals(numBlocks + 1, buffers.size)

        val actualSum = buffers.map { it.sum() }.sum()
        assertEquals(checksum, actualSum)

        val expectedOids = (0 until numBlocks + 1).map { if (it == 0) objectId else "$objectId-$it" }.sorted()
        val actualOids = oids.sorted()
        assertEquals(expectedOids, actualOids)

        assertThat(verified, hasItem(numBlocks.toLong()))
    }

    @Test
    fun testUploadWithLargeFile() {
        val numBlocks = 128 // 512M
        val byteArray = ByteArray(BLOCK_SIZE * numBlocks) { it.toByte() }
        val checksum = byteArray.sum()
        val readChannel = ByteArrayReadChannel(byteArray)
        val objectId = "medium-oid"

        val oids = arrayListOf<String>()
        val buffers = arrayListOf<ByteArray>()
        val verified = arrayListOf<Long>()
        val store: ObjectStore = mockk(relaxed = true)

        coEvery { store.write(capture(oids), capture(buffers), any()) } returns Unit

        val upload = UploadService(objectId, 0, byteArray.size.toLong(), readChannel, store)
        upload.onProgress = { verified += it }
        runBlocking { upload.upload() }

        assertEquals(numBlocks, buffers.size)

        val actualSum = buffers.map { it.sum() }.sum()
        assertEquals(checksum, actualSum)

        val expectedOids = (0 until numBlocks).map { if (it == 0) objectId else "$objectId-$it" }.sorted()
        val actualOids = oids.sorted()
        assertEquals(expectedOids, actualOids)

        assertThat(verified, hasItem(numBlocks - 1.toLong()))
    }

    @Test
    fun testMediumSlowReadFastWrite() {
        val numBlocks = 64
        val byteArray = ByteArray(BLOCK_SIZE * numBlocks + BLOCK_SIZE / 2) { it.toByte() }
        val checksum = byteArray.sum()
        val readChannel = DelayedByteArrayReadChannel(byteArray, 100)
        val objectId = "medium-oid"

        val oids = arrayListOf<String>()
        val buffers = arrayListOf<ByteArray>()
        val verified = arrayListOf<Long>()
        val store: ObjectStore = mockk(relaxed = true)

        coEvery { store.write(capture(oids), capture(buffers), any()) } returns Unit

        val upload = UploadService(objectId, 0, byteArray.size.toLong(), readChannel, store)
        upload.onProgress = { verified += it }
        runBlocking { upload.upload() }

        assertEquals(numBlocks + 1, buffers.size)

        val actualSum = buffers.map { it.sum() }.sum()
        assertEquals(checksum, actualSum)

        val expectedOids = (0 until numBlocks + 1).map { if (it == 0) objectId else "$objectId-$it" }.sorted()
        val actualOids = oids.sorted()
        assertEquals(expectedOids, actualOids)

        assertThat(verified, hasItem(numBlocks.toLong()))
    }

    @Test
    fun testFastReadSlowWrite() {
        val numBlocks = 64
        val byteArray = ByteArray(BLOCK_SIZE * numBlocks + BLOCK_SIZE / 2) { it.toByte() }
        val checksum = byteArray.sum()
        val readChannel = ByteArrayReadChannel(byteArray)
        val objectId = "medium-oid"

        val oids = arrayListOf<String>()
        val buffers = arrayListOf<ByteArray>()
        val verified = arrayListOf<Long>()
        val store: ObjectStore = mockk(relaxed = true)

        coEvery { store.write(capture(oids), capture(buffers), any()) } coAnswers {
            delay(100)
            Unit
        }

        val upload = UploadService(objectId, 0, byteArray.size.toLong(), readChannel, store)
        upload.onProgress = { verified += it }
        runBlocking { upload.upload() }

        assertEquals(numBlocks + 1, buffers.size)

        val actualSum = buffers.map { it.sum() }.sum()
        assertEquals(checksum, actualSum)

        val expectedOids = (0 until numBlocks + 1).map { if (it == 0) objectId else "$objectId-$it" }.sorted()
        val actualOids = oids.sorted()
        assertEquals(expectedOids, actualOids)

        assertThat(verified, hasItem(numBlocks.toLong()))
    }

    @Test
    @Ignore
    fun testRealisticSlowReadAndWrite() {
        val numBlocks = 64
        val byteArray = ByteArray(BLOCK_SIZE * numBlocks + BLOCK_SIZE / 2) { it.toByte() }
        val checksum = byteArray.sum()
        val readChannel = DelayedByteArrayReadChannel(byteArray, 1000) // 1MB/s. Likely to be slower
        val objectId = "medium-oid"

        val oids = arrayListOf<String>()
        val buffers = arrayListOf<ByteArray>()
        val verified = arrayListOf<Long>()
        val store: ObjectStore = mockk(relaxed = true)

        coEvery { store.write(capture(oids), capture(buffers), any()) } coAnswers {
            delay(500) // 8M/s. This is likely to be a lot faster
            Unit
        }

        val upload = UploadService(objectId, 0, byteArray.size.toLong(), readChannel, store)
        upload.onProgress = { verified += it }
        runBlocking { upload.upload() }

        assertEquals(numBlocks + 1, buffers.size)

        val actualSum = buffers.map { it.sum() }.sum()
        assertEquals(checksum, actualSum)

        val expectedOids = (0 until numBlocks + 1).map { if (it == 0) objectId else "$objectId-$it" }.sorted()
        val actualOids = oids.sorted()
        assertEquals(expectedOids, actualOids)

        assertThat(verified, hasItem(numBlocks.toLong()))
    }

    @Test
    fun testUploadAtNonBlockOffset() {
        val numBlocks = 32
        val byteArray = ByteArray(BLOCK_SIZE * numBlocks) { it.toByte() }
        val checksum = byteArray.sum()
        val readChannel = ByteArrayReadChannel(byteArray)
        val objectId = "medium-oid"

        val oids = arrayListOf<String>()
        val buffers = arrayListOf<ByteArray>()
        val verified = arrayListOf<Long>()
        val store: ObjectStore = mockk(relaxed = true)

        coEvery { store.write(capture(oids), capture(buffers), any()) } returns Unit

        // we add the offset to make read channel work
        val offset = BLOCK_SIZE / 2.toLong()
        val upload = UploadService(
            objectId, offset, byteArray.size.toLong() + offset,
            readChannel, store
        )
        upload.onProgress = { verified += it }
        runBlocking { upload.upload() }

        assertEquals(numBlocks + 1, buffers.size)

        val actualSum = buffers.map { it.sum() }.sum()
        assertEquals(checksum, actualSum)

        val expectedOids = (0 until numBlocks + 1).map { if (it == 0) objectId else "$objectId-$it" }.sorted()
        val actualOids = oids.sorted()
        assertEquals(expectedOids, actualOids)

        assertThat(verified, hasItem(numBlocks.toLong()))
    }

    @Test
    fun testUploadAtBlockBoundary() {
        val numBlocks = 32
        val byteArray = ByteArray(BLOCK_SIZE * numBlocks) { it.toByte() }
        val checksum = byteArray.sum()
        val readChannel = ByteArrayReadChannel(byteArray)
        val objectId = "medium-oid"

        val oids = arrayListOf<String>()
        val buffers = arrayListOf<ByteArray>()
        val verified = arrayListOf<Long>()
        val store: ObjectStore = mockk(relaxed = true)

        coEvery { store.write(capture(oids), capture(buffers), any()) } returns Unit

        val upload = UploadService(
            objectId, BLOCK_SIZE * 4.toLong(), byteArray.size.toLong(),
            readChannel, store
        )
        upload.onProgress = { verified += it }
        runBlocking { upload.upload() }

        assertEquals(numBlocks, buffers.size) // We don't expect any buffers for already allocated

        val actualSum = buffers.map { it.sum() }.sum()
        assertEquals(checksum, actualSum)

        // We expect to start at block 4
        val expectedOids = (4 until numBlocks + 4).map { if (it == 0) objectId else "$objectId-$it" }.sorted()
        val actualOids = oids.sorted()
        assertEquals(expectedOids, actualOids)

        assertThat(verified, hasItem(numBlocks + 3.toLong()))
    }

    @Test
    fun testUploadWithSmallChunkSizeAndNoDelay() {
        val numBlocks = 1
        val byteArray = ByteArray(BLOCK_SIZE * numBlocks) { it.toByte() }
        val checksum = byteArray.sum()
        val readChannel = CappedAndDelayedByteArrayReadChannel(byteArray, chunkSize = 1024, delayPerChunk = 0)
        val objectId = "medium-oid"

        val oids = arrayListOf<String>()
        val buffers = arrayListOf<ByteArray>()
        val verified = arrayListOf<Long>()
        val store: ObjectStore = mockk(relaxed = true)

        coEvery { store.write(capture(oids), capture(buffers), any()) } returns Unit

        val upload = UploadService(objectId, 0, byteArray.size.toLong(), readChannel, store)
        upload.onProgress = { verified += it }
        runBlocking { upload.upload() }

        assertEquals(numBlocks, buffers.size)

        val actualSum = buffers.map { it.sum() }.sum()
        assertEquals(checksum, actualSum)

        val expectedOids = (0 until numBlocks).map { if (it == 0) objectId else "$objectId-$it" }.sorted()
        val actualOids = oids.sorted()
        assertEquals(expectedOids, actualOids)

        assertThat(verified, hasItem(numBlocks - 1.toLong()))
    }
}