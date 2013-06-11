package biz.nijhof.e53;

import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class Element53Signal {
	private Context _context = null;
	private PendingIntent _pi = null;

	public static final int RESULT_CLIENT = 1;
	public static final int RESULT_DOMAIN = 2;
	public static final int RESULT_NETWORK = 3;
	public static final int RESULT_SIZE = 4;
	public static final int RESULT_STATUS = 5;
	public static final int RESULT_LOG = 6;
	public static final int RESULT_RESET = 7;
	public static final int RESULT_STARTED = 8;
	public static final int RESULT_CONNECTED = 9;
	public static final int RESULT_ERROR = 10;
	public static final int RESULT_STOPPED = 11;
	public static final int RESULT_TRIALOVER = 12;


	public Element53Signal(Context context) {
		_context = context;
	}

	public void setPI(PendingIntent pi) {
		_pi = pi;
	}

	public void UILog(String msg) {
		Log.v("e53", msg);
		try {
			if (_pi != null) {
				Intent intent = new Intent();
				intent.putExtra("Log", msg);
				_pi.send(_context, RESULT_LOG, intent);
			}
		} catch (CanceledException ex) {
		}
	}

	public void reportDomain(boolean isTrail, String tunnelDomain, int localPort) {
		if (_pi != null)
			try {
				Intent intent = new Intent();
				intent.putExtra("Trial", isTrail);
				intent.putExtra("TunnelDomain", tunnelDomain);
				intent.putExtra("LocalPort", localPort);
				_pi.send(_context, RESULT_DOMAIN, intent);
			} catch (CanceledException ex) {
			}
	}

	public void reportNetwork(String name, String dnsServer, String gateway) {
		if (_pi != null)
			try {
				Intent intent = new Intent();
				intent.putExtra("Network", name);
				intent.putExtra("DNS", dnsServer);
				intent.putExtra("Gateway", gateway);
				_pi.send(_context, RESULT_NETWORK, intent);
			} catch (CanceledException ex) {
			}
	}

	public void reportSize(String recordName, int clientmsgsize, int servermsgsize) {
		if (_pi != null)
			try {
				Intent intent = new Intent();
				intent.putExtra("Type", recordName);
				intent.putExtra("Client", clientmsgsize);
				intent.putExtra("Server", servermsgsize);
				_pi.send(_context, RESULT_SIZE, intent);
			} catch (CanceledException ex) {
			}
	}

	public void reportStarted() {
		if (_pi != null)
			try {
				Intent intent = new Intent();
				_pi.send(_context, RESULT_STARTED, intent);
			} catch (CanceledException ex) {
			}
	}

	public void reportConnected() {
		if (_pi != null)
			try {
				Intent intent = new Intent();
				_pi.send(_context, RESULT_CONNECTED, intent);
			} catch (CanceledException ex) {
			}
	}

	public void reportError() {
		if (_pi != null)
			try {
				Intent intent = new Intent();
				_pi.send(_context, RESULT_ERROR, intent);
			} catch (CanceledException ex) {
			}
	}

	public void reportStopped() {
		if (_pi != null)
			try {
				Intent intent = new Intent();
				_pi.send(_context, RESULT_STOPPED, intent);
			} catch (CanceledException ex) {
			}
	}

	public void reportReset() {
		try {
			Intent intent = new Intent();
			_pi.send(_context, RESULT_RESET, intent);
		} catch (CanceledException ex) {
		}
	}

	public void reportTrialOver() {
		try {
			Intent intent = new Intent();
			_pi.send(_context, RESULT_TRIALOVER, intent);
		} catch (CanceledException ex) {
		}
	}
}
