package dev.nullclient.ai.world;

/** Block matcher. Comma-separated; "=id" is exact, a bare "id" also matches "*_id" and "id_*". */
public final class BlockSpec {
    private BlockSpec() {
    }

    public static boolean test(String id, String spec) {
        for (String part : spec.split(",")) {
            if (part.startsWith("=")) {
                if (id.equals(part.substring(1))) {
                    return true;
                }
            } else if (id.equals(part) || id.endsWith("_" + part) || id.startsWith(part + "_")) {
                return true;
            }
        }
        return false;
    }
}