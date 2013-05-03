package gov.nasa.arc.sensing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class CameraPreview extends SurfaceView implements PreviewCallback, SurfaceHolder.Callback {

	private final static String TAG = "Camera Preview";
	private final static int WIDTH = 320;
	private final static int HEIGHT = 240;

	private SurfaceHolder mHolder;
	private Camera mCamera;
	private int mnCameraOrientation;

	private boolean isSendingData;
	private int port = 9002;
	private String host;
	private DatagramSocket socket = null;

	public CameraPreview(Context context) {
		super(context);
		mHolder = getHolder();
		mHolder.addCallback(this);
		mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
	}
	
	public CameraPreview(Context context, AttributeSet attributeSet) {
		super(context, attributeSet);
		mHolder = getHolder();
		mHolder.addCallback(this);
		mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
	}

	public void setCamera(Camera camera) {
		mCamera = camera;
		if (mCamera != null) {
			requestLayout();
		}
	}

	public void setUDPSettings(DatagramSocket socket, String host, int port) {
		this.socket = socket;
		this.host = host;
		this.port = port;
	}

	public void setSendingData(boolean isSendingData) {
		this.isSendingData = isSendingData;
	}

	public void surfaceCreated(SurfaceHolder holder) {
		mCamera = getCameraInstance();
		try {
			if (mCamera != null) {
				mCamera.setPreviewDisplay(holder);
				mCamera.startPreview();
			}
		} catch (IOException e) {
			mCamera.release();
			mCamera = null;
			Log.d(TAG, "Error setting camera preview: " + e.getMessage());
		}
	}

	public void surfaceDestroyed(SurfaceHolder holder) {
		if (mCamera != null) {
			mCamera.setPreviewCallback(null);
			mCamera.stopPreview();
			mCamera.release();
			mCamera = null;
		}
	}

	public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
		if (mHolder.getSurface() == null) {
			// preview surface does not exist
			return;
		}

		// stop preview before making changes
		try {
			mCamera.stopPreview();
		} catch (Exception e) {
			// ignore: tried to stop a non-existent preview
		}

		// set preview size and make any resize, rotate or
		// reformatting changes here
		Camera.Parameters parameters = mCamera.getParameters();
		parameters.setPreviewSize(WIDTH, HEIGHT);
		parameters.setPreviewFrameRate(30);
		parameters.setSceneMode(Camera.Parameters.SCENE_MODE_SPORTS);
		parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
		parameters.setColorEffect(Camera.Parameters.EFFECT_NONE);
		mCamera.setParameters(parameters);

		// start preview with new settings
		try {
			mCamera.setDisplayOrientation(mnCameraOrientation);
			mCamera.setPreviewDisplay(mHolder);
			mCamera.setPreviewCallback(this);
			mCamera.startPreview();

		} catch (Exception e) {
			Log.d(TAG, "Error starting camera preview: " + e.getMessage());
		}
	}

	@Override
	protected void onLayout(boolean changed, int l, int t, int r, int b) {
		// Only support portrait for now so do nothing
	}

	public void setCameraDisplayOrientation(int orientation) {
		mnCameraOrientation = orientation;
	}

	/** A safe way to get an instance of the Camera object. */
	public static Camera getCameraInstance() {
		Camera camera = null;
		try {
			camera = Camera.open(); // attempt to get a Camera instance
		} catch (Exception e) {
			Log.e(TAG, "Camera is not available (in use or does not exist: " + e.getMessage());
		}
		return camera; // returns null if camera is unavailable
	}

	private static int HEADER_SIZE = 5;
	private static int DATAGRAM_MAX_SIZE = 1450 - HEADER_SIZE;
	private int frame_nb = 0;

	// Preview callback used whenever new frame is available...send image via UDP !!!
	@Override
	public void onPreviewFrame(byte[] data, Camera camera) {

		if (!isSendingData || socket == null)
			return;

		final byte[] buffer = convertYuvToJPEG(data, WIDTH, HEIGHT);

		Runnable updateDbRunnable = new Runnable() {
			@Override
			public void run() {
				int size_p = 0, i;
				Log.e(TAG, "SIZE: " + buffer.length);

				int nb_packets = (int) Math.ceil(buffer.length / (float) DATAGRAM_MAX_SIZE);
				int size = DATAGRAM_MAX_SIZE;

				/* Loop through slices */
				for (i = 0; i < nb_packets; i++) {
					if (i > 0 && i == nb_packets - 1)
						size = buffer.length - i * DATAGRAM_MAX_SIZE;

					/* Set additional header */
					byte[] data2 = new byte[HEADER_SIZE + size];
					data2[0] = (byte) frame_nb;
					data2[1] = (byte) nb_packets;
					data2[2] = (byte) i;
					data2[3] = (byte) (size >> 8);
					data2[4] = (byte) size;

					/* Copy current slice to byte array */
					System.arraycopy(buffer, i * DATAGRAM_MAX_SIZE, data2, HEADER_SIZE, size);

					try {
						size_p = data2.length;
						DatagramPacket packet = new DatagramPacket(data2, size_p, InetAddress.getByName(host), port);
						socket.setBroadcast(true);
						socket.send(packet);
					} catch (Exception e) {
						Log.e(TAG, "Error: ", e);
					}
				}
				frame_nb++;

				if (frame_nb == 128)
					frame_nb = 0;

			}
		};
		Thread videoThread = new Thread(updateDbRunnable);
		videoThread.start();
	}

	public byte[] convertYuvToJPEG(byte[] data, int width, int height) {
		YuvImage img = new YuvImage(data, ImageFormat.NV21, width, height, null);
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		img.compressToJpeg(new Rect(0, 0, width, height), 70, bos);
		return bos.toByteArray();
	}

	/**
	 * Converts YUV420 NV21 to RGB8888
	 * 
	 * @param data
	 *            byte array on YUV420 NV21 format.
	 * @param width
	 *            pixels width
	 * @param height
	 *            pixels height
	 * @return a RGB8888 pixels int array. Where each int is a pixels ARGB.
	 */
	public static int[] convertYUV420_NV21toRGB8888(byte[] data, int width, int height) {
		int size = width * height;
		int offset = size;
		int[] pixels = new int[size];
		int u, v, y1, y2, y3, y4;

		// i percorre os Y and the final pixels
		// k percorre os pixles U e V
		for (int i = 0, k = 0; i < size; i += 2, k += 2) {
			y1 = data[i] & 0xff;
			y2 = data[i + 1] & 0xff;
			y3 = data[width + i] & 0xff;
			y4 = data[width + i + 1] & 0xff;

			u = data[offset + k] & 0xff;
			v = data[offset + k + 1] & 0xff;
			u = u - 128;
			v = v - 128;

			pixels[i] = convertYUVtoRGB(y1, u, v);
			pixels[i + 1] = convertYUVtoRGB(y2, u, v);
			pixels[width + i] = convertYUVtoRGB(y3, u, v);
			pixels[width + i + 1] = convertYUVtoRGB(y4, u, v);

			if (i != 0 && (i + 2) % width == 0)
				i += width;
		}

		return pixels;
	}

	private static int convertYUVtoRGB(int y, int u, int v) {
		int r, g, b;

		r = y + (int) 1.402f * v;
		g = y - (int) (0.344f * u + 0.714f * v);
		b = y + (int) 1.772f * u;
		r = r > 255 ? 255 : r < 0 ? 0 : r;
		g = g > 255 ? 255 : g < 0 ? 0 : g;
		b = b > 255 ? 255 : b < 0 ? 0 : b;
		return 0xff000000 | (b << 16) | (g << 8) | r;
	}

}
