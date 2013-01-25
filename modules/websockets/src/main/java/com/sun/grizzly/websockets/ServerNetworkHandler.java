/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package com.sun.grizzly.websockets;

import com.sun.grizzly.arp.AsyncProcessorTask;
import com.sun.grizzly.arp.AsyncTask;
import com.sun.grizzly.http.servlet.HttpServletRequestImpl;
import com.sun.grizzly.http.servlet.HttpServletResponseImpl;
import com.sun.grizzly.http.servlet.ServletContextImpl;
import com.sun.grizzly.tcp.Constants;
import com.sun.grizzly.tcp.Request;
import com.sun.grizzly.tcp.Response;
import com.sun.grizzly.tcp.http11.GrizzlyRequest;
import com.sun.grizzly.tcp.http11.GrizzlyResponse;
import com.sun.grizzly.tcp.http11.InternalInputBuffer;
import com.sun.grizzly.tcp.http11.InternalOutputBuffer;
import com.sun.grizzly.util.InputReader;
import com.sun.grizzly.util.buf.ByteChunk;
import com.sun.grizzly.util.buf.MessageBytes;
import com.sun.grizzly.util.buf.UDecoder;
import com.sun.grizzly.util.http.Cookie;
import com.sun.grizzly.util.http.HttpRequestURIDecoder;
import com.sun.grizzly.util.http.mapper.Mapper;
import com.sun.grizzly.util.http.mapper.MappingData;
import com.sun.grizzly.websockets.glassfish.GlassfishSupport;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.security.Principal;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

public class ServerNetworkHandler extends BaseNetworkHandler {

    private static final Logger LOGGER = Logger.getLogger(WebSocketEngine.WEBSOCKET);
    private final Request request;
    private final Response response;
    private final HttpServletRequest httpServletRequest;
    private final HttpServletResponse httpServletResponse;
    private final InternalInputBuffer inputBuffer;
    private final InternalOutputBuffer outputBuffer;
    private UDecoder urlDecoder = new UDecoder();
    private final ProtocolHandler protocolHandler;
    private boolean isClosed;

    public ServerNetworkHandler(Request req, Response resp,
            ProtocolHandler protocolHandler, Mapper mapper) {
        request = req;
        response = resp;
        final WSGrizzlyRequestImpl grizzlyRequest = new WSGrizzlyRequestImpl();
        grizzlyRequest.setRequest(request);
        final GrizzlyResponse grizzlyResponse = new GrizzlyResponse();
        grizzlyResponse.setResponse(response);
        grizzlyRequest.setResponse(grizzlyResponse);
        grizzlyResponse.setRequest(grizzlyRequest);
        try {
            // Has to be called before servlet request/response wrappers initialization
            grizzlyRequest.parseSessionId();

            final WSServletRequestImpl wsServletRequest =
                    new WSServletRequestImpl(grizzlyRequest, mapper);
            httpServletRequest = wsServletRequest;
            httpServletResponse = new HttpServletResponseImpl(grizzlyResponse);

            wsServletRequest.initSession();
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }

        this.protocolHandler = protocolHandler;
        inputBuffer = (InternalInputBuffer) req.getInputBuffer();
        outputBuffer = (InternalOutputBuffer) resp.getOutputBuffer();

    }

    @Override
    protected int read() {
        int read;
        ByteChunk newChunk = new ByteChunk(WebSocketEngine.INITIAL_BUFFER_SIZE);

        Throwable error = null;

        try {
            ByteChunk bytes = new ByteChunk();
            if (chunk.getLength() > 0) {
                newChunk.append(chunk);
            }

            read = inputBuffer.doRead(bytes, request);
            if (read > 0) {
                newChunk.append(bytes);
            }
        } catch (Throwable e) {
            error = e;
            read = -1;
        }

        if (read == -1) {
            throw new WebSocketException("Connection closed", error);
        }
        chunk.setBytes(newChunk.getBytes(), 0, newChunk.getEnd());
        return read;
    }

    public byte get() {
        synchronized (chunk) {
            fill();
            try {
                return (byte) chunk.substract();
            } catch (IOException e) {
                throw new WebSocketException(e.getMessage(), e);
            }
        }
    }

    public byte[] get(int count) {
        synchronized (chunk) {
            try {
                byte[] bytes = new byte[count];
                int total = 0;
                while (total < count) {
                    if (chunk.getLength() < count) {
                        read();
                    }
                    total += chunk.substract(bytes, total, count - total);
                }
                return bytes;
            } catch (IOException e) {
                throw new WebSocketException(e.getMessage(), e);
            }
        }
    }

    private void fill() {
        synchronized (chunk) {
            if (chunk.getLength() == 0) {
                read();
            }
        }
    }

    public void write(byte[] bytes) {
        synchronized (outputBuffer) {
            try {
                ByteChunk buffer = new ByteChunk();
                buffer.setBytes(bytes, 0, bytes.length);
                outputBuffer.doWrite(buffer, response);
                outputBuffer.flush();
            } catch (IOException e) {
                throw new WebSocketException(e.getMessage(), e);
            }
        }
    }

    public boolean ready() {
        synchronized (chunk) {
            return chunk.getLength() != 0;
        }
    }

    public HttpServletRequest getRequest() throws IOException {
        return httpServletRequest;
    }

    public HttpServletResponse getResponse() throws IOException {
        return httpServletResponse;
    }

    public synchronized void close() {
        if (!isClosed) {
            isClosed = true;
            //            key.cancel();
            protocolHandler.getProcessorTask().setAptCancelKey(true);
//            protocolHandler.getProcessorTask().terminateProcess();
            final AsyncProcessorTask asyncProcessorTask =
                    protocolHandler.getAsyncTask();
            asyncProcessorTask.setStage(AsyncTask.FINISH);
            try {
                asyncProcessorTask.doTask();
            } catch (Exception e) {
                e.printStackTrace();
            }

            protocolHandler.getWebSocket().onClose(null);
        }
    }

    private class WSGrizzlyRequestImpl extends GrizzlyRequest {

        /**
         * Make method visible for websockets
         */
        @Override
        protected void parseSessionId() {
            // Try to get session id from request-uri
            super.parseSessionId();

            // Try to get session id from cookie
            Cookie[] parsedCookies = getCookies();
            if (parsedCookies != null) {
                for (Cookie c : parsedCookies) {
                    if (Constants.SESSION_COOKIE_NAME.equals(c.getName())) {
                        setRequestedSessionId(c.getValue());
                        setRequestedSessionCookie(true);
                        break;
                    }
                }
            }
        }
    } // END WSServletRequestImpl

    private class WSServletRequestImpl extends HttpServletRequestImpl {

        private final GlassfishSupport glassfishSupport;
        private String pathInfo;
        private String servletPath;
        private String contextPath;
        private boolean isUserPrincipalUpdated;

        public WSServletRequestImpl(GrizzlyRequest r, Mapper mapper) throws IOException {
            super(r);
            setContextImpl(new ServletContextImpl());
            if (mapper != null) {
                final MappingData mappingData = updatePaths(r, mapper);
                glassfishSupport = new GlassfishSupport(mappingData.context,
                        mappingData.wrapper, this);
            } else {
                glassfishSupport = new GlassfishSupport();
            }
        }

        @Override
        protected void initSession() {
            if (!glassfishSupport.isValid()) {
                super.initSession();
            }
        }

        @Override
        public HttpSession getSession(boolean create) {
            if (glassfishSupport.isValid()) {
                return glassfishSupport.getSession(create);
            }

            return super.getSession(create);
        }

        @Override
        public boolean isUserInRole(String role) {
            if (glassfishSupport.isValid()) {
                return glassfishSupport.isUserInRole(role);
            }

            return super.isUserInRole(role);
        }

        @Override
        public Principal getUserPrincipal() {
            checkGlassfishAuth();

            return super.getUserPrincipal();
        }

        @Override
        public String getRemoteUser() {
            checkGlassfishAuth();

            return super.getRemoteUser();
        }

        @Override
        public String getAuthType() {
            checkGlassfishAuth();

            return super.getAuthType();
        }

        @Override
        public String getContextPath() {
            return contextPath;
        }

        @Override
        public String getServletPath() {
            return servletPath;
        }

        @Override
        public String getPathInfo() {
            return pathInfo;
        }

        private MappingData updatePaths(GrizzlyRequest r, Mapper mapper) {
            final Request req = r.getRequest();
            try {
                MessageBytes decodedURI = req.decodedURI();
                decodedURI.duplicate(req.requestURI());
                HttpRequestURIDecoder.decode(decodedURI, urlDecoder, null, null);
                MappingData data = new MappingData();
                mapper.map(req.remoteHost(), decodedURI, data);
                pathInfo = data.pathInfo.toString();
                servletPath = data.wrapperPath.toString();
                contextPath = data.contextPath.toString();

                return data;
            } catch (Exception e) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "Unable to map request", e);
                }
                pathInfo = null;
                servletPath = null;
                contextPath = null;
            }

            return null;
        }

        private void checkGlassfishAuth() {
            if (glassfishSupport.isValid() && !isUserPrincipalUpdated) {
                isUserPrincipalUpdated = true;
                glassfishSupport.updateUserPrincipal(WSServletRequestImpl.this.request);
            }
        }
    } // END WSServletRequestImpl

    @Override
    public String toString() {
        final InputReader inputStream = (InputReader) inputBuffer.getInputStream();
        final int remoteSocketAddress =
                ((SocketChannel) inputStream.key.channel()).socket().getPort();
        final StringBuilder sb = new StringBuilder();
        sb.append("SNH[");
        sb.append(remoteSocketAddress);
        sb.append(",").append(super.toString());
        sb.append(']');
        return sb.toString();
    }
}