package com.example.alex.arcore_rosbridge;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.jilk.ros.ROSClient;
import com.jilk.ros.Topic;
import com.jilk.ros.rosbridge.ROSBridgeClient;


import java.util.UUID;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final double MIN_OPENGL_VERSION = 3.0;

    private Session arSession;
    private boolean rosbridge_connected = false;
    private ROSBridgeClient rosclient;

    private EditText rosmaster_ip_txt;
    private EditText rosbridge_port_txt;
    private Button connect_btn;
    private Button calibration_btn;

    private float[] cam_pos = new float[3];
    private float[] cam_quat = new float[4];

    private android.opengl.GLSurfaceView glSurfaceView;
    private android.os.Handler frameHandler = new android.os.Handler();
    private final Runnable frameUpdate = new Runnable() {
        @Override public void run() {
            if (arSession != null) {
                try {
                    Frame frame = arSession.update();
                    if (frame.getCamera().getTrackingState() == TrackingState.TRACKING) {
                        Pose pose = frame.getCamera().getDisplayOrientedPose();
                        pose.getTranslation(cam_pos, 0);
                        pose.getRotationQuaternion(cam_quat, 0);
                        updatePoseViews();
                    }
                } catch (Exception ignored) {}
            }
            frameHandler.postDelayed(this, 33);
        }
    };

    private TextView pos_x_txt;
    private TextView pos_y_txt;
    private TextView pos_z_txt;
    private TextView rot_x_txt;
    private TextView rot_y_txt;
    private TextView rot_z_txt;
    private TextView rot_w_txt;

    // BLE integration
    private static final UUID PORTAL_SERVICE_UUID =
            UUID.fromString("f8b69c7b-3a91-4f2d-8e7a-9c4d35d5f49a");
    private static final int REQ_BLE_PERMS = 0xB1E;
    private BleClient bleClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!checkIsSupportedDeviceOrFinish(this)) {
            return;
        }
        setContentView(R.layout.activity_main);

        glSurfaceView = findViewById(R.id.arcore_gl_surface);
        rosmaster_ip_txt = findViewById(R.id.rosmaster_ip_txt);
        rosbridge_port_txt = findViewById(R.id.rosbridge_port_txt);
        connect_btn = findViewById(R.id.connect_btn);
        calibration_btn = findViewById(R.id.calibration_btn);

        calibration_btn.setOnClickListener(v ->
                Toast.makeText(getApplicationContext(), "Calibration reset", Toast.LENGTH_SHORT).show());

        pos_x_txt = findViewById(R.id.pos_x_txt);
        pos_y_txt = findViewById(R.id.pos_y_txt);
        pos_z_txt = findViewById(R.id.pos_z_txt);
        rot_x_txt = findViewById(R.id.rot_x_txt);
        rot_y_txt = findViewById(R.id.rot_y_txt);
        rot_z_txt = findViewById(R.id.rot_z_txt);
        rot_w_txt = findViewById(R.id.rot_w_txt);

        rosmaster_ip_txt.clearFocus();
        rosbridge_port_txt.clearFocus();

        try {
            arSession = new Session(this);
            Config config = new Config(arSession);
            config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
            config.setCloudAnchorMode(Config.CloudAnchorMode.DISABLED);
            config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
            arSession.configure(config);
        } catch (Exception ex) {
            Toast toast = Toast.makeText(this, "ARCore unavailable: " + ex.getMessage(), Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        }

        connect_btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                rosmaster_ip_txt.clearFocus();
                rosbridge_port_txt.clearFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);

                String socket = "ws://" + rosmaster_ip_txt.getText().toString().trim() + ":" + rosbridge_port_txt.getText().toString().trim();
                // Toast.makeText(getApplicationContext(), socket, Toast.LENGTH_LONG).show();
                rosclient = new ROSBridgeClient(socket);
                rosbridge_connected = rosclient.connect(new ROSClient.ConnectionStatusListener() {
                    @Override
                    public void onConnect() {
                        runOnUiThread(() -> {
                            if (rosbridge_connected) {
                                Toast.makeText(getApplicationContext(), "Connected to ROS", Toast.LENGTH_LONG).show();
                            }
                        });
                    }

                    @Override
                    public void onDisconnect(boolean normal, String reason, int code) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getApplicationContext(), "Disconnected to ROS", Toast.LENGTH_LONG).show();
                            }
                        });
                    }

                    @Override
                    public void onError(Exception ex) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getApplicationContext(), "Error: " + ex.toString(), Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
            }
        });

        // Start BLE integration
        if (hasBlePermissions()) {
            bleClient = new BleClient(this, PORTAL_SERVICE_UUID);
            bleClient.start();
        } else {
            requestBlePermissions();
        }
    }

    /**
     * Returns false and displays an error message if Sceneform can not run, true if Sceneform can run
     * on this device.
     *
     * <p>Sceneform requires Android N on the device as well as OpenGL 3.0 capabilities.
     *
     * <p>Finishes the activity if Sceneform can not run
     */
    public static boolean checkIsSupportedDeviceOrFinish(final Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.e(TAG, "Sceneform requires Android N or later");
            Toast.makeText(activity, "Sceneform requires Android N or later", Toast.LENGTH_LONG).show();
            activity.finish();
            return false;
        }
        String openGlVersionString =
                ((ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE))
                        .getDeviceConfigurationInfo()
                        .getGlEsVersion();
        if (Double.parseDouble(openGlVersionString) < MIN_OPENGL_VERSION) {
            Log.e(TAG, "Sceneform requires OpenGL ES 3.0 later");
            Toast.makeText(activity, "Sceneform requires OpenGL ES 3.0 or later", Toast.LENGTH_LONG)
                    .show();
            activity.finish();
            return false;
        }
        return true;
    }

    // ───── BLE permission helpers ─────
    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // For Android 12/13, also require fine location if targetSdk < 31
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        } else {
            // For Android 6-11
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this,
                new String[] {
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    // Always include location on 12/13 when targetSdk < 31
                    Manifest.permission.ACCESS_FINE_LOCATION
                },
                REQ_BLE_PERMS
            );
        } else {
            ActivityCompat.requestPermissions(
                this,
                new String[] { Manifest.permission.ACCESS_FINE_LOCATION },
                REQ_BLE_PERMS
            );
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (arSession != null) {
            try { arSession.resume(); } catch (Exception ignored) {}
        }
        frameHandler.post(frameUpdate);
    }

    @Override
    protected void onPause() {
        frameHandler.removeCallbacks(frameUpdate);
        if (arSession != null) {
            arSession.pause();
        }
        super.onPause();
    }

    private void updatePoseViews() {
        pos_x_txt.setText(String.format("%.2f", cam_pos[0]));
        pos_y_txt.setText(String.format("%.2f", cam_pos[1]));
        pos_z_txt.setText(String.format("%.2f", cam_pos[2]));
        rot_x_txt.setText(String.format("%.2f", cam_quat[0]));
        rot_y_txt.setText(String.format("%.2f", cam_quat[1]));
        rot_z_txt.setText(String.format("%.2f", cam_quat[2]));
        rot_w_txt.setText(String.format("%.2f", cam_quat[3]));

        if (rosbridge_connected) {
            Topic<com.jilk.ros.message.Pose> cam_topic = new Topic<>("/arcore/cam_pose", com.jilk.ros.message.Pose.class, rosclient);
            cam_topic.advertise();
            com.jilk.ros.message.Pose msg = new com.jilk.ros.message.Pose();
            msg.position.x = cam_pos[0];
            msg.position.y = cam_pos[1];
            msg.position.z = cam_pos[2];
            msg.orientation.x = cam_quat[0];
            msg.orientation.y = cam_quat[1];
            msg.orientation.z = cam_quat[2];
            msg.orientation.w = cam_quat[3];
            cam_topic.publish(msg);
        }

        if (bleClient != null) {
            bleClient.sendPose(cam_pos, cam_quat);
        }
    }

    @Override
    public void onRequestPermissionsResult(int reqCode, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(reqCode, perms, res);
        if (reqCode == REQ_BLE_PERMS && hasBlePermissions()) {
            bleClient = new BleClient(this, PORTAL_SERVICE_UUID);
            bleClient.start();
        }
    }

    @Override
    protected void onDestroy() {
        if (bleClient != null) bleClient.stop();
        super.onDestroy();
    }
}