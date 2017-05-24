
package android.webkit;

import android.Manifest.permission;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.webkit.HTML5VideoView;
import android.webkit.HTML5VideoViewProxy;
import android.view.Surface;
import android.opengl.GLES20;
import android.os.PowerManager;
import android.util.Log;

/**
 * @hide This is only used by the browser
 */
public class HTML5VideoInline extends HTML5VideoView{

    // Due to the fact that the decoder consume a lot of memory, we make the
    // surface texture as singleton. But the GL texture (m_textureNames)
    // associated with the surface texture can be used for showing the screen
    // shot when paused, so they are not singleton.
    private static SurfaceTexture mSurfaceTexture = null;
    private int[] mTextureNames;
    // Every time when the VideoLayer Id change, we need to recreate the
    // SurfaceTexture in order to delete the old video's decoder memory.
    private static int mVideoLayerUsingSurfaceTexture = -1;
	private static String LOG = "HTML5VideoInline";
	public static void LOGD(String msg){
			Log.d(LOG,msg);
	}

    // Video control FUNCTIONS:
    @Override
    public void start() {
        if (!getPauseDuringPreparing()) {
            super.start();
        }
    }

    HTML5VideoInline(HTML5VideoViewProxy proxy ,int videoLayerId, int position,
            boolean autoStart,int current_state) {
           // LOGD("mMediaPlayer="+mMediaPlayer);
        init(proxy,videoLayerId, position, autoStart,current_state);
        mTextureNames = null;
    }

    @Override
    public void decideDisplayMode() {
        SurfaceTexture surfaceTexture = getSurfaceTexture(getVideoLayerId());
        Surface surface = new Surface(surfaceTexture);
		if(DebugFlags.WEB_HTML5)
			LOGD("decideDisplayMode surface="+surface+" surfacetexture="+surfaceTexture);
		
		mProxy.SetInlineSurface(surface);
        mPlayer.setSurface(surface);
        //surface.release();
    }

    // Normally called immediately after setVideoURI. But for full screen,
    // this should be after surface holder created
    @Override
    public void prepareDataAndDisplayMode(HTML5VideoViewProxy proxy) {
        super.prepareDataAndDisplayMode(proxy);
        setFrameAvailableListener(proxy);
        // TODO: This is a workaround, after b/5375681 fixed, we should switch
        // to the better way.
        if (mProxy.getContext().checkCallingOrSelfPermission(permission.WAKE_LOCK)
                == PackageManager.PERMISSION_GRANTED) {
            mPlayer.setWakeMode(proxy.getContext(), PowerManager.FULL_WAKE_LOCK);
        }
    }

    // Pause the play and update the play/pause button
    @Override
    public void pauseAndDispatch(HTML5VideoViewProxy proxy) {
        super.pauseAndDispatch(proxy);
    }

    // Inline Video specific FUNCTIONS:

    @Override
    public SurfaceTexture getSurfaceTexture(int videoLayerId) {
        // Create the surface texture.
        if (videoLayerId != mVideoLayerUsingSurfaceTexture
            || mSurfaceTexture == null
            || mTextureNames == null) {
            if (mTextureNames != null) {
                GLES20.glDeleteTextures(1, mTextureNames, 0);
            }
			
            mTextureNames = new int[1];
            GLES20.glGenTextures(1, mTextureNames, 0);
            mSurfaceTexture = new SurfaceTexture(mTextureNames[0]);
			//LOGD("create new SurfaceTexture mSurfaceTexture="+mSurfaceTexture);
        }
		//LOGD("return mSurfaceTexture="+mSurfaceTexture);
        mVideoLayerUsingSurfaceTexture = videoLayerId;
        return mSurfaceTexture;
    }

    public boolean surfaceTextureDeleted() {
        return (mSurfaceTexture == null);
    }

    @Override
    public void deleteSurfaceTexture() {
        cleanupSurfaceTexture();
		mProxy.dispatchReleaseInlineSurface();
        return;
    }

    public static void cleanupSurfaceTexture() {
		if(DebugFlags.WEB_HTML5)
			LOGD("clearupSurfaceTexture");
        mSurfaceTexture = null;
        mVideoLayerUsingSurfaceTexture = -1;
			
        return;
    }

    @Override
    public int getTextureName() {
        if (mTextureNames != null) {
            return mTextureNames[0];
        } else {
            return 0;
        }
    }

    private void setFrameAvailableListener(SurfaceTexture.OnFrameAvailableListener l) {
        mSurfaceTexture.setOnFrameAvailableListener(l);
    }

}
