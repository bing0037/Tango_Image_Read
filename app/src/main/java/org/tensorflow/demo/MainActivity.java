package org.tensorflow.demo;

import android.app.Activity;
import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
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
import android.util.Base64;
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
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
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
import java.text.DecimalFormat;
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

    private int image_width = 320;
    private int image_height = 240;
    private Bitmap rgbFrameBitmap = null;
    private String imageEncoded;

    private TangoPointCloudData PointCloudData;
    private float[] points_rough;

    private String fileContent = "";    // read external file. -libn

    // get the position(x,y) of the pixel.
    public List<PointF> rectDepthxy = new LinkedList<PointF>();

    private int voice_count = 0;


    private class MeasuredPoint {
        public double mTimestamp;
        public float[] mDepthTPoint;

        public MeasuredPoint(double timestamp, float[] depthTPoint) {
            mTimestamp = timestamp;
            mDepthTPoint = depthTPoint;
        }
    }

    public MeasuredPoint getBboxDepth(float u, float v) {
        TangoPointCloudData pointCloud = mPointCloudManager.getLatestPointCloud();
        if (pointCloud == null) {
            return null;
        }

        double rgbTimestamp;
        TangoImageBuffer imageBuffer = mCurrentImageBuffer;
        rgbTimestamp = imageBuffer.timestamp;

        TangoPoseData depthlTcolorPose = TangoSupport.getPoseAtTime(
                rgbTimestamp,
                TangoPoseData.COORDINATE_FRAME_CAMERA_DEPTH,
                TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR,
                TangoSupport.ENGINE_TANGO,
                TangoSupport.ENGINE_TANGO,
                TangoSupport.ROTATION_IGNORED);
        if (depthlTcolorPose.statusCode != TangoPoseData.POSE_VALID) {
            Log.w("getdepthBbox", "Could not get color camera transform at time "
                    + rgbTimestamp);
            return null;
        }

        float[] depthPoint;


        depthPoint = TangoDepthInterpolation.getDepthAtPointBilateral(
                pointCloud,
                new double[] {0.0, 0.0, 0.0},
                new double[] {0.0, 0.0, 0.0, 1.0},
                imageBuffer,
                u, v,
                mDisplayRotation,
                depthlTcolorPose.translation,
                depthlTcolorPose.rotation);

        if (depthPoint == null) {
            Log.i("getBboxDepth()", "depth is null");
            return null;
        }
        //Log.i("getBboxDepth()", String.format("x:%f, y:%f, z:%f",depthPoint[0],depthPoint[1],depthPoint[2]));
        //tts1.speak("Depth detected",TextToSpeech.QUEUE_ADD,null,"Detected");
        return new MeasuredPoint(rgbTimestamp, depthPoint);
    }

    @Override
    public void onCreate(Bundle savedInstanceState){
        // GLSurfaceView for RGB color camera

        // read external file to get server IP. -libn
        File extStore = Environment.getExternalStorageDirectory();
        String path = extStore.getAbsolutePath() + "/Tango_Image_Read/Server_IP.txt";
        String s = "";

        try {
            File myFile = new File(path);
            FileInputStream fIn = new FileInputStream(myFile);
            BufferedReader myReader = new BufferedReader(new InputStreamReader(fIn));
            s = myReader.readLine();    // read one line. -libn
            fileContent += s;
            myReader.close();
            Log.w("Tango_depth",fileContent);

        } catch (IOException e) {
            e.printStackTrace();
        }


        tts1=new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if(status != TextToSpeech.ERROR) {
                    tts1.setLanguage(Locale.UK);
                }
            }
        });


        rgbImageToDepthImage = ImageUtils.getTransformationMatrix(
                image_width, image_height,
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
        return new Point(image_width, image_height);
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

                    // reduce accuracy of pointcloud data. -libn
                    float[] points = new float[size * 3];
                    points_rough = new float[size * 3];
                    floatBuffer.get(points);
                    floatBuffer.rewind();

                    // test rough accuracy to save data. -libn
                    DecimalFormat df = new DecimalFormat("#.##");
                    for(int i = 0; i < points.length; i++)
                    {
                        points_rough[i] = Float.valueOf(df.format(points[i]));
                    }

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
                            rgbFrameBitmap = Bitmap.createBitmap(image_width, image_height, Bitmap.Config.ARGB_8888);
                            rgbFrameBitmap.setPixels(renderer_.argbInt, 0, image_width, 0, 0, image_width, image_height);



                            // 5) save Bitmap in byte[] using Base64
                            // ref: https://stackoverflow.com/questions/4989182/converting-java-bitmap-to-byte-array
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            rgbFrameBitmap.compress(Bitmap.CompressFormat.PNG, 90, baos);
                            byte[] b_base64 = baos.toByteArray();
                            imageEncoded = Base64.encodeToString(b_base64, Base64.DEFAULT);



//                            ImageUtils.saveBitmap(rgbFrameBitmap,"rgbFrameBitmap_in_renderer.png");
//                            Log.w("Tango_depth","I get Camera Data.");

                            // TEST: get the position of the pixel: -libn
                            // 1) get the transformation matrix from rgb frame to original frame. -libn
//                            Log.w("Tango_depth",String.format("rgbImageToDepthImage: %s",rgbImageToDepthImage.toString()));

                            // 2) choose a rectangle in the rgb frame:
                            // Example: new RectF(positionX - radius, positionY - radius, positionX + radius, positionY + radius)
                            final RectF rect =
                                    new RectF(
                                            0.f,
                                            0.f,
                                            160.f,
                                            120.f);

                            // 3) update the rectangle from rgb frame to original color image frame:
                            Log.w("Orig coord: %s",rect.toString());
                            rgbImageToDepthImage.mapRect(rect);
                            Log.w("Updated coord: %s",rect.toString());


                            // 4) normalize the position in original frame:
                            rectDepthxy.clear();
                            rectDepthxy.add(new PointF(rect.centerX()/1920.0f,rect.centerY()/1080.0f));
//                            rectDepthxy.add(new PointF(900.f/1920.0f,900.f/1080.0f));

//                            // 5) get the position(x,y,z) of the pixel(rectxy.x, rectxy.y):
//                            PointF center_xy = rectDepthxy.get(0);
//                            MeasuredPoint m = getBboxDepth(center_xy.x,center_xy.y);
//
//                            // 6) print the position(x,y,z) of the pixel:
//                            Log.w("Tango_depth",String.format("Position of pixel (%f, %f) is (%f, %f, %f)", center_xy.x, center_xy.y,
//                                    m.mDepthTPoint[0], m.mDepthTPoint[1], m.mDepthTPoint[2]));


//                    // try to align depth image & color image. -libn
//                    rectDepthxy.clear();
//                    rectDepthxy.add(new PointF(0.1f,0.2f));
////                    rectDepthxy.add(new PointF(rect.centerX()/1920.0f,rect.centerY()/1080.0f));
//
//                    for(PointF rect: rectDepthxy) {
//                        MeasuredPoint m = getBboxDepth(rect.x,rect.y);
//                        if (m.mDepthTPoint.length == 3) {
//                            Log.w("Tango_depth",String.format("depth of the pixel: %f", (m.mDepthTPoint[2])));
////                            if(m.mDepthTPoint[2] < closest_depth) {
////                                closest_depth = m.mDepthTPoint[2];
////                                closest_obstacle = new PointF(rect.x,rect.y);
////
////                            }
//                        }
//                    }




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

                            // 5) get the position(x,y,z) of the pixel(rectxy.x, rectxy.y):
                            PointF center_xy = rectDepthxy.get(0);
                            MeasuredPoint m = getBboxDepth(center_xy.x,center_xy.y);

                            // 6) print the position(x,y,z) of the pixel:
                            if(m == null)
                            {
                                Log.w("Tango_depth","Depth information is null");
                            }
                            else
                            {
                                Log.w("Tango_depth",String.format("Position of pixel (%f, %f) is (%f, %f, %f)", center_xy.x, center_xy.y,
                                        m.mDepthTPoint[0], m.mDepthTPoint[1], m.mDepthTPoint[2]));

                                voice_count++;
                                if(voice_count > 20)
                                {
                                    voice_count = 0;
                                    tts1.speak(String.format("Depth %.2f",m.mDepthTPoint[2]),TextToSpeech.QUEUE_ADD,null,"Detected");
                                }



//                                // +) save the depth data to phone. -libn
//                                File extStore = Environment.getExternalStorageDirectory();
//                                String path = extStore.getAbsolutePath() + "/Tango_Image_Read/Depth_data_info.txt";
//
//                                try {
//                                    File myFile = new File(path);
//                                    OutputStream outputfilestream = new FileOutputStream(myFile);
//                                    outputfilestream.write("pixel(x,y):".getBytes());
//                                    outputfilestream.write("\n".getBytes());
//                                    outputfilestream.write(center_xy.toString().getBytes());
//                                    outputfilestream.write("\n".getBytes());
//
//                                    outputfilestream.write("\n".getBytes());
//                                    outputfilestream.write("position(x,y,z):".getBytes());
//                                    outputfilestream.write("\n".getBytes());
//                                    outputfilestream.write(Arrays.toString(m.mDepthTPoint).getBytes());
//
//
////                                    outputfilestream.write(String.format("Position of pixel (%f, %f) is (%f, %f, %f)", center_xy.x, center_xy.y,
////                                            m.mDepthTPoint[0], m.mDepthTPoint[1], m.mDepthTPoint[2]).getBytes());
//                                    outputfilestream.close();
//
//                                } catch (IOException e) {
//                                    e.printStackTrace();
//                                }
                            }

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


//    public boolean getOrientationDir(PointF bbox_in){
//        boolean isClockwise = false;
//        float adjacent = 320.0f - 640.0f*bbox_in.x;
//        if(adjacent < 0.f){
//            isClockwise = true;
//        }
//        else{
//            isClockwise = false;
//        }
//        return isClockwise;
//    }

//    public double getOrientationVal(PointF bbox_in){
//        double orientation = 0;
//        double adjacent = (double)(480.0f - 480.0f*bbox_in.y);
//        double opposite = (double)(Math.abs((320.0f - 640.0f*bbox_in.x)));
//        orientation = Math.toDegrees(Math.atan(opposite/adjacent));
//        return orientation;
//    }


    // send PointCloud data to server. -libn
    protected Boolean sendPointCloudData() {

//        // +Test) save the depth data to phone. -libn
//        File extStore = Environment.getExternalStorageDirectory();
//        String path = extStore.getAbsolutePath() + "/Tango_Image_Read/Depth_data.txt";
//
//        try {
//            File myFile = new File(path);
//            OutputStream outputfilestream = new FileOutputStream(myFile);
//            outputfilestream.write(Arrays.toString(points_rough).getBytes());
//            outputfilestream.close();
//
//        } catch (IOException e) {
//            e.printStackTrace();
//        }


        // test speed. -libn
//        Log.w("Tango_depth","Sending Data!!!");
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
            URL url = new URL("http",fileContent,8080,"jsonData");
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
//                // 5) save the response from server. -libn
//                File f = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/objects");
//                if (!f.exists()) {
//                    f.mkdirs();
//                }
//                final File file = new File(f,"test_libn_2.txt");
//                OutputStream outputfilestream = new FileOutputStream(file);
//                outputfilestream.write(sb.toString().getBytes());
//                outputfilestream.close();

//                Log.w("Tango_depth","Object file saved!");

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

        JSONObject jsonObject = new JSONObject();
        try {

            // jsonObject.put("Bitmap444", "aaa"); // time delay: if there is no data: ~0.02s!

            // save PointCloud data. -libn
//            jsonObject.put("DepthData", Arrays.toString(points_rough)); // time delay: sending data: ~1s

            // send ColorImage data. -libn
            jsonObject.put("ColorImage", imageEncoded); // time delay: sending data: ~0.3s




            // raw image sending test. -libn -20190111
            // 1) send Bitmap: No valid data!
//            jsonObject.put("Bitmap000", rgbFrameBitmap.toString()); // time delay: toString(): ~0.3s; sending data: ~0.3s
//
//
//            // 2) send ByteBuffer: data exists!
//            int size_img     = rgbFrameBitmap.getRowBytes() * rgbFrameBitmap.getHeight();
//            ByteBuffer b = ByteBuffer.allocate(size_img);
//            rgbFrameBitmap.copyPixelsToBuffer(b);
//            jsonObject.put("Bitmap111", b.toString()); // time delay: toString(): ~0.3s; sending data: ~0.3s
//
//            // 3) send image in ByteArray: data exists!
//            ByteArrayOutputStream stream = new ByteArrayOutputStream();
//            rgbFrameBitmap.compress(Bitmap.CompressFormat.JPEG, 100,stream);
//            byte[] byteArray = stream.toByteArray();
//            Log.w("Tango_depth",String.format("byteArray.length = %d",byteArray.length));
//            jsonObject.put("Bitmap222", Arrays.toString(byteArray)); // time delay: toString(): ~0.3s; sending data: ~0.3s
//
//            // 4) Bitmap to byte
//            int size_byte = rgbFrameBitmap.getRowBytes() * rgbFrameBitmap.getHeight();
//            ByteBuffer byteBuffer = ByteBuffer.allocate(size_byte);
//            rgbFrameBitmap.copyPixelsToBuffer(byteBuffer);
//            byte[] byteArray333 = byteBuffer.array();
//            jsonObject.put("Bitmap333", Arrays.toString(byteArray333)); // time delay: toString(): ~0.3s; sending data: ~0.3s



            // Transfer pointCloudData in files. -libn
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


