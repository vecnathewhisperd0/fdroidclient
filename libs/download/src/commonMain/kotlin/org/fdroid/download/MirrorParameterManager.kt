package org.fdroid.download

/**
 * This is an interface for providing access to stored parameters for mirrors without adding
 * additional dependencies.  The expectation is that this will be used to store and retrieve
 * data about mirror performance to use when ordering mirror for subsequent tests.
 *
 * Currently it supports success and error count, but other parameters could be added later.
 */

public interface MirrorParameterManager {

    /**
     * Set or get the number of successful or failed attempts to access the specified mirror.  The
     * intent is to order mirrors for subsequent tests in ascending order of successes, offset by
     * the number of failures (less tested mirrors should be given priority).  Untested mirrors
     * default to a negative value for successes/failures.
     */
    public fun incrementMirrorSuccessCount(mirrorUrl: String)

    public fun setMirrorSuccessCount(mirrorUrl: String, successCount: Int)

    public fun getMirrorSuccessCount(mirrorUrl: String): Int

    public fun incrementMirrorErrorCount(mirrorUrl: String)

    public fun setMirrorErrorCount(mirrorUrl: String, errorCount: Int)

    public fun getMirrorErrorCount(mirrorUrl: String): Int

    /**
     * Returns true or false depending on whether the location preference has been enabled. This
     * preference reflects whether mirrors matching your location should get priority.
     */
    public fun preferForeignMirrors(): Boolean

    /**
     * Returns the country code of the user's current location
     */
    public fun getCurrentLocations(): List<String>

}
