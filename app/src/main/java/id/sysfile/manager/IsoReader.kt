package id.sysfile.manager

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.BitSet
import java.util.concurrent.atomic.AtomicBoolean

/** Satu entri di dalam berkas ISO 9660. */
data class IsoEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val extentSector: Long
)

/** Pembaca ISO 9660 (ECMA-119 + Joliet UTF-16BE + Rock Ridge NM) read-only.
 *
 *  Dibaca akses-acak lewat RandomAccessFile — citra multi-GB tidak pernah
 *  dimuat utuh ke memori.
 *  - Volume Descriptor mulai di sektor 16 (offset 0x8000, ukuran sektor 2048).
 *  - Primary Volume Descriptor (tipe 1, magic "CD001", versi 1) wajib ada.
 *  - Supplementary Volume Descriptor (tipe 2, magic "CD001") dengan escape
 *    sequence Joliet (`%/\@`, `%/C`, `%/E` di offset 88..119) dipilih bila ada
 *    dan akar direktorinya valid, supaya nama panjang Unicode terbaca utuh.
 *  - Di dalam tiap Directory Record (panjang @0, extAttr @1, extent LBA LE @2..5,
 *    ukuran data LE @10..13, flags @25 bit 1 = direktori, fileIdLen @32,
 *    fileId @33..):
 *    - Bila bukan Joliet dan ada entri Rock Ridge `NM` di System Use Area,
 *      nama diambil dari `NM` (flag 0 = nama lengkap/potongan yang disambung).
 *    - Akhiran versi `;1` (atau `;N`) pada nama berkas ISO 9660 dilucuti,
 *      dan titik tunggal di ujung nama tanpa ekstensi (`README.;1` -> `README`)
 *      dibersihkan.
 */
class IsoReader private constructor(
    private val raf: RandomAccessFile,
    private val fileLength: Long,
    val blockSize: Int,
    private val rootSector: Long,
    private val rootSize: Long,
    val isJoliet: Boolean,
    val volumeLabel: String
) : Closeable {

    companion object {
        const val DEFAULT_SECTOR_SIZE = 2048
        const val SYSTEM_AREA_SECTORS = 16L
        const val MAX_DIR_BYTES = 16L * 1024L * 1024L
        private const val MAX_VD_SCAN = 64

        /** Buka berkas .iso; null bila bukan ISO 9660 valid atau terpotong. */
        fun open(file: File): IsoReader? {
            if (!file.isFile) return null
            val raf = try { RandomAccessFile(file, "r") } catch (_: Exception) { return null }
            return try {
                val reader = openOn(raf)
                if (reader == null) {
                    try { raf.close() } catch (_: Exception) { }
                    null
                } else reader
            } catch (_: Exception) {
                try { raf.close() } catch (_: Exception) { }
                null
            }
        }

        private fun openOn(raf: RandomAccessFile): IsoReader? {
            val len = raf.length()
            val vdStart = SYSTEM_AREA_SECTORS * DEFAULT_SECTOR_SIZE
            if (len < vdStart + DEFAULT_SECTOR_SIZE) return null

            var pvdBlockSize = DEFAULT_SECTOR_SIZE
            var pvdRootSector = -1L
            var pvdRootSize = -1L
            var pvdLabel = ""

            var jolietBlockSize = DEFAULT_SECTOR_SIZE
            var jolietRootSector = -1L
            var jolietRootSize = -1L
            var jolietLabel = ""

            for (i in 0 until MAX_VD_SCAN) {
                val offset = vdStart + i.toLong() * DEFAULT_SECTOR_SIZE
                if (offset + DEFAULT_SECTOR_SIZE > len) break
                val sec = readAt(raf, offset, DEFAULT_SECTOR_SIZE) ?: break
                if (sec[1] != 'C'.code.toByte() ||
                    sec[2] != 'D'.code.toByte() ||
                    sec[3] != '0'.code.toByte() ||
                    sec[4] != '0'.code.toByte() ||
                    sec[5] != '1'.code.toByte()
                ) {
                    if (i == 0) return null
                    break
                }
                val type = sec[0].toInt() and 0xFF
                val version = sec[6].toInt() and 0xFF
                if (type == 255) break // Volume Descriptor Set Terminator
                if (version != 1) continue

                if (type == 1 && pvdRootSector < 0L) {
                    val blk = u16le(sec, 128)
                    if (!isValidBlockSize(blk)) return null
                    val rootRecLen = sec[156].toInt() and 0xFF
                    if (rootRecLen < 34) return null
                    val rootExtent = u32le(sec, 156 + 2)
                    val rootLen = u32le(sec, 156 + 10)
                    if (rootExtent <= 0L || rootLen <= 0L) return null
                    if (rootExtent * blk >= len) return null
                    pvdBlockSize = blk
                    pvdRootSector = rootExtent
                    pvdRootSize = rootLen
                    pvdLabel = String(sec, 40, 32, Charsets.ISO_8859_1).trim()
                } else if (type == 2 && jolietRootSector < 0L && isJolietEscape(sec)) {
                    val blk = u16le(sec, 128)
                    if (isValidBlockSize(blk)) {
                        val rootRecLen = sec[156].toInt() and 0xFF
                        if (rootRecLen >= 34) {
                            val rootExtent = u32le(sec, 156 + 2)
                            val rootLen = u32le(sec, 156 + 10)
                            if (rootExtent > 0L && rootLen > 0L && rootExtent * blk < len) {
                                jolietBlockSize = blk
                                jolietRootSector = rootExtent
                                jolietRootSize = rootLen
                                jolietLabel = decodeUtf16Be(sec, 40, 32).trim()
                            }
                        }
                    }
                }
            }

            if (pvdRootSector < 0L) return null

            val useJoliet = jolietRootSector > 0L
            return IsoReader(
                raf = raf,
                fileLength = len,
                blockSize = if (useJoliet) jolietBlockSize else pvdBlockSize,
                rootSector = if (useJoliet) jolietRootSector else pvdRootSector,
                rootSize = if (useJoliet) jolietRootSize else pvdRootSize,
                isJoliet = useJoliet,
                volumeLabel = if (useJoliet && jolietLabel.isNotEmpty()) jolietLabel else pvdLabel
            )
        }

        private fun isValidBlockSize(blk: Int): Boolean =
            blk in 512..8192 && (blk and (blk - 1)) == 0

        /** Escape sequence Joliet (UCS-2 Level 1/2/3) di byte 88..119 SVD:
         *  `0x25 0x2F 0x40` (`%/@`), `0x25 0x2F 0x43` (`%/C`), atau `0x25 0x2F 0x45` (`%/E`). */
        private fun isJolietEscape(sec: ByteArray): Boolean {
            if (sec[88] != 0x25.toByte() || sec[89] != 0x2F.toByte()) return false
            val b2 = sec[90].toInt() and 0xFF
            return b2 == 0x40 || b2 == 0x43 || b2 == 0x45
        }

        private fun decodeUtf16Be(b: ByteArray, off: Int, len: Int): String {
            val evenLen = len and 1.inv()
            if (evenLen <= 0) return ""
            return String(b, off, evenLen, Charsets.UTF_16BE).trimEnd('\u0000', ' ')
        }

        private fun readAt(raf: RandomAccessFile, offset: Long, size: Int): ByteArray? {
            if (offset < 0L || size < 0) return null
            return try {
                val len = raf.length()
                if (offset > len || size > len - offset) return null
                val buf = ByteArray(size)
                raf.seek(offset)
                var read = 0
                while (read < size) {
                    val n = raf.read(buf, read, size - read)
                    if (n < 0) return null
                    read += n
                }
                buf
            } catch (_: Exception) {
                null
            }
        }

        private fun u16le(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

        private fun u32le(b: ByteArray, off: Int): Long =
            (b[off].toLong() and 0xFF) or
                ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or
                ((b[off + 3].toLong() and 0xFF) shl 24)
    }

    private val lock = Any()

    override fun close() {
        try { raf.close() } catch (_: Exception) { }
    }

    private fun readBytes(offset: Long, size: Int): ByteArray? {
        synchronized(lock) { return readAt(raf, offset, size) }
    }

    /** Daftar isi direktori pada [sector] dengan panjang byte [size]. */
    private fun listDirExtent(sector: Long, size: Long): List<IsoEntry>? {
        if (sector <= 0L || size < 0L || size > MAX_DIR_BYTES) return null
        if (size == 0L) return emptyList()
        val offset = sector * blockSize
        val raw = readBytes(offset, size.toInt()) ?: return null
        val out = mutableListOf<IsoEntry>()
        var pos = 0
        while (pos < raw.size) {
            val recLen = raw[pos].toInt() and 0xFF
            if (recLen == 0) {
                // Directory record tidak melintasi batas sektor: lompat ke sektor berikutnya.
                val nextSector = ((pos / blockSize) + 1) * blockSize
                if (nextSector <= pos) break
                pos = nextSector
                continue
            }
            if (recLen < 34 || pos + recLen > raw.size) return null
            val extAttr = raw[pos + 1].toInt() and 0xFF
            val extent = u32le(raw, pos + 2) + extAttr
            val dataLen = u32le(raw, pos + 10)
            val flags = raw[pos + 25].toInt() and 0xFF
            val isDir = (flags and 0x02) != 0
            val nameLen = raw[pos + 32].toInt() and 0xFF
            if (33 + nameLen > recLen) return null

            // Lewati entri "." (0x00) dan ".." (0x01)
            val isSelfOrParent = nameLen == 1 && (raw[pos + 33].toInt() == 0 || raw[pos + 33].toInt() == 1)
            if (!isSelfOrParent && nameLen > 0) {
                val rawName = parseEntryName(raw, pos, recLen, nameLen, isDir)
                if (rawName != null && isSafeEntryName(rawName)) {
                    val extentOffset = extent * blockSize
                    if (isDir) {
                        if (extent > 0L && extentOffset + dataLen <= fileLength) {
                            out += IsoEntry(rawName, true, dataLen, extent)
                        }
                    } else {
                        if (dataLen == 0L || (extent > 0L && extentOffset + dataLen <= fileLength)) {
                            out += IsoEntry(rawName, false, dataLen, extent)
                        }
                    }
                    if (out.size > FileOps.MAX_ARCHIVE_ENTRIES) return null
                }
            }
            pos += recLen
        }
        return out.sortedWith(
            compareByDescending<IsoEntry> { it.isDir }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun parseEntryName(
        raw: ByteArray,
        pos: Int,
        recLen: Int,
        nameLen: Int,
        isDir: Boolean
    ): String? {
        // Coba Rock Ridge NM di System Use Area bila bukan Joliet
        if (!isJoliet) {
            val suaStart = pos + 33 + nameLen + (if (nameLen % 2 == 0) 1 else 0)
            val suaEnd = pos + recLen
            val rrName = parseRockRidgeName(raw, suaStart, suaEnd)
            if (!rrName.isNullOrEmpty()) return rrName
        }

        val decoded = if (isJoliet) {
            decodeUtf16Be(raw, pos + 33, nameLen)
        } else {
            String(raw, pos + 33, nameLen, Charsets.ISO_8859_1)
        }
        val clean = stripIsoVersion(decoded, isDir)
        return clean.ifEmpty { null }
    }

    /** Baca entri Rock Ridge `NM` (Alternate Name) dari System Use Area bila ada. */
    private fun parseRockRidgeName(raw: ByteArray, start: Int, end: Int): String? {
        var p = start
        var sb: StringBuilder? = null
        while (p + 4 <= end) {
            val c0 = raw[p].toInt() and 0xFF
            val c1 = raw[p + 1].toInt() and 0xFF
            val len = raw[p + 2].toInt() and 0xFF
            val ver = raw[p + 3].toInt() and 0xFF
            if (len < 4 || p + len > end) break
            if (c0 == 'N'.code && c1 == 'M'.code && ver == 1 && len >= 5) {
                val flags = raw[p + 4].toInt() and 0xFF
                // Bit 1 (CURRENT) / Bit 2 (PARENT) tidak punya teks nama
                if ((flags and 0x06) == 0) {
                    val part = String(raw, p + 5, len - 5, Charsets.UTF_8)
                    if (sb == null) sb = StringBuilder()
                    sb.append(part)
                    if ((flags and 0x01) == 0) break // CONTINUE tidak aktif
                }
            }
            p += len
        }
        return sb?.toString()?.takeIf { it.isNotEmpty() }
    }

    /** Bersihkan akhiran versi `;1` dan titik gantung ISO 9660 (`README.;1` -> `README`). */
    private fun stripIsoVersion(raw: String, isDir: Boolean): String {
        var s = raw
        val semi = s.lastIndexOf(';')
        if (semi > 0 && s.substring(semi + 1).all { it.isDigit() }) {
            s = s.substring(0, semi)
        }
        if (!isDir && s.endsWith(".")) {
            s = s.dropLast(1)
        }
        return s
    }

    private fun isSafeEntryName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')

    /** Daftar seluruh isi ISO sebagai daftar datar [ArchiveItem] (untuk ArchiveOps.listContents). */
    fun listAllItems(): List<ArchiveItem>? {
        val result = mutableListOf<ArchiveItem>()
        val visited = BitSet()
        if (!walkList(rootSector, rootSize, "", 0, visited, result)) return null
        return result
    }

    private fun walkList(
        sector: Long,
        size: Long,
        prefix: String,
        depth: Int,
        visited: BitSet,
        out: MutableList<ArchiveItem>
    ): Boolean {
        if (depth > 64 || sector <= 0L || sector > Int.MAX_VALUE) return false
        val secIdx = sector.toInt()
        if (visited.get(secIdx)) return false
        visited.set(secIdx)
        val entries = listDirExtent(sector, size) ?: return false
        for (e in entries) {
            if (out.size >= FileOps.MAX_ARCHIVE_ENTRIES) return false
            val rel = if (prefix.isEmpty()) e.name else "$prefix/${e.name}"
            if (e.isDir) {
                out += ArchiveItem("$rel/", true, 0L)
                if (!walkList(e.extentSector, e.size, rel, depth + 1, visited, out)) return false
            } else {
                out += ArchiveItem(rel, false, e.size)
            }
        }
        return true
    }

    /** Cari entri berdasarkan path relatif di dalam ISO. */
    fun resolveEntry(path: String): IsoEntry? {
        val segments = Ext4Reader.normalizePath(path)
            .split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        var curSector = rootSector
        var curSize = rootSize
        for ((i, seg) in segments.withIndex()) {
            val entries = listDirExtent(curSector, curSize) ?: return null
            val e = entries.firstOrNull { it.name == seg }
                ?: entries.firstOrNull { it.name.equals(seg, ignoreCase = true) }
                ?: return null
            if (i == segments.lastIndex) return e
            if (!e.isDir) return null
            curSector = e.extentSector
            curSize = e.size
        }
        return null
    }

    /** Baca berkas sebagai teks UTF-8 (maks [maxBytes]); null bila biner/terlalu besar/gagal. */
    fun readFileText(path: String, maxBytes: Int = 1_000_000): String? {
        val entry = resolveEntry(path) ?: return null
        if (entry.isDir || entry.size < 0L || entry.size > maxBytes) return null
        if (entry.size == 0L) return ""
        val bytes = readBytes(entry.extentSector * blockSize, entry.size.toInt()) ?: return null
        if (!ImgOps.looksLikeText(bytes)) return null
        return String(bytes, Charsets.UTF_8)
    }

    /** Tulis isi berkas [entry] ke [out] secara streaming (64 KiB) dengan batas [maxBytes]. */
    fun writeEntryTo(
        entry: IsoEntry,
        out: File,
        maxBytes: Long = FileOps.MAX_EXTRACT_BYTES,
        cancelFlag: AtomicBoolean? = null
    ): Boolean {
        if (entry.isDir || entry.size < 0L || entry.size > maxBytes) return false
        val startOffset = entry.extentSector * blockSize
        if (entry.size > 0L && (startOffset < 0L || startOffset + entry.size > fileLength)) return false
        var ok = false
        try {
            CountingOutputStream(FileOutputStream(out), maxBytes).use { cos ->
                var remaining = entry.size
                var pos = startOffset
                val chunk = 64 * 1024
                while (remaining > 0L) {
                    if (cancelFlag?.get() == true) throw ImgCancelledException()
                    val take = minOf(chunk.toLong(), remaining).toInt()
                    val buf = readBytes(pos, take) ?: return@use
                    cos.write(buf, 0, take)
                    pos += take
                    remaining -= take
                }
                ok = remaining == 0L
            }
        } catch (e: ImgCancelledException) {
            out.delete()
            throw e
        } catch (_: Exception) {
            ok = false
        }
        if (!ok) out.delete()
        return ok
    }

    /** Ekstrak seluruh isi ISO ke [destDir] dengan penjaga zip-slip, anggaran 2 GiB, progres, dan batal. */
    fun extractAll(
        destDir: File,
        onEntry: () -> Unit = {},
        cancelFlag: AtomicBoolean? = null
    ): Boolean {
        val budget = ExtractBudget()
        val written = mutableListOf<File>()
        val createdDirs = mutableListOf<File>()
        fun cleanup() {
            written.forEach { it.delete() }
            FileOps.deleteCreatedDirs(createdDirs)
        }
        val visited = BitSet()
        val destCanonical = try {
            destDir.mkdirs()
            destDir.canonicalPath
        } catch (_: Exception) {
            return false
        }
        return try {
            val ok = walkExtract(
                rootSector, rootSize, "", 0, destDir, destCanonical,
                budget, written, createdDirs, visited, onEntry, cancelFlag
            )
            if (!ok) cleanup()
            ok
        } catch (e: ImgCancelledException) {
            cleanup()
            throw e
        } catch (_: Exception) {
            cleanup()
            false
        }
    }

    private fun walkExtract(
        sector: Long,
        size: Long,
        relPath: String,
        depth: Int,
        destDir: File,
        destCanonical: String,
        budget: ExtractBudget,
        written: MutableList<File>,
        createdDirs: MutableList<File>,
        visited: BitSet,
        onEntry: () -> Unit,
        cancelFlag: AtomicBoolean?
    ): Boolean {
        if (depth > 64 || sector <= 0L || sector > Int.MAX_VALUE) return false
        val secIdx = sector.toInt()
        if (visited.get(secIdx)) return false
        visited.set(secIdx)
        val entries = listDirExtent(sector, size) ?: return false
        for (e in entries) {
            if (cancelFlag?.get() == true) throw ImgCancelledException()
            if (!budget.allowEntry()) return false
            if (!isSafeEntryName(e.name)) return false
            val childRel = if (relPath.isEmpty()) e.name else "$relPath/${e.name}"
            val out = File(destDir, childRel)
            val outCanonical = try { out.canonicalPath } catch (_: Exception) { return false }
            if (!FileOps.isPathInside(destCanonical, outCanonical)) return false
            if (e.isDir) {
                FileOps.noteCreatedDir(out, destCanonical, createdDirs)
                out.mkdirs()
                val dirCanon = try { out.canonicalPath } catch (_: Exception) { return false }
                if (!FileOps.isPathInside(destCanonical, dirCanon)) return false
                onEntry()
                if (!walkExtract(
                        e.extentSector, e.size, childRel, depth + 1, destDir,
                        destCanonical, budget, written, createdDirs, visited,
                        onEntry, cancelFlag
                    )
                ) return false
            } else {
                if (e.size < 0L || e.size > budget.remaining()) return false
                val existed = out.exists()
                val parent = out.parentFile ?: destDir
                FileOps.noteCreatedDir(parent, destCanonical, createdDirs)
                parent.mkdirs()
                val parentCanon = try { parent.canonicalPath } catch (_: Exception) { return false }
                if (!FileOps.isPathInside(destCanonical, parentCanon)) return false
                if (!existed) written += out
                if (!writeEntryTo(e, out, budget.remaining(), cancelFlag)) return false
                if (!budget.addBytes(e.size)) return false
                onEntry()
            }
        }
        return true
    }
}
