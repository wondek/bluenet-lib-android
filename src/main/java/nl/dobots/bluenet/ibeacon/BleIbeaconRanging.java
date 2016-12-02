package nl.dobots.bluenet.ibeacon;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import nl.dobots.bluenet.ble.extended.structs.BleDevice;
import nl.dobots.bluenet.ble.extended.structs.BleDeviceMap;
import nl.dobots.bluenet.utils.BleLog;

/**
 * Copyright (c) 2015 Bart van Vliet <bart@dobots.nl>. All rights reserved.
 * <p/>
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3, as
 * published by the Free Software Foundation.
 * <p/>
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 3 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 * <p/>
 * Created on 2-11-16
 *
 * @author Bart van Vliet
 */
public class BleIbeaconRanging {
	private static final String TAG = BleIbeaconRanging.class.getCanonicalName();
	private static final long REGION_TIMEOUT_MS = 30000L;
	private static final long TICK_INTERVAL_MS = 1000L;

	private List<BleIbeaconFilter> _iBeaconFilter;
//	private Set<IBleBeaconCallback> _scanCallbacks;
	private Set<BleBeaconRangingListener> _rangingListeners;
	private BleDeviceMap _devices;
	private Map<UUID, Long> _lastSeen;
	private Set<UUID> _inRegion;
	private boolean _paused;


	// handler used for delayed execution and timeouts
	private Handler _handler;




	public BleIbeaconRanging() {
		_iBeaconFilter = new ArrayList<>();
//		_scanCallbacks = new HashSet<>();
		_rangingListeners = new HashSet<>();
		_devices = new BleDeviceMap();
		_lastSeen = new HashMap<>();
		_inRegion = new HashSet<>();
		_paused = false;

		// create handler with its own thread
		HandlerThread handlerThread = new HandlerThread("BleExtHandler");
		handlerThread.start();
		_handler = new Handler(handlerThread.getLooper());

		_handler.postDelayed(tick, TICK_INTERVAL_MS);
	}

	public void destroy() {
		_handler.removeCallbacksAndMessages(null);
	}


	public synchronized void addIbeaconFilter(BleIbeaconFilter filter) {
		_iBeaconFilter.add(filter);
		_lastSeen.put(filter.getUuid(), 0L);
		BleLog.LOGv(TAG, "lastseen " + filter.getUuid() + " at " + _lastSeen.get(filter.getUuid()));
	}

	public synchronized void remIbeaconFilter(BleIbeaconFilter filter) {
		for (int i = _iBeaconFilter.size(); i > 0; i--) {
			if (_iBeaconFilter.get(i).equals(filter)) {
				_iBeaconFilter.remove(i);
			}
		}
		// TODO: Not sure if we can remove this, there may be others with the same UUID
		_lastSeen.remove(filter.getUuid());
	}

	public synchronized void clearIbeaconFilter() {
		_iBeaconFilter.clear();
		_lastSeen.clear();
	}

	public List<BleIbeaconFilter> getIbeaconFilter() {
		return _iBeaconFilter;
	}


//	public boolean subscribe(IBleBeaconCallback callback) {
//		return _scanCallbacks.add(callback);
//	}
//
//	public boolean unsubscribe(IBleBeaconCallback callback) {
//		return _scanCallbacks.remove(callback);
//	}

	// Subscribe to beacon scans, and enter / exit region events.
	public boolean registerListener(BleBeaconRangingListener listener) {
		return _rangingListeners.add(listener);
	}

	public boolean unregisterListener(BleBeaconRangingListener listener) {
		return _rangingListeners.remove(listener);
	}

	public void pause() {
		_paused = true;
	}

	public void resume() {
		_paused = false;
		// TODO: Fire event to listeners?
	}

	public Set getEnteredRegions() {
		return _inRegion;
	}


	public synchronized boolean onScannedDevice(BleDevice device, @Nullable IBleBeaconCallback callback) {

		boolean iBeaconMatch = false;
		if (_iBeaconFilter.isEmpty() || _paused) return false;
//		if (callback == null && _scanCallbacks.isEmpty()) return false;
		if (callback == null && _rangingListeners.isEmpty()) return false;
		if (device.isIBeacon()) {
			for (BleIbeaconFilter iBeaconFilter : _iBeaconFilter) {
				if (iBeaconFilter.matches(device.getProximityUuid(), device.getMajor(), device.getMinor())) {
					iBeaconMatch = true;
					break;
				}
			}
//		} else { Log.d(TAG, "is not ibeacon"); }
		}
		if (iBeaconMatch) {
			BleLog.LOGv(TAG, "matching ibeacon filter: " + device.getAddress() + " (" + device.getName() + ")");
			device = updateDevice(device);
//			for (IBleBeaconCallback cb : _scanCallbacks) {
//				cb.onBeaconScanned(device);
//			}

			long currentTime = SystemClock.elapsedRealtime();
			_lastSeen.put(device.getProximityUuid(), currentTime);
			BleLog.LOGv(TAG, "lastseen " + device.getProximityUuid() + " at " + currentTime + "=" + _lastSeen.get(device.getProximityUuid()));
			if (!_inRegion.contains(device.getProximityUuid())) {
				enterRegion(device.getProximityUuid());
			}

			for (BleBeaconRangingListener listener : _rangingListeners) {
//				Log.d(TAG, "send to listener: " + listener);
				listener.onBeaconScanned(device);
			}
		} else {
			BleLog.LOGv(TAG, "not matching any ibeacon filter:" + device.getAddress() + " (" + device.getName() + ")");
		}
		return iBeaconMatch;
	}

	private Runnable tick = new Runnable() {
		@Override
		public void run() {
			checkRegionExits();
			_handler.postDelayed(this, TICK_INTERVAL_MS);
		}
	};

	private synchronized void checkRegionExits() {
		// TODO: this crashes on logout, because _lastSeen.get(uuid) returns null for some reason unknown
		long curTime = SystemClock.elapsedRealtime();
		if (_lastSeen == null) {
			BleLog.LOGe(TAG, "lastSeen = null!");
		}
		for (UUID uuid : _inRegion) {
			BleLog.LOGd(TAG, "lastSeen " + uuid + ": " + _lastSeen.get(uuid));
			if (curTime - _lastSeen.get(uuid) > REGION_TIMEOUT_MS) {
				exitRegion(uuid);
			}
		}
	}

	private synchronized void enterRegion(UUID uuid) {
		BleLog.LOGd(TAG, "enterRegion: " + uuid.toString());
		_inRegion.add(uuid);
		for (BleBeaconRangingListener listener : _rangingListeners) {
			listener.onRegionEnter(uuid);
		}
	}

	private synchronized void exitRegion(UUID uuid) {
		BleLog.LOGd(TAG, "exitRegion: " + uuid.toString());
//		_lastSeen.remove(uuid);
		_inRegion.remove(uuid);
		if (!_paused) {
			for (BleBeaconRangingListener listener : _rangingListeners) {
				listener.onRegionExit(uuid);
			}
		}
	}

	private synchronized BleDevice updateDevice(BleDevice device) {
		return _devices.updateDevice(device);
	}

}
