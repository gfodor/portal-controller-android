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
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.media.Image;
import android.os.SystemClock;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.exceptions.NotYetAvailableException;
import android.util.Size;


import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.ByteBuffer;
import org.openftc.apriltag.AprilTagDetection;
import org.openftc.apriltag.AprilTagDetectorJNI;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.android.OpenCVLoader;
import org.firstinspires.ftc.robotcore.external.matrices.MatrixF;
import java.util.ArrayList;
import java.util.Locale;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final double MIN_OPENGL_VERSION = 3.0;

    private Session arSession;
    private Button calibration_btn;

    private float[] cam_pos = new float[3];
    private float[] cam_quat = new float[4];

    private GLSurfaceView glSurfaceView;
    private android.os.Handler frameHandler = new android.os.Handler();
    private final Runnable frameUpdate = new Runnable() {
        @Override public void run() {
            if (arSession != null) {
                glSurfaceView.queueEvent(() -> {
                    try {
                        Frame frame = arSession.update();
                        if (frame.getCamera().getTrackingState() == TrackingState.TRACKING) {
                            Pose pose = frame.getCamera().getDisplayOrientedPose();
                            pose.getTranslation(cam_pos, 0);
                            pose.getRotationQuaternion(cam_quat, 0);
                            runOnUiThread(() -> updatePoseViews());
                        }
                        captureImageForDetector(frame);
                    } catch (Exception ex) {
                        Log.e(TAG, "Error updating AR session", ex);
                    }
                });
                glSurfaceView.requestRender();
            }
            frameHandler.postDelayed(this, 33);
        }
    };

    private class SimpleRenderer implements GLSurfaceView.Renderer {
        private int textureId;

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            textureId = createCameraTexture();
            if (arSession != null) {
                arSession.setCameraTextureName(textureId);
            }
        }

        @Override public void onSurfaceChanged(GL10 gl, int w, int h) {}

        @Override public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        }
    }

    private int createCameraTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int texId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return texId;
    }

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
    private final ExecutorService detectorExecutor = Executors.newSingleThreadExecutor();
    private long lastImageTime = 0;
    private long aprilTagDetectorPtr = 0;
    private static final double TAG_SIZE_METERS = 0.166;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Load OpenCV native libs early
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "❌ OpenCV native library failed to load");
        } else {
            Log.i(TAG, "✅ OpenCV loaded");
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!checkIsSupportedDeviceOrFinish(this)) {
            return;
        }
        setContentView(R.layout.activity_main);

        glSurfaceView = findViewById(R.id.arcore_gl_surface);
        glSurfaceView.setEGLContextClientVersion(2);
        glSurfaceView.setRenderer(new SimpleRenderer());
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
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


        try {
            arSession = new Session(this);
            CameraConfigFilter filter = new CameraConfigFilter(arSession);
            java.util.List<CameraConfig> configs = arSession.getSupportedCameraConfigs(filter);
            CameraConfig chosen = configs.isEmpty() ? null : configs.get(0);
            for (CameraConfig cc : configs) {
                Size sz = cc.getImageSize();
                if (sz.getWidth() >= 640 && sz.getHeight() >= 480) { chosen = cc; break; }
            }
            if (chosen != null) {
                arSession.setCameraConfig(chosen);
                Size s = chosen.getImageSize();
                Log.i(TAG, "Camera config set to " + s.getWidth() + "x" + s.getHeight());
            }
            Config config = new Config(arSession);
            config.setPlaneFindingMode(Config.PlaneFindingMode.DISABLED);
            config.setCloudAnchorMode(Config.CloudAnchorMode.DISABLED);
            config.setLightEstimationMode(Config.LightEstimationMode.DISABLED);
            arSession.configure(config);
            aprilTagDetectorPtr = AprilTagDetectorJNI.createApriltagDetector(
                    AprilTagDetectorJNI.TagFamily.TAG_36h11.string, 2.0f, 1);
        } catch (Exception ex) {
            Toast toast = Toast.makeText(this, "ARCore unavailable: " + ex.getMessage(), Toast.LENGTH_LONG);
            toast.setGravity(Gravity.CENTER, 0, 0);
            toast.show();
        }


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
        glSurfaceView.onResume();
        frameHandler.post(frameUpdate);
    }

    @Override
    protected void onPause() {
        frameHandler.removeCallbacks(frameUpdate);
        if (arSession != null) {
            arSession.pause();
        }
        glSurfaceView.onPause();
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


        if (bleClient != null) {
            bleClient.sendPose(cam_pos, cam_quat);
        }
    }

    private void captureImageForDetector(Frame frame) {
        long now = SystemClock.uptimeMillis();
        if (now - lastImageTime < 1000) return;
        lastImageTime = now;
        try {
            Image img = frame.acquireCameraImage();
            int w = img.getWidth();
            int h = img.getHeight();
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            img.close();
            float[] fl = frame.getCamera().getImageIntrinsics().getFocalLength();
            float[] pp = frame.getCamera().getImageIntrinsics().getPrincipalPoint();
            float fx = fl[0];
            float fy = fl[1];
            float cx = pp[0];
            float cy = pp[1];
            Log.d(TAG, "Captured image " + w + "x" + h + " at " + now);
            detectorExecutor.execute(() -> runAprilTagDetector(data, w, h, fx, fy, cx, cy));
        } catch (NotYetAvailableException e) {
            // frame not ready; ignore
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
        detectorExecutor.shutdown();
        if (aprilTagDetectorPtr != 0) {
            AprilTagDetectorJNI.releaseApriltagDetector(aprilTagDetectorPtr);
            aprilTagDetectorPtr = 0;
        }
        super.onDestroy();
    }

    private void runAprilTagDetector(byte[] data, int width, int height,
                                     float fx, float fy, float cx, float cy) {
        if (aprilTagDetectorPtr == 0) return;

        Mat grey = new Mat(height, width, CvType.CV_8UC1);
        grey.put(0, 0, data);

        ArrayList<AprilTagDetection> detections =
                AprilTagDetectorJNI.runAprilTagDetectorSimple(
                        aprilTagDetectorPtr, grey, TAG_SIZE_METERS,
                        fx, fy, cx, cy);

        grey.release();

        if (!detections.isEmpty()) {
            AprilTagDetection det = detections.get(0);
            MatrixF r = det.pose.R;
            StringBuilder rot = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    rot.append(String.format(Locale.US, "%.3f ", r.get(i, j)));
                }
            }
            Log.i(TAG, String.format(Locale.US,
                    "AprilTag id=%d pos [%.3f %.3f %.3f] rot %s",
                    det.id, det.pose.x, det.pose.y, det.pose.z,
                    rot.toString().trim()));
            float[] pos = {
                    (float) det.pose.x,
                    (float) det.pose.y,
                    (float) det.pose.z
            };
            float[] rotMat = new float[9];
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    rotMat[i * 3 + j] = (float) r.get(i, j);
                }
            }
            if (bleClient != null) {
                bleClient.sendAprilTag(det.id, pos, rotMat);
            }
        }
    }
}