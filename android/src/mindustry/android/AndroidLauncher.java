package mindustry.android;

import android.*;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.os.*;
import android.os.Build.*;
import android.telephony.*;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import arc.*;
import arc.backend.android.*;
import arc.files.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import dalvik.system.*;
import mindustry.*;
import mindustry.android.controller.AndroidControllerProvider;
import mindustry.game.EventType.*;
import mindustry.input.controller.ControllerConfig;
import mindustry.input.controller.ControllerSupport;
import mindustry.net.*;
import mindustry.ui.*;
import mindustry.ui.FileChooser.*;
import mindustry.ui.dialogs.*;

import java.io.*;
import java.lang.Thread.*;
import java.util.*;

import static mindustry.Vars.*;

public class AndroidLauncher extends AndroidApplication{
    public static final int PERMISSION_REQUEST_CODE = 1;
    boolean doubleScaleTablets = true;
    FileChooserDialog chooser;
    Runnable permCallback;
    public static AndroidControllerProvider controllerProvider;
    private static AndroidLauncher instance;

    @Override
    protected void onCreate(Bundle savedInstanceState){
        UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            CrashHandler.log(error);

            //try to forward exception to system handler
            if(handler != null){
                handler.uncaughtException(thread, error);
            }else{
                Log.err(error);
                System.exit(1);
            }
        });

        super.onCreate(savedInstanceState);
        instance = this;
        // initialize controller support
        controllerProvider = new AndroidControllerProvider();
        try{
            ControllerSupport.setProvider(controllerProvider);
            // NOTE: ControllerConfig.loadDefaults() must not run here - Core.settings does not
            // exist yet at this point (it is loaded later by Control.load()).
        }catch(Throwable t){
            Log.err("Failed to init controller provider", t);
        }
        if(doubleScaleTablets && isTablet(this)){
            Scl.setAddition(0.5f);
        }

        initialize(new ClientLauncher(){

            @Override
            public void hide(){
                moveTaskToBack(true);
            }

            @Override
            public rhino.Context getScriptContext(){
                return AndroidRhinoContext.enter(getCacheDir());
            }

            @Override
            public void shareFile(Fi file){
            }

            @Override
            public ClassLoader loadJar(Fi jar, ClassLoader parent) throws Exception{
                //Required to load jar files in Android 14: https://developer.android.com/about/versions/14/behavior-changes-14#safer-dynamic-code-loading
                try{
                    jar.file().setReadOnly();
                    return new DexClassLoader(jar.file().getPath(), getFilesDir().getPath(), null, parent){
                        @Override
                        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException{
                            //check for loaded state
                            Class<?> loadedClass = findLoadedClass(name);
                            if(loadedClass == null){
                                try{
                                    //try to load own class first
                                    loadedClass = findClass(name);
                                }catch(ClassNotFoundException | NoClassDefFoundError e){
                                    //use parent if not found
                                    return parent.loadClass(name);
                                }
                            }

                            if(resolve){
                                resolveClass(loadedClass);
                            }
                            return loadedClass;
                        }
                    };
                }catch(SecurityException e){
                    //`setReadOnly` to jar file in `/sdcard/Android/data/...` does not work on some Android 14 devices, but in `/data/...`, it does

                    if(Build.VERSION.SDK_INT < VERSION_CODES.O_MR1){
                        throw e;
                    }

                    Fi cacheDir = new Fi(getCacheDir()).child("mods");
                    cacheDir.mkdirs();

                    //long file name support
                    Fi modCacheDir = cacheDir.child(jar.nameWithoutExtension());
                    Fi modCache = modCacheDir.child(Long.toHexString(jar.lastModified()) + ".zip");

                    if(modCacheDir.equals(jar.parent())){
                        //should not reach here, just in case
                        throw e;
                    }

                    //Cache will be deleted when mod is removed
                    if(!modCache.exists() || jar.length() != modCache.length()){
                        modCacheDir.mkdirs();
                        jar.copyTo(modCache);
                    }
                    modCache.file().setReadOnly();
                    return loadJar(modCache, parent);
                }
            }

            @Override
            public void showFileChooser(FileChooserParams params){
                try{
                    String extension = params.extensions[0];

                    if(VERSION.SDK_INT >= VERSION_CODES.Q){
                        Intent intent = new Intent(params.open ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType(extension.equals("zip") && !params.open && params.extensions.length == 1 ? "application/zip" : "*/*");
                        intent.putExtra(Intent.EXTRA_TITLE, params.fileName);
                        if(params.allowMultiple){
                            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        }

                        addResultListener(i -> startActivityForResult(intent, i), (code, in) -> {
                            if(code == Activity.RESULT_OK && in != null && in.getData() != null){
                                Uri[] uris;
                                if(in.getClipData() != null){
                                    uris = new Uri[in.getClipData().getItemCount()];
                                    for(int i = 0; i < uris.length; i++){
                                        uris[i] = in.getClipData().getItemAt(i).getUri();
                                    }
                                }else{
                                    uris = new Uri[]{in.getData()};
                                }

                                if(uris.length == 0 || uris[0].getPath().contains("(invalid)")) return;

                                Fi[] files = Seq.with(uris).map(uri -> new Fi(uri.getPath()){
                                    @Override
                                    public InputStream read(){
                                        try{
                                            return getContentResolver().openInputStream(uri);
                                        }catch(IOException e){
                                            throw new ArcRuntimeException(e);
                                        }
                                    }

                                    @Override
                                    public OutputStream write(boolean append){
                                        try{
                                            return getContentResolver().openOutputStream(uri, "rwt");
                                        }catch(IOException e){
                                            throw new ArcRuntimeException(e);
                                        }
                                    }

                                    @Override
                                    public Writer writer(boolean append, String charset){
                                        try{
                                            return new OutputStreamWriter(write(append), charset);
                                        }catch(IOException ex){
                                            throw new ArcRuntimeException(ex);
                                        }
                                    }
                                }).toArray(Fi.class);

                                Core.app.post(() -> Core.app.post(() -> params.handleChooseResult(files)));
                            }
                        });
                    }else if(VERSION.SDK_INT >= VERSION_CODES.M && !(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)){
                        chooser = FileChooser.createFallbackFileChooser(params);

                        ArrayList<String> perms = new ArrayList<>();
                        if(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
                            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                        }
                        if(checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED){
                            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                        }
                        requestPermissions(perms.toArray(new String[0]), PERMISSION_REQUEST_CODE);
                    }else{
                        FileChooser.showFallbackFileChooser(params);
                    }
                }catch(Throwable error){
                    Core.app.post(() -> Vars.ui.showException(error));
                }
            }

            @Override
            public void beginForceLandscape(){
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            }

            @Override
            public void endForceLandscape(){
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
            }

        }, new AndroidApplicationConfiguration(){{
            useImmersiveMode = true;
            hideStatusBar = true;
            useGL30 = true;
        }});

        var intent = getIntent();
        Events.on(ClientLoadEvent.class, u -> handleIntent(intent));

        try{
            //new external folder
            Fi data = Core.files.absolute(((Context)this).getExternalFilesDir(null).getAbsolutePath());
            Core.settings.setDataDirectory(data);

            //delete unused cache folder to free up space
            try{
                Fi cache = Core.settings.getDataDirectory().child("cache");
                if(cache.exists()){
                    cache.deleteDirectory();
                }
            }catch(Throwable t){
                Log.err("Failed to delete cached folder", t);
            }

            //move to internal storage if there's no file indicating that it moved
            if(!Core.files.local("files_moved").exists()){
                Log.info("Moving files to external storage...");

                try{
                    //current local storage folder
                    Fi src = Core.files.absolute(Core.files.getLocalStoragePath());
                    for(Fi fi : src.list()){
                        fi.copyTo(data);
                    }
                    //create marker
                    Core.files.local("files_moved").writeString("files moved to " + data);
                    Core.files.local("files_moved_103").writeString("files moved again");
                    Log.info("Files moved.");
                }catch(Throwable t){
                    Log.err("Failed to move files!");
                    t.printStackTrace();
                }
            }
        }catch(Exception e){
            //print log but don't crash
            Log.err(e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
        if(requestCode == PERMISSION_REQUEST_CODE){
            for(int i : grantResults){
                if(i != PackageManager.PERMISSION_GRANTED) return;
            }
            if(chooser != null){
                Core.app.post(chooser::show);
            }
            if(permCallback != null){
                Core.app.post(permCallback);
                permCallback = null;
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);

        handleIntent(intent);
    }

    private void handleIntent(Intent intent){
        if(intent == null) return;

        try{
            Uri uri = intent.getData();
            if(uri != null){
                String scheme = uri.getScheme();

                //clear data (not sure if necessary?)
                intent.setAction(Intent.ACTION_MAIN);
                intent.setData(null);
                setIntent(intent);

                if("mindustry".equalsIgnoreCase(scheme)){ //open a server URL

                    String host = uri.getHost();
                    int port = uri.getPort();

                    if(host != null && !host.isEmpty()){
                        Core.app.post(() -> {
                            ui.showConfirm(Core.bundle.format("servers.connect.confirm", host), () -> ui.join.connect(host, port != -1 ? port : 6567));
                        });
                    }
                }else{ //open a save file
                    Fi file = Core.files.cache("imported");
                    file.write(getContentResolver().openInputStream(uri), false);

                    ClientLauncher.handleFileImport(file);
                }
            }
        }catch(Throwable e){
            Log.err(e);
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event){
        if(controllerProvider != null && controllerProvider.onGenericMotionEvent(event)){
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event){
        boolean isGamepad = false;
        if(event.getDevice() != null){
            int sources = event.getDevice().getSources();
            isGamepad = ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) || ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
        }
        if(isGamepad && controllerProvider != null && controllerProvider.onKeyEvent(event)){
            return true;
        }
        // also check keycode range for gamepad
        if((keyCode >= KeyEvent.KEYCODE_BUTTON_A && keyCode <= KeyEvent.KEYCODE_BUTTON_MODE) || (keyCode >= KeyEvent.KEYCODE_DPAD_UP && keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT)){
            if(controllerProvider != null && controllerProvider.onKeyEvent(event)) return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event){
        if(controllerProvider != null && controllerProvider.onKeyEvent(event)){
            // still let system handle back button if it's controller B mapped to back?
            // Return true to consume gamepad events
            int sources = event.getDevice() != null ? event.getDevice().getSources() : 0;
            boolean isGamepad = ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) || ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
            if(isGamepad || (keyCode >= KeyEvent.KEYCODE_BUTTON_A && keyCode <= KeyEvent.KEYCODE_BUTTON_MODE)) return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event){
        if(controllerProvider != null && controllerProvider.onGenericMotionEvent(event)){
            // Also forward to super so Arc's view gets it if needed, but we already consumed
            // For joystick, we want to prevent touch handling, so return true
            if((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event){
        // intercept gamepad keys before dispatch
        if(event.getDevice() != null){
            int sources = event.getDevice().getSources();
            boolean isGamepad = ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) || ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
            if(isGamepad && controllerProvider != null){
                controllerProvider.onKeyEvent(event);
                // Consume gamepad events to prevent them from triggering android back/menu actions incorrectly
                // But allow BACK to still work as controller B alternative: we handle that via controller mapping
                if(event.getKeyCode() != KeyEvent.KEYCODE_BACK && event.getKeyCode() != KeyEvent.KEYCODE_MENU){
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    public static AndroidLauncher getInstance(){ return instance; }

    public static AndroidControllerProvider getControllerProvider(){ return controllerProvider; }

    private boolean isTablet(Context context){
        TelephonyManager manager = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        return manager != null && manager.getPhoneType() == TelephonyManager.PHONE_TYPE_NONE;
    }
}
