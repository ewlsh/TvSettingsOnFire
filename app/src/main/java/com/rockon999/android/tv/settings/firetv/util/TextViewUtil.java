package com.rockon999.android.tv.settings.firetv.util;

import android.util.Log;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.TextView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Created by rockon999 on 2/18/18.
 */

public class TextViewUtil {

    private static final String TAG = "TextViewUtil";

    public static void setOnEditorActionListener(TextView textInput, final OnEditorActionListener listener) {
        try {

            Class<?>[] clazzes = TextView.class.getDeclaredClasses();
            for (Class<?> clazz : clazzes) {
                if (clazz.getName().contains("OnEditorActionListener")) {
                    if (clazz.isInterface()) {
                        Method m = TextView.class.getMethod("setOnEditorActionListener", clazz);

                        InvocationHandler handler = new InvocationHandler() {
                            @Override
                            public Object invoke(Object o, Method method, Object[] objects) throws Throwable {
                                // onEditorAction(TextView v, int actionId, KeyEvent event) {
                                if (method.getName().equals("onEditorAction") && objects.length == 3) {
                                    TextView v = (TextView) objects[0];
                                    Integer actionId = (Integer) objects[1];
                                    KeyEvent event = (KeyEvent) objects[2];

                                    return listener.onEditorAction(v, actionId, event);
                                }

                                throw new RuntimeException("This should never occur unless we're running on a API level that isn't 22.");
                            }
                        };
                        Class<?> proxyClass = Proxy.getProxyClass(clazz.getClassLoader(), clazz);
                        Object f = proxyClass.getConstructor(new Class[]{InvocationHandler.class}).newInstance(handler);
                        m.invoke(textInput, f);
                    }
                }
            }

        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | java.lang.InstantiationException e) {
            Log.d(TAG, "failed to setup textinput listener", e);
        }
    }

    public interface OnEditorActionListener {
        boolean onEditorAction(TextView v, int actionId, KeyEvent event);
    }
}
