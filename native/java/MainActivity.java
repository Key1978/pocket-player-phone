package com.pocketplayer.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Plugins must be registered before the bridge starts.
        registerPlugin(PocketNativePlugin.class);
        super.onCreate(savedInstanceState);

        WebView webView = webView();
        if (webView != null) {
            // Lets the next track start on its own while the screen is off.
            webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        }

        // Radio and podcast streams are passed through the app (see StreamProxy).
        if (getBridge() != null) {
            getBridge().setWebViewClient(new BridgeWebViewClient(getBridge()) {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    Uri u = request.getUrl();
                    if (u != null && "localhost".equals(u.getHost()) && "/_pp/stream".equals(u.getPath())) {
                        return StreamProxy.open(u.getQueryParameter("u"), request.getRequestHeaders());
                    }
                    return super.shouldInterceptRequest(view, request);
                }
            });
        }

        // Back button sends the app to the background instead of closing it, so music keeps playing.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                WebView wv = webView();
                if (wv != null && wv.canGoBack()) {
                    wv.goBack();
                } else {
                    moveTaskToBack(true);
                }
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        keepWebViewRunning();
    }

    @Override
    public void onStop() {
        super.onStop();
        keepWebViewRunning();
    }

    @Override
    public void onDestroy() {
        try {
            stopService(new Intent(this, PlaybackService.class));
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    private WebView webView() {
        return getBridge() != null ? getBridge().getWebView() : null;
    }

    /** Playback and the hearing boost run inside the web view, so it must not be paused in the background. */
    private void keepWebViewRunning() {
        WebView wv = webView();
        if (wv != null) {
            wv.onResume();
            wv.resumeTimers();
        }
    }
}
