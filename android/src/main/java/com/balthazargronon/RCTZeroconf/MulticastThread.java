package com.balthazargronon.RCTZeroconf;

import com.balthazargronon.RCTZeroconf.netlib.NetUtil;
import com.balthazargronon.RCTZeroconf.netlib.dns.DNSMessage;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeArray;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.Nullable;

public class MulticastThread extends Thread {
    // the standard mDNS multicast address and port number
    private static final byte[] MDNS_ADDR =
            new byte[] {(byte) 224,(byte) 0,(byte) 0,(byte) 251};
    private static final int MDNS_PORT = 5353;

    private static final int BUFFER_SIZE = 4096;

    private NetworkInterface networkInterface;
    private InetAddress groupAddress;
    private MulticastSocket multicastSocket;
    private NetUtil netUtil;
    private ReactContext context;

    /**
     * Construct the network thread.
     * @param context
     */
    public MulticastThread(ReactContext context) {
        super("net");
        this.context = context;
        netUtil = new NetUtil(context);
    }

    /**
     * Open a multicast socket on the mDNS address and port.
     * @throws IOException
     */
    private void openSocket() throws IOException {
        multicastSocket = new MulticastSocket(MDNS_PORT);
        multicastSocket.setTimeToLive(2);
        multicastSocket.setReuseAddress(true);
        multicastSocket.setNetworkInterface(networkInterface);
        multicastSocket.joinGroup(groupAddress);
    }

    /**
     * The main network loop.  Multicast DNS packets are received,
     * processed, and sent to the UI.
     *
     * This loop may be interrupted by closing the multicastSocket,
     * at which time any commands in the commandQueue will be
     * processed.
     */
    @Override
    public void run() {
        Set<InetAddress> localAddresses = NetUtil.getLocalAddresses();

        // initialize the network
        try {
            networkInterface = netUtil.getFirstWifiOrEthernetInterface();
            if (networkInterface == null) {
                throw new IOException("Your WiFi is not enabled.");
            }
            groupAddress = InetAddress.getByAddress(MDNS_ADDR);

            openSocket();
        } catch (IOException e1) {
            return;
        }

        // set up the buffer for incoming packets
        byte[] responseBuffer = new byte[BUFFER_SIZE];
        DatagramPacket response = new DatagramPacket(responseBuffer, BUFFER_SIZE);

        // loop!
        while (true) {
            // zero the incoming buffer for good measure.
            java.util.Arrays.fill(responseBuffer, (byte) 0); // clear buffer

            // receive a packet (or process an incoming command)
            try {
                multicastSocket.receive(response);
            } catch (IOException e) {
                // check for commands to be run
                Command cmd = commandQueue.poll();
                if (cmd == null) {
                    return;
                }

                // reopen the socket
                try {
                    openSocket();
                } catch (IOException e1) {
                    return;
                }

                // process commands
                if (cmd instanceof QueryCommand) {
                    try {
                        query(((QueryCommand) cmd).host);
                    } catch (IOException e1) {
                    }
                } else if (cmd instanceof QuitCommand) {
                    break;
                }

                continue;
            }

            // ignore our own packet transmissions.
            if (localAddresses.contains(response.getAddress())) {
                continue;
            }

            // parse the DNS packet
            DNSMessage message;
            try {
                message = new DNSMessage(response.getData(), response.getOffset(), response.getLength());
            } catch (Exception e) {
                continue;
            }

            if(message != null) {
                String[] valueArray = message.toString().split("\n");
                if(valueArray.length == 2) {
                    if(valueArray[0].contains(this.filterHost)) {
                        if(valueArray[1].toLowerCase().contains("txt")) {
                            WritableMap service = new WritableNativeMap();
                            service.putString(ZeroconfModule.KEY_SERVICE_NAME, valueArray[0].replace("." + this.filterHost, ""));
                            final InetAddress host = response.getAddress();
                            final String fullServiceName;
                            if (host == null) {
                                fullServiceName = valueArray[0];
                            } else {
                                fullServiceName = valueArray[0];
                                service.putString(ZeroconfModule.KEY_SERVICE_HOST, valueArray[0].replace("." + this.filterHost, ""));

                                WritableArray addresses = new WritableNativeArray();
                                addresses.pushString(host.getHostAddress());

                                service.putArray(ZeroconfModule.KEY_SERVICE_ADDRESSES, addresses);
                            }
                            service.putString(ZeroconfModule.KEY_SERVICE_FULL_NAME, fullServiceName);
                            service.putInt(ZeroconfModule.KEY_SERVICE_PORT, MDNS_PORT);

                            WritableMap txtRecords = new WritableNativeMap();

                            valueArray[1] = valueArray[1].replace(" txt ", "").replace(" TXT ", "");
                            String[] attributes = valueArray[1].split("//");
                            for (String keys : attributes) {
                                String[] recordValue = keys.split("=");
                                if(recordValue.length == 2) {
                                    txtRecords.putString(String.format(Locale.getDefault(), "%s", recordValue[0].trim()), String.format(Locale.getDefault(), "%s", recordValue[1] != null ? recordValue[1].trim() : ""));
                                }
                            }

                            service.putMap(ZeroconfModule.KEY_SERVICE_TXT, txtRecords);

                            sendEvent(this.context, ZeroconfModule.EVENT_UPDATE, service);
                        }
                    }
                }
            }

        }
    }

    protected void sendEvent(ReactContext reactContext,
                             String eventName,
                             @Nullable Object params) {
        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName, params);
    }

    /**
     * Transmit an mDNS query on the local network.
     * @param host
     * @throws IOException
     */
    private void query(String host) throws IOException {
        byte[] requestData = (new DNSMessage(host)).serialize();
        DatagramPacket request =
                new DatagramPacket(requestData, requestData.length, InetAddress.getByAddress(MDNS_ADDR), MDNS_PORT);
        multicastSocket.send(request);
    }

    // inter-process communication
    // poor man's message queue

    private String filterHost = null;

    private Queue<Command> commandQueue = new ConcurrentLinkedQueue<Command>();
    private static abstract class Command {
    }
    private static class QuitCommand extends Command {}
    private static class QueryCommand extends Command {
        public QueryCommand(String host) {
            this.host = host;
        }
        public String host;
    }
    public void submitQuery(String host) {
        commandQueue.offer(new QueryCommand(host));
        filterHost = host;
        if(multicastSocket != null) {
            multicastSocket.close();
        }
    }
    public void submitQuit() {
        commandQueue.offer(new QuitCommand());
        if (multicastSocket != null) {
            multicastSocket.close();
        }
    }
}
