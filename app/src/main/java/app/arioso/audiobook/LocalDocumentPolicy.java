package app.arioso.audiobook;

final class LocalDocumentPolicy {
    private LocalDocumentPolicy() { }

    static boolean accepts(String scheme, String authority) {
        if (!"content".equals(scheme) || authority == null) return false;
        switch (authority) {
            case "com.android.externalstorage.documents":
            case "com.android.providers.downloads.documents":
            case "com.android.providers.media.documents":
                return true;
            default:
                return false;
        }
    }
}
