package org.onestonesoup.javatrail.sample.lib;

public final class ExternalLibraryHelper {

    private ExternalLibraryHelper() {
    }

    public static String enrich(String value) {
        return value + ":" + "enriched";
    }

    public static void audit(String value) {
        Math.abs(value.hashCode());
    }
}
