package org.belos.belmarket.net.bluetooth.httpish.headers;

import org.belos.belmarket.net.bluetooth.FileDetails;

public class ETagHeader extends Header {

    @Override
    public String getName() {
        return "etag";
    }

    public void handle(FileDetails details, String value) {
        details.setCacheTag(value);
    }

}
