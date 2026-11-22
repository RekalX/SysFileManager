package id.sysfile.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class IsoReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun putU16Both(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }

    private fun putU32Both(buf: ByteArray, off: Int, v: Long) {
        val x = v.toInt()
        buf[off] = (x and 0xFF).toByte()
        buf[off + 1] = ((x ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((x ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((x ushr 24) and 0xFF).toByte()
        buf[off + 4] = ((x ushr 24) and 0xFF).toByte()
        buf[off + 5] = ((x ushr 16) and 0xFF).toByte()
        buf[off + 6] = ((x ushr 8) and 0xFF).toByte()
        buf[off + 7] = (x and 0xFF).toByte()
    }

    private fun writeDirRecord(
        buf: ByteArray,
        pos: Int,
        extent: Long,
        dataLen: Long,
        isDir: Boolean,
        nameBytes: ByteArray,
        rockRidgeNm: String? = null
    ): Int {
        val suaBytes = if (rockRidgeNm != null) {
            val nm = rockRidgeNm.toByteArray(Charsets.UTF_8)
            val entryLen = 5 + nm.size
            ByteArray(entryLen).apply {
                this[0] = 'N'.code.toByte()
                this[1] = 'M'.code.toByte()
                this[2] = entryLen.toByte()
                this[3] = 1
                this[4] = 0
                nm.copyInto(this, 5)
            }
        } else ByteArray(0)
        val pad = if (nameBytes.size % 2 == 0) 1 else 0
        var recLen = 33 + nameBytes.size + pad + suaBytes.size
        if (recLen % 2 == 1) recLen++
        buf[pos] = recLen.toByte()
        buf[pos + 1] = 0
        putU32Both(buf, pos + 2, extent)
        putU32Both(buf, pos + 10, dataLen)
        buf[pos + 25] = if (isDir) 0x02 else 0x00
        putU16Both(buf, pos + 28, 1)
        buf[pos + 32] = nameBytes.size.toByte()
        nameBytes.copyInto(buf, pos + 33)
        if (suaBytes.isNotEmpty()) {
            suaBytes.copyInto(buf, pos + 33 + nameBytes.size + pad)
        }
        return pos + recLen
    }

    /** Bangun citra ISO 9660 sintetis:
     *  - sektor 16: PVD
     *  - sektor 17: Terminator
     *  - sektor 18: root dir (berisi README.TXT, DOCS/, dan berkas Rock Ridge)
     *  - sektor 19: DOCS dir (berisi GUIDE.MD)
     *  - sektor 20: data README.TXT ("Halo ISO 9660")
     *  - sektor 21: data GUIDE.MD ("# Panduan")
     *  - sektor 22: data berkas Rock Ridge ("isi rock ridge")
     */
    private fun buildSampleIso(file: File) {
        val sector = 2048
        val img = ByteArray(23 * sector)
        val readmeBytes = "Halo ISO 9660".toByteArray(Charsets.UTF_8)
        val guideBytes = "# Panduan".toByteArray(Charsets.UTF_8)
        val rrBytes = "isi rock ridge".toByteArray(Charsets.UTF_8)

        // Sektor 16: Primary Volume Descriptor
        val pvd = 16 * sector
        img[pvd] = 1
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(img, pvd + 1)
        img[pvd + 6] = 1
        val label = "SYSFILE_ISO".padEnd(32, ' ').toByteArray(Charsets.US_ASCII)
        label.copyInto(img, pvd + 40)
        putU32Both(img, pvd + 80, 23L)
        putU16Both(img, pvd + 120, 1)
        putU16Both(img, pvd + 124, 1)
        putU16Both(img, pvd + 128, sector)
        // Root directory record di PVD @156 (34 byte)
        writeDirRecord(img, pvd + 156, 18L, sector.toLong(), true, byteArrayOf(0))

        // Sektor 17: Terminator
        val term = 17 * sector
        img[term] = 255.toByte()
        "CD001".toByteArray(Charsets.US_ASCII).copyInto(img, term + 1)
        img[term + 6] = 1

        // Sektor 18: Root directory
        var p = 18 * sector
        p = writeDirRecord(img, p, 18L, sector.toLong(), true, byteArrayOf(0))
        p = writeDirRecord(img, p, 18L, sector.toLong(), true, byteArrayOf(1))
        p = writeDirRecord(img, p, 20L, readmeBytes.size.toLong(), false, "README.TXT;1".toByteArray(Charsets.US_ASCII))
        p = writeDirRecord(img, p, 19L, sector.toLong(), true, "DOCS".toByteArray(Charsets.US_ASCII))
        writeDirRecord(img, p, 22L, rrBytes.size.toLong(), false, "LONG_F.TXT;1".toByteArray(Charsets.US_ASCII), rockRidgeNm = "long-filename.txt")

        // Sektor 19: DOCS directory
        var p2 = 19 * sector
        p2 = writeDirRecord(img, p2, 19L, sector.toLong(), true, byteArrayOf(0))
        p2 = writeDirRecord(img, p2, 18L, sector.toLong(), true, byteArrayOf(1))
        writeDirRecord(img, p2, 21L, guideBytes.size.toLong(), false, "GUIDE.MD;1".toByteArray(Charsets.US_ASCII))

        // Sektor 20..22: Data berkas
        readmeBytes.copyInto(img, 20 * sector)
        guideBytes.copyInto(img, 21 * sector)
        rrBytes.copyInto(img, 22 * sector)

        file.writeBytes(img)
    }

    @Test
    fun categoryAndKind_recognizesIso() {
        assertEquals(Category.ARCHIVE, FileOps.categoryOf("ubuntu.iso"))
        assertEquals(Category.ARCHIVE, FileOps.categoryOf("GAME.ISO"))
        assertEquals("iso", ArchiveOps.kindOf("ubuntu.iso"))
        assertEquals("iso", ArchiveOps.kindOf("GAME.ISO"))
        assertEquals("ubuntu", ArchiveOps.archiveBaseName("ubuntu.iso"))
        assertTrue(FileOps.isIncompressibleEntry("ubuntu.iso"))
    }

    @Test
    fun listreadAndExtract_iso9660AndRockRidge() {
        val iso = File(tmp.root, "sample.iso")
        buildSampleIso(iso)

        val items = ArchiveOps.listContents(iso)
        assertNotNull(items)
        val names = items!!.map { it.name }
        assertEquals(listOf("DOCS/", "DOCS/GUIDE.MD", "README.TXT", "long-filename.txt"), names)

        assertEquals("Halo ISO 9660", ArchiveOps.readEntryText(iso, "README.TXT"))
        assertEquals("# Panduan", ArchiveOps.readEntryText(iso, "DOCS/GUIDE.MD"))
        assertEquals("isi rock ridge", ArchiveOps.readEntryText(iso, "long-filename.txt"))

        val singleDir = tmp.newFolder("single")
        val extractedOne = ArchiveOps.extractSingle(iso, "DOCS/GUIDE.MD", singleDir)
        assertNotNull(extractedOne)
        assertEquals("# Panduan", extractedOne!!.readText())

        val allDir = tmp.newFolder("all")
        assertTrue(ArchiveOps.extractToFolder(iso, allDir))
        assertEquals("Halo ISO 9660", File(allDir, "sample/README.TXT").readText())
        assertEquals("# Panduan", File(allDir, "sample/DOCS/GUIDE.MD").readText())
        assertEquals("isi rock ridge", File(allDir, "sample/long-filename.txt").readText())
    }

    @Test
    fun invalidFile_returnsNullOrFalse() {
        val bad = File(tmp.root, "bad.iso")
        bad.writeBytes(ByteArray(4096))
        assertNull(IsoReader.open(bad))
        assertNull(ArchiveOps.listContents(bad))
        val out = tmp.newFolder("badout")
        assertFalse(ArchiveOps.extract(bad, out))
    }
}
