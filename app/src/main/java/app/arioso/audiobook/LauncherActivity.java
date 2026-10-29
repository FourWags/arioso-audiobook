package app.arioso.audiobook;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** A no-display, separate-affinity launcher handoff keeps the library task independent of its icon alias. */
public final class LauncherActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
