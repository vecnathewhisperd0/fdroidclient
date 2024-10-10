package org.fdroid.fdroid

import javax.net.ssl.SSLSocketFactory

/**
 * This is an interface for providing access to custom sockets without adding additional
 * dependencies.  The socket factory that this interface returns can be added to the configuration
 * of an https client or connection
 *
 * Currently it supports custom sockets with SNI features, but other features could be added later.
 */

public interface SocketFactoryManager {

    /**
     * Returns true or false depending on whether the SNI system parameter has been enabled. This
     * parameter will affect the behavior of the sockets provided by the socket factory.
     */
    public fun sniEnabled(): Boolean

    /**
     * Returns true or false depending on whether features have been changed that require a new
     * socket factory instance to be instantiated.
     */
    public fun needNewSocketFactory(): Boolean

    /**
     * Gets a socket factory based on the current feature settings.  If the local instance is null,
     * or was created with different feature settings, a new socket factory will be instantiated.
     */
    public fun getSocketFactory(): SSLSocketFactory

}