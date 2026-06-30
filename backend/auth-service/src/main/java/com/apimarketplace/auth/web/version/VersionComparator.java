package com.apimarketplace.auth.web.version;

/**
 * Minimal semantic-version comparison for the self-hosted update check: decides
 * whether a published {@code latest} version is newer than the {@code running}
 * one. Only the numeric {@code major.minor.patch} core is compared - a
 * pre-release / build suffix ({@code -SNAPSHOT}, {@code -rc1}, {@code +sha}) and
 * an optional leading {@code v} are stripped first.
 *
 * <p>Conservative by design: if either side is blank or not parseable as a
 * numeric core, {@link #isUpdateAvailable} returns {@code false} so the user is
 * never wrongly told they are behind. {@code 0.1.0-SNAPSHOT} and {@code 0.1.0}
 * compare equal (same core), so a SNAPSHOT build is not flagged as outdated
 * against its own release.
 */
public final class VersionComparator {

    private VersionComparator() {}

    /** True iff both parse and {@code latest}'s core is strictly newer than {@code running}'s. */
    public static boolean isUpdateAvailable(String running, String latest) {
        int[] r = parseCore(running);
        int[] l = parseCore(latest);
        if (r == null || l == null) {
            return false;
        }
        return compare(l, r) > 0;
    }

    /**
     * Parses the {@code major.minor.patch} core to a 3-int array, or {@code null}
     * when the value is blank or any present component is non-numeric. A leading
     * {@code v} and anything from the first {@code -} or {@code +} are ignored;
     * missing minor/patch default to 0.
     */
    static int[] parseCore(String version) {
        if (version == null) {
            return null;
        }
        String v = version.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.charAt(0) == 'v' || v.charAt(0) == 'V') {
            v = v.substring(1);
        }
        int cut = indexOfAny(v, '-', '+');
        if (cut >= 0) {
            v = v.substring(0, cut);
        }
        if (v.isEmpty()) {
            return null;
        }
        String[] parts = v.split("\\.");
        if (parts.length > 3) {
            return null;
        }
        int[] core = new int[]{0, 0, 0};
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty() || !isDigits(p)) {
                return null;
            }
            try {
                core[i] = Integer.parseInt(p);
            } catch (NumberFormatException overflow) {
                return null;
            }
        }
        return core;
    }

    private static int compare(int[] a, int[] b) {
        for (int i = 0; i < 3; i++) {
            int c = Integer.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int indexOfAny(String s, char a, char b) {
        int ia = s.indexOf(a);
        int ib = s.indexOf(b);
        if (ia < 0) {
            return ib;
        }
        if (ib < 0) {
            return ia;
        }
        return Math.min(ia, ib);
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
