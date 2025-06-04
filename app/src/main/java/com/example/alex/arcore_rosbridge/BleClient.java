package com.example.alex.arcore_rosbridge;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
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
                Log.i(TAG, "Connected – discovering services");
                gatt = g; g.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.i(TAG, "Disconnected"); stop();
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(serviceUuid);
            Log.i(TAG, "Service discovery " + (svc != null ? "succeeded" : "failed"));
            // TODO: subscribe / write to characteristics (RX f49b, TX f49c) as needed.
        }
    };
}