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

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.CharacterStyle
import android.text.style.TypefaceSpan
import android.util.Log
import com.nostra13.universalimageloader.core.DisplayImageOptions
import com.nostra13.universalimageloader.core.assist.ImageScaleType
import com.nostra13.universalimageloader.core.display.FadeInBitmapDisplayer
import org.fdroid.fdroid.BuildConfig
import org.fdroid.fdroid.Hasher
import org.fdroid.fdroid.R
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.DateFormat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

const val FALLBACK_ICONS_DIR = "/icons/"

private val UTC = TimeZone.getTimeZone("Etc/GMT")

// The date format used for storing dates (e.g. lastupdated, added) in the database.
internal val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
internal val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH:mm:ss", Locale.ENGLISH)

private val ANDROID_VERSION_NAMES = arrayOf(
        "?",  // 0, undefined
        "1.0",  // 1
        "1.1",  // 2
        "1.5",  // 3
        "1.6",  // 4
        "2.0",  // 5
        "2.0.1",  // 6
        "2.1",  // 7
        "2.2",  // 8
        "2.3",  // 9
        "2.3.3",  // 10
        "3.0",  // 11
        "3.1",  // 12
        "3.2",  // 13
        "4.0",  // 14
        "4.0.3",  // 15
        "4.1",  // 16
        "4.2",  // 17
        "4.3",  // 18
        "4.4",  // 19
        "4.4W",  // 20
        "5.0",  // 21
        "5.1",  // 22
        "6.0",  // 23
        "7.0",  // 24
        "7.1",  // 25
        "8.0",  // 26
        "8.1",  // 27
        "9.0",  // 28
        "10.0")
private val FRIENDLY_SIZE_FORMAT = arrayOf("%.0f B", "%.0f KiB", "%.1f MiB", "%.2f GiB")

val defaultDisplayImageOptionsBuilder: DisplayImageOptions.Builder by lazy {
    DisplayImageOptions.Builder().cacheInMemory(true)
            .cacheOnDisk(true).considerExifParams(false)
            .bitmapConfig(Bitmap.Config.RGB_565).imageScaleType(ImageScaleType.EXACTLY)
}

val repoAppDisplayImageOptions: DisplayImageOptions by lazy {
    defaultDisplayImageOptionsBuilder
            .showImageOnLoading(R.drawable.ic_repo_app_default)
            .showImageForEmptyUri(R.drawable.ic_repo_app_default)
            .showImageOnFail(R.drawable.ic_repo_app_default)
            .displayer(FadeInBitmapDisplayer(200, true, true, false))
            .build()
}

/**
 * Converts a `long` bytes value, like from [File.length], to
 * an `int` value that is kilobytes, suitable for things like
 * [android.widget.ProgressBar.setMax] or
 * [android.support.v4.app.NotificationCompat.Builder.setProgress]
 */
fun bytesToKb(bytes: Long) = (bytes / 1024).toInt()

fun calcFingerprint(keyHexString: String?): String? {
    if (keyHexString.isNullOrEmpty() || keyHexString.matches(".*[^a-fA-F0-9].*".toRegex())) {
        Log.e(TAG, "Signing key certificate was blank or contained a non-hex-digit!")
        return null
    }
    return Hasher.unhex(keyHexString).calcFingerprint()
}

fun canConnectToSocket(host: String?, port: Int): Boolean {
    return try {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), 5)
        socket.close()
        true
    } catch (e: IOException) {
        // Could not connect.
        false
    }
}

fun isServerSocketInUse(port: Int): Boolean {
    return try {
        ServerSocket(port).close()
        false
    } catch (e: IOException) {
        // Could not connect.
        true
    }
}

fun debugLog(tag: String?, msg: String?) {
    if (BuildConfig.DEBUG) {
        Log.d(tag, msg)
    }
}

fun debugLog(tag: String?, msg: String?, tr: Throwable?) {
    if (BuildConfig.DEBUG) {
        Log.d(tag, msg, tr)
    }
}

/**
 * Formats the app name using "sans-serif" and then appends the summary after a space with
 * "sans-serif-light". Doesn't mandate any font sizes or any other styles, that is up to the
 * [android.widget.TextView] which it ends up being displayed in.
 */
fun formatAppNameAndSummary(appName: String, summary: String): CharSequence {
    val toFormat = "$appName $summary"
    val normal: CharacterStyle = TypefaceSpan("sans-serif")
    val light: CharacterStyle = TypefaceSpan("sans-serif-light")
    return SpannableStringBuilder(toFormat).apply {
        setSpan(normal, 0, appName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(light, appName.length, toFormat.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

fun getAndroidVersionName(sdkLevel: Int): String? {
    if (sdkLevel < 0) {
        return ANDROID_VERSION_NAMES[0]
    }
    return if (sdkLevel >= ANDROID_VERSION_NAMES.size) String.format(Locale.ENGLISH, "v%d", sdkLevel)
    else ANDROID_VERSION_NAMES[sdkLevel]
}

fun getFriendlySize(size: Long): String? {
    var s = size.toDouble()
    var i = 0
    while (i < FRIENDLY_SIZE_FORMAT.size - 1 && s >= 1024) {
        s = 100 * s / 1024 / 100.0
        i++
    }
    return String.format(FRIENDLY_SIZE_FORMAT[i], s)
}

fun getLocaleFromAndroidLangTag(languageTag: String?): Locale? {
    if (languageTag.isNullOrEmpty()) {
        return null
    }
    val parts = languageTag.split("-").toTypedArray()
    if (parts.size == 1) {
        return Locale(parts[0])
    }
    if (parts.size == 2) {
        var country = parts[1]
        // Some languages have an "r" before the country as per the values folders, such
        // as "zh-rCN". As far as the Locale class is concerned, the "r" is
        // not helpful, and this should be "zh-CN". Thus, we will
        // strip the "r" when found.
        if (country[0] == 'r' && country.length == 3) {
            country = country.substring(1)
        }
        return Locale(parts[0], country)
    }
    Log.e(TAG, "Locale could not be parsed from language tag: $languageTag")
    return Locale(languageTag)
}

/**
 * Create a standard [PackageManager] [Uri] for pointing to an app.
 */
fun getPackageUri(packageName: String): Uri = Uri.parse("package:$packageName")

/**
 * Converts two `long` bytes values, like from [File.length], to
 * an `int` value that is a percentage, suitable for things like
 * [android.widget.ProgressBar.setMax] or
 * [android.support.v4.app.NotificationCompat.Builder.setProgress].
 * `total` must never be zero!
 */
fun getPercent(current: Long, total: Long) = ((100L * current + total / 2) / total).toInt()

private fun parseDateFormat(format: DateFormat, str: String?, fallback: Date?): Date? {
    if (str.isNullOrEmpty()) {
        return fallback
    }
    var result: Date?
    try {
        format.timeZone = UTC
        result = format.parse(str)
    } catch (e: ArrayIndexOutOfBoundsException) {
        e.printStackTrace()
        result = fallback
    } catch (e: NumberFormatException) {
        e.printStackTrace()
        result = fallback
    } catch (e: ParseException) {
        e.printStackTrace()
        result = fallback
    }
    return result
}

internal fun formatDateFormat(format: DateFormat, date: Date?, fallback: String?): String? {
    if (date == null) {
        return fallback
    }
    format.timeZone = UTC
    return format.format(date)
}

/**
 * Parses a date string into UTC time
 */
fun parseDate(str: String?, fallback: Date?) = parseDateFormat(DATE_FORMAT, str, fallback)

/**
 * Parses a date/time string into UTC time
 */
fun parseTime(str: String?, fallback: Date?) = parseDateFormat(TIME_FORMAT, str, fallback)
