/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.ContentResolver;
import android.database.ContentObserver;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.provider.Settings;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.animation.AccelerateInterpolator;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.Surface;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.DelegateViewHelper;
import com.android.systemui.statusbar.policy.DeadZone;
import com.android.systemui.statusbar.policy.KeyButtonView;

public class NavigationBarView extends LinearLayout {
    final static boolean DEBUG = false;
    final static String TAG = "PhoneStatusBar/NavigationBarView";

    final static boolean NAVBAR_ALWAYS_AT_RIGHT = true;

    // slippery nav bar when everything is disabled, e.g. during setup
    final static boolean SLIPPERY_WHEN_DISABLED= true;

    final static boolean ANIMATE_HIDE_TRANSITION = false; // turned off because it introduces unsightly delay when videos goes to full screen

    protected IStatusBarService mBarService;
    final Display mDisplay;
    View mCurrentView = null;
    View[] mRotatedViews = new View[4];

    int mBarSize;
    boolean mVertical;
    boolean mScreenOn;
	
    boolean mHidden, mLowProfile, mShowMenu, mNavStyles;
    int mDisabledFlags = 0;
    int mNavigationIconHints = 0;
	
    public final static int STOCK_STYLE = 0;
    public final static int HONEYCOMB_STYLE = 1;
    public final static int ZENYTH_STYLE = 2;
    public final static int AIRBRUSH_STYLE = 3;
    public final static int BALLOON_STYLE = 4;
    public final static int METRO_STYLE = 5;
    public final static int PLAYSTATION_STYLE = 6;
    public final static int IPHONE_BLACK_STYLE = 7;
    public final static int IPHONE_WHITE_STYLE = 8;
    public final static int TEXT_STYLE = 9;

    private Drawable mBackIcon, mBackLandIcon, mBackAltIcon, mBackAltLandIcon;
    
    private DelegateViewHelper mDelegateHelper;
    private DeadZone mDeadZone;
    private Context mContext;

    // workaround for LayoutTransitions leaving the nav buttons in a weird state (bug 5549288)
    final static boolean WORKAROUND_INVALID_LAYOUT = true;
    final static int MSG_CHECK_INVALID_LAYOUT = 8686;

    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_CHECK_INVALID_LAYOUT:
                    final String how = "" + m.obj;
                    final int w = getWidth();
                    final int h = getHeight();
                    final int vw = mCurrentView.getWidth();
                    final int vh = mCurrentView.getHeight();

                    if (h != vh || w != vw) {
                        Slog.w(TAG, String.format(
                            "*** Invalid layout in navigation bar (%s this=%dx%d cur=%dx%d)",
                            how, w, h, vw, vh));
                        if (WORKAROUND_INVALID_LAYOUT) {
                            requestLayout();
                        }
                    }
                    break;
            }
        }
    }

    public void setDelegateView(View view) {
        mDelegateHelper.setDelegateView(view);
    }

    public void setBar(BaseStatusBar phoneStatusBar) {
        mDelegateHelper.setBar(phoneStatusBar);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mDeadZone != null && event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            mDeadZone.poke(event);
        }
        if (mDelegateHelper != null) {
            boolean ret = mDelegateHelper.onInterceptTouchEvent(event);
            if (ret) return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mDelegateHelper.onInterceptTouchEvent(event);
    }

    private H mHandler = new H();

    public View getRecentsButton() {
        return mCurrentView.findViewById(R.id.recent_apps);
    }

    public View getMenuButton() {
        return mCurrentView.findViewById(R.id.menu);
    }

    public View getBackButton() {
        return mCurrentView.findViewById(R.id.back);
    }

    public View getHomeButton() {
        return mCurrentView.findViewById(R.id.home);
    }

    // for when home is disabled, but search isn't
    public View getSearchLight() {
        return mCurrentView.findViewById(R.id.search_light);
    }

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
	mContext = context;
        mHidden = false;

        mDisplay = ((WindowManager)context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));

        final Resources res = mContext.getResources();
        mBarSize = res.getDimensionPixelSize(R.dimen.navigation_bar_size);
        mVertical = false;
        mShowMenu = false;
		mNavStyles = false;
        mDelegateHelper = new DelegateViewHelper(this);

		mBackIcon = res.getDrawable(R.drawable.ic_sysbar_back);
        mBackLandIcon = res.getDrawable(R.drawable.ic_sysbar_back_land);
        mBackAltIcon = res.getDrawable(R.drawable.ic_sysbar_back_ime);
        mBackAltLandIcon = res.getDrawable(R.drawable.ic_sysbar_back_ime);
	mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SYSTEMUI_NAVBAR_COLOR), false,
                new ContentObserver(new Handler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateColor();
                    }
                });
    }

    public void notifyScreenOn(boolean screenOn) {
        mScreenOn = screenOn;
        setDisabledFlags(mDisabledFlags, true);
    }

    View.OnTouchListener mLightsOutListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                // even though setting the systemUI visibility below will turn these views
                // on, we need them to come up faster so that they can catch this motion
                // event
                setLowProfile(false, false, false);

                try {
                    mBarService.setSystemUiVisibility(0, View.SYSTEM_UI_FLAG_LOW_PROFILE);
                } catch (android.os.RemoteException ex) {
                }
            }
            return false;
        }
    };

    public void setNavigationIconHints(int hints) {
        setNavigationIconHints(hints, false);
    }

    public void setNavigationIconHints(int hints, boolean force) {
        if (!force && hints == mNavigationIconHints) return;

        if (DEBUG) {
            android.widget.Toast.makeText(mContext,
                "Navigation icon hints = " + hints,
                500).show();
        }

        mNavigationIconHints = hints;

		// We can't gaurantee users will set these buttons as targets
		if (getBackButton() != null) {
			getBackButton().setAlpha(
			(0 != (hints & StatusBarManager.NAVIGATION_HINT_BACK_NOP)) ? 0.5f : 1.0f);
			// Disable this for the moment
			/* ((ImageView)getBackButton()).setImageDrawable(
				 (0 != (hints & StatusBarManager.NAVIGATION_HINT_BACK_ALT))
				 ? (mVertical ? mBackAltLandIcon : mBackAltIcon)
				 : (mVertical ? mBackLandIcon : mBackIcon)); */
		}

        getHomeButton().setAlpha(
            (0 != (hints & StatusBarManager.NAVIGATION_HINT_HOME_NOP)) ? 0.5f : 1.0f);
        getRecentsButton().setAlpha(
            (0 != (hints & StatusBarManager.NAVIGATION_HINT_RECENT_NOP)) ? 0.5f : 1.0f);
    }

	public void setIconStyle(final boolean style) {
		setIconStyle(style, false);
	}	

	public void setIconStyle(final boolean style, final boolean force) {
		if (!force && mNavStyles == style) return;
			mNavStyles = style;
			boolean localStyle = style;

		int currentStyle = Settings.System.getInt(mContext.getContentResolver(),
								Settings.System.NAVBAR_STYLE_ICON, STOCK_STYLE);

	switch (currentStyle) {
		default:
			case STOCK_STYLE:
				((ImageView) getMenuButton())
				.setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land
				: R.drawable.ic_sysbar_menu);
				((ImageView) getBackButton())
				.setImageResource(mVertical ? R.drawable.ic_sysbar_back_land
				: R.drawable.ic_sysbar_back);
				((ImageView) getRecentsButton())
				.setImageResource(mVertical ? R.drawable.ic_sysbar_recent_land
				: R.drawable.ic_sysbar_recent);
				((ImageView) getHomeButton())
				.setImageResource(mVertical ? R.drawable.ic_sysbar_home_land
				: R.drawable.ic_sysbar_home);
			break;

			case HONEYCOMB_STYLE:
				((ImageView) getMenuButton())
				.setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land_hc
				: R.drawable.ic_sysbar_menu_hc);
				((ImageView) getBackButton())
				.setImageResource(mVertical ? R.drawable.ic_sysbar_back_land_hc
				: R.drawable.ic_sysbar_back_hc);
				((ImageView) getRecentsButton())
				.setImageResource(mVertical ? R.drawable.ic_sysbar_recent_land_hc
				: R.drawable.ic_sysbar_recent_hc);
				((ImageView) getHomeButton())
				.setImageResource(mVertical ? R.drawable.ic_sysbar_home_land_hc
				: R.drawable.ic_sysbar_home_hc);
				localStyle = true;
			break;

			case ZENYTH_STYLE:
				((ImageView) getMenuButton())
				.setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land_zen
				: R.drawable.ic_sysbar_menu_zen);
				((ImageView) getBackButton())
				.setImageResource(mVertical ? R.drawable.ic_sysbar_back_land_zen
				: R.drawable.ic_sysbar_back_zen);
				((ImageView) getRecentsButton())
				.setImageResource(mVertical ? R.drawable.ic_sysbar_recent_land_zen
				: R.drawable.ic_sysbar_recent_zen);
				((ImageView) getHomeButton())
				.setImageResource(mVertical ? R.drawable.ic_sysbar_home_land_zen
				: R.drawable.ic_sysbar_home_zen);
				localStyle = true;
			break;

		case AIRBRUSH_STYLE:
			((ImageView) getMenuButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land_air
			: R.drawable.ic_sysbar_menu_air);
			((ImageView) getBackButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_back_land_air
			: R.drawable.ic_sysbar_back_air);
			((ImageView) getRecentsButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_recent_land_air
			: R.drawable.ic_sysbar_recent_air);
			((ImageView) getHomeButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_home_land_air
			: R.drawable.ic_sysbar_home_air);
			localStyle = true;
		break;

		case BALLOON_STYLE:
			((ImageView) getMenuButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land_ball
			: R.drawable.ic_sysbar_menu_ball);
			((ImageView) getBackButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_back_land_ball
			: R.drawable.ic_sysbar_back_ball);
			((ImageView) getRecentsButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_recent_land_ball
			: R.drawable.ic_sysbar_recent_ball);
			((ImageView) getHomeButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_home_land_ball
			: R.drawable.ic_sysbar_home_ball);
			localStyle = true;
		break;

		case METRO_STYLE:
			((ImageView) getMenuButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land_metro
			: R.drawable.ic_sysbar_menu_metro);
			((ImageView) getBackButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_back_land_metro
			: R.drawable.ic_sysbar_back_metro);
			((ImageView) getRecentsButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_recent_land_metro
			: R.drawable.ic_sysbar_recent_metro);
			((ImageView) getHomeButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_home_land_metro
			: R.drawable.ic_sysbar_home_metro);
			localStyle = true;
		break;

		case PLAYSTATION_STYLE:
			((ImageView) getMenuButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land
			: R.drawable.ic_sysbar_menu);
			((ImageView) getBackButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_back_land_play
			: R.drawable.ic_sysbar_back_play);
			((ImageView) getRecentsButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_recent_land_play
			: R.drawable.ic_sysbar_recent_play);
			((ImageView) getHomeButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_home_land_play
			: R.drawable.ic_sysbar_home_play);
			localStyle = true;
		break;

		case IPHONE_BLACK_STYLE:
			((ImageView) getMenuButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_blank
			: R.drawable.ic_sysbar_blank);
			((ImageView) getBackButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_blank
			: R.drawable.ic_sysbar_blank);
			((ImageView) getRecentsButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_blank
			: R.drawable.ic_sysbar_blank);
			((ImageView) getHomeButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_home_blk
			: R.drawable.ic_sysbar_home_blk);
			localStyle = true;
		break;

		case IPHONE_WHITE_STYLE:
			((ImageView) getMenuButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_blank
			: R.drawable.ic_sysbar_blank);
			((ImageView) getBackButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_blank
			: R.drawable.ic_sysbar_blank);
			((ImageView) getRecentsButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_blank
			: R.drawable.ic_sysbar_blank);
			((ImageView) getHomeButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_home_white
			: R.drawable.ic_sysbar_home_white);
			localStyle = true;
		break;

		case TEXT_STYLE:
			((ImageView) getMenuButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_menu_land_text
			: R.drawable.ic_sysbar_menu_text);
			((ImageView) getBackButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_back_land_text
			: R.drawable.ic_sysbar_back_text);
			((ImageView) getRecentsButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_recent_land_text
			: R.drawable.ic_sysbar_recent_text);
			((ImageView) getHomeButton())
			.setImageResource(mVertical ? R.drawable.ic_sysbar_home_land_text
			: R.drawable.ic_sysbar_home_text);
			localStyle = true;
		break;
		}
	}

    
    public void setDisabledFlags(int disabledFlags) {
        setDisabledFlags(disabledFlags, false);
    }

    public void setDisabledFlags(int disabledFlags, boolean force) {
        if (!force && mDisabledFlags == disabledFlags) return;

        mDisabledFlags = disabledFlags;

        final boolean disableHome = ((disabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);
        final boolean disableRecent = ((disabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0);
        final boolean disableBack = ((disabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                && ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) == 0);
        final boolean disableSearch = ((disabledFlags & View.STATUS_BAR_DISABLE_SEARCH) != 0);

        if (SLIPPERY_WHEN_DISABLED) {
            setSlippery(disableHome && disableRecent && disableBack && disableSearch);
        }

        if (!mScreenOn && mCurrentView != null) {
            ViewGroup navButtons = (ViewGroup) mCurrentView.findViewById(R.id.nav_buttons);
            LayoutTransition lt = navButtons == null ? null : navButtons.getLayoutTransition();
            if (lt != null) {
                lt.disableTransitionType(
                        LayoutTransition.CHANGE_APPEARING | LayoutTransition.CHANGE_DISAPPEARING |
                        LayoutTransition.APPEARING | LayoutTransition.DISAPPEARING);
            }
        }

        getBackButton()   .setVisibility(disableBack       ? View.INVISIBLE : View.VISIBLE);
        getHomeButton()   .setVisibility(disableHome       ? View.INVISIBLE : View.VISIBLE);
        getRecentsButton().setVisibility(disableRecent     ? View.INVISIBLE : View.VISIBLE);

        getSearchLight().setVisibility((disableHome && !disableSearch) ? View.VISIBLE : View.GONE);
	
    }

    public void setSlippery(boolean newSlippery) {
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
        if (lp != null) {
            boolean oldSlippery = (lp.flags & WindowManager.LayoutParams.FLAG_SLIPPERY) != 0;
            if (!oldSlippery && newSlippery) {
                lp.flags |= WindowManager.LayoutParams.FLAG_SLIPPERY;
            } else if (oldSlippery && !newSlippery) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_SLIPPERY;
            } else {
                return;
            }
            WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout(this, lp);
        }
    }

    public void setMenuVisibility(final boolean show) {
        setMenuVisibility(show, false);
    }

    public void setMenuVisibility(final boolean show, final boolean force) {
        if (!force && mShowMenu == show) return;

        mShowMenu = show;

        getMenuButton().setVisibility(mShowMenu ? View.VISIBLE : View.INVISIBLE);
    }

    public void setLowProfile(final boolean lightsOut) {
        setLowProfile(lightsOut, true, false);
    }

    public void setLowProfile(final boolean lightsOut, final boolean animate, final boolean force) {
        if (!force && lightsOut == mLowProfile) return;

        mLowProfile = lightsOut;

        if (DEBUG) Slog.d(TAG, "setting lights " + (lightsOut?"out":"on"));

        final View navButtons = mCurrentView.findViewById(R.id.nav_buttons);
        final View lowLights = mCurrentView.findViewById(R.id.lights_out);

        // ok, everyone, stop it right there
        navButtons.animate().cancel();
        lowLights.animate().cancel();

        if (!animate) {
            navButtons.setAlpha(lightsOut ? 0f : 1f);

            lowLights.setAlpha(lightsOut ? 1f : 0f);
            lowLights.setVisibility(lightsOut ? View.VISIBLE : View.GONE);
        } else {
            navButtons.animate()
                .alpha(lightsOut ? 0f : 1f)
                .setDuration(lightsOut ? 750 : 250)
                .start();

            lowLights.setOnTouchListener(mLightsOutListener);
            if (lowLights.getVisibility() == View.GONE) {
                lowLights.setAlpha(0f);
                lowLights.setVisibility(View.VISIBLE);
            }
            lowLights.animate()
                .alpha(lightsOut ? 1f : 0f)
                .setDuration(lightsOut ? 750 : 250)
                .setInterpolator(new AccelerateInterpolator(2.0f))
                .setListener(lightsOut ? null : new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator _a) {
                        lowLights.setVisibility(View.GONE);
                    }
                })
                .start();
        }
    }

    public void setHidden(final boolean hide) {
        if (hide == mHidden) return;

        mHidden = hide;
        Slog.d(TAG,
            (hide ? "HIDING" : "SHOWING") + " navigation bar");

        // bring up the lights no matter what
        setLowProfile(false);
    }

    @Override
    public void onFinishInflate() {
        mRotatedViews[Surface.ROTATION_0] = 
        mRotatedViews[Surface.ROTATION_180] = findViewById(R.id.rot0);

        mRotatedViews[Surface.ROTATION_90] = findViewById(R.id.rot90);
        
        mRotatedViews[Surface.ROTATION_270] = NAVBAR_ALWAYS_AT_RIGHT
                                                ? findViewById(R.id.rot90)
                                                : findViewById(R.id.rot270);

        mCurrentView = mRotatedViews[Surface.ROTATION_0];
	updateColor();

	
    }

    public void reorient() {
        final int rot = mDisplay.getRotation();
        for (int i=0; i<4; i++) {
            mRotatedViews[i].setVisibility(View.GONE);
        }
        mCurrentView = mRotatedViews[rot];
        mCurrentView.setVisibility(View.VISIBLE);

        mDeadZone = (DeadZone) mCurrentView.findViewById(R.id.deadzone);

        // force the low profile & disabled states into compliance
        setLowProfile(mLowProfile, false, true /* force */);
        setDisabledFlags(mDisabledFlags, true /* force */);
        setMenuVisibility(mShowMenu, true /* force */);
		setIconStyle(mNavStyles, true /* force */);

        if (DEBUG) {
            Slog.d(TAG, "reorient(): rot=" + mDisplay.getRotation());
        }

        setNavigationIconHints(mNavigationIconHints, true);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mDelegateHelper.setInitialTouchRegion(getHomeButton(), getBackButton(), getRecentsButton());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (DEBUG) Slog.d(TAG, String.format(
                    "onSizeChanged: (%dx%d) old: (%dx%d)", w, h, oldw, oldh));

        final boolean newVertical = w > 0 && h > w;
        if (newVertical != mVertical) {
            mVertical = newVertical;
            //Slog.v(TAG, String.format("onSizeChanged: h=%d, w=%d, vert=%s", h, w, mVertical?"y":"n"));
            reorient();
        }

        postCheckForInvalidLayout("sizeChanged");
        super.onSizeChanged(w, h, oldw, oldh);
    }

    /*
    @Override
    protected void onLayout (boolean changed, int left, int top, int right, int bottom) {
        if (DEBUG) Slog.d(TAG, String.format(
                    "onLayout: %s (%d,%d,%d,%d)", 
                    changed?"changed":"notchanged", left, top, right, bottom));
        super.onLayout(changed, left, top, right, bottom);
    }

    // uncomment this for extra defensiveness in WORKAROUND_INVALID_LAYOUT situations: if all else
    // fails, any touch on the display will fix the layout.
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Slog.d(TAG, "onInterceptTouchEvent: " + ev.toString());
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            postCheckForInvalidLayout("touch");
        }
        return super.onInterceptTouchEvent(ev);
    }
    */

    private String getResourceName(int resId) {
        if (resId != 0) {
            final android.content.res.Resources res = mContext.getResources();
            try {
                return res.getResourceName(resId);
            } catch (android.content.res.Resources.NotFoundException ex) {
                return "(unknown)";
            }
        } else {
            return "(null)";
        }
    }

    private void postCheckForInvalidLayout(final String how) {
        mHandler.obtainMessage(MSG_CHECK_INVALID_LAYOUT, 0, 0, how).sendToTarget();
    }

    private static String visibilityToString(int vis) {
        switch (vis) {
            case View.INVISIBLE:
                return "INVISIBLE";
            case View.GONE:
                return "GONE";
            default:
                return "VISIBLE";
        }
    }

    private void updateColor() {
        int color = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SYSTEMUI_NAVBAR_COLOR,
                Settings.System.SYSTEMUI_NAVBAR_COLOR_DEF);
        float alpha = Color.alpha(color);
        this.setBackground(new ColorDrawable(color));
        this.setAlpha(alpha);
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NavigationBarView {");
        final Rect r = new Rect();
        final Point size = new Point();
        mDisplay.getRealSize(size);

        pw.println(String.format("      this: " + PhoneStatusBar.viewInfo(this)
                        + " " + visibilityToString(getVisibility())));

        getWindowVisibleDisplayFrame(r);
        final boolean offscreen = r.right > size.x || r.bottom > size.y;
        pw.println("      window: " 
                + r.toShortString()
                + " " + visibilityToString(getWindowVisibility())
                + (offscreen ? " OFFSCREEN!" : ""));

        pw.println(String.format("      mCurrentView: id=%s (%dx%d) %s",
                        getResourceName(mCurrentView.getId()),
                        mCurrentView.getWidth(), mCurrentView.getHeight(),
                        visibilityToString(mCurrentView.getVisibility())));

        pw.println(String.format("      disabled=0x%08x vertical=%s hidden=%s low=%s menu=%s",
                        mDisabledFlags,
                        mVertical ? "true" : "false",
                        mHidden ? "true" : "false",
                        mLowProfile ? "true" : "false",
			mNavStyles ? "true" : "false",
                        mShowMenu ? "true" : "false"));

        final View back = getBackButton();
        final View home = getHomeButton();
        final View recent = getRecentsButton();
        final View menu = getMenuButton();

        pw.println("      back: "
                + PhoneStatusBar.viewInfo(back)
                + " " + visibilityToString(back.getVisibility())
                );
        pw.println("      home: "
                + PhoneStatusBar.viewInfo(home)
                + " " + visibilityToString(home.getVisibility())
                );
        pw.println("      rcnt: "
                + PhoneStatusBar.viewInfo(recent)
                + " " + visibilityToString(recent.getVisibility())
                );
        pw.println("      menu: "
                + PhoneStatusBar.viewInfo(menu)
                + " " + visibilityToString(menu.getVisibility())
                );
        pw.println("    }");
	}
}
