package com.arabiflow.fixture;
import android.app.Activity;
import android.os.Bundle;
public class FixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);
        setTitle(getString(R.string.app_name));
    }
}
