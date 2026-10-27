package app.dart.audiobook;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalDocumentPolicyTest {
    @Test public void acceptsAndroidOnDeviceProviders() {
        assertTrue(LocalDocumentPolicy.accepts("content", "com.android.externalstorage.documents"));
        assertTrue(LocalDocumentPolicy.accepts("content", "com.android.providers.downloads.documents"));
        assertTrue(LocalDocumentPolicy.accepts("content", "com.android.providers.media.documents"));
    }

    @Test public void rejectsOtherAndLookalikeProviders() {
        assertFalse(LocalDocumentPolicy.accepts("file", "com.android.externalstorage.documents"));
        assertFalse(LocalDocumentPolicy.accepts("content", null));
        assertFalse(LocalDocumentPolicy.accepts("content", ""));
        assertFalse(LocalDocumentPolicy.accepts("content", "com.google.android.apps.docs.storage"));
        assertFalse(LocalDocumentPolicy.accepts("content", "com.android.externalstorage.documents.remote"));
    }
}
