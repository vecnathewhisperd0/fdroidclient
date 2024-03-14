package org.fdroid.fdroid.net;

import androidx.annotation.NonNull;

import com.example.justnetcipher.NetCipher;
import com.example.justnetcipher.client.TlsOnlySocketFactory;

import org.fdroid.fdroid.SocketFactoryManager;

import javax.net.ssl.SSLSocketFactory;

public class FDroidSocketFactoryManager implements SocketFactoryManager {

    private volatile boolean sniEnabled = true;
    private volatile boolean needNewFactory = false;
    private volatile TlsOnlySocketFactory factory = null;

    public FDroidSocketFactoryManager() {
        factory = NetCipher.getTlsOnlySocketFactory();
    }

    public FDroidSocketFactoryManager(boolean sniEnabled) {
        this.sniEnabled = sniEnabled;
        if (sniEnabled) {
            factory = NetCipher.getTlsOnlySocketFactory();
        } else {
            factory = NetCipher.getTlsOnlySocketFactoryNoSni();
        }
    }

    @Override
    public void enableSni() {
        if (!sniEnabled) {
            needNewFactory = true;
        }
        sniEnabled = true;
    }

    @Override
    public void disableSni() {
        if (sniEnabled) {
            needNewFactory = true;
        }
        sniEnabled = false;
    }

    @Override
    public boolean needNewSocketFactory() {
        return needNewFactory;
    }

    @NonNull
    @Override
    public SSLSocketFactory getSocketFactory() {
        if (needNewFactory) {
            if (sniEnabled) {
                factory = NetCipher.getTlsOnlySocketFactory();
            } else {
                factory = NetCipher.getTlsOnlySocketFactoryNoSni();
            }
            needNewFactory = false;
        }
        return factory;
    }
}
