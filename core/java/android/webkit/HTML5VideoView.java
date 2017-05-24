
package android.webkit;

import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;
import android.view.SurfaceView;
import android.webkit.HTML5VideoViewProxy;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import java.util.Locale;


/**
 * @hide This is only used by the browser
 */
public class HTML5VideoView implements MediaPlayer.OnPreparedListener{

    protected static final String LOGTAG = "HTML5VideoView";
	private void LOGD(String msg){
			Log.d(LOGTAG,msg);
	}
    protected static final String COOKIE = "Cookie";
    protected static final String HIDE_URL_LOGS = "x-hide-urls-from-log";

    // For handling the seekTo before prepared, we need to know whether or not
    // the video is prepared. Therefore, we differentiate the state between
    // prepared and not prepared.
    // When the video is not prepared, we will have to save the seekTo time,
    // and use it when prepared to play.
    // NOTE: these values are in sync with VideoLayerAndroid.h in webkit side.
    // Please keep them in sync when changed.
    static final int STATE_INITIALIZED        = 0;
    static final int STATE_NOTPREPARED        = 1;
    static final int STATE_PREPARED           = 2;
    static final int STATE_PLAYING            = 3;
    static final int STATE_RELEASED           = 4;
    protected static int mCurrentState;
	//protected boolean mCurrentPaused = false;

    protected HTML5VideoViewProxy mProxy;

    // Save the seek time when not prepared. This can happen when switching
    // video besides initial load.
    protected int mSaveSeekTime;

    // This is used to find the VideoLayer on the native side.
    protected int mVideoLayerId;

    // Given the fact we only have one SurfaceTexture, we cannot support multiple
    // player at the same time. We may recreate a new one and abandon the old
    // one at transition time.
    protected MediaPlayer mPlayer;

    // This will be set up every time we create the Video View object.
    // Set to true only when switching into full screen while playing
    protected boolean mAutostart;

    // We need to save such info.
    protected Uri mUri;
    protected Map<String, String> mHeaders;

    // The timer for timeupate events.
    // See http://www.whatwg.org/specs/web-apps/current-work/#event-media-timeupdate
    protected static Timer mTimer = null;

    protected boolean mPauseDuringPreparing;

    // The spec says the timer should fire every 250 ms or less.
    private static final int TIMEUPDATE_PERIOD = 250;  // ms



	private int mVideoWidth;
    private int mVideoHeight;
    // common Video control FUNCTIONS:
    public void start() {
    LOGD("start");
        if (mCurrentState == STATE_PREPARED) {
            // When replaying the same video, there is no onPrepared call.
            // Therefore, the timer should be set up here.
            if (mTimer == null)
            {
                mTimer = new Timer();
                mTimer.schedule(new TimeupdateTask(mProxy), TIMEUPDATE_PERIOD,
                        TIMEUPDATE_PERIOD);
            }
			Log.d(LOGTAG, "mPlayer.start()");
            mPlayer.start();			
			mCurrentState = STATE_PLAYING;
            setPlayerBuffering(false);
        }
    }

    public void pause() {
		if(DebugFlags.WEB_HTML5)
			LOGD("html5videoview pause isPlaying()="+isPlaying());
        if (isPlaying()) {
            mPlayer.pause();
			mCurrentState = STATE_PREPARED;
			if(DebugFlags.WEB_HTML5)
				LOGD("MediaPlayer pause! pos: " + mSaveSeekTime);
			//mCurrentPaused = true;
        } else if (mCurrentState == STATE_NOTPREPARED) {
       			 if(DebugFlags.WEB_HTML5)
					LOGD("mCurrentState=STATE_NOTPREPARED");
            mPauseDuringPreparing = true;
        }
        // Delete the Timer to stop it since there is no stop call.
        if (mTimer != null) {
            mTimer.purge();
            mTimer.cancel();
            mTimer = null;
        }
    }
	public void cancelTimer(){
		if(DebugFlags.WEB_HTML5)
			LOGD("cancelTimer mTimer="+mTimer);
		 if (mTimer != null) {
            mTimer.purge();
            mTimer.cancel();
            mTimer = null;
        }
	}
	public void resumeTimer(){
		if(DebugFlags.WEB_HTML5)
			LOGD("resumeTimer isPlaying()="+isPlaying());
		if(isPlaying()){
		   if (mTimer == null){
                mTimer = new Timer();
                mTimer.schedule(new TimeupdateTask(mProxy), TIMEUPDATE_PERIOD,TIMEUPDATE_PERIOD);
            }
		}
	}
    public int getDuration() {
		//LOGD("getDuration ");
        if (mCurrentState == STATE_PREPARED || mCurrentState == STATE_PLAYING) {
            return mPlayer.getDuration();
        } else {
            return -1;
        }
    }

    public int getCurrentPosition() {
        if (mCurrentState == STATE_PREPARED || mCurrentState == STATE_PLAYING) {
            return mPlayer.getCurrentPosition();
        }
        return 0;
    }

    public void seekTo(int pos) {
		//LOGD("seek to seek="+pos+" mCurrentState= "+mCurrentState);
		//mSaveSeekTime = pos;
	
        if (mCurrentState == STATE_PREPARED || mCurrentState == STATE_PLAYING) {
				if(getDuration() > 0 && (pos + 1000) > getDuration())
					pos -= 100;				
               	mPlayer.seekTo(pos);
        }
        else{
            mSaveSeekTime = pos;
        	}
        
    }

    public boolean isPlaying() {
        if (mCurrentState == STATE_PREPARED || mCurrentState == STATE_PLAYING) {
			if(mPlayer !=null){
            	return mPlayer.isPlaying();
			}else{
				return false;
			}
				
        } else {
            return false;
        }
    }

    public void release() {		
        if (mCurrentState != STATE_RELEASED) {
			mCurrentState = STATE_RELEASED;
			if(DebugFlags.WEB_HTML5)
				LOGD("mPlayer.release() mPlayer="+mPlayer);
			if(mPlayer!=null){
            	mPlayer.release();
			}
        }
        
		if(mProxy!=null){
			mProxy.dispatchReleaseMediaPlayer();
		}
    }

    public void stopPlayback() {
        if (mCurrentState == STATE_PREPARED || mCurrentState == STATE_PLAYING) {
			if(DebugFlags.WEB_HTML5)
				LOGD("mPlayer.stop()");
			if(mPlayer!=null){
            	mPlayer.stop();
				}
        }
    }

    public boolean getAutostart() {
        return mAutostart;
    }
    public boolean getPauseDuringPreparing() {
        return mPauseDuringPreparing;
    }

    // Every time we start a new Video, we create a VideoView and a MediaPlayer
    public void init(HTML5VideoViewProxy proxy ,int videoLayerId, int position, boolean autoStart,int currentstate) {
       // mPlayer = mMenew MediaPlayer();
       	mProxy = proxy;
        mPlayer = proxy.createMediaPlayer();
        mCurrentState = currentstate;
        
        mVideoLayerId = videoLayerId;
        mSaveSeekTime = position;
        mAutostart = autoStart;
        mPauseDuringPreparing = false;
    
    }

	public void onVideoSizeChanged(int width, int height) {
		mVideoWidth = width;
		mVideoHeight = height;
	}

    protected HTML5VideoView() {
    }

    protected static Map<String, String> generateHeaders(String url,
            HTML5VideoViewProxy proxy) {
        boolean isPrivate = proxy.getWebView().isPrivateBrowsingEnabled();
        String cookieValue = CookieManager.getInstance().getCookie(url, isPrivate);
        Map<String, String> headers = new HashMap<String, String>();
        if (cookieValue != null) {
            headers.put(COOKIE, cookieValue);
        }
        if (isPrivate) {
            headers.put(HIDE_URL_LOGS, "true");
        }

        return headers;
    }

    public void setVideoURI(String uri, HTML5VideoViewProxy proxy) {
        // When switching players, surface texture will be reused.
        mUri = Uri.parse(uri);
        mHeaders = generateHeaders(uri, proxy);
		String ua = mProxy.getWebView().getSettings().getUserAgentString();
		if (ua.contains("iPhone") || ua.contains("iPad")) {
			Locale currentLocale = Locale.getDefault();
			String ipad = "AppleCoreMedia/1.0.0.9A405 (iPad; U; CPU OS 5_0_1 like Mac OS X; " + currentLocale + ")";//zh_cn)";
			//Log.d(LOGTAG, "ua2: " + ipad);
			mHeaders.put("User-Agent", ipad);
		}
    }

    // Listeners setup FUNCTIONS:
    public void setOnCompletionListener(HTML5VideoViewProxy proxy) {
        mPlayer.setOnCompletionListener(proxy);
    }

    public void setOnErrorListener(HTML5VideoViewProxy proxy) {
        mPlayer.setOnErrorListener(proxy);
    }

    public void setOnPreparedListener(HTML5VideoViewProxy proxy) {
        mProxy = proxy;
        mPlayer.setOnPreparedListener(this);
    }

    public void setOnInfoListener(HTML5VideoViewProxy proxy) {
        mPlayer.setOnInfoListener(proxy);
    }

	public void setOnVideoSizeChangedListener(HTML5VideoViewProxy proxy) {
        mPlayer.setOnVideoSizeChangedListener(proxy);
    }

    // Normally called immediately after setVideoURI. But for full screen,
    // this should be after surface holder created
    public void prepareDataAndDisplayMode(HTML5VideoViewProxy proxy) {
        // SurfaceTexture will be created lazily here for inline mode
        decideDisplayMode();

        setOnCompletionListener(mProxy);
        setOnPreparedListener(mProxy);
        setOnErrorListener(mProxy);
        setOnInfoListener(mProxy);
		setOnVideoSizeChangedListener(mProxy);
		if(DebugFlags.WEB_HTML5)
			LOGD("prepareDataAndDisplayMode mCurrentState = "+mCurrentState);
        if(mCurrentState == STATE_PLAYING||mCurrentState == STATE_PREPARED||mCurrentState == STATE_NOTPREPARED){
			start();
			return ;
		}
	
        // When there is exception, we could just bail out silently.
        // No Video will be played though. Write the stack for debug
     
        try {
            mPlayer.setDataSource(mProxy.getContext(), mUri, mHeaders);
            mPlayer.prepareAsync();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mCurrentState = STATE_NOTPREPARED;
    }


    // Common code
    public int getVideoLayerId() {
        return mVideoLayerId;
    }


    public int getCurrentState() {
        if (isPlaying()) {
			mCurrentState = STATE_PLAYING;
            return STATE_PLAYING;
        } else {
            return mCurrentState;
        }
    }

    private static final class TimeupdateTask extends TimerTask {
        private HTML5VideoViewProxy mProxy;

        public TimeupdateTask(HTML5VideoViewProxy proxy) {
            mProxy = proxy;
        }

        @Override
        public void run() {
            mProxy.onTimeupdate();
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
    if(DebugFlags.WEB_HTML5)
    	LOGD("<-------onPrepared=----------->");
        mCurrentState = STATE_PREPARED;
        seekTo(mSaveSeekTime);
        if (mProxy != null) {
            mProxy.onPrepared(mp);
        }
		if(DebugFlags.WEB_HTML5)
			LOGD("onPrepare mPauseDuringPreparing="+mPauseDuringPreparing);

        if (mPauseDuringPreparing) {
            pauseAndDispatch(mProxy);
            mPauseDuringPreparing = false;
        }

		mVideoWidth = mp.getVideoWidth();
        mVideoHeight = mp.getVideoHeight();
    }
	public int getVideoWidth() { return mVideoWidth; }
	public int getVideoHeight() { return mVideoHeight; }

    // Pause the play and update the play/pause button
    public void pauseAndDispatch(HTML5VideoViewProxy proxy) {
        pause();
        if (proxy != null) {
            proxy.dispatchOnPaused();
        }
    }

    // Below are functions that are different implementation on inline and full-
    // screen mode. Some are specific to one type, but currently are called
    // directly from the proxy.
    public void enterFullScreenVideoState(int layerId,
            HTML5VideoViewProxy proxy, WebViewClassic webView) {
    }

    public boolean isFullScreenMode() {
        return false;
    }

    public void decideDisplayMode() {
    }

    public boolean getReadyToUseSurfTex() {
        return false;
    }

    public SurfaceTexture getSurfaceTexture(int videoLayerId) {
        return null;
    }

    public void deleteSurfaceTexture() {
    }

    public int getTextureName() {
        return 0;
    }

    // This is true only when the player is buffering and paused
    public static boolean mPlayerBuffering = false;

    public boolean getPlayerBuffering() {
        return mPlayerBuffering;
    }

    public void setPlayerBuffering(boolean playerBuffering) {
		if(mPlayerBuffering == playerBuffering){
			return;
		}
        mPlayerBuffering = playerBuffering;
        switchProgressView(playerBuffering);
    }


    protected void switchProgressView(boolean playerBuffering) {
        // Only used in HTML5VideoFullScreen
    }

    public boolean surfaceTextureDeleted() {
        // Only meaningful for HTML5VideoInline
        return false;
    }

    public boolean fullScreenExited() {
        // Only meaningful for HTML5VideoFullScreen
        return false;
    }

    private boolean mStartWhenPrepared = false;

    public void setStartWhenPrepared(boolean willPlay) {
        mStartWhenPrepared  = willPlay;
    }

    public boolean getStartWhenPrepared() {
        return mStartWhenPrepared;
    }

    public void showControllerInFullScreen() {
    }

}
