package org.fdroid.fdroid.net;

import androidx.annotation.NonNull;

import com.example.justnetcipher.NetCipher;
import com.example.justnetcipher.client.TlsOnlySocketFactory;

import org.fdroid.fdroid.Preferences;
import org.fdroid.fdroid.SocketFactoryManager;

import javax.net.ssl.SSLSocketFactory;

public class FDroidSocketFactoryManager implements SocketFactoryManager {

    private volatile boolean isSniEnabled = true;
    private volatile TlsOnlySocketFactory factory = null;

    public FDroidSocketFactoryManager() {
        Preferences prefs = Preferences.get();
        this.isSniEnabled = prefs.isSniEnabled();
        if (isSniEnabled) {
            factory = NetCipher.getTlsOnlySocketFactory();
        } else {
            factory = NetCipher.getTlsOnlySocketFactoryNoSni();
        }
    }

    @Override
    public boolean sniEnabled() {
        Preferences prefs = Preferences.get();
        return prefs.isSniEnabled();
    }

    @Override
    public boolean needNewSocketFactory() {
        Preferences prefs = Preferences.get();
        return this.isSniEnabled != prefs.isSniEnabled();
    }

    @NonNull
    @Override
    public SSLSocketFactory getSocketFactory() {
        Preferences prefs = Preferences.get();
        if (needNewSocketFactory()) {
            this.isSniEnabled = prefs.isSniEnabled();
            if (isSniEnabled) {
                factory = NetCipher.getTlsOnlySocketFactory();
            } else {
                factory = NetCipher.getTlsOnlySocketFactoryNoSni();
            }
        }
        return factory;
    }
}
