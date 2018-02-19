package com.rockon999.android.tv.settings.firetv.util;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Created by rockon999 on 2/18/18.
 */

@Retention(RetentionPolicy.SOURCE)
public @interface Unsupported {
    String reason();
}
