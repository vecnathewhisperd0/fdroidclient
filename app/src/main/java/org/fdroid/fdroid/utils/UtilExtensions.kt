/*
 * Copyright (C) 2010-12  Ciaran Gultnieks, ciaran@ciarang.com
 * Copyright (C) 2019 Michael Pöhn, michael.poehn@fsfe.org
 * Copyright (C) 2020 Isira Seneviratne, isirasen96@gmail.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

@file:JvmMultifileClass
@file:JvmName("Utils")

package org.fdroid.fdroid.utils

import android.content.pm.PackageInfo
import android.content.res.Resources
import android.database.Cursor
import android.net.Uri
import android.support.v4.widget.SwipeRefreshLayout
import android.text.format.DateUtils
import android.util.Log
import android.util.TypedValue
import org.fdroid.fdroid.FDroidApp
import org.fdroid.fdroid.Hasher
import org.fdroid.fdroid.Preferences
import org.fdroid.fdroid.R
import org.fdroid.fdroid.compat.FileCompat
import org.fdroid.fdroid.data.Repo
import org.fdroid.fdroid.data.SanitizedFile
import java.io.*
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.cert.Certificate
import java.security.cert.CertificateEncodingException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

private const val BUFFER_SIZE = 4096

private val HEX_LOOKUP_ARRAY = "0123456789ABCDEF".toCharArray()

private val safePackageNamePattern: Pattern by lazy {
    Pattern.compile("[a-zA-Z0-9._]+")
}

val String?.isSafePackageName: Boolean
    get() = this != null && isNotEmpty() && safePackageNamePattern.matcher(this).matches()

val PackageInfo?.packageSig: String
    get() {
        if (this == null || signatures.isNullOrEmpty()) {
            return ""
        }
        var sigHash = ""
        try {
            val hash = Hasher("MD5", signatures[0].toCharsString().toByteArray())
            sigHash = hash.hash
        } catch (e: NoSuchAlgorithmException) {
            // ignore
        }
        return sigHash
    }

val Repo.localRepoUri: Uri
    get() {
        if (address.isNullOrEmpty()) {
            return Uri.parse("http://wifi-not-enabled")
        }
        val uri = Uri.parse(address)
        val b = uri.buildUpon()
        if (!fingerprint.isNullOrEmpty()) {
            b.appendQueryParameter("fingerprint", fingerprint)
        }
        val scheme = if (Preferences.get().isLocalRepoHttpsEnabled) "https" else "http"
        b.scheme(scheme)
        return b.build()
    }

val Repo.sharingUri: Uri
    get() {
        if (address.isNullOrEmpty()) {
            return Uri.parse("http://wifi-not-enabled")
        }
        val b = localRepoUri.buildUpon()
        b.scheme(localRepoUri.scheme!!.replaceFirst("http".toRegex(), "fdroidrepo"))
        b.appendQueryParameter("swap", "1")
        if (!FDroidApp.bssid.isNullOrEmpty()) {
            b.appendQueryParameter("bssid", FDroidApp.bssid)
            if (!FDroidApp.ssid.isNullOrEmpty()) {
                b.appendQueryParameter("ssid", FDroidApp.ssid)
            }
        }
        return b.build()
    }

fun SwipeRefreshLayout.applySwipeLayoutColors() {
    val typedValue = TypedValue()
    context.theme.resolveAttribute(R.attr.colorPrimary, typedValue, true)
    setColorSchemeColors(typedValue.data)
}

fun ByteArray?.calcFingerprint(): String? {
    if (this == null) {
        return null
    }
    if (size < 256) {
        Log.e(TAG, "key was shorter than 256 bytes ($size), cannot be valid!")
        return null
    }
    var ret: String? = null
    try {
        // keytool -list -v gives you the SHA-256 fingerprint
        val digest = MessageDigest.getInstance("sha256")
        digest.update(this)
        val fingerprint = digest.digest()
        val formatter = Formatter(StringBuilder())
        for (aFingerprint in fingerprint) {
            formatter.format("%02X", aFingerprint)
        }
        ret = formatter.toString()
        formatter.close()
    } catch (e: Throwable) { // NOPMD
        Log.w(TAG, "Unable to get certificate fingerprint", e)
    }
    return ret
}

fun Certificate?.calcFingerprint(): String? {
    return if (this == null) {
        null
    } else try {
        encoded.calcFingerprint()
    } catch (e: CertificateEncodingException) {
        null
    }
}

// void is represented as Unit in Kotlin
fun Closeable?.closeQuietly() = if (this == null) Unit else close()

/**
 * Read the input stream until it reaches the end, ignoring any exceptions.
 */
fun InputStream.consumeStream() {
    val buffer = ByteArray(256)
    var read: Int
    do {
        read = read(buffer)
    } while (read != -1)
}

fun InputStream.copy(output: OutputStream) {
    val buffer = ByteArray(BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count == -1) {
            break
        }
        output.write(buffer, 0, count)
    }
    output.flush()
}

fun File.copyQuietly(outFile: File): Boolean {
    var input: InputStream? = null
    var output: OutputStream? = null
    return try {
        input = FileInputStream(this)
        output = FileOutputStream(outFile)
        input.copy(output)
        true
    } catch (e: IOException) {
        Log.e(TAG, "I/O error when copying a file", e)
        false
    } finally {
        output.closeQuietly()
        input.closeQuietly()
    }
}

fun Date.daysSince() = TimeUnit.MILLISECONDS.toDays(Calendar.getInstance().timeInMillis - time).toInt()

/**
 * Formats UTC time into a date string
 */
fun Date?.formatDate(fallback: String?) = formatDateFormat(DATE_FORMAT, this, fallback)

/**
 * Formats UTC time into a date/time string
 */
fun Date?.formatTime(fallback: String?) = formatDateFormat(TIME_FORMAT, this, fallback)

fun Resources.formatLastUpdated(date: Date): String {
    val msDiff = Calendar.getInstance().timeInMillis - date.time
    val days = msDiff / DateUtils.DAY_IN_MILLIS
    val weeks = msDiff / (DateUtils.DAY_IN_MILLIS * 7)
    val months = msDiff / (DateUtils.DAY_IN_MILLIS * 30)
    val years = msDiff / (DateUtils.DAY_IN_MILLIS * 365)

    return when {
        days < 1 -> getString(R.string.details_last_updated_today)
        weeks < 1 -> getQuantityString(R.plurals.details_last_update_days, days.toInt(), days)
        months < 1 -> getQuantityString(R.plurals.details_last_update_weeks, weeks.toInt(), weeks)
        years < 1 -> getQuantityString(R.plurals.details_last_update_months, months.toInt(), months)
        else -> getQuantityString(R.plurals.details_last_update_years, years.toInt(), years)
    }
}

/**
 * Get the checksum hash of the file `apk` using the algorithm in `algo`.
 * `apk` must exist on the filesystem and `algo` must be supported
 * by this device, otherwise an [IllegalArgumentException] is thrown.  This
 * method must be very defensive about checking whether the file exists, since APKs
 * can be uninstalled/deleted in background at any time, even if this is in the
 * middle of running.
 *
 *
 * This also will run into filesystem corruption if the device is having trouble.
 * So hide those so F-Droid does not pop up crash reports about that. As such this
 * exception-message-parsing-and-throwing-a-new-ignorable-exception-hackery is
 * probably warranted. See https://www.gitlab.com/fdroid/fdroidclient/issues/855
 * for more detail.
 */
fun File.getBinaryHash(algorithm: String?): String? {
    var fis: FileInputStream? = null
    try {
        val md = MessageDigest.getInstance(algorithm)
        fis = FileInputStream(this)
        val bis = BufferedInputStream(fis)
        val dataBytes = ByteArray(8192)
        var nread: Int
        while (bis.read(dataBytes).also { nread = it } != -1) { // NOPMD Avoid assignments in operands
            md.update(dataBytes, 0, nread)
        }
        return md.digest().toHexString()?.toLowerCase(Locale.ENGLISH)
    } catch (e: IOException) {
        val message = e.message
        if (message!!.contains("read failed: EIO (I/O error)")) {
            debugLog(TAG, "potential filesystem corruption while accessing $this: $message")
        } else if (message.contains(" ENOENT ")) {
            debugLog(TAG, "$this vanished: $message")
        }
    } catch (e: NoSuchAlgorithmException) {
        throw IllegalArgumentException(e)
    } finally {
        fis.closeQuietly()
    }
    return null
}

fun ByteArray.hashBytes(algorithm: String): String? {
    return try {
        val md = MessageDigest.getInstance(algorithm)
        val hash = md.digest(this).toHexString()
        md.reset()
        hash
    } catch (e: NoSuchAlgorithmException) {
        Log.e(TAG, "Device does not support $algorithm MessageDisgest algorithm")
        null
    }
}

fun String?.parseInt(fallback: Int): Int {
    return if (this == null || isEmpty()) fallback
    else
        try {
            toInt()
        } catch (e: NumberFormatException) {
            fallback
        }
}

fun String?.parseCommaSeparatedString() =
        if (this == null || isEmpty()) null else split(",").toTypedArray()

fun Array<String>?.serializeCommaSeparatedString() =
        if (this == null || isEmpty()) null else joinToString(separator = ",") { it }

/**
 * Computes the base 16 representation of the given byte array and returns the bytes as
 * a string of hexadecimal digits.
 *
 * @see [source](https://stackoverflow.com/a/9855338)
 */
fun ByteArray.toHexString(): String? {
    val hexChars = CharArray(size * 2)
    for (j in this.indices) {
        val v = this[j].toInt() and 0xFF
        hexChars[j * 2] = HEX_LOOKUP_ARRAY[v ushr 4]
        hexChars[j * 2 + 1] = HEX_LOOKUP_ARRAY[v and 0x0F]
    }
    return String(hexChars)
}

/**
 * Attempt to symlink, but if that fails, it will make a copy of the file.
 */
fun SanitizedFile.symlinkOrCopyFileQuietly(outFile: SanitizedFile) =
    FileCompat.symlink(this, outFile) || copyQuietly(outFile)

/**
 * Useful for debugging during development, so that arbitrary queries can be made, and their
 * results inspected in the debugger.
 */
fun Cursor?.dumpCursor(): List<Map<String, String?>>? {
    val data: MutableList<Map<String, String?>> = ArrayList()
    if (this == null) {
        return data
    }
    moveToFirst()
    while (!isAfterLast) {
        val row: MutableMap<String, String?> = HashMap(columnCount)
        for (col in columnNames) {
            val i = getColumnIndex(col)
            when (getType(i)) {
                Cursor.FIELD_TYPE_NULL -> row[col] = null
                Cursor.FIELD_TYPE_INTEGER -> row[col] = getInt(i).toString()
                Cursor.FIELD_TYPE_FLOAT -> row[col] = getFloat(i).toDouble().toString()
                Cursor.FIELD_TYPE_STRING -> row[col] = getString(i)
                Cursor.FIELD_TYPE_BLOB -> row[col] = String(getBlob(i), Charset.defaultCharset())
            }
        }
        data.add(row)
        moveToNext()
    }
    close()
    return data
}
