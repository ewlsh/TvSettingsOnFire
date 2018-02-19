package com.rockon999.android.tv.settings.firetv.util;

import android.util.Log;
import android.view.View;
import android.widget.AdapterView;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Created by rockon999 on 2/18/18.
 */

public class AdapterViewUtil {
    private static final String TAG = "AdapterViewUtil";


    public static void setOnItemClickListener(AdapterView adapter, final OnItemClickListener listener) {
        try {
            Class<?>[] clazzes = AdapterView.class.getDeclaredClasses();
            for (Class<?> clazz : clazzes) {
                if (clazz.isInterface()) {
                    if (clazz.getName().contains("OnItemClickListener")) {
                        Method m = AdapterView.class.getMethod("setOnItemClickListener", clazz);

                        InvocationHandler handler = new InvocationHandler() {
                            @Override
                            public Object invoke(Object o, Method method, Object[] objects) throws Throwable {
                                // onItemClick(AdapterView<?> parent, View view, int position, long id) {
                                if (method.getName().equals("onItemClick") && objects.length == 4) {
                                    AdapterView<?> parent = (AdapterView) objects[0];
                                    View view = (View) objects[1];
                                    Integer position = (Integer) objects[2];
                                    Long id = (Long) objects[3];

                                    listener.onItemClick(parent, view, position, id);
                                    return null;
                                }

                                throw new RuntimeException("This should never occur unless we're running on a API level that isn't 22.");
                            }
                        };
                        Class<?> proxyClass = Proxy.getProxyClass(clazz.getClassLoader(), clazz);
                        Object f = proxyClass.getConstructor(new Class[]{InvocationHandler.class}).newInstance(handler);
                        m.invoke(adapter, f);
                    }
                }
            }

        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | java.lang.InstantiationException e) {
            Log.d(TAG, "failed to setup textinput listener", e);
        }

    }

    public interface OnItemClickListener {
        void onItemClick(AdapterView<?> parent, View view, int position, long id);
    }
}
