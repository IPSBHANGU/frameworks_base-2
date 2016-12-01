/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.pm.dex;

import android.util.AtomicFile;
import android.util.Slog;
import android.os.Build;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FastPrintWriter;
import com.android.server.pm.AbstractStatsBase;
import com.android.server.pm.PackageManagerServiceUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Iterator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import dalvik.system.VMRuntime;
import libcore.io.IoUtils;

/**
 * Stat file which store usage information about dex files.
 */
public class PackageDexUsage extends AbstractStatsBase<Void> {
    private final static String TAG = "PackageDexUsage";

    private final static int PACKAGE_DEX_USAGE_VERSION = 1;
    private final static String PACKAGE_DEX_USAGE_VERSION_HEADER =
            "PACKAGE_MANAGER__PACKAGE_DEX_USAGE__";

    private final static String SPLIT_CHAR = ",";
    private final static String DEX_LINE_CHAR = "#";

    // Map which structures the information we have on a package.
    // Maps package name to package data (which stores info about UsedByOtherApps and
    // secondary dex files.).
    // Access to this map needs synchronized.
    @GuardedBy("mPackageUseInfoMap")
    private Map<String, PackageUseInfo> mPackageUseInfoMap;

    public PackageDexUsage() {
        super("package-dex-usage.list", "PackageDexUsage_DiskWriter", /*lock*/ false);
        mPackageUseInfoMap = new HashMap<>();
    }

    /**
     * Record a dex file load.
     *
     * Note this is called when apps load dex files and as such it should return
     * as fast as possible.
     *
     * @param loadingPackage the package performing the load
     * @param dexPath the path of the dex files being loaded
     * @param ownerUserId the user id which runs the code loading the dex files
     * @param loaderIsa the ISA of the app loading the dex files
     * @param isUsedByOtherApps whether or not this dex file was not loaded by its owning package
     * @param primaryOrSplit whether or not the dex file is a primary/split dex. True indicates
     *        the file is either primary or a split. False indicates the file is secondary dex.
     * @return true if the dex load constitutes new information, or false if this information
     *         has been seen before.
     */
    public boolean record(String owningPackageName, String dexPath, int ownerUserId,
            String loaderIsa, boolean isUsedByOtherApps, boolean primaryOrSplit) {
        if (!PackageManagerServiceUtils.checkISA(loaderIsa)) {
            throw new IllegalArgumentException("loaderIsa " + loaderIsa + " is unsupported");
        }
        synchronized (mPackageUseInfoMap) {
            PackageUseInfo packageUseInfo = mPackageUseInfoMap.get(owningPackageName);
            if (packageUseInfo == null) {
                // This is the first time we see the package.
                packageUseInfo = new PackageUseInfo();
                if (primaryOrSplit) {
                    // If we have a primary or a split apk, set isUsedByOtherApps.
                    // We do not need to record the loaderIsa or the owner because we compile
                    // primaries for all users and all ISAs.
                    packageUseInfo.mIsUsedByOtherApps = isUsedByOtherApps;
                } else {
                    // For secondary dex files record the loaderISA and the owner. We'll need
                    // to know under which user to compile and for what ISA.
                    packageUseInfo.mDexUseInfoMap.put(
                            dexPath, new DexUseInfo(isUsedByOtherApps, ownerUserId, loaderIsa));
                }
                mPackageUseInfoMap.put(owningPackageName, packageUseInfo);
                return true;
            } else {
                // We already have data on this package. Amend it.
                if (primaryOrSplit) {
                    // We have a possible update on the primary apk usage. Merge
                    // isUsedByOtherApps information and return if there was an update.
                    return packageUseInfo.merge(isUsedByOtherApps);
                } else {
                    DexUseInfo newData = new DexUseInfo(
                            isUsedByOtherApps, ownerUserId, loaderIsa);
                    DexUseInfo existingData = packageUseInfo.mDexUseInfoMap.get(dexPath);
                    if (existingData == null) {
                        // It's the first time we see this dex file.
                        packageUseInfo.mDexUseInfoMap.put(dexPath, newData);
                        return true;
                    } else {
                        if (ownerUserId != existingData.mOwnerUserId) {
                            // Oups, this should never happen, the DexManager who calls this should
                            // do the proper checks and not call record if the user does not own the
                            // dex path.
                            // Secondary dex files are stored in the app user directory. A change in
                            // owningUser for the same path means that something went wrong at some
                            // higher level, and the loaderUser was allowed to cross
                            // user-boundaries and access data from what we know to be the owner
                            // user.
                            throw new IllegalArgumentException("Trying to change ownerUserId for "
                                    + " dex path " + dexPath + " from " + existingData.mOwnerUserId
                                    + " to " + ownerUserId);
                        }
                        // Merge the information into the existing data.
                        // Returns true if there was an update.
                        return existingData.merge(newData);
                    }
                }
            }
        }
    }

    /**
     * Convenience method for sync reads which does not force the user to pass a useless
     * (Void) null.
     */
    public void read() {
      read((Void) null);
    }

    /**
     * Convenience method for async writes which does not force the user to pass a useless
     * (Void) null.
     */
    public void maybeWriteAsync() {
      maybeWriteAsync((Void) null);
    }

    @Override
    protected void writeInternal(Void data) {
        AtomicFile file = getFile();
        FileOutputStream f = null;

        try {
            f = file.startWrite();
            OutputStreamWriter osw = new OutputStreamWriter(f);
            write(osw);
            osw.flush();
            file.finishWrite(f);
        } catch (IOException e) {
            if (f != null) {
                file.failWrite(f);
            }
            Slog.e(TAG, "Failed to write usage for dex files", e);
        }
    }

    /**
     * File format:
     *
     * file_magic_version
     * package_name_1
     * #dex_file_path_1_1
     * user_1_1, used_by_other_app_1_1, user_isa_1_1_1, user_isa_1_1_2
     * #dex_file_path_1_2
     * user_1_2, used_by_other_app_1_2, user_isa_1_2_1, user_isa_1_2_2
     * ...
     * package_name_2
     * #dex_file_path_2_1
     * user_2_1, used_by_other_app_2_1, user_isa_2_1_1, user_isa_2_1_2
     * #dex_file_path_2_2,
     * user_2_2, used_by_other_app_2_2, user_isa_2_2_1, user_isa_2_2_2
     * ...
    */
    /* package */ void write(Writer out) {
        // Make a clone to avoid locking while writing to disk.
        Map<String, PackageUseInfo> packageUseInfoMapClone = clonePackageUseInfoMap();

        FastPrintWriter fpw = new FastPrintWriter(out);

        // Write the header.
        fpw.print(PACKAGE_DEX_USAGE_VERSION_HEADER);
        fpw.println(PACKAGE_DEX_USAGE_VERSION);

        for (Map.Entry<String, PackageUseInfo> pEntry : packageUseInfoMapClone.entrySet()) {
            // Write the package line.
            String packageName = pEntry.getKey();
            PackageUseInfo packageUseInfo = pEntry.getValue();

            fpw.println(String.join(SPLIT_CHAR, packageName,
                    writeBoolean(packageUseInfo.mIsUsedByOtherApps)));

            // Write dex file lines.
            for (Map.Entry<String, DexUseInfo> dEntry : packageUseInfo.mDexUseInfoMap.entrySet()) {
                String dexPath = dEntry.getKey();
                DexUseInfo dexUseInfo = dEntry.getValue();
                fpw.println(DEX_LINE_CHAR + dexPath);
                fpw.print(String.join(SPLIT_CHAR, Integer.toString(dexUseInfo.mOwnerUserId),
                        writeBoolean(dexUseInfo.mIsUsedByOtherApps)));
                for (String isa : dexUseInfo.mLoaderIsas) {
                    fpw.print(SPLIT_CHAR + isa);
                }
                fpw.println();
            }
        }
        fpw.flush();
    }

    @Override
    protected void readInternal(Void data) {
        AtomicFile file = getFile();
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(file.openRead()));
            read(in);
        } catch (FileNotFoundException expected) {
            // The file may not be there. E.g. When we first take the OTA with this feature.
        } catch (IOException e) {
            Slog.w(TAG, "Failed to parse package dex usage.", e);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    /* package */ void read(Reader reader) throws IOException {
        Map<String, PackageUseInfo> data = new HashMap<>();
        BufferedReader in = new BufferedReader(reader);
        // Read header, do version check.
        String versionLine = in.readLine();
        if (versionLine == null) {
            throw new IllegalStateException("No version line found.");
        } else {
            if (!versionLine.startsWith(PACKAGE_DEX_USAGE_VERSION_HEADER)) {
                // TODO(calin): the caller is responsible to clear the file.
                throw new IllegalStateException("Invalid version line: " + versionLine);
            }
            int version = Integer.parseInt(
                    versionLine.substring(PACKAGE_DEX_USAGE_VERSION_HEADER.length()));
            if (version != PACKAGE_DEX_USAGE_VERSION) {
                throw new IllegalStateException("Unexpected version: " + version);
            }
        }

        String s = null;
        String currentPakage = null;
        PackageUseInfo currentPakageData = null;

        Set<String> supportedIsas = new HashSet<>();
        for (String abi : Build.SUPPORTED_ABIS) {
            supportedIsas.add(VMRuntime.getInstructionSet(abi));
        }
        while ((s = in.readLine()) != null) {
            if (s.startsWith(DEX_LINE_CHAR)) {
                // This is the start of the the dex lines.
                // We expect two lines for each dex entry:
                // #dexPaths
                // onwerUserId,isUsedByOtherApps,isa1,isa2
                if (currentPakage == null) {
                    throw new IllegalStateException(
                        "Malformed PackageDexUsage file. Expected package line before dex line.");
                }

                // First line is the dex path.
                String dexPath = s.substring(DEX_LINE_CHAR.length());
                // Next line is the dex data.
                s = in.readLine();
                if (s == null) {
                    throw new IllegalStateException("Could not fine dexUseInfo for line: " + s);
                }

                // We expect at least 3 elements (isUsedByOtherApps, userId, isa).
                String[] elems = s.split(SPLIT_CHAR);
                if (elems.length < 3) {
                    throw new IllegalStateException("Invalid PackageDexUsage line: " + s);
                }
                int ownerUserId = Integer.parseInt(elems[0]);
                boolean isUsedByOtherApps = readBoolean(elems[1]);
                DexUseInfo dexUseInfo = new DexUseInfo(isUsedByOtherApps, ownerUserId);
                for (int i = 2; i < elems.length; i++) {
                    String isa = elems[i];
                    if (supportedIsas.contains(isa)) {
                        dexUseInfo.mLoaderIsas.add(elems[i]);
                    } else {
                        // Should never happen unless someone crafts the file manually.
                        // In theory it could if we drop a supported ISA after an OTA but we don't
                        // do that.
                        Slog.wtf(TAG, "Unsupported ISA when parsing PackageDexUsage: " + isa);
                    }
                }
                if (supportedIsas.isEmpty()) {
                    Slog.wtf(TAG, "Ignore dexPath when parsing PackageDexUsage because of " +
                            "unsupported isas. dexPath=" + dexPath);
                    continue;
                }
                currentPakageData.mDexUseInfoMap.put(dexPath, dexUseInfo);
            } else {
                // This is a package line.
                // We expect it to be: `packageName,isUsedByOtherApps`.
                String[] elems = s.split(SPLIT_CHAR);
                if (elems.length != 2) {
                    throw new IllegalStateException("Invalid PackageDexUsage line: " + s);
                }
                currentPakage = elems[0];
                currentPakageData = new PackageUseInfo();
                currentPakageData.mIsUsedByOtherApps = readBoolean(elems[1]);
                data.put(currentPakage, currentPakageData);
            }
        }

        synchronized (mPackageUseInfoMap) {
            mPackageUseInfoMap.clear();
            mPackageUseInfoMap.putAll(data);
        }
    }

    /**
     * Syncs the existing data with the set of available packages by removing obsolete entries.
     */
    public void syncData(Map<String, Set<Integer>> packageToUsersMap) {
        synchronized (mPackageUseInfoMap) {
            Iterator<Map.Entry<String, PackageUseInfo>> pIt =
                    mPackageUseInfoMap.entrySet().iterator();
            while (pIt.hasNext()) {
                Map.Entry<String, PackageUseInfo> pEntry = pIt.next();
                String packageName = pEntry.getKey();
                PackageUseInfo packageUseInfo = pEntry.getValue();
                Set<Integer> users = packageToUsersMap.get(packageName);
                if (users == null) {
                    // The package doesn't exist anymore, remove the record.
                    pIt.remove();
                } else {
                    // The package exists but we can prune the entries associated with non existing
                    // users.
                    Iterator<Map.Entry<String, DexUseInfo>> dIt =
                            packageUseInfo.mDexUseInfoMap.entrySet().iterator();
                    while (dIt.hasNext()) {
                        DexUseInfo dexUseInfo = dIt.next().getValue();
                        if (!users.contains(dexUseInfo.mOwnerUserId)) {
                            // User was probably removed. Delete its dex usage info.
                            dIt.remove();
                        }
                    }
                    if (!packageUseInfo.mIsUsedByOtherApps
                            && packageUseInfo.mDexUseInfoMap.isEmpty()) {
                        // The package is not used by other apps and we removed all its dex files
                        // records. Remove the entire package record as well.
                        pIt.remove();
                    }
                }
            }
        }
    }

    public PackageUseInfo getPackageUseInfo(String packageName) {
        synchronized (mPackageUseInfoMap) {
            return mPackageUseInfoMap.get(packageName);
        }
    }

    public void clear() {
        synchronized (mPackageUseInfoMap) {
            mPackageUseInfoMap.clear();
        }
    }
    // Creates a deep copy of the class' mPackageUseInfoMap.
    private Map<String, PackageUseInfo> clonePackageUseInfoMap() {
        Map<String, PackageUseInfo> clone = new HashMap<>();
        synchronized (mPackageUseInfoMap) {
            for (Map.Entry<String, PackageUseInfo> e : mPackageUseInfoMap.entrySet()) {
                clone.put(e.getKey(), new PackageUseInfo(e.getValue()));
            }
        }
        return clone;
    }

    private String writeBoolean(boolean bool) {
        return bool ? "1" : "0";
    }

    private boolean readBoolean(String bool) {
        if ("0".equals(bool)) return false;
        if ("1".equals(bool)) return true;
        throw new IllegalArgumentException("Unknown bool encoding: " + bool);
    }

    private boolean contains(int[] array, int elem) {
        for (int i = 0; i < array.length; i++) {
            if (elem == array[i]) {
                return true;
            }
        }
        return false;
    }

    public String dump() {
        StringWriter sw = new StringWriter();
        write(sw);
        return sw.toString();
    }

    /**
     * Stores data on how a package and its dex files are used.
     */
    public static class PackageUseInfo {
        // This flag is for the primary and split apks. It is set to true whenever one of them
        // is loaded by another app.
        private boolean mIsUsedByOtherApps;
        // Map dex paths to their data (isUsedByOtherApps, owner id, loader isa).
        private final Map<String, DexUseInfo> mDexUseInfoMap;

        public PackageUseInfo() {
            mIsUsedByOtherApps = false;
            mDexUseInfoMap = new HashMap<>();
        }

        // Creates a deep copy of the `other`.
        public PackageUseInfo(PackageUseInfo other) {
            mIsUsedByOtherApps = other.mIsUsedByOtherApps;
            mDexUseInfoMap = new HashMap<>();
            for (Map.Entry<String, DexUseInfo> e : other.mDexUseInfoMap.entrySet()) {
                mDexUseInfoMap.put(e.getKey(), new DexUseInfo(e.getValue()));
            }
        }

        private boolean merge(boolean isUsedByOtherApps) {
            boolean oldIsUsedByOtherApps = mIsUsedByOtherApps;
            mIsUsedByOtherApps = mIsUsedByOtherApps || isUsedByOtherApps;
            return oldIsUsedByOtherApps != this.mIsUsedByOtherApps;
        }

        public boolean isUsedByOtherApps() {
            return mIsUsedByOtherApps;
        }

        public Map<String, DexUseInfo> getDexUseInfoMap() {
            return mDexUseInfoMap;
        }
    }

    /**
     * Stores data about a loaded dex files.
     */
    public static class DexUseInfo {
        private boolean mIsUsedByOtherApps;
        private final int mOwnerUserId;
        private final Set<String> mLoaderIsas;

        public DexUseInfo(boolean isUsedByOtherApps, int ownerUserId) {
            this(isUsedByOtherApps, ownerUserId, null);
        }

        public DexUseInfo(boolean isUsedByOtherApps, int ownerUserId, String loaderIsa) {
            mIsUsedByOtherApps = isUsedByOtherApps;
            mOwnerUserId = ownerUserId;
            mLoaderIsas = new HashSet<>();
            if (loaderIsa != null) {
                mLoaderIsas.add(loaderIsa);
            }
        }

        // Creates a deep copy of the `other`.
        public DexUseInfo(DexUseInfo other) {
            mIsUsedByOtherApps = other.mIsUsedByOtherApps;
            mOwnerUserId = other.mOwnerUserId;
            mLoaderIsas = new HashSet<>(other.mLoaderIsas);
        }

        private boolean merge(DexUseInfo dexUseInfo) {
            boolean oldIsUsedByOtherApps = mIsUsedByOtherApps;
            mIsUsedByOtherApps = mIsUsedByOtherApps || dexUseInfo.mIsUsedByOtherApps;
            boolean updateIsas = mLoaderIsas.addAll(dexUseInfo.mLoaderIsas);
            return updateIsas || (oldIsUsedByOtherApps != mIsUsedByOtherApps);
        }

        public boolean isUsedByOtherApps() {
            return mIsUsedByOtherApps;
        }

        public int getOwnerUserId() {
            return mOwnerUserId;
        }

        public Set<String> getLoaderIsas() {
            return mLoaderIsas;
        }
    }
}
