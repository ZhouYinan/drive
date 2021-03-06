package com.shemanigans.mime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;

import android.app.Service;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

public class ServiceBinder extends Service {

	private final static String TAG = BluetoothLeService.class.getSimpleName();

	private final String LIST_NAME = "NAME";
	private final String LIST_UUID = "UUID";

	public String mDeviceName;
	private String data;

	private int i = 0;
	private int j = 0;
	private int k = 0;
	private int arraySize = 65000;

	public double[] values = {1, 2, 3, 4};

	private String[] imp = new String[arraySize];

	Calendar c = Calendar.getInstance();

	private File DataDir = new File(Environment.getExternalStorageDirectory() + "/Biohm/");
	private File duodecimalMinute = new File(DataDir, "duodecimalMinute.txt");
	private File monoHour  = new File(DataDir, "monoHour.txt");

	private BluetoothLeService mBluetoothLeService;

	private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
			new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

	private BluetoothGattCharacteristic mSampleRateCharacteristic;
	private BluetoothGattCharacteristic mACFrequencyCharacteristic;

	// Service connection to bind to BluetoothLeService
	private final ServiceConnection mServiceConnection = new ServiceConnection() {

		@Override
		public void onServiceConnected(ComponentName componentName, IBinder service) {
			mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
			mBluetoothLeService.clientConnected();
			getGattServices(mBluetoothLeService.getSupportedGattServices());
		}

		@Override
		public void onServiceDisconnected(ComponentName componentName) {
			mBluetoothLeService.clientDisconnected();
			mBluetoothLeService = null;
		}
	};

	private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			final String action = intent.getAction();

			// Get all the supported services and characteristics.
			if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
				getGattServices(mBluetoothLeService.getSupportedGattServices());
			} 

			else if (BluetoothLeService.ACTION_DATA_AVAILABLE_BIOIMPEDANCE.equals(action)) {
				values = intent.getDoubleArrayExtra(BluetoothLeService.EXTRA_DATA_BIOIMPEDANCE_DOUBLE);
				data = intent.getStringExtra(BluetoothLeService.EXTRA_DATA_BIOIMPEDANCE_STRING);

				// Store data in preallocated memory to conserve resources.
				if(i <= (arraySize - 1)) { // at 90 hz for notifications, this amounts to every 12 minutes.
					imp[i] = data;
				}
				else {
					i = -1;
					exportToText(imp, duodecimalMinute);

					if(fileCheck(monoHour)) {
						exportToText(imp, monoHour);
					}

					j++;
				}

				if(j >= 5) {
					// 12 minutes, 5 times gives an hour of data.
					// Delete and replace hourly data and copy file to "daily file" as needed.
					j = 0;
					k++;
					updateData();
				}

				if(k >= 6) { // Save data every 6 hours.
					k = 0;
					SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");
					String strDate = sdf.format(c.getTime());
					File hexHour  = new File(DataDir, strDate + ".txt");
					try {
						copyFile(monoHour, hexHour);
						monoHour.delete();
					}
					catch (IOException e) {
						e.printStackTrace();
					}
				}
				i++;
			}
		}
	};


	@Override
	public void onCreate() {
		Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
		bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
		registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		mDeviceName = intent.getStringExtra(DeviceControlActivity.EXTRA_DEVICE_NAME_BINDER);
		DataDir.mkdir();
		if(fileCheck(duodecimalMinute)) {
			duodecimalMinute.delete();
		}
		if(fileCheck(monoHour)) {
			monoHour.delete();
		}
		fileCheckInitial();
		return START_STICKY;
	}

	public class LocalBinder extends Binder {
		ServiceBinder getService() {
			return ServiceBinder.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	private final IBinder mBinder = new LocalBinder();

	@Override
	public void onDestroy() {
		unregisterReceiver(mGattUpdateReceiver);
		unbindService(mServiceConnection);
		mBluetoothLeService.clientDisconnected();
	}

	// Called when the service is first started to clear old data.
	public void exportToTextInitial() {
		// write files to SD
		try {
			FileOutputStream fOutHourly = new FileOutputStream(duodecimalMinute, true);
			OutputStreamWriter myOutWriter = new OutputStreamWriter(fOutHourly);
			duodecimalMinute = new File(DataDir, "duodecimalMinute.txt");
			myOutWriter.append(tableTitle());
			myOutWriter.close();
			fOutHourly.close();
		} 
		catch (Exception e) {
			Log.i(TAG, e.getMessage());
			//Toast.makeText(getBaseContext(), e.getMessage(),
			//Toast.LENGTH_SHORT).show();
		}
	}
	
	// write on SD card file data in the text box
	public void exportToText(String[] value, File file) {
		final String[] writer = value;
		final File exported = file;

		// Run on new thread so UI thread isn't blocked.
		new Thread(new Runnable() {
			public void run() {
				try {
					FileOutputStream fOut = new FileOutputStream(exported, true);
					OutputStreamWriter myOutWriter = new OutputStreamWriter(fOut);

					for (int i = 0; i < writer.length; i++) {
						myOutWriter.append(writer[i]);
						myOutWriter.append("\n");
					}

					myOutWriter.close();
					fOut.close();
				} 
				catch (Exception e) {
					Log.i(TAG, e.getMessage());
				}
			}
		}).start();

	}

	public void updateData() {	
		new Thread(new Runnable() {
			public void run() {
				try {
					if(fileCheck(monoHour)) {
						//check if daily exists. if so, delete new hourly data...
						// ... as data was already appended to hourly data (monoHour).
						duodecimalMinute.delete();
						fileCheckInitial();
					}
					// else, copy (first time hourly data is created). 
					else {
						copyFile(duodecimalMinute, monoHour);
						duodecimalMinute.delete();
						fileCheckInitial();
					}
				} 
				catch (IOException e) {
					e.printStackTrace();
				}
			}
		}).start();		
	}
	
	// Copies a file.
	// Used when the initial array is full and needs to be saved.
	public void copyFile(File source, File dest)
			throws IOException {
		FileChannel inputChannel = null;
		FileChannel outputChannel = null;
		try {
			inputChannel = new FileInputStream(source).getChannel();
			outputChannel = new FileOutputStream(dest).getChannel();
			outputChannel.transferFrom(inputChannel, 0, inputChannel.size());
			Log.i(TAG, "Size of channel: " + inputChannel.size());
		} 

		finally {
			inputChannel.close();
			outputChannel.close();
		}
	}

	public void fileCheckInitial() {
		if(duodecimalMinute.exists() == false) {
			exportToTextInitial();
		}
	}

	public boolean fileCheck(File check) {
		if(!check.exists()) {
			return false;
		}
		else {
			return true;
		}
	}

	// Checks if external storage is available for read and write.
	public boolean isExternalStorageWritable() {
		String state = Environment.getExternalStorageState();
		if (Environment.MEDIA_MOUNTED.equals(state)) {
			return true;
		}
		return false;
	}

	// Checks if external storage is available to at least read.
	public boolean isExternalStorageReadable() {
		String state = Environment.getExternalStorageState();
		if (Environment.MEDIA_MOUNTED.equals(state) ||
				Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
			return true;
		}
		return false;
	}

	public String tableTitle() {
		return 	fixedLengthString("X", 6) 
				+ fixedLengthString("Y", 6)
				+ fixedLengthString("Z", 6)
				+ fixedLengthString("Ω", 9)
				+ fixedLengthString("θ", 8)
				+ fixedLengthString("KHz", 4)
				+ "\n";
	}

	public static String fixedLengthString(String string, int length) {
		return String.format("%-"+length+ "s", string);
	}

	// Writes a value 1 byte in length to the sample rate characteristic.
	public void writeSampleRateCharacteristic(int value) {
		mBluetoothLeService.writeCharacteristic(mSampleRateCharacteristic, value);
	}

	// Writes a value 3 bytes in length to the AC frequency characteristic. 
	public void writeFrequencySweepCharacteristic(byte[] values) {
		mBluetoothLeService.writeCharacteristicArray(mACFrequencyCharacteristic, values);
	}

	// Finds a characteristic by comparing bytes.
	private boolean findCharacteristic(String characteristicUUID, String referenceUUID) {
		byte[]characteristic;
		byte[] reference;
		boolean check = false;
		characteristic = characteristicUUID.getBytes();
		reference = referenceUUID.getBytes();
		for(int i = 0; i< characteristic.length; i++) {
			if(characteristic[i] == reference[i]) {
				check = true;
			}
			else {
				check = false;
				i = characteristic.length;
			}
		}
		return check;
	}

	private static IntentFilter makeGattUpdateIntentFilter() {
		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
		intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
		intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
		intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE_BIOIMPEDANCE);
		return intentFilter;
	}

	private void getGattServices(List<BluetoothGattService> gattServices) {
		if (gattServices == null) return;
		String uuid = null;
		String unknownServiceString = getResources().getString(R.string.unknown_service);
		String unknownCharaString = getResources().getString(R.string.unknown_characteristic);
		ArrayList<HashMap<String, String>> gattServiceData = new ArrayList<HashMap<String, String>>();

		ArrayList<ArrayList<HashMap<String, String>>> gattCharacteristicData
		= new ArrayList<ArrayList<HashMap<String, String>>>();

		mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

		// Loops through available GATT Services.
		for (BluetoothGattService gattService : gattServices) {
			HashMap<String, String> currentServiceData = new HashMap<String, String>();
			uuid = gattService.getUuid().toString();

			currentServiceData.put(
					LIST_NAME, SampleGattAttributes.lookup(uuid, unknownServiceString));

			currentServiceData.put(LIST_UUID, uuid);
			gattServiceData.add(currentServiceData);

			ArrayList<HashMap<String, String>> gattCharacteristicGroupData =
					new ArrayList<HashMap<String, String>>();

			List<BluetoothGattCharacteristic> gattCharacteristics =
					gattService.getCharacteristics();

			ArrayList<BluetoothGattCharacteristic> charas =
					new ArrayList<BluetoothGattCharacteristic>();

			// Loops through available Characteristics.
			for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
				if ((gattCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) > 0) {

					if(findCharacteristic(gattCharacteristic.getUuid().toString(), 
							SampleGattAttributes.SAMPLE_RATE)) {
						mSampleRateCharacteristic = gattCharacteristic;
					}
					if(findCharacteristic(gattCharacteristic.getUuid().toString(), 
							SampleGattAttributes.AC_FREQ)) {
						mACFrequencyCharacteristic = gattCharacteristic;
					}
				}
				charas.add(gattCharacteristic);
				HashMap<String, String> currentCharaData = new HashMap<String, String>();
				uuid = gattCharacteristic.getUuid().toString();
				currentCharaData.put(
						LIST_NAME, SampleGattAttributes.lookup(uuid, unknownCharaString));
				currentCharaData.put(LIST_UUID, uuid);
				gattCharacteristicGroupData.add(currentCharaData);
			}
			mGattCharacteristics.add(charas);
			gattCharacteristicData.add(gattCharacteristicGroupData);
		}
	}

}
