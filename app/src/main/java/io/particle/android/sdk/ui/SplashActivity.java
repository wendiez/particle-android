package io.particle.android.sdk.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import io.particle.android.sdk.cloud.ParticleCloudSDK;
import io.particle.android.sdk.cloud.SDKGlobals;
import io.particle.android.sdk.utils.EZ;
import io.particle.android.sdk.utils.TLog;
import io.particle.sdk.app.R;


/**
 * More than just a way to display a splash screen; this also wraps
 * FirstRealActivitySelector for you, and launches the appropriate activity
 * from there.
 */
public class SplashActivity extends BaseActivity {

    private static final TLog log = TLog.get(SplashActivity.class);

    // FIXME: is it worth putting this in the customization file?
    private static final int SPLASH_DISPLAY_TIME = 0;

    private boolean finished = false;


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (SPLASH_DISPLAY_TIME < 1) {
            // don't display the splash screen at all, immediately move to the next activity.
            onShowingSplashComplete();
            return;
        }

        this.setContentView(R.layout.activity_splash);

        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (finished) {
            onShowingSplashComplete();
            return;
        }

        EZ.runOnMainThreadDelayed(SPLASH_DISPLAY_TIME, () -> {
            finished = true;
            onShowingSplashComplete();
        });
    }

    private void onShowingSplashComplete() {
        if (isFinishing()) {
            log.i("Activity is already finished/finishing, not launching next Activity");

        } else {
            Intent intent;
            if (SDKGlobals.getAppDataStorage().getUserHasClaimedDevices()) {
                intent = NextActivitySelector.getNextActivityIntent(this,
                        ParticleCloudSDK.getCloud(),
                        SDKGlobals.getSensitiveDataStorage(),
                        null);
            } else {
                intent = new Intent(this, IntroActivity.class);
            }

            log.d("Splash screen done, moving to next Activity: " + intent);
            startActivity(intent);
            finish();
        }
    }

}