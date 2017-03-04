/**
 * Copyright (c) 2010-2017 by the respective copyright holders.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.network.handler;

import static org.openhab.binding.network.NetworkBindingConstants.*;

import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.DecimalType;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.eclipse.smarthome.core.types.RefreshType;
import org.openhab.binding.network.service.NetworkService;
import org.openhab.binding.network.service.StateUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link NetworkHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Marc Mettke - Initial contribution
 */
public class NetworkHandler extends BaseThingHandler implements StateUpdate {
    private Logger logger = LoggerFactory.getLogger(NetworkHandler.class);
    private NetworkService networkService;

    private boolean lastKnownOnlineState = false;
    private double lastKnownPingTime = 0;

    public NetworkHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void dispose() {
        networkService.stopAutomaticRefresh();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command instanceof RefreshType) {
            switch (channelUID.getId()) {
                case CHANNEL_ONLINE:
                    updateState(CHANNEL_ONLINE, lastKnownOnlineState ? OnOffType.ON : OnOffType.OFF);
                    break;
                case CHANNEL_TIME:
                    updateState(CHANNEL_TIME, new DecimalType(lastKnownPingTime));
                    break;
                default:
                    logger.debug("Command received for an unknown channel: {}", channelUID.getId());
                    break;
            }
        } else {
            logger.debug("Command {} is not supported for channel: {}", command, channelUID.getId());
        }
    }

    @Override
    public void newState(double state) {
        lastKnownOnlineState = state >= 0;
        lastKnownPingTime = state;

        updateState(CHANNEL_ONLINE, lastKnownOnlineState ? OnOffType.ON : OnOffType.OFF);
        updateState(CHANNEL_TIME, new DecimalType(lastKnownPingTime));
    }

    @Override
    public void invalidConfig() {
        updateStatus(ThingStatus.OFFLINE);
    }

    int confValueToInt(Object value) {
        return value instanceof java.math.BigDecimal ? ((java.math.BigDecimal) value).intValue()
                : Integer.valueOf((String) value);
    }

    boolean confValueToBoolean(Object value) {
        return value instanceof Boolean ? ((Boolean) value) : Boolean.valueOf((String) value);
    }

    @Override
    public void initialize() {
        logger.debug("Initialize Network handler.");
        this.networkService = new NetworkService();
        Configuration conf = this.getConfig();

        super.initialize();

        networkService.setHostname(String.valueOf(conf.get(PARAMETER_HOSTNAME)));
        Object value;

        value = conf.get(PARAMETER_PORT);
        if (value != null) {
            networkService.setPort(confValueToInt(value));
        }

        value = conf.get(PARAMETER_RETRY);
        if (value != null) {
            networkService.setRetry(confValueToInt(value));
        }

        value = conf.get(PARAMETER_REFRESH_INTERVAL);
        if (value != null) {
            networkService.setRefreshInterval(value instanceof java.math.BigDecimal
                    ? ((java.math.BigDecimal) value).longValue() : Integer.valueOf((String) value));
        }

        value = conf.get(PARAMETER_TIMEOUT);
        if (value != null) {
            networkService.setTimeout(confValueToInt(value));
        }

        value = conf.get(PARAMETER_DHCPLISTEN);
        if (value != null) {
            networkService.setDHCPListen(confValueToBoolean(value));
        }

        value = conf.get(PARAMETER_USE_SYSTEM_PING);
        if (value != null) {
            networkService.setUseSystemPing(confValueToBoolean(value));
        }

        networkService.startAutomaticRefresh(scheduler, this);
    }

}
