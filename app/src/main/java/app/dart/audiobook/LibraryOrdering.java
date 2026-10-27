package app.dart.audiobook;

import java.math.BigDecimal;

/** Stable author, series, and book ordering for library views and cover stacks. */
final class LibraryOrdering {
    private LibraryOrdering() { }

    static int compare(String leftAuthor, String leftSeries, String leftNumber,
                       String leftTitle, String leftUri, String rightAuthor,
                       String rightSeries, String rightNumber, String rightTitle,
                       String rightUri) {
        int result = leftAuthor.compareToIgnoreCase(rightAuthor);
        if (result != 0) return result;
        result = leftSeries.compareToIgnoreCase(rightSeries);
        if (result != 0) return result;
        BigDecimal leftOrder = parseOrder(leftNumber);
        BigDecimal rightOrder = parseOrder(rightNumber);
        if (leftOrder != null && rightOrder != null) {
            result = leftOrder.compareTo(rightOrder);
            if (result != 0) return result;
        } else if (leftOrder != null) return -1;
        else if (rightOrder != null) return 1;
        result = compareNatural(leftTitle, rightTitle);
        return result != 0 ? result : leftUri.compareTo(rightUri);
    }

    /** Keep unnumbered titles such as Volume 2 before Volume 10. */
    static int compareNatural(String left, String right) {
        int a = 0, b = 0;
        while (a < left.length() && b < right.length()) {
            char ac = left.charAt(a), bc = right.charAt(b);
            if (Character.isDigit(ac) && Character.isDigit(bc)) {
                int ae = a, be = b;
                while (ae < left.length() && Character.isDigit(left.charAt(ae))) ae++;
                while (be < right.length() && Character.isDigit(right.charAt(be))) be++;
                int az = a, bz = b;
                while (az < ae && left.charAt(az) == '0') az++;
                while (bz < be && right.charAt(bz) == '0') bz++;
                int al = ae - az, bl = be - bz;
                if (al != bl) return Integer.compare(al, bl);
                for (int i = 0; i < al; i++) {
                    int difference = Character.compare(left.charAt(az + i), right.charAt(bz + i));
                    if (difference != 0) return difference;
                }
                if (ae - a != be - b) return Integer.compare(ae - a, be - b);
                a = ae; b = be;
            } else {
                int difference = Character.compare(Character.toLowerCase(ac), Character.toLowerCase(bc));
                if (difference != 0) return difference;
                a++; b++;
            }
        }
        return Integer.compare(left.length() - a, right.length() - b);
    }

    static BigDecimal parseOrder(String value) {
        if (value == null || value.length() > 13
                || !value.matches("-?[0-9]{1,4}(\\.[0-9]{1,2})?")) return null;
        try { return new BigDecimal(value); }
        catch (NumberFormatException ignored) { return null; }
    }
}
