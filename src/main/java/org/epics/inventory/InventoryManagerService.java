package org.epics.inventory;

import static org.epics.gpclient.GPClient.cacheLastValue;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.SynchronousQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.epics.gpclient.GPClient;
import org.epics.gpclient.PVEvent;
import org.epics.gpclient.PVReader;
import org.epics.gpclient.PVReaderListener;
import org.epics.vtype.VString;
import org.phoebus.channelfinder.Channel;
import org.phoebus.channelfinder.ChannelFinderClient;
import org.phoebus.channelfinder.ChannelFinderService;
import org.phoebus.channelfinder.Property.Builder;
import org.phoebus.util.shell.CommandShell;

import edu.msu.nscl.olog.api.LogBuilder;
import edu.msu.nscl.olog.api.LogbookBuilder;
import edu.msu.nscl.olog.api.OlogClient;
import edu.msu.nscl.olog.api.OlogClientImpl;

/**
 * A simple manager service which monitors a set of pv's, upon a value change it
 * performs a set of operations including.
 * 
 * 1. creating a log entry
 * 
 * 2. updating channelfinder
 * 
 * 3. email configured user
 * 
 * @author Kunal Shroff
 *
 */

public class InventoryManagerService {

    private static final Logger logger = Logger.getLogger(InventoryManagerService.class.getName());

    private volatile CommandShell shell;
    private final SynchronousQueue<Boolean> restart = new SynchronousQueue<>();

    public static final String deviceProperty = "device";
    public static final String serialProperty = "serialNumber";

    private static OlogClient logbook;
    static ChannelFinderClient client;
    static Collection<Channel> serialNumberChannels;
    static Map<String, PVReader<VString>> monitoredChannels = new HashMap<>();


    public static void main(String[] args) {
        new InventoryManagerService();
    }

    private static final String COMMANDS = "Commands:\n\n"
            + "\trestart          - Re-load alarm configuration and restart.\n"
            + "\tshutdown         - Shut alarm server down and exit.\n";

    private InventoryManagerService() {
        try {
            // 'main' loop that keeps performing a full startup and shutdown
            // whenever a 'restart' is requested.
            boolean run = true;
            while (run) {
                logger.info("String InventoryManagerService...");
                init();
                shell = new CommandShell(COMMANDS, this::handleShellCommands);
                // Start the command shell at the root node.
                shell.start();

                // Run until, via command topic or shell input, asked to
                // a) restart (restart given with value 'true')
                // b) shut down (restart given with value 'false')
                run = restart.take();
                if (run)
                    logger.info("Restarting...");
                else
                    logger.info("Shutting down");

                shell.stop();
                stop();
            }
        } catch (final Throwable ex) {
            logger.log(Level.SEVERE, "Alarm Server main loop error", ex);
        }

        logger.info("Done.");
        System.exit(0);
    }

    static void init() {

        logger.info("Setting the properties for olog and channelfinder...");
        System.setProperty("olog.properties",
                InventoryManagerService.class.getClassLoader().getResource("olog.properties").getPath());
        System.setProperty("channelfinder.properties",
                InventoryManagerService.class.getClassLoader().getResource("channelfinder.properties").getPath());

        try {

            logger.info("creating clients for olog and channelfinder...");
            logbook = OlogClientImpl.OlogClientBuilder.serviceURL().withHTTPAuthentication(true).create();
            client = ChannelFinderService.getInstance().getClient();
            startConnections(queryCF());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to create logbook client", e);
        }
    }


    /**
     * Search for channels in channelfinder with property "SerialNumber"
     * 
     * @return a list of all channels with property "SerialNumber"
     */
    static Collection<Channel> queryCF() {
        // load the configuration, this is a channelfiner query which returns the list
        // of pv's which are to be monitored.
        Map<String, String> searchMap = new HashMap<String, String>();
        searchMap.put(serialProperty, "*");
        Collection<Channel> channels = client.find(searchMap);
        logger.info("Found " + channels.size() + " serial channels.");
        return channels;
    }

    /**
     * Create connections to all the channels provided, and on
     * 
     * @param channels
     */
    static void startConnections(Collection<Channel> channels) {
        channels.forEach(ch -> {
            logger.info("Creating a monitor for : " + ch.getName());
            PVReader<VString> pv = GPClient
                    .read(GPClient.channel(ch.getName(), cacheLastValue(VString.class)))
                    .addReadListener(new ValueProcessor(ch.getName(), client, logbook)).start();
            monitoredChannels.put(ch.getName(), pv);
        });
    }

    public static void stop() {
        monitoredChannels.entrySet().forEach((e) -> {
            e.getValue().close();
        });
    }


    private void restart() {
        stop();
        init();
    }

    public static class ValueProcessor implements PVReaderListener<VString> {

        private final ChannelFinderClient client;
        private final String name;
        private final OlogClient logClient;

        ValueProcessor(String name, ChannelFinderClient client, OlogClient logClient) {
            this.name = name;
            this.client = client;
            this.logClient = logClient;
        }

        @Override
        public void pvChanged(PVEvent event, PVReader<VString> p) {
            logger.info("pv event for " + name + ": " + event + " " + p.isConnected() + " " + p.getValue());
            if (p.getValue() != null) {
                // update channelfinder
                Collection<Channel> oldChannels = client.findByName(name);
                String val = p.getValue().getValue();
                client.update(Builder.property(serialProperty, val), name);
                Collection<Channel> updatedChannels = client.findByName(name);

                // update the logbook
                StringBuffer sb = new StringBuffer();
                sb.append("Serial number updated for channels \n");
                sb.append("Old Channels: \n");
                oldChannels.stream().forEach(ch -> {
                    sb.append(ch.toString() + "\n");
                    sb.append("Property:\n");
                    ch.getProperties().forEach(property -> {
                        sb.append(property.getName() + ":" + property.getValue() + "\n");
                    });
                    sb.append("Tag:\n");
                    ch.getTags().forEach(tag -> {
                        sb.append(tag.getName());
                    });
                });
                sb.append("Updated Channels: \n");
                updatedChannels.stream().forEach(ch -> {
                    sb.append(ch.toString() + "\n");
                    sb.append("Property:\n");
                    ch.getProperties().forEach(property -> {
                        sb.append(property.getName() + ":" + property.getValue() + "\n");
                    });
                    sb.append("Tag:\n");
                    ch.getTags().forEach(tag -> {
                        sb.append(tag.getName());
                    });
                });

                LogBuilder entry = LogBuilder.log().description(sb.toString()).level("Info")
                        .appendToLogbook(LogbookBuilder.logbook("Operations"));
                logger.info(entry.toString());
                logClient.set(entry);
            }

        }

    }

    /**
     * Handle shell commands. Passed to command shell.
     * 
     * @param args
     *            - variadic String
     * @return result - boolean result of executing the command.
     * @throws Throwable
     */
    private boolean handleShellCommands(final String... args) throws Throwable {

        if (args == null)
            restart.offer(false);
        else if (args.length == 1) {
            if (args[0].startsWith("shut"))
                restart.offer(false);
            else if (args[0].equals("restart"))
                restart.offer(true);
        }
        return true;
    }

}

