package biz.nijhof.e53;

import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import android.app.PendingIntent;
import android.content.Context;
import android.text.TextUtils;
import android.util.Base64;
import biz.nijhof.e53.Base32.DecodingException;

public class Element53DNS {

	public static final byte RECORD_RANDOM = 0;
	public static final byte RECORD_A = 1;
	public static final byte RECORD_NS = 2;
	public static final byte RECORD_CNAME = 5;
	public static final byte RECORD_NULL = 10;
	public static final byte RECORD_TEXT = 16;
	public static final byte RECORD_AAAA = 28;

	private final String[] _rcodes = { "", "FORMERR", "SERVFAIL", "NXDOMAIN", "NOTIMP", "REFUSED", "YXDOMAIN", "YXRRSET", "NXRRSET", "NOTAUTH", "NOTZONE" };

	private byte _recordtype = RECORD_TEXT;
	private boolean _nostrictchecking = false;
	private boolean _debug = false;

	private Element53Signal _signal = null;
	private final Random _random = new Random();

	public Element53DNS(Context context) {
		_signal = new Element53Signal(context);
	}

	public void setPI(PendingIntent pi) {
		_signal.setPI(pi);
	}

	public void setRecordType(byte recordtype) {
		_recordtype = recordtype;
	}

	public void setNoStrict(boolean nostrict) {
		_nostrictchecking = nostrict;
	}

	public void setDebug(boolean debug) {
		_debug = debug;
	}

	public byte getRecordType(boolean randomize) throws E53DNSException {
		if (randomize && _recordtype == RECORD_RANDOM) {
			int x = _random.nextInt(3);
			if (x == 0)
				return RECORD_CNAME;
			else if (x == 1)
				return RECORD_NULL;
			else if (x == 2)
				return RECORD_TEXT;
			else
				throw new E53DNSException("Random error");
		}
		return (byte) _recordtype;
	}

	public String getRecordName() throws E53DNSException {
		if (_recordtype == RECORD_RANDOM)
			return "Random";
		else if (_recordtype == RECORD_CNAME)
			return "CNAME";
		else if (_recordtype == RECORD_NULL)
			return "NULL";
		else if (_recordtype == RECORD_TEXT)
			return "TEXT";
		else
			throw new E53DNSException(String.format("Unknown record type: %d", _recordtype));
	}

	public byte[] encodeData(int id, byte[] message, String domain, boolean recursion) throws UnsupportedEncodingException, UnknownHostException,
			E53DNSException, InterruptedException {
		// Create payload
		String payload = Base32.encode(message);

		// Divide into parts
		int pparts = payload.length() / 63 + 1;
		int plen = payload.length() / pparts;
		int pindex = 0;
		String ppayload = "";
		while (pindex < payload.length()) {
			if (pindex > 0)
				ppayload += ".";
			ppayload += payload.substring(pindex, Math.min(pindex + plen, payload.length()));
			pindex += plen;
		}

		// Build domain name
		String domainName = (ppayload.equals("") ? domain : String.format("%s.%s", ppayload, domain));
		if (domainName.length() > 253)
			throw new E53DNSException(String.format("Domain name too long: %d bytes, reduce message size", domainName.length()));
		if (_debug)
			_signal.UILog(String.format("Sending ID %s query %s", id, domainName));

		byte qr = 0; // 0 = query; 1 = response
		byte opcode = 0; // 0 = query
		byte aa = 0; // authorative answer
		byte tc = 0; // truncation flag
		byte rd = (byte) (recursion ? 1 : 0); // recursion desired
		byte ra = 0; // recursion available
		byte z = 0; // zero reserved
		byte rcode = 0; // 0 = no error

		byte[] sbuffer = new byte[512];
		int index = 0;
		sbuffer[index++] = (byte) ((id >> 8) & 0xFF);
		sbuffer[index++] = (byte) (id & 0xFF);
		sbuffer[index++] = (byte) (qr << 7 | opcode << 3 | aa << 2 | tc << 1 | rd);
		sbuffer[index++] = (byte) (ra << 7 | z << 4 | rcode);

		// Question count
		sbuffer[index++] = 0;
		sbuffer[index++] = 1;

		// Answer count
		sbuffer[index++] = 0;
		sbuffer[index++] = 0;

		// NS count
		sbuffer[index++] = 0;
		sbuffer[index++] = 0;

		// Additional count
		sbuffer[index++] = 0;
		sbuffer[index++] = 0;

		// Query name
		String[] qname = domainName.split("\\.");
		for (int i = 0; i < qname.length; i++) {
			byte[] b = qname[i].getBytes("US-ASCII");
			sbuffer[index++] = (byte) b.length;
			System.arraycopy(b, 0, sbuffer, index, b.length);
			index += b.length;
		}
		sbuffer[index++] = 0;

		// Query type
		sbuffer[index++] = 0;
		sbuffer[index++] = getRecordType(true);

		// Query class
		sbuffer[index++] = 0;
		sbuffer[index++] = 1; // 1 = INternet

		byte[] result = new byte[index];
		System.arraycopy(sbuffer, 0, result, 0, index);
		return result;
	}

	public byte[] decodeData(int eid, byte[] data) throws UnsupportedEncodingException, E53DNSException, DecodingException, UnknownHostException {
		// Decode response
		DNSResult result = decodeResponse(eid, data, false);

		List<String> lstMessages = new ArrayList<String>();

		// Sanity checks
		if (result.qr != 1)
			lstMessages.add("Expected response");

		if (result.opcode != 0)
			lstMessages.add("Expected opcode query");

		if (result.tc != 0)
			lstMessages.add("Packet truncated");

		if (result.ra == 0)
			lstMessages.add("No recursion available");

		String messages = TextUtils.join(", ", lstMessages);

		if (result.rcode == 0) {
			if (!messages.equals(""))
				if (_nostrictchecking)
					_signal.UILog(messages);
				else
					throw new E53DNSException(messages);
		} else
			throw new E53DNSException(String.format("rcode=%d (%s) %s", result.rcode, result.rtext, messages));

		if (result.id != eid) {
			_signal.UILog(String.format("Received ID %d, expected ID %d", result.id, eid));
			return null;
		}

		if (_debug)
			_signal.UILog(String.format("Received ID %d Q=%d A=%d NS=%d AR=%d", result.id, result.QRecord.size(), result.ANRecord.size(),
					result.NSRecord.size(), result.ARRecord.size()));

		// Check query
		if (result.QRecord.size() != 1)
			throw new E53DNSException(String.format("Expected one query response count=%d", result.QRecord.size()));

		if (!(result.QRecord.get(0).Prop.Type == RECORD_CNAME || result.QRecord.get(0).Prop.Type == RECORD_NULL || result.QRecord.get(0).Prop.Type == RECORD_TEXT)
				|| result.QRecord.get(0).Prop.Class != 1)
			throw new E53DNSException(String.format("Question name=%s type=%d:%d", result.QRecord.get(0).Name.Name, result.QRecord.get(0).Prop.Class,
					result.QRecord.get(0).Prop.Type));

		// Check answer
		if (result.ANRecord.size() != 1)
			throw new E53DNSException(String.format("Expected one answer count=%d", result.ANRecord.size()));
		if (result.ANRecord.get(0).Prop.Class != 1)
			throw new E53DNSException(String.format("Answer name=%s type=%d:%d", result.ANRecord.get(0).Name.Name, result.ANRecord.get(0).Prop.Class,
					result.ANRecord.get(0).Prop.Type));

		if (!result.QRecord.get(0).Name.Name.equals(result.ANRecord.get(0).Name.Name))
			throw new E53DNSException(String.format("Wrong answer name: %s <> %s", result.QRecord.get(0).Name.Name, result.ANRecord.get(0).Name.Name));

		if (!(result.ANRecord.get(0).Prop.Type == RECORD_NULL || result.ANRecord.get(0).Prop.Type == RECORD_TEXT || result.ANRecord.get(0).Prop.Type == RECORD_CNAME))
			throw new E53DNSException(String.format("Unknown answer name=%s type=%d:%d", result.ANRecord.get(0).Name.Name, result.ANRecord.get(0).Prop.Class,
					result.ANRecord.get(0).Prop.Type));

		// Return decode data
		return result.ANRecord.get(0).Data;
	}

	public DNSResult decodeResponse(int eid, byte[] data, boolean all) throws UnsupportedEncodingException, E53DNSException, DecodingException,
			UnknownHostException {
		DNSResult result = new DNSResult();

		int index = 0;
		result.id = (data[index++] & 0xFF) * 256 + (data[index++] & 0xFF);

		result.qr = (data[index] >> 7) & 1;
		result.opcode = (data[index] >> 3) & 0x0F;
		result.aa = (data[index] >> 2) & 1;
		result.tc = (data[index] >> 1) & 1;
		result.rd = data[index] & 1;
		index++;

		result.ra = (data[index] >> 7) & 1;
		result.rcode = data[index] & 0x0F;
		index++;

		if (result.rcode <= _rcodes.length)
			result.rtext = _rcodes[result.rcode];
		else
			result.rtext = String.format("Error %d", result.rcode);

		int qdcount = (data[index++] & 0xFF) * 256 + (data[index++] & 0xFF);
		int ancount = (data[index++] & 0xFF) * 256 + (data[index++] & 0xFF);
		int nscount = (data[index++] & 0xFF) * 256 + (data[index++] & 0xFF);
		int arcount = (data[index++] & 0xFF) * 256 + (data[index++] & 0xFF);

		result.QRecord = new ArrayList<DNSRecord>();
		result.ANRecord = new ArrayList<DNSRecord>();
		result.NSRecord = new ArrayList<DNSRecord>();
		result.ARRecord = new ArrayList<DNSRecord>();

		// Question
		for (int i = 0; i < qdcount; i++) {
			DNSRecord qRecord = new DNSRecord();
			qRecord.Name = decodeName(data, index);
			index = qRecord.Name.Index;
			qRecord.Prop = new DNSProp();
			qRecord.Prop.Type = (data[index++] & 0xFF) * 256 + (data[index++] & 0xFF);
			qRecord.Prop.Class = (data[index++] & 0xFF) * 256 + (data[index++] & 0xFF);
			result.QRecord.add(qRecord);
		}

		// Decode answer section
		for (int i = 0; i < ancount; i++) {
			DNSRecord anRecord = new DNSRecord();
			anRecord.Name = decodeName(data, index);
			index = anRecord.Name.Index;
			anRecord.Prop = decodeType(data, index);
			index = anRecord.Prop.Index;

			// Decode data
			if (anRecord.Prop.Type == RECORD_NULL) {
				int rdatalen = (data[index] & 0xFF);
				anRecord.Data = new byte[rdatalen];
				System.arraycopy(data, index + 1, anRecord.Data, 0, rdatalen);
			} else if (anRecord.Prop.Type == RECORD_TEXT) {
				int rdatalen = (data[index] & 0xFF);
				String r_data = new String(data, index + 1, rdatalen, "US-ASCII");
				anRecord.Data = Base64.decode(r_data, Base64.DEFAULT | Base64.NO_WRAP);
			} else if (anRecord.Prop.Type == RECORD_CNAME) {
				String r_data = decodeName(data, index).Name;
				r_data = r_data.replace(".", "");
				anRecord.Data = Base32.decode(r_data);
			}

			result.ANRecord.add(anRecord);
			index += anRecord.Prop.Len;
		}

		if (all) {
			// Name servers
			for (int ns = 0; ns < nscount; ns++) {
				DNSRecord nsRecord = new DNSRecord();
				nsRecord.Name = decodeName(data, index);
				index = nsRecord.Name.Index;
				nsRecord.Prop = decodeType(data, index);
				index = nsRecord.Prop.Index;

				if (nsRecord.Prop.Type == RECORD_NS && nsRecord.Prop.Class == 1)
					nsRecord.Content = decodeName(data, index).Name;
				else
					_signal.UILog(String.format("Skipping NS record name=%s type=%d:%d", nsRecord.Name.Name, nsRecord.Prop.Class, nsRecord.Prop.Type));

				result.NSRecord.add(nsRecord);
				index += nsRecord.Prop.Len;
			}

			// Additional
			for (int ar = 0; ar < arcount; ar++) {
				DNSRecord arRecord = new DNSRecord();
				arRecord.Name = decodeName(data, index);
				index = arRecord.Name.Index;
				arRecord.Prop = decodeType(data, index);
				index = arRecord.Prop.Index;

				if (arRecord.Prop.Type == RECORD_A && arRecord.Prop.Class == 1) {
					byte[] rawAddress = new byte[4];
					System.arraycopy(data, index, rawAddress, 0, 4);
					arRecord.Address = Inet4Address.getByAddress(rawAddress);

				} else if (arRecord.Prop.Type == RECORD_AAAA && arRecord.Prop.Class == 1) {
					byte[] rawAddress = new byte[16];
					System.arraycopy(data, index, rawAddress, 0, 16);
					arRecord.Address = Inet6Address.getByAddress(rawAddress);
				} else
					_signal.UILog(String.format("Skipping AR record name=%s type=%d:%d", arRecord.Name.Name, arRecord.Prop.Class, arRecord.Prop.Type));

				result.ARRecord.add(arRecord);
				index += arRecord.Prop.Len;
			}
		}

		return result;
	}

	private DNSName decodeName(byte[] data, int index) throws UnsupportedEncodingException {
		DNSName name = new DNSName();
		name.Name = "";
		int len = (data[index++] & 0xFF);
		while (len > 0) {
			if (!name.Name.equals(""))
				name.Name += ".";
			if ((len & 0xC0) == 0) {
				name.Name += new String(data, index, len, "US-ASCII");
				index += len;
				len = (data[index++] & 0xFF);
			} else {
				// Compression
				int ptr = (len & 0x3F) * 256 + (data[index++] & 0xFF);
				name.Name += decodeName(data, ptr).Name;
				break;
			}
		}
		name.Index = index;
		return name;
	}

	private DNSProp decodeType(byte[] data, int index) {
		DNSProp type = new DNSProp();
		type.Type = (data[index++] & 0xFF) * 256 + (data[index++] & 0xFF);
		type.Class = (data[index++] & 0xFF) * 256 + (data[index++] & 0xFF);
		type.TTL = (data[index++] & 0xFF) * 256 * 256 * 256 + (data[index++] & 0xFF) * 256 * 256 + (data[index++] & 0xFF) * 256 + (data[index++] & 0xFF);
		type.Len = (data[index++] & 0xFF) * 256 + (data[index++] & 0xFF);
		type.Index = index;
		return type;
	}

	public class DNSName {
		public String Name;
		public int Index;
	}

	public class DNSProp {
		public int Type;
		public int Class;
		public int TTL;
		public int Len;
		public int Index;
	}

	public class DNSRecord {
		public DNSName Name;
		public DNSProp Prop;
		public byte[] Data;
		public InetAddress Address;
		public String Content;
	}

	public class DNSResult {
		public int id;
		public int qr;
		public int opcode;
		public int aa;
		public int tc;
		public int rd;
		public int ra;
		public int rcode;
		public String rtext;
		public List<DNSRecord> QRecord;
		public List<DNSRecord> ANRecord;
		public List<DNSRecord> NSRecord;
		public List<DNSRecord> ARRecord;
	}

	public class E53DNSException extends Exception {
		private static final long serialVersionUID = 8123L;

		public E53DNSException(String message) {
			super(message);
		}
	}
}
