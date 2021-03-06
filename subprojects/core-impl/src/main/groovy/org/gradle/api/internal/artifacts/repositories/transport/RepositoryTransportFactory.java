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
package org.gradle.api.internal.artifacts.repositories.transport;

import org.apache.ivy.core.cache.RepositoryCacheManager;
import org.apache.ivy.plugins.repository.Repository;
import org.apache.ivy.plugins.repository.TransferListener;
import org.apache.ivy.plugins.resolver.AbstractResolver;
import org.gradle.api.artifacts.repositories.PasswordCredentials;
import org.gradle.api.internal.artifacts.ivyservice.filestore.ArtifactCaches;
import org.gradle.api.internal.artifacts.ivyservice.filestore.ArtifactFileStore;
import org.gradle.api.internal.artifacts.repositories.ProgressLoggingTransferListener;
import org.gradle.api.internal.artifacts.repositories.cachemanager.DownloadingRepositoryCacheManager;
import org.gradle.api.internal.artifacts.repositories.cachemanager.LocalFileRepositoryCacheManager;
import org.gradle.api.internal.artifacts.repositories.transport.file.FileTransport;
import org.gradle.api.internal.artifacts.repositories.transport.http.HttpTransport;
import org.gradle.api.internal.artifacts.resolutioncache.CachedArtifactResolutionIndex;
import org.gradle.logging.ProgressLoggerFactory;

import java.net.URI;

public class RepositoryTransportFactory {
    private final ArtifactCaches artifactCaches;
    private final TransferListener transferListener;
    private final RepositoryCacheManager downloadingCacheManager;
    private final RepositoryCacheManager localCacheManager;

    public RepositoryTransportFactory(ArtifactCaches artifactCaches, ProgressLoggerFactory progressLoggerFactory, ArtifactFileStore fileStore,
                                      CachedArtifactResolutionIndex<String> artifactUrlCachedResolutionIndex) {
        this.artifactCaches = artifactCaches;
        this.transferListener = new ProgressLoggingTransferListener(progressLoggerFactory, RepositoryTransport.class);
        this.downloadingCacheManager = new DownloadingRepositoryCacheManager("downloading", fileStore, artifactUrlCachedResolutionIndex);
        this.localCacheManager = new LocalFileRepositoryCacheManager("local");
    }

    public RepositoryTransport createHttpTransport(String name, PasswordCredentials credentials) {
        return decorate(new HttpTransport(name, credentials, artifactCaches, downloadingCacheManager));
    }

    public RepositoryTransport createFileTransport(String name) {
        return decorate(new FileTransport(name, localCacheManager));
    }
    
    private RepositoryTransport decorate(RepositoryTransport original) {
        return new ListeningRepositoryTransport(original);
    }

    public void attachListener(Repository repository) {
        if (!repository.hasTransferListener(transferListener)) {
            repository.addTransferListener(transferListener);
        }
    }

    public RepositoryCacheManager getDownloadingCacheManager() {
        return downloadingCacheManager;
    }

    public RepositoryCacheManager getLocalCacheManager() {
        return localCacheManager;
    }

    public TransferListener getTransferListener() {
        return transferListener;
    }

    private class ListeningRepositoryTransport implements RepositoryTransport {
        private final RepositoryTransport delegate;

        private ListeningRepositoryTransport(RepositoryTransport delegate) {
            this.delegate = delegate;
        }

        public void configureCacheManager(AbstractResolver resolver) {
            delegate.configureCacheManager(resolver);
        }

        public ResourceCollection getRepositoryAccessor() {
            ResourceCollection resourceCollection = delegate.getRepositoryAccessor();
            attachListener(resourceCollection);
            return resourceCollection;
        }

        public String convertToPath(URI uri) {
            return delegate.convertToPath(uri);
        }
    }
}
