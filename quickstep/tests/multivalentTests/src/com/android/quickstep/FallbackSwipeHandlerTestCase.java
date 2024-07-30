/*
 * Copyright (C) 2024 The Android Open Source Project
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

import androidx.test.filters.SmallTest;

import com.android.launcher3.util.LauncherMultivalentJUnit;
import com.android.quickstep.fallback.FallbackRecentsView;
import com.android.quickstep.fallback.RecentsState;

import org.junit.runner.RunWith;
import org.mockito.Mock;

@SmallTest
@RunWith(LauncherMultivalentJUnit.class)
public class FallbackSwipeHandlerTestCase extends AbsSwipeUpHandlerTestCase<
        RecentsActivity,
        RecentsState,
        FallbackRecentsView<RecentsActivity>,
        RecentsActivity,
        FallbackActivityInterface,
        FallbackSwipeHandler> {

    @Mock private RecentsActivity mRecentsActivity;
    @Mock private FallbackRecentsView mRecentsView;


    @Override
    protected FallbackSwipeHandler createSwipeHandler(
            long touchTimeMs, boolean continuingLastGesture) {
        return new FallbackSwipeHandler(
                mContext,
                mRecentsAnimationDeviceState,
                mTaskAnimationManager,
                mGestureState,
                touchTimeMs,
                continuingLastGesture,
                mInputConsumerController);
    }

    @Override
    protected RecentsActivity getRecentsContainer() {
        return mRecentsActivity;
    }

    @Override
    protected FallbackRecentsView getRecentsView() {
        return mRecentsView;
    }
}
