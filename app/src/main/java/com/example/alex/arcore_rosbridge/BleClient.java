package com.example.alex.arcore_rosbridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Scans for the advertised "Portal" runtime (service UUID f8b69c7b-…-f49a) and opens a GATT link.
 * Extend with characteristic read/write as needed.
 */
public class BleClient {
    private static final String TAG = "BleClient";
    private final Context context;
    private final BluetoothLeScanner scanner;
    private final UUID serviceUuid;
    private BluetoothGatt gatt;

    private static final UUID RX_UUID =
            UUID.fromString("f8b69c7b-3a91-4f2d-8e7a-9c4d35d5f49b");
    // NEW – desired ATT MTU and flag
    private static final int DESIRED_MTU = 96;
    private volatile boolean mtuReady = false;

    private BluetoothGattCharacteristic rxChar;

    // Added packet type constants
    private static final byte PACKET_POSE       = 0x00;
    private static final byte PACKET_CALIBRATION = 0x01;
    private static final byte PACKET_APRILTAG    = 0x02;
    private static final byte PACKET_BUTTON     = 0x03;

    public static final byte BUTTON_VOL_UP   = 0x00;
    public static final byte BUTTON_VOL_DOWN = 0x01;

    public BleClient(Context ctx, UUID serviceUuid) {
        this.context = ctx.getApplicationContext();
        this.serviceUuid = serviceUuid;
        BluetoothManager mgr = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        this.scanner = mgr.getAdapter() != null ? mgr.getAdapter().getBluetoothLeScanner() : null;
    }

    /** Begin scanning – caller must ensure permissions are already granted. */
    @RequiresPermission(allOf = {
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT"
    })
    public void start() {
        if (scanner == null) {
            Log.w(TAG, "BLE scanner unavailable");
            return;
        }
        List<ScanFilter> filters = Collections.singletonList(
                new ScanFilter.Builder().setServiceUuid(new ParcelUuid(serviceUuid)).build()
        );
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanner.startScan(filters, settings, scanCb);   // pass actual filters
        Log.i(TAG, "Scanning for Portal service " + serviceUuid);
    }

    public void stop() {
        if (scanner != null) try { scanner.stopScan(scanCb); } catch (Exception ignored) {}
        if (gatt != null) { gatt.close(); gatt = null; }
        rxChar = null;
        mtuReady = false;
    }

    /* ───────── Callbacks ───────── */
    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int c, ScanResult res) {
            BluetoothDevice dev = res.getDevice();
            Log.i(TAG, "Found " + dev.getName() + " (" + dev.getAddress() + ")");
            scanner.stopScan(this);
            gatt = dev.connectGatt(context, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        }
        @Override public void onScanFailed(int errorCode) {
            Log.e(TAG, "BLE scan failed with error: " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int newState) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "Connected – requesting MTU " + DESIRED_MTU);
                gatt = g;
                mtuReady = false;
                g.requestMtu(DESIRED_MTU);
                return; // wait for onMtuChanged before discovering services
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected"); stop();
            }
        }
        @Override public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            Log.i(TAG, "MTU changed callback: mtu=" + mtu + " status=" + status);
            mtuReady = (status == BluetoothGatt.GATT_SUCCESS && mtu >= 28);
            if (!mtuReady) {
                Log.w(TAG, "MTU negotiation failed – proceeding with default MTU");
            }
            g.discoverServices();   // continue regardless
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(serviceUuid);
            Log.i(TAG, "Service discovery " + (svc != null ? "succeeded" : "failed"));
            rxChar = svc != null ? svc.getCharacteristic(RX_UUID) : null;
            if (rxChar != null) rxChar.setWriteType(
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            Log.i(TAG, "Services discovered; rxChar=" + (rxChar != null) + " mtuReady=" + mtuReady);
            // TODO: subscribe / write to characteristics (RX f49b, TX f49c) as needed.
        }
    };

    public void sendPose(float[] pos, float[] quat) {
        if (!mtuReady || gatt == null || rxChar == null) {
            Log.w(TAG, "Pose send skipped – mtuReady=" + mtuReady + " gatt=" + (gatt != null) + " rxChar=" + (rxChar != null));
            return;
        }
        ByteBuffer bb = ByteBuffer.allocate(1 + 4 * 7).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(PACKET_POSE);
        bb.putFloat(pos[0]).putFloat(pos[1]).putFloat(pos[2])
          .putFloat(quat[0]).putFloat(quat[1]).putFloat(quat[2]).putFloat(quat[3]);
        rxChar.setValue(bb.array());
        boolean ok = gatt.writeCharacteristic(rxChar);
    }

    /** Send AprilTag detection with raw camera pose included. */
    public void sendAprilTag(int id,
                             float[] camPos, float[] camQuat,
                             float[] pos, float[] rotMat) {
        if (!mtuReady || gatt == null || rxChar == null) {
            Log.w(TAG, "AprilTag send skipped – mtuReady=" + mtuReady + " gatt=" + (gatt != null) + " rxChar=" + (rxChar != null));
            return;
        }
        if (camPos.length < 3 || camQuat.length < 4 || pos.length < 3 || rotMat.length < 9) return;

        // Packet: [hdr][id][camPos(3f)][camQuat(4f)][tagPos(3f)][rot(9f)]
        ByteBuffer bb = ByteBuffer.allocate(1 + 4 + 4 * (3 + 4 + 3 + 9))
                                  .order(ByteOrder.LITTLE_ENDIAN);
        bb.put(PACKET_APRILTAG);
        bb.putInt(id);
        for (int i = 0; i < 3; i++) bb.putFloat(camPos[i]);
        for (int i = 0; i < 4; i++) bb.putFloat(camQuat[i]);
        for (int i = 0; i < 3; i++) bb.putFloat(pos[i]);
        for (int i = 0; i < 9; i++) bb.putFloat(rotMat[i]);
        rxChar.setValue(bb.array());
        gatt.writeCharacteristic(rxChar);
    }

    /** Send a one-byte calibration trigger (packet type 0x01). */
    public void sendCalibrationTrigger() {
        if (!mtuReady || gatt == null || rxChar == null) {
            Log.w(TAG, "Calibration trigger skipped – link not ready");
            return;
        }
        rxChar.setValue(new byte[] { PACKET_CALIBRATION });
        gatt.writeCharacteristic(rxChar);
    }

    /** Send a button press or release event. */
    public void sendButtonEvent(byte button, boolean pressed) {
        if (!mtuReady || gatt == null || rxChar == null) {
            Log.w(TAG, "Button event skipped – link not ready");
            return;
        }
        ByteBuffer bb = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(PACKET_BUTTON);
        bb.put(button);
        bb.put((byte) (pressed ? 1 : 0));
        rxChar.setValue(bb.array());
        gatt.writeCharacteristic(rxChar);
    }
}