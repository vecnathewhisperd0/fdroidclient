package org.fdroid.fdroid.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import org.fdroid.fdroid.Preferences;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocketFactory;

public class FDroidSocketFactoryManagerTest {

    private static final String HTTPS_FDROID_NO_SNI = "https://github.com/f-droid";
    private static final String HTTPS_FDROID_SNI = "https://cloudflare.f-droid.org/fdroid/repo/entry.jar";

    @Test
    public void connectionWithoutSniTest() throws IOException, InterruptedException {

        // test setup
        URL withoutSniUrl = new URL(HTTPS_FDROID_NO_SNI);
        Preferences prefs = Preferences.get();
        FDroidSocketFactoryManager manager = new FDroidSocketFactoryManager();

        // should be able to connect to github without sni
        prefs.setSniEnabledValue(false);

        // open connection
        HttpURLConnection connection = (HttpURLConnection) withoutSniUrl.openConnection();
        HttpsURLConnection httpsConnection = ((HttpsURLConnection) connection);
        SSLSocketFactory tlsOnly = manager.getSocketFactory();
        httpsConnection.setSSLSocketFactory(tlsOnly);
        httpsConnection.setHostnameVerifier(org.apache.http.conn.ssl.SSLSocketFactory.STRICT_HOSTNAME_VERIFIER);

        // ignore msg, but read data to force connection
        InputStream is = (InputStream) connection.getContent();
        byte buffer[] = new byte[1024];
        int read = is.read(buffer);
        String msg = new String(buffer, 0, read);
        assertEquals(200, connection.getResponseCode());
        connection.disconnect();

    }

    @Test
    public void connectionWithSniTest() throws IOException, InterruptedException {

        // test setup
        URL withSniUrl = new URL(HTTPS_FDROID_SNI);
        Preferences prefs = Preferences.get();
        FDroidSocketFactoryManager manager = new FDroidSocketFactoryManager();

        // should not be able to connect to cloudflare without sni
        prefs.setSniEnabledValue(false);

        boolean gotException = false;
        try {
            Log.d("FOO", "START");

            // open connection
            HttpURLConnection connection = (HttpURLConnection) withSniUrl.openConnection();
            HttpsURLConnection httpsConnection = ((HttpsURLConnection) connection);
            SSLSocketFactory tlsOnly = manager.getSocketFactory();
            httpsConnection.setSSLSocketFactory(tlsOnly);
            httpsConnection.setHostnameVerifier(org.apache.http.conn.ssl.SSLSocketFactory.STRICT_HOSTNAME_VERIFIER);
            Log.d("FOO", "OPEN CONNECTIONS");

            // ignore msg, but read data to force connection
            InputStream is = (InputStream) connection.getContent();
            Log.d("FOO", "GET INPUT");
            byte buffer[] = new byte[1024];
            int read = is.read(buffer);
            Log.d("FOO", "READ");
            String msg = new String(buffer, 0, read);
            Log.d("FOO", "MESSAGE");
            // code below should not be reachable
            assertEquals(200, connection.getResponseCode());
            connection.disconnect();
            Log.d("FOO", "DISCONNECT");

        } catch (SSLHandshakeException e) {
            Log.d("FOO", "SSL EXCEPTION");
            // expected exception (HANDSHAKE_FAILURE_ON_CLIENT_HELLO)
            gotException = true;
        } catch (Exception e) {
            Log.d("FOO", "OTHER EXCEPTION");
            // unexpected exception
            gotException = false;
        }
        assertTrue(gotException);

        // next try to connect to cloudflare with sni
        prefs.setSniEnabledValue(true);

        HttpURLConnection connection = (HttpURLConnection) withSniUrl.openConnection();
        HttpsURLConnection httpsConnection = ((HttpsURLConnection) connection);
        SSLSocketFactory tlsOnly = manager.getSocketFactory();
        httpsConnection.setSSLSocketFactory(tlsOnly);
        httpsConnection.setHostnameVerifier(org.apache.http.conn.ssl.SSLSocketFactory.STRICT_HOSTNAME_VERIFIER);

        // ignore msg, but read data to force connection
        InputStream is = (InputStream) connection.getContent();
        byte buffer[] = new byte[1024];
        int read = is.read(buffer);
        String msg = new String(buffer, 0, read);
        assertEquals(200, connection.getResponseCode());
        connection.disconnect();

    }
}
