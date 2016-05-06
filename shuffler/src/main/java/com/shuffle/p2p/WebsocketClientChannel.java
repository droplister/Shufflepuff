/**
 *
 * Copyright © 2016 Mycelium.
 * Use of this source code is governed by an ISC
 * license that can be found in the LICENSE file.
 *
 */

package com.shuffle.p2p;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.MessageHandler;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import sun.reflect.generics.reflectiveObjects.NotImplementedException;

/**
 *
 *
 * Created by Daniel Krawisz on 1/31/16.
 */

/**
 * A manager for websocket connections.
 */

public class WebsocketClientChannel implements Channel<URI, Bytestring> {

    /**
     *  Necessary class to use the javax.websocket library.
     */

    private Listener<URI, Bytestring> globalListener = null;

    @ClientEndpoint
    public class WebsocketClientEndpoint {

        Session userSession = null;
        URI uri;
        Receiver<Bytestring> receiver = null;

        public WebsocketClientEndpoint(URI endpointUri) {
            this.uri = endpointUri;
        }

        public Session newSession() throws RuntimeException, DeploymentException, IOException {
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            return container.connectToServer(this, this.uri);
        }

        @OnOpen
        public void onOpen(Session userSession) {
            this.userSession = userSession;
            // receiver cannot be initialized here because @OnOpen is called before
            // the associated WebsocketSession is added to openSessions, resulting in
            // a NullPointerException.
        }

        @OnMessage
        public void onMessage(byte[] message, Session userSession)  {
            Bytestring bytestring = new Bytestring(message);

            if (receiver == null) {
                WebsocketPeer.WebsocketSession session = openSessions.get(this.uri);
                try {
                    receiver = globalListener.newSession(session);
                } catch (InterruptedException e) {
                    return;
                }
            }

            try {
                receiver.receive(bytestring);
            } catch (InterruptedException e) {
                return;
            }
        }

        @OnClose
        public void onClose(Session userSession, CloseReason reason) {
            this.userSession = null;
            receiver = null;
        }
    }

    // Only one object representing each peer is allowed at a time.
    private class Peers {

        private final Map<URI, WebsocketPeer> peers = new HashMap<>();

        public synchronized WebsocketPeer get(URI identity) {
            WebsocketPeer peer = peers.get(identity);
            if (peer == null) {
                peer = new WebsocketPeer(identity);
                peers.put(identity, peer);
            }
            return peer;
        }

        // remove function?
    }

    final Peers peers = new Peers();

    // A special class used to house synchronized functions regarding the list of open sessions.
    class OpenSessions {

        // The sessions which are currently open.
        private Map<URI, WebsocketPeer.WebsocketSession> openSessions = new ConcurrentHashMap<>();

        // We don't want to overwrite a session that already exists, so this is in a synchronized
        // function. This is for creating new sessions.
        public synchronized WebsocketPeer.WebsocketSession putNewSession(
                URI identity,
                WebsocketPeer peer) {

            WebsocketPeer.WebsocketSession openSession = openSessions.get(identity);
            if (openSession != null) {
                if (openSession.session.isOpen()) {
                    return null;
                }

                openSessions.remove(identity);
            }

            WebsocketPeer.WebsocketSession session = null;
            try {
                session = peer.newSession();
            } catch (DeploymentException e) {
                return null;
            }

            openSessions.put(identity, session);

            return session;
        }

        public void add(URI identity, WebsocketPeer.WebsocketSession session) {
            openSessions.put(identity, session);
        }

        public WebsocketPeer.WebsocketSession get(URI identity) {
            return openSessions.get(identity);
        }

        public WebsocketPeer.WebsocketSession remove(URI identity) {
            return openSessions.remove(identity);
        }

        public void closeAll() {
            for (WebsocketPeer.WebsocketSession session : openSessions.values()) {
                session.close();
            }
        }

    }

    private OpenSessions openSessions = null;

    // Class definition for representation of a particular websocket peer.
    public class WebsocketPeer extends FundamentalPeer<URI, Bytestring> {

        WebsocketSession currentSession;

        // Constructor for initiating a connection.
        public WebsocketPeer(URI identity) {
            super(identity);
        }

        private WebsocketPeer setSession(javax.websocket.Session session) throws IOException {
            // this doesn't add to openSessions... the way class OpenSessions is constructed, it can't.
            // add() function now exists in OpenSessions, is this okay?
            currentSession = new WebsocketSession(session);
            openSessions.add(identity(), currentSession);
            return this;
        }

        WebsocketPeer.WebsocketSession newSession() throws DeploymentException {
            try {
                // this doesn't add to openSessions either.
                // add() function now exists in OpenSessions, is this okay?
                currentSession = this.new WebsocketSession(new WebsocketClientEndpoint(identity()).newSession());
                openSessions.add(identity(), currentSession);
                return currentSession;
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public synchronized com.shuffle.p2p.Session<URI, Bytestring> openSession(
                final Receiver<Bytestring> receiver) {
            // Don't allow sessions to be opened when we're opening or closing the channel.
            synchronized (lock) { }

            if (openSessions == null) {
                return null;
            }

            if (currentSession != null) {
                return null;
            }

            final WebsocketSession session = openSessions.putNewSession(identity(), this);

            if (session == null) {
                return null;
            }

            // is this necessary?
            // not quite sure of this function's purpose.
            session.session.addMessageHandler(new MessageHandler.Whole<byte[]>() {
                public void onMessage(byte[] message) {
                    try {
                        receiver.receive(new Bytestring(message));
                    } catch (InterruptedException e) {
                        session.close();
                    }
                }
            });

            return session;

        }

        // Encapsulates a particular websocket session.
        public class WebsocketSession implements com.shuffle.p2p.Session<URI, Bytestring> {
            javax.websocket.Session session;

            public WebsocketSession(javax.websocket.Session session) throws IOException {
                this.session = session;
            }

            @Override
            public synchronized boolean send(Bytestring message) {
                // Don't allow sending messages while we're opening or closing the channel.
                synchronized (lock) { }

                if (!session.isOpen()) {
                    return false;
                }

                try {
                    // MUST sendBinary rather than sendText to receive byte[] messages
                    ByteBuffer buf = ByteBuffer.wrap(message.bytes);
                    session.getBasicRemote().sendBinary(buf);
                } catch (IOException e) {
                    return false;
                }
                return true;
            }

            @Override
            public synchronized void close() {
                try {
                    session.close();
                } catch (IOException e) {

                }
                session = null;
                WebsocketPeer.this.currentSession = null;
                openSessions.remove(WebsocketPeer.this.identity());
            }

            @Override
            public synchronized boolean closed() {
                return session == null || !session.isOpen();
            }

            @Override
            public Peer<URI, Bytestring> peer() {
                return WebsocketPeer.this;
            }

        }

    }

    private boolean running = false;
    private final Object lock = new Object();

    public WebsocketClientChannel() {

    }

    private class WebsocketConnection implements Connection<URI, Bytestring> {

        @Override
        public URI identity() {
            // TODO
            throw new NotImplementedException();
        }

        @Override
        public void close() {
            synchronized (lock) {
                openSessions.closeAll();
                openSessions = null;
                running = false;
            }
        }
    }

    @Override
    public Connection<URI, Bytestring> open(Listener<URI, Bytestring> listener) {
        synchronized (lock) {
            if (running) return null;
            running = true;
            openSessions = new OpenSessions();
            globalListener = listener;
            return new WebsocketConnection();
        }
    }

    @Override
    public Peer<URI, Bytestring> getPeer(URI you) {
        return peers.get(you);
    }
}
