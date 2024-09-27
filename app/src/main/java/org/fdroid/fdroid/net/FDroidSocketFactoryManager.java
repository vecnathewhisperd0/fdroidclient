package org.fdroid.fdroid.net;

import android.util.Log;

import androidx.annotation.NonNull;

import info.guardianproject.netcipher.NetCipher;
import info.guardianproject.netcipher.client.TlsOnlySocketFactory;

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
            Log.d("FOO", "INIT WITH SOCKET FACTORY");
            factory = NetCipher.getTlsOnlySocketFactory();
        } else {
            Log.d("FOO", "INIT WITH NO-SNI SOCKET FACTORY");
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
        Log.d("FOO", "THIS: " + this.isSniEnabled + " vs. PREFS: " + prefs.isSniEnabled());
        return this.isSniEnabled != prefs.isSniEnabled();
    }

    @NonNull
    @Override
    public SSLSocketFactory getSocketFactory() {
        Preferences prefs = Preferences.get();
        if (needNewSocketFactory()) {
            Log.d("FOO", "NEED NEW FACTORY");
            this.isSniEnabled = prefs.isSniEnabled();
            if (isSniEnabled) {
                Log.d("FOO", "RETURN SOCKET FACTORY");
                factory = NetCipher.getTlsOnlySocketFactory();
            } else {
                Log.d("FOO", "RETURN NO-SNI SOCKET FACTORY");
                factory = NetCipher.getTlsOnlySocketFactoryNoSni();
            }
        } else {
            Log.d("FOO", "RETURN EXISTING FACTORY");
        }
        return factory;
    }
}
