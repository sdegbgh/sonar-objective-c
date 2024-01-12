/*
 * Sonar Objective-C Plugin
 * Copyright (C) 2012 OCTO Technology
 * dev@sonar.codehaus.org
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */

package org.sonar.plugins.objectivec.tests;

import com.sun.swing.internal.plaf.metal.resources.metal_sv;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.commons.lang.StringUtils;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.Measure;
import org.sonar.api.measures.Metric;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.resources.Resource;
import org.sonar.api.utils.ParsingUtils;
import org.sonar.api.utils.StaxParser;
import org.sonar.api.utils.XmlParserException;
import org.sonar.plugins.surefire.TestCaseDetails;
import org.sonar.plugins.surefire.TestSuiteParser;
import org.sonar.plugins.surefire.TestSuiteReport;

import javax.xml.transform.TransformerException;
import java.io.File;
import java.io.FilenameFilter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by gillesgrousset on 06/01/15.
 */
public class SurefireParser {

    public void collect(Project project, SensorContext context, File reportsDir) {
        File[] xmlFiles = getReports(reportsDir);

        if (xmlFiles.length == 0) {
            insertZeroWhenNoReports(project, context);
        } else {
            parseFiles(context, xmlFiles);
        }
    }

    private File[] getReports(File dir) {
        if (dir == null || !dir.isDirectory() || !dir.exists()) {
            return new File[0];
        }

        File[] list = dir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("TEST") && name.endsWith(".xml");
            }
        });

        return dir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.startsWith("TEST") && name.endsWith(".xml");
            }
        });
    }

    private void insertZeroWhenNoReports(Project pom, SensorContext context) {
        if ( !StringUtils.equalsIgnoreCase("pom", pom.getPackaging())) {
            context.saveMeasure(CoreMetrics.TESTS, 0.0);
        }
    }

    private void parseFiles(SensorContext context, File[] reports) {
        Set<TestSuiteReport> analyzedReports = new HashSet<TestSuiteReport>();
        try {
            for (File report : reports) {
                TestSuiteParser parserHandler = new TestSuiteParser();
                StaxParser parser = new StaxParser(parserHandler, false);
                parser.parse(report);

                for (TestSuiteReport fileReport : parserHandler.getParsedReports()) {
                    if ( !fileReport.isValid() || analyzedReports.contains(fileReport)) {
                        continue;
                    }
                    if (fileReport.getTests() > 0) {
                        double testsCount = fileReport.getTests() - fileReport.getSkipped();
                        saveClassMeasure(context, fileReport, CoreMetrics.SKIPPED_TESTS, fileReport.getSkipped());
                        saveClassMeasure(context, fileReport, CoreMetrics.TESTS, testsCount);
                        saveClassMeasure(context, fileReport, CoreMetrics.TEST_ERRORS, fileReport.getErrors());
                        saveClassMeasure(context, fileReport, CoreMetrics.TEST_FAILURES, fileReport.getFailures());
                        saveClassMeasure(context, fileReport, CoreMetrics.TEST_EXECUTION_TIME, fileReport.getTimeMS());
                        double passedTests = testsCount - fileReport.getErrors() - fileReport.getFailures();
                        if (testsCount > 0) {
                            double percentage = passedTests * 100d / testsCount;
                            saveClassMeasure(context, fileReport, CoreMetrics.TEST_SUCCESS_DENSITY, ParsingUtils.scaleValue(percentage));
                        }
                        saveTestsDetails(context, fileReport);
                        analyzedReports.add(fileReport);
                    }
                }
            }

        } catch (Exception e) {
            throw new XmlParserException("Can not parse surefire reports", e);
        }
    }

    private void saveTestsDetails(SensorContext context, TestSuiteReport fileReport) throws TransformerException {
        StringBuilder testCaseDetails = new StringBuilder(256);
        testCaseDetails.append("<tests-details>");
        List<TestCaseDetails> details = fileReport.getDetails();
        for (TestCaseDetails detail : details) {
            testCaseDetails.append("<testcase status=\"").append(detail.getStatus())
                    .append("\" time=\"").append(detail.getTimeMS())
                    .append("\" name=\"").append(detail.getName()).append("\"");
            boolean isError = detail.getStatus().equals(TestCaseDetails.STATUS_ERROR);
            if (isError || detail.getStatus().equals(TestCaseDetails.STATUS_FAILURE)) {
                testCaseDetails.append(">")
                        .append(isError ? "<error message=\"" : "<failure message=\"")
                        .append(StringEscapeUtils.escapeXml(detail.getErrorMessage())).append("\">")
                        .append("<![CDATA[").append(StringEscapeUtils.escapeXml(detail.getStackTrace())).append("]]>")
                        .append(isError ? "</error>" : "</failure>").append("</testcase>");
            } else {
                testCaseDetails.append("/>");
            }
        }
        testCaseDetails.append("</tests-details>");
        context.saveMeasure(getUnitTestResource(fileReport.getClassKey()), new Measure(CoreMetrics.TEST_DATA, testCaseDetails.toString()));
    }

    private void saveClassMeasure(SensorContext context, TestSuiteReport fileReport, Metric metric, double value) {
        if ( !Double.isNaN(value)) {

            String basename = fileReport.getClassKey().replace('.', '/');

            // .m file
            context.saveMeasure(getUnitTestResource(basename + ".m"), metric, value);

            // Try .m file with + in name
            try {
                context.saveMeasure(getUnitTestResource(basename.replace('_', '+') + ".m"), metric, value);
            } catch (Exception e) {
                // Nothing : File was probably already registered successfully
            }
        }
    }

    public Resource getUnitTestResource(String filename) {

        org.sonar.api.resources.File sonarFile = new org.sonar.api.resources.File(filename);
        sonarFile.setQualifier(Qualifiers.UNIT_TEST_FILE);
        return sonarFile;
    }
}
