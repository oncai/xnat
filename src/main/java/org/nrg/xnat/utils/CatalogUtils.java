/*
 * web: org.nrg.xnat.utils.CatalogUtils
 * XNAT http://www.xnat.org
 * Copyright (c) 2005-2017, Washington University School of Medicine and Howard Hughes Medical Institute
 * All Rights Reserved
 *
 * Released under the Simplified BSD.
 */

package org.nrg.xnat.utils;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang3.StringUtils;
import org.nrg.config.entities.Configuration;
import org.nrg.config.exceptions.ConfigServiceException;
import org.nrg.framework.constants.Scope;
import org.nrg.xdat.XDAT;
import org.nrg.xdat.bean.*;
import org.nrg.xdat.bean.base.BaseElement;
import org.nrg.xdat.bean.reader.XDATXMLReader;
import org.nrg.xdat.model.*;
import org.nrg.xdat.om.*;
import org.nrg.xft.XFTTable;
import org.nrg.xft.event.EventMetaI;
import org.nrg.xft.event.EventUtils;
import org.nrg.xft.security.UserI;
import org.nrg.xft.utils.FileUtils;
import org.nrg.xft.utils.zip.TarUtils;
import org.nrg.xft.utils.zip.ZipI;
import org.nrg.xft.utils.zip.ZipUtils;
import org.nrg.xnat.helpers.resource.XnatResourceInfo;
import org.nrg.xnat.presentation.ChangeSummaryBuilderA;
import org.nrg.xnat.restlet.util.FileWriterWrapperI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.channels.FileLock;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipOutputStream;
import java.security.MessageDigest;

/**
 * @author timo
 */
@SuppressWarnings("deprecation")
public class CatalogUtils {

    public final static String[] FILE_HEADERS = {"Name", "Size", "URI", "collection", "file_tags", "file_format", "file_content", "cat_ID", "digest"};
    public final static String[] FILE_HEADERS_W_FILE = {"Name", "Size", "URI", "collection", "file_tags", "file_format", "file_content", "cat_ID", "file", "digest"};

    public static boolean getChecksumConfiguration(final XnatProjectdata project) throws ConfigServiceException {
        final String projectId = project.getId();
        final Configuration configuration = XDAT.getConfigService().getConfig("checksums", "checksums", StringUtils.isBlank(projectId) ? Scope.Site : Scope.Project, projectId);

        if (configuration != null) {
            final String checksumProperty = XDAT.getSiteConfigurationProperty("checksums");
            if (!StringUtils.isBlank(checksumProperty)) {
                return Boolean.parseBoolean(checksumProperty);
            }
        }

        return getChecksumConfiguration();
    }

    public static Boolean getChecksumConfiguration() throws ConfigServiceException {
        if (_checksumConfig == null) {
            String checksumProperty = XDAT.getSiteConfigurationProperty("checksums");
            if (!StringUtils.isBlank(checksumProperty)) {
                _checksumConfig = Boolean.parseBoolean(checksumProperty);
            }
        }
        return _checksumConfig;
    }

    /**
     * This sets the cached value for the checksum configuration. Note that this does <i>not</i> set the persisted
     * configuration value for the checksum configuration. This is used by the {@link org.nrg.xnat.utils.ChecksumsSiteConfigurationListener}
     * to update the cached value whenever the database value is changed elsewhere.
     *
     * @param checksumConfig The value to set for the cached checksum configuration setting.
     * @return The previous value for the cached checksum configuration setting.
     */
    public static Boolean setChecksumConfiguration(boolean checksumConfig) {
        Boolean hold = _checksumConfig;
        _checksumConfig = checksumConfig;
        return hold;
    }

    public static void calculateResourceChecksums(final CatCatalogI cat, final File f) {
        for (CatEntryI entry : cat.getEntries_entry()) {
            CatalogUtils.setChecksum(entry, f.getParent());
        }
    }

    /**
     * Set digest field on entry with corresponding MD5
     *
     * @param entry CatEntryI for operation
     * @param path  Path to catalog (used for relative paths)
     * @return true if entry was modified, false if not.
     */
    private static boolean setChecksum(final CatEntryI entry, final String path) {
        if (StringUtils.isBlank(entry.getDigest())) {//this should only occur if the MD5 isn't already there.
            final File file = CatalogUtils.getFile(entry, path);//this will allow absolute paths to be functional.  Catalogs are sometimes generated by client tools. They may not stay relative to the catalog, as XNAT would make them.
            if (file != null && file.exists()) {//fail safe to missing files, maybe the files haven't been put in place yet...
                final String checksum = getHash(file);
                if (StringUtils.isNotBlank(checksum)) {
                    entry.setDigest(checksum);
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Calculates a checksum hash for the submitted file based on the currently configured hash algorithm. Note that
     * currently XNAT only supports MD5 hashes. If an error that occurs while calculating the checksum, the error is
     * logged and this method returns an empty string.
     *
     * @param file The file for which the checksum should be calculated.
     *
     * @return The checksum for the file if successful, an empty string otherwise.
     */
    @Nonnull
    public static String getHash(final File file) {
        //try {
        //    return MD5.asHex(MD5.getHash(file));
        //} catch (IOException e) {
        //    logger.error("An error occurred calculating the checksum for a file at the path: " + file.getPath(), e);
        //    return "";
        //}
        //faster to use java's MD5 than FastMD5 (com.twmacinta.util.MD5) library
        return getFileHash(file.getAbsolutePath(), file.length());
    }

    /**
     * Speed up getHash using java7, allow for use of other hashing algorithms
     */
    @Nonnull
    private static String getFileHash(String filepath, long size) {
        return getFileHash(filepath, size, "MD5");
    }

    @Nonnull
    private static String getFileHash(String filepath, long size, String hash_type) {
        String digest = "";
        int buf_size;
        if (size < 512) {
            buf_size = 512;
        } else if (size > 65536) {
            buf_size = 65536;
        } else {
            buf_size = (int) size;
        }
        try {
            //read into buffer and update md5
            MessageDigest md5 = MessageDigest.getInstance(hash_type);
            RandomAccessFile store = new RandomAccessFile(filepath, "r");
            FileChannel channel = store.getChannel();
            ByteBuffer buffer = ByteBuffer.allocate(buf_size);
            channel.read(buffer);
            buffer.flip();
            md5.update(buffer);
            channel.close();
            store.close();

            //compute hex
            digest = org.apache.commons.codec.binary.Hex.encodeHexString(md5.digest());
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        } catch (NoSuchAlgorithmException e) {
            logger.error("Unsupported hashing algorithm " + hash_type, e);
        }
        return digest;
    }

    public static List<Object[]> getEntryDetails(CatCatalogI cat, String parentPath, String uriPath, XnatResource _resource, boolean includeFile, final CatEntryFilterI filter, XnatProjectdata proj, String locator) {
        final ArrayList<Object[]> al = new ArrayList<>();
        for (final CatCatalogI subset : cat.getSets_entryset()) {
            al.addAll(getEntryDetails(subset, parentPath, uriPath, _resource, includeFile, filter, proj, locator));
        }

        for (final CatEntryI entry : cat.getEntries_entry()) {
            if (filter == null || filter.accept(entry)) {
                final List<Object> row = Lists.newArrayList();
                final String entryPath = StringUtils.replace(FileUtils.AppendRootPath(parentPath, entry.getUri()), "\\", "/");
                final File file = getFileOnLocalFileSystem(entryPath);
                assert file != null;
                row.add(file.getName());
                row.add(includeFile ? 0 : file.length());
                if (locator.equalsIgnoreCase("URI")) {
                    row.add(FileUtils.IsAbsolutePath(entry.getUri()) ? uriPath + "/" + entry.getId() : uriPath + "/" + entry.getUri());
                } else if (locator.equalsIgnoreCase("absolutePath")) {
                    row.add(entryPath);
                } else if (locator.equalsIgnoreCase("projectPath")) {
                    row.add(entryPath.substring(proj.getRootArchivePath().substring(0, proj.getRootArchivePath().lastIndexOf(proj.getId())).length()));
                } else {
                    row.add("");
                }
                row.add(_resource.getLabel());
                final List<String> fieldsAndTags = Lists.newArrayList();
                for (CatEntryMetafieldI meta : entry.getMetafields_metafield()) {
                    fieldsAndTags.add(meta.getName() + "=" + meta.getMetafield());
                }
                for (CatEntryTagI tag : entry.getTags_tag()) {
                    fieldsAndTags.add(tag.getTag());
                }
                row.add(Joiner.on(",").join(fieldsAndTags));
                row.add(entry.getFormat());
                row.add(entry.getContent());
                row.add(_resource.getXnatAbstractresourceId());
                if (includeFile) {
                    row.add(file);
                }
                row.add(entry.getDigest());
                al.add(row.toArray());
            }
        }

        return al;
    }

    /**
     * Takes a size of a file or heap of memory in the form of a long and returns a formatted readable version in the
     * form of byte units. For example, 46 would become 46B, 1,024 would become 1KB, 1,048,576 would become 1MB, etc.
     *
     * @param size The size in bytes to be formatted.
     * @return A formatted string representing the byte size.
     */
    public static String formatSize(long size) {
        if (size < 1024) {
            return size + " B";
        }
        int exp = (int) (Math.log(size) / Math.log(1024));
        return String.format("%.1f %sB", size / Math.pow(1024, exp), "KMGTPE".charAt(exp - 1));
    }

    /**
     * Formats an object's file statistics for display.
     *
     * @param label     The label of the object (session, scan, resource, etc.)
     * @param fileCount The number of files that compose the object.
     * @param rawSize   The size of the files that compose the object.
     * @return A formatted display of the file statistics.
     */
    public static String formatFileStats(final String label, final long fileCount, final Object rawSize) {
        long size = 0;
        if (rawSize != null) {
            if (rawSize instanceof Integer) {
                size = (Integer) rawSize;
            } else if (rawSize instanceof Long) {
                size = (Long) rawSize;
            }
        }
        if (label == null || label.equals("") || label.equalsIgnoreCase("total")) {
            return String.format("%s in %s files", formatSize(size), fileCount);
        }
        return String.format("%s: %s in %s files", label, formatSize(size), fileCount);
    }

    public static Map<File, CatEntryI> getCatalogEntriesForFiles(final String rootPath, final XnatResourcecatalog catalog, final List<File> files) {
        final File catFile = catalog.getCatalogFile(rootPath);
        final String parentPath = catFile.getParent();
        final CatCatalogBean cat = getCatalog(rootPath, catalog);

        final Map<File, CatEntryI> entries = Maps.newHashMap();
        if (cat != null) {
            for (final CatEntryI entry : cat.getEntries_entry()) {
                final String entryPath = StringUtils.replace(FileUtils.AppendRootPath(parentPath, entry.getUri()), "\\", "/");
                final File file = getFileOnLocalFileSystem(entryPath);
                if (file != null && files.contains(file)) {
                    entries.put(file, entry);
                }
            }
        }
        return entries;
    }


    /**
     * Reviews the catalog directory and adds any files that aren't already referenced in the catalog,
     *  removes any that have been deleted, computes checksums, and updates catalog stats.
     *
     * @param catRes                catalog resource
     * @param catFile               path to catalog xml file
     * @param cat                   content of catalog xml file
     * @param user                  user for transaction
     * @param event_id              event id for transaction
     * @param addUnreferencedFiles  adds files not referenced in catalog
     * @param removeMissingFiles    removes files referenced in catalog but not on filesystem
     * @param populateStats         updates file count & size for catRes in XNAT db
     * @param checksums             computes/updates checksums
     * @return new Object[] { modified, audit_summary }     modified: true if cat modified and needs save
     *                                                      audit_summary: audit hashmap
     */
    public static Object[] refreshCatalog(XnatResourcecatalog catRes, final File catFile, final CatCatalogBean cat,
                                         final UserI user, final Number event_id,
                                         final boolean addUnreferencedFiles, final boolean removeMissingFiles,
                                         final boolean populateStats, final boolean checksums) {

        final AtomicLong size = new AtomicLong(0);
        final AtomicInteger count = new AtomicInteger(0);
        final URI catFolderURI = catFile.getParentFile().toURI();
        final Date now = Calendar.getInstance().getTime();
        boolean modified = false;
        final int event_id_int = Integer.parseInt(event_id.toString());

        //Needed for audit summary
        final AtomicInteger added = new AtomicInteger(0);
        final AtomicInteger modded = new AtomicInteger(0);

        //Build a hashmap so that instead of repeatedly looping through all the catalog entries,
        //comparing URI to our relative path, we can just do an O(1) lookup in our hashmap
        final HashMap<String, Object[]> catalog_map = buildCatalogMap(cat);

        final AtomicInteger rtn = new AtomicInteger(0);
        final XnatResourceInfo info = XnatResourceInfo.buildResourceInfo(null, null,
                null, null, user, now, now, event_id);

        try {
            Files.walkFileTree(catFile.getParentFile().toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    boolean mod = false;
                    File f = file.toFile();

                    if (f.equals(catFile)) {
                        //don't add the catalog xml to its own list
                        return FileVisitResult.CONTINUE;
                    }

                    //verify that there is only one catalog xml in this directory
                    //fail if more then one is present -- otherwise they will be merged.
                    if (f.getName().endsWith(".xml") && isCatalogFile(f)) {
                        logger.error("Multiple catalog files - not refreshing");
                        rtn.set(-1);
                        return FileVisitResult.TERMINATE;
                    }

                    //check if file exists in catalog already
                    final String full_path = f.getAbsolutePath();
                    final String relative = catFolderURI.relativize(f.toURI()).getPath();

                    if (catalog_map.containsKey(relative)) {
                        //map_entry[0] is the catalog entry
                        //map_entry[1] is the catalog or entryset containing the above catalog entry
                        //map_entry[2] is id prefix based on the catalog + entryset containing the entry
                        //map_entry[3] is a bool for existing on filesystem
                        Object[] map_entry = catalog_map.get(relative);
                        CatEntryBean entry = (CatEntryBean) map_entry[0];
                        map_entry[3] = true; //mark that file exists

                        //logic mimics CatalogUtils.formalizeCatalog(cat, catFile.getParent(), user, now, checksums, removeMissingFiles);
                        //older catalog files might have missing entries?
                        if (entry.getCreatedby() == null && user != null) {
                            entry.setCreatedby(user.getUsername());
                            mod = true;
                        }
                        if (entry.getCreatedtime() == null) {
                            entry.setCreatedtime(now);
                            mod = true;
                        }
                        if (entry.getCreatedeventid() == null && event_id != null) {
                            entry.setCreatedeventid(event_id.toString());
                            mod = true;
                        }
                        if (StringUtils.isEmpty(entry.getId())) {
                            entry.setId(map_entry[2] + "/" + f.getName());
                            mod = true;
                        }
                        // CatDcmentryBeans fail to set format correctly because it's not in their xml
                        if (entry.getClass().equals(CatDcmentryBean.class)) {
                            entry.setFormat("DICOM");
                            mod = true;
                        }

                        //this used to be run as part of writeCatalogFile
                        //however, that code didn't update checksums if they'd changed
                        if (checksums) {
                            String digest = getFileHash(full_path, attrs.size());
                            if (!StringUtils.isEmpty(digest) && !digest.equals(entry.getDigest())) {
                                entry.setDigest(digest);
                                if (user != null) entry.setModifiedby(user.getUsername());
                                entry.setModifiedtime(now);
                                entry.setModifiedeventid(event_id_int);
                                mod = true;
                                modded.getAndIncrement();
                            }
                        }

                        //this used to be run as populateStats
                        if (populateStats) {
                            size.addAndGet(attrs.size());
                            count.getAndIncrement();
                        }
                    } else {
                        if (addUnreferencedFiles) {
                            CatEntryBean newEntry = populateAndAddCatEntry(cat,relative,f.getName(),info);

                            //this used to be run as part of writeCatalogFile
                            if (checksums) {
                                newEntry.setDigest(getFileHash(full_path, attrs.size()));
                            }

                            mod = true;

                            //this used to be run as populateStats
                            //this conditional has to be inside the "addUnreferencedFiles" conditional so that the stats
                            //don't go out of sync with the catalog (if files aren't added, they shouldn't be counted)
                            if (populateStats) {
                                size.addAndGet(attrs.size());
                                count.getAndIncrement();
                            }

                            added.getAndIncrement();
                        }
                    }

                    //if we traverse any file and modify its entry, set rtn=1
                    //if no file entries are modified during the whole walk, rtn will remain 0
                    if (mod) rtn.set(1);

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    // Skip dirs that can't be traversed
                    logger.error("Skipped: " + file + " (" + e.getMessage() + ")", e);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    // Ignore and log errors traversing a dir
                    if (e != null) {
                        logger.error("Error traversing: " + dir + " (" + e.getMessage() + ")",e);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new AssertionError("Files.walkFileTree shouldn't throw IOException since we modified " +
                    "SimpleFileVisitor not to do so");
        }

        int rtn_val = rtn.get();
        if (rtn_val == -1) {
            //multiple catalog files
            return new Object[] {false, null};
        }
        modified = rtn_val == 1;

        int nremoved = 0;
        //this used to be run as part of formalizeCatalog
        if (removeMissingFiles) {
            for (Object[] map_entry : catalog_map.values()) {
                //map_entry[0] is the catalog entry
                //map_entry[1] is the catalog or entryset containing the above catalog entry
                //map_entry[2] is id prefix based on the catalog + entryset containing the entry
                //map_entry[3] is a bool for existing on filesystem
                if (!(boolean)map_entry[3]) {
                    //File wasn't visited
                    //logger.info("Removing "+((CatEntryBean)map_entry[0]).getName());
                    ((CatCatalogBean)map_entry[1]).getEntries_entry().remove(map_entry[0]);
                    modified = true;
                    nremoved++;
                }
            }
        }

        //Add to audit_summary
        Map<String, Map<String, Integer>> audit_summary = new HashMap<>();
        if (nremoved > 0)
            addAuditEntry(audit_summary, event_id_int, now, ChangeSummaryBuilderA.REMOVED, nremoved);
        int nmod = modded.get();
        if (nmod > 0)
            addAuditEntry(audit_summary, event_id_int, now, ChangeSummaryBuilderA.MODIFIED, nmod);
        int nadded = added.get();
        if (nadded > 0)
            addAuditEntry(audit_summary, event_id_int, now, ChangeSummaryBuilderA.ADDED, nadded);

        //Update resource with new file count & size
        // This is going to implicitly removeMissingFiles, since file count & size attributes are computed
        // while walking the file tree. If removeMissingFiles isn't specified, file count & size
        // won't match the catalog xml. This has always been the case, since you can't compute file size for
        // a non-existent file. It used to implicitly addUnreferencedFiles, too (see populateStats method).
        if (populateStats) {
            Integer c = count.get();
            Long s = size.get();
            if (!c.equals(catRes.getFileCount())) {
                catRes.setFileCount(c);
                modified = true;
            }

            if (!s.equals(catRes.getFileSize())) {
                catRes.setFileSize(s);
                modified = true;
            }
        }
        return new Object[]{ modified, audit_summary};
    }

    private static HashMap<String, Object[]> buildCatalogMap(CatCatalogI cat) {
        return buildCatalogMap(cat, "");
    }

    private static HashMap<String, Object[]> buildCatalogMap(CatCatalogI cat, String id_prefix) {
        HashMap<String, Object[]> catalog_map = new HashMap<String, Object[]>();
        for (CatCatalogI subset : cat.getSets_entryset()) {
            catalog_map.putAll(buildCatalogMap(subset, cat.getId() + "/"));
        }

        List<CatEntryI> entries = cat.getEntries_entry();
        for (int i = 0; i< entries.size(); i++) {
            CatEntryI entry = entries.get(i);
            //map_entry[0] is the catalog entry
            //map_entry[1] is the catalog or entryset containing the above catalog entry
            //map_entry[2] is id prefix based on the catalog + entryset containing the entry
            //map_entry[3] is a bool for existing on filesystem
            Object[] map_entry = new Object[] {entry, cat, id_prefix + cat.getId(), false};
            catalog_map.put(entry.getUri(),map_entry);
        }

        return catalog_map;
    }

    public interface CatEntryFilterI {
        boolean accept(final CatEntryI entry);
    }

    public static CatEntryI getEntryByFilter(final CatCatalogI cat, final CatEntryFilterI filter) {
        CatEntryI e;
        for (CatCatalogI subset : cat.getSets_entryset()) {
            e = getEntryByFilter(subset, filter);
            if (e != null) return e;
        }

        for (CatEntryI entry : cat.getEntries_entry()) {
            try {
                if (filter.accept(entry)) {
                    return entry;
                }
            } catch (Exception exception) {
                logger.error("Error occurred filtering catalog entry: " + entry, exception);
            }
        }

        return null;
    }

    public static Collection<CatEntryI> getEntriesByFilter(final CatCatalogI cat, final CatEntryFilterI filter) {
        List<CatEntryI> entries = new ArrayList<>();

        for (CatCatalogI subset : cat.getSets_entryset()) {
            entries.addAll(getEntriesByFilter(subset, filter));
        }

        for (CatEntryI entry : cat.getEntries_entry()) {
            try {
                if (filter == null || filter.accept(entry)) {
                    entries.add(entry);

                }
            } catch (Exception exception) {
                logger.error("Error occurred filtering catalog entry: " + entry, exception);
            }
        }

        return entries;
    }

    @SuppressWarnings("unused")
    public static CatCatalogI getCatalogByFilter(final CatCatalogI cat) {
        CatCatalogI e;
        for (CatCatalogI subset : cat.getSets_entryset()) {
            e = getCatalogByFilter(subset);
            if (e != null) return e;
        }

        return null;
    }

    public static List<File> getFiles(CatCatalogI cat, String parentPath) {
        List<File> al = new ArrayList<>();
        for (CatCatalogI subset : cat.getSets_entryset()) {
            al.addAll(getFiles(subset, parentPath));
        }

        for (CatEntryI entry : cat.getEntries_entry()) {
            String entryPath = StringUtils.replace(FileUtils.AppendRootPath(parentPath, entry.getUri()), "\\", "/");
            File f = getFileOnLocalFileSystem(entryPath);

            if (f != null)
                al.add(f);
        }

        return al;
    }

    /**
     * Gets file from file system.  This method supports relative or absolute paths in the CatEntryI. It also supports
     * files that are gzipped on the file system, but don't include .gz in the catalog URI (this used to be very common).
     *
     * @param entry      Catalog Entry for file to be retrieved
     * @param parentPath Path to catalog file directory
     * @return File object represented by CatEntryI
     */
    public static File getFile(CatEntryI entry, String parentPath) {
        String entryPath = StringUtils.replace(FileUtils.AppendRootPath(parentPath, entry.getUri()), "\\", "/");
        return getFileOnLocalFileSystem(entryPath);
    }

    public static Stats getFileStats(CatCatalogI cat, String parentPath) {
        return new Stats(cat, parentPath);
    }

    public static class Stats {
        public int count;
        public long size;

        public Stats(CatCatalogI cat, String parentPath) {
            count = 0;
            size = 0;
            for (final File f : getFiles(cat, parentPath)) {
                if (f != null && f.exists() && !f.getName().endsWith("catalog.xml")) {
                    count++;
                    size += f.length();
                }
            }
        }
    }

    public static Collection<CatEntryI> getEntriesByRegex(final CatCatalogI cat, String regex) {
        List<CatEntryI> entries = new ArrayList<>();
        for (CatCatalogI subset : cat.getSets_entryset()) {
            entries.addAll(getEntriesByRegex(subset, regex));
        }
        for (CatEntryI entry : cat.getEntries_entry()) {
            try {
                if (entry.getUri().matches(regex)) {
                    entries.add(entry);
                }
            } catch (Exception exception) {
                logger.error("Error occurred testing catalog entry: " + entry, exception);
            }
        }
        return entries;
    }

    public static Collection<String> getURIs(CatCatalogI cat) {
        Collection<String> all = Lists.newArrayList();
        for (CatCatalogI subset : cat.getSets_entryset()) {
            all.addAll(getURIs(subset));
        }

        for (CatEntryI entry : cat.getEntries_entry()) {
            all.add(entry.getUri());
        }

        return all;
    }

    public static boolean checkEntryByURI(String content, String uri) {
        return StringUtils.contains(content,"URI=\"" + uri + "\"");
    }

    public static CatEntryI getEntryByURI(CatCatalogI cat, String name) {
        CatEntryI e;
        for (CatCatalogI subset : cat.getSets_entryset()) {
            e = getEntryByURI(subset, name);
            if (e != null) return e;
        }

        for (CatEntryI entry : cat.getEntries_entry()) {
            if (entry.getUri().equals(name)) {
                return entry;
            }
        }

        //do the decoded check after the basic match.  URLDecoder is horribly non-performant.
        final String decoded = URLDecoder.decode(name);
        if (decoded != null) {
            for (CatEntryI entry : cat.getEntries_entry()) {
                if ((entry.getUri().equals(decoded))) {
                    return entry;
                }
            }
        }

        return null;
    }

    @SuppressWarnings("unused")
    public static CatEntryI getEntryByName(CatCatalogI cat, String name) {
        CatEntryI e;
        for (CatCatalogI subset : cat.getSets_entryset()) {
            e = getEntryByName(subset, name);
            if (e != null) return e;
        }

        for (CatEntryI entry : cat.getEntries_entry()) {
            String decoded = URLDecoder.decode(name);
            if (entry.getName().equals(name) || entry.getName().equals(decoded)) {
                return entry;
            }
        }

        return null;
    }

    public static CatEntryI getEntryById(CatCatalogI cat, String name) {
        CatEntryI e;
        for (CatCatalogI subset : cat.getSets_entryset()) {
            e = getEntryById(subset, name);
            if (e != null) return e;
        }

        for (CatEntryI entry : cat.getEntries_entry()) {
            if (entry.getId().equals(name)) {
                return entry;
            }
        }

        return null;
    }

    public static CatDcmentryI getDCMEntryByUID(CatCatalogI cat, String uid) {
        CatDcmentryI e;
        for (CatCatalogI subset : cat.getSets_entryset()) {
            e = getDCMEntryByUID(subset, uid);
            if (e != null) return e;
        }

        for (CatEntryI entry : cat.getEntries_entry()) {
            if (entry instanceof CatDcmentryI && ((CatDcmentryI) entry).getUid().equals(uid)) {
                return (CatDcmentryI) entry;
            }
        }

        return null;
    }

    @SuppressWarnings("unused")
    public static CatDcmentryI getDCMEntryByInstanceNumber(CatCatalogI cat, Integer num) {
        CatDcmentryI e;
        for (CatCatalogI subset : cat.getSets_entryset()) {
            e = getDCMEntryByInstanceNumber(subset, num);
            if (e != null) return e;
        }

        for (CatEntryI entry : cat.getEntries_entry()) {
            if (entry instanceof CatDcmentryI && ((CatDcmentryI) entry).getInstancenumber().equals(num)) {
                return (CatDcmentryI) entry;
            }
        }

        return null;
    }

    public static File getFileOnLocalFileSystem(String fullPath) {
        File f = new File(fullPath);
        if (!f.exists()) {
            if (!fullPath.endsWith(".gz")) {
                f = new File(fullPath + ".gz");
                if (!f.exists()) {
                    return null;
                }
            } else {
                return null;
            }
        }

        return f;
    }

    public static void configureEntry(final CatEntryBean newEntry, final XnatResourceInfo info, boolean modified) {
        if (info.getDescription() != null) {
            newEntry.setDescription(info.getDescription());
        }
        if (info.getFormat() != null) {
            newEntry.setFormat(info.getFormat());
        }
        if (info.getContent() != null) {
            newEntry.setContent(info.getContent());
        }
        if (info.getTags().size() > 0) {
            for (final String entry : info.getTags()) {
                final CatEntryTagBean t = new CatEntryTagBean();
                t.setTag(entry);
                newEntry.addTags_tag(t);
            }
        }
        if (info.getMeta().size() > 0) {
            for (final Map.Entry<String, String> entry : info.getMeta().entrySet()) {
                final CatEntryMetafieldBean meta = new CatEntryMetafieldBean();
                meta.setName(entry.getKey());
                meta.setMetafield(entry.getValue());
                newEntry.addMetafields_metafield(meta);
            }
        }

        if (modified) {
            if (info.getUser() != null && newEntry.getModifiedby() == null) {
                newEntry.setModifiedby(info.getUser().getUsername());
            }
            if (info.getLastModified() != null) {
                newEntry.setModifiedtime(info.getLastModified());
            }
            if (info.getEvent_id() != null && newEntry.getModifiedeventid() == null) {
                newEntry.setModifiedeventid(info.getEvent_id().toString());
            }
        } else {
            if (info.getUser() != null && newEntry.getCreatedby() == null) {
                newEntry.setCreatedby(info.getUser().getUsername());
            }
            if (info.getCreated() != null && newEntry.getCreatedtime() == null) {
                newEntry.setCreatedtime(info.getCreated());
            }
            if (info.getEvent_id() != null && newEntry.getCreatedeventid() == null) {
                newEntry.setCreatedeventid(info.getEvent_id().toString());
            }
        }

    }

    public static void configureEntry(final XnatResource newEntry, final XnatResourceInfo info, final UserI user) throws Exception {
        if (info.getDescription() != null) {
            newEntry.setDescription(info.getDescription());
        }
        if (info.getFormat() != null) {
            newEntry.setFormat(info.getFormat());
        }
        if (info.getContent() != null) {
            newEntry.setContent(info.getContent());
        }
        if (info.getTags().size() > 0) {
            for (final String entry : info.getTags()) {
                final XnatAbstractresourceTag t = new XnatAbstractresourceTag(user);
                t.setTag(entry);
                newEntry.setTags_tag(t);
            }
        }
        if (info.getMeta().size() > 0) {
            for (final Map.Entry<String, String> entry : info.getMeta().entrySet()) {
                final XnatAbstractresourceTag t = new XnatAbstractresourceTag(user);
                t.setTag(entry.getValue());
                t.setName(entry.getKey());
                newEntry.setTags_tag(t);
            }
        }
    }

    public static List<String> storeCatalogEntry(final List<? extends FileWriterWrapperI> fileWriters, final String destination, final XnatResourcecatalog catResource, final XnatProjectdata proj, final boolean extract, final XnatResourceInfo info, final boolean overwrite, final EventMetaI ci) throws Exception {
        final File catFile = catResource.getCatalogFile(proj.getRootArchivePath());
        final String parentPath = catFile.getParent();
        final CatCatalogBean cat = catResource.getCleanCatalog(proj.getRootArchivePath(), false, null, null);

        List<String> duplicates = new ArrayList<>();

        for (FileWriterWrapperI fileWriter : fileWriters) {
            String filename = fileWriter.getName();

            int index = filename.lastIndexOf('\\');
            if (index < filename.lastIndexOf('/')) {
                index = filename.lastIndexOf('/');
            }

            if (index > 0) {
                filename = filename.substring(index + 1);
            }

            String compression_method = (filename.contains(".")) ? filename.substring(filename.lastIndexOf(".")) : "";

            if (extract && (compression_method.equalsIgnoreCase(".tar") || compression_method.equalsIgnoreCase(".gz") || compression_method.equalsIgnoreCase(".zip") || compression_method.equalsIgnoreCase(".zar"))) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Found archive file " + filename);
                }

                File destinationDir = catFile.getParentFile();
                final InputStream is = fileWriter.getInputStream();

                ZipI zipper;
                if (compression_method.equalsIgnoreCase(".tar")) {
                    zipper = new TarUtils();
                } else if (compression_method.equalsIgnoreCase(".gz")) {
                    zipper = new TarUtils();
                    zipper.setCompressionMethod(ZipOutputStream.DEFLATED);
                } else {
                    zipper = new ZipUtils();
                }

                @SuppressWarnings("unchecked")
                final List<File> files = zipper.extract(is, destinationDir.getAbsolutePath(), overwrite, ci);
                try {
                    String content= org.apache.commons.io.FileUtils.readFileToString(catFile);

                    for (final File f : files) {
                        if (!f.isDirectory()) {
                            //relative path is used to compare to existing catalog entries, and add it if its missing.  entry paths are relative to the location of the catalog file.
                            final String relative = destinationDir.toURI().relativize(f.toURI()).getPath();

                            if (!checkEntryByURI(content,relative)) {
                                populateAndAddCatEntry(cat,relative,f.getName(),info);
                            }
                        }
                    }
                } catch (IOException e) {
                    logger.error(e.getMessage(),e);
                }

                if (!overwrite) {
                    duplicates.addAll(zipper.getDuplicates());
                }
            } else {
                File parentFolder = new File(parentPath);
                final String instance;
                if (!StringUtils.isBlank(fileWriter.getNestedPath())) {
                    instance = makePath(fileWriter.getNestedPath(), filename);
                } else if (StringUtils.isBlank(destination)) {
                    instance = filename;
                } else if (destination.startsWith("/")) {
                    instance = destination.substring(1);
                } else {
                    instance = destination;
                }

                final File saveTo = new File(parentFolder, instance);

                if (saveTo.exists() && !overwrite) {
                    duplicates.add(instance);
                } else {
                    if (saveTo.exists()) {
                        final CatEntryBean e = (CatEntryBean) getEntryByURI(cat, instance);
                        CatalogUtils.moveToHistory(catFile, saveTo, e, ci);
                    }

                    if (!saveTo.getParentFile().mkdirs() && !saveTo.getParentFile().exists()) {
                        throw new Exception("Failed to create required directory: " + saveTo.getParentFile().getAbsolutePath());
                    }

                    if (logger.isDebugEnabled()) {
                        logger.debug("Saving filename " + filename + " to file " + saveTo.getAbsolutePath());
                    }

                    fileWriter.write(saveTo);

                    if (saveTo.isDirectory()) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Found a directory: " + saveTo.getAbsolutePath());
                        }

                        @SuppressWarnings("unchecked")
                        final Iterator<File> iterator = org.apache.commons.io.FileUtils.iterateFiles(saveTo, null, true);
                        while (iterator.hasNext()) {
                            final File movedF = iterator.next();

                            String relativePath = instance + "/" + FileUtils.RelativizePath(saveTo, movedF).replace('\\', '/');
                            updateEntry(cat, relativePath, movedF, info, ci);
                        }

                    } else {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Updating catalog entry for file " + saveTo.getAbsolutePath());
                        }
                        updateEntry(cat, instance, saveTo, info, ci);
                    }
                }
            }
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Writing catalog file " + catFile.getAbsolutePath() + " with " + cat.getEntries_entry().size() + " total entries");
        }

        writeCatalogToFile(cat, catFile);

        return duplicates;
    }

    private static String makePath(String nestedPath, String name) {
        String separator = nestedPath.contains("\\") ? "\\" : "/";
        StringBuilder path = new StringBuilder(nestedPath);
        if (!nestedPath.endsWith(separator)) {
            path.append(separator);
        }
        return path.append(name).toString();
    }

    public static CatCatalogMetafieldI getAuditField(CatCatalogI cat) {
        CatCatalogMetafieldI field = null;
        for (CatCatalogMetafieldI mf : cat.getMetafields_metafield()) {
            if ("AUDIT".equals(mf.getName())) {
                field = mf;
                break;
            }
        }

        if (field == null) {
            field = new CatCatalogMetafieldBean();
            field.setName("AUDIT");
            try {
                cat.addMetafields_metafield(field);
            } catch (Exception ignored) {
            }
        }
        return field;
    }

    public static void refreshAuditSummary(CatCatalogI cat, Map<String, Map<String, Integer>> audit_summary) {
        CatCatalogMetafieldI field = getAuditField(cat);
        if (audit_summary == null) {
            //rebuild from each catalog entry
            audit_summary = buildAuditSummary(cat);
            field.setMetafield(convertAuditToString(audit_summary));
        } else {
            String prev_audit = StringUtils.defaultIfBlank(field.getMetafield(),"");
            String cur_audit = convertAuditToString(audit_summary);
            if (!prev_audit.isEmpty() && !cur_audit.isEmpty()) {
                prev_audit+="|";
            }
            field.setMetafield(prev_audit + cur_audit);
        }
    }

    public static Map<String, Map<String, Integer>> retrieveAuditySummary(CatCatalogI cat) {
        if (cat == null) return new HashMap<>();
        CatCatalogMetafieldI field = null;
        for (CatCatalogMetafieldI mf : cat.getMetafields_metafield()) {
            if ("AUDIT".equals(mf.getName())) {
                field = mf;
                break;
            }
        }

        if (field != null) {
            return convertAuditToMap(field.getMetafield());
        } else {
            return buildAuditSummary(cat);
        }

    }

    public static void addAuditEntry(Map<String, Map<String, Integer>> summary, String key, String action, Integer i) {
        if (!summary.containsKey(key)) {
            summary.put(key, new HashMap<String, Integer>());
        }

        if (!summary.get(key).containsKey(action)) {
            summary.get(key).put(action, 0);
        }

        summary.get(key).put(action, summary.get(key).get(action) + i);
    }

    public static void addAuditEntry(Map<String, Map<String, Integer>> summary, Integer eventId, Object d, String action, Integer i) {
        String key = eventId + ":" + d;
        addAuditEntry(summary, key, action, i);
    }

    public static void writeCatalogToFile(CatCatalogI xml, File dest) throws Exception {
        try {
            writeCatalogToFile(xml, dest, getChecksumConfiguration());
        } catch (ConfigServiceException exception) {
            throw new Exception("Error attempting to retrieve checksum configuration", exception);
        }
    }

    public static void writeCatalogToFile(CatCatalogI xml, File dest, boolean calculateChecksums) throws Exception {
        writeCatalogToFile(xml, dest, calculateChecksums, null);
    }

    public static void writeCatalogToFile(CatCatalogI xml, File dest, boolean calculateChecksums,
                                          Map<String, Map<String, Integer>> audit_summary) throws Exception {
        if (calculateChecksums) {
            CatalogUtils.calculateResourceChecksums(xml, dest);
        }

        if (!dest.getParentFile().exists()) {
            if (!dest.getParentFile().mkdirs() && !dest.getParentFile().exists()) {
                throw new Exception("Failed to create required directory: " + dest.getParentFile().getAbsolutePath());
            }
        }

        refreshAuditSummary(xml, audit_summary);

        try (final FileOutputStream fos = new FileOutputStream(dest)) {
            final FileLock fl = fos.getChannel().lock();
            try {
                final OutputStreamWriter fw = new OutputStreamWriter(fos);
                xml.toXML(fw);
                fw.flush();
            } finally {
                fl.release();
            }
        }
    }

    public static File getCatalogFile(final String rootPath, final XnatResourcecatalogI resource) {
        String fullPath = getFullPath(rootPath, resource);
        if (fullPath.endsWith("\\")) {
            fullPath = fullPath.substring(0, fullPath.length() - 1);
        }
        if (fullPath.endsWith("/")) {
            fullPath = fullPath.substring(0, fullPath.length() - 1);
        }


        File f = new File(fullPath);
        if (!f.exists()) {
            f = new File(fullPath + ".gz");
        }

        if (!f.exists()) {
            f = new File(fullPath);

            CatCatalogBean cat = new CatCatalogBean();
            if (resource.getLabel() != null) {
                cat.setId(resource.getLabel());
            } else {
                cat.setId("" + Calendar.getInstance().getTimeInMillis());
            }

            try {
                if (!f.getParentFile().mkdirs() && !f.getParentFile().exists()) {
                    throw new Exception("Failed to create required directory: " + f.getParentFile().getAbsolutePath());
                }

                FileWriter fw = new FileWriter(f);
                cat.toXML(fw, true);
                fw.close();
            } catch (IOException exception) {
                logger.error("Error writing to the folder: " + f.getParentFile().getAbsolutePath(), exception);
            } catch (Exception exception) {
                logger.error("Error creating the folder: " + f.getParentFile().getAbsolutePath(), exception);
            }
        }

        return f;
    }

    public static CatCatalogBean getCatalog(File catalogFile) {
        if (!catalogFile.exists()) return null;
        try {
            InputStream fis = new FileInputStream(catalogFile);
            if (catalogFile.getName().endsWith(".gz")) {
                fis = new GZIPInputStream(fis);
            }

            BaseElement base;

            XDATXMLReader reader = new XDATXMLReader();
            base = reader.parse(fis);

            if (base instanceof CatCatalogBean) {
                return (CatCatalogBean) base;
            }
        } catch (FileNotFoundException exception) {
            logger.error("Couldn't find file: " + catalogFile, exception);
        } catch (IOException exception) {
            logger.error("Error occurred reading file: " + catalogFile, exception);
        } catch (SAXException exception) {
            logger.error("Error processing XML in file: " + catalogFile, exception);
        }

        return null;
    }

    /**
     * Parses catalog xml for resource and returns the Bean object.  Returns null if not found.
     *
     * @param rootPath The root path for the catalog.
     * @param resource The resource catalog.
     * @return The initialized catalog bean.
     */
    public static CatCatalogBean getCatalog(String rootPath, XnatResourcecatalogI resource) {
        File catalogFile = null;
        try {
            catalogFile = CatalogUtils.getCatalogFile(rootPath, resource);
            if (catalogFile.getName().endsWith(".gz")) {
                FileUtils.GUnzipFiles(catalogFile);
                catalogFile = CatalogUtils.getCatalogFile(rootPath, resource);
            }
        } catch (FileNotFoundException exception) {
            logger.error("Couldn't find file: " + catalogFile, exception);
        } catch (IOException exception) {
            logger.error("Error occurred reading file: " + catalogFile, exception);
        } catch (Exception exception) {
            logger.error("Unknown exception reading file at: " + rootPath, exception);
        }

        return catalogFile != null ? getCatalog(catalogFile) : null;
    }

    public static CatCatalogBean getCleanCatalog(String rootPath, XnatResourcecatalogI resource, boolean includeFullPaths) {
        return getCleanCatalog(rootPath, resource, includeFullPaths, null, null);
    }

    public static CatCatalogBean getCleanCatalog(String rootPath, XnatResourcecatalogI resource, boolean includeFullPaths, UserI user, EventMetaI c) {
        File catalogFile = null;
        try {
            catalogFile = handleCatalogFile(rootPath, resource);

            InputStream fis = new FileInputStream(catalogFile);
            if (catalogFile.getName().endsWith(".gz")) {
                fis = new GZIPInputStream(fis);
            }

            BaseElement base;

            XDATXMLReader reader = new XDATXMLReader();
            base = reader.parse(fis);

            String parentPath = catalogFile.getParent();

            if (base instanceof CatCatalogBean) {
                CatCatalogBean cat = (CatCatalogBean) base;
                formalizeCatalog(cat, parentPath, user, c);

                if (includeFullPaths) {
                    CatCatalogMetafieldBean mf = new CatCatalogMetafieldBean();
                    mf.setName("CATALOG_LOCATION");
                    mf.setMetafield(parentPath);
                    cat.addMetafields_metafield(mf);
                }

                return cat;
            }
        } catch (FileNotFoundException exception) {
            logger.error("Couldn't find file " + (catalogFile != null ? "indicated by " + catalogFile.getAbsolutePath() : "of unknown location"), exception);
        } catch (SAXException exception) {
            logger.error("Couldn't parse file " + (catalogFile != null ? "indicated by " + catalogFile.getAbsolutePath() : "of unknown location"), exception);
        } catch (IOException exception) {
            logger.error("Couldn't parse or unzip file " + (catalogFile != null ? "indicated by " + catalogFile.getAbsolutePath() : "of unknown location"), exception);
        } catch (Exception exception) {
            logger.error("Unknown error handling file " + (catalogFile != null ? "indicated by " + catalogFile.getAbsolutePath() : "of unknown location"), exception);
        }

        return null;
    }

    /**
     * Reviews existing catalog and adds any missing fields
     *
     * @param cat     Catalog entry to be cleaned
     * @param catPath Path to catalog file (used to access files with relative paths).
     * @param user    User in operation
     * @param now     Corresponding event
     * @return true if catalog was modified, otherwise false
     */
    public static boolean formalizeCatalog(final CatCatalogI cat, final String catPath, UserI user, EventMetaI now) {
        return formalizeCatalog(cat, catPath, user, now, false, false);
        //default to false for checksums for now.  Maybe it should use the default setting for the server.  But, this runs everytime a catalog xml is loaded.  So, it will get re-run over and over.  Not sure we want to add that amount of processing.
    }

    /**
     * Reviews existing catalog and adds any missing fields
     *
     * @param cat                Catalog entry to be cleaned
     * @param catPath            Path to catalog file (used to access files with relative paths).
     * @param user               User in operation
     * @param now                Corresponding event
     * @param createChecksums    Boolean whether or not to generate checksums (if missing)
     * @param removeMissingFiles Boolean whether or not to delete references to missing files
     * @return true if catalog was modified, otherwise false
     */
    public static boolean formalizeCatalog(final CatCatalogI cat, final String catPath, UserI user, EventMetaI now, boolean createChecksums, boolean removeMissingFiles) {
        return formalizeCatalog(cat, catPath, cat.getId(), user, now, createChecksums, removeMissingFiles);
    }

    public static String getFullPath(String rootPath, XnatResourcecatalogI resource) {

        String fullPath = StringUtils.replace(FileUtils.AppendRootPath(rootPath, resource.getUri()), "\\", "/");
        while (fullPath.contains("//")) {
            fullPath = StringUtils.replace(fullPath, "//", "/");
        }

        if (!fullPath.endsWith("/")) {
            fullPath += "/";
        }

        return fullPath;
    }

    @SuppressWarnings("unused")
    public boolean modifyEntry(CatCatalogI cat, CatEntryI oldEntry, CatEntryI newEntry) {
        for (int i = 0; i < cat.getEntries_entry().size(); i++) {
            CatEntryI e = cat.getEntries_entry().get(i);
            if (e.getUri().equals(oldEntry.getUri())) {
                cat.getEntries_entry().remove(i);
                cat.getEntries_entry().add(newEntry);
                return true;
            }
        }

        for (CatCatalogI subset : cat.getSets_entryset()) {
            if (modifyEntry(subset, oldEntry, newEntry)) {
                return true;
            }
        }

        return false;
    }

    public static List<File> findHistoricalCatFiles(File catFile) {
        final List<File> files = new ArrayList<>();

        final File historyDir = FileUtils.BuildHistoryParentFile(catFile);

        final String name = catFile.getName();

        final FilenameFilter filter = new FilenameFilter() {
            @Override
            public boolean accept(File arg0, String arg1) {
                return (arg1.equals(name));
            }
        };

        if (historyDir.exists()) {
            final File[] historyFiles = historyDir.listFiles();
            if (historyFiles != null) {
                for (File d : historyFiles) {
                    if (d.isDirectory()) {
                        final File[] matched = d.listFiles(filter);
                        if (matched != null && matched.length > 0) {
                            files.addAll(Arrays.asList(matched));
                        }
                    }
                }
            }
        }

        return files;
    }

    public static boolean removeEntry(CatCatalogI cat, CatEntryI entry) {
        for (int i = 0; i < cat.getEntries_entry().size(); i++) {
            CatEntryI e = cat.getEntries_entry().get(i);
            if (e.getUri().equals(entry.getUri())) {
                cat.getEntries_entry().remove(i);
                return true;
            }
        }

        for (CatCatalogI subset : cat.getSets_entryset()) {
            if (removeEntry(subset, entry)) {
                return true;
            }
        }

        return false;
    }

    public static Boolean maintainFileHistory() {
        if (_maintainFileHistory == null) {
            _maintainFileHistory = XDAT.getBoolSiteConfigurationProperty("audit.maintain-file-history", false);
        }
        return _maintainFileHistory;
    }

    public static void moveToHistory(File catFile, File f, CatEntryBean entry, EventMetaI ci) throws Exception {
        //move existing file to audit trail
        if (CatalogUtils.maintainFileHistory()) {
            final File newFile = FileUtils.MoveToHistory(f, EventUtils.getTimestamp(ci));
            addCatHistoryEntry(catFile, newFile.getAbsolutePath(), entry, ci);
        }
    }

    public static void addCatHistoryEntry(File catFile, String f, CatEntryBean entry, EventMetaI ci) throws Exception {
        //move existing file to audit trail
        CatEntryBean newEntryBean = (CatEntryBean) entry.copy();
        newEntryBean.setUri(f);
        if (ci != null) {
            newEntryBean.setModifiedtime(ci.getEventDate());
            if (ci.getEventId() != null) {
                newEntryBean.setModifiedeventid(ci.getEventId().toString());
            }
            if (ci.getUser() != null) {
                newEntryBean.setModifiedby(ci.getUser().getUsername());
            }
        }

        File newCatFile = FileUtils.BuildHistoryFile(catFile, EventUtils.getTimestamp(ci));
        CatCatalogBean newCat;
        if (newCatFile.exists()) {
            newCat = CatalogUtils.getCatalog(newCatFile);
        } else {
            newCat = new CatCatalogBean();
        }

        assert newCat != null;
        newCat.addEntries_entry(newEntryBean);

        CatalogUtils.writeCatalogToFile(newCat, newCatFile);
    }

    public static XFTTable populateTable(XFTTable table, UserI user, XnatProjectdata proj, boolean cacheFileStats) {
        XFTTable newTable = new XFTTable();
        String[] fields = {"xnat_abstractresource_id", "label", "element_name", "category", "cat_id", "cat_desc", "file_count", "file_size", "tags", "content", "format"};
        newTable.initTable(fields);
        table.resetRowCursor();
        while (table.hasMoreRows()) {
            Object[] old = table.nextRow();
            Object[] _new = new Object[11];
            if (logger.isDebugEnabled()) {
                logger.debug("Found resource with ID: " + old[0] + "(" + old[1] + ")");
            }
            _new[0] = old[0];
            _new[1] = old[1];
            _new[2] = old[2];
            _new[3] = old[3];
            _new[4] = old[4];
            _new[5] = old[5];

            XnatAbstractresource res = XnatAbstractresource.getXnatAbstractresourcesByXnatAbstractresourceId(old[0], user, false);
            assert res != null;

            if (cacheFileStats) {
                if (res.getFileCount() == null) {
                    res.setFileCount(res.getCount(proj.getRootArchivePath()));
                }
                if (res.getFileSize() == null) {
                    res.setFileSize(res.getSize(proj.getRootArchivePath()));
                }
                try {
                    res.save(user, true, false, null);
                } catch (Exception exception) {
                    if (res instanceof XnatResourcecatalog) {
                        logger.error("Failed to save updates to resource catalog: " + res.getLabel(), exception);
                    } else {
                        logger.error("Failed to save updates to abstract resource: " + res.getXnatAbstractresourceId(), exception);
                    }
                }
            }

            _new[6] = res.getFileCount();
            _new[7] = res.getFileSize();
            _new[8] = res.getTagString();
            _new[9] = res.getContent();
            _new[10] = res.getFormat();

            newTable.rows().add(_new);
        }

        return newTable;
    }

    public static boolean populateStats(XnatAbstractresource abstractResource, String rootPath) {
        Integer c = abstractResource.getCount(rootPath);
        Long s = abstractResource.getSize(rootPath);

        boolean modified = false;

        if (!c.equals(abstractResource.getFileCount())) {
            abstractResource.setFileCount(c);
            modified = true;
        }

        if (!s.equals(abstractResource.getFileSize())) {
            abstractResource.setFileSize(s);
            modified = true;
        }

        return modified;
    }

    private static CatEntryBean populateAndAddCatEntry(CatCatalogBean cat, String uri, String fname, XnatResourceInfo info) {
        CatEntryBean newEntry = new CatEntryBean();
        newEntry.setUri(uri);
        newEntry.setName(fname);
        newEntry.setId(cat.getId() + "/" + fname);
        configureEntry(newEntry, info, false);
        cat.addEntries_entry(newEntry);
        return newEntry;
    }

    private static void updateEntry(CatCatalogBean cat, String dest, File f, XnatResourceInfo info, EventMetaI ci) {
        final CatEntryBean e = (CatEntryBean) getEntryByURI(cat, dest);

        if (e == null) {
            populateAndAddCatEntry(cat,dest,f.getName(),info);
        } else {
            if (ci != null) {
                if (ci.getUser() != null)
                    e.setModifiedby(ci.getUser().getUsername());
                e.setModifiedtime(ci.getEventDate());
                if (ci.getEventId() != null) {
                    e.setModifiedeventid(ci.getEventId().toString());
                }
            }
        }
    }

    private static String convertAuditToString(Map<String, Map<String, Integer>> summary) {
        StringBuilder sb = new StringBuilder();
        int counter1 = 0;
        for (Map.Entry<String, Map<String, Integer>> entry : summary.entrySet()) {
            if (counter1++ > 0) sb.append("|");
            sb.append(entry.getKey()).append("=");
            int counter2 = 0;
            for (Map.Entry<String, Integer> sub : entry.getValue().entrySet()) {
                if (counter2++ > 0) sb.append(";");
                sb.append(sub.getKey()).append(":").append(sub.getValue());
            }

        }
        return sb.toString();
    }

    private static Map<String, Map<String, Integer>> convertAuditToMap(String audit) {
        Map<String, Map<String, Integer>> summary = new HashMap<>();
        for (String changeSet : audit.split("|")) {
            String[] split1 = changeSet.split("=");
            if (split1.length > 1) {
                String key = split1[0];
                Map<String, Integer> counts = new HashMap<>();
                for (String operation : split1[1].split(";")) {
                    String[] entry = operation.split(":");
                    counts.put(entry[0], Integer.valueOf(entry[1]));
                }
                summary.put(key, counts);
            }
        }
        return summary;
    }

    private static Map<String, Map<String, Integer>> buildAuditSummary(CatCatalogI cat) {
        Map<String, Map<String, Integer>> summary = new HashMap<>();
        buildAuditSummary(cat, summary);
        return summary;
    }

    private static void buildAuditSummary(CatCatalogI cat, Map<String, Map<String, Integer>> summary) {
        for (CatCatalogI subSet : cat.getSets_entryset()) {
            buildAuditSummary(subSet, summary);
        }

        for (CatEntryI entry : cat.getEntries_entry()) {
            addAuditEntry(summary, entry.getCreatedeventid(), entry.getCreatedtime(), ChangeSummaryBuilderA.ADDED, 1);

            if (entry.getModifiedtime() != null) {
                addAuditEntry(summary, entry.getModifiedeventid(), entry.getModifiedtime(), ChangeSummaryBuilderA.MODIFIED, 1);
            }
        }
    }

    private static File handleCatalogFile(final String rootPath, final XnatResourcecatalogI resource) throws Exception {
        File catalog = CatalogUtils.getCatalogFile(rootPath, resource);
        if (catalog.getName().endsWith(".gz")) {
            try {
                FileUtils.GUnzipFiles(catalog);
                catalog = CatalogUtils.getCatalogFile(rootPath, resource);
            } catch (FileNotFoundException exception) {
                logger.error("Couldn't find file: " + catalog, exception);
            } catch (IOException exception) {
                logger.error("Error occurred reading file: " + catalog, exception);
            }
        }
        return catalog;
    }

    /**
     *
     * THIS HAS BEEN DEPRECATED BY refreshCatalog
     *
     * Reviews the catalog directory and adds any files that aren't already referenced in the catalog.
     *
     * @param catFile  path to catalog xml file
     * @param cat      content of catalog xml file
     * @param user     user for transaction
     * @param event_id event id for transaction
     * @return true if the cat was modified (and needs to be saved).
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public static boolean addUnreferencedFiles(final File catFile, final CatCatalogI cat, final UserI user, final Number event_id) {
        //list of all files in the catalog folder
        final Collection<File> files = org.apache.commons.io.FileUtils.listFiles(catFile.getParentFile(), null, true);

        //verify that there is only one catalog xml in this directory
        //fail if more then one is present -- otherwise they will be merged.
        for (final File f : files) {
            if (!f.equals(catFile)) {
                if (f.getName().endsWith(".xml") && isCatalogFile(f)) {
                    return false;
                }
            }
        }

        //URI object for the catalog folder (used to generate relative file paths)
        final URI catFolderURI = catFile.getParentFile().toURI();

        final Date now = Calendar.getInstance().getTime();

        boolean modified = false;

        try {
            String content= org.apache.commons.io.FileUtils.readFileToString(catFile);
            final XnatResourceInfo info = XnatResourceInfo.buildResourceInfo(null, null, null, null, user, now, now, event_id);
            for (final File f : files) {
                if (!f.equals(catFile)) {//don't add the catalog xml to its own list
                    //relative path is used to compare to existing catalog entries, and add it if its missing.  entry paths are relative to the location of the catalog file.
                    final String relative = catFolderURI.relativize(f.toURI()).getPath();

                    if (!checkEntryByURI(content,relative)) {
                        populateAndAddCatEntry((CatCatalogBean) cat,relative,f.getName(),info);
                        modified = true;
                    }

                }
            }
        } catch (IOException e) {
            logger.error(e.getMessage(),e);
        }

        return modified;
    }

    private static boolean isCatalogFile(File f) {
        if (f.getName().endsWith("_catalog.xml")) {
            return true;
        }
        try {
            if (org.apache.commons.io.FileUtils.readFileToString(f, Charset.defaultCharset()).contains("<cat:Catalog")) {
                return true;
            }
        } catch (IOException e) {
            // Do nothing for now
        }
        return false;
    }

    /**
     * Reviews the catalog directory and returns any files that aren't already referenced in the catalogs in that folder.
     *
     * @param catFolder path to catalog xml folder
     * @return true if the cat was modified (and needs to be saved).
     */
    public static List<String> getUnreferencedFiles(final File catFolder) {
        final List<String> unreferenced = Lists.newArrayList();

        //list of all files in the catalog folder
        @SuppressWarnings("unchecked")
        final String[] files = catFolder.list();

        //identify the catalog XMLs in this folder
        final List<CatCatalogI> catalogs = Lists.newArrayList();
        if (files != null) {
            for (final String filename : files) {
                if (filename.endsWith(".xml")) {
                    File f = new File(catFolder, filename);
                    if (isCatalogFile(f)) {
                        CatCatalogI cat = CatalogUtils.getCatalog(f);
                        if (cat != null) {
                            catalogs.add(cat);
                        }
                    }
                }
            }
        }

        Collection<String> cataloged = new TreeSet<>();
        for (CatCatalogI cat : catalogs) {
            cataloged.addAll(getURIs(cat));
        }

        if (files != null) {
            for (final String f : files) {
                if (!(f.endsWith(".xml"))) {//ignore catalog files
                    if (!cataloged.remove(f)) {
                        unreferenced.add(f);
                    }
                }
            }
        }

        return unreferenced;
    }

    private static boolean formalizeCatalog(final CatCatalogI cat, final String catPath, String header, UserI user, EventMetaI now, final boolean createChecksum, final boolean removeMissingFiles) {
        boolean modified = false;

        for (CatCatalogI subSet : cat.getSets_entryset()) {
            if (formalizeCatalog(subSet, catPath, header + "/" + subSet.getId(), user, now, createChecksum, removeMissingFiles)) {
                modified = true;
            }
        }

        List<CatEntryI> toRemove = Lists.newArrayList();

        for (CatEntryI entry : cat.getEntries_entry()) {
            if (entry.getCreatedby() == null && user != null) {
                entry.setCreatedby(user.getUsername());
                modified = true;
            }
            if (entry.getCreatedtime() == null && now != null) {
                ((CatEntryBean) entry).setCreatedtime(now.getEventDate());
                modified = true;
            }
            if (entry.getCreatedeventid() == null && now != null && now.getEventId() != null) {
                ((CatEntryBean) entry).setCreatedeventid(now.getEventId().toString());
                modified = true;
            }

            if (createChecksum) {
                if (CatalogUtils.setChecksum(entry, catPath)) {
                    modified = true;
                }
            }

            if (StringUtils.isEmpty(entry.getId()) || removeMissingFiles) {
                String entryPath = StringUtils.replace(FileUtils.AppendRootPath(catPath, entry.getUri()), "\\", "/");
                File f = getFileOnLocalFileSystem(entryPath);
                if (f != null && StringUtils.isEmpty(entry.getId())) {
                    entry.setId(header + "/" + f.getName());
                    modified = true;
                } else if (f == null) {
                    if (removeMissingFiles) {
                        toRemove.add(entry);
                        modified = true;
                    } else {
                        logger.error("Missing Resource:" + entryPath);
                    }
                }
            }

            if (entry.getClass().equals(CatDcmentryBean.class)) { // CatDcmentryBeans fail to set format correctly because it's not in their xml
                entry.setFormat("DICOM");
            }
        }

        if (toRemove.size() > 0) {
            for (CatEntryI entry : toRemove) {
                CatalogUtils.removeEntry(cat, entry);
            }
        }

        return modified;
    }

    private static final Logger logger = LoggerFactory.getLogger(CatalogUtils.class);

    private static Boolean _maintainFileHistory = null;
    private static Boolean _checksumConfig = null;
}
