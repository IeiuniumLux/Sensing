package gov.nasa.arc.sensing;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.DecimalFormat;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.ToggleButton;

public class SensingActivity extends Activity implements SensorEventListener {
	private static final String TAG = "SensingActivity";
	private static final int RATE = SensorManager.SENSOR_DELAY_NORMAL;

	private SensorManager sensorManager;
	private float[] accelerationValues;
	private float[] magneticValues;
	private TextView sensorXLabel;
	private TextView sensorXValue;
	private TextView sensorYLabel;
	private TextView sensorYValue;
	// private TextView sensorZLabel;
	// private TextView sensorZValue;
	private DatagramSocket sensorSocket = null;
	private DatagramSocket cameraSocket = null;
	private boolean isSendingData;
	private ToggleButton togglebutton;

	private CameraPreview mPreview;
	private boolean isLandscape;
	private SharedPreferences prefs;
	private String host;
	private int sensorPort;
	private int cameraPort;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		super.setContentView(R.layout.sensing);

		// Keep the screen on so that changes in orientation can be easily observed
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		// Get a reference to the sensor service
		sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

		// Initialize references to the UI views that will be updated in the code
		sensorXLabel = (TextView) findViewById(R.id.sensorXLabel);
		sensorXValue = (TextView) findViewById(R.id.sensorXValue);
		sensorYLabel = (TextView) findViewById(R.id.sensorYLabel);
		sensorYValue = (TextView) findViewById(R.id.sensorYValue);
		// sensorZLabel = (TextView) findViewById(R.id.sensorZLabel);
		// sensorZValue = (TextView) findViewById(R.id.sensorZValue);

		PreferenceManager.setDefaultValues(this, R.xml.udp_preferences, false);
		prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		host = prefs.getString(getString(R.string.ipAddressKey), "255.255.255.255");
		sensorPort = Integer.parseInt(prefs.getString(getString(R.string.sensorPortKey), "9001"));
		cameraPort = Integer.parseInt(prefs.getString(getString(R.string.cameraPortKey), "9002"));
		isLandscape = prefs.getBoolean("landscapeMode", true);
		setRequestedOrientation((isLandscape) ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

		if (checkCameraHardware(getApplicationContext())) {
			mPreview = (CameraPreview) findViewById(R.id.camera_preview);
			mPreview.setCameraDisplayOrientation(isLandscape ? 0 : 90);
		}

		togglebutton = (ToggleButton) findViewById(R.id.sendAccelerationDataToggleButton);
		togglebutton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
				vib.vibrate(45);
				isSendingData = (((ToggleButton) v).isChecked()) ? true : false;
				try {
					sensorSocket = new DatagramSocket();
					cameraSocket = new DatagramSocket();
				} catch (Exception exception) {
					Log.e(TAG, "Error: ", exception);
				}
				if (mPreview != null) {
					mPreview.setUDPSettings(cameraSocket, host, cameraPort);
					mPreview.setSendingData(isSendingData);
				}
			}
		});

	}

	@Override
	protected void onResume() {
		super.onResume();
		updateSelectedSensor();
	}

	@Override
	protected void onPause() {
		super.onPause();
		sensorManager.unregisterListener(this);
		isSendingData = false;
		togglebutton.setChecked(false);
		if (sensorSocket != null) {
			sensorSocket.close();
			sensorSocket = null;
		}
		if (mPreview != null) {
			mPreview.setSendingData(isSendingData);
		}
		if (cameraSocket != null) {
			cameraSocket.close();
			cameraSocket = null;

		}
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		float[] rotationMatrix;

		switch (event.sensor.getType()) {
		case Sensor.TYPE_ACCELEROMETER:
			accelerationValues = event.values.clone();
			rotationMatrix = generateRotationMatrix();

			if (rotationMatrix != null) {
				determineOrientation(rotationMatrix);
			}
			break;
		case Sensor.TYPE_MAGNETIC_FIELD:
			magneticValues = event.values.clone();
			rotationMatrix = generateRotationMatrix();

			if (rotationMatrix != null) {
				determineOrientation(rotationMatrix);
			}
			break;
		case Sensor.TYPE_PRESSURE:
			break;
		}
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
		// Log.d(TAG, String.format("Accuracy for sensor %s = %d", sensor.getName(), accuracy));
	}

	/**
	 * Generates a rotation matrix using the member data stored in accelerationValues and magneticValues.
	 * 
	 * @return The rotation matrix returned from {@link android.hardware.SensorManager#getRotationMatrix(float[], float[], float[], float[])} or <code>null</code> if either
	 *         <code>accelerationValues</code> or <code>magneticValues</code> is null.
	 */
	private float[] generateRotationMatrix() {
		float[] rotationMatrix = null;

		if (accelerationValues != null && magneticValues != null) {
			rotationMatrix = new float[16];
			boolean rotationMatrixGenerated;
			rotationMatrixGenerated = SensorManager.getRotationMatrix(rotationMatrix, null, accelerationValues, magneticValues);

			if (!rotationMatrixGenerated) {
				Log.w(TAG, getString(R.string.rotationMatrixGenFailureMessage));

				rotationMatrix = null;
			}
		}

		return rotationMatrix;
	}

	private float[] calculateOrientation() {
		float[] values = new float[3];
		float[] inputRotationMatrix = new float[9];
		float[] outputRotationMatrix = new float[9];

		SensorManager.getRotationMatrix(inputRotationMatrix, null, accelerationValues, magneticValues);

		if (isLandscape) {
			// Landscape
			SensorManager.remapCoordinateSystem(inputRotationMatrix, SensorManager.AXIS_Z, SensorManager.AXIS_MINUS_X, outputRotationMatrix);
		} else {
			// Portrait
			SensorManager.remapCoordinateSystem(inputRotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, outputRotationMatrix);
		}

		SensorManager.getOrientation(outputRotationMatrix, values);

		return values;
	}

	/**
	 * Uses the last read accelerometer and gravity values to determine if the device is face up or face down.
	 * 
	 * @param rotationMatrix
	 *            The rotation matrix to use if the orientation calculation
	 */
	private void determineOrientation(float[] rotationMatrix) {
		final float[] orientationValues = calculateOrientation();

		// float[] outR = new float[9];

		// boolean t= SensorManager.remapCoordinateSystem(rotationMatrix,
		// SensorManager.AXIS_X,
		// SensorManager.AXIS_Y,
		// outR);
		//
		// orientationValues = calculateOrientation();
		// SensorManager.getOrientation((t) ? outR : rotationMatrix, orientationValues);

		if (isSendingData) {
			Runnable updateDbRunnable = new Runnable() {
				@Override
				public void run() {
					try {
						ByteArrayOutputStream bas = new ByteArrayOutputStream();
						DataOutputStream ds = new DataOutputStream(bas);

						for (float value : orientationValues) {
							ds.writeFloat(value);
						}
						ds.flush();
						ds.close();
						bas.close();
						byte[] data = bas.toByteArray();

						try {
							DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName(host), sensorPort);
							sensorSocket.setBroadcast(true);
							sensorSocket.send(packet);
						} catch (Exception e) {
							Log.e(TAG, "Error: ", e);
						}
					} catch (IOException exception) {
						Log.e(TAG, "Error: ", exception);
					}
				}
			};
			Thread sensorThread = new Thread(updateDbRunnable);
			sensorThread.start();
		}
		// double yaw = Math.toDegrees(orientationValues[0]);
		double pitch = Math.toDegrees(orientationValues[1]);
		double roll = Math.toDegrees(orientationValues[2]);

		// double yaw = orientationValues[0];
		// double pitch = orientationValues[1];
		// double roll = orientationValues[2];

		sensorXLabel.setText(R.string.pitchLabel);
		sensorXValue.setText(String.valueOf(roundTwoDecimals(pitch)));

		sensorYLabel.setText(R.string.rollLabel);
		sensorYValue.setText(String.valueOf(roundTwoDecimals(roll)));

		// sensorZLabel.setText(R.string.yawLabel);
		// sensorZValue.setText(String.valueOf(roundTwoDecimals(yaw)));

		sensorXLabel.setVisibility(View.VISIBLE);
		sensorXValue.setVisibility(View.VISIBLE);
		sensorYLabel.setVisibility(View.VISIBLE);
		sensorYValue.setVisibility(View.VISIBLE);
		// sensorZLabel.setVisibility(View.VISIBLE);
		// sensorZValue.setVisibility(View.VISIBLE);
	}

	/**
	 * Updates the views for when the selected sensor is changed
	 */
	private void updateSelectedSensor() {
		// Clear any current registrations
		sensorManager.unregisterListener(this);

		// Enable the appropriate sensors
		sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), RATE);
		sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD), RATE);

		Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);

		// Only make registration call if device has a pressure sensor
		if (sensor != null) {
			sensorManager.registerListener(this, sensor, RATE);
		}
	}

	/** Check if this device has a camera */
	private boolean checkCameraHardware(Context context) {
		if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
			// this device has a camera
			return true;
		} else {
			// no camera on this device
			return false;
		}
	}

	public double roundTwoDecimals(double d) {
		DecimalFormat df = new DecimalFormat("#.0");
		return Double.valueOf(df.format(d));
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.sensing_view_menu, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		SharedPreferences.Editor editor = prefs.edit();

		switch (item.getItemId()) {
		case R.id.udpSettings:
			startActivityForResult(new Intent(this, SettingsActivity.class), R.id.udpSettings);
			break;
		case R.id.displayOrienration:
			if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
				isLandscape = false;
			} else {
				setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
				isLandscape = true;
			}
			if (mPreview != null)
				mPreview.setCameraDisplayOrientation(isLandscape ? 0 : 90);
			editor.putBoolean("landscapeMode", isLandscape);
			editor.commit();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case R.id.udpSettings:
			if (resultCode == RESULT_OK) {
				host = prefs.getString(getString(R.string.ipAddressKey), "255.255.255.255");
				sensorPort = Integer.parseInt(prefs.getString(getString(R.string.sensorPortKey), "9001"));
				cameraPort = Integer.parseInt(prefs.getString(getString(R.string.cameraPortKey), "9002"));
			}
		default:
			break;
		}
	}
}