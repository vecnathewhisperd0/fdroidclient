package org.fdroid.fdroid;

import android.os.Build;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.widget.TextView;

public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.about);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            setFinishOnTouchOutside(false);
        }

        String versionName = Utils.getVersionName(this);
        if (versionName != null) {
            ((TextView) findViewById(R.id.version)).setText(versionName);
        }

        findViewById(R.id.ok_button).setOnClickListener(v -> finish());
    }
}
