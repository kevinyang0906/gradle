/*
 * Copyright 2010 the original author or authors.
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

package org.gradle.api.internal.tasks.testing.junit;

import org.gradle.api.AntBuilder;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.api.internal.tasks.testing.AbstractTestFrameworkTest;
import org.gradle.api.internal.tasks.testing.TestClassProcessor;
import org.gradle.api.internal.tasks.testing.junit.report.TestReporter;
import org.gradle.api.tasks.testing.junit.JUnitOptions;
import org.gradle.messaging.actor.ActorFactory;
import org.gradle.util.IdGenerator;
import org.jmock.Expectations;
import org.junit.Before;

import static junit.framework.Assert.assertNotNull;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;

/**
 * @author Tom Eyckmans
 */
public class JUnitTestFrameworkTest extends AbstractTestFrameworkTest {
    private JUnitTestFramework jUnitTestFramework;
    private TestReporter reporterMock;
    private JUnitOptions jUnitOptionsMock;
    private IdGenerator<?> idGenerator;
    private ServiceRegistry serviceRegistry;

    @Before
    public void setUp() throws Exception {
        super.setUp();

        reporterMock = context.mock(TestReporter.class);
        jUnitOptionsMock = context.mock(JUnitOptions.class);
        idGenerator = context.mock(IdGenerator.class);
        serviceRegistry = context.mock(ServiceRegistry.class);

        context.checking(new Expectations(){{
            allowing(testMock).getTestClassesDir(); will(returnValue(testClassesDir));
            allowing(testMock).getClasspath(); will(returnValue(classpathMock));
            allowing(testMock).getAnt(); will(returnValue(context.mock(AntBuilder.class)));
            allowing(testMock).getTemporaryDir(); will(returnValue(temporaryDir));
        }});
    }

    @org.junit.Test
    public void testInitialize() {
        jUnitTestFramework = new JUnitTestFramework(testMock);
        setMocks();

        assertNotNull(jUnitTestFramework.getOptions());
        assertNotNull(jUnitTestFramework.getReporter());
    }

    @org.junit.Test
    public void testCreatesTestProcessor() {
        jUnitTestFramework = new JUnitTestFramework(testMock);
        setMocks();
        final ActorFactory actorFactory = context.mock(ActorFactory.class);

        context.checking(new Expectations() {{
            one(testMock).getTestResultsDir(); will(returnValue(testResultsDir));
            one(serviceRegistry).get(IdGenerator.class); will(returnValue(idGenerator));
            one(serviceRegistry).get(ActorFactory.class); will(returnValue(actorFactory));
        }});

        TestClassProcessor testClassProcessor = jUnitTestFramework.getProcessorFactory().create(serviceRegistry);
        assertThat(testClassProcessor, instanceOf(JUnitTestClassProcessor.class));
    }

    @org.junit.Test
    public void testReport() {
        jUnitTestFramework = new JUnitTestFramework(testMock);
        setMocks();

        context.checking(new Expectations() {{
            one(testMock).getTestResultsDir(); will(returnValue(testResultsDir));
            one(testMock).getTestReportDir(); will(returnValue(testReportDir));
            one(testMock).isTestReport(); will(returnValue(true));
            one(reporterMock).setTestReportDir(testReportDir);
            one(reporterMock).setTestResultsDir(testResultsDir);
            one(reporterMock).generateReport();
        }});

        jUnitTestFramework.report();
    }

    @org.junit.Test
    public void testReportWithDisabledReport() {
        jUnitTestFramework = new JUnitTestFramework(testMock);
        setMocks();

        context.checking(new Expectations() {{
            one(testMock).isTestReport(); will(returnValue(false));
        }});

        jUnitTestFramework.report();
    }

    private void setMocks() {
        jUnitTestFramework.setReporter(reporterMock);
        jUnitTestFramework.setOptions(jUnitOptionsMock);
    }
}
