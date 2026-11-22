package com.valentinlarge.courrier

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.util.Base64
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Les vignettes de la galerie, fabriquées en téléchargeant le moins possible :
 * - une petite image est prise entière ;
 * - une photo porte presque toujours une miniature d'elle-même dans ses premiers octets : on ne
 *   demande au serveur que ce début ;
 * - le reste (grandes images sans miniature) n'est téléchargé qu'en Wi-Fi.
 */
object Thumbs {

    /** Jusqu'à cette taille, une image est téléchargée entière, quel que soit le réseau. */
    @Volatile
    var smallBytes: Long = 600L * 1024

    /** Au-delà, on ne télécharge jamais une image pour une simple vignette. */
    const val BIG_BYTES: Long = 16L * 1024 * 1024

    /** Longueur du début de photo demandé au serveur : la miniature tient dans les 64 premiers kilo-octets du fichier. */
    const val HEAD_BYTES: Int = 96 * 1024

    /** Le petit côté d'une vignette, en points d'écran : de quoi rester nette dans son carré. */
    private const val SHORT_SIDE = 192
    private const val LONG_SIDE = 576

    /** Vrai pour une photo JPEG, la seule sorte d'image qui range sa miniature au début du fichier. */
    fun isPhoto(attachment: Attachment): Boolean {
        val extension = attachment.name.substringAfterLast('.', "").lowercase()
        val mime = attachment.mime.substringBefore(';').trim().lowercase()
        return mime == "image/jpeg" || mime == "image/jpg" || extension == "jpg" || extension == "jpeg"
    }

    /** Décode le début d'une pièce tel que le serveur l'a envoyé ; null si son codage ne permet pas d'en lire un morceau. */
    fun decodeHead(raw: ByteArray, encoding: String): ByteArray? = when (encoding) {
        "base64" -> {
            // Seuls comptent les caractères de l'alphabet ; le dernier groupe, peut-être coupé, est laissé de côté.
            val clean = ByteArray(raw.size)
            var count = 0
            for (byte in raw) {
                val c = byte.toInt().toChar()
                if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/') clean[count++] = byte
            }
            try {
                Base64.decode(clean, 0, count - count % 4, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        "", "identity", "binary", "8bit", "7bit" -> raw
        else -> null
    }

    private fun u8(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 0xFF

    private fun u16(bytes: ByteArray, at: Int, big: Boolean): Int =
        if (big) (u8(bytes, at) shl 8) or u8(bytes, at + 1) else (u8(bytes, at + 1) shl 8) or u8(bytes, at)

    private fun u32(bytes: ByteArray, at: Int, big: Boolean): Long =
        if (big) (u16(bytes, at, true).toLong() shl 16) or u16(bytes, at + 2, true).toLong()
        else (u16(bytes, at + 2, false).toLong() shl 16) or u16(bytes, at, false).toLong()

    /** Ce que les renseignements d'une photo disent d'utile ici : son sens, et où se trouve sa miniature. */
    private class Exif(val orientation: Int, val thumbStart: Int, val thumbLength: Int)

    /** Parcourt les en-têtes d'un JPEG jusqu'aux renseignements de l'appareil photo ; null s'ils manquent ou sont coupés. */
    private fun exifOf(bytes: ByteArray): Exif? {
        if (bytes.size < 4 || u8(bytes, 0) != 0xFF || u8(bytes, 1) != 0xD8) return null
        var at = 2
        while (at + 4 <= bytes.size) {
            if (u8(bytes, at) != 0xFF) return null
            val marker = u8(bytes, at + 1)
            if (marker == 0xFF) {
                at++ // octet de remplissage
                continue
            }
            // Début de l'image elle-même, ou fin du fichier : les renseignements n'y sont pas.
            if (marker == 0xDA || marker == 0xD9) return null
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                at += 2 // marqueur sans contenu
                continue
            }
            val length = u16(bytes, at + 2, true)
            if (length < 2) return null
            val end = at + 2 + length
            val start = at + 4
            if (marker == 0xE1 && length >= 16 && end <= bytes.size && isExifHeader(bytes, start)) {
                return readTiff(bytes, start + 6, end)
            }
            at = end
        }
        return null
    }

    private fun isExifHeader(bytes: ByteArray, at: Int): Boolean =
        bytes[at] == 'E'.code.toByte() && bytes[at + 1] == 'x'.code.toByte() && bytes[at + 2] == 'i'.code.toByte() &&
            bytes[at + 3] == 'f'.code.toByte() && bytes[at + 4].toInt() == 0 && bytes[at + 5].toInt() == 0

    /** Lit les deux tables utiles : la première donne le sens de l'image, la seconde l'emplacement de la miniature. */
    private fun readTiff(bytes: ByteArray, tiff: Int, end: Int): Exif? {
        if (tiff + 8 > end) return null
        val big = when {
            bytes[tiff] == 'M'.code.toByte() && bytes[tiff + 1] == 'M'.code.toByte() -> true
            bytes[tiff] == 'I'.code.toByte() && bytes[tiff + 1] == 'I'.code.toByte() -> false
            else -> return null
        }
        if (u16(bytes, tiff + 2, big) != 42) return null

        // Une table : un nombre d'entrées, les entrées (12 octets chacune), puis la place de la table suivante.
        fun entries(table: Long): Pair<Int, Int>? {
            if (table < 8 || tiff + table + 2 > end) return null
            val first = (tiff + table).toInt()
            val count = u16(bytes, first, big)
            if (first + 2 + count * 12 + 4 > end) return null
            return first + 2 to count
        }

        fun value(entry: Int): Long = if (u16(bytes, entry + 2, big) == 3) u16(bytes, entry + 8, big).toLong() else u32(bytes, entry + 8, big)

        val (first0, count0) = entries(u32(bytes, tiff + 4, big)) ?: return null
        var orientation = 1
        for (i in 0 until count0) {
            val entry = first0 + i * 12
            if (u16(bytes, entry, big) == 0x0112) orientation = u16(bytes, entry + 8, big)
        }

        var thumbStart = 0
        var thumbLength = 0
        val next = u32(bytes, first0 + count0 * 12, big)
        val second = if (next == 0L) null else entries(next)
        if (second != null) {
            var offset = 0L
            var length = 0L
            for (i in 0 until second.second) {
                val entry = second.first + i * 12
                when (u16(bytes, entry, big)) {
                    0x0201 -> offset = value(entry)
                    0x0202 -> length = value(entry)
                }
            }
            if (offset > 0 && length > 0 && tiff + offset + length <= end) {
                thumbStart = (tiff + offset).toInt()
                thumbLength = length.toInt()
            }
        }
        return Exif(if (orientation in 1..8) orientation else 1, thumbStart, thumbLength)
    }

    /** La miniature rangée au début d'une photo, redressée ; null s'il n'y en a pas dans ces octets. */
    fun exifThumbnail(head: ByteArray): Bitmap? {
        val exif = exifOf(head) ?: return null
        if (exif.thumbLength <= 0) return null
        val bitmap = BitmapFactory.decodeByteArray(head, exif.thumbStart, exif.thumbLength) ?: return null
        return upright(bitmap, exif.orientation)
    }

    /** Une image entière, lue en réduction (sans la charger en grand) et redressée ; null si l'appareil ne sait pas la lire. */
    fun fromFile(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SHORT_SIDE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // Le sens d'une photo est écrit dans ses premiers octets.
        val head = ByteArray(min(file.length(), 128L * 1024).toInt())
        val read = file.inputStream().use { input ->
            var total = 0
            while (total < head.size) {
                val n = input.read(head, total, head.size - total)
                if (n < 0) break
                total += n
            }
            total
        }
        val orientation = exifOf(if (read == head.size) head else head.copyOf(read))?.orientation ?: 1
        return upright(bitmap, orientation)
    }

    /** Remet une image dans le sens où elle doit être regardée. */
    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            6 -> matrix.setRotate(90f)
            7 -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            8 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Enregistre la vignette : réduite à sa taille d'affichage, sur fond blanc, en JPEG. */
    fun save(bitmap: Bitmap, target: File) {
        var scale = SHORT_SIDE.toFloat() / min(bitmap.width, bitmap.height)
        if (max(bitmap.width, bitmap.height) * scale > LONG_SIDE) scale = LONG_SIDE.toFloat() / max(bitmap.width, bitmap.height)
        val width = if (scale < 1f) max(1, (bitmap.width * scale).roundToInt()) else bitmap.width
        val height = if (scale < 1f) max(1, (bitmap.height * scale).roundToInt()) else bitmap.height
        // Une image à fond transparent deviendrait noire en JPEG : elle est posée sur du blanc.
        val flat = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(Color.WHITE)
        val scaled = if (width != bitmap.width || height != bitmap.height) Bitmap.createScaledBitmap(bitmap, width, height, true) else bitmap
        canvas.drawBitmap(scaled, 0f, 0f, null)

        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        temp.outputStream().use { flat.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }
}
