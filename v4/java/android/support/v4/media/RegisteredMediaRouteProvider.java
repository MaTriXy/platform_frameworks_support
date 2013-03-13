/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support.v4.media;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.IBinder.DeathRecipient;
import android.os.Message;
import android.os.Messenger;
import android.support.v4.media.MediaRouter.ControlRequestCallback;
import android.util.Log;
import android.util.SparseArray;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Maintains a connection to a particular media route provider service.
 */
final class RegisteredMediaRouteProvider extends MediaRouteProvider
        implements ServiceConnection {
    private static final String TAG = "RegisteredMediaRouteProvider";
    private static final boolean DEBUG = true;

    private final ComponentName mComponentName;
    private final PrivateHandler mPrivateHandler;
    private final ArrayList<Controller> mControllers = new ArrayList<Controller>();

    private boolean mBound;
    private Connection mActiveConnection;
    private boolean mConnectionReady;

    public RegisteredMediaRouteProvider(Context context, ComponentName componentName) {
        super(context, new ProviderMetadata(componentName.getPackageName()));

        mComponentName = componentName;
        mPrivateHandler = new PrivateHandler();
    }

    @Override
    public RouteController onCreateRouteController(String routeId) {
        ProviderDescriptor descriptor = getDescriptor();
        if (descriptor != null) {
            RouteDescriptor[] routes = descriptor.getRoutes();
            for (int i = 0; i < routes.length; i++) {
                if (routes[i].getId().equals(routeId)) {
                    Controller controller = new Controller(routeId);
                    mControllers.add(controller);
                    if (mConnectionReady) {
                        controller.attachConnection(mActiveConnection);
                    }
                    return controller;
                }
            }
        }
        return null;
    }

    public boolean hasComponentName(String packageName, String className) {
        return mComponentName.getPackageName().equals(packageName)
                && mComponentName.getClassName().equals(className);
    }

    public void bind() {
        if (DEBUG) {
            Log.d(TAG, this + ": Binding");
        }

        Intent service = new Intent(MediaRouteProviderService.SERVICE_INTERFACE);
        service.setComponent(mComponentName);
        try {
            // TODO: Should this use BIND_ALLOW_OOM_MANAGEMENT?
            mBound = getContext().bindService(service, this, Context.BIND_AUTO_CREATE);
            if (!mBound && DEBUG) {
                Log.d(TAG, this + ": Bind failed");
            }
        } catch (SecurityException ex) {
            if (DEBUG) {
                Log.d(TAG, this + ": Bind failed", ex);
            }
        }
    }

    public void unbind() {
        if (DEBUG) {
            Log.d(TAG, this + ": Unbinding");
        }

        disconnect();
        if (mBound) {
            mBound = false;
            getContext().unbindService(this);
        }
    }

    public void rebindIfDisconnected() {
        if (mActiveConnection == null) {
            unbind();
            bind();
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        if (DEBUG) {
            Log.d(TAG, this + ": Connected");
        }

        if (mBound) {
            disconnect();

            Messenger messenger = (service != null ? new Messenger(service) : null);
            if (MediaRouteProviderService.isValidRemoteMessenger(messenger)) {
                Connection connection = new Connection(messenger);
                if (connection.register()) {
                    mActiveConnection = connection;
                } else {
                    if (DEBUG) {
                        Log.d(TAG, this + ": Registration failed");
                    }
                }
            } else {
                Log.e(TAG, this + ": Service returned invalid messenger binder");
            }
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        if (DEBUG) {
            Log.d(TAG, this + ": Service disconnected");
        }
        disconnect();
    }

    private void onConnectionReady(Connection connection) {
        if (mActiveConnection == connection) {
            mConnectionReady = true;
            attachControllersToConnection();
        }
    }

    private void onConnectionDied(Connection connection) {
        if (mActiveConnection == connection) {
            if (DEBUG) {
                Log.d(TAG, this + ": Service connection died");
            }
            disconnect();
        }
    }

    private void onConnectionError(Connection connection, String error) {
        if (mActiveConnection == connection) {
            if (DEBUG) {
                Log.d(TAG, this + ": Service connection error - " + error);
            }
            unbind();
        }
    }

    private void onConnectionDescriptorChanged(Connection connection,
            ProviderDescriptor descriptor) {
        if (mActiveConnection == connection) {
            if (DEBUG) {
                Log.d(TAG, this + ": Descriptor changed, descriptor=" + descriptor);
            }
            setDescriptor(descriptor);
        }
    }

    private void disconnect() {
        if (mActiveConnection != null) {
            setDescriptor(null);
            mConnectionReady = false;
            detachControllersFromConnection();
            mActiveConnection.dispose();
            mActiveConnection = null;
        }
    }

    private void onControllerReleased(Controller controller) {
        mControllers.remove(controller);
        controller.detachConnection();
    }

    private void attachControllersToConnection() {
        int count = mControllers.size();
        for (int i = 0; i < count; i++) {
            mControllers.get(i).attachConnection(mActiveConnection);
        }
    }

    private void detachControllersFromConnection() {
        int count = mControllers.size();
        for (int i = 0; i < count; i++) {
            mControllers.get(i).detachConnection();
        }
    }

    @Override
    public String toString() {
        return "Service connection " + mComponentName.flattenToShortString();
    }

    private final class Controller extends RouteController {
        private final String mRouteId;

        private boolean mSelected;
        private int mPendingSetVolume = -1;
        private int mPendingUpdateVolumeDelta;

        private Connection mConnection;
        private int mControllerId;

        public Controller(String routeId) {
            mRouteId = routeId;
        }

        public void attachConnection(Connection connection) {
            mConnection = connection;
            mControllerId = connection.createRouteController(mRouteId);
            if (mSelected) {
                connection.selectRoute(mControllerId);
                if (mPendingSetVolume >= 0) {
                    connection.setVolume(mControllerId, mPendingSetVolume);
                    mPendingSetVolume = -1;
                }
                if (mPendingUpdateVolumeDelta != 0) {
                    connection.updateVolume(mControllerId, mPendingUpdateVolumeDelta);
                    mPendingUpdateVolumeDelta = 0;
                }
            }
        }

        public void detachConnection() {
            if (mConnection != null) {
                mConnection.releaseRouteController(mControllerId);
                mConnection = null;
                mControllerId = 0;
            }
        }

        @Override
        public void release() {
            onControllerReleased(this);
        }

        @Override
        public void select() {
            mSelected = true;
            if (mConnection != null) {
                mConnection.selectRoute(mControllerId);
            }
        }

        @Override
        public void unselect() {
            mSelected = false;
            if (mConnection != null) {
                mConnection.unselectRoute(mControllerId);
            }
        }

        @Override
        public void setVolume(int volume) {
            if (mConnection != null) {
                mConnection.setVolume(mControllerId, volume);
            } else {
                mPendingSetVolume = volume;
                mPendingUpdateVolumeDelta = 0;
            }
        }

        @Override
        public void updateVolume(int delta) {
            if (mConnection != null) {
                mConnection.updateVolume(mControllerId, delta);
            } else {
                mPendingUpdateVolumeDelta += delta;
            }
        }

        @Override
        public boolean sendControlRequest(Intent intent, ControlRequestCallback callback) {
            if (mConnection != null) {
                return mConnection.sendControlRequest(mControllerId, intent, callback);
            }
            return false;
        }
    }

    private final class Connection implements DeathRecipient {
        private final Messenger mServiceMessenger;
        private final ReceiveHandler mReceiveHandler;
        private final Messenger mReceiveMessenger;

        private int mNextRequestId = 1;
        private int mNextControllerId = 1;
        private int mServiceVersion; // non-zero when registration complete

        private int mPendingRegisterRequestId;
        private final SparseArray<ControlRequestCallback> mPendingCallbacks =
                new SparseArray<ControlRequestCallback>();

        public Connection(Messenger serviceMessenger) {
            mServiceMessenger = serviceMessenger;
            mReceiveHandler = new ReceiveHandler(this);
            mReceiveMessenger = new Messenger(mReceiveHandler);
        }

        public boolean register() {
            mPendingRegisterRequestId = mNextRequestId++;
            if (!sendRequest(MediaRouteProviderService.CLIENT_MSG_REGISTER,
                    mPendingRegisterRequestId,
                    MediaRouteProviderService.CLIENT_VERSION_CURRENT, null, null)) {
                return false;
            }

            try {
                mServiceMessenger.getBinder().linkToDeath(this, 0);
                return true;
            } catch (RemoteException ex) {
                binderDied();
            }
            return false;
        }

        public void dispose() {
            sendRequest(MediaRouteProviderService.CLIENT_MSG_UNREGISTER, 0, 0, null, null);
            mReceiveHandler.dispose();
            mServiceMessenger.getBinder().unlinkToDeath(this, 0);

            mPrivateHandler.post(new Runnable() {
                @Override
                public void run() {
                    failPendingCallbacks();
                }
            });
        }

        private void failPendingCallbacks() {
            int count = 0;
            for (int i = 0; i < mPendingCallbacks.size(); i++) {
                mPendingCallbacks.get(i).onResult(ControlRequestCallback.REQUEST_FAILED, null);
            }
            mPendingCallbacks.clear();
        }

        public boolean onGenericFailure(int requestId) {
            if (requestId == mPendingRegisterRequestId) {
                mPendingRegisterRequestId = 0;
                onConnectionError(this, "Registation failed");
            }
            ControlRequestCallback callback = mPendingCallbacks.get(requestId);
            if (callback != null) {
                mPendingCallbacks.remove(requestId);
                callback.onResult(ControlRequestCallback.REQUEST_FAILED, null);
            }
            return true;
        }

        public boolean onGenericSuccess(int requestId) {
            return true;
        }

        public boolean onRegistered(int requestId, int serviceVersion,
                Bundle descriptorBundle) {
            if (mServiceVersion == 0
                    && requestId == mPendingRegisterRequestId
                    && serviceVersion >= MediaRouteProviderService.SERVICE_VERSION_1) {
                mPendingRegisterRequestId = 0;
                mServiceVersion = serviceVersion;
                onConnectionDescriptorChanged(this,
                        ProviderDescriptor.fromBundle(descriptorBundle));
                onConnectionReady(this);
                return true;
            }
            return false;
        }

        public boolean onDescriptorChanged(Bundle descriptorBundle) {
            if (mServiceVersion != 0) {
                onConnectionDescriptorChanged(this,
                        ProviderDescriptor.fromBundle(descriptorBundle));
                return true;
            }
            return false;
        }

        public boolean onControlRequestResult(int requestId, int resultCode,
                Bundle resultData) {
            ControlRequestCallback callback = mPendingCallbacks.get(requestId);
            if (callback != null) {
                mPendingCallbacks.remove(requestId);
                callback.onResult(resultCode, resultData);
                return true;
            }
            return false;
        }

        @Override
        public void binderDied() {
            mPrivateHandler.post(new Runnable() {
                @Override
                public void run() {
                    onConnectionDied(Connection.this);
                }
            });
        }

        public int createRouteController(String routeId) {
            int controllerId = mNextControllerId++;
            Bundle data = new Bundle();
            data.putString(MediaRouteProviderService.CLIENT_DATA_ROUTE_ID, routeId);
            sendRequest(MediaRouteProviderService.CLIENT_MSG_CREATE_ROUTE_CONTROLLER,
                    mNextRequestId++, controllerId, null, data);
            return controllerId;
        }

        public void releaseRouteController(int controllerId) {
            sendRequest(MediaRouteProviderService.CLIENT_MSG_RELEASE_ROUTE_CONTROLLER,
                    mNextRequestId++, controllerId, null, null);
        }

        public void selectRoute(int controllerId) {
            sendRequest(MediaRouteProviderService.CLIENT_MSG_SELECT_ROUTE,
                    mNextRequestId++, controllerId, null, null);
        }

        public void unselectRoute(int controllerId) {
            sendRequest(MediaRouteProviderService.CLIENT_MSG_UNSELECT_ROUTE,
                    mNextRequestId++, controllerId, null, null);
        }

        public void setVolume(int controllerId, int volume) {
            Bundle data = new Bundle();
            data.putInt(MediaRouteProviderService.CLIENT_DATA_VOLUME, volume);
            sendRequest(MediaRouteProviderService.CLIENT_MSG_SET_ROUTE_VOLUME,
                    mNextRequestId++, controllerId, null, data);
        }

        public void updateVolume(int controllerId, int delta) {
            Bundle data = new Bundle();
            data.putInt(MediaRouteProviderService.CLIENT_DATA_VOLUME, delta);
            sendRequest(MediaRouteProviderService.CLIENT_MSG_UPDATE_ROUTE_VOLUME,
                    mNextRequestId++, controllerId, null, data);
        }

        public boolean sendControlRequest(int controllerId, Intent intent,
                ControlRequestCallback callback) {
            int requestId = mNextRequestId++;
            if (sendRequest(MediaRouteProviderService.CLIENT_MSG_ROUTE_CONTROL_REQUEST,
                    requestId, controllerId, intent, null)) {
                if (callback != null) {
                    mPendingCallbacks.put(requestId, callback);
                }
                return true;
            }
            return false;
        }

        private boolean sendRequest(int what, int requestId, int arg, Object obj, Bundle data) {
            Message msg = Message.obtain();
            msg.what = what;
            msg.arg1 = requestId;
            msg.arg2 = arg;
            msg.obj = obj;
            msg.setData(data);
            msg.replyTo = mReceiveMessenger;
            try {
                mServiceMessenger.send(msg);
                return true;
            } catch (DeadObjectException ex) {
                // The service died.
            } catch (RemoteException ex) {
                if (what != MediaRouteProviderService.CLIENT_MSG_UNREGISTER) {
                    Log.e(TAG, "Could not send message to service.", ex);
                }
            }
            return false;
        }
    }

    private final class PrivateHandler extends Handler {
    }

    /**
     * Handler that receives messages from the server.
     * <p>
     * This inner class is static and only retains a weak reference to the connection
     * to prevent the client from being leaked in case the service is holding an
     * active reference to the client's messenger.
     * </p><p>
     * This handler should not be used to handle any messages other than those
     * that come from the service.
     * </p>
     */
    private static final class ReceiveHandler extends Handler {
        private final WeakReference<Connection> mConnectionRef;

        public ReceiveHandler(Connection connection) {
            mConnectionRef = new WeakReference<Connection>(connection);
        }

        public void dispose() {
            mConnectionRef.clear();
        }

        @Override
        public void handleMessage(Message msg) {
            final int what = msg.what;
            final int requestId = msg.arg1;
            final int arg = msg.arg2;
            final Object obj = msg.obj;
            if (!processMessage(what, requestId, arg, obj)) {
                if (DEBUG) {
                    Log.d(TAG, "Unhandled message from server: " + msg);
                }
            }
        }

        private boolean processMessage(int what, int requestId, int arg, Object obj) {
            Connection connection = mConnectionRef.get();
            if (connection != null) {
                switch (what) {
                    case MediaRouteProviderService.SERVICE_MSG_GENERIC_FAILURE:
                        connection.onGenericFailure(requestId);
                        return true;

                    case MediaRouteProviderService.SERVICE_MSG_GENERIC_SUCCESS:
                        connection.onGenericSuccess(requestId);
                        return true;

                    case MediaRouteProviderService.SERVICE_MSG_REGISTERED:
                        if (obj instanceof Bundle) {
                            return connection.onRegistered(requestId, arg, (Bundle)obj);
                        }
                        break;

                    case MediaRouteProviderService.SERVICE_MSG_DESCRIPTOR_CHANGED:
                        if (obj instanceof Bundle) {
                            return connection.onDescriptorChanged((Bundle)obj);
                        }
                        break;

                    case MediaRouteProviderService.SERVICE_MSG_CONTROL_RESULT:
                        if (obj == null || obj instanceof Bundle) {
                            return connection.onControlRequestResult(
                                    requestId, arg, (Bundle)obj);
                        }
                        break;
                }
            }
            return false;
        }
    }
}
