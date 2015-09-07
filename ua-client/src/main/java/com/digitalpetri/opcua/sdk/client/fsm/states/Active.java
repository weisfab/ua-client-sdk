/*
 * digitalpetri OPC-UA SDK
 *
 * Copyright (C) 2015 Kevin Herron
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.digitalpetri.opcua.sdk.client.fsm.states;

import java.util.concurrent.CompletableFuture;

import com.digitalpetri.opcua.sdk.client.OpcUaClient;
import com.digitalpetri.opcua.sdk.client.api.ServiceFaultListener;
import com.digitalpetri.opcua.sdk.client.api.UaSession;
import com.digitalpetri.opcua.sdk.client.fsm.SessionState;
import com.digitalpetri.opcua.sdk.client.fsm.SessionStateEvent;
import com.digitalpetri.opcua.sdk.client.fsm.SessionStateFsm;
import com.digitalpetri.opcua.stack.client.UaTcpStackClient;
import com.digitalpetri.opcua.stack.core.StatusCodes;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Active implements SessionState {

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private volatile ServiceFaultListener faultListener;

    private final UaSession session;
    private final CompletableFuture<UaSession> future;

    public Active(UaSession session, CompletableFuture<UaSession> future) {
        this.session = session;
        this.future = future;
    }

    @Override
    public CompletableFuture<Void> activate(SessionStateEvent event, SessionStateFsm fsm) {
        OpcUaClient client = fsm.getClient();
        UaTcpStackClient stackClient = client.getStackClient();

        client.addFaultListener(faultListener = serviceFault -> {
            long statusCode = serviceFault.getResponseHeader().getServiceResult().getValue();

            if (statusCode == StatusCodes.Bad_SessionIdInvalid) {
                logger.warn("ServiceFault: {}", serviceFault.getResponseHeader().getServiceResult());
                fsm.handleEvent(SessionStateEvent.ERR_SESSION_INVALID);
            }
        });

        stackClient.getChannelFuture().thenAccept(sc -> {
            Channel channel = sc.getChannel();

            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                    fsm.handleEvent(SessionStateEvent.ERR_CONNECTION_LOST);
                }
            });
        });

        client.getSubscriptionManager().restartPublishing();

        future.complete(session);

        return CF_VOID_COMPLETED;
    }

    @Override
    public CompletableFuture<Void> deactivate(SessionStateEvent event, SessionStateFsm fsm) {
        OpcUaClient client = fsm.getClient();

        client.removeFaultListener(faultListener);

        return CF_VOID_COMPLETED;
    }

    @Override
    public SessionState transition(SessionStateEvent event, SessionStateFsm fsm) {
        switch (event) {
            case DISCONNECT_REQUESTED:
                return new ClosingSession(session);

            case ERR_CONNECTION_LOST:
                return new Reactivating(session, 0);

            case ERR_SESSION_INVALID:
                return new CreatingSession(new CompletableFuture<>());
        }

        return this;
    }

    @Override
    public CompletableFuture<UaSession> getSessionFuture() {
        return future;
    }


}