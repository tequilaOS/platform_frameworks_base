/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.net;

import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.VpnManager.NOTIFICATION_CHANNEL_VPN;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.net.VpnConfig;
import com.android.internal.net.VpnProfile;
import com.android.server.connectivity.Vpn;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class LockdownVpnTrackerTest {
    private static final NetworkCapabilities TEST_CELL_NC = new NetworkCapabilities.Builder()
            .addTransportType(TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED)
            .build();
    private static final LinkProperties TEST_CELL_LP = new LinkProperties();

    static {
        TEST_CELL_LP.setInterfaceName("rmnet0");
        TEST_CELL_LP.addLinkAddress(new LinkAddress("192.0.2.2/25"));
    }

    // Use a context wrapper instead of a mock since LockdownVpnTracker builds notifications which
    // is tedious and currently unnecessary to mock.
    private final Context mContext = new ContextWrapper(InstrumentationRegistry.getContext()) {
        @Override
        public Object getSystemService(String name) {
            if (Context.CONNECTIVITY_SERVICE.equals(name)) return mCm;
            if (Context.NOTIFICATION_SERVICE.equals(name)) return mNotificationManager;

            return super.getSystemService(name);
        }
    };
    @Mock private ConnectivityManager mCm;
    @Mock private Vpn mVpn;
    @Mock private NotificationManager mNotificationManager;
    @Mock private NetworkInfo mVpnNetworkInfo;
    @Mock private VpnConfig mVpnConfig;
    @Mock private Network mNetwork;

    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private VpnProfile mProfile;

    private VpnProfile createTestVpnProfile() {
        final String profileName = "testVpnProfile";
        final VpnProfile profile = new VpnProfile(profileName);
        profile.name = "My VPN";
        profile.server = "192.0.2.1";
        profile.dnsServers = "8.8.8.8";
        profile.ipsecIdentifier = "My ipsecIdentifier";
        profile.ipsecSecret = "My PSK";
        profile.type = VpnProfile.TYPE_IKEV2_IPSEC_PSK;

        return profile;
    }

    private NetworkCallback getDefaultNetworkCallback() {
        final ArgumentCaptor<NetworkCallback> callbackCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        verify(mCm).registerSystemDefaultNetworkCallback(callbackCaptor.capture(), eq(mHandler));
        return callbackCaptor.getValue();
    }

    private NetworkCallback getVpnNetworkCallback() {
        final ArgumentCaptor<NetworkCallback> callbackCaptor =
                ArgumentCaptor.forClass(NetworkCallback.class);
        verify(mCm).registerNetworkCallback(any(), callbackCaptor.capture(), eq(mHandler));
        return callbackCaptor.getValue();
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mHandlerThread = new HandlerThread("LockdownVpnTrackerTest");
        mHandlerThread.start();
        mHandler = mHandlerThread.getThreadHandler();

        doReturn(mVpnNetworkInfo).when(mVpn).getNetworkInfo();
        doReturn(false).when(mVpnNetworkInfo).isConnectedOrConnecting();
        doReturn(mVpnConfig).when(mVpn).getLegacyVpnConfig();

        mProfile = createTestVpnProfile();
    }

    @After
    public void tearDown() throws Exception {
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            mHandlerThread.join();
        }
    }

    private LockdownVpnTracker initAndVerifyLockdownVpnTracker() {
        final LockdownVpnTracker lockdownVpnTracker =
                new LockdownVpnTracker(mContext, mHandler, mVpn, mProfile);
        lockdownVpnTracker.init();
        verify(mVpn).setEnableTeardown(false);
        verify(mVpn).setLockdown(true);
        verify(mCm).setLegacyLockdownVpnEnabled(true);
        verify(mVpn).stopVpnRunnerPrivileged();
        verify(mNotificationManager).cancel(any(), eq(SystemMessage.NOTE_VPN_STATUS));

        return lockdownVpnTracker;
    }

    private void callCallbacksForNetworkConnect(NetworkCallback callback, Network network,
            NetworkCapabilities nc, LinkProperties lp, boolean blocked) {
        callback.onAvailable(network);
        callback.onCapabilitiesChanged(network, nc);
        callback.onLinkPropertiesChanged(network, lp);
        callback.onBlockedStatusChanged(network, blocked);
    }

    private void callCallbacksForNetworkConnect(NetworkCallback callback, Network network) {
        callCallbacksForNetworkConnect(
                callback, network, TEST_CELL_NC, TEST_CELL_LP, true /* blocked */);
    }

    private boolean isExpectedNotification(Notification notification, int titleRes, int iconRes) {
        if (!NOTIFICATION_CHANNEL_VPN.equals(notification.getChannelId())) {
            return false;
        }
        final CharSequence expectedTitle = mContext.getString(titleRes);
        final CharSequence actualTitle = notification.extras.getCharSequence(
                Notification.EXTRA_TITLE);
        if (!TextUtils.equals(expectedTitle, actualTitle)) {
            return false;
        }
        return notification.getSmallIcon().getResId() == iconRes;
    }

    @Test
    public void testShutdown() {
        final LockdownVpnTracker lockdownVpnTracker = initAndVerifyLockdownVpnTracker();
        final NetworkCallback defaultCallback = getDefaultNetworkCallback();
        final NetworkCallback vpnCallback = getVpnNetworkCallback();
        clearInvocations(mVpn, mCm, mNotificationManager);

        lockdownVpnTracker.shutdown();
        verify(mVpn).stopVpnRunnerPrivileged();
        verify(mVpn).setLockdown(false);
        verify(mCm).setLegacyLockdownVpnEnabled(false);
        verify(mNotificationManager).cancel(any(), eq(SystemMessage.NOTE_VPN_STATUS));
        verify(mVpn).setEnableTeardown(true);
        verify(mCm).unregisterNetworkCallback(defaultCallback);
        verify(mCm).unregisterNetworkCallback(vpnCallback);
    }

    @Test
    public void testDefaultNetworkConnected() {
        initAndVerifyLockdownVpnTracker();
        final NetworkCallback defaultCallback = getDefaultNetworkCallback();
        clearInvocations(mVpn, mCm, mNotificationManager);

        // mNetwork connected and available.
        callCallbacksForNetworkConnect(defaultCallback, mNetwork);

        // Vpn is starting
        verify(mVpn).startLegacyVpnPrivileged(mProfile, mNetwork, TEST_CELL_LP);
        verify(mNotificationManager).notify(any(), eq(SystemMessage.NOTE_VPN_STATUS),
                argThat(notification -> isExpectedNotification(notification,
                        R.string.vpn_lockdown_connecting, R.drawable.vpn_disconnected)));
    }

    @Test
    public void testSystemDefaultLost() {
        initAndVerifyLockdownVpnTracker();
        final NetworkCallback defaultCallback = getDefaultNetworkCallback();
        // mNetwork connected
        callCallbacksForNetworkConnect(defaultCallback, mNetwork);
        clearInvocations(mVpn, mCm, mNotificationManager);

        defaultCallback.onLost(mNetwork);

        // Vpn is stopped
        verify(mVpn).stopVpnRunnerPrivileged();
        verify(mNotificationManager).cancel(any(), eq(SystemMessage.NOTE_VPN_STATUS));
    }
}
