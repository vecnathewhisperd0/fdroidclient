package org.fdroid.download

import mu.KotlinLogging
import org.fdroid.fdroid.ProgressListener
import org.fdroid.fdroid.isMatching
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

public abstract class Downloader constructor(
    @JvmField
    protected val outputFile: File,
) {

    public companion object {
        private val log = KotlinLogging.logger {}
    }

    protected var fileSize: Long? = null

    /**
     * If not null, this is the expected sha256 hash of the [outputFile] after download.
     */
    protected var sha256: String? = null

    /**
     * If you ask for the cacheTag before calling download(), you will get the
     * same one you passed in (if any). If you call it after download(), you
     * will get the new cacheTag from the server, or null if there was none.
     *
     * If this cacheTag matches that returned by the server, then no download will
     * take place, and a status code of 304 will be returned by download().
     */
    @Deprecated("Used only for v1 repos")
    public var cacheTag: String? = null

    @Volatile
    private var cancelled = false

    @Volatile
    private var progressListener: ProgressListener? = null

    /**
     * Call this to start the download.
     * Never call this more than once. Create a new [Downloader], if you need to download again!
     *
     * @totalSize must be set to what the index tells us the size will be
     * @sha256 must be set to the sha256 hash from the index and only be null for `entry.jar`.
     */
    @Throws(IOException::class, InterruptedException::class)
    public abstract fun download(totalSize: Long, sha256: String? = null)

    /**
     * Call this to start the download.
     * Never call this more than once. Create a new [Downloader], if you need to download again!
     */
    @Deprecated("Use only for v1 repos")
    @Throws(IOException::class, InterruptedException::class)
    public abstract fun download()

    @Throws(IOException::class, NotFoundException::class)
    protected abstract fun getInputStream(resumable: Boolean): InputStream
    protected open suspend fun getBytes(resumable: Boolean, receiver: BytesReceiver) {
        throw NotImplementedError()
    }

    /**
     * Returns the size of the file to be downloaded in bytes.
     * Note this is -1 when the size is unknown.
     * Used only for progress reporting.
     */
    protected abstract fun totalDownloadSize(): Long

    /**
     * After calling [download], this returns true if a new file was downloaded and
     * false if the file on the server has not changed and thus was not downloaded.
     */
    @Deprecated("Only for v1 repos")
    public abstract fun hasChanged(): Boolean
    public abstract fun close()

    public fun setListener(listener: ProgressListener) {
        progressListener = listener
    }

    @Throws(IOException::class, InterruptedException::class)
    protected fun downloadFromStream(isResume: Boolean) {
        log.debug { "Downloading from stream" }
        try {
            FileOutputStream(outputFile, isResume).use { outputStream ->
                getInputStream(isResume).use { input ->
                    // Getting the input stream is slow(ish) for HTTP downloads, so we'll check if
                    // we were interrupted before proceeding to the download.
                    throwExceptionIfInterrupted()
                    copyInputToOutputStream(input, outputStream)
                }
            }
            // Even if we have completely downloaded the file, we should probably respect
            // the wishes of the user who wanted to cancel us.
            throwExceptionIfInterrupted()
        } finally {
            close()
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    @Throws(InterruptedException::class, IOException::class, NoResumeException::class)
    protected suspend fun downloadFromBytesReceiver(isResume: Boolean) {
        try {
            val messageDigest: MessageDigest? = if (sha256 == null) null else {
                MessageDigest.getInstance("SHA-256")
            }
            FileOutputStream(outputFile, isResume).use { outputStream ->
                var bytesCopied = outputFile.length()
                var lastTimeReported = 0L
                val bytesTotal = totalDownloadSize()
                getBytes(isResume) { bytes, numTotalBytes ->
                    // Getting the input stream is slow(ish) for HTTP downloads, so we'll check if
                    // we were interrupted before proceeding to the download.
                    throwExceptionIfInterrupted()
                    outputStream.write(bytes)
                    messageDigest?.update(bytes)
                    bytesCopied += bytes.size
                    val total = if (bytesTotal == -1L) numTotalBytes ?: -1L else bytesTotal
                    lastTimeReported = reportProgress(lastTimeReported, bytesCopied, total)
                }
                // check if expected sha256 hash matches
                sha256?.let { expectedHash ->
                    if (!messageDigest.isMatching(expectedHash)) {
                        throw IOException("Hash not matching")
                    }
                }
                // force progress reporting at the end
                reportProgress(0L, bytesCopied, bytesTotal)
            }
            // Even if we have completely downloaded the file, we should probably respect
            // the wishes of the user who wanted to cancel us.
            throwExceptionIfInterrupted()
        } finally {
            close()
        }
    }

    /**
     * This copies the downloaded data from the [InputStream] to the [OutputStream],
     * keeping track of the number of bytes that have flown through for the [progressListener].
     *
     * Attention: The caller is responsible for closing the streams.
     */
    @Throws(IOException::class, InterruptedException::class)
    private fun copyInputToOutputStream(input: InputStream, output: OutputStream) {
        val messageDigest: MessageDigest? = if (sha256 == null) null else {
            MessageDigest.getInstance("SHA-256")
        }
        try {
            var bytesCopied = outputFile.length()
            var lastTimeReported = 0L
            val bytesTotal = totalDownloadSize()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var numBytes = input.read(buffer)
            while (numBytes >= 0) {
                throwExceptionIfInterrupted()
                output.write(buffer, 0, numBytes)
                messageDigest?.update(buffer, 0, numBytes)
                bytesCopied += numBytes
                lastTimeReported = reportProgress(lastTimeReported, bytesCopied, bytesTotal)
                numBytes = input.read(buffer)
            }
            // check if expected sha256 hash matches
            sha256?.let { expectedHash ->
                if (!messageDigest.isMatching(expectedHash)) {
                    throw IOException("Hash not matching")
                }
            }
            // force progress reporting at the end
            reportProgress(0L, bytesCopied, bytesTotal)
        } finally {
            output.flush()
            progressListener = null
        }
    }

    private fun reportProgress(lastTimeReported: Long, bytesRead: Long, bytesTotal: Long): Long {
        val now = System.currentTimeMillis()
        return if (now - lastTimeReported > 100) {
            log.debug { "onProgress: $bytesRead/$bytesTotal" }
            progressListener?.onProgress(bytesRead, bytesTotal)
            now
        } else {
            lastTimeReported
        }
    }

    /**
     * Cancel a running download, triggering an [InterruptedException]
     */
    public fun cancelDownload() {
        cancelled = true
    }

    /**
     * After every network operation that could take a while, we will check if an
     * interrupt occurred during that blocking operation. The goal is to ensure we
     * don't move onto another slow, network operation if we have cancelled the
     * download.
     *
     * @throws InterruptedException
     */
    @Throws(InterruptedException::class)
    private fun throwExceptionIfInterrupted() {
        if (cancelled) {
            log.info { "Received interrupt, cancelling download" }
            Thread.currentThread().interrupt()
            throw InterruptedException()
        }
    }

}

public fun interface BytesReceiver {
    public suspend fun receive(bytes: ByteArray, numTotalBytes: Long?)
}
