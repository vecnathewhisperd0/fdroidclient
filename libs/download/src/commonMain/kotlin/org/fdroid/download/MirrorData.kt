package org.fdroid.download

public class MirrorData @JvmOverloads constructor(
    @kotlin.jvm.JvmField public var successes: Int = 0,
    @kotlin.jvm.JvmField public var errors: Int = 0
) {
    public fun fromPreferencesString(preferencesString: String) {
        val parts: List<String> = preferencesString.split(",")
        try {
            if (parts.size != 2) {
                // missing parts, no-op
            } else {
                successes = Integer.valueOf(parts[0])
                errors = Integer.valueOf(parts[1])
            }
        } catch (e: NumberFormatException) {
            // invalid numbers, no-op
        }
    }

    public fun toPreferencesString(): String {
        return "" + successes + "," + errors
    }
}
