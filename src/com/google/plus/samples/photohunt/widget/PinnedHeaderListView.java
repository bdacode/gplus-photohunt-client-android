/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.google.plus.samples.photohunt.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AbsListView.OnScrollListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ListAdapter;
import android.widget.ListView;

/**
 * A ListView that maintains a header pinned at the top of the list. The
 * pinned header can be pushed up and dissolved as needed.
 */
public class PinnedHeaderListView extends ListView
        implements OnScrollListener, OnItemSelectedListener {

    /**
     * Adapter interface.  The list adapter must implement this interface.
     */
    public interface PinnedHeaderAdapter {

        /**
         * Returns the overall number of pinned headers, visible or not.
         */
        int getPinnedHeaderCount();

        /**
         * Creates or updates the pinned header view.
         */
        View getPinnedHeaderView(int viewIndex, View convertView, ViewGroup parent);

        /**
         * Configures the pinned headers to match the visible list items. The
         * adapter should call {@link PinnedHeaderListView#setHeaderPinnedAtTop},
         * {@link PinnedHeaderListView#setHeaderPinnedAtBottom},
         * {@link PinnedHeaderListView#setFadingHeader} or
         * {@link PinnedHeaderListView#setHeaderInvisible}, for each header that
         * needs to change its position or visibility.
         */
        void configurePinnedHeaders(PinnedHeaderListView listView);

        /**
         * Returns the list position to scroll to if the pinned header is touched.
         * Return -1 if the list does not need to be scrolled.
         */
        int getScrollPositionForHeader(int viewIndex);
    }

    private static final int MAX_ALPHA = 255;
    private static final int TOP = 0;
    private static final int BOTTOM = 1;
    private static final int FADING = 2;

    private static final int DEFAULT_ANIMATION_DURATION = 100;
    
    private static final float PREFERRED_SELECTION_OFFSET_FROM_TOP = 0.33f;
    
    private int mRequestedScrollPosition = -1;
    private boolean mSmoothScrollRequested;

    private static final class PinnedHeader {
        View view;
        boolean visible;
        int y;
        int height;
        int alpha;
        int state;

        boolean animating;
        boolean targetVisible;
        int sourceY;
        int targetY;
        long targetTime;
    }

    private PinnedHeaderAdapter mAdapter;
    private int mSize;
    private PinnedHeader[] mHeaders;
    private RectF mBounds = new RectF();
    private Rect mClipRect = new Rect();
    private OnScrollListener mOnScrollListener;
    private OnItemSelectedListener mOnItemSelectedListener;
    private int mScrollState;

    private int mAnimationDuration = DEFAULT_ANIMATION_DURATION;
    private boolean mAnimating;
    private long mAnimationTargetTime;
    private int mHeaderPaddingLeft;
    private int mHeaderWidth;

    public PinnedHeaderListView(Context context) {
        this(context, null);
    }

    public PinnedHeaderListView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PinnedHeaderListView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        super.setOnScrollListener(this);
        super.setOnItemSelectedListener(this);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mHeaderPaddingLeft = getPaddingLeft();
        mHeaderWidth = r - l - mHeaderPaddingLeft - getPaddingRight();
    }

    public void setPinnedHeaderAnimationDuration(int duration) {
        mAnimationDuration = duration;
    }

    @Override
    public void setAdapter(ListAdapter adapter) {
        mAdapter = (PinnedHeaderAdapter) adapter;
        super.setAdapter(adapter);
    }

    @Override
    public void setOnScrollListener(OnScrollListener onScrollListener) {
        mOnScrollListener = onScrollListener;
        super.setOnScrollListener(this);
    }

    @Override
    public void setOnItemSelectedListener(OnItemSelectedListener listener) {
        mOnItemSelectedListener = listener;
        super.setOnItemSelectedListener(this);
    }

    public void onScroll(
    		AbsListView view,
    		int firstVisibleItem,
    		int visibleItemCount,
            int totalItemCount) {
        if (mAdapter != null) {
            int count = mAdapter.getPinnedHeaderCount();
            if (count != mSize) {
                mSize = count;
                if (mHeaders == null) {
                    mHeaders = new PinnedHeader[mSize];
                } else if (mHeaders.length < mSize) {
                    PinnedHeader[] headers = mHeaders;
                    mHeaders = new PinnedHeader[mSize];
                    System.arraycopy(headers, 0, mHeaders, 0, headers.length);
                }
            }

            for (int i = 0; i < mSize; i++) {
                if (mHeaders[i] == null) {
                    mHeaders[i] = new PinnedHeader();
                }
                mHeaders[i].view = mAdapter.getPinnedHeaderView(i, mHeaders[i].view, this);
            }

            mAnimationTargetTime = System.currentTimeMillis() + mAnimationDuration;
            mAdapter.configurePinnedHeaders(this);
            invalidateIfAnimating();

        }
        if (mOnScrollListener != null) {
            mOnScrollListener.onScroll(this, firstVisibleItem, visibleItemCount, totalItemCount);
        }
    }

    @Override
    protected float getTopFadingEdgeStrength() {
        // Disable vertical fading at the top when the pinned header is present
        return mSize > 0 ? 0 : super.getTopFadingEdgeStrength();
    }

    public void onScrollStateChanged(AbsListView view, int scrollState) {
        mScrollState = scrollState;
        if (mOnScrollListener != null) {
            mOnScrollListener.onScrollStateChanged(this, scrollState);
        }
    }

    /**
     * Ensures that the selected item is positioned below the top-pinned headers
     * and above the bottom-pinned ones.
     */
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        int height = getHeight();

        int windowTop = 0;
        int windowBottom = height;

        for (int i = 0; i < mSize; i++) {
            PinnedHeader header = mHeaders[i];
            if (header.visible) {
                if (header.state == TOP) {
                    windowTop = header.y + header.height;
                } else if (header.state == BOTTOM) {
                    windowBottom = header.y;
                    break;
                }
            }
        }

        View selectedView = getSelectedView();
        if (selectedView != null) {
            if (selectedView.getTop() < windowTop) {
                setSelectionFromTop(position, windowTop);
            } else if (selectedView.getBottom() > windowBottom) {
                setSelectionFromTop(position, windowBottom - selectedView.getHeight());
            }
        }

        if (mOnItemSelectedListener != null) {
            mOnItemSelectedListener.onItemSelected(parent, view, position, id);
        }
    }

    public void onNothingSelected(AdapterView<?> parent) {
        if (mOnItemSelectedListener != null) {
            mOnItemSelectedListener.onNothingSelected(parent);
        }
    }

    public int getPinnedHeaderHeight(int viewIndex) {
        ensurePinnedHeaderLayout(viewIndex);
        return mHeaders[viewIndex].view.getHeight();
    }

    /**
     * Set header to be pinned at the top.
     *
     * @param viewIndex index of the header view
     * @param y is position of the header in pixels.
     */
    public void setHeaderPinnedAtTop(int viewIndex, int y) {
        ensurePinnedHeaderLayout(viewIndex);
        PinnedHeader header = mHeaders[viewIndex];
        header.visible = true;
        header.y = y;
        header.state = TOP;
        header.animating = false;
    }

    /**
     * Set header to be pinned at the bottom.
     *
     * @param viewIndex index of the header view
     * @param y is position of the header in pixels.
     * @param animate true if the transition to the new coordinate should be animated
     */
    public void setHeaderPinnedAtBottom(int viewIndex, int y, boolean animate) {
        ensurePinnedHeaderLayout(viewIndex);
        PinnedHeader header = mHeaders[viewIndex];
        header.state = BOTTOM;
        if (header.animating) {
            header.targetTime = mAnimationTargetTime;
            header.sourceY = header.y;
            header.targetY = y;
        } else if (animate && (header.y != y || !header.visible)) {
            if (header.visible) {
                header.sourceY = header.y;
            } else {
                header.visible = true;
                header.sourceY = y + header.height;
            }
            header.animating = true;
            header.targetVisible = true;
            header.targetTime = mAnimationTargetTime;
            header.targetY = y;
        } else {
            header.visible = true;
            header.y = y;
        }
    }

    /**
     * Set header to be pinned at the top of the first visible item.
     *
     * @param viewIndex index of the header view
     * @param position the first position visible after the headers
     * @param fade whether the pinned item should fade as it is pushed up
     */
    public void setFadingHeader(int viewIndex, int position, boolean fade) {
        ensurePinnedHeaderLayout(viewIndex);

        View child = getChildAt(position - getFirstVisiblePosition());
        if (child == null) return;

        PinnedHeader header = mHeaders[viewIndex];
        header.visible = true;
        header.state = FADING;
        header.alpha = MAX_ALPHA;
        header.animating = false;

        int top = getTotalTopPinnedHeaderHeight();
        header.y = top;
        if (fade) {
            int bottom = child.getBottom() - top;
            int headerHeight = header.height;
            if (bottom < headerHeight) {
                int portion = bottom - headerHeight;
                header.alpha = MAX_ALPHA * (headerHeight + portion) / headerHeight;
                header.y = top + portion;
            }
        }
    }

    /**
     * Makes header invisible.
     *
     * @param viewIndex index of the header view
     * @param animate true if the transition to the new coordinate should be animated
     */
    public void setHeaderInvisible(int viewIndex, boolean animate) {
        PinnedHeader header = mHeaders[viewIndex];
        if (header.visible && (animate || header.animating) && header.state == BOTTOM) {
            header.sourceY = header.y;
            if (!header.animating) {
                header.visible = true;
                header.targetY = getBottom() + header.height;
            }
            header.animating = true;
            header.targetTime = mAnimationTargetTime;
            header.targetVisible = false;
        } else {
            header.visible = false;
        }
    }

    private void ensurePinnedHeaderLayout(int viewIndex) {
        View view = mHeaders[viewIndex].view;
        if (view.isLayoutRequested()) {
            int widthSpec = MeasureSpec.makeMeasureSpec(mHeaderWidth, MeasureSpec.EXACTLY);
            int heightSpec;
            ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
            if (layoutParams != null && layoutParams.height > 0) {
                heightSpec = MeasureSpec.makeMeasureSpec(layoutParams.height, MeasureSpec.EXACTLY);
            } else {
                heightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            }
            view.measure(widthSpec, heightSpec);
            int height = view.getMeasuredHeight();
            mHeaders[viewIndex].height = height;
            view.layout(0, 0, mHeaderWidth, height);
        }
    }

    /**
     * Returns the sum of heights of headers pinned to the top.
     */
    public int getTotalTopPinnedHeaderHeight() {
        for (int i = mSize; --i >= 0;) {
            PinnedHeader header = mHeaders[i];
            if (header.visible && header.state == TOP) {
                return header.y + header.height;
            }
        }
        return 0;
    }

    /**
     * Returns the list item position at the specified y coordinate.
     */
    public int getPositionAt(int y) {
        do {
            int position = pointToPosition(getPaddingLeft() + 1, y);
            if (position != -1) {
                return position;
            }
            // If position == -1, we must have hit a separator. Let's examine
            // a nearby pixel
            y--;
        } while (y > 0);
        return 0;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mScrollState == SCROLL_STATE_IDLE) {
            final int y = (int)ev.getY();
            for (int i = mSize; --i >= 0;) {
                PinnedHeader header = mHeaders[i];
                if (header.visible && header.y <= y && header.y + header.height > y) {
                    if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                        return smoothScrollToPartition(i);
                    } else {
                        return true;
                    }
                }
            }
        }

        return super.onInterceptTouchEvent(ev);
    }

    public void requestPositionToScreen(int position, boolean smoothScroll) {
        mRequestedScrollPosition = position;
        mSmoothScrollRequested = smoothScroll;
        requestLayout();
    }  

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        if (mRequestedScrollPosition == -1) {
            return;
        }  

        final int position = mRequestedScrollPosition;
        mRequestedScrollPosition = -1;

        int firstPosition = getFirstVisiblePosition() + 1; 
        int lastPosition = getLastVisiblePosition();
        if (position >= firstPosition && position <= lastPosition) {
            return; // Already on screen
        }  

        final int offset = (int) (getHeight() * PREFERRED_SELECTION_OFFSET_FROM_TOP);
        if (!mSmoothScrollRequested) {
            setSelectionFromTop(position, offset);

            // Since we have changed the scrolling position, we need to redo child layout
            // Calling "requestLayout" in the middle of a layout pass has no effect,
            // so we call layoutChildren explicitly
            super.layoutChildren();

        } else {
            // We will first position the list a couple of screens before or after
            // the new selection and then scroll smoothly to it.
            int twoScreens = (lastPosition - firstPosition) * 2; 
            int preliminaryPosition;
            if (position < firstPosition) {
                preliminaryPosition = position + twoScreens;
                if (preliminaryPosition >= getCount()) {
                    preliminaryPosition = getCount() - 1; 
                }  
                if (preliminaryPosition < firstPosition) {
                    setSelection(preliminaryPosition);
                    super.layoutChildren();
                }  
            } else {
                preliminaryPosition = position - twoScreens;
                if (preliminaryPosition < 0) {
                    preliminaryPosition = 0; 
                }  
                if (preliminaryPosition > lastPosition) {
                    setSelection(preliminaryPosition);
                    super.layoutChildren();
                }  
            }

            smoothScrollToPositionFromTopCheck(position, offset);
        }
    }

    private boolean smoothScrollToPartition(int partition) {
        final int position = mAdapter.getScrollPositionForHeader(partition);
        if (position == -1) {
            return false;
        }

        int offset = 0;
        for (int i = 0; i < partition; i++) {
            PinnedHeader header = mHeaders[i];
            if (header.visible) {
                offset += header.height;
            }
        }

        smoothScrollToPositionFromTopCheck(position + getHeaderViewsCount(), offset);
        return true;
    }

    @SuppressLint("NewApi")
	private void smoothScrollToPositionFromTopCheck(int position, int offset) {
    	if (android.os.Build.VERSION.SDK_INT >= 11)
    	{
    	    smoothScrollToPositionFromTop(position, offset); 
    	}
    	else if (android.os.Build.VERSION.SDK_INT >= 8)
    	{
    	    int firstVisible = getFirstVisiblePosition();
    	    int lastVisible = getLastVisiblePosition();
    	    
    	    if (position < firstVisible)
    	        smoothScrollToPosition(position);
    	    else
    	        smoothScrollToPosition(position + lastVisible - firstVisible - 2);
    	}	
    }
    
    private void invalidateIfAnimating() {
        mAnimating = false;
        for (int i = 0; i < mSize; i++) {
            if (mHeaders[i].animating) {
                mAnimating = true;
                invalidate();
                return;
            }
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        long currentTime = mAnimating ? System.currentTimeMillis() : 0;

        int top = 0;
        int bottom = getBottom();
        boolean hasVisibleHeaders = false;
        for (int i = 0; i < mSize; i++) {
            PinnedHeader header = mHeaders[i];
            if (header.visible) {
                hasVisibleHeaders = true;
                if (header.state == BOTTOM && header.y < bottom) {
                    bottom = header.y;
                } else if (header.state == TOP || header.state == FADING) {
                    int newTop = header.y + header.height;
                    if (newTop > top) {
                        top = newTop;
                    }
                }
            }
        }

        if (hasVisibleHeaders) {
            canvas.save();
            mClipRect.set(0, top, getWidth(), bottom);
            canvas.clipRect(mClipRect);
        }

        super.dispatchDraw(canvas);

        if (hasVisibleHeaders) {
            canvas.restore();

            // First draw top headers, then the bottom ones to handle the Z axis correctly
            for (int i = mSize; --i >= 0;) {
                PinnedHeader header = mHeaders[i];
                if (header.visible && (header.state == TOP || header.state == FADING)) {
                    drawHeader(canvas, header, currentTime);
                }
            }

            for (int i = 0; i < mSize; i++) {
                PinnedHeader header = mHeaders[i];
                if (header.visible && header.state == BOTTOM) {
                    drawHeader(canvas, header, currentTime);
                }
            }
        }

        invalidateIfAnimating();
    }

    private void drawHeader(Canvas canvas, PinnedHeader header, long currentTime) {
        if (header.animating) {
            int timeLeft = (int)(header.targetTime - currentTime);
            if (timeLeft <= 0) {
                header.y = header.targetY;
                header.visible = header.targetVisible;
                header.animating = false;
            } else {
                header.y = header.targetY + (header.sourceY - header.targetY) * timeLeft
                        / mAnimationDuration;
            }
        }
        if (header.visible) {
            View view = header.view;
            int saveCount = canvas.save();
            canvas.translate(mHeaderPaddingLeft, header.y);
            if (header.state == FADING) {
                mBounds.set(0, 0, mHeaderWidth, view.getHeight());
                canvas.saveLayerAlpha(mBounds, header.alpha, Canvas.ALL_SAVE_FLAG);
            }
            view.draw(canvas);
            canvas.restoreToCount(saveCount);
        }
    }
}