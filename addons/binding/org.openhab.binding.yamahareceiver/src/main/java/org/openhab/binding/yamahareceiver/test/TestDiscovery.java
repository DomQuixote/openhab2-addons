/**
 * Copyright (c) 2014-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.yamahareceiver.test;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.UUID;

import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.jupnp.model.meta.DeviceDetails;
import org.jupnp.model.meta.ManufacturerDetails;
import org.jupnp.model.meta.ModelDetails;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.RemoteDeviceIdentity;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.model.types.DeviceType;
import org.jupnp.model.types.UDN;
import org.openhab.binding.yamahareceiver.YamahaReceiverBindingConstants;
import org.openhab.binding.yamahareceiver.discovery.YamahaDiscoveryParticipant;

public class TestDiscovery extends AbstractDiscoveryService {
    private YamahaEmulatedHttpXMLServer server;
    private URL url;

    public TestDiscovery() {
        super(YamahaReceiverBindingConstants.BRIDGE_THING_TYPES_UIDS, 2, true);
        try {
            url = new URL("http", "127.0.0.1", 12121, "");
            server = new YamahaEmulatedHttpXMLServer(url.getPort());
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void startScan() {
        try {
            YamahaDiscoveryParticipant testClass = new YamahaDiscoveryParticipant();
            DeviceDetails details = new DeviceDetails(url, "Yamaha AVR Test", new ManufacturerDetails("YAMAHA"),
                    new ModelDetails("AVR Test"), "123456789", "1234-1234-1234-1234", null, null, null, null);
            DeviceType type = new DeviceType("org.test", YamahaReceiverBindingConstants.UPNP_TYPE);
            RemoteDevice device;
            RemoteDeviceIdentity identity = new RemoteDeviceIdentity(
                    new UDN(UUID.fromString("e49825eb-8d1f-4e7a-8de4-c091850597f5")), 10, url, null,
                    InetAddress.getByName(url.getHost()));
            device = new RemoteDevice(identity, type, details, (RemoteService) null);

            DiscoveryResult discoveryResult = testClass.createResult(device);
            thingDiscovered(discoveryResult);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
