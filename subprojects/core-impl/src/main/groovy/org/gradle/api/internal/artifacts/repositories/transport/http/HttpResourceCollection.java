/*
 * Copyright 2011 the original author or authors.
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
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.repositories.transport.http;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.*;
import org.apache.http.entity.FileEntity;
import org.apache.http.impl.client.ContentEncodingHttpClient;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.cookie.DateUtils;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.util.EntityUtils;
import org.apache.ivy.core.module.id.ArtifactRevisionId;
import org.apache.ivy.plugins.repository.*;
import org.apache.ivy.util.url.ApacheURLLister;
import org.gradle.api.UncheckedIOException;
import org.gradle.api.internal.artifacts.ivyservice.filestore.ArtifactCaches;
import org.gradle.api.internal.artifacts.ivyservice.filestore.CachedArtifact;
import org.gradle.api.internal.artifacts.ivyservice.filestore.CachedArtifactCandidates;
import org.gradle.api.internal.artifacts.repositories.transport.ResourceCollection;
import org.gradle.internal.UncheckedException;
import org.gradle.util.hash.HashValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * A repository which uses commons-httpclient to access resources using HTTP/HTTPS.
 */
public class HttpResourceCollection extends AbstractRepository implements ResourceCollection {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpResourceCollection.class);
    private final DefaultHttpClient client = new ContentEncodingHttpClient();
    private final BasicHttpContext httpContext = new BasicHttpContext();
    private final RepositoryCopyProgressListener progress = new RepositoryCopyProgressListener(this);
    private final List<HttpResource> openResources = new ArrayList<HttpResource>();

    private final ArtifactCaches artifactCaches;
    private final HttpClientConfigurer configurer;

    public HttpResourceCollection(HttpSettings httpSettings, ArtifactCaches artifactCaches) {
        this.artifactCaches = artifactCaches;
        configurer = new HttpClientConfigurer(httpSettings);
        configurer.configure(client);
    }

    public HttpResource getResource(String source) throws IOException {
        return getResource(source, null);
    }

    public HttpResource getResource(final String source, ArtifactRevisionId artifactId) throws IOException {
        return getResource(source, artifactId, true);
    }

    public HttpResource getResource(String source, ArtifactRevisionId artifactId, boolean forDownload) throws IOException {
        abortOpenResources();
        if (forDownload) {
            HttpResource httpResource = initGet(source, artifactId);
            return recordOpenGetResource(httpResource);
        }
        return initHead(source);
    }

    private void abortOpenResources() {
        for (HttpResource openResource : openResources) {
            LOGGER.warn("Forcing close on abandoned resource: " + openResource);
            try {
                openResource.close();
            } catch (IOException e) {
                LOGGER.warn("Failed to close abandoned resource", e);
            }
        }
        openResources.clear();
    }

    private HttpResource recordOpenGetResource(HttpResource httpResource) {
        if (httpResource instanceof HttpResponseResource) {
            openResources.add(httpResource);
        }
        return httpResource;
    }

    private HttpResource initGet(String source, ArtifactRevisionId artifactId) {
        LOGGER.debug("Constructing GET resource: {}", source);

        // Do we have any artifacts in the cache with the same checksum
        CachedArtifactCandidates byHashCacheCandidates = artifactCaches.getArtifactIdCache().findCandidates(artifactId);
        if (!byHashCacheCandidates.isEmpty()) {
            CachedHttpResource cachedResource = findCachedResourceBySha1(source, byHashCacheCandidates);
            if (cachedResource != null) {
                return cachedResource;
            }
        }

        HttpGet request = new HttpGet(source);
        return processHttpRequest(source, request, artifactCaches.getUrlCache().findCandidates(source).getMostRecent());
    }

    private HttpResource initHead(String source) {
        LOGGER.debug("Constructing HEAD resource: {}", source);
        HttpHead request = new HttpHead(source);
        return processHttpRequest(source, request, null);
    }

    private HttpResource processHttpRequest(String source, HttpRequestBase request, CachedArtifact potentialCachedArtifact) {
        String method = request.getMethod();
        configurer.configureMethod(request);

        if (potentialCachedArtifact != null) {
            String formattedDate = DateUtils.formatDate(new Date(potentialCachedArtifact.getLastModified()));
            LOGGER.info("Adding If-Modified-Since: {}. [HTTP {}: {}]", new Object[]{formattedDate, method, source});
            request.addHeader("If-Modified-Since", formattedDate);
        }

        HttpResponse response;
        try {
            response = executeMethod(request);
        } catch (IOException e) {
            throw new UncheckedIOException(String.format("Could not %s '%s'.", method, source), e);
        }

        if (wasMissing(response)) {
            LOGGER.info("Resource missing. [HTTP {}: {}]", method, source);
            return new MissingHttpResource(source);
        }
        if (potentialCachedArtifact != null && wasUnmodified(response)) {
            LOGGER.info("Resource was unmodified. [HTTP {}: {}]", method, source);
            return new CachedHttpResource(source, potentialCachedArtifact, HttpResourceCollection.this);
        }
        if (!wasSuccessful(response)) {
            LOGGER.info("Failed to get resource: {}. [HTTP {}: {}]", new Object[]{method, response.getStatusLine(), source});
            throw new UncheckedIOException(String.format("Could not %s '%s'. Received status code %s from server: %s",
                                                         method, source, response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase()));
        }
        LOGGER.info("Resource found. [HTTP {}: {}]", method, source);
        return new HttpResponseResource(method, source, response) {
            @Override
            public void close() throws IOException {
                super.close();
                HttpResourceCollection.this.openResources.remove(this);
            }
        };
    }

    private CachedHttpResource findCachedResourceBySha1(String source, CachedArtifactCandidates candidates) {
        String checksumType = "SHA-1";
        String checksumUrl = source + ".sha1";

        HashValue sha1 = downloadSha1(checksumUrl);
        if (sha1 == null) {
            LOGGER.info("Checksum {} unavailable. [HTTP GET: {}]", checksumType, checksumUrl);
        } else {
            CachedArtifact cached = candidates.findByHashValue(sha1);
            if (cached != null) {
                LOGGER.info("Checksum {} matched cached resource: [HTTP GET: {}]", checksumType, checksumUrl);
                return new CachedHttpResource(source, cached, HttpResourceCollection.this);
            }

            LOGGER.info("Checksum {} did not match cached resources: [HTTP GET: {}]", checksumType, checksumUrl);
        }
        return null;
    }

    private HashValue downloadSha1(String checksumUrl) {
        HttpGet get = new HttpGet(checksumUrl);
        configurer.configureMethod(get);
        try {
            HttpResponse httpResponse = executeMethod(get);
            if (wasSuccessful(httpResponse)) {
                String checksumValue = EntityUtils.toString(httpResponse.getEntity());
                return HashValue.parse(checksumValue);
            }
            if (!wasMissing(httpResponse)) {
                LOGGER.info("Request for checksum at {} failed: {}", checksumUrl, httpResponse.getStatusLine());
            }
            return null;
        } catch (Exception e) {
            LOGGER.warn("Checksum missing at {} due to: {}", checksumUrl, e.getMessage());
            return null;
        } finally {
            // TODO:DAZ Don't need this
            get.abort();
        }
    }

    public void get(String source, File destination) throws IOException {
        throw new UnsupportedOperationException();
    }

    public void downloadResource(Resource res, File destination) throws IOException {
        if (!(res instanceof HttpResource)) {
            throw new IllegalArgumentException("Can only download HttpResource");
        }
        HttpResource resource = (HttpResource) res;
        fireTransferInitiated(resource, TransferEvent.REQUEST_GET);
        try {
            progress.setTotalLength(resource.getContentLength() > 0 ? resource.getContentLength() : null);
            resource.writeTo(destination, progress);
        } catch (IOException e) {
            fireTransferError(e);
            throw e;
        } catch (Exception e) {
            fireTransferError(e);
            throw UncheckedException.throwAsUncheckedException(e);
        } finally {
            progress.setTotalLength(null);
            openResources.remove(resource);
        }
    }

    @Override
    protected void put(final File source, String destination, boolean overwrite) throws IOException {
        LOGGER.debug("Attempting to put resource {}.", destination);
        assert source.isFile();
        fireTransferInitiated(new BasicResource(destination, true, source.length(), source.lastModified(), false), TransferEvent.REQUEST_PUT);
        try {
            progress.setTotalLength(source.length());
            doPut(source, destination);
        } catch (IOException e) {
            fireTransferError(e);
            throw e;
        } catch (Exception e) {
            fireTransferError(e);
            throw UncheckedException.throwAsUncheckedException(e);
        } finally {
            progress.setTotalLength(null);
        }
    }

    private void doPut(File source, String destination) throws IOException {
        HttpPut method = new HttpPut(destination);
        configurer.configureMethod(method);
        method.setEntity(new FileEntity(source, "application/octet-stream"));
        LOGGER.debug("Performing HTTP PUT: {}", method.getURI());
        HttpResponse response = client.execute(method, httpContext);
        EntityUtils.consume(response.getEntity());
        if (!wasSuccessful(response)) {
            throw new IOException(String.format("Could not PUT '%s'. Received status code %s from server: %s",
                                                destination, response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase()));
        }
    }

    private HttpResponse executeMethod(HttpUriRequest method) throws IOException {
        LOGGER.debug("Performing HTTP GET: {}", method.getURI());
        HttpResponse httpResponse = client.execute(method, httpContext);
        // Consume content for non-successful, responses. This avoids the connection being left open.
        if (!wasSuccessful(httpResponse)) {
            EntityUtils.consume(httpResponse.getEntity());
            return httpResponse;
        }
        return httpResponse;
    }

    public List list(String parent) throws IOException {
        // Parse standard directory listing pages served up by Apache
        ApacheURLLister urlLister = new ApacheURLLister();
        List<URL> urls = urlLister.listAll(new URL(parent));
        if (urls != null) {
            List<String> ret = new ArrayList<String>(urls.size());
            for (URL url : urls) {
                ret.add(url.toExternalForm());
            }
            return ret;
        }
        return null;
    }

    private boolean wasMissing(HttpResponse response) {
        int statusCode = response.getStatusLine().getStatusCode();
        return statusCode == 404;
    }

    private boolean wasSuccessful(HttpResponse response) {
        int statusCode = response.getStatusLine().getStatusCode();
        return statusCode >= 200 && statusCode < 300;
    }

    private boolean wasUnmodified(HttpResponse response) {
        int statusCode = response.getStatusLine().getStatusCode();
        return statusCode == 304;
    }

}
