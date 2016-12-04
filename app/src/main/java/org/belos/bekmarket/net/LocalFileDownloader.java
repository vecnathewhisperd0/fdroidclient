package org.belos.belmarket.net;

import org.belos.belmarket.Utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;

public class LocalFileDownloader extends Downloader {

    private InputStream inputStream;

    LocalFileDownloader(URL url, File destFile) throws FileNotFoundException, MalformedURLException {
        super(url, destFile);
    }

    @Override
    protected InputStream getDownloadersInputStream() throws IOException {
        inputStream = new FileInputStream(new File(sourceUrl.getPath()));
        return inputStream;
    }

    @Override
    protected void close() {
        if (inputStream != null) {
            Utils.closeQuietly(inputStream);
        }
    }

    @Override
    public boolean hasChanged() {
        return false;
    }

    @Override
    public int totalDownloadSize() {
        return 0;
    }

    @Override
    public void download() throws IOException, InterruptedException {
        downloadFromStream(1024 * 50, false);
    }

    @Override
    public boolean isCached() {
        return false;
    }
}
