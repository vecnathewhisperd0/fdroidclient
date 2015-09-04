package org.fdroid.fdroid.net;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import org.fdroid.fdroid.ProgressListener;

import java.io.IOException;

/**
 * Given a {@link org.fdroid.fdroid.net.Downloader}, this wrapper will conduct the download operation on a
 * separate thread. All progress/status/error/etc events will be forwarded from that thread to the thread
 * that {@link AsyncDownloadWrapper#download()} was invoked on. If you want to respond with UI feedback
 * to these events, it is important that you execute the download method of this class from the UI thread.
 * That way, all forwarded events will be handled on that thread.
 */
@SuppressWarnings("serial")
public class AsyncDownloadWrapper extends Handler {

    private static final String TAG = "AsyncDownloadWrapper";

    private static final int MSG_DOWNLOAD_COMPLETE  = 2;
    private static final int MSG_DOWNLOAD_CANCELLED = 3;
    private static final int MSG_ERROR              = 4;
    private static final String MSG_DATA            = "data";

    private final Downloader downloader;
    private final Listener listener;
    private DownloadThread downloadThread = null;

    /**
     * Normally the listener would be provided using a setListener method.
     * However for the purposes of this async downloader, it doesn't make
     * sense to have an async task without any way to notify the outside
     * world about completion. Therefore, we require the listener as a
     * parameter to the constructor.
     */
    public AsyncDownloadWrapper(Downloader downloader, Listener listener) {
        this.downloader = downloader;
        this.listener   = listener;
    }

    public void download() {
        downloadThread = new DownloadThread();
        downloadThread.start();
    }

    public void attemptCancel(boolean userRequested) {
        if (downloadThread != null) {
            downloadThread.interrupt();
        }
    }

    /**
     * Receives "messages" from the download thread, and passes them onto the
     * relevant {@link org.fdroid.fdroid.net.AsyncDownloadWrapper.Listener}
     * @param message
     */
    public void handleMessage(Message message) {
        switch (message.arg1) {
        case MSG_DOWNLOAD_COMPLETE:
            listener.onDownloadComplete();
            break;
        case MSG_DOWNLOAD_CANCELLED:
            listener.onDownloadCancelled();
            break;
        case MSG_ERROR:
            listener.onErrorDownloading(message.getData().getString(MSG_DATA));
            break;
        }
    }

    public int getBytesRead() {
        return downloader.getBytesRead();
    }

    public int getTotalBytes() {
        return downloader.getTotalBytes();
    }

    public interface Listener extends ProgressListener {
        void onErrorDownloading(String localisedExceptionDetails);
        void onDownloadComplete();
        void onDownloadCancelled();
    }

    private class DownloadThread extends Thread {

        public void run() {
            try {
                downloader.download();
                sendMessage(MSG_DOWNLOAD_COMPLETE);
            } catch (InterruptedException e) {
                sendMessage(MSG_DOWNLOAD_CANCELLED);
            } catch (IOException e) {
                Log.e(TAG, "I/O exception in download thread", e);
                Bundle data = new Bundle(1);
                data.putString(MSG_DATA, e.getLocalizedMessage());
                Message message = new Message();
                message.arg1 = MSG_ERROR;
                message.setData(data);
                AsyncDownloadWrapper.this.sendMessage(message);
            }
        }

        private void sendMessage(int messageType) {
            Message message = new Message();
            message.arg1 = messageType;
            AsyncDownloadWrapper.this.sendMessage(message);
        }
    }
}
