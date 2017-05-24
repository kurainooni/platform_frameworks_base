/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.webkit;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.http.EventHandler;
import android.net.http.Headers;
import android.net.http.RequestHandle;
import android.net.http.RequestQueue;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.net.Uri;
import android.content.Intent;
import android.os.Process;
import android.os.RemoteException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import android.view.Surface;

/**
 * <p>Proxy for HTML5 video views.
 */
class HTML5VideoViewProxy extends Handler
                          implements MediaPlayer.OnPreparedListener,
                          MediaPlayer.OnCompletionListener,
                          MediaPlayer.OnErrorListener,
                          MediaPlayer.OnInfoListener,
                          MediaPlayer.OnVideoSizeChangedListener,
                          SurfaceTexture.OnFrameAvailableListener {
    // Logging tag.
    private static final String LOGTAG = "HTML5VideoViewProxy";
	private static void LOGD(String msg){
			Log.d(LOGTAG,msg);
	}
    // Message Ids for WebCore thread -> UI thread communication.
    private static final int PLAY                = 100;
    private static final int SEEK                = 101;
    private static final int PAUSE               = 102;
    private static final int ERROR               = 103;
    private static final int LOAD_DEFAULT_POSTER = 104;
    private static final int BUFFERING_START     = 105;
    private static final int BUFFERING_END       = 106;
	private static final int NOTIFYURL			 = 107;
    // Message Ids to be handled on the WebCore thread
    private static final int PREPARED          = 200;
    private static final int ENDED             = 201;
    private static final int POSTER_FETCHED    = 202;
    private static final int PAUSED            = 203;
    private static final int STOPFULLSCREEN    = 204;
    private static final int RESTORESTATE      = 205;
	private static final int UPDATE_PLAYBACK_STATE = 206;
	private static final int SIZE_CHANGED      = 207;

    // Timer thread -> UI thread
    private static final int TIMEUPDATE = 300;

	private MediaPlayer mMediaPlayer = null;//new MediaPlayer();
	private static HTML5VideoView mHTML5VideoView;
    // The C++ MediaPlayerPrivateAndroid object.
    private static HTML5VideoView mInlineHtmlVideoView;
    int mNativePointer;
	private  Surface mInlineSurface;
    // The handler for WebCore thread messages;
    private Handler mWebCoreHandler;
    // The WebViewClassic instance that created this view.
    private WebViewClassic mWebView;
    // The poster image to be shown when the video is not playing.
    // This ref prevents the bitmap from being GC'ed.
    private Bitmap mPoster;
    // The poster downloader.
    private PosterDownloader mPosterDownloader;
    // The seek position.
    private int mSeekPosition;
	private static boolean mLastIsFullScreenMode = false;
    // A helper class to control the playback. This executes on the UI thread!
    public static final class VideoPlayer {
        // The proxy that is currently playing (if any).
        private static HTML5VideoViewProxy mCurrentProxy;
        // The VideoView instance. This is a singleton for now, at least until
        // http://b/issue?id=1973663 is fixed.
        

        public static boolean isVideoSelfEnded = false;
        // By using the baseLayer and the current video Layer ID, we can
        // identify the exact layer on the UI thread to use the SurfaceTexture.
        private static int mBaseLayer = 0;
		private static SurfaceTexture mVideoSurfaceTexture = null;

        private static void setPlayerBuffering(boolean playerBuffering) {
            mHTML5VideoView.setPlayerBuffering(playerBuffering);
        }

        // Every time webView setBaseLayer, this will be called.
        // When we found the Video layer, then we set the Surface Texture to it.
        // Otherwise, we may want to delete the Surface Texture to save memory.
        public static void setBaseLayer(int layer) {
            // Don't do this for full screen mode.
            if (mHTML5VideoView != null
                && !mHTML5VideoView.isFullScreenMode()
                && !mHTML5VideoView.surfaceTextureDeleted()) {

                mBaseLayer = layer;

                int currentVideoLayerId = mHTML5VideoView.getVideoLayerId();
                SurfaceTexture surfTexture = mHTML5VideoView.getSurfaceTexture(currentVideoLayerId);
                int textureName = mHTML5VideoView.getTextureName();

                if (layer != 0 && surfTexture != null && currentVideoLayerId != -1) {
                    int playerState = mHTML5VideoView.getCurrentState();
                    if (mHTML5VideoView.getPlayerBuffering()){
						//LOGD("buffering stage");
                        playerState = HTML5VideoView.STATE_NOTPREPARED;
                    	}
					//LOGD("playstage="+playerState);
                    boolean foundInTree = nativeSendSurfaceTexture(surfTexture,
                            layer, currentVideoLayerId, textureName,
                            playerState);
                    if (playerState >= HTML5VideoView.STATE_PREPARED
                            && !foundInTree) {
                            if(DebugFlags.WEB_HTML5)
                            	LOGD("setbase pause and delete currentVideoLayerId="+currentVideoLayerId);
                        //mHTML5VideoView.pauseAndDispatch(mCurrentProxy);
						//FIXME: do not delete the video's surfaceTexture here,
					    // release the surfacetexture when new video come.
					    //in here will cause 'foundInTree' return false when get wrong videolayer
					    // and Constantly create and delete surfacetexture.
                       // mHTML5VideoView.deleteSurfaceTexture();
                    }
                }
            }
        }

        // When a WebView is paused, we also want to pause the video in it.
        public static void pauseAndDispatch() {
            if (mHTML5VideoView != null) {
                mHTML5VideoView.pauseAndDispatch(mCurrentProxy);
                // When switching out, clean the video content on the old page
                // by telling the layer not readyToUseSurfTex.
                LOGD("pauseAndDispath mBaseLayer=" + mCurrentProxy.getWebView().getBaseLayer());
                setBaseLayer(mCurrentProxy.getWebView().getBaseLayer());
            }
        }

        public static void enterFullScreenVideo(int layerId, String url,
                HTML5VideoViewProxy proxy, WebViewClassic webView) {
                // Save the inline video info and inherit it in the full screen
                int savePosition = 0;
				int width = 0;
				int height = 0;
				int current_state = 0;
                boolean savedIsPlaying = true;
                if (mHTML5VideoView != null) {
                    // If we are playing the same video, then it is better to
                    // save the current position.
                    
                    if (layerId == mHTML5VideoView.getVideoLayerId()) {
                        savePosition = mHTML5VideoView.getCurrentPosition();
                        savedIsPlaying = mHTML5VideoView.isPlaying();
						width = mHTML5VideoView.getVideoWidth();
						height = mHTML5VideoView.getVideoHeight();
						current_state = mHTML5VideoView.getCurrentState();
                    }
                    //mHTML5VideoView.pauseAndDispatch(mCurrentProxy);
                    //mHTML5VideoView.release();
                }
				if(DebugFlags.WEB_HTML5)
					LOGD("enterfullscreenvideo "+" mCurrentProxy="+mCurrentProxy+" proxy="+proxy+
					"current_state="+current_state+" saveIsPlaying"+" layerId="+layerId);
				if(mInlineHtmlVideoView!=null&&mInlineHtmlVideoView.getVideoLayerId()!=layerId){
					if(DebugFlags.WEB_HTML5)
						LOGD("we delete the pre mInlineVieoview");
					mInlineHtmlVideoView.release();
					mCurrentProxy.dispatchReleaseInlineSurface();
					mInlineHtmlVideoView.deleteSurfaceTexture();
					mInlineHtmlVideoView = null;
				}
                mHTML5VideoView = new HTML5VideoFullScreen(proxy.getContext(),proxy,
                        layerId, savePosition, savedIsPlaying, width, height,current_state);
                mCurrentProxy = proxy;
                mHTML5VideoView.setVideoURI(url, mCurrentProxy);
                mHTML5VideoView.enterFullScreenVideoState(layerId, proxy, webView);
        }

        public static void exitFullScreenVideo(HTML5VideoViewProxy proxy,
                WebViewClassic webView) {
            if (!mHTML5VideoView.fullScreenExited() && mHTML5VideoView.isFullScreenMode()) {
				if(DebugFlags.WEB_HTML5)
					LOGD("exitFullScreenVideo");
                WebChromeClient client = webView.getWebChromeClient();
                if (client != null) {
                    client.onHideCustomView();
                }
            }
        	}
				public static boolean isFullScreenMode() {
			return mHTML5VideoView.isFullScreenMode();
		}
		public static void onVideoSizeChanged(int width, int height) {
			//if(DebugFlags.WEB_HTML5)
				LOGD("onVideoSizeChange width="+width+" height="+height);
			mHTML5VideoView.onVideoSizeChanged(width, height);
		}

        // This is on the UI thread.
        // When native tell Java to play, we need to check whether or not it is
        // still the same video by using videoLayerId and treat it differently.
        public static void play(String url, int time, HTML5VideoViewProxy proxy,
                WebChromeClient client, int videoLayerId) {
                if(DebugFlags.WEB_HTML5)
               		 LOGD("videoplayer play");
            int currentVideoLayerId = -1;
            boolean backFromFullScreenMode = false;
			int current_state = 0;
            if (mHTML5VideoView != null) {
                currentVideoLayerId = mHTML5VideoView.getVideoLayerId();
                backFromFullScreenMode = mHTML5VideoView.fullScreenExited();
				current_state = mHTML5VideoView.getCurrentState();
				if(DebugFlags.WEB_HTML5)
					LOGD("backFromFullScreenMode="+backFromFullScreenMode+" mHtml5VideoView="+mHTML5VideoView
						+"currentVideoLayerId="+currentVideoLayerId+"current_state="+current_state);
            }

            if (currentVideoLayerId != videoLayerId
                || mHTML5VideoView.surfaceTextureDeleted()) {
                if(DebugFlags.WEB_HTML5)
                	LOGD("normal mCurrentProxy="+mCurrentProxy+" proxy="+proxy+"currentVideolayerid="+currentVideoLayerId
					+" videoLayerId="+videoLayerId);
                if(currentVideoLayerId != videoLayerId && mHTML5VideoView!=null){
					//mHTML5VideoView.stopPlayback();
					mHTML5VideoView.release();
					mCurrentProxy.dispatchReleaseInlineSurface();
					mHTML5VideoView.deleteSurfaceTexture();
					//mCurrentProxy.dispatchReleaseMediaPlayer();
					if(DebugFlags.WEB_HTML5)
						LOGD("play videoid different release the mediaplayer mMediaPlayer="+mCurrentProxy.getMediaPlayer()+
						"inlinesurface="+mCurrentProxy.getInlineSurface());
					mHTML5VideoView = null;
					mInlineHtmlVideoView = null;
					current_state = HTML5VideoView.STATE_INITIALIZED;
				}
                mCurrentProxy = proxy;
				//LOGD("mMediaPlayer="+mMediaPlayer);
                mHTML5VideoView = new HTML5VideoInline(proxy,videoLayerId, time, false,current_state);
				mInlineHtmlVideoView = mHTML5VideoView;
                mHTML5VideoView.setVideoURI(url, mCurrentProxy);
                mHTML5VideoView.prepareDataAndDisplayMode(proxy);
            } else if (mCurrentProxy == proxy) {
            if(DebugFlags.WEB_HTML5)
            	LOGD("handle the case when keep playing whith one video");
                // Here, we handle the case when we keep playing with one video
                if (!mHTML5VideoView.isPlaying()) {
                    //mHTML5VideoView.seekTo(time);
                    mHTML5VideoView.start();
                }else{
                if(DebugFlags.WEB_HTML5)
                	LOGD("playing so cancel the playerbuffering");
					mHTML5VideoView.setPlayerBuffering(false);
				}
            } else if (mCurrentProxy != null) {
            if(DebugFlags.WEB_HTML5)
             	LOGD("mCurrentProxy !=null");
                // Some other video is already playing. Notify the caller that
                // its playback ended.
                proxy.dispatchOnEnded();
            }
        }

        public static boolean isPlaying(HTML5VideoViewProxy proxy) {
            return (mCurrentProxy == proxy && mHTML5VideoView != null
                    && mHTML5VideoView.isPlaying());
        }

        public static int getCurrentPosition() {
            int currentPosMs = 0;
            if (mHTML5VideoView != null) {
                currentPosMs = mHTML5VideoView.getCurrentPosition();
            }
            return currentPosMs;
        }

		public static int getDuration() {
			int durationMs = 0;
			if (mHTML5VideoView != null) {
				durationMs = mHTML5VideoView.getDuration();
			}
			return durationMs;
		}

        public static void seek(int time, HTML5VideoViewProxy proxy) {
            if (mCurrentProxy == proxy && time >= 0 && mHTML5VideoView != null) {
                mHTML5VideoView.seekTo(time);
            }
        }

        public static void pause(HTML5VideoViewProxy proxy) {
            if (mCurrentProxy == proxy && mHTML5VideoView != null) {
                mHTML5VideoView.pause();
            }
        }

        public static void onPrepared() {
			if(DebugFlags.WEB_HTML5)
				LOGD("videoPlayer onPrepared mBaseLayer="+mBaseLayer);
           // if (!mHTML5VideoView.isFullScreenMode() || mHTML5VideoView.getAutostart()) {
                mHTML5VideoView.start();
           // }
			
            if (mBaseLayer != 0) {
				LOGD("mCurrentProxy.getWebView().getBaseLayer()="+mCurrentProxy.getWebView().getBaseLayer());
                setBaseLayer(mCurrentProxy.getWebView().getBaseLayer());
            }
        }

        public static void end() {
			if(DebugFlags.WEB_HTML5)
				LOGD("videoPlayer end");
            if (mCurrentProxy != null) {
                if (isVideoSelfEnded)
                    mCurrentProxy.dispatchOnEnded();
                else
                    mCurrentProxy.dispatchOnPaused();
            }
            isVideoSelfEnded = false;
        }
    }

    // A bunch event listeners for our VideoView
    // MediaPlayer.OnPreparedListener
    public void onPrepared(MediaPlayer mp) {
    if(DebugFlags.WEB_HTML5)
    	LOGD("onPrepared mp="+mp+" dur="+mp.getDuration()+" width="+mp.getVideoWidth()+" height="+mp.getVideoHeight());
        VideoPlayer.onPrepared();
        Message msg = Message.obtain(mWebCoreHandler, PREPARED);
        Map<String, Object> map = new HashMap<String, Object>();
        if(mp.isPlaying()){
			if(DebugFlags.WEB_HTML5)
       			 LOGD("mediaplayer is playing");
              map.put("dur", new Integer(mp.getDuration()));  
       }else{
       		if(DebugFlags.WEB_HTML5)
               LOGD("mediaplayer can not get duration");
              map.put("dur", new Integer(0));
       }
        map.put("width", new Integer(mp.getVideoWidth()));
        map.put("height", new Integer(mp.getVideoHeight()));
        msg.obj = map;
        mWebCoreHandler.sendMessage(msg);
    }

    //MediaPlayer.OnVideoSizeChangedListener
    public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
    if(DebugFlags.WEB_HTML5)
  		LOGD("onvideoSizechanged width="+width+"height="+height+"threa.id="+Process.myTid());

		VideoPlayer.onVideoSizeChanged(width, height);

        Message msg = Message.obtain(mWebCoreHandler, SIZE_CHANGED);
        Map<String, Object> map = new HashMap<String, Object>();
	
        map.put("dur", new Integer(mHTML5VideoView.getDuration()));
        map.put("width", new Integer(width));
        map.put("height", new Integer(height));
        msg.obj = map;
        mWebCoreHandler.sendMessage(msg);
    }

    // MediaPlayer.OnCompletionListener;
    public void onCompletion(MediaPlayer mp) {
        // The video ended by itself, so we need to
        // send a message to the UI thread to dismiss
        // the video view and to return to the WebView.
        // arg1 == 1 means the video ends by itself.
        if(DebugFlags.WEB_HTML5)
       		 LOGD("onCompletion mp ="+mp);
		if(mHTML5VideoView!=null){
			if(DebugFlags.WEB_HTML5)
				LOGD("oncompletion we pause the video and audio and timer");
			
			mHTML5VideoView.pauseAndDispatch(this);
		}
        sendMessage(obtainMessage(ENDED, 1, 0));
    }

    // MediaPlayer.OnErrorListener
    public boolean onError(MediaPlayer mp, int what, int extra) {
        sendMessage(obtainMessage(ERROR));
        return false;
    }

    public void dispatchOnEnded() {
        Message msg = Message.obtain(mWebCoreHandler, ENDED);
        mWebCoreHandler.sendMessage(msg);
		if(DebugFlags.WEB_HTML5)
			LOGD("dispatchOnEnded we release the inline surface and mediaplayer");
		if(mInlineSurface!=null){
			mInlineSurface.release();
			if(DebugFlags.WEB_HTML5)
				LOGD("dispatchonEnded mInlineSurface="+mInlineSurface);
			mInlineSurface = null;
		}
		if(DebugFlags.WEB_HTML5)
			LOGD("dispatchonEnded mInlineSurface="+mInlineSurface+" mMediaPlayer="+mMediaPlayer);
		if(mMediaPlayer!=null){
			mMediaPlayer.release();
			mMediaPlayer = null;
			mHTML5VideoView.release();
		}
		mInlineHtmlVideoView = null;
    }

    public void dispatchOnPaused() {
		if(DebugFlags.WEB_HTML5)
			LOGD("dispatchOnPaused");
        Message msg = Message.obtain(mWebCoreHandler, PAUSED);
        mWebCoreHandler.sendMessage(msg);
    }

	public void dispatchUpdatePlaybackState(boolean paused){
		if(DebugFlags.WEB_HTML5)
			LOGD("dispatchupdateplaybackstate paused="+paused+"this="+this);
		if(mHTML5VideoView!=null){
			mHTML5VideoView.resumeTimer();
		}
		int arg1 = paused?1:0;
		 Message msg = Message.obtain(mWebCoreHandler, UPDATE_PLAYBACK_STATE,arg1,0);
        mWebCoreHandler.sendMessage(msg);
	}

	public void dispatchReleaseMediaPlayer(){
		if(DebugFlags.WEB_HTML5)
			LOGD("dispatchReleaseMediaplayer mMediaPlayer="+mMediaPlayer+"mHtml5VideoView="+mHTML5VideoView);
		if(mMediaPlayer!=null){
			mMediaPlayer.release();
			mMediaPlayer = null;
		}
		if(mHTML5VideoView!=null){
			if(DebugFlags.WEB_HTML5)
			  LOGD("deletesurfacetexture");
			mHTML5VideoView.deleteSurfaceTexture();
		}
	}

	public void dispatchReleaseInlineSurface(){
		if(DebugFlags.WEB_HTML5)
			LOGD("dispatchReleaseInlineSurface");
		if(mInlineSurface!=null){
			mInlineSurface.release();
			mInlineSurface = null;
		}
	}
    public void dispatchOnStopFullScreen() {
		if(DebugFlags.WEB_HTML5)
			LOGD("dispatchOnStopFullscreen");
        Message msg = Message.obtain(mWebCoreHandler, STOPFULLSCREEN);
        mWebCoreHandler.sendMessage(msg);
    }

    public void onTimeupdate() {
        sendMessage(obtainMessage(TIMEUPDATE));
    }

    // When there is a frame ready from surface texture, we should tell WebView
    // to refresh.
    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        // TODO: This should support partial invalidation too.
        mWebView.invalidate();
    }

    // Handler for the messages from WebCore or Timer thread to the UI thread.
    @Override
    public void handleMessage(Message msg) {
        // This executes on the UI thread.
        switch (msg.what) {
            case PLAY: {
				LOGD("PLAY");
                String url = (String) msg.obj;
                WebChromeClient client = mWebView.getWebChromeClient();
                int videoLayerID = msg.arg1;
                if (client != null) {
					//LOGD("HTML5VideoView", "VideoPlayer.play, mSeekPosition: " + mSeekPosition);
                    VideoPlayer.play(url, mSeekPosition, this, client, videoLayerID);
					if (mLastIsFullScreenMode == true) {
						//LOGD("html5", "enterFullScreenVideo(videoLayerID, url)");
					    enterFullScreenVideo(videoLayerID, url);
						mLastIsFullScreenMode = false;
				    }
                }
                break;
            }
			case NOTIFYURL:{
               LOGD("NOTIFYURL");
                   String url = (String) msg.obj;
                   mWebView.receivedVideoUrl(url);
                               break;
                }                
            case SEEK: {
                Integer time = (Integer) msg.obj;
                mSeekPosition = time;
                VideoPlayer.seek(mSeekPosition, this);
                break;
            }
            case PAUSE: {
                VideoPlayer.pause(this);
                break;
            }
            case ENDED:
					{
                if (msg.arg1 == 1){
                    VideoPlayer.isVideoSelfEnded = true;
                	}
				if(DebugFlags.WEB_HTML5)
					LOGD("ENDED VideoPlayer.isVideoSelfEnded="+VideoPlayer.isVideoSelfEnded);
				mLastIsFullScreenMode = VideoPlayer.isFullScreenMode();
				  WebChromeClient client = mWebView.getWebChromeClient();
				if (client != null && !mHTML5VideoView.fullScreenExited()) {
					client.onHideCustomView();
				}	
                VideoPlayer.end();
                break;
            	}
            case ERROR: {
                WebChromeClient client = mWebView.getWebChromeClient();
                if (client != null) {
                    client.onHideCustomView();
                }
                break;
            }
            case LOAD_DEFAULT_POSTER: {
                WebChromeClient client = mWebView.getWebChromeClient();
                if (client != null) {
                    doSetPoster(client.getDefaultVideoPoster());
                }
                break;
            }
            case TIMEUPDATE: {
                if (VideoPlayer.isPlaying(this)) {
					//LOGD("update time now playing ");
					//mHTML5VideoView.setPlayerBuffering(false);
                    mSeekPosition = VideoPlayer.getCurrentPosition();
                    sendTimeupdate();
                }
                break;
            }
            case BUFFERING_START: {
				if(DebugFlags.WEB_HTML5)
					LOGD("BUFFERING_STRAT");
                VideoPlayer.setPlayerBuffering(true);
				refreshLayer();
                break;
            }
            case BUFFERING_END: {
				if(DebugFlags.WEB_HTML5)
					LOGD("BUFFERING_END");
                VideoPlayer.setPlayerBuffering(false);
				refreshLayer();
                break;
            }
        }
    }

    // Everything below this comment executes on the WebCore thread, except for
    // the EventHandler methods, which are called on the network thread.

    // A helper class that knows how to download posters
    private static final class PosterDownloader implements EventHandler {
        // The request queue. This is static as we have one queue for all posters.
        private static RequestQueue mRequestQueue;
        private static int mQueueRefCount = 0;
        // The poster URL
        private URL mUrl;
        // The proxy we're doing this for.
        private final HTML5VideoViewProxy mProxy;
        // The poster bytes. We only touch this on the network thread.
        private ByteArrayOutputStream mPosterBytes;
        // The request handle. We only touch this on the WebCore thread.
        private RequestHandle mRequestHandle;
        // The response status code.
        private int mStatusCode;
        // The response headers.
        private Headers mHeaders;
        // The handler to handle messages on the WebCore thread.
        private Handler mHandler;

        public PosterDownloader(String url, HTML5VideoViewProxy proxy) {
            try {
                mUrl = new URL(url);
            } catch (MalformedURLException e) {
                mUrl = null;
            }
            mProxy = proxy;
            mHandler = new Handler();
        }
        // Start the download. Called on WebCore thread.
        public void start() {
            retainQueue();

            if (mUrl == null) {
                return;
            }

            // Only support downloading posters over http/https.
            // FIXME: Add support for other schemes. WebKit seems able to load
            // posters over other schemes e.g. file://, but gets the dimensions wrong.
            String protocol = mUrl.getProtocol();
            if ("http".equals(protocol) || "https".equals(protocol)) {
                mRequestHandle = mRequestQueue.queueRequest(mUrl.toString(), "GET", null,
                        this, null, 0);
            }
        }
        // Cancel the download if active and release the queue. Called on WebCore thread.
        public void cancelAndReleaseQueue() {
            if (mRequestHandle != null) {
                mRequestHandle.cancel();
                mRequestHandle = null;
            }
            releaseQueue();
        }
        // EventHandler methods. Executed on the network thread.
        public void status(int major_version,
                int minor_version,
                int code,
                String reason_phrase) {
            mStatusCode = code;
        }

        public void headers(Headers headers) {
            mHeaders = headers;
        }

        public void data(byte[] data, int len) {
            if (mPosterBytes == null) {
                mPosterBytes = new ByteArrayOutputStream();
            }
            mPosterBytes.write(data, 0, len);
        }

        public void endData() {
            if (mStatusCode == 200) {
                if (mPosterBytes.size() > 0) {
                    Bitmap poster = BitmapFactory.decodeByteArray(
                            mPosterBytes.toByteArray(), 0, mPosterBytes.size());
                    mProxy.doSetPoster(poster);
                }
                cleanup();
            } else if (mStatusCode >= 300 && mStatusCode < 400) {
                // We have a redirect.
                try {
                    mUrl = new URL(mHeaders.getLocation());
                } catch (MalformedURLException e) {
                    mUrl = null;
                }
                if (mUrl != null) {
                    mHandler.post(new Runnable() {
                       public void run() {
                           if (mRequestHandle != null) {
                               mRequestHandle.setupRedirect(mUrl.toString(), mStatusCode,
                                       new HashMap<String, String>());
                           }
                       }
                    });
                }
            }
        }

        public void certificate(SslCertificate certificate) {
            // Don't care.
        }

        public void error(int id, String description) {
            cleanup();
        }

        public boolean handleSslErrorRequest(SslError error) {
            // Don't care. If this happens, data() will never be called so
            // mPosterBytes will never be created, so no need to call cleanup.
            return false;
        }
        // Tears down the poster bytes stream. Called on network thread.
        private void cleanup() {
            if (mPosterBytes != null) {
                try {
                    mPosterBytes.close();
                } catch (IOException ignored) {
                    // Ignored.
                } finally {
                    mPosterBytes = null;
                }
            }
        }

        // Queue management methods. Called on WebCore thread.
        private void retainQueue() {
            if (mRequestQueue == null) {
                mRequestQueue = new RequestQueue(mProxy.getContext());
            }
            mQueueRefCount++;
        }

        private void releaseQueue() {
            if (mQueueRefCount == 0) {
                return;
            }
            if (--mQueueRefCount == 0) {
                mRequestQueue.shutdown();
                mRequestQueue = null;
            }
        }
    }

    /**
     * Private constructor.
     * @param webView is the WebView that hosts the video.
     * @param nativePtr is the C++ pointer to the MediaPlayerPrivate object.
     */
    private HTML5VideoViewProxy(WebViewClassic webView, int nativePtr) {
        // This handler is for the main (UI) thread.
        super(Looper.getMainLooper());
        // Save the WebView object.
        mWebView = webView;
		if(DebugFlags.WEB_HTML5)
			LOGD("HTML5VideoViewProxy contruct mMediaPlayer="+mMediaPlayer+"thread.id="+Process.myTid());
        // Pass Proxy into webview, such that every time we have a setBaseLayer
        // call, we tell this Proxy to call the native to update the layer tree
        // for the Video Layer's surface texture info
        mWebView.setHTML5VideoViewProxy(this);
        // Save the native ptr
        mNativePointer = nativePtr;
        // create the message handler for this thread
        createWebCoreHandler();
    }

	public Surface getInlineSurface(){
		if(DebugFlags.WEB_HTML5)
			LOGD("getInlineSurface mInlineSurface="+mInlineSurface);
		
		return mInlineSurface;
	}
	public void SetInlineSurface(Surface surface){
		if(DebugFlags.WEB_HTML5)
			LOGD("setInlineSurface surface="+surface);
		mInlineSurface  = surface;
	} 

	public boolean inlineSurfaceDeleted(){
		if(DebugFlags.WEB_HTML5)
			LOGD("inlineSurfaceDelete mInlineSurface="+mInlineSurface);
		if(mInlineSurface!=null){
			if(DebugFlags.WEB_HTML5)
				LOGD("inlineSurface isvalid="+mInlineSurface.isValid());
		}
		return mInlineSurface == null || !mInlineSurface.isValid()||mInlineHtmlVideoView == null || mInlineHtmlVideoView.surfaceTextureDeleted();
	}
	public void createInlineHtml5VideoView(HTML5VideoViewProxy proxy,int videoLayerId,int time){
		if(DebugFlags.WEB_HTML5)
			LOGD("createInlineHtml5VideoView videolayerid="+videoLayerId+" time="+time+" duration="+mHTML5VideoView.getDuration()+"mInlineHtmlView="+mInlineHtmlVideoView);
		if(inlineSurfaceDeleted()){
			mInlineHtmlVideoView = new HTML5VideoInline(proxy,videoLayerId, time, false,mHTML5VideoView.getCurrentState()); 
			mHTML5VideoView = mInlineHtmlVideoView;
			mHTML5VideoView.prepareDataAndDisplayMode(proxy);
			refreshLayer();
		}
	}
	public void setInlineVideoView(){
		if(DebugFlags.WEB_HTML5)
			LOGD("setInlineVideoView mInlineview= "+mInlineHtmlVideoView+"mHtml5="+mHTML5VideoView);
		mHTML5VideoView = mInlineHtmlVideoView;		
		refreshLayer();
	}

	public void refreshLayer(){
		if(DebugFlags.WEB_HTML5)
			LOGD("refreshLayer");
		//mWebView.mPrivateHandler.sendEmptyMessage(WebView.REFRESH_LAYERS);
	}
    private void createWebCoreHandler() {
        mWebCoreHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case PREPARED: {
                        Map<String, Object> map = (Map<String, Object>) msg.obj;
                        Integer duration = (Integer) map.get("dur");
                        Integer width = (Integer) map.get("width");
                        Integer height = (Integer) map.get("height");
                        nativeOnPrepared(duration.intValue(), width.intValue(),
                                height.intValue(), mNativePointer);
                        break;
                    }
					case SIZE_CHANGED: {
                        Map<String, Object> map = (Map<String, Object>) msg.obj;
                        Integer duration = (Integer) map.get("dur");
                        Integer width = (Integer) map.get("width");
                        Integer height = (Integer) map.get("height");
                        nativeOnPrepared(duration.intValue(), width.intValue(),
                                height.intValue(), mNativePointer);
                        break;
                    }
                    case ENDED:
						if(DebugFlags.WEB_HTML5)
							LOGD("ENDED");
                        mSeekPosition = 0;
                        nativeOnEnded(mNativePointer);
                        break;
                    case PAUSED:
						if(DebugFlags.WEB_HTML5)
							LOGD("PAUSED");
                        nativeOnPaused(mNativePointer);
                        break;
                    case POSTER_FETCHED:
                        Bitmap poster = (Bitmap) msg.obj;
                        nativeOnPosterFetched(poster, mNativePointer);
                        break;
                    case TIMEUPDATE:
                        nativeOnTimeupdate(msg.arg1, msg.arg2, mNativePointer);
                        break;
                    case STOPFULLSCREEN:
						if(DebugFlags.WEB_HTML5)
							LOGD("STOPFULLSCREEN");
                        nativeOnStopFullscreen(mNativePointer);
                        break;
					case UPDATE_PLAYBACK_STATE:
						if(DebugFlags.WEB_HTML5)
							LOGD("UPDATE_PLAYBACK_SATE");
						boolean paused = (msg.arg1==1)?true:false;
						nativeUpdatePlaybackState(mNativePointer,paused);
						break;
                }
            }
        };
    }

    private void doSetPoster(Bitmap poster) {
        if (poster == null) {
            return;
        }
        // Save a ref to the bitmap and send it over to the WebCore thread.
        mPoster = poster;
        Message msg = Message.obtain(mWebCoreHandler, POSTER_FETCHED);
        msg.obj = poster;
        mWebCoreHandler.sendMessage(msg);
    }

    private void sendTimeupdate() {
        Message msg = Message.obtain(mWebCoreHandler, TIMEUPDATE);
        msg.arg1 = VideoPlayer.getCurrentPosition();
		msg.arg2 = VideoPlayer.getDuration();
        mWebCoreHandler.sendMessage(msg);
    }

    public Context getContext() {
        return mWebView.getContext();
    }

    // The public methods below are all called from WebKit only.
    /**
     * Play a video stream.
     * @param url is the URL of the video stream.
     */
    public void play(String url, int position, int videoLayerID) {
    if(DebugFlags.WEB_HTML5)
   		 LOGD("play mWebView.isPaused()="+mWebView.isPaused());
     
	if(url == null || (mHTML5VideoView !=null &&mHTML5VideoView.isPlaying()&& mHTML5VideoView.getVideoLayerId()==videoLayerID)){
		LOGD("mediaplayer is playing so return");
			return ;
		}
        /*if (position > 0) {
            seek(position);
        }*/
        Message message = obtainMessage(PLAY);
        message.arg1 = videoLayerID;
        message.obj = url;
        sendMessage(message);
    }
	/**
	*
	*/
	public void notifyurl(String url){
		if(mWebView.getSettings().isPopupVideoEnable()){
				LOGD("notifyurl="+url);
				Message message = obtainMessage(NOTIFYURL);
				message.obj = url;
				sendMessage(message);
			}
		
	}
    /**
     * Seek into the video stream.
     * @param  time is the position in the video stream.
     */
    public void seek(int time) {
    if(DebugFlags.WEB_HTML5)
		LOGD("seek time="+time);
	
        Message message = obtainMessage(SEEK);
        message.obj = new Integer(time);
        sendMessage(message);
    }

    /**
     * Pause the playback.
     */
    public void pause() {
    if(DebugFlags.WEB_HTML5)
    	LOGD("pause");
	
	/*
	 try {
			throw new Exception("pase");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	*/
        Message message = obtainMessage(PAUSE);
        sendMessage(message);
    }

    /**
     * Tear down this proxy object.
     */
    public void teardown() {
        // This is called by the C++ MediaPlayerPrivate dtor.
        // Cancel any active poster download.
        LOGD("teardown");
        if (mPosterDownloader != null) {
            mPosterDownloader.cancelAndReleaseQueue();
        }
		VideoPlayer.mVideoSurfaceTexture = null;
        if(mHTML5VideoView != null) {
			mHTML5VideoView.cancelTimer();
			mHTML5VideoView.release();
        }
        mNativePointer = 0;
    }

    /**
     * Load the poster image.
     * @param url is the URL of the poster image.
     */
    public void loadPoster(String url) {
        if (url == null) {
            Message message = obtainMessage(LOAD_DEFAULT_POSTER);
            sendMessage(message);
            return;
        }
        // Cancel any active poster download.
        if (mPosterDownloader != null) {
            mPosterDownloader.cancelAndReleaseQueue();
        }
        // Load the poster asynchronously
        mPosterDownloader = new PosterDownloader(url, this);
        mPosterDownloader.start();
    }

    // These three function are called from UI thread only by WebView.
    public void setBaseLayer(int layer) {
        VideoPlayer.setBaseLayer(layer);
    }

    public void pauseAndDispatch() {
        VideoPlayer.pauseAndDispatch();
    }

    public void enterFullScreenVideo(int layerId, String url) {
		if(DebugFlags.WEB_HTML5)
			LOGD("-------------------enterFullScreenVideo-----------------------mMediaplayer="+mMediaPlayer);

		VideoPlayer.enterFullScreenVideo(layerId, url, this, mWebView);
    }

    public void exitFullScreenVideo() {
        VideoPlayer.exitFullScreenVideo(this, mWebView);
    }

    /**
     * The factory for HTML5VideoViewProxy instances.
     * @param webViewCore is the WebViewCore that is requesting the proxy.
     *
     * @return a new HTML5VideoViewProxy object.
     */
    public static HTML5VideoViewProxy getInstance(WebViewCore webViewCore, int nativePtr) {
        return new HTML5VideoViewProxy(webViewCore.getWebViewClassic(), nativePtr);
    }

    /* package */ WebViewClassic getWebView() {
        return mWebView;
    }

	public  MediaPlayer createMediaPlayer(){
		if(mMediaPlayer == null){
			LOGD("now we need a mediaplayer(use by inline or fullscreen mode)");
			mMediaPlayer = new MediaPlayer();
		}
		return mMediaPlayer;
	}
	public MediaPlayer getMediaPlayer(){
		return mMediaPlayer;
	}
    private native void nativeOnPrepared(int duration, int width, int height, int nativePointer);
    private native void nativeOnEnded(int nativePointer);
    private native void nativeOnPaused(int nativePointer);
	private native void nativeUpdatePlaybackState(int nativePointer,boolean paused);
    private native void nativeOnPosterFetched(Bitmap poster, int nativePointer);
    private native void nativeOnTimeupdate(int position, int duration, int nativePointer);
    private native void nativeOnStopFullscreen(int nativePointer);
    private native void nativeOnRestoreState(int nativePointer);
    private native static boolean nativeSendSurfaceTexture(SurfaceTexture texture,
            int baseLayer, int videoLayerId, int textureName,
            int playerState);

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
    if(DebugFlags.WEB_HTML5)
    	LOGD("onInfo what="+what+" extra ="+extra);
        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
            sendMessage(obtainMessage(BUFFERING_START, what, extra));
        } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
            sendMessage(obtainMessage(BUFFERING_END, what, extra));
        }
        return false;
    }
}
