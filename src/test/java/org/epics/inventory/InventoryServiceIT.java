package org.epics.inventory;

import static org.epics.inventory.InventoryManagerService.deviceProperty;
import static org.epics.inventory.InventoryManagerService.serialProperty;
import static org.junit.Assert.assertTrue;
import static org.phoebus.channelfinder.Channel.Builder.channel;
import static org.phoebus.channelfinder.Property.Builder.property;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.epics.gpclient.GPClient;
import org.epics.gpclient.PV;
import org.epics.vtype.VType;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.phoebus.channelfinder.Channel;
import org.phoebus.channelfinder.ChannelFinderClient;
import org.phoebus.channelfinder.ChannelFinderService;
import org.phoebus.channelfinder.Property;

public class InventoryServiceIT {
    static Collection<String> createdProperties = new ArrayList<>();
    static Collection<Channel.Builder> createdChannels = new ArrayList<>();

    @BeforeClass
    public static void setup() {

        InventoryManagerService.init();
        // create a set of test channels
        ChannelFinderClient client = ChannelFinderService.getInstance().getClient();
        Collection<String> existingPropertyNames = client.getAllProperties();

        if (!existingPropertyNames.contains(deviceProperty)) {
            client.set(Property.Builder.property(deviceProperty));
            createdProperties.add(deviceProperty);
        }
        if (!existingPropertyNames.contains(serialProperty)) {
            client.set(Property.Builder.property(serialProperty));
            createdProperties.add(serialProperty);
        }

        for (int i = 0; i < 10; i++) {
            createdChannels.add(channel("test_" + i).with(property(deviceProperty, "device" + i))
                    .with(property(serialProperty, "ser_#" + i)));
        }
        client.set(createdChannels);
    }

    @Test
    public void test() {
        Collection<Channel> channels = InventoryManagerService.queryCF();
        InventoryManagerService.startConnections(channels);
        // Write values to the test channels
        channels.forEach(ch -> {
            PV<VType, Object> pv = GPClient.readAndWrite(GPClient.channel("loc://" + ch.getName())).addReadListener((event, p) ->{
                // do nothing
            }).start();
            pause(1000);
            if (pv.isWriteConnected()) {
                pv.write(ch.getProperty(serialProperty).getValue() + "_2");
            }
            pv.close();
        });

        pause(10000);

        // Check if channelfinder has been updated
        ChannelFinderClient client = ChannelFinderService.getInstance().getClient();

        // client.find(map)
        Map<String, String> searchMap = new HashMap<String, String>();
        searchMap.put(serialProperty, "*");

        Collection<Channel> updatedChannels = client.find(searchMap);
        updatedChannels.stream().forEach(ch -> {
            System.out.println(
                    ch.getName() + " " + serialProperty + " property " + ch.getProperty(serialProperty).getValue());
            assertTrue(
                    "Failed to updated channel " + ch.getName() + " " + serialProperty + " property "
                            + ch.getProperty(serialProperty).getValue(),
                    ch.getProperty(serialProperty).getValue().endsWith("_2"));
        });

        pause(10000);
        InventoryManagerService.stop();
    }

    @AfterClass
    public static void cleanup() {
        final ChannelFinderClient client = ChannelFinderService.getInstance().getClient();
        createdProperties.stream().forEach(property -> {
            client.deleteProperty(property);
        });
        createdChannels.stream().forEach(ch -> {
            client.deleteChannel(ch.build().getName());
        });
    }

    public static void pause(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Logger.getLogger(InventoryServiceIT.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
