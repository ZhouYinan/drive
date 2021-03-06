package com.shemanigans.mime;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class BluetoothLeService extends Service {
	private final static String TAG = BluetoothLeService.class.getSimpleName();

	private BluetoothManager mBluetoothManager;
	private BluetoothAdapter mBluetoothAdapter;
	private String mBluetoothDeviceAddress;
	private String mDeviceAddressRestore;
	private String mDeviceNameRestore;
	private BluetoothGatt mBluetoothGatt;
	private int mConnectionState = STATE_DISCONNECTED;
	private static int sNumBoundClients = 0;
	private static int GATT_INDETERMINATE = 8;
	
	// BioImpedance data parsing variables
	
	private byte ACfrequency = 0;
	private int phaseAngleUnit = 0;
	private int phaseAngleTenth = 0;
	private int phaseAngleThousandth = 0;
	private int arrayStepper = 0;
	private double phaseAngleWhole = 0;
	
	public final static double[] Z_BLE = {1, 2, 3, 4};

	private static final int STATE_DISCONNECTED = 0;
	private static final int STATE_CONNECTING = 1;
	private static final int STATE_CONNECTED = 2;

	public final static String ACTION_GATT_CONNECTED =
			"com.example.bluetooth.le.ACTION_GATT_CONNECTED";
	public final static String ACTION_GATT_CONNECTING =
			"com.example.bluetooth.le.ACTION_GATT_CONNECTING";
	public final static String ACTION_GATT_DISCONNECTED =
			"com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
	public final static String ACTION_GATT_SERVICES_DISCOVERED =
			"com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
	public final static String ACTION_DATA_AVAILABLE =
			"com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
	public final static String ACTION_DATA_AVAILABLE_BIOIMPEDANCE =
			"com.example.bluetooth.le.ACTION_DATA_AVAILABLE_BIOIMPEDANCE";
	public final static String ACTION_DATA_AVAILABLE_SAMPLE_RATE =
			"com.example.bluetooth.le.ACTION_DATA_AVAILABLE_SAMPLE_RATE";
	public final static String ACTION_DATA_AVAILABLE_FREQUENCY_PARAMS =
			"com.example.bluetooth.le.ACTION_DATA_AVAILABLE_FREQUENCY_PARAMS";
	public final static String EXTRA_DATA =
			"com.example.bluetooth.le.EXTRA_DATA";
	public final static String EXTRA_DATA_BIOIMPEDANCE_STRING =
			"com.example.bluetooth.le.EXTRA_DATA_BIOIMPEDANCE_STRING";
	public final static String EXTRA_DATA_BIOIMPEDANCE_DOUBLE =
			"com.example.bluetooth.le.EXTRA_DATA_BIOIMPEDANCE_DOUBLE";
	public final static String EXTRA_DATA_SAMPLE_RATE =
			"com.example.bluetooth.le.EXTRA_DATA_SAMPLE_RATE";
	public final static String EXTRA_DATA_FREQUENCY_PARAMS =
			"com.example.bluetooth.le.EXTRA_DATA_FREQUENCY_PARAMS";
	
	public final static UUID UUID_HEART_RATE_MEASUREMENT =
			UUID.fromString(SampleGattAttributes.HEART_RATE_MEASUREMENT);
	public final static UUID UUID_BIOIMPEDANCE_DATA = 
			UUID.fromString(SampleGattAttributes.BIOIMPEDANCE_DATA);
	public final static UUID UUID_SAMPLE_RATE = 
			UUID.fromString(SampleGattAttributes.SAMPLE_RATE);
	public final static UUID UUID_AC_FREQUENCY = 
			UUID.fromString(SampleGattAttributes.AC_FREQ);

	// Queue for reading multiple characteristics due to delay induced by callback.
	private Queue<BluetoothGattCharacteristic> characteristicReadQueue = new LinkedList<BluetoothGattCharacteristic>();

	// Implements callback methods for GATT events that the app cares about.  For example,
	// connection change and services discovered.
	private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			String intentAction;
			if (newState == BluetoothProfile.STATE_CONNECTED) {
				intentAction = ACTION_GATT_CONNECTED;
				mConnectionState = STATE_CONNECTED;
				broadcastUpdate(intentAction);
				Log.i(TAG, "Connected to GATT server.");
				// Attempts to discover services after successful connection.
				Log.i(TAG, "Attempting to start service discovery:" +
						mBluetoothGatt.discoverServices());
			} 
			else if (newState == BluetoothProfile.STATE_CONNECTING) {
				intentAction = ACTION_GATT_CONNECTING;
				mConnectionState = STATE_CONNECTING;
				Log.i(TAG, "Attempting to connect to GATT server...");
				broadcastUpdate(intentAction);
			}
			else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
				intentAction = ACTION_GATT_DISCONNECTED;
				mConnectionState = STATE_DISCONNECTED;
				Log.i(TAG, "Disconnected from GATT server.");
				broadcastUpdate(intentAction);
			}
		}

		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
			} else {
				Log.w(TAG, "onServicesDiscovered received: " + status);
			}
		}

		@Override
		// Checks queue for characteristics to be read and reads them
		public void onCharacteristicRead(BluetoothGatt gatt,
				BluetoothGattCharacteristic characteristic,
				int status) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				if(characteristicReadQueue.size() > 0) {
					characteristicReadQueue.remove();
				}
				if(findCharacteristic(
						characteristic.getUuid().toString(), 
						SampleGattAttributes.SAMPLE_RATE)) {
					broadcastUpdate(ACTION_DATA_AVAILABLE_SAMPLE_RATE, characteristic);
				}
				else if(findCharacteristic(
						characteristic.getUuid().toString(), 
						SampleGattAttributes.AC_FREQ)) {
					broadcastUpdate(ACTION_DATA_AVAILABLE_FREQUENCY_PARAMS, characteristic);
				}
				else {
					broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
				}
			}
			else {
				Log.i(TAG, "onCharacteristicRead error: " + status);
			}
			if(characteristicReadQueue.size() > 0) {
				mBluetoothGatt.readCharacteristic(characteristicReadQueue.element());
			}
		}

		/*@Override
		public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {         
			if (status == BluetoothGatt.GATT_SUCCESS) {
				Log.i(TAG, "Callback: Wrote GATT Descriptor successfully."); 
				if(descriptorWriteQueue.size() > 0) {
					descriptorWriteQueue.remove();  //pop the item that we just finishing writing
				}
			}           
			else{
				Log.i(TAG, "Callback: Error writing GATT Descriptor: "+ status);
			}
			Log.i(TAG, "" + descriptorWriteQueue.size());
			//if there is more to write, do it!
			if(descriptorWriteQueue.size() > 0) {
				mBluetoothGatt.writeDescriptor(descriptorWriteQueue.element());
			}
		};*/

		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt,
				BluetoothGattCharacteristic characteristic) {
			if(UUID_BIOIMPEDANCE_DATA.equals(characteristic.getUuid())) {
				broadcastUpdate(ACTION_DATA_AVAILABLE_BIOIMPEDANCE, characteristic);
			}
		}
	};

	private void broadcastUpdate(final String action) { // used to send broadcasts that don't have attached data
		final Intent intent = new Intent(action);
		sendBroadcast(intent);
	}

	private void broadcastUpdate(final String action, // used to send broadcasts that have attached data
			final BluetoothGattCharacteristic characteristic) { 
		final Intent intent = new Intent(action);

		// This is special handling for the Heart Rate Measurement profile.  Data parsing is
		// carried out as per profile specifications:
		// http://developer.bluetooth.org/gatt/characteristics/Pages/CharacteristicViewer.aspx?u=org.bluetooth.characteristic.heart_rate_measurement.xml

		if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
			int flag = characteristic.getProperties(); 
			int format = -1;
			if ((flag & 0x01) != 0) {
				format = BluetoothGattCharacteristic.FORMAT_UINT16;
				Log.d(TAG, "Heart rate format UINT16.");
			} else {
				format = BluetoothGattCharacteristic.FORMAT_UINT8;
				Log.d(TAG, "Heart rate format UINT8.");
			}
			final int heartRate = characteristic.getIntValue(format, 1);
			Log.d(TAG, String.format("Received heart rate: %d", heartRate));
			intent.putExtra(EXTRA_DATA, String.valueOf(heartRate));
		} 

		else if (UUID_BIOIMPEDANCE_DATA.equals(characteristic.getUuid())) {
			// Formatting for transceiver data
			final byte[] data = characteristic.getValue();
			if (data != null && data.length > 0) {
				final StringBuilder stringBuilder = new StringBuilder(data.length);
				final StringBuilder impVal = new StringBuilder(data.length);
				
				for(byte byteChar : data) {
					
					if(arrayStepper <= 2) {
						Z_BLE[arrayStepper] = byteChar;
						stringBuilder.append(fixedLengthString(String.valueOf(byteChar), 6));
					}
					else if(arrayStepper > 2 && arrayStepper <= 5) {
						if(String.valueOf(byteChar).length() == 1) {
							impVal.append("0" + String.valueOf(byteChar));
						}
						else {
							impVal.append(String.valueOf(byteChar));
						}							
					}
					else if(arrayStepper == 6) {
						phaseAngleUnit = byteChar;
					}
					else if(arrayStepper == 7) {
						phaseAngleTenth = byteChar;
					}
					else if(arrayStepper == 8) {
						phaseAngleThousandth = byteChar;
					}
					else if(arrayStepper > 8) {
						ACfrequency = byteChar;
					}
					arrayStepper++;
				}
				arrayStepper = 0;
				
				if(phaseAngleThousandth > 0) {
					phaseAngleWhole = (phaseAngleUnit * 10000);
					phaseAngleTenth *= 100;
					phaseAngleWhole += phaseAngleTenth;
					phaseAngleWhole += phaseAngleThousandth;
					phaseAngleWhole /= 10000;
				}
				else {
					phaseAngleWhole = (phaseAngleUnit * 10000);
					phaseAngleTenth *= 100;
					phaseAngleWhole += phaseAngleTenth;
					phaseAngleWhole += Math.abs(phaseAngleThousandth);
					phaseAngleWhole /= -10000;
				}
				double actualVal = Double.parseDouble(impVal.toString());
				actualVal = actualVal / 1000;
				Z_BLE[3] = actualVal;
				stringBuilder.append(fixedLengthString(String.valueOf(actualVal), 9));
				stringBuilder.append(fixedLengthString(String.valueOf(phaseAngleWhole), 8));
				stringBuilder.append(fixedLengthString(String.valueOf(ACfrequency), 4));
				intent.putExtra(EXTRA_DATA_BIOIMPEDANCE_STRING, new String(stringBuilder.toString()));
				intent.putExtra(EXTRA_DATA_BIOIMPEDANCE_DOUBLE, Z_BLE);
			}
		} 

		else if (UUID_SAMPLE_RATE.equals(characteristic.getUuid())) {
			final byte[] data = characteristic.getValue();
			if (data != null && data.length > 0) {
				intent.putExtra(EXTRA_DATA_SAMPLE_RATE, data[0]);
				Log.i(TAG, "Got default sample rate.");
			}
		}

		else if (UUID_AC_FREQUENCY.equals(characteristic.getUuid())) {
			final byte[] data = characteristic.getValue();
			if (data != null && data.length > 0) {
				intent.putExtra(EXTRA_DATA_FREQUENCY_PARAMS, data);
				Log.i(TAG, "Got default frequency sweep params.");
			}
		}

		else {
			// For all other profiles, writes the data formatted in decimal.
			final byte[] data = characteristic.getValue();
			if (data != null && data.length > 0) {
				final StringBuilder stringBuilder = new StringBuilder(data.length);
				for(byte byteChar : data) {
					Log.i(TAG, Byte.toString(byteChar) + " and frivolous details");
					stringBuilder.append(Byte.toString(byteChar) + " ");
				}
				intent.putExtra(EXTRA_DATA, new String(data) + "\n" + stringBuilder.toString());
			}
		}
		sendBroadcast(intent);
	}

	public class LocalBinder extends Binder {
		BluetoothLeService getService() {
			return BluetoothLeService.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	@Override
	public boolean onUnbind(Intent intent) {
		// After using a given device, you should make sure that BluetoothGatt.close() is called
		// such that resources are cleaned up properly.  In this particular example, close() is
		// invoked when the UI is disconnected from the Service.
		close();
		return super.onUnbind(intent);
	}

	private final IBinder mBinder = new LocalBinder();

	/**
	 * Initializes a reference to the local BT adapter.
	 *
	 * @return Return true if the initialization is successful.
	 */
	public boolean initialize() {
		// For API level 18 and above, get a reference to BluetoothAdapter through
		// BluetoothManager.
		if (mBluetoothManager == null) {
			mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
			if (mBluetoothManager == null) {
				Log.e(TAG, "Unable to initialize BluetoothManager.");
				return false;
			}
		}

		mBluetoothAdapter = mBluetoothManager.getAdapter();
		if (mBluetoothAdapter == null) {
			Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
			return false;
		}

		return true;
	}

	/**
	 * Connects to the GATT server hosted on the Bluetooth LE device.
	 *
	 * @param address The device address of the destination device.
	 *
	 * @return Return true if the connection is initiated successfully. The connection result
	 *         is reported asynchronously through the
	 *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
	 *         callback.
	 */
	public boolean connect(final String address) {
		if (mBluetoothAdapter == null || address == null) {
			Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
			return false;
		}

		// Previously connected device.  Try to reconnect.
		if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
				&& mBluetoothGatt != null) {
			Log.i(TAG, "Trying to use an existing mBluetoothGatt for connection.");
			if (mBluetoothGatt.connect()) {
				Log.i(TAG, "State changed to connecting...");
				mConnectionState = STATE_CONNECTING;
				mGattCallback.onConnectionStateChange(mBluetoothGatt, GATT_INDETERMINATE, STATE_CONNECTING);
				return true;
			} 
			else {
				Log.i(TAG, "Boolean returned false.");
				return false;
			}
		}

		final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
		if (device == null) {
			Log.w(TAG, "Device not found.  Unable to connect.");
			return false;
		}
		// We want to directly connect to the device, so we are setting the autoConnect
		// parameter to false.
		mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
		Log.i(TAG, "Trying to create a new connection.");
		mBluetoothDeviceAddress = address;
		mConnectionState = STATE_CONNECTING;
		return true;
	}

	public static String fixedLengthString(String string, int length) {
		return String.format("%-"+length+ "s", string);
	}
	
	public int getmConnectionState() { 
		return mConnectionState; 
	} 

	/**
	 * Disconnects an existing connection or cancel a pending connection. The disconnection result
	 * is reported asynchronously through the
	 * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
	 * callback.
	 */
	public void disconnect() {
		if (mBluetoothAdapter == null || mBluetoothGatt == null) {
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		mBluetoothGatt.disconnect();
	}

	/**
	 * After using a given BLE device, the app must call this method to ensure resources are
	 * released properly.
	 */
	public void close() {
		if (mBluetoothGatt == null) {
			return;
		}
		mBluetoothGatt.close();
		mBluetoothGatt = null;
	}

	/**
	 * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
	 * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
	 * callback.
	 *
	 * @param characteristic The characteristic to read from.
	 */
	public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
		if (mBluetoothAdapter == null || mBluetoothGatt == null) {
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		//put the characteristic into the read queue        
		characteristicReadQueue.add(characteristic);
		//if there is only 1 item in the queue, then read it.  If more than 1, we handle asynchronously in the callback above
		//GIVE PRECEDENCE to descriptor writes.  They must all finish first.
		if(characteristicReadQueue.size() > 0) {
			Log.i(TAG, "Attempted read of queue before callback method.");
			mBluetoothGatt.readCharacteristic(characteristic); 
		}
	}

	/*public void writeGattDescriptor(BluetoothGattDescriptor descriptor){
		//put the descriptor into the write queue
		descriptorWriteQueue.add(descriptor);
		//if there is only 1 item in the queue, then write it.  If more than 1, we handle asynchronously in the callback above
		if(descriptorWriteQueue.size() > 0){ 
			mBluetoothGatt.writeDescriptor(descriptor);
			Log.i(TAG, "Wrote descriptor please");
		}
	}*/

	public void writeCharacteristic(BluetoothGattCharacteristic characteristic, int value) {
		if (mBluetoothAdapter == null || mBluetoothGatt == null) {
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		characteristic.setValue(value, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
		mBluetoothGatt.writeCharacteristic(characteristic);
	}

	public void writeCharacteristicArray(BluetoothGattCharacteristic characteristic, byte[] values) {
		if (mBluetoothAdapter == null || mBluetoothGatt == null) {
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		characteristic.setValue(values);
		mBluetoothGatt.writeCharacteristic(characteristic);
	}

	/**
	 * Enables or disables notification on a given characteristic.
	 *
	 * @param characteristic Characteristic to act on.
	 * @param enabled If true, enable notification.  False otherwise.
	 */
	public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic,
			boolean enabled) {
		if (mBluetoothAdapter == null || mBluetoothGatt == null) {
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		mBluetoothGatt.setCharacteristicNotification(characteristic, enabled); // can be true or false

		// This is specific to Heart Rate Measurement.
		if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
			BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
					UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
			descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
			mBluetoothGatt.writeDescriptor(descriptor);
		}

		if (UUID_BIOIMPEDANCE_DATA.equals(characteristic.getUuid())) {
			BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
					UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
			descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
			mBluetoothGatt.writeDescriptor(descriptor);
		}
	}

	// Method for indications if desired. Not used currently.
	
	public void setCharacteristicIndication(BluetoothGattCharacteristic characteristic,
			boolean enabled) {
		if (mBluetoothAdapter == null || mBluetoothGatt == null) {
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);

		// This is specific to Heart Rate Measurement.
		if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
			BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
					UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
			descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
			mBluetoothGatt.writeDescriptor(descriptor);
		}

		if (UUID_BIOIMPEDANCE_DATA.equals(characteristic.getUuid())) {
			BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
					UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
			descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
			mBluetoothGatt.writeDescriptor(descriptor);
		}
	}

	public void removeCharacteristicNotification(BluetoothGattCharacteristic characteristic,
			boolean enabled) {
		if (mBluetoothAdapter == null || mBluetoothGatt == null) {
			Log.w(TAG, "BluetoothAdapter not initialized");
			return;
		}
		mBluetoothGatt.setCharacteristicNotification(characteristic, enabled); // can be true or false

		// This is specific to Heart Rate Measurement.
		if (UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())) {
			BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
					UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
			descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
			mBluetoothGatt.writeDescriptor(descriptor);
		}

		if (UUID_BIOIMPEDANCE_DATA.equals(characteristic.getUuid())) {
			BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
					UUID.fromString(SampleGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
			descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
			mBluetoothGatt.writeDescriptor(descriptor);
		}
	}

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

	/**
	 * Retrieves a list of supported GATT services on the connected device. This should be
	 * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
	 *
	 * @return A {@code List} of supported services.
	 */
	public List<BluetoothGattService> getSupportedGattServices() {
		if (mBluetoothGatt == null) return null;

		return mBluetoothGatt.getServices();
	}

	public void clientConnected() {
		sNumBoundClients++;
	}

	public void clientDisconnected() {
		sNumBoundClients--;
	}

	public int getNumberOfBoundClients() {
		return sNumBoundClients;
	}

	public void setDeviceAdress(String address) {
		this.mDeviceAddressRestore = address;
	}

	public String getDeviceAddress() {
		return mDeviceAddressRestore;
	}

	public void setDeviceName(String name) {
		this.mDeviceNameRestore = name;
	}

	public String getDeviceName() {
		return mDeviceNameRestore;
	}

}
