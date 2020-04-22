/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.quickstep;

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_UP;

import static com.android.launcher3.config.FeatureFlags.ENABLE_QUICKSTEP_LIVE_TILE;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;
import static com.android.quickstep.GestureState.DEFAULT_STATE;
import static com.android.quickstep.util.RecentsOrientedState.isFixedRotationTransformEnabled;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_INPUT_MONITOR;
import static com.android.systemui.shared.system.QuickStepContract.KEY_EXTRA_SYSUI_PROXY;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_TRACING_ENABLED;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Region;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Choreographer;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.BinderThread;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.WorkerThread;

import com.android.launcher3.BaseDraggingActivity;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.config.FeatureFlags;
import com.android.launcher3.logging.UserEventDispatcher;
import com.android.launcher3.model.AppLaunchTracker;
import com.android.launcher3.provider.RestoreDbTask;
import com.android.launcher3.testing.TestLogging;
import com.android.launcher3.testing.TestProtocol;
import com.android.launcher3.tracing.nano.LauncherTraceProto;
import com.android.launcher3.tracing.nano.TouchInteractionServiceProto;
import com.android.launcher3.uioverrides.plugins.PluginManagerWrapper;
import com.android.launcher3.util.OnboardingPrefs;
import com.android.launcher3.util.TraceHelper;
import com.android.quickstep.inputconsumers.AccessibilityInputConsumer;
import com.android.quickstep.inputconsumers.AssistantInputConsumer;
import com.android.quickstep.inputconsumers.DeviceLockedInputConsumer;
import com.android.quickstep.inputconsumers.OtherActivityInputConsumer;
import com.android.quickstep.inputconsumers.OverscrollInputConsumer;
import com.android.quickstep.inputconsumers.OverviewInputConsumer;
import com.android.quickstep.inputconsumers.OverviewWithoutFocusInputConsumer;
import com.android.quickstep.inputconsumers.ResetGestureInputConsumer;
import com.android.quickstep.inputconsumers.ScreenPinnedInputConsumer;
import com.android.quickstep.util.ActiveGestureLog;
import com.android.quickstep.util.AssistantUtilities;
import com.android.quickstep.util.ProtoTracer;
import com.android.quickstep.views.RecentsView;
import com.android.systemui.plugins.OverscrollPlugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.shared.recents.ISystemUiProxy;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.InputChannelCompat.InputEventReceiver;
import com.android.systemui.shared.system.InputConsumerController;
import com.android.systemui.shared.system.InputMonitorCompat;
import com.android.systemui.shared.tracing.ProtoTraceable;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Wrapper around a list for processing arguments.
 */
class ArgList extends LinkedList<String> {
    public ArgList(List<String> l) {
        super(l);
    }

    public String peekArg() {
        return peekFirst();
    }

    public String nextArg() {
        return pollFirst().toLowerCase();
    }
}

/**
 * Service connected by system-UI for handling touch interaction.
 */
@TargetApi(Build.VERSION_CODES.Q)
public class TouchInteractionService extends Service implements PluginListener<OverscrollPlugin>,
        ProtoTraceable<LauncherTraceProto> {

    private static final String TAG = "TouchInteractionService";

    private static final String KEY_BACK_NOTIFICATION_COUNT = "backNotificationCount";
    private static final String NOTIFY_ACTION_BACK = "com.android.quickstep.action.BACK_GESTURE";
    private static final String HAS_ENABLED_QUICKSTEP_ONCE = "launcher.has_enabled_quickstep_once";
    private static final int MAX_BACK_NOTIFICATION_COUNT = 3;

    /**
     * System Action ID to show all apps.  This ID should follow the ones in
     * com.android.systemui.accessibility.SystemActions.
     */
    private static final int SYSTEM_ACTION_ID_ALL_APPS = 100;

    private int mBackGestureNotificationCounter = -1;
    @Nullable
    private OverscrollPlugin mOverscrollPlugin;

    private final IBinder mMyBinder = new IOverviewProxy.Stub() {

        @BinderThread
        public void onInitialize(Bundle bundle) {
            ISystemUiProxy proxy = ISystemUiProxy.Stub.asInterface(
                    bundle.getBinder(KEY_EXTRA_SYSUI_PROXY));
            MAIN_EXECUTOR.execute(() -> {
                SystemUiProxy.INSTANCE.get(TouchInteractionService.this).setProxy(proxy);
                TouchInteractionService.this.initInputMonitor();
                preloadOverview(true /* fromInit */);
            });
            sIsInitialized = true;
        }

        @BinderThread
        @Override
        public void onOverviewToggle() {
            TestLogging.recordEvent(TestProtocol.SEQUENCE_MAIN, "onOverviewToggle");
            mOverviewCommandHelper.onOverviewToggle();
        }

        @BinderThread
        @Override
        public void onOverviewShown(boolean triggeredFromAltTab) {
            mOverviewCommandHelper.onOverviewShown(triggeredFromAltTab);
        }

        @BinderThread
        @Override
        public void onOverviewHidden(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
            if (triggeredFromAltTab && !triggeredFromHomeKey) {
                // onOverviewShownFromAltTab hides the overview and ends at the target app
                mOverviewCommandHelper.onOverviewHidden();
            }
        }

        @BinderThread
        @Override
        public void onTip(int actionType, int viewType) {
            mOverviewCommandHelper.onTip(actionType, viewType);
        }

        @BinderThread
        @Override
        public void onAssistantAvailable(boolean available) {
            MAIN_EXECUTOR.execute(() -> {
                mDeviceState.setAssistantAvailable(available);
                TouchInteractionService.this.onAssistantVisibilityChanged();
            });
        }

        @BinderThread
        @Override
        public void onAssistantVisibilityChanged(float visibility) {
            MAIN_EXECUTOR.execute(() -> {
                mDeviceState.setAssistantVisibility(visibility);
                TouchInteractionService.this.onAssistantVisibilityChanged();
            });
        }

        @BinderThread
        public void onBackAction(boolean completed, int downX, int downY, boolean isButton,
                boolean gestureSwipeLeft) {
            if (mOverviewComponentObserver == null) {
                return;
            }

            final BaseActivityInterface activityInterface =
                    mOverviewComponentObserver.getActivityInterface();
            UserEventDispatcher.newInstance(getBaseContext()).logActionBack(completed, downX, downY,
                    isButton, gestureSwipeLeft, activityInterface.getContainerType());

            if (completed && !isButton && shouldNotifyBackGesture()) {
                UI_HELPER_EXECUTOR.execute(TouchInteractionService.this::tryNotifyBackGesture);
            }
        }

        @BinderThread
        public void onSystemUiStateChanged(int stateFlags) {
            MAIN_EXECUTOR.execute(() -> {
                mDeviceState.setSystemUiFlags(stateFlags);
                TouchInteractionService.this.onSystemUiFlagsChanged();
            });
        }

        @BinderThread
        public void onActiveNavBarRegionChanges(Region region) {
            MAIN_EXECUTOR.execute(() -> mDeviceState.setDeferredGestureRegion(region));
        }

        /** Deprecated methods **/
        public void onQuickStep(MotionEvent motionEvent) { }

        public void onQuickScrubEnd() { }

        public void onQuickScrubProgress(float progress) { }

        public void onQuickScrubStart() { }

        public void onPreMotionEvent(int downHitTarget) { }

        public void onMotionEvent(MotionEvent ev) {
            ev.recycle();
        }

        public void onBind(ISystemUiProxy iSystemUiProxy) { }
    };

    private static boolean sConnected = false;
    private static boolean sIsInitialized = false;

    public static boolean isConnected() {
        return sConnected;
    }

    public static boolean isInitialized() {
        return sIsInitialized;
    }

    private final BaseSwipeUpHandler.Factory mLauncherSwipeHandlerFactory =
            this::createLauncherSwipeHandler;
    private final BaseSwipeUpHandler.Factory mFallbackSwipeHandlerFactory =
            this::createFallbackSwipeHandler;

    private ActivityManagerWrapper mAM;
    private OverviewCommandHelper mOverviewCommandHelper;
    private OverviewComponentObserver mOverviewComponentObserver;
    private InputConsumerController mInputConsumer;
    private RecentsAnimationDeviceState mDeviceState;
    private TaskAnimationManager mTaskAnimationManager;

    private InputConsumer mUncheckedConsumer = InputConsumer.NO_OP;
    private InputConsumer mConsumer = InputConsumer.NO_OP;
    private Choreographer mMainChoreographer;
    private InputConsumer mResetGestureInputConsumer;
    private GestureState mGestureState = DEFAULT_STATE;

    private InputMonitorCompat mInputMonitorCompat;
    private InputEventReceiver mInputEventReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        // Initialize anything here that is needed in direct boot mode.
        // Everything else should be initialized in onUserUnlocked() below.
        mMainChoreographer = Choreographer.getInstance();
        mAM = ActivityManagerWrapper.getInstance();
        mDeviceState = new RecentsAnimationDeviceState(this);
        mDeviceState.addNavigationModeChangedCallback(this::onNavigationModeChanged);
        mDeviceState.runOnUserUnlocked(this::onUserUnlocked);
        ProtoTracer.INSTANCE.get(this).add(this);

        sConnected = true;
    }

    private void disposeEventHandlers() {
        if (mInputEventReceiver != null) {
            mInputEventReceiver.dispose();
            mInputEventReceiver = null;
        }
        if (mInputMonitorCompat != null) {
            mInputMonitorCompat.dispose();
            mInputMonitorCompat = null;
        }
    }

    private void initInputMonitor() {
        disposeEventHandlers();
        if (mDeviceState.isButtonNavMode() || !SystemUiProxy.INSTANCE.get(this).isActive()) {
            return;
        }

        Bundle bundle = SystemUiProxy.INSTANCE.get(this).monitorGestureInput("swipe-up",
                mDeviceState.getDisplayId());
        mInputMonitorCompat = InputMonitorCompat.fromBundle(bundle, KEY_EXTRA_INPUT_MONITOR);
        mInputEventReceiver = mInputMonitorCompat.getInputReceiver(Looper.getMainLooper(),
                mMainChoreographer, this::onInputEvent);

        mDeviceState.updateGestureTouchRegions();
    }

    /**
     * Called when the navigation mode changes, guaranteed to be after the device state has updated.
     */
    private void onNavigationModeChanged(SysUINavigationMode.Mode mode) {
        initInputMonitor();
        resetHomeBounceSeenOnQuickstepEnabledFirstTime();
    }

    @UiThread
    public void onUserUnlocked() {
        mTaskAnimationManager = new TaskAnimationManager();
        mOverviewComponentObserver = new OverviewComponentObserver(this, mDeviceState);
        mOverviewCommandHelper = new OverviewCommandHelper(this, mDeviceState,
                mOverviewComponentObserver);
        mResetGestureInputConsumer = new ResetGestureInputConsumer(mTaskAnimationManager);
        mInputConsumer = InputConsumerController.getRecentsAnimationInputConsumer();
        mInputConsumer.registerInputConsumer();
        onSystemUiFlagsChanged();
        onAssistantVisibilityChanged();

        // Temporarily disable model preload
        // new ModelPreload().start(this);
        mBackGestureNotificationCounter = Math.max(0, Utilities.getDevicePrefs(this)
                .getInt(KEY_BACK_NOTIFICATION_COUNT, MAX_BACK_NOTIFICATION_COUNT));
        resetHomeBounceSeenOnQuickstepEnabledFirstTime();

        PluginManagerWrapper.INSTANCE.get(getBaseContext()).addPluginListener(this,
                OverscrollPlugin.class, false /* allowMultiple */);

        mOverviewComponentObserver.setOverviewChangeListener(this::onOverviewTargetChange);
        onOverviewTargetChange(mOverviewComponentObserver.isHomeAndOverviewSame());
    }

    private void resetHomeBounceSeenOnQuickstepEnabledFirstTime() {
        if (!mDeviceState.isUserUnlocked() || mDeviceState.isButtonNavMode()) {
            // Skip if not yet unlocked (can't read user shared prefs) or if the current navigation
            // mode doesn't have gestures
            return;
        }

        // Reset home bounce seen on quick step enabled for first time
        SharedPreferences sharedPrefs = Utilities.getPrefs(this);
        if (!sharedPrefs.getBoolean(HAS_ENABLED_QUICKSTEP_ONCE, true)) {
            sharedPrefs.edit()
                    .putBoolean(HAS_ENABLED_QUICKSTEP_ONCE, true)
                    .putBoolean(OnboardingPrefs.HOME_BOUNCE_SEEN, false)
                    .apply();
        }
    }

    private void onOverviewTargetChange(boolean isHomeAndOverviewSame) {
        AccessibilityManager am = getSystemService(AccessibilityManager.class);

        if (isHomeAndOverviewSame) {
            Intent intent = new Intent(mOverviewComponentObserver.getHomeIntent())
                    .setAction(Intent.ACTION_ALL_APPS);
            RemoteAction allAppsAction = new RemoteAction(
                    Icon.createWithResource(this, R.drawable.ic_apps),
                    getString(R.string.all_apps_label),
                    getString(R.string.all_apps_label),
                    PendingIntent.getActivity(this, SYSTEM_ACTION_ID_ALL_APPS, intent,
                            PendingIntent.FLAG_UPDATE_CURRENT));
            am.registerSystemAction(allAppsAction, SYSTEM_ACTION_ID_ALL_APPS);
        } else {
            am.unregisterSystemAction(SYSTEM_ACTION_ID_ALL_APPS);
        }
    }

    @UiThread
    private void onSystemUiFlagsChanged() {
        if (mDeviceState.isUserUnlocked()) {
            SystemUiProxy.INSTANCE.get(this).setLastSystemUiStateFlags(
                    mDeviceState.getSystemUiStateFlags());
            mOverviewComponentObserver.onSystemUiStateChanged();

            // Update the tracing state
            if ((mDeviceState.getSystemUiStateFlags() & SYSUI_STATE_TRACING_ENABLED) != 0) {
                ProtoTracer.INSTANCE.get(TouchInteractionService.this).start();
            } else {
                ProtoTracer.INSTANCE.get(TouchInteractionService.this).stop();
            }
        }
    }

    @UiThread
    private void onAssistantVisibilityChanged() {
        if (mDeviceState.isUserUnlocked()) {
            mOverviewComponentObserver.getActivityInterface().onAssistantVisibilityChanged(
                    mDeviceState.getAssistantVisibility());
        }
    }

    @Override
    public void onDestroy() {
        sIsInitialized = false;
        if (mDeviceState.isUserUnlocked()) {
            mInputConsumer.unregisterInputConsumer();
            mOverviewComponentObserver.onDestroy();
            PluginManagerWrapper.INSTANCE.get(getBaseContext()).removePluginListener(this);
        }
        disposeEventHandlers();
        mDeviceState.destroy();
        SystemUiProxy.INSTANCE.get(this).setProxy(null);
        ProtoTracer.INSTANCE.get(TouchInteractionService.this).stop();
        ProtoTracer.INSTANCE.get(this).remove(this);

        getSystemService(AccessibilityManager.class)
                .unregisterSystemAction(SYSTEM_ACTION_ID_ALL_APPS);

        sConnected = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "Touch service connected");
        return mMyBinder;
    }

    private void onInputEvent(InputEvent ev) {
        if (!(ev instanceof MotionEvent)) {
            Log.e(TAG, "Unknown event " + ev);
            return;
        }
        MotionEvent event = (MotionEvent) ev;

        TestLogging.recordMotionEvent(
                TestProtocol.SEQUENCE_TIS, "TouchInteractionService.onInputEvent", event);

        if (!mDeviceState.isUserUnlocked()) {
            return;
        }

        Object traceToken = TraceHelper.INSTANCE.beginFlagsOverride(
                TraceHelper.FLAG_ALLOW_BINDER_TRACKING);

        final int action = event.getAction();
        if (action == ACTION_DOWN) {
            mDeviceState.setOrientationTransformIfNeeded(event);
            GestureState newGestureState;

            if (mDeviceState.isInSwipeUpTouchRegion(event)) {
                // Clone the previous gesture state since onConsumerAboutToBeSwitched might trigger
                // onConsumerInactive and wipe the previous gesture state
                GestureState prevGestureState = new GestureState(mGestureState);
                newGestureState = createGestureState();
                mConsumer.onConsumerAboutToBeSwitched();
                mConsumer = newConsumer(prevGestureState, newGestureState, event);

                ActiveGestureLog.INSTANCE.addLog("setInputConsumer: " + mConsumer.getName());
                mUncheckedConsumer = mConsumer;
            } else if (mDeviceState.isUserUnlocked()
                    && mDeviceState.isFullyGesturalNavMode()
                    && mDeviceState.canTriggerAssistantAction(event)) {
                newGestureState = createGestureState();
                // Do not change mConsumer as if there is an ongoing QuickSwitch gesture, we should
                // not interrupt it. QuickSwitch assumes that interruption can only happen if the
                // next gesture is also quick switch.
                mUncheckedConsumer = new AssistantInputConsumer(
                    this,
                    newGestureState,
                    InputConsumer.NO_OP, mInputMonitorCompat,
                    mOverviewComponentObserver.assistantGestureIsConstrained());
            } else {
                newGestureState = DEFAULT_STATE;
                mUncheckedConsumer = InputConsumer.NO_OP;
            }

            // Save the current gesture state
            mGestureState = newGestureState;
        } else {
            // Other events
            if (mUncheckedConsumer != InputConsumer.NO_OP) {
                // Only transform the event if we are handling it in a proper consumer
                mDeviceState.setOrientationTransformIfNeeded(event);
            }
        }

        if (mUncheckedConsumer != InputConsumer.NO_OP) {
            ActiveGestureLog.INSTANCE.addLog("onMotionEvent", event.getActionMasked());
        }

        boolean cleanUpConsumer = (action == ACTION_UP || action == ACTION_CANCEL)
                && mConsumer != null
                && !mConsumer.getActiveConsumerInHierarchy().isConsumerDetachedFromGesture();
        mUncheckedConsumer.onMotionEvent(event);

        if (cleanUpConsumer) {
            reset();
        }
        TraceHelper.INSTANCE.endFlagsOverride(traceToken);
    }

    private GestureState createGestureState() {
        GestureState gestureState = new GestureState(mOverviewComponentObserver,
                ActiveGestureLog.INSTANCE.generateAndSetLogId());
        gestureState.updateRunningTask(TraceHelper.whitelistIpcs("getRunningTask.0",
                () -> mAM.getRunningTask(false /* filterOnlyVisibleRecents */)));
        return gestureState;
    }

    private InputConsumer newConsumer(GestureState previousGestureState,
            GestureState newGestureState, MotionEvent event) {
        boolean canStartSystemGesture = mDeviceState.canStartSystemGesture();

        if (!mDeviceState.isUserUnlocked()) {
            Log.d(TAG, "User locked. Can start system gesture? " + canStartSystemGesture
                + " sysUiFlags: " + mDeviceState.getSystemUiStateFlags());
            if (canStartSystemGesture) {
                // This handles apps launched in direct boot mode (e.g. dialer) as well as apps
                // launched while device is locked even after exiting direct boot mode (e.g. camera).
                return createDeviceLockedInputConsumer(newGestureState);
            } else {
                return mResetGestureInputConsumer;
            }
        }

        // When there is an existing recents animation running, bypass systemState check as this is
        // a followup gesture and the first gesture started in a valid system state.
        InputConsumer base = canStartSystemGesture
                || previousGestureState.isRecentsAnimationRunning()
                        ? newBaseConsumer(previousGestureState, newGestureState, event)
                        : mResetGestureInputConsumer;
        // TODO(b/149880412): 2 button landscape mode is wrecked. Fixit!
        if (mDeviceState.isGesturalNavMode()) {
            handleOrientationSetup(base);
        }
        if (mDeviceState.isFullyGesturalNavMode()) {
            if (mDeviceState.canTriggerAssistantAction(event)) {
                base = new AssistantInputConsumer(
                    this,
                    newGestureState,
                    base,
                    mInputMonitorCompat,
                    mOverviewComponentObserver.assistantGestureIsConstrained());
            }

            if (FeatureFlags.ENABLE_QUICK_CAPTURE_GESTURE.get()) {
                OverscrollPlugin plugin = null;
                if (FeatureFlags.FORCE_LOCAL_OVERSCROLL_PLUGIN.get()) {
                    TaskOverlayFactory factory =
                            TaskOverlayFactory.INSTANCE.get(getApplicationContext());
                    plugin = factory.getLocalOverscrollPlugin();  // may be null
                }

                // If not local plugin was forced, use the actual overscroll plugin if available.
                if (plugin == null && mOverscrollPlugin != null && mOverscrollPlugin.isActive()) {
                    plugin = mOverscrollPlugin;
                }

                if (plugin != null) {
                    // Put the overscroll gesture as higher priority than the Assistant or base
                    // gestures
                    base = new OverscrollInputConsumer(this, newGestureState, base,
                        mInputMonitorCompat, plugin);
                }
            }

            if (mDeviceState.isScreenPinningActive()) {
                // Note: we only allow accessibility to wrap this, and it replaces the previous
                // base input consumer (which should be NO_OP anyway since topTaskLocked == true).
                base = new ScreenPinnedInputConsumer(this, newGestureState);
            }

            if (mDeviceState.isAccessibilityMenuAvailable()) {
                base = new AccessibilityInputConsumer(this, mDeviceState, base,
                        mInputMonitorCompat);
            }
        } else {
            if (mDeviceState.isScreenPinningActive()) {
                base = mResetGestureInputConsumer;
            }
        }
        return base;
    }

    private void handleOrientationSetup(InputConsumer baseInputConsumer) {
        if (!isFixedRotationTransformEnabled(this)) {
            return;
        }
        mDeviceState.enableMultipleRegions(baseInputConsumer instanceof OtherActivityInputConsumer);
        BaseDraggingActivity activity =
                mOverviewComponentObserver.getActivityInterface().getCreatedActivity();
        if (activity == null || !(activity.getOverviewPanel() instanceof RecentsView)) {
            return;
        }
        ((RecentsView) activity.getOverviewPanel())
            .setLayoutRotation(mDeviceState.getCurrentActiveRotation(),
                mDeviceState.getDisplayRotation());
        activity.getDragLayer().recreateControllers();
    }

    private InputConsumer newBaseConsumer(GestureState previousGestureState,
            GestureState gestureState, MotionEvent event) {
        if (mDeviceState.isKeyguardShowingOccluded()) {
            // This handles apps showing over the lockscreen (e.g. camera)
            return createDeviceLockedInputConsumer(gestureState);
        }

        boolean forceOverviewInputConsumer = false;
        if (AssistantUtilities.isExcludedAssistant(gestureState.getRunningTask())) {
            // In the case where we are in the excluded assistant state, ignore it and treat the
            // running activity as the task behind the assistant
            gestureState.updateRunningTask(TraceHelper.whitelistIpcs("getRunningTask.assistant",
                    () -> mAM.getRunningTask(true /* filterOnlyVisibleRecents */)));
            ComponentName homeComponent = mOverviewComponentObserver.getHomeIntent().getComponent();
            ComponentName runningComponent =
                    gestureState.getRunningTask().baseIntent.getComponent();
            forceOverviewInputConsumer =
                    runningComponent != null && runningComponent.equals(homeComponent);
        }

        if (previousGestureState.getFinishingRecentsAnimationTaskId() > 0) {
            // If the finish animation was interrupted, then continue using the other activity input
            // consumer but with the next task as the running task
            RunningTaskInfo info = new ActivityManager.RunningTaskInfo();
            info.id = previousGestureState.getFinishingRecentsAnimationTaskId();
            gestureState.updateRunningTask(info);
            return createOtherActivityInputConsumer(previousGestureState, gestureState, event);
        } else if (gestureState.getRunningTask() == null) {
            return mResetGestureInputConsumer;
        } else if (previousGestureState.isRunningAnimationToLauncher()
                || gestureState.getActivityInterface().isResumed()
                || forceOverviewInputConsumer) {
            return createOverviewInputConsumer(
                    previousGestureState, gestureState, event, forceOverviewInputConsumer);
        } else if (ENABLE_QUICKSTEP_LIVE_TILE.get()
                && gestureState.getActivityInterface().isInLiveTileMode()) {
            return createOverviewInputConsumer(
                    previousGestureState, gestureState, event, forceOverviewInputConsumer);
        } else if (mDeviceState.isGestureBlockedActivity(gestureState.getRunningTask())) {
            return mResetGestureInputConsumer;
        } else {
            return createOtherActivityInputConsumer(previousGestureState, gestureState, event);
        }
    }

    private InputConsumer createOtherActivityInputConsumer(GestureState previousGestureState,
            GestureState gestureState, MotionEvent event) {

        final boolean shouldDefer;
        final BaseSwipeUpHandler.Factory factory;

        if (!mOverviewComponentObserver.isHomeAndOverviewSame()) {
            shouldDefer = previousGestureState.getFinishingRecentsAnimationTaskId() < 0;
            factory = mFallbackSwipeHandlerFactory;
        } else {
            shouldDefer = gestureState.getActivityInterface().deferStartingActivity(mDeviceState,
                    event);
            factory = mLauncherSwipeHandlerFactory;
        }

        final boolean disableHorizontalSwipe = mDeviceState.isInExclusionRegion(event);
        return new OtherActivityInputConsumer(this, mDeviceState, mTaskAnimationManager,
                gestureState, shouldDefer, this::onConsumerInactive,
                mInputMonitorCompat, disableHorizontalSwipe, factory);
    }

    private InputConsumer createDeviceLockedInputConsumer(GestureState gestureState) {
        if (mDeviceState.isFullyGesturalNavMode() && gestureState.getRunningTask() != null) {
            return new DeviceLockedInputConsumer(this, mDeviceState, mTaskAnimationManager,
                    gestureState, mInputMonitorCompat);
        } else {
            return mResetGestureInputConsumer;
        }
    }

    public InputConsumer createOverviewInputConsumer(GestureState previousGestureState,
            GestureState gestureState, MotionEvent event,
            boolean forceOverviewInputConsumer) {
        BaseDraggingActivity activity = gestureState.getActivityInterface().getCreatedActivity();
        if (activity == null) {
            return mResetGestureInputConsumer;
        }

        if (activity.getRootView().hasWindowFocus()
                || previousGestureState.isRunningAnimationToLauncher()
                || (FeatureFlags.ASSISTANT_GIVES_LAUNCHER_FOCUS.get()
                    && forceOverviewInputConsumer)) {
            return new OverviewInputConsumer(gestureState, activity, mInputMonitorCompat,
                    false /* startingInActivityBounds */);
        } else {
            final boolean disableHorizontalSwipe = mDeviceState.isInExclusionRegion(event);
            return new OverviewWithoutFocusInputConsumer(activity, mDeviceState, gestureState,
                    mInputMonitorCompat, disableHorizontalSwipe);
        }
    }

    /**
     * To be called by the consumer when it's no longer active. This can be called by any consumer
     * in the hierarchy at any point during the gesture (ie. if a delegate consumer starts
     * intercepting touches, the base consumer can try to call this).
     */
    private void onConsumerInactive(InputConsumer caller) {
        if (mConsumer != null && mConsumer.getActiveConsumerInHierarchy() == caller) {
            reset();
        }
    }

    private void reset() {
        mConsumer = mUncheckedConsumer = mResetGestureInputConsumer;
        mGestureState = new GestureState();
    }

    private void preloadOverview(boolean fromInit) {
        if (!mDeviceState.isUserUnlocked()) {
            return;
        }
        if (mDeviceState.isButtonNavMode() && !mOverviewComponentObserver.isHomeAndOverviewSame()) {
            // Prevent the overview from being started before the real home on first boot.
            return;
        }

        if (RestoreDbTask.isPending(this)) {
            // Preloading while a restore is pending may cause launcher to start the restore
            // too early.
            return;
        }

        final BaseActivityInterface<BaseDraggingActivity> activityInterface =
                mOverviewComponentObserver.getActivityInterface();
        final Intent overviewIntent = new Intent(
                mOverviewComponentObserver.getOverviewIntentIgnoreSysUiState());
        if (activityInterface.getCreatedActivity() == null) {
            // Make sure that UI states will be initialized.
            activityInterface.createActivityInitListener((wasVisible) -> {
                AppLaunchTracker.INSTANCE.get(TouchInteractionService.this);
                return false;
            }).register(overviewIntent);
        } else if (fromInit) {
            // The activity has been created before the initialization of overview service. It is
            // usually happens when booting or launcher is the top activity, so we should already
            // have the latest state.
            return;
        }

        mTaskAnimationManager.preloadRecentsAnimation(overviewIntent);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (!mDeviceState.isUserUnlocked()) {
            return;
        }
        final BaseActivityInterface activityInterface =
                mOverviewComponentObserver.getActivityInterface();
        final BaseDraggingActivity activity = activityInterface.getCreatedActivity();
        if (activity == null || activity.isStarted()) {
            // We only care about the existing background activity.
            return;
        }
        if (mOverviewComponentObserver.canHandleConfigChanges(activity.getComponentName(),
                activity.getResources().getConfiguration().diff(newConfig))) {
            return;
        }

        preloadOverview(false /* fromInit */);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] rawArgs) {
        if (rawArgs.length > 0 && Utilities.IS_DEBUG_DEVICE) {
            ArgList args = new ArgList(Arrays.asList(rawArgs));
            switch (args.nextArg()) {
                case "cmd":
                    if (args.peekArg() == null) {
                        printAvailableCommands(pw);
                    } else {
                        onCommand(pw, args);
                    }
                    break;
            }
        } else {
            // Dump everything
            FeatureFlags.dump(pw);
            if (mDeviceState.isUserUnlocked()) {
                PluginManagerWrapper.INSTANCE.get(getBaseContext()).dump(pw);
            }
            mDeviceState.dump(pw);
            if (mOverviewComponentObserver != null) {
                mOverviewComponentObserver.dump(pw);
            }
            if (mGestureState != null) {
                mGestureState.dump(pw);
            }
            SysUINavigationMode.INSTANCE.get(this).dump(pw);
            pw.println("TouchState:");
            BaseDraggingActivity createdOverviewActivity = mOverviewComponentObserver == null ? null
                    : mOverviewComponentObserver.getActivityInterface().getCreatedActivity();
            boolean resumed = mOverviewComponentObserver != null
                    && mOverviewComponentObserver.getActivityInterface().isResumed();
            pw.println("  createdOverviewActivity=" + createdOverviewActivity);
            pw.println("  resumed=" + resumed);
            pw.println("  mConsumer=" + mConsumer.getName());
            ActiveGestureLog.INSTANCE.dump("", pw);
            pw.println("ProtoTrace:");
            pw.println("  file="
                    + ProtoTracer.INSTANCE.get(TouchInteractionService.this).getTraceFile());
        }
    }

    private void printAvailableCommands(PrintWriter pw) {
        pw.println("Available commands:");
        pw.println("  clear-touch-log: Clears the touch interaction log");
    }

    private void onCommand(PrintWriter pw, ArgList args) {
        switch (args.nextArg()) {
            case "clear-touch-log":
                ActiveGestureLog.INSTANCE.clear();
                break;
        }
    }

    private BaseSwipeUpHandler createLauncherSwipeHandler(GestureState gestureState,
            long touchTimeMs, boolean continuingLastGesture, boolean isLikelyToStartNewTask) {
        return new LauncherSwipeHandler(this, mDeviceState, mTaskAnimationManager,
                gestureState, touchTimeMs, continuingLastGesture, mInputConsumer);
    }

    private BaseSwipeUpHandler createFallbackSwipeHandler(GestureState gestureState,
            long touchTimeMs, boolean continuingLastGesture, boolean isLikelyToStartNewTask) {
        return new FallbackSwipeHandler(this, mDeviceState, gestureState,
                mInputConsumer, isLikelyToStartNewTask, continuingLastGesture);
    }

    protected boolean shouldNotifyBackGesture() {
        return mBackGestureNotificationCounter > 0 &&
                !mDeviceState.getGestureBlockedActivityPackages().isEmpty();
    }

    @WorkerThread
    protected void tryNotifyBackGesture() {
        if (shouldNotifyBackGesture()) {
            mBackGestureNotificationCounter--;
            Utilities.getDevicePrefs(this).edit()
                    .putInt(KEY_BACK_NOTIFICATION_COUNT, mBackGestureNotificationCounter).apply();
            mDeviceState.getGestureBlockedActivityPackages().forEach(blockedPackage ->
                    sendBroadcast(new Intent(NOTIFY_ACTION_BACK).setPackage(blockedPackage)));
        }
    }

    @Override
    public void onPluginConnected(OverscrollPlugin overscrollPlugin, Context context) {
        mOverscrollPlugin = overscrollPlugin;
    }

    @Override
    public void onPluginDisconnected(OverscrollPlugin overscrollPlugin) {
        mOverscrollPlugin = null;
    }

    @Override
    public void writeToProto(LauncherTraceProto proto) {
        if (proto.touchInteractionService == null) {
            proto.touchInteractionService = new TouchInteractionServiceProto();
        }
        proto.touchInteractionService.serviceConnected = true;
        proto.touchInteractionService.serviceConnected = true;
    }
}
