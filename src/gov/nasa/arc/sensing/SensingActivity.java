package gov.nasa.arc.sensing;

import ioio.lib.api.DigitalOutput;
import ioio.lib.api.IOIO;
import ioio.lib.api.Uart;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.util.BaseIOIOLooper;
import ioio.lib.util.IOIOLooper;
import ioio.lib.util.android.IOIOActivity;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
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

public class SensingActivity extends IOIOActivity {
	private static final String TAG = "SensingActivity";

	private TextView sensorXValue;
	private TextView sensorYValue;
	private DatagramSocket sensorSocket = null;
	private boolean isTransmitting;
	private boolean isIOIOConnected = false;
	private ToggleButton togglebutton;
	private SharedPreferences prefs;
	private String host;
	private int sensorPort;

	private Handler mHandler = new Handler();

	public static boolean isLandscape;
	public static SensorFusion mSensorFusion = null;

	final ByteBuffer byteBuffer = ByteBuffer.allocate(12);

	private final Runnable mRunnable = new Runnable() {
		@Override
		public void run() {
			if (mSensorFusion == null)
				return;

			mHandler.postDelayed(this, 100); // Update IMU data every 50ms

			sensorXValue.setText(mSensorFusion.pitch);
			sensorYValue.setText(mSensorFusion.roll);

			if (isTransmitting && !isIOIOConnected) {

				Runnable updateDbRunnable = new Runnable() {
					@Override
					public void run() {
						try {
							for (float value : mSensorFusion.getFusedOrientation()) {
								byteBuffer.putFloat(value);
							}
							byte[] data = byteBuffer.array();
							DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName(host), sensorPort);
							sensorSocket.setBroadcast(true);
							sensorSocket.send(packet);

						} catch (IOException exception) {
							Log.e(TAG, "Error: ", exception);
						} finally {
							byteBuffer.clear();
						}
					}
				};
				Thread sensorThread = new Thread(updateDbRunnable);
				sensorThread.start();
			}
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		super.setContentView(R.layout.sensing);

		// Keep the screen on so that changes in orientation can be easily observed
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		// Get a reference to the sensor service
		SensorManager sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

		Typeface tf = Typeface.createFromAsset(getAssets(), "fonts/digital_bold.ttf");

		// Initialize references to the UI views that will be updated in the code
		findViewById(R.id.sensorXLabel);
		sensorXValue = (TextView) findViewById(R.id.sensorXValue);
		sensorXValue.setTypeface(tf);
		findViewById(R.id.sensorYLabel);
		sensorYValue = (TextView) findViewById(R.id.sensorYValue);
		sensorYValue.setTypeface(tf);

		PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
		prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		host = prefs.getString(getString(R.string.ipAddressKey), "255.255.255.255");
		sensorPort = Integer.parseInt(prefs.getString(getString(R.string.sensorPortKey), "9001"));
		isLandscape = prefs.getBoolean("landscapeMode", true);
		setRequestedOrientation((isLandscape) ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

		togglebutton = (ToggleButton) findViewById(R.id.sendDataToggleButton);
		togglebutton.setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(View v) {
				Vibrator vib = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
				vib.vibrate(45);
				isTransmitting = (((ToggleButton) v).isChecked()) ? true : false;
				try {
					sensorSocket = new DatagramSocket();
				} catch (Exception exception) {
					Log.e(TAG, "Error: ", exception);
				}
			}
		});
		mSensorFusion = new SensorFusion(sensorManager);
		mHandler.postDelayed(mRunnable, 50); // Update IMU data every 50ms
	}

	@Override
	protected void onResume() {
		super.onResume();

		// Restore the sensor listeners when user resumes the application.
		mSensorFusion.initListeners();

		mHandler.postDelayed(mRunnable, 50); // Update IMU data every 50ms
	}

	@Override
	protected void onPause() {
		super.onPause();

		mHandler.removeCallbacks(mRunnable);

		// Unregister sensor listeners to prevent the activity from draining the device's battery.
		mSensorFusion.unregisterListeners();

		isTransmitting = false;
		togglebutton.setChecked(false);
		if (sensorSocket != null) {
			sensorSocket.close();
			sensorSocket = null;
		}
	}

	@Override
	protected void onStop() {
		super.onStop();

		// unregister sensor listeners to prevent the activity from draining the device's battery.
		mSensorFusion.unregisterListeners();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();

		mSensorFusion.unregisterListeners();
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
			}
		default:
			break;
		}
	}

	/**
	 * This is the thread on which all the IOIO activity happens. It will be run every time the application is resumed and aborted when it is paused. The method setup() will be called right after a
	 * connection with the IOIO has been established (which might happen several times!). Then, loop() will be called repetitively until the IOIO gets disconnected.
	 */
	class Looper extends BaseIOIOLooper {
		private DigitalOutput led;

		Uart uart;
		int pinToDIN = 39; // Serial data is sent on this pin into the XBee (RX or DIN) to be transmitted wirelessly
		OutputStream uartOutputStream;

		/**
		 * Called every time a connection with IOIO has been established. Typically used to open pins.
		 * 
		 * @throws ConnectionLostException
		 *             When IOIO connection is lost.
		 * 
		 * @see ioio.lib.util.AbstractIOIOActivity.IOIOThread#setup()
		 */
		@Override
		protected void setup() throws ConnectionLostException {

			led = ioio_.openDigitalOutput(0, true);

			uart = ioio_.openUart(IOIO.INVALID_PIN, pinToDIN, 9600, Uart.Parity.NONE, Uart.StopBits.ONE);
			uartOutputStream = uart.getOutputStream();
		}

		/**
		 * Called repetitively while the IOIO is connected.
		 * 
		 * @throws ConnectionLostException
		 *             When IOIO connection is lost.
		 * 
		 * @see ioio.lib.util.AbstractIOIOActivity.IOIOThread#loop()
		 */
		@Override
		public void loop() throws ConnectionLostException, InterruptedException {
			isIOIOConnected = true;
			led.write(!isTransmitting);
			if (isTransmitting) {
				try {
					for (float value : mSensorFusion.getFusedOrientation()) {
						byteBuffer.putFloat(value);
					}
					byte[] data = byteBuffer.array();
					uartOutputStream.write(data);
					uartOutputStream.flush();
					Log.d("DEBUG", String.valueOf(byteBuffer.array().length));
					byteBuffer.clear();
				} catch (IOException exception) {
					Log.e(TAG, "Error: ", exception);
				} catch (Exception e) {
					Log.e(TAG, "Error: ", e);
				}

			}
			Thread.sleep(300);
		}

		@Override
		public void disconnected() {
			uart.close();
			isIOIOConnected = false;
			isTransmitting = false;
		}
	}

	/**
	 * A method to create our IOIO thread.
	 * 
	 * @see ioio.lib.util.AbstractIOIOActivity#createIOIOThread()
	 */
	@Override
	protected IOIOLooper createIOIOLooper() {
		return new Looper();
	}

}