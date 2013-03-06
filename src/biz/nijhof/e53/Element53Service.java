package biz.nijhof.e53;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;

import com.stericson.RootTools.RootTools;
import com.stericson.RootTools.RootToolsException;

import biz.nijhof.e53.Base32.DecodingException;
import biz.nijhof.e53.Element53DNS.DNSRecord;
import biz.nijhof.e53.Element53DNS.DNSResult;
import biz.nijhof.e53.Element53DNS.E53DNSException;
import biz.nijhof.e53.Preferences;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;
import android.provider.Settings.Secure;

public class Element53Service extends Service {
	private static final int PROTOCOL_VERSION = 1;

	private int _client = 0;
	private String _tunnelDomain = null;
	private String _networkType = "Wifi";
	private String _dnsServer = null;
	private String _gateway = null;
	private boolean _routedns = false;
	private boolean _probeclient = false;
	private boolean _probeserver = false;
	private int _maxprobes = 3;
	private int _clientmsgsize = 128;
	private int _servermsgsize = 128;
	private int _maxresends = 20;
	private int _minidlewait = 100;
	private int _maxidlewait = 3200;
	private int _requestwait = 800; // milliseconds
	private int _receivetimeout = 1500; // milliseconds
	private int _receivewait = 800; // milliseconds
	private int _errorwait = 1500; // milliseconds
	private int _localPort = 3128;
	private int _notifyinterval = 1000; // milliseconds
	private int _roottimeout = 10000; // milliseconds
	private boolean _rootaccess = false;
	private boolean _nocrcchecking = false;
	private boolean _experimental = false;
	private boolean _debug = false;
	private boolean _checkconnection = false;
	private int _rxchan = 0;
	private int _rxqueue = 0;

	private SharedPreferences _preferences = null;
	private ConnectivityManager _connectivityManager = null;
	private WifiManager _wifiManager = null;
	private NotificationManager _notificationManager = null;
	private PowerManager _powerManager = null;
	private PowerManager.WakeLock _wakeLock = null;
	private Element53Signal _signal = null;

	private final Random _random = new Random();

	private Thread _dnsThread = null;
	private Thread _serverThread = null;
	private ServerSocket _serverSocket = null;
	private int _lastChannel = 0;

	private PendingIntent _intentBack = null;
	private Notification _notification = null;
	private PendingIntent _pi = null;

	private Element53DNS _dnsHelper = null;

	// Pro/lit
	private boolean _isTrial = false;
	private static final int MAXTRIALBYTES = 10 * 1024 * 1024; // 10 Megabytes

	private class Msg {
		public int Client;
		public int Seq;
		public int Channel;
		public int Control;
		public byte[] Data;

		public static final int OOB_RESET = 1;
		public static final int OOB_QUIT = 2;
		public static final int OOB_PROBE_CLIENT = 3;
		public static final int OOB_PROBE_SERVER = 4;
		public static final int OOB_SET_SIZE = 5;

		public static final int CONTROL_DATA = 0;
		public static final int CONTROL_CLOSE = 1;
		public static final int CONTROL_OPEN = 2;
		public static final int CONTROL_STATUS = 3;

		Msg() {
			this.Client = _client;
			this.Seq = 0;
			this.Channel = 0;
			this.Control = 0;
			this.Data = new byte[0];
		}

		Msg(int seq, int channel, int control, byte[] data) {
			this.Client = _client;
			this.Seq = seq;
			this.Channel = channel;
			this.Control = control;
			this.Data = data;
		}
	}

	public class E53Exception extends Exception {
		private static final long serialVersionUID = 8123L;

		public E53Exception(String message) {
			super(message);
		}
	}

	private Map<String, Socket> _mapChannelSocket = new ConcurrentHashMap<String, Socket>();
	private Queue<Msg> _queue = new ConcurrentLinkedQueue<Msg>();

	private Msg getMsg() {
		return (_queue.isEmpty() ? new Msg() : _queue.remove());
	}

	private int GetChannel() throws E53Exception {
		int i = 0;
		while (true) {
			_lastChannel++;
			if (_lastChannel > 255)
				_lastChannel = 1;
			if (!_mapChannelSocket.containsKey(Integer.toString(_lastChannel)))
				return _lastChannel;
			if (++i > 255)
				throw new E53Exception("No channels available");
		}
	}

	public class LocalBinder extends Binder {
		Element53Service getService() {
			return Element53Service.this;
		}

		void setPI(PendingIntent pi) {
			_pi = pi;
			_dnsHelper.setPI(pi);
			_signal.setPI(pi);
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	private final IBinder mBinder = new LocalBinder();

	private boolean _broadcastReceiverRegistered = false;

	private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {

			if (intent.getAction().equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
				_checkconnection = true;
				NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
				if (info != null)
					_signal.UILog(String.format("Wifi state: %s", info.getDetailedState()));
			}

			else if (intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
				_checkconnection = true;
				NetworkInfo info = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
				if (info != null)
					_signal.UILog(String.format("Network %s %s state: %s", info.getTypeName(), info.getSubtypeName(), info.getDetailedState()));
			}
		}
	};

	@Override
	public void onCreate() {
		// References
		_notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		_preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		_wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
		_connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
		_powerManager = (PowerManager) getSystemService(POWER_SERVICE);
		_dnsHelper = new Element53DNS(getApplicationContext());
		_signal = new Element53Signal(getApplicationContext());
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		stop();
	}

	public void start() {
		// Acquire wake lock
		_wakeLock = _powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "e53");
		_wakeLock.acquire();

		// Build notification
		Intent toLaunch = new Intent(getApplicationContext(), Element53Activity.class);
		toLaunch.setAction("android.intent.action.MAIN");
		toLaunch.addCategory("android.intent.category.LAUNCHER");
		toLaunch.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

		_intentBack = PendingIntent.getActivity(getApplicationContext(), 0, toLaunch, PendingIntent.FLAG_UPDATE_CURRENT);

		String text = getText(R.string.Running).toString();
		_notification = new Notification(R.drawable.ic_launcher, text, System.currentTimeMillis());
		_notification.setLatestEventInfo(getApplicationContext(), getText(R.string.app_name), getText(R.string.Running), _intentBack);

		IntentFilter iff = new IntentFilter();
		iff.addAction("android.net.wifi.STATE_CHANGE");
		iff.addAction("android.net.conn.CONNECTIVITY_CHANGE");
		this.registerReceiver(this.mBroadcastReceiver, iff);
		_broadcastReceiverRegistered = true;

		// Start foreground service
		startForeground(8123, _notification);

		// Start tunnel
		new Thread(new Runnable() {
			public void run() {
				startTunnel();
			}
		}).start();
	}

	public void stop() {
		// Start shutdown
		new Thread(new Runnable() {
			public void run() {
				stopTunnel();
			}
		}).start();
	}

	private void startTunnel() {
		try {
			// Get (default) settings
			getSettings();

			// Start server thread
			_serverThread = new Thread(new Runnable() {
				public void run() {
					try {
						// Wait for DNS reset to complete
						synchronized (_serverThread) {
							_serverThread.wait();
						}

						// Start accepting connections
						_serverSocket = new ServerSocket(_localPort);
						String serverType = (_tunnelDomain.endsWith(".nijhof.biz") ? "SOCKS5" : "client");
						_signal.UILog(String.format("Accepting %s connections on localhost:%d", serverType, _localPort));
						_signal.reportConnected();

						while (Thread.currentThread() == _serverThread)
							try {
								// Wait for new client to connect
								final Socket clientsocket = _serverSocket.accept();
								if (clientsocket.isConnected()) {
									// Assign channel
									final int channel = GetChannel();
									_signal.UILog(String.format("Client connection from %s:%d, channel=%d", clientsocket.getInetAddress().getHostAddress(),
											clientsocket.getPort(), channel));

									// Start receiving from socket/channel
									new Thread(new Runnable() {
										public void run() {
											try {
												// Create channel
												final String ch = Integer.toString(channel);
												_mapChannelSocket.put(ch, clientsocket);
												_queue.add(new Msg(0, channel, Msg.CONTROL_OPEN, new byte[0]));

												byte[] buffer = new byte[_clientmsgsize];
												DataInputStream ds = new DataInputStream(_mapChannelSocket.get(ch).getInputStream());
												BufferedInputStream in = new BufferedInputStream(ds);
												while (true) {
													// Exit when channel removed
													if (!_mapChannelSocket.containsKey(ch)) {
														_signal.UILog(String.format("Stopping receive for channel %d", channel));
														break;
													}

													// Check if socket was
													// closed
													if (_mapChannelSocket.get(ch).isClosed())
														throw new E53Exception(String.format("Socket for channel %d was closed", channel));

													// Receive data
													if (in.available() > 0) {
														int bytes = in.read(buffer);

														// Queue data to
														// transmit
														byte[] data = new byte[bytes];
														System.arraycopy(buffer, 0, data, 0, bytes);
														_queue.add(new Msg(0, channel, Msg.CONTROL_DATA, data));

														// Wake-up DNS thread
														if (_dnsThread != null)
															synchronized (_dnsThread) {
																_dnsThread.notify();
															}

														_receivewait /= 2;
														if (_receivewait < _minidlewait)
															_receivewait = _minidlewait;
													} else {
														_receivewait *= 2;
														if (_receivewait > _maxidlewait)
															_receivewait = _maxidlewait;
														Thread.sleep(_receivewait);
													}
												}
											} catch (Exception ex) {
												_signal.UILog(String.format("Error receiving from channel %d: %s", channel, ex));
												stackTrace(ex);
												clientCloseChannel(channel);
											}
										}
									}).start();
								} else
									throw new E53Exception("Client socket not connected");
							} catch (Exception ex) {
								_signal.UILog(String.format("Error accepting socket connection: %s", ex));
								stackTrace(ex);
								Thread.sleep(_errorwait);
							}
					} catch (Exception ex) {
						_signal.UILog(String.format("Error creating server socket: %s", ex));
						stackTrace(ex);
					}
				}
			});
			_serverThread.start();

			// Start DNS thread
			_dnsThread = new Thread(new Runnable() {
				public void run() {
					int seq = 0;
					int resends = 0;
					Msg msg = new Msg();
					boolean confirmed = true;
					boolean sent = false;
					boolean received = false;
					boolean control = false;
					long latencyCumm = 0;
					long latencyCount = 0;
					int requests = 0;
					int bytesSent = 0;
					int bytesReceived = 0;
					int totalBytesSent = 0;
					int totalBytesReceived = 0;
					int errors = 0;
					int resets = 0;
					Date start = new Date();
					Date lastNotify = new Date();

					try {
						// Create UDP DNS socket
						DatagramSocket dgs = new DatagramSocket();
						dgs.setSoTimeout(_receivetimeout);

						// Wait for network state events
						Thread.sleep(_receivetimeout);

						// Resolve DNS server address
						InetAddress dnsAddress = checkConnectivity();

						// Reset tunnel
						int retry = 1;
						while (true)
							try {
								retry++;
								resetTunnel(dgs, dnsAddress);
								break;
							} catch (Exception ex) {
								_signal.UILog(String.format("Reset: %s retry=%d", ex, retry));
								if (_debug)
									ex.printStackTrace();
								if (retry <= _maxresends) {
									if (!ex.getClass().equals(SocketTimeoutException.class))
										Thread.sleep(_errorwait);
								} else {
									_signal.UILog("Giving up");
									_signal.reportError();
									throw ex;
								}
							}

						// Probe message sizes
						probeTunnel(dgs, dnsAddress);
						_signal.reportSize(_dnsHelper.getRecordName(), _clientmsgsize, _servermsgsize);

						// Enable server thread
						synchronized (_serverThread) {
							_serverThread.notify();
						}

						// Start communicating
						while (Thread.currentThread() == _dnsThread)
							try {
								if (_checkconnection) {
									dnsAddress = checkConnectivity();
									probeTunnel(dgs, dnsAddress);
									_signal.reportSize(_dnsHelper.getRecordName(), _clientmsgsize, _servermsgsize);
								}

								// Nothing happened, wait increasing time
								if (!sent && !received && !control && confirmed) {
									_requestwait *= 2;
									if (_requestwait > _maxidlewait)
										_requestwait = _maxidlewait;
									synchronized (_dnsThread) {
										_dnsThread.wait(_requestwait);
									}
								} else {
									_requestwait /= 2;
									if (_requestwait < _minidlewait)
										_requestwait = _minidlewait;
								}

								if (confirmed) {
									// Get next sequence
									seq = (seq == 255 ? 1 : seq + 1);

									// Get next message
									msg = getMsg();
									confirmed = false;
									resends = 0;
								} else {
									// Not confirmed
									resends++;
									if (resends <= _maxresends)
										_signal.UILog(String.format("Resending seq=%d try=%d", seq, resends));
									else {
										// Too many resends: reset tunnel
										_signal.reportReset();
										resetTunnel(dgs, dnsAddress);

										// Probe again, network could be changed
										probeTunnel(dgs, dnsAddress);
										_signal.reportSize(_dnsHelper.getRecordName(), _clientmsgsize, _servermsgsize);

										// Reset data
										seq = 0;
										resends = 0;
										confirmed = true;
										sent = false;
										received = false;
										control = false;
										bytesSent = 0;
										bytesReceived = 0;
										resets++;
										start = new Date();
										continue;
									}
								}

								// Generate random request ID
								int id = _random.nextInt() & 0xFFFF;

								// Encode request
								msg.Seq = seq;
								byte[] rawMessage = composeRawMessage(msg);
								byte[] pdata = _dnsHelper.encodeData(id, rawMessage, _tunnelDomain, true);
								DatagramPacket packet = new DatagramPacket(pdata, pdata.length, dnsAddress, 53);

								// Send request
								dgs.send(packet);
								sent = (msg.Data.length > 0);
								requests++;
								Date sentTime = new Date();

								// Receive response
								int seqErrors = 0;
								while (true) {
									// Receive packet
									byte[] rbuffer = new byte[512];
									DatagramPacket rpacket = new DatagramPacket(rbuffer, rbuffer.length);
									dgs.receive(rpacket);

									// Decode response
									byte[] rmessage = _dnsHelper.decodeData(id, rpacket.getData());
									if (rmessage == null)
										continue; // Invalid ID

									// Calculate latency
									latencyCount++;
									latencyCumm += new Date().getTime() - sentTime.getTime();

									// Decode message
									Msg rmsg = decomposeRawMessage(rmessage);

									// Update state
									confirmed = (rmsg.Seq == seq);
									received = (rmsg.Data.length > 0);
									control = (rmsg.Control > 0);

									// Sanity check
									if (rmsg.Client != _client)
										throw new E53Exception(String.format("Message for client %d, we are %d", rmsg.Client, _client));

									// Check state
									if (confirmed) {
										// Update statistics
										bytesSent += msg.Data.length;
										bytesReceived += rmsg.Data.length;
										totalBytesSent += msg.Data.length;
										totalBytesReceived += rmsg.Data.length;

										// Process received data
										if (rmsg.Control == Msg.CONTROL_DATA) {
											serverData(rmsg);
										} else if (rmsg.Control == Msg.CONTROL_CLOSE)
											serverCloseChannel(rmsg.Channel);
										else if (rmsg.Control == Msg.CONTROL_STATUS) {
											_rxchan = (rmsg.Data[0] & 0xFF) * 256 + (rmsg.Data[1] & 0xFF);
											_rxqueue = (rmsg.Data[2] & 0xFF) * 256 + (rmsg.Data[3] & 0xFF);
										} else
											throw new E53Exception(String.format("Unknown control message %d", rmsg.Control));

										// Successfully received response
										break;
									} else {
										seqErrors++;
										_signal.UILog(String.format("Sequence error, received %d expected %d try %d", rmsg.Seq, seq, seqErrors));

										// Too many sequence errors?
										if (seqErrors >= _maxresends)
											throw new E53Exception("Too many sequence errors");

										// Retry
										continue;
									}
								}

								// Update notification
								int notifyDelta = (int) (new Date().getTime() - lastNotify.getTime());
								if (notifyDelta > _notifyinterval) {
									lastNotify = new Date();
									bytesSent = bytesSent * 1000 / notifyDelta;
									bytesReceived = bytesReceived * 1000 / notifyDelta;
									float latency = (latencyCount == 0 ? 0f : ((float) latencyCumm / latencyCount));
									updateNotification(start, seq, requests, latency, errors, resets, bytesSent, bytesReceived, totalBytesSent,
											totalBytesReceived);
									bytesSent = 0;
									bytesReceived = 0;
								}

							} catch (Exception ex) {
								errors++;
								_signal.UILog(String.format("Error communicating: %s", ex));
								stackTrace(ex);

								if (!ex.getClass().equals(SocketTimeoutException.class))
									Thread.sleep(_errorwait);
							}

						// Send quit message
						quitTunnel(dgs, dnsAddress);
					} catch (Exception ex) {
						_signal.UILog(String.format("Connectivity error: %s", ex));
						stackTrace(ex);
					}
				}
			});
			_dnsThread.start();

		} catch (Exception ex) {
			_signal.UILog(String.format("Error: %s", ex));
			stackTrace(ex);
			_signal.reportError();
		} finally {
			_signal.reportStarted();
		}
	}

	private void stopTunnel() {
		try {
			// Unregister broadcast receiver
			if (_broadcastReceiverRegistered)
				try {
					this.unregisterReceiver(this.mBroadcastReceiver);
				} catch (Exception ex) {
				} finally {
					_broadcastReceiverRegistered = false;
				}

			// Terminate DNS loop
			if (_dnsThread != null) {
				Thread tmp = _dnsThread;
				_dnsThread = null;
				try {
					tmp.interrupt();
				} catch (Exception ex) {
				}
			}

			// Close server socket
			if (_serverSocket != null)
				try {
					_serverSocket.close();
				} catch (Exception ex) {
				} finally {
					_serverSocket = null;
				}

			// Terminate server loop
			if (_serverThread != null) {
				Thread tmp = _serverThread;
				_serverThread = null;
				try {
					tmp.interrupt();
				} catch (Exception ex) {
				}
			}

			// Close sockets/channels
			try {
				closeAllChannels();
			} catch (Exception ex) {
			}

			// Undo routing
			if (_routedns)
				try {
					delRoute(_gateway, _dnsServer);
				} catch (Exception ex) {
				}
			_gateway = null;
			_dnsServer = null;

			// Dispose notification
			_notification = null;

			// End foreground service
			stopForeground(true);

			// Release wake lock
			if (_wakeLock != null) {
				_wakeLock.release();
				_wakeLock = null;
			}
		} finally {
			_signal.reportStopped();
		}

	}

	private void getSettings() throws E53Exception, CanceledException, DecodingException, E53DNSException {
		// Get tunnel domain
		_tunnelDomain = _preferences.getString(Preferences.PREF_TUNNELDOMAIN, "");
		if (_tunnelDomain.equals(""))
			_tunnelDomain = "t.nijhof.biz";
		_signal.UILog(String.format("Tunnel domain: %s", _tunnelDomain));

		// Get network type
		_networkType = _preferences.getString(Preferences.PREF_NETWORKTYPE, "");
		if (_networkType.equals(""))
			_networkType = "Wifi";
		_signal.UILog(String.format("Network type: %s", _networkType));

		// Get DNS record type
		byte recordtype = (byte) Integer.parseInt(_preferences.getString(Preferences.PREF_RECORDTYPE, Integer.toString(Element53DNS.RECORD_TEXT)));
		_dnsHelper.setRecordType(recordtype);
		_signal.UILog(String.format("Record type: %s", _dnsHelper.getRecordName()));

		// Calculate maximum message sizes
		int clientheadersize = 6;
		int serverheadersize = 5;
		int subdomainlength = 255 - _tunnelDomain.length() - 1;
		int maxclientmsgsize = (subdomainlength - subdomainlength / 64 - 1) * 5 / 8 - clientheadersize;

		int packetleft = 512 - (6 * 2 + 255 + 5 * 2 + 4 + 2);

		if (recordtype == Element53DNS.RECORD_RANDOM || recordtype == Element53DNS.RECORD_CNAME)
			packetleft = Math.min(255 - 5, packetleft - 5) * 5 / 8;
		else if (recordtype == Element53DNS.RECORD_NULL)
			packetleft = Math.min(255, packetleft - 1) * 8 / 8;
		else if (recordtype == Element53DNS.RECORD_TEXT)
			packetleft = Math.min(255, packetleft - 1) * 6 / 8;
		int maxservermsgsize = packetleft - serverheadersize;

		_signal.UILog(String.format("Max message size client/server %d/%d bytes", maxclientmsgsize, maxservermsgsize));

		// Get message sizes
		String cmsg = _preferences.getString(Preferences.PREF_CLIENTMSGSIZE, "");
		String smsg = _preferences.getString(Preferences.PREF_SERVERMSGSIZE, "");
		_clientmsgsize = (cmsg.equals("") ? 0 : Integer.parseInt(cmsg));
		_servermsgsize = (smsg.equals("") ? (recordtype == Element53DNS.RECORD_RANDOM ? 100 : 0) : Integer.parseInt(smsg));
		_probeclient = (_clientmsgsize <= 0);
		_probeserver = (_servermsgsize <= 0);
		if (!_probeclient && !_probeserver) {
			_signal.UILog(String.format("Client message size is %d bytes", _clientmsgsize));
			_signal.UILog(String.format("Server message size is %d bytes", _servermsgsize));

			// Check message sizes
			if (_clientmsgsize < 1 || _clientmsgsize > maxclientmsgsize)
				throw new E53Exception("Client message size too small/large");
			if (_servermsgsize < 1 || _servermsgsize > maxservermsgsize)
				throw new E53Exception("Server message size too small/large");
		}
		// Get maximum number of re-sends
		String sMaxResends = _preferences.getString(Preferences.PREF_MAXRESENDS, "");
		_maxresends = (sMaxResends.equals("") ? 20 : Integer.parseInt(sMaxResends));
		_signal.UILog(String.format("Maximum number of resends is %d", _maxresends));

		// Get idle wait range
		String sMinIdleWait = _preferences.getString(Preferences.PREF_MINIDLEWAIT, "");
		String sMaxIdleWait = _preferences.getString(Preferences.PREF_MAXIDLEWAIT, "");
		_minidlewait = (sMinIdleWait.equals("") ? 100 : Integer.parseInt(sMinIdleWait));
		_maxidlewait = (sMaxIdleWait.equals("") ? 3200 : Integer.parseInt(sMaxIdleWait));
		_signal.UILog(String.format("Idle wait min %d max %d ms", _minidlewait, _maxidlewait));
		_requestwait = _minidlewait;

		// Get local port
		String pport = _preferences.getString(Preferences.PREF_LOCALPORT, "");
		_localPort = (pport.equals("") ? 3128 : Integer.parseInt(pport));
		if (_localPort < 1024 || _localPort > 65535)
			throw new E53Exception("Invalid local port number");
		_signal.UILog(String.format("Local port number is %d", _localPort));

		// Get receive time-out
		String rtimeout = _preferences.getString(Preferences.PREF_RECEIVETIMEOUT, "");
		_receivetimeout = (rtimeout.equals("") ? 1500 : Integer.parseInt(rtimeout));
		_signal.UILog(String.format("Receive time-out is %d ms", _receivetimeout));

		// Get error wait time
		String ewait = _preferences.getString(Preferences.PREF_ERRORWAIT, "");
		_errorwait = (ewait.equals("") ? 1500 : Integer.parseInt(ewait));
		_signal.UILog(String.format("Error wait is %d ms", _errorwait));

		// Get flags
		_routedns = _preferences.getBoolean(Preferences.PREF_ROUTEDNS, false);
		boolean nostrictchecking = _preferences.getBoolean(Preferences.PREF_NOSTRICTCHECKING, false);
		_nocrcchecking = _preferences.getBoolean(Preferences.PREF_NOCRCCHECKING, false);
		_experimental = _preferences.getBoolean(Preferences.PREF_EXPERIMENTAL, false);
		_signal.UILog(String.format("Route DNS to gateway: %s", _routedns ? "Yes" : "No"));
		_signal.UILog(String.format("No strick checking: %s", nostrictchecking ? "Yes" : "No"));
		_signal.UILog(String.format("No CRC checking: %s", _nocrcchecking ? "Yes" : "No"));
		_signal.UILog(String.format("Experimental features: %s", _experimental ? "Yes" : "No"));
		_dnsHelper.setNoStrict(nostrictchecking);

		// Debug mode?
		_debug = _preferences.getBoolean(Preferences.PREF_DEBUG, false);
		RootTools.debugMode = _debug;
		_signal.UILog(String.format("Debug mode: %s", _debug ? "Yes" : "No"));
		_dnsHelper.setDebug(_debug);

		// Check root
		_rootaccess = RootTools.isAccessGiven();
		_signal.UILog(String.format("Root access: %s", _rootaccess ? "Yes" : "No"));

		// Show tunnel domain
		_signal.reportDomain(_isTrial, _tunnelDomain, _localPort);
	}

	private InetAddress checkConnectivity() throws E53Exception, IOException, RootToolsException, TimeoutException, CanceledException, InterruptedException {

		// Get DNS server
		String dnsServer = _preferences.getString(Preferences.PREF_DNSSERVER, "");
		boolean useDNSServer = _preferences.getBoolean(Preferences.PREF_USEDNSSERVER, false);

		// Check Wifi
		if (Build.PRODUCT.contains("sdk")) {
			Process proc = Runtime.getRuntime().exec("getprop net.dns1");
			_dnsServer = new BufferedReader(new InputStreamReader(proc.getInputStream())).readLine();
			_checkconnection = false;
		} else {
			// Check if network connected/available
			NetworkInfo nwInfo = null;
			if (_networkType.equals("Wifi"))
				nwInfo = _connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
			else if (_networkType.equals("Mobile"))
				nwInfo = _connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
			else
				throw new E53Exception(String.format("Unknown network type: %s", _networkType));
			if (nwInfo == null || !nwInfo.isConnected())
				throw new E53Exception(String.format("%s not connected/available", _networkType));

			// Get network info
			String gateway = null;
			String name = null;
			if (_networkType.equals("Wifi")) {
				// Get connection info
				DhcpInfo dhcpInfo = _wifiManager.getDhcpInfo();
				WifiInfo wifiInfo = _wifiManager.getConnectionInfo();

				// Gateway
				gateway = _preferences.getString(Preferences.PREF_ALTGATEWAY, "");
				boolean useAltGateway = _preferences.getBoolean(Preferences.PREF_USEALTGATEWAY, false);
				if (gateway.equals("") || !useAltGateway)
					gateway = intToIp(dhcpInfo.gateway);
				else
					gateway = resolveIPv4(gateway).getHostAddress();

				// DNS server
				if (dnsServer.equals("") || !useDNSServer)
					dnsServer = intToIp(dhcpInfo.dns1);
				else
					dnsServer = resolveIPv4(dnsServer).getHostAddress();

				name = wifiInfo.getSSID();
			} else {
				NetworkInfo networkInfo = null;
				if (dnsServer.equals("") || !useDNSServer) {
					Process proc = Runtime.getRuntime().exec("getprop net.dns1");
					dnsServer = new BufferedReader(new InputStreamReader(proc.getInputStream())).readLine();
				} else
					dnsServer = resolveIPv4(dnsServer).getHostAddress();

				gateway = null;

				if (_networkType.equals("Mobile"))
					networkInfo = _connectivityManager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
				name = String.format("%s %s", networkInfo.getTypeName(), networkInfo.getSubtypeName());
			}

			// Undo previous routing
			delRoute(_gateway, _dnsServer);
			_gateway = gateway;

			// Find recursive DNS servers
			if (_experimental) {
				// Create socket
				DatagramSocket dgs = new DatagramSocket();
				dgs.setSoTimeout(_receivetimeout);

				// Find recursive
				List<String> lstSearched = new ArrayList<String>();
				Map<String, InetAddress> mapServers = searchRecursiveServers(dgs, dnsServer, resolveIPv4(dnsServer), lstSearched);

				if (mapServers.size() > 0) {
					// Show found servers
					for (String rname : mapServers.keySet())
						_signal.UILog(String.format("Recursive server %s", rname));

					// Pick random recursive server
					int server = _random.nextInt(mapServers.size());
					String rname = (String) mapServers.keySet().toArray()[server];
					dnsServer = mapServers.get(rname).getHostAddress();
				}
			}

			// Set new servers
			_dnsServer = dnsServer;
			_signal.reportNetwork(name, dnsServer, gateway);

			// Route DNS server to gateway
			addRoute(_gateway, _dnsServer);

			_checkconnection = false;
		}

		return resolveIPv4(_dnsServer);
	}

	private void addRoute(String gateway, String dnsServer) throws IOException, RootToolsException, TimeoutException {
		if (_routedns && gateway != null && !dnsServer.equals(gateway))
			if (_rootaccess) {
				String command = String.format("ip route add %s via %s", dnsServer, gateway);
				_signal.UILog(command);
				List<String> lstRoot = RootTools.sendShell(command, _roottimeout);
				if (_debug)
					for (String line : lstRoot)
						_signal.UILog(line);
			}
	}

	private void delRoute(String gateway, String dnsServer) throws IOException, RootToolsException, TimeoutException {
		if (_routedns && gateway != null && dnsServer != null && !dnsServer.equals(gateway))
			if (_rootaccess) {
				String command = String.format("ip route del %s via %s", dnsServer, gateway);
				_signal.UILog(command);
				List<String> lstRoot = RootTools.sendShell(command, _roottimeout);
				if (_debug)
					for (String line : lstRoot)
						_signal.UILog(line);
			}
	}

	public boolean checkTrialLimit(int bytesToAdd) {
		if (_isTrial) {
			SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
			int trialTotalBytes = (sharedPreferences.contains("E53TotalBytes") ? sharedPreferences.getInt("E53TotalBytes", 0) : 0);
			trialTotalBytes += bytesToAdd;
			SharedPreferences.Editor editor = sharedPreferences.edit();
			editor.putInt("E53TotalBytes", trialTotalBytes);
			editor.commit();
			return (trialTotalBytes < MAXTRIALBYTES);
		}
		return true;
	}

	private void updateNotification(Date start, int seq, int requests, float latency, int errors, int resets, int bytesSent, int bytesReceived,
			int totalBytesSent, int totalBytesReceived) {
		// Update notification
		if (_notification != null) {
			String format = getText(R.string.Speed).toString();
			float tx = (bytesSent / 10) / 100f;
			float rx = (bytesReceived / 10) / 100f;
			String text = String.format(format, tx, rx);
			_notification.setLatestEventInfo(getApplicationContext(), getText(R.string.app_name), text, _intentBack);
			_notificationManager.notify(8123, _notification);
		}

		// Update user interface
		if (_pi != null)
			try {
				long elapse = new Date().getTime() - start.getTime();
				Intent intent = new Intent();
				intent.putExtra("Seq", seq);
				intent.putExtra("Req", requests);
				intent.putExtra("Elapse", elapse);
				intent.putExtra("Latency", latency);
				intent.putExtra("DNSWait", _requestwait);
				intent.putExtra("RXWait", _receivewait);
				intent.putExtra("Error", errors);
				intent.putExtra("Reset", resets);
				intent.putExtra("Sent", bytesSent);
				intent.putExtra("Received", bytesReceived);
				intent.putExtra("TotalSent", totalBytesSent);
				intent.putExtra("TotalReceived", totalBytesReceived);
				intent.putExtra("TXChan", _mapChannelSocket.size());
				intent.putExtra("RXChan", _rxchan);
				intent.putExtra("TXQueue", _queue.size());
				intent.putExtra("RXQueue", _rxqueue);
				_pi.send(getApplicationContext(), Element53Signal.RESULT_STATUS, intent);
			} catch (CanceledException ex) {
			}

		// Check trial limit
		if (!checkTrialLimit(bytesSent + bytesReceived)) {
			stopTunnel();
			_signal.reportTrialOver();
		}
	}

	private void resetTunnel(DatagramSocket dgs, InetAddress dnsAddress) throws IOException, InterruptedException, E53Exception, DecodingException,
			E53DNSException {
		// This leads to data loss :-(
		closeAllChannels();

		// Get unique ID
		String android_id = Secure.getString(getApplicationContext().getContentResolver(), Secure.ANDROID_ID);
		if (android_id == null || android_id.equals(""))
			android_id = _wifiManager.getConnectionInfo().getMacAddress();
		if (android_id == null && Build.PRODUCT.contains("sdk"))
			android_id = "SDK";
		byte[] id = android_id.getBytes("US-ASCII");

		// Build reset OOB data
		byte[] data = new byte[16 + id.length];
		data[0] = PROTOCOL_VERSION;
		data[1] = (byte) ((_servermsgsize >> 8) & 0xFF);
		data[2] = (byte) (_servermsgsize & 255);
		data[3] = (byte) ((_clientmsgsize >> 8) & 0xFF);
		data[4] = (byte) (_clientmsgsize & 255);
		data[5] = (byte) _dnsHelper.getRecordType(false);
		data[6] = (byte) (_nocrcchecking ? 1 : 0);
		data[7] = (byte) (_debug ? 1 : 0);
		data[8] = (byte) (_isTrial ? 1 : 0);
		data[9] = 0; // reserved
		data[10] = 0; // reserved
		data[11] = 0; // reserved
		data[12] = 0; // reserved
		data[13] = 0; // reserved
		data[14] = 0; // reserved
		data[15] = (byte) id.length;
		System.arraycopy(id, 0, data, 16, id.length);

		// Send OOB reset
		_signal.UILog(String.format("Reset %s", android_id));
		Msg response = sendOOB(dgs, dnsAddress, Msg.OOB_RESET, data);
		byte protocol = response.Data[0];
		if (protocol > PROTOCOL_VERSION)
			throw new E53Exception(String.format("Unsupported protocol version %d, please update", protocol));
		_client = response.Client;
		_signal.UILog(String.format("Protocol %d client %d", protocol, _client));
	}

	private Map<String, InetAddress> searchRecursiveServers(DatagramSocket dgs, String name, InetAddress dnsAddress, List<String> lstSearched) {
		if (lstSearched.contains(name))
			return new HashMap<String, InetAddress>();

		lstSearched.add(name);
		_signal.UILog(String.format("Recursive search %s", name));

		int tries = 1;
		Map<String, InetAddress> mapServers = new HashMap<String, InetAddress>();
		while (true)
			try {
				DNSResult result = null;
				try {
					// Route DNS server to gateway
					addRoute(_gateway, dnsAddress.getHostAddress());

					// Send request
					int rid = _random.nextInt() & 0xFFFF;
					byte[] pdata = _dnsHelper.encodeData(rid, new byte[6], _tunnelDomain, false);
					DatagramPacket cpacket = new DatagramPacket(pdata, pdata.length, dnsAddress, 53);
					dgs.send(cpacket);

					// Wait for response
					byte[] rbuffer = new byte[512];
					DatagramPacket rpacket = new DatagramPacket(rbuffer, rbuffer.length);
					dgs.receive(rpacket);
					result = _dnsHelper.decodeResponse(rid, rpacket.getData(), true);
				} finally {
					// Undo routing
					delRoute(_gateway, dnsAddress.getHostAddress());
				}

				// Check response
				if (result.rcode != 0)
					throw new E53Exception(result.rtext);

				// Process response
				if (result.ra == 1) {
					if (dnsAddress.getClass().equals(Inet4Address.class) && !mapServers.containsKey(name))
						mapServers.put(name, dnsAddress);
				} else {
					for (DNSRecord nsrecord : result.NSRecord)
						if (nsrecord.Content != null) {
							// Find additional record
							InetAddress address = null;
							for (DNSRecord arrecord : result.ARRecord)
								if (nsrecord.Prop.Type == Element53DNS.RECORD_A && nsrecord.Content.equals(arrecord.Name.Name)) {
									address = arrecord.Address;
									break;
								}

							// Resolve address
							if (address == null && !lstSearched.contains(nsrecord.Content)) {
								_signal.UILog(String.format("Resolving %s", nsrecord.Content));
								try {
									address = resolveIPv4(nsrecord.Content);
								} catch (UnknownHostException ex) {
									_signal.UILog(String.format("Could not resolve %s: %s", nsrecord.Content, ex));
								}
							}

							// Recursive search
							if (address != null)
								mapServers.putAll(searchRecursiveServers(dgs, nsrecord.Content, address, lstSearched));
						}
				}

				break;
			} catch (Exception ex) {
				_signal.UILog(String.format("Error searching: %s", ex));
				stackTrace(ex);
				if (++tries <= _maxprobes)
					mapServers.clear();
				else {
					_signal.UILog("Giving up");
					break;
				}
			}
		return mapServers;
	}

	private void quitTunnel(final DatagramSocket dgs, final InetAddress dnsAddress) throws IOException, InterruptedException, DecodingException,
			E53DNSException, E53Exception {
		_signal.UILog("Quit");
		byte[] data = new byte[0];
		sendOOB(dgs, dnsAddress, Msg.OOB_QUIT, data);
	}

	private void clientProbe(final DatagramSocket dgs, final InetAddress dnsAddress, int size) throws IOException, InterruptedException, E53Exception,
			DecodingException, E53DNSException {
		byte[] data = new byte[size];
		data[0] = (byte) ((size >> 8) & 0xFF);
		data[1] = (byte) (size & 255);
		for (int i = 2; i < size; i++)
			data[i] = (byte) (i & 0xFF);
		sendOOB(dgs, dnsAddress, Msg.OOB_PROBE_CLIENT, data);
	}

	private void serverProbe(final DatagramSocket dgs, final InetAddress dnsAddress, int clientsize, int serversize) throws IOException, InterruptedException,
			E53Exception, DecodingException, E53DNSException {
		byte[] data = new byte[clientsize];
		data[0] = (byte) ((serversize >> 8) & 0xFF);
		data[1] = (byte) (serversize & 255);
		sendOOB(dgs, dnsAddress, Msg.OOB_PROBE_SERVER, data);
	}

	private void setSize(final DatagramSocket dgs, final InetAddress dnsAddress, int clientsize, int serversize) throws IOException, InterruptedException,
			E53Exception, DecodingException, E53DNSException {
		byte[] data = new byte[4];
		data[0] = (byte) ((serversize >> 8) & 0xFF);
		data[1] = (byte) (serversize & 255);
		data[2] = (byte) ((clientsize >> 8) & 0xFF);
		data[3] = (byte) (clientsize & 255);
		sendOOB(dgs, dnsAddress, Msg.OOB_SET_SIZE, data);
		_clientmsgsize = clientsize;
		_servermsgsize = serversize;
	}

	private void probeTunnel(DatagramSocket dgs, InetAddress dnsAddress) throws Exception {
		int csize = 0;
		int ssize = 0;

		// Probe client message size
		if (_probeclient) {
			int cmin = 1;
			int cmax = 255;
			while (cmax - cmin > 0) {
				int tries = 1;
				boolean ok = false;
				csize = (int) Math.ceil((cmin + cmax) / 2f);
				while (true)
					try {
						clientProbe(dgs, dnsAddress, csize);
						ok = true;
						break;
					} catch (Exception ex) {
						if (_debug)
							_signal.UILog(String.format("Probe client: %s", ex));
						stackTrace(ex);
						if (++tries > _maxprobes) {
							if (_debug)
								_signal.UILog("Giving up");
							break;
						}
					}
				_signal.UILog(String.format("Client size probe %d %s", csize, (ok ? "ok" : "fail")));
				if (ok)
					cmin = csize;
				else
					cmax = csize - 1;
			}
			csize = cmax;
		} else
			csize = _clientmsgsize;

		// Probe server message size
		if (_probeserver) {
			int smin = 1;
			int smax = 255;
			while (smax - smin > 0) {
				int tries = 1;
				boolean ok = false;
				ssize = (int) Math.ceil((smin + smax) / 2f);
				while (true)
					try {
						serverProbe(dgs, dnsAddress, csize, ssize);
						ok = true;
						break;
					} catch (Exception ex) {
						if (_debug)
							_signal.UILog(String.format("Probe server: %s", ex));
						if (++tries > _maxprobes) {
							if (_debug)
								_signal.UILog("Giving up");
							break;
						}
					}
				_signal.UILog(String.format("Server size probe %d %s", ssize, (ok ? "ok" : "fail")));
				if (ok)
					smin = ssize;
				else
					smax = ssize - 1;
			}
			ssize = smax;
		} else
			ssize = _servermsgsize;

		_signal.UILog(String.format("Probed size client=%d, server=%d", csize, ssize));

		// Set probed message sizes
		int tries = 0;
		while (true)
			try {
				tries++;
				setSize(dgs, dnsAddress, csize, ssize);
				break;
			} catch (Exception ex) {
				_signal.UILog(String.format("Set size: %s", ex));
				if (tries <= _maxresends) {
					if (!ex.getClass().equals(SocketTimeoutException.class))
						Thread.sleep(_errorwait);
				} else {
					_signal.UILog("Giving up");
					throw ex;
				}
			}
	}

	private Msg sendOOB(final DatagramSocket dgs, final InetAddress dnsAddress, int command, byte[] data) throws IOException, E53Exception,
			InterruptedException, DecodingException, E53DNSException {
		// Encode OOB message
		Msg msg = new Msg();
		msg.Client = _client;
		msg.Seq = 0; // = OOB
		msg.Channel = 0; // Unused
		msg.Control = command; // OOB code
		msg.Data = data;
		byte[] rawMessage = composeRawMessage(msg);

		// Send message
		int id = _random.nextInt() & 0xFFFF;

		byte[] pdata = _dnsHelper.encodeData(id, rawMessage, _tunnelDomain, true);
		DatagramPacket cpacket = new DatagramPacket(pdata, pdata.length, dnsAddress, 53);
		dgs.send(cpacket);

		// Wait for response
		while (true) {
			byte[] rbuffer = new byte[512];
			DatagramPacket rpacket = new DatagramPacket(rbuffer, rbuffer.length);
			dgs.receive(rpacket);

			// Decode response
			byte[] rmessage = _dnsHelper.decodeData(id, rpacket.getData());
			if (rmessage == null)
				continue;

			Msg response = decomposeRawMessage(rmessage);

			// Sanity checks
			if (response.Seq != 0)
				throw new E53Exception("Invalid control sequence");
			if (response.Channel != 0)
				throw new E53Exception("Invalid control channel");
			if (response.Control != msg.Control)
				throw new E53Exception("Invalid control command");

			return response;
		}
	}

	private void serverData(Msg rmsg) throws IOException {
		String ch = Integer.toString(rmsg.Channel);
		if (rmsg.Data.length > 0) {
			if (_mapChannelSocket.containsKey(ch))
				try {
					_mapChannelSocket.get(ch).getOutputStream().write(rmsg.Data);
				} catch (IOException ex) {
					clientCloseChannel(rmsg.Channel);
					throw ex;
				}
			else
				_signal.UILog(String.format("Data for unknown channel %d", rmsg.Channel));
		}
	}

	private void clientCloseChannel(int channel) {
		String ch = Integer.toString(channel);
		if (_mapChannelSocket.containsKey(ch)) {
			// Queue close command
			_signal.UILog(String.format("Sending close for channel %d to server", channel));
			_queue.add(new Msg(0, channel, Msg.CONTROL_CLOSE, new byte[0]));

			// Always close socket
			Socket socket = _mapChannelSocket.get(ch);
			_mapChannelSocket.remove(ch);
			if (!socket.isClosed())
				try {
					socket.close();
				} catch (Exception ex) {
					_signal.UILog(String.format("Error closing socket of channel %d: %s", channel, ex));
					stackTrace(ex);
				}
		}
	}

	private void serverCloseChannel(int channel) throws IOException, E53Exception {
		String ch = Integer.toString(channel);
		if (_mapChannelSocket.containsKey(ch)) {
			_signal.UILog(String.format("Received server close for channel %d", channel));
			Socket socket = _mapChannelSocket.get(ch);
			_mapChannelSocket.remove(ch);
			socket.close();
			for (Msg qmsg : _queue)
				if (qmsg.Channel == channel) {
					_signal.UILog(String.format("Discarding message for closed channel %d", channel));
					_queue.remove(qmsg);
				}
		} else
			_signal.UILog(String.format("Server close for unknown channel %d", channel));
	}

	private void closeAllChannels() {
		for (String ch : _mapChannelSocket.keySet())
			try {
				Socket socket = _mapChannelSocket.get(ch);
				// Prevent sending close to server
				_mapChannelSocket.remove(ch);
				socket.close();
			} catch (Exception ex) {
				_signal.UILog(String.format("Closing channel %s: %s", ch, ex));
				stackTrace(ex);
			}

		// Clear queue
		_queue.clear();
	}

	private String intToIp(int i) {
		return (i & 0xFF) + "." + ((i >> 8) & 0xFF) + "." + ((i >> 16) & 0xFF) + "." + ((i >> 24) & 0xFF);
	}

	private InetAddress resolveIPv4(String name) throws UnknownHostException {
		for (InetAddress addr : InetAddress.getAllByName(name))
			if (addr.getClass().equals(Inet4Address.class))
				return addr;
		throw new UnknownHostException();
	}

	private byte[] composeRawMessage(Msg msg) {
		int nonce = _random.nextInt() & 0xFF;
		byte[] message = new byte[6 + msg.Data.length];
		message[0] = (byte) msg.Client;
		message[1] = (byte) msg.Seq;
		message[2] = (byte) nonce;
		message[3] = (byte) msg.Channel;
		message[4] = (byte) msg.Control;
		message[5] = (_nocrcchecking ? 0 : crc8(msg.Data));
		System.arraycopy(msg.Data, 0, message, 6, msg.Data.length);
		if (_debug)
			_signal.UILog(String.format("Compose seq=%d nonce=%d ch=%d ctl=%d crc=%d len=%d", msg.Seq, nonce, msg.Channel, msg.Control, message[5],
					msg.Data.length));
		return message;
	}

	private Msg decomposeRawMessage(byte[] rmessage) throws E53Exception {
		Msg rmsg = new Msg();
		rmsg.Client = (rmessage[0] & 0xFF);
		rmsg.Seq = (rmessage[1] & 0xFF);
		rmsg.Channel = (rmessage[2] & 0xFF);
		rmsg.Control = (rmessage[3] & 0xFF);
		rmsg.Data = new byte[rmessage.length - 5];
		System.arraycopy(rmessage, 5, rmsg.Data, 0, rmessage.length - 5);
		byte rcrc8 = rmessage[4];
		if (!_nocrcchecking && rcrc8 != crc8(rmsg.Data))
			throw new E53Exception("CRC mismatch");
		if (_debug)
			_signal.UILog(String.format("Decompose seq=%d ch=%d ctl=%d crc=%d len=%d", rmsg.Seq, rmsg.Channel, rmsg.Control, rcrc8, rmsg.Data.length));
		return rmsg;
	}

	private static final int _crc8_table[] = { 0x00, 0x1B, 0x36, 0x2D, 0x6C, 0x77, 0x5A, 0x41, 0xD8, 0xC3, 0xEE, 0xF5, 0xB4, 0xAF, 0x82, 0x99, 0xD3, 0xC8,
			0xE5, 0xFE, 0xBF, 0xA4, 0x89, 0x92, 0x0B, 0x10, 0x3D, 0x26, 0x67, 0x7C, 0x51, 0x4A, 0xC5, 0xDE, 0xF3, 0xE8, 0xA9, 0xB2, 0x9F, 0x84, 0x1D, 0x06,
			0x2B, 0x30, 0x71, 0x6A, 0x47, 0x5C, 0x16, 0x0D, 0x20, 0x3B, 0x7A, 0x61, 0x4C, 0x57, 0xCE, 0xD5, 0xF8, 0xE3, 0xA2, 0xB9, 0x94, 0x8F, 0xE9, 0xF2,
			0xDF, 0xC4, 0x85, 0x9E, 0xB3, 0xA8, 0x31, 0x2A, 0x07, 0x1C, 0x5D, 0x46, 0x6B, 0x70, 0x3A, 0x21, 0x0C, 0x17, 0x56, 0x4D, 0x60, 0x7B, 0xE2, 0xF9,
			0xD4, 0xCF, 0x8E, 0x95, 0xB8, 0xA3, 0x2C, 0x37, 0x1A, 0x01, 0x40, 0x5B, 0x76, 0x6D, 0xF4, 0xEF, 0xC2, 0xD9, 0x98, 0x83, 0xAE, 0xB5, 0xFF, 0xE4,
			0xC9, 0xD2, 0x93, 0x88, 0xA5, 0xBE, 0x27, 0x3C, 0x11, 0x0A, 0x4B, 0x50, 0x7D, 0x66, 0xB1, 0xAA, 0x87, 0x9C, 0xDD, 0xC6, 0xEB, 0xF0, 0x69, 0x72,
			0x5F, 0x44, 0x05, 0x1E, 0x33, 0x28, 0x62, 0x79, 0x54, 0x4F, 0x0E, 0x15, 0x38, 0x23, 0xBA, 0xA1, 0x8C, 0x97, 0xD6, 0xCD, 0xE0, 0xFB, 0x74, 0x6F,
			0x42, 0x59, 0x18, 0x03, 0x2E, 0x35, 0xAC, 0xB7, 0x9A, 0x81, 0xC0, 0xDB, 0xF6, 0xED, 0xA7, 0xBC, 0x91, 0x8A, 0xCB, 0xD0, 0xFD, 0xE6, 0x7F, 0x64,
			0x49, 0x52, 0x13, 0x08, 0x25, 0x3E, 0x58, 0x43, 0x6E, 0x75, 0x34, 0x2F, 0x02, 0x19, 0x80, 0x9B, 0xB6, 0xAD, 0xEC, 0xF7, 0xDA, 0xC1, 0x8B, 0x90,
			0xBD, 0xA6, 0xE7, 0xFC, 0xD1, 0xCA, 0x53, 0x48, 0x65, 0x7E, 0x3F, 0x24, 0x09, 0x12, 0x9D, 0x86, 0xAB, 0xB0, 0xF1, 0xEA, 0xC7, 0xDC, 0x45, 0x5E,
			0x73, 0x68, 0x29, 0x32, 0x1F, 0x04, 0x4E, 0x55, 0x78, 0x63, 0x22, 0x39, 0x14, 0x0F, 0x96, 0x8D, 0xA0, 0xBB, 0xFA, 0xE1, 0xCC, 0xD7 };

	private byte crc8(byte[] data) {
		byte crc = 0;
		for (byte c : data)
			crc = (byte) _crc8_table[(crc ^ c) & 0xFF];
		return crc;
	}

	private void stackTrace(Exception ex) {
		if (_debug)
			_signal.UILog(Log.getStackTraceString(ex));
	}
}
