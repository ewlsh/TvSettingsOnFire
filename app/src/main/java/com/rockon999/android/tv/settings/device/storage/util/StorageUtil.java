package com.rockon999.android.tv.settings.device.storage.util;

import android.os.StatFs;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by rockon999 on 2/18/18.
 */

public class StorageUtil {

    public static class StorageMeasurer {
        private final File file;
        private long size;

        public StorageMeasurer(File file) {
            this.file = file;
        }

        public static StorageMeasurer createAndCalculate(File file) {
            StorageMeasurer measurer = new StorageMeasurer(file);
            measurer.calculate();
            return measurer;
        }

        public long getSize() {
            return size;
        }

        public void calculate() {
            if (file == null || !file.exists()) {
                this.size = 0;
                return;
            }

            if (!file.isDirectory()) {
                this.size = file.length();
                return;
            }

            final Deque<File> dirs = new ArrayDeque<>();
            dirs.push(file);

            long result = 0;

            while (!dirs.isEmpty()) {
                final File dir = dirs.pop();

                if (!dir.exists()) {
                    continue;
                }

                final File[] listFiles = dir.listFiles();

                if (listFiles == null || listFiles.length == 0) {
                    continue;
                }

                for (final File child : listFiles) {
                    result += child.length();

                    boolean isSymlink = false;

                    try {
                        isSymlink = isSymlink(child);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    if (child.isDirectory() && !isSymlink) {
                        dirs.push(child);
                    }
                }
            }
            this.size = result;
        }

        private static boolean isSymlink(File file) throws IOException {
            File canon;
            if (file.getParent() == null) {
                canon = file;
            } else {
                File canonDir = file.getParentFile().getCanonicalFile();
                canon = new File(canonDir, file.getName());
            }
            return !canon.getCanonicalFile().equals(canon.getAbsoluteFile());
        }
    }
}
