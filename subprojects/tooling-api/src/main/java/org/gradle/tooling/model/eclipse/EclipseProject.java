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
package org.gradle.tooling.model.eclipse;

import org.gradle.tooling.model.*;

import java.io.File;

/**
 * The complete model of an Eclipse project.
 *
 * <p>Note that the names of Eclipse projects are unique, and can be used as an identifier for the project.
 */
public interface EclipseProject extends HierarchicalEclipseProject, HasGradleProject {
    /**
     * {@inheritDoc}
     */
    EclipseProject getParent();

    /**
     * {@inheritDoc}
     */
    DomainObjectSet<? extends EclipseProject> getChildren();

    /**
     * The gradle project that is associated with this project.
     * Typically, a single eclipse project corresponds to a single gradle project.
     * <p>
     * See {@link HasGradleProject}
     *
     * @return associated gradle project
     */
    GradleProject getGradleProject();

    /**
     * Returns the external dependencies which make up the classpath of this project.
     *
     * @return The dependencies. Returns an empty set if the project has no external dependencies.
     */
    DomainObjectSet<? extends ExternalDependency> getClasspath();

    /**
     * Returns the project directory for this project.
     *
     * @return The project directory.
     */
    File getProjectDirectory();

}
