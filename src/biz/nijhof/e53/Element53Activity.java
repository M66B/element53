package biz.nijhof.e53;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;

import biz.nijhof.e53.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.view.ViewPager;
import android.text.method.ScrollingMovementMethod;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;



public class Element53Activity extends Activity {
	private TextView _txtDomain;
	private TextView _txtNetwork;
	private TextView _txtDNSServer;
	private TextView _txtGateway;
	private TextView _txtTypeSize;
	private TextView _txtSeqReq;
	private TextView _txtWait;
	private TextView _txtLatency;
	private TextView _txtErrorReset;
	private TextView _txtSent;
	private TextView _txtReceived;
	private TextView _txtChannels;
	private TextView _txtQueued;
	private TextView _txtLog;
	private TextView _txtManual;
	private ImageView _btnStartStop;
	private TextView _txtTotal;
	
	private List<View> _lstViews;
	private ViewPager _viewPager;

	private boolean _isBound;
	private Element53Service _boundService;

	private Timer _blinkTimer = null;

	boolean _started = false;
	boolean _connected = false;
	private int _loglines = 50;
	private int _clientmsgsize = 0;
	private int _servermsgsize = 0;

	private ServiceConnection mConnection = new ServiceConnection() {
		public void onServiceConnected(ComponentName className, IBinder service) {
			Element53Service.LocalBinder binder = ((Element53Service.LocalBinder) service);
			_boundService = binder.getService();
			binder.setPI(createPendingResult(0, new Intent(), 0));
		}

		public void onServiceDisconnected(ComponentName className) {
			_boundService = null;
		}
	};	

	@Override
	protected void onDestroy() {
		if (_isBound) {
			unbindService(mConnection);
			_isBound = false;
		}
		super.onDestroy();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		try {
			if (resultCode == Element53Signal.RESULT_DOMAIN) {
				if (data.getBooleanExtra("Trial", false))
					Toast.makeText(getApplicationContext(), "Element53 trial version", Toast.LENGTH_LONG).show();
				_txtDomain.setText(String.format("%s:%d", data.getStringExtra("TunnelDomain"), data.getIntExtra("LocalPort", -1)));

			} else if (resultCode == Element53Signal.RESULT_NETWORK) {
				_txtNetwork.setText(data.getStringExtra("Network"));
				_txtDNSServer.setText(data.getStringExtra("DNS"));
				_txtGateway.setText(data.getStringExtra("Gateway"));

			} else if (resultCode == Element53Signal.RESULT_SIZE) {
				_clientmsgsize = data.getIntExtra("Client", -1);
				_servermsgsize = data.getIntExtra("Server", -1);
				_txtTypeSize.setText(String.format("%s %d / %d bytes", data.getStringExtra("Type"), _clientmsgsize, _servermsgsize));

			} else if (resultCode == Element53Signal.RESULT_STATUS) {
				Date elapse = new Date(data.getLongExtra("Elapse", -1));
				DateFormat formatter = new SimpleDateFormat("HH:mm:ss");
				formatter.setTimeZone(TimeZone.getTimeZone("GMT"));

				float latency = data.getFloatExtra("Latency", -1);
				float maxclient = (1000 / latency * _clientmsgsize) / 1000;
				float maxserver = (1000 / latency * _servermsgsize) / 1000;

				_txtSeqReq.setText(String.format("%d / %d %s", data.getIntExtra("Seq", -1), data.getIntExtra("Req", -1), formatter.format(elapse)));
				_txtWait.setText(String.format("%d / %d ms", data.getIntExtra("DNSWait", -1), data.getIntExtra("RXWait", -1)));
				_txtLatency.setText(String.format("%.0f ms %.1f / %.1f kBps", latency, maxclient, maxserver));
				_txtErrorReset.setText(String.format("%d / %d", data.getIntExtra("Error", -1), data.getIntExtra("Reset", -1)));

				float tx = (data.getIntExtra("Sent", -1) / 10) / 100f;
				float rx = (data.getIntExtra("Received", -1) / 10) / 100f;
				float ttx = (data.getIntExtra("TotalSent", -1) / 10) / 100f;
				float trx = (data.getIntExtra("TotalReceived", -1) / 10) / 100f;
				float tt = (data.getIntExtra("Total", -1) / 10) / 100f;

				_txtSent.setText(String.format("%.2f kBps / %.2f kB", tx, ttx));
				_txtReceived.setText(String.format("%.2f kBps / %.2f kB", rx, trx));
				_txtTotal.setText(String.format("%.2f kB",tt));
				_txtChannels.setText(String.format("%d / %d", data.getIntExtra("TXChan", -1), data.getIntExtra("RXChan", -1)));
				_txtQueued.setText(String.format("%d / %d", data.getIntExtra("TXQueue", -1), data.getIntExtra("RXQueue", -1)));

			} else if (resultCode == Element53Signal.RESULT_LOG) {
				String text = _txtLog.getText().toString();
				if (text.split("\\n").length >= _loglines)
					text = text.substring(0, text.lastIndexOf("\n"));
				text = data.getStringExtra("Log") + "\n" + text;
				_txtLog.setText(text);

			} else if (resultCode == Element53Signal.RESULT_RESET)
				Toast.makeText(getApplicationContext(), "Element53 reset", Toast.LENGTH_SHORT).show();

			else if (resultCode == Element53Signal.RESULT_STARTED) {
				_started = true;
				_connected = false;
				_btnStartStop.setImageResource(R.drawable.stopbtn);
			}

			else if (resultCode == Element53Signal.RESULT_CONNECTED)
				_connected = true;

			else if (resultCode == Element53Signal.RESULT_ERROR) {
			}

			else if (resultCode == Element53Signal.RESULT_STOPPED) {
				_started = false;
				_connected = false;
				_btnStartStop.setImageResource(R.drawable.startbtn);
			}

			else if (resultCode == Element53Signal.RESULT_TRIALOVER) {
				showTrailOverMessage();
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		super.onActivityResult(requestCode, resultCode, data);
	}

	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		// Main layout
		setContentView(R.layout.main);
		
		// Adding views to view pager
		_lstViews = new ArrayList<View>();
		_lstViews.add(View.inflate(getApplicationContext(), R.layout.manual, null));
		_lstViews.add(View.inflate(getApplicationContext(), R.layout.status, null));
		_lstViews.add(View.inflate(getApplicationContext(), R.layout.log, null));

		// Set up view pager.
		_viewPager = (ViewPager) findViewById(R.id.element53Pager);
		_viewPager.setAdapter(new Element53PagerAdapter(_lstViews));
		_viewPager.setCurrentItem(1);
		
		
		
		SimpleViewPagerIndicator pageIndicator = (SimpleViewPagerIndicator) findViewById(R.id.page_indicator);
		pageIndicator.setViewPager(_viewPager);
		
		pageIndicator.notifyDataSetChanged();		
		
		// Setup controls
		View manualView = _lstViews.get(0);
		View statusView = _lstViews.get(1);
		View logView = _lstViews.get(2);
		_txtDomain = (TextView) statusView.findViewById(R.id.txtDomain);
		_txtNetwork = (TextView) statusView.findViewById(R.id.txtNetwork);
		_txtDNSServer = (TextView) statusView.findViewById(R.id.txtDNSServer);
		_txtGateway = (TextView) statusView.findViewById(R.id.txtGateway);
		
		_txtTypeSize = (TextView) statusView.findViewById(R.id.txtTypeSize);
		_txtSeqReq = (TextView) statusView.findViewById(R.id.txtSeqReq);
		_txtWait = (TextView) statusView.findViewById(R.id.txtWait);
		_txtLatency = (TextView) statusView.findViewById(R.id.txtLatency);
		_txtErrorReset = (TextView) statusView.findViewById(R.id.txtErrorReset);
		_txtSent = (TextView) statusView.findViewById(R.id.txtSent);
		_txtReceived = (TextView) statusView.findViewById(R.id.txtReceived);
		_txtTotal = (TextView) statusView.findViewById(R.id.txtTotal);
		_txtQueued = (TextView) statusView.findViewById(R.id.txtQueued);
		_txtChannels = (TextView) statusView.findViewById(R.id.txtChannels);
		_txtLog = (TextView) logView.findViewById(R.id.txtLog);
		_txtManual = (TextView) manualView.findViewById(R.id.txtManual);
		_btnStartStop = (ImageView) statusView.findViewById(R.id.startStopButton);

		_txtLog.setMovementMethod(new ScrollingMovementMethod());
		_txtManual.setText(R.string.Description);

		_btnStartStop.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				if (_started) {
					_boundService.stop();
					_started = false;
					_connected = false;
					if (_blinkTimer != null)
						_blinkTimer.cancel();
					_btnStartStop.setVisibility(View.VISIBLE);
					_btnStartStop.setImageResource(R.drawable.startbtn);
				} else {
					_txtDomain.setText(R.string.na);
					_txtNetwork.setText(R.string.na);
					_txtDNSServer.setText(R.string.na);
					_txtGateway.setText(R.string.na);
					_txtTypeSize.setText(R.string.na);
					_txtSeqReq.setText(R.string.na);
					_txtWait.setText(R.string.na);
					_txtLatency.setText(R.string.na);
					_txtErrorReset.setText(R.string.na);
					_txtSent.setText(R.string.na);
					_txtReceived.setText(R.string.na);
					_txtQueued.setText(R.string.na);
					_txtTotal.setText(R.string.na);
					_txtChannels.setText(R.string.na);
					_txtLog.setText("");
					_txtLog.scrollTo(0, 0);

					SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
					String sLogLines = preferences.getString(Preferences.PREF_LOGLINES, "");
					_loglines = (sLogLines.equals("") ? 50 : Integer.parseInt(sLogLines));

					if (_boundService != null) {
						if (_boundService.checkTrialLimit(0)) {
							_boundService.start();
							_started = true;
							_connected = false;
							_blinkTimer = new Timer();
							_blinkTimer.schedule(new TimerTask() {
								@Override
								public void run() {
									Element53Activity.this.runOnUiThread(new Runnable() {
										public void run() {
											if (_started) {
												if (_btnStartStop.getVisibility() == View.VISIBLE && !_connected)
													_btnStartStop.setVisibility(View.INVISIBLE);
												else
													_btnStartStop.setVisibility(View.VISIBLE);
											}
										}
									});
								}
							}, 500, 500);
						} else
							showTrailOverMessage();
					}
				}
			}
		});

		// Attach to service
		bindService(new Intent(Element53Activity.this, Element53Service.class), mConnection, Context.BIND_AUTO_CREATE);
		_isBound = true;
		
		// Check ProxyDroid
		checkProxyDroid();  

	}
	
	
	// Wire options menu
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.main, menu);
		return true;
	}

	// Handle option selection
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.menuSettings:
			Intent preferencesIntent = new Intent(getBaseContext(), Preferences.class);
			startActivity(preferencesIntent);
			return true;
		case R.id.menuClear:
			_txtLog.setText("");
			_txtLog.scrollTo(0, 0);
			return true;
		case R.id.menuAbout:
			// Get version
			String version = null;
			try {
				String versionName = getBaseContext().getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
				int versionCode = getBaseContext().getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
				String format = getText(R.string.Version).toString();
				version = String.format(format, versionName, versionCode);
			} catch (Exception ex) {
				version = ex.getMessage();
			}

			AlertDialog.Builder aboutDialog = new AlertDialog.Builder(this);
			aboutDialog.setTitle(R.string.app_name);
			aboutDialog.setMessage(version);
			aboutDialog.setPositiveButton("Ok", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
				}
			});
			aboutDialog.create().show();
				return true;
			case R.id.menuMain:
					_viewPager.setCurrentItem(1);
					return true;
			case R.id.menuLog:
					_viewPager.setCurrentItem(2);
					return true;			
			case R.id.menuManual:
					_viewPager.setCurrentItem(0);
					return true;
			default:
				return super.onOptionsItemSelected(item);
			}
	}

	// Handle back button
	@Override
	public void onBackPressed() {
		if (_started) {
			AlertDialog.Builder b = new AlertDialog.Builder(Element53Activity.this);
			b.setTitle(getString(R.string.app_name));
			b.setMessage(getString(R.string.Quit));
			b.setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int whichButton) {
					if (_boundService != null)
						_boundService.stop();
					Element53Activity.super.onBackPressed();
				}
			});
			b.setNegativeButton(android.R.string.no, null);
			b.show();
		} else
			Element53Activity.super.onBackPressed();
	}

	private void showTrailOverMessage() {
		// Inform user
		AlertDialog.Builder trailOverDialog = new AlertDialog.Builder(this);
		trailOverDialog.setTitle(R.string.app_name);
		trailOverDialog.setMessage("Sorry, trial over. You have reached the free 10 MB limit.");
		trailOverDialog.setPositiveButton("Yes, I want to buy", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=org.proxydroid"));
				startActivity(browserIntent);
			}
		});
		trailOverDialog.setNegativeButton("No, thanks", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
			}
		});
		trailOverDialog.create().show();
	}

	private void checkProxyDroid() {
		if (isInstalled("org.proxydroid") == null) {
			AlertDialog.Builder proxyDroidDialog = new AlertDialog.Builder(this);
			proxyDroidDialog.setTitle(R.string.app_name);
			proxyDroidDialog.setMessage("ProxyDroid is not installed\n(ProxyDroid should connect to localhost:3128 / SOCKS5)");
			proxyDroidDialog.setPositiveButton("Install now", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
					Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=org.proxydroid"));
					startActivity(browserIntent);
				}
			});
			proxyDroidDialog.setNegativeButton("No, thanks", new DialogInterface.OnClickListener() {
				public void onClick(DialogInterface dialog, int which) {
				}
			});
			proxyDroidDialog.create().show();
		}
	}

	private ApplicationInfo isInstalled(String uri) {
		try {
			return getPackageManager().getApplicationInfo(uri, 0);
		} catch (PackageManager.NameNotFoundException e) {
			return null;
		}
	}
}
