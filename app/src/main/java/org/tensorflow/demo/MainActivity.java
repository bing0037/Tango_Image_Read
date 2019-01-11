package org.tensorflow.demo;

import android.app.Activity;
import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.media.ImageReader;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.os.Trace;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Display;
import android.widget.Toast;
import android.view.View;
import android.speech.tts.TextToSpeech;

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.Tango.OnTangoUpdateListener;
import com.google.atap.tangoservice.Tango.TangoUpdateCallback;
import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoInvalidException;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPointCloudData;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;
import com.google.atap.tangoservice.experimental.TangoImageBuffer;
import com.google.tango.depthinterpolation.TangoDepthInterpolation;
import com.google.tango.support.TangoPointCloudManager;
import com.google.tango.support.TangoSupport;
import com.google.tango.transformhelpers.TangoTransformHelper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.lang.Math;
import java.util.Locale;

//import org.tensorflow.demo.OverlayView.DrawCallback;
import org.json.JSONException;
import org.json.JSONObject;
import org.tensorflow.demo.env.BorderedText;
import org.tensorflow.demo.env.ImageUtils;
import org.tensorflow.demo.env.Logger;
import org.tensorflow.demo.R;

/**
 * Created by manjekarbudhai on 7/27/17.
 */

public class MainActivity extends CameraActivity  {

    private Tango tango_;
    private TangoConfig tangoConfig_;
    private volatile boolean tangoConnected_ = false;
    private TangoPointCloudManager mPointCloudManager;
    HashMap<Integer, Integer> cameraTextures_ = null;
    private GLSurfaceView view_;
    private Renderer renderer_;
    private volatile TangoImageBuffer mCurrentImageBuffer;
    private int mDisplayRotation = 0;
    private Matrix rgbImageToDepthImage;
    TextToSpeech tts1;

    private TangoPointCloudData PointCloudData;
    private Bitmap rgbFrameBitmap = null;

    private class MeasuredPoint {
        public double mTimestamp;
        public float[] mDepthTPoint;

        public MeasuredPoint(double timestamp, float[] depthTPoint) {
            mTimestamp = timestamp;
            mDepthTPoint = depthTPoint;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState){
        // GLSurfaceView for RGB color camera

        tts1=new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if(status != TextToSpeech.ERROR) {
                    tts1.setLanguage(Locale.UK);
                }
            }
        });


        rgbImageToDepthImage = ImageUtils.getTransformationMatrix(
                320, 240,
                1920, 1080,
                0, true);

        super.onCreate(savedInstanceState);

        cameraTextures_ = new HashMap<>();
        mPointCloudManager = new TangoPointCloudManager();

        // Request depth in the Tango config because otherwise frames
        // are not delivered.
        tango_ = new Tango(this, new Runnable(){
            @Override
            public void run(){
                synchronized (this) {
                    try {
                        tangoConfig_ = tango_.getConfig(TangoConfig.CONFIG_TYPE_DEFAULT);
                        tangoConfig_.putBoolean(TangoConfig.KEY_BOOLEAN_COLORCAMERA, true);
                        tangoConfig_.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
                        tangoConfig_.putInt(TangoConfig.KEY_INT_DEPTH_MODE, TangoConfig.TANGO_DEPTH_MODE_POINT_CLOUD);
                        tango_.connect(tangoConfig_);
                        startTango();
                        TangoSupport.initialize(tango_);
                        //cameraTextures_ = new HashMap<>();

                    } catch (TangoOutOfDateException e) {
                        Log.i("new Tango", "error in onCreate");
                    }
                }
            }
        });

        try {
            TimeUnit.SECONDS.sleep(2);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        cameraTextures_ = new HashMap<>();
        view_ = (GLSurfaceView)findViewById(R.id.surfaceviewclass);
        view_.setEGLContextClientVersion(2);
        view_.setDebugFlags(GLSurfaceView.DEBUG_CHECK_GL_ERROR);
        view_.setRenderer(renderer_ = new Renderer(this));
        view_.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        new Thread(new RunDetection()).start();

    }

    @Override
    public void onStart(){
        Log.i("onStart " , "Main onStart");
        super.onStart();
    }

    @Override
    public void onResume() {
        Log.i("onResume ", "Main onResume called");
        super.onResume();
        //startTango();
        if (tango_ == null) {
            startActivityForResult(
                    Tango.getRequestPermissionIntent(Tango.PERMISSIONTYPE_MOTION_TRACKING),
                    Tango.TANGO_INTENT_ACTIVITYCODE);


            tango_ = new Tango(this, new Runnable() {

                @Override
                public void run() {
                    Log.i("onResume ", "new tango");
                    synchronized (this) {
                        try {
                            tangoConfig_ = tango_.getConfig(TangoConfig.CONFIG_TYPE_DEFAULT);
                            tangoConfig_.putBoolean(TangoConfig.KEY_BOOLEAN_COLORCAMERA, true);
                            tangoConfig_.putBoolean(TangoConfig.KEY_BOOLEAN_AUTORECOVERY, true);
                            tango_.connect(tangoConfig_);
                            startTango();
                            TangoSupport.initialize(tango_);
                            //cameraTextures_ = new HashMap<>();

                        } catch (TangoOutOfDateException e) {
                            Log.i("new Tango", "error in onCreate");
                        }
                    }
                }
            });
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        /*if(tts1 !=null){
            tts1.stop();
            tts1.shutdown();
        }*/
        synchronized (this) {
            try {
                if (tango_ != null) {
                    tango_.disconnect();
                    tangoConnected_ = false;
                }
            }
            catch (TangoErrorException e) {
                Toast.makeText(
                        this,
                        "Tango error: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public synchronized void onStop() {
        Log.i("onStop " , "Main onStop");
        super.onStop();
    }

    public synchronized void attachTexture(final int cameraId, final int textureName) {
        if (textureName > 0) {
            // Link the texture with Tango if the texture changes after
            // Tango is connected. This generally doesn't happen but
            // technically could because they happen in separate
            // threads. Otherwise the link will be made in startTango().
            if(cameraTextures_ != null && tango_ != null) {
                if (tangoConnected_ && cameraTextures_.get(cameraId) != textureName)
                    tango_.connectTextureId(cameraId, textureName);
                cameraTextures_.put(cameraId, textureName);
            }
        }
        else
            cameraTextures_.remove(cameraId);
    }

    public synchronized void updateTexture(int cameraId) {
        if (tangoConnected_) {
            try {
                tango_.updateTexture(cameraId);
            }
            catch (TangoInvalidException e) {
                e.printStackTrace();
            }
        }
    }

    public Point getCameraFrameSize(int cameraId) {
        // TangoCameraIntrinsics intrinsics = mTango.getCameraIntrinsics(cameraId);
        // return new Point(intrinsics.width, intrinsics.height);
        return new Point(320, 240);
        //   return new Point(1280, 720);
    }

    private void startTango() {
        try {

            tangoConnected_ = true;
            Log.i("startTango", "Tango Connected");

            Display display = getWindowManager().getDefaultDisplay();
            mDisplayRotation = display.getRotation();

            // Attach cameras to textures.
            synchronized(this) {
                for (Map.Entry<Integer, Integer> entry : cameraTextures_.entrySet())
                    tango_.connectTextureId(entry.getKey(), entry.getValue());
            }

            // Attach Tango listener.
            ArrayList<TangoCoordinateFramePair> framePairs = new ArrayList<>();
            framePairs.add(new TangoCoordinateFramePair(
                    TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                    TangoPoseData.COORDINATE_FRAME_DEVICE));
            tango_.connectListener(framePairs, new Tango.TangoUpdateCallback(){
                @Override
                public void onPointCloudAvailable(TangoPointCloudData pointCloud) {
                    mPointCloudManager.updatePointCloud(pointCloud);
                    PointCloudData = pointCloud;
                    int size = PointCloudData.numPoints;
                    FloatBuffer floatBuffer = PointCloudData.points;
                    floatBuffer.rewind();
//                    Log.w("Tango_depth","I get Depth Data!");
//                    Log.w("Tango_depth","PointCloud data available!");
//                    Log.w("Tango_depth",String.format("PointCloud data size = %d", size));
                }

                @Override
                public void onPoseAvailable(TangoPoseData tangoPoseData) {
                }

                @Override
                public void onXyzIjAvailable(TangoXyzIjData tangoXyzIjData) {
                }
                @Override
                public void onTangoEvent(TangoEvent tangoEvent) {
                    //Log.i("TangoEvent", String.format("%s: %s", tangoEvent.eventKey, tangoEvent.eventValue));
                }
                @Override
                public void onFrameAvailable(int i) {
                    //Log.i("onFrameAvailabe", "Main onFrameAvailabe called");
                    if (i == TangoCameraIntrinsics.TANGO_CAMERA_COLOR) {
                        // mColorCameraPreview.onFrameAvailable();
                        view_.requestRender();
                        if(renderer_.argbInt != null){
                            // get rgb raw data: -libn
                            int width = 320;
                            int height = 240;
                            rgbFrameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                            rgbFrameBitmap.setPixels(renderer_.argbInt, 0, width, 0, 0, width, height);
//                            ImageUtils.saveBitmap(rgbFrameBitmap,"rgbFrameBitmap_in_renderer.png");
//                            Log.w("Tango_depth","I get Camera Data.");

                        }
                    }
                }
            });

            tango_.experimentalConnectOnFrameListener(TangoCameraIntrinsics.TANGO_CAMERA_COLOR,
                    new Tango.OnFrameAvailableListener() {
                        @Override
                        public void onFrameAvailable(TangoImageBuffer tangoImageBuffer, int i) {
                            mCurrentImageBuffer = copyImageBuffer(tangoImageBuffer);
                            // Log.i("onFrame",String.format("Tango Image Size: %dx%d",
                            //     mCurrentImageBuffer.width,mCurrentImageBuffer.height));
                        }

                        TangoImageBuffer copyImageBuffer(TangoImageBuffer imageBuffer) {
                            ByteBuffer clone = ByteBuffer.allocateDirect(imageBuffer.data.capacity());
                            imageBuffer.data.rewind();
                            clone.put(imageBuffer.data);
                            imageBuffer.data.rewind();
                            clone.flip();
                            return new TangoImageBuffer(imageBuffer.width, imageBuffer.height,
                                    imageBuffer.stride, imageBuffer.frameNumber,
                                    imageBuffer.timestamp, imageBuffer.format, clone,
                                    imageBuffer.exposureDurationNs);
                        }
                    });
        }
        catch (TangoOutOfDateException e) {
            Toast.makeText(
                    this,
                    "TangoCore update required",
                    Toast.LENGTH_SHORT).show();
        }
        catch (TangoErrorException e) {
            Toast.makeText(
                    this,
                    "Tango error: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private  void stopTango() {
        try {
            if (tangoConnected_) {
                tango_.disconnect();
                tangoConnected_ = false;
            }
        }
        catch (TangoErrorException e) {
            Toast.makeText(
                    this,
                    "Tango error: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    public class RunDetection implements Runnable{
        @Override
        public void run(){
            final int  sleepShort = 5;
            while(true) {
                try {
                    if(tangoConnected_ == false){
                        Thread.sleep(sleepShort);
                        continue;
                    }
                    if (null != renderer_.argbInt) {

//                        // main loop! -libn
//                        // get rgb raw data: -libn
//                        Bitmap rgbFrameBitmap = null;
//                        rgbFrameBitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
//                        rgbFrameBitmap.setPixels(renderer_.argbInt, 0, 640, 0, 0, 640, 480);
//                        ImageUtils.saveBitmap(rgbFrameBitmap,"rgbFrameBitmap_in_main_manjeka.png");

                        // send PointCloud data to server. -libn
                        sendPointCloudData();



                    } else {
                        Thread.sleep(sleepShort);
                    }
                }catch(Exception e){
                    System.out.println(e);
                }
            }
        }
    }


    public boolean getOrientationDir(PointF bbox_in){
        boolean isClockwise = false;
        float adjacent = 320.0f - 640.0f*bbox_in.x;
        if(adjacent < 0.f){
            isClockwise = true;
        }
        else{
            isClockwise = false;
        }
        return isClockwise;
    }

    public double getOrientationVal(PointF bbox_in){
        double orientation = 0;
        double adjacent = (double)(480.0f - 480.0f*bbox_in.y);
        double opposite = (double)(Math.abs((320.0f - 640.0f*bbox_in.x)));
        orientation = Math.toDegrees(Math.atan(opposite/adjacent));
        return orientation;
    }


    // send PointCloud data to server. -libn
    protected Boolean sendPointCloudData() {

        // test speed. -libn
        Log.w("Tango_depth","Sending Data!!!");
        HttpURLConnection urlConnection = null;
        JSONObject jsonObject = new JSONObject();
        // 1) package the data in JSON form using jsonObject.put(). -libn
        JSONObject data = getJSONObject();
        try{
            // 2) add some other informations in JSON form using jsonObject.put(). -libn
            jsonObject.put("main_body",data);
//                Log.d("Debug", String.valueOf(3));
        } catch(JSONException e){
            Log.e("makeRequest","JSON Exception");
            return false;
        }
        try{
            // 3) configure the connection. -libn
            URL url = new URL("http","192.168.2.5",8080,"jsontttt");
            urlConnection = (HttpURLConnection) url.openConnection();
            Log.v("makeRequest", "Made connection to " + url.toString());
//            publishProgress(10);
            urlConnection.setDoOutput(true);
            urlConnection.setDoInput(true);
            urlConnection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            urlConnection.setRequestProperty("Accept", "application/json");
            urlConnection.setRequestMethod("POST");
//            publishProgress(15);
            OutputStreamWriter os = new OutputStreamWriter(urlConnection.getOutputStream());
            os.write(jsonObject.toString());
            os.close();
            Log.w("Tango_depth","Sending data to server.");

            // 4) get response. -libn
//            publishProgress(25);
            StringBuilder sb = new StringBuilder();
            int HttpResult = urlConnection.getResponseCode();
            Log.w("Tango_depth", String.format("HttpResultCode = %d", HttpResult));
//            publishProgress(75);
            if (HttpResult == HttpURLConnection.HTTP_OK) {
                BufferedReader br = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), "UTF-8"));
                String line = null;
                int count = 0;
                while ((line = br.readLine()) != null) {
                    count++;
                    if(count%1000==0){
//                        publishProgress(75+25*count/60000);
                    }
                    sb.append(line);
                    sb.append("\n");
                }
                br.close();
                // 5) save the response from server. -libn
                File f = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/objects");
                if (!f.exists()) {
                    f.mkdirs();
                }
                final File file = new File(f,"test_libn_2.txt");
                OutputStream outputfilestream = new FileOutputStream(file);
                outputfilestream.write(sb.toString().getBytes());
                outputfilestream.close();

                Log.w("Tango_depth","Object file saved!");

            } else {
                System.out.println(urlConnection.getResponseMessage());
            }
        } catch(MalformedURLException e){
            Log.e("makeRequest","MalformedURLException");
        } catch(IOException e){
            e.printStackTrace();
        }
        return true;
    }
//
//    // 6) do something after getting response from server. -libn
//    @Override
//    protected void onPostExecute(Boolean aBoolean) {
////            Toast.makeText(MainMenu.this,"Saved",Toast.LENGTH_LONG).show();
////            d.dismiss();
////            AlertDialog.Builder a = new AlertDialog.Builder(MainMenu.this);
////            a.setMessage("Open File?");
////            a.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
////                @Override
////                public void onClick(DialogInterface dialog, int which) {
////                    openFile();
////                }
////            });
////            a.setNegativeButton("No", new DialogInterface.OnClickListener() {
////                @Override
////                public void onClick(DialogInterface dialog, int which) {
////                    dialog.cancel();
////                }
////            });
////            a.show();
//    }

//    @Override
//    protected void onProgressUpdate(Integer... values) {
////            d.setProgress(values[0]);
////            switch(values[0]){
////                case 0:
////                    d.setMessage("Making Connection");
////                    break;
////                case 15:
////                    d.setMessage("Posting Data");
////                    break;
////                case 25:
////                    d.setMessage("Waiting for Server to Parse Data");
////                    break;
////                case 75:
////                    d.setMessage("Writing File");
////                    break;
////            }
////            if(values[0]==75){
////                d.setMessage("Receiving File");
////            }
//    }

    // package the data in JSON form. -libn
    private JSONObject getJSONObject(){

//        // 1 send file to server: ~1.25Hz
//        String files[]={"test_libn_2.pcd"};
//        JSONObject jsonObject = new JSONObject();
//        for(String f: files) {
//            StringBuilder text = new StringBuilder();
//            File file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/pointclouds/" + f);
//            try {
//                BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
//                String line;
//                while ((line = bufferedReader.readLine()) != null) {
//                    text.append(line);
//                    text.append('\n');
//                }
//                bufferedReader.close();
//            } catch (IOException e) {
//                Log.e("getJSONObject", "IO Exception");
//            }
//            try {
////                if(f.equals(frontFileName + ".pcd"))
//                jsonObject.put("front", text.toString());
////                else if (f.equals(backFileName + ".pcd"))
////                    jsonObject.put("back", text.toString());
//            } catch(JSONException e){
//                Log.e("getJSONObject","JSON Exception");
//            }
//        }

        // 2 send data to server: ~1.5Hz
        int size = PointCloudData.numPoints;
        FloatBuffer floatBuffer = PointCloudData.points;
        float[] points = new float[size * 3];

        floatBuffer.get(points);
        floatBuffer.rewind();
        JSONObject jsonObject = new JSONObject();
        try {

            jsonObject.put("front", Arrays.toString(points)); // time delay: toString(): ~0.3s; sending data: ~0.3s

//            jsonObject.put("Bitmap", rgbFrameBitmap.toString()); // time delay: toString(): ~0.3s; sending data: ~0.3s
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            rgbFrameBitmap.compress(Bitmap.CompressFormat.JPEG, 100,stream);
            byte[] byteArray = stream.toByteArray();
            Log.w("Tango_depth",String.format("byteArray.length = %d",byteArray.length));
//            jsonObject.put("Bitmap", byteArray.toString()); // time delay: toString(): ~0.3s; sending data: ~0.3s
            jsonObject.put("Bitmap", stream.toString()); // time delay: toString(): ~0.3s; sending data: ~0.3s

//            // test transformation delay. -libn
//            Arrays.toString(points);    // time delay: ~0.3s
//            jsonObject.put("front", "aaa");
//                else if (f.equals(backFileName + ".pcd"))
//                    jsonObject.put("back", text.toString());
        } catch(JSONException e){
            Log.e("getJSONObject","JSON Exception");
        }

//        for(String f: files) {
////            StringBuilder text = new StringBuilder();
////            File file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/pointclouds/" + f);
////            try {
////                BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
////                String line;
////                while ((line = bufferedReader.readLine()) != null) {
////                    text.append(line);
////                    text.append('\n');
////                }
////                bufferedReader.close();
////            } catch (IOException e) {
////                Log.e("getJSONObject", "IO Exception");
////            }
//
//        }

        // 3 send std_dev to server:
//        try {
//            jsonObject.put("std_dev", 3);
//        } catch (JSONException e){
//            e.printStackTrace();
//        }

        return jsonObject;
    }

//    public boolean isOnline() {
//        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
//        NetworkInfo netInfo = cm.getActiveNetworkInfo();
//        return netInfo != null && netInfo.isConnected();
//    }

}


