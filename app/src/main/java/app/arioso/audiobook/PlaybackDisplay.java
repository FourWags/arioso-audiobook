package app.arioso.audiobook;

/** Names exposed to Android's media session and playback notification. */
final class PlaybackDisplay {
    private PlaybackDisplay() { }

    static String title(boolean privatePlayback, String title) {
        return privatePlayback || title == null || title.isEmpty() ? "Arioso" : title;
    }

    static String artist(boolean privatePlayback, String author) {
        return privatePlayback || author == null ? "" : author;
    }

    static String notificationText(boolean privatePlayback, String author) {
        return privatePlayback ? "Private playback"
                : author == null || author.isEmpty() ? "Audiobook" : author;
    }
}
