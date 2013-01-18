var modpath = '/usr/local/lib/node_modules/';

//require(modpath + 'nodetime').profile();

var sys = require('util'),
	Buffer = require('buffer').Buffer,
	dgram = require('dgram'),
	net = require('net'),
	base32 = require(modpath + 'base32'),
	crc = require(modpath + 'crc'),
	//mongo = require(modpath + 'mongodb'),
	//microtime = require(modpath + 'microtime');
	config = require('./config53.js').config;

// INSTALLING

// MongoDB
// 	http://docs.mongodb.org/manual/tutorial/install-mongodb-on-debian-or-ubuntu-linux/
//	(SysV style init process)

// node.js
//	https://github.com/joyent/node/wiki/Installing-Node.js-via-package-manager
//	(Debian Squeeze)

// Packages
// 	npm install base32 -g
// 	npm install crc -g
// 	npm install mongodb -g
//	(npm install microtime -g)

// Stop/start
// 	killall node
//	(assuming one node process)
//	nano /etc/rc.local
// 		/usr/local/bin/node /root/element53.js >>/root/element53.log 2>&1 &
// 		ssh -f -N -D 0.0.0.0:1080 localhost -p 22022


// TO DO
//	optimize find recursive server
//	group small messages (client and server)
//	probe random record type with cname
//	move updateNotification to Element53Signal
//	get DNS/gateway mobile/WiMAX

// Data structures
var _id = {}; // [unique_id] -> client
var _clientid = {}; // [client] -> unique_id
var _seq = {}; // [client]
var _socket = {}; // [client][channel]
var _queue = {} // [client]
var _msgsize = {}; // [client]
var _lastmsg = {}; // [client]
var _clientclose = {}; // [client][channel]
var _lastactivity = {}; // [client]
var _laststatus = {}; // [client]
var _nocrc = {}; // [client]
var _debug = {}; // [client]

// Constants

var MONGO_LOG = 0;

var PROTOCOL_VERSION = 1;

var OOB_RESET = 1;
var OOB_QUIT = 2;
var OOB_PROBE_CLIENT = 3;
var OOB_PROBE_SERVER = 4;
var OOB_SET_SIZE = 5;

var CONTROL_DATA = 0;
var CONTROL_CLOSE = 1;
var CONTROL_OPEN = 2;
var CONTROL_STATUS = 3;

var RECORD_RANDOM = 0;
var RECORD_CNAME = 5;
var RECORD_NULL = 10;
var RECORD_TEXT = 16;

Object.size = function(obj) {
	var size = 0, key;
	for (key in obj)
		if (obj.hasOwnProperty(key))
		size++;
	return size;
};

var eLog = function(msg) {
  	console.log(new Date() + ' ' + msg);
};

var sliceBits = function(b, off, len) {
	var s = 7 - (off + len - 1);
	b = b >>> s;
	return b & ~(0xff << len);
};

var decodeRequest = function(buffer) {
	var query = {};
	query.header = {};
	query.question = {};

	var index = 0;
	query.header.id = buffer[index++] * 256 + buffer[index++];

	query.header.qr = sliceBits(buffer[index], 0, 1);
	query.header.opcode = sliceBits(buffer[index], 1, 4);
	query.header.aa = sliceBits(buffer[index], 5, 1);
	query.header.tc = sliceBits(buffer[index], 6, 1);
	query.header.rd = sliceBits(buffer[index], 7, 1);
	index++;

	query.header.ra = sliceBits(buffer[index], 0, 1);
	query.header.z = sliceBits(buffer[index], 1, 3);
	query.header.rcode = sliceBits(buffer[index], 4, 4);
	index++;

	query.header.qdcount = buffer[index++] * 256 + buffer[index++];
	query.header.ancount = buffer[index++] * 256 + buffer[index++];
	query.header.nscount = buffer[index++] * 256 + buffer[index++];
	query.header.arcount = buffer[index++] * 256 + buffer[index++];

	// One question
	var len = buffer[index++];
	if ((len & 0xC0) == 0) {
		query.question.qname = decodeName(buffer, --index);
		index += query.question.qname.length + 1;
	} else {
		// Compression
		eLog('Compression receive');
		var poffset = (len & 0x3F) * 256 + buffer[index++];
		query.question.qname = decodeName(buffer, poffset);
	}
	query.question.qtype = buffer[index++] * 256 + buffer[index++];
	query.question.qclass = buffer[index++] * 256 + buffer[index++];

	// Sanity checks
	if (query.header.qr != 0)
		throw new Error('Expected query');
	if (query.header.opcode != 0)
		throw new Error('Expected opcode query');
	if (query.header.tc != 0)
		throw new Error('Packet truncated');
	if (query.header.rcode != 0)
		throw new Error('rcode=' + query.header.rcode);
	if (query.question.qtype != RECORD_CNAME && query.question.qtype != RECORD_NULL && query.question.qtype != RECORD_TEXT)
		throw new Error('Invalid qtype=' + query.question.qtype);
	if (query.question.qclass != 1) // INternet
		throw new Error('Invalid qclass=' + query.question.qclass);

	return query;
};

var decodeName = function(buffer, index) {
	var name = '';
	var len = buffer[index++];
	while (len > 0) {
		name += buffer.toString('binary', index, index + len) + '.';
		index += len;
		len = buffer[index++];
	}
	return name;
};

var encodeResponse = function(query, rcode, record) {
	var response = {};
	response.header = {};

	response.header.id = query.header.id;

	response.header.qr = 1;
	response.header.opcode = 0;
	response.header.aa = 1;
	response.header.tc = 0;
	response.header.rd = query.header.rd;

	response.header.ra = 1;
	response.header.z = 0;
	response.header.rcode = rcode;

	response.header.qdcount = 1;
	response.header.ancount = (record == null ? 0 : 1);
	response.header.nscount = 0;
	response.header.arcount = 0;

	response.question = {};
	response.question.qname = query.question.qname;
	response.question.qtype = query.question.qtype;
	response.question.qclass = query.question.qclass;

	response.record = record;
	return response;
};

var buildResponseBuffer = function(response) {
	var index = 0;
	var buf = new Buffer(512);

	numToBuffer(buf, index, response.header.id, 2); index += 2;

	buf[index++] = response.header.qr << 7 | response.header.opcode << 3 | response.header.aa << 2 | response.header.tc << 1 | response.header.rd;
	buf[index++] = response.header.ra << 7 | response.header.z << 4 | response.header.rcode;

	numToBuffer(buf, index, response.header.qdcount, 2); index += 2;
	numToBuffer(buf, index, response.header.ancount, 2); index += 2;
	numToBuffer(buf, index, response.header.nscount, 2); index += 2;
	numToBuffer(buf, index, response.header.arcount, 2); index += 2;

	var qname = encodeName(response.question.qname);
	qname.copy(buf, index, 0, qname.length); index += qname.length;
	numToBuffer(buf, index, response.question.qtype, 2); index += 2;
	numToBuffer(buf, index, response.question.qclass, 2); index += 2;

	if (response.record != null) {
		var rname = encodeName(response.record.qname);
		if (response.question.qname == response.record.qname) {
			numToBuffer(buf, index, 0xC0 * 256 + 12, 2);
			index += 2;
		}
		else {
			eLog('No compression!');
			rname.copy(buf, index, 0, rname.length);
			index += rname.length;
		}
		numToBuffer(buf, index, response.record.qtype, 2); index += 2;
		numToBuffer(buf, index, response.record.qclass, 2); index += 2;
		numToBuffer(buf, index, response.record.ttl, 4); index += 4;
		numToBuffer(buf, index, response.record.rdata.length, 2); index += 2;

		var rlen = index + response.record.rdata.length;
		if (rlen > 512)
			throw new Error('Response too long length=' + rlen);

		response.record.rdata.copy(buf, index, 0, response.record.rdata.length); index += response.record.rdata.length;
	}

	var buffer = new Buffer(index);
	buf.copy(buffer, 0, 0, index);

	return buffer;
};

var numToBuffer = function(buf, offset, num, len, debug) {
	// Sanity check
	if (typeof num != 'number')
		throw new Error('Num must be a number');

	for (var i = offset; i < offset + len; i++) {
		var shift = 8 * ((len - 1) - (i - offset));
		var insert = (num >> shift) & 255;
		buf[i] = insert;
	}
	return buf;
};

var encodeName = function(name) {
	var qname = new Buffer(name.length + 1);
	var index = 0;
	var parts = name.split('.');
	for (var i = 0; i < parts.length; i++) {
		qname[index++] = parts[i].length;
		for(var j = 0; j < parts[i].length; j++)
			qname[index++] = parts[i].charCodeAt(j);
	}
	qname[index] = 0;
	return qname;
};

var getMsg = function(client) {
	if (new Date().getTime() - _laststatus[client].getTime() > config.status_interval_ms) {
		_laststatus[client] = new Date();
		var status = new Buffer(4);
		var channels = Object.size(_socket[client]);
		status[0] = channels >> 8;
		status[1] = channels & 0xFF;
		status[2] = _queue[client].length >> 8;
		status[3] = _queue[client].length & 0xFF;
		return { channel: 0, control: CONTROL_STATUS, data: status };
	}
	else if (client in _queue) {
		var msg = _queue[client].shift();
		while (msg != undefined && _clientclose[client][msg.channel]) {
			eLog('Discarding message for closed channel client=' + client + ' channel=' + msg.channel);
			var msg = _queue[client].shift();
		}
		if (msg != undefined)
			return msg;
	}
	return { channel: 0, control: CONTROL_DATA, data: new Buffer(0) };
};

var crc8 = function(data) {
	return crc.crc8(data.toString('binary'));
};

var decodeMessage = function(query) {
	// Strip domain name
	var index = query.question.qname.lastIndexOf(config.domain);
	if (index < 0)
		throw new Error('Invalid qname=' + query.question.qname);
	var payload = query.question.qname.substring(0, index).replace('.', '');

	// Decode message
	var buf = base32.decode(payload);
	var message = new Buffer(buf, 'binary');

	// Extract message
	var rclient = message[0];
	var rseq = message[1];
	// message[2] = nonce
	var rchan = message[3];
	var rctl = message[4];
	var rcrc8 = message[5]
	var rdata = new Buffer(message.length - 6);
	message.copy(rdata, 0, 6, message.length);
	if (rseq != 0 && !_nocrc[rclient] && rcrc8 != crc8(rdata))
		throw new Error('CRC mismatch');

	return { client: rclient, seq: rseq, channel: rchan, control: rctl, data: rdata };
};

var encodeMessage = function(rmsg) {
	// Build response
	var message = new Buffer(5 + _lastmsg[rmsg.client].data.length);
	message[0] = rmsg.client;
	message[1] = _seq[rmsg.client];
	message[2] = _lastmsg[rmsg.client].channel; // possible different channel
	message[3] = _lastmsg[rmsg.client].control;
	message[4] = (_nocrc[rmsg.client] ? 0 : crc8(_lastmsg[rmsg.client].data));
	_lastmsg[rmsg.client].data.copy(message, 5, 0, _lastmsg[rmsg.client].data.length);
	return message;
};

var encodeOOB = function(rmsg, data) {
	var message = new Buffer(5 + data.length);
	message[0] = rmsg.client;
	message[1] = 0; // sequence OOB = 0
	message[2] = 0; // channel unused
	message[3] = rmsg.control;
	message[4] = (_nocrc[rmsg.client] ? 0 : crc8(data));
	data.copy(message, 5, 0, data.length);
	return message;
}

var handleReset = function(rmsg) {
	// Get client ID for unique ID
	var clientid = rmsg.data.toString('ascii', 16, 16 + rmsg.data[15]);
	if (!(clientid in _id))
		_id[clientid] = getFreeClient();
	rmsg.client = _id[clientid];
	_clientid[rmsg.client] = clientid;

	// Reset sequence
	_seq[rmsg.client] = 0;

	// Get client data
	var protocol = rmsg.data[0];
	var servermsgsize = rmsg.data[1] * 256 + rmsg.data[2];
	var clientmsgsize = rmsg.data[3] * 256 + rmsg.data[4];
	var recordtype = rmsg.data[5];
	var nocrc = (rmsg.data[6] == 0 ? false : true);
	var debug = (rmsg.data[7] == 0 ? false : true);
	var istrial = (rmsg.data[8] == 0 ? false : true);
	// bytes 9..15 are reserved for future use

	// Process client data
	_msgsize[rmsg.client] = servermsgsize;
	_nocrc[rmsg.client] = nocrc;
	_debug[rmsg.client] = debug;

	eLog('Reset id=' + clientid + ' client=' + rmsg.client + ' protocol=' + protocol + ' trial=' + istrial + ' clients=' + Object.size(_seq));

	// Set empty last message
	_lastmsg[rmsg.client] = { client: rmsg.client, seq: 0, channel: 0, control: CONTROL_DATA, data: new Buffer(0) };

	// Close sockets
	if (rmsg.client in _socket)
		for (var channel in _socket[rmsg.client]) {
			eLog('End socket client=' + rmsg.client + ' channel=' + channel);
			_clientclose[rmsg.client][channel] = true;
			_socket[rmsg.client][channel].end();
		}
	_socket[rmsg.client] = {};
	if (!(rmsg.client in _clientclose))
		_clientclose[rmsg.client] = {};
	_queue[rmsg.client] = new Array();
	_lastactivity[rmsg.client] = new Date();
	_laststatus[rmsg.client] = new Date();

	// Register client
	if (MONGO_LOG)
		try {
			var server = new mongo.Server('localhost', 27017, { auto_reconnect: true });
			var db = new mongo.Db('element53', server);
			db.open(function(err, db) {
				if (err)
					eLog('Mongo DB open (reset) error=' + err);
				else {
					db.createCollection('client', function(err, collection) {
						if (err)
							eLog('Mongo DB create (reset) error=' + err);
						else {
							var document = {
								clientid: clientid,
								protocol: protocol,
								servermsgsize: servermsgsize,
								clientmsgsize: clientmsgsize,
								recordtype: recordtype,
								nocrc: nocrc,
								debug: debug,
								istrial: istrial,
								time: new Date()
							};
							collection.update(
								{ clientid: clientid },
								document,
								{ upsert: true, multi: false },
								function(err) {
									eLog('Upserted client (size) id=' + clientid + ' err=' + err);
								}
							);
						}
					});
					db.close();
				}
			});
		}
		catch (e) {
			eLog('Error upserting: ' + e.message);
		}

	// Build response
	var data = new Buffer(1);
	data[0] = PROTOCOL_VERSION;

	// Encode response
	return encodeOOB(rmsg, data);
};

var handleQuit = function(rmsg) {
	eLog('Quit client=' + rmsg.client);

	// Close sockets
	if (rmsg.client in _socket)
		for (var channel in _socket[rmsg.client]) {
			eLog('End socket client=' + rmsg.client + ' channel=' + channel);
			_clientclose[rmsg.client][channel] = true;
			_socket[rmsg.client][channel].end();
		}

	// Send response
	var data = new Buffer(0);
	return encodeOOB(rmsg, data);
};

var handleProbeClient = function(rmsg) {
	var size = rmsg.data[0] * 256 + rmsg.data[1];
	eLog('Probe client size client=' + rmsg.client + ' size=' + size);

	// Send response
	var data = new Buffer(0);
	return encodeOOB(rmsg, data);
};

var handleProbeServer = function(rmsg) {
	var size = rmsg.data[0] * 256 + rmsg.data[1];
	eLog('Probe server size client=' + rmsg.client + ' size=' + size);

	// Build response
	var data = new Buffer(size);
	for (var i = 0; i < size; i++)
		data[i] = (i & 0xFF);

	// Send response
	return encodeOOB(rmsg, data);
};

var handleSetSize = function(rmsg) {
	var servermsgsize = rmsg.data[0] * 256 + rmsg.data[1];
	var clientmsgsize = rmsg.data[2] * 256 + rmsg.data[3];
	_msgsize[rmsg.client] = servermsgsize;
	eLog('Set size client=' + rmsg.client + ' server=' + servermsgsize + ' client=' + clientmsgsize);

	// Register size
	if (MONGO_LOG)
		try {
			var server = new mongo.Server('localhost', 27017, { auto_reconnect: true });
			var db = new mongo.Db('element53', server);
			db.open(function(err, db) {
				if (err)
					eLog('Mongo DB open (size) error=' + err);
				else {
					db.createCollection('client', function(err, collection) {
						if (err)
							eLog('Mongo DB create (size) error=' + err);
						else {
							var document = {
								clientid: _clientid[rmsg.client],
								servermsgsize: servermsgsize,
								clientmsgsize: clientmsgsize,
								time: new Date()
							};
							collection.update(
								{ clientid: _clientid[rmsg.client] },
								{ $set : document },
								{ upsert: true, multi: false },
								function(err) {
									eLog('Upserted client (size) id=' + rmsg.client + ' err=' + err);
								}
							);
						}
					});
					db.close();
				}
			});
		}
		catch (e) {
			eLog('Error upserting: ' + e.message);
		}

	// Send response
	var data = new Buffer(0);
	return encodeOOB(rmsg, data);
}

var getFreeClient = function() {
	// Check for inactive clients
	for (client = 1; client <= 255; client++)
		if (client in _lastactivity) {
			var delta = new Date().getTime() - _lastactivity[client].getTime();
			eLog('Client=' + client + ' inactive ' + delta + ' ms');
			if (delta >= config.max_client_inactive_ms) {
				eLog('Delete client=' + client);

				// Close channels
				for (var channel in _socket[client])
					_socket[client][channel].end();

				// Delete client
				delete _seq[client];
				delete _socket[client];
				delete _queue[client];
				delete _msgsize[client];
				delete _lastmsg[client];
				delete _clientclose[client];
				delete _lastactivity[client];
				delete _laststatus [client];
				delete _nocrc[client];
				delete _debug [client];
			}
		}

	// Find free client
	for (client = 1; client <= 255; client++)
		if (!(client in _lastactivity))
			return client;

	throw new Error('No free clients');
};

var sendMessage = function(rinfo, query, message) {
	// Build record
	var record = {};
	record.qname = query.question.qname;
	record.qclass = 1; // INternet
	record.qtype = query.question.qtype;
	record.ttl = 1;

	// Encode data
	if (query.question.qtype == RECORD_CNAME) {
		// base32 encoded CNAME record
		var cname = base32.encode(message.toString('binary'));
		var p = Math.floor(cname.length / 4);
		cname = cname.substr(0 * p, p) + '.' + cname.substr(1 * p, p) + '.' + cname.substr(2 * p, p) + '.' + cname.substr(3 * p) + '.';
		if (cname.length > 255)
			throw new Error('Message too long length=' + cname.length);
		record.rdata = encodeName(cname);
	}
	else if (query.question.qtype == RECORD_TEXT) {
		// base64 encoded TEXT record
		var payload = new Buffer('~' + message.toString('base64'));
		if (payload.length - 1 > 255)
			throw new Error('Message too long length=' + payload.length);
		payload[0] = payload.length - 1;
		record.rdata = payload;
	}
	else if (query.question.qtype == RECORD_NULL) {
		// binary encoded NULL record
		if (message.length > 255)
			throw new Error('Message too long length=' + message.length);
		record.rdata = new Buffer(message.length + 1);
		record.rdata[0] = message.length;
		// buf.copy(targetBuffer, [targetStart], [sourceStart], [sourceEnd])
		message.copy(record.rdata, 1, 0, message.length);
	}
	else
		throw new Error('Unknown record type=' + query.question.qtype);

	// Send response
	var response = encodeResponse(query, 0, record);
	var buffer = buildResponseBuffer(response);
	server.send(buffer, 0, buffer.length, rinfo.port, rinfo.address, function (err, sent) { });
};

var server = dgram.createSocket('udp4');

server.on('message', function (smsg, rinfo) {
	//var start = microtime.now();
	var query = null;
	var rmsg = null;
	try {
		// Decode query
		query = decodeRequest(smsg);

		// Decode message
		rmsg = decodeMessage(query);

		// Handle message
		if (rmsg.seq == 0) {
			// Out of band message
			eLog('OOB=' + rmsg.control + ' client=' + rmsg.client);

			// Reset
			if (rmsg.control == OOB_RESET) {
				var message = handleReset(rmsg);
				sendMessage(rinfo, query, message);
			}

			// Quit
			else if (rmsg.control == OOB_QUIT) {
				var message = handleQuit(rmsg);
				sendMessage(rinfo, query, message);
			}

			// Client probe
			else if (rmsg.control == OOB_PROBE_CLIENT) {
				var message = handleProbeClient(rmsg);
				sendMessage(rinfo, query, message);
			}

			// Server probe
			else if (rmsg.control == OOB_PROBE_SERVER) {
				var message = handleProbeServer(rmsg);
				sendMessage(rinfo, query, message);
			}

			// Set size
			else if (OOB_SET_SIZE) {
				var message = handleSetSize(rmsg);
				sendMessage(rinfo, query, message);
			}
		}
		else {
			// In band message

			// Check client ID
			if (!(rmsg.client in _socket))
				throw new Error('Unknown client=' + rmsg.client);

			// Check sequence
			var nextseq = (_seq[rmsg.client] == 255 ? 1 : _seq[rmsg.client] + 1);
			if (rmsg.seq == nextseq) {
				_seq[rmsg.client] = nextseq;

				// Handle open
				if (rmsg.control == CONTROL_OPEN) {
					eLog('Socket open by client=' + rmsg.client + ' channel=' + rmsg.channel);

					// Create new socket
					_clientclose[rmsg.client][rmsg.channel] = false;
					_socket[rmsg.client][rmsg.channel] = new net.Socket({ type: 'tcp4' });

					// Data from socket
					_socket[rmsg.client][rmsg.channel].on('data', function(data) {
						try {
							if (rmsg.client in _queue && !_clientclose[rmsg.client][rmsg.channel]) {
								var start = 0;
								while (start < data.length) {
									var end = start + _msgsize[rmsg.client];
									if (end > data.length)
										end = data.length;
									var msg = { channel: rmsg.channel, control: CONTROL_DATA, data: data.slice(start, end) };
									_queue[rmsg.client].push(msg);
									start += _msgsize[rmsg.client];
								}
							}
							else
								eLog('Socket data discard client=' + rmsg.client + ' channel=' + rmsg.channel + ' len=' + data.length);
						}
						catch (e) {
							eLog('Error processing data: ' + e.message + ' stack=' + e.stack);
						}
					});

					// Socket timeout
					_socket[rmsg.client][rmsg.channel].on('timeout', function() {
						eLog('Socket timeout client=' + rmsg.client + ' channel=' + rmsg.channel)
					});

					// Socket end
					_socket[rmsg.client][rmsg.channel].on('end', function() {
						eLog('Socket end client=' + rmsg.client + ' channel=' + rmsg.channel)
					});

					// Socket close
					_socket[rmsg.client][rmsg.channel].on('close', function() {
						if (_clientclose[rmsg.client][rmsg.channel])
							eLog('Socket closed by client client=' + rmsg.client + ' channel=' + rmsg.channel)
						else {
							eLog('Socket closed by server client=' + rmsg.client + ' channel=' + rmsg.channel)
							var msg = { channel: rmsg.channel, control: CONTROL_CLOSE, data: new Buffer(0) };
							_queue[rmsg.client].push(msg);
						}
						delete _socket[rmsg.client][rmsg.channel];
						delete _clientclose[rmsg.client][rmsg.channel];
					});

					// Sokcet error
					_socket[rmsg.client][rmsg.channel].on('error', function(error) {
						eLog('Socket error client=' + rmsg.client + ' channel=' + rmsg.channel + ': ' + error);
						_socket[rmsg.client][rmsg.channel].end();
					});

					// Connect socket
					_socket[rmsg.client][rmsg.channel].connect(config.server_port, config.server_ip, function() {
						eLog('Socket connected client=' + rmsg.client + ' channel=' + rmsg.channel);
					});
				}

				// Handle data
				else if (rmsg.control == CONTROL_DATA) {
					if (rmsg.data.length > 0) {
						if (rmsg.channel in _socket[rmsg.client])
							_socket[rmsg.client][rmsg.channel].write(rmsg.data);
						else
							eLog('Data for closed channel client=' + rmsg.client + ' channel=' + rmsg.channel + ' len=' + rmsg.data.length);
					}
				}

				// Handle close
				else if (rmsg.control == CONTROL_CLOSE) {
					eLog('Socket close by client=' + rmsg.client + ' channel=' + rmsg.channel);
					if (rmsg.channel in _socket[rmsg.client]) {
						_clientclose[rmsg.client][rmsg.channel] = true;
						_socket[rmsg.client][rmsg.channel].end();
						for (var i = 0; i < _queue[rmsg.client].length; i++)
							if (_queue[rmsg.client][i].channel == rmsg.channel) {
								eLog('Discarding message for closed channel=' + rmsg.channel);
								delete _queue[rmsg.client][i];
							}
					}
					else
						eLog('Socket to close not found client=' + rmsg.client + ' channel=' + rmsg.channel);
				}

				// Get data to send
				delete _lastmsg[rmsg.client];
				_lastmsg[rmsg.client] = getMsg(rmsg.client);
				msg = _lastmsg[rmsg.client];
			}
			else
				eLog('Retransmit seq=' + _seq[rmsg.client] + ' client=' + rmsg.client + ' chan=' + _lastmsg[rmsg.client].channel + ' ctrl=' + _lastmsg[rmsg.client].control + ' len=' + _lastmsg[rmsg.client].data.length + ' rseq=' + rmsg.seq);

			// Build response
			var message = encodeMessage(rmsg);

			// Send response
			sendMessage(rinfo, query, message);
			_lastactivity[rmsg.client] = new Date();

			// Cleanup
			delete query;
			delete rmsg;
			delete message;
		}
	}
	catch (e) {
		if (rmsg == null || rmsg.seq != 0 || !(rmsg.control == OOB_PROBE_CLIENT || rmsg.control == OOB_PROBE_SERVER))
			eLog('Error processing message: ' + e.message + ' stack=' + e.stack);

		// Send error response
		if (query != null)
			try {
				eLog('Sending error response because: ' + e.message);
				var response = encodeResponse(query, 3, null);
				var buffer = buildResponseBuffer(response);
				server.send(buffer, 0, buffer.length, rinfo.port, rinfo.address, function (err, sent) { });
			}
			catch (ee) {
				eLog('Error sending error response: ' + ee.message);
			}
	}
	//var elapse = microtime.now() - start;
	//console.log('Elapse: ' + elapse + ' us');
});

server.addListener('error', function (ex) {
	eLog('Server error: ' + ex.message);
});

server.bind(config.port);

eLog('Started element53 DNS server port=' + config.port);
eLog('Tunnel domain ' + config.domain);
eLog('Using server ' + config.server_ip + ':' + config.server_port);
