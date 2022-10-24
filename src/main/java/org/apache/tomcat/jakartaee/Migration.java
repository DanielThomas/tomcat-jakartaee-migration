/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.jakartaee;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.CloseShieldInputStream;
import org.apache.commons.io.output.CloseShieldOutputStream;

public class Migration {

    private static final Logger logger = Logger.getLogger(Migration.class.getCanonicalName());
    private static final StringManager sm = StringManager.getManager(Migration.class);

    private static final Set<String> DEFAULT_EXCLUDES = new HashSet<>();

    static {
        // Apache Commons
        DEFAULT_EXCLUDES.add("commons-codec-*.jar");
        DEFAULT_EXCLUDES.add("commons-lang-*.jar");
        // Apache HTTP Components
        DEFAULT_EXCLUDES.add("httpclient-*.jar");
        DEFAULT_EXCLUDES.add("httpcore-*.jar");
        // ASM
        DEFAULT_EXCLUDES.add("asm-*.jar");
        // AspectJ
        DEFAULT_EXCLUDES.add("aspectjweaver-*.jar");
        // Bouncy Castle JCE provider
        DEFAULT_EXCLUDES.add("bcprov*.jar");
        DEFAULT_EXCLUDES.add("bcpkix*.jar");
        // Closure compiler
        DEFAULT_EXCLUDES.add("closure-compiler-*.jar");
        // Eclipse compiler for Java
        DEFAULT_EXCLUDES.add("ecj-*.jar");
        // Hystrix
        DEFAULT_EXCLUDES.add("hystrix-core-*.jar");
        DEFAULT_EXCLUDES.add("hystrix-serialization-*.jar");
        // Jackson
        DEFAULT_EXCLUDES.add("jackson-annotations-*.jar");
        DEFAULT_EXCLUDES.add("jackson-core-*.jar");
        DEFAULT_EXCLUDES.add("jackson-module-afterburner-*.jar");
        // Logging
        DEFAULT_EXCLUDES.add("jul-to-slf4j-*.jar");
        DEFAULT_EXCLUDES.add("log4j-to-slf4j-*.jar");
        DEFAULT_EXCLUDES.add("slf4j-api-*.jar");
        // Spring
        DEFAULT_EXCLUDES.add("spring-aop-*.jar");
        DEFAULT_EXCLUDES.add("spring-expression-*.jar");
        DEFAULT_EXCLUDES.add("spring-security-crypto-*.jar");
        DEFAULT_EXCLUDES.add("spring-security-rsa-*.jar");
    }

    private EESpecProfile profile = EESpecProfile.TOMCAT;

    private boolean enableDefaultExcludes = true;
    private boolean zipInMemory;
    private File source;
    private File destination;
    private final List<Converter> converters;
    private final Set<String> excludes = new HashSet<>();

    public Migration() {
        // Initialise the converters
        converters = new ArrayList<>();

        converters.add(new TextConverter());
        converters.add(new ClassConverter());
        converters.add(new ManifestConverter());

        // Final converter is the pass-through converter
        converters.add(new PassThroughConverter());
    }

    /**
     * Set the Jakarta EE specifications that should be used.
     *
     * @param profile the Jakarta EE specification profile
     */
    public void setEESpecProfile(EESpecProfile profile) {
        this.profile = profile;
    }

    /**
     * Get the Jakarta EE profile being used.
     *
     * @return the profile
     */
    public EESpecProfile getEESpecProfile() {
        return profile;
    }

    public void setEnableDefaultExcludes(boolean enableDefaultExcludes) {
        this.enableDefaultExcludes = enableDefaultExcludes;
    }

    public void setZipInMemory(boolean zipInMemory) {
        this.zipInMemory = zipInMemory;
    }

    public void addExclude(String exclude) {
        this.excludes.add(exclude);
    }

    public void setSource(File source) {
        if (!source.canRead()) {
            throw new IllegalArgumentException(sm.getString("migration.cannotReadSource",
                    source.getAbsolutePath()));
        }
        this.source = source;
    }


    public void setDestination(File destination) {
        this.destination = destination;
    }


    public boolean execute() throws IOException {
        boolean converted;

        logger.log(Level.INFO, sm.getString("migration.execute", source.getAbsolutePath(),
                destination.getAbsolutePath(), profile.toString()));

        long t1 = System.nanoTime();
        if (source.isDirectory()) {
            if ((destination.exists() && destination.isDirectory()) || destination.mkdirs()) {
                converted = migrateDirectory(source, destination);
            } else {
                throw new IOException(sm.getString("migration.mkdirError", destination.getAbsolutePath()));
            }
        } else {
            // Single file`
            File parentDestination = destination.getAbsoluteFile().getParentFile();
            if (parentDestination.exists() || parentDestination.mkdirs()) {
                converted = migrateFile(source, destination);
            } else {
                throw new IOException(sm.getString("migration.mkdirError", parentDestination.getAbsolutePath()));
            }
        }
        logger.log(Level.INFO, sm.getString("migration.done",
                TimeUnit.MILLISECONDS.convert(System.nanoTime() - t1, TimeUnit.NANOSECONDS)));

        return converted;
    }


    private boolean migrateDirectory(File src, File dest) throws IOException {
        boolean converted = false;

        // Won't return null because src is known to be a directory
        String[] files = src.list();
        for (String file : files) {
            File srcFile = new File(src, file);
            File destFile = new File(dest, file);
            if (srcFile.isDirectory()) {
                if ((destFile.exists() && destFile.isDirectory()) || destFile.mkdir()) {
                    converted = converted | migrateDirectory(srcFile, destFile);
                } else {
                    throw new IOException(sm.getString("migration.mkdirError", destFile.getAbsolutePath()));
                }
            } else {
                converted = converted | migrateFile(srcFile, destFile);
            }
        }

        return converted;
    }


    private boolean migrateFile(File src, File dest) throws IOException {
        boolean converted;

        boolean inplace = src.equals(dest);
        if (!inplace) {
            try (InputStream is = new FileInputStream(src);
                    OutputStream os = new FileOutputStream(dest)) {
                converted = migrateStream(src.getName(), is, os);
            }
        } else {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream((int) (src.length() * 1.05));

            try (InputStream is = new FileInputStream(src)) {
                converted = migrateStream(src.getName(), is, buffer);
            }

            try (OutputStream os = new FileOutputStream(dest)) {
                os.write(buffer.toByteArray());
            }
        }

        return converted;
    }


    private boolean migrateArchiveStreaming(InputStream src, OutputStream dest) throws IOException {
        boolean converted = false;

        try (ZipArchiveInputStream srcZipStream = new ZipArchiveInputStream(CloseShieldInputStream.wrap(src));
                ZipArchiveOutputStream destZipStream = new ZipArchiveOutputStream(CloseShieldOutputStream.wrap(dest))) {
            ZipArchiveEntry srcZipEntry;
            CRC32 crc32 = new CRC32();
            while ((srcZipEntry = srcZipStream.getNextZipEntry()) != null) {
                String srcName = srcZipEntry.getName();
                if (isSignatureFile(srcName)) {
                    logger.log(Level.WARNING, sm.getString("migration.skipSignatureFile", srcName));
                    continue;
                }
                String destName = profile.convert(srcName);
                if (srcZipEntry.getMethod() == ZipEntry.STORED) {
                    ByteArrayOutputStream tempBuffer = new ByteArrayOutputStream((int) (srcZipEntry.getSize() * 1.05));
                    converted = converted | migrateStream(srcName, srcZipStream, tempBuffer);
                    crc32.update(tempBuffer.toByteArray(), 0, tempBuffer.size());
                    MigrationZipArchiveEntry destZipEntry = new MigrationZipArchiveEntry(srcZipEntry);
                    destZipEntry.setName(destName);
                    destZipEntry.setSize(tempBuffer.size());
                    destZipEntry.setCrc(crc32.getValue());
                    destZipStream.putArchiveEntry(destZipEntry);
                    tempBuffer.writeTo(destZipStream);
                    destZipStream.closeArchiveEntry();
                    crc32.reset();
                } else {
                    MigrationZipArchiveEntry destZipEntry = new MigrationZipArchiveEntry(srcZipEntry);
                    destZipEntry.setName(destName);
                    destZipStream.putArchiveEntry(destZipEntry);
                    converted = converted | migrateStream(srcName, srcZipStream, destZipStream);
                    destZipStream.closeArchiveEntry();
                }
            }
        }

        return converted;
    }


    private boolean migrateArchiveInMemory(InputStream src, OutputStream dest) throws IOException {
        boolean converted = false;

        // Read the source into memory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        IOUtils.copy(src, baos);
        baos.flush();
        SeekableInMemoryByteChannel srcByteChannel = new SeekableInMemoryByteChannel(baos.toByteArray());
        // Create the destination in memory
        SeekableInMemoryByteChannel destByteChannel = new SeekableInMemoryByteChannel();

        try (ZipFile srcZipFile = new ZipFile(srcByteChannel);
                ZipArchiveOutputStream destZipStream = new ZipArchiveOutputStream(destByteChannel)) {
            Enumeration<ZipArchiveEntry> entries = srcZipFile.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry srcZipEntry = entries.nextElement();
                String srcName = srcZipEntry.getName();
                if (isSignatureFile(srcName)) {
                    logger.log(Level.WARNING, sm.getString("migration.skipSignatureFile", srcName));
                    continue;
                }
                String destName = profile.convert(srcName);
                MigrationZipArchiveEntry destZipEntry = new MigrationZipArchiveEntry(srcZipEntry);
                destZipEntry.setName(destName);
                destZipStream.putArchiveEntry(destZipEntry);
                converted = converted | migrateStream(srcName, srcZipFile.getInputStream(srcZipEntry), destZipStream);
                destZipStream.closeArchiveEntry();
            }
        }

        // Write the destination back to the stream
        ByteArrayInputStream bais = new ByteArrayInputStream(destByteChannel.array(), 0, (int) destByteChannel.size());
        IOUtils.copy(bais, dest);

        return converted;
    }


    private boolean isSignatureFile(String sourceName) {
        return sourceName.startsWith("META-INF/") && (
                sourceName.endsWith(".SF") ||
                sourceName.endsWith(".RSA") ||
                sourceName.endsWith(".DSA") ||
                sourceName.endsWith(".EC")
                );
    }


    private boolean migrateStream(String name, InputStream src, OutputStream dest) throws IOException {
        boolean converted = false;

        if (isExcluded(name)) {
            Util.copy(src, dest);
            logger.log(Level.INFO, sm.getString("migration.skip", name));
        } else if (isArchive(name)) {
            if (zipInMemory) {
                logger.log(Level.INFO, sm.getString("migration.archive.memory", name));
                converted = migrateArchiveInMemory(src, dest);
                logger.log(Level.INFO, sm.getString("migration.archive.complete", name));
            } else {
                logger.log(Level.INFO, sm.getString("migration.archive.stream", name));
                converted = migrateArchiveStreaming(src, dest);
                logger.log(Level.INFO, sm.getString("migration.archive.complete", name));
            }
        } else {
            for (Converter converter : converters) {
                if (converter.accepts(name)) {
                    converted = converted | converter.convert(name, src, dest, profile);
                    break;
                }
            }
        }

        return converted;
    }


    private boolean isArchive(String fileName) {
        return fileName.endsWith(".jar") || fileName.endsWith(".war") || fileName.endsWith(".zip");
    }


    private boolean isExcluded(String name) {
        File f = new File(name);
        String filename = f.getName();

        if (enableDefaultExcludes && GlobMatcher.matchName(DEFAULT_EXCLUDES, filename, true)) {
            return true;
        }

        if (GlobMatcher.matchName(excludes, filename, true)) {
            return true;
        }

        return false;
    }

    private static class MigrationZipArchiveEntry extends ZipArchiveEntry {

        MigrationZipArchiveEntry(ZipArchiveEntry entry) throws ZipException {
            super(entry);
        }

        @Override
        public void setName(String name) {
            super.setName(name);
        }
    }
}
