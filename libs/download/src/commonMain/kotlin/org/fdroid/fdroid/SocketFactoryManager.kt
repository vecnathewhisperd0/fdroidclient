package org.fdroid.fdroid

import javax.net.ssl.SSLSocketFactory

public interface SocketFactoryManager {

    public fun enableSni()

    public fun disableSni()

    public fun needNewSocketFactory(): Boolean

    public fun getSocketFactory(): SSLSocketFactory

}