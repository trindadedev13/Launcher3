/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.launcher3.taskbar;

import static android.window.DesktopModeFlags.ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY;

import static com.android.launcher3.QuickstepTransitionManager.TASKBAR_TO_APP_DURATION;
import static com.android.launcher3.QuickstepTransitionManager.TRANSIENT_TASKBAR_TRANSITION_DURATION;
import static com.android.launcher3.QuickstepTransitionManager.getTaskbarToHomeDuration;
import static com.android.launcher3.statemanager.BaseState.FLAG_NON_INTERACTIVE;
import static com.android.launcher3.taskbar.TaskbarEduTooltipControllerKt.TOOLTIP_STEP_FEATURES;
import static com.android.launcher3.taskbar.TaskbarLauncherStateController.FLAG_VISIBLE;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.window.RemoteTransition;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.Flags;
import com.android.launcher3.Hotseat;
import com.android.launcher3.LauncherState;
import com.android.launcher3.Utilities;
import com.android.launcher3.anim.AnimatedFloat;
import com.android.launcher3.logging.InstanceId;
import com.android.launcher3.logging.InstanceIdSequence;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.taskbar.bubbles.BubbleBarController;
import com.android.launcher3.uioverrides.QuickstepLauncher;
import com.android.launcher3.util.DisplayController;
import com.android.launcher3.util.MultiPropertyFactory;
import com.android.launcher3.util.OnboardingPrefs;
import com.android.quickstep.HomeVisibilityState;
import com.android.quickstep.RecentsAnimationCallbacks;
import com.android.quickstep.SystemUiProxy;
import com.android.quickstep.util.GroupTask;
import com.android.quickstep.views.RecentsView;
import com.android.systemui.shared.system.QuickStepContract.SystemUiStateFlags;
import com.android.wm.shell.shared.bubbles.BubbleBarLocation;
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * A data source which integrates with a Launcher instance
 */
public class LauncherTaskbarUIController extends TaskbarUIController {

    private static final String TAG = "TaskbarUIController";

    public static final int MINUS_ONE_PAGE_PROGRESS_INDEX = 0;
    public static final int ALL_APPS_PAGE_PROGRESS_INDEX = 1;
    public static final int WIDGETS_PAGE_PROGRESS_INDEX = 2;
    public static final int SYSUI_SURFACE_PROGRESS_INDEX = 3;
    public static final int LAUNCHER_PAUSE_PROGRESS_INDEX = 4;

    public static final int DISPLAY_PROGRESS_COUNT = 5;

    private final AnimatedFloat mTaskbarInAppDisplayProgress = new AnimatedFloat(
            this::onInAppDisplayProgressChanged);
    private final MultiPropertyFactory<AnimatedFloat> mTaskbarInAppDisplayProgressMultiProp =
            new MultiPropertyFactory<>(mTaskbarInAppDisplayProgress,
                    AnimatedFloat.VALUE, DISPLAY_PROGRESS_COUNT, Float::max);
    private final AnimatedFloat mLauncherPauseProgress = new AnimatedFloat(
            this::onLauncherPauseProgressUpdate);

    private final QuickstepLauncher mLauncher;
    private final HomeVisibilityState mHomeState;

    private final DeviceProfile.OnDeviceProfileChangeListener mOnDeviceProfileChangeListener =
            dp -> {
                onStashedInAppChanged(dp);
                adjustHotseatForBubbleBar();
                if (mControllers != null && mControllers.taskbarViewController != null) {
                    mControllers.taskbarViewController.onRotationChanged(dp);
                }
            };
    private final HomeVisibilityState.VisibilityChangeListener  mVisibilityChangeListener =
            this::onLauncherVisibilityChanged;

    // Initialized in init.
    private final TaskbarLauncherStateController
            mTaskbarLauncherStateController = new TaskbarLauncherStateController();

    public LauncherTaskbarUIController(QuickstepLauncher launcher) {
        mLauncher = launcher;
        mHomeState =  SystemUiProxy.INSTANCE.get(mLauncher).getHomeVisibilityState();
    }

    @Override
    protected void init(TaskbarControllers taskbarControllers) {
        super.init(taskbarControllers);

        mTaskbarLauncherStateController.init(mControllers, mLauncher,
                mControllers.getSharedState().sysuiStateFlags);

        mLauncher.setTaskbarUIController(this);
        mHomeState.addListener(mVisibilityChangeListener);
        onLauncherVisibilityChanged(
                Flags.useActivityOverlay()
                        ? mHomeState.isHomeVisible() : mLauncher.hasBeenResumed(),
                true /* fromInit */);

        onStashedInAppChanged(mLauncher.getDeviceProfile());
        mLauncher.addOnDeviceProfileChangeListener(mOnDeviceProfileChangeListener);

        // Restore the in-app display progress from before Taskbar was recreated.
        float[] prevProgresses = mControllers.getSharedState().inAppDisplayProgressMultiPropValues;
        // Make a copy of the previous progress to set since updating the multiprop will update
        // the property which also calls onInAppDisplayProgressChanged() which writes the current
        // values into the shared state
        prevProgresses = Arrays.copyOf(prevProgresses, prevProgresses.length);
        for (int i = 0; i < prevProgresses.length; i++) {
            mTaskbarInAppDisplayProgressMultiProp.get(i).setValue(prevProgresses[i]);
        }
    }

    @Override
    protected void onDestroy() {
        onLauncherVisibilityChanged(false /* isVisible */, true /* fromInitOrDestroy */);
        super.onDestroy();
        mTaskbarLauncherStateController.onDestroy();

        mLauncher.setTaskbarUIController(null);
        mLauncher.removeOnDeviceProfileChangeListener(mOnDeviceProfileChangeListener);
        mHomeState.removeListener(mVisibilityChangeListener);
    }

    private void onInAppDisplayProgressChanged() {
        if (mControllers != null) {
            // Update our shared state so we can restore it if taskbar gets recreated.
            for (int i = 0; i < DISPLAY_PROGRESS_COUNT; i++) {
                mControllers.getSharedState().inAppDisplayProgressMultiPropValues[i] =
                        mTaskbarInAppDisplayProgressMultiProp.get(i).getValue();
            }
            // Ensure nav buttons react to our latest state if necessary.
            mControllers.navbarButtonsViewController.updateNavButtonTranslationY();
        }
    }

    @Override
    protected boolean isTaskbarTouchable() {
        // Touching down during animation to Hotseat will end the transition and allow the touch to
        // go through to the Hotseat directly.
        return !isAnimatingToHotseat();
    }

    public void setShouldDelayLauncherStateAnim(boolean shouldDelayLauncherStateAnim) {
        mTaskbarLauncherStateController.setShouldDelayLauncherStateAnim(
                shouldDelayLauncherStateAnim);
    }

    @Override
    public void stashHotseat(boolean stash) {
        mTaskbarLauncherStateController.stashHotseat(stash);
    }

    @Override
    public void unStashHotseatInstantly() {
        mTaskbarLauncherStateController.unStashHotseatInstantly();
    }

    /**
     * Adds the Launcher resume animator to the given animator set.
     *
     * This should be used to run a Launcher resume animation whose progress matches a
     * swipe progress.
     *
     * @param placeholderDuration a placeholder duration to be used to ensure all full-length
     *                            sub-animations are properly coordinated. This duration should not
     *                            actually be used since this animation tracks a swipe progress.
     */
    protected void addLauncherVisibilityChangedAnimation(AnimatorSet animation,
            int placeholderDuration) {
        animation.play(onLauncherVisibilityChanged(
                /* isResumed= */ true,
                /* fromInit= */ false,
                /* startAnimation= */ false,
                placeholderDuration));
    }

    /**
     * Should be called from onResume() and onPause(), and animates the Taskbar accordingly.
     */
    @Override
    public void onLauncherVisibilityChanged(boolean isVisible) {
        if (DesktopModeStatus.enterDesktopByDefaultOnFreeformDisplay(mLauncher)) {
            DisplayController.INSTANCE.get(mLauncher).notifyConfigChange();
        }
        onLauncherVisibilityChanged(isVisible, false /* fromInit */);
    }

    private void onLauncherVisibilityChanged(boolean isVisible, boolean fromInitOrDestroy) {
        onLauncherVisibilityChanged(
                isVisible,
                fromInitOrDestroy,
                /* startAnimation= */ true,
                getTaskbarAnimationDuration(isVisible));
    }

    private int getTaskbarAnimationDuration(boolean isVisible) {
        // fast animation duration since we will not be playing workspace reveal animation.
        boolean shouldOverrideToFastAnimation =
                !isHotseatIconOnTopWhenAligned() || mLauncher.getPredictiveBackToHomeInProgress();
        boolean isPinnedTaskbar = DisplayController.isPinnedTaskbar(mLauncher);
        if (isVisible || isPinnedTaskbar) {
            return getTaskbarToHomeDuration(shouldOverrideToFastAnimation, isPinnedTaskbar);
        } else {
            return DisplayController.isTransientTaskbar(mLauncher)
                    ? TRANSIENT_TASKBAR_TRANSITION_DURATION
                    : TASKBAR_TO_APP_DURATION;
        }
    }

    @Nullable
    private Animator onLauncherVisibilityChanged(
            boolean isVisible, boolean fromInitOrDestroy, boolean startAnimation, int duration) {
        // Launcher is resumed during the swipe-to-overview gesture under shell-transitions, so
        // avoid updating taskbar state in that situation (when it's non-interactive -- or
        // "background") to avoid premature animations.
        LauncherState state = mLauncher.getStateManager().getState();
        boolean nonInteractiveState = state.hasFlag(FLAG_NON_INTERACTIVE)
                && !state.isTaskbarAlignedWithHotseat(mLauncher);
        if (isVisible && (nonInteractiveState || mSkipLauncherVisibilityChange)) {
            return null;
        }

        if (!ENABLE_DESKTOP_WINDOWING_WALLPAPER_ACTIVITY.isTrue()
                && mControllers.taskbarDesktopModeController.getAreDesktopTasksVisible()) {
            // TODO: b/333533253 - Remove after flag rollout
            isVisible = false;
        }

        mTaskbarLauncherStateController.updateStateForFlag(FLAG_VISIBLE, isVisible);
        if (fromInitOrDestroy) {
            duration = 0;
        }
        return mTaskbarLauncherStateController.applyState(duration, startAnimation);
    }

    @Override
    public void onStateTransitionCompletedAfterSwipeToHome(LauncherState state) {
        mTaskbarLauncherStateController.onStateTransitionCompletedAfterSwipeToHome(state);
    }

    @Override
    public void refreshResumedState() {
        onLauncherVisibilityChanged(Flags.useActivityOverlay()
                ? mHomeState.isHomeVisible() : mLauncher.hasBeenResumed());
    }

    @Override
    public void adjustHotseatForBubbleBar(boolean isBubbleBarVisible) {
        if (mLauncher.getHotseat() != null) {
            mLauncher.getHotseat().adjustForBubbleBar(isBubbleBarVisible);
        }
    }

    private void adjustHotseatForBubbleBar() {
        Hotseat hotseat = mLauncher.getHotseat();
        if (mControllers.bubbleControllers.isEmpty() || hotseat == null) return;
        boolean hiddenForBubbles =
                mControllers.bubbleControllers.get().bubbleBarViewController.isHiddenForNoBubbles();
        if (hiddenForBubbles) return;
        hotseat.post(() -> adjustHotseatForBubbleBar(/* isBubbleBarVisible= */ true));
    }

    /**
     * Create Taskbar animation when going from an app to Launcher as part of recents transition.
     * @param toState If known, the state we will end up in when reaching Launcher.
     * @param callbacks callbacks to track the recents animation lifecycle. The state change is
     *                 automatically reset once the recents animation finishes
     */
    public Animator createAnimToLauncher(@NonNull LauncherState toState,
            @NonNull RecentsAnimationCallbacks callbacks, long duration) {
        return mTaskbarLauncherStateController.createAnimToLauncher(toState, callbacks, duration);
    }

    public void updateTaskbarLauncherStateGoingHome() {
        mTaskbarLauncherStateController.updateStateForFlag(FLAG_VISIBLE, true);
        mTaskbarLauncherStateController.applyState();
    }

    public boolean isDraggingItem() {
        boolean bubblesDragging = false;
        if (mControllers.bubbleControllers.isPresent()) {
            bubblesDragging =
                    mControllers.bubbleControllers.get().bubbleDragController.isDragging();
        }
        return mControllers.taskbarDragController.isDragging() || bubblesDragging;
    }

    @Override
    protected void onStashedInAppChanged() {
        onStashedInAppChanged(mLauncher.getDeviceProfile());
    }

    private void onStashedInAppChanged(DeviceProfile deviceProfile) {
        boolean taskbarStashedInApps = mControllers.taskbarStashController.isStashedInApp();
        deviceProfile.isTaskbarPresentInApps = !taskbarStashedInApps;
    }

    /**
     * Starts a Taskbar EDU flow, if the user should see one upon launching an application.
     */
    public void showEduOnAppLaunch() {
        if (!shouldShowEduOnAppLaunch()) {
            // Called in case the edu finishes and search edu is still pending
            mControllers.taskbarEduTooltipController.maybeShowSearchEdu();
            return;
        }

        // Persistent features EDU tooltip.
        if (!DisplayController.isTransientTaskbar(mLauncher)) {
            mControllers.taskbarEduTooltipController.maybeShowFeaturesEdu();
            return;
        }

        // Transient swipe EDU tooltip.
        mControllers.taskbarEduTooltipController.maybeShowSwipeEdu();
    }

    /** Will make the next onRecentsAnimationFinished() animation a no-op. */
    public void setSkipNextRecentsAnimEnd() {
        mTaskbarLauncherStateController.setSkipNextRecentsAnimEnd();
    }

    /**
     * Returns {@code true} if a Taskbar education should be shown on application launch.
     */
    public boolean shouldShowEduOnAppLaunch() {
        if (Utilities.isRunningInTestHarness()) {
            return false;
        }

        // Persistent features EDU tooltip.
        if (!DisplayController.isTransientTaskbar(mLauncher)) {
            return !OnboardingPrefs.TASKBAR_EDU_TOOLTIP_STEP.hasReachedMax(mLauncher);
        }

        // Transient swipe EDU tooltip.
        return mControllers.taskbarEduTooltipController.getTooltipStep() < TOOLTIP_STEP_FEATURES;
    }

    @Override
    public void onTaskbarIconLaunched(ItemInfo item) {
        super.onTaskbarIconLaunched(item);
        InstanceId instanceId = new InstanceIdSequence().newInstanceId();
        mLauncher.logAppLaunch(mControllers.taskbarActivityContext.getStatsLogManager(), item,
                instanceId);
    }

    /**
     * Animates Taskbar elements during a transition to a Launcher state that should use in-app
     * layouts.
     *
     * @param progress [0, 1]
     *                 0 => use home layout
     *                 1 => use in-app layout
     */
    public void onTaskbarInAppDisplayProgressUpdate(float progress, int progressIndex) {
        mTaskbarInAppDisplayProgressMultiProp.get(progressIndex).setValue(progress);
        if (mControllers == null) {
            // This method can be called before init() is called.
            return;
        }
        if (mControllers.uiController.isIconAlignedWithHotseat()) {
            if (!mTaskbarLauncherStateController.isAnimatingToLauncher()) {
                // Only animate the nav buttons while home and not animating home, otherwise let
                // the TaskbarViewController handle it.
                mControllers.navbarButtonsViewController
                        .getTaskbarNavButtonTranslationYForInAppDisplay()
                        .updateValue(mLauncher.getDeviceProfile().getTaskbarOffsetY()
                                * mTaskbarInAppDisplayProgress.value);
                mControllers.navbarButtonsViewController
                        .getOnTaskbarBackgroundNavButtonColorOverride().updateValue(progress);
            }
            if (isBubbleBarEnabled()) {
                mControllers.bubbleControllers.ifPresent(
                        c -> c.bubbleStashController.setInAppDisplayOverrideProgress(
                                mTaskbarInAppDisplayProgress.value));
            }
        }
    }

    /** Returns true iff any in-app display progress > 0. */
    public boolean shouldUseInAppLayout() {
        return mTaskbarInAppDisplayProgress.value > 0;
    }

    public boolean isBubbleBarEnabled() {
        return BubbleBarController.isBubbleBarEnabled();
    }

    /** Whether the bubble bar has any bubbles. */
    public boolean hasBubbles() {
        if (mControllers == null) {
            return false;
        }
        if (mControllers.bubbleControllers.isEmpty()) {
            return false;
        }
        return mControllers.bubbleControllers.get().bubbleBarViewController.hasBubbles();
    }

    @Override
    public void onExpandPip() {
        super.onExpandPip();
        mTaskbarLauncherStateController.updateStateForFlag(FLAG_VISIBLE, false);
        mTaskbarLauncherStateController.applyState();
    }

    @Override
    public void updateStateForSysuiFlags(@SystemUiStateFlags long sysuiFlags) {
        mTaskbarLauncherStateController.updateStateForSysuiFlags(sysuiFlags);
    }

    @Override
    public boolean isIconAlignedWithHotseat() {
        return mTaskbarLauncherStateController.isIconAlignedWithHotseat();
    }

    @Override
    public boolean isHotseatIconOnTopWhenAligned() {
        return mTaskbarLauncherStateController.isInHotseatOnTopStates()
                && mTaskbarInAppDisplayProgressMultiProp.get(MINUS_ONE_PAGE_PROGRESS_INDEX)
                    .getValue() == 0;
    }

    @Override
    public boolean isAnimatingToHotseat() {
        return mTaskbarLauncherStateController.isAnimatingToLauncher()
                && isIconAlignedWithHotseat();
    }

    @Override
    public void endAnimationToHotseat() {
        mTaskbarLauncherStateController.resetIconAlignment();
    }

    @Override
    protected boolean isInOverviewUi() {
        return mTaskbarLauncherStateController.isInOverviewUi();
    }

    @Override
    protected boolean canToggleHomeAllApps() {
        return mLauncher.isResumed()
                && !mTaskbarLauncherStateController.isInOverviewUi()
                && !mLauncher.areDesktopTasksVisible();
    }

    @Override
    public RecentsView getRecentsView() {
        return mLauncher.getOverviewPanel();
    }

    @Override
    public void launchSplitTasks(
            @NonNull GroupTask groupTask, @Nullable RemoteTransition remoteTransition) {
        mLauncher.launchSplitTasks(groupTask, remoteTransition);
    }

    @Override
    protected void onIconLayoutBoundsChanged() {
        mTaskbarLauncherStateController.resetIconAlignment();
    }

    @Override
    public void dumpLogs(String prefix, PrintWriter pw) {
        super.dumpLogs(prefix, pw);

        pw.println(String.format("%s\tTaskbar in-app display progress: %.2f", prefix,
                mTaskbarInAppDisplayProgress.value));
        mTaskbarInAppDisplayProgressMultiProp.dump(
                prefix + "\t\t",
                pw,
                "mTaskbarInAppDisplayProgressMultiProp",
                "MINUS_ONE_PAGE_PROGRESS_INDEX",
                "ALL_APPS_PAGE_PROGRESS_INDEX",
                "WIDGETS_PAGE_PROGRESS_INDEX",
                "SYSUI_SURFACE_PROGRESS_INDEX",
                "LAUNCHER_PAUSE_PROGRESS_INDEX");

        mTaskbarLauncherStateController.dumpLogs(prefix + "\t", pw);
    }

    @Override
    protected String getTaskbarUIControllerName() {
        return "LauncherTaskbarUIController";
    }

    @Override
    public void onBubbleBarLocationAnimated(BubbleBarLocation location) {
        mTaskbarLauncherStateController.onBubbleBarLocationChanged(location, /* animate = */ true);
        mLauncher.setBubbleBarLocation(location);
    }

    @Override
    public void onBubbleBarLocationUpdated(BubbleBarLocation location) {
        mTaskbarLauncherStateController.onBubbleBarLocationChanged(location, /* animate = */ false);
        mLauncher.setBubbleBarLocation(location);
    }

    @Override
    public void onSwipeToUnstashTaskbar() {
        // Once taskbar is unstashed, the user cannot return back to the overlay. We can
        // clear it here to set the expected state once the user goes home.
        if (mLauncher.getWorkspace().isOverlayShown()) {
            mLauncher.getWorkspace().onOverlayScrollChanged(0);
        }
    }

    /**
     * Called when Launcher Activity resumed while staying at home.
     * <p>
     * Shift nav buttons up to at-home position.
     */
    public void onLauncherResume() {
        mLauncherPauseProgress.animateToValue(0.0f).start();
    }

    /**
     * Called when Launcher Activity paused while staying at home.
     * <p>
     * To avoid UI clash between taskbar & bottom sheet, shift nav buttons down to in-app position.
     */
    public void onLauncherPause() {
        mLauncherPauseProgress.animateToValue(1.0f).start();
    }

    /**
     * On launcher stop, avoid animating taskbar & overriding pre-existing animations.
     */
    public void onLauncherStop() {
        mLauncherPauseProgress.cancelAnimation();
        mLauncherPauseProgress.updateValue(0.0f);
    }

    private void onLauncherPauseProgressUpdate() {
        // If we are not aligned with hotseat, setting this will clobber the 3 button nav position.
        // So in that case, treat the progress as 0 instead.
        float pauseProgress = isIconAlignedWithHotseat() ? mLauncherPauseProgress.value : 0;
        onTaskbarInAppDisplayProgressUpdate(pauseProgress, LAUNCHER_PAUSE_PROGRESS_INDEX);
    }


}
