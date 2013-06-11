package biz.nijhof.e53;

import biz.nijhof.e53.R;
import android.os.Bundle;
import android.preference.PreferenceActivity;

public class Preferences extends PreferenceActivity {
	// Constants
	public static final String PREF_TUNNELDOMAIN = "TunnelDomain";
	public static final String PREF_NETWORKTYPE = "NetworkType";
	public static final String PREF_DNSSERVER = "DNSServer";
	public static final String PREF_USEDNSSERVER = "UseDNSServer";
	public static final String PREF_ALTGATEWAY = "AltGateway";
	public static final String PREF_USEALTGATEWAY = "UseAltGateway";
	public static final String PREF_ROUTEDNS = "RouteDNS";
	public static final String PREF_CLIENTMSGSIZE = "ClientMsgSize";
	public static final String PREF_SERVERMSGSIZE = "ServerMsgSize";
	public static final String PREF_RECORDTYPE = "RecordType";
	public static final String PREF_LOCALPORT = "LocalPort";
	public static final String PREF_RECEIVETIMEOUT = "ReceiveTimeout";
	public static final String PREF_ERRORWAIT = "ErrorWait";
	public static final String PREF_MAXRESENDS = "MaxResends";
	public static final String PREF_MINIDLEWAIT = "MinIdleWait";
	public static final String PREF_MAXIDLEWAIT = "MaxIdleWait";
	public static final String PREF_NOSTRICTCHECKING = "NoStrictChecking";
	public static final String PREF_NOCRCCHECKING = "NoCRCChecking";
	public static final String PREF_EXPERIMENTAL = "Experimental";
	public static final String PREF_LOGLINES = "LogLines";
	public static final String PREF_DEBUG = "Debug";

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		addPreferencesFromResource(R.xml.preferences);
	}
}
