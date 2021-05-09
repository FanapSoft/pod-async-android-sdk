package com.fanap.podasync;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.util.Log;

import com.fanap.podasync.model.AsyncConstant;
import com.fanap.podasync.model.AsyncMessageType;
import com.fanap.podasync.model.ClientMessage;
import com.fanap.podasync.model.Message;
import com.fanap.podasync.model.MessageWrapperVo;
import com.fanap.podasync.model.PeerInfo;
import com.fanap.podasync.model.RegistrationRequest;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.neovisionaries.ws.client.ThreadType;
import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketExtension;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;
import com.neovisionaries.ws.client.WebSocketListener;
import com.neovisionaries.ws.client.WebSocketState;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

import io.sentry.android.core.SentryAndroid;
import io.sentry.core.Breadcrumb;
import io.sentry.core.Sentry;
import io.sentry.core.SentryEvent;
import io.sentry.core.SentryLevel;

/*
 * By default WebSocketFactory uses for non-secure WebSocket connections (ws:)
 * and for secure WebSocket connections (wss:).
 */
public class Async {

    long currentTime = 0;

    private WebSocket webSocket;
    private static final int SOCKET_CLOSE_TIMEOUT = 110000;
    private WebSocket webSocketReconnect;
    private static final String TAG = "Async" + " ";
    private static volatile Async instance;
    private boolean isServerRegister;
    private boolean rawLog;
    private boolean isDeviceRegister;
    private static SharedPreferences sharedPrefs;
    private MessageWrapperVo messageWrapperVo;
    private static AsyncListenerManager asyncListenerManager = new AsyncListenerManager();

    private Gson gson = new GsonBuilder().create();

    private String errorMessage;
    private long lastSentMessageTime;
    private long lastReceiveMessageTime;
    private String message;
    private String state;
    private String appId;
    private String peerId;
    private String deviceID;
    private ArrayList<String> asyncQueue = new ArrayList<>();
    private String serverAddress;
    private static final Handler pingHandler;
    private static final Handler reconnectHandler;
    private static final Handler socketCloseHandler;
    private String token;
    private String serverName;
    private String ssoHost;
    private int retryStep = 1;
    private boolean reconnectOnClose = false;
    private boolean log;
    private long connectionCheckTimeout = 10000;
    private long JSTimeLatency = 100;
    private static boolean overrideSentry;


    private Async() {

    }

    public static Async getInstance(Context context) {
        if (instance == null) {
            sharedPrefs = context.getSharedPreferences(AsyncConstant.Constants.PREFERENCE, Context.MODE_PRIVATE);
            instance = new Async();

        }
        return instance;
    }

    public static Async getInstance(Context context, boolean overrideSentry) {
        if (instance == null) {
            sharedPrefs = context.getSharedPreferences(AsyncConstant.Constants.PREFERENCE, Context.MODE_PRIVATE);
            instance = new Async();

            if (overrideSentry) {
                setupSentry(context);
            }

        }
        return instance;
    }

    private static void setupSentry(Context context) {

        overrideSentry = true;

        SentryAndroid.init(context.getApplicationContext(),
                options -> {
                    options.setDsn(context.getApplicationContext().getString(R.string.async_sentry_dsn));
                    options.setCacheDirPath(context.getCacheDir().getAbsolutePath());
                    options.setSentryClientName("PodAsync-Android");
                    options.addInAppInclude("com.fanap.podasync");
                    options.setEnvironment("PODASYNC");
                });


    }


    private void onEvent(WebSocket webSocket) {
        webSocket.addListener(new WebSocketListener() {
            /**
             * Get the current state of this WebSocket.
             * <p>
             * <p>
             * The initial state is {@link WebSocketState#CREATED CREATED}.
             * When {conncet(String, String, String, String, String, String)} is called, the state is changed to
             * { CONNECTING}, and then to
             * {OPEN} after a successful opening
             * handshake. The state is changed to {CLOSING} when a closing handshake
             * is started, and then to {CLOSED}
             * when the closing handshake finished.
             * </p>
             *
             * @return The current state.
             */
            @Override
            public void onStateChanged(WebSocket websocket, WebSocketState newState) throws Exception {
                asyncListenerManager.callOnStateChanged(newState.toString());
                setState(newState.toString());
                showRawInfoLog("State" + " Is Now " + newState.toString());
                switch (newState) {
                    case OPEN:
                        reconnectHandler.removeCallbacksAndMessages(null);
                        retryStep = 1;
                        break;
                    case CLOSED:
                        stopSocket();
                        if (reconnectOnClose) {
                            retryReconnect();
                        } else {
                            showErrorLog("Socket Closed!");
                        }
                        break;
                    case CONNECTING:

                        break;
                    case CLOSING:

                        break;
                }
            }

            @Override
            public void onConnected(WebSocket websocket, Map<String, List<String>> headers) throws Exception {

            }

            @Override
            public void onConnectError(WebSocket websocket, WebSocketException cause) throws Exception {
                captureError("Connect Error", cause);
            }


            /**
             * <p>
             * Before a WebSocket is closed, a closing handshake is performed. A closing handshake
             * is started (1) when the server sends a close frame to the client or (2) when the
             * client sends a close frame to the server. You can start a closing handshake by calling
             * {disconnect} method (or by sending a close frame manually).
             * </p>
             */
            @Override
            public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) throws Exception {
                captureError("On Disconnected", serverCloseFrame.getCloseReason());
                asyncListenerManager.callOnDisconnected(serverCloseFrame.getCloseReason());
            }

            @Override
            public void onFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {

            }

            @Override
            public void onContinuationFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {

            }

            @Override
            public void onTextFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {

            }

            @Override
            public void onBinaryFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {

            }

            @Override
            public void onCloseFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {
                captureMessage("On Close Frame", frame.getCloseReason());
            }

            @Override
            public void onPingFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {

            }

            @Override
            public void onPongFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {

            }


            /**
             * @param textMessage that received when socket send message to Async
             */
            @Override
            public void onTextMessage(WebSocket websocket, String textMessage) throws Exception {
                captureMessage("On Text Message", textMessage);
                int type = 0;
                lastReceiveMessageTime = new Date().getTime();

                ClientMessage clientMessage = gson.fromJson(textMessage, ClientMessage.class);
                if (clientMessage != null) {
                    type = clientMessage.getType();
                }
                scheduleCloseSocket();

                @AsyncMessageType.MessageType int currentMessageType = type;
                switch (currentMessageType) {
                    case AsyncMessageType.MessageType.ACK:
                        handleOnAck(clientMessage);
                        break;
                    case AsyncMessageType.MessageType.DEVICE_REGISTER:
                        handleOnDeviceRegister(websocket, clientMessage);
                        break;
                    case AsyncMessageType.MessageType.ERROR_MESSAGE:
                        handleOnErrorMessage(clientMessage);
                        break;
                    case AsyncMessageType.MessageType.MESSAGE_ACK_NEEDED:
                    case AsyncMessageType.MessageType.MESSAGE_SENDER_ACK_NEEDED:

                        handleOnMessageAckNeeded(websocket, clientMessage);
                        break;
                    case AsyncMessageType.MessageType.MESSAGE:
                        handleOnMessage(clientMessage);
                        break;
                    case AsyncMessageType.MessageType.PEER_REMOVED:
                        break;
                    case AsyncMessageType.MessageType.PING:
                        handleOnPing(websocket, clientMessage);
                        break;
                    case AsyncMessageType.MessageType.SERVER_REGISTER:
                        handleOnServerRegister(textMessage);
                        break;
                }
            }

            @Override
            public void onTextMessage(WebSocket websocket, byte[] data) throws Exception {

            }

            @Override
            public void onBinaryMessage(WebSocket websocket, byte[] binary) throws Exception {

            }

            @Override
            public void onSendingFrame(WebSocket websocket, WebSocketFrame frame) throws Exception {

            }

            @Override
            public void onFrameSent(WebSocket websocket, WebSocketFrame frame) throws Exception {

            }

            @Override
            public void onFrameUnsent(WebSocket websocket, WebSocketFrame frame) throws Exception {

            }

            @Override
            public void onThreadCreated(WebSocket websocket, ThreadType threadType, Thread thread) throws Exception {

            }

            @Override
            public void onThreadStarted(WebSocket websocket, ThreadType threadType, Thread thread) throws Exception {

            }

            @Override
            public void onThreadStopping(WebSocket websocket, ThreadType threadType, Thread thread) throws Exception {

            }

            @Override
            public void onError(WebSocket websocket, WebSocketException cause) throws Exception {
                captureError("On WebSocket Error", cause);
                asyncListenerManager.callOnError(cause.toString());
            }

            @Override
            public void onFrameError(WebSocket websocket, WebSocketException cause, WebSocketFrame frame) throws Exception {

            }

            /**
             * After error event its start reconnecting again.
             * Note that you should not trigger reconnection in onError() method because onError()
             * may be called multiple times due to one error.
             * Instead, onDisconnected() is the right place to trigger reconnection.
             */

            @Override
            public void onMessageError(WebSocket websocket, WebSocketException cause, List<WebSocketFrame> frames) throws Exception {
                captureError("On Message Error", cause);
            }

            @Override
            public void onMessageDecompressionError(WebSocket websocket, WebSocketException cause, byte[] compressed) throws Exception {

            }

            @Override
            public void onTextMessageError(WebSocket websocket, WebSocketException cause, byte[] data) throws Exception {

            }

            @Override
            public void onSendError(WebSocket websocket, WebSocketException cause, WebSocketFrame frame) throws Exception {

            }

            @Override
            public void onUnexpectedError(WebSocket websocket, WebSocketException cause) throws Exception {

            }

            @Override
            public void handleCallbackError(WebSocket websocket, Throwable cause) throws Exception {

            }

            @Override
            public void onSendingHandshake(WebSocket websocket, String requestLine, List<String[]> headers) throws Exception {

            }
        });
    }

    private void captureMessage(String message) {


        Breadcrumb c = new Breadcrumb();
        c.setCategory("INFO");
        c.setData("DATA", message);
        c.setLevel(SentryLevel.INFO);
        c.setMessage(message);
        c.setType("INFO LOG");
        if (Sentry.isEnabled())
            Sentry.addBreadcrumb(c, "NORMAL_INFO_WITHOUT_DATA");

        showLog(message);
    }

    private void captureMessage(String info, String data) {


        Breadcrumb c = new Breadcrumb();
        c.setCategory("INFO");
        c.setData("DATA", data);
        c.setLevel(SentryLevel.INFO);
        c.setMessage(info);
        c.setType("DATA LOG");
        if (Sentry.isEnabled())
            Sentry.addBreadcrumb(c, "NORMAL_INFO");


        showLog(info);
        showRawInfoLog(data);
    }


    private void captureError(String hint, Exception cause) {


        SentryEvent event = new SentryEvent(cause);
        event.setEnvironment("PODASYNC");
        event.setLevel(SentryLevel.ERROR);
        event.setTag("FROM_SDK", "PODASYNC");
        event.setExtra("FROM_SDK", "PODASYNC");
        if (Sentry.isEnabled())
            Sentry.captureEvent(event, hint);

        showErrorLog(hint);
        showErrorLog(cause.getMessage());

    }

    private void captureError(String hint, String cause) {

        SentryEvent event = new SentryEvent(new Exception(cause));
        event.setEnvironment("PODASYNC");
        event.setLevel(SentryLevel.ERROR);
        event.setTag("FROM_SDK", "PODASYNC");
        event.setExtra("FROM_SDK", "PODASYNC");
        if (Sentry.isEnabled())
            Sentry.captureEvent(event, hint);


        showErrorLog(hint);
        showErrorLog(cause);


    }

    private void showErrorLog(String cause) {
        if (log) Log.e(TAG, cause);
    }

    private void showRawInfoLog(String info) {
        if (rawLog) Log.d(TAG, info);
    }

    private void showLog(String info) {
        if (log) Log.i(TAG, info);
    }


    /*
     * Its showed
     * */
    public void rawLog(boolean rawLog) {
        this.rawLog = rawLog;
    }

    private void retryReconnect() {
        runOnUiThreadRecconect(new Runnable() {
            @Override
            public void run() {
                try {
                    reConnect();
                } catch (WebSocketException e) {
                    asyncListenerManager.callOnError(e.getMessage());
                }
                if (log)
                    captureMessage("Retry Connect", "Async: reConnect in " + " retryStep " + retryStep + " s ");

            }
        }, retryStep * 1000);
        if (retryStep < 60) retryStep *= 2;
    }

    public void isLoggable(boolean log) {
        this.log = log;
    }

    public void connect(String socketServerAddress, final String appId, String serverName,
                        String token, String ssoHost, String deviceID) {

        try {


            WebSocketFactory webSocketFactory = new WebSocketFactory();
            SSLSocketFactory.getDefault();
            setAppId(appId);
            setServerAddress(socketServerAddress);
            setToken(token);
            setServerName(serverName);
            setSsoHost(ssoHost);

            String sName = Uri.parse(socketServerAddress).getHost();

            webSocketFactory.setServerName(sName);

            webSocket = webSocketFactory
                    .createSocket(socketServerAddress);

            fillInitialSentry(socketServerAddress, appId, serverName, token, ssoHost, deviceID);


            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {

                Socket socket = webSocket.getSocket();

                showRawInfoLog("Enabling SNI for " + sName);

                try {
                    Method method = socket.getClass().getMethod("setHostname", String.class);
                    method.invoke(socket, sName);
                } catch (Exception e) {
                    captureError("Set SNI", e);
                }
            }


            onEvent(webSocket);

            webSocket.setMaxPayloadSize(100);
            webSocket.addExtension(WebSocketExtension.PERMESSAGE_DEFLATE);
            webSocket.connectAsynchronously();

            if (deviceID != null && !deviceID.isEmpty()) {
                setDeviceID(deviceID);
            }

        } catch (IOException e) {
            captureError("IOConnect", e);
        } catch (Exception e) {
            captureError("Connect", e);
        }
    }


    public void connect() {

        try {

            WebSocketFactory webSocketFactory = new WebSocketFactory();

            String sName = Uri.parse(serverAddress).getHost();

            webSocketFactory.setServerName(sName);

            webSocket = webSocketFactory
                    .createSocket(serverAddress);


            Socket socket = webSocket.getSocket();

            showRawInfoLog("Enabling SNI for " + sName);

            try {
                Method method = null;
                if (socket != null) {
                    method = socket.getClass().getMethod("setHostname", String.class);
                    method.invoke(socket, sName);
                }
            } catch (Exception e) {
                captureError("Set SNI", e);
            }

            onEvent(webSocket);

            webSocket.setMaxPayloadSize(100);
            webSocket.addExtension(WebSocketExtension.PERMESSAGE_DEFLATE);
            webSocket.connectAsynchronously();

            if (deviceID != null && !deviceID.isEmpty()) {
                setDeviceID(deviceID);
            }

        } catch (IOException e) {
            captureError("IOConnect", e);
        } catch (Exception e) {
            captureError("Connect", e);
        }
    }

    private void fillInitialSentry(String socketServerAddress, String appId, String serverName, String token, String ssoHost, String deviceID) {

        if (Sentry.isEnabled()) {
            Sentry.setExtra("appId", appId);
            Sentry.setExtra("socketServerAddress", socketServerAddress);
            Sentry.setExtra("serverName", serverName);
            Sentry.setExtra("token", token);
            Sentry.setExtra("ssoHost", ssoHost);
            Sentry.setExtra("deviceID", deviceID);
        }
    }

    /**
     * @Param textContent
     * @Param messageType it could be 3, 4, 5
     * @Param []receiversId the Id's that we want to send
     */
    public void sendMessage(String textContent, int messageType, long[] receiversId) {
        try {
            Message message = new Message();
            message.setContent(textContent);
            message.setReceivers(receiversId);

            String jsonMessage = gson.toJson(message);
            String wrapperJsonString = getMessageWrapper(jsonMessage, messageType);
            sendData(webSocket, wrapperJsonString);
        } catch (Exception e) {
            asyncListenerManager.callOnError(e.getCause().getMessage());
            captureError("Send Message", e);
        }
    }

    /**
     * First we checking the state of the socket then we send the message
     */
    public void sendMessage(String textContent, int messageType) {
        try {
            long ttl = new Date().getTime();
            Message message = new Message();
            message.setContent(textContent);
            message.setPriority(1);
            message.setPeerName(getServerName());
            message.setTtl(ttl);

            String json = gson.toJson(message);

            messageWrapperVo = new MessageWrapperVo();
            messageWrapperVo.setContent(json);
            messageWrapperVo.setType(messageType);

            String json1 = gson.toJson(messageWrapperVo);
            sendData(webSocket, json1);

        } catch (Exception e) {
            asyncListenerManager.callOnError(e.getCause().getMessage());
            captureError("Send Message", e);
        }

    }


    public void closeSocket() {
        captureMessage("Send Close Socket");
        webSocket.sendClose();

    }

    public void logOut() {
        captureMessage("Request log out");
        removePeerId(AsyncConstant.Constants.PEER_ID, null);
        isServerRegister = false;
        isDeviceRegister = false;
        webSocket.sendClose();
    }

    public void setReconnectOnClose(boolean reconnectOnClosed) {
        reconnectOnClose = reconnectOnClosed;
    }

    /**
     * Add a listener to receive events on this Async.
     *
     * @param listener A listener to add.
     * @return {@code this} object.
     */
    public Async addListener(AsyncListener listener) {
        asyncListenerManager.addListener(listener, log);
        return this;
    }

    public Async setListener(AsyncListener listener) {
        asyncListenerManager.clearListeners();
        asyncListenerManager.addListener(listener, log);
        return this;
    }

    public Async addListeners(List<AsyncListener> listeners) {
        asyncListenerManager.addListeners(listeners);
        return this;
    }

    public Async removeListener(AsyncListener listener) {
        asyncListenerManager.removeListener(listener);
        return this;
    }

    public List<AsyncListener> getSynchronizedListeners() {
        return asyncListenerManager.getSynchronizedListeners();
    }

    public Async clearListeners() {
        asyncListenerManager.clearListeners();
        return this;
    }

    /**
     * Connect webSocket to the Async
     *
     * @Param socketServerAddress
     * @Param appId
     */
    private void handleOnAck(ClientMessage clientMessage) throws IOException {
        setMessage(clientMessage.getContent());
        asyncListenerManager.callOnTextMessage(clientMessage.getContent());
    }

    /**
     * When socket closes by any reason
     * , server is still registered and we sent a lot of message but
     * they are still in the queue
     */
    private void handleOnDeviceRegister(WebSocket websocket, ClientMessage clientMessage) {
        try {
            isDeviceRegister = true;
            String newPeerId = clientMessage.getContent();
            addSentryExtra("PEER_ID", clientMessage.getContent());
            if (!peerIdExistence()) {
                savePeerId(newPeerId);
                captureMessage("Peer id doesn't exist");

            }

            if (newPeerId.equals(peerId)) {

                captureMessage("PEER ids are equal");
                captureMessage("SERVER_ALREADY_REGISTERED");
                captureMessage("ASYNC_IS_READY");

                callAsyncReady();

            } else {
                savePeerId(newPeerId);
                serverRegister(websocket);
            }
        } catch (Exception e) {
            captureError("Handle On Device Register", e);
        }

    }

    private void serverRegister(WebSocket websocket) {
        if (websocket != null) {
            try {
                captureMessage("SEND_SERVER_REGISTER");
                RegistrationRequest registrationRequest = new RegistrationRequest();
                registrationRequest.setName(getServerName());

                String jsonRegistrationRequestVo = gson.toJson(registrationRequest);
                String jsonMessageWrapperVo = getMessageWrapper(jsonRegistrationRequestVo, AsyncMessageType.MessageType.SERVER_REGISTER);
                sendData(websocket, jsonMessageWrapperVo);
            } catch (Exception e) {
                captureError("SEND_SERVER_REGISTER", e);
            }
        } else {
            captureError("SEND_SERVER_REGISTER", "WebSocket Is Null");
        }
    }

    private void sendData(WebSocket websocket, String jsonMessageWrapperVo) {

        try {
            lastSentMessageTime = new Date().getTime();
            if (jsonMessageWrapperVo != null) {
                if (getState().equals("OPEN")) {
                    if (websocket != null) {
                        websocket.sendText(jsonMessageWrapperVo);
                        captureMessage("SEND DATA", jsonMessageWrapperVo);
                    } else {
                        captureError("SEND DATA", "webSocket instance is Null");
                    }
                } else {
                    asyncListenerManager.callOnError("Socket is close");
                    queueMessages(jsonMessageWrapperVo);
                    captureError("SEND DATA", "Socket is close");
                    captureMessage("Add to queue", jsonMessageWrapperVo);
                }

            } else {
                captureError("SEND DATA", "message is Null");
            }
            ping();
        } catch (Exception e) {
            captureError("SEND DATA", e);
        }

    }

    //TODO
    private void sendData(String jsonMessageWrapperVo) {

        try {
            lastSentMessageTime = new Date().getTime();
            if (jsonMessageWrapperVo != null) {
                if (getState().equals("OPEN")) {
                    if (webSocket != null) {
                        webSocket.sendText(jsonMessageWrapperVo);
                        captureMessage("SEND DATA", jsonMessageWrapperVo);
                    } else {
                        captureError("SEND DATA", "webSocket instance is Null");
                    }
                } else {
                    asyncListenerManager.callOnError("Socket is close");
                    queueMessages(jsonMessageWrapperVo);
                    captureError("SEND DATA", "Socket is close");
                    captureMessage("Add to queue", jsonMessageWrapperVo);
                }

            } else {
                captureError("SEND DATA", "message is Null");
            }
            ping();
        } catch (Exception e) {
            captureError("SEND DATA", e);
        }

    }

    private void sendDataWithoutQueue(String jsonMessageWrapperVo) {

        try {
            lastSentMessageTime = new Date().getTime();
            if (jsonMessageWrapperVo != null) {
                if (getState().equals("OPEN")) {
                    if (webSocket != null) {
                        webSocket.sendText(jsonMessageWrapperVo);
                        captureMessage("SEND DATA", jsonMessageWrapperVo);
                    } else {
                        captureError("SEND DATA", "webSocket instance is Null");
                    }
                } else {
                    asyncListenerManager.callOnError("Socket is close");
                    captureError("SEND DATA", "Socket is close");
                    captureMessage("Add to queue", jsonMessageWrapperVo);
                }

            } else {
                captureError("SEND DATA", "message is Null");
            }
            ping();
        } catch (Exception e) {
            captureError("SEND DATA", e);
        }

    }

    private void queueMessages(String jsonMessageWrapperVo) {
        asyncQueue.add(jsonMessageWrapperVo);
    }

    private void handleOnErrorMessage(ClientMessage clientMessage) {
        captureError("Handle On Error", clientMessage.getContent());
        setErrorMessage(clientMessage.getContent());
    }

    private void handleOnMessage(ClientMessage clientMessage) {
        if (clientMessage != null) {
            try {
                setMessage(clientMessage.getContent());
                asyncListenerManager.callOnTextMessage(clientMessage.getContent());
            } catch (Exception e) {
                captureError("Handle On Message", e);
            }
        } else {
            captureError("Handle On Message", "ClientMessage Is Null");
        }
    }

    private void handleOnPing(WebSocket webSocket, ClientMessage clientMessage) {
        try {
            if (clientMessage.getContent() != null) {
                if (getDeviceId() == null || getDeviceId().isEmpty()) {
                    addSentryExtra("DEVICE_ID", clientMessage.getContent());
                    setDeviceID(clientMessage.getContent());
                }
                deviceRegister(webSocket);
            } else {
                captureMessage("ASYNC_PING_RECEIVED");
            }
        } catch (Exception e) {
            captureError("Handle On Ping", e);
        }
    }

    private void addSentryExtra(String key, String value) {
        Sentry.setExtra(key, value);
    }

    /*
     * After registered on the server its sent messages from queue
     * */
    private void handleOnServerRegister(String textMessage) {
        try {
            captureMessage("SERVER_REGISTERED");
            captureMessage("ASYNC_IS_READY");
            captureMessage(textMessage);

            callAsyncReady();
        } catch (Exception e) {
            captureError("Handle On Server Register", e);
        }
    }

    private void callAsyncReady() {
        isServerRegister = true;
        asyncListenerManager.callOnStateChanged("ASYNC_READY");
        dequeueMessages();
    }

    private void dequeueMessages() {
        //todo send with delay and clear list
        captureMessage("Check async queue with size: " + asyncQueue.size());
        for (String message : asyncQueue) {
            sendData(webSocket, message);
        }
    }

    private void enableServerName() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
            connect();
        }
    }


    public boolean isServerRegister() {
        return isServerRegister;
    }

    private void handleOnMessageAckNeeded(WebSocket websocket, ClientMessage clientMessage) {

        try {
            if (websocket != null) {
                handleOnMessage(clientMessage);

                Message messageSenderAckNeeded = new Message();
                messageSenderAckNeeded.setMessageId(clientMessage.getId());

                String jsonSenderAckNeeded = gson.toJson(messageSenderAckNeeded);
                String jsonSenderAckNeededWrapper = getMessageWrapper(jsonSenderAckNeeded, AsyncMessageType.MessageType.ACK);
                sendData(websocket, jsonSenderAckNeededWrapper);
            } else {
                captureError("Handle On Message need ACK", "WebSocket Is Null ");
            }
        } catch (Exception e) {
            captureError("Handle On Message need ACK", e);
        }
    }

    private void deviceRegister(WebSocket websocket) {
        try {
            if (websocket != null) {
                PeerInfo peerInfo = new PeerInfo();
                if (getPeerId() != null) {
                    peerInfo.setRefresh(true);
                } else {
                    peerInfo.setRenew(true);
                }
                peerInfo.setAppId(getAppId());
                peerInfo.setDeviceId(getDeviceId());

//                JsonAdapter<PeerInfo> jsonPeerMessageAdapter = moshi.adapter(PeerInfo.class);
                String peerMessageJson = gson.toJson(peerInfo);
                String jsonPeerInfoWrapper = getMessageWrapper(peerMessageJson, AsyncMessageType.MessageType.DEVICE_REGISTER);
                sendData(websocket, jsonPeerInfoWrapper);
                captureMessage("SEND_DEVICE_REGISTER", jsonPeerInfoWrapper);

            } else {
                captureError("SEND_DEVICE_REGISTER", "WebSocket Is Null ");
            }

        } catch (Exception e) {
            captureError("SEND_DEVICE_REGISTER", e);
        }
    }

    @NonNull
    private String getMessageWrapper(String json, int messageType) {
        messageWrapperVo = new MessageWrapperVo();
        messageWrapperVo.setContent(json);
        messageWrapperVo.setType(messageType);

        return gson.toJson(messageWrapperVo);
    }

    /**
     * If peerIdExistence we set {@param refresh = true} to the
     * Async else we set {@param renew = true}  to the Async to
     * get the new PeerId
     */
    private void reConnect() throws WebSocketException {
        connect(getServerAddress(), getAppId(), getServerName(), getToken(), getSsoHost(), null);
    }

    private void scheduleRec() throws WebSocketException {

         ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

          scheduler.scheduleAtFixedRate(() -> {

//                if (!state.isNetworkAvailable()) {
//                    return;
//                }

              if (!"OPEN".equals(getState())) try {
                  webSocket = webSocket.recreate();
                  webSocket.connect();
              } catch (Exception e) {

              }
          }, 0, 5, TimeUnit.SECONDS);

    }

    /**
     * Remove the peerId and send ping again but this time
     * peerId that was set in the server was removed
     */

    private void removePeerId(String peerId, String nul) {
        SharedPreferences.Editor editor = sharedPrefs.edit();
        editor.putString(peerId, nul);
        editor.apply();
    }

    private void ping() {
        runOnUIThread(new Runnable() {
            @Override
            public void run() {
                long currentTime = new Date().getTime();
                if (currentTime - lastSentMessageTime >= connectionCheckTimeout - JSTimeLatency) {
                    if (!getState().equals("CLOSING") || !getState().equals("CLOSED")) {
                        message = getMessageWrapper("", AsyncMessageType.MessageType.PING);
                        try {
                            sendData(webSocket, message);
                        } catch (Exception e) {
                            captureError("SEND PING", e);
                        }
                        captureMessage("SEND_ASYNC_PING", message);
                    } else {
                        captureError("SEND PING", "SOCKET IS CLOSED");
                    }
                }
            }
        }, connectionCheckTimeout);
    }

    static {
        reconnectHandler = new Handler(Looper.getMainLooper());
    }

    protected static void runOnUiThreadRecconect(Runnable runnable, long delayedTime) {
        if (reconnectHandler != null) {
            reconnectHandler.postDelayed(runnable, delayedTime);
        } else {
            runnable.run();
        }
    }


    static {
        pingHandler = new Handler(Looper.getMainLooper());
    }

    protected static void runOnUIThread(Runnable runnable, long delayedTime) {
        if (pingHandler != null) {
            pingHandler.postDelayed(runnable, delayedTime);
        } else {
            runnable.run();
        }
    }

    static {
        socketCloseHandler = new Handler(Looper.getMainLooper());
    }

    protected static void runOnUIThreadCloseSocket(Runnable runnable, long delayedTime) {
        if (socketCloseHandler != null) {
            socketCloseHandler.postDelayed(runnable, delayedTime);
        } else {
            runnable.run();
        }
    }

    private void ScheduleCloseSocket() {
        runOnUIThreadCloseSocket(new Runnable() {
            @Override
            public void run() {
                if (lastSentMessageTime - lastReceiveMessageTime >= SOCKET_CLOSE_TIMEOUT) {
                    closeSocket();
                }
            }
        }, SOCKET_CLOSE_TIMEOUT);
    }

    /**
     * After a delay Time it calls the method in the Run
     */
    private void scheduleCloseSocket() {
        currentTime = new Date().getTime();
        socketCloseHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currentTime - lastReceiveMessageTime > 10000) {
                    closeSocket();
                }
            }
        }, 10000);
    }

    /**
     * When its send message the lastSentMessageTime gets updated.
     * if the {@param currentTime} - {@param lastSentMessageTime} was bigger than 10 second
     * it means we need to send ping to keep socket alive.
     * we don't need to set ping interval because its send ping automatically by itself
     * with the {@param type}type that not 0.
     * We set {@param type = 0} with empty content.
     */

    public void stopSocket() {
        try {
            if (webSocket != null) {
                isServerRegister = false;
                webSocket.disconnect();
                webSocket = null;
                pingHandler.removeCallbacksAndMessages(null);
                captureMessage("Socket Stopped");
            }
        } catch (Exception e) {
            captureError("STOP SOCKET", e);
        }
    }

    /**
     * Checking if the peerId exist or not. if user logout Peer id is set to null
     */
    private boolean peerIdExistence() {
        boolean isPeerIdExistence;
        String peerId = sharedPrefs.getString(AsyncConstant.Constants.PEER_ID, null);
        setPeerId(peerId);
        isPeerIdExistence = peerId != null;
        return isPeerIdExistence;
    }

    /**
     * Save peerId in the SharedPreferences
     */
    private void savePeerId(String peerId) {
        captureMessage("Saving new peer id: " + peerId);

        SharedPreferences.Editor editor = sharedPrefs.edit();
        editor.putString(AsyncConstant.Constants.PEER_ID, peerId);
        editor.apply();
    }


    //Save deviceId in the SharedPreferences
    private static void saveDeviceId(String deviceId) {
        SharedPreferences.Editor editor = sharedPrefs.edit();
        editor.putString(AsyncConstant.Constants.DEVICE_ID, deviceId);
        editor.apply();
    }

    private void setServerName(String serverName) {
        this.serverName = serverName;
    }

    private String getServerName() {
        return serverName;
    }

    private String getDeviceId() {
//        return sharedPrefs.getString(AsyncConstant.Constants.DEVICE_ID, null);
        return deviceID;
    }

    public void setDeviceID(String deviceID) {
        this.deviceID = deviceID;
    }

    public String getPeerId() {
        return sharedPrefs.getString(AsyncConstant.Constants.PEER_ID, null);
    }

    private void setPeerId(String peerId) {
        this.peerId = peerId;
    }

    private String getAppId() {
        return appId;
    }

    private void setAppId(String appId) {
        this.appId = appId;
    }

    public String getState() {
        return state;
    }

    private void setState(String state) {
        this.state = state;
    }

    private void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    private void setMessage(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }

    private String getServerAddress() {
        return serverAddress;
    }

    private void setServerAddress(String serverAddress) {
        this.serverAddress = serverAddress;
    }

    private void setToken(String token) {
        this.token = token;
    }

    private String getToken() {
        return token;
    }

    /**
     * Get the manager that manages registered listeners.
     */
    AsyncListenerManager getListenerManager() {
        return asyncListenerManager;
    }

    private void setSsoHost(String ssoHost) {
        this.ssoHost = ssoHost;
    }

    private String getSsoHost() {
        return ssoHost;
    }
}

