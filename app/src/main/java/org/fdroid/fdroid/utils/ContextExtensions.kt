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

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Log
import android.util.TypedValue
import android.widget.Toast
import com.nostra13.universalimageloader.utils.StorageUtils
import org.fdroid.fdroid.R
import java.io.File

const val TAG = "Utils"

private val toastHandler: Handler by lazy {
    Handler(Looper.getMainLooper())
}

/**
 * Returns the directory where cached icons/feature graphics/screenshots are stored
 */
val Context.imageCacheDir: File
    get() = File(StorageUtils.getCacheDirectory(applicationContext, true), "icons")

val Context.imageCacheDirAvailableMemory: Long
    get() {
        var statDir = imageCacheDir
        while (!statDir.exists()) {
            statDir = statDir.parentFile
        }
        val stat = StatFs(statDir.path)
        return if (Build.VERSION.SDK_INT < 18) stat.availableBlocks.toLong() * stat.blockSize.toLong()
        else stat.availableBlocksLong * stat.blockSizeLong
    }

val Context.imageCacheDirTotalMemory: Long
    get() {
        var statDir = imageCacheDir
        while (!statDir.exists()) {
            statDir = statDir.parentFile
        }
        val stat = StatFs(statDir.path)
        return if (Build.VERSION.SDK_INT < 18) stat.blockCount.toLong() * stat.blockSize.toLong()
        else stat.blockCountLong * stat.blockSizeLong
    }

/**
 * Tries to get the [PackageInfo.versionName] of the client, returning `null` on failure.
 */
val Context.versionName: String?
    get() {
        var versionName: String? = null
        try {
            versionName = packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Could not get client version name", e)
        }
        return versionName
    }

fun Context.dpToPx(dp: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

fun Context.formatFingerprint(fingerprint: String?): String? {
    if (fingerprint.isNullOrEmpty() || fingerprint.length != 64 // SHA-256 is 64 hex chars
            || fingerprint.matches(".*[^0-9a-fA-F].*".toRegex())) { // its a hex string
        return getString(R.string.bad_fingerprint)
    }
    val displayFP = StringBuilder(fingerprint.substring(0, 2))
    var i = 2
    while (i < fingerprint.length) {
        displayFP.append(" ").append(fingerprint.substring(i, i + 2))
        i += 2
    }
    return displayFP.toString()
}

fun Context.getIconsDir(dpiMultiplier: Double): String {
    val dpi: Double = resources.displayMetrics.densityDpi * dpiMultiplier
    return when {
        dpi >= 640 -> "/icons-640/"
        dpi >= 480 -> "/icons-480/"
        dpi >= 320 -> "/icons-320/"
        dpi >= 240 -> "/icons-240/"
        dpi >= 160 -> "/icons-160/"
        else -> "/icons-120/"
    }
}

/**
 * Tries to get the [PackageInfo] for the `packageName` provided, returning `null` on failure.
 */
fun Context.getPackageInfo(packageName: String): PackageInfo? {
    try {
        return packageManager.getPackageInfo(packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        debugLog(TAG, "Could not get PackageInfo: ", e)
    }
    return null
}

/**
 * In order to send a [Toast] from a [android.app.Service], we have to do these tricks.
 */
fun Context.showToastFromService(msg: String?, length: Int) {
    toastHandler.post { Toast.makeText(applicationContext, msg, length).show() }
}
