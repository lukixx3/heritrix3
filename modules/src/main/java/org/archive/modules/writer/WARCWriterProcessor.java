/*
 *  This file is part of the Heritrix web crawler (crawler.archive.org).
 *
 *  Licensed to the Internet Archive (IA) by one or more individual 
 *  contributors. 
 *
 *  The IA licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.archive.modules.writer;

import static org.archive.io.warc.WARCConstants.FTP_CONTROL_CONVERSATION_MIMETYPE;
import static org.archive.io.warc.WARCConstants.HEADER_KEY_CONCURRENT_TO;
import static org.archive.io.warc.WARCConstants.HEADER_KEY_ETAG;
import static org.archive.io.warc.WARCConstants.HEADER_KEY_IP;
import static org.archive.io.warc.WARCConstants.HEADER_KEY_LAST_MODIFIED;
import static org.archive.io.warc.WARCConstants.HEADER_KEY_PAYLOAD_DIGEST;
import static org.archive.io.warc.WARCConstants.HEADER_KEY_PROFILE;
import static org.archive.io.warc.WARCConstants.HEADER_KEY_TRUNCATED;
import static org.archive.io.warc.WARCConstants.HTTP_REQUEST_MIMETYPE;
import static org.archive.io.warc.WARCConstants.HTTP_RESPONSE_MIMETYPE;
import static org.archive.io.warc.WARCConstants.METADATA;
import static org.archive.io.warc.WARCConstants.NAMED_FIELD_TRUNCATED_VALUE_HEAD;
import static org.archive.io.warc.WARCConstants.NAMED_FIELD_TRUNCATED_VALUE_LENGTH;
import static org.archive.io.warc.WARCConstants.NAMED_FIELD_TRUNCATED_VALUE_TIME;
import static org.archive.io.warc.WARCConstants.PROFILE_REVISIT_IDENTICAL_DIGEST;
import static org.archive.io.warc.WARCConstants.PROFILE_REVISIT_NOT_MODIFIED;
import static org.archive.io.warc.WARCConstants.REQUEST;
import static org.archive.io.warc.WARCConstants.TYPE;
import static org.archive.modules.CoreAttributeConstants.A_DNS_SERVER_IP_LABEL;
import static org.archive.modules.CoreAttributeConstants.A_FTP_CONTROL_CONVERSATION;
import static org.archive.modules.CoreAttributeConstants.A_FTP_FETCH_STATUS;
import static org.archive.modules.CoreAttributeConstants.A_SOURCE_TAG;
import static org.archive.modules.CoreAttributeConstants.HEADER_TRUNC;
import static org.archive.modules.CoreAttributeConstants.LENGTH_TRUNC;
import static org.archive.modules.CoreAttributeConstants.TIMER_TRUNC;
import static org.archive.modules.recrawl.RecrawlAttributeConstants.A_ETAG_HEADER;
import static org.archive.modules.recrawl.RecrawlAttributeConstants.A_FETCH_HISTORY;
import static org.archive.modules.recrawl.RecrawlAttributeConstants.A_LAST_MODIFIED_HEADER;
import static org.archive.modules.recrawl.RecrawlAttributeConstants.A_WRITE_TAG;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.HttpStatus;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.archive.io.ArchiveFileConstants;
import org.archive.io.ReplayInputStream;
import org.archive.io.warc.WARCWriter;
import org.archive.io.warc.WARCWriterPool;
import org.archive.io.warc.WARCWriterPoolSettings;
import org.archive.modules.CoreAttributeConstants;
import org.archive.modules.CrawlMetadata;
import org.archive.modules.CrawlURI;
import org.archive.modules.ProcessResult;
import org.archive.modules.deciderules.recrawl.IdenticalDigestDecideRule;
import org.archive.modules.extractor.Link;
import org.archive.spring.ConfigPath;
import org.archive.uid.RecordIDGenerator;
import org.archive.uid.UUIDGenerator;
import org.archive.util.ArchiveUtils;
import org.archive.util.anvl.ANVLRecord;

/**
 * WARCWriterProcessor.
 * Intends to follow the WARC/1.0 specification.
 * 
 * <p>TODO: Remove ANVLRecord. Rename NameValue or use RFC822
 * (commons-httpclient?) or find something else.
 * 
 * @contributor stack
 */
public class WARCWriterProcessor extends WriterPoolProcessor implements WARCWriterPoolSettings {
    private static final long serialVersionUID = 6182850087635847443L;
    private static final Logger logger = 
        Logger.getLogger(WARCWriterProcessor.class.getName());

    private ConcurrentMap<String, ConcurrentMap<String, AtomicLong>> stats = new ConcurrentHashMap<String, ConcurrentMap<String, AtomicLong>>();

    private AtomicLong urlsWritten = new AtomicLong();
    
    public long getDefaultMaxFileSize() {
        return 1000000000L; // 1 SI giga-byte (10^9 bytes), per WARC appendix A
    }
    public List<ConfigPath> getDefaultStorePaths() {
        List<ConfigPath> paths = new ArrayList<ConfigPath>();
        paths.add(new ConfigPath("warcs default store path", "warcs"));
        return paths;
    }
    
    /**
     * Whether to write 'request' type records. Default is true.
     */
    {
        setWriteRequests(true);
    }
    public boolean getWriteRequests() {
        return (Boolean) kp.get("writeRequests");
    }
    public void setWriteRequests(boolean writeRequests) {
        kp.put("writeRequests",writeRequests);
    }
    
    /**
     * Whether to write 'metadata' type records. Default is true.
     */
    {
        setWriteMetadata(true);
    }
    public boolean getWriteMetadata() {
        return (Boolean) kp.get("writeMetadata");
    }
    public void setWriteMetadata(boolean writeMetadata) {
        kp.put("writeMetadata",writeMetadata);
    }
    
    /**
     * Whether to write 'revisit' type records when a URI's history indicates
     * the previous fetch had an identical content digest. Default is true.
     */
    {
        setWriteRevisitForIdenticalDigests(true);
    }
    public boolean getWriteRevisitForIdenticalDigests() {
        return (Boolean) kp.get("writeRevisitForIdenticalDigests");
    }
    public void setWriteRevisitForIdenticalDigests(boolean writeRevisits) {
        kp.put("writeRevisitForIdenticalDigests",writeRevisits);
    }

    /**
     * Whether to write 'revisit' type records when a 304-Not Modified response
     * is received. Default is true.
     */
    {
        setWriteRevisitForNotModified(true);
    }
    public boolean getWriteRevisitForNotModified() {
        return (Boolean) kp.get("writeRevisitForNotModified");
    }
    public void setWriteRevisitForNotModified(boolean writeRevisits) {
        kp.put("writeRevisitForNotModified",writeRevisits);
    }

    /**
     * Generator for record IDs
     */
    protected RecordIDGenerator generator = new UUIDGenerator();
    public RecordIDGenerator getRecordIDGenerator() {
        return generator; 
    }
    public void setRecordIDGenerator(RecordIDGenerator generator) {
        this.generator = generator;
    }

    private transient List<String> cachedMetadata;

    public WARCWriterProcessor() {
    }

    @Override
    protected void setupPool(final AtomicInteger serialNo) {
        setPool(new WARCWriterPool(serialNo, this, getPoolMaxActive(), getMaxWaitForIdleMs()));
    }

    /**
     * Writes a CrawlURI and its associated data to store file.
     * 
     * Currently this method understands the following uri types: dns, http, and
     * https.
     * 
     * @param curi CrawlURI to process.
     * 
     */
    @Override
    protected ProcessResult innerProcessResult(CrawlURI puri) {
        CrawlURI curi = (CrawlURI)puri;
        String scheme = curi.getUURI().getScheme().toLowerCase();
        try {
            if (shouldWrite(curi)) {
                return write(scheme, curi);
            } else {
                copyForwardWriteTagIfDupe(curi);
            }
        } catch (IOException e) {
            curi.getNonFatalFailures().add(e);
            logger.log(Level.SEVERE, "Failed write of Records: " +
                curi.toString(), e);
        }
        return ProcessResult.PROCEED;
    }

    protected ProcessResult write(final String lowerCaseScheme, 
            final CrawlURI curi)
    throws IOException {
        WARCWriter writer = (WARCWriter) getPool().borrowFile();
      
        long position = writer.getPosition();
        try {
            // See if we need to open a new file because we've exceeded maxBytes.
            // Call to checkFileSize will open new file if we're at maximum for
            // current file.
            writer.checkSize();
            if (writer.getPosition() != position) {
                // We just closed the file because it was larger than maxBytes.
                // Add to the totalBytesWritten the size of the first record
                // in the file, if any.
                setTotalBytesWritten(getTotalBytesWritten() +
                    (writer.getPosition() - position));
                position = writer.getPosition();
            }
                       
            // Reset writer temp stats so they reflect only this set of records.
            // They'll be added to totals below, in finally block, after records
            // have been written.
            writer.resetTmpStats();
            // Write a request, response, and metadata all in the one
            // 'transaction'.
            final URI baseid = getRecordID();
            final String timestamp =
                ArchiveUtils.getLog14Date(curi.getFetchBeginTime());
            if (lowerCaseScheme.startsWith("http")) {
                writeHttpRecords(curi, writer, baseid, timestamp); 
            } else if (lowerCaseScheme.equals("dns")) {
                writeDnsRecords(curi, writer, baseid, timestamp);
            } else if (lowerCaseScheme.equals("ftp")) {
                writeFtpRecords(writer, curi, baseid, timestamp);
            } else if (lowerCaseScheme.equals("whois")) {
                writeWhoisRecords(writer, curi, baseid, timestamp);
            } else {
                logger.warning("No handler for scheme " + lowerCaseScheme);
            }
        } catch (IOException e) {
            // Invalidate this file (It gets a '.invalid' suffix).
            getPool().invalidateFile(writer);
            // Set the writer to null otherwise the pool accounting
            // of how many active writers gets skewed if we subsequently
            // do a returnWriter call on this object in the finally block.
            writer = null;
            throw e;
        } finally {
            if (writer != null) {
                if (WARCWriter.getStat(writer.getTmpStats(), WARCWriter.TOTALS, WARCWriter.NUM_RECORDS) > 0l) {
                     addStats(writer.getTmpStats());
                     urlsWritten.incrementAndGet();
                }
                if (logger.isLoggable(Level.FINE)) { 
                    logger.fine("wrote " 
                        + WARCWriter.getStat(writer.getTmpStats(), WARCWriter.TOTALS, WARCWriter.SIZE_ON_DISK) 
                        + " bytes to " + writer.getFile().getName() + " for " + curi);
                }
            	setTotalBytesWritten(getTotalBytesWritten() +
            	     (writer.getPosition() - position));
                getPool().returnFile(writer);

                String filename = writer.getFile().getName();
                if (filename.endsWith(ArchiveFileConstants.OCCUPIED_SUFFIX)) {
                    filename = filename.substring(0, filename.length() - ArchiveFileConstants.OCCUPIED_SUFFIX.length());
                }
                curi.addExtraInfo("warcFilename", filename);

                @SuppressWarnings("unchecked")
                Map<String,Object>[] history = (Map<String,Object>[])curi.getData().get(A_FETCH_HISTORY);
                if (history != null && history[0] != null) {
                    history[0].put(A_WRITE_TAG, filename);
                }
            }
        }
        return checkBytesWritten();
    }

    protected void addStats(Map<String, Map<String, Long>> substats) {
        for (String key: substats.keySet()) {
            // intentionally redundant here -- if statement avoids creating
            // unused empty map every time; putIfAbsent() ensures thread safety
            if (stats.get(key) == null) {
                stats.putIfAbsent(key, new ConcurrentHashMap<String, AtomicLong>());
            }
            
            for (String subkey: substats.get(key).keySet()) {
                AtomicLong oldValue = stats.get(key).get(subkey);
                if (oldValue == null) {
                    oldValue = stats.get(key).putIfAbsent(subkey, new AtomicLong(substats.get(key).get(subkey)));
                }
                if (oldValue != null) {
                    oldValue.addAndGet(substats.get(key).get(subkey));
                }
            }
        }
    }
   
    private void writeDnsRecords(final CrawlURI curi, WARCWriter w,
            final URI baseid, final String timestamp) throws IOException {
        ANVLRecord headers = null;
        String ip = (String)curi.getData().get(A_DNS_SERVER_IP_LABEL);
        if (ip != null && ip.length() > 0) {
            headers = new ANVLRecord(1);
            headers.addLabelValue(HEADER_KEY_IP, ip);
        }
        writeResponse(w, timestamp, curi.getContentType(), baseid,
            curi, headers);
    }

    private void writeWhoisRecords(WARCWriter w, CrawlURI curi, URI baseid,
            String timestamp) throws IOException {
        ANVLRecord headers = new ANVLRecord(1);
        Object whoisServerIP = curi.getData().get(CoreAttributeConstants.A_WHOIS_SERVER_IP);
        if (whoisServerIP != null) {
            headers.addLabelValue(HEADER_KEY_IP, whoisServerIP.toString());
        }
        writeResponse(w, timestamp, curi.getContentType(), baseid, curi, headers);
    }

    private void writeHttpRecords(final CrawlURI curi, WARCWriter w,
            final URI baseid, final String timestamp) throws IOException {
        // Add named fields for ip, checksum, and relate the metadata
        // and request to the resource field.
        // TODO: Use other than ANVL (or rename ANVL as NameValue or
        // use RFC822 (commons-httpclient?).
        ANVLRecord headers = new ANVLRecord(5);
        if (curi.getContentDigest() != null) {
            headers.addLabelValue(HEADER_KEY_PAYLOAD_DIGEST,
                    curi.getContentDigestSchemeString());
        }
        headers.addLabelValue(HEADER_KEY_IP, getHostAddress(curi));
        URI rid;
        
        if (IdenticalDigestDecideRule.hasIdenticalDigest(curi) && 
                getWriteRevisitForIdenticalDigests()) {
            rid = writeRevisitDigest(w, timestamp, HTTP_RESPONSE_MIMETYPE,
                    baseid, curi, headers);
        } else if (curi.getFetchStatus() == HttpStatus.SC_NOT_MODIFIED && 
                getWriteRevisitForNotModified()) {
            rid = writeRevisitNotModified(w, timestamp,
                    baseid, curi, headers);
        } else {
            // Check for truncated annotation
            String value = null;
            Collection<String> anno = curi.getAnnotations();
            if (anno.contains(TIMER_TRUNC)) {
                value = NAMED_FIELD_TRUNCATED_VALUE_TIME;
            } else if (anno.contains(LENGTH_TRUNC)) {
                value = NAMED_FIELD_TRUNCATED_VALUE_LENGTH;
            } else if (anno.contains(HEADER_TRUNC)) {
                value = NAMED_FIELD_TRUNCATED_VALUE_HEAD;
            }
            // TODO: Add annotation for TRUNCATED_VALUE_UNSPECIFIED
            if (value != null) {
                headers.addLabelValue(HEADER_KEY_TRUNCATED, value);
            }
            rid = writeResponse(w, timestamp, HTTP_RESPONSE_MIMETYPE,
            	baseid, curi, headers);
        }
        
        headers = new ANVLRecord(1);
        headers.addLabelValue(HEADER_KEY_CONCURRENT_TO,
            '<' + rid.toString() + '>');

        if (getWriteRequests()) {
            writeRequest(w, timestamp, HTTP_REQUEST_MIMETYPE,
                    baseid, curi, headers);
        }
        if (getWriteMetadata()) {
            writeMetadata(w, timestamp, baseid, curi, headers);
        }
    }

    private void writeFtpRecords(WARCWriter w, final CrawlURI curi, final URI baseid,
            final String timestamp) throws IOException {
        ANVLRecord headers = new ANVLRecord(3);
        headers.addLabelValue(HEADER_KEY_IP, getHostAddress(curi));
        String controlConversation = curi.getData().get(A_FTP_CONTROL_CONVERSATION).toString();
        URI rid = writeFtpControlConversation(w, timestamp, baseid, curi, headers, controlConversation);
        
        if (curi.getContentDigest() != null) {
            headers.addLabelValue(HEADER_KEY_PAYLOAD_DIGEST,
            curi.getContentDigestSchemeString());
        }
            
        if (curi.getRecorder() != null) {
            if (IdenticalDigestDecideRule.hasIdenticalDigest(curi) && 
                    getWriteRevisitForIdenticalDigests()) {
                rid = writeRevisitDigest(w, timestamp, null,
                        baseid, curi, headers, 0);
            } else {
                headers = new ANVLRecord(3);
                // Check for truncated annotation
                String value = null;
                Collection<String> anno = curi.getAnnotations();
                if (anno.contains(TIMER_TRUNC)) {
                    value = NAMED_FIELD_TRUNCATED_VALUE_TIME;
                } else if (anno.contains(LENGTH_TRUNC)) {
                    value = NAMED_FIELD_TRUNCATED_VALUE_LENGTH;
                } else if (anno.contains(HEADER_TRUNC)) {
                    value = NAMED_FIELD_TRUNCATED_VALUE_HEAD;
                }
                // TODO: Add annotation for TRUNCATED_VALUE_UNSPECIFIED
                if (value != null) {
                    headers.addLabelValue(HEADER_KEY_TRUNCATED, value);
                }
                
                if (curi.getContentDigest() != null) {
                    headers.addLabelValue(HEADER_KEY_PAYLOAD_DIGEST,
                            curi.getContentDigestSchemeString());
                }
                headers.addLabelValue(HEADER_KEY_CONCURRENT_TO, '<' + rid.toString() + '>');
                rid = writeResource(w, timestamp, curi.getContentType(), baseid, curi, headers);
            }
        }
        if (getWriteMetadata()) {
            headers = new ANVLRecord(1);
            headers.addLabelValue(HEADER_KEY_CONCURRENT_TO, '<' + rid.toString() + '>');
            writeMetadata(w, timestamp, baseid, curi, headers);
        }
    }

    protected URI writeFtpControlConversation(WARCWriter w, String timestamp,
            URI baseid, CrawlURI curi, ANVLRecord headers,
            String controlConversation) throws IOException {
        final URI uid = qualifyRecordID(baseid, TYPE, METADATA);
        byte[] b = controlConversation.getBytes("UTF-8");
        w.writeMetadataRecord(curi.toString(), timestamp,
                FTP_CONTROL_CONVERSATION_MIMETYPE, uid, headers,
                new ByteArrayInputStream(b), b.length);
        return uid;
    }

    protected URI writeRequest(final WARCWriter w,
            final String timestamp, final String mimetype,
            final URI baseid, final CrawlURI curi,
            final ANVLRecord namedFields) 
    throws IOException {
        final URI uid = qualifyRecordID(baseid, TYPE, REQUEST);
        ReplayInputStream 
            ris = curi.getRecorder().getRecordedOutput().getReplayInputStream();
        try {
            w.writeRequestRecord(curi.toString(), timestamp, mimetype, uid,
                namedFields, ris,
                curi.getRecorder().getRecordedOutput().getSize());
        } finally {
            IOUtils.closeQuietly(ris);
        }
        return uid;
    }
    
    protected URI writeResponse(final WARCWriter w,
            final String timestamp, final String mimetype,
            final URI baseid, final CrawlURI curi,
            final ANVLRecord namedFields) 
    throws IOException {
        ReplayInputStream ris =
            curi.getRecorder().getRecordedInput().getReplayInputStream();
        try {
            w.writeResponseRecord(curi.toString(), timestamp, mimetype, baseid,
                namedFields, ris,
                curi.getRecorder().getRecordedInput().getSize());
        } finally {
            IOUtils.closeQuietly(ris);
        }
        return baseid;
    }
    
    protected URI writeResource(final WARCWriter w,
            final String timestamp, final String mimetype,
            final URI baseid, final CrawlURI curi,
            final ANVLRecord namedFields) 
    throws IOException {
        ReplayInputStream ris = curi.getRecorder().getRecordedInput().getReplayInputStream();
        try {
            w.writeResourceRecord(curi.toString(), timestamp, mimetype, baseid,
                namedFields, ris,
                curi.getRecorder().getRecordedInput().getSize());
        } finally {
            IOUtils.closeQuietly(ris);
        }
        return baseid;
    }

    protected URI writeRevisitDigest(final WARCWriter w,
            final String timestamp, final String mimetype,
            final URI baseid, final CrawlURI curi,
            final ANVLRecord namedFields) 
    throws IOException {
        long revisedLength = curi.getRecorder().getRecordedInput().getContentBegin();
        revisedLength = revisedLength > 0 
            ? revisedLength 
            : curi.getRecorder().getRecordedInput().getSize();
        return writeRevisitDigest(w, timestamp, mimetype, baseid, curi,
                namedFields, revisedLength);
    }

    protected URI writeRevisitDigest(final WARCWriter w,
            final String timestamp, final String mimetype, final URI baseid,
            final CrawlURI curi, final ANVLRecord namedFields,
            long contentLength) throws IOException {
        namedFields.addLabelValue(
        		HEADER_KEY_PROFILE, PROFILE_REVISIT_IDENTICAL_DIGEST);
        namedFields.addLabelValue(
        		HEADER_KEY_TRUNCATED, NAMED_FIELD_TRUNCATED_VALUE_LENGTH);
        ReplayInputStream ris =
            curi.getRecorder().getRecordedInput().getReplayInputStream();
        try {
            w.writeRevisitRecord(curi.toString(), timestamp, mimetype, baseid,
                namedFields, ris, contentLength);
        } finally {
            IOUtils.closeQuietly(ris);
        }
        curi.getAnnotations().add("warcRevisit:digest");
        return baseid;
    }
    
    protected URI writeRevisitNotModified(final WARCWriter w,
            final String timestamp, 
            final URI baseid, final CrawlURI puri,
            final ANVLRecord namedFields) 
    throws IOException {
    	CrawlURI curi = (CrawlURI) puri;
        namedFields.addLabelValue(
        		HEADER_KEY_PROFILE, PROFILE_REVISIT_NOT_MODIFIED);
        // save just enough context to understand basis of not-modified
        if(curi.isHttpTransaction()) {
            saveHeader(curi, namedFields, A_ETAG_HEADER, HEADER_KEY_ETAG);
            saveHeader(curi, namedFields, A_LAST_MODIFIED_HEADER, HEADER_KEY_LAST_MODIFIED);
        }
        // truncate to zero-length (all necessary info is above)
        namedFields.addLabelValue(HEADER_KEY_TRUNCATED,
            NAMED_FIELD_TRUNCATED_VALUE_LENGTH);
        ReplayInputStream ris =
            curi.getRecorder().getRecordedInput().getReplayInputStream();
        try {
            w.writeRevisitRecord(curi.toString(), timestamp, null, baseid,
                namedFields, ris, 0);
        } finally {
            IOUtils.closeQuietly(ris);
        }
        curi.getAnnotations().add("warcRevisit:notModified");
        return baseid;
    }
    
    /**
     * Saves a header from the given HTTP operation into the 
     * provider headers under a new name
     */
    protected void saveHeader(CrawlURI curi, ANVLRecord warcHeaders,
            String origName, String newName) {
        String value = curi.getHttpHeader(origName);
        if (value != null) {
            warcHeaders.addLabelValue(newName, value);
        }
    }

	protected URI writeMetadata(final WARCWriter w,
            final String timestamp,
            final URI baseid, final CrawlURI curi,
            final ANVLRecord namedFields) 
    throws IOException {
        final URI uid = qualifyRecordID(baseid, TYPE, METADATA);
        // Get some metadata from the curi.
        // TODO: Get all curi metadata.
        // TODO: Use other than ANVL (or rename ANVL as NameValue or use
        // RFC822 (commons-httpclient?).
        ANVLRecord r = new ANVLRecord();
        if (curi.isSeed()) {
            r.addLabel("seed");
        } else {
        	if (curi.forceFetch()) {
        		r.addLabel("force-fetch");
        	}
            if(StringUtils.isNotBlank(flattenVia(curi))) {
                r.addLabelValue("via", flattenVia(curi));
            }
            if(StringUtils.isNotBlank(curi.getPathFromSeed())) {
                r.addLabelValue("hopsFromSeed", curi.getPathFromSeed());
            }
            if (curi.containsDataKey(A_SOURCE_TAG)) {
                r.addLabelValue("sourceTag", 
                        (String)curi.getData().get(A_SOURCE_TAG));
            }
        }
        long duration = curi.getFetchCompletedTime() - curi.getFetchBeginTime();
        if (duration > -1) {
            r.addLabelValue("fetchTimeMs", Long.toString(duration));
        }
        
        if (curi.getData().containsKey(A_FTP_FETCH_STATUS)) {
            r.addLabelValue("ftpFetchStatus", curi.getData().get(A_FTP_FETCH_STATUS).toString());
        }

        if (curi.getRecorder() != null && curi.getRecorder().getCharset() != null) {
            r.addLabelValue("charsetForLinkExtraction", curi.getRecorder().getCharset().name());
        }
        
        for (String annotation: curi.getAnnotations()) {
            if (annotation.startsWith("usingCharsetIn") || annotation.startsWith("inconsistentCharsetIn")) {
                String[] kv = annotation.split(":", 2);
                r.addLabelValue(kv[0], kv[1]);
            }
        }

        // Add outlinks though they are effectively useless without anchor text.
        Collection<Link> links = curi.getOutLinks();
        if (links != null && links.size() > 0) {
            for (Link link: links) {
                r.addLabelValue("outlink", link.toString());
            }
        }
        
        // TODO: Other curi fields to write to metadata.
        // 
        // Credentials
        // 
        // fetch-began-time: 1154569278774
        // fetch-completed-time: 1154569281816
        //
        // Annotations.
        
        byte [] b = r.getUTF8Bytes();
        w.writeMetadataRecord(curi.toString(), timestamp, ANVLRecord.MIMETYPE,
            uid, namedFields, new ByteArrayInputStream(b), b.length);
        return uid;
    }
    
    protected URI getRecordID() throws IOException {
        return generator.getRecordID();
    }
    
    protected URI qualifyRecordID(final URI base, final String key,
            final String value)
    throws IOException {
        Map<String, String> qualifiers = new HashMap<String, String>(1);
        qualifiers.put(key, value);
        return generator.qualifyRecordID(base, qualifiers);
    }  

    public List<String> getMetadata() {
        if (cachedMetadata != null) {
            return cachedMetadata;
        }
        ANVLRecord record = new ANVLRecord(7);
        record.addLabelValue("software", "Heritrix/" +
                ArchiveUtils.VERSION + " http://crawler.archive.org");
        try {
            InetAddress host = InetAddress.getLocalHost();
            record.addLabelValue("ip", host.getHostAddress());
            record.addLabelValue("hostname", host.getCanonicalHostName());
        } catch (UnknownHostException e) {
            logger.log(Level.WARNING,"unable top obtain local crawl engine host",e);
        }
        
        // conforms to ISO 28500:2009 as of May 2009
        // as described at http://bibnum.bnf.fr/WARC/ 
        // latest draft as of November 2008
        record.addLabelValue("format","WARC File Format 1.0"); 
        record.addLabelValue("conformsTo","http://bibnum.bnf.fr/WARC/WARC_ISO_28500_version1_latestdraft.pdf");
        
        // Get other values from metadata provider

        CrawlMetadata provider = getMetadataProvider();

        addIfNotBlank(record,"operator", provider.getOperator());
        addIfNotBlank(record,"publisher", provider.getOrganization());
        addIfNotBlank(record,"audience", provider.getAudience());
        addIfNotBlank(record,"isPartOf", provider.getJobName());
        // TODO: make date match 'job creation date' as in Heritrix 1.x
        // until then, leave out (plenty of dates already in WARC 
        // records
//            String rawDate = provider.getBeginDate();
//            if(StringUtils.isNotBlank(rawDate)) {
//                Date date;
//                try {
//                    date = ArchiveUtils.parse14DigitDate(rawDate);
//                    addIfNotBlank(record,"created",ArchiveUtils.getLog14Date(date));
//                } catch (ParseException e) {
//                    logger.log(Level.WARNING,"obtaining warc created date",e);
//                }
//            }
        addIfNotBlank(record,"description", provider.getDescription());
        addIfNotBlank(record,"robots", provider.getRobotsPolicyName().toLowerCase());

        addIfNotBlank(record,"http-header-user-agent",
                provider.getUserAgent());
        addIfNotBlank(record,"http-header-from",
                provider.getOperatorFrom());

        // really ugly to return as List<String>, but changing would require 
        // larger refactoring
        return Collections.singletonList(record.toString());
    }
    
    protected void addIfNotBlank(ANVLRecord record, String label, String value) {
        if(StringUtils.isNotBlank(value)) {
            record.addLabelValue(label, value);
        }
    }

    @Override
    public String report() {
        logger.info("final stats: " + stats);
        
        StringBuilder buf = new StringBuilder();
        buf.append("Processor: " + getClass().getName() + "\n");
        buf.append("  Function:          Writes WARCs\n");
        buf.append("  Total CrawlURIs:   " + urlsWritten + "\n");
        buf.append("  Revisit records:   " + WARCWriter.getStat(stats, WARCWriter.REVISIT, WARCWriter.NUM_RECORDS) + "\n");
        
        long bytes = WARCWriter.getStat(stats, WARCWriter.RESPONSE, WARCWriter.CONTENT_BYTES)
                + WARCWriter.getStat(stats, WARCWriter.RESOURCE, WARCWriter.CONTENT_BYTES);
        buf.append("  Crawled content bytes (including http headers): "
                + bytes + " (" + ArchiveUtils.formatBytesForDisplay(bytes) + ")\n");
        
        bytes = WARCWriter.getStat(stats, WARCWriter.TOTALS, WARCWriter.TOTAL_BYTES);
        buf.append("  Total uncompressed bytes (including all warc records): "
                + bytes + " (" + ArchiveUtils.formatBytesForDisplay(bytes) + ")\n");
        
        buf.append("  Total size on disk ("+ (getCompress() ? "compressed" : "uncompressed") + "): "
                + getTotalBytesWritten() + " (" + ArchiveUtils.formatBytesForDisplay(getTotalBytesWritten()) + ")\n");
        
        return buf.toString();
    }
    
}
